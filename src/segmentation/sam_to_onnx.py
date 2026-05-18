"""
@author Bc. Lenka Sokova

Script for exporting MobileSAM or EdgeSAM to ONNX.

It creates two files:
  - {model}_encoder.onnx
  - {model}_decoder.onnx

Examples:
  python segmentation/sam_to_onnx.py --model mobilesam --checkpoint mobile_sam.pt
  python segmentation/sam_to_onnx.py --model edgesam --checkpoint edge_sam.pth
  python segmentation/sam_to_onnx.py --model mobilesam --checkpoint mobile_sam.pt --quantize dynamic_int8
  python segmentation/sam_to_onnx.py --model mobilesam --checkpoint mobile_sam.pt --image-size 512
"""

import argparse
import copy
import os
import sys
import types
from contextlib import nullcontext
from pathlib import Path

import numpy as np
import torch
import torch.nn as nn


# CLI args

def parse_args():
    parser = argparse.ArgumentParser(
        description="Export MobileSAM or EdgeSAM encoder + decoder to ONNX."
    )
    parser.add_argument(
        "--model",
        required=True,
        choices=["mobilesam", "edgesam"],
        help="Which SAM variant to export.",
    )
    parser.add_argument(
        "--checkpoint",
        required=True,
        help="Path to the model checkpoint (.pt / .pth).",
    )
    parser.add_argument(
        "--out-dir",
        default="models",
        help="Directory to write output files into.",
    )
    parser.add_argument(
        "--image-size",
        type=int,
        default=1024,
        help="Input image size for the encoder. 1024 is full size, 512 is smaller.",
    )
    parser.add_argument(
        "--num-points",
        type=int,
        default=2,
        help="Number of prompt points for the decoder. Unused points use label -1.",
    )
    parser.add_argument(
        "--opset",
        type=int,
        default=17,
        help="ONNX opset version.",
    )
    parser.add_argument(
        "--simplify",
        action="store_true",
        default=True,
        help="Run onnxsim to simplify the graph (default: on).",
    )
    parser.add_argument(
        "--no-simplify",
        dest="simplify",
        action="store_false",
        help="Disable onnxsim graph simplification.",
    )
    parser.add_argument(
        "--quantize",
        default="none",
        choices=["none", "dynamic_int8", "static_int8", "fp16"],
        help="Post-export quantization / precision conversion.",
    )
    parser.add_argument(
        "--calibration-dataset",
        default=None,
        metavar="DIR",
        help="Folder with images for static INT8 calibration.",
    )
    parser.add_argument(
        "--calibration-size",
        type=int,
        default=100,
        metavar="N",
        help="Maximum number of calibration images to use.",
    )
    parser.add_argument(
        "--src-dir",
        default=None,
        metavar="DIR",
        help="Path to a local MobileSAM or EdgeSAM folder.",
    )
    parser.add_argument(
        "--device",
        default="cpu",
        help="PyTorch device, for example cpu or cuda:0.",
    )
    return parser.parse_args()


# EdgeSAM import fixes

def _apply_edgesam_import_stubs():
    """Add a few fake modules so EdgeSAM can be imported on newer PyTorch."""
    if "mmengine.optim.optimizer.zero_optimizer" not in sys.modules:
        _zo = types.ModuleType("mmengine.optim.optimizer.zero_optimizer")
        class _ZROStub:
            def __init__(self, *a, **kw): pass
        _zo.ZeroRedundancyOptimizer = _ZROStub
        sys.modules["mmengine.optim.optimizer.zero_optimizer"] = _zo

    if "transformers" not in sys.modules:
        _tf = types.ModuleType("transformers")
        _tf.Adafactor = None
        sys.modules["transformers"] = _tf

    if "projects" not in sys.modules:
        _proj = types.ModuleType("projects")
        _proj.__path__ = []
        _proj.__file__ = "<projects_stub>"
        sys.modules["projects"] = _proj
    if "projects.EfficientDet" not in sys.modules:
        _ed = types.ModuleType("projects.EfficientDet")
        _ed.__file__ = "<efficientdet_stub>"
        _ed.efficientdet = None
        sys.modules["projects.EfficientDet"] = _ed

    class _ExtStub(types.ModuleType):
        def __init__(self, name):
            super().__init__(name)
            self.__file__ = "<mmcv_ext_stub>"
        def __getattr__(self, name):
            def _noop(*a, **kw):
                raise RuntimeError(
                    f"mmcv C++ op '{name}' called during ONNX export — this is a bug."
                )
            return _noop

    if "mmdet" not in sys.modules:
        _mmdet = types.ModuleType("mmdet")
        _mmdet.__path__ = []
        _mmdet.__file__ = "<mmdet_stub>"
        sys.modules["mmdet"] = _mmdet

    if "mmdet.models" not in sys.modules:
        _mmdet_models = types.ModuleType("mmdet.models")
        _mmdet_models.__path__ = []
        _mmdet_models.__file__ = "<mmdet_models_stub>"
        sys.modules["mmdet.models"] = _mmdet_models

    class _UnusedMmdetExportStub:
        def __init__(self, *a, **kw): pass
        def __call__(self, *a, **kw):
            raise RuntimeError("An unused mmdet RPN component was called during export.")
        def predict_by_feat(self, *a, **kw):
            raise RuntimeError("An unused mmdet RPN component was called during export.")

    if "mmdet.models.dense_heads" not in sys.modules:
        _dense_heads = types.ModuleType("mmdet.models.dense_heads")
        _dense_heads.__file__ = "<mmdet_dense_heads_stub>"
        _dense_heads.RPNHead = _UnusedMmdetExportStub
        _dense_heads.CenterNetUpdateHead = _UnusedMmdetExportStub
        sys.modules["mmdet.models.dense_heads"] = _dense_heads

    if "mmdet.models.necks" not in sys.modules:
        _necks = types.ModuleType("mmdet.models.necks")
        _necks.__file__ = "<mmdet_necks_stub>"
        _necks.FPN = _UnusedMmdetExportStub
        sys.modules["mmdet.models.necks"] = _necks

    sys.modules["mmdet"].models = sys.modules["mmdet.models"]
    sys.modules["mmdet.models"].dense_heads = sys.modules["mmdet.models.dense_heads"]
    sys.modules["mmdet.models"].necks = sys.modules["mmdet.models.necks"]

    if "mmcv._ext" not in sys.modules:
        _ext = _ExtStub("mmcv._ext")
        sys.modules["mmcv._ext"] = _ext
        try:
            import mmcv as _mmcv_pkg
            _mmcv_pkg._ext = _ext
        except ImportError:
            pass

    try:
        import mmcv
        from packaging.version import Version
        if Version(mmcv.__version__) >= Version("2.2.0"):
            mmcv.__version__ = "2.1.0"
    except ImportError:
        pass


# Load model

def load_model(model_name: str, checkpoint: str, device: str, src_dir: str = None):
    if not Path(checkpoint).exists():
        sys.exit(f"ERROR: checkpoint not found: {checkpoint}")

    if src_dir is not None:
        src_path = str(Path(src_dir).resolve())
        if src_path not in sys.path:
            sys.path.insert(0, src_path)
            print(f"Using source folder: {src_path}")

    if model_name == "mobilesam":
        try:
            from mobile_sam import sam_model_registry
        except ImportError as e:
            sys.exit(
                f"ERROR: MobileSAM import failed: {e}\n"
                "  Either pip install git+https://github.com/ChaoningZhang/MobileSAM.git\n"
                "  or clone it locally and pass --src-dir <path/to/MobileSAM>"
            )
        sam = sam_model_registry["vit_t"](checkpoint=checkpoint)

    else:  # edgesam
        _apply_edgesam_import_stubs()
        try:
            from edge_sam import sam_model_registry
        except ImportError as e:
            sys.exit(
                f"ERROR: EdgeSAM import failed: {e}\n"
                "  Either pip install git+https://github.com/chongzhou96/EdgeSAM.git\n"
                "  or clone it locally and pass --src-dir <path/to/EdgeSAM>"
            )
        sam = sam_model_registry["edge_sam"](checkpoint=checkpoint)

    sam.to(device).eval()
    return sam


# Small prompt encoder patch

def patch_prompt_encoder_for_onnx(sam):
    """Replace _embed_points with a version that exports to ONNX more safely."""
    def _embed_points_onnx_safe(self, points, labels, pad):
        points = points + 0.5
        if pad:
            padding_point = torch.zeros((points.shape[0], 1, 2), device=points.device)
            padding_label = -torch.ones((labels.shape[0], 1), device=labels.device)
            points = torch.cat([points, padding_point], dim=1)
            labels = torch.cat([labels, padding_label], dim=1)

        point_embedding = self.pe_layer.forward_with_coords(points, self.input_image_size)

        neg1  = (labels == -1).unsqueeze(-1)
        zero  = (labels ==  0).unsqueeze(-1)
        one   = (labels ==  1).unsqueeze(-1)
        zeros = torch.zeros_like(point_embedding)

        point_embedding = torch.where(neg1, zeros, point_embedding)
        point_embedding = point_embedding + torch.where(neg1, self.not_a_point_embed.weight, zeros)
        point_embedding = point_embedding + torch.where(zero, self.point_embeddings[0].weight, zeros)
        point_embedding = point_embedding + torch.where(one,  self.point_embeddings[1].weight, zeros)
        return point_embedding

    sam.prompt_encoder._embed_points = types.MethodType(
        _embed_points_onnx_safe, sam.prompt_encoder
    )


# Wrappers used for export

class EncoderWrapper(nn.Module):
    def __init__(self, sam):
        super().__init__()
        self.encoder = sam.image_encoder

    def forward(self, image: torch.Tensor) -> torch.Tensor:
        return self.encoder(image)


class MobileSAMDecoderWrapper(nn.Module):
    """Wrapper for the MobileSAM decoder."""

    def __init__(self, sam, image_size: int):
        super().__init__()
        self.sam = sam
        self.image_size = image_size

    def forward(self, point_coords, point_labels, image_embedding, has_mask_input, mask_input):
        gated_mask = mask_input * has_mask_input.view(1, 1, 1, 1)
        sparse_emb, dense_emb = self.sam.prompt_encoder(
            points=(point_coords, point_labels.long()), boxes=None, masks=gated_mask,
        )
        low_res_masks, iou_pred = self.sam.mask_decoder(
            image_embeddings=image_embedding,
            image_pe=self.sam.prompt_encoder.get_dense_pe(),
            sparse_prompt_embeddings=sparse_emb,
            dense_prompt_embeddings=dense_emb,
            multimask_output=True,
        )
        masks = self.sam.postprocess_masks(
            low_res_masks,
            input_size=(self.image_size, self.image_size),
            original_size=(self.image_size, self.image_size),
        )
        return masks, iou_pred, low_res_masks


class EdgeSAMDecoderWrapper(nn.Module):
    """EdgeSAM prompt encoder + mask decoder (num_multimask_outputs=3 → 3 masks)."""

    def __init__(self, sam, image_size: int):
        super().__init__()
        self.sam = sam
        self.image_size = image_size

    def forward(self, point_coords, point_labels, image_embedding, has_mask_input, mask_input):
        gated_mask = mask_input * has_mask_input.view(1, 1, 1, 1)
        sparse_emb, dense_emb = self.sam.prompt_encoder(
            points=(point_coords, point_labels.long()), boxes=None, masks=gated_mask,
        )
        low_res_masks, iou_pred = self.sam.mask_decoder(
            image_embeddings=image_embedding,
            image_pe=self.sam.prompt_encoder.get_dense_pe(),
            sparse_prompt_embeddings=sparse_emb,
            dense_prompt_embeddings=dense_emb,
            num_multimask_outputs=3,
        )
        masks = self.sam.postprocess_masks(
            low_res_masks,
            input_size=(self.image_size, self.image_size),
            original_size=(self.image_size, self.image_size),
        )
        return masks, iou_pred, low_res_masks


def _get_decoder_wrapper(model_name: str, sam, image_size: int) -> nn.Module:
    if model_name == "mobilesam":
        return MobileSAMDecoderWrapper(sam, image_size)
    return EdgeSAMDecoderWrapper(sam, image_size)


# Small EdgeSAM tracing patch

class _RepeatInterleaveCtx:
    """Temporarily replace repeat_interleave during EdgeSAM decoder export."""
    def __enter__(self):
        self._orig = torch.repeat_interleave

        def _expand_repeat(input, repeats, dim=None, output_size=None):
            if dim is not None and isinstance(repeats, int):
                target = list(input.shape)
                target[dim] *= repeats
                return input.expand(*target)
            return self._orig(input, repeats, dim=dim, output_size=output_size)

        torch.repeat_interleave = _expand_repeat
        return self

    def __exit__(self, *_):
        torch.repeat_interleave = self._orig


# Helpers for ONNX export

def _onnx_export_encoder(wrapper, image_size, opset, out_path, device):
    dummy = torch.zeros(1, 3, image_size, image_size, device=device)
    with torch.no_grad():
        torch.onnx.export(
            wrapper, dummy, out_path,
            opset_version=opset,
            input_names=["image"],
            output_names=["image_embedding"],
            dynamic_axes={},
            do_constant_folding=True,
            dynamo=False,
        )
    print(f"Saved encoder: {out_path}")


def _onnx_export_decoder(wrapper, image_size, num_points, opset, out_path, device,
                         use_repeat_patch: bool = False):
    E = image_size // 16
    M = image_size // 4
    dummies = (
        torch.zeros(1, num_points, 2, device=device),
        torch.zeros(1, num_points, device=device),
        torch.zeros(1, 256, E, E, device=device),
        torch.zeros(1, device=device),
        torch.zeros(1, 1, M, M, device=device),
    )
    ctx = _RepeatInterleaveCtx() if use_repeat_patch else nullcontext()
    with ctx, torch.no_grad():
        torch.onnx.export(
            wrapper, dummies, out_path,
            opset_version=opset,
            input_names=["point_coords", "point_labels", "image_embedding",
                         "has_mask_input", "mask_input"],
            output_names=["masks", "iou_predictions", "low_res_masks"],
            dynamic_axes={},
            do_constant_folding=True,
            dynamo=False,
        )
    print(f"Saved decoder: {out_path}")


# Main export functions

def export_encoder(model_name, sam, image_size, opset, out_path, device):
    wrapper = EncoderWrapper(sam)
    wrapper.eval()
    _onnx_export_encoder(wrapper, image_size, opset, out_path, device)
    return out_path


def export_decoder(model_name, sam, image_size, num_points, opset, out_path, device):
    wrapper = _get_decoder_wrapper(model_name, sam, image_size)
    wrapper.eval()
    _onnx_export_decoder(wrapper, image_size, num_points, opset, out_path, device,
                         use_repeat_patch=(model_name == "edgesam"))
    return out_path


def export_encoder_fp16(model_name, sam, image_size, opset, out_path, device):
    """Export encoder with fp16 weights and float32 input/output."""
    class _FP16Enc(nn.Module):
        def __init__(self, enc):
            super().__init__()
            self.enc = enc
        def forward(self, x):
            return self.enc(x.half()).float()

    enc_fp16 = copy.deepcopy(sam.image_encoder).half().to(device)
    wrapper = _FP16Enc(enc_fp16)
    wrapper.eval()
    _onnx_export_encoder(wrapper, image_size, opset, out_path, device)
    return out_path


def export_decoder_fp16(model_name, sam, image_size, num_points, opset, out_path, device):
    """Export decoder with fp16 weights and float32 input/output."""
    sam_fp16 = copy.deepcopy(sam)
    sam_fp16.mask_decoder.half()
    sam_fp16.to(device)
    patch_prompt_encoder_for_onnx(sam_fp16)

    decoder_kwargs = (
        {"multimask_output": True} if model_name == "mobilesam"
        else {"num_multimask_outputs": 3}
    )

    class _FP16Dec(nn.Module):
        def __init__(self, s, sz, dec_kw):
            super().__init__()
            self.sam = s
            self.sz = sz
            self.dec_kw = dec_kw

        def forward(self, point_coords, point_labels, image_embedding,
                    has_mask_input, mask_input):
            gated = mask_input * has_mask_input.view(1, 1, 1, 1)
            sparse_emb, dense_emb = self.sam.prompt_encoder(
                points=(point_coords, point_labels.long()), boxes=None, masks=gated)
            low_res, iou = self.sam.mask_decoder(
                image_embeddings=image_embedding.half(),
                image_pe=self.sam.prompt_encoder.get_dense_pe().half(),
                sparse_prompt_embeddings=sparse_emb.half(),
                dense_prompt_embeddings=dense_emb.half(),
                **self.dec_kw,
            )
            masks = self.sam.postprocess_masks(
                low_res.float(), (self.sz, self.sz), (self.sz, self.sz))
            return masks, iou.float(), low_res.float()

    wrapper = _FP16Dec(sam_fp16, image_size, decoder_kwargs)
    wrapper.eval()
    _onnx_export_decoder(wrapper, image_size, num_points, opset, out_path, device,
                         use_repeat_patch=(model_name == "edgesam"))
    return out_path


# ONNX utils

def simplify_onnx(onnx_path: str) -> str:
    try:
        import onnx
        from onnxsim import simplify

        model = onnx.load(onnx_path)
        original_bytes = model.SerializeToString()
        model_simplified, ok = simplify(model)

        if not ok:
            print(f"Simplify check failed for {os.path.basename(onnx_path)}, keeping original file.")
            return onnx_path

        onnx.save(model_simplified, onnx_path)
        try:
            import onnxruntime as ort
            ort.InferenceSession(onnx_path, providers=["CPUExecutionProvider"])
            print(f"Simplified: {onnx_path}")
        except Exception as e:
            print(f"Simplified model did not run in ONNX Runtime ({e}), restoring original file.")
            with open(onnx_path, "wb") as f:
                f.write(original_bytes)

        return onnx_path
    except ImportError:
        print("onnxsim is not installed, skipping simplify step.")
        return onnx_path


def validate_onnx(onnx_path: str):
    import onnx
    onnx.checker.check_model(onnx.load(onnx_path))
    print("ONNX check passed.")


def run_ort_smoketest(onnx_path: str):
    try:
        import onnxruntime as ort
    except ImportError:
        print("onnxruntime is not installed, skipping test run.")
        return

    sess = ort.InferenceSession(onnx_path, providers=["CPUExecutionProvider"])
    inputs = {inp.name: np.zeros(inp.shape, dtype=np.float32) for inp in sess.get_inputs()}
    outputs = sess.run(None, inputs)
    print("ONNX Runtime test passed.")
    print(f"Inputs: {[i.name for i in sess.get_inputs()]}")
    print(f"Outputs: {list(zip([o.name for o in sess.get_outputs()], [o.shape for o in outputs]))}")


def patch_resize_cubic_to_linear(onnx_path: str) -> None:
    """Change cubic Resize to linear so the exported model works better."""
    import onnx
    model = onnx.load(onnx_path)
    patched = 0
    for node in model.graph.node:
        if node.op_type == "Resize":
            for attr in node.attribute:
                if attr.name == "mode" and attr.s == b"cubic":
                    attr.s = b"linear"
                    patched += 1
    if patched:
        onnx.save(model, onnx_path)
        print(f"Changed {patched} Resize node(s) from cubic to linear in {os.path.basename(onnx_path)}")


# Calibration helpers

def _collect_image_paths(image_dir: str, n_samples: int) -> list:
    exts = {".jpg", ".jpeg", ".png", ".bmp", ".webp"}
    paths = sorted(p for p in Path(image_dir).rglob("*") if p.suffix.lower() in exts)
    if not paths:
        raise ValueError(f"No images found in {image_dir}")
    return paths[:n_samples]


def _preprocess(path, image_size: int) -> np.ndarray:
    try:
        from PIL import Image
    except ImportError:
        raise ImportError("Pillow is required for calibration: pip install pillow")
    img = Image.open(path).convert("RGB").resize((image_size, image_size))
    arr = np.array(img, dtype=np.float32) / 255.0
    return arr.transpose(2, 0, 1)[np.newaxis]


def build_encoder_calibration_reader(image_dir: str, image_size: int, n_samples: int):
    from onnxruntime.quantization import CalibrationDataReader
    paths = _collect_image_paths(image_dir, n_samples)
    print(f"Found {len(paths)} calibration images for encoder.")

    class _Reader(CalibrationDataReader):
        def __init__(self):
            self._paths, self._idx = paths, 0
        def get_next(self):
            if self._idx >= len(self._paths):
                return None
            img = _preprocess(self._paths[self._idx], image_size)
            self._idx += 1
            return {"image": img}

    return _Reader()


def build_decoder_calibration_reader(
    encoder_onnx_path: str, image_dir: str, image_size: int,
    n_samples: int, num_points: int,
):
    import onnxruntime as ort
    from onnxruntime.quantization import CalibrationDataReader

    paths = _collect_image_paths(image_dir, n_samples)
    print(f"Found {len(paths)} calibration images for decoder.")
    enc_sess = ort.InferenceSession(encoder_onnx_path, providers=["CPUExecutionProvider"])
    M = image_size // 4

    class _Reader(CalibrationDataReader):
        def __init__(self):
            self._idx = 0
        def get_next(self):
            if self._idx >= len(paths):
                return None
            img = _preprocess(paths[self._idx], image_size)
            embedding = enc_sess.run(None, {"image": img})[0]
            cx = cy = image_size / 2.0
            coords = np.zeros((1, num_points, 2), dtype=np.float32)
            labels = np.full((1, num_points), -1, dtype=np.float32)
            coords[0, 0] = [cx, cy]
            labels[0, 0] = 1.0
            self._idx += 1
            return {
                "point_coords":    coords,
                "point_labels":    labels,
                "image_embedding": embedding,
                "has_mask_input":  np.zeros([1], dtype=np.float32),
                "mask_input":      np.zeros([1, 1, M, M], dtype=np.float32),
            }

    return _Reader()


# Quantization

def quantize_onnx(
    onnx_path: str, method: str, apply_resize_patch: bool, calibration_reader=None,
) -> str:
    if method == "none":
        return onnx_path

    stem = Path(onnx_path).stem
    q_path = str(Path(onnx_path).parent / f"{stem}_int8.onnx")

    if method == "dynamic_int8":
        try:
            from onnxruntime.quantization import quantize_dynamic, QuantType
        except ImportError:
            print("onnxruntime quantization is not available, skipping quantization.")
            return onnx_path
        try:
            quantize_dynamic(model_input=onnx_path, model_output=q_path,
                             weight_type=QuantType.QInt8)
        except Exception as e:
            print(f"Dynamic quantization failed: {e}")
            return onnx_path
        if apply_resize_patch:
            patch_resize_cubic_to_linear(q_path)

    elif method == "static_int8":
        if calibration_reader is None:
            print("Static int8 needs --calibration-dataset.")
            return onnx_path
        try:
            from onnxruntime.quantization import (
                quantize_static, CalibrationMethod, QuantType, QuantFormat,
            )
        except ImportError:
            print("onnxruntime quantization is not available, skipping quantization.")
            return onnx_path

        preprocessed = str(Path(onnx_path).parent / f"{stem}_preprocessed.onnx")
        src = onnx_path
        try:
            from onnxruntime.quantization.shape_inference import quant_pre_process
            quant_pre_process(onnx_path, preprocessed, skip_symbolic_shape=False)
            src = preprocessed
            print(f"Preprocess done: {preprocessed}")
        except Exception as e:
            print(f"Preprocess skipped: {e}")

        try:
            quantize_static(
                model_input=src,
                model_output=q_path,
                calibration_data_reader=calibration_reader,
                weight_type=QuantType.QInt8,
                activation_type=QuantType.QUInt8,
                quant_format=QuantFormat.QDQ,
                calibrate_method=CalibrationMethod.MinMax,
                per_channel=False,
            )
        except Exception as e:
            print(f"Static quantization failed: {e}")
            return onnx_path
        finally:
            if src != onnx_path and Path(preprocessed).exists():
                Path(preprocessed).unlink()

        if apply_resize_patch:
            patch_resize_cubic_to_linear(q_path)

    orig_mb = Path(onnx_path).stat().st_size / 1_048_576
    q_mb    = Path(q_path).stat().st_size / 1_048_576
    print(f"Saved {method}: {q_path}")
    print(f"Size: {orig_mb:.1f} MB -> {q_mb:.1f} MB ({(1 - q_mb / orig_mb) * 100:.0f}% smaller)")
    return q_path


# Export pipeline

def _process_encoder(args, sam, prefix, out_dir):
    enc_path = str(out_dir / f"{prefix}_encoder.onnx")

    export_encoder(args.model, sam, args.image_size, args.opset, enc_path, args.device)
    validate_onnx(enc_path)
    if args.simplify:
        simplify_onnx(enc_path)
        validate_onnx(enc_path)
    run_ort_smoketest(enc_path)

    if args.quantize == "fp16":
        enc_q = str(out_dir / f"{prefix}_encoder_fp16.onnx")
        export_encoder_fp16(args.model, sam, args.image_size, args.opset, enc_q, args.device)
        validate_onnx(enc_q)
        if args.simplify:
            simplify_onnx(enc_q)
            validate_onnx(enc_q)
        _print_size_reduction(enc_path, enc_q, "fp16")
        run_ort_smoketest(enc_q)

    elif args.quantize in ("dynamic_int8", "static_int8"):
        calib = None
        if args.quantize == "static_int8":
            calib = build_encoder_calibration_reader(
                args.calibration_dataset, args.image_size, args.calibration_size)
        enc_q = quantize_onnx(enc_path, args.quantize,
                               apply_resize_patch=(args.model == "edgesam"),
                               calibration_reader=calib)
        if enc_q != enc_path:
            run_ort_smoketest(enc_q)


def _process_decoder(args, sam, prefix, out_dir):
    dec_path = str(out_dir / f"{prefix}_decoder.onnx")

    patch_prompt_encoder_for_onnx(sam)
    export_decoder(args.model, sam, args.image_size, args.num_points,
                   args.opset, dec_path, args.device)
    validate_onnx(dec_path)
    if args.simplify:
        simplify_onnx(dec_path)
        validate_onnx(dec_path)
    run_ort_smoketest(dec_path)

    if args.quantize == "fp16":
        dec_q = str(out_dir / f"{prefix}_decoder_fp16.onnx")
        export_decoder_fp16(args.model, sam, args.image_size, args.num_points,
                            args.opset, dec_q, args.device)
        validate_onnx(dec_q)
        if args.simplify:
            simplify_onnx(dec_q)
            validate_onnx(dec_q)
        _print_size_reduction(dec_path, dec_q, "fp16")
        run_ort_smoketest(dec_q)

    elif args.quantize in ("dynamic_int8", "static_int8"):
        calib = None
        if args.quantize == "static_int8":
            enc_for_calib = str(out_dir / f"{prefix}_encoder.onnx")
            if not Path(enc_for_calib).exists():
                print("Encoder ONNX file was not found, so decoder calibration could not be created.")
            else:
                calib = build_decoder_calibration_reader(
                    enc_for_calib, args.calibration_dataset,
                    args.image_size, args.calibration_size, args.num_points)
        dec_q = quantize_onnx(dec_path, args.quantize,
                               apply_resize_patch=(args.model == "edgesam"),
                               calibration_reader=calib)
        if dec_q != dec_path:
            run_ort_smoketest(dec_q)


def _print_size_reduction(fp32_path: str, q_path: str, label: str):
    fp32_mb = Path(fp32_path).stat().st_size / 1_048_576
    q_mb    = Path(q_path).stat().st_size / 1_048_576
    print(f"Saved {label}: {q_path}")
    print(f"Size: {fp32_mb:.1f} MB -> {q_mb:.1f} MB ({(1 - q_mb / fp32_mb) * 100:.0f}% smaller)")


# Print summary

def _print_summary(args, out_dir: Path):
    quant_suffixes = ("_int8", "_fp16")
    num_masks = 4 if args.model == "mobilesam" else 3
    E = args.image_size // 16
    M = args.image_size // 4

    print("\n" + "=" * 62)
    print("Files:")
    print(f"  {'File':<44} {'Size':>8}   {'vs fp32':>8}")
    print(f"  {'-'*44} {'-'*8}   {'-'*8}")

    baselines: dict[str, float] = {}
    for f in sorted(out_dir.glob("*.onnx")):
        if not any(s in f.name for s in quant_suffixes):
            baselines[f.stem] = f.stat().st_size / 1_048_576

    for f in sorted(out_dir.glob("*.onnx")):
            size_mb = f.stat().st_size / 1_048_576
            base_key = f.stem
            for s in quant_suffixes:
                base_key = base_key.replace(s, "")
            base_mb  = baselines.get(base_key)
            is_quant = any(s in f.name for s in quant_suffixes)
            note = f"-{(1 - size_mb / base_mb) * 100:.0f}%" if (base_mb and is_quant) else "-"
            print(f"  {f.name:<44} {size_mb:>6.1f} MB   {note:>9}")

    print()
    print("Tensor shapes:")
    rows = [
        ("ENCODER", "", ""),
        ("  image",            f"(1, 3, {args.image_size}, {args.image_size})", "float32  values in [0,1]"),
        ("  image_embedding",  f"(1, 256, {E}, {E})",                           "float32"),
        ("DECODER", "", ""),
        ("  point_coords",     f"(1, {args.num_points}, 2)",                    "float32  pixel coords x,y"),
        ("  point_labels",     f"(1, {args.num_points})",                       "float32  1=fg 0=bg -1=pad"),
        ("  image_embedding",  f"(1, 256, {E}, {E})",                           "float32  from encoder"),
        ("  has_mask_input",   "(1,)",                                           "float32  0 or 1"),
        ("  mask_input",       f"(1, 1, {M}, {M})",                             "float32  prev low_res_masks or zeros"),
        ("  → masks",          f"(1, {num_masks}, {args.image_size}, {args.image_size})", "float32  logits → sigmoid for prob"),
        ("  → iou_predictions",f"(1, {num_masks})",                             "float32  predicted quality"),
        ("  → low_res_masks",  f"(1, {num_masks}, {M}, {M})",                   "float32  feed back as mask_input"),
    ]
    for name, shape, note in rows:
        if not shape:
            print(f"  {name}")
        else:
            print(f"  {name:<22} {shape:<36} {note}")
    print()


# Main

def main():
    args = parse_args()
    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    prefix = args.model

    if args.quantize == "static_int8" and not args.calibration_dataset:
        sys.exit("ERROR: --calibration-dataset is required when --quantize static_int8")
    if args.calibration_dataset and not Path(args.calibration_dataset).is_dir():
        sys.exit(f"ERROR: --calibration-dataset path not found: {args.calibration_dataset}")

    print("\n" + "=" * 62)
    print(f"{args.model.upper()} to ONNX")
    print(f"Checkpoint: {args.checkpoint}")
    print(f"Image size: {args.image_size}")
    print(f"Num points: {args.num_points}")
    print(f"Opset: {args.opset}")
    print(f"Simplify: {args.simplify}")
    print(f"Quantize: {args.quantize}")
    if args.quantize == "static_int8":
        print(f"Calibration dataset: {args.calibration_dataset}")
        print(f"Calibration size: {args.calibration_size}")
    if args.src_dir:
        print(f"Source dir: {args.src_dir}")
    print(f"Device: {args.device}")
    print(f"Output dir: {out_dir}")
    print("=" * 62 + "\n")

    sam = load_model(args.model, args.checkpoint, args.device, src_dir=args.src_dir)
    print("Model loaded.\n")

    print("Encoder")
    _process_encoder(args, sam, prefix, out_dir)
    print()

    print("Decoder")
    _process_decoder(args, sam, prefix, out_dir)
    print()

    _print_summary(args, out_dir)


if __name__ == "__main__":
    main()
