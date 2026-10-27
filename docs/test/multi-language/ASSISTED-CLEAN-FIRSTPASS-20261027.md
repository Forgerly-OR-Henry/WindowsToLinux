# 2026-10-27 清理服务器后的 ASSISTED 复验

本轮进行中，服务器清场及独立复查已完成。task-board、asset-lending首次完整通过；file-transfer首次在后端START后态观测审批处失败，已修复审批步骤解释并完成原任务核对、正常停止，完整重试待执行。CSV部署成功，但业务RESTART最初健康检查遇SSH无退出回执，原任务UNKNOWN保留，尚未核对或重试。survey-scoring及三个CLI尚未运行，不能称为首次八项通过。上一轮结果与本轮不合并。

## 执行边界

部署、安装、发布、生命周期及收尾仍由原产品 ASSISTED 链路执行，主管仅调用测试、观察和修复。仅使用测试代码，不启动真实界面或浏览器，不执行全模块或全项目测试。预算沿用 LOW/LOW、1500000 已知 Token 监督阈值及90分钟部署等待。

当前产品应用接口只有停止和禁用自启，没有删除应用及成功共享SDK的通用卸载能力。因此本轮新增明确选入的一次性测试机维护程序，通过宿主任务、已认证SSH身份、精确命令审批及原SSH SDK执行；这是测试维护入口，不宣称为现有产品的卸载功能，也不代替后续部署链路。

清场程序先只读盘点和模拟APT移除，生成绑定主机标识摘要、固定目标元数据、精确包与服务清单的计划；实际删除前重算计划，漂移或越界拒绝。保留操作系统必要的系统Python、SSH、包管理器、服务管理器和核心运行库。没有通过手工SSH安装、部署或清理。

## 基线及清场预检

起点为本地提交 `072c60cd`，开始时Git干净。上一轮首次4/8与后续八项PASS的日志、原状态和报告保留；本轮证据位于 `.ai-workspace/evidence/assisted-clean-firstpass-20261027/`。

目标为 `45.192.105.213:22`，产品实际认证指纹为 `SHA256:E9Abpbby0C0I4R3OUBIEv52qTv7mKjvRPh70bEEGPFE`。凭据仅由当前产品凭据库解锁后使用，主密码经禁回显输入传入子进程环境，不保存明文。

`host-before-01` 只读盘点测试通过、Maven0：5个实际受管根目录，100010个条目、4575321239文件字节，15个相关服务单元、16个定义/启用链接、14个运行账户、35个当时识别到的编译/开发依赖包。系统Python与SSH可用。这是清理前证据，不是清理完成证明。

`host-plan-01` 的APT模拟拒绝 `g++-x86-64-linux-gnu`，远端命令明确FAILED/exit65，尚未执行删除。该Ubuntu架构包未被初版固定包名规则识别；随后补充精确架构格式及反例，核心包拒绝规则保留。该次夹具在任务回调内对非零退出断言，导致原本已知拒绝进入UNKNOWN等待；主管正常Ctrl+C中断该测试，确认自有进程退出，保留原状态、日志与interruption回执。Maven最终退出码未知，不编造为普通测试失败码。

夹具已将结果断言移至宿主回调外，并分别约束只读与实际清理的执行等待。清场程序首轮本地6项为3通过、3失败、0skip；失败是Windows路径及POSIX信号mock适配，原日志/JUnit保留。修正后仅重跑3个失败及直接受影响的3个方法，6/0/0/0skip通过；结合首轮未受影响的计划篡改用例，7个直接场景均有通过证据。随后host-plan-02由已修复夹具正常记录失败并退出Maven1，没有再挂起；APT模拟发现将连带移除ubuntu-server元包，守卫拒绝，仍无删除。调整为保留Git/Git文档及Ubuntu服务器元包，其余编译器、SDK、CMake/Ninja和开发包删除范围不变。该修正的两个直接受影响方法2/0/0/0skip通过，未重复其他已过组；后续实机计划及清场结果见下文。

## 本轮部署结果

首次两个项目均以干净工作树、本地提交 `6723f1c1` 启动。后续出现另一项论文任务的未提交改动，本任务保留且不暂存；后续启动记录会据实保留工作树状态。

| 项目 | 首次结果 | 实际验证边界 |
| --- | --- | --- |
| task-board | PASS | JUnit1项、0失败/错误/跳过，1987.543秒，Maven0；真实HTTP、跨语言、任务创建及产品停启/重启后持久化；额外产品STOP确认STOPPED/DISABLED |
| file-transfer | NOT_PASS | JUnit1失败、0错误/跳过，8296.934秒，Maven1；部署成功，业务组件START后状态观测审批被拒，首次UNKNOWN历史保留 |
| asset-lending | PASS | JUnit1项、0失败/错误/跳过，1273.506秒，Maven0；HTTP业务及重启持久化通过，额外产品STOP确认STOPPED/DISABLED |
| csv-inspector | NOT_PASS | JUnit1失败、0错误/跳过，1094.21秒，Maven1；部署成功，业务RESTART最初health遇SSH未确认退出，原管理journal不存在 |
| survey-scoring及三个CLI | 未运行 | 尚无本轮结果 |

task-board原部署任务为 `a6dacc81-30c5-4f38-ae40-65d15414096f`，应用 `wtl-assisted-task-board-1dw2zdba4o`，修订 `09bfcd2282557b0f505756d0ea71f7ee2c1d91ece1680a72f049c19822f2fefe`。已知监测Token为部署106006、审批895232，共1001238，93个generation attempt id；四次能力探测用量未知且未计入，不是完整账单。

file-transfer原部署任务 `6b4a4305-e1ea-43cc-aac3-86c2a2e82821` 已SUCCEEDED，应用 `wtl-assisted-file-transfer-1eo6h9o4xs`，修订 `ef93891539c93cdeebc5bab00e1ef998681ace9aa27a8f7420f5523bcd0d7ecb`。两个64MiB版本均上传完成，前三次完整下载分别耗时2341102、1890492、1587141毫秒，字节数与SHA均一致；保留原请求等待，没有重发、降低负载或调整预算。只读产品SDK观测确认服务活跃和实际慢传，TCP ACK不代替内容校验；无法据此唯一确定网络或客户端根因，没有改本机系统设置。

之后取消上传的重启持久化、坏SHA422及后端单独STOP后网关DEGRADED和真实502顺序检查已过。失败在组件backend START任务 `9ecd9652-529a-4040-8645-1904a490f262`：START命令已有exit0及`LIFECYCLE=start`，随后的运行时类型检查成功，再下一次`publication.observe`被独立审批DENY。真正只读查原库确认原操作为EFFECTING、backend attempted=true/verified=false，公开拒绝理由称只读observe未执行所声明START。确认是审批混淆任务目标与当前观测步骤，不是START未派发；不能重复START。原隔离库文件SHA在查询前后不变。

首次失败保留，未执行后续两次恢复后完整下载及最终STOP，不能记业务通过。已知监测Token为部署124928、审批972914，共1097842，129个generation attempt id，低于1500000阈值；探测用量边界同上。finally资源阻塞及同JVM二次打开数据库的锁异常是原UNKNOWN后的抑制错误，不作为首因。

本次最小修复在管理审批证据中加入当前命令operation和持久阶段，并用产品固定SYSTEM说明区分整个任务目标、单次只读检查与生命周期副作用；attempted并非成功证明，后态检查正是获取证明的步骤。保留独立DENY、命令/身份/范围校验及宿主单次授权，不自动放行任何观察命令。

`management-step-local-01`限定三个直接场景，2通过、1失败、0跳过；新反例最初把拒绝注入了START前的预观察，没有覆盖本次后态故障，原XML与失败回执保留。调整为确认受控端已实际START后拒绝后态观察，真实任务必须UNKNOWN，写副作用只有一次STOP及一次START、拒绝命令不得获得宿主许可。`management-step-local-02`仅重跑该失败组和直接受影响的正常组件停启，2项通过、0失败/错误/跳过，66.328秒；首轮通过且未受后续改动影响的身份/范围守卫结果保留。

本地提交 `bbcca259` 后，经现有产品恢复入口精确核对原业务任务 `9ecd9652-529a-4040-8645-1904a490f262`，没有误核对已成功的部署root。`component-recovery-01` JUnit1项通过、Maven0，266.739秒；原任务当前ENDED/SUCCEEDED，恢复日志仅有两次STOP、没有START，之后应用STOPPED/DISABLED。独立真正只读复核原journal为FINISHED、原backend verified=false仍未重构，查询前后文件SHA不变。该结果仅证明原目标当前可核验及正常收尾，不是首次通过，也没有补做完整V2剩余两次下载。产品原任务核对不重发原生命周期效果，但会执行正常健康/准入步骤；不将其统称为严格零副作用读取。

asset-lending原部署任务 `11dc6f99-1461-44fc-b076-53954c6ee5e7`，应用 `wtl-assisted-asset-lending-1klwdalod0`，修订 `ca9d8a0a7f5b29b5d6b20852f4d0bd5e834bb61a65fe4a5de79b0ee0db0110d8`。已知监测Token759233，86个generation attempt id，能力探测未计入。最终额外产品停止回执及进程exit0完整，assessment为COMPLETE。

## CSV 业务重启前的 SSH 未确认

原部署root `4f973b0b-a135-47ba-b19a-cd22eacb2c3e` 显式SUCCEEDED，应用 `wtl-assisted-csv-inspector-1l4x0165xg`，修订 `76e6bb39424db7f801e901f3453819b3a840a9e46f2f65555e57092eba5c79f6`。业务RESTART任务 `64d81e3a-24be-4118-a3a3-5de9276b225f` 在最初应用观察的health请求得到 `SSH_COMMAND_EXIT_UNKNOWN`。单条命令elapsed1679毫秒、timeout55000毫秒，channel已开后关闭、未见输出及退出码，sessionOpen=false，记录IOException。不能解释为55秒超时，也不能把命令计时当连接寿命。

原库真正只读审计确认该业务task没有management journal，也没有该task的runtime/deployment日志；查询前后原库长度、mtime、SHA保持。因此没有已登记RESTART副作用可供重建，不能说原重启已完成。已知监测Token705783、66个generation attempt id；原失败及UNKNOWN保留，finally资源阻塞和同JVM二开库锁异常仅为抑制错误。

Astra对这个具体传输问题做了只读源码核查：未找到execute/close互斥失效或helper误杀SSH祖先进程的证据。原诊断的disconnectInitiatorLocal=false是默认值，disconnectCode=-1表示未观察到disconnect回调，无法据此确定关闭发起方。通过产品SDK另起两个严格只读元数据任务，分别按ssh.service和sshd日志标识核故障时间窗，原SSH服务active/NRestarts0、两个组件仍active；指定窗口未检出OOM/进程故障分类事件。采样未调用health或cleanup，仍不足以唯一确定远端、网络或客户端原因。

已修复诊断歧义：显式记录disconnectObserved，未观测回调时发起方为UNKNOWN；保留首个受限异常类型、直接cause及最多六个白名单栈帧，不存异常消息、地址、载荷或Throwable。新增session单调年龄、命令开始读取计数及本命令增量，避免把整连接读取数与单条命令计时混用。`diagnostics-local-01`限定六个直接方法全部通过、Maven0，含raw Io close与显式disconnect两种wire反例、一次派发不重放、脱敏及首异常保留；没有修改任务outcome、发送、重试、超时、权限或生命周期行为。此项是诊断修复，不宣称SSH断连根因已解决。

同时纠正管理审批SYSTEM说明：health可能执行准入/清理 bookkeeping或应用代码，必须按完整可达合同审核，不能按health标签当纯只读或自动重试；attempted只记录尝试，不能证明已派发或成功。`management-step-local-03`仅复验直接相关的正常组件停启与START后观测拒绝两项，均通过、0失败/错误/跳过；未重复其它已过组。原CSV任务核对及完整新任务复验尚未执行。

## 清场执行与独立复查

修正本地提交为 `96f02172`，固定程序SHA256为 `df93637867809abaae4bb533340fce256a71f4a2b2d582244344616629d1aa1f`。`host-plan-03` 的实际APT模拟成功，计划SHA256为 `003419f302f7ef15249d53d0c4cd9652204dc4720c4891d9d19fed8df3d2a277`；仅保留Git/Git文档与系统元包后，ubuntu-server不再出现在删除集合中。该差异支持此前Git依赖链候选，但没有另取Depends正文作直接归因。

`host-reset-01` 经程序重算同一计划后执行成功，JUnit1项通过、Maven0，45.549秒：停止15单元，删除15定义文件（另一个启用链接由disable移除）、14运行账户、40编译/开发包与5个受管根，清空APT下载缓存。受管根包含helper/成功共享SDK、工作区、代码、数据及配置；上一轮远端应用与数据已按本轮要求删除，旧报告只代表历史观测。

随后 `host-after-01` 以新任务独立只读盘点并调用原产品固定平台能力探测，JUnit1项通过、Maven0，45.271秒。实际根目录、相关units、账户/subids、目标包均为空；APT缓存deb/partial文件及字节均0，核心包版本保持。公共PATH的Java/Javac/Jar、Node/npm/pnpm/yarn、Maven、pip/其他Python包管理器、Go、Rust/Cargo、.NET、Kotlin、PHP/Composer、Ruby/Bundler、CMake/Ninja及C/C++编译器均未观察到，Docker/Podman客户端0；系统Python3.12和SSH仍可用。

这证明清除了本轮已盘点的部署及测试软件环境，不声称重新安装操作系统。后续八项目在这一次清场后顺序执行，同一轮后续项目可以复用本轮前面项目由程序安装的共享依赖；不会把上一轮残留或清场步骤记为新轮首次成功。

## 追加测试数据库清理

用户追加要求适时清理测试遗留后，真正只读审计确认主库19张部署业务表、运行日志、平台任务/资源及Mod服务器引用均为0，服务器、模型、凭据与偏好配置保留；并非主库重新累积了部署历史。

本地58个隔离/辅助目录逐项分类后，守卫程序精确删除前轮8个完整成功库、4个完成观察库及4个完成清场核验库，共16目录、267文件、14933295文件字节；42个保护目录均存在。随后一次新只读观察产生的唯一临时库也在其JVM正常退出后清除，合计17目录、270文件、15203989文件字节。原始日志、JUnit及清理回执保留；当前部署库、认证源、失败/UNKNOWN原库、备份控制库和共享目录未删除。

删除前检查精确绝对路径、祖先及子树无重解析、文件独占与所属进程退出；没有打开当时活动的file-transfer库，也没有访问远端。四个主库根文件长度、mtime及SHA前后不变。首次外层PowerShell回执因正常脚本返回后的LASTEXITCODE为空而报告exit2，实际16项均已删除；没有重删，独立复核确认16项均不存在、42保护目录完整，Verified=true/exit0，原回执保留。
