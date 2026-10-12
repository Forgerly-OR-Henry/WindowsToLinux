# Agent 运行预算配置

运行容量分属[平台配置](../../../src/app/config/src/main/resources/gold/debug/windowstolinux/app/config/config.yaml)和[部署 Mod 配置](../../../src/mod/deployment/src/main/resources/gold/debug/windowstolinux/mod/deployment/config/config.yaml)。平台管理公共 AI 状态；部署 Mod 管理自己的调用及循环预算。它们沿现有随包 YAML 加载，不从提示词、环境变量或临时系统属性另取默认值。修改配置后需重新构建并启动应用或加载新 Mod；已冻结的原任务与恢复记录使用其原身份和证据。

## 运行默认值

| 所属 | 配置键 | 默认值 | 作用范围 |
|---|---|---:|---|
| 平台 | `common.ai.native-state.max-states` | 4096 | 每个原任务，所有 purpose 合计；主部署与审批共享 |
| 平台 | `common.ai.native-state.max-total-bytes` | 67108864 bytes，64 MiB | 每个原任务实际 UTF-8 载荷合计 |
| 平台 | `common.ai.native-state.max-value-bytes` | 1048576 bytes，1 MiB | 单个状态，受公共 continuation 契约上界约束 |
| 部署 Mod | `ai.deployment-max-output-tokens` | 32768 Tokens | 单次普通部署请求 |
| 部署 Mod | `ai.approval-max-output-tokens` | 8192 Tokens | 单次审批请求，必须不超过普通输出额度 |
| 部署 Mod | `ai.extended-deployment-max-output-tokens` | 65536 Tokens | 单次扩展部署请求，不小于普通输出额度 |
| 部署 Mod | `ai.requests.deployment-timeout-seconds` | 120 seconds | 单次普通部署模型请求 |
| 部署 Mod | `ai.requests.approval-timeout-seconds` | 120 seconds | 单次审批模型请求 |
| 部署 Mod | `ai.requests.extended-timeout-seconds` | 600 seconds | 单次扩展模型请求 |
| 部署 Mod | `ai.execution.shared-tool-attempts` | 4096 | 一次协调作用域，嵌套循环继续扣减同一预算 |
| 部署 Mod | `ai.execution.standalone-tool-attempts` | 256 | 没有外层协调作用域时使用 |
| 部署 Mod | `ai.execution.local-tool-turns` | 128 | 单个局部模型工具循环 |
| 部署 Mod | `ai.execution.agent-model-responses` | 512 | 一次主 Agent 协调循环 |
| 部署 Mod | `ai.execution.retained-review-sessions` | 4096 | 不同原审批请求会话身份的留存容量 |
| 部署 Mod | `ai.execution.max-observations` | 4096 | 原任务可留存的观察证据数量 |
| 部署 Mod | `ai.history.characters` | 131072 UTF-16码元 | 一份模型历史的文字预算 |
| 部署 Mod | `ai.history.payload-bytes` | 262144 bytes | 一份模型历史的 UTF-8 及 opaque 载荷预算 |
| 部署 Mod | `ai.history.max-batches` | 24 | 滚动保留的完整工具响应批次 |
| 部署 Mod | `ai.history.initial-batches` | 2 | 滚动历史中的初始批次留存数量 |
| 部署 Mod | `ai.history.initial-payload-bytes` | 8192 bytes | 初始批次载荷窗口 |

这些值允许模型在较长任务中自主排查。主管审批仍依据实时 Skill 和原请求决定重复讨论是否必要；审查身份留存容量不规定应讨论的轮数，也不授予执行权限。工具次数、主响应数、审查身份数与 native 状态数是不同作用域，不能相互换算为已批准或已执行命令数。任一预算先达到即可拒绝，不保证所有最坏嵌套都能同时跑满。

输出配置是调用方请求额度，实际 provider 额度还受平台输出策略、模型能力与提供方支持影响。扩大总状态或工具预算不会扩大单次输出，也不替代完整输入投递与独立审批。

## 容量到达后的行为

native 状态不按 LRU 或当前请求的引用集逐出。同一任务和 purpose 可能存在独立会话，早期句柄仍可合法续接；当前未引用不能证明它可以删除。任务结束或显式关闭原任务资源后，沿既有生命周期清除状态。

每个状态的载荷字节数由宿主首次按 UTF-8 计算并缓存。公开句柄中的字节数必须匹配宿主记录，task、purpose、profile 和 revision 校验继续生效。

provider 已成功但本地保存状态失败时，返回 FAILED、原问题码和纯计量响应：保留 provider 实际已报告用量、模型及宿主测量值，清空文本、工具调用和 continuation。业务继续按失败处理，原 purpose 会话停止；计量不触发重复 provider 请求。未报告的用量仍未知，不能用0替代。此单次调用失败不证明原部署副作用已结清。

## 配置范围与结构边界

来源、归档和构建已有独立 YAML 项：source/archive 默认2 GiB、来源条目100000、构建内存4096 MiB、工作目录4294967296 bytes。它们受各自准入和服务器构建协议的校验约束。本轮聚焦模型运行预算，未扩大这些物理协议上界，未验证任意大型或超大型项目的部署能力。

公共非视觉 AI 请求仍有1 MiB载荷上界。历史窗口在该契约内调整，并按完整工具批次滚动；配置较大窗口不能截断可执行输入来伪造审查完成。身份长度、授权、正式回执及数据库结清条件继续按原业务契约校验。

## 验收范围

2026-10-12本地限定验证：99个不同场景的最新结果全部通过，零未解决失败或跳过；五轮共108次执行，失败及重跑记录保留。第一轮平台27项通过后，Mod测试编译发现两个构造与两个新夹具标识遗漏；第二轮Mod71项中67通过，四项夹具预期或容量设置未对齐；第三、四轮只重跑失败及直接受影响场景，协调夹具仍分别遇到等待时限；第五轮使用同一冻结源码文件的2048个不同页，在512轮响应内完成实际READ、原账本记录、预算停止和资源收尾。独立文件数量、源码窗口及模型响应数不是同一预算，该夹具不证明任意2048文件项目都能跑满所有上限。

覆盖配置的严格必填、未知/重复/类型/溢出与相互关系拒绝，跨用途和同用途独立 native 分支的早期句柄，按任务清除、宿主UTF-8计量及伪造字节拒绝，本地HTTP首次/已有绑定保存失败的纯计量响应与封闭处理，调用方失败先判定和工具不执行，完整历史批次、最终1 MiB编码门、嵌套预算不回填、超过400条观察及实际索引分页。属于本地受控模型/HTTP和产品夹具；真实provider、SSH与原任务恢复调用均为0。

35个Java owner格式write/check通过。五轮测试及格式检查的3272项源码冻结均零变化，其他3235项源文件逐字节保持；原第13轮三个数据库文件的SHA未变。平台26个叶字段/19个分组、Mod36个叶字段/9个分组全部有注释；总配置说明中的完整平台示例与实际资源字段和值一致。仅完成受影响定点验证，未运行全仓或全模块测试。

38项源码与共享配置说明的自有增量见[精确补丁](patches/DEPLOYMENT-AGENT-RUNTIME-LIMITS-13.patch)，原始SHA256为 `340433f57e40cf37123f59b16590806913f6ea6813010c6caae57a02a4d1b1fa`。12项可独立应用到索引，26项含既有迁移上下文，仅交付本次精确增量，保留原迁移。验证对象是当前实际工作区，不声称只检出该提交即可构建全部正在迁移的仓库。

GPT-6.1-Sol负责前期实现，GPT-6-Astra仅参与状态容量、旧分支保护及失败计量边界的只读判断。实现子任务随后因模型服务容量不足退出，父代理接管完成剩余接线、定点修正和交付；这不是部署provider终止原因。

第13轮实际失败、已知与未知 provider 用量、未结清 prepare_tools 和恢复边界见[Agent 自主验收记录](DEPLOYMENT-AGENT-AUTONOMY.md)。配置化后的真实服务器部署、浏览器业务、非默认目录与 Linux 重启仍待后续产品入口验收。
