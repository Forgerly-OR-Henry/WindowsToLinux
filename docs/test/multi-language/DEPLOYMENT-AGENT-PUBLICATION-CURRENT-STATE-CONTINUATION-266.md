# 266：原 File Transfer 当前状态补偿接续的本地实现与定点验证

本阶段实现新的固定 `deployment.agent.publication-continuation.v1` 动作，解决原238回滚结果为 UNKNOWN、旧动作不能再次发送时的受限接续。旧安装程序、loader、installer、v1诊断和v2预检保持原字节。此处完成的是本地代码与产物验证，未执行真实接续，也未完成 File Transfer BUSINESS v2 或浏览器验收。

用户已授权必要修正版与内部单组件 STOP/START；远端动作继续由产品串行执行，每个完整动作独立 APP 审批并取得新 grant。密码和 API 密钥不进入模型正文。194、234已结清，不恢复；221不自动恢复。

## 最终实现

- 新接续绑定原 plan、271 intent、272 FAILED/UNKNOWN、完整v1/v2历史、353–361预检及其回执、六项当前文件指纹。产品通过完整原事件重新推导 eligibility 和请求，不以验收投影替代权限证据。
- 新固定源码随完整命令输入送审。只复用原生纯实现片段，构造时逐字核对旧公开渲染结果；不修改、重装或再次调用旧 UNKNOWN 回滚入口。
- 在既有 admission/run 两把锁内核对当前状态、文件指纹、完整参数、managed-data links、unit render/FragmentPath/drop-ins、candidate/task/build/snapshot/maintenance/唯一 release。通过后只执行一次受限回滚，删除精确归属的维护标记，保留候选供后续独立清理。
- 新 journal 严格保存 REQUEST、REVIEW、GRANT、DISPATCH、RESULT、RECEIPT、END；历史 action/grant 交叉去重，同一原 pending 最多一次接续。REFUSED、UNKNOWN、部分结果或拒绝不触发再次发送。
- 接续回执不直接清除原 pending，也不补造272的 ACK。仍须取得新独立审批的 wholegraph；目标成立后追加既有 `OBSERVED_ONLY`，再逐动作清理两个候选，取得最终 wholegraph 和 VERIFIED，最后沿既有 FAILED 本地收尾与归档链结清。

变更共12个源码路径：9个生产路径、3个测试路径，其中7个既有路径、5个新路径。没有新数据库表、公开 API 或 UI。

## 实际验证与修正

全部命令明确限定 `class#method`，关闭真实 provider、staticLive、agentLive；只在定点测试成功后执行 `-DskipTests package`，未运行全套测试。

| 轮次 | 实际结果 | 修正与保留证据 |
| --- | --- | --- |
| attempt01 | testCompile失败，0 JUnit；未打包 | 新测试重复局部变量名，改名；原日志保留 |
| attempt02 | 38例：29P / 6F / 3E / 0skip；未打包 | 新codec的 LongNode/IntNode直接相等比较拒绝合法整数；仅改为序列化后统一解析比较。修正新增测试审批字段和复用action反例；原XML保留 |
| attempt03 | 17例：12P / 5F / 0E / 0skip；未打包 | 新完整flow夹具已有REMOTE_LAYOUT事件但缺原任务checkpoint；只在该新方法通过真实TaskService保存/释放checkpoint，并用公开Layouts.original核配对 |
| attempt04 | 5例全部通过；test/package/sourceFreeze均0 | 仅重跑失败flow的5个场景；打包跳过测试 |

最终有效范围为13个方法、38个用例全部通过；三轮原始JUnit合计60例，为46P / 11F / 3E / 0skip，首轮编译失败另列。已通过且不受后续修改影响的方法未重复运行。元数据按方法记录直接生产依赖，保持各轮原源码摘要、完整参数用例成员、首次失败和最新有效结果。

覆盖新请求和回执身份、审批完整源码、fresh host grant、拒绝/部分/超时/成功后的不重发、新观察屏障、两候选清理、FAILED本地收尾及中断窗口。Bash测试使用完整新guard和旧native rollback，含13类漂移拒绝及一次正向回滚；Windows夹具明确替代root/owner/stat/flock/systemd、参数与managed-data边界，不能视为真实Linux权限、锁、systemd或生产参数验收。

## 源码、产物与身份

- 最终源码3332项，manifest SHA256 `20ece15e4c9adc540c008367b2fecad98bd53abafd134e7a51e96a43d0a34542`。
- deployment JAR：4040152字节，SHA256 `6557ab51b91862c1c795b96e3ade04c3925d75145431da33cda093f1ffbf9a27`；其余5个host JAR保持263字节。
- 实际JUnit classpath为54项、3282文件；58个deployment owner、3个AI owner、4个平台族及5个完整host模块核对通过。JAR实际1124个class、127个非目录非class成员。
- 最终metadata SHA256 `0807e249d51f9f8b40b0b6f9e34c0ef6b8727e9bfc82601d82f0e7fa8b8a7504`；classpath SHA256 `b966aab2c36446781918f4d4d7ace522cb6966a591a2ac95dbe378645b8b7f39`。这是源码/target/JAR/实际classpath字节链，不是JVM CodeSource证明。
- 原程序身份沿255原始JUnit/OPEN硬锚与sealed263证明接续，旧Helper、Program、旧测试族、10项资源及RemoteLayout完整族均保持原字节，未重跑旧Program测试。proof SHA256 `354354144dc2655b9efd2b603f0631627e2abfcf779270cb867f85cb55b79a6d`，verification SHA256 `0e78593adc70522a4ed86b18ee12ff161c61f6cb33bc6c3542a30e8abcb1d0b8`。
- 元数据原始捕获成功后，发现继承的便利计数与一项checked-entry摘要过时。原捕获完整保留，另以实际JAR与target重新核记；源码、JAR、classpath及原始JUnit不变。纯身份builder初次因三字段blob与含附加字段artifact整对象比较而拒绝，修为精确身份投影并独立核对实际成员计数和摘要后通过；失败输入保留。

四轮验证及metadata共140个已记录进程身份均退出，独立审计含finalizer为141项；8个自有临时目录已清理。验证、捕获及身份builder执行开关均关闭；未终止用户既有进程。

正式patch仅包含捕获的12路径前后差异，所有hunk已按保留preimage独立重建。patch为148593字节，SHA256 `0ca107b0a9ee916a6b2cc614393d1da9b321a882dd002ac2ecdccd6d74284aaa`。按本仓库迁移中既有约定，仅提交本阶段正式patch及本记录，保留既有未提交源码；不推送。

## 继续边界

本阶段没有打开原数据库、启动原Runtime、调用真实模型或SSH；无新增FULL。最近265实账中原任务 `072bc074-4bce-4c14-b425-f2ff965c7d40` 仍UNKNOWN，272仍FAILED/UNKNOWN，旧READY266和原pending保留；本阶段没有改变这些事实。下一阶段先通过只读公开生产codec核对原状态与新eligibility，再单次产品协调，继续每动作独立APP及新grant。

历史仍为9次FULL（File Transfer 8次、CSV Inspector 208一次），0项完整BUSINESS验收。目标保持 File Transfer → CSV Inspector 严格逐个完成BUSINESS v2、真实浏览器和必要收尾；两者均完成后暂停，按项目报告FULL的 `APP IO / (DEP IO + APP IO)` 与加权缓存 `hit / input`，明确known/unknown，失败FULL和纯恢复窗口分别列示。等待用户决定方向后再开始，不启动第三个项目。

辅助证据位于 `.ai-workspace/agent-live-evidence/agent-autonomy-20261012/agent-publication-current-state-continuation-266/`；此记录对应正式patch [DEPLOYMENT-AGENT-PUBLICATION-CURRENT-STATE-CONTINUATION-266.patch](DEPLOYMENT-AGENT-PUBLICATION-CURRENT-STATE-CONTINUATION-266.patch)。
