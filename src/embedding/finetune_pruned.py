"""
@author Bc. Lenka Sokova

Fine-tune a pruned EmbeddingModel checkpoint.

Usage:
  # Structured pruning + fine-tune
  python finetune_pruned.py \\
      --checkpoint runs/mobilenet_v3_large/.../checkpoints/best.pt \\
      --pruning structured --pruning-amount 0.3 --epochs 15
"""

import argparse
import sys
import time
from pathlib import Path

import torch

sys.path.insert(0, str(Path(__file__).resolve().parent))

from dataset import ClassBalancedBatchSampler, ImageFolderWithPaths
from losses import build_loss
from model import EmbeddingModel
from train import (
    build_loader_generator,
    build_optimizer,
    build_scheduler,
    compute_val_loss,
    configure_single_gpu_determinism,
    evaluate_retrieval,
    seed_worker,
    set_seed,
    train_one_epoch,
)
from utils import (
    plot_history,
    save_checkpoint,
    save_history_csv,
    save_history_json,
    tb_log_epoch,
    tb_log_hparams,
)

# Import prune_model from export_tflite without running its main().
import importlib.util as _ilu
_spec = _ilu.spec_from_file_location(
    "export_tflite",
    Path(__file__).resolve().parent / "export_tflite.py",
)
_mod = _ilu.module_from_spec(_spec)
_spec.loader.exec_module(_mod)
prune_model = _mod.prune_model


def parse_args():
    parser = argparse.ArgumentParser(
        description="Prune an EmbeddingModel checkpoint and fine-tune it."
    )
    parser.add_argument("--checkpoint", type=Path, required=True,
                        help="Base checkpoint (best.pt / last.pt).")
    parser.add_argument("--pruning", choices=["unstructured", "structured"], required=True,
                        help="Pruning method.")
    parser.add_argument("--pruning-amount", type=float, default=0.3,
                        help="Fraction of weights/channels to prune (default: 0.3).")
    parser.add_argument("--epochs", type=int, default=15,
                        help="Number of fine-tuning epochs (default: 15).")
    parser.add_argument("--lr", type=float, default=None,
                        help="Learning rate override. Defaults to 1/10 of the original LR.")
    parser.add_argument("--output-dir", type=Path, default=None,
                        help="Output directory. Defaults to <checkpoint_dir>/finetuned_<pruning>_<amount>/")
    return parser.parse_args()


def main():
    args = parse_args()

    ckpt_path = args.checkpoint.resolve()
    if not ckpt_path.exists():
        print(f"Checkpoint not found: {ckpt_path}")
        sys.exit(1)

    ckpt = torch.load(ckpt_path, map_location="cpu", weights_only=False)
    cfg = ckpt["config"]

    # Override a few settings for fine-tuning.
    cfg["train"]["epochs"] = args.epochs
    cfg["train"]["lr"] = args.lr or cfg["train"]["lr"] / 10
    cfg["train"]["pretrained"] = False
    cfg["train"]["freeze_backbone"] = False

    prune_tag = f"{args.pruning}_{int(args.pruning_amount * 100)}pct"
    output_dir = args.output_dir or (ckpt_path.parent / f"finetuned_{prune_tag}")
    output_dir.mkdir(parents=True, exist_ok=True)
    (output_dir / "checkpoints").mkdir(exist_ok=True)
    (output_dir / "plots").mkdir(exist_ok=True)

    device = torch.device("cuda:0" if torch.cuda.is_available() else "cpu")
    set_seed(cfg["seed"])
    configure_single_gpu_determinism()

    image_size = cfg["data"]["image_size"]

    print(f"Checkpoint: {ckpt_path}")
    print(f"Pruning: {args.pruning}, amount={args.pruning_amount:.0%}")
    print(f"Epochs: {args.epochs}")
    print(f"LR: {cfg['train']['lr']}")
    print(f"Output: {output_dir}")

    model = EmbeddingModel(
        backbone_name=cfg["train"]["backbone"],
        embedding_dim=cfg["train"]["embedding_dim"],
        pretrained=False,
    )
    model.load_state_dict(ckpt["model_state_dict"])

    total_before = sum(p.numel() for p in model.parameters())
    print(f"Parameters before pruning: {total_before:,}")

    model = prune_model(model, args.pruning, args.pruning_amount, image_size)

    if args.pruning == "structured":
        total_after = sum(p.numel() for p in model.parameters())
        removed = total_before - total_after
        print(f"Parameters after pruning: {total_after:,} "
              f"({removed:,} removed, {removed/total_before:.1%})")
    else:
        zeros = sum((p == 0).sum().item() for p in model.parameters())
        total = sum(p.numel() for p in model.parameters())
        print(f"Sparsity: {zeros:,}/{total:,} zero weights ({zeros/total:.1%})")

    model.to(device)

    train_ds = ImageFolderWithPaths(cfg["data"]["train_dir"], image_size=image_size, train=True)
    val_ds   = ImageFolderWithPaths(cfg["data"]["val_dir"],   image_size=image_size, train=False)

    train_sampler = ClassBalancedBatchSampler(
        train_ds,
        batch_size=cfg["train"]["batch_size"],
        samples_per_class=cfg["sampling"]["samples_per_class"],
        seed=cfg["seed"],
    )

    train_loader = torch.utils.data.DataLoader(
        train_ds,
        batch_sampler=train_sampler,
        num_workers=cfg["data"]["num_workers"],
        pin_memory=True,
        worker_init_fn=seed_worker,
        generator=build_loader_generator(cfg["seed"]),
    )

    val_loader = torch.utils.data.DataLoader(
        val_ds,
        batch_size=cfg["train"]["batch_size"],
        shuffle=False,
        num_workers=cfg["data"]["num_workers"],
        pin_memory=True,
        worker_init_fn=seed_worker,
        generator=build_loader_generator(cfg["seed"] + 1),
    )

    criterion = build_loss(cfg, num_classes=len(train_ds.class_to_idx)).to(device)
    optimizer = build_optimizer(model, cfg, criterion=criterion)
    scheduler = build_scheduler(optimizer, cfg)
    scaler    = torch.amp.GradScaler("cuda", enabled=cfg["train"]["mixed_precision"])

    from torch.utils.tensorboard import SummaryWriter
    writer = SummaryWriter(str(output_dir / "tb")) if cfg["logging"]["tensorboard"] else None

    history = []
    best_top1 = -1.0

    es_cfg     = cfg.get("early_stopping", {})
    es_enabled = es_cfg.get("enabled", False)
    es_patience = es_cfg.get("patience", 10)
    es_monitor  = es_cfg.get("monitor", "val_top1")
    es_best     = -1.0
    es_counter  = 0

    for epoch in range(cfg["train"]["epochs"]):
        train_sampler.set_epoch(epoch)
        start = time.time()

        train_loss = train_one_epoch(model, train_loader, criterion, optimizer, scaler, device, cfg)
        val_loss   = compute_val_loss(model, val_loader, criterion, device, cfg)
        metrics    = evaluate_retrieval(model, val_loader, device, cfg["evaluation"]["k_values"])

        if scheduler:
            scheduler.step()

        row = {
            "epoch":           epoch + 1,
            "train_loss":      train_loss,
            "val_loss":        val_loss,
            "val_top1":        metrics.get("top1", 0),
            "val_top3":        metrics.get("top3", 0),
            "val_top5":        metrics.get("top5", 0),
            "val_mAP":         metrics.get("mAP", 0),
            "val_MRR":         metrics.get("MRR", 0),
            "lr":              optimizer.param_groups[0]["lr"],
            "epoch_time_sec":  time.time() - start,
        }
        for k in cfg["evaluation"]["k_values"]:
            row[f"val_mAP@{k}"] = metrics.get(f"mAP@{k}", 0)

        history.append(row)
        print(
            f"Epoch {epoch + 1}/{cfg['train']['epochs']}: "
            f"loss={train_loss:.4f}, "
            f"top1={row['val_top1']:.4f}, "
            f"mAP={row['val_mAP']:.4f}, "
            f"MRR={row['val_MRR']:.4f}"
        )

        ckpt_payload = {
            "epoch":               epoch + 1,
            "model_state_dict":    model.state_dict(),
            "optimizer_state_dict": optimizer.state_dict(),
            "config":              cfg,
            "metrics":             row,
            "pruning":             {"method": args.pruning, "amount": args.pruning_amount},
        }
        save_checkpoint(ckpt_payload, output_dir / "checkpoints" / "last.pt")

        if row["val_top1"] > best_top1:
            best_top1 = row["val_top1"]
            save_checkpoint(ckpt_payload, output_dir / "checkpoints" / "best.pt")

        tb_log_epoch(writer, metrics, train_loss, val_loss, row["lr"], epoch, cfg["evaluation"]["k_values"])

        if cfg["logging"]["save_csv"]:
            save_history_csv(history, output_dir)
        if cfg["logging"]["save_json"]:
            save_history_json(history, output_dir)
        if cfg["logging"]["save_plots"]:
            plot_history(history, output_dir)

        if es_enabled:
            current = row.get(es_monitor, 0)
            if current > es_best:
                es_best    = current
                es_counter = 0
            else:
                es_counter += 1
                print(f"Early stopping: no improvement in {es_monitor} for {es_counter}/{es_patience} epochs.")
                if es_counter >= es_patience:
                    print(f"Stopping early after epoch {epoch + 1}.")
                    break

    tb_log_hparams(writer, cfg, history, extra_hparams={
        "pruning/method": args.pruning,
        "pruning/amount": args.pruning_amount,
    })

    total_final = sum(p.numel() for p in model.parameters())
    size_final_mb = sum(p.numel() * p.element_size() for p in model.parameters()) / 1024 ** 2

    print("\nParameter summary:")
    print(f"  Before pruning: {total_before:,} ({total_before * 4 / 1024**2:.1f} MB fp32)")
    if args.pruning == "structured":
        print(f"  After pruning: {total_final:,} ({size_final_mb:.1f} MB fp32), "
              f"{total_before - total_final:,} removed ({(total_before - total_final)/total_before:.1%})")
    else:
        zeros_final = sum((p == 0).sum().item() for p in model.parameters())
        print(f"  After pruning: {total_final:,} (unchanged, unstructured)")
        print(f"  Final sparsity: {zeros_final:,}/{total_final:,} = {zeros_final/total_final:.1%} "
              f"(was {args.pruning_amount:.0%} before fine-tuning)")

    print(f"\nDone. Best top-1: {best_top1:.4f}")
    print(f"Best checkpoint: {output_dir / 'checkpoints' / 'best.pt'}")
    print(f"Export with: python export_tflite.py --checkpoint {output_dir / 'checkpoints' / 'best.pt'} --quantization float16")


if __name__ == "__main__":
    main()
