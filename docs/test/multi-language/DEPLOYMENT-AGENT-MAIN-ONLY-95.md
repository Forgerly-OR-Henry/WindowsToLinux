# task-board 纯主 Agent 真实验收 95：动态计划与容量拒绝

唯一产品入口轮 `task-board-main-only-95-01` 于 2026-10-14 12:58:58.1836523 至 13:07:50.1298044（UTC+8）自然结束。`DeploymentStaticLiveAcceptanceTest#deploysWebsiteThroughMainAgentDiagnostic` 原 XML 为 1 FAIL / 0 ERROR / 0 SKIP、523.374 秒，Maven 退出 1，公开失败码 `DEPLOYMENT_AGENT_STOPPED`。本轮未完成部署验收。

运行基于本地提交 `54334809aebc9646b829bf6a67d01b82c31473a6`，JAR SHA-256 `abd1fab6083f0820eee8e0c2f2d951b84464e9f108da9306fe586e1713b69124`。3296 项源码开始、结束均匹配 94 封口原像，源码和 Skill 未中途修改；94 仅改验收诊断，19 个相关生产 class 和 15 个 Agent 资源保持。产品实际 SSH 指纹与原授权一致，Root 未接管 SSH 或提供部署步骤，APP 审批全链在 transport 前关闭。

模型实际返回 17 次 LANGUAGE 响应，自主 inspect、source_index、read 16 次、identify 2 次、plan 7 次、Skill、host_command、unable。阶段只有 INVESTIGATING、IDENTIFIED，未进入 PLANNED、PREPARED、发布、业务、浏览器或 input。本轮实际进入 94 新容量与停止说明投影路径，完整安全 JSON 为 OBSERVED；91 输入桥未被实际模型调用。

三个容量拒绝均绑定原任务 `63319548-7ba9-4c63-8d40-8de438d6b768`、同一 source/config/layout 身份及原 call。首份两组件 AUTO 的 minimum/preferred 为 64/256 MiB，需求 1879048192 字节，可用 1227870208 字节。模型自行重新识别为单组件，并把 minimum/preferred 改为 64/64 MiB，需求降至 1476395008 字节，可用 1227866112 字节。随后单组件 AUTO 的 minimum/preferred 均为 0，框架按源码下界计算需求 1342177280 字节，可用 1227853824 字节。三份均保留 1073741824 字节 host reserve、完整组件字段、原 aggregate 和 SHA，未截断，且声明被拒计划尚未创建新 inputs。这证明本次 Agent 实际动态调整请求；不由此代替 STATIC、ASSISTED 真机验证或断言所有合法方案均不可行。

模型最终实际停止说明已安全保留，标为 `MODEL_DECLARED_REASON`，1507 字符，SHA-256 `27de7fc25c669ff1d9ac0a06295b00a884588791ed3f197515f878084cbfb2d7`，redacted/truncated 均为 false。模型自述主机约 97% 占用，最小计划仍差约 109 MiB，主要占用来自其它任务的历史 loop 工作镜像，现有授权不允许直接清理，并称剩余缓存与日志不足以弥补缺口。这是模型自述；后续须用原主机正式回执核对数值和历史身份，再通过产品恢复或收尾处理。不能根据这段说明直接删除旧镜像、未知资源或已发布 App，也不能把所述最小计划当成未经源码核对的全局下界。

本轮数据库根 `agent-main-only-diagnostic-live-task-board-7lgw9m3eg`，请求 App `wtl-agent-main-only-diagnostic-task-board-7lgwb81fo`，公开目标端口 48100。原任务最终 Task/Goal 状态、各资源 owner/released 及正式业务身份仍待精确核查；公开 FAILED 和进程结束不能代替资源结清证据。调查数据库、旧 UNKNOWN 和原失败轮次全部保留，恢复或删除操作为零。

Runtime 关闭后审批 attempts、providerCalls、Token 均为 0。17 次部署 provider usage 字段全部已知：input 786539、output 74169、合计 **860708 Token**；cache hit 425216、miss 361323，输入缓存命中率 **54.0617%**，字段一致。reasoning 62718 是 output 子集。两次能力探针 usage 与 transport 次数未知，另列，不按零计。本轮为失败调试轮，不混入最终冻结八项目批次，也不是正式审批验收。

初始与实际运行时各记录 7 个 PID/创建时间，去重后 7 个已知身份经正常用户 CIM 核对均已退出，未人工终止；短采样不声称穷尽所有短进程。仅本轮 GUID 受保护配置副本已精确删除，原 clean 配置双 SHA 不变，调查数据库删除零。原日志 SHA-256 `03450f27ae1a53134d1a16752c2c6134262c31fecd2f993d80b53b09cccb5180`，XML `c248963884c676f02520ae2b3281941c77eca9345d280eae8dc48f6cdd2177b5`，TXT `a04d0bdc8c84af6176054a0b86d9f3764893fc1e26c066b859bfb6b97a52a93d`；原报告、时间、冻结源码、用量和进程收尾已核，未为封口新增模型、SSH、数据库连接或业务测试。

96 已只读核实原 host_command 主执行和正式回执：同原任务、operation `981d2878-4101-4e54-b98e-0d22a4f701bc`、body SHA `f71e2702bebf703f3e404db7225eaaf387ee1a7154be09bc3319e11da0b163db`，SUCCEEDED、exit 0、未截断。原 stdout 确认 `/dev/vda1` 可用 1227849728 字节、占用 97%，`/var` 报告 25215954944 字节。cache 与 log 合计报告 93212672 字节，算术上小于缺口，且报告大小不代表可回收量。约 15 GiB 历史镜像仍仅为模型自述，未获独立对应 du 证据。

同源码的新 Agent 候选下界已由正式 Quota 源码与原反馈核实：`archive + 2*unpacked + 64 MiB` 为 67318945 字节，向上按 64 MiB 对齐得到至少 128 MiB；Agent host cost 为两倍，另保留 1 GiB，所以最低为 1342177280 字节，较最后 capacity 少 114323456 字节。此结论限定当前授权 host/layout、至少一个新候选及不复用既有配额，不能推广到其它模式、目录或既有候选复用。独立核查同时验证了三份容量投影、32 个原日志行、两个源码哈希和 14 个挂载目标哈希，没有新增数据库或模型调用。

14 个 loop 挂载确有原始记录，部分路径含原 58、74、86 的候选名称；正式 Task/resource/App 归属、backing image 和可清理状态仍未知。优先原 86 未发布候选 `wtl-agent-main-only-diagnostic-task-board-596hqa2zk`，准备仅其原任务 `de3db483-375a-4400-9cd6-17d65cfa66fd` 的正式资源与业务引用只读核查。读取方案须先审身份、字段、官方 decoder、只读连接和原数据库字节守卫，不连接所有历史库，也不直接清盘或改写 UNKNOWN。

task-board 完整业务、浏览器、重启和产品停用仍未通过；真实历史恢复与明细清理、非默认目录真机部署及重启、渐进审批、其余七项目和最终八项目验收未完成。下一步按实际资源身份通过产品恢复和回执结清处理容量阻塞，原证据与已发布业务数据保留。
