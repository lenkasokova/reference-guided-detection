"""
@author Bc. Lenka Sokova

Simple script for exporting a trained EmbeddingModel checkpoint to TFLite.

Pipeline:  best.pt  [→ pruning]  →  ONNX  →  TF SavedModel  →  .tflite

Usage examples:
  # float32 (most compatible, largest)
  python export_tflite.py --checkpoint runs/mobilenet_v3_small/supcon_t007/checkpoints/best.pt

  # float16 (half size, same accuracy on most phones)
  python export_tflite.py --checkpoint runs/lite0/supcon_t007/checkpoints/best.pt --quantization float16

  # int8 (smallest, ~4x faster on phones with NNAPI/Hexagon DSP)
  python export_tflite.py --checkpoint runs/lite0/supcon_t007/checkpoints/best.pt --quantization int8

  # unstructured pruning (30% of lowest-magnitude weights zeroed globally)
  python export_tflite.py --checkpoint best.pt --pruning unstructured --pruning-amount 0.3

  # structured pruning (30% of Conv2d output channels with smallest L2 norm removed)
  python export_tflite.py --checkpoint best.pt --pruning structured --pruning-amount 0.3

Pruning:
  unstructured: sets small weights to zero
  structured: removes whole channels

Extra packages:
  pip install onnx onnx2tf tensorflow torch-pruning
"""

import argparse
import sys
from pathlib import Path

import numpy as np
import torch
import torch.nn as nn
import torch.nn.utils.prune as prune

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from model import EmbeddingModel


def load_model(checkpoint_path: Path):
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
        model = prune_model(model, "structured", pruning_meta["amount"], cfg["data"]["image_size"])

    model.load_state_dict(ckpt["model_state_dict"])
    model.eval()
    return model, cfg


def prune_model(model: EmbeddingModel, method: str, amount: float, image_size: int) -> EmbeddingModel:
    """Apply pruning before export."""
    if amount <= 0.0 or amount >= 1.0:
        raise ValueError(f"--pruning-amount must be in (0, 1), got {amount}")

    if method == "unstructured":
        conv_layers   = [(m, "weight") for m in model.modules() if isinstance(m, nn.Conv2d)]
        linear_layers = [(m, "weight") for m in model.modules() if isinstance(m, nn.Linear)]
        parameters_to_prune = conv_layers + linear_layers

        prune.global_unstructured(
            parameters_to_prune,
            pruning_method=prune.L1Unstructured,
            amount=amount,
        )
        for module, name in parameters_to_prune:
            prune.remove(module, name)

        total = sum(m.weight.numel() for m, _ in parameters_to_prune)
        zeros = sum((m.weight == 0).sum().item() for m, _ in parameters_to_prune)
        print(f"Unstructured pruning: {zeros}/{total} zero weights ({zeros/total:.1%})")

    elif method == "structured":
        try:
            import torch_pruning as tp
        except ImportError:
            print("torch-pruning is not installed. Run: pip install torch-pruning")
            sys.exit(1)

        example_inputs = torch.zeros(1, 3, image_size, image_size)

        # Keep the embedding head unchanged so embedding_dim stays the same.
        ignored_layers = list(model.embedding_head.modules())

        imp = tp.importance.MagnitudeImportance(p=2)
        pruner = tp.pruner.MagnitudePruner(
            model,
            example_inputs,
            importance=imp,
            pruning_ratio=amount,
            ignored_layers=ignored_layers,
        )

        before = sum(p.numel() for p in model.parameters())
        pruner.step()
        after = sum(p.numel() for p in model.parameters())
        print(f"Structured pruning: {before:,} -> {after:,} parameters "
              f"({(before - after) / before:.1%} smaller)")

    else:
        raise ValueError(f"Unknown pruning method: {method!r}. Choose 'unstructured' or 'structured'.")

    return model


class _ExportWrapper(torch.nn.Module):
    """Small wrapper used for ONNX export."""
    def __init__(self, model: EmbeddingModel):
        super().__init__()
        self.backbone = model.backbone
        self.embedding_head = model.embedding_head

    def forward(self, x):
        feats = self.backbone(x)
        emb = self.embedding_head(feats)
        norm = torch.sqrt((emb * emb).sum(dim=1, keepdim=True) + 1e-12)
        return emb / norm


def export_onnx(model: EmbeddingModel, onnx_path: Path, image_size: int):
    wrapper = _ExportWrapper(model).eval()
    dummy = torch.zeros(1, 3, image_size, image_size)
    torch.onnx.export(
        wrapper,
        dummy,
        str(onnx_path),
        input_names=["image"],
        output_names=["embedding"],
        opset_version=18,
        dynamic_axes=None,
        external_data=False,
    )
    print(f"Saved ONNX: {onnx_path}")


def build_calibration_fn(calibration_dir: Path, image_size: int, n_images: int = 200):
    """Build a small image generator for INT8 calibration."""
    from PIL import Image
    from torchvision import transforms

    transform = transforms.Compose([
        transforms.Resize((image_size, image_size)),
        transforms.ToTensor(),
        transforms.Normalize(mean=[0.485, 0.456, 0.406], std=[0.229, 0.224, 0.225]),
    ])

    img_exts = {".jpg", ".jpeg", ".png", ".bmp", ".webp"}
    all_paths = [
        p for p in sorted(calibration_dir.rglob("*"))
        if p.suffix.lower() in img_exts
    ]
    if not all_paths:
        print(f"No images found in calibration dir: {calibration_dir}")
        sys.exit(1)

    import random
    rng = random.Random(42)
    chosen = rng.sample(all_paths, min(n_images, len(all_paths)))
    print(f"INT8 calibration: {len(chosen)} images from {calibration_dir}")

    def representative_dataset():
        for path in chosen:
            img = Image.open(path).convert("RGB")
            t = transform(img)  # [3, H, W]
            arr = t.numpy().transpose(1, 2, 0)[np.newaxis].astype(np.float32)  # [1, H, W, 3]
            yield [arr]

    return representative_dataset


def convert_to_tflite(onnx_path: Path, tflite_path: Path, quantization: str, image_size: int,
                      calibration_dir: Path | None = None):
    try:
        import onnx2tf
    except ImportError:
        print("onnx2tf is not installed. Run: pip install onnx2tf")
        sys.exit(1)

    import numpy as np
    import tensorflow as tf
    import onnx2tf.onnx2tf as _onnx2tf_mod

    # Patch a small onnx2tf helper so conversion works with newer numpy.
    _orig_download = _onnx2tf_mod.download_test_image_data
    _onnx2tf_mod.download_test_image_data = lambda: np.zeros(
        (1, 3, image_size, image_size), dtype=np.float32
    )

    # onnx2tf changes NCHW to NHWC automatically.
    saved_model_dir = tflite_path.parent / (tflite_path.stem + "_saved_model")

    try:
        onnx2tf.convert(
            input_onnx_file_path=str(onnx_path),
            output_folder_path=str(saved_model_dir),
            non_verbose=True,
        )
    finally:
        _onnx2tf_mod.download_test_image_data = _orig_download
    print(f"Saved TF SavedModel: {saved_model_dir}")

    converter = tf.lite.TFLiteConverter.from_saved_model(str(saved_model_dir))

    if quantization == "int8":
        if calibration_dir is None:
            print("--calibration-dir is required for INT8 quantization.")
            sys.exit(1)
        calibration_dataset_fn = build_calibration_fn(calibration_dir, image_size)

    if quantization == "float16":
        converter.optimizations = [tf.lite.Optimize.DEFAULT]
        converter.target_spec.supported_types = [tf.float16]

    elif quantization == "int8":
        converter.optimizations = [tf.lite.Optimize.DEFAULT]
        converter.representative_dataset = calibration_dataset_fn
        converter.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS_INT8]
        # Input and output stay float32 because preprocessing is done outside the model.
        converter.inference_input_type = tf.float32
        converter.inference_output_type = tf.float32

    tflite_model = converter.convert()
    tflite_path.write_bytes(tflite_model)
    size_kb = len(tflite_model) / 1024
    print(f"Saved TFLite: {tflite_path} ({size_kb:.1f} KB)")


def verify_tflite(tflite_path: Path, image_size: int, embedding_dim: int):
    """Run one dummy inference to check the model."""
    try:
        import tensorflow as tf
    except ImportError:
        return

    interpreter = tf.lite.Interpreter(model_path=str(tflite_path))
    interpreter.allocate_tensors()

    in_det = interpreter.get_input_details()[0]
    out_det = interpreter.get_output_details()[0]

    dummy = np.zeros(in_det["shape"], dtype=in_det["dtype"])
    interpreter.set_tensor(in_det["index"], dummy)
    interpreter.invoke()
    output = interpreter.get_tensor(out_det["index"])

    assert output.shape == (1, embedding_dim), \
        f"Unexpected output shape: {output.shape}, expected (1, {embedding_dim})"
    print(f"Check passed: input {in_det['shape']} {in_det['dtype'].__name__} -> "
          f"output {output.shape} {output.dtype}")


def parse_args():
    parser = argparse.ArgumentParser(description="Convert EmbeddingModel checkpoint to TFLite")
    parser.add_argument("--checkpoint", type=Path, required=True,
                        help="Path to best.pt or last.pt")
    parser.add_argument("--output", type=Path, default=None,
                        help="Output .tflite path (default: next to checkpoint)")
    parser.add_argument("--quantization", choices=["float32", "float16", "int8"],
                        default="float32",
                        help="Quantization mode (default: float32)")
    parser.add_argument("--pruning", choices=["none", "unstructured", "structured"],
                        default="none",
                        help="Pruning method applied before export (default: none)")
    parser.add_argument("--pruning-amount", type=float, default=0.3,
                        help="Fraction of weights/channels to prune, in (0, 1) (default: 0.3)")
    parser.add_argument("--calibration-dir", type=Path, default=None,
                        help="Directory of images used for INT8 calibration (required for --quantization int8).")
    parser.add_argument("--keep-onnx", action="store_true",
                        help="Keep intermediate ONNX file")
    parser.add_argument("--keep-saved-model", action="store_true",
                        help="Keep intermediate TF SavedModel directory")
    return parser.parse_args()


def main():
    args = parse_args()

    ckpt_path = args.checkpoint.resolve()
    if not ckpt_path.exists():
        print(f"Checkpoint not found: {ckpt_path}")
        sys.exit(1)

    prune_suffix = f"_pruned_{args.pruning}_{int(args.pruning_amount * 100)}pct" \
                   if args.pruning != "none" else ""
    quant_suffix = f"_{args.quantization}" if args.quantization != "float32" else "_fp32"
    out_path = args.output or ckpt_path.parent / f"embedding{prune_suffix}{quant_suffix}.tflite"
    out_path = out_path.resolve()
    out_path.parent.mkdir(parents=True, exist_ok=True)

    onnx_path = out_path.with_suffix(".onnx")

    print(f"Checkpoint: {ckpt_path}")
    print(f"Output: {out_path}")
    print(f"Quantization: {args.quantization}")
    if args.pruning != "none":
        print(f"Pruning: {args.pruning}, amount={args.pruning_amount:.0%}")

    model, cfg = load_model(ckpt_path)

    image_size    = cfg["data"]["image_size"]
    backbone      = cfg["train"]["backbone"]
    embedding_dim = cfg["train"]["embedding_dim"]
    print(f"Backbone: {backbone}, embedding_dim: {embedding_dim}, image_size: {image_size}")

    total_params = sum(p.numel() for p in model.parameters())
    size_mb = sum(p.numel() * p.element_size() for p in model.parameters()) / 1024 ** 2
    print(f"Parameters: {total_params:,} ({size_mb:.1f} MB fp32)")

    if args.pruning != "none":
        model = prune_model(model, args.pruning, args.pruning_amount, image_size)
        if args.pruning == "structured":
            pruned_params = sum(p.numel() for p in model.parameters())
            pruned_mb = sum(p.numel() * p.element_size() for p in model.parameters()) / 1024 ** 2
            removed = total_params - pruned_params
            print(f"Parameters after pruning: {pruned_params:,} ({pruned_mb:.1f} MB fp32), "
                  f"{removed:,} removed ({removed/total_params:.1%})")
        else:
            zeros = sum((p == 0).sum().item() for p in model.parameters())
            total = sum(p.numel() for p in model.parameters())
            print(f"Sparsity: {zeros:,}/{total:,} zero weights ({zeros/total:.1%})")

    export_onnx(model, onnx_path, image_size)
    convert_to_tflite(onnx_path, out_path, args.quantization, image_size,
                      calibration_dir=args.calibration_dir)
    verify_tflite(out_path, image_size, embedding_dim)

    if not args.keep_onnx:
        onnx_path.unlink(missing_ok=True)

    if not args.keep_saved_model:
        import shutil
        saved_model_dir = out_path.parent / (out_path.stem + "_saved_model")
        if saved_model_dir.exists():
            shutil.rmtree(saved_model_dir)

    tflite_mb = out_path.stat().st_size / 1024 ** 2
    print("\nDone.")
    print(f"TFLite model: {out_path}")
    print(f"TFLite size: {tflite_mb:.1f} MB")
    print(f"Input: [1, {image_size}, {image_size}, 3] NHWC float32 in [0, 1]")
    print(f"Output: [1, {embedding_dim}] float32 L2-normalized embedding")
    print("Android use: cosine similarity = dot(emb_a, emb_b)")


if __name__ == "__main__":
    main()
