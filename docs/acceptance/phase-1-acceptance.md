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
