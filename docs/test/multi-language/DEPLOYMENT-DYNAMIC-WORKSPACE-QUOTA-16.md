# 隔离工作区动态选额与候选容量反馈

本记录接续 [主 Agent 隔离诊断 15](DEPLOYMENT-AGENT-MAIN-ONLY-DIAGNOSTIC-15.md)。用户在 2026-10-12 恢复推进，要求核实 4 GiB 额度，并确保 STATIC、ASSISTED、AGENT 的真实部署路径合理选额；核实后继续 task-board，不暂停。

## 实际边界

4 GiB 是原 `build.workspace-bytes` 默认值，helper 接受 64 MiB～128 GiB。它不是固定 4 GiB 磁盘；已经创建的实体工作区也不支持在线扩缩容。独立 Agent 工具链卷的 8 GiB 预算属于另一资源边界，本次工作区选额没有修改它。

新任务默认 AUTO。STATIC 根据真实归档与已识别固定配方估算构建预算；ASSISTED 通过独立分析提供有界需求；AGENT 自主在组件计划中给出有源码依据的 minimum/preferred 构建预算，也可声明 EXPLICIT 精确额度。未知下载或容器体量需要明确预算。源码和锁文件不能证明已安装依赖体积，初始估算不能证明构建必然成功。

共享部分仅包含有界计算和严格容量观察，三模式的编排、分析及审批保持各自职责。额度采用字节，AUTO 向上取整到 64 MiB，受最低需求、优选需求、当前主机可用空间和政策上限共同约束。EXPLICIT 不自动裁小。整图一次性计算保留余量，避免每个组件重复扣除。

来源最低开销为压缩归档与展开来源，加 64 MiB 元数据余量；AGENT 保守计算两份展开来源。标准候选的主机成本保持 `max(3×归档字节, 选定额度)`，Agent 未创建候选保持 `2×选定额度`。这些成本边界不覆盖独立工具链、原生数据库和未来持久数据的全部需求。

## 确认、执行与恢复

STATIC/ASSISTED 在真实归档完成后、最终确认前选额并展示依据。AGENT 在接受计划时绑定选额。创建、构建、已审阅摘要、候选账本和恢复使用同一个值。创建前重新观察容量，主机空间变化只能拒绝，不得改变已确认额度。已有候选的业务重规划保持原实体额度，不重新为已分配工作区扣除空间。

新配置字段位于部署 Mod 的 `config/config.yaml`，详见 [配置说明](../../Config.md)：

| 字段 | 默认值 | 含义 |
| --- | ---: | --- |
| `build.workspace-bytes` | 4294967296 | EXPLICIT 输入的初值 |
| `build.workspace-max-bytes` | 137438953472 | 自动或明确额度的政策上限 |
| `build.workspace-host-reserve-bytes` | 1073741824 | 整图主机保留余量 |

原冻结任务不重新套用新 YAML。新候选缺少额度或绑定不匹配时拒绝恢复，不猜测 4 GiB，也不以新配置补造旧证据。数据库结构版本保持 1。

## 容量拒绝的结清条件

15 的最后准备操作已可能完成 helper/probe，不能把整段准备记为 NOT_SENT，也不能伪写 PREPARE_RESULT 让恢复误判存在候选。

新的 `PREPARE_CAPACITY_REJECTED` 仅由产品的严格边界产生：helper 已确认就绪或安装已完整验证，主机和容量观察成功、exit 0、输出未截断且可解析，取消、配置、来源和布局检查成立，候选创建未调用。事件绑定原任务、组件、服务器、来源、配置、布局、计划、准备动作与事件编号、已选额度和容量观察身份。它只结清该次候选准备拒绝，不能授予清理权限或回填历史操作。

模型收到实际 available/required/workspace/reserve 字节事实后，自主判断重新规划未创建候选、选择其他获授权能力或停止。其余效果仍未结清时不继续。UNKNOWN、取消、缺失、截断、外来或重复证明保持不确定，不将旧 13/14/15 改写为成功或已结清。

## 验证状态

68 个不同业务逻辑场景最新通过，35 个 Java 文件的限定格式检查通过。17 轮原始编译、夹具和业务失败日志及 XML 全部保留，只重跑失败组和直接受影响的新断言。原始 class/method/parameter 去重有 71 项，其中 3 项属于拆名前已被对应通过场景替代的 STATIC 错误；不能表述为原始 71 项全通过。精确参数别名与实际 XML 对应已独立核实。

| 本地直接范围 | 不同逻辑场景 |
| --- | ---: |
| 有界计算、显式值、图余量、未知需求与溢出 | 5 |
| 严格配置加载 | 31 |
| Agent 计划协议及往返 | 10 |
| 上传验证消费者与原独立审批边界 | 4 |
| 容量拒绝、取消、截断、缺值及先前未结效果 | 5 |
| helper 安装成功后拒绝与安装 UNKNOWN | 2 |
| Agent 实际创建/构建、计划状态、重规划及恢复额度绑定 | 2 |
| STATIC 实际发布请求：小/大来源、较少主机空间及明确 4 GiB | 4 |
| 独立 ASSISTED 分析和发布请求：小/大来源及明确 4 GiB | 3 |
| 标准单组件/图最终确认前选额，拒绝不发布 | 2 |

受控请求中，STATIC/ASSISTED 的小来源 AUTO 实际额度为 3355443200 字节，增加来源体量后额度增大；STATIC 同来源仅 3 GiB 可用时选定 2147483648 字节；EXPLICIT 4 GiB 保持 4294967296 字节。Agent 的不同需求产生 268435456 与 671088640 字节，创建后业务重规划保持原额度。这些证明实际接线有变化，不是物理 Linux 卷实测。

3275 原文件前像和 3279 最终文件逐字节保留，36 个原 owner 修改、4 个新文件，非 owner 差异和删除均为 0。父复核当前文件、原前后像、参数映射及补丁正向/反向应用后字节一致；最终正常用户 CIM 显示自有验证进程 0。原用户 IDE 进程未由本任务终止。Skill 的严格元数据和实际目录加载通过；skill-creator 的 quick_validate 因可用 Python 缺 PyYAML 未执行，不声称该脚本通过。

7 个独立源码/测试/Skill 文件直接提交，其他 33 个混合文件及混合 `docs/Config.md` 只交付相对于本阶段前像的自有增量，不整体暂存原迁移。完整 [精确增量补丁](patches/DEPLOYMENT-DYNAMIC-WORKSPACE-QUOTA-16.patch) 同时包含 40 个 source owner 和本次配置说明增量，SHA256 为 `dddc831b1b030408bf20c5c5e505cd81e3237b568aa93416149fdf6cefa6f8ec`；原 source patch SHA256 为 `004ce3fee7834df4a91af44f04c2b4279930b3466aa7741724f0670cfed85cf2`。补丁相对于本阶段原工作区，不是已证明可独立构建的干净 checkout。

本次直接定点验证与真实部署记录分别保存。随后完成的主 Agent 真实对照仍因产品收尾阻塞而保留 UNKNOWN，详见下文。此前通过的本地或真机记录不计为新验收。独立审批、真实前后端业务、成功后的产品收尾、非默认目录真机及重启、其余七项目和最终全新服务器冻结八项目仍需实际证据。

## 真实主 Agent 对照 16-03

`task-board-main-only-16-03` 于 2026-10-13 00:09:02+08结束：产品入口一项 FAIL、零错误/跳过，895.8 秒，3279 源码/Skill运行期间零变化。原 task 为 `dc79c361-80f1-467a-8d36-4935ff4c8355`，原库 `agent-main-only-diagnostic-live-task-board-3tpqmebog`，App 意图 `wtl-agent-main-only-diagnostic-task-board-3tpqnu090`。

主模型自主完成来源识别、计划和候选准备；工具链准备 exit 65 后自行使用 Skill、check、command 与 host_command，命令出现 exit 127 后继续排查，最终封存两个组件并调用 deliver/publish/verify/finish。监督代理未执行服务器命令或提供部署步骤，未中途修改源码。此轮 65 次明确 USER_AUTHORIZED_DIAGNOSTIC 判断，审批业务尝试/调用为 0；它证明本版本的主模型能自主推进到远端发布验证，不能替代完整独立审批验收，也不能把失败整体归为审批过严。

产品收尾将原任务保留 UNKNOWN。只读 H2 核对 Agent effectsSettled=false、平台资源 2766、正式 execution 1 条，phase EXECUTING/outcome UNKNOWN，businessCommitted=false、cleanupComplete=false、publicationTemplate 已保存；正式应用/发布等十表均为 0，没有结果摘要。未进入浏览器、真实跨语言业务及生命周期收尾。原主 DB SHA256 `df8e39a7734dcaac46b4fef971081ba7efa907965a682137b8b8ce2f9aa9b48a` 两次只读审计前后均相同，锁已释放。

账本记录 187 FINISHED →188 ERROR ApiException →189 CAUSE OTHER →190 UNKNOWN，无 CLEANED/DELIVERY_RESULT。两个宿主命令的原检查点均 ENDED/exit 0，其 task/server/指纹/元数据绑定匹配；released_at 在 ERROR 后 7 毫秒。现有代码要求清理前检查点已释放，却直到发布循环 finally 才释放，形成可直接复现的顺序缺陷。原 ApiException 的具体 message 没有保存，不将 OTHER 补写为某个历史错误；继续修复和恢复时保留全部原证据与未结资源。

33 个实际捕获响应全部 LANGUAGE：input1611665、output67509、合计1679174，cache hit850176/miss761489，已知输入命中率52.75%；reasoning51743是输出子集。两个配置能力探测的 provider usage 与 transport次数未知。审批0来自隔离诊断条件，不能用于正式审批成本达标；本轮排除最终全新服务器冻结八项目。

此前 `16` 和 `16-02` 两次入口在 SSH检查/模型配置前因安全凭据副本配对缺失、复制旧未结任务导致关闭校验失败，均为 ERROR，非 Agent部署失败；日志/XML/result保留。改用本轮已保存凭据的新测试库生成安全配置副本后进入16-03。原凭据源主DB及受保护引导文件未改，两个任务创建的临时凭据副本已在停止使用后按精确工作区绝对路径清理；保留的16调查库、原13/14/15和原373正式业务未删除。
