# Agent 八项目验收状态

记录时间：2026-10-11。原 file-transfer 的部署、64KiB 上传下载和重启持久化已通过，原产品恢复收尾完成；425 整条测试仍保留 FAIL。当前仅串行推进原 task-board，实时执行进度以 [CURRENT](../../../.ai-workspace/progress/CURRENT.md) 为准。

## 400 起的新服务器单项目复验

产品连接检查 400、现有产品只读主机观察 401 均为 1 PASS / 0 SKIP，未调用模型。根卷约 29 GiB、可用 27 GiB，未发现已部署应用监听。新主机指纹独立核验后绑定；凭据仅经测试子进程临时环境传入，未写入文档或模型正文。建立独立用量账本，373 起的 3,567,598 已知 Token 不混入本次。

405 经原任务数据库的限定网络字段投影确认：395 的网关声明 `EXTERNAL 0.0.0.0:46110`，实际保存的运行配置为 `HOST=127.0.0.1`、`PORT=46110`；原源码使用该 HOST 启动监听。后端和网关连接后端的地址保持回环符合内部依赖。已确认网关仅监听回环与对外声明矛盾；不能由此断言原 Socket EOF 只有这一种成因。

测试 HTTP 客户端已显式使用直接连接，2 项受控测试覆盖无 HTTP 响应的系统代理，以及完整二进制 POST/响应。网络投影另有 2 项字段过滤检查和 1 项原任务实际读取；405 共 5 PASS / 0 SKIP。Agent 计划提示补充实际监听与端口声明的区别，发布提示明确封存后不能重新规划配置；409 的 3 项发布反馈测试通过，包含监听地址诊断传入真实模型工具观察。以上均未发送模型请求或远端命令。

共享 native 健康检查现要求有效非回环 EXTERNAL 健康端口的监听地址和原 cgroup 归属在同一条监听记录上成立；超时保留有界的预期/实际地址诊断。内部端点、改写为回环的恢复候选、容器、PROCESS/COMMAND 保持原路径。该检查只排除“仅监听回环”，不证明公网、防火墙、NAT、IPv6 dual-stack 或其他外部端口可用。helper 完整字节摘要及独立审批合同同步为 `90bacdd769a17245401ce8e426117bbcb87ba4362a46d9cee8129bcd9892859d`。

407 的 10 组离线行为检查最终通过。410 的 6 项 Java 定点中 5 项通过，1 项因未指定 Windows Bash 路径失败；411 指定实际 Bash 后该项及直接受影响的安装器/资源摘要检查共 3 项通过。连同 405，13 项不同 Java 场景和 10 组 Python 行为检查最终通过，未运行全套。新完整复验仅选择原 file-transfer；正式完整结果仍为 0/8。本页下文保留前次服务器暂停时的历史快照，不能当作当前服务器用量。

## 413 单项目复验及安装引导修正

413 原 file-transfer 任务 `326e4f19-656b-44dd-aea3-1348de46be35` 在 helper 文件安装审批被拒绝，未发送安装命令，未创建候选或构建。完整项目仍未成功。19 次请求全部返回已知用量，共 482,659 Token：部署 292,979、审批 189,680；供应商输入缓存命中 190,080、未命中 284,236，命中率 40.1%，审批占比 39.3%。另 4 次配置探测用量未知。Maven 退出 1、本地 Java 剩余 0，2,038 源码及原八样例 203 文件摘要一致。

源码核对确认：共享文件安装器在缺失运行依赖时会直接调用 apt/dnf，缺少 Agent 已有的原生配置、源与事务认证守卫。拒绝理由另外提到目录所有者，但源码创建后会再次验证所有者和权限；该部分没有证实可绕过目录守卫。

414 将引导行为放在 Agent 专用 renderer，使用 HELPER_RUNTIME 的完整现有包事务程序和固定包表；缺失可信 Python 时在安装前拒绝，不调用裸包管理器回退。默认文件安装器与 STATIC/ASSISTED 默认安装器逐字节一致，公共 helper 摘要不变。独立审批收到全部五段有序源码及完整目标，仍按当前任务、操作、命令身份重新判断；原 413 拒绝保留。

415–418 的 29 项不同 Java 定点最终通过，离线 Python 6 组通过；未运行全套。包含实际 Agent 安装入口的十一种拒绝、守卫、发送及身份边界，以及原共享审批两项直接影响场景。417 初次仅守卫夹具失败：其异常提前触发到了只读就绪检查，限制为独立许可后的发送守卫后，418 单项复验通过。所有本地验证进程已退出。随后只复验原 file-transfer，其他项目保持待验。

此次新增源码及测试直接提交；已有混合迁移文件的本任务增量保留于 [安装修复补丁](patches/AGENT-BOOTSTRAP-414.patch)。补丁基于各文件修复前工作树，完整 before/after 与 SHA 在本轮证据目录；不能把本地提交当作包括其他任务迁移的完整源码快照。

## 419 复验与 420 启动边界修正

419 仍在首次 helper 安装审批被拒绝，命令未发送。任务 `9b55f5db-6975-4064-92a1-2996e04e362a`、16 次实际用量，共 451,764 Token，部署 239,968、审批 211,796；缓存命中 37.8%，审批占比 46.9%。Maven 退出 1、本地 Java 0，2,043 源码冻结零变化。原拒绝保留；两轮没有执行安装，不能据此认定服务器已被污染。

只读审计证实：现存 helper 摘要冲突检查和独立内嵌包事务已在源码中，相关拒绝理由与实现不符；但旧安装 Bash 启动时可能先读取继承环境，确有缺口。420 仅让 Agent 的就绪检查和引导安装使用固定净环境、Bash 特权模式，原共享入口保持原字节；同步完整请求绑定。原生包事务合同补齐 HELPER_RUNTIME 的实际作用域、可信 OS 启动基线和不启用 CRB 的事实，源码导航改为有界位置及摘要，完整当前源码仍保留。

421 明确筛选的 28 项 Java 定点全部通过、无跳过，包括真实本地恶意启动环境的旧/新入口对照、完整独立审批、源码导航和实际 Agent 安装边界；没有调用付费模型或测试服务器，没有运行全套。完整项目仍待真实复验。混合迁移文件仅保留 [此次任务补丁](patches/AGENT-BOOTSTRAP-STARTUP-420.patch)，其他自有文件直接提交。原 before/after 和 SHA 保留；补丁在 LF 规范副本上反向检查通过。

## 422 复验与 423–424 绑定一致性修正

422 的 helper 安装审批和 Go 准备审批已通过，流程到达 Go 绑定；该次独立绑定审批拒绝，未发送 bind。任务 `24926b20-cb44-499b-995c-1c500fff7f9f` 保留为 UNKNOWN，原拒绝和来源证据不重放。33 次请求均返回用量，共 753,837 Token：部署 398,801、审批 355,036，缓存命中 45.3%、审批占比 47.1%；另 4 项配置探测用量未知。Maven 退出 1、本地 Java 0，2,043 源码及原 203 样例文件摘要未变。

拒绝理由混淆了目录身份摘要与归档摘要，同时指出了实际缺口：绑定读取 MANAGED 记录时没有重新核对目录身份和官方摘要。423 仅修正 Agent 的记录校验，要求目录精确等于现有 `agent_installation_key` 派生路径；官方算法为 SHA-256 时再核对归档 SHA-256 相等，SHA-512 不跨算法比较。SYSTEM 记录原校验、安装 key 算法及共享 helper 保持原行为。

审批证据现从当前精确 bind 参数展开有界的完整选择字段和绑定摘要，并导航到 key 实现；明确目录 key、归档摘要及树摘要的不同含义。这些派生事实不证明已经安装，也不授许可；当前完整控制器、命令、目标和独立审批保留。423–424 的 8 组 Python、11 项 Java 定点全部通过、无跳过，覆盖不一致记录在创建/探测前拒绝、SYSTEM 原校验、实际审批请求及篡改展开字段的拒绝；没有调用真实模型或服务器，没有运行全套。

新服务器 400 起三轮调试累计已知 1,688,260 Token，12 项配置探测用量未知；不混入未来同版八项目完整验收。下一轮仍只验证原 file-transfer，其他项目不同时执行。

## 425 部署及业务通过，426–427 原任务收尾恢复

425 原部署任务 `ca096f6e-e86f-4798-996c-a8d039edadce` 返回 SUCCEEDED，Go/Node 两组件构建发布及整图健康通过。外部 HTTP、64 KiB 二进制上传下载和重启持久化全部通过，原样例字节未改；这是部署业务闭环，不包含样例另行规定的 64 MiB、并发和故障注入标准档。

原整条测试仍为失败：最后 DISABLE_AUTOSTART 的前置只读 observe 查询等待 20 秒后返回 SSH_COMMAND_TIMEOUT/UNKNOWN，任务 `c0f7db6d-e99c-44cd-aa95-6b43840e8d34` 未进入实际修改阶段。超时取证时输入已发送、未见输出或退出；不能据此证明最终 SSH lease 未关闭或数据库损坏。源码核对确认 UNKNOWN 状态再次保存 UNKNOWN 证据也会被通用状态规则拒绝，随后显示的“完成持久化需核清”不是本次数据库故障的证明。

426 本地原任务证据读取 1 PASS / 0 SKIP、无 API/SSH。427 通过已有产品恢复入口，将原收尾任务核清为 ENDED/FAILED（修改尚未开始），没有重放原动作；随后产品重新停止应用、禁用自启，并核验 STOPPED/DISABLED。恢复检查 1 PASS / 0 SKIP、没有模型请求，Maven 退出 0，全部本地 Java 已退出。原 425 Maven 退出 1 和失败记录保留；不能改写成整条真测通过。

425 共 123 次实际用量，2,151,746 Token：部署 1,439,561、审批 712,185，输入缓存命中 1,298,304 / 未命中 823,344（61.2%），审批占总 33.1%；66 独立审批、无模拟人工问题，另 4 项配置探测用量未知。新服务器调试累计已知 3,840,006，16 项配置探测用量未知。核心业务已覆盖 1/8，正式同版全新服务器八项目一轮和成本标准仍待验。

428 收尾改为依据最新观察跳过已经 DISABLED 的重复禁用动作，保留最终 fresh inspect 及 STOPPED/DISABLED 双断言。共享账本仅补 UNKNOWN→UNKNOWN 的有界证据保存；已知终态仍需核清，未知任务的归档与资源释放规则不放松。429 明确筛选的 8 项账本、资源保留、原恢复身份及真实提交/归档失败定点全部通过、无跳过，同时编译收尾测试调整；无 API/SSH、没有运行全套。混合迁移测试文件只保留 [本任务收尾补丁](patches/AGENT-CLEANUP-428.patch)，不整体提交其他任务源码。下一步仍串行推广 task-board。

## 前次暂停结果（373–398，历史快照）

原八项目完整验收仍为 **0/8**。395 轮 file-transfer 的 Go 后端和 Node 网关均实际构建、发布，产品部署任务返回 `SUCCEEDED`，整图健康检查通过；随后业务验收第一条外部首页 GET 出现 `Socket: Unexpected end of file from server`。上传、下载、重启和持久化验收尚未开始，因此不能计为完整成功。

该轮任务为 `01a9bfd3-567b-464b-9c20-fd11d3b67f55`，应用为 `wtl-agent-file-transfer-32u11s8w28`。测试 finally 已通过产品停止应用、禁用自启并核验 `STOPPED / DISABLED`；Maven 退出码为 1，本地 Java 剩余数为 0。2,035 个源码文件的运行前后摘要一致，原八样例 203 个文件保持原字节。

396 轮产品只读观察执行 1 项、通过 1 项、跳过 0 项，Maven 退出码为 0，未调用 AI；根卷约 29 GiB，已用 4.5 GiB、可用 25 GiB，两个应用服务已停止。日志未提供业务失败时的实际监听地址；这些证据不足以证明所有临时卷都已回收，也不证明环境必须重装。用户选择以全新服务器重新开始测试和计量。

## 前次服务器用量（373 起）

计量起点为 373 轮服务器连接验证，旧服务器调试用量不混入。以下均为调试轮，不是满足验收标准的同版八项目批次。

| 轮次 | 已知输入＋输出 Token | 输入缓存命中 | 审批占总 Token | 实际结果 |
| --- | ---: | ---: | ---: | --- |
| 375 | 1,537,724 | 52.4% | 37.9% | 工具链安装成功；候选卷容量不足，构建 ENOSPC，原任务 UNKNOWN 保留 |
| 382 | 13,603 | 92.9% | 40.9% | 首次主机探查审批拒绝，命令未发送，任务 FAILED |
| 395 | 2,016,271 | 56.7% | 41.2% | 构建、发布及产品健康通过；外部 HTTP 业务失败 |
| 本次合计 | 3,567,598 | 55.0% | 39.8% | 不满足正式验收条件 |

395 轮输入 1,983,984、输出 32,287；供应商缓存命中 1,125,248、未命中 858,736；部署 1,184,888、审批 831,383。119 次请求均返回用量，71 项审批、0 项人工问题。每轮另有 4 项模型配置探测，其用量未捕获，按未知保留。

缓存率按 `Σ供应商命中输入 / Σ实际输入` 计算；审批占比按 `Σ审批输入及输出 / Σ全部输入及输出` 计算，不平均项目百分比。正式最低要求仍为同版、全新服务器、无中途修改的一轮原八项目全部通过，缓存至少 60%、审批不超过 20%；努力目标为缓存至少 80%、审批不足 10%。

## 已完成修正及源码边界

- Agent 候选容量使用既有 4 GiB 配置，并贯穿实际创建与构建核算；压缩归档加两倍解压源码的下界、整数溢出和宿主空间预检均在创建前检查。12 个唯一定点测试最终通过、无跳过，没有运行全套。
- SSH Frame 使用 Bash 特权模式屏蔽启动文件、导出函数及部分环境选项的继承，两处读取显式调用 builtin；Agent 已有隔离环境的内层同步设置。8 个唯一定点测试最终通过，包含旧实现失败、新实现成功的本地受控对照。
- SSH 修复已本地提交 `7229a151`，未推送。容量修复涉及四个已有混合或未跟踪迁移文件，原字节、最终字节及本任务增量分别保留，尚未整体提交；该提交号不能代表完整工作树。
- 397 的两项审计仅阅读源码和公开执行证据，没有修改代码、执行测试或发送远端命令。

## 恢复后待解决

1. 定位外部 HTTP 失败的直接原因。已确认现有本机健康探针只验证回环响应及服务进程归属，不证明 EXTERNAL 端点实际对外监听；395 的最终 HOST、PORT 和失败瞬间监听证据尚缺。默认客户端代理是另一待核实因素，当前本地独立 JVM 的选择为 DIRECT，不能替代原测试 JVM 的证据。
2. 完成通用 Agent 失败回收。现有工具链 detach 依赖成功冻结回执，UNKNOWN、部分创建失败及部分 bind 失败会保留临时卷；Java 恢复在执行、PREPARE 或 CAPABILITY 未核清时会先停止，候选清理可能到不了。建议将执行结果核清与资源清理分开：先保留原失败证据，再凭精确 operation、源码、unit、image、loop 和 mount 归属证明停止、卸载、detach 及删除本临时卷。资源清理成功不能改写原 UNKNOWN，不能补造执行结果。
3. 核实当前最终运行配置与外部监听后，以单个完整项目形成闭环，再推进原八项目权限矩阵。单组件验证仅算调试节点，不能替代原完整项目。
4. 在核心稳定后的合适阶段提取安装、控制、配置、持久化等目录配置，再优化缓存和审批用量；保持 STATIC、ASSISTED、AGENT 独立，避免模型或样例特化。
5. 下一轮先通过产品核验新服务器连接、主机指纹与容量，再建立独立用量账本；原拒绝和 UNKNOWN 不重放，历史调试不并入新批次。

上述失败回收方案尚未实施或通过真实服务器验证。前次暂停期间没有启动新的模型请求、部署或远端清理；本次已按用户唤醒恢复单项目工作。

详细历史见 [失败分析](AGENT-FAILURE-ANALYSIS.md) 和 [AI 验收记录](AI-LIVE-VERIFICATION.md)；原日志、用量与源码摘要位于 [本轮证据目录](../../../.ai-workspace/agent-live-evidence/agent-root-cause-20261010/)。

## 430 task-board 首次主机基线拒绝

file-transfer 业务及原产品恢复收尾完成后，仅串行测试原 task-board（Java＋Node，MANUAL_REVIEW，remember=false）。首次固定主机基线独立审批 DENY，命令未发送，任务 `937e921c-eddc-4573-bd01-3ffe224099c5` 返回 FAILED/ACCESS_DENIED；部署和人工审批阶段未开始。原拒绝保留。两次请求均返回用量：13,936 Token，部署 8,124、审批 5,812；输入缓存命中 12,672、未命中 1,025（92.5%），审批占总 41.7%。另 4 项配置探测用量未知。Maven 退出 1、本地 Java 0、2,043 源码及 203 原样例字节未变。新服务器调试累计已知 3,853,942，20 项配置探测未知，不能作为正式八项目指标。

当前理由要求预先提供服务器 OS 二进制摘要。源码中 uname/dpkg/rpm 已使用固定绝对路径，apt-get/dnf/systemctl 仅检查可用性；当前所有权、不可写性及父目录守卫完整，系统二进制预置摘要不属于现有 OS 信任合同。接下来核对并补齐 Agent 固定基线的机械源绑定与该信任边界，避免任意命令借用标签；不重复同一付费拒绝。

## 431–432 Agent 固定基线信任与源码绑定

仅 Agent baseline 的启动 PATH、guard PATH 和目录扫描集合去掉 `/usr/local` 搜索目录；uname/dpkg/rpm 的固定绝对路径及文件守卫保留。container 渲染保持原字节，共享 capability probe、默认 guards 以及 STATIC/ASSISTED 路径不改。root-owned 受保护系统文件与 OS/SSH 启动是既有信任假设，并不等于实际二进制摘要测量，也不防御已被 root 篡改的系统、完整中间 symlink 链或检查与使用竞态。

Agent 审批导航只有准确 operation、完整 canonical 脚本、空 stdin 长度/摘要、真实 output/timeout 边界及唯一当前 deployment-host 目标匹配时生成。脚本仍完整保留在 exactAction；导航 SHA 和范围仅绑定显示的脚本字节，不伪造远端工具已验证结论。apt-get/dnf/systemctl 仅检查存在性。facts 拒绝注入导航，messages 重算并核对导航及原会话绑定；任意同标签脚本继续独立完整审查，不借用信任。当前每条命令仍独立审批，原有效 DENY 不重审、不取得宿主许可或 dispatch。

432 明确筛选的 14 项 Java 定点全 PASS/0 SKIP，四个自有 Java 文件按项目 IDEA formatter 处理；没有调用真实 API/SSH，没有运行全套，所有验证进程退出。两个已有混合迁移文件仅保留 [本任务增量](patches/AGENT-HOST-BASELINE-431.patch)，before/after/SHA 与原用量单列保留。该补丁以零上下文记录自有增量，使用 `git apply --unidiff-zero`，当前反向检查通过。下一步仅 fresh 复验 task-board，不能由本地通过推断真实部署或审批通过。

## 433–437 task-board 发布失败与原任务核查

433 原 task-board 使用 MANUAL_REVIEW、remember=false，Java、Node、Python 的受管工具链准备成功，源码构建和制品封存完成。发布返回 `application-health-timeout`；随后模型提议删除和重建发布目录，完整独立审批发现路径归属守卫缺失，且正文没有其理由承诺的服务安装、启动和健康核验，正确 DENY，命令未发送。原任务 `49f9dd9a-2857-440c-836c-f708ba2ef3c5` 保持 UNKNOWN，两份 FILE 资源保留；不重审拒绝或重放原命令。Maven 退出 1、本地 Java 0，2,045 源码及原八样例 203 文件字节未变。

87 次请求均返回用量，共 1,364,509 Token：部署 709,630、审批 654,879；供应商输入缓存命中率 51.2%，审批占总 48.0%。51 条已审阅动作对应 51 次模拟人工确认，未启用记忆授权。另 4 项配置探测用量未知。400 起服务器调试累计已知 5,218,451，24 项配置探测未知；仍未满足正式同版八项目验收标准。

434 通过已有产品只读观察入口读取原服务 journal，1 PASS/0 SKIP，没有模型请求：`run-backend.sh` 第 7 行执行 `java`，命令不存在，进程退出 127。源码确认临时 application-job 设置已核验工具链 PATH，永久 PROCESS unit 没有该设置；绑定本身已随候选传递，JAVA 安装并未失败。此证据不能证明修复 PATH 后其他启动、健康路由或数据库配置都正确。

435 使用现有产品原任务恢复入口，确认原服务器及 helper 身份后仍为 UNKNOWN，1 FAIL；公开结果是 `DEPLOYMENT_AGENT_FINAL_ASSESSMENT_MISSING`，原发布开始但没有最终评估记录。436 本地元数据检查因参数名误用单数失败，未连接服务器或调用模型；437 更正为现有入口的复数参数后 1 PASS。命令检查点为 ENDED 3、INTENT 1，没有 DISPATCHED；原回滚记录、拒绝、来源和任务身份均保留。这三项检查没有新增模型请求，不能把恢复失败记成完成。

438 选择 Agent 独立配置修复：在新发布提案及不可变配置创建前，从当前来源绑定、实际已执行的工具集合派生缺省运行 PATH。显式 RUNTIME Text PATH 不被覆盖，BUILD-only 或非文本声明拒绝，原 64 条目及 1,024 字符限制保留；共享路径计算只提取原有逻辑，helper 字节、摘要及旧 unit renderer 保持不变。通用计划提示要求读取实际 HTTP 路由和存储消费者，区分目录与文件配置语义，不引入模型或样例特化。

439 的 12 个明确筛选场景中 11 PASS，1 个 Git Bash 夹具因临时目录权限失败；440 改为 JUnit 所有的临时目录后，仅复验失败场景，1 PASS。最终 12 个不同场景全 PASS/0 SKIP：覆盖构建及运行路径一致、显式配置和预算边界、真实隔离环境下的可执行文件查找、配置摘要变化，以及直接受影响的构建、helper 完整字节和 HTTP 工具合同。受控启动包含未提供路径时退出 127 的对照，但没有运行真实 systemd、模型或服务器，也不等于发布健康验证。两份新 Java 文件完成 IDEA formatter；所有验证进程退出，首次失败留下的空测试目录已限定路径清理。三个已有混合迁移文件只记录 [本任务增量](patches/AGENT-RUNTIME-PATH-438.patch)，完整 before/after/SHA 单独保留，补丁反向检查通过；未整体提交其他任务迁移。下一步只 fresh 复验 task-board。

## 441–443 下一轮容量检查与等待重装

运行路径修复四文件限定提交为 `aebf528c`，未推送；三个已有混合迁移文件的自有增量通过正式补丁保留，HEAD 不代表完整工作树。新付费部署尚未开始。

441 新增只读测试包装器，直接调用已有产品 `DeploymentCandidateChannel.availableBytes()`，没有另写服务器容量命令。442 的原任务元数据投影 PASS，原 `GRAPH_ROLLBACK_RESULT` 第 137 条实际为 RESTORED；容量夹具因请求身份不是规定的 SHA-256 失败，发生在构造通道时，未发送容量请求。443 使用当前任务生成合法身份后，仅复验容量场景，1 PASS/0 SKIP，Maven 0、Java 0、没有模型请求。

产品测得可用 **13,459,542,016 字节（12.54 GiB）**；当前 workspace 为 4,294,967,296 字节，两个组件连续准备所需起始下界为 **13,958,643,712 字节（13 GiB）**。该下界沿用现有每次创建前的 `2W＋1GiB` 检查，加上先创建的一个 W，不是空间预留，也不保证后续其他占用。当前低于下界 499,101,696 字节，未发起新付费部署或删除原 UNKNOWN 候选。已请求用户重装一次性服务器，或扩容并确保至少 20 GiB 可用；就绪后继续只测 task-board。

用户已重装并提供新连接信息。444 因本地 PowerShell 参数引用遗漏导致 Maven 未执行场景；445 修正引用后，产品连接和只读容量两场景均 PASS/0 SKIP，Maven 0、本地验证进程退出，没有模型调用。新指纹为 `SHA256:AmqPg28C3v3Pn4WNUm5tSsRzOoR49i6c8M3eJpNZdlY`，实际可用 27,963,637,760 字节（26.04 GiB），足够本次两组件准备。旧主机信任与用量账本不作为新服务器的已验证事实；本次仍只推进 task-board。

存储占用的源码原因是每组件固定预分配，而非项目文件实际达到 4 GiB：共用 helper 用 `fallocate` 实际预留整个 workspace，并以 `nodiscard` 格式化，任务看板两个候选因而预留约 8 GiB。此前 Agent 错按归档大小及 256 MiB 下限分配，实际构建曾写满；377 修正为使用既有 4 GiB 配置后，占用变得可见。STATIC、ASSISTED、AGENT 共用这一底层卷机制，当前 Agent 发布失败的恢复缺口使旧候选未回收，叠加新任务的空间需求。没有逐个旧映像的实际块占用核查，不能将服务器全部已用空间归因于这两份候选，也没有证据表明 AI 输出或业务数据消耗了这些容量。后续优先修复 Agent 失败收尾；改变共用预分配方式另行评估，不能缩回曾写满的 256 MiB。

只读审计确认恢复分支遗漏：`AI_PUBLICATION_FINISHED` 只由成功 verify/finish 写入，但恢复对所有发布开始的任务都要求此事件，已知原生失败并回滚没有对应分支。单条 RESTORED 仍不能代替原绑定、快照、命令顺序及当前全图状态核对；不能据此把原任务改成 FAILED 或完成。保留 UNKNOWN 的精确候选清理也必须先证明无未结命令、无活动构建、原全图已恢复，不能仅凭候选目录存在或事件计数删除。当前没有实施该恢复合同修改，不调用会丢弃回滚快照的通用 cleanup，也不放松同目标准入。此项列为后续 Agent 专用修复，原 FILE/来源/回执保留。

441 测试包装器及固定枚举投影仅记录 [本任务增量](patches/AGENT-CAPACITY-441.patch)，原混合文件不整体提交。400 起已知用量保持 5,218,451 Token，仍是失败调试统计；空间检查和本地修复没有新增部署或审批模型用量。正式八项目结果尚未达标。

## 446–450 新服务器 task-board 发布与收尾拒绝

446 仅运行原 task-board，MANUAL_REVIEW、remember=false。原生构建、封存、发布和整图独立健康核验完成，成功 finish 评估已持久化；最后候选卷清理的独立 APP 拒绝，因为此次请求缺少候选归属、构建非活动及当前发布关联证据。清理未发送，原任务 `1b9d7376-6a8e-4f0f-86e7-57fecb4e37ac` 保持 UNKNOWN，公开问题为 `DEPLOYMENT_AGENT_CANDIDATE_CLEANUP_UNCONFIRMED`。原拒绝与两份 FILE 保留，不重放删除，也不把部署健康当成完整业务验收。

该轮 Maven 退出 1，本地 Java 为 0；2,047 个源码及原八样例 203 个文件在运行前后字节不变。126 次请求全部返回用量，共 **2,353,861 Token**：部署 1,309,092、审批 1,044,769，输入 2,321,620、输出 32,241；供应商输入缓存命中 1,218,688、未命中 1,102,932，命中率 **52.5%**，审批占总 **44.4%**。73 条审批对应 73 次模拟人工确认，另 4 项模型配置探测用量未知。以上为 445 新服务器调试账本，不混入旧服务器 5,218,451 Token，也不满足最终同版八项目验收。

449 通过现有本地原任务元数据入口确认 `AI_PUBLICATION_FINISHED`、`AI_PUBLICATION_VERIFIED` 各一条，清理仅有 EXECUTING、无 RESULT；1 PASS、无 API/SSH。450 通过现有产品只读服务器观察入口确认两服务 active/running；根卷约 29 GiB、已用 13 GiB、可用 17 GiB，候选卷尚在，没有新的空间不足证据。该观察 1 PASS、无模型请求，Maven 与本地验证进程均已退出。

实际网站端口为 18100、API 为 18101，而本轮 Agent 输入明确要求 publicUrl 的 46100，并为浏览器直接调用 API 指定 46101。业务断言尚未运行，恢复清理即使成功也不能替代此项验收。后续仍只推进 task-board：修复 Agent 当前清理证据传递，并核查用户 requirements 至计划、发布的约束；不手改已封存提案，也不按样例默认端口降低标准。

451 只读源码审计确认，现有 `publication-original-status` 已检查原任务候选归属、构建活动状态及当前发布归属，但这些真实回执没有进入清理 APP。修复仅在 Agent 增加按当前清理调用绑定的证据补充器，沿用原产品查询与恢复入口；共享清理编排、helper 和 STATIC/ASSISTED 默认审批保持。历史 source/artifact 绑定与当前远端事实必须区分，不能声称查询重新计算了制品内容，也不能把 artifact SHA 与 release SHA 混为一值。真实恢复尚未验证，原 UNKNOWN 继续保留。

453 已在 Agent 接受计划、解析资源和修改封存状态之前增加网络守卫：PROCESS 不允许无实现的 host/target 端口映射；结构化 publicUrl 必须对应 EXTERNAL 端口，容器真实映射和未要求外露的 CLI 保留。提示补充用户要求优先于源码默认、浏览器回环地址指向用户机器，以及实际监听、浏览器 API/CORS 应先核验再封存。该守卫不会修改模型计划，也不能证明启动脚本兑现了声明。

453 唯一一次明确筛选 Maven 共 44 个场景，42 PASS、2 FAIL：新增网络约束 22、Session 拒绝后保留旧计划与封存 3、原计划编码 10 均通过；请求协议 9 项中 7 通过、2 项期待动态 pending schema 的断言失败。before/after catalog 均为相同固定 schema，未因这两个既有差异回退实现或删除断言。Maven 退出 1、Java 为 0；这些本地结果不能表述为全范围通过或真实部署通过。

父验收包装器仅对 Agent 增加真实浏览器 API 配置、跨域 GET/POST 预检、协议响应头可访问及浏览器访问路径的重启持久化检查，保留同源代理的合法实现。task-board/survey 原本直接调用 API 的验收地址也在输入中写明；原样例文件和 STATIC/ASSISTED 的业务默认不变。这些是 HTTP 与配置验收，不是浏览器 DOM 自动化；下一次真实 task-board 才执行。

452 清理证据及直接调用方共 45 个不同定点场景通过、无跳过：先前 23 个清理场景，随后新增 9 个审批及宿主授权后绑定变化/拒绝场景、2 个原命令证据场景、2 个原恢复身份场景和 9 个发布调用方场景。最初 fixture 缺真实 references 导致 23 项失败，补齐真实正式执行形状后通过，未放宽生产校验；最后一次 Maven 退出 0，全部验证 Java 退出，没有真实模型或 SSH 请求。

补充证据默认接口为空，Agent 之外保留原审批事实与路由读取次数。清理前的新查询仍独立审批，回执只服务该次清理；审批后及真正发送前重新核 candidate/source/artifact/tools 和原正式提案，删除后再次确认 absent 且非活动，无法确认则 UNKNOWN。原拒绝不改写，不能在同一证据生命周期循环求批准。原外层 DELIVERY 未结结果也不补写，只有严格匹配原已核实完成路径时才允许当前清理。

454 通过已有产品本地元数据投影，确认原不可变端点实际是 backend `46101→18101`、frontend `46100→18100`，两组件都是 PROCESS；1 PASS，Maven 0、Java 0，无 API/SSH。这是实际保存的提案证据，已不只是模型自述推断。当前新网络守卫会拒绝此形状；原 446 恢复只用于技术发布结清和清理，不能算业务通过。

三个正式零上下文补丁记录本次八个已有混合文件的自有增量：[清理证据](patches/AGENT-CLEANUP-EVIDENCE-452.patch)、[网络计划](patches/AGENT-PLAN-NETWORK-453.patch)、[Agent 浏览器验收](patches/AGENT-BROWSER-ACCEPTANCE-454.patch)。before/after 原字节与 SHA 单列保留，反向检查通过；原 203 样例及 70 个 helper 冻结文件字节未变。混合迁移不整体提交，提交不能代表完整工作树。

454 原产品恢复已结束：Maven 1、Java 0，2053 个源码文件摘要零变化。原任务仍 UNKNOWN，恢复在候选清理前停于 `DEPLOYMENT_GRAPH_HEALTH_UNAVAILABLE`，没有执行新清理或停止/禁自启。新增四次 APP 实际用量 17,337（输入 14,257、输出 3,080、缓存命中 11,264，缓存率 79.0%），没有部署模型请求；当前新服务器调试累计 2,371,198，不计正式同版八项目验收。

454 的直接原因已经从真实响应元数据和调用链确认：第四次 APP 返回 `finishReason=stop`，结构化提交器在解析决定和宿主授权之前拒绝纯文本；恢复 scope 没有持续审批会话，旧条件因而未设置 `requiredTool=submit_result`。这次失败不能称为审批 DENY 或服务器不健康。

455 已统一 APP 的单工具结构化提交约束；语言部署、空工具、其他工具及多个工具的原选择保留，不做模型或供应商特化。单轮和持续审批仍逐条判断，纯文本、无效形状和合法 DENY 均不能取得执行许可。共享健康查询增加可选诊断观察器，默认调用保留原 UNKNOWN；只有 Agent 恢复接入既有脱敏诊断，诊断失败也不改变结果。19 个不同定点场景全部 PASS、无跳过：新增提交场景 13、原持续审批 1、健康异常/拒绝/诊断失败/默认结果 4、三种 API 协议工具选择 1；Maven 0、Java 0，没有真实 AI/SSH 请求。

455 两份正式补丁只保存四个已有混合文件的自有增量：[统一审批提交](patches/APP-REQUIRED-SUBMISSION-455.patch)、[Agent 健康诊断](patches/AGENT-HEALTH-DIAGNOSTICS-455.patch)，反向检查通过。两个已跟踪源码的增量无法直接应用至较旧 HEAD，未整体暂存迁移内容；原字节备份及 SHA 保留。真实恢复和新任务看板部署仍待验证。

456 原产品恢复已结束：Maven 1、Java 0，2054 个源码摘要零变化。第四次 APP 已正确进行原生结构化提交，但合法 DENY：当前审批缺少应用、服务单元、发布目录与健康探测参数的对应证据。命令没有发送，原 UNKNOWN 和拒绝保留，未进入清理或停止/禁自启。四次新增 APP 实际用量 15,487，输入 14,277、输出 1,210、缓存命中 13,568、未命中 709，缓存率 95.0%；当前新服务器跨版本调试累计 2,386,685，不能作为正式批次指标。

457 仅在 Agent 原任务恢复补充当前健康证据。复用整图验证已有的逐组件原状态查询，不新增查询；每次 APP 仍审查完整当前命令。补充原正式提案的 unit、systemd/container、发布根、manifest、配置与制品身份，原 typed 健康策略及其完整编码，再关联本轮完整查询命令与回执。查询证明当前发布归属和构建状态，监听归属与健康仍由随后 helper 执行时检查；不宣称重新计算远端封存制品、不把制品与发布 SHA 混用，也不复用历史许可。

457 绑定在 APP 后、宿主授权后及实际发送前重新校验，缺失、变化或合法 DENY 都保留 UNKNOWN。新增 38 个受控健康证据场景及一个理由提示场景，39 PASS、无跳过，Maven 0、Java 0，没有真实 AI/SSH 请求。成功场景经真实 `GraphPublisher.verifyPublished` 验证两个不同组件，保持原有两次状态查询；本地 fixture 不能证明服务器已恢复。原外层 DELIVERY 不合成结果。

审批理由软性目标为一句、160 字以内；Agent 公开结论软性目标为 200 字以内。完整命令、源码、typed 参数、审批规则、输出上限及校验器不变，必要事实仍应完整提供，不能截断证据。两份正式补丁保存已有混合文件自有增量：[恢复健康证据接入](patches/AGENT-HEALTH-EVIDENCE-457.patch)、[简短模型理由](patches/AI-CONCISE-REASONS-457.patch)，反向检查通过。下一步只通过原产品入口恢复旧 task-board，再开始新的完整 task-board；原端口错误提案仅用于结清，不计业务成功。

457 已限定提交五个自有文件，提交 `345feb77`，无推送。458 原产品恢复已结束：Maven 1、Java 0，2056 个源码摘要零变化。两个组件健康检查通过，backend 的 inspect/observe 和 frontend 的 inspect 也通过；frontend 的 observe-deployment 在独立 APP 获合法 DENY，未发送。实际第二个参数是 ownership manifest SHA，审批将其误认为资源 ID；此时补充器仅为健康检查提供对应事实，运行观察仍缺少可直接核验的参数映射。没有进入清理或停止/禁自启，原 UNKNOWN 保留。

458 九次新增 APP 实际用量 45,216，输入 43,957、输出 1,259、命中 23,296、未命中 20,661，缓存率 53.0%。新服务器跨版本调试累计 2,431,901；不作正式批次指标，也不把健康通过写成部署业务成功。下一项修复覆盖原图验证已有的 canonical inspect-runtime/native observe-deployment，复用同一批当前状态查询；不加查询、不改 helper、逐命令审批与发送前绑定校验保持。

459 已补齐 canonical inspect-runtime 与原生 observe-deployment：第二个位置参数明确是归属 manifest SHA，应用 ID、manifest、releaseRoot、原系统单元和当前原发布逐组件绑定。APP、宿主授权后及实际发送前重新核对；取得许可后替换 helper 或内联命令同样拒绝。容器内联观察仍走原完整独立审批，默认无补充证据，其业务和命令未复制或修改。运行观察 DENY 保存理由、完整动作和证据摘要并终止本次验证。

459 只运行新增 13 个及直接受影响 6 个场景，共 19 PASS、无跳过，Maven 0、Java 0，无真实模型/SSH 请求。没有重跑整个测试类或全项目。两个 Java 文件的精确 IDEA 格式化与差异检查通过，原字节、格式化后 SHA 和定点日志保留；当前原任务仍 UNKNOWN，真实原恢复 460 和新的完整 task-board 461 尚待执行。

459 限定三文件提交 `9d0c5b8d`，无推送。460 原恢复提前停在第二次 original-status：Maven 1、Java 0，2056 个源码摘要零变化，三次 APP 实际用量 11,312，缓存率 94.2%，没有继续健康或清理。462 通过现有本地产品元数据入口确认新增 `HEALTH_STATUS_QUERY_DENIED`，1 PASS、Maven 0、Java 0，无 AI/SSH；因此确认为合法 DENY，具体理由仍缺失，不能把它归为响应错误或仅凭空补充事实判定全部根因。

原状态查询前的证据分支目前返回空补充，健康及运行观察有映射而该查询自身没有。下一项修复补充原正式提案、本地选中绑定和七个规范参数的对应，明确查询尚未执行、没有本次远端回执，不把预期 CURRENT 写成已观察事实。另补原状态内部捕获的可选异常诊断传递，保留默认 UNKNOWN 和原拒绝，验证后才用新证据经原产品入口恢复。新服务器跨版本调试已知累计 2,443,213，不能作为正式同版八项目验收用量。

463 已补齐 STATUS 七参数、资源目标、原正式引用与选中绑定，证据明确 `NO_RESULT_FOR_THIS_PENDING_REQUEST`。实际回执仅在同次原查询成功且完整时记录；旧拒绝不重写，新拒绝停止本生命周期，审批及发送边界继续核验。健康/运行观察的原字段保持，容器内联默认审批保留，查询次数保持原每组件一次。

464 增加原状态捕获异常的可选诊断观察器，并在 Agent 恢复的整图验证中传递原观察器；旧无观察器调用保持原 UNKNOWN，诊断失败也保留原结果及原异常。18 个查询证据新增/直接受影响场景、4 个新诊断场景及2个直接健康异常场景，共24 PASS、无跳过，Maven 0、Java 0，无模型/SSH请求。四个Java文件精确格式化、差异检查通过；没有运行整个测试类或全项目。[状态诊断补丁](patches/AGENT-STATUS-DIAGNOSTICS-464.patch)只记录已有混合源码的本次增量，反向检查通过。下一步原产品恢复465；仍不能算任务看板业务成功。

463/464 已限定五文件提交 `aa89e740`，无推送。465 原恢复的两个 original-status 审批均通过，随后 backend application-health 获合法 DENY：理由称 frontend 维护标记令后端目标存在歧义，并要求可执行的监听归属证据。该健康命令未发送，原任务仍 UNKNOWN，没有清理、停止或禁自启。Maven 1、Java 0，2056 个源码文件摘要零变化；四次新增 APP 用量 31,950，输入 31,420、输出 530、缓存命中 13,440、未命中 17,980，缓存率 42.8%。445 新服务器跨版本调试累计 2,475,163，四项配置探测用量仍未知。

466 只读 helper 审计确认，维护标记按组件独立保存，frontend 的标记不能推导 backend 目标错配。健康入口仅略过本组件的持久 marker 拒绝，仍获取本组件锁并核对 orphan；可能创建锁文件或清理已核验的旧作业，不能称为完全无写。现有监听检查证明该端口存在属于目标 cgroup 的 PID，未把 HTTP 的具体连接逐一绑定到该 PID。下一项 Agent 专用证据修复从实际、完整摘要已核验的 assembled helper 提取有界源码段，并明确未展开依赖及上述限制；不改变 helper、命令、独立审批或沿用历史许可。修复尚待定点与真实恢复验证。

467 的 Agent 健康补充现包含完整摘要已核验的实际 assembled helper 源码段、精确行位置和逐段 SHA；源码全部纳入当前审批证据摘要。入口采用精确行锚点，缺失、重复、逆序、越界或完整源码漂移均停止。逐组件维护标记保留各自完整回执的实际 token，不改变后端目标或推断维护已结束。HEALTH 附源码，STATUS 保留待执行查询事实；运行观察只增加当前逐组件标记事实，容器内联默认审批保留。锁文件创建、打开/截断、已核验旧作业清理等副作用，以及未展开依赖、可选 override 和 HTTP 具体响应归属限制均明确展示。

467 只执行新增 9 个与直接受影响既有 18 个场景，27 PASS、无跳过，Maven 0、Java 0，没有 API/SSH 请求。实际 `renderScript()` 的逐行字节、完整 helper pin、逐段 SHA、健康证据摘要和二组件标记正/反例均通过；原 DENY 终止及 APP/宿主授权/实际发送三处绑定检查保持。四个 Java 文件精确格式化，运行前后 SHA 一致，原 203 样例和 59 个已冻结 helper 资源未变。真实恢复 468 和新任务看板业务验收仍待执行。

468 原恢复再次在 backend 健康审批停下。两个原状态查询通过，新的合法 DENY 称健康入口会执行未展开的 COMMAND/probe 辅助程序，并把后端没有维护标记时的 `matchesOwnExpectedRelease=false` 视为不匹配。实际当前请求为 DAEMON + HTTP；尚需逐源码核对前置解析与分支条件，不能只凭模型理由断言该请求会执行那些程序。命令未发送，原 UNKNOWN 与拒绝保留，没有清理或 STOP/DISABLE。Maven 1、Java 0，2058 源码冻结零变化；4 次新增 APP 为 55,695 Token，输入 55,170、输出 525、缓存命中 14,720、未命中 40,450，缓存率 26.7%。445 跨版本调试累计 2,530,858，四项配置探测用量未知；不作为正式同版八项目指标。

469–471 已核对并补齐当前健康请求的实际路径证据：入口在 health_loaded 前解析当前 canonical payload，重设 kind；若实际 mode 变为 ON_DEMAND，非 COMMAND 会先拒绝，因此当前 HTTP 不会执行 verification 或 UDP custom probe。证据明确保留锁与 orphan 副作用、未展开依赖和 HTTP 响应归属限制，不授权执行。维护标记新增 ABSENT / OWN_EXPECTED_RELEASE 和是否存在等明确事实；旧 false 保留，但说明“无标记”不是外来身份冲突，foreign token 仍拒绝。

471 仅新增 8 个及直接受影响既有 19 个场景，27 PASS、0 跳过，Maven 0、Java 0，无 API/SSH 请求。其中六个实际 Git Bash 场景运行完整 pinned 入口、解析器和健康分支，OS/加载/程序边界使用显式受控夹具，不能表述为完整 helper 或真实服务器验证。三 Java 精确格式化及运行前后 SHA 核对通过；467 的 HelperSourceEvidence 两文件、原203样例及59个冻结 helper 资源未改。接下来通过原产品恢复472；UNKNOWN 不会释放 TaskLedger 的 TASK_END 逻辑申请，公开 blockers 又省略这类申请，故不得以没有 SERVER blocker 或新数据库绕过原任务准入。待原恢复结清后，重新取得当次产品容量回执并明确检查至少13GiB，再仅复验任务看板。

472 原恢复仍在 backend 健康审批停止：两个 original-status 查询通过，合法 DENY 认为三参数接口只提供两项输入。当前 selectHealth 实际严格匹配 applicationId、ownershipManifestSha256、canonical health payload 三个参数；完整补充证据已发出，健康请求 visible bytes 为113,583。命令未发送，没有清理或 STOP/DISABLE，原UNKNOWN及拒绝保留。2059源码冻结零变化、Maven1、Java0；新增4次APP为58,483 Token，输入57,870、输出613、命中14,720、未命中43,150，缓存率25.4%。445跨版本调试累计2,589,341，四项配置探测仍未知，不作为正式验收指标。下一小修复仅在Agent HEALTH证据补有序三个literalArguments与实际canonical request字节匹配事实，避免与runtime两参数展示混淆，不改变执行命令或审批权限。

473/474 的补充仅作用于 Agent HEALTH：保留原字段，增加有序三个 literalArguments 和独立 resource target；展示实际与 expected canonical 完整脚本的机械匹配结果，并通过既有 verifiedBody 提取易读调用正文。该 expected 来自 selectHealth 已匹配的实际请求，全量纳入证据摘要；未增加 shell 解析器、改变命令、helper 或审批权限。

474 只执行新增3场景和直接受影响20场景，23 PASS、0跳过，Maven0、Java0，无API/SSH。正例验证完整三实参、实际UTF-8字节与证据SHA；缺payload/替换unit均在证据或发送前拒绝；原APP、宿主授权、发送三处重校验与合法DENY终止保留。两Java精确格式化与运行前后SHA检查通过，203样例、59helper资源以及467 HelperSourceEvidence与470 Bash测试文件未变。接下来仅进行原产品恢复475，不能将本地通过或原恢复结清计为任务看板业务成功。

475 原恢复再次获得合法健康 DENY：两个原状态查询通过，三参数问题未再被提出，但审批理由回到固定锁写效果未获授权及 run_application_native 未展开。命令未发送，原UNKNOWN及拒绝保留，未清理或STOP/DISABLE；2059源码冻结零变、Maven1、Java0。新增4APP为61,800 Token，输入61,299、输出501、命中14,592、未命中46,707，缓存率23.8%。445跨版本调试累计2,651,141，四项配置探测仍未知。476只读核查确认可信共享合同对health只给高层描述，缺三参、固定锁/orphan副作用与HTTP分支边界；补充的重复固定源码触发第二套审查的呈现问题有依据，但静态核查不能证明这就是模型拒绝的全部原因。下一修复为Agent专用固定摘要健康SYSTEM合同；保留动态完整命令、独立APP和当前宿主单次授权，默认STATIC/ASSISTED不改。原源码提取器仅移到测试验证用途，不能称作取消动态程序源码审查。

## 477–478 Agent 健康审批合同

477/478c 已完成：Agent 健康审批增加独立协议/完整 helper SHA 固定的 SYSTEM 合同，准确描述三实参、锁/orphan 写效果、执行分支与 HTTP 归属边界；USER 保留完整动态命令、当前绑定与独立审批。共享 hook 默认空，原 STATIC/ASSISTED SYSTEM 字节兼容检查通过；未知固定 hook 在 persistent 会话降级前拒绝。固定源码提取器逐字节移至 test，生产无旧 class。45 定点 PASS（新增6/直接影响旧39）/0SKIP/Maven0/Java0/noAPI/noSSH，九源码 SHA 与原203fixture/59helper零变；478/478b仅夹具构造/调用参数编译失败，已修正，无付费调用。三混合源码只记录自有增量 patch，mirror反向检查通过并清理。下一步限定本地提交后仅原恢复479；原任务ENDED和产品STOP/DISABLE通过后核验当前容量>=13GiB，再只fresh task-board。旧UNKNOWN/拒绝及跨版本计量保留，正式目标未完成。

三个已有混合或未跟踪源文件的自有增量见 [合同补丁](patches/AGENT-HEALTH-REVIEW-CONTRACT-477.patch)；不整体提交其他任务的迁移源码。补丁使用零上下文，检查或应用需带 `--unidiff-zero`；独立镜像反向检查通过，镜像已清理。可信合同由产品静态文本与独立 helper pin 提供，不接受模型或远端观察生成的 SYSTEM 指令，也不授予任何执行许可。未知 persistent 合同仅拒绝，不改变共享会话合同。

## 479 原状态查询合同范围问题

479 原产品恢复已结束：第二 original-status 独立APP合法DENY，理由为该七参数动词不在健康专用合同内；健康检查尚未进入。固定HealthEvidence的reviewInstructions无条件附合同到STATUS/runtime，480仅Agent收窄至当前已严格匹配HEALTH分支；原动态证据和权限不改。原UNKNOWN/拒绝保留，未cleanup/STOP-DISABLE。2061源码freeze零变/Maven1/Java0；新增3APP29356(input29007/output349/cache8448/miss20559，29.1%)，fresh445跨版本调试累计2680497/4probe未知，仍非正式八项目批次。477本地限定提交7306520d/no push/index0。当前无付费/服务器请求在途，Sol仅两个自有源码修复。

请求返回 SUCCEEDED 是模型传输结果，不能当作命令审批 APPROVE；这轮第二状态查询有合法 DENY，命令没有发送。专用健康合同覆盖到了不相关的状态查询，是源码已确认的呈现缺陷；不能据此推断修正后所有实际审批一定通过。先定点验证适用范围及默认提示，再继续原恢复。

## 480–481 健康合同按当前命令隔离

480/481 完成Agent专用合同范围修正：facts/verify入口清空实际verifiedReviewIntent；仅完整canonical请求、原task/target/pending核验成功后填充，hook仅为该pending HEALTH提供固定SYSTEM文本。STATUS/runtime/无当前选择/未覆盖请求均空，旧pending不继承合同或旧grant，实际send前清空。33定点PASS（新增3/直接旧30）/0SKIP/Maven0/Java0/noAPI/noSSH；两源码SHA/原203fixture59helper零变，477其他七源码未改。IDEA formatter未应用（默认用户锁不可用，隔离bat profile不支持Java），源码字节未变，仅人工风格和diff检查，临时目录已清理；不虚报format通过。所有writer STOP；限定三自有文件提交后仅原恢复482。原任务ENDED和产品STOP-DISABLE成功、当前容量>=13GiB后才fresh task-board483；未开始其他付费项目。

共享 hook 签名、固定默认审批和未知 persistent 合同的 SYSTEM 保护不改。先前477在所有当前上下文统一附合同的策略由本节取代；STATUS/runtime 不再携带健康专属合同。控制验证不能证明实际模型一定批准，所有旧有效 DENY 与原 UNKNOWN 保留。

## 482 原查询执行前后阶段依赖

482 原产品恢复结束：first original-status APP合法DENY，要求先提供待执行query结果回执NO_RESULT_FOR_THIS_PENDING_REQUEST且旧business仍UNKNOWN/EXECUTING。健康/cleanup/STOP-DISABLE均未进入，命令未发送，原UNKNOWN/拒绝保留。2061freeze零变/Maven1/Java0；新增2APP15576(input15337/output239/cache7168/miss8169，46.7%)，fresh445跨版本调试累计2696073/4probe未知，正式目标未完成。480修复已限定提交098dcbe5/no push/index0。485 Astra仅只读审核真实status/runtime效果与执行前审批、执行后取证阶段合同，需排除真正缺授权/源证据再提出Agent-only方案；当前不重复同证据付费，不新DB绕SERVERlease，483新task-board与484capacity仅prepare/parse未跑。

待处理 query 没有执行结果是当前事实；旧部署业务 UNKNOWN 也不能被改写。审批拒绝引用这两个事实，表现为要求执行结果后才允许结果查询的依赖环。正在核查真实固定查询效果和阶段合同；尚不能仅凭拒绝理由证明没有其他授权或源码缺口，也不据此强制审批通过。

## 486–488 观察合同与执行阶段

486–488 Agent观察合同与阶段事实修正已验证：STATUS/native runtime按当前严格核验的请求提供独立固定SYSTEM合同；HEALTH合同仍限HEALTH。原UNKNOWN/EXECUTING、DENY及未结算外层delivery保留，同一原门禁导出ledger分类，不放宽执行权限。错误CURRENT/BUILD_ACTIVE保留真实RESULT但不晋升健康观察。67定点PASS（新增25/直接旧42）/0SKIP/Maven0/Java0/noAPI/noSSH；五源码冻结零变，原203fixture/59helper及477其他七源码零变。包含十个本地Bash分支及一个锁验证源码场景，OS/renderer/verify/systemd为明确fixture，Linux共享锁与完整硬编码binding parser未实际执行；不宣称远端guard或模型批准。formatter未应用，人工风格/diff检查，不虚报format PASS。writer全部STOP；限定本地提交后才原产品恢复489，ENDED/STOP-DISABLE及当前容量>=13GiB后才483单task-board，旧UNKNOWN不通过新DB绕开。

STATUS不被描述为验证FragmentPath/drop-ins/unit字节；native inspect/observe有各自实际守卫。工具链verify读取既有锁并取得共享锁，不能描述为完全不取锁，也不等于prepare许可。受控验证只支持接口、阶段、绑定与分支行为；原任务仍UNKNOWN，实际部署及正式同版八项目批次未完成。

## 489 原观察推进与健康载荷核对

489 原产品恢复结束：两个原STATUS已推进至backend HEALTH，独立APP合法DENY指向第三参数解码状态码并要求expectedStatus=200；HEALTH未发，cleanup/STOP-DISABLE未进入，原UNKNOWN及拒绝保留。2064source freeze零变/Maven1/Java0；新增4APP50701(input50119/output582/cache12288/miss37831，24.5%)，fresh445跨版本调试累计2746774/4probe未知。486–488六路径已提交40742d89/no push/index0，67定点与源码/原测试资源核对通过。490 Astra只读核对实际producer/helper parser/动态证据是否遗漏机械解码字段；不依据拒绝改原200策略、不重复相同证据付费。484capacity/483fresh task-board仍未运行，不新DB绕原SERVER lease。

模型API的SUCCEEDED只是响应传输成功；HEALTH本次决定为DENY，不能当作部署成功。当前源码producer为HTTP序列追加endpoint与expectedStatus，再统一逐字段NUL分隔及末NUL编码；helper读取HTTP的timeout、endpoint、status。具体当前证据与字段视图仍在核对，旧业务目标及实际健康状态均未完成。

## 490–492 通用健康载荷字节视图

490–492 Agent-only健康payload字节视图完成：无producer/helper丢字段确证，HTTP支持200–399；URI脱敏不能用于断言APP具体漏字段。仅strict selectHealth后对实际canonical第三参数机械解码，完整有序字段/空字段、strict UTF8/末NUL/65536-byte界限、decoded bytes SHA和encoded文本SHA、字段重拼及规范Base64往返；不复制kind parser、不改原policy/实际命令/固定合同/APP-host-send/DENY。41定点PASS（新增21/直接旧20）/0SKIP/Maven0/Java0/noAPI/noSSH，两个源码冻结零变，原203fixture59helper及其他十源码未改。HTTP200与204、TCP/PROCESS、COMMAND空白引号Unicode/空expectedOutput、UDP_REPLY/CUSTOM空字段、八framing负例及六APP/SEND改变payload反例均覆盖。formatter未应用，仅人工风格/diff检查。全部writer STOP；限定三文件本地提交后才493原恢复，旧UNKNOWN保留，484容量/483fresh尚未执行。

视图只证明所审请求的输入字节及表示一致，不能证明HTTP响应、远端guard、监听归属或授权。合法UTF8/NUL的缺状态或额外字段可以被纯framing如实显示，但完整请求必须与原typed policy产生的canonical请求相同才能进入APP。未修改日志脱敏或旧DENY；模型最终是否批准、原任务结清及task-board完整部署仍待真实验证。

## 493 原本地绑定检查阶段核对

493 原产品恢复结束：firstSTATUS推进，secondSTATUS独立APP合法DENY，认为本地绑定检查仅后续谓词且尚不能判定已获发送授权；HEALTH未进入，原UNKNOWN/拒绝保留，未cleanup/STOP-DISABLE。2064source freeze零变/Maven1/Java0；新增3APP33754(input33287/output467/cache14080/miss19207，42.3%)，fresh445跨版本累计2780528/4probe未知。491/492两源码及状态已提交ce573ce1，41定点实际PASS；不重复相同证据付费。494 Astra只读评估已完成本地gate事实与未来remote/send-time条件分层，以及通用APP LOW对照是否比继续补句合理。原恢复harness不调用configureLiveModel，仅加approvalReasoningEffort property无效，不虚报模型配置已变；若选择对照须通过实际产品配置入口保存/验证与新route冻结。484容量/483fresh仍未执行，原SERVER lease不通过新DB绕开。

本次未触达健康视图审批，不能将结果解释为新字节视图成功或失败。先核对实际已完成本地检查与将来再次检查的表达，再选择必要的Agent修正或明确配置实验；原有效DENY不会改判，核心部署及正式八项目指标仍未完成。

## 494–496 审批推理配置对照准备

494只读审计确认facts/verify/reviewInstructions在APP前已执行本地requireBinding及canonical匹配，之后APP/host/send是重检；未发现门禁漏执行，localBindingChecks名称列表缺明确完成状态，但不能确定NONE是拒绝原因。495仅原恢复test harness四行默认关闭、显式Agent原state限定的configureAgentRecoveryModel入口，复用产品模型配置保存/验证以部署NONE、审批LOW对照，核心facts/合同/helper/permission不改。496限定test-compile PASS/Maven0/Java0/no业务test/noAPI/noSSH；该源冻结0，其他十二Agent证据源码/原203fixture59helper零变，四行自有增量patch隔离反向检查PASS并清理mirror。source混合未跟踪基线只提交formal增量patch与状态，不纳入他人全fixture；原恢复495wrapper仅prepare/parse，真实配置/provider接受及恢复未执行，不虚报LOW已生效或provider确实思考。

[原恢复harness增量](patches/AGENT-RECOVERY-REASONING-PROBE-495.patch)是零上下文补丁；复现需要当前混合fixture基线，应用需git apply --unidiff-zero。默认行为与STATIC/ASSISTED恢复保持；显式开启时非Agent state在配置前拒绝。此次只验证编译与四行增量，尚不支持任何部署、恢复或用量结论。
