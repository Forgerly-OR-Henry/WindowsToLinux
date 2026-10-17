# Agent publication contract diagnostic followup 274

本阶段完成独立只读 followup 的内部实现、定点验证和产物核验。最终覆盖为 **6 个方法、19 个用例通过**，随后 `-DskipTests package` 通过；不是全量测试或实机验收通过。没有打开原业务 Runtime/数据库，没有调用模型或 SSH。

配套 `DEPLOYMENT-AGENT-PUBLICATION-CONTRACT-DIAGNOSTIC-FOLLOWUP-274.patch` 只包含本阶段相对已捕获工作区前像的 12 条源码增量：8 条生产、4 条测试，8 个既有文件和 4 个新文件。保留工作区其他迁移改动，不以 Git HEAD 冒充这些前像。

## 目标与权限边界

继续原 File Transfer Task `072bc074-4bce-4c14-b425-f2ff965c7d40`，execution `45e3a9a4-a6b8-403a-89f5-605e11eeeca0`。File Transfer 完整 BUSINESS v2、真实浏览器验证和必要收尾完成后，再串行处理 CSV Inspector；两项完成后暂停，报告 FULL 窗口的 `APP IO / (DEP IO + APP IO)`、加权 `cache hit / input` 及 known/unknown。失败 FULL 与恢复窗口分列，不启动第三项目。

原194及234已结清，不再恢复；221不自动恢复。本阶段不更换该目标，不修改数据库表、公开 API 或 UI。

## 原因与实现

273保留的旧449–456诊断回执是 `UNKNOWN / CONTROL / UNCONFIRMED / null`，三项 `NOT_CHECKED`。它只表示没有接受完整控制序列，不能证明原生函数从未执行。直接改旧 Python 或 Program 会改变旧请求身份并破坏历史解码，因此旧 journal、Program、资源及旧 ProgramTest 字节冻结。

新增 `deployment.agent.publication-contract-diagnostic.followup.v1` 和独立 journal。它必须绑定完整旧449 REQUEST/END、原回执、原 pending272及 continuation385，以及晚于旧END的完整 unmet-goal wholegraph。原449 REQUEST身份最多消费一次新 followup REQUEST；DENY、NOT_SENT、中断、UNKNOWN均不重置。新 action、版本或 observation不能重开该次数。

每次仍独立 APP 审查完整 launcher/stdin，并取得新的精确单次 host grant。产品先重新观察整图：目标已成时可走原有 guarded settlement；目标未成且符合新准入时才选 followup。任何 followup结果均令本次协调立即 UNKNOWN，不授予修改、安装、ACK、cleanup或结清权限，不重发旧272/385/449，不重装READY。

新解码器只输出有限控制原因，保留可信的已通过前缀和合法非零根EXIT的当前phase；矛盾或不完整序列不能产生PASS。原始stderr、拒绝参数、控制管道内容不进入安全回执。`sourceVersion`仍是产品绑定的预期stdin SHA，不是独立远端测量。

完整组装语法检查发现既有 recovery base 在 `database/10-native-instances.sh` 打开的 Python here-document尚未闭合。仅新Program顺次补入原 `20-native-targets.sh`、`21-native-operations.sh`完整资源，再附原contract guard。两个既有片段保持原字节，并单独核对source/target/JAR及classpath首命中。片段中的Python三单引号又与外层raw-triple嵌入冲突，新Program改用可读JSON字符串承载Bash，保留全部内容；旧Program身份不变。这些本地反例不能被表述为旧272实机失败的唯一原因。

删除新 `verifyCurrent` 同一调用内的重复prefix解析：每次仍先完整fresh `Compensation.read`，再核本实例REQUEST的action、规范binding、plan/pending/prior及最新observation。历史防复用跳过首followup以前的无关前缀，保留之后全部action/grant拒绝；没有跨调用缓存。

## 实际验证

均使用显式 `-Dtest=Class#method`、`-pl src/mod/deployment -am`，真实live开关关闭。Program三个方法覆盖旧身份/完整绑定、有限控制原因、实际Python提取和完整Bash语法及固定header场景；History两个方法为9项篡改/复用拒绝和6项真实TaskWork/journal本地peer流程；Evidence一个方法覆盖完整源码分页、拒绝及独立fresh grant。

| 实际批次 | 用例 | 通过 | 失败 | 错误 | 跳过 | 打包 |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| attempt01 | 19 | 13 | 6 | 0 | 0 | 未执行 |
| attempt02 | 19 | 2 | 13 | 4 | 0 | 未执行 |
| attempt03，快测 | 4 | 4 | 0 | 0 | 0 | 未执行 |
| attempt04，历史组 | 15 | 10 | 5 | 0 | 0 | 未执行 |
| attempt05，历史组 | 15 | 15 | 0 | 0 | 0 | exit0，skipTests |

原始72条JUnit记录为44P/24F/4E，全部保留。最终有效覆盖只取attempt03的4项和attempt05的15项，不能把原始记录全报通过。

attempt04实际阶段日志显示，五个复杂场景均在统一15秒等待后继续到最后语义断言之后，完整TaskWork耗时约21–30秒。该方法包含初始化、三轮协调、防重放及最后FAILED结清；15秒不是生产单次操作超时。复用现fixture新增显式Duration重载，默认双参入口仍15秒，仅该参数化方法六场景使用60秒有界等待，保留全部断言。生产timeout不变，也不声称15秒性能通过。已通过快测不依赖该helper，未重复执行。

实际产物证据：

- 完整source inventory：3341；最终manifest SHA `ab7772ecbb748a7c1e231d82fade6aac276759f83b834241b039b40fa90f96cc`。
- owner核验：65个DEP、3个AI、4个平台family，5个完整稳定host module；本阶段8个production、4个test family。
- 实际classpath：54 entries / 3306 files；SHA `f2796765592fde0cf6f07bd6bfaf15f9d6512e85680eed56f4ccc26672c45b5b`。
- deployment JAR：4120259字节，SHA `7e3370520d90a76b8c6a193a68132c781b53b606640f442ec27524d17fcb863b`；另5个host JAR字节与SHA保持不变。
- metadata SHA `e3d9a85842084bb2bb879305f6f42a3fc226e4b07391333239f830af8fad8769`；两既有native资源核验证据 SHA `c6001ddcb6e2ac3f1196adcc199a653599cdb59206204b1a426bcc76dab9477f`。
- 255→263→266→271→274旧Program/source/target/JAR链核验通过，proof SHA `c18a5160d0751f28a075ad00a3627e49174603a13452f685291c022d6640901e`。历史旧Program测试没有重跑，相关旧类族按字节核验。
- patch 155466字节，SHA `9aeee00f1a48034e080fe83c1c48c5d65dd441b5699d71d3ee45e4d58abd173b`；全部12路径hunk从真实前像独立重建，`git apply --numstat`解析通过。

每轮Maven均有普通非管理员CIM关闭及独立核查，PID与创建时间精确匹配，临时目录按自有标记删除。各轮关闭/独立核查记录数依次为22/23、11/12、25/26、11/12、18/19，均为0存活。纯字节metadata、资源和proof工具同步exit0，不宣称所有普通读工具都有PID索引。

证据根：`.ai-workspace/agent-live-evidence/agent-autonomy-20261012/agent-publication-contract-control-failure-274/`。保留各轮实际输入、源码前像、manifest、原始XML/日志、关闭记录；metadata绑定和proof绑定的执行位均已关闭。attempt02的初备input是LF JSON语义副本，原root绑定为Windows换行，另存实际root字节及明确limitation，没有把两者说成逐字相同。

## 后续验收边界

本阶段未运行Linux端Python `pass_fds`/selectors/killpg或三个真实native合同；本地完整Bash检查仅语法解析，固定header测试仅使用合成场景。本地APP/host/SSH peer不是真实provider或SDK grant消费。source/target/JAR与raw JUnit CP证据不是JVM CodeSource。

最近273实际读账仍是原Task/Mod UNKNOWN、pending未结清、lastAction456、最新wholegraph426。274没有重读原库，275必须先fresh byte capture和公开只读历史重核，再单独放行原Task产品协调；不能以本记录替代新的eligibility、APP、grant、远端回执、BUSINESS或浏览器证据。

没有新增FULL、恢复provider窗口或可计算的审批占比/缓存命中率。本阶段只作本地提交，不推送；实际提交与最终封存摘要保存在证据根的独立记录中。
