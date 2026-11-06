#!/bin/sh
# 仅停止由 start-stage1.sh 记录的本应用进程；备份/恢复前使用。
set -eu
BASE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
. "$BASE_DIR/deploy/load-config.sh"
DATA_DIR=${IPAM_DATA_DIR:-"$BASE_DIR/data"}
PID_FILE="$DATA_DIR/ipam-stage1.pid"
[ -r "$PID_FILE" ] || { echo '未找到 PID 文件，应用可能未由本脚本启动。' >&2; exit 1; }
PID=$(cat "$PID_FILE")
case "$PID" in *[!0-9]*|'') echo 'PID 文件格式错误，拒绝停止进程。' >&2; exit 2;; esac
if ! kill -0 "$PID" 2>/dev/null; then rm -f "$PID_FILE"; echo '记录的进程已退出。'; exit 0; fi
kill "$PID"
COUNT=0
while kill -0 "$PID" 2>/dev/null && [ "$COUNT" -lt 10 ]; do sleep 1; COUNT=$((COUNT+1)); done
if kill -0 "$PID" 2>/dev/null; then echo "进程 $PID 未在 10 秒内停止，请检查日志，未执行强制终止。" >&2; exit 1; fi
rm -f "$PID_FILE"; echo "应用已停止：PID=$PID"
