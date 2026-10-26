# 2026-10-27 ASSISTED 测试与失败修复

本轮仍在进行，尚未达成首轮通过。task-board、file-transfer 首次有效尝试均在来源分析后失败，尚未进入构建、发布和业务验收。其余六项目暂缓，先调查共同阻断；不能据此记为六项失败或已完成八项测试。

| 项目 | 首次有效尝试 | 当前证据 |
| --- | --- | --- |
| task-board | FAILED | 来源准备返回 `DEPLOYMENT_STATIC_SOURCE_REJECTED`；65.878秒，应用列表空 |
| file-transfer | FAILED | 同一来源拒绝，应用未发布 |
| asset-lending、csv-inspector、survey-scoring | 未运行 | 等待共同阻断定位 |
| log-analyzer、directory-diff、binary-inspector | 未运行 | 等待共同阻断定位 |

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

最终本地联测17项通过（八项目接线等12项、RuntimeDraft5项），无API/SSH调用。八项目矩阵、显式代理和声明端点一致性、任意合法主组件ID、backend冒名拒绝、完整配置/绑定字段及CLI默认值均有覆盖。期间一次lambda捕获编译错误已修正并保留原日志。真实第四次尝试尚未完成，不能把本地矩阵通过记为八项目部署通过。
