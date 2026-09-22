# V023 MAC 地址修订版：独立复测

- 日期：2026-09-18；结论：**在 Windows 隔离环境内通过，可进入麒麟 ARM64 与人工验收**。
- 修订提交：`e77c9b9a04e59dde69e81ce64277f306545d7db9`（`fix: reject control characters in MAC input`）。
- 最终包：`deliverables/ipam-stage4-v023-mac-address-kylin-arm64-20260918-151325.zip`，29,695,063 bytes，SHA-256 `F0BD96A8AC4C01F085595849F57B013E6017E2FBBE50944BB4F1F07A95A3334E`；包内版本 `3.8.1-v023-mac-address`。
- 仅使用隔离测试库及临时解包目录；没有改动业务数据库、清理已有用户文件或在麒麟/IE11/真实网络设备上操作。

## 首轮阻断问题复测

从上述最终 ZIP 解包后的 `ipam-stage3.jar` 直接反射调用 `PhaseFourService.normalizeMac()`，独立构造输入：前缀 NUL、末尾 LF、前缀 CR、末尾 Tab、内部 LF、前缀 DEL 均返回拒绝；普通首尾空格、连字符格式、12 位连续十六进制格式均被接受并规范化为 `AA:BB:CC:DD:EE:FF`。修复是在 `trim()` 前检查原始字符。项目 `memory.md` 已记录根因、修复与防回归；后端自动用例也新增控制字符边界测试。

## 回归与交付包核验

1. 独立重跑 `scripts/test-stage4.ps1`，退出码 0；阶段 1–13、MAC 专项、Edge 页面流程、窄屏、科技管理员隔离、Node 语法检查均返回 `PASS|STAGE4_FULL_REGRESSION`。
2. 使用**最终 ZIP 内 JAR**执行 `StageThirteenMacAddressSmokeTest`，返回 `PASS|STAGE13_MAC_NORMALIZATION_SNAPSHOT_PRIVACY_EXPORT_REASSIGN_V020_UPGRADE`。输入为隔离的 V019 历史库副本，包含 2 条分配和 1 条释放；测试先升 V020 再升 V021。升级后 `schema_history` 共 21 个版本、最新 V021，分配和释放数量未变，旧记录 MAC 非空数均为 0；SQLite `integrity_check=ok`，`foreign_key_check` 无异常。
3. 最终 ZIP 的 `release-files.sha256` 共 57 项，逐项复算无缺失或不符；无 `..` 穿越路径。包内版本与安装脚本校验版本一致；包内前端 `app.js` 与当前源码 SHA-256 一致。
4. `git diff --check HEAD` 无错误；复测未改动已跟踪文件。既有未跟踪目录及资料保留。

## 剩余边界

此结论不是麒麟 ARM64 实机、IE11 或生产数据库验收。上线前按包内 `docs/release/stage4-v023-mac-test.md` 完成管理员/科技管理员人工核查、备份和回滚演练；真实网段和网络设备行为须在目标环境确认。部署脚本会在迁移前执行数据库备份，并检查发布文件与目标机环境。
