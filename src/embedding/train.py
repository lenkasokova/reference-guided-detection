"""
@author Bc. Lenka Sokova

Training script for the image embedding model.
"""

import argparse
import os
import random
import time
from pathlib import Path

os.environ.setdefault("CUBLAS_WORKSPACE_CONFIG", ":4096:8")

import numpy as np
import torch
from torch.optim.lr_scheduler import CosineAnnealingLR
from torch.utils.data import DataLoader
from torch.utils.tensorboard import SummaryWriter
from torch.amp import GradScaler, autocast

from dataset import ClassBalancedBatchSampler, ImageFolderWithPaths
from losses import build_loss
from metrics import compute_retrieval_metrics
from model import EmbeddingModel
from utils import (
    load_config,
    plot_history,
    prepare_output_dir,
    save_checkpoint,
    save_config,
    save_history_csv,
    save_history_json,
    tb_log_epoch,
    tb_log_hparams,
)


def parse_args():
    default_config = Path(__file__).resolve().parent / "configs" / "train.yaml"
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", type=Path, default=default_config)
    return parser.parse_args()


def set_seed(seed):
    random.seed(seed)
    np.random.seed(seed)
    torch.manual_seed(seed)
    torch.cuda.manual_seed_all(seed)


def configure_single_gpu_determinism():
    torch.backends.cudnn.benchmark = False
    torch.backends.cudnn.deterministic = True
    torch.use_deterministic_algorithms(True)


def seed_worker(worker_id):
    del worker_id
    worker_seed = torch.initial_seed() % 2**32
    random.seed(worker_seed)
    np.random.seed(worker_seed)


def build_loader_generator(seed):
    generator = torch.Generator()
    generator.manual_seed(seed)
    return generator


def build_optimizer(model, cfg, criterion=None):
    params = list(model.parameters())
    if criterion is not None:
        proxy_params = list(criterion.parameters())
        if proxy_params:
            params += proxy_params
    return torch.optim.AdamW(
        params,
        lr=cfg["train"]["lr"],
        weight_decay=cfg["train"]["weight_decay"],
    )


def build_scheduler(optimizer, cfg):
    if cfg["train"]["scheduler"] == "cosine":
        return CosineAnnealingLR(optimizer, T_max=cfg["train"]["epochs"])
    return None


def train_one_epoch(model, loader, criterion, optimizer, scaler, device, cfg):
    model.train()
    total_loss = 0
    n_batches = 0

    for images, labels, _ in loader:

        images = images.to(device, non_blocking=True)
        labels = labels.to(device, non_blocking=True)

        optimizer.zero_grad(set_to_none=True)

        if cfg["train"]["mixed_precision"]:
            with autocast(device_type="cuda"):
                embeddings = model(images)
                loss = criterion(embeddings, labels)

            scaler.scale(loss).backward()

            if cfg["train"]["grad_clip"]:
                scaler.unscale_(optimizer)
                torch.nn.utils.clip_grad_norm_(
                    model.parameters(), cfg["train"]["grad_clip"]
                )

            scaler.step(optimizer)
            scaler.update()

        else:
            embeddings = model(images)
            loss = criterion(embeddings, labels)

            loss.backward()

            if cfg["train"]["grad_clip"]:
                torch.nn.utils.clip_grad_norm_(
                    model.parameters(), cfg["train"]["grad_clip"]
                )

            optimizer.step()

        total_loss += loss.item()
        n_batches += 1

    return total_loss / max(1, n_batches)


@torch.no_grad()
def compute_val_loss(model, loader, criterion, device, cfg):
    model.eval()
    total_loss = 0
    n_batches = 0

    for images, labels, _ in loader:
        images = images.to(device, non_blocking=True)
        labels = labels.to(device, non_blocking=True)

        if cfg["train"]["mixed_precision"]:
            with autocast(device_type="cuda"):
                embeddings = model(images)
                loss = criterion(embeddings, labels)
        else:
            embeddings = model(images)
            loss = criterion(embeddings, labels)

        total_loss += loss.item()
        n_batches += 1

    return total_loss / max(1, n_batches)


@torch.no_grad()
def evaluate_retrieval(model, loader, device, k_values):
    model.eval()

    embs = []
    labels = []

    for images, lab, _ in loader:
        images = images.to(device)
        emb = model(images)

        embs.append(emb.cpu())
        labels.append(lab)

    embs = torch.cat(embs)
    labels = torch.cat(labels)

    return compute_retrieval_metrics(embs.numpy(), labels.numpy(), tuple(k_values))


def main():

    args = parse_args()
    cfg = load_config(args.config)

    device = torch.device("cuda:0" if torch.cuda.is_available() else "cpu")
    set_seed(cfg["seed"])
    configure_single_gpu_determinism()

    print(f"Device: {device}")

    output_dir = prepare_output_dir(cfg["output_dir"])
    save_config(cfg, output_dir)

    writer = SummaryWriter(str(output_dir / "tb")) if cfg["logging"]["tensorboard"] else None

    model = EmbeddingModel(
        backbone_name=cfg["train"]["backbone"],
        embedding_dim=cfg["train"]["embedding_dim"],
        pretrained=cfg["train"]["pretrained"],
    ).to(device)

    if cfg["train"]["epochs"] == 0:
        print("Epochs = 0, saving the initial checkpoint without training.")
        out_path = output_dir / "checkpoints" / "initial.pt"
        save_checkpoint(
            {
                "epoch": 0,
                "model_state_dict": model.state_dict(),
                "optimizer_state_dict": {},
                "config": cfg,
                "metrics": {},
            },
            out_path,
        )
        print(f"Saved: {out_path}")
        if writer:
            writer.close()
        return

    train_loader_generator = build_loader_generator(cfg["seed"])
    val_loader_generator = build_loader_generator(cfg["seed"] + 1)

    print("Starting training.")

    train_ds = ImageFolderWithPaths(
        cfg["data"]["train_dir"],
        image_size=cfg["data"]["image_size"],
        train=True,
    )

    val_ds = ImageFolderWithPaths(
        cfg["data"]["val_dir"],
        image_size=cfg["data"]["image_size"],
        train=False,
    )

    train_sampler = ClassBalancedBatchSampler(
        train_ds,
        batch_size=cfg["train"]["batch_size"],
        samples_per_class=cfg["sampling"]["samples_per_class"],
        seed=cfg["seed"],
    )

    train_loader = DataLoader(
        train_ds,
        batch_sampler=train_sampler,
        num_workers=cfg["data"]["num_workers"],
        pin_memory=True,
        worker_init_fn=seed_worker,
        generator=train_loader_generator,
    )

    val_loader = DataLoader(
        val_ds,
        batch_size=cfg["train"]["batch_size"],
        shuffle=False,
        num_workers=cfg["data"]["num_workers"],
        pin_memory=True,
        worker_init_fn=seed_worker,
        generator=val_loader_generator,
    )

    if cfg["train"]["freeze_backbone"]:
        for p in model.backbone.parameters():
            p.requires_grad = False

    criterion = build_loss(cfg, num_classes=len(train_ds.class_to_idx)).to(device)
    optimizer = build_optimizer(model, cfg, criterion=criterion)
    scheduler = build_scheduler(optimizer, cfg)

    scaler = GradScaler("cuda", enabled=cfg["train"]["mixed_precision"])

    history = []
    best_top1 = -1

    es_cfg = cfg.get("early_stopping", {})
    es_enabled = es_cfg.get("enabled", False)
    es_patience = es_cfg.get("patience", 10)
    es_monitor  = es_cfg.get("monitor", "val_top1")
    es_best     = -1.0
    es_counter  = 0

    for epoch in range(cfg["train"]["epochs"]):

        train_sampler.set_epoch(epoch)

        start = time.time()

        train_loss = train_one_epoch(
            model,
            train_loader,
            criterion,
            optimizer,
            scaler,
            device,
            cfg,
        )

        val_loss = compute_val_loss(
            model,
            val_loader,
            criterion,
            device,
            cfg,
        )

        metrics = evaluate_retrieval(
            model,
            val_loader,
            device,
            cfg["evaluation"]["k_values"],
        )

        if scheduler:
            scheduler.step()

        epoch_time = time.time() - start
        lr = optimizer.param_groups[0]["lr"]

        row = {
            "epoch": epoch + 1,
            "train_loss": train_loss,
            "val_loss": val_loss,
            "val_top1": metrics.get("top1", 0),
            "val_top3": metrics.get("top3", 0),
            "val_top5": metrics.get("top5", 0),
            "val_mAP": metrics.get("mAP", 0),
            "val_MRR": metrics.get("MRR", 0),
            "lr": lr,
            "epoch_time_sec": epoch_time,
        }
        for k in cfg["evaluation"]["k_values"]:
            row[f"val_mAP@{k}"] = metrics.get(f"mAP@{k}", 0)

        history.append(row)

        print(
            f"Epoch {epoch + 1}: "
            f"loss={train_loss:.4f}, "
            f"top1={row['val_top1']:.4f}, "
            f"mAP={row['val_mAP']:.4f}, "
            f"MRR={row['val_MRR']:.4f}"
        )

        tb_log_epoch(writer, metrics, train_loss, val_loss, lr, epoch, cfg["evaluation"]["k_values"])

        model_state = model.state_dict()

        save_checkpoint(
            {
                "epoch": epoch + 1,
                "model_state_dict": model_state,
                "optimizer_state_dict": optimizer.state_dict(),
                "config": cfg,
                "metrics": row,
            },
            output_dir / "checkpoints" / "last.pt",
        )

        if row["val_top1"] > best_top1:
            best_top1 = row["val_top1"]

            save_checkpoint(
                {
                    "epoch": epoch + 1,
                    "model_state_dict": model_state,
                    "optimizer_state_dict": optimizer.state_dict(),
                    "config": cfg,
                    "metrics": row,
                },
                output_dir / "checkpoints" / "best.pt",
            )

        if cfg["logging"]["save_csv"]:
            save_history_csv(history, output_dir)

        if cfg["logging"]["save_json"]:
            save_history_json(history, output_dir)

        if cfg["logging"]["save_plots"]:
            plot_history(history, output_dir)

        if es_enabled:
            current = row.get(es_monitor, 0)
            if current > es_best:
                es_best = current
                es_counter = 0
            else:
                es_counter += 1
                print(
                    f"Early stopping: no improvement in {es_monitor} "
                    f"for {es_counter}/{es_patience} epochs."
                )
                if es_counter >= es_patience:
                    print(f"Stopping early after epoch {epoch + 1}.")
                    break

    tb_log_hparams(writer, cfg, history)

    print("Training finished.")


if __name__ == "__main__":
    main()
