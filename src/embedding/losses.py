"""
@author Bc. Lenka Sokova
"""

import torch
import torch.nn as nn
import torch.nn.functional as F


class SupConLoss(nn.Module):
    def __init__(self, temperature=0.07):
        super().__init__()
        self.temperature = temperature

    def forward(self, features, labels):
        device = features.device
        features = F.normalize(features, dim=1)

        logits = torch.matmul(features, features.T) / self.temperature
        logits_mask = torch.ones_like(logits) - torch.eye(logits.size(0), device=device)
        logits = logits - 1e9 * (1 - logits_mask)

        labels = labels.contiguous().view(-1, 1)
        positive_mask = torch.eq(labels, labels.T).float().to(device)
        positive_mask = positive_mask * logits_mask

        exp_logits = torch.exp(logits) * logits_mask
        log_prob = logits - torch.log(exp_logits.sum(dim=1, keepdim=True) + 1e-12)

        positive_count = positive_mask.sum(dim=1)
        mean_log_prob_pos = (positive_mask * log_prob).sum(dim=1) / (positive_count + 1e-12)

        valid = positive_count > 0

        if valid.any():
            loss = -mean_log_prob_pos[valid].mean()
        else:
            # keep graph connected
            loss = features.sum() * 0.0

        return loss


class TripletLossBatch(nn.Module):
    def __init__(self, margin=0.2, miner="semi_hard"):
        super().__init__()
        self.margin = margin
        self.miner = miner

    def forward(self, embeddings, labels):
        embeddings = F.normalize(embeddings, dim=1)
        dist = torch.cdist(embeddings, embeddings, p=2)

        total_loss = []
        n = embeddings.size(0)

        for i in range(n):
            anchor_label = labels[i]
            pos_mask = (labels == anchor_label).clone()
            neg_mask = labels != anchor_label
            pos_mask[i] = False

            pos_dists = dist[i][pos_mask]
            neg_dists = dist[i][neg_mask]

            if pos_dists.numel() == 0 or neg_dists.numel() == 0:
                continue

            hardest_pos = pos_dists.max()

            if self.miner == "hard":
                chosen_neg = neg_dists.min()
            elif self.miner == "semi_hard":
                semi_hard = neg_dists[
                    (neg_dists > hardest_pos) & (neg_dists < hardest_pos + self.margin)
                ]
                chosen_neg = semi_hard.min() if semi_hard.numel() > 0 else neg_dists.min()
            else:
                idx = torch.randint(0, neg_dists.numel(), (1,), device=neg_dists.device)
                chosen_neg = neg_dists[idx].squeeze(0)

            loss_i = F.relu(hardest_pos - chosen_neg + self.margin)
            total_loss.append(loss_i)

        if len(total_loss) == 0:
            # keep graph connected
            return embeddings.sum() * 0.0

        return torch.stack(total_loss).mean()


class ProxyAnchorLoss(nn.Module):
    def __init__(self, num_classes, embedding_dim, margin=0.1, alpha=32):
        super().__init__()
        self.proxies = nn.Parameter(torch.randn(num_classes, embedding_dim))
        nn.init.kaiming_normal_(self.proxies, mode="fan_out")
        self.margin = margin
        self.alpha = alpha
        self.num_classes = num_classes

    def forward(self, embeddings, labels):
        # embeddings are already L2-normalised by the model
        proxies = F.normalize(self.proxies, dim=1)

        # cosine similarity matrix: (B, C)
        sim = embeddings @ proxies.T

        # pos_mask[i, c] = 1 iff label[i] == c
        pos_mask = torch.zeros(
            embeddings.size(0), self.num_classes, device=embeddings.device
        )
        pos_mask.scatter_(1, labels.unsqueeze(1), 1.0)
        neg_mask = 1.0 - pos_mask

        # Only average over proxies that have at least one positive in this batch
        has_pos = pos_mask.sum(dim=0) > 0  # (C,)

        pos_exp = pos_mask * torch.exp(-self.alpha * (sim - self.margin))  # (B, C)
        neg_exp = neg_mask * torch.exp(self.alpha * (sim + self.margin))   # (B, C)

        # Sum contributions per proxy, then log(1 + sum)
        pos_loss = torch.log(1 + pos_exp.sum(dim=0))  # (C,)
        neg_loss = torch.log(1 + neg_exp.sum(dim=0))  # (C,)

        if has_pos.any():
            return pos_loss[has_pos].mean() + neg_loss[has_pos].mean()

        # keep graph connected when no valid proxy in batch
        return embeddings.sum() * 0.0


def build_loss(cfg, num_classes=None):
    name = cfg["loss"]["name"].lower()

    if name == "supcon":
        return SupConLoss(temperature=cfg["loss"]["temperature"])

    if name == "triplet":
        return TripletLossBatch(
            margin=cfg["loss"]["margin"],
            miner=cfg["loss"]["miner"],
        )

    if name == "proxy_anchor":
        if num_classes is None:
            raise ValueError("proxy_anchor loss requires num_classes (inferred from dataset)")
        return ProxyAnchorLoss(
            num_classes=num_classes,
            embedding_dim=cfg["train"]["embedding_dim"],
            margin=cfg["loss"].get("margin", 0.1),
            alpha=cfg["loss"].get("alpha", 32),
        )

    raise ValueError(f"Unknown loss: {name}")
