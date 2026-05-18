"""
@author Bc. Lenka Sokova

Download PlantNet300K from HuggingFace and save the raw images.

Output structure:
  plantnet_raw/
    train/  <label>/  <idx:07d>.jpg
    val/    <label>/  <idx:07d>.jpg
    test/   <label>/  <idx:07d>.jpg
    metadata.csv   (split, label, idx, file)
"""

from pathlib import Path

import pandas as pd
from datasets import load_dataset
from PIL import Image
from tqdm import tqdm

OUTPUT_DIR = Path("plantnet_raw")
JPEG_QUALITY = 95


def main():
    ds = load_dataset("mikehemberger/plantnet300K")
    rows = []

    for split_name, split_ds in ds.items():
        print(f"\nSaving {split_name} ({len(split_ds)} images)...")
        for idx, sample in enumerate(tqdm(split_ds, desc=f"  {split_name}")):
            lbl = sample["label"]
            out_dir = OUTPUT_DIR / split_name / str(lbl)
            out_dir.mkdir(parents=True, exist_ok=True)

            img = sample["image"].convert("RGB")
            fname = out_dir / f"{idx:07d}.jpg"
            img.save(fname, quality=JPEG_QUALITY)
            img.close()

            rows.append({"split": split_name, "label": lbl, "idx": idx, "file": str(fname)})

    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    pd.DataFrame(rows).to_csv(OUTPUT_DIR / "metadata.csv", index=False)
    print(f"\nDone. Output folder: {OUTPUT_DIR.resolve()}")


if __name__ == "__main__":
    main()
