# task-board 独立审批部署失败轮 105

2026-10-14，本轮恢复真实独立审批，仅通过产品入口部署 task-board。主 API Agent 自主规划、排查和与审批交互，最终自行 `unable`；未进入应用准备、发布、业务或浏览器验收。原失败证据保留，不把 [纯部署 102 的完整通过](DEPLOYMENT-AGENT-MAIN-ONLY-FULL-BUSINESS-102.md) 当作本轮结果，也不能据两轮结果断定审批是唯一原因。

## 实际执行

- 唯一方法：`DeploymentStaticLiveAcceptanceTest#deploysWebsitesThroughAgentEntry`，`managed.live.projects=task-board`，实际只有一个参数实例。
- `AUTOMATIC`、`rememberAuthorization=false`；主 Agent 和审批 Agent 使用独立模型调用、上下文及证据工具，每个动作仍经过精确产品权限。
- run：`task-board-approved-105-01`；实际时间 `2026-10-14T15:45:33.4803874+08:00` 至 `16:17:56.7590439+08:00`。
- 原 XML：1 个失败、错误/跳过 0，1930.959 秒；Maven 退出 1，源码冻结检查退出 0。公开结果 `FAILED / IO_FAILURE / DEPLOYMENT_AGENT_STOPPED`，原日志记录 HANDOFF；`unable` 正文未保留，仍为 UNKNOWN。
- 原 Task：`4c164123-81ba-4847-a1d1-bf96cb0ac008`；预定 App：`wtl-agent-task-board-b51heq0ds`；独立状态目录：`agent-live-task-board-b51hcylqs`；预定公开端口 48500。
- 运行 HEAD：`11ecae5b82d34251437e6eeffb1114dd498739ba`；3297 份源码精确等于 104 `after-final/manifest.json`，运行前后无源码、Skill 或配置热改。
- JAR SHA-256：`4e28dd5418778c8ade7ad85dd142197783b1b7215c85533c3ffcccedf120ecac`。[104 定点输入桥修复](DEPLOYMENT-AGENT-FULL-INPUT-BRIDGE-104.md) 已证明生产 class 和 Agent 资源与 102 相同；不将当前迁移工作区构建表述为干净 HEAD 构建。

Root 没有接手 SSH 部署、提供具体配额/构建步骤、代审批放行或因调试用量终止运行。唯一产品输入经 104 的严格桥到达：模型询问是否可以删除历史候选磁盘镜像。Root 只重申原用户授权条件：核实原任务/原操作/共享依赖、原恢复成功、资源与回执结清后才可清理；未挂载或非当前任务不足以证明可删除。回复没有新增宿主批量删除许可，也不能替代独立审批或产品权限。此后模型继续提交替代方案和排查，最终自行停止。

## 自主协作及证据边界

主模型有 29 个完整响应，审批模型 78 个完整响应；主模型尝试了 10 次 plan、9 次 host_command、一次 input，并有三次按需 skill 调用。Skill 参数未留，不能猜测各次加载的具体 Skill。

审批有 25 次 `submit_result`、23 次真实产品权限回执。它独立读取完整执行输入、来源、产品契约和回执；系统日志广删、无身份和挂载守卫地递归删除共享 `/tmp` 路径的两项请求被 DENY，原记录明确 NOT_SENT。拒绝后存在只读测量、单文件替代、带守卫替代和新容量事实，不能把 78 个审批响应当成无限重复讨论。也不据此弱化授权或加入固定讨论轮数。

9 次宿主命令工具尝试中，六项有本 Task/op/body 的 EXIT=0 回执，两项 DENY 未发送，一项因尚未评估 pending 观察在提出操作前拒绝。六项实际操作身份是：`80117c8f-f8ab-47c7-977b-ba964e30bebc`、`c4b69444-f0b7-48f6-a8a8-7fc6228bd200`、`5213634b-bf8f-4f9c-93af-2c2173977762`、`871a6bcc-7aab-4ef8-bc65-bdae04efc721`、`fb81dc73-0487-4a3c-a010-d6bc4412597a`、`112ad05f-baf8-49cf-81f3-3cb4fe0e4e55`。EXIT=0 不等于所有资源结清。当前持久 Task state/blockers 和完整资源依赖尚未独立读取，不能将它们写成零或成功结清，原任务及资源保留。

原日志有实测容量差异：102 首次完整容量证据可用 `1349644288` 字节；105 初次原 stat 为 `320803 × 4096 = 1314009088` 字节。来源摘要和布局摘要相同，配置摘要不同；具体空间占用来源未证明。105 最后替代请求的两个临时路径均 absent，df 前后相同、可用 `1341939712` 字节，没有新的容量入场通过证据。模型输入中的近似差额和各次观测不能混成一份完整事实。

确定的观测缺口是测试入口仍用 `mainOnlyDiagnostic` 限制安全容量/停止投影：fullAgent 的容量 DATA 只记录 512 字符前缀，组件 mode/minimum/preferred/explicit 完整声明缺证；`unable` 只保留参数长度。下一步只修这一监控入口并定点验证，复用现有白名单、身份绑定和敏感数据处理，不重跑本轮付费取证。配额的 rounded floor/preferred 反馈是有依据的通用改善候选，尚未证明其是此次失败的必要原因；没有证据称配额算法错误或审批过严。

## 本轮 provider usage

| 用途 | 物理尝试 | usage 完整已知 | 已知输入 | 已知输出 | 已知合计 |
|---|---:|---:|---:|---:|---:|
| LANGUAGE | 30 | 29 | 1,537,461 | 121,550 | 1,659,011 |
| APPROVAL | 79 | 78 | 935,902 | 94,794 | 1,030,696 |
| 合计 | 109 | 107 | 2,473,363 | 216,344 | 2,689,707 |

已知输入缓存命中 `1,508,480`、未命中 `964,883`，加权命中率 `60.9890258729%`。审批占已知输入加输出 `38.3200103208%`；含未知用量的完整 provider 占比未知。reasoning 已包含在输出，不再次加总。所有观测到的请求上限符合普通部署 32768、审批 8192，审批没有超出普通部署单次上限。

LANGUAGE 和 APPROVAL 各一次 `HTTP_CHUNK_INCOMPLETE` 的物理尝试 usage UNKNOWN，同 generation id 的第二次尝试成功，不能用重试的已知 usage 代填第一次。另有两角色 TEXT/TOOL_CALLING 共四条能力探测记录，usage 和 transport 次数均 UNKNOWN，未记录与这两次重试的绑定；不能相加成已确认六次付费调用，也不能当零。本轮调试及此前恢复、失败轮的消耗不混入最终冻结八项目轮。

## 封存和后续范围

七个已记录的自有 PID/创建时间组合均已退出，正常用户 CIM 正向控制通过，未终止其他进程。只删除本轮唯一 GUID 加密配置副本，原加密配置双 SHA 不变；正式 App102、SQLite、当前配置、原 Task/资源、其他 UNKNOWN 和调查证据保留。本轮未到浏览器检查点，也没有应用 STOP/DISABLE 的成功结论。

原证位于 `.ai-workspace/agent-live-evidence/agent-autonomy-20261012/`：

- `task-board-approved-105-01.log` SHA-256：`8f90cd3b6ada88855a2446366903596d9840df56eb8639cf9b883443b9f7c1df`。
- `task-board-approved-105-01/reports/`、严格绑定的 `request-f4c0624d-6c7b-4ed8-9372-45852fce2ec9/`、进程和配置副本清理证据。
- `approved-full-business-live-105/` 的原 usage、sealed-verification 和 parent-verification。父核报告时间/SHA、12 helper、源清单/JAR、请求绑定、进程及用量通过；这是原证核对，未新增业务测试。原封口的 `independentApprovalVerified=false` 表示完整成功验收未成立，不能据其 success 分支计数 null 说审批模型没有调用；真实响应与物理尝试数在上表独立记录。
- `approved-capacity-stop-audit-106/REPORT.md` 和 `safe-projection.json`：只读原日志及必要源码的定向审核，无 Runtime、数据库、SSH、模型或重复 probe。

完整独立审批下的 task-board 成功部署、非默认目录真实部署/整机重启恢复、不同模式的实际动态配额、其余七项目及全新服务器冻结八项目仍未验收。103 辅助包保留为 OFFLINE_NOT_RUN；此前 [恢复 100](DEPLOYMENT-AGENT-ORIGINAL-PREPARATION-RECOVERY-100.md) 和 [明细核对 101](DEPLOYMENT-AGENT-SETTLEMENT-DETAIL-AUDIT-101.md) 沿其已有证据，不当作本轮新恢复验收。
