#!/bin/sh
# 用法：先停止 IPAM 服务，再执行 ./deploy/backup-restore.sh backup|restore [数据目录] [备份文件]
set -eu
ACTION=${1:?需要 backup 或 restore}; DATA_DIR=${2:-data}; BACKUP=${3:-"$DATA_DIR/backups/ipam-$(date +%Y%m%d-%H%M%S).db"}
BASE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd); APP_JAR=${IPAM_JAR:-"$BASE_DIR/release/ipam-stage1.jar"}; LIB_DIR=${IPAM_LIB_DIR:-"$BASE_DIR/release/lib"}
case "$ACTION" in backup|restore) ;; *) echo '只支持 backup 或 restore' >&2; exit 2;; esac
[ -r "$APP_JAR" ] && [ -d "$LIB_DIR" ] || { echo '发布包不完整' >&2; exit 2; }
printf '%s\n' '确认正式服务已停止后按 Enter 继续（脚本不会自行停止服务）：'; read _answer
java -cp "$APP_JAR:$LIB_DIR/*" com.icbc.changzhou.ipam.DatabaseTool "$ACTION" "$DATA_DIR/ipam.db" "$BACKUP"
