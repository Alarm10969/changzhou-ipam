#!/bin/sh
# 用法：sh ./deploy/rollback-stage2.sh /绝对路径/升级前备份.db [旧版本ZIP]
set -eu
BASE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
. "$BASE_DIR/deploy/load-config.sh"
DATA_DIR=${IPAM_DATA_DIR:-"$BASE_DIR/data"}
BACKUP=${1:?请提供升级前数据库备份的绝对路径}
OLD_ZIP=${2:-}
[ -r "$BACKUP" ] || { echo "找不到数据库备份：$BACKUP" >&2; exit 2; }
if [ -r "$DATA_DIR/ipam-stage1.pid" ] && kill -0 "$(cat "$DATA_DIR/ipam-stage1.pid")" 2>/dev/null; then
  IPAM_DATA_DIR="$DATA_DIR" "$BASE_DIR/deploy/stop-stage1.sh"
fi
printf '\n' | "$BASE_DIR/deploy/backup-restore.sh" restore "$DATA_DIR" "$BACKUP"
if [ -n "$OLD_ZIP" ]; then
  [ -r "$OLD_ZIP" ] || { echo "找不到旧版本 ZIP：$OLD_ZIP" >&2; exit 2; }
  command -v unzip >/dev/null 2>&1 || { echo '缺少 unzip，无法恢复旧版本程序文件。' >&2; exit 2; }
  unzip -o "$OLD_ZIP" -d "$BASE_DIR"
fi
echo '数据库已恢复。若同时提供旧版本 ZIP，程序文件也已恢复；请按对应版本启动说明重新启动。'
