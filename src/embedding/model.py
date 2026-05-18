"""
@author Bc. Lenka Sokova
"""

import torch
import torch.nn as nn
import torch.nn.functional as F
import timm


# timm only has direct pretrained weights for efficientnet_lite0.
# For lite1-4, use the tf_efficientnet_lite* versions and load those weights.
_TF_PRETRAINED_NAMES = {
    "efficientnet_lite1": "tf_efficientnet_lite1",
    "efficientnet_lite2": "tf_efficientnet_lite2",
    "efficientnet_lite3": "tf_efficientnet_lite3",
    "efficientnet_lite4": "tf_efficientnet_lite4",
}

# Simple config names that map to timm model names.
_DIRECT_TIMM_NAMES = {
    "mobilenet_v3_small": "mobilenetv3_small_100",
    "mobilenet_v3_large": "mobilenetv3_large_100",
    "ghostnet_100": "ghostnet_100",
}

SUPPORTED_BACKBONES = {
    "efficientnet_lite0",
    "efficientnet_lite1",
    "efficientnet_lite2",
    "efficientnet_lite3",
    "efficientnet_lite4",
    "mobilenet_v3_small",
    "mobilenet_v3_large",
    "ghostnet_100",
}

# Backward compatibility alias
SUPPORTED_EFFICIENTNET_LITE_BACKBONES = SUPPORTED_BACKBONES


def _create_backbone(backbone_name: str, pretrained: bool) -> nn.Module:
    # If the name maps directly to timm, create it like that.
    timm_name = _DIRECT_TIMM_NAMES.get(backbone_name)
    if timm_name:
        try:
            return timm.create_model(
                timm_name,
                pretrained=pretrained,
                num_classes=0,
                global_pool="avg",
            )
        except Exception as e:
            print(f"Pretrained load failed for {backbone_name} ({e}). Using random weights.")
            return timm.create_model(timm_name, pretrained=False, num_classes=0, global_pool="avg")

    backbone = timm.create_model(
        backbone_name,
        pretrained=False,
        num_classes=0,
        global_pool="avg",
    )

    if not pretrained:
        return backbone

    # For lite1-4, borrow weights from the tf_efficientnet_lite* version.
    tf_name = _TF_PRETRAINED_NAMES.get(backbone_name)
    if tf_name:
        try:
            tf_model = timm.create_model(tf_name, pretrained=True, num_classes=0, global_pool="avg")
            missing, unexpected = backbone.load_state_dict(tf_model.state_dict(), strict=False)
            if missing:
                print(f"{backbone_name} from {tf_name}: missing keys: {missing}")
            del tf_model
            print(f"{backbone_name}: loaded pretrained weights from {tf_name}")
        except Exception as e:
            print(f"Pretrained load failed for {backbone_name} via {tf_name} ({e}). Using random weights.")
        return backbone

    try:
        return timm.create_model(
            backbone_name,
            pretrained=True,
            num_classes=0,
            global_pool="avg",
        )
    except Exception as e:
        print(f"Pretrained download failed for {backbone_name} ({e}). Using random weights.")
        return backbone


class EmbeddingModel(nn.Module):
    def __init__(self, backbone_name="efficientnet_lite0", embedding_dim=128, pretrained=True):
        super().__init__()

        if backbone_name not in SUPPORTED_BACKBONES:
            supported = ", ".join(sorted(SUPPORTED_BACKBONES))
            raise ValueError(
                f"Unsupported backbone: {backbone_name}. Supported backbones: {supported}"
            )

        backbone = _create_backbone(backbone_name, pretrained)
        with torch.no_grad():
            _dummy = torch.zeros(1, 3, 224, 224)
            in_features = backbone(_dummy).shape[1]
        self.backbone = backbone

        self.embedding_head = nn.Sequential(
            nn.Linear(in_features, 512),
            nn.ReLU(inplace=True),
            nn.Dropout(0.2),
            nn.Linear(512, embedding_dim),
        )

    def forward(self, x):
        feats = self.backbone(x)
        emb = self.embedding_head(feats)
        emb = F.normalize(emb, dim=1)
        return emb
