# 常州 IP 资源审批分配管理系统

当前为**第一阶段**交付：工程与离线依赖、SQLite 迁移、三类账号体系、会话/CSRF/审计，以及支行、网点、地址段和 IP 池管理。

后续阶段（申请审批、释放、报表导出）尚未实现，不能提前作为已交付功能使用。

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

## 复制到麒麟机

在 Windows 构建机运行 `./scripts/package-kylin.ps1`，将生成的 ZIP 整包复制到麒麟机普通用户目录后解压。ZIP 内部即为一个完整发布根目录，包含 JAR、`lib`、前端、依赖校验清单和 `deploy` 脚本；不需要复制 `backend`、`frontend` 源码，也不需要在目标机安装构建工具。

在麒麟机用 `./deploy/start-stage1.sh` 启动。脚本会隐藏输入初始管理员密码，将其写入权限为 600 的一次性文件，应用读取后立即删除；不会把口令放进长期运行进程的环境变量。

## 安全操作边界

- 会话原始令牌仅保存在 `HttpOnly` Cookie；数据库只保存 SHA-256 摘要。
- 所有状态变更 API 要求登录会话、CSRF Token 与同源 Origin/Referer 校验，并记录审计日志。
- 重置密码、禁用账号和本人改密都会撤销该用户的全部旧会话；本人改密后会创建新会话。
- 备份使用 SQLite `VACUUM INTO` 在线快照；恢复脚本要求服务先停止，并先保留可回滚副本。
