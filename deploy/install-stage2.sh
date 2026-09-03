#!/bin/sh
# 在已解压的发布根目录中运行；不需要 root，也不覆盖业务数据库。
set -eu
BASE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
TARGET_DIR=${IPAM_INSTALL_DIR:-/data/项目/IP管理系统}
DATA_DIR=${IPAM_DATA_DIR:-"$TARGET_DIR/data"}

[ "$BASE_DIR" = "$TARGET_DIR" ] || {
  echo "请先将发布包内容解压到 $TARGET_DIR，再从该目录执行本脚本。" >&2
  echo "当前发布目录：$BASE_DIR" >&2
  exit 2
}
chmod u+x "$BASE_DIR"/deploy/*.sh
cd "$BASE_DIR"
./deploy/verify-package.sh
if [ -r "$DATA_DIR/ipam-stage1.pid" ] && kill -0 "$(cat "$DATA_DIR/ipam-stage1.pid")" 2>/dev/null; then
  IPAM_DATA_DIR="$DATA_DIR" ./deploy/stop-stage1.sh
fi
if [ -r "$DATA_DIR/ipam.db" ]; then
  BACKUP="$DATA_DIR/backups/ipam-before-stage2-usability-$(date +%Y%m%d-%H%M%S).db"
  printf '\n' | IPAM_DATA_DIR="$DATA_DIR" ./deploy/backup-restore.sh backup "$DATA_DIR" "$BACKUP"
  echo "升级前数据库备份：$BACKUP"
fi
IPAM_DATA_DIR="$DATA_DIR" ./deploy/check-kylin-arm64.sh
IPAM_DATA_DIR="$DATA_DIR" ./deploy/start-stage1.sh
