# 第一阶段验收记录

## 已在开发机可验证的项目

- [ ] `scripts/test-stage1.ps1`：固定依赖校验、Java 11 字节码编译、SQLite WAL 读写及在线备份/恢复烟测。
- [ ] 三类角色的登录、管理员接口越权、CSRF、改密/重置/禁用会话撤销 API 测试。
- [ ] 管理端：支行、网点、地址段、IP 资源池和审计记录的创建与查询测试。

## 需要目标机操作人员执行的单次验收

将 Windows 构建出的整个 `release` 目录复制到麒麟机（例如 `/home/部署账号/ipam-stage1`）后，在该目录、但未启动正式服务时执行：

```sh
cd /home/部署账号/ipam-stage1
chmod +x deploy/check-kylin-arm64.sh deploy/verify-package.sh deploy/backup-restore.sh
./deploy/verify-package.sh
IPAM_DATA_DIR=/home/部署账号/ipam-data ./deploy/check-kylin-arm64.sh
```

默认应用端口为 `18080`；如行内另行分配端口，预检和启动时均设置相同的 `IPAM_PORT`。

## 麟机功能验收

```sh
IPAM_PORT=18080 IPAM_DATA_DIR=/data/项目/IP管理系统/data ./deploy/start-stage1.sh
```

启动成功后，以 `http://麒麟服务器IP:18080/` 打开页面：管理员首次登录并改密，依次创建支行、网点、科技管理员和绑定的 B 角；科技管理员与 B 角分别首次登录并改密。两类普通角色均不得显示管理菜单，且直接访问 `/api/v1/admin/users` 应返回 `403`。管理员禁用账号、重置密码以及用户自助改密后，原登录页面刷新均应提示会话失效。验收结束可用 `./deploy/stop-stage1.sh` 停止服务。

忘记唯一管理员密码时，不得删除 `ipam.db`。停止服务后使用 `./deploy/recover-admin.sh`：该脚本只重置管理员密码、撤销其会话并记录恢复审计。

把生成的 `kylin-stage1-report-*.txt` 原样回传。脚本一次检查架构、Java 11、发布包、运行期不依赖 Maven/Node/Nginx/MySQL、数据目录可写及文件系统、SQLite JDBC 原生库加载/WAL/真实读写/备份恢复、ping 和监听端口。`WARN` 需要人工判断；`FAIL` 不允许进入下一阶段。

## 会话规则

| 事件 | 原会话 |
|---|---|
| 管理员重置密码 | 立即全部失效；下次登录必须改密 |
| 管理员禁用账号 | 立即全部失效，无法重新登录 |
| 本人改密 | 全部旧会话失效；当前浏览器获得新会话 |
| 退出登录 | 当前会话失效 |

## 明确未在本阶段验收的范围

申请、审批、IP 分配、撤销释放、ping 处置、仪表盘和导出属于后续阶段，未实现亦未宣称通过。
