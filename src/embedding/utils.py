"""
@author Bc. Lenka Sokova
"""

import json
from pathlib import Path

import matplotlib.pyplot as plt
import pandas as pd
import torch
import yaml


def load_config(path):
    with open(path, "r") as f:
        return yaml.safe_load(f)


def prepare_output_dir(output_dir):
    output_dir = Path(output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    (output_dir / "checkpoints").mkdir(exist_ok=True)
    (output_dir / "plots").mkdir(exist_ok=True)
    return output_dir


def save_config(cfg, output_dir):
    with open(output_dir / "config_used.yaml", "w") as f:
        yaml.safe_dump(cfg, f)


def save_checkpoint(state, path):
    torch.save(state, path)


def save_history_csv(history, output_dir):
    df = pd.DataFrame(history)
    df.to_csv(output_dir / "history.csv", index=False)


def save_history_json(history, output_dir):
    with open(output_dir / "history.json", "w") as f:
        json.dump(history, f, indent=2)


def tb_log_epoch(writer, metrics: dict, train_loss: float, val_loss: float,
                 lr: float, epoch: int, k_values: list):
    """Log one epoch of scalars to TensorBoard."""
    if writer is None:
        return
    writer.add_scalar("train/loss", train_loss, epoch)
    writer.add_scalar("val/loss", val_loss, epoch)
    writer.add_scalar("train/lr", lr, epoch)
    for k in k_values:
        writer.add_scalar(f"val/top{k}", metrics.get(f"top{k}", 0), epoch)
    writer.add_scalar("val/mAP", metrics.get("mAP", 0), epoch)
    writer.add_scalar("val/MRR", metrics.get("MRR", 0), epoch)
    for k in k_values:
        writer.add_scalar(f"val/mAP@{k}", metrics.get(f"mAP@{k}", 0), epoch)


def tb_log_hparams(writer, cfg: dict, history: list, extra_hparams: dict | None = None):
    """Log hyperparameters and final metrics to TensorBoard."""
    if writer is None or not history:
        return
    final = history[-1]
    hparams = {
        "backbone":         cfg["train"]["backbone"],
        "embedding_dim":    cfg["train"]["embedding_dim"],
        "epochs":           cfg["train"]["epochs"],
        "batch_size":       cfg["train"]["batch_size"],
        "lr":               cfg["train"]["lr"],
        "weight_decay":     cfg["train"]["weight_decay"],
        "scheduler":        cfg["train"]["scheduler"],
        "mixed_precision":  cfg["train"]["mixed_precision"],
        "grad_clip":        float(cfg["train"]["grad_clip"] or 0),
        "freeze_backbone":  cfg["train"]["freeze_backbone"],
        "loss":             cfg["loss"]["name"],
        "loss/temperature": float(cfg["loss"].get("temperature", 0)),
        "loss/margin":      float(cfg["loss"].get("margin", 0)),
        "loss/miner":       cfg["loss"].get("miner", ""),
        "image_size":       cfg["data"]["image_size"],
    }
    if extra_hparams:
        hparams.update(extra_hparams)
    final_metrics = {
        "hparam/top1": final.get("val_top1", 0),
        "hparam/top3": final.get("val_top3", 0),
        "hparam/top5": final.get("val_top5", 0),
        "hparam/mAP":  final.get("val_mAP", 0),
        "hparam/MRR":  final.get("val_MRR", 0),
        "hparam/loss": final.get("train_loss", 0),
    }
    writer.add_hparams(hparams, final_metrics)
    writer.close()


def plot_history(history, output_dir):
    df = pd.DataFrame(history)
    (output_dir / "plots").mkdir(parents=True, exist_ok=True)

    for y in ["train_loss", "val_top1", "val_top3", "val_top5", "lr"]:
        if y not in df.columns:
            continue
        plt.figure(figsize=(8, 5))
        plt.plot(df["epoch"], df[y])
        plt.xlabel("Epoch", fontsize=14)
        plt.ylabel(y, fontsize=14)
        plt.grid(True)
        plt.tight_layout()
        plt.savefig(output_dir / "plots" / f"{y}.png")
        plt.close()
