#!/bin/sh
# 首次/常规启动：默认绑定内网全部网卡的 18080 端口，不依赖 systemd、Maven、Node 或 Nginx。
set -eu
BASE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
. "$BASE_DIR/deploy/load-config.sh"
APP_JAR=${IPAM_JAR:-"$BASE_DIR/ipam-stage3.jar"}
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
PASSWORD_FILE=""
TERMINAL_STATE=""
cleanup_start() {
  [ -z "$TERMINAL_STATE" ] || stty "$TERMINAL_STATE" 2>/dev/null || true
  [ -z "$PASSWORD_FILE" ] || rm -f "$PASSWORD_FILE"
}
trap cleanup_start EXIT HUP INT TERM
unset IPAM_ADMIN_PASSWORD
ADMIN_STATUS=$(java -cp "$APP_JAR:$BASE_DIR/lib/*" com.icbc.changzhou.ipam.DatabaseTool admin-status "$DATA_DIR/ipam.db")
case "$ADMIN_STATUS" in
  EXISTS) echo '使用已有管理员账号启动，不需要重复输入初始密码。';;
  MISSING)
    PASSWORD_FILE=$(mktemp "$DATA_DIR/.bootstrap-password.XXXXXX")
    printf '初始管理员账号（默认 %s）：' "$ADMIN_USERNAME"; IFS= read -r INPUT_USERNAME || true
    [ -z "${INPUT_USERNAME:-}" ] || ADMIN_USERNAME=$INPUT_USERNAME
    TERMINAL_STATE=$(stty -g)
    printf '初始管理员密码：'; stty -echo; IFS= read -r ADMIN_PASSWORD; stty "$TERMINAL_STATE"; TERMINAL_STATE=""; printf '\n'
    [ -n "${ADMIN_PASSWORD:-}" ] || { echo '密码不能为空。' >&2; exit 2; }
    printf '%s' "$ADMIN_PASSWORD" > "$PASSWORD_FILE"
    unset ADMIN_PASSWORD;;
  *) echo '无法确定管理员初始化状态，已停止启动。' >&2; exit 2;;
esac
nohup env IPAM_DATA_DIR="$DATA_DIR" IPAM_STATIC_DIR="$BASE_DIR/frontend" IPAM_PORT="$PORT" IPAM_BIND="$BIND" IPAM_ADMIN_USERNAME="$ADMIN_USERNAME" IPAM_ADMIN_PASSWORD_FILE="$PASSWORD_FILE" java -jar "$APP_JAR" >> "$LOG_FILE" 2>&1 &
PID=$!; printf '%s\n' "$PID" > "$PID_FILE"
sleep 2
if kill -0 "$PID" 2>/dev/null; then
  DISPLAY_HOST=$BIND
  if [ "$DISPLAY_HOST" = "0.0.0.0" ]; then DISPLAY_HOST=$(hostname -I 2>/dev/null | awk '{print $1}'); fi
  [ -n "$DISPLAY_HOST" ] || DISPLAY_HOST='服务器IP'
  echo "已启动：PID=$PID，地址 http://$DISPLAY_HOST:$PORT/"
  echo "多网卡服务器请使用终端可达的服务器地址；配置文件：$IPAM_CONFIG_FILE"
  echo "日志：$LOG_FILE"
else
  echo "启动失败，最近日志如下：" >&2; tail -n 50 "$LOG_FILE" >&2; rm -f "$PID_FILE"; exit 1
fi
