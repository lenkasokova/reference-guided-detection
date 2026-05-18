"""
@author Bc. Lenka Sokova
"""

import random
from collections import defaultdict
from pathlib import Path

from PIL import Image
import torch
from torch.utils.data import Dataset, Sampler
from torchvision import transforms


IMG_EXTS = {".jpg", ".jpeg", ".png", ".bmp", ".webp"}


class ImageFolderWithPaths(Dataset):
    def __init__(self, root_dir, image_size=224, train=True):
        self.root_dir = Path(root_dir)
        self.samples = []
        self.class_to_idx = {}

        class_dirs = sorted([p for p in self.root_dir.iterdir() if p.is_dir()])
        for idx, class_dir in enumerate(class_dirs):
            self.class_to_idx[class_dir.name] = idx
            for img_path in class_dir.rglob("*"):
                if img_path.suffix.lower() in IMG_EXTS:
                    self.samples.append((img_path, idx))

        if train:
            self.transform = transforms.Compose([
                transforms.RandomResizedCrop(image_size, scale=(0.8, 1.0)),
                transforms.RandomHorizontalFlip(),
                transforms.RandomRotation(15),
                transforms.ColorJitter(brightness=0.2, contrast=0.2, saturation=0.1),
                transforms.ToTensor(),
                transforms.Normalize(
                    mean=[0.485, 0.456, 0.406],
                    std=[0.229, 0.224, 0.225],
                ),
            ])
        else:
            self.transform = transforms.Compose([
                transforms.Resize((image_size, image_size)),
                transforms.ToTensor(),
                transforms.Normalize(
                    mean=[0.485, 0.456, 0.406],
                    std=[0.229, 0.224, 0.225],
                ),
            ])

    def __len__(self):
        return len(self.samples)

    def __getitem__(self, idx):
        path, label = self.samples[idx]
        img = Image.open(path).convert("RGB")
        img = self.transform(img)
        return img, label, str(path)


class ClassBalancedBatchSampler(Sampler):
    def __init__(
        self,
        dataset,
        batch_size,
        samples_per_class,
        seed=0,
    ):
        self.dataset = dataset
        self.batch_size = batch_size
        self.samples_per_class = samples_per_class
        self.classes_per_batch = batch_size // samples_per_class
        self.seed = seed
        self.epoch = 0

        if batch_size % samples_per_class != 0:
            raise ValueError("batch_size must be divisible by samples_per_class")

        self.label_to_indices = defaultdict(list)
        for idx, (_, label) in enumerate(dataset.samples):
            self.label_to_indices[label].append(idx)

        self.valid_labels = [
            label for label, ids in self.label_to_indices.items()
            if len(ids) >= samples_per_class
        ]

        if len(self.valid_labels) < self.classes_per_batch:
            raise ValueError(
                "Not enough classes with sufficient samples to build a balanced batch"
            )

    def __iter__(self):
        rng = random.Random(self.seed + self.epoch)
        for _ in range(len(self)):
            chosen_labels = rng.sample(self.valid_labels, self.classes_per_batch)
            batch = []
            for label in chosen_labels:
                inds = rng.sample(self.label_to_indices[label], self.samples_per_class)
                batch.extend(inds)
            rng.shuffle(batch)
            yield batch

    def __len__(self):
        return len(self.dataset) // self.batch_size

    def set_epoch(self, epoch):
        self.epoch = epoch
