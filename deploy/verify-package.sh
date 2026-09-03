#!/bin/sh
# 校验固定的离线运行依赖；可从任意目录运行。
set -eu
BASE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$BASE_DIR"
if command -v sha256sum >/dev/null 2>&1; then
  sha256sum -c dependencies.sha256
  if [ -r release-files.sha256 ]; then
    tr -d '\r' < release-files.sha256 | sha256sum -c -
  fi
else
  echo 'ERROR|SHA256|缺少 sha256sum，无法验证离线依赖' >&2
  exit 2
fi
