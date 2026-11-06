#!/bin/sh
# Interactive first-install configuration. Existing configurations are never overwritten.
set -eu
BASE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
TARGET="$BASE_DIR/ipam.conf"
[ ! -e "$TARGET" ] || { echo "配置已存在：$TARGET。请编辑该文件，停止并重新启动服务后生效。"; exit 1; }
printf '监听地址（默认 0.0.0.0，监听本机全部网卡）：'; IFS= read -r NEW_BIND
printf '访问端口（默认 18080）：'; IFS= read -r NEW_PORT
printf '数据目录（绝对路径，默认 %s/data）：' "$BASE_DIR"; IFS= read -r NEW_DATA
umask 077
TEMP=$(mktemp "$BASE_DIR/.ipam-config.XXXXXX")
trap 'rm -f "$TEMP"' EXIT HUP INT TERM
printf 'IPAM_BIND=%s\nIPAM_PORT=%s\nIPAM_DATA_DIR=%s\n' "${NEW_BIND:-0.0.0.0}" "${NEW_PORT:-18080}" "${NEW_DATA:-$BASE_DIR/data}" > "$TEMP"
(unset IPAM_BIND IPAM_PORT IPAM_DATA_DIR IPAM_INSTALL_DIR; IPAM_CONFIG_FILE="$TEMP"; . "$BASE_DIR/deploy/load-config.sh")
# A no-clobber link prevents concurrent configuration from replacing another operator's file.
ln "$TEMP" "$TARGET"
echo "已保存：$TARGET。请运行 sh ./deploy/install-stage3.sh。服务器 IP 由运维配置，本工具不修改网卡。"
