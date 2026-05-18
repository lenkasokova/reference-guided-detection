"""
@author Bc. Lenka Sokova

Save an EmbeddingModel checkpoint without training.

Usage:
  python build_model.py
  python build_model.py --config configs/train.yaml
  python build_model.py --backbone mobilenet_v3_large --embedding-dim 128
  python build_model.py --backbone efficientnet_lite0 --output my_initial.pt
"""

import argparse
from pathlib import Path

import torch

from model import EmbeddingModel
from utils import load_config, prepare_output_dir, save_checkpoint, save_config


def parse_args():
    default_config = Path(__file__).resolve().parent / "configs" / "train.yaml"
    parser = argparse.ArgumentParser(description="Build and save an untrained EmbeddingModel.")
    parser.add_argument("--config", type=Path, default=default_config,
                        help="Config YAML (default: configs/train.yaml)")
    parser.add_argument("--backbone", type=str, default=None,
                        help="Override config backbone name")
    parser.add_argument("--embedding-dim", type=int, default=None,
                        help="Override config embedding_dim")
    parser.add_argument("--output", type=Path, default=None,
                        help="Output .pt path (default: <output_dir>/checkpoints/initial.pt)")
    return parser.parse_args()


def main():
    args = parse_args()
    cfg = load_config(args.config)

    if args.backbone:
        cfg["train"]["backbone"] = args.backbone
    if args.embedding_dim is not None:
        cfg["train"]["embedding_dim"] = args.embedding_dim

    backbone = cfg["train"]["backbone"]
    embedding_dim = cfg["train"]["embedding_dim"]

    device = torch.device("cuda:0" if torch.cuda.is_available() else "cpu")
    print(f"Backbone: {backbone}, embedding_dim: {embedding_dim}, device: {device}")

    model = EmbeddingModel(
        backbone_name=backbone,
        embedding_dim=embedding_dim,
        pretrained=True,
    ).to(device)

    total_params = sum(p.numel() for p in model.parameters())
    size_mb = sum(p.numel() * p.element_size() for p in model.parameters()) / 1024 ** 2
    print(f"Parameters: {total_params:,} ({size_mb:.1f} MB fp32)")

    output_dir = prepare_output_dir(cfg["output_dir"])
    save_config(cfg, output_dir)

    out_path = args.output or (output_dir / "checkpoints" / "initial.pt")
    out_path = Path(out_path).resolve()
    out_path.parent.mkdir(parents=True, exist_ok=True)

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

    print(f"Saved checkpoint: {out_path}")


if __name__ == "__main__":
    main()
