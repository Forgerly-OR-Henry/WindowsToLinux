# 原238发布纠错277：本地验证通过，按用户要求暂停

277补齐独立、一次性的内部发布纠错执行合同，本地定点验证和打包成功；尚未启动产品协调、provider、SSH或原数据库读取。用户要求切换配置，本轮在本地提交后暂停。原238仍UNKNOWN，File Transfer及CSV Inspector的完整BUSINESS v2、真实浏览器与必要收尾均未完成。

## 缺陷与修复范围

已确认的本地生成缺陷：旧Helper发布恢复程序和ContinuationProgram拼接定义时止于`database/10-native-instances.sh`，其中打开的`WTL_NATIVE_DB_PY`要到`21-native-operations.sh`才闭合，因此后接guard进入未闭合定义。这不证明旧272/391未执行或没有远端副作用，历史UNKNOWN保持。

旧Helper、RecoveryProgram、ContinuationProgram、FollowupProgram及其资源和解码器冻结，历史sourceVersion不变。新增`deployment.agent.publication-correction.v1`，事件`PUBLICATION_CORRECTION`，固定回执`WTL_AGENT_PUBLICATION_CORRECTION_1`/`PUBLICATION_CORRECTION=`。完整拼接原定义和20/21闭合段，保留原continuation guard，执行真实`rollback_deployment_first`；不安装helper/controller，不改写旧ACK。

纠错绑定原pending、原385–391 continuation及276实际491–498成功followup的请求、回执和源码身份，并要求498之后的新完整整图。目标已满足则优先原目标证明，否则新动作仍独立APP、新精确单次grant；APP展示完整程序和typed binding。原两把锁、状态、六项fingerprint和三项native contract在修改前重核，candidate保留。执行限制仍180秒、16384字节、9个目标。

任何REQUEST均消耗“原pending＋原continuation REQUEST”一次性键，DENY、中断、UNKNOWN也不重置，版本、UUID或后续诊断不能绕过。纠错回执成功也只保留本次UNKNOWN；后续必须新完整整图达标，才沿既有observed-only、逐动作独立cleanup、原生VERIFIED/FAILED结清。请求日志以canonical binding摘要避免重复正文超过既有4096限制，完整APP和前缀验证仍保留，没有放宽帧限制。

源码范围12个路径：新增Correction、CorrectionProgram、publication-correction.py及ProgramTest；修改PublicationRecovery、PublicationCompensation、AgentRecovery、CommandEvidence、FixedCommandApproval，以及HistoryArchiveTest、CommandEvidenceTest和测试Fixture。没有数据库表、公开API或UI变更。两处同一验证边界内的重复完整历史读取被移除，保留fresh public read、前后不可变事件比较及所有身份和回执校验；无跨回调缓存。

## 实际验证

仅选择本次直接相关的6个方法，共12个参数化JUnit用例。最终覆盖由已通过组及失败组定点复跑合并，不重复跑已通过组，不运行全套测试。

| 证据目录 | 实际结果 | 说明 |
| --- | --- | --- |
| test-01 | 退出1，未执行测试 | Maven本地仓库写权限前置失败 |
| test-02 | 退出1，未执行测试 | Windows dotted `-D`参数未正确引用，随后改为字符串参数数组 |
| test-03 | 12例：9P/3F/0E/0skip | Evidence 1P、Program 3P；Archive三例超过测试封装60秒，失败保留 |
| test-04 | 8例：6P/2F/0E/0skip | 两处重复读取消除后；资格3例已P，complete/unknown仍超过60秒 |
| test-05 | 中断，退出1，无完成报告 | 父在发现组合方法时限替换未完整应用后中止；不计通过或业务失败 |
| test-06 | 5例：5P/0F/0E/0skip，package退出0 | 仅失败组合方法，保留全部业务断言；Maven总计4分41秒 |

最终12例：test-03的Evidence 1＋Program 3、test-04的资格3、test-06的组合5。保留的完成JUnit原始记录共25例，20P/5F/0E/0skip，历史失败不改判通过。

60秒是测试内三次顺序协调合并的封装上限，不是用户或产品SLA。为避免继续扩张生产性能改动，Fixture可选上限仅由60改90秒，默认15秒及所有旧60秒调用不变；只有新增组合方法使用90秒，断言和生产timeout未变。不能声称该组合在60秒内通过。

Program测试使用真实生成定义、Python/Bash语法及固定入口，覆盖13种漂移拒绝和1种本地owned rollback；Windows环境只替换必要的系统、锁及合同输入。此为本地测试，不是Linux实机或业务验收证据。审批测试覆盖完整源码和独立fresh grant；历史测试覆盖资格、消耗、所有结果保持UNKNOWN及后续新整图/独立cleanup。旧sourceVersion断言通过。

最终`src/mod/deployment/target/deployment.jar`为4158100字节，已直接检查包含新增Correction、CorrectionProgram类及Python资源；未做全源、全CP或全JAR条目清查。提交时只统一计算12个源码、该JAR及本轮patch/说明的摘要，见配套`.sha256`。

## 暂停与恢复入口

本轮测试临时目录`c277a`、`u277a`及自有空reader sourcepath已删除；实际测试进程退出。沙箱内CIM读取曾被拒绝，随后成功读取，带本轮专有标记的Java为0；没有终止用户进程。`pause-cleanup.json`保留准确结果。

`ReadOriginalCorrection277.java`仅隔离编译退出0，未运行，未打开DB、DPAPI或Runtime。`run-original277.ps1`默认禁用，已准备但未执行，没有live-01。后续只读工具以文件属性检查代替整个DB文件反复hash，协议自身的动作、源码及回执摘要仍保留。

恢复必须先等待用户明确继续并核对切换后的配置，再由产品对原238进行一次有界协调，逐动作独立APP及新grant；不得直接启动脚本或自动重试。若需要事后账本读取，须等产品退出后使用public只读工具，缺失或不完整不算结清。原Task `072bc074-4bce-4c14-b425-f2ff965c7d40`，execution `45e3a9a4-a6b8-403a-89f5-605e11eeeca0`，原状态目录`src/app/db/data/agent-live-file-transfer-8pqtfa76o`。本轮未改变目标或原账本。

File Transfer优先，完成其BUSINESS v2、真实浏览器和必要STOP/DISABLE收尾后才处理CSV Inspector。两项都完成后再次暂停，报告FULL的APP IO/(DEP＋APP IO)及输入加权cache hit/input，区分known/unknown、失败FULL和恢复窗口；不得把275恢复窗口的100% APP已知IO占比及52.3083%缓存命中率当两项目最终结果。194/234不恢复，221不自动恢复，不启动第三项目，密码和API密钥不进入模型正文。

证据根：`.ai-workspace/agent-live-evidence/agent-autonomy-20261012/agent-publication-correction-277`。当前工作区存在先前源码迁移，按本任务既定交付方式，仅将本轮可应用源码增量patch、正式说明及提交时摘要提交本地Git，不暂存整批迁移源码、个人AGENTS或辅助证据目录，不推送。实际工作源码已修改且完成上述定点验证；patch不是整仓迁移提交。
