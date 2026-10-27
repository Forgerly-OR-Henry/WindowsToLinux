# 2026-10-27 ASSISTED 测试与失败修复

本轮测试与已确认问题修复完成：15次部署尝试中，八项目首次通过4/8，后续各项目均有通过记录。五项网站部署、业务及最终STOPPED/DISABLED通过，其中survey-scoring首次通过，其他四项为重试通过；三个CLI首次安装及COMMAND检查通过。CSV首次PHP准备失去SSH退出证据，原附件确认准备成功后原任务已核对FAILED，断连原因仍未知。不能将本轮记为同配置首轮8/8。

| 项目 | 首次有效尝试 | 当前证据 |
| --- | --- | --- |
| task-board | FAILED | 第五次尝试部署/业务通过，产品最终STOPPED/DISABLED；前四次结果保留 |
| file-transfer | FAILED | 第二次尝试部署/完整文件业务通过，产品最终STOPPED/DISABLED；首轮来源拒绝保留 |
| asset-lending | UNKNOWN→FAILED（产品核对） | 第二次部署/借还与重启持久化通过，产品最终STOPPED/DISABLED；首轮拒绝保留 |
| csv-inspector | UNKNOWN→FAILED（产品核对） | 第二次部署、分析业务及重启持久化通过，最终STOPPED/DISABLED；首次SSH原因未知 |
| survey-scoring | PASS | 三组件部署、84.21评分及重启持久化通过，最终STOPPED/DISABLED |
| log-analyzer | PASS | 程序自行补齐CMake/Ninja，安装及固定样例COMMAND检查通过，INSTALLED/NOT_APPLICABLE |
| directory-diff | PASS | 固定目录样例COMMAND检查通过，INSTALLED/NOT_APPLICABLE |
| binary-inspector | PASS | 固定二进制样例COMMAND检查通过，INSTALLED/NOT_APPLICABLE |

## 入口与执行边界

按用户最新要求，只通过测试代码调用真实产品 ASSISTED 链路，不启动桌面界面，也不设置实际浏览器检查点。网站入口为 `DeploymentAssistedRegressionLiveAcceptanceTest#deploysAssistedWebsitesForManualAcceptance`，CLI 入口为 `deploysAssistedCliForManualAcceptance`，每次显式限定一个项目。不执行整类、全模块或全项目测试。

依赖准备、部署、发布、生命周期和收尾全部由被测程序执行；主管只负责测试调用、观察、诊断及修改代码，没有通过手工 SSH 完成部署或预安装依赖。没有将静态部署、Agent、历史成功或修复后重试并入本轮首次成功。

本轮使用 `45.192.105.213:22` 的当前服务器身份，产品观察并绑定指纹 `SHA256:E9Abpbby0C0I4R3OUBIEv52qTv7mKjvRPh70bEEGPFE`。旧指纹不再用于新任务。主机初始软件/服务状态尚未单独完整盘点，不能宣称同全新系统环境验收。

## 基线及预检

用户要求测试前先提交全部既有未提交内容。基线提交为 `1d654025`，包含132个源码、测试、夹具及正式文档文件，提交后Git工作区干净，未推送。个人AGENTS.md、凭据、运行库和辅助证据未提交。验证为JDK21 reactor `test-compile -DskipTests`、暂存差异检查、当前API密钥精确排除、两份DOCX压缩包完整性检查，不代表全套功能验证。

预检失败独立保留：

- connection-01：PowerShell拆分Maven点号参数，未进入测试。
- connection-02：旧测试凭据库不兼容当前Schema，未连接服务器。
- connection-03：当前指纹与旧expected不符，批准回调断言导致90秒超时；正常关闭报告未完成任务。验证JVM自行退出，未强杀，没有部署动作。
- connection-04：当前Schema的新隔离库，通过程序连接测试成功，进程退出0；凭据由禁回显输入传给子进程环境，证据不保存明文。

夹具修复提交 `d90aea85`：完整指纹行精确匹配，失配通过正常拒绝路径结束；连接超时先请求正常取消；可选live测试主密码由子进程环境提供。`DeploymentLiveHostTrustTest` 的失配、伪装前缀及精确匹配3项本地测试通过，没有模型或SSH调用。首次沙箱H2只读错误与正常用户复验分别保留。通用AI定点另有 `LimitedHttpBodySubscriberTest`、`ModelHttpCallTest`、`ModelProtocolCodecTest` 共19项通过。

## 两次首次部署尝试

两项均基于 `d90aea85`，部署与独立审批均配置 `deepseek-flash`、LOW。Mod普通/扩展/审批输出预算分别32768/65536/65536，模型配置上限65536，监督已知Token阈值1500000，部署等待90分钟；未为取得通过临时提高预算或期限。

task-board 原任务为 `ec414c7b-de2f-4004-a060-12c3662e1fb6`，state为 `assisted-live-task-board-11hwlkwie4`。file-transfer 原任务为 `e11bf87f-1d04-4f4b-8d50-6bb4f1bfef75`，state为 `assisted-live-file-transfer-11jwl2emy4`。两项SSH认证及四项模型能力探测均成功；最后ADVISE之后，来源准备捕获SecurityException并返回上述拒绝。当前证据尚未确认触发该异常的具体条件，不能将其归因为输出截断、服务器容量或构建故障。

两项已捕获部署模型Token分别42967、37574，合计80541；独立审批尚未调用。每项四个能力探测的Token和物理请求数未知，80541不是完整账单。模型曾一次返回多个submit_result，被单结果合同整体拒绝，未执行这些动作；不能将每条格式拒绝当成一次远端失败。

## 建议校验的本地修复

原库只读查询确认失败阶段为“审阅组件图”，来源识别已返回；数据库和引导文件SHA前后不变。持久日志没有保存具体建议载荷或安全异常细节，因此两次真实失败的精确字段/候选仍未知。

独立本地反例确认：冻结来源未变化时，模型为具体版本字段建议 `>=24 <25`，旧实现立即抛出 `Unsupported assisted source suggestion`。其他未知字段、不合规候选、重复字段、空或过量证据同样走该中止分支，无法像未观察引用一样在原有回合内纠正。这是已经证实的本地缺陷，不足以确认两次真实请求各自触发的具体分支。

修复将上述建议整批原子拒绝，返回固定安全原因，并在原12回合内允许纠正；没有接受部分建议、放宽候选约束、增加回合或吞掉冻结源码完整性失败。模型可见说明补齐当前候选限制。`DeploymentAssistedAnalysisTest,DeploymentAssistedAnalysisProtocolRegressionTest` 共21项离线定点通过；修复前反例失败单独保留，没有API或SSH调用。真实复验尚未完成。

## 历史测试库清理

两次首轮及凭据父进程退出后，按固定名单逐目录校验绝对路径、UTC修改时间、全部后代无重解析及文件独占可读，清除435个此前普通测试状态目录，共12630文件、2598103015字节。主库配对文件、主库配置、本轮状态和受保护备份未动；没有远端命令。旧报告和外部日志保留，但被删除库中的原始账本与恢复能力不再保留。

首次清理因JSON日期自动转换后的比较方式不当，435项均被守卫跳过，没有删除；改为UTC ticks精确比较后执行第二轮，435项全部删除，未发生部分删除失败。两份回执分别保留。

补充真正只读审计确认，主库仍有8个应用、14个单元、20条执行记录等旧业务，之前的目录清理没有清主库内部历史。用户已明确旧库用于上一轮测试并授权重建，本轮选择保留基础配置的更小清理。服务器地址核对与本轮目标一致；主库平台任务、资源及Mod操作均为0后，使用产品数据库独占锁及单个事务，先拆应用/组件health外键环，再按真实外键顺序清19张deployment业务表，精确删除22条对应服务器引用及209条旧runtime_log。一次apply退出0，事务内确认上述业务表、引用和日志均归零，保留设置的内存逻辑快照一致，db-access.bin未变。没有禁用外键、TRUNCATE、远端卸载或复制秘密。

独立真正只读复核再次确认上述业务、引用和日志均为0，server1/model1/credential3/security1/桌面偏好14/Mod偏好4及Mod身份和版本保留，服务器地址匹配、app_state为CLEAN。只读复查前后4个根文件长度、mtime和SHA不变，清理及审计自有JVM残留0。回执为 `main-db-cleanup-apply-01*`、`main-db-cleanup-post-readonly-01*`。

原始日志、JUnit、启动属性、阶段进度和清理回执位于 `.ai-workspace/evidence/assisted-firstpass-20261027/`。后续真实复验、最终收尾和未验证范围将在此记录续写。

## task-board 第二次尝试：来源审核通过，Python依赖安装失败

`task-board-retry-02` 基于 `d7900d07`，新任务 `ab8e707a-63dc-40d1-a552-36d248a03df7`、state `assisted-live-task-board-11rje8t7tk`。程序完成来源识别、SQLite声明、组件审核/归档、helper安装及Java21/Node24准备，尚未发布应用。后续Python3.12依赖安装失败，最终Maven exit1、JUnit1项失败，耗时708.88秒；所属JVM自然退出。原任务没有被改写为成功，未运行其余六项目。

本次不再缺少apt错误证据：程序自动诊断记录 `stage=system-dependencies`、apt exit100、`timedOut=false/cancelled=false`，dpkg前端锁由PID3960的unattended-upgr持有。工具链请求668字节、SHA为 `5f65b85ba9554a11a9c7fc395349f3b3f658f15d8226be1079b1f86315c76097`，与本地重建的 `prepare/PYTHON/3.12/-/1713/-` 完全匹配，不能将其误称为NPM或Node下载失败。

helper既有绝对期限为1713秒，Java外层1728秒，但APT参数固定只等锁120秒，导致预算仍有余量时提前失败。修复只将APT等待值改为调用时的原 `remaining()`，保持单次安装、原有总期限、取消及所属进程组收尾。没有人工安装依赖、删除锁、停止系统更新进程或增加总预算。原生等待参数语义见 [APT官方实现](https://github.com/Debian/apt/blob/main/apt-pkg/deb/debsystem.cc)。

工具链Python本地精确6方法通过，覆盖Python/PHP/Ruby的长短剩余预算、耗尽不启动、参数构造后继续扣同一预算、超时诊断与真实所属子进程的超时/取消退出。旧固定120的六个反例失败及此前沙箱TEMP权限失败分别保留。

另确认并修复live夹具传值问题：原reviewer直接覆盖端口却保留默认accessUrl；现在复用正式共享运行草稿转换，且导出按真实已验证图的healthComponentId核对主网站host/端口，保留显式代理URL。没有改生产usage规则。3项本地接线检查通过；本次尚未发生实际错误导出，因为应用未发布。

直接相关旧RuntimeDraft组首次2项失败，反映旧断言仍期待配置整段替换、禁止公开秘密引用。当前合同是合并配置并消费应用声明，秘密字段保存标识/修订的公开引用，不授予资源授权。已仅同步测试断言并保留rememberAuthorization/databaseDetails拒绝检查，3项复验通过；生产合同未为通过测试而放宽。首次依赖解析权限失败、测试异常声明编译错误及原2项失败记录均保留。下一次真实复验仍未完成。

## task-board 第三次尝试：本轮夹具接线遗漏

`task-board-retry-03` 基于 `01ea517a`，任务 `68f3ebba-ab65-4390-bcbf-94939dff4f16`、state `assisted-live-task-board-12987n2qmk`，107.551秒后返回 `DEPLOYMENT_STATIC_REVIEW_INVALID`；Maven1/JUnit1项失败，已知部署模型91668 Token，审批0，四项探测用量未知。没有进入本轮远端安装或发布。

这是本轮夹具修正引入的接线遗漏：完整后端审核包含bindAddress，不能将全部受审输入传给只允许预启动字段的RuntimeDraft.apply。此前仅frontend的3项检查未覆盖该情况。随后新增八项目实际runtimeInputs本地矩阵，旧转换出现四个backend字段范围拒绝和CSV配置原文被规范化，复现报告单独保留。

修复只在测试侧区分普通端口编辑与完整审核：先用port-only共享逻辑更新派生默认值，再完整保留原审核输入，最后在完整上下文中同步显式accessUrl。CLI只有timeout时不调用转换，也不丢弃其声明或默认值。没有扩大生产预启动字段权限、过滤丢失输入或用异常fallback补过。

该输入边界交由Astra High做只读专项判断，Sol负责实现；没有让Astra做常态监督或必经最终审查。专项判断另指出当前共享逻辑的一个独立问题：同次修改port与显式代理URL，且代理端口等于旧端口时，显式URL会被联动改写。本地旧版两个反例均失败，修复仅使端口联动保留明确提供的accessUrl，并继续同步声明端点URL。

最终本地联测17项通过（八项目接线等12项、RuntimeDraft5项），无API/SSH调用。八项目矩阵、显式代理和声明端点一致性、任意合法主组件ID、backend冒名拒绝、完整配置/绑定字段及CLI默认值均有覆盖。期间一次lambda捕获编译错误已修正并保留原日志。不能把本地矩阵通过记为八项目部署通过。

## task-board 第四次尝试：准备异常与原任务核对

`task-board-retry-04` 基于 `824bb90a`，原任务 `9d89ba69-a5f8-44e5-8715-dbbb981a1ee7`、state `assisted-live-task-board-12hi6krhd4`。本次完整后端输入及frontend端口46100/accessUrl审核正确；119.821秒后在“归档已审阅冻结组件”阶段进入UNKNOWN，Maven1/JUnit1项失败。已知部署模型83369 Token、审批0，四项探测用量未知，没有本次容量审批、安装或发布记录。

原状态的产品无派发检查1项通过；持久异常日志确认 `java.lang.IllegalStateException`，仅保留publication-policy检查点，没有AGENT命令检查点。旧诊断没有消息或栈，阶段文本也覆盖归档之后的工作区选择，不能据此唯一断言归档失败或容量失败。只读调查的数据库及引导文件SHA未变。

随后使用 `DeploymentStaticLiveAcceptanceTest#reconcilesOriginalTaskThroughProduct` 核对原任务，1项通过，进程退出0；最终原任务ENDED/FAILED、blockers为空，正常释放恢复会话，没有重放准备或将结果改成成功。

为保留后续可定位证据，准备异常诊断增加最多6个经标识符白名单校验的内部类/方法/行号，不保存异常消息、源文件名或外部帧，隐私及数量边界1项本地测试通过。真实文件能力的task-board双组件连续归档1项本地测试也通过，覆盖归档SHA、原snapshot与文件资源释放；使用隔离副本及host.invalid，不能当成原live逐字节回放或归档无缺陷的证明。

排查期间执行既有动态容量3参数定点，三项均等待超时。新增诊断确定它们由测试桩的AssertionFailedError引起，发生在“审阅组件图”，不同于本次live的IllegalStateException。旧桩要求可纠正字段全部为空，当前协议仍可提供未填的可选healthEndpoint；应核对已经人工确认的字段不能再交由模型覆盖。原失败日志保留，修正及复验另行记录，不将本地失败冒认为live根因。

修正旧桩后，同一3参数测试均越过旧断言，新的安全帧一致指向 `DeploymentManagedHelperBundle.renderScript:255`。之前APT片段修改遗漏了helper固定SHA及独立审批契约SHA，实际脚本摘要为 `b29887d896bb61cf6b61e43b59441f78c63008b8ddd32fff1c3b6285eed9d3ab`，旧固定摘要仍为 `f00011d5db422c2331ad77eb4c2f31b55c99d78f85780b6e5b60d5b617c90c01`。现有逐字节组装单项也单独复现同一拒绝。该遗漏由本轮修改引入；本地已确认，不据此补造旧live缺失的异常栈。

修复同步两处独立固定值，保留逐字节完整性检查、独立契约核验、协议版本及原预算。对应两项身份检查和安装器摘要范围/绑定单项已通过；容量场景随后又暴露旧桩遗漏approvalPolicyRevision、toolchainPreparationContract.diagnostic及诊断安装协议。已对照实际生产合同同步：精确匹配策略修订号，使用生产codec重算诊断请求并核对原任务、服务器、指纹、计划、UUID、SHA和target；本地peer只解码准确的prepare-diagnosed首发请求并返回受控TOOLCHAIN成功回执，不伪造诊断附件。

最终 `DeploymentAssistedWorkflowTest#independentAssistedPathUsesTheConfirmedDynamicQuota` 三参数全部通过（48.92秒，Maven exit0），原单场景30秒期限及容量/业务断言不变。归档1项、安全诊断1项、身份2项、安装器绑定1项和容量3项合计8个相关本地检查通过；不重复计算中间轮次。仅覆盖上述范围，没有执行全模块/全项目测试，真实第五次尝试随后进行。原失败均保留。

## task-board 第五次尝试：部署、业务与收尾通过

`task-board-retry-05` 基于 `1c69ac29`，启动时Git干净，原任务 `c7486dca-3e5b-4178-9608-9e0f6d3079b9`、state `assisted-live-task-board-13dgcawqc0`、应用 `wtl-assisted-task-board-13dgcciwxk`。JUnit1项通过，1585.117秒，Maven退出0。程序完成来源/审核/容量、更新helper、Java21/Node24/Python3.12准备、构建、双组件发布和健康门槛。Python本次成功回执为3.12.16；不能据此证明再次实际遇到包锁或需要等待特定时长。

实际业务为HTTP首页、新建任务，以及由产品执行STOP、START、RESTART后读取任务确认持久化；没有真实界面或浏览器验收。AI_LIVE_PASS记录部署模型调用27、审批调用59；监控已知Token为78697+861391=940088，86个generation attempt ID，能力探测用量仍不含在内，不是完整账单。

原夹具finally已STOP，delivery随后再次启动网站并导出；因此又由产品按精确application/revision/ownership执行STOP和检查。最终 `PRODUCT_CLEANUP_VERIFIED` 为STOPPED/DISABLED，清理进程退出0，未手动SSH部署或启停。交付摘要与最终停止证据分别保留，不把中途停止或RUNNING导出当作最终状态。本轮首轮FAILED保持，原第四次UNKNOWN核对仍为FAILED；其余项目验收继续。

## file-transfer 第二次尝试：完整文件业务通过

`file-transfer-retry-02` 基于 `dca48414`（实现同 `1c69ac29`，前一提交只更新报告），启动时Git干净。原任务 `158141e6-02df-435f-8874-ba86694ad9b1`、state `assisted-live-file-transfer-1401ui3fnk`、应用 `wtl-assisted-file-transfer-1401ujpa7s`。程序完成Go1.24.13等工具链准备、双组件构建/发布与整应用健康检查；JUnit1项通过，2511.937秒，Maven退出0，没有新增代码修正或放宽测试期限/负载。

完整v2业务通过：两个64MiB版本、1MiB分块、首16块重启后续传、重复/冲突分块及提前完成拒绝、10路并发complete只生成同一版本、取消状态重启持久化、错误SHA拒绝。通过正式gateway依赖选择独立Go后端，由产品只STOP后端，确认Node继续服务且真实下游请求返回502；原STOP已ENDED后才START恢复，未完成上传分块与版本状态保持。故障前后共5次完整64MiB下载，字节数/SHA全部正确，耗时61186/56324/59262/73818/82891毫秒。共用QA日志仍含AGENT前缀，本次实际部署模式为ASSISTED，没有切换Agent。

监控已知Token为104514+1016318=1120832，部署/审批调用32/108，140个generation attempt ID；每次四项能力探测用量仍未包含，不是完整账单。组件独立管理审批和状态核对耗时已计入本次实际验收；原AI管理任务期限为max(调用值,15)分钟，未在本轮提高。

finally停止、delivery再次启动导出后，又经产品精确绑定原应用/revision/ownership执行STOP。最终STOPPED/DISABLED，清理进程退出0。首轮FAILED仍保留；其余六项目继续，未执行真实界面、浏览器或全套测试。

## asset-lending 首次尝试：审批契约遗漏输入桥接说明

`asset-lending-first-01` 基于 `410bb500`，启动时Git干净，原任务 `8870f1d3-7478-4f64-895b-11c244560419`、state `assisted-live-asset-lending-14ybwmxl1g`。来源、审核、容量与规划通过，182.518秒后UNKNOWN，JUnit1项失败、Maven1，应用未发布。已知部署/审批Token为72572/80390、共152962，28个监控generation attempt ID，四项能力探测不含。

无派发产品检查1项通过；原结果为 `DEPLOYMENT_GRAPH_REMOTE_UNCONFIRMED`，保留graph/policy/两个unit和三个source-snapshot。进度文本 `AI_TOOL_RESULT inspect-host` 来自上一次工具提交，后面已有工具链审批，不能单凭此文本归因。退场收集拒绝信息又因原Runtime仍持有资源而同JVM reopen失败，单独保留，不当作首次原因。

真正只读、共享锁及SHA前后核对确认，原 `DEPLOYMENT_COMMAND_REVIEW_DENIED` 的公开理由认为673字节请求只在stdin，缺少到fd3的复制。随后是ACCESS_DENIED操作异常和上述UNKNOWN，没有准备阶段异常。原请求为DOTNET branch10、exact10.0.300、1780秒，operation `23cf54e5-8058-4444-a71c-292f0d148d0b`。拒绝发生在审核返回之前，未到dispatch边界和session.execute，该prepare没有实际派发；unit的CANDIDATE_MAY_EXIST是派发前保守初值。

实际固定helper由 `DeploymentManagedHelperBundle` 组装 `/usr/bin/python3 -I - "$@" 3<&0 <<'WTL_OFFICIAL_TOOLCHAINS'`：先将外部stdin保存到fd3，再让here-doc向Python fd0提供固定源码，内部读取dup(3)。原审批契约只提内部fd3，遗漏边界映射。修复只补齐与该固定源码一致的说明；不改执行脚本、外部命令、helper pin、审批阈值或DENY规则。

本地新增实测从完整renderScript提取实际wrapper，仅把解释器换成受控shell reader：正例673字节及SHA一致，删除3<&0和移到here-doc之后两个负控均不一致；契约还须包含真实header。旧契约反例失败已保留，直接ContractTest两项通过；最终措辞限定JSON request verbs后仅复验受影响新方法1项通过，均0skip。未运行生产Python或安装器，Maven/Bash正常退出。修复已本地提交 `926c8875`，未推送。

随后 `reconcilesOriginalTaskThroughProduct` 对原任务核对1项通过（31.68秒，进程退出0），最终原任务ENDED/FAILED、blockers为空，恢复会话正常释放。没有重放.NET准备或删除原状态，也不将核对成功记为部署成功。下一次部署仍为新的重试。

## asset-lending 第二次尝试：真实准备与借还业务通过

`asset-lending-retry-02` 基于 `f9629edc`（实现含 `926c8875` 契约修复），启动时Git干净。原任务 `5044bc2d-1d75-4fef-9cdc-ed8c3011ae45`、state `assisted-live-asset-lending-15ad65cf0s`、应用 `wtl-assisted-asset-lending-15ad676ml4`。同类.NET准备经正常独立审批后，由程序取得SDK10.0.300成功回执，未增加外部fd重定向；随后完成双组件构建、发布及整应用健康门槛。

HTTP业务验证分类和资产创建、借用submit/approve/checkout/return，确认closed，再由产品RESTART后确认closed持久化。JUnit1项通过、Maven0，1327.813秒，部署/审批调用23/56，79个generation attempt ID；已知Token81524+686959=768483，四项探测不含。没有改执行脚本、helper pin、审批规则、测试负载或期限。

交付导出后额外产品STOP和inspect核验最终STOPPED/DISABLED，清理进程退出0。首轮UNKNOWN及正常核对FAILED分别保留；本轮已完成3个项目的修复后部署/业务/收尾，其余五项继续，不能称为首轮全通过。

## CSV 首次尝试：已派发准备的退出证据丢失

`csv-inspector-first-01` 基于 `2b11407b`，启动时Git干净；原任务 `2a356645-9bc7-4869-a469-f2e52fb684bd`、state `assisted-live-csv-inspector-15tqk3zo70`、应用候选 `wtl-assisted-csv-inspector-15tqk5tiy8`。来源识别analyzer PIP_LOCKED与web COMPOSER_LOCKED，Python3.12.16准备成功。后续PHP8.3准备在已确认channel打开、输入COMPLETED/frame WRITTEN后失去退出证据；481.444秒后JUnit1项失败、Maven1，根UNKNOWN，没有发布应用。已知Token37854+159921=197775，22个监控generation attempt ID，四探测不含。

原PHP operation为 `0a9d42f4-8d1a-4547-9811-373f4b606c1a`，request SHA `a483b7b40e213065337ef62a10de8fcd7f607f485fd13a94b0f294a1b17ae4f5`，参数PHP/8.3/exact=-/1781秒。`SSH_COMMAND_EXIT_UNKNOWN` 表示260512毫秒时channel/session已关闭，exit/output未观察到，尚未达到1796000毫秒请求期限。不能把没有输出当作没有执行：安装子命令输出被持续排空、仅保留尾部，helper正常长编译也可静默。

真正只读核查保留原库：transportFailureType=-、disconnectCode=-1、sessionCloseObserved=true、KEX=DONE、readCallbacks206、handler不活跃、距最后完成读取48923毫秒。客户端实际为Apache MINA SSHD2.14.0，已有30秒心跳/max3，默认idle10分钟；目前不能归因APT锁、预算、网络、OOM或明确的对端主动断开，未修改心跳或超时参数。

确认两项诊断缺口并修复：live输出遗漏已有Apache异常类型及断连/read安全字段，现以有界类型白名单保留，仍排除消息、对端、路径和凭据。原附件入口只支持未建立图的早期准备，现增加ASSISTED active graph的显式原operation只读选择：原TaskContext、原请求及server/layout/fingerprint守卫保持；仅成功空白选择继续常规核对，其他选择或失败均UNKNOWN。即使附件为SUCCEEDED，也不结清图、不关闭原graph/unit/file、不询问单准备结论，不重放prepare。

该恢复入口由Astra High只读专项核对所有权与边界，Sol实现。直接本地RecoveryTest37项及SSH筛选2项全部通过，0skip，Maven退出0；覆盖17个graph场景、旧early规则、真实宿主/H2/FILE占用保留及只执行一次diagnose-only。未执行本轮新PHP准备，下一步通过产品读取原附件；原SSH断开原因仍未确认。

修复本地提交 `91788df2` 后，原任务只读诊断1项通过（26.98秒/Maven0）。精确operation/request SHA/plan绑定相符，附件未截断，读取session正常关闭；原graph/unit/file与UNKNOWN保留，未结清。附件确认preparationStatus=SUCCEEDED、failureCategory=null、最后stage=installation-command、executable=php、actualExitCode=0、timedOut/cancelled=false，stdout/stderr均空。这是原PHP准备最终成功的独立证据，不是原SSH已观察到退出，也不能确定断开原因。

随后以成功空白选择进入普通原任务恢复，1项通过（29.43秒/Maven0），最终ENDED/FAILED、blockers为空，正常释放原资源，没有重放PHP准备。初次UNKNOWN、只读附件成功、常规核对FAILED三者分别保留。下一次为新的CSV重试；不调整SSH心跳或超时参数，也不将这次证据丢失宣称为已修复的网络根因。

## CSV 第二次尝试：部署、分析与重启持久化通过

`csv-inspector-retry-02` 基于 `697818e3`（实现含 `91788df2` 原图诊断修复），启动时Git干净。原任务 `58faa55d-9f6a-4321-97ff-a2a47b914525`、state `assisted-live-csv-inspector-16jpu2pa48`、应用 `wtl-assisted-csv-inspector-16jpu4o6cs`。程序完成Python/PHP工具链准备与绑定、双组件构建、发布及整应用健康门槛；原PHP准备已有成功附件，本次重试环境不等于全新未安装PHP的主机。

HTTP业务检查worker有效、创建demo/basic分析任务并完成，断言3行、6个问题、1个有效行；经产品整应用RESTART后worker PID变化，任务仍为completed，导出包含issueCount。JUnit1项通过、0skip、Maven0，耗时1008.261秒。66个监控generation attempt ID，已知部署/审批Token59461+648566=708027，四项能力探测不含，未提高预算或期限。

导出后由产品精确绑定原应用、revision与ownership再次STOP并inspect，最终STOPPED/DISABLED，清理进程退出0，assessment为COMPLETE。首次UNKNOWN、附件SUCCEEDED、原任务核对FAILED均保留；本次成功不能证明原SSH断连根因已修复。本轮四项网站重试通过，survey-scoring及三个CLI尚未运行。

## survey-scoring 首次尝试：三组件部署与评分通过

`survey-scoring-first-01` 基于 `4237af9a`，启动时Git干净。原任务 `0fa7b74a-6f23-4a95-9735-58e980b2efe5`、state `assisted-live-survey-scoring-16yoqkyfak`、应用 `wtl-assisted-survey-scoring-16yoqml36c`。程序完成来源审核、工具链准备、scorer/backend/frontend三组件构建、按依赖发布及整应用健康门槛。准备和构建中的长时间等待均由原命令自然返回成功，没有人工安装、重复派发或提高期限。

HTTP提交问卷，断言返回有效id且评分为84.21，再经产品整应用RESTART后读取原提交，确认评分持久化。JUnit1项通过、0skip、Maven0，耗时2124.481秒。121个监控generation attempt ID，已知部署/审批Token114893+1061619=1176512，四项能力探测不含。LOW/LOW及原1500000监督阈值不变。

导出后的额外产品STOP和inspect确认最终STOPPED/DISABLED，清理进程退出0，assessment为COMPLETE。此项计为本轮首次通过；其他四网站重试历史不变。三个CLI继续，未执行真实界面或浏览器。

## log-analyzer 首次尝试：程序补齐依赖并通过CLI检查

`log-analyzer-first-01` 基于 `dec61cf3`，启动时Git干净。原任务 `63b217b3-c8a8-48ef-a221-d42faa9f348d`、state `assisted-live-log-analyzer-17smwv7q2o`、应用 `wtl-assisted-log-analyzer-17smwx0zm0`。程序准备Rust工具链；发现CMAKE_SERVICE伴随构建缺少CMake/Ninja后，在原任务内由部署模型提出supplement，经独立审批后派发，命令及后续验证成功，再继续原prepare/build/publish。

该AI补充operation为 `31b3202f-2080-4ff0-b9ce-2f2399275d85`、SHA `401a432717d5b48cf23994521dbc27f952f50045f5247c372b8c99fa4db65677`；没有主管手工安装或重新启动整项部署。这属于首次任务的正常ASSISTED能力补齐，不是此前失败后的人工重试。

受审COMMAND使用samples/events.log、JSON格式并要求输出包含 `"matched": 6`。产品健康核验及fixture断言通过，最终INSTALLED/NOT_APPLICABLE，导出同状态；没有运行额外CLI业务list或交互验收。JUnit1项通过、0skip、807.065秒、Maven0，49个监控generation attempt ID，已知部署/审批Token71664+408637=480301，四项能力探测不含。CLI无常驻网站需STOP；测试进程自然退出。

## directory-diff 首次尝试：安装与固定样例检查通过

`directory-diff-first-01` 基于 `c0ccf981`，启动时Git干净。原任务 `4979559c-3bab-4079-9694-3468afdcd0b2`、state `assisted-live-directory-diff-183oa40xx4`、应用 `wtl-assisted-directory-diff-183oa5oss4`。产品完成Python3.12与原生伴随构建所需能力准备、构建和发布，没有主管安装依赖或重试整项任务。

受审COMMAND以JSON格式对samples/baseline生成live-baseline快照，要求输出包含 `"status": "complete"`；产品健康及fixture断言通过。最终及导出状态均INSTALLED/NOT_APPLICABLE，JUnit1项通过、0skip、405.230秒、Maven0。38个监控generation attempt ID，已知部署/审批Token68414+293216=361630，四项能力探测不含。未追加CLI交互或其他业务操作；仅剩binary-inspector未运行。

## binary-inspector 首次尝试：安装与二进制样例检查通过

`binary-inspector-first-01` 基于 `89e7a6b4`，启动时Git干净。原任务 `654ba666-b46f-4fdb-9247-82f50c0d4c01`、state `assisted-live-binary-inspector-189hymk0b8`、应用 `wtl-assisted-binary-inspector-189hynz6d4`。程序完成来源审核、工具链准备/绑定、原生组件构建、发布和COMMAND健康检查；固定samples/normal.bin以JSON格式检查，要求输出包含 `"records": 5`，产品及fixture断言通过。

最终及导出状态均INSTALLED/NOT_APPLICABLE，JUnit1项通过、0skip、570.510秒、Maven0。45个监控generation attempt ID，已知部署/审批Token106530+320645=427175，四项能力探测不含。没有追加交互或其他CLI业务操作。

## 最终核对及范围

从15次尝试的原始launch、JUnit、退出码和日志，以及8份成功delivery、5份最终STOP回执重新建立 `final-matrix.json`，独立于不同版本的assessment格式，没有覆盖旧诊断。八项最新JUnit均为1项通过、0failure/error/skip；五网站的额外收尾均精确绑定原应用与revision，确认STOPPED/DISABLED，三个CLI确认INSTALLED/NOT_APPLICABLE。首次通过为survey-scoring、log-analyzer、directory-diff、binary-inspector；另外四项的原始失败及UNKNOWN/核对结果均保留。

本轮已确认并修复的范围包括来源建议的可纠正拒绝、APT原预算内锁等待及helper固定摘要同步、live完整审核输入与显式代理URL传递、独立审批契约的stdin→fd3说明、原graph准备附件的只读诊断入口及SSH安全诊断输出。相关本地定点结果见各节；没有执行全项目或全模块测试，也没有真实界面/浏览器验收。Astra High仅用于输入/代理和原图诊断所有权的两项只读判断，Sol负责实现与测试。

不能据本轮宣称最后代码在全新服务器上一轮8/8：项目跨修复版本逐项验证，工具链和补充依赖保留在同一测试服务器，后续尝试会复用这些状态；初始系统未完整盘点。CSV首次原PHP最终成功与SSH退出证据丢失分别成立，断连根因未确定，未作无证据的心跳或期限调整。

旧库清理保留本轮证据库和基础配置：435旧隔离目录及主库旧部署/运行历史已清理，原清理回执和独立只读复核均保留。最后runner正常quit退出0，工作区相关自有Java/Python/Maven/收尾进程为0，既有IDE进程37680保留，核对绝对路径且无重解析后删除本任务j27临时目录；没有强杀或额外远端动作。最终回执为 `final-process-temp-receipt.json`。代码、测试与正式报告均按阶段本地提交，未推送。
