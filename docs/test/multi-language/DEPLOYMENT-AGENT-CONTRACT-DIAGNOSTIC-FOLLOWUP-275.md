# 原238合同后续诊断协调275：仍 UNKNOWN，等待独立账本核验

使用274已完成定点验证并封存的包，对原 File Transfer 238 执行一次产品协调。唯一产品 session26133 实际退出1，原任务仍 UNKNOWN，保留原 ENDED 断言失败。此记录不证明新诊断 REQUEST、APP、grant、dispatch 或远端 receipt 已成功；须由276独立读取实际产品账本确认。

原 Task 为 `072bc074-4bce-4c14-b425-f2ff965c7d40`，execution 为 `45e3a9a4-a6b8-403a-89f5-605e11eeeca0`，Runtime 为 `agent-live-file-transfer-8pqtfa76o`。本轮没有新建 FULL，不重放旧449诊断、385接续、272回滚或 READY266 安装。产品取得新整图后，只有目标已成才可走既有 guarded settlement；否则完整旧 UNKNOWN/CONTROL 诊断且尚无 followup REQUEST 时，才允许一次独立审批和新 grant 的只读 followup。所有 followup 结果都使本次协调保留 UNKNOWN，不转成修改、ACK、清理或结清授权。

## 实际前置核验

fresh byte capture 实际0，baseline SHA256 `7bdd238aa4539b966ecce98756fb2faf027b662282be141cb2484a5da4688d16`。3341源、65deployment/3AI/4platform/5完整host、54项类路径/3306文件与274封存一致。source manifest 为 `ab7772ecbb748a7c1e231d82fade6aac276759f83b834241b039b40fa90f96cc`，deployment JAR 为4120259B/`7e3370520d90a76b8c6a193a68132c781b53b606640f442ec27524d17fcb863b`。字节链不等于 JVM CodeSource 或远端效果证明。

隔离 javac 实际0，编译产生两个 class；只读公开历史 reader 实际0，before/after字节核各0、stderr0。使用 existing-only 原锁、DPAPI 和 H2 READONLY，未启动 Runtime、recovery、provider 或 SSH。原文件对及空锁保持不变。真实读到原 Mod/Task UNKNOWN，pending271/旧272 FAILED/UNKNOWN，completed0、verifiedfalse、lastAction456；旧449–456完整，安全 receipt 为 UNKNOWN/CONTROL/UNCONFIRMED/null，三项均 NOT_CHECKED。最新整图426早于456，followup REQUEST0，eligibility=false，没有在外部构造未来 binding 或 action。

预检 close 实际0，7个精确进程身份全退并删除自有空 sourcepath；独立核验含closer共8个全退。read-consumed/result/stdout/closure四份实际证据均绑定主审核；reader stdout SHA256 `a560843e7a4a7cd57d5f011981b1b46b7c8d016866ec12e7b9cd5533e5f0d1ca`。

第一次 offline 因辅助脚本要求已删除的空 sourcepath 仍存在，发生 FileNotFoundError，实际退出1；没有产品动作或 offline 输出。原失败、旧consumer及输入和更正diff保留在 `parent-corrections/removed-sourcepath275`。仅将检查改为严格解析现存父目录、精确匹配叶子文字并确认叶子不存在。旧 baseline 与 capture producer 保留历史字节，当前 consumer 独立重绑为 `195b134a6ec6c0d523d4528bfce20527734de28a639ce1f10727e35d172ce9f9`；未重跑已通过的编译和只读历史校验。

第二次 offline session61502 实际0，原 stdout/stderr 保留，stderr0。offline SHA256 `f007972b6d7042a7b46071819c35d62844df74e9993bc4950195e681dc6ee817`。父核实际输出后，单独释放一次产品，actual input SHA256 `7d8bc491ecb60133a019954e38f3173ddcea712ca281424ab0bebc22f84a6864`，逐字保留在 `parent-inputs275`。

## 实际单次协调与关闭

入口 `DeploymentStaticLiveAcceptanceTest#reconcilesOriginalTaskThroughProduct`，originalTaskOnly=true、configureRecoveryModel=false，30分钟上限，原 ENDED 断言未改。实际窗口为2026-10-17T04:28:18.9009429Z—04:30:13.5453551Z：before0、Maven1、after0，sampler Completed、无 sampler cleanup failures、originalFailurePhase=null。JUnit1例/1F/0E/0skip，actual UNKNOWN，progress=`AI_RECOVERY_ORIGINAL_HOST_VERIFIED`；两个 source-snapshot blocker 和业务结果需显式协调仍在。报告保留 `Original Mod recovery still owns resources` 关闭异常，不能称资源或业务已结清。

| 证据（相对本轮根） | SHA256 |
| --- | --- |
| `tools/original238-coordinate-275-01/result.json` | `68a324ecca91a3225b620ab8e5da4f49ef118666185d1ead223a19555f08d021` |
| `tools/original238-coordinate-275-01/maven.stdout.log`（222015B） | `27a34492413d2c1c6ff834ca6c4cef2389e3039c5ee036dc81529d17b572741d` |
| `tools/original238-coordinate-275-01/after-metadata.json` | `c8e9b920a0fb6612c17fc9cb0fa5dda98d8a077c3763a2d13d623c39a5c142cc` |
| `tools/original238-coordinate-275-01/closure.json` | `8ca1b2830dbc626e8d5b4e79b032c918398cf9f131b30f4052b574eca6430f2f` |
| `parent-finalizer-audit275.json` | `33aba2bb8f9cae84883017e3c25ee6554ad598a547f91c8c2272c94ed760f9e5` |

前后源码/JAR/CP保持274字节。原DB后态259772416B/SHA256 `4203dc7dc7528e42113f8236cb2cbba3423acd865a0a3c73c9dd63b8c52b469d`；bootstrap358B/`81c546baf9dc3e7632155d86c8ae6d657109f82f1608e01006ca97ec07f7bd8b`不变。文件变化和入口范围说明均不能代替具体产品动作证据。

normal product close 实际0，18个精确 PID/创建时间全退，两个自有 TMP/UDS 已删；main finalizer 实际0，含 capture、预检和closer共29个身份全退；独立普通非管理员 audit 实际0，含finalizer共30个身份全退，三个自有临时路径不存在，所有执行位false，无终止或原数据删除。相比272，本轮 product closer 身份已被实际记录并在后置核验中覆盖。

## 本轮恢复用量

prepare、strict summary及独立原行/上下文/身份/双时间窗/CRLF SHA/算术 audit 实际均0。98条原诊断中16条为本275的 APP generation，82条历史排除：256为15、258为6、261为5、264为7、267为12、269为11、272为26；当前重复0。不改旧 summary，不把截断窗口中的历史行数当历史完整用量。

| 本窗口已知字段 | 数值 |
| --- | ---: |
| APP输入 | 382226 |
| APP输出 | 4000 |
| APP输入＋输出 | 386226 |
| 缓存命中输入 | 199936 |
| 缓存未命中输入 | 182290 |
| 输入加权缓存命中率 | 52.30832020846305% |
| APP占本窗口已知完整IO | 100% |

summary SHA256 `afccbd96511aedb1fbcddb0a58ec8dccf6b7731825d84acf2279b105b9930a34`。没有 DEP generation 样本，16条 reasoning 字段未知；reasoning 属于 output 子集、不另加。恢复窗口单列，generation数不等于动作数、批准数或实际HTTP数；100%不等于FULL审批占比或审批通过率。完整 generation/callback/probe/HTTP/账单覆盖未知，未知字段不填0。用量脚本执行后全部授权标志已消费为false。

下一步仅276独立只读核验本轮新journal，不自动重试协调。File Transfer 后再处理 CSV Inspector；两项完整 BUSINESS v2、真实浏览器和必要收尾完成后暂停，报告 FULL APP IO/(DEP+APP IO) 及输入加权缓存命中率，失败FULL与恢复窗口分开列示。194/234不恢复，221不自动恢复，无第三项目；本轮新增FULL和完整BUSINESS通过均0。

证据根：`.ai-workspace/agent-live-evidence/agent-autonomy-20261012/file-transfer-contract-diagnostic-followup-275`。274正式源码变更及定点测试结论见274记录，本轮未运行全量测试，未推送。
