# 第二阶段需求与验收基线

> 版本：2026-09-01
>
> 状态：需求基线，尚未实现。第一阶段现有代码和数据必须保留，通过新增迁移升级，禁止删除数据库重建。

## 1. 当前基线

- 当前角色为管理员（`ADMIN`）和科技管理员（`TECH_ADMIN`）；B角已从第一阶段后续调整中取消。
- 科技管理员不绑定支行或网点，可以自助修改密码。
- 管理员已经可以维护支行、网点、地址段和逐IP资源池，但目前只能逐条录入。
- 当前数据库为单机 SQLite，应用为单实例，目标机只依赖 Java 11。
- 第二阶段原有范围为申请、审批和手动分配；本文件增加“多管理员”和“基础数据批量导入”。

## 2. 新增需求一：多管理员与初始管理员保护

### 2.1 权限模型

- 首次部署创建的管理员标记为“初始管理员”。系统中只能有一个初始管理员。
- 初始管理员可以创建其他管理员账号。
- 其他管理员与初始管理员具有相同的业务权限和功能，包括：
  - 创建管理员和科技管理员；
  - 重置非初始账号密码；
  - 启用、停用和删除非初始账号；
  - 组织资源、申请审批、手动分配、批量导入、配置、导出和审计查询。
- `is_initial_admin` 只是保护标记，不产生额外业务菜单或审批权限。

### 2.2 保护规则

- 初始管理员永远不能被停用、删除或降级为科技管理员。
- 初始管理员保护标记不能通过任何Web API修改。
- 任何管理员都不能停用或删除自己，避免当前操作者误锁死自己。
- 其他管理员可以被停用、重新启用或删除。
- 页面上的“删除账号”在数据库中采用软删除：设置 `deleted_at`、停用账号并撤销全部会话，不物理删除历史行。
- 被删除账号不能登录、不能恢复为启用状态；如需重新开通，创建新账号。原用户名不允许复用，以保证审计记录指向唯一主体。
- 停用、删除、重置密码、创建管理员均必须写安全审计日志。

### 2.3 初始化和离线恢复

- 首次启动仅在数据库不存在任何管理员时创建初始管理员，并同时设置 `is_initial_admin=1`。
- 后续通过页面创建的管理员固定为 `is_initial_admin=0`。
- 离线 `recover-admin` 工具只恢复初始管理员，不再按“ID最小的管理员”猜测目标。
- 初始管理员恢复后仍保持启用、未删除、管理员角色，并强制首次改密。

### 2.4 账号管理界面

账号列表增加：角色、初始管理员标识、启用状态、删除状态、创建人和创建时间。

- 初始管理员行显示“系统初始管理员”徽标，停用和删除按钮不可见。
- 当前登录管理员自己的行不显示停用和删除按钮。
- 其他管理员和科技管理员显示重置密码、启用/停用、删除操作。
- 创建账号时可选择“管理员”或“科技管理员”；默认选择科技管理员。
- 删除必须二次确认，确认文案包含账号和姓名。

### 2.5 数据库迁移要求

第二阶段通过新的版本迁移升级现有 `sys_user`，不能修改或重放 V001/V002：

```sql
-- V003前置门禁：第一阶段数据库必须恰好有一个管理员。
CREATE TEMP TABLE v003_admin_guard (
  admin_count INTEGER NOT NULL CHECK(admin_count = 1)
);
INSERT INTO v003_admin_guard(admin_count)
SELECT COUNT(*) FROM sys_user WHERE role = 'ADMIN';
DROP TABLE v003_admin_guard;

ALTER TABLE sys_user
  ADD COLUMN is_initial_admin INTEGER NOT NULL DEFAULT 0
  CHECK(is_initial_admin IN (0, 1));

ALTER TABLE sys_user ADD COLUMN deleted_at TEXT NULL;

-- 第一阶段尚不能创建其他管理员，门禁通过后将唯一管理员升级为初始管理员。
UPDATE sys_user
SET is_initial_admin = 1, is_active = 1, deleted_at = NULL
WHERE role = 'ADMIN';

CREATE UNIQUE INDEX uk_sys_user_single_initial_admin
  ON sys_user(is_initial_admin)
  WHERE is_initial_admin = 1;

CREATE INDEX idx_sys_user_role_state
  ON sys_user(role, is_active, deleted_at);

CREATE TRIGGER trg_initial_admin_no_delete
BEFORE DELETE ON sys_user
WHEN OLD.is_initial_admin = 1
BEGIN
  SELECT RAISE(ABORT, 'INITIAL_ADMIN_PROTECTED');
END;

CREATE TRIGGER trg_initial_admin_no_disable_or_demote
BEFORE UPDATE ON sys_user
WHEN OLD.is_initial_admin = 1
 AND (
   NEW.is_initial_admin <> 1
   OR NEW.role <> 'ADMIN'
   OR NEW.is_active <> 1
   OR NEW.deleted_at IS NOT NULL
 )
BEGIN
  SELECT RAISE(ABORT, 'INITIAL_ADMIN_PROTECTED');
END;
```

迁移执行前必须检查管理员数量：若没有管理员或已经存在多个可能的“初始管理员”候选，迁移应停止并输出人工处理说明，不能静默猜测。

## 3. 新增需求二：网点、地址段和IP资源池批量导入

批量导入是对应基础资料页面的补充入口，不设置独立导航或独立业务模块：网点、地址段、IP 资源池页面同时保留“单条新增”、列表和“批量新增”卡片；支行页面保持原有单条维护方式。

### 3.0 组织与网络资源关系（V005）

- 一个支行可以对应多个网点。
- 每个网点最多绑定一个地址段；新地址段必须选择网点，支行由网点关系派生。
- V005 为 `ip_range` 增加可为空的 `outlet_id` 和唯一索引。升级前已有地址段不猜测网点，显示为“旧数据待绑定”，由管理员编辑确认。
- 网点绑定地址段后，系统自动生成 CIDR 内全部主机地址。网络地址和广播地址不入池；网关进入池但固定为不可用、系统保留。
- 自动生成首版支持 `/16–/30`，单地址段最多 65534 个主机地址；管理页面按 50 条分页并支持 IP 搜索。
- 管理员可逐个编辑普通 IP 的可用、人工保留和说明状态；网关及已分配 IP 不允许修改。
- 审批可用 IP 按申请 `outlet_id` 精确匹配，不允许同支行不同网点之间混用地址段。
- 支行、网点、地址段及 IP 状态保存后均提供编辑入口。已有分配记录时禁止修改会破坏历史归属的网点、CIDR或网关。

### 3.1 支持格式

- CSV：UTF-8，允许UTF-8 BOM，使用标准双引号转义。
- Excel：仅支持无宏 `.xlsx`；不支持旧 `.xls`、`.xlsm`、加密或带密码文件。
- 提供三套可下载模板：网点、地址段、IP资源池。
- 每次只选择一种目标类型并上传一个文件，避免跨表顺序不明确。

Excel解析使用与Java 11兼容、经离线校验和及漏洞审查的 Apache POI 5.x XSSF读取能力。解析必须设置压缩膨胀、单ZIP条目、总文本量、文件大小、行数和处理超时限制；上传临时目录权限仅限应用用户。

### 3.2 模板字段

#### 网点模板

| 列名 | 必填 | 规则 |
|---|---:|---|
| `branch_code` | 是 | 必须对应已启用支行编号 |
| `outlet_code` | 是 | 同一支行内唯一 |
| `outlet_name` | 是 | 1–100字符 |
| `is_active` | 否 | `1/0`或`是/否`，默认启用 |

#### 地址段模板

| 列名 | 必填 | 规则 |
|---|---:|---|
| `branch_code` | 是 | 必须对应已启用支行编号 |
| `outlet_code` | 是 | 必须属于该支行、已启用且尚未绑定其他地址段 |
| `cidr` | 是 | 规范IPv4 CIDR，不得与已有或本批地址段重叠 |
| `subnet_mask` | 否 | 留空时由CIDR推导；填写时必须一致 |
| `gateway` | 是 | 必须属于地址段，且不能作为普通IP分配 |
| `dns_primary` | 是 | 合法IPv4地址 |
| `dns_secondary` | 否 | 留空写入NULL |
| `is_active` | 否 | 默认启用 |

#### IP资源池模板

| 列名 | 必填 | 规则 |
|---|---:|---|
| `branch_code` | 是 | 用于防止选错同名地址段 |
| `cidr` | 是 | 必须对应已存在且启用的地址段 |
| `ip_address` | 是 | 必须位于CIDR内，不能是网络、广播或网关地址 |
| `is_reserved` | 否 | 默认否 |
| `reservation_reason` | 条件必填 | `is_reserved=是`时必填，最长200字符 |
| `is_active` | 否 | 默认启用 |

所有模板使用业务编号和CIDR关联，不暴露或要求用户填写数据库内部ID。

IP资源池模板用于批量调整自动地址池中指定 IP 的可用/保留状态，不再用于逐行创建整个地址池。

### 3.3 导入流程

```text
下载模板
  → 选择目标类型并上传
  → 文件安全检查和逐行校验
  → 展示汇总与错误明细
  → 全部有效后管理员再次确认
  → 重新校验文件SHA-256和当前数据库状态
  → 单个SQLite写事务整批提交
  → 显示导入结果并写审计
```

- 网点、地址段采用“只新增”模式；已有编号或CIDR视为重复错误。IP资源池模板只更新自动生成地址池中明确列出的 IP 状态。
- 第一版采用“全有或全无”；任何一行错误都不写入业务表。
- 空白尾行忽略；表头名称必须与模板一致；未知列给出错误，不悄悄丢弃。
- Excel公式单元格不计算、不信任，直接作为格式错误；外部链接、宏和加密文件拒绝。
- 默认安全上限建议为单文件5 MiB、10000条数据行、处理60秒；最终数值可通过系统配置调整，但不能由请求参数绕过。
- 校验预览和正式提交之间必须重新检查关联数据及唯一性，防止另一管理员同时修改基础数据。
- 正式写入进入SQLite单写者队列并使用短事务；失败时整批回滚。
- 原始上传文件只保存在权限受限的临时目录，完成、取消或过期后删除；数据库仅保留文件名、SHA-256、汇总和错误明细。

### 3.4 导入任务表

```sql
CREATE TABLE bulk_import_job (
  id INTEGER PRIMARY KEY,
  target_type TEXT NOT NULL
    CHECK(target_type IN ('OUTLET','IP_RANGE','IP_POOL')),
  file_format TEXT NOT NULL CHECK(file_format IN ('CSV','XLSX')),
  original_file_name TEXT NOT NULL CHECK(length(original_file_name) BETWEEN 1 AND 255),
  file_sha256 TEXT NOT NULL CHECK(length(file_sha256) = 64),
  status TEXT NOT NULL
    CHECK(status IN ('VALIDATING','PREVIEW_READY','COMPLETED','FAILED','CANCELLED','EXPIRED')),
  total_rows INTEGER NOT NULL DEFAULT 0 CHECK(total_rows >= 0),
  valid_rows INTEGER NOT NULL DEFAULT 0 CHECK(valid_rows >= 0),
  error_rows INTEGER NOT NULL DEFAULT 0 CHECK(error_rows >= 0),
  created_by INTEGER NOT NULL,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
  confirmed_at TEXT NULL,
  completed_at TEXT NULL,
  FOREIGN KEY(created_by) REFERENCES sys_user(id) ON DELETE RESTRICT
);

CREATE INDEX idx_bulk_import_job_creator_time
  ON bulk_import_job(created_by, created_at);

CREATE TABLE bulk_import_error (
  id INTEGER PRIMARY KEY,
  job_id INTEGER NOT NULL,
  row_number INTEGER NOT NULL CHECK(row_number >= 1),
  field_name TEXT NULL CHECK(field_name IS NULL OR length(field_name) <= 64),
  error_code TEXT NOT NULL CHECK(length(error_code) BETWEEN 1 AND 64),
  error_message TEXT NOT NULL CHECK(length(error_message) BETWEEN 1 AND 500),
  FOREIGN KEY(job_id) REFERENCES bulk_import_job(id) ON DELETE CASCADE
);

CREATE INDEX idx_bulk_import_error_job_row
  ON bulk_import_error(job_id, row_number);
```

## 4. 第二阶段开发顺序

### 任务2.1：迁移与多管理员

- 新增迁移并保护现有初始管理员。
- 扩展创建账号接口以支持 `ADMIN` 和 `TECH_ADMIN`。
- 实现非初始管理员的启停、软删除和密码重置。
- 更新离线管理员恢复工具和账号管理界面。

### 任务2.2：批量导入基础设施

- 固定并校验Apache POI及其离线依赖。
- 实现CSV/XLSX安全解析、三套模板下载、上传临时文件管理。
- 实现导入任务、预览、错误明细、取消和过期清理。

### 任务2.3：三类数据导入

- 网点导入及支行编号关联校验。
- 地址段导入、CIDR规范化、掩码推导和重叠检测。
- IP池导入、地址归属和保留地址校验。
- 正式确认时再次校验并整批事务提交。

### 任务2.4：原第二阶段业务闭环

- IP申请、审批、驳回和站内通知。
- 可搜索分页的可用IP选择弹窗。
- 审批通过与IP分配同事务，并发唯一性兜底。
- 用户只能查看本人数据。

## 5. 第二阶段验收门

### 多管理员

- 初始管理员能创建管理员；新管理员登录后看到相同管理功能。
- 新管理员也能创建管理员和科技管理员。
- 通过页面、直接API和直接SQL触发器测试，初始管理员均不能被停用、删除、降级或取消保护。
- 非初始管理员可被停用、启用和软删除；停用/删除后全部会话立即失效。
- 当前管理员不能停用或删除自己。
- 离线恢复工具只恢复初始管理员。

### 批量导入

- CSV和XLSX三套模板均可成功导入中文数据。
- 错误支行、重复编号、重叠CIDR、越界IP、网关IP、重复IP、公式单元格和超限文件均被明确拒绝。
- 有任意错误时业务表写入数为0；全部有效时一次提交成功。
- 两名管理员在预览后同时修改关联数据时，后确认者会重新校验而不是覆盖。
- 导入任务、结果、操作者、文件摘要和错误明细可审计，原始临时文件按规则删除。
- 麒麟ARM64普通用户环境完成真实CSV/XLSX上传、预览和确认验收。

### 组织、地址段与地址池

- 同一支行可创建多个网点，同一网点不能创建或导入第二个地址段。
- `/24` 地址段绑定后自动生成254个主机地址，网关系统保留，其余253个默认可用。
- 普通 IP 可编辑为不可用或人工保留；网关、已分配 IP 的状态修改被拒绝。
- 旧地址段升级后保持未绑定状态，不自动猜测；管理员绑定后补齐缺失的自动地址池。
- 审批只返回申请网点绑定地址段中的可用 IP，同支行其他网点地址不出现。

### 申请与分配

- 申请状态机、审批分配同事务、并发选择同一IP、权限隔离全部通过。
- 第二阶段所有验收通过后才能进入撤销释放、仪表盘和导出阶段。

## 6. 本阶段明确不做

- 不支持旧版 `.xls`、宏文件 `.xlsm` 或加密Excel。
- 不支持导入时自动覆盖、删除现有业务数据。
- 不支持跨文件自动推断导入顺序。
- 不支持多应用实例同时访问同一SQLite文件。
