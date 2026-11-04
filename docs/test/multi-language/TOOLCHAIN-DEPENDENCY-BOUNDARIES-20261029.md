# 工具链依赖事务边界定点验证（2026-10-29）

当前在r09f隔离树修复两项不同的依赖事务边界：APT禁止移除，以及完整固定依赖动作的独占systemd服务、累计预算与可验证收尾。首版已本地提交da90并打包；随后真实无包Linux诊断确认了cgroup路径退役和默认ExitType计时缺口，v2准入、完成证明和独立清理现已同步并完成所列本地定点。真实包事务、模型、新一轮原业务及生产v2的Linux验证尚未执行。R08仍为6/8失败，Log和Directory原UNKNOWN不重放、不改判，没有启动R09或重新整轮清场。

## 已证问题与未知边界

R08 Directory原root a61da983-01f9-4084-9581-29c4f471a96e的持久化拒绝理由指出：apt-get install -y未禁止解析器移除现有软件包，可能超出声明的依赖闭包目标。第二阶段只在私有副本读取精确拒绝行，原UNKNOWN不重放；SSH/API/master内存脱敏与整个JSON残留检查通过，safe输出70f60127、父接纳9f68be64。

源码共享dependency_main确实仅传-y和--no-install-recommends，没有禁止移除。APT官方说明[--no-remove会在计划移除任何软件包时中止](https://manpages.debian.org/trixie/apt/apt-get.8.en.html)。因此确认的是命令缺少声明范围所需的禁止移除约束；本次在独立审批处已拒绝，不能说实际发生过卸载、解析器确实会卸载或整轮已修复通过。

本问题与R07 CSV无native提交、ff83原生提醒分支不同；未撤销或改变ff83。共享资源从原Agent位置迁至service/deploy/toolchain时的7a321fa1及后续冻结原件保留。APT基础补丁仅收紧安装参数，不改变独立审批决定、模型、任务状态或既有来源、签名、root-owned、配置与hook校验。

R08 Log的已脱敏独立审批理由指出每条内层命令1800秒可能超过所述外层1513秒，而且仅在finally且leader仍存活时killpg不能保证完整进程树退出。源码确认的是：原dependency_run每次重置1800秒；默认signal终止不保证finally执行；leader先退而后代持有stdout时会跳过原组kill；setsid后代可离开原进程组。1513这个精确数仅见于审批理由，本次没有独立计时证据。该请求在审批处已拒绝，不能说APT已经dispatch、发生过超时或留下了进程。

## 隔离改动

在独立r09f工作树、源73bbbbbb基础上，为共享APT install增加--no-remove，继续使用原包清单、认证选项和单次事务/回执。该路径供PYTHON/PHP/RUBY/NATIVE_BUILD/HELPER_RUNTIME使用；APT基础补丁未改DNF包命令参数，生命周期监督同时覆盖两管理器。实际八项影响仍须下一统一冻结轮证明。

两个既有Python测试夹具仍引用已迁出的service/agent/toolchain依赖资源，现修正为生产实际service/deploy/toolchain，并在既有PHP、native build和helper-runtime事务断言中检查禁止移除选项。不是增加旧路径兼容副本，也不把夹具路径修正当生产问题修复。

以上APT/loader基础已提交cff67b662c3711d084d77b3b30e850acb4b21a04。其后的生命周期修改仍共享同一固定包表、APT/DNF认证、OS文件/owner/digest、hook和配置检查，不通过删守卫或强制批准解决拒绝。

## 固定依赖动作的新执行合同

standard NativeBuild、Agent dependencies别名和HELPER_RUNTIME嵌入入口使用同一完整固定Python源。规范参数为profile、operation、整数T、server、task；T必须与最终CommandRequest.timeout精确一致且在45至7200秒。完整源、stdin长度/SHA、exact action、layout及包/自有unit/manifest文件targets仍由inspect交叉校验。外层channel若把T再次缩短，在独立审批和dispatch之前拒绝；已经获得的旧grant不能静默执行另一预算。HELPER_RUNTIME继续绑定原固定两小时files-install envelope，不依赖本来尚待安装的managed-helper。

完整依赖worker及包管理后代先进入operation独占的root transient service。v2在worker核实入组、实际属性/InvocationID/cgroup/MainPID并写READY后，仍须等待监督端完成核心cgroup.events FD绑定、初读populated=1及独立ACK；ACK及原期限核对通过之前，没有包事务或包数据库查询。没有所需systemd/cgroup v2/属性/路径能力就拒绝，不fallback裸Popen。服务使用Type=exec、ExitType=cgroup、RemainAfterExit=yes、KillMode=control-group、SendSIGKILL=yes、有限TimeoutStartSec和TimeoutStopSec；未照搬vendor DynamicUser沙箱，root包维护仍是现有OS信任边界。

动作在远端开始时建立一个monotonic总deadline，配置/owner查询、update/install/query及回执共享该额度，不为下一条命令重置1800秒。实际RuntimeMaxSec在worker源持久化后扣除已消耗的准备时间，并满足RuntimeMaxSec+5秒stop+25秒启动/观察预留不超过精确T；manifest持久化后、启动服务前再核一次，准备过慢不能扩大总额度。worker同时使用同一初始时间导出的总工作deadline。独立systemd期限可以覆盖SSH/launcher/worker亲代异常退出，不依赖Python finally；它不是与本地SSH request绝对同一瞬间的截止，也不是即时取消协议。

自有路径为捕获layout.stateRoot下dependency-operations/operation，unit为windowstolinux-dependency-operation.service。全新root-owned独占目录和manifest绑定task/server/operation、源digest、unit与预算；碰撞/身份漂移拒绝，不复用旧操作。v2 manifest、Description、独立cleanup-ownership、READY、ACK和内部结果envelope共同绑定同次原操作。源、manifest及各JSON文件先完整写入/fsync临时文件，再用无覆盖link发布，避免controller读到最终名半文件；这复用了既有tracked binding的发布方式。自有文件/目录别名拒绝，现有OS工具正常路径守卫保持原规则。

正常成功要求原包命令真实成功、完整精确版本回执及结果envelope、loaded/exited且MainPID=0/ExecMainStatus=0/Result=success、匹配InvocationID，以及从准入起持续持有的同一核心cgroup2 events FD的终止证明。仅接受正长度pread(offset=0)完整读到populated=0，或先核核心文件/文件系统/devino及初值1后，同一FD的pread实际返回ENODEV。二者使用不同proofKind；退役分支不能声称直接递归读取cgroup.procs为空。目录缺失、普通FD、EOF、seek失败、其它errno、未绑定或错身份均不提供成功证明。

监督端先并发观察正常完成与held-FD证明，随后才有限stop精确unit、真实wait launcher0并核终态；不能先stop后用杀净来补业务成功。v2回执严格校验27个生命周期字段、实际ACK/独立归属规范字节SHA、整数、小数/溢出、身份、源及boolean。缺少或额外带旧recursiveCgroupEmptyObserved均拒绝，未增加生产旧合同兼容。

显式产品清理inventory/plan/after识别新prefix仅作为候选；v2必须有root-owned manifest/实际worker源和独立cleanup-ownership，与实际loaded unit的完整静态属性、预算、Description、InvocationID一致才认领，不依赖READY已经出现。apply在停止前再次比较该绑定，reset-failed前另核原归属和terminal/MainPID0，拒绝foreign/colliding unit。不通配kill，不杀全局apt/dpkg，不认领旧UNKNOWN。成功后manifest/回执保留，供后续独立清理核验。现有disposable-host-reset仅认本轮原默认layout，不据prefix接管其他layout的unit。

## 保证与尚未验证的Linux边界

取消、断连、stop失败、身份不明、缺少完整正常完成与已绑定核心FD证明都保留UNKNOWN；独立期限不是已观测的停止回执。不可中断D状态可能无法在有限时间核退，强制停止APT也不证明包数据库一致或事务回滚，不自动重跑修复。root maintainer script可通过systemctl启动其他unit；本服务cgroup终止不代表全主机无跨unit副作用，也不授权停止合法系统服务。

首版真实Linux探针的not-found19字段门通过，但active/exited后ControlGroup为空且目录退役，原UNKNOWN70保留。后续FD首版实际在lseek阶段errno19，仍UNKNOWN70；不能回填为pread通过。新版本改用正长度pread0并显式ExitType=cgroup：正常延迟读取取得同一held FD退役19；leader先退但活setsid后代时MainPID0/running/populated1，正确拒绝正常成功。对应精确Ubuntu源码支持核心文件退役与默认ExitType=main在leader退出后解除RuntimeMax的判断；这不是原R08曾实际残留包进程的证明。

第一次TTL夹具在READY前身份检查UNKNOWN70，触发的原show值未保存，保持未知。新cases-03只修已证准备态协议：同次21字段有界留存，原期限内仅未绑定/未READY/静态完全一致的空或零ID可PENDING，绑定后漂移严格拒绝。新TTL实际两次startup均BOUND，没有实际触发PENDING；44.110311秒进入stop-sigterm/populated1，49.356519秒failed/Resulttimeout，同held FD pread19，原client实际-9。独立期限机制观察完成，不是零异常或八项业务通过。快照绑定清理已精确核unit notfound、自有nonce/marker/空根非递归删除及JVM/outer/tmp退出。证据见[父TTL收尾](H:/Program/WindowsToLinux/.ai-workspace/evidence/assisted-continuous-20261028/r09-fix-only-01/events-fd-kernel-fixture-04/cases-03/parent-cases03-actual-closeout-01.json)。这些prototype结果不能代替新生产源的后续Linux矩阵。

## v2实际本地定点与产物边界

producer原始资源SHA为f6d63fb499d58430cfd57618372cc79f57c1c56ca9f4689aa97eb107422d8b6b，实际完整bundled源SHA为20911b7d038244f1e5d3c8a9118d0eb9ed95388052e73f7f70aabac281642df3；两者范围不同，不互相替代。v2实施保留首版累计T/frame5/APT禁止移除/原审批/source保护，没有把原问题改回裸包进程。

监督端11个明确Python方法首次11/11通过、0.244秒。包含正常完成必须先于stop、两个proof、最小T45、READY前独立归属、worker无ACK不能包动作、严格ACK类型、核心FD/文件系统/初值、准备态身份和原期限。自查还修正finally中client wait异常跳过FD关闭/诊断并可能掩盖原拒绝的缺口：资源与观察始终收尾，原失败保留，成功回执延后到finally完成后；直接反例分别覆盖已有主失败和正常完成后的wait异常。这是新实现自查，不能说R08已发生过同样故障。

清理归属四方法actual0/0.131秒；另新增apply在stop后、reset前归属漂移的单方法，首次因测试在mock恢复后读取PHASE而exit1，原失败保留。去掉该过期断言，四事件序列及reset/Popen/kill零调用断言保留，仅复测同方法1/0/0/0、0.014秒；原四未重跑。v2本阶段Python共16个唯一方法、17次执行，不能称一次16全绿或全类通过。生成器/解释器实际wait、CIM和自有tmp关闭以[worker收尾](H:/Program/WindowsToLinux/.ai-workspace/evidence/assisted-continuous-20261028/r09-fix-only-01/production-lifecycle-v2-08/producer-worker-local-closeout-01.json)及[清理反例收尾](H:/Program/WindowsToLinux/.ai-workspace/evidence/assisted-continuous-20261028/r09-fix-only-01/production-lifecycle-v2-08/cleanup-apply-drift-review-closeout-01.json)为准。

Java仅18个明确方法、8类，首组实际51个参数用例：50通过，1个旧冻结源码SHA断言失败；没有错误或跳过。新实际Python canonical record函数生成的14键ownership/16键ACK字节，经Java消费端校验通过；正常populated0和退役null/19两个正例、29类回执反例、原审批/grant、完整源/targets/导航，以及Native选定16个参数用例均已执行。唯一失败停在旧bundle SHA0ebcde53断言，后续profile循环当时未执行；更新为已审新源20911b7d后只复测该方法，JUnit1/0/0/0与Maven actual0。未重跑其余50或原Native16，不合并成单次51全绿，旧三CurrentSource仍未解决。

两处旧frame夹具仅同步dependencies/native的五参数与完整targets，RUST负例保留合法形状而只改profile，不增加生产旧兼容。显式selector覆盖其4方法/10次参数调用。首次XML归档长输出路径失败和辅助时间投影错误另保留，后用短名独立封存8份原XML再复测，没有改掉原51/1。实际[原组结果](H:/Program/WindowsToLinux/.ai-workspace/evidence/assisted-continuous-20261028/r09-fix-only-01/production-lifecycle-v2-08/java-targeted-02-original-results.json)及[单方法复测](H:/Program/WindowsToLinux/.ai-workspace/evidence/assisted-continuous-20261028/r09-fix-only-01/production-lifecycle-v2-08/java-failed-only-03-results.json)单列。

验证后仅本地提交本次源、测试与本说明，默认不推送。提交后的必要package显式skipTests；实际结果、当前源与CLASS/JAR资源对应及任务进程/tmp关闭，以[本阶段最终产物与收尾](H:/Program/WindowsToLinux/.ai-workspace/evidence/assisted-continuous-20261028/r09-fix-only-01/production-lifecycle-v2-08/parent-final-artifact-closeout-08.json)为准，不能拿首版package05或prototype CLASS代替。该构建不是全量测试或生产Linux证明。下一步受控新生产Linux矩阵完成后才新冻结、产品清场和八项完整原验收；FTP8MiB、其余既定负载与独立审批不减。

## 首版实际验证与交付边界（历史结果）

APT基础仅指定七个Python unittest方法，0失败，0.058秒，Python10704实际wait/exit0及CIM退；该历史结果保留。生命周期阶段先运行5个新增方法加直接受影响原7个，共12方法0失败；随后Ready/manifest类型收紧及新增cleanup辨认，运行7个直接方法0失败；再只验证原子发布新增反例和2个直接监督方法，共3方法0失败；持久化后预算门只复测直接2方法，0失败。生命周期阶段为15个唯一方法、24次方法执行，不能把重跑合成24个唯一测试；内部subTest不另算方法。没有真实Linux、APT或SSH调用。

首轮Java仅9类明确方法，实际XML64用例：63通过，1个旧篡改fixture失败。该fixture仍替换“两参数后紧接heredoc”的旧片段，在新五参数输入中替换实际为no-op；修为当前参数尾，先断言mutant字节确实不同再断言parser拒绝，不放宽生产parser。原9XML/失败原样封存；其中20个Native原参数用例全部通过（218.6秒），不因修fixture或原子发布重跑。

随后只复测失败及直接受完整源、原子发布和整数收紧影响的5类8方法，实际19个参数用例，0失败/0错误/0跳过，Maven实际exit0/BUILD SUCCESS。未再跑其余首轮已过组；不表述成“单次64全绿”。原子发布反例证明fsync/close先于最终名发布、碰撞不覆盖；溢出反例使用2^64+60，不能借longValue回绕成T=60。它们是新实现自查收紧，不冒充原R08已经发生过的失败。

最后预算准备复核只运行源字节/别名/严格回执一致性1方法；回执的文本身份类型收口只运行生命周期回执反例1方法，两次均实际1/0/0/0。数字InvocationID不能借asText当文本身份；未重跑Native20或旧三CurrentSource。各轮XML和原生退出分开保留。

新树必要package明确-pl src/mod/files,src/mod/deployment -am -DskipTests，双公开Central settings、JDK21、staticLive/agentLive=false。package04实际BUILD SUCCESS、17 reactor项目成功，全部测试跳过，Files第三Mod已生成；它是后续原子发布/整数收紧之前的中间产物，不能当最终修复JAR。首个有效package03仅testCompile失败于一处直接fixture旧targets签名，修正后再构建；此前两次cmd launcher在Maven启动前失败，另行保留，不计构建/业务分母。本任务误建的12个空目录已在同一PowerShell按精确绝对边界、创建窗口、nonreparse及空目录条件非递归清理；没有改系统设置或原文件。

提交后的最终skipTests package实际结果、JAR与CLASS/资源对应SHA、全部原生wait/CIM及临时收尾以[本任务最终收尾回执](H:/Program/WindowsToLinux/.ai-workspace/evidence/assisted-continuous-20261028/r09-fix-only-01/dependency-lifecycle-02/r09-lifecycle-closeout-02.json)逐项绑定；不沿用中间package04产物。

实际launch/stdout/stderr/exit、CIM、tmp及原XML位于[生命周期定点证据](H:/Program/WindowsToLinux/.ai-workspace/evidence/assisted-continuous-20261028/r09-fix-only-01/dependency-lifecycle-02/)。实际JUnit非空classpath54项，当前user.dir/target均属于r09f；未引用r08 targets、classpath或t06。自有临时目录位于该证据目录，不涉及R08收尾。所有正式代码与本记录完成直接验证后仅本地提交，不推送。

旧三个CurrentSource失败方法未纳入本次64，继续保留未解决状态：toolchainCurrentSourceClaimsAndChangedRequestsRejectBeforeApproval、toolchainCompleteEvidenceRetainsTheOriginalEncodedRequestLimit、toolchainPreparationSuppliesOneCurrentSourceAfterHistoryAndPreservesFinalDenial。它们构造installed managed-helper的deployment.toolchain.prepare.v1、prepare-toolchains和EMPTY stdin，走旧prepare_official_toolchains源证据；本次不改该路径，不以新dependencies五参数或64个定点结果称其已闭合。未执行全类、全模块或全项目测试，未取得真实provider、Linux内核收尾或新八项验收结果。R08工作树、冻结73/P71b/R5b、失败和UNKNOWN、全部取证、targets及论文修改保留。
