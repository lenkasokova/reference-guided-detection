"""
@author Bc. Lenka Sokova

Evaluate CLIP ViT-B/16 as an embedding model.

It uses the same metrics as evaluate_roc.py.

Usage:
    python evaluate_clip.py --dataset-dir ./plantnet_prepared/val
    python evaluate_clip.py --dataset-dir ./val --image-size 224 --batch-size 64
"""

import argparse
import json
import random
import sys
from pathlib import Path

import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
sys.path.insert(0, str(Path(__file__).resolve().parent))
from evaluate_roc import (
    build_loader,
    build_pair_scores,
    compute_roc_metrics,
    compute_pr_metrics,
    compute_threshold_metrics,
    compute_tar_at_far,
    find_extreme_pairs,
    summarize_model,
    summarize_embeddings,
    save_curve_plot,
    save_pairs_txt,
    save_summary_txt,
)
from metrics import compute_embeddings, compute_retrieval_metrics


MODEL_ID = "openai/clip-vit-base-patch16"
DEFAULT_IMAGE_SIZE = 224

# ImageNet normalization from ImageFolderWithPaths
_IN_MEAN   = (0.485,      0.456,      0.406)
_IN_STD    = (0.229,      0.224,      0.225)
# CLIP normalization used by the vision encoder
_CLIP_MEAN = (0.48145466, 0.4578275,  0.40821073)
_CLIP_STD  = (0.26862954, 0.26130258, 0.27577711)


class CLIPEmbedder(nn.Module):
    """Small wrapper around the CLIP image encoder."""

    def __init__(self):
        super().__init__()
        try:
            from transformers import CLIPModel
        except ImportError:
            print("transformers is not installed. Run: pip install transformers")
            sys.exit(1)

        print(f"Loading {MODEL_ID}...")
        self.clip = CLIPModel.from_pretrained(MODEL_ID)

        self.register_buffer("_in_mean",   torch.tensor(_IN_MEAN).view(1, 3, 1, 1))
        self.register_buffer("_in_std",    torch.tensor(_IN_STD).view(1, 3, 1, 1))
        self.register_buffer("_clip_mean", torch.tensor(_CLIP_MEAN).view(1, 3, 1, 1))
        self.register_buffer("_clip_std",  torch.tensor(_CLIP_STD).view(1, 3, 1, 1))

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        x = x * self._in_std + self._in_mean
        x = (x - self._clip_mean) / self._clip_std
        feats = self.clip.get_image_features(pixel_values=x)
        return F.normalize(feats, dim=1)


def parse_args():
    parser = argparse.ArgumentParser(
        description=(
            "Evaluate CLIP ViT-B/16 as an embedding model."
        )
    )
    parser.add_argument(
        "--dataset-dir", type=Path, required=True,
        help="Dataset root with one sub-directory per class.",
    )
    parser.add_argument(
        "--image-size", type=int, default=DEFAULT_IMAGE_SIZE,
        help=f"Input spatial resolution (default: {DEFAULT_IMAGE_SIZE}).",
    )
    parser.add_argument("--batch-size",  type=int, default=64,
                        help="Inference batch size (default: 64).")
    parser.add_argument("--num-workers", type=int, default=0,
                        help="DataLoader workers (default: 0).")
    parser.add_argument(
        "--device", type=str,
        default="cuda" if torch.cuda.is_available() else "cpu",
    )
    parser.add_argument(
        "--max-pairs", type=int, default=0,
        help="Cap on evaluated pairs (0 = all pairs).",
    )
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument(
        "--far-levels", type=float, nargs="+", default=[1e-2, 1e-3, 1e-4],
    )
    parser.add_argument(
        "--k-values", type=int, nargs="+", default=[1, 3, 5],
    )
    parser.add_argument(
        "--output-dir", type=Path, default=None,
        help="Output directory (default: ./evaluate_clip/<dataset_name>).",
    )
    parser.add_argument("--save-curves", action="store_true")
    parser.add_argument("--top-n", type=int, default=20)
    return parser.parse_args()


def main():
    args = parse_args()

    random.seed(args.seed)
    np.random.seed(args.seed)
    torch.manual_seed(args.seed)
    torch.cuda.manual_seed_all(args.seed)

    device = torch.device(args.device)

    model = CLIPEmbedder().to(device)
    model.eval()

    model_stats = summarize_model(model)
    print(
        f"Parameters: {model_stats['total_parameters']:,} "
        f"({model_stats['model_size_mb']} MB fp32)"
    )

    dataset, loader = build_loader(
        dataset_dir=args.dataset_dir,
        image_size=args.image_size,
        batch_size=args.batch_size,
        num_workers=args.num_workers,
    )
    print(
        f"Dataset: {len(dataset)} images, "
        f"{len(dataset.class_to_idx)} classes, "
        f"image_size={args.image_size}"
    )

    with torch.no_grad():
        embs, labels, paths = compute_embeddings(model, loader, device)

    k_values      = tuple(sorted(set(args.k_values)))
    retrieval     = compute_retrieval_metrics(embs, labels, k_values)
    extreme_pairs = find_extreme_pairs(embs, labels, paths, top_n=args.top_n)
    scores, targets = build_pair_scores(embs, labels, args.max_pairs, args.seed)
    roc           = compute_roc_metrics(scores, targets)
    pr            = compute_pr_metrics(scores, targets)
    best_f1       = compute_threshold_metrics(scores, targets, roc["thresholds"][1:])
    tar_at_far    = compute_tar_at_far(roc, args.far_levels)
    emb_stats     = summarize_embeddings(embs)

    result = {
        "checkpoint": MODEL_ID,
        "dataset_dir": str(args.dataset_dir.resolve()),
        "num_images": len(dataset),
        "num_classes": len(dataset.class_to_idx),
        "model_stats": model_stats,
        "embedding_stats": emb_stats,
        "retrieval": retrieval,
        "verification": {
            "num_pairs": roc["num_pairs"],
            "num_positive_pairs": roc["num_positive_pairs"],
            "num_negative_pairs": roc["num_negative_pairs"],
            "auroc": roc["auroc"],
            "eer": roc["eer"],
            "eer_threshold": roc["eer_threshold"],
            "auprc": pr["auprc"],
            "best_f1_operating_point": best_f1,
            "tar_at_far": tar_at_far,
        },
        "paths_preview": paths[:5],
        "pair_analysis": extreme_pairs,
    }

    if args.save_curves:
        result["verification"]["roc_curve"] = {
            "fpr":        roc["fpr"].tolist(),
            "tpr":        roc["tpr"].tolist(),
            "thresholds": roc["thresholds"].tolist(),
        }
        result["verification"]["pr_curve"] = {
            "precision":  pr["precision"].tolist(),
            "recall":     pr["recall"].tolist(),
            "thresholds": pr["thresholds"].tolist(),
        }

    print(f"Images: {result['num_images']}")
    print(f"Classes: {result['num_classes']}")
    print(f"Embedding dim: {emb_stats['embedding_dim']}")
    for k in k_values:
        print(f"Top-{k}: {retrieval.get(f'top{k}', 0.0):.6f}")
    for k in k_values:
        print(f"mAP@{k}: {retrieval.get(f'mAP@{k}', 0.0):.6f}")
    print(f"mAP: {retrieval['mAP']:.6f}")
    print(f"MRR: {retrieval['MRR']:.6f}")
    print(f"AUROC: {roc['auroc']:.6f}")
    print(f"AUPRC: {pr['auprc']:.6f}")
    print(f"EER: {roc['eer']:.6f}")
    print(f"Best-F1: {best_f1['f1']:.6f}")

    dataset_name = args.dataset_dir.resolve().name
    output_dir   = args.output_dir or Path("evaluate_clip") / dataset_name
    output_dir.mkdir(parents=True, exist_ok=True)

    base        = f"clip_vit_b16_{dataset_name}"
    output_json = output_dir / f"{base}_evaluation.json"
    output_roc  = output_dir / f"{base}_roc.png"
    output_pr   = output_dir / f"{base}_pr.png"
    pairs_txt   = output_dir / f"{base}_pairs.txt"
    summary_txt = output_dir / f"{base}_summary.txt"

    with output_json.open("w", encoding="utf-8") as f:
        json.dump(result, f, indent=2)
    print(f"Saved JSON: {output_json}")

    save_curve_plot(
        roc["fpr"], roc["tpr"],
        xlabel="False Positive Rate", ylabel="True Positive Rate",
        title="ROC Curve", output_path=output_roc,
    )
    print(f"Saved ROC PNG: {output_roc}")

    save_curve_plot(
        pr["recall"], pr["precision"],
        xlabel="Recall", ylabel="Precision",
        title="Precision-Recall Curve", output_path=output_pr,
    )
    print(f"Saved PR PNG: {output_pr}")

    save_pairs_txt(extreme_pairs, pairs_txt)
    print(f"Saved pairs: {pairs_txt}")

    save_summary_txt(result, list(k_values), summary_txt)
    print(f"Saved summary: {summary_txt}")


if __name__ == "__main__":
    main()
