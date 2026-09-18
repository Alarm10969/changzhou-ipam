# V023 MAC 地址增补：独立验收首轮

- 日期：2026-09-18；结论：**未通过，需修复 MAC 输入控制字符校验后复测**。
- 代码：`956db29 feat: add MAC address assignment snapshots`。
- 被验最终包：`deliverables/ipam-stage4-v023-mac-address-kylin-arm64-20260918-144338.zip`，29,688,749 bytes，SHA-256 `12E52E155305050C97C6DD67259B23C53B878BE0F3FCC5D43EA6D8C512C8ABB0`；包内版本 `3.8.0-v023-mac-address`。
- 仅在 Windows 本机、隔离 SQLite 与最终 ZIP 解包环境验证；未操作真实业务库、麒麟设备或真实网络。

## 通过的核验

1. 独立重跑 `scripts/test-stage4.ps1`，阶段 1–13、MAC 后端用例、Edge 页面回归、Node 语法和差异检查均返回 `PASS|STAGE4_FULL_REGRESSION`。
2. 最终 ZIP 独立解包，对 `release-files.sha256` **55** 个文件逐项复算 SHA-256，缺失、格式错误或哈希不符为 **0**；包内版本与交付版本一致。
3. 从含 2 条历史分配、1 条历史释放事件的 V020 数据库制作独立副本，以最终 ZIP 内 `ipam-stage3.jar` 启动升级；健康接口为 `UP`。升级后 `schema_history` 为 V021，历史数量保持 2 和 1，旧记录 MAC 非空数量均为 0；SQLite `integrity_check` 为 `ok`，`foreign_key_check` 无异常。隔离服务已停止。

## 阻断问题：首尾控制字符被静默吞掉

基线沿用交换机端口规则，要求 MAC 输入中的换行/控制字符在任何位置均被拒绝。最终 ZIP 内 JAR 的 `PhaseFourService.normalizeMac()` 对原始输入先调用 `String.trim()`，再做字符/格式检查；Java `trim()` 同时剥掉部分控制字符，导致前缀 NUL (`U+0000`) 和末尾 LF (`U+000A`) 被接受。

使用最终 ZIP 内 JAR 经 JShell 反射直接调用，实际得到：

```text
NUL_PREFIX_RESULT=AA:BB:CC:DD:EE:FF
LF_SUFFIX_RESULT=AA:BB:CC:DD:EE:FF
```

预期均返回 `MAC_INVALID`；当前却会写入规范化 MAC。问题与项目 `memory.md` 已记录过的“交换机端口先 trim 导致首尾控制字符绕过校验”同型。请在**任何 trim/归一化之前**检查原始输入控制字符，保留合法首尾普通空白处理；审批、补录、编辑三个入口均调用相同服务端校验。新增 NUL、LF/CR、Tab 等首尾和内部控制字符回归，用最终 ZIP JAR 复现修复结果，并把本次 Bug 根因、修复、防回归写入项目 `memory.md`。

## 复测门禁

修订版需重新交付提交 SHA、版本/压缩包路径/大小/SHA-256 和部署指令；独立复测控制字符拒绝、三种合法格式、空值、审批/补录/编辑、权限隔离、释放历史、导出、V020→V021 升级及阶段 1–13 回归。未复测通过前，不将 V023 标记为可验收发布。
