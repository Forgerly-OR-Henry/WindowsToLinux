# 原194产品恢复232

2026-10-16，唯一原任务产品恢复检查通过：1 PASS、0 FAIL、0 ERROR、0 SKIP，157.4秒。产品返回原任务 ENDED / FAILED，事后正式归档只读核对也通过。FAILED是原失败部署的最终结果，不是恢复检查失败，也不是业务部署成功。

## 当前来源与执行

恢复用户为更新Codex而暂停的工作后，核对HEAD `a84293be852f8309c4d0a7a3c97d3d3eb96f2298`、空索引、3310项实际源码、六个JAR及原数据pair均与暂停点一致。PowerShell7.6.5、Java21.0.10、Maven3.9.12、Python3.12.10可用。保留混合迁移工作树，未切换干净HEAD或整体暂存源码。

复用232暂停前已完成的约76MB私有必要备份；正常用户重新核源与副本散列相同，没有再拷贝整个Runtime。沙箱读取私有备份被ACL拒绝后，只在正常用户环境核验，不把它判断为服务器失败。未重新运行231已通过的48项源码定点检查、格式化或打包。

当前deployment.jar为3755629字节，SHA256 `1299e3225748d0019a60097a610990c4dbee0613e9812a5ea4069066dd050ca2`。实际运行前后源码、部署30组、AI3族、平台4族、五宿主完整target/JAR及54个classpath入口、3210个文件均冻结一致。这是静态来源绑定，不是JVM CodeSource插桩。

原Task为 `a67e3b89-d04e-49a7-8b5e-20ed93803d53`，Runtime为 `src/app/db/data/agent-live-task-board-q3k1ohhos`。唯一入口为 `DeploymentStaticLiveAcceptanceTest#reconcilesOriginalTaskThroughProduct`，明确originalTaskOnly=true、originalPreparationOnly=false、configureAgentRecoveryModel=false。运行窗口为2026-10-16T14:53:03.6145110Z至14:55:58.2639669Z；Maven、before、after均退出0。

产品使用原受保护配置及已获信任的重装后主机指纹，每个完整动作仍独立审批、取得新grant。没有新能力探测、其它旧任务恢复、新FULL或Root直接SSH。没有预判远端CREATED、重用旧grant、重放不明命令或回滚旧UNKNOWN。

## 诊断工具与验证边界

暂停时诊断工具为未验证Java草稿。本轮先编译并用纯消费者确认：旧草稿只检查member摘要格式，错误beforeRequest也会被接受。仅补上原集合的candidate、请求和回执精确绑定，最终Java编译退出0，47项纯消费者通过；PowerShell未修改，三个既有AST结果复用。使用Sol XHigh，没有调用Astra。

这47项覆盖v1/v2、UNOBSERVED不变成absence、错误请求和回执拒绝、typed三种stage的绑定及输出长度、UNKNOWN/guard、严格产品proof，以及当前FAILED与原历史UNKNOWN分离。synthetic数据不能替代原97条实际事件或真实Linux证据。自有消费者临时目录已清，正常用户CIM确认相应Java/javac已退出；未事前采集其精确PID对，该限制保留。

产品结束后，父按新pair和真实窗口只运行了一次原诊断工具。它在EXACT_PLATFORM_TASK守门退出1，未产生平台任务行投影，stderr为空，原pair及锁不变；随后正常关闭两个自有进程并清临时目录。这个失败不能单独证明原行缺失或归档成功。原工具及失败证据保留，不放宽原97前缀检查、不重跑付费恢复。

生产归档路径会在结清后保留操作回执和宿主有界摘要，并删除符合条件的原明细。另建最小正式归档只读工具，复用生产TaskArchiveReader及DeploymentOperationReceipts解码；先通过Java编译、19项内存H2消费者、3项纯绑定及两个PS AST检查，再绑定本次postpair和窗口执行唯一实际读取，退出0、stderr为空。原reader及原97守门未修改。

| 本次正式归档观察 | 结果 |
| --- | --- |
| 精确原任务平台活动行、全部资源行 | 各0 |
| 原Mod task、event、execution明细 | 各0 |
| 精确原任务operation receipt | 唯一1行，AGENT / FAILED |
| 正式宿主有界摘要 | ENDED / FAILED，blockers=0 |
| 原App应用、组件、发布、committed execution | 各0 |

回执与数据库行由生产decoder逐字段核同，server摘要与230保留的安全身份核同，原App从本轮父绑定取得。结清回执时间为14:55:51.726Z，宿主结束时间为14:55:51.750Z，均在本次产品窗口内；事件为DEPLOYMENT_AGENT_PREFORMAL_RECONCILED，证明摘要为 `f8b7b53205b8945302667a4ae9793d0ad9c824da8c7f99d55cccaf02746c8735`。FAILED的validateBusiness本身不检查App缺席，因此另做上述精确业务行计数。

这确认了原任务在产品内结清、归档且没有完成业务App。已依法删除的详细事件不再从旧日志重建，未补判远端曾是哪一种typed准备阶段。正式声明不替代新的远端物理盘点；本轮没有刷新实时磁盘自由量，不推算实际释放字节。

## 已知用量与收尾

原Task的last100有界窗口记录84条usage行，排除58条旧记录；本次26个不同generation的input/output均已知。input为365454、output为12632，总计378086 Token；缓存输入276861、未缓存88593，已知一致输入加权命中率75.7581%。

本次仅恢复审批，已知生成全部属于APPROVAL，因此审批占已知总Token为100%。这不是两项目FULL的审批趋势或优化收益。26项reasoning用量未知，不重复加入output；完整generation覆盖、物理HTTP数和账单仍未知，不付费补取。

正常Close确认产品15个自有PID/创建时间对已退出，本轮TMP与短UDS已清。父独立正常用户CIM核产品、两个reader及其closer共21对均退出，归档reader临时目录已清，未终止其它进程。产品后原DB为10416128字节，SHA256 `dd5277baee4f887183564407ee64684345a4a08562925ff91cc9d138c674492e`；358字节引导SHA256仍为 `886b792519a8fe8ed0dc5ac894eef95c3ca1856617f0448ccafe3bba4b133f8e`。两次只读前后原pair与空产品锁均不变，不恢复旧4319库，备份及调查证据保留。

父第一次事后绑定因Windows深路径导致XML读取FileNotFound，在任何绑定写入前退出。仅纠正extended路径处理后再绑定；未重跑产品或更改守门条件。

辅助证据根为 `.ai-workspace/agent-live-evidence/agent-autonomy-20261012/original194-product-stage-observation-232/`，主要入口是resume-audit、parent-terminal-audit、parent-process-closure、provider-usage、唯一产品目录、diagnostics工具验证及实际失败记录、diagnostics-archive的工具验证和实际归档投影。229、230、231封存证据保持。本阶段未修改生产源码、配置或重新打包，仅隔离本记录本地提交，不推送。

CSV Inspector和File Transfer仍严格串行，累计7次FULL、零完整业务通过；本次没有新增业务验收。下一项目必须重新识别源码、API、URL、CORS、监听、端口及当前容量；不重放旧计划或额外重试其它旧UNKNOWN。两者实际业务完成后分别核FULL审批与cache，审批仍高再按用户要求暂停讨论。其余项目、冻结八项目、非默认目录和整机重启恢复仍待验。
