#!/bin/sh
# 首次/常规启动：默认绑定内网全部网卡的 18080 端口，不依赖 systemd、Maven、Node 或 Nginx。
set -eu
BASE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
APP_JAR=${IPAM_JAR:-"$BASE_DIR/ipam-stage2.jar"}
DATA_DIR=${IPAM_DATA_DIR:-"$BASE_DIR/data"}
PORT=${IPAM_PORT:-18080}
BIND=${IPAM_BIND:-0.0.0.0}
ADMIN_USERNAME=${IPAM_ADMIN_USERNAME:-admin}
PID_FILE="$DATA_DIR/ipam-stage1.pid"
LOG_FILE="$DATA_DIR/ipam-stage1.log"
[ -r "$APP_JAR" ] || { echo "找不到发布 JAR: $APP_JAR" >&2; exit 2; }
mkdir -p "$DATA_DIR"
if [ -r "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then echo "应用已运行，PID=$(cat "$PID_FILE")" >&2; exit 1; fi
rm -f "$PID_FILE"
umask 077
PASSWORD_FILE="$DATA_DIR/.bootstrap-password.$$"
trap 'rm -f "$PASSWORD_FILE"' EXIT HUP INT TERM
printf '初始管理员账号（默认 %s）：' "$ADMIN_USERNAME"; read INPUT_USERNAME || true
[ -z "${INPUT_USERNAME:-}" ] || ADMIN_USERNAME=$INPUT_USERNAME
printf '初始管理员密码（若已有管理员，仅用于安全启动文件并会立即删除）：'
stty -echo; read ADMIN_PASSWORD; stty echo; printf '\n'
[ -n "${ADMIN_PASSWORD:-}" ] || { echo '密码不能为空。' >&2; exit 2; }
printf '%s' "$ADMIN_PASSWORD" > "$PASSWORD_FILE"
unset ADMIN_PASSWORD
nohup env IPAM_DATA_DIR="$DATA_DIR" IPAM_STATIC_DIR="$BASE_DIR/frontend" IPAM_PORT="$PORT" IPAM_BIND="$BIND" IPAM_ADMIN_USERNAME="$ADMIN_USERNAME" IPAM_ADMIN_PASSWORD_FILE="$PASSWORD_FILE" java -jar "$APP_JAR" >> "$LOG_FILE" 2>&1 &
PID=$!; printf '%s\n' "$PID" > "$PID_FILE"
sleep 2
if kill -0 "$PID" 2>/dev/null; then
  echo "已启动：PID=$PID，地址 http://$(hostname -I 2>/dev/null | awk '{print $1}'):$PORT/"
  echo "日志：$LOG_FILE"
else
  echo "启动失败，最近日志如下：" >&2; tail -n 50 "$LOG_FILE" >&2; rm -f "$PID_FILE"; exit 1
fi
