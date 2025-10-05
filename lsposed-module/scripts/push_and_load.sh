#!/usr/bin/env bash
set -euo pipefail

if [ $# -lt 2 ]; then
  echo "Usage: $0 <batt_design_override.ko> <design_uah> [model_name]" >&2
  exit 1
fi

KO_SRC="$1"
DESIGN_UAH="$2"
MODEL_NAME="${3:-CustomBatt}"
KO_BASENAME="$(basename "$KO_SRC")"
KO_DST="/data/local/tmp/$KO_BASENAME"

echo "[+] adb push ko"
adb push "$KO_SRC" "$KO_DST"

echo "[+] try insmod"
adb shell su -c "insmod $KO_DST design_uah=$DESIGN_UAH model_name=$MODEL_NAME || echo 'insmod maybe already loaded'"

echo "[+] verify sysfs"
adb shell su -c "cat /sys/module/batt_design_override/parameters/design_uah || echo 'not loaded'"

echo "Done." 
