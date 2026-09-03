# 常州 IP 资源审批分配管理系统

当前代码包含第一至第三阶段功能：一个支行可维护多个网点，每个网点最多绑定一个地址段；地址段页面采用“先选支行、再选网点”的两级绑定，绑定后自动生成全部主机 IP，网关自动设为系统保留，其余地址默认可用。支行、网点、地址段和具体 IP 状态均可编辑；审批只展示申请网点对应地址段中的可用 IP，并会在最终确认前自动 Ping 该地址作占用风险提示。IP 资源池采用“支行—网点—地址段”三级筛选，可按 IP、姓名或统一认证号检索，已分配项展示使用人资料，管理员可填写原因强制释放。网点、网点信息均提供分页；地址段、网点均支持筛选。独立“网点信息”资料库仅展示网点号、机构编码、名称、地址、电话和所属支行，支持筛选、编辑和 XLSX 导入，不参与资源分配。导出页可按数据类型勾选字段。详细范围与限制见 `docs/phase-2-requirements-and-acceptance.md`、`docs/phase-3-requirements-and-acceptance.md`。

导入模板由管理员接口下载：`/api/v1/admin/import-templates/{OUTLET|IP_RANGE|IP_POOL}.{csv|xlsx}`。上传、预览、确认和取消均要求登录、CSRF 与同源校验；正式确认会重新检查文件摘要和当前数据库状态，在一个 SQLite 事务内全量写入或回滚。

## 本地快速运行

要求：仅 JDK 11+。不需要 Maven、Node、MySQL 或 Nginx。

```powershell
$env:IPAM_ADMIN_USERNAME = 'admin'
$env:IPAM_ADMIN_PASSWORD = 'ChangeMe_2026!'
.\scripts\build-release.ps1
.\scripts\run-local.ps1
```

打开 `http://127.0.0.1:18080/`。首次启动只会在数据库没有管理员时读取上述两个环境变量创建管理员，并强制其首次改密；启动后请清除环境变量。

## 目录说明

- `backend/lib`：已固定版本并校验的离线运行依赖。
- `backend/src/main/resources/db/migration`：按版本执行且校验和受保护的 SQLite 迁移。
- `deploy/check-kylin-arm64.sh`：由目标机操作人员一次执行的只读/临时烟测与结构化报告。
- `deploy/backup-restore.sh`：停止服务后的备份恢复操作封装。
- `docs/acceptance/phase-1-acceptance.md`：第一阶段验收清单与证据边界。
- `docs/acceptance/phase-2-acceptance.md`：第二阶段开发机验证、发布包和实机验收边界。

## 数据与运行参数存储位置

麒麟默认安装根目录为 `/data/项目/IP管理系统`，默认数据目录为其下的 `data`。设置 `IPAM_DATA_DIR` 时，下列数据文件会整体迁移到指定目录。

- `data/ipam.db`：SQLite 主数据库，保存支行、网点、地址段、IP 地址池、默认 DNS、账号、会话摘要、申请、分配、导入记录、通知和审计日志。
- `data/ipam.db-wal`、`data/ipam.db-shm`：SQLite WAL 运行文件，仅在服务运行或尚未完成检查点时存在，不应单独复制或删除。
- `data/imports/<任务号>.upload`：批量导入“预览成功、尚未确认”的临时原文件；确认或取消后删除。文件名、摘要、结果和错误明细保存在 `ipam.db`。
- `data/backups/*.db`：安装升级或手工备份生成的完整 SQLite 快照。
- `data/ipam-stage1.log`：应用运行日志；`data/ipam-stage1.pid`：由启停脚本维护的进程号。
- `data/.bootstrap-password.*`：启动脚本创建的 600 权限一次性口令文件，应用读取后立即删除；不应长期存在。
- 默认 DNS 与释放倒计时等业务参数保存在 `ipam.db` 的 `system_config` 表。端口、绑定地址和数据目录等启动参数来自 `IPAM_PORT`、`IPAM_BIND`、`IPAM_DATA_DIR` 环境变量，未设置时分别使用 `18080`、`0.0.0.0` 和安装目录下的 `data`。

## 复制到麒麟机

在 Windows 构建机运行 `./scripts/package-kylin.ps1`，将生成的 ZIP 整包复制到麒麟机普通用户目录后解压。ZIP 内部即为一个完整发布根目录，包含 JAR、`lib`、前端、依赖校验清单和 `deploy` 脚本；不需要复制 `backend`、`frontend` 源码，也不需要在目标机安装构建工具。

在麒麟机优先用 `./deploy/install-stage3.sh` 完成校验、备份和启动。底层启动脚本会隐藏输入初始管理员密码，将其写入权限为 600 的一次性文件，应用读取后立即删除；不会把口令放进长期运行进程的环境变量。

## 麒麟一键安装

将本次生成的 `ipam-stage2-usability-kylin-arm64-时间戳.zip` 复制到麒麟机既有目录 `/data/项目/IP管理系统` 后执行以下一条命令。它不会创建项目路径；会在原路径解压、校验发布文件和离线依赖、停止由本项目脚本管理的旧进程、备份已有数据库、执行 ARM64 预检，然后以普通用户身份启动。已有 `data/ipam.db` 不会被删除或重建。

```sh
cd '/data/项目/IP管理系统' && unzip -o './ipam-stage2-usability-kylin-arm64-YYYYMMDD-HHmm.zip' && sh './deploy/install-stage2.sh'
```

## 安全操作边界

- 会话原始令牌仅保存在 `HttpOnly` Cookie；数据库只保存 SHA-256 摘要。
- 所有状态变更 API 要求登录会话、CSRF Token 与同源 Origin/Referer 校验，并记录审计日志。
- 重置密码、禁用账号和本人改密都会撤销该用户的全部旧会话；本人改密后会创建新会话。
- 备份使用 SQLite `VACUUM INTO` 在线快照；恢复脚本要求服务先停止，并先保留可回滚副本。
- 初始管理员由数据库唯一索引和触发器双重保护，不能停用、删除、降级或取消保护；离线恢复仅作用于此账号。
- Apache POI 5.5.1 及其固定离线依赖用于只读 XLSX；宏、公式、外部链接、加密和超限文件会被拒绝。

## 开发机回归

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\test-stage2.ps1
```

该入口先执行第一阶段回归，再执行 V003–V006、初始管理员触发器、CSV/XLSX 导入、默认 DNS、组织级联软删除、网点唯一地址段绑定、自动地址池、编辑与申请审批分配烟测。它不替代麒麟 ARM64、IE11/奇安信实机验收。
