"""
@author Bc. Lenka Sokova

Evaluate a pretrained backbone before training and save the checkpoint.

Usage:
    python eval_pretrained.py --config configs/runs_mobilenet_v3_large/pretrained_zero_epoch.yaml
"""

import argparse
import random
from pathlib import Path

import numpy as np
import torch
from torch.utils.data import DataLoader
from torch.utils.tensorboard import SummaryWriter

import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from dataset import ImageFolderWithPaths
from metrics import compute_retrieval_metrics
from model import EmbeddingModel
from utils import (
    load_config,
    prepare_output_dir,
    save_checkpoint,
    save_config,
    save_history_csv,
    save_history_json,
    tb_log_hparams,
)


def parse_args():
    default_config = Path(__file__).resolve().parent / "configs" / "runs_mobilenet_v3_large" / "pretrained_zero_epoch.yaml"
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", type=Path, default=default_config)
    return parser.parse_args()


def set_seed(seed):
    random.seed(seed)
    np.random.seed(seed)
    torch.manual_seed(seed)
    torch.cuda.manual_seed_all(seed)


def seed_worker(worker_id):
    del worker_id
    worker_seed = torch.initial_seed() % 2**32
    random.seed(worker_seed)
    np.random.seed(worker_seed)


@torch.no_grad()
def evaluate_retrieval(model, loader, device, k_values):
    model.eval()
    embs, labels = [], []
    for images, lab, _ in loader:
        images = images.to(device)
        embs.append(model(images).cpu())
        labels.append(lab)
    embs = torch.cat(embs)
    labels = torch.cat(labels)
    return compute_retrieval_metrics(embs.numpy(), labels.numpy(), tuple(k_values))


def build_loader(data_dir, cfg, train=False, seed=42):
    ds = ImageFolderWithPaths(data_dir, image_size=cfg["data"]["image_size"], train=train)
    generator = torch.Generator()
    generator.manual_seed(seed)
    return DataLoader(
        ds,
        batch_size=cfg["train"]["batch_size"],
        shuffle=False,
        num_workers=cfg["data"]["num_workers"],
        pin_memory=True,
        worker_init_fn=seed_worker,
        generator=generator,
    ), ds


def main():
    args = parse_args()
    cfg = load_config(args.config)

    device = torch.device("cuda:0" if torch.cuda.is_available() else "cpu")
    set_seed(cfg["seed"])

    print("Evaluating pretrained model.")
    print("Backbone:", cfg["train"]["backbone"])
    print("Device:", device)

    output_dir = prepare_output_dir(cfg["output_dir"])
    save_config(cfg, output_dir)

    writer = SummaryWriter(str(output_dir / "tb")) if cfg["logging"]["tensorboard"] else None

    val_loader, _ = build_loader(cfg["data"]["val_dir"], cfg, train=False, seed=cfg["seed"] + 1)
    test_loader, _ = build_loader(cfg["data"]["test_dir"], cfg, train=False, seed=cfg["seed"] + 2)

    model = EmbeddingModel(
        backbone_name=cfg["train"]["backbone"],
        embedding_dim=cfg["train"]["embedding_dim"],
        pretrained=cfg["train"]["pretrained"],
    ).to(device)

    val_metrics = evaluate_retrieval(model, val_loader, device, cfg["evaluation"]["k_values"])
    test_metrics = evaluate_retrieval(model, test_loader, device, cfg["evaluation"]["k_values"])

    row = {
        "epoch": 0,
        "train_loss": float("nan"),
        "val_loss": float("nan"),
        "val_top1": val_metrics.get("top1", 0),
        "val_top3": val_metrics.get("top3", 0),
        "val_top5": val_metrics.get("top5", 0),
        "val_mAP": val_metrics.get("mAP", 0),
        "val_MRR": val_metrics.get("MRR", 0),
        "test_top1": test_metrics.get("top1", 0),
        "test_top3": test_metrics.get("top3", 0),
        "test_top5": test_metrics.get("top5", 0),
        "test_mAP": test_metrics.get("mAP", 0),
        "test_MRR": test_metrics.get("MRR", 0),
        "lr": float("nan"),
        "epoch_time_sec": 0,
    }
    for k in cfg["evaluation"]["k_values"]:
        row[f"val_mAP@{k}"] = val_metrics.get(f"mAP@{k}", 0)
        row[f"test_mAP@{k}"] = test_metrics.get(f"mAP@{k}", 0)

    history = [row]

    print(
        f"Val: top1={row['val_top1']:.4f}, mAP={row['val_mAP']:.4f}, MRR={row['val_MRR']:.4f}"
    )
    print(
        f"Test: top1={row['test_top1']:.4f}, mAP={row['test_mAP']:.4f}, MRR={row['test_MRR']:.4f}"
    )

    checkpoint = {
        "epoch": 0,
        "model_state_dict": model.state_dict(),
        "optimizer_state_dict": None,
        "config": cfg,
        "metrics": row,
    }
    save_checkpoint(checkpoint, output_dir / "checkpoints" / "best.pt")
    save_checkpoint(checkpoint, output_dir / "checkpoints" / "last.pt")

    if cfg["logging"]["save_csv"]:
        save_history_csv(history, output_dir)
    if cfg["logging"]["save_json"]:
        save_history_json(history, output_dir)

    tb_log_hparams(writer, cfg, history)

    print(f"Done. Results saved to {output_dir}")


if __name__ == "__main__":
    main()
