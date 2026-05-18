#!/usr/bin/env python3
"""
@author Bc. Lenka Sokova

Script for testing an EfficientDet TFLite model on a COCO-style dataset.

Examples:
  python detection/evaluate_efficientdet_tflite_coco.py \
      --model efficientdet_lite0.tflite \
      --images data/val2017 \
      --annotations data/annotations_trainval2017/annotations/instances_val2017.json
"""

from __future__ import annotations

import argparse
import json
import re
import time
from pathlib import Path
from typing import Any

import numpy as np
from PIL import Image

SCRIPT_DIR = Path(__file__).resolve().parent
DEFAULT_DATA_ROOT = (SCRIPT_DIR / "../data").resolve()
DEFAULT_ANNOTATIONS = (
    DEFAULT_DATA_ROOT / "annotations_trainval2017" / "annotations" / "instances_val2017.json"
)
DEFAULT_IMAGES = DEFAULT_DATA_ROOT / "val2017"

COCO_NAME_ALIASES = {
    "airplane": "aeroplane",
    "motorcycle": "motorbike",
    "couch": "sofa",
    "potted plant": "pottedplant",
    "dining table": "diningtable",
    "tv": "tvmonitor",
    "cell phone": "mobile phone",
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Test a TFLite EfficientDet model on a COCO-style split."
    )
    parser.add_argument(
        "--model",
        type=Path,
        required=True,
        help="Path to the .tflite EfficientDet model.",
    )
    parser.add_argument(
        "--images",
        type=Path,
        default=DEFAULT_IMAGES,
        help=(
            "Folder with split images, for example val2017 or test2017. "
            f"Defaults to {DEFAULT_IMAGES}."
        ),
    )
    parser.add_argument(
        "--annotations",
        type=Path,
        default=DEFAULT_ANNOTATIONS,
        help=(
            "COCO annotation JSON or image_info JSON for the split. "
            f"Defaults to {DEFAULT_ANNOTATIONS}."
        ),
    )
    parser.add_argument(
        "--output-json",
        type=Path,
        default=None,
        help="Where to save predictions JSON. Defaults next to the model.",
    )
    parser.add_argument(
        "--max-images",
        type=int,
        default=0,
        help="Limit how many images are used. 0 means all images.",
    )
    parser.add_argument(
        "--score-threshold",
        type=float,
        default=0.01,
        help="Skip detections below this score.",
    )
    parser.add_argument(
        "--top-k",
        type=int,
        default=100,
        help="Maximum number of detections per image.",
    )
    parser.add_argument(
        "--no-eval",
        action="store_true",
        help="Only save predictions JSON and skip COCOeval.",
    )
    return parser.parse_args()


def load_split(annotation_path: Path) -> list[dict[str, Any]]:
    with annotation_path.open("r", encoding="utf-8") as f:
        payload = json.load(f)
    images = payload.get("images")
    if not isinstance(images, list):
        raise ValueError(f"No images list found in {annotation_path}")
    return images


def infer_split_name(annotation_path: Path) -> str | None:
    match = re.search(r"(?:instances|image_info|captions|person_keypoints)_(.+)$", annotation_path.stem)
    if match:
        return match.group(1)
    return None


def resolve_annotation_path(annotation_arg: Path) -> Path:
    if annotation_arg.is_file():
        return annotation_arg

    possible_paths = [
        annotation_arg / "instances_val2017.json",
        annotation_arg / "annotations" / "instances_val2017.json",
        annotation_arg / "annotations_trainval2017" / "annotations" / "instances_val2017.json",
    ]
    for path in possible_paths:
        if path.is_file():
            return path

    found_files = sorted(annotation_arg.rglob("instances_*.json")) if annotation_arg.is_dir() else []
    if len(found_files) == 1:
        return found_files[0]

    raise SystemExit(
        f"Could not find COCO annotations from {annotation_arg}. "
        "Use a JSON file or a folder with instances_val2017.json."
    )


def is_image_dir(path: Path) -> bool:
    if not path.is_dir():
        return False
    return any(path.glob("*.jpg")) or any(path.glob("*.jpeg")) or any(path.glob("*.png"))


def resolve_image_dir(images_arg: Path, annotation_path: Path) -> Path:
    if is_image_dir(images_arg):
        return images_arg

    split_name = infer_split_name(annotation_path) or "val2017"
    possible_dirs = [
        images_arg / split_name,
        images_arg / "images" / split_name,
        images_arg / "data" / split_name,
    ]
    for path in possible_dirs:
        if is_image_dir(path):
            return path

    raise SystemExit(
        f"Could not find image folder from {images_arg}. "
        f"Use an image folder or a parent folder containing {split_name}."
    )


def resolve_image_path(images_dir: Path, image_item: dict[str, Any]) -> Path:
    image_name = Path(str(image_item["file_name"])).name
    return images_dir / image_name


def load_category_mappings(annotation_path: Path) -> tuple[dict[str, int], dict[int, str]]:
    with annotation_path.open("r", encoding="utf-8") as f:
        payload = json.load(f)
    categories = payload.get("categories", [])
    name_to_id: dict[str, int] = {}
    id_to_name: dict[int, str] = {}
    for category in categories:
        category_id = int(category["id"])
        name = str(category["name"]).strip().lower()
        id_to_name[category_id] = name
        name_to_id[name] = category_id
    return name_to_id, id_to_name


def normalize_label(label: str) -> str:
    normalized = label.strip().lower()
    return COCO_NAME_ALIASES.get(normalized, normalized)


def load_detector(model_path: Path, score_threshold: float, top_k: int) -> Any:
    try:
        from mediapipe.tasks.python import BaseOptions  # type: ignore
        from mediapipe.tasks.python import vision  # type: ignore
    except ImportError as exc:
        raise SystemExit(
            "MediaPipe is needed for this script.\n"
            "Install it with:\n"
            "  pip install mediapipe"
        ) from exc

    options = vision.ObjectDetectorOptions(
        base_options=BaseOptions(model_asset_path=str(model_path)),
        score_threshold=score_threshold,
        max_results=top_k,
    )
    return vision.ObjectDetector.create_from_options(options)


def run_detector(
    detector: Any,
    image_path: Path,
    image_id: int,
    score_threshold: float,
    label_to_category_id: dict[str, int],
) -> tuple[list[dict[str, Any]], float]:
    try:
        import mediapipe as mp  # type: ignore
    except ImportError as exc:
        raise SystemExit(
            "MediaPipe is needed for this script.\n"
            "Install it with:\n"
            "  pip install mediapipe"
        ) from exc

    # Some images may load as grayscale, so convert them to RGB first.
    rgb_image = Image.open(image_path).convert("RGB")
    mp_image = mp.Image(
        image_format=mp.ImageFormat.SRGB,
        data=np.asarray(rgb_image),
    )
    width = mp_image.width
    height = mp_image.height

    started = time.perf_counter()
    result = detector.detect(mp_image)
    latency_ms = (time.perf_counter() - started) * 1000.0

    predictions: list[dict[str, Any]] = []
    for detection in result.detections:
        categories = detection.categories
        if not categories:
            continue
        top_category = max(categories, key=lambda category: float(category.score))
        score = float(top_category.score)
        if score < score_threshold:
            continue

        label = normalize_label(
            top_category.category_name or top_category.display_name or ""
        )
        category_id = label_to_category_id.get(label)
        if category_id is None:
            continue

        box = detection.bounding_box
        x = max(0.0, min(float(width), float(box.origin_x)))
        y = max(0.0, min(float(height), float(box.origin_y)))
        w = max(0.0, min(float(width), float(box.origin_x + box.width)) - x)
        h = max(0.0, min(float(height), float(box.origin_y + box.height)) - y)
        if w <= 0.0 or h <= 0.0:
            continue

        predictions.append(
            {
                "image_id": image_id,
                "category_id": category_id,
                "bbox": [round(x, 3), round(y, 3), round(w, 3), round(h, 3)],
                "score": round(score, 6),
            }
        )

    return predictions, latency_ms


def evaluate_with_pycocotools(annotation_path: Path, predictions_path: Path) -> None:
    try:
        from pycocotools.coco import COCO  # type: ignore
        from pycocotools.cocoeval import COCOeval  # type: ignore
    except ImportError as exc:
        raise SystemExit(
            "pycocotools is needed for COCO evaluation.\n"
            "Install it with:\n"
            "  pip install pycocotools"
        ) from exc

    coco_gt = COCO(str(annotation_path))
    if "annotations" not in coco_gt.dataset:
        raise SystemExit(
            "This annotation file has no ground-truth boxes, so COCO evaluation cannot run. "
            "Predictions were still saved."
        )

    with predictions_path.open("r", encoding="utf-8") as f:
        detections = json.load(f)
    if not detections:
        raise SystemExit("No detections were produced, so COCOeval cannot run.")

    coco_dt = coco_gt.loadRes(str(predictions_path))
    evaluator = COCOeval(coco_gt, coco_dt, "bbox")
    evaluator.evaluate()
    evaluator.accumulate()
    evaluator.summarize()


def main() -> None:
    args = parse_args()

    if not args.model.is_file():
        raise SystemExit(f"Model not found: {args.model}")

    annotation_path = resolve_annotation_path(args.annotations)
    images_dir = resolve_image_dir(args.images, annotation_path)

    output_json = args.output_json
    if output_json is None:
        output_json = args.model.with_name(f"{args.model.stem}_coco_predictions.json")

    split = load_split(annotation_path)
    if args.max_images > 0:
        split = split[:args.max_images]

    label_to_category_id, _ = load_category_mappings(annotation_path)
    detector = load_detector(args.model, args.score_threshold, args.top_k)
    all_predictions: list[dict[str, Any]] = []
    latencies_ms: list[float] = []
    processed = 0

    try:
        for item in split:
            image_id = item["id"]
            image_path = resolve_image_path(images_dir, item)
            if not image_path.is_file():
                print(f"Skipping missing image: {image_path}")
                continue

            predictions, latency_ms = run_detector(
                detector=detector,
                image_path=image_path,
                image_id=image_id,
                score_threshold=args.score_threshold,
                label_to_category_id=label_to_category_id,
            )
            all_predictions.extend(predictions)
            latencies_ms.append(latency_ms)
            processed += 1

            if processed % 100 == 0:
                print(
                    f"Processed {processed}/{len(split)} images. "
                    f"Detections so far: {len(all_predictions)}"
                )
    finally:
        try:
            detector.close()
        except Exception as exc:
            print(f"Could not close detector: {exc}")

    output_json.parent.mkdir(parents=True, exist_ok=True)
    with output_json.open("w", encoding="utf-8") as f:
        json.dump(all_predictions, f)

    avg_latency = float(np.mean(latencies_ms)) if latencies_ms else 0.0
    print()
    print("Done.")
    print(f"Processed images: {processed}")
    print(f"Detections: {len(all_predictions)}")
    print(f"Average latency (ms): {avg_latency:.3f}")
    print(f"Images dir: {images_dir}")
    print(f"Annotations: {annotation_path}")
    print(f"Predictions JSON: {output_json}")

    if args.no_eval:
        return

    evaluate_with_pycocotools(annotation_path, output_json)


if __name__ == "__main__":
    main()
