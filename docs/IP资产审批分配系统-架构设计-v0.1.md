# IP资产审批分配系统架构设计 v0.2

> 状态：第一阶段已有实现；第二阶段新增需求以 `docs/phase-2-requirements-and-acceptance.md` 为准。本文件仍只描述总体架构和阶段计划。
>
> 依据：`IP资源审批分配管理系统-需求文档.docx`、`D:\CODE\Projects\notes\mailsmanager-env-report.txt`、`D:\CODE\Projects\notes\MEMORY.md`。

## 1. 已确认约束与待验收项

### 1.1 已确认

- 目标机：Kylin V10 SP1、ARM64（aarch64）、Java 11.0.19、Python 3.8.2、UTF-8。
- 目标机未安装 Node.js 和 Nginx；运行期不能依赖它们。
- 行内环境按普通用户权限部署，不要求 root/sudo，不擅自安装系统软件。
- 浏览器最低兼容 IE11；前端产物必须转译为 ES5，并补齐必要 polyfill。
- 系统账号独立于行内统一认证。当前角色为管理员、科技管理员；B角已从现行范围取消。
- “注册”定义为管理员开通账号，不提供公网式自助注册接口。
- 管理员和科技管理员登录后可以自助修改本人的密码；必须先验证当前密码，改密成功后撤销该账号其他会话。
- 初始管理员可以创建其他管理员；所有管理员业务权限相同，但初始管理员永久禁止停用、删除、降级或取消保护。其他管理员可停用或软删除，任何管理员不能停用或删除自己。
- 自动分配 IP 不在本期范围；管理员审批时必须手动选择具体 IP。

### 1.2 开发前必须补做的环境门禁

用户实机执行 `mvn -version` 和 `mysql --version` 均提示“未找到命令”，说明当前 Shell 无法解析这两个可执行程序（未安装或未加入 PATH）。采用预构建 JAR + 内嵌 SQLite 后，目标机不需要 Maven、MySQL 客户端或 MySQL 服务，不应为本项目擅自安装它们。编码前仍需只读确认：

1. 在 Windows 构建机确认 Maven Wrapper/离线依赖可生成包含 Linux ARM64 SQLite 原生库的可执行 JAR；目标机只运行 JAR。
2. 在麒麟实机执行最小 JDBC 烟测，确认 Xerial SQLite 原生库可解压、加载，并能在指定数据目录创建数据库/WAL/SHM 文件。
3. `ping` 在普通用户下的退出码和超时参数是否可用。
4. 数据目录必须位于本机磁盘，不得放在 NFS/SMB 等网络文件系统；确认容量、权限、备份和恢复位置。
5. 应用监听地址、端口、HTTPS/证书方案、行内防火墙放行方式。
6. IE11 真实终端版本、文档模式、奇安信浏览器兼容模式和 TLS 能力。

## 2. 技术选型

### 2.1 推荐栈

| 层次 | 选型 | 原因与边界 |
|---|---|---|
| 前端 | React 17.0.2 + TypeScript 4.9 + Webpack 5 + Babel 7 + core-js 3 | React 18 已放弃 IE；React 官方建议 IE 项目停留 React 17。构建目标固定为 IE11/ES5。由于 React 17 属于旧代技术，依赖必须锁版本并做漏洞扫描。 |
| UI | 自建轻量业务组件，不引入依赖现代 CSS/浏览器 API 的大型组件库 | 降低 IE11 兼容风险；布局使用 Flexbox 并提供 block/table fallback，不使用 CSS Grid。 |
| 图表 | Apache ECharts 4.9.x，Canvas 渲染 | ECharts 4 官方明确兼容 IE11；固定版本、离线打包、不使用 CDN。 |
| 后端 | Java 11 + Spring Boot 2.7.18 + Spring MVC/Security/JDBC | 目标机已存在 Java 11；可在开发机打成单个可执行 JAR，目标机无需 Maven。Spring Boot 3/4 需要更高 Java 版本，不符合现状。 |
| 数据访问 | Spring JDBC + 显式 SQL + 版本化迁移脚本 | 显式 SQL 易审计；迁移通过 `schema_history` 记录版本与校验和，不依赖目标机数据库工具。 |
| 数据库 | SQLite + Xerial sqlite-jdbc 3.53.1.0（候选锁定版本） | 该包包含 Linux ARM64 原生库，且内含 SQLite 版本晚于 WAL-reset 修复版本。约30名用户、单机单实例、短写事务场景可行；无需数据库服务。必须使用本地磁盘、WAL、外键、忙等待和在线备份，并通过 ARM64 实机烟测。 |
| 部署 | 前端在 Windows 构建为静态 `dist`，随 Spring Boot JAR 同源发布；普通用户级 systemd 服务 | 目标机无 Node/Nginx；同源部署可减少 CORS、Cookie 和 IE 路径兼容问题。 |
| 测试 | JUnit 5、Spring MockMvc、临时 SQLite 文件、Jest + IE11 构建烟测 | 覆盖并发占用、锁等待、崩溃恢复、备份恢复；明确区分开发机测试、麒麟 ARM64 SQLite 测试和 IE11 实机验收。 |

### 2.2 版本风险

IE11 已停止主流支持，迫使前端停留旧代运行时；Spring Boot 2.7.18 也是 Java 11 条件下的兼容基线，而非长期最优版本。因此需设置两项治理措施：

- 依赖只能来自行内批准的离线制品库，锁定校验和并执行 SCA/漏洞审查；若银行安全基线不允许旧代依赖，则“升级目标 JRE 至 17+”必须先立项。
- 浏览器兼容代码集中在 `legacy/` 和 polyfill 入口，业务领域/API 不绑定 IE，便于未来移除兼容层。

SQLite 不是“没有并发限制”：WAL 允许读写并行，但同一时刻仍只有一个写者。因此本系统必须保持单应用实例，把所有写事务做短，并对写操作排队；如果未来扩展为多实例、数百并发用户或持续批量写入，再迁移到行内数据库服务。SQLite 文件、`-wal` 和 `-shm` 必须位于同一台主机的本地磁盘。

不推荐 Python 标准库实现本系统：虽然目标机有 Python 3.8.2 和内置 SQLite，但缺少可安装第三方库的条件，难以在账号安全、会话、审计和导出方面达到生产要求。Java 侧使用随发布包携带的 SQLite JDBC 驱动，不依赖系统 `sqlite3` 命令。

## 3. 系统架构

### 3.1 逻辑架构

```text
IE11/奇安信兼容模式
        |
        | HTTPS（同源）/ JSON UTF-8
        v
Spring Boot 单体应用
  ├─ 静态前端资源
  ├─ REST API /api/v1
  ├─ 账号、会话、RBAC、CSRF
  ├─ 组织、申请、审批、分配、释放
  ├─ 仪表盘、导出、站内通知、审计
  └─ 定时释放扫描器
        |
        | JDBC + 短事务/单写者队列
        v
本机 SQLite（数据库 + WAL + SHM）
```

采用“模块化单体”，而不是微服务。当前规模下它更容易离线部署、备份、排错和执行跨模块事务；各领域模块保持清晰边界，未来可按模块拆分。

### 3.2 API 与鉴权

- API：RESTful JSON，统一前缀 `/api/v1`；统一错误结构含 `code`、`message`、`requestId`，不返回堆栈和 SQL。
- 鉴权：不采用存入 `localStorage` 的 JWT。使用 256 位随机不透明会话令牌，浏览器只保存 `HttpOnly` Cookie，数据库只保存令牌 SHA-256 摘要；禁用账号或重置密码时可立即撤销全部会话。
- Cookie：`HttpOnly`、`SameSite=Lax`；生产启用 HTTPS 后设置 `Secure`。登录成功轮换会话 ID。
- CSRF：所有写接口要求独立 CSRF Token；同时校验 Origin/Referer（在行内代理允许的前提下）。
- 密码：BCrypt（cost 12，最终按目标机压测调整）；不存明文、可逆密文或静态默认密码。初始管理员通过一次性启动参数/控制台引导创建，并强制首次改密。
- 自助改密：管理员和科技管理员登录后输入当前密码、新密码和确认密码；验证通过后更新摘要、强制退出其他设备并写审计。忘记密码仍由管理员重置，系统不设置密保问题。
- 登录防护：失败次数限制、临时锁定、统一失败提示、登录/重置/启禁用全量审计。
- 权限：ADMIN 管理全局且管理员之间业务权限相同；TECH_ADMIN 仅使用用户端功能。唯一初始管理员由保护标记区分，但不额外增加业务权限。

### 3.3 核心事务和状态机

申请状态：

```text
PENDING --审批并成功创建分配记录--> APPROVED
PENDING --填写驳回原因------------> REJECTED
```

“审批通过”和“创建分配记录”在同一数据库事务完成，不允许出现已批准但未分配 IP 的中间状态。SQLite 不支持 `SELECT ... FOR UPDATE`，审批事务必须：

1. 进入应用内单写者队列，以 `BEGIN IMMEDIATE` 尽早取得写锁。
2. 再次校验申请仍为 `PENDING`、IP 属于申请支行且可用。
3. 插入 `ip_allocation`，由部分唯一索引兜底防止重复占用。
4. 更新申请为 `APPROVED`，写入审批人和时间，提交事务；遇到 `SQLITE_BUSY` 仅做有限重试并返回明确提示。

管理员手动选择 IP 的界面不是普通下拉框。审批详情页显示“选择可用 IP”按钮，点击后打开可搜索、可分页的表格弹窗，列出 IP、所属地址段、子网掩码、网关、DNS 和可用状态；支持按 IP/地址段过滤，管理员单选一行后确认。页面不默认替管理员选中任何 IP，提交时后端仍按上述事务重新校验，避免两名管理员同时选中同一地址。

分配状态：

```text
ASSIGNED --用户撤销--> PENDING_RELEASE --用户/管理员取消--> ASSIGNED
PENDING_RELEASE --到期且 ping 不通/管理员确认--> RELEASED
PENDING_RELEASE --到期但 ping 通或检测错误--> RELEASE_EXCEPTION
RELEASE_EXCEPTION --取消释放--> ASSIGNED
RELEASE_EXCEPTION --管理员强制--> RELEASED
```

- 发起撤销时把当前配置值复制为固定的 `release_deadline`，以后修改配置不影响已发起记录。
- 定时器只处理到期且仍为 `PENDING_RELEASE` 的记录，并使用行锁/乐观锁保证幂等。
- ping 只允许检测数据库中、且属于已配置地址段的规范化 IP；使用固定参数数组启动进程，禁止拼接 shell 命令。超时、命令不存在或权限错误一律进入 `RELEASE_EXCEPTION`，绝不当成“不通”自动释放。

### 3.4 数据权限和安全补充

- 科技管理员不能用任意统一认证号查询他人资产，只能查询本人数据；管理员可查询全局数据。
- `branch_id`、`outlet_id`、姓名等在申请和分配表保留业务快照，避免组织/用户改名破坏历史审计。
- 资源编码仅科技资产必填；非科技资产必须归一化为 `NULL`，不能用空字符串绕过规则。
- 导出 CSV/XLSX 时对以 `= + - @` 开头的文本做公式注入防护，并记录导出人、范围和字段。
- 所有审批、分配、撤销、强制释放、账号操作和配置修改写入不可由普通用户修改的审计日志。
- 网点、地址段和IP资源池支持CSV/XLSX批量导入；采用上传校验预览、再次确认、整批事务提交，默认只新增且有一行错误即整批不写入。

## 4. 工程目录

```text
常州IP分配系统/
├─ AGENTS.md
├─ memory.md
├─ README.md
├─ docs/
│  ├─ IP资产审批分配系统-架构设计-v0.1.md
│  ├─ api/
│  ├─ deployment/
│  └─ acceptance/
├─ backend/
│  ├─ pom.xml
│  ├─ src/main/java/com/icbc/changzhou/ipam/
│  │  ├─ IpamApplication.java
│  │  ├─ common/            # 错误模型、分页、时间、请求ID
│  │  ├─ config/            # 安全、数据库、调度、序列化
│  │  ├─ auth/              # 登录、会话、CSRF、密码策略
│  │  ├─ user/              # 管理员、科技管理员和初始管理员保护
│  │  ├─ organization/      # 支行、网点、地址段、IP池
│  │  ├─ application/       # IP申请
│  │  ├─ allocation/        # 审批、手动分配
│  │  ├─ release/           # 撤销、ping、定时释放
│  │  ├─ dashboard/         # 统计查询
│  │  ├─ export/            # CSV/XLSX
│  │  ├─ notification/      # 站内通知
│  │  └─ audit/             # 审计日志
│  ├─ src/main/resources/
│  │  ├─ application.yml
│  │  ├─ db/migration/      # schema_history管理的版本化SQL
│  │  └─ static/            # 发布时复制前端dist
│  └─ src/test/
│     ├─ java/
│     └─ resources/
├─ frontend/
│  ├─ package.json
│  ├─ package-lock.json
│  ├─ babel.config.js
│  ├─ tsconfig.json
│  ├─ webpack.config.js
│  ├─ public/
│  └─ src/
│     ├─ api/
│     ├─ auth/
│     ├─ components/
│     ├─ views/user/
│     ├─ views/admin/
│     ├─ router/
│     ├─ store/
│     ├─ legacy/            # IE11专用适配
│     ├─ styles/
│     ├─ polyfills.ts
│     └─ index.tsx
├─ deploy/
│  ├─ check-env.sh
│  ├─ install-user.sh
│  ├─ uninstall-user.sh
│  ├─ systemd-user/
│  └─ verify-package.sh
└─ scripts/
   ├─ build-release.ps1
   └─ verify-release.ps1
```

## 5. 数据库设计

### 5.1 设计说明

- 业务枚举在数据库中使用稳定英文码，中文只用于界面展示。
- `ip_address_pool` 是必要补充表：只有地址段而没有逐 IP 清单，无法可靠地用表格选择 IP、统计空闲量和做并发占用。
- `sys_session` 支持立即撤销会话；`audit_log` 支持安全审计；第二阶段的 `bulk_import_job` 和 `bulk_import_error` 见阶段需求文档。
- 每个 JDBC 连接都必须执行 `PRAGMA foreign_keys=ON` 和 `PRAGMA busy_timeout=5000`；应用启动时确认 `PRAGMA journal_mode=WAL` 返回 `wal`，并使用 `PRAGMA synchronous=FULL` 优先保证断电耐久性。
- SQLite 写入经应用内队列串行化，事务必须短；应用只允许单实例运行。唯一性和外键由数据库兜底，复杂跨字段规则由服务层和自动化测试强制。
- SQLite 部分唯一索引实现“只有未释放记录占用 IP/资源编码”，保留完整历史的同时允许释放后再次使用。
- 备份必须使用 SQLite 在线备份 API或在受控 checkpoint 后备份，不能在运行中只复制 `.db` 而遗漏 `-wal` 文件。

### 5.2 建表 SQL（SQLite）

```sql
-- 以下连接级配置必须由应用对每个连接执行并检查结果。
PRAGMA foreign_keys = ON;
PRAGMA busy_timeout = 5000;
PRAGMA journal_mode = WAL;
PRAGMA synchronous = FULL;

-- 版本化迁移记录。checksum用于发现已发布脚本被篡改。
CREATE TABLE schema_history (
  version TEXT PRIMARY KEY,
  description TEXT NOT NULL,
  checksum TEXT NOT NULL,
  installed_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);

-- 支行表。
CREATE TABLE branch (
  id INTEGER PRIMARY KEY,
  code TEXT NOT NULL UNIQUE CHECK(length(code) BETWEEN 1 AND 32),
  name TEXT NOT NULL CHECK(length(name) BETWEEN 1 AND 100),
  is_active INTEGER NOT NULL DEFAULT 1 CHECK(is_active IN (0, 1)),
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
  updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);
CREATE INDEX idx_branch_active_name ON branch(is_active, name);

-- 网点/机关部室表。
CREATE TABLE outlet (
  id INTEGER PRIMARY KEY,
  branch_id INTEGER NOT NULL,
  code TEXT NOT NULL CHECK(length(code) BETWEEN 1 AND 32),
  name TEXT NOT NULL CHECK(length(name) BETWEEN 1 AND 100),
  is_active INTEGER NOT NULL DEFAULT 1 CHECK(is_active IN (0, 1)),
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
  updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
  UNIQUE(branch_id, code),
  FOREIGN KEY(branch_id) REFERENCES branch(id) ON UPDATE RESTRICT ON DELETE RESTRICT
);
CREATE INDEX idx_outlet_branch_active ON outlet(branch_id, is_active, name);

-- 支行可分配的CIDR地址段。
CREATE TABLE ip_range (
  id INTEGER PRIMARY KEY,
  branch_id INTEGER NOT NULL,
  cidr TEXT NOT NULL CHECK(length(cidr) BETWEEN 3 AND 43),
  subnet_mask TEXT NOT NULL CHECK(length(subnet_mask) BETWEEN 3 AND 45),
  gateway TEXT NOT NULL CHECK(length(gateway) BETWEEN 3 AND 45),
  dns_primary TEXT NOT NULL CHECK(length(dns_primary) BETWEEN 3 AND 45),
  dns_secondary TEXT NULL CHECK(dns_secondary IS NULL OR length(dns_secondary) BETWEEN 3 AND 45),
  is_active INTEGER NOT NULL DEFAULT 1 CHECK(is_active IN (0, 1)),
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
  updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
  UNIQUE(branch_id, cidr),
  FOREIGN KEY(branch_id) REFERENCES branch(id) ON UPDATE RESTRICT ON DELETE RESTRICT
);

-- 可供管理员在表格弹窗中选择的逐IP明细。
CREATE TABLE ip_address_pool (
  id INTEGER PRIMARY KEY,
  ip_range_id INTEGER NOT NULL,
  ip_address TEXT NOT NULL UNIQUE CHECK(length(ip_address) BETWEEN 3 AND 45),
  is_reserved INTEGER NOT NULL DEFAULT 0 CHECK(is_reserved IN (0, 1)),
  reservation_reason TEXT NULL CHECK(reservation_reason IS NULL OR length(reservation_reason) <= 200),
  is_active INTEGER NOT NULL DEFAULT 1 CHECK(is_active IN (0, 1)),
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
  updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
  FOREIGN KEY(ip_range_id) REFERENCES ip_range(id) ON UPDATE RESTRICT ON DELETE RESTRICT
);
CREATE INDEX idx_ip_pool_range_available
  ON ip_address_pool(ip_range_id, is_active, is_reserved, ip_address);

-- 独立系统账号。初始管理员只有一个并受到永久保护。
CREATE TABLE sys_user (
  id INTEGER PRIMARY KEY,
  username TEXT NOT NULL COLLATE NOCASE UNIQUE CHECK(length(username) BETWEEN 3 AND 64),
  password_hash TEXT NOT NULL CHECK(length(password_hash) BETWEEN 40 AND 255),
  real_name TEXT NOT NULL CHECK(length(real_name) BETWEEN 1 AND 100),
  role TEXT NOT NULL CHECK(role IN ('ADMIN','TECH_ADMIN')),
  is_initial_admin INTEGER NOT NULL DEFAULT 0 CHECK(is_initial_admin IN (0, 1)),
  is_active INTEGER NOT NULL DEFAULT 1 CHECK(is_active IN (0, 1)),
  must_change_password INTEGER NOT NULL DEFAULT 1 CHECK(must_change_password IN (0, 1)),
  failed_login_count INTEGER NOT NULL DEFAULT 0 CHECK(failed_login_count >= 0),
  locked_until TEXT NULL,
  password_changed_at TEXT NULL,
  last_login_at TEXT NULL,
  created_by INTEGER NULL,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
  updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
  deleted_at TEXT NULL,
  version INTEGER NOT NULL DEFAULT 0 CHECK(version >= 0),
  CHECK(is_initial_admin = 0 OR (role = 'ADMIN' AND is_active = 1 AND deleted_at IS NULL)),
  FOREIGN KEY(created_by) REFERENCES sys_user(id) ON UPDATE RESTRICT ON DELETE RESTRICT
);
CREATE UNIQUE INDEX uk_sys_user_single_initial_admin
  ON sys_user(is_initial_admin) WHERE is_initial_admin = 1;
CREATE INDEX idx_sys_user_role_state ON sys_user(role, is_active, deleted_at);

CREATE TRIGGER trg_initial_admin_no_delete
BEFORE DELETE ON sys_user
WHEN OLD.is_initial_admin = 1
BEGIN
  SELECT RAISE(ABORT, 'INITIAL_ADMIN_PROTECTED');
END;

CREATE TRIGGER trg_initial_admin_no_disable_or_demote
BEFORE UPDATE ON sys_user
WHEN OLD.is_initial_admin = 1
 AND (NEW.is_initial_admin <> 1 OR NEW.role <> 'ADMIN'
      OR NEW.is_active <> 1 OR NEW.deleted_at IS NOT NULL)
BEGIN
  SELECT RAISE(ABORT, 'INITIAL_ADMIN_PROTECTED');
END;

-- IP申请记录；applicant_id是业务归属人，submitted_by是实际操作者。
CREATE TABLE ip_application (
  id INTEGER PRIMARY KEY,
  application_no TEXT NOT NULL UNIQUE CHECK(length(application_no) BETWEEN 1 AND 32),
  applicant_id INTEGER NOT NULL,
  submitted_by INTEGER NOT NULL,
  auth_id TEXT NOT NULL CHECK(length(auth_id) BETWEEN 1 AND 32),
  applicant_name TEXT NOT NULL CHECK(length(applicant_name) BETWEEN 1 AND 100),
  branch_id INTEGER NOT NULL,
  outlet_id INTEGER NOT NULL,
  asset_type TEXT NOT NULL CHECK(asset_type IN ('TECH','NON_TECH')),
  resource_code TEXT NULL CHECK(resource_code IS NULL OR length(resource_code) BETWEEN 1 AND 64),
  device_type TEXT NULL CHECK(device_type IS NULL OR device_type IN ('PC','OTHER')),
  status TEXT NOT NULL DEFAULT 'PENDING'
    CHECK(status IN ('PENDING','APPROVED','REJECTED')),
  reviewer_id INTEGER NULL,
  review_comment TEXT NULL CHECK(review_comment IS NULL OR length(review_comment) <= 500),
  reviewed_at TEXT NULL,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
  updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
  version INTEGER NOT NULL DEFAULT 0 CHECK(version >= 0),
  CHECK(
    (asset_type = 'TECH' AND resource_code IS NOT NULL AND device_type IS NOT NULL)
    OR
    (asset_type = 'NON_TECH' AND resource_code IS NULL AND device_type IS NULL)
  ),
  CHECK(status <> 'REJECTED' OR (review_comment IS NOT NULL AND length(trim(review_comment)) > 0)),
  FOREIGN KEY(applicant_id) REFERENCES sys_user(id) ON UPDATE RESTRICT ON DELETE RESTRICT,
  FOREIGN KEY(submitted_by) REFERENCES sys_user(id) ON UPDATE RESTRICT ON DELETE RESTRICT,
  FOREIGN KEY(reviewer_id) REFERENCES sys_user(id) ON UPDATE RESTRICT ON DELETE RESTRICT,
  FOREIGN KEY(branch_id) REFERENCES branch(id) ON UPDATE RESTRICT ON DELETE RESTRICT,
  FOREIGN KEY(outlet_id) REFERENCES outlet(id) ON UPDATE RESTRICT ON DELETE RESTRICT
);
CREATE UNIQUE INDEX uk_application_pending_resource
  ON ip_application(resource_code)
  WHERE status = 'PENDING' AND asset_type = 'TECH';
CREATE INDEX idx_application_status_time ON ip_application(status, created_at);
CREATE INDEX idx_application_scope ON ip_application(branch_id, outlet_id, status);
CREATE INDEX idx_application_auth ON ip_application(auth_id, created_at);

-- IP分配及完整释放历史。
CREATE TABLE ip_allocation (
  id INTEGER PRIMARY KEY,
  application_id INTEGER NOT NULL UNIQUE,
  ip_pool_id INTEGER NOT NULL,
  auth_id TEXT NOT NULL CHECK(length(auth_id) BETWEEN 1 AND 32),
  applicant_name TEXT NOT NULL CHECK(length(applicant_name) BETWEEN 1 AND 100),
  branch_id INTEGER NOT NULL,
  outlet_id INTEGER NOT NULL,
  asset_type TEXT NOT NULL CHECK(asset_type IN ('TECH','NON_TECH')),
  resource_code TEXT NULL CHECK(resource_code IS NULL OR length(resource_code) BETWEEN 1 AND 64),
  device_type TEXT NULL CHECK(device_type IS NULL OR device_type IN ('PC','OTHER')),
  ip_address TEXT NOT NULL CHECK(length(ip_address) BETWEEN 3 AND 45),
  subnet_mask TEXT NOT NULL CHECK(length(subnet_mask) BETWEEN 3 AND 45),
  gateway TEXT NOT NULL CHECK(length(gateway) BETWEEN 3 AND 45),
  dns_primary TEXT NOT NULL CHECK(length(dns_primary) BETWEEN 3 AND 45),
  dns_secondary TEXT NULL CHECK(dns_secondary IS NULL OR length(dns_secondary) BETWEEN 3 AND 45),
  allocated_by INTEGER NOT NULL,
  allocated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
  status TEXT NOT NULL DEFAULT 'ASSIGNED'
    CHECK(status IN ('ASSIGNED','PENDING_RELEASE','RELEASED','RELEASE_EXCEPTION')),
  release_requested_by INTEGER NULL,
  release_requested_at TEXT NULL,
  release_deadline TEXT NULL,
  ping_checked_at TEXT NULL,
  ping_result TEXT NOT NULL DEFAULT 'UNKNOWN'
    CHECK(ping_result IN ('UNKNOWN','REACHABLE','UNREACHABLE','ERROR')),
  release_handled_by INTEGER NULL,
  released_at TEXT NULL,
  release_reason TEXT NULL CHECK(release_reason IS NULL OR length(release_reason) <= 500),
  updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
  version INTEGER NOT NULL DEFAULT 0 CHECK(version >= 0),
  CHECK(
    (asset_type = 'TECH' AND resource_code IS NOT NULL AND device_type IS NOT NULL)
    OR
    (asset_type = 'NON_TECH' AND resource_code IS NULL AND device_type IS NULL)
  ),
  FOREIGN KEY(application_id) REFERENCES ip_application(id) ON UPDATE RESTRICT ON DELETE RESTRICT,
  FOREIGN KEY(ip_pool_id) REFERENCES ip_address_pool(id) ON UPDATE RESTRICT ON DELETE RESTRICT,
  FOREIGN KEY(branch_id) REFERENCES branch(id) ON UPDATE RESTRICT ON DELETE RESTRICT,
  FOREIGN KEY(outlet_id) REFERENCES outlet(id) ON UPDATE RESTRICT ON DELETE RESTRICT,
  FOREIGN KEY(allocated_by) REFERENCES sys_user(id) ON UPDATE RESTRICT ON DELETE RESTRICT,
  FOREIGN KEY(release_requested_by) REFERENCES sys_user(id) ON UPDATE RESTRICT ON DELETE RESTRICT,
  FOREIGN KEY(release_handled_by) REFERENCES sys_user(id) ON UPDATE RESTRICT ON DELETE RESTRICT
);
CREATE UNIQUE INDEX uk_allocation_active_pool
  ON ip_allocation(ip_pool_id) WHERE status <> 'RELEASED';
CREATE UNIQUE INDEX uk_allocation_active_ip
  ON ip_allocation(ip_address) WHERE status <> 'RELEASED';
CREATE UNIQUE INDEX uk_allocation_active_resource
  ON ip_allocation(resource_code)
  WHERE status <> 'RELEASED' AND asset_type = 'TECH' AND resource_code IS NOT NULL;
CREATE INDEX idx_allocation_scope_status
  ON ip_allocation(branch_id, outlet_id, status);
CREATE INDEX idx_allocation_auth ON ip_allocation(auth_id, status);
CREATE INDEX idx_allocation_release_due ON ip_allocation(status, release_deadline);

-- 系统配置。
CREATE TABLE system_config (
  id INTEGER PRIMARY KEY,
  config_key TEXT NOT NULL UNIQUE CHECK(length(config_key) BETWEEN 1 AND 64),
  config_value TEXT NOT NULL CHECK(length(config_value) <= 500),
  description TEXT NULL CHECK(description IS NULL OR length(description) <= 500),
  updated_by INTEGER NULL,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
  updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
  version INTEGER NOT NULL DEFAULT 0 CHECK(version >= 0),
  FOREIGN KEY(updated_by) REFERENCES sys_user(id) ON UPDATE RESTRICT ON DELETE RESTRICT
);
INSERT OR IGNORE INTO system_config(config_key, config_value, description)
VALUES('release_countdown_hours', '12', 'IP撤销后的释放倒计时小时数，仅影响新撤销');

-- 登录会话；只保存令牌摘要，不保存浏览器中的原始令牌。
CREATE TABLE sys_session (
  id INTEGER PRIMARY KEY,
  token_hash TEXT NOT NULL UNIQUE CHECK(length(token_hash) = 64),
  csrf_token_hash TEXT NOT NULL CHECK(length(csrf_token_hash) = 64),
  user_id INTEGER NOT NULL,
  expires_at TEXT NOT NULL,
  last_seen_at TEXT NOT NULL,
  revoked_at TEXT NULL,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
  FOREIGN KEY(user_id) REFERENCES sys_user(id) ON UPDATE RESTRICT ON DELETE CASCADE
);
CREATE INDEX idx_session_user_active ON sys_session(user_id, revoked_at, expires_at);

-- 安全审计日志；detail_json只能保存脱敏后的变更摘要。
CREATE TABLE audit_log (
  id INTEGER PRIMARY KEY,
  request_id TEXT NULL CHECK(request_id IS NULL OR length(request_id) <= 64),
  actor_user_id INTEGER NULL,
  action TEXT NOT NULL CHECK(length(action) BETWEEN 1 AND 64),
  target_type TEXT NOT NULL CHECK(length(target_type) BETWEEN 1 AND 64),
  target_id TEXT NULL CHECK(target_id IS NULL OR length(target_id) <= 64),
  result TEXT NOT NULL CHECK(result IN ('SUCCESS','FAILURE')),
  detail_json TEXT NULL,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
  FOREIGN KEY(actor_user_id) REFERENCES sys_user(id) ON UPDATE RESTRICT ON DELETE RESTRICT
);
CREATE INDEX idx_audit_actor_time ON audit_log(actor_user_id, created_at);
CREATE INDEX idx_audit_target_time ON audit_log(target_type, target_id, created_at);
```

### 5.3 不能只靠 DDL 的校验

服务层必须在事务内强制以下规则：

- 所有管理员业务权限相同；只有唯一初始管理员具有不可停用、不可删除、不可降级的保护属性。任何管理员不能停用或删除自己。
- 科技资产的 `resource_code`、`device_type` 必填；非科技资产二者统一写 `NULL`。
- `outlet_id` 必须属于所选 `branch_id`。
- 分配 IP 必须属于该申请支行的启用地址段，且不是网络地址、广播地址、网关或保留地址。
- 驳回必须有原因；审批和分配必须同事务；状态迁移必须符合状态机。
- 地址段重叠检查需要解析 CIDR 后比较数值范围，不能只比较字符串。
- 更新/删除必须核对 `version`，避免页面停留过久后覆盖他人刚完成的处理。

初始管理员不写入固定口令 SQL。实现阶段提供一次性安全引导：从受保护的环境变量读取临时密码、生成 BCrypt 摘要、仅在 `sys_user` 无管理员时创建，并强制首次改密；完成后立即清除环境变量。

## 6. 开发阶段与验收门

### 阶段一：环境门禁、工程骨架、账号与组织权限

任务：

- 完成 JDK、SQLite JDBC ARM64加载、数据目录、ping、IE11、端口和 HTTPS 只读预检并形成报告；不要求目标机安装 Maven/MySQL。
- 初始化 Git 基线、前后端工程、离线依赖锁定、`schema_history` 版本化迁移。
- 实现支行、网点、地址段和 IP 池管理。
- 实现管理员安全初始化、登录/退出、会话、CSRF、密码策略、自助改密，以及科技管理员创建/重置/启禁用。
- 建立统一错误模型、请求ID和审计日志。

验收门：管理员和科技管理员登录、自助改密和越权测试通过；禁用/重置/自助改密后的会话撤销符合规则；SQLite 外键/WAL/忙等待/备份恢复测试通过；发布包在麒麟 Java 11/ARM64 成功加载驱动。

### 阶段二：多管理员、批量导入、申请审批与手动分配闭环

任务：

- 实现申请表单、动态必填规则和本人数据权限。
- 实现初始管理员保护、其他管理员创建/启停/软删除及同权管理。
- 实现网点、地址段、IP资源池CSV/XLSX模板、预检预览和整批导入。
- 实现待审批列表、驳回、可搜索分页的 IP 表格弹窗和审批分配事务。
- 实现 IP/资源编码唯一占用、并发审批防重、站内通知。
- 实现用户查询，但限制为本人或合法代理范围。

验收门：初始管理员数据库/API/UI三层保护通过；其他管理员同权及启停软删除通过；三类CSV/XLSX导入安全与整批回滚测试通过；申请状态机、两个管理员并发选择同一 IP、科技/非科技字段和越权查询测试全部通过；不存在“已批准但无分配记录”。

### 阶段三：撤销释放、监控与导出

任务：

- 实现撤销二次确认、取消撤销、固定截止时间和待释放列表。
- 实现安全 ping、定时扫描、异常待处理、强制释放/取消释放和幂等恢复。
- 实现支行使用率、网点余量、筛选；ECharts 4 Canvas 图表。
- 实现 CSV/XLSX 导出、字段选择、范围过滤和公式注入防护。

验收门：用可控 ping 替身覆盖通/不通/超时/命令错误；重启和重复调度不重复释放；配置修改不改变历史截止时间；统计与底表一致。

### 阶段四：安全加固、离线交付与行内实机验收

任务：

- SCA/依赖漏洞、SQL注入、XSS、CSRF、越权、暴力登录、导出注入和敏感日志审查。
- 生成单一可追踪发布包、SHA-256、安装/升级/回滚脚本和 SQLite 在线备份/恢复说明。
- 在麒麟 ARM64 普通用户会话部署，验证 systemd 用户服务、重启恢复和日志轮转。
- 在真实 IE11/奇安信兼容模式验证登录、无尾斜杠路由、静态资源 MIME、表单、图表和下载。
- 执行独立业务验收：申请、驳回、并发分配、撤销倒计时、ping 异常、强制释放和审计追踪。

验收门：必须分别记录“Windows 构建测试、数据库集成测试、麒麟实机、IE11 实机、安全审查”的证据；前一层通过不能替代后一层。

## 7. 评审时需要确认的决策

1. “通知”本期是否仅站内消息，还是要接邮件/短信；本设计默认仅站内消息。
2. 统一认证号的格式、长度、是否属于敏感字段，以及日志/导出的脱敏要求。
3. 是否只支持 IPv4；表长兼容 IPv6，但 CIDR 展开、广播地址和 ping 参数本期建议先限定 IPv4。
4. 生产是否必须 HTTPS；若必须，需要确认行内证书来源和 IE11 可接受的 TLS 套件。
5. SQLite 数据文件和备份文件最终放置目录、备份保留周期、恢复责任人；数据文件必须在本机磁盘。

## 2026-10-09：V025 增量架构

本节及 `requirements-v025-2026-10-09.md` 为当前增量基线，覆盖本文较早阶段的冲突描述。继续采用 Java 11、SQLite 单实例和原生 ES5 管理台。

新增 V022 数据库迁移为池地址添加独立已占用标记，为分配与释放快照添加交换机 IP、设备序列号。当前有效分配维护使用预期版本检查并保存前后审计，原申请快照保持原始内容。

备份恢复通过管理员专属页面完成。整份数据库在线快照包装为带版本和摘要的 ZIP；全系统七天提醒以数据库外的最近手动备份元数据计时，不阻断业务。恢复在隔离副本校验和迁移后，由全局 API 维护门禁等待所有在途请求结束，先保存当前快照，再原子切换；记录持久恢复标记以支持启动回滚。恢复后全部旧会话撤销。该门禁覆盖登录、会话活跃更新和审计，不能仅按 HTTP 写方法判断数据库写入。
