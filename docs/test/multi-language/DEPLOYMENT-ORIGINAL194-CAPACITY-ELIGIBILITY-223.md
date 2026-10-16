# 223：原194候选容量回收资格只读核查

原194本地只读取证完成；**没有执行恢复、SSH、审批请求、新部署或远端删除**。当前证据已表明现有准备恢复分支不能结清该任务，不能直接付费重试原恢复入口。候选镜像是否仍在、实际归属与依赖、可以释放多少空间，均未重新观察。

## 对象与实际读取

仅Task `a67e3b89-d04e-49a7-8b5e-20ed93803d53`，Runtime `src/app/db/data/agent-live-task-board-q3k1ohhos`，原App身份 `wtl-agent-task-board-q3k1qalz8`。221原盘点中两个各4,429,185,024字节的镜像只是容量线索，不是本轮实时容量或可删除证明。没有重试197/201、旧58/74/116或其它任务。

正常用户Java21源文件模式工具只运行一次并退出0。读取前由.NET只读句柄拒绝原DB与引导文件被写入或删除，Java持原空产品锁；DPAPI只在内存解包既有引导，禁止生成新引导。使用embedded H2 `IFEXISTS=TRUE;ACCESS_MODE_DATA=r`、关闭trace并确认只读及原database generation，没有启动Runtime initializer、迁移数据库或读取配置、凭据正文。

原受保护H2文件59,060,224字节，SHA256 `11d49c71edf4071bb06a1cebd85969f562f908aa4a82d8848165adaa91ca7b4a`；DPAPI引导358字节，SHA256 `886b792519a8fe8ed0dc5ac894eef95c3ca1856617f0448ccafe3bba4b133f8e`。读取前后长度/SHA完全相同。未备份原pair；必要私有保全留在任何未来真实恢复之前。H2 URL没有CIPHER，不将整个数据库误称为加密文件。

工具仅投影固定任务的白名单身份、事件/资源类型、状态、长度和摘要，未知payload明确未解释；不输出消息、作者脚本、秘密值或异常正文。4096事件/资源上限实际完整容纳本次97条事件、3454条资源，未截断；所有SQL限定原Task或其明确引用的App/unit。

## 实际账本与恢复边界

| 观察 | 本轮结果 |
|---|---|
| 平台及Agent状态 | 均UNKNOWN；semantics 4、AGENT/AUTOMATIC |
| 原准备动作 | PREPARE_EXECUTING 44与RESULT 47配对，CANDIDATE 48为backend；PREPARE_EXECUTING 95为frontend，尚无结果配对 |
| 原封存动作 | SEAL_EXECUTING 89与RESULT 90配对，SEALED_ARTIFACT 91；没有未结SEAL |
| 计划 | PLAN与PLAN_APPLICATION各2；两次App身份相同，计划摘要不同 |
| controller/model-command | 无controller事件索引；无model-command检查点 |
| 原资源 | 3454行中5项active：SERVER/MOD/SCHEMA各1、FILE 2；唯一author checkpoint为已释放的原布局 |
| 正式业务引用 | 原Task无deployment_execution/operation receipt；精确原App无正式App/component/release行 |

此前依据进度标签提出“可能是未结SEAL”的怀疑被本轮账本否定。真正未配对项是事件95的frontend准备动作；组件名由固定UTF-8摘要核对，不从旧日志补写NOT_SENT或结清证明。简单配对统计本身不是正式资源恢复证明。

现有普通Recovery支持在全部真实回执及资源结清后，将原UNKNOWN落为FAILED/CANCELLED，持久OperationReceipt和平台有界摘要，再事务删除可删除明细。因此无需先新增“永久保UNKNOWN同时清资源”的接口。该能力不代表任意UNKNOWN均可直接归档；未结证据仍保留，原外部194/221报告不在删除范围。

但当前`DeploymentAgentPreparationCompensation.capture`要求原PREPARE、PLAN、PLAN_APPLICATION各唯一；194各有2条，必定不匹配。`DeploymentAgentPreparationAbsence.capture`仅接受首个准备前的窄形状，已存在的PREPARE_RESULT/CANDIDATE/SEAL等也使194不匹配。事件流没有其它准备结清proof，故preparationsSettled为false。

若原布局、工作区和配额重建门先通过，`DeploymentAgentRecovery.beforePublication`调用PreparationReconciliation后会在SSH/审批前返回`DEPLOYMENT_AGENT_ORIGINAL_PREPARATION_UNCONFIRMED`；若更早的工作区重建不通过，则先返回`DEPLOYMENT_AGENT_PREFORMAL_IDENTITY_MISSING`。本次安全投影没有完整解码CANDIDATE_QUOTA正文，不能声称已排除后者。两条路径都不能由直接重跑现入口取得清理证明，因此本轮没有调用该入口。

下一窄问题是支持“已有完成候选，后续另一准备未结”的原任务恢复：须保留原完整事件前缀、各次计划/来源/配额绑定，以及全部原候选的资源生命周期。不能只放宽`one()`，再沿当前partial分支只清第二候选而漏掉第一候选；也不能从本地审批失败或旧日志推断作者载荷未发送。后续实现、定点验证、私有pair保全、真实独立审批和远端absent回执均尚未完成。

## 构件、工具及收尾

HEAD `9584252e851cb88c6b3040dbe6d293897c56ce25`、索引空；当前3309项源码与222最终值映射/集合均相同。`deployment.jar`仍为 `e1d0504933ea55f8404f40a27321bc50ba4e9135d44079adb24cfddc52d2c92e`，host AI JAR仍为 `74ff9dfb37104c23e10baa409a18c2723fc1ab33848e5550af6a6e3ac5429863`。复用222定点与跳测包证据，本轮不执行JUnit、格式化或打包。

基线capture唯一exec50153退出0，222封存151件原SHA相同。原JUnit classpath按分号55项含末尾空项，最初捕获把空路径按工作区当前目录遍历，额外记录333770文件；这份原捕获保留，不当为必要reader依赖或Java CodeSource。实际reader从原非空绝对条目独立派生DB/Windows classes、H2、Jackson、JNA最小8项190文件，运行前逐SHA和目录集合核对。

工具交付前PS AST零错误、16项有界静态/纯guard通过，不冒称Java运行或生产decoder测试。父审在数据库读取前发现并修正了初版嵌套layout形状和过宽NOT_SENT矩阵，原件保留；主体没有因此重试。实际唯一读取随后成功。父独立核97事件、3454资源、原pair、3309源、构件、进程闭合和临时目录消失。

正常用户CIM核reader的launcher/Java精确PID与创建时间均退出，Close进程实际退出0；仅删除自有marker约束的TMP，无UDS、Runtime或凭据副本。0远端恢复/删除、0新FULL、0provider/SSH；没有新的缓存或审批收益数据。221失败窗口cache66.9393%/APP34.3074%仅复用原观察，两个已选项目仍累计7次FULL，均未完成业务验收。

VM地址问题也已现场只读复核：`192.168.201.1`属于Windows本机VMware VMnet1；212使用60321覆盖非localhost普通HTTP浏览器行为，前后端运行在Windows，正式远端仍45.192.105.213:22。见[212记录](DEPLOYMENT-FILE-TRANSFER-HTTP-DIGEST-212.md)。本轮未重开该服务或浏览器。

证据根`.ai-workspace/agent-live-evidence/agent-autonomy-20261012/capacity-original194-223/`，包括`baseline.json`、`audit/REPORT.md`、`local-reader/read-01/`及`parent-readonly-verification223.json`。Sol High准备工具、Sol XHigh审现有恢复边界，Astra未使用。本轮只隔离本记录提交，不推送。完整Goal仍active：容量安全回收、两项目业务、冻结后全新机八项目、非默认目录及整机重启恢复均未完成。
