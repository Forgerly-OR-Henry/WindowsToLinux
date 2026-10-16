# 原194文件拒因的伴随元数据观测229

2026-10-16，完成AGENT专属的最小诊断增量：在原恢复前检查精确返回 `FILES/NON_PRIVATE_FILE` 后，尝试一次独立审批、新grant的只读元数据观测。5个指定方法、19项不同用例均已有本地通过证据，跳过测试的打包通过。尚未重新运行原194产品恢复，具体远端文件和首次失败谓词仍未知。

## 原因与实现边界

228真实产品恢复仍为UNKNOWN。只读取证已核前端PREPARE95/PRE审批成功并执行，exit64、stdout空、stderr73字节，固定拒因为 `FILES/NON_PRIVATE_FILE`；新RECOVERY_MULTI检查点revision1、未release。该拒因合并了文件类型、归属、权限及链接数失败，不能据此认定chmod问题或授权删除。

直接修改旧检查脚本会改变其exactAction摘要；`DeploymentAgentMultiPreparationCompensation.validateRows` 会在下次观测前拒绝与既存beforeRequest不一致的进度。实现因此保留旧Partial Probe的request/requestDigest、before/after脚本、present/absence及V1 rejection消费者，亦未修改HelperReadiness、Multi进度或proof规则。父在格式化前后分别核旧方法片段和三个依赖源码一致；定点验证继续执行旧owned/absent结果及11项V1严格反例。

仅修改四个既有owner：PartialPreparationProbe、Recovery及对应PartialPreparationProbeTest、PreparationCompensationTest。无新增生产文件、依赖、数据库迁移或其它模式编排改动。

- 新伴随request只从原Captured派生八个固定文件角色：DEPLOYER、AGENT_TASK、WORKSPACE_DEVICE、WORKSPACE_LIMIT、PURPOSE、WORKSPACE_IMAGE、AGENT_MODE、SOURCE_ARCHIVE。
- 原可信工具、祖先及candidate目录守门保留。mutable父目录不安全时不查询archive；每个允许查询的角色只做一次固定GNU stat，不使用-L，不读取正文或hash正文，也不修改权限、挂载或文件。
- 新协议 `WTL_AGENT_PRIVATE_FILE_METADATA_1` 仅输出固定角色及STATUS/KIND/OWNER/GROUP_OTHER_WRITE/LINKS枚举。原始路径、UID/GID、mode、stat输出及错误正文不外传；stat失败为UNOBSERVED，畸形为UNCONFIRMED，均不是absence证明。GROUP_OTHER_WRITE仅对应022位，未宣称owner不可写；AGENT_MODE原本可选，未观测到它不等于缺失必需文件。
- 严格消费者要求八角色完整、固定顺序、有限枚举、终止LF、约定0或64退出码、无stderr且不截断。额外、重复、错序、畸形或未知字段不进入文件事实。
- 仅原PRE返回FAILED且保有精确V1 FILES/NON_PRIVATE_FILE回执时触发一次；取消、中断、UNKNOWN、畸形或其它拒因不触发。先保存原诊断，再在正常控制流中审批新完整动作，不在日志finally中发送。
- multi与单候选范围只增加当前Captured的唯一新请求摘要。新动作仍经完整独立APP、新grant及原身份/事件/配置/layout/检查点/业务引用守门，SDK前后均核；返回后守门变化、UNKNOWN/value或不完整回执均不投影files。
- 新日志标明NEW_COMPANION_OBSERVATION和cleanupEvidence=false。它不修改原失败、UNKNOWN、业务事件、checkpoint或资源，不进入present/absence/cleanup消费者；审批拒绝、执行未知或日志失败也不能覆盖原结果。

这只是新时间点的事实。多文件和父目录查询之间仍可能有竞态，不宣称原子快照或排除root篡改，不追补228首次失败的具体谓词，也不自动修复、重放或清理。

## 本地实际验证

基线HEAD为 `9e303d7b34efbc429e618ed2ba88abf3021fbdd1`、index空，3310份源码与228最终值一致。228封存120文件、六JAR及原e37/886 pair逐SHA复核相同。正常工作目录新核JDK21.0.10、Maven3.9.12、Python3.12.10。保留原迁移混合工作树，四owner原dirty字节在编辑前保全。

唯一格式化write/check及源码边界均exit0。Java只选择以下5个方法，未跑整类、模块全套或全项目：

| 方法 | 不同JUnit用例 |
| --- | ---: |
| privateFileMetadataObservationReportsOnlyFixedSameCallFacts | 1 |
| privateFileMetadataProtocolRejectsMalformedAndForeignOutput | 1 |
| companionMetadataObservationKeepsFrozenProgressAndRequiresFreshIndependentReview | 5 |
| fixedRejectionsExposeOnlyStaticStageAndReason | 11 |
| previousExactOwnedAndAbsentResultsRemainUnchanged | 1 |

首轮java-01的Maven/before/after均0，实际17P、2Skipped；两个Bash方法因旧默认 `C:/Program Files/Git/bin/bash.exe` 不可用而跳过，执行器正确拒绝把它当验证通过。执行器遇首个含skip的报告即停止汇总，原result仅记14case；父在下次Maven前补保留同一UTC窗口内的另一类原XML/TXT，核实该类5P，完整首轮为19case。

从实际Git安装位置核得 `E:/Program/Git/bin/bash.exe`，正常非管理员环境版本5.3.9、SHA `9aa00217a92def5fed1b15c6e2b34a7f89e3781af822ecc00960fe5340df093e`。沙箱内一次仅版本查询因MSYS命名对象访问0xC0000022失败，未误判工具缺失或服务器失败。仅在执行器的记录参数和实际Maven命令显式加入managed.test.bash，没有修改测试源码或产品以绕过跳过。

java-02只补跑上述两个Skipped方法，2P0F0E0S，Maven/before/after和执行器均0；已通过的17项未重跑。合并两次原始报告为19项不同用例都有P，累计21条报告结果中另保留2条原skip，不称21项不同通过。

新Bash方法内部11种场景不额外计JUnit数量。文件类型、symlink、hardlink及失败/畸形输出使用真实本地Bash/GNU stat；Windows上的root/归属/mode、可信工具和内核环境是明确的受控替身。集成5参数包括multi与单候选、APP拒绝、SDK前后guard变化、执行UNKNOWN/value和日志失败；使用真实H2 Mod账本及本地NativeAiFixture，checkpoint仍为受控MemoryCheckpoint/CAS。它们不证明真实Linux权限、SSH、真实provider、宿主重启或原194恢复成功。正常backend before按旧流程推进一次revision；断言伴随动作不额外写progress，payload、未释放状态及原事件保留。

唯一 `-B -pl src/mod/deployment -am -DskipTests package` 的Maven/before/after均0。最终源码3310份、只有四owner变化，manifest SHA `7c9655ab6c0230348f1d646ccbc27c9f7fa677384798a5e4c3fe17ca896eb3b1`。新deployment.jar SHA为 `3c61049bf3ecd0ba9ff3611968a558799502c16839c00d2aef02d899f1e349f8`；五个宿主JAR SHA保持原值。包后核部署30组（68个class成员及8资源）、AI3族、四宿主族及五宿主完整target/JAR内容；父独核新协议/日志常量及部署成员原字节。该证据是静态source/target/JAR绑定，不是JVM CodeSource。

父汇总器各有一次本地工具错误：深报告路径未加Win32 extended前缀、旧类族元数据无outerPresent字段。保留原工具后，仅修对应只读核验：前者用规范显示路径加extended读取，后者从source派生准确outer并核完整target/JAR成员和原字节；均复核通过，没有重跑业务测试或打包，也未改原失败/skip报告。

## 交付与后续

原194数据库仍76132352字节/SHA `e37dfde9b81d7e20b46bf359b77b4d677da4b1d3d5c498835a378240962ed16b`，引导358字节/SHA `886b792519a8fe8ed0dc5ac894eef95c3ca1856617f0448ccafe3bba4b133f8e`。本轮未打开该库、DPAPI、产品Runtime或真实模型/SSH；本地测试H2独立于该原库。原UNKNOWN、检查点、私有备份及资源调查证据保留，未自动restore或删除。

正常Close及父独立CIM已核136组本轮精确PID/创建时间均退出，标记TMP/短UDS不存在，共享格式缓存保留。补丁[精确源码增量](patches/AGENT-PRIVATE-FILE-METADATA-229.patch) SHA `3ebb9a4f715a10ec65a31ef380fe560b01ccc6981424df4208087f0607d6c02a` 已在带标记的自有副本执行真实git apply正反并逐字节匹配，当前源码反向check通过，副本已清；不能据此声称干净HEAD能独立构建。仅本任务文档、patch及单路径-text属性进入本地提交，不推送。

证据根：`.ai-workspace/agent-live-evidence/agent-autonomy-20261012/original194-private-file-diagnostic-229/`；入口为baseline、implementation/test-plan、parent-legacy-after-format、parent-unit01-observation、parent-unit-verification、parent-package-verification、parent-process-closure及implementation/patch-byte-verification。准备报告和writer报告保留其当时的未执行状态，实际以父执行、原XML和最后验证为准。使用Sol High/XHigh，未使用Astra。

下一步按当前源码/JAR重新核基线，先私有保全当前必要pair，再通过唯一原Task产品入口取得伴随观测；不借新诊断改旧beforeRequest或grant，也不重复付费能力探测。还未得到新的远端文件事实、归属/依赖/absence或容量结清证明。本轮无真实provider usage或缓存/审批收益；228恢复窗口78.7118%缓存、APP100%仅历史纯恢复，不混入FULL趋势。CSV Inspector、File Transfer仍严格串行、累计7FULL且均未完整业务通过；两者调整和真实业务通过后再核FULL审批/cache，审批仍高时按用户要求暂停讨论。完整冻结八项目、非默认目录、整机重启及其它模式动态容量仍未验证。
