# V022 存量 IP 补录与网关调整：独立验收（首轮）

- 验收日期：2026-09-17；结论：**不通过，退回整改**。
- 代码：`e67de49 feat: add manual assignment and gateway adjustment`。
- 被验包：`deliverables/ipam-stage4-v022-manual-assignment-gateway-kylin-arm64-20260917-185511.zip`，29,651,169 bytes，SHA-256 `7D362FA96DB32B8B228DB9155E65FB815BAC694063B57CF615351F094946EEF2`。包内 `VERSION.txt` 显示 `3.7.0-v022-manual-assignment-gateway`。
- 范围：Windows 隔离 SQLite 与本机 Java；未在生产数据库、麒麟 ARM64、行内浏览器或真实网络设备上操作。

## 已通过

1. 独立重跑 `scripts/test-stage4.ps1`，得到阶段 1–11、Edge 页面回归、Node 语法和差异检查的 `PASS|STAGE4_FULL_REGRESSION`；新用例覆盖单条补录、资产条件字段、责任人隔离、三种来源状态释放、网关切换、并发补录。
2. 核对压缩包大小、SHA-256 与包内版本。已看到迁移、发布文件和安装脚本的交付结构；这不代表麒麟实机安装成功。
3. 失败的旧库升级仅发生在独立复制的测试库；失败后 `schema_history` 仍为 V019，2 条既有分配和 1 条释放事件仍在，`PRAGMA integrity_check` 返回 `ok`。

## 阻断问题

### P0：已有 V019 数据库无法升级到 V020

从 `.local-v020-acceptance/ipam.db` 用 SQLite `.backup` 制作独立副本。该副本在升级前为 V019，含 2 条分配、1 条释放事件。以 V022 的 `release/ipam-stage3.jar` 和独立数据目录启动时，立即抛出 `SQLITE_LOCKED: A table in the database is locked`，栈定位 `IpamApplication$Database.applyManualAssignmentMigration(IpamApplication.java:259)`，应用无法启动。

代码路径：`IpamApplication.java` 的 `apply()` 在查询 `schema_history` 的 `PreparedStatement` / `ResultSet` 尚未关闭时，发现 V019 已存在便直接调用 V020 重建表迁移；新库逐版本迁移走的是另一条路径，故阶段 11 新库测试未发现。应先完全关闭查询结果，再调用 V020 迁移，并增加“已有 V019 数据、分配和释放历史”的启动升级回归。整改后需对**交付 ZIP 内 JAR**复测，而不只对当前源码构建复测。

### P1：资源概览把系统保留网关重复计为停用

在阶段 11 生成的隔离数据库上执行与 `PhaseThreeService.overview()` 相同的统计条件，结果为：地址总数 1524、可用 1515、已分配 1、停用 7、人工保留 1、系统保留 6，分类合计 1530，比总数多 6。真正的非网关停用地址只有 1 个。`disabledCount` 条件为 `p.is_active=0 AND a.id IS NULL`，而系统保留网关也被设置为 `is_active=0`。应排除 `p.ip_address=r.gateway`，并为网关转移前后统计不重叠增加回归。

### P1：过期网关调整页面可能覆盖另一管理员的新网关

静态代码核对：页面从 `gateway-options` 读取旧网关用于确认摘要，但 POST 只提交 `newGateway` 和可选说明；`ManualAssignmentService.adjustGateway()` 只读**提交时的当前网关**，没有校验页面所见旧网关或版本。管理员甲打开旧页面后，乙先成功调整网关；甲仍可提交旧页面目标，服务端会从乙的新网关再次转移，和基线“刷新旧页面后不得覆盖更新”不符。应提交并校验 `expectedGateway` 或版本，旧页面返回冲突并要求刷新；补并发／过期页回归。

## 复测门禁

整改后重跑 V019 历史库复制升级、`PRAGMA foreign_key_check`、阶段 1–11、网关过期页冲突与统计互斥、最终 ZIP 哈希和解包后 JAR 启动。未通过前，不得称本次 V022 可投入升级。
