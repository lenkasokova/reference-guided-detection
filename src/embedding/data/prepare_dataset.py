"""
@author Bc. Lenka Sokova

Prepare PlantNet300K with disjoint species in train, val, and test.

Each species is only in one split.
"""

import argparse
import io
import random
import shutil
from collections import Counter, defaultdict
from pathlib import Path

import numpy as np
import pandas as pd
from datasets import load_dataset
from PIL import Image
from tqdm import tqdm


# Config
OUTPUT_DIR = Path("plantnet")

# Split ratios
TRAIN_RATIO = 0.80
VAL_RATIO   = 0.10
TEST_RATIO  = 0.10

MIN_IMAGES_TOTAL = 50

MAX_TRAIN_IMAGES = 120  # cap per class in train/ (HF train split only)
MAX_VAL_IMAGES   = 60   # cap per class in val/  (all HF splits pooled)
MAX_TEST_IMAGES  = 60   # cap per class in test/ (all HF splits pooled)

TARGET_SIZE    = (224, 224)
DO_CENTER_CROP = False
JPEG_QUALITY   = 95
SEED           = 42

DEDUP_HASH_SIZE = 8   # 8×8 average hash (64 bits)
DEDUP_MAX_DIST  = 4   # max Hamming distance to treat as duplicate

def class_folder_name(label: int) -> str:
    return f"class_{int(label):04d}"


def preprocess_image(image: Image.Image) -> Image.Image:
    image = image.convert("RGB")
    if DO_CENTER_CROP:
        w, h = image.size
        side = min(w, h)
        left, top = (w - side) // 2, (h - side) // 2
        image = image.crop((left, top, left + side, top + side))
    return image.resize(TARGET_SIZE, Image.Resampling.LANCZOS)


def avg_hash(img: Image.Image) -> int:
    arr = np.array(
        img.convert("L").resize((DEDUP_HASH_SIZE, DEDUP_HASH_SIZE), Image.Resampling.LANCZOS),
        dtype=np.float32,
    )
    bits = (arr > arr.mean()).flatten()
    result = 0
    for b in bits:
        result = (result << 1) | int(b)
    return result


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Prepare PlantNet300K open-set splits.")
    parser.add_argument(
        "--no-dedup",
        action="store_true",
        default=False,
        help="Disable image deduplication (dedup is on by default).",
    )
    return parser.parse_args()


def main():
    args = parse_args()
    dedup = not args.no_dedup
    if dedup:
        print("Deduplication: on")
    else:
        print("Deduplication: off")

    rng = random.Random(SEED)

    if OUTPUT_DIR.exists():
        print(f"Removing old output folder: {OUTPUT_DIR}")
        shutil.rmtree(OUTPUT_DIR)
    OUTPUT_DIR.mkdir(parents=True)

    ds = load_dataset("mikehemberger/plantnet300K")
    hf_splits = {
        "train": ds["train"],
        "val":   ds["validation"],
        "test":  ds["test"],
    }

    # Pass 1: read labels only
    print("Pass 1: reading labels...")
    global_counts: Counter = Counter()
    species_indices: defaultdict[int, list] = defaultdict(list)

    for split_name, split_ds in hf_splits.items():
        label_ds = split_ds.select_columns(["label"])
        for idx, sample in enumerate(tqdm(label_ds, desc=f"  {split_name}")):
            lbl = sample["label"]
            global_counts[lbl] += 1
            species_indices[lbl].append((split_name, idx))

    eligible = [
        lbl for lbl, cnt in global_counts.most_common()
        if cnt >= MIN_IMAGES_TOTAL
    ]

    n_eligible = len(eligible)
    if n_eligible < 3:
        raise ValueError(
            f"Only {n_eligible} species passed the limit (MIN_IMAGES_TOTAL={MIN_IMAGES_TOTAL}). "
            "Try lowering MIN_IMAGES_TOTAL."
        )

    assert abs(TRAIN_RATIO + VAL_RATIO + TEST_RATIO - 1.0) < 1e-9, \
        "TRAIN_RATIO + VAL_RATIO + TEST_RATIO must equal 1.0"

    n_train = round(n_eligible * TRAIN_RATIO)
    n_val   = round(n_eligible * VAL_RATIO)
    n_test  = n_eligible - n_train - n_val

    train_species = set(eligible[:n_train])
    val_species   = set(eligible[n_train : n_train + n_val])
    test_species  = set(eligible[n_train + n_val :])

    print(f"Eligible species: {n_eligible} "
          f"(train={len(train_species)}, val={len(val_species)}, test={len(test_species)})")
    assert not (train_species & val_species),  "train/val overlap"
    assert not (train_species & test_species), "train/test overlap"
    assert not (val_species   & test_species), "val/test overlap"

    selected: dict[int, tuple[str, set]] = {}

    for lbl in train_species:
        entries = [(s, i) for s, i in species_indices[lbl] if s == "train"]
        rng.shuffle(entries)
        selected[lbl] = ("train", set(entries[:MAX_TRAIN_IMAGES]))

    for lbl in val_species:
        entries = list(species_indices[lbl])
        rng.shuffle(entries)
        selected[lbl] = ("val", set(entries[:MAX_VAL_IMAGES]))

    for lbl in test_species:
        entries = list(species_indices[lbl])
        rng.shuffle(entries)
        selected[lbl] = ("test", set(entries[:MAX_TEST_IMAGES]))

    del species_indices

    # Pass 2: save images
    print("Pass 2: saving images...")
    summary_rows: list = []
    seen_hashes: defaultdict[int, set] = defaultdict(set)

    for hf_split, split_ds in hf_splits.items():
        for idx, sample in enumerate(tqdm(split_ds, desc=f"  {hf_split}")):
            lbl = sample["label"]
            if lbl not in selected:
                continue
            out_split, idx_set = selected[lbl]
            if (hf_split, idx) not in idx_set:
                continue

            out_dir = OUTPUT_DIR / out_split / class_folder_name(lbl)
            out_dir.mkdir(parents=True, exist_ok=True)

            img = preprocess_image(sample["image"])

            if dedup:
                h = avg_hash(img)
                if any(bin(h ^ h2).count('1') <= DEDUP_MAX_DIST for h2 in seen_hashes[lbl]):
                    img.close()
                    continue
                seen_hashes[lbl].add(h)

            buf = io.BytesIO()
            img.save(buf, format="JPEG", quality=JPEG_QUALITY)
            img.close()

            fname = out_dir / f"{hf_split}_{idx:07d}.jpg"
            fname.write_bytes(buf.getvalue())

            summary_rows.append({
                "split":        out_split,
                "label":        lbl,
                "class_folder": class_folder_name(lbl),
                "file":         str(fname),
            })

    summary_df = pd.DataFrame(summary_rows)
    summary_df.to_csv(OUTPUT_DIR / "prepared_summary.csv", index=False)

    counts_df = (
        summary_df.groupby(["split", "label"])
        .size()
        .reset_index(name="count")
    )
    counts_df.to_csv(OUTPUT_DIR / "class_counts.csv", index=False)

    print("\nSplit totals:")
    for split in ["train", "val", "test"]:
        n     = (summary_df["split"] == split).sum()
        n_cls = summary_df.loc[summary_df["split"] == split, "label"].nunique()
        print(f"  {split:<8} {n:>6} images  {n_cls:>4} classes")

    print(f"\nDone. Output folder: {OUTPUT_DIR.resolve()}")


if __name__ == "__main__":
    main()
