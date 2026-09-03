#!/bin/sh
# 用法：sh ./deploy/rollback-stage3.sh /绝对路径/升级前备份.db [旧版本ZIP]
set -eu
BASE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
DATA_DIR=${IPAM_DATA_DIR:-"$BASE_DIR/data"}
BACKUP=${1:?请提供升级前备份的绝对路径}
OLD_ZIP=${2:-}
[ -r "$BACKUP" ] || { echo "找不到数据库备份：$BACKUP" >&2; exit 2; }
if [ -r "$DATA_DIR/ipam-stage1.pid" ] && kill -0 "$(cat "$DATA_DIR/ipam-stage1.pid")" 2>/dev/null; then IPAM_DATA_DIR="$DATA_DIR" "$BASE_DIR/deploy/stop-stage1.sh"; fi
printf '\n' | "$BASE_DIR/deploy/backup-restore.sh" restore "$DATA_DIR" "$BACKUP"
if [ -n "$OLD_ZIP" ]; then command -v unzip >/dev/null 2>&1 || { echo '缺少 unzip。' >&2; exit 2; }; unzip -o "$OLD_ZIP" -d "$BASE_DIR"; fi
echo '数据库已恢复；若提供旧 ZIP，程序文件也已恢复。请按旧版本说明启动服务。'
