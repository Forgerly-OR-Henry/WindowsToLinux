# Agent 原238补偿协调256：仍UNKNOWN

256唯一原Task产品恢复已结束，原Task仍UNKNOWN；没有新增FULL或完整BUSINESS通过。本阶段无production source修改，不生成patch；使用已本地提交的255修正版`1aa345283739487b59de58fb501775162db94d43`，实现边界见[255完整输入修正](DEPLOYMENT-AGENT-RECOVERY-REVIEW-INPUT-255.md)。257只读账本诊断尚PENDING，不推当前UNKNOWN原因、INSTALL发送与否、远端效果或资源结清。

## 实际入口与结果

固定Task `072bc074-4bce-4c14-b425-f2ff965c7d40`、Runtime `agent-live-file-transfer-8pqtfa76o`、期望App身份`wtl-agent-file-transfer-8pqtgvbwc`；run `original238-compensate-256-01`、session98226。唯一方法`DeploymentStaticLiveAcceptanceTest#reconcilesOriginalTaskThroughProduct`，originalTaskOnly=true、preparationOnly=false、configureRecoveryModel=false。每个完整动作仍须独立APP+fresh grant；Root无直接SSH、无旧action/grant重放，不触其它Task/newFULL/显式clear-history。产品可能正规FAILED收尾或仍UNKNOWN，原ENDED断言保持。

| 项目 | 实际证据 |
| --- | --- |
| UTC窗口 | 2026-10-16T22:07:45.2951296Z—22:11:45.5898246Z |
| 退出与JUnit | Maven1；1F/0E/0skip；原Task UNKNOWN |
| source guard | before0 / after0，无热改/自动retry |
| result SHA256 | `cc887e3921ea3e75d3d7cf173cd190c422d6b8cfd9a66268a23e5e2e2a942fd7` |
| 最终stdout | 220274B / `f2af657139757ec088f97d4d2a7b86f8688949a5483634276448a90b8cf12b6e` |
| 正常收尾 | close0，18产品精确PID+创建时间退出；最终含两capture共22pair全退，2ownTMP/UDS absent，Runtime保留 |

原JUnit失败不等于原Task已ENDED/FAILED；旧252经253/254证明的DENY/NOT_SENT也不能代替本轮证据。所有执行、监控、收尾和usage flags已撤回false。工具清临时目录不等于产品资源结清。

## 冻结与准备故障

实际source3319 / manifest `8bdcb32a96e2a22db672608aa36b7de3a43677c47e19a8767aa98d05f9f29824`，六JAR/54CP-3249files由本次fresh capture、offline0、before/after守门绑定；不沿旧CP3248。deployment.jar3917160B/SHA `8a3a3db409d5f66e29ffa98fa787a665b3961103dfc086210ba269de3efa0c6c`，CP SHA `392aa7034674cde7724c4ad4513ec81cac98ffb1640708d5cc574cc27d3692e9`。静态字节/有效CP证明不冒充完整JVM CodeSource或远端状态。

首capture在`RETAINED_EXACT_FILE_SET`安全失败，baseline未产，DB/Java/provider/SSH均未启动。原因是旧sealed根长路径报告未列入旧seal直接文件集；父保失败脚本和输出，再按原已封存anchor核三份报告SHA并修精确枚举，第二次capture0。255原seal105直接项另有1份XML由原sealed programIdentityProof255间接绑定；252原seal68直接项另有2份XML/TXT由原sealed result.json间接绑定。三份均与原anchor字节相符，旧seal及文件未写；不能声称旧seal直接覆盖全部现存报告。

放行记录脚本完成binding/review写入后最终print变量shadow致exit1；父独核该错误时产品未启动、binding/review一致、validation0。随后仅实际启动一个产品run，不把记录错误当产品重试。

after-metadata SHA `e8b8cc9a6a8a30dfc3a71158e197edcd194ab134c2a4566d016f76a57e1af62a`记录产品后DB193339392B/SHA `444856f628fa2c41e45a27216251059a78b2f7422729246c06c37c7ee9c25253`，bootstrap358B/SHA `81c546baf9dc3e7632155d86c8ae6d657109f82f1608e01006ca97ec07f7bd8b`。只作后续fresh pair来源，不由DB大小/散列推断账本或远端效果。

## 纯恢复窗口用量

父唯一本地parser exit0，独核97原行SHA及CRLF，按原Task、generation ID+attempt+purpose及双时间排除历史，不追加provider取证。

| 已知bounded子集 | 数值 |
| --- | ---: |
| 当前APP generation attempts | 55 |
| 排除历史attempts | 42＝238的6＋241的2＋248的8＋252的26 |
| input / output / IO | 1713683 / 11625 / 1725308 |
| cache hit / miss input | 790912 / 922771 |
| 一致已知input加权cache命中率 | 46.15275987449% |
| APP占已知完整IO | 100% |
| reasoning UNKNOWN | 55条 |

55不是55个动作/批准/grant/发送；APP knownIO100%不推DEP物理调用为零。reasoning是output子集，不补零或再加到IO；完整generation、callback、物理HTTP、四probe、账单与费用UNKNOWN。bounded last100可能混历史，不能当完整用量/优化收益，纯恢复也不能并入FULL审批占比或BUSINESS通过率。summary SHA `a606e40c1b4a39f28a24e66a42dbc476a6a2e02efade2a2a1813f0ae2e3c17be`。

## 可追溯证据与停止边界

证据根：`.ai-workspace/agent-live-evidence/agent-autonomy-20261012/file-transfer-original238-compensation-256/`。关键记录为`parent-terminal-review256.json`、`parent-final-process-audit256.json`（SHA `48af35d649f70139ceb4835a9d17a354f0559f21dfb012d5d9f11d53322faabd`）、`parent-capture-failure256-attempt01.json`、`parent-retained-report-audit256.json`、`parent-release-recording-audit256.json`、`usage/parent-crosscheck256.json`。本报告只读这些已完成产物，没有重跑parser、打开DB或执行CIM/网络。256正式本地commit/seal由父后置。

257只读账本及当前Mod/problem/dispatch/settlement/远端判断PENDING，不自动再协调。历史9 FULL=FT8+CSV Inspector阶段208一次，完整BUSINESS仍0；194/234不恢复，221不自动恢复。最终目标仍为FT完整BUSINESSv2、真实浏览器及正规收尾后串行CSV；两项真实完成及必要收尾后立即暂停，分项目报告FULL审批IO/cache known/unknown，失败与纯恢复独列，等待真人方向，不自动第三项目。
