"""
@author Bc. Lenka Sokova

Evaluate a trained embedding checkpoint.

The dataset must follow the same layout as training:
  dataset_dir/
    class_a/
      img1.jpg
      img2.jpg
    class_b/
      ...

It saves a JSON report and ROC/PR curve PNGs.
"""

import argparse
import json
import random
import sys
from pathlib import Path

import matplotlib.pyplot as plt
import numpy as np
import torch
from torch.utils.data import DataLoader

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from dataset import ImageFolderWithPaths
from metrics import compute_embeddings, compute_retrieval_metrics
from model import EmbeddingModel


def parse_args():
    parser = argparse.ArgumentParser(
        description="Compute retrieval and verification metrics for an embedding checkpoint."
    )
    parser.add_argument(
        "--checkpoint",
        type=Path,
        required=True,
        help="Path to training checkpoint (best.pt or last.pt).",
    )
    parser.add_argument(
        "--dataset-dir",
        type=Path,
        required=True,
        help="Dataset root with one subdirectory per class.",
    )
    parser.add_argument(
        "--batch-size",
        type=int,
        default=128,
        help="Inference batch size.",
    )
    parser.add_argument(
        "--num-workers",
        type=int,
        default=4,
        help="DataLoader worker count.",
    )
    parser.add_argument(
        "--device",
        type=str,
        default="cuda" if torch.cuda.is_available() else "cpu",
        help="Inference device.",
    )
    parser.add_argument(
        "--max-pairs",
        type=int,
        default=0,
        help="Optional cap on evaluated pairs. 0 means use all pairs.",
    )
    parser.add_argument(
        "--seed",
        type=int,
        default=42,
        help="Random seed used only for optional pair subsampling.",
    )
    parser.add_argument(
        "--far-levels",
        type=float,
        nargs="+",
        default=[1e-2, 1e-3, 1e-4],
        help="FAR levels for TAR@FAR reporting.",
    )
    parser.add_argument(
        "--k-values",
        type=int,
        nargs="+",
        default=[1, 3, 5],
        help="Top-k and mAP@k values for retrieval metrics.",
    )
    parser.add_argument(
        "--output-json",
        type=Path,
        default=None,
        help="Optional override path for the metrics JSON.",
    )
    parser.add_argument(
        "--output-roc-png",
        type=Path,
        default=None,
        help="Optional override path for the ROC curve PNG.",
    )
    parser.add_argument(
        "--output-pr-png",
        type=Path,
        default=None,
        help="Optional override path for the precision-recall curve PNG.",
    )
    parser.add_argument(
        "--save-curves",
        action="store_true",
        help="Store full ROC and PR curve arrays in the JSON output.",
    )
    parser.add_argument(
        "--top-n",
        type=int,
        default=20,
        help="Number of hardest/easiest pairs to log per category (default: 20).",
    )
    parser.add_argument(
        "--score-alphas",
        type=float,
        nargs="+",
        default=[0.0, 0.5, 1.0],
        help=(
            "Alpha values for Score(c) classification metric "
            "(default: 0.0 0.5 1.0). "
            "Score(c) = max(s) + alpha*(mean_k(s) - max(s)), "
            "evaluated for every combination of --k-values and --score-alphas."
        ),
    )
    parser.add_argument(
        "--score-threshold",
        type=float,
        default=None,
        help=(
            "Optional similarity threshold. Individual similarity scores below "
            "this value are excluded before computing Score(c). Classes with no "
            "qualifying similarities are skipped; queries where no class qualifies "
            "are rejected and excluded from accuracy and coverage denominators. "
            "Omit to use all similarities."
        ),
    )
    return parser.parse_args()


def find_extreme_pairs(
    embs: np.ndarray,
    labels: np.ndarray,
    paths: list[str],
    top_n: int = 20,
) -> dict:
    """Find hard and easy positive and negative pairs."""
    sim = (embs @ embs.T).astype(np.float64)
    n = sim.shape[0]

    tri_i, tri_j = np.triu_indices(n, k=1)
    scores = sim[tri_i, tri_j]
    same = labels[tri_i] == labels[tri_j]

    def _top(mask, ascending: bool, k: int) -> list[dict]:
        idx = np.where(mask)[0]
        if len(idx) == 0:
            return []
        s = scores[idx]
        order = np.argpartition(s, min(k, len(s)) - 1) if ascending else \
                np.argpartition(-s, min(k, len(s)) - 1)
        order = order[:k]
        order = order[np.argsort(s[order] if ascending else -s[order])]
        rows = []
        for o in order:
            gi, gj = int(tri_i[idx[o]]), int(tri_j[idx[o]])
            rows.append({
                "similarity": float(scores[idx[o]]),
                "path_a": paths[gi],
                "path_b": paths[gj],
                "class_a": str(labels[gi]),
                "class_b": str(labels[gj]),
            })
        return rows

    return {
        "hardest_positives": _top(same, ascending=True, k=top_n),
        "easiest_positives": _top(same, ascending=False, k=top_n),
        "hardest_negatives": _top(~same, ascending=False, k=top_n),
        "easiest_negatives": _top(~same, ascending=True, k=top_n),
    }


def save_pairs_txt(pairs: dict, output_path: Path) -> None:
    """Save extreme pairs as a human-readable text file."""
    output_path.parent.mkdir(parents=True, exist_ok=True)
    sections = [
        ("HARDEST POSITIVES (same class, lowest similarity)", pairs["hardest_positives"]),
        ("EASIEST POSITIVES (same class, highest similarity)", pairs["easiest_positives"]),
        ("HARDEST NEGATIVES (different class, highest similarity)", pairs["hardest_negatives"]),
        ("EASIEST NEGATIVES (different class, lowest similarity)", pairs["easiest_negatives"]),
    ]
    with output_path.open("w", encoding="utf-8") as f:
        for title, rows in sections:
            f.write(f"{'=' * 70}\n{title}\n{'=' * 70}\n")
            for rank, row in enumerate(rows, 1):
                f.write(
                    f"  #{rank:>3}  sim={row['similarity']:+.6f}"
                    f"  [{row['class_a']}] {row['path_a']}\n"
                    f"         [{row['class_b']}] {row['path_b']}\n"
                )
            f.write("\n")


def save_summary_txt(result: dict, k_values: list[int], output_path: Path) -> None:
    """Save a text summary of the evaluation."""
    output_path.parent.mkdir(parents=True, exist_ok=True)
    retrieval = result["retrieval"]
    ver = result["verification"]
    best_f1 = ver["best_f1_operating_point"]
    emb = result["embedding_stats"]
    mdl = result["model_stats"]

    with output_path.open("w", encoding="utf-8") as f:
        SEP = "=" * 70
        f.write(f"{SEP}\nEVALUATION SUMMARY\n{SEP}\n")
        f.write(f"Checkpoint : {result['checkpoint']}\n")
        f.write(f"Dataset    : {result['dataset_dir']}\n")
        f.write(f"Images     : {result['num_images']}\n")
        f.write(f"Classes    : {result['num_classes']}\n\n")

        f.write(f"{SEP}\nMODEL STATS\n{SEP}\n")
        total = mdl["total_parameters"]
        trainable = mdl["trainable_parameters"]
        f.write(f"  Total parameters     : {total:,}\n" if total is not None else "  Total parameters     : N/A\n")
        f.write(f"  Trainable parameters : {trainable:,}\n" if trainable is not None else "  Trainable parameters : N/A (post-quantization)\n")
        f.write(f"  Model size           : {mdl['model_size_mb']} MB\n\n")

        f.write(f"{SEP}\nEMBEDDING STATS\n{SEP}\n")
        f.write(f"  Dim          : {emb['embedding_dim']}\n")
        f.write(f"  Mean L2 norm : {emb['mean_l2_norm']:.6f}\n")
        f.write(f"  Std  L2 norm : {emb['std_l2_norm']:.6f}\n")
        f.write(f"  Min  L2 norm : {emb['min_l2_norm']:.6f}\n")
        f.write(f"  Max  L2 norm : {emb['max_l2_norm']:.6f}\n\n")

        f.write(f"{SEP}\nRETRIEVAL METRICS\n{SEP}\n")
        for k in sorted(set(k_values)):
            f.write(f"  Top-{k:<3}      : {retrieval.get(f'top{k}', 0.0):.6f}\n")
        for k in sorted(set(k_values)):
            f.write(f"  Recall@{k:<3}   : {retrieval.get(f'recall@{k}', 0.0):.6f}\n")
        for k in sorted(set(k_values)):
            f.write(f"  mAP@{k:<3}      : {retrieval.get(f'mAP@{k}', 0.0):.6f}\n")
        f.write(f"  mAP          : {retrieval['mAP']:.6f}\n")
        f.write(f"  MRR          : {retrieval['MRR']:.6f}\n\n")

        f.write(f"{SEP}\nVERIFICATION METRICS\n{SEP}\n")
        f.write(f"  Pairs (total)    : {ver['num_pairs']}\n")
        f.write(f"  Pairs (positive) : {ver['num_positive_pairs']}\n")
        f.write(f"  Pairs (negative) : {ver['num_negative_pairs']}\n")
        f.write(f"  AUROC            : {ver['auroc']:.6f}\n")
        f.write(f"  AUPRC            : {ver['auprc']:.6f}\n")
        f.write(f"  EER              : {ver['eer']:.6f}\n")
        f.write(f"  EER threshold    : {ver['eer_threshold']:.6f}\n\n")

        f.write(f"{SEP}\nBEST-F1 OPERATING POINT\n{SEP}\n")
        for key in ("threshold", "f1", "precision", "recall", "accuracy",
                    "balanced_accuracy", "specificity", "tp", "fp", "tn", "fn"):
            f.write(f"  {key:<20}: {best_f1[key]}\n")
        f.write("\n")

        f.write(f"{SEP}\nTAR @ FAR\n{SEP}\n")
        for label, vals in ver["tar_at_far"].items():
            f.write(f"  {label:<20}: TAR={vals['tar']:.6f}  threshold={vals['threshold']:.6f}\n")
        f.write("\n")

        score_c = result.get("score_c")
        if score_c:
            f.write(f"{SEP}\nSCORE(c) CLASSIFICATION\n"
                    f"  Score(c) = max(s) + alpha*(mean_k(s) - max(s))\n{SEP}\n")
            for key, vals in score_c.items():
                cov_str = f"  coverage={vals['coverage']:.6f}" if vals["coverage"] < 1.0 else ""
                f.write(f"  {key:<25}: accuracy={vals['accuracy']:.6f}{cov_str}\n")
            f.write("\n")


def default_output_paths(checkpoint_path: Path, dataset_dir: Path):
    dataset_name = dataset_dir.resolve().name
    fmt = checkpoint_path.suffix.lstrip(".")  # "pt" or "tflite"
    base_name = f"{checkpoint_path.stem}_{fmt}_{dataset_name}_evaluation"
    evaluate_dir = checkpoint_path.parent / "evaluate" / base_name
    return {
        "json": evaluate_dir / "evaluation.json",
        "roc_png": evaluate_dir / "roc.png",
        "pr_png": evaluate_dir / "pr.png",
        "pairs_txt": evaluate_dir / "pairs.txt",
        "summary_txt": evaluate_dir / "summary.txt",
    }


def load_model(checkpoint_path: Path, device: torch.device):
    ckpt = torch.load(checkpoint_path, map_location="cpu", weights_only=False)
    cfg = ckpt["config"]

    model = EmbeddingModel(
        backbone_name=cfg["train"]["backbone"],
        embedding_dim=cfg["train"]["embedding_dim"],
        pretrained=False,
    )

    # If the checkpoint was saved after structured pruning, load it the same way here.
    pruning_meta = ckpt.get("pruning")
    if pruning_meta and pruning_meta.get("method") == "structured":
        from export_tflite import prune_model
        model = prune_model(model, "structured", pruning_meta["amount"], cfg["data"]["image_size"])

    model.load_state_dict(ckpt["model_state_dict"])
    model.to(device)
    model.eval()
    return model, cfg


def load_tflite_interpreter(tflite_path: Path):
    try:
        import tensorflow as tf
        interpreter = tf.lite.Interpreter(model_path=str(tflite_path))
    except ImportError:
        try:
            import tflite_runtime.interpreter as tflite_runtime
            interpreter = tflite_runtime.Interpreter(model_path=str(tflite_path))
        except ImportError:
            print("tensorflow or tflite_runtime is required for TFLite evaluation.")
            sys.exit(1)
    interpreter.allocate_tensors()
    return interpreter


def tflite_image_size(interpreter) -> int:
    """Read the input image size from the first tensor."""
    shape = interpreter.get_input_details()[0]["shape"]  # [1, H, W, 3]
    return int(shape[1])


def tflite_embedding_dim(interpreter) -> int:
    shape = interpreter.get_output_details()[0]["shape"]  # [1, D]
    return int(shape[1])


def compute_embeddings_tflite(
    interpreter, loader
) -> tuple[np.ndarray, np.ndarray, list[str]]:
    """Run TFLite inference one image at a time."""
    input_idx = interpreter.get_input_details()[0]["index"]
    output_idx = interpreter.get_output_details()[0]["index"]
    input_dtype = interpreter.get_input_details()[0]["dtype"]

    all_embs: list[np.ndarray] = []
    all_labels: list[int] = []
    all_paths: list[str] = []

    for images, labels, paths in loader:
        images_np = images.numpy().astype(input_dtype)
        for i in range(len(images_np)):
            img_nhwc = images_np[i].transpose(1, 2, 0)[np.newaxis]
            interpreter.set_tensor(input_idx, img_nhwc)
            interpreter.invoke()
            emb = interpreter.get_tensor(output_idx)[0].copy()
            all_embs.append(emb)
        all_labels.extend(labels.numpy().tolist())
        all_paths.extend(paths)

    return np.stack(all_embs), np.array(all_labels, dtype=np.int64), all_paths


def summarize_tflite_model(tflite_path: Path, interpreter) -> dict:
    size_mb = round(tflite_path.stat().st_size / 1024 ** 2, 3)
    return {
        "total_parameters": None,
        "trainable_parameters": None,
        "model_size_mb": size_mb,
    }


def build_loader(dataset_dir: Path, image_size: int, batch_size: int, num_workers: int):
    dataset = ImageFolderWithPaths(dataset_dir, image_size=image_size, train=False)
    loader = DataLoader(
        dataset,
        batch_size=batch_size,
        shuffle=False,
        num_workers=num_workers,
        pin_memory=True,
    )
    return dataset, loader


def build_pair_scores(embs: np.ndarray, labels: np.ndarray, max_pairs: int, seed: int):
    sim = embs @ embs.T
    same = labels[:, None] == labels[None, :]

    tri_i, tri_j = np.triu_indices(sim.shape[0], k=1)
    scores = sim[tri_i, tri_j].astype(np.float64, copy=False)
    targets = same[tri_i, tri_j].astype(np.uint8, copy=False)

    if max_pairs and len(scores) > max_pairs:
        rng = np.random.default_rng(seed)
        chosen = rng.choice(len(scores), size=max_pairs, replace=False)
        scores = scores[chosen]
        targets = targets[chosen]

    return scores, targets


def compute_roc_metrics(scores: np.ndarray, targets: np.ndarray):
    if scores.ndim != 1 or targets.ndim != 1:
        raise ValueError("scores and targets must be 1D arrays")
    if len(scores) != len(targets):
        raise ValueError("scores and targets must have the same length")

    positives = int(targets.sum())
    negatives = int(len(targets) - positives)
    if positives == 0 or negatives == 0:
        raise ValueError("ROC requires both positive and negative pairs")

    order = np.argsort(-scores, kind="mergesort")
    scores_sorted = scores[order]
    targets_sorted = targets[order]

    tps = np.cumsum(targets_sorted, dtype=np.float64)
    fps = np.cumsum(1 - targets_sorted, dtype=np.float64)

    distinct = np.where(np.diff(scores_sorted))[0]
    threshold_idxs = np.r_[distinct, len(scores_sorted) - 1]

    tps = tps[threshold_idxs]
    fps = fps[threshold_idxs]
    thresholds = scores_sorted[threshold_idxs]

    tpr = tps / positives
    fpr = fps / negatives

    tpr = np.r_[0.0, tpr]
    fpr = np.r_[0.0, fpr]
    thresholds = np.r_[np.inf, thresholds]

    auroc = float(np.trapz(tpr, fpr))

    fnr = 1.0 - tpr
    eer_idx = int(np.argmin(np.abs(fpr - fnr)))
    eer = float((fpr[eer_idx] + fnr[eer_idx]) / 2.0)
    eer_threshold = float(thresholds[eer_idx])

    return {
        "fpr": fpr,
        "tpr": tpr,
        "fnr": fnr,
        "thresholds": thresholds,
        "auroc": auroc,
        "eer": eer,
        "eer_threshold": eer_threshold,
        "num_pairs": int(len(scores)),
        "num_positive_pairs": positives,
        "num_negative_pairs": negatives,
    }


def compute_pr_metrics(scores: np.ndarray, targets: np.ndarray):
    positives = int(targets.sum())
    if positives == 0:
        raise ValueError("Precision-recall requires at least one positive pair")

    order = np.argsort(-scores, kind="mergesort")
    scores_sorted = scores[order]
    targets_sorted = targets[order]

    tps = np.cumsum(targets_sorted, dtype=np.float64)
    fps = np.cumsum(1 - targets_sorted, dtype=np.float64)

    distinct = np.where(np.diff(scores_sorted))[0]
    threshold_idxs = np.r_[distinct, len(scores_sorted) - 1]

    tps = tps[threshold_idxs]
    fps = fps[threshold_idxs]
    thresholds = scores_sorted[threshold_idxs]

    precision = tps / np.maximum(tps + fps, 1e-12)
    recall = tps / positives

    precision_curve = np.r_[1.0, precision]
    recall_curve = np.r_[0.0, recall]
    thresholds_curve = np.r_[np.inf, thresholds]

    auprc = float(np.trapz(precision_curve, recall_curve))

    return {
        "precision": precision_curve,
        "recall": recall_curve,
        "thresholds": thresholds_curve,
        "auprc": auprc,
    }


def compute_threshold_metrics(scores: np.ndarray, targets: np.ndarray, thresholds: np.ndarray):
    if len(thresholds) == 0:
        raise ValueError("thresholds must not be empty")

    best = None
    for threshold in thresholds:
        preds = scores >= threshold

        tp = int(np.sum(preds & (targets == 1)))
        fp = int(np.sum(preds & (targets == 0)))
        tn = int(np.sum((~preds) & (targets == 0)))
        fn = int(np.sum((~preds) & (targets == 1)))

        precision = tp / max(tp + fp, 1)
        recall = tp / max(tp + fn, 1)
        specificity = tn / max(tn + fp, 1)
        accuracy = (tp + tn) / max(len(targets), 1)
        f1 = 2.0 * precision * recall / max(precision + recall, 1e-12)
        balanced_accuracy = 0.5 * (recall + specificity)

        row = {
            "threshold": float(threshold),
            "accuracy": float(accuracy),
            "precision": float(precision),
            "recall": float(recall),
            "specificity": float(specificity),
            "f1": float(f1),
            "balanced_accuracy": float(balanced_accuracy),
            "tp": tp,
            "fp": fp,
            "tn": tn,
            "fn": fn,
        }

        if (
            best is None
            or row["f1"] > best["f1"]
            or (
                np.isclose(row["f1"], best["f1"])
                and row["balanced_accuracy"] > best["balanced_accuracy"]
            )
        ):
            best = row

    return best


def compute_tar_at_far(roc: dict, far_levels: list[float]):
    results = {}
    fpr = roc["fpr"]
    tpr = roc["tpr"]
    thresholds = roc["thresholds"]

    for far in far_levels:
        valid = np.where(fpr <= far)[0]
        if len(valid) == 0:
            idx = 0
        else:
            idx = int(valid[-1])

        results[f"TAR@FAR={far:g}"] = {
            "tar": float(tpr[idx]),
            "far": float(fpr[idx]),
            "threshold": float(thresholds[idx]),
        }

    return results


def compute_score_c_metrics(
    embs: np.ndarray,
    labels: np.ndarray,
    k_values: tuple,
    alphas: tuple,
    threshold: float | None = None,
) -> dict:
    """Compute Score(c) classification accuracy and coverage."""
    n = len(labels)
    unique_classes = np.unique(labels)

    sim = (embs @ embs.T).astype(np.float64)
    np.fill_diagonal(sim, -np.inf)

    class_indices = {c: np.where(labels == c)[0] for c in unique_classes}

    results = {}
    for k in k_values:
        for alpha in alphas:
            correct  = 0
            accepted = 0
            for i in range(n):
                s      = sim[i]
                true_c = labels[i]
                best_score = -np.inf
                best_c     = None

                for c, idx in class_indices.items():
                    gallery = idx[idx != i] if c == true_c else idx
                    if len(gallery) == 0:
                        continue

                    sims_c = s[gallery]

                    if threshold is not None:
                        sims_c = sims_c[sims_c >= threshold]
                    if len(sims_c) == 0:
                        continue

                    actual_k = min(k, len(sims_c))
                    top_k = np.partition(sims_c, len(sims_c) - actual_k)[-actual_k:]

                    max_s    = float(top_k.max())
                    mean_k_s = float(top_k.mean())
                    score    = max_s + alpha * (mean_k_s - max_s)

                    if score > best_score:
                        best_score = score
                        best_c     = c

                if best_c is None:
                    continue  # query rejected — no class had qualifying similarities

                accepted += 1
                if best_c == true_c:
                    correct += 1

            accuracy = round(correct / accepted, 6) if accepted > 0 else 0.0
            coverage = round(accepted / n, 6)
            results[f"k={k}_alpha={alpha:.2f}"] = {
                "accuracy": accuracy,
                "coverage": coverage,
            }

    return results


def summarize_model(model: torch.nn.Module) -> dict:
    total = sum(p.numel() for p in model.parameters())
    trainable = sum(p.numel() for p in model.parameters() if p.requires_grad)
    size_mb = sum(p.numel() * p.element_size() for p in model.parameters()) / 1024 ** 2
    return {
        "total_parameters": total,
        "trainable_parameters": trainable,
        "model_size_mb": round(size_mb, 3),
    }


def summarize_embeddings(embs: np.ndarray):
    norms = np.linalg.norm(embs, axis=1)
    return {
        "embedding_dim": int(embs.shape[1]),
        "mean_l2_norm": float(norms.mean()),
        "std_l2_norm": float(norms.std()),
        "min_l2_norm": float(norms.min()),
        "max_l2_norm": float(norms.max()),
    }


def save_curve_plot(x, y, xlabel: str, ylabel: str, title: str, output_path: Path):
    output_path.parent.mkdir(parents=True, exist_ok=True)

    plt.figure(figsize=(4, 4))
    plt.plot(x, y, linewidth=2)
    plt.xlabel(xlabel, fontsize=14)
    plt.ylabel(ylabel, fontsize=14)

    plt.grid(True, alpha=0.3)
    plt.tight_layout()
    plt.savefig(output_path, dpi=200)
    plt.close()


def main():
    args = parse_args()

    random.seed(args.seed)
    np.random.seed(args.seed)
    torch.manual_seed(args.seed)
    torch.cuda.manual_seed_all(args.seed)
    torch.backends.cudnn.benchmark = False
    torch.backends.cudnn.deterministic = True
    torch.use_deterministic_algorithms(True)

    is_tflite = args.checkpoint.suffix == ".tflite"

    if is_tflite:
        interpreter = load_tflite_interpreter(args.checkpoint)
        image_size = tflite_image_size(interpreter)
        model_stats = summarize_tflite_model(args.checkpoint, interpreter)
        print(f"TFLite model: image_size={image_size}, embedding_dim={tflite_embedding_dim(interpreter)}")
    else:
        device = torch.device(args.device)
        model, cfg = load_model(args.checkpoint, device)
        image_size = cfg["data"]["image_size"]
        model_stats = summarize_model(model)

    dataset, loader = build_loader(
        dataset_dir=args.dataset_dir,
        image_size=image_size,
        batch_size=args.batch_size,
        num_workers=args.num_workers,
    )

    if is_tflite:
        embs, labels, paths = compute_embeddings_tflite(interpreter, loader)
    else:
        with torch.no_grad():
            embs, labels, paths = compute_embeddings(model, loader, device)

    k_values = tuple(sorted(set(args.k_values)))
    retrieval = compute_retrieval_metrics(embs, labels, k_values)
    score_c = compute_score_c_metrics(
        embs, labels, k_values, tuple(sorted(set(args.score_alphas))),
        threshold=args.score_threshold,
    )
    extreme_pairs = find_extreme_pairs(embs, labels, paths, top_n=args.top_n)
    scores, targets = build_pair_scores(
        embs=embs,
        labels=labels,
        max_pairs=args.max_pairs,
        seed=args.seed,
    )
    roc = compute_roc_metrics(scores, targets)
    pr = compute_pr_metrics(scores, targets)
    best_f1 = compute_threshold_metrics(scores, targets, roc["thresholds"][1:])
    tar_at_far = compute_tar_at_far(roc, args.far_levels)
    embedding_stats = summarize_embeddings(embs)

    result = {
        "checkpoint": str(args.checkpoint.resolve()),
        "dataset_dir": str(args.dataset_dir.resolve()),
        "num_images": int(len(dataset)),
        "num_classes": int(len(dataset.class_to_idx)),
        "model_stats": model_stats,
        "embedding_stats": embedding_stats,
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
        "score_c": score_c,
    }

    if args.save_curves:
        result["verification"]["roc_curve"] = {
            "fpr": roc["fpr"].tolist(),
            "tpr": roc["tpr"].tolist(),
            "thresholds": roc["thresholds"].tolist(),
        }
        result["verification"]["pr_curve"] = {
            "precision": pr["precision"].tolist(),
            "recall": pr["recall"].tolist(),
            "thresholds": pr["thresholds"].tolist(),
        }

    print(f"Images: {result['num_images']}")
    print(f"Classes: {result['num_classes']}")
    total = model_stats["total_parameters"]
    trainable = model_stats["trainable_parameters"]
    print(f"Total parameters: {total:,}" if total is not None else "Total parameters: N/A")
    print(f"Trainable parameters: {trainable:,}" if trainable is not None else "Trainable parameters: N/A (post-quantization)")
    print(f"Model size: {model_stats['model_size_mb']} MB")
    print(f"Embedding dim: {embedding_stats['embedding_dim']}")
    for k in k_values:
        print(f"Top-{k}: {retrieval.get(f'top{k}', 0.0):.6f}")
    for k in k_values:
        print(f"Recall@{k}: {retrieval.get(f'recall@{k}', 0.0):.6f}")
    for k in k_values:
        print(f"mAP@{k}: {retrieval.get(f'mAP@{k}', 0.0):.6f}")
    print(f"mAP: {retrieval['mAP']:.6f}")
    print(f"MRR: {retrieval['MRR']:.6f}")
    print(f"AUROC: {roc['auroc']:.6f}")
    print(f"AUPRC: {pr['auprc']:.6f}")
    print(f"EER: {roc['eer']:.6f}")
    print(f"EER threshold: {roc['eer_threshold']:.6f}")
    print(f"Best-F1 threshold: {best_f1['threshold']:.6f}")
    print(f"Best-F1: {best_f1['f1']:.6f}")
    print("Score(c) classification:")
    print(f"  top1 baseline: {retrieval.get('top1', 0.0):.6f}")
    for key, vals in score_c.items():
        cov_str = f"  coverage={vals['coverage']:.4f}" if vals["coverage"] < 1.0 else ""
        print(f"  {key}: accuracy={vals['accuracy']:.6f}{cov_str}")

    output_paths = default_output_paths(args.checkpoint, args.dataset_dir)
    output_json = args.output_json or output_paths["json"]
    output_roc_png = args.output_roc_png or output_paths["roc_png"]
    output_pr_png = args.output_pr_png or output_paths["pr_png"]
    output_pairs_txt = output_paths["pairs_txt"]
    output_summary_txt = output_paths["summary_txt"]

    output_json.parent.mkdir(parents=True, exist_ok=True)
    with output_json.open("w", encoding="utf-8") as f:
        json.dump(result, f, indent=2)
    print(f"Saved evaluation JSON: {output_json}")

    save_curve_plot(
        roc["fpr"],
        roc["tpr"],
        xlabel="False Positive Rate",
        ylabel="True Positive Rate",
        title="ROC Curve",
        output_path=output_roc_png,
    )
    print(f"Saved ROC PNG: {output_roc_png}")

    save_curve_plot(
        pr["recall"],
        pr["precision"],
        xlabel="Recall",
        ylabel="Precision",
        title="Precision-Recall Curve",
        output_path=output_pr_png,
    )
    print(f"Saved PR PNG: {output_pr_png}")

    save_pairs_txt(extreme_pairs, output_pairs_txt)
    print(f"Saved pair analysis: {output_pairs_txt}")

    save_summary_txt(result, args.k_values, output_summary_txt)
    print(f"Saved evaluation summary: {output_summary_txt}")


if __name__ == "__main__":
    main()
