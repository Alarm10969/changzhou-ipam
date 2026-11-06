#!/bin/sh
# Shared by operational entry points. Configuration is data, never executed as shell code.
if [ -n "${IPAM_CONFIG_FILE:-}" ] && [ ! -f "$IPAM_CONFIG_FILE" ]; then
  echo '指定的部署配置文件不存在。' >&2; exit 2
fi
IPAM_CONFIG_FILE=${IPAM_CONFIG_FILE:-"$BASE_DIR/ipam.conf"}
if [ -e "$IPAM_CONFIG_FILE" ]; then
  [ -r "$IPAM_CONFIG_FILE" ] || { echo '无法读取部署配置文件。' >&2; exit 2; }
  while IFS= read -r ipam_line || [ -n "$ipam_line" ]; do
    ipam_line=$(printf '%s' "$ipam_line" | tr -d '\r')
    case "$ipam_line" in ''|'#'*) continue;; esac
    case "$ipam_line" in *=*) ;; *) echo '配置格式错误，应为 KEY=value。' >&2; exit 2;; esac
    ipam_key=${ipam_line%%=*}; ipam_value=${ipam_line#*=}
    case "$ipam_key" in
      IPAM_BIND) IPAM_BIND=${IPAM_BIND:-$ipam_value};;
      IPAM_PORT) IPAM_PORT=${IPAM_PORT:-$ipam_value};;
      IPAM_DATA_DIR) IPAM_DATA_DIR=${IPAM_DATA_DIR:-$ipam_value};;
      IPAM_INSTALL_DIR) IPAM_INSTALL_DIR=${IPAM_INSTALL_DIR:-$ipam_value};;
      *) echo "未知配置项：$ipam_key" >&2; exit 2;;
    esac
  done < "$IPAM_CONFIG_FILE"
fi
IPAM_BIND=${IPAM_BIND:-0.0.0.0}
IPAM_PORT=${IPAM_PORT:-18080}
IPAM_DATA_DIR=${IPAM_DATA_DIR:-"$BASE_DIR/data"}
IPAM_INSTALL_DIR=${IPAM_INSTALL_DIR:-"$BASE_DIR"}
case "$IPAM_PORT" in ''|*[!0-9]*) echo '端口必须是 1–65535 的整数。' >&2; exit 2;; esac
if [ "${#IPAM_PORT}" -gt 5 ] || [ "$IPAM_PORT" -lt 1 ] || [ "$IPAM_PORT" -gt 65535 ]; then echo '端口必须是 1–65535 的整数。' >&2; exit 2; fi
if ! printf '%s\n' "$IPAM_BIND" | awk -F. 'NF!=4{exit 1} {for(i=1;i<=4;i++)if($i!~/^[0-9]+$/ || length($i)>3 || $i+0>255)exit 1}'; then
  echo '监听地址须为本机 IPv4 地址或 0.0.0.0。' >&2; exit 2
fi
case "$IPAM_DATA_DIR" in /*) ;; *) echo '数据目录必须填写绝对路径，不加引号。' >&2; exit 2;; esac
case "$IPAM_INSTALL_DIR" in /*) ;; *) echo '安装目录必须填写绝对路径，不加引号。' >&2; exit 2;; esac
if [ -f "$IPAM_CONFIG_FILE" ]; then export IPAM_CONFIG_FILE; fi
export IPAM_BIND IPAM_PORT IPAM_DATA_DIR IPAM_INSTALL_DIR
