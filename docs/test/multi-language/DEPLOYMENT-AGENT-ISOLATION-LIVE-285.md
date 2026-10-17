# File Transfer 285：计划重选遗漏后续成本，严格准入拒绝

唯一 `file-transfer-isolated-285-01`，UTC 2026-10-17T14:59:24.4664553Z—15:10:53.6455912Z，session86678 实际退出 1；JUnit 1F/0E/0Skip、683.9s，Goal BLOCKED。实现基线 `6f1958d8b1e811a6926d7b844da85e4aae07df1f`。第 15 次历史 FULL，File Transfer 和 CSV 均未完成完整业务验收，CSV 未开始。

| 对象 | 实际值 |
| --- | --- |
| Task | `40109210-4105-4dcc-a332-3f46ee42013f` |
| Root | `src/app/db/data/agent-live-file-transfer-5c929zxeg` |
| App | `wtl-agent-file-transfer-5c92bmlwg` |
| 计划端口 | 49290 / 49291 |
| 实际候选 | App 后缀 `backend-525f7bab44afa87d` |

## 实际动作与确认原因

六保护集合首次实际 READY，sourceVersion `feaefb734c1d18b10bbe855c0739fbb3275ecfd31c9073c3a427b2a319f24c7d`，116 行/23558 B，17 paths/4 units/2 ports；free 2233180160、required 1140850688、reserve 1073741824 B，available memory 3552735232 B、CPU 2。新后端候选实际 create/query/upload 24765 B/verify/open；没有 build/seal/publication 或浏览器证明。

后续新 scope 准入实际 REFUSED/DISK_SPACE，116 行/23623 B，allocatedCandidates 只有本轮后端；free 1964691456、required 1140850688、reserve 1073741824 B，短缺 249901056 B。prepare_tools 的工具链动作未派发，因此没有本轮 Go controller early refusal 或 285 typed proof 的新实机覆盖。模型修改计划被 `Prepared workspace budget cannot change` 拒绝，随后声明 unable，任务 UNKNOWN。

286 固定 reader 的公开账本确认：首 PLAN40/配额41、42 为 backend/web 各 268435456 B；末 PLAN63/配额64、65 为 backend 268435456、web 402653184 B。末计划摘要 `5603399c9919e44f9b17135c0baa682df61101da4146c20a06517a92205b22f9`；CANDIDATE_QUOTA52/CANDIDATE54 仅记录后端。

源码确认 Agent selectWorkspaces 重规划时只 preserve 已创建组件，把未创建组件交给纯 selector；没有为已创建组件将来的 release/app 副本保留预算，也未扣隔离的额外 64 MiB 探测需求。于是本地可重选 web 384 MiB，严格 probe 仍正确合计 `backend release 256 + web candidate/release 2×384 + probe 64 = 1088 MiB`，再加一次 1024 MiB reserve，合法拒绝。相对于首计划，web 增加的双份 256 MiB 正好抵消后端已分配的 256 MiB，故 required 未下降。模型把内存 floor 当磁盘余量、把 PROBE reserve 混算的自述不作为原因结论。286 仅针对 Agent 选择路径修正，尚待定点验证。

## 只读账本及收尾

286 reader 实际 javac/read 均 0，UTC 15:15:17.968601Z—15:15:19.608930Z：existing-only bootstrap、H2 READONLY/IFEXISTS、现有空锁，公开 92 事件完整逐字匹配，pair 属性锁内和关闭后不变。平台和 Mod 均 UNKNOWN；1633 资源中 1628 released、5 unreleased；正式 App/component/release/receipt 和 execution 全 0。CAPABILITY 1 executing/1 result、PREPARE 1/1、CURRENT_SOURCE 1；无 controller 或 command 事件。effectsSettledProductionEvaluator=true 只表示该 journal 判断，不替代平台结清或远端 absence 证明。

已见 UNKNOWN 后才写 automatic-close；产品真实取消 postQuery `17fa63a4-052e-40f7-a092-ed4ab99007c3`，返回 CANCELLED。进程自然退出，无 kill。CIM 本轮 Java 0、terminated 0；核 owner/nonce/SID、绝对边界及无 reparse 后清 f285a/u285a、私有加密 credential285 copy；reader 自有三个编译/临时目录也已清。没有浏览器页或浏览器输入文件，source176 未由清理打开。

本轮 UNKNOWN Runtime/候选、六组旧任务和全部证据保留，不回填、不改判、不直接按目录删除。原 194 不恢复。暂不另开 FULL 或扩大第七保护 frame；先修本地选择缺陷，并评估本次 285 原产品 recovery 的精确归属收尾入口。任何远端动作仍独立 APP、新 grant，不能凭当前本地 reader 结果直接清资源。

## 失败 FULL 已知用量

封闭 stdout 离线统计，没有新增模型调用。

| 角色 | 物理尝试 | 输入 | 输出 | 缓存命中 | 缓存未命中 | 输入加输出 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| DEP | 20 | 848958 | 60169 | 570496 | 278462 | 909127 |
| APP | 34 | 963233 | 9554 | 763392 | 199841 | 972787 |
| 合计 | 54 | 1812191 | 69723 | 1333888 | 478303 | 1881914 |

APP 已知 IO 占比 `51.6914%`，输入加权 cache `73.6064%`。54 条 IO/cache 已知，DEP20 reasoning 已包含 output，APP34 未提供；完整 provider、配置探测、HTTP/账单未知。本表只代表失败 FULL，不替代两项目最终指标，不混入纯恢复。

实机证据在 `.ai-workspace/agent-live-evidence/agent-autonomy-20261012/file-transfer-after-second-resolver-285/`，固定 reader 及后续只读审计在 `file-transfer-capacity-refusal-286/`。File Transfer 完成 BUSINESS v2、真实浏览器及 STOP/DISABLE 后才开始 CSV；两个项目完成后暂停汇报指标。
