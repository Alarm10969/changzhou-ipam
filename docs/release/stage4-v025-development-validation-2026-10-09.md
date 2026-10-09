# V025 开发机验收记录

日期：2026-10-09。版本：`3.9.0-v025-resource-maintenance`，数据库最高迁移 V022，开发起点 Git `021e814fcbaf260bf861d7dffed54fddb5fd52fb`，工作分支 `feat/v025-resource-maintenance`。

全部测试使用临时隔离数据库与随机测试口令，未恢复或修改实际业务库。开发环境为 macOS ARM64、Java 11.0.31 和真实 Microsoft Edge；不依赖目标机安装 Node 或构建工具。

## 已通过

- 18 个固定离线依赖摘要校验、Java 11 编译、阶段 1–15 连续回归。历史升级保留 V019 分配与释放数据，连续迁移到 V022；V021 网页恢复在副本升级，源文件不变。
- 资源维护专项：当前编码唯一性与必填规则、原快照保留、版本冲突、MAC 自由文本及容量/控制字符、序列号及科技管理员隐私、分列交换机数据、旧端口迁移、占用批量原子性、候选排除、补录/撤销/释放、统计与 CSV/XLSX。
- 备份恢复专项：两个管理员均可下载/预览/取消/全量恢复；非管理员权限和 CSRF；ZIP/裸数据库、格式/摘要/迁移/结构/外键校验；全局非强制提醒；已有请求排空、恢复前原库快照、候选篡改拒绝、失败保留数据、会话撤销、未完成导入失效、替换中断回滚、已提交重启和损坏回滚副本拒绝。
- `scripts/http-v025-regression.py` 对真实临时 Java 服务执行账户权限、状态、维护字段、审计、隐私，以及两个管理员分别完成全量恢复、旧会话拒绝、按恢复账号重新登录和业务数据回退；全系统备份计时不会随恢复回退。
- 真实 Edge 执行 `browser-v025-regression.js`、既有 `browser-phase4-regression.js` 和审批候选分页回归；覆盖常用桌面/较窄屏、四种中文 PING、集中维护、当前页批量选择、备份上传/失败/取消/恢复、管理员专属字段与事件绑定。
- `scripts/browser-v025-live-regression.js` 将真实 Edge 接入临时 Java 服务，验证加载后的备份记录、实际 ZIP 下载、完整 `.db` 预览取消、确认全量恢复、自动回到登录页、使用备份时密码重新登录，以及备份后新增数据被移除；测试后停止进程并清理临时库。
- 前端 JavaScript、Python 构建/HTTP 测试脚本、安装脚本语法及 `git diff --check`。

## 证据边界

浏览器模拟接口回归用于校验页面交互，真实 HTTP 回归用于校验服务器、数据库及权限。最终麒麟 ZIP 的摘要清单覆盖全部交付文件；安装后的目标机检查仍按手工验收执行。

可重复执行的开发机入口：

```sh
python3 scripts/build-portable.py --java-home '/absolute/path/to/jdk11' --test --http-test --package
node scripts/browser-v025-regression.js
node scripts/browser-phase4-regression.js
node scripts/browser-approval-pagination-regression.js
node scripts/browser-v025-live-regression.js --java-home '/absolute/path/to/jdk11' --classes .local-v025-build/classes
```

浏览器测试使用开发机 Node、Playwright 与 Edge（可通过 `IPAM_PLAYWRIGHT`、`IPAM_EDGE` 指定已有安装）；不向目标机添加这些依赖。

未在麒麟 ARM64、IE11/奇安信或生产环境实机运行本版，开发机通过不能替代这些验收。未部署或执行真实数据恢复。网页恢复仅支持经过完整校验的 V021/V022，本轮不改写已发布迁移。
