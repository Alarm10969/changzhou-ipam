#!/bin/sh
# 一次性只读/临时麒麟 ARM64 门禁。不会安装软件、修改系统配置或启动正式服务。
set -u
BASE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
APP_JAR=${IPAM_JAR:-"$BASE_DIR/ipam-stage2.jar"}
LIB_DIR=${IPAM_LIB_DIR:-"$BASE_DIR/lib"}
DATA_DIR=${IPAM_DATA_DIR:-"$BASE_DIR/data"}
REPORT=${1:-"$BASE_DIR/kylin-stage2-report-$(date +%Y%m%d-%H%M%S).txt"}
PORT=${IPAM_PORT:-18080}
PASS=0; WARN=0; FAIL=0
record() { status=$1; key=$2; shift 2; value=$*; printf '%s|%s|%s\n' "$status" "$key" "$value" | tee -a "$REPORT"; case "$status" in PASS) PASS=$((PASS+1));; WARN) WARN=$((WARN+1));; FAIL|ERROR) FAIL=$((FAIL+1));; esac; }
command_line() { if command -v -- "$1" >/dev/null 2>&1; then "$@" 2>&1 | head -n 1; else printf 'NOT_FOUND'; fi; }
: > "$REPORT" || { printf '%s\n' "无法写入报告: $REPORT" >&2; exit 2; }
record INFO REPORT "$REPORT"
record INFO OS "$(grep '^PRETTY_NAME=' /etc/os-release 2>/dev/null | cut -d= -f2- | tr -d '"' || true)"
ARCH=$(uname -m 2>/dev/null || true); [ "$ARCH" = "aarch64" ] || [ "$ARCH" = "arm64" ] && record PASS ARCH "$ARCH" || record FAIL ARCH "需要 aarch64/arm64，实际 $ARCH"
JAVA_LINE=$(command_line java -version); case "$JAVA_LINE" in *'11.'*) record PASS JAVA11 "$JAVA_LINE";; *) record FAIL JAVA11 "$JAVA_LINE";; esac
for cmd in mvn node nginx mysql; do if command -v -- "$cmd" >/dev/null 2>&1; then record WARN "NO_RUNTIME_$cmd" "发现 $cmd；本发布包不依赖它"; else record PASS "NO_RUNTIME_$cmd" "未安装（符合运行期前提）"; fi; done
[ -r "$APP_JAR" ] && [ -d "$LIB_DIR" ] && record PASS RELEASE_LAYOUT "$APP_JAR" || record FAIL RELEASE_LAYOUT "需要发布 JAR 和 lib 目录"
mkdir -p "$DATA_DIR" 2>/dev/null || true
[ -d "$DATA_DIR" ] && [ -w "$DATA_DIR" ] && record PASS DATA_WRITABLE "$DATA_DIR" || record FAIL DATA_WRITABLE "$DATA_DIR"
FS=$(df -T "$DATA_DIR" 2>/dev/null | awk 'NR==2{print $2}'); case "$FS" in nfs*|cifs|smb*|'') record WARN DATA_FILESYSTEM "${FS:-unknown}（须由运维确认不是网络文件系统）";; *) record PASS DATA_FILESYSTEM "$FS";; esac
if [ -r "$APP_JAR" ] && [ -d "$LIB_DIR" ] && [ -w "$DATA_DIR" ]; then
  CP="$APP_JAR:$LIB_DIR/*"
  SMOKE=$(java -Djava.io.tmpdir="$DATA_DIR" -cp "$CP" com.icbc.changzhou.ipam.StageOneSmokeTest 2>&1); CODE=$?
  if [ $CODE -eq 0 ]; then record PASS SQLITE_JDBC_ARM64 "$(printf '%s' "$SMOKE" | tr '\n' ';')"; else record FAIL SQLITE_JDBC_ARM64 "exit=$CODE; $(printf '%s' "$SMOKE" | tr '\n' ';')"; fi
else record WARN SQLITE_JDBC_ARM64 "因发布包或数据目录不满足，未执行"; fi
if command -v -- ping >/dev/null 2>&1; then
  PING=$(ping -c 1 -W 1 127.0.0.1 2>&1); CODE=$?
  [ $CODE -eq 0 ] && record PASS PING "$PING" || record WARN PING "exit=$CODE; $PING"
else record WARN PING "未找到 ping；后续阶段不能把此情形当作 IP 不通"; fi
if command -v -- ss >/dev/null 2>&1; then
  LISTENERS=$(ss -ltn 2>/dev/null | awk -v port=":$PORT" '$4 ~ (port "$") {print $0}' | tr '\n' ';')
  if [ -n "$LISTENERS" ]; then record FAIL "PORT_AVAILABLE_$PORT" "端口已被占用: $LISTENERS"; else record PASS "PORT_AVAILABLE_$PORT" "未发现监听进程"; fi
else record WARN "PORT_AVAILABLE_$PORT" "未找到 ss，未检测端口"; fi
record INFO SUMMARY "PASS=$PASS WARN=$WARN FAIL=$FAIL"
[ "$FAIL" -eq 0 ] && exit 0 || exit 1
