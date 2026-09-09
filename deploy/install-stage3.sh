#!/bin/sh
# 在已解压的第三阶段发布根目录运行；普通用户、连续迁移且保留升级前备份。
set -eu
BASE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
TARGET_DIR=${IPAM_INSTALL_DIR:-/data/项目/IP管理系统}
DATA_DIR=${IPAM_DATA_DIR:-"$TARGET_DIR/data"}
[ "$BASE_DIR" = "$TARGET_DIR" ] || { echo "请先解压到 $TARGET_DIR 后再运行本脚本。当前：$BASE_DIR" >&2; exit 2; }
chmod u+x "$BASE_DIR"/deploy/*.sh
cd "$BASE_DIR"
./deploy/verify-package.sh
[ -f "$BASE_DIR/ipam-stage3.jar" ] || { echo '缺少第三阶段应用包。' >&2; exit 2; }
if [ -r "$DATA_DIR/ipam-stage1.pid" ] && kill -0 "$(cat "$DATA_DIR/ipam-stage1.pid")" 2>/dev/null; then IPAM_DATA_DIR="$DATA_DIR" ./deploy/stop-stage1.sh; fi
if [ -r "$DATA_DIR/ipam.db" ]; then
  BACKUP="$DATA_DIR/backups/ipam-before-stage3-$(date +%Y%m%d-%H%M%S).db"
  printf '\n' | IPAM_DATA_DIR="$DATA_DIR" ./deploy/backup-restore.sh backup "$DATA_DIR" "$BACKUP"
  echo "升级前数据库备份：$BACKUP"
fi
IPAM_DATA_DIR="$DATA_DIR" ./deploy/check-kylin-arm64.sh
IPAM_DATA_DIR="$DATA_DIR" ./deploy/start-stage1.sh
PORT=${IPAM_PORT:-18080}
if command -v curl >/dev/null 2>&1; then HEALTH=$(curl -fsS "http://127.0.0.1:$PORT/api/v1/health")
elif command -v wget >/dev/null 2>&1; then HEALTH=$(wget -qO- "http://127.0.0.1:$PORT/api/v1/health")
else echo '服务已启动，但缺少 curl/wget，无法执行健康接口检查；请安装前补齐其中之一。' >&2; exit 2
fi
printf '%s' "$HEALTH" | grep -q '"status":"UP"' || { echo "健康接口未返回 UP：$HEALTH" >&2; exit 2; }
grep -q '^3.3.1-v014-legacy-pc-and-approval-pagination$' "$BASE_DIR/VERSION.txt" || { echo '版本文件校验失败。' >&2; exit 2; }
echo '第三阶段已启动，健康接口与版本号校验通过。'
