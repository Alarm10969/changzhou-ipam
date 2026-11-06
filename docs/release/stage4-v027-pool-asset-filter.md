# V027 资产两级筛选发布说明

版本：`3.11.0-v027-pool-asset-filter`。数据库结构保持 V001–V023。

在“基础资料 → IP 资源池”选择资产大类（科技资产 / 非科技资产）和资产小类（资产名称）即可组合查询。小类按大类联动，也可以在“全部资产大类”下直接选择。切换大类会清空小类，翻页保留条件，重置恢复全部。

查询覆盖审批、手工及批量补录的有效分配；释放历史不参与。资产改类、改名、停用或删除后，仍有有效分配的快照可以作为小类选项查询。跨大类同名资产通过定义编号区分。

本版包含 V026 批量补录已分配及原有功能。从 V026 升级无需新增迁移；从 V025 升级会先应用已有 V023 批量补录迁移。升级脚本校验文件、备份数据库后启动。

回归入口：`scripts/build-portable.py --java-home <JDK11> --test --http-test --package`；专项为 `StageSeventeenPoolAssetFilterSmokeTest` 与 `scripts/browser-pool-asset-filter-regression.js`。开发机验证记录见 `outputs/v027-release-validation-20261028.md`。麒麟 ARM64、IE11、奇安信目标环境仍需实机验收。
