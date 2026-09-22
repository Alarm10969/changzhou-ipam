# V022 存量 IP 补录与网关调整：首轮问题整改独立复测

- 日期：2026-09-17
- 代码提交：`e721899 fix: close v019 migration queries and guard gateway updates`
- 结论：**开发机独立复测通过，可交付目标环境手工测试；麒麟 ARM64 和行内浏览器实机验收尚未进行。**
- 最终复测包：`deliverables/ipam-stage4-v022-manual-assignment-gateway-kylin-arm64-20260917-194619.zip`，29,654,909 bytes，SHA-256 `FAAD86DD7F37D26F60CDAFED53E97D5A59E6C56991FCD67778C7066A1863921A`；包内版本为 `3.7.0-v022-manual-assignment-gateway`。

## 三项首轮问题的复测

1. **V019 历史库升级：通过。** 从 `.local-v020-acceptance/ipam.db` 使用 SQLite `.backup` 建立独立副本（升级前 V019、2 条分配、1 条释放事件），使用**最终 ZIP 解包后的** `ipam-stage3.jar`、独立数据目录和 `127.0.0.1:18147` 启动；健康接口返回 `UP`。升级后 `schema_history` 达到 V020，两类历史数量仍为 2 和 1，`PRAGMA integrity_check` 为 `ok`，`foreign_key_check` 无输出。服务已停止；未接触原测试库或生产库。
2. **状态统计互斥：通过。** 独立运行的阶段 11 隔离库统计为：总数 1524＝可用 1515＋已分配 1＋停用 1＋人工保留 1＋系统保留 6。修订后的停用条件排除当前网关，阶段 11 用例还覆盖调整前后分类合计。
3. **过期网关页面：通过。** 服务端要求 `expectedGateway`，事务内与当前值比较；不一致返回 409 `GATEWAY_STALE`。页面提交所见旧网关并显示刷新入口；阶段 11 及真实 Edge 自动回归分别覆盖过期值拒绝和冲突提示。

## 交付包与回归

- 从最终 ZIP 独立解包，对 `release-files.sha256` 的 **53** 条文件逐项计算 SHA-256，缺失、格式错误或哈希不符均为 **0**；解包版本与安装脚本版本门禁一致。
- 独立重跑 `scripts/test-stage4.ps1`，阶段 1–12、浏览器阶段四回归、Node 语法及差异检查均返回 PASS，包括 `STAGE12_V019_EXISTING_ASSIGNMENT_RELEASE_UPGRADE` 和 `STAGE4_FULL_REGRESSION`。
- 旧库场景是在 Windows 本机 SQLite/Java 环境验证。未执行麒麟 ARM64 原机安装、生产数据备份恢复、行内 IE11/奇安信浏览器兼容或真实网关设备切换；这些仍是目标环境上线门禁。

## 手工部署与目标环境验收

将最终 ZIP 复制到既有 `/data/项目/IP管理系统` 后，以项目普通用户执行：

```sh
cd '/data/项目/IP管理系统' && unzip -o './ipam-stage4-v022-manual-assignment-gateway-kylin-arm64-20260917-194619.zip' && sh './deploy/install-stage3.sh'
```

部署前确认数据库备份和回滚条件；部署后按 `docs/release/stage4-v022-manual-test.md` 逐条核对补录、释放状态、责任科技管理员、网关转移和网络设备现状。系统不会自动修改真实网关、路由或 DHCP。
