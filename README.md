# Mobile Application for Detection and Identification of Visually Similar Objects in Images

Master's thesis repository — Brno University of Technology, Faculty of Information Technology, 2026.

| | |
|---|---|
| **Author** | Bc. Lenka Šoková |
| **Supervisor** | Ing. Tomáš Goldmann, Ph.D. |
| **GitHub** | [lenkasokova/reference-guided-detection](https://github.com/lenkasokova/reference-guided-detection) |
| **Thesis PDF** | [xsokov01_master_thesis.pdf](xsokov01_master_thesis.pdf) |
| **User Guide** | [mobile_app_user_guide.pdf](mobile_app_user_guide.pdf) |

The [user guide](mobile_app_user_guide.pdf) describes how to use the application.

---

## Overview

This work presents an Android application for detecting and identifying visually similar objects using reference images. The user stores reference images with labels and descriptions and subsequently identifies similar objects in a live camera stream. The system is primarily focused on plant identification and comparison, but is designed to be extensible to other domains.

Due to the computational constraints of existing one-shot and open-vocabulary detectors (e.g., OWL-ViT at ~86 M parameters proved too slow for real-time mobile inference), the proposed solution uses a **lightweight hybrid pipeline** built around embedding-based similarity comparison:

1. **Detect** — EfficientDet-Lite locates regions of interest in the current frame via MediaPipe.
2. **Segment** *(optional)* — MobileSAM or EdgeSAM isolates the target object from the background via ONNX Runtime.
3. **Embed** — A fine-tuned MobileNetV3 Large backbone maps the cropped region to a 128-dimensional L2-normalised feature vector via LiteRT.
4. **Match** — Cosine similarity against precomputed gallery embeddings identifies the closest reference; gallery-based scoring (k=3, α=0.75) aggregates multiple reference images per class.

All inference runs on-device without any server communication.

## Model Locations

The trained and exported models are mainly stored in these folders:

- `src/embedding/runs/...`
  Training runs, checkpoints, plots, TensorBoard logs, exported TFLite models, and evaluation results.
  This also includes exported and quantized embedding models that were evaluated.
- `src/android-app/app/src/main/assets/embeddingModel/`
  Embedding models used by the Android app.
- `src/android-app/app/src/main/assets/segmentModel/`
  Segmentation models used by the Android app.
- `src/android-app/app/src/main/assets/detectionModel/`
  Detection models used by the Android app.

## Project structure

<details>
<summary><strong>Expand</strong></summary>

```text
reference-guided-detection/
│
├── src/
│   ├── embedding/                          # Metric-learning embedding pipeline
│   │   ├── configs/                        # Experiments for backbone/loss/hyperparameter comparisons
│   │   │   └── ...
│   │   ├── data/                           # Dataset download & preparation
│   │   │   ├── download_dataset.py
│   │   │   └── prepare_dataset.py
│   │   ├── evaluation/                     # Evaluation scripts
│   │   │   ├── evaluate_roc.py             # Retrieval + verification metrics
│   │   │   ├── evaluate_clip.py            # CLIP baseline
│   │   │   └── eval_pretrained.py          # Zero-epoch pretrained baseline
│   │   ├── export/                         # TFLite export
│   │   │   └── export_tflite.py
│   │   ├── dataset.py                      # PyTorch Dataset & sampler
│   │   ├── model.py                        # EmbeddingModel (backbone + head)
│   │   ├── losses.py                       # SupCon, Triplet, Proxy Anchor
│   │   ├── metrics.py                      # Retrieval metrics
│   │   ├── utils.py                        # Checkpointing, logging, plotting
│   │   ├── build_model.py
│   │   ├── train.py                        # Main training entry point
│   │   ├── finetune_pruned.py
│   │   ├── run_experiments.sh              # Runs all configured embedding experiments
│   │   └── requirements.txt
│   ├── segmentation/                       # SAM model export & evaluation
│   │   ├── sam_to_onnx.py                  # Export MobileSAM or EdgeSAM -> ONNX
│   │   ├── evaluate_quantization.py        # fp32 vs fp16 vs int8 mask IoU
│   │   └── requirements.txt
│   ├── detection/                          # EfficientDet evaluation
│   │   ├── evaluate_efficientdet_tflite_coco.py
│   │   └── requirements.txt
│   ├── android-app/                        # Kotlin/Compose Android app
│   │   └── ...
│   └── data/                               # Datasets — not committed
│       └── custom dataset
├── master_thesis_latex_source/             # LaTeX thesis source
│   └── ...
├── xsokov01_master_thesis.pdf              # Compiled thesis PDF
├── mobile_app_user_guide.pdf               # User guide for the Android app
└── README.md
```

</details>

The experiments in `src/embedding/` compare multiple lightweight embedding backbones (`mobilenet_v3_large`, `mobilenet_v3_small`, `lite0`, `lite4`, and `ghostnet_100`) and several metric-learning objectives (`triplet` with hard or semi-hard mining, `SupCon`, and `Proxy Anchor`). The YAML files in `src/embedding/configs/` define these runs, including batch-size and loss-hyperparameter variants, `eval_pretrained.py` measures the zero-epoch pretrained baseline, and `run_experiments.sh --all` executes the full experiment suite sequentially.

---

## Master thesis (LaTeX source)

Source lives in `master_thesis_latex_source/`. The main entry point is `projekt.tex`; the Makefile drives a standard `pdflatex` + `bibtex` build.

### Requirements

```bash
# Debian / Ubuntu
sudo apt install texlive-full bibtex

```

### Compile PDF

```bash
cd master_thesis_latex_source
make          # runs pdflatex → bibtex → pdflatex → pdflatex
```

The output is `master_thesis_latex_source/projekt.pdf`.

---

## Setup

Create and activate a Python virtual environment first:

```bash
python3 -m venv .venv
source .venv/bin/activate
python -m pip install --upgrade pip
```

Tested environment used in this work:

- `CUDA 12.8`
- `PyTorch 2.9.1+cu128`
- Python version is not specified in the thesis appendix

Install the dependencies for the pipeline you need:

```bash
# Embedding (training + evaluation + TFLite export)
pip install -r src/embedding/requirements.txt

# Segmentation (SAM → ONNX export + quantization evaluation)
pip install -r src/segmentation/requirements.txt

# MobileSAM package + checkpoint
pip install git+https://github.com/ChaoningZhang/MobileSAM.git
wget -O mobile_sam.pt https://github.com/ChaoningZhang/MobileSAM/raw/master/weights/mobile_sam.pt

# EdgeSAM package + checkpoint (+ RepViT backbone)
pip install git+https://github.com/chongzhou96/EdgeSAM.git timm
wget -O edge_sam.pth https://huggingface.co/spaces/chongzhou/EdgeSAM/resolve/main/weights/edge_sam.pth
# optional alternative checkpoint:
# wget -O edge_sam_3x.pth https://huggingface.co/spaces/chongzhou/EdgeSAM/resolve/main/weights/edge_sam_3x.pth

# Detection (EfficientDet COCO evaluation)
pip install -r src/detection/requirements.txt
```

---

## Embedding

Plant embedding model trained with metric learning (SupCon / Triplet / Proxy Anchor).
All commands are run from the **project root**.

### 1. Prepare dataset

Downloads Pl@ntNet-300K from HuggingFace and splits into open-set train/val/test (350/44/43 disjoint classes, ~39k/2.6k/2.4k images):

```bash
python src/embedding/data/prepare_dataset.py
# Output: src/embedding/plantnet_prepared/{train,val,test}/
```

### 2. Train

Pick a config from `src/embedding/configs/` and run:

```bash
python src/embedding/train.py --config src/embedding/configs/runs_mobilenet_v3_large/supcon_t007.yaml
```

The `--config` parameter sets the path to the YAML file with the training setup, for example backbone, loss, batch size, number of epochs, learning rate, dataset paths, and `output_dir`.

The config file contains these parameters:

| Parameter | Meaning |
|---|---|
| `seed` | Random seed for reproducible training. |
| `output_dir` | Folder where checkpoints, logs, plots, and results are saved. |
| `data.train_dir` | Path to the training dataset. |
| `data.val_dir` | Path to the validation dataset. |
| `data.test_dir` | Path to the test dataset. |
| `data.image_size` | Input image size used by the model. |
| `data.num_workers` | Number of worker processes for data loading. |
| `train.epochs` | Number of training epochs. |
| `train.batch_size` | Batch size used in training and evaluation. |
| `train.lr` | Learning rate for the optimizer. |
| `train.weight_decay` | Weight decay used for regularization. |
| `train.embedding_dim` | Size of the output embedding vector. |
| `train.backbone` | Backbone model used for feature extraction. |
| `train.pretrained` | If `true`, load pretrained backbone weights. |
| `train.freeze_backbone` | If `true`, keep backbone weights fixed during training. |
| `train.scheduler` | Learning rate scheduler type, for example `cosine`. |
| `train.mixed_precision` | If `true`, use mixed precision training on GPU. |
| `train.grad_clip` | Gradient clipping value. |
| `loss.name` | Loss function, for example `triplet`, `supcon`, or `proxy_anchor`. |
| `loss.temperature` | Temperature parameter used by contrastive-style losses. |
| `loss.margin` | Margin used by margin-based losses such as triplet loss. |
| `loss.miner` | Strategy for selecting pairs or triplets, for example `hard` or `semi_hard`. |
| `loss.alpha` | Additional loss hyperparameter used mainly by Proxy Anchor. |
| `sampling.samples_per_class` | Number of images per class sampled into one batch. |
| `logging.save_csv` | If `true`, save training history as CSV. |
| `logging.save_json` | If `true`, save training history as JSON. |
| `logging.save_plots` | If `true`, save plots of training metrics. |
| `logging.save_confusion_like_retrieval` | If `true`, save retrieval-style confusion outputs. |
| `logging.tensorboard` | If `true`, write logs for TensorBoard. |
| `logging.log_every` | How often to print training progress. |
| `evaluation.k_values` | Values of `k` used for retrieval metrics such as top-1, top-3, and top-5. |
| `evaluation.normalize_embeddings` | If `true`, normalize embeddings before evaluation. |
| `evaluation.run_val_retrieval` | If `true`, run retrieval evaluation on the validation set during training. |
| `early_stopping.enabled` | If `true`, stop training early when the monitored metric stops improving. |
| `early_stopping.patience` | Number of epochs to wait before early stopping. |
| `early_stopping.monitor` | Metric used for early stopping, for example `val_top1`. |

It can be run with any config file from `src/embedding/configs/`, for example:

```bash
python src/embedding/train.py --config src/embedding/configs/runs_mobilenet_v3_large/triplet_hard.yaml
python src/embedding/train.py --config src/embedding/configs/runs_mobilenet_v3_small/supcon_t005.yaml
python src/embedding/train.py --config src/embedding/configs/runs_lite4/proxy_anchor_m02_a64.yaml
```

Each training run is also logged to TensorBoard.
Checkpoints and TensorBoard logs are written to the `output_dir` set in the config (default: `./runs/plant_embedding`).

To run a batch of experiments:

```bash
bash src/embedding/run_experiments.sh
```

After training, a run folder looks like this:

```text
runs/mobilenet_v3_large/supcon_t007/
├── checkpoints/
│   ├── best.pt
│   └── last.pt
├── plots/
│   ├── train_loss.png
│   ├── val_top1.png
│   ├── val_top3.png
│   ├── val_top5.png
│   └── lr.png
├── tb/
├── config_used.yaml
├── history.csv
└── history.json
```

To open TensorBoard, start it in a new terminal and activate the environment there too:

```bash
source .venv/bin/activate
tensorboard --logdir runs
```

Then open the local TensorBoard address shown in the terminal, usually `http://localhost:6006/`.

### 3. Evaluate

**Retrieval + verification metrics** (R@k, mAP, AUROC, EER, ROC/PR curves):

```bash
python src/embedding/evaluation/evaluate_roc.py \
    --checkpoint runs/plant_embedding/checkpoints/best.pt \
    --dataset-dir src/embedding/plantnet_prepared/test
```

After evaluation, the files are saved in:

```text
runs/mobilenet_v3_large/supcon_t007/checkpoints/evaluate/
└── best_pt_test_evaluation/
    ├── evaluation.json
    ├── roc.png
    ├── pr.png
    ├── pairs.txt
    └── summary.txt
```

The folder name is `<model_name>_<dataset_name>`, so for example:
- `best_pt_test_evaluation/`
- `best_tflite_test_evaluation/`


**CLIP baseline** (same metrics pipeline, no checkpoint needed):

```bash
python src/embedding/evaluation/evaluate_clip.py \
    --dataset-dir src/embedding/plantnet_prepared/val
```

**Pretrained backbone without training** (zero-epoch baseline):

```bash
python src/embedding/evaluation/eval_pretrained.py \
    --config src/embedding/configs/runs_mobilenet_v3_large/pretrained_zero_epoch.yaml
```

### 4. Export to TFLite

```bash
# float32 (largest, most compatible)
python src/embedding/export/export_tflite.py \
    --checkpoint runs/plant_embedding/checkpoints/best.pt

# float16 (~2x smaller, same accuracy)
python src/embedding/export/export_tflite.py \
    --checkpoint runs/plant_embedding/checkpoints/best.pt \
    --quantization float16

# int8 (~4x smaller, fastest on NNAPI — requires calibration dataset)
python src/embedding/export/export_tflite.py \
    --checkpoint runs/plant_embedding/checkpoints/best.pt \
    --quantization int8 \
    --calibration-dir src/embedding/plantnet_prepared/train

# structured pruning (ratio 0.3) + float16 — smallest model with re-training
python src/embedding/export/export_tflite.py \
    --checkpoint runs/plant_embedding/checkpoints/best.pt \
    --pruning structured --pruning-amount 0.3 \
    --quantization float16
```

---

## Segmentation

Exports MobileSAM or EdgeSAM to ONNX for ONNX Runtime on Android. Segmentation is an **optional** pipeline step used to isolate the target object before embedding. MobileSAM FP16 offers the best size/stability trade-off (23.3 MB, Mask IoU ≈ 0.998 vs. FP32 baseline).

### Requirements

```bash
# MobileSAM
pip install git+https://github.com/ChaoningZhang/MobileSAM.git
wget -O mobile_sam.pt https://github.com/ChaoningZhang/MobileSAM/raw/master/weights/mobile_sam.pt

# EdgeSAM
pip install git+https://github.com/chongzhou96/EdgeSAM.git
wget -O edge_sam.pth https://huggingface.co/spaces/chongzhou/EdgeSAM/resolve/main/weights/edge_sam.pth
# optional alternative checkpoint:
# wget -O edge_sam_3x.pth https://huggingface.co/spaces/chongzhou/EdgeSAM/resolve/main/weights/edge_sam_3x.pth

pip install -r src/segmentation/requirements.txt
```

### Export to ONNX

```bash
# MobileSAM — float32
python src/segmentation/sam_to_onnx.py --model mobilesam --checkpoint mobile_sam.pt

# EdgeSAM — float32
python src/segmentation/sam_to_onnx.py --model edgesam --checkpoint edge_sam.pth

# With quantization
python src/segmentation/sam_to_onnx.py --model mobilesam --checkpoint mobile_sam.pt \
    --quantize dynamic_int8

python src/segmentation/sam_to_onnx.py --model edgesam --checkpoint edge_sam.pth \
    --quantize fp16

# With static INT8 (requires calibration images)
python src/segmentation/sam_to_onnx.py --model edgesam --checkpoint edge_sam.pth \
    --quantize static_int8 --calibration-dataset src/data/val2017

# Also produce .ort (ONNX Runtime Mobile format)
python src/segmentation/sam_to_onnx.py --model mobilesam --checkpoint mobile_sam.pt \
    --quantize dynamic_int8 --to-ort

# Smaller encoder resolution (faster on device, lower quality)
python src/segmentation/sam_to_onnx.py --model mobilesam --checkpoint mobile_sam.pt \
    --image-size 512
```

Output files go to `models/` by default. Override with `--out-dir`.

---

## Detection

Evaluates an EfficientDet TFLite model on COCO using MediaPipe (matches the Android inference path).

### Requirements

```bash
pip install mediapipe pycocotools pillow
```

### COCO val2017

Download the dataset here:

- Images: http://images.cocodataset.org/zips/val2017.zip
- Annotations: http://images.cocodataset.org/annotations/annotations_trainval2017.zip

Example:

```bash
mkdir -p src/data
cd src/data
wget http://images.cocodataset.org/zips/val2017.zip
wget http://images.cocodataset.org/annotations/annotations_trainval2017.zip
unzip val2017.zip
unzip annotations_trainval2017.zip
```

### Run

```bash
python src/detection/evaluate_efficientdet_tflite_coco.py \
    --model src/android-app/app/src/main/assets/detectionModel/efficientdet_lite0.tflite \
    --images src/data/val2017 \
    --annotations src/data/annotations_trainval2017/annotations/instances_val2017.json
```

Options:

```bash
# Limit to first 500 images (faster iteration)
python src/detection/evaluate_efficientdet_tflite_coco.py \
    --model src/android-app/app/src/main/assets/detectionModel/efficientdet_lite0.tflite \
    --max-images 500

# Export predictions JSON only, skip COCOeval
python src/detection/evaluate_efficientdet_tflite_coco.py \
    --model src/android-app/app/src/main/assets/detectionModel/efficientdet_lite0.tflite \
    --no-eval --output-json src/detection/outputs/predictions.json
```

---

## Android app

### Requirements

| Tool | Version |
|------|---------|
| Android Studio | Meerkat or newer |
| Android Gradle Plugin | 9.0.0 |
| Kotlin | 2.3.10 |
| Min SDK | 24 (Android 7.0) |
| Compile / Target SDK | 36 |

Key runtime dependencies (managed by Gradle, no manual install needed):

| Library | Version |
|---------|---------|
| LiteRT (TFLite) | 2.1.0 |
| ONNX Runtime Android | 1.20.0 |
| MediaPipe Tasks Vision | 0.10.32 |
| CameraX | 1.5.3 |
| Jetpack Compose BOM | 2026.02.00 |
| Room | 2.7.2 |

### Run

1. Open `src/android-app/` in Android Studio.
2. Place the model files under `app/src/main/assets/` (see table below).
3. Connect a device or start an emulator (API 24+).
4. Click **Run** or execute:

```bash
cd src/android-app
./gradlew installDebug
```

### Model assets

| Asset folder | Content | Format |
|---|---|---|
| `embeddingModel/mobilenet_v3_large/` | Embedding TFLite models (fp32, fp16, pruned variants) | `.tflite` |
| `segmentModel/` | SAM encoder + decoder | `.onnx` / `.ort` |
| `detectionModel/` | EfficientDet-Lite0 or Lite2 | `.tflite` (MediaPipe) |
| `data/plants/` | Reference plant images for the built-in gallery | `.jpg` / `.png` |

---
