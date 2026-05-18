#!/usr/bin/env bash
set -euo pipefail

# @author Bc. Lenka Sokova
#
# Script for running embedding experiments one by one.
# Output of each run is saved to logs/<folder>/<name>.log
#
# Usage:
#   bash run_experiments.sh [--folder <folder>] [--only <name1,name2,...>]
#   bash run_experiments.sh --folders <folder1,folder2,...>
#   bash run_experiments.sh --all
#
# Options:
#   --folder   run one config folder
#   --folders  run more config folders
#   --all      run all folders starting with runs_
#   --only     run only selected config names

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TRAIN="$SCRIPT_DIR/train.py"

# Default values
FOLDER="runs"
FOLDERS=""
ALL=false
ONLY=""

# Read arguments
while [[ $# -gt 0 ]]; do
  case "$1" in
    --folder)        FOLDER="$2";        shift 2 ;;
    --folders)       FOLDERS="$2";       shift 2 ;;
    --all)           ALL=true;           shift   ;;
    --only)          ONLY="$2";          shift 2 ;;
    *) echo "Unknown argument: $1"; exit 1 ;;
  esac
done

# Make the list of folders to run
if [[ "$ALL" == true ]]; then
  mapfile -t FOLDER_LIST < <(find "$SCRIPT_DIR/configs" -maxdepth 1 -type d -name "runs_*" | sort)
  FOLDER_LIST=("${FOLDER_LIST[@]##*/}")
elif [[ -n "$FOLDERS" ]]; then
  IFS=',' read -ra FOLDER_LIST <<< "$FOLDERS"
else
  FOLDER_LIST=("$FOLDER")
fi

if [[ ${#FOLDER_LIST[@]} -eq 0 ]]; then
  echo "No config folders found."
  exit 1
fi

# Find all yaml config files in selected folders
declare -a RUNS=()

for folder in "${FOLDER_LIST[@]}"; do
  CONFIG_DIR="$SCRIPT_DIR/configs/$folder"
  if [[ ! -d "$CONFIG_DIR" ]]; then
    echo "Config folder not found: $CONFIG_DIR"
    exit 1
  fi
  while IFS= read -r -d '' f; do
    name=$(basename "$f" .yaml)
    RUNS+=("$folder/$name configs/$folder/$name.yaml")
  done < <(find "$CONFIG_DIR" -maxdepth 1 -name "*.yaml" -print0 | sort -z)
done

# Check if the experiment should run
should_run() {
  local name="$1"
  [[ -z "$ONLY" ]] && return 0
  IFS=',' read -ra FILTER <<< "$ONLY"
  for f in "${FILTER[@]}"; do
    [[ "$f" == "$name" ]] && return 0
  done
  return 1
}

run_experiment() {
  local key="$1"
  local config="$2"
  local folder="${key%/*}"
  local name="${key##*/}"
  local log="$SCRIPT_DIR/logs/$folder/${name}.log"

  mkdir -p "$(dirname "$log")"

  echo ""
  echo "----------------------------------------"
  echo "Running: $name"
  echo "Folder: $folder"
  echo "Config: $config"
  echo "Log: $log"
  echo "Time: $(date '+%Y-%m-%d %H:%M:%S')"
  echo "----------------------------------------"

  CUDA_VISIBLE_DEVICES=0 python "$TRAIN" --config "$SCRIPT_DIR/$config" \
    2>&1 | tee "$log"

  local exit_code="${PIPESTATUS[0]}"
  if [[ "$exit_code" -ne 0 ]]; then
    echo "Experiment $name failed with exit code $exit_code. Check $log"
    return "$exit_code"
  fi

  echo "Experiment $name finished."
}

# Main part
cd "$SCRIPT_DIR"

FAILED=()

for entry in "${RUNS[@]}"; do
  read -r key config <<< "$entry"
  name="${key##*/}"

  if ! should_run "$name"; then
    echo "Skipping: $key"
    continue
  fi

  if run_experiment "$key" "$config"; then
    :
  else
    FAILED+=("$key")
    echo "Continuing with next experiment."
  fi
done

echo ""
echo "----------------------------------------"
echo "All experiments finished."
if [[ ${#FAILED[@]} -eq 0 ]]; then
  echo "Status: all experiments passed."
else
  echo "Status: failed experiments: ${FAILED[*]}"
fi
echo "Time: $(date '+%Y-%m-%d %H:%M:%S')"
echo "----------------------------------------"
