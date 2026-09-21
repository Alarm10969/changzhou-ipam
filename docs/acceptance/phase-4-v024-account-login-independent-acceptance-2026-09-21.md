# V024 账号重建登录修订版：独立验收

- 日期：2026-09-21
- 功能结论：**通过**。
- 交付结论：**有条件通过；V024 源码和发布资料尚未提交 Git，缺少可追溯的 V024 提交 SHA。**
- 被验包：`deliverables/ipam-stage4-v024-account-login-kylin-arm64-20260920-173800.zip`
- 包大小：29,699,202 bytes
- SHA-256：`2A6F817F1065D2919274D919D3E704E57820ACD99ED6E1CD8B7A5E44DCCA9B8D`
- 包内版本：`3.8.2-v024-account-login`，数据库结构仍为 V001–V021。

## 独立验证结果

1. 独立执行 `scripts/test-stage4.ps1`，阶段 1–13、账号专项、MAC、网关、规划冲突、Edge 页面、窄屏、科技管理员隔离、前端语法和差异检查全部通过，最终返回 `PASS|STAGE4_FULL_REGRESSION`。
2. 独立解压最终 ZIP，直接运行包内 `ipam-stage3.jar`：
   - `StageEightBranchUserReuseSmokeTest` 返回 `PASS|STAGE8_SOFT_DELETED_BRANCH_AND_USER_REUSE_LOGIN_RESET`；
   - `StageThirteenMacAddressSmokeTest` 返回 `PASS|STAGE13_MAC_NORMALIZATION_SNAPSHOT_PRIVACY_EXPORT_REASSIGN_V020_UPGRADE`。
3. 使用最终 ZIP 内 JAR 和前端启动隔离服务，按真实 HTTP 接口完成：创建科技管理员、删除、以相同登录名重建管理员、旧密码拒绝、新密码登录、活动同名账号重复创建拒绝、连续五次错误口令锁定、管理员重置密码、重置后重新登录。结果分别为：创建 201、删除 204、重建 201、旧密码 401、新密码 200、重复创建 409、锁定后正确密码 401、重置 204、重置后登录 200。旧、新账号 ID 分别为 2 和 3；列表保留 1 条已删除历史和 1 条当前账号。
4. 隔离数据库 `integrity_check=ok`、`foreign_key_check` 无异常；创建、删除和重置密码审计记录均存在。未使用或修改业务数据库。
5. ZIP 共 60 个条目；`release-files.sha256` 覆盖 59 个文件，逐项复算错误 0，路径穿越项 0。安装脚本、前端、V024 发布说明和人工验收清单均在包内，安装脚本版本门禁与包内版本一致。

## 交付阻断项

当前 Git `HEAD` 仍为 V023 修复提交 `e77c9b9a04e59dde69e81ce64277f306545d7db9`。V024 的后端、测试、版本、安装脚本、前端、需求说明和 `memory.md` 均留在工作区修改或未跟踪状态，开发任务没有交付 V024 commit SHA。因此包的功能可用于隔离手工测试，但尚不能声明为“可由仓库提交稳定复现的最终版本”。

开发任务需只提交本次 V024 相关文件，保留其他用户未跟踪文件；提交后重新从该提交构建 ZIP，提供提交 SHA、包路径、大小和 SHA-256，再做一次包内账号专项及清单核验。麒麟 ARM64、IE11/奇安信和生产数据库仍需现场验收。
