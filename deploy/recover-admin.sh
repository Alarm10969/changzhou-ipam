#!/bin/sh
# 仅用于遗忘唯一管理员密码的恢复；先正常停止服务，不删除 SQLite 数据库。
set -eu
BASE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
APP_JAR=${IPAM_JAR:-"$BASE_DIR/ipam-stage3.jar"}
LIB_DIR=${IPAM_LIB_DIR:-"$BASE_DIR/lib"}
DATA_DIR=${IPAM_DATA_DIR:-"$BASE_DIR/data"}
DB="$DATA_DIR/ipam.db"
[ -r "$APP_JAR" ] && [ -d "$LIB_DIR" ] && [ -f "$DB" ] || { echo '发布包或数据库不存在。' >&2; exit 2; }
printf '%s\n' '本操作会重置唯一管理员密码、撤销其全部会话，但不会删除业务数据。确认服务已停止后按 Enter：'; read _answer
umask 077
PASSWORD_FILE="$DATA_DIR/.recovery-password.$$"
trap 'rm -f "$PASSWORD_FILE"' EXIT HUP INT TERM
printf '新管理员密码：'; stty -echo; read FIRST; stty echo; printf '\n'
printf '再次输入新管理员密码：'; stty -echo; read SECOND; stty echo; printf '\n'
[ "$FIRST" = "$SECOND" ] || { echo '两次密码不一致。' >&2; exit 2; }
printf '%s' "$FIRST" > "$PASSWORD_FILE"; unset FIRST SECOND
java -cp "$APP_JAR:$LIB_DIR/*" com.icbc.changzhou.ipam.DatabaseTool reset-admin "$DB" "$PASSWORD_FILE"
echo '重置完成。请运行 ./deploy/start-stage1.sh，并在首次登录后按提示再次修改密码。'
