"""
@author Bc. Lenka Sokova
"""

import numpy as np
import torch


@torch.no_grad()
def compute_embeddings(model, loader, device):
    model.eval()

    all_embs = []
    all_labels = []
    all_paths = []

    for images, labels, paths in loader:
        images = images.to(device, non_blocking=True)
        embs = model(images)

        all_embs.append(embs.cpu())
        all_labels.append(labels.cpu())
        all_paths.extend(paths)

    embs = torch.cat(all_embs, dim=0).numpy()
    labels = torch.cat(all_labels, dim=0).numpy()

    return embs, labels, all_paths


@torch.no_grad()
def compute_retrieval_metrics(embs, labels, k_values=(1, 3, 5)):
    """
    Compute retrieval metrics.

    Each sample is used as both a query and a gallery item.
    The same item is removed from its own ranking.
    """
    embs = np.asarray(embs, dtype=np.float32)
    labels = np.asarray(labels)

    sim = embs @ embs.T
    n = sim.shape[0]
    np.fill_diagonal(sim, -np.inf)

    sorted_idx = np.argsort(-sim, axis=1)

    max_k = max(k_values)
    metrics = {}

    for k in k_values:
        topk = sorted_idx[:, :k]
        hit = (labels[topk] == labels[:, None]).any(axis=1)
        metrics[f"top{k}"] = hit.mean().item()

    aps_full = []
    aps_at_k = {k: [] for k in k_values}

    for i in range(n):
        idx_full = sorted_idx[i]
        idx_full = idx_full[sim[i][idx_full] > -np.inf]

        rel_full = (labels[idx_full] == labels[i]).astype(np.float32)
        n_rel = rel_full.sum()
        if n_rel == 0:
            aps_full.append(0.0)
            for k in k_values:
                aps_at_k[k].append(0.0)
            continue

        cum_tp = np.cumsum(rel_full)
        ranks = np.arange(1, len(rel_full) + 1, dtype=np.float32)
        precision_at_r = cum_tp / ranks

        ap_full = (precision_at_r * rel_full).sum() / n_rel
        aps_full.append(ap_full)

        for k in k_values:
            rel_k = rel_full[:k]
            n_rel_k = rel_k.sum()
            if n_rel_k == 0:
                aps_at_k[k].append(0.0)
            else:
                p_k = precision_at_r[:k]
                aps_at_k[k].append((p_k * rel_k).sum() / min(n_rel, k))

    metrics["mAP"] = float(np.mean(aps_full))
    for k in k_values:
        metrics[f"mAP@{k}"] = float(np.mean(aps_at_k[k]))
        metrics[f"recall@{k}"] = metrics[f"top{k}"]

    rrs = []
    for i in range(n):
        idx = sorted_idx[i]
        idx = idx[sim[i][idx] > -np.inf]
        match = np.where(labels[idx] == labels[i])[0]
        rrs.append(1.0 / (match[0] + 1) if len(match) > 0 else 0.0)

    metrics["MRR"] = float(np.mean(rrs))

    return metrics


# Keep the old name for backward compatibility
@torch.no_grad()
def retrieval_topk(embs, labels, k_values=(1, 3, 5)):
    full = compute_retrieval_metrics(embs, labels, k_values)
    return {f"top{k}": full[f"top{k}"] for k in k_values}
