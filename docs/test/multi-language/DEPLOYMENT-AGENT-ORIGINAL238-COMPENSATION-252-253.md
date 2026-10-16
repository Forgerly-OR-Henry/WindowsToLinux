# 原238单次补偿接续：252—254结果与拒因

日期：2026-10-17。**252唯一产品恢复已结束，原Task仍UNKNOWN；253真实只读账本已证安装APP DENY、NOT_SENT，无安装grant或dispatch，业务补偿尚未发送。254单条脱敏日志进一步确认APP称未能完整审阅分页可执行内容。** 日志本身被截断，尚不能据此认定APP误判或安装程序存在漏洞。251实现及本地定点验证边界见[251本地修正版](DEPLOYMENT-AGENT-FIRST-PARTIAL-COMPENSATION-251.md)；该实现记录已本地提交 `d19887d6e19b4e0980cecaad8fcf7634cb7300bd` 并封存，本地44例通过不等于实服通过。

## 252实际结果

唯一run为 `original238-compensate-252-01`、session38624；唯一原Task `072bc074-4bce-4c14-b425-f2ff965c7d40`，Runtime `src/app/db/data/agent-live-file-transfer-8pqtfa76o`，预期原App身份 `wtl-agent-file-transfer-8pqtgvbwc`（不是正式App已存在的证明）。入口仅 `DeploymentStaticLiveAcceptanceTest#reconcilesOriginalTaskThroughProduct`，originalTaskOnly=true、originalPreparationOnly=false、configureAgentRecoveryModel=false。

| 证据 | 实际结果 |
| --- | --- |
| UTC窗口 | 2026-10-16T21:03:32.3075341Z—2026-10-16T21:05:42.4090093Z |
| Maven / 源码前后守门 | Maven1；before/afterSource均0 |
| JUnit | 1F/0E/0skip，suite112.4s；原ENDED断言未改 |
| 原Task | UNKNOWN；进度为AI_RECOVERY_ORIGINAL_HOST_VERIFIED；随后253建立的阻塞证据见下文 |
| 正常收尾 | close0；15个精确PID/创建时间pairs全退，仅清2个本run自有TMP/UDS目录；Runtime保留，执行开关均false |

范围允许原Task在逐动作独立APP＋fresh grant下执行有界native-first partial补偿，并在原生正式门禁满足后FAILED收尾/归档；也允许保持UNKNOWN。实际没有自动重试、新FULL或其它Task恢复，Root直接SSH=0；不恢复194/234，不自动恢复221，不额外clear/archive。JUnit失败不等于原Task已ENDED/FAILED，进度文字也不证明动作批准、派发或远端效果。

252前后源码3319条、六JAR、54个CP入口/3248成员冻结检查通过；manifest SHA256 `088988fb02ef6b9687378cbffb00f1ab3704167bce9d20dfeeab0b79e1285160`。原Runtime的after DB为170356736B，SHA256 `abda3a07bef33f8aefe3b97467c749f4f9de1f77df89f3593ee16aa2495e32d7`；bootstrap仍358B，SHA256 `81c546baf9dc3e7632155d86c8ae6d657109f82f1608e01006ca97ec07f7bd8b`。原空锁仍为空，创建时间 `2026-10-16T17:03:17.5271606Z` 守门通过。DB字节变化不证明补偿已发、资源释放或归档成功。

证据根：`.ai-workspace/agent-live-evidence/agent-autonomy-20261012/file-transfer-original238-compensation-252/`。终态及收尾见 `parent-terminal-audit252.json`、run目录的 `result.json`、`after-metadata.json`、`closure.json`。result SHA256 `749542619b61379eb8cc717c8e8d0aa97d58b7098d9f29a480453858028108c6`；stdout220040B，SHA256 `7fc7fb8883605ed37ec9af0ce9a527e0c9aef76a1ce8fbabfa1ea7fbb5777fde`。本文未读取DB或raw模型正文。

## 纯恢复用量，独立于FULL

随后唯一本地解析exit0；用量以 `usage/usage-summary252.json` 及 `usage/parent-crosscheck252.json` 为准。父独核97条原行SHA及CRLF：本窗口去重26条generation attempts，全为APPROVAL；严格排除原238的61、241的2、248的8条历史attempts，共71条。

| 当前已知子集 | 数值 |
| --- | ---: |
| input / output tokens | 720550 / 7656 |
| 已知input＋output | 728206 |
| cache hit / miss input tokens | 379008 / 341542 |
| 已知一致input加权cache命中率 | 52.5998195823% |
| APP占已知完整input＋output | 100% |
| reasoning子计数UNKNOWN | 26条 |

26条generation outcome均SUCCEEDED，只表示生成成功，不等于APP批准、26个动作/发送或Task成功。APP已知I/O占100%不能推出DEP物理调用为零；97行不是97次当前调用。reasoning属于output子集，未知不填0、不再加到728206。bounded last100不证明完整generation/callback覆盖；物理HTTP、四probe用量、全账单和费用仍UNKNOWN，不推费用或优化收益。summary SHA256 `f0c9d48cc328bd407e97e52a3f16f2a873b9681f064d27d0f08ca4ddc351273e`。

## 253真实只读核账

证据根为 `.ai-workspace/agent-live-evidence/agent-autonomy-20261012/file-transfer-post-compensation-ledger-253/`。fresh capture0，baseline SHA256 `0c1fb35806c03d8b1b24197bce860867a3416b8019d49540eb500de702e4f044`；隔离javac0，产出2个class，stderr0。唯一 `local-ledger-253-read-01` reader实际0、stderr0：使用原有空锁、既有bootstrap本地解密、H2 IFEXISTS及只读连接、有限SELECT；没有Runtime恢复、模型调用或SSH。锁内及关闭后DB/bootstrap逐字不变，源码/JAR/CP前后检查通过。normal finalizer0核compiler、reader及launcher共4个精确PID/创建时间pairs全退，清除自有空sourcepath；执行开关已撤回false。

| 真实账本事实 | 结果 |
| --- | --- |
| 原平台Task / Mod task | UNKNOWN；平台问题为UNKNOWN / DEPLOYMENT_AGENT_FIRST_PUBLICATION_COMPENSATION_UNCONFIRMED |
| 事件 / effectsSettled | 207 / false；没有COMPENSATION_PLAN |
| 资源 | 9376条，9371已释放，5条保留；3条原server引用仍在 |
| 正式App / component / release / receipt | 均0；不等于远端资源为0 |
| 新固定程序readiness | REQUEST 226、APPROVE 227、fresh grant 228、DISPATCH 229、RESULT 230 ACKNOWLEDGED / MISSING |
| 新固定程序install | REQUEST 231、REVIEW 232 DENY、RESULT 233 NOT_SENT / UNKNOWN；无grant、无dispatch |
| 安装typed state | VALID，ready=false、mayInstall=false、installIntentPresent=true；最新attempt reviewed=true、granted=false、dispatched=false、ended=true |

readiness action为 `9cfe1c6e-3f22-4153-936c-b8508bbf11ef`，install action为 `9ecbb423-2c7b-435a-86f7-7636620dc086`。OPEN 225冻结source SHA256 `477ca1395417bbf1ec024c7e427066775da8d7861f2d2ee759896d598cd59ca2`、version SHA256 `4bce53b129711ad351344107e8f298b2588c20f0ec82f9856d9b5e3b19eb2021`、binding SHA256 `8e86a64e8d66c2834793fe36b8bf46de265044fc5690a2902fd470ece6204c80`。9个程序帧不等于9次部署；26条APP生成不等于26个获批动作。

唯一匹配的非AI失败日志 `d1923abd-5119-4669-9c58-6a00e03186a5`，recordedAt `1792184735299`，事件 `DEPLOYMENT_AGENT_FAILURE`，异常ApiException，问题 `ACCESS_DENIED / DEPLOYMENT_COMMAND_REVIEW_DENIED`。253仅输出message长度526字符、786 UTF-8字节及SHA256 `1bdd3be494bfbd0aec953878270f7879ec0515683489dffbd14f78ae15d327d8`；details为NULL，APP reason SHA256 `e997124043152f7966f9e596651e5fcce37ec8936de896bfb257c3d0d9169c86`。拒绝文字未由253明文读取，不能提前归因为具体源码缺陷或APP误判。

stdout SHA256 `30dbc7af6132fcfaf20a87d1c947352a7fcadb8ff3c7d3ca55f51b51958e48b8`；终态独核见 `parent-terminal-audit253.json` 和 `parent-final-process-verification253.json`。本地账本不是新的远端状态探测。保留原UNKNOWN、业务引用及所有拒绝证据，未自动重试安装或业务补偿。

随后代码只读检查还发现：当前Installation在INSTALL REQUEST时即永久记入installAction，DENY＋NOT_SENT且无grant/dispatch也不解除阻断；OPEN冻结版本及review绑定。该策略比“防止可能已发送的安装重发”更严格。若要修正证据后重新独立申请，需要明确区分已知未发送与已发送UNKNOWN；尚未实施修复，先核真实拒因，不放宽独立APP或fresh grant门禁。

## 254单条脱敏拒因

新根 `file-transfer-install-denial-reason-254/` 仅查询上述固定log/task/owner/version/category/event/recordedAt的message，不取details或其它日志正文。先核526字符、786 UTF-8字节及原SHA，再输出有界脱敏文本；密码、API key赋值、Bearer/sk-、长token和控制符按保守模式过滤。该过滤不声称识别任意自定义秘密。

首启动壳在Java前因StrictMode下FileInfo.Parent不存在退出，改用Directory后才启动首reader。首reader实际1、安全退出，未输出理由；其新增的空锁散列读取遇IOException，疑似与Windows独占FileLock冲突。原253成功工具只在锁内核空长度和创建身份，254 attempt02恢复该模式；壳仍在锁前/后核空锁SHA。两个源码、编译和失败证据分别保留，不删除claim复用入口。

attempt02隔离javac0、2个class，唯一reader实际0、stderr0、源码/JAR/CP/原DB pair前后不变，锁内及关闭后pair守门通过。脱敏理由称：189420字节stdin内嵌165726字节controller，涉及root安装以及原生DAEMON回滚；必须完整审阅installer、loader及回滚本体，但现有证据分页只看到了部分内容。**原日志文字到“completeExecutableInput 仅”即终止，后续理由未知**，不补造被截文字，不将APP关于程序行为的描述当作已核实的执行事实。

成功stdout SHA256 `99ea7baf83ece7f367d83b67501443a20db99f1861e534a5641edd3c1763cba4`，原理由摘要守门仍为 `1bdd3be494bfbd0aec953878270f7879ec0515683489dffbd14f78ae15d327d8`；脱敏2处，safe reason SHA256 `ee30088e03485d05ec00bb3b5b35a472ace16578104e3f026aff2fc2b883a28b`。normal close0核两组compiler/reader及launcher共8个精确PID/创建时间pairs全退、两自有空sourcepath删除、所有reader flags false，原Runtime保留。两次均无模型、SSH或恢复动作。本轮没有改变程序、审批、安装重试合同或发送新的安装请求；接下来只读定位完整证据为何不可审阅。

本次252纯恢复新增FULL和完整BUSINESS均0。历史9次FULL＝File Transfer8次＋CSV Inspector阶段208一次，完整BUSINESS仍0；CSV当前接续新轮排队，不能说历史从未启动。File Transfer、CSV严格逐个完成真实完整业务及必要正式收尾后立即暂停，按项目分别报告FULL审批占比、cache已知/未知，失败和纯恢复窗口独列；等用户决定，不自动第三项目。
