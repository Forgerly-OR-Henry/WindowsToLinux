# 四个失败项目修复与 ASSISTED 复验（2026-10-23，PAUSED_BY_USER）

**PAUSED_BY_USER。用户要求剩余流程较多时简单收尾，保存状态供后续静态部署及可能重装。** task-board、asset-lending、csv-inspector三项完整通过且已核STOPPED/DISABLED；file-transfer未完整验收。05已发布、64块200，首下载仅BEGIN而无return/SHA；父按用户要求精确停止本轮Surefire47092，Maven/wrapper97287实际exit1。这是用户中断，不是自然业务失败，无JUnit终态或完整PASS。05原RESTART已经产品恢复结清，新产品STOP已核STOPPED/DISABLED，暂停交接完成；远端数据和日志仍不等于已备份。

04的第49块超时及成功finally收尾、05的用户中断、源码冻结与业务验收分别保留。简要续接见[交接记录](ASSISTED-FOUR-FIXES-20261023-HANDOFF.md)。本报告不包含八项回归、Agent真机或全项目测试。

## 当前验收结果

| 项目 / 入口 | 已取得的实际结果 | 产品收尾 |
| --- | --- | --- |
| task-board-retest-01 | JUnit 1/0失败/0错误/0跳过，1185.687s，exec0；完整业务、HTTP、重启及管理流程通过。部署 `6097f24b-13b9-4f9f-9b93-1c33a0a45417`，app `wtl-assisted-task-board-14rq8omy4` | STOP已完成；其后独立查询曾UNKNOWN，经原查询恢复结ENDED/FAILED、blockers=[]，只读再核STOPPED/DISABLED、exit0 |
| asset-lending-retest-01 | JUnit 1/0/0/0，1081.309s，Maven/exec0；跨语言业务、HTTP、重启及持久性通过。部署 `d233999a-b489-4b57-ab6f-a743370894b3`，app `wtl-assisted-asset-lending-569jbuoec` | cleanup01产品核STOPPED/DISABLED、exec0 |
| csv-inspector-retest-01 | JUnit 1/0/0/0，986.871s，Maven/exec0；3 rows/6 issues/1 valid、worker重启PID改变、原job持久化和export通过。发布 `a76fa5c5-a03c-4485-8ef4-19daaa952d08`，app `wtl-assisted-csv-inspector-6sih3gxmw` | cleanup01产品核STOPPED/DISABLED、exec0 |
| file-transfer-retest-04 | JUnit 1/0失败/1错误/0跳过，1265.403s、exec1；primary为SocketTimeoutException: Read timed out。publication `c7d36e89-730c-4d04-a2bb-fcb1945d481f` SUCCEEDED，app `wtl-assisted-file-transfer-9zpvbig78`、state `assisted-live-file-transfer-9zpv9ukyc`；上传index48超时，完整业务未通过 | 原finally内STOP `9fd92f41-d0b3-4cbe-8bd2-dbad35450859` SUCCEEDED，后续两次inspect均STOPPED/DISABLED，无UNKNOWN或重发STOP |
| file-transfer-retest-05 | publication `3864c25a-fc06-4031-8f17-a07679067037` SUCCEEDED，app `wtl-assisted-file-transfer-ay1rdwz3k`、state `assisted-live-file-transfer-ay1rcfcb8`；64块200，首下载只有BEGIN。PAUSED_BY_USER，Surefire47092被精确停止，Maven/wrapper97287 exit1；无JUnit终态，不记自然业务失败或完整通过 | 旧cleanup01/02失败保留；原RESTART恢复ENDED/SUCCEEDED后，cleanup03新STOP成功核STOPPED/DISABLED，exit0 |

对应 `.log/.xml`、原管理导出和独立cleanup日志保存在证据根 `.ai-workspace/evidence/assisted-four-fixes-20261023/`（下文辅助路径均相对该根）。原导出保持当时的RUNNING/DISABLED，不改写成后续收尾状态。CSV导出SHA为 `ce0c21b9e1b5ebae4a7ac2491dd2d438932d194c344031d135f147bd3592cfff`；modelCalls=66是fixture调用统计，不能当最终物理尝试或Token总量。三个完整通过结果也不证明各项目历史故障根因均已消除。

## 原四项故障：确认事实、修复与未知项

| 项目 | 已确认与本轮修复 | 仍不能确认 |
| --- | --- | --- |
| task-board | helper安装被独立审批DENY；BODY摘要与完整带回执stdin摘要本来不同，不是篡改证明。明确绑定body/request/helper来源，安装器改为临时文件核验后串行发布，发布验证/探测成功才写成功回执 | 不把审批拒绝概括成服务器安装失败；后续完整通过另列 |
| file-transfer | 原SSH安装会话约58.5s关闭且无退出状态，远未到原7200000ms期限；原恢复声明helper-only却读取额外回执。修复实际读取范围和原task/server/operation/request/receipt绑定。原安装观察为SUCCEEDED，原部署 `d23bc3f3-129e-4679-88c0-5a97b5bbe582` 经产品结ENDED/FAILED，未重放安装 | SSH提前关闭的底层原因仍未知 |
| asset-lending | 原RESTART遇systemd消息总线接收方断开，后报unit-fragment。查询失败单列观察不可用；只读查询最多三次、1/2s间隔且不超原期限，身份冲突继续拒绝，不重复生命周期动作 | 没有证据证明历史消息总线故障由OOM等特定原因引起 |
| csv-inspector | 原apt-get非零/helper exit65且缺stderr。补实际命令阶段、executable、退出码、timeout/cancel、脱敏stdout/stderr各最多16KiB及截断标记，经独立审批读取原请求诊断 | 新PHP准备SUCCEEDED/exit0，旧apt失败未复现；锁、网络或依赖原因均未证实 |

CSV三次新增准备诊断与完整验收分开：preparation01漏setup marker，PHP尚未发送，原task经新helper-only审批结ENDED/FAILED；02的checkpoint key实际68字符超过64上限，保留完整UUID身份去连字符后修至64，原task同样FAILED结清，未重放安装；03本地执行中断、缺退出码时保留UNKNOWN，后经新独立审批的原operation诊断确认PHP准备SUCCEEDED/exit0，再按完整作者checkpoint集合和实际资源判据结原task `c4aa1515-55cb-46eb-9b16-57bb0bcf2335` 为ENDED/FAILED、blockers=[]。extra operation/resource、拒绝和身份不符仍拒绝收尾。只读观察aux exit2来自既有UNKNOWN资源关闭保护，不是DB损坏；最终原结清aux0。见 `csv-preparation-01/02/03*.log`、`csv-original-reconcile-01.log`、`csv-preparation-03-original-conclusion-01.log`，失败/中断/拒绝历史均保留。

## 文件传输：失败、恢复、业务和清理分阶段

| 阶段 | 实际结论与证据 |
| --- | --- |
| 完整01 | JUnit 1/1失败/0错误/0跳过，294.577s；上传进度9406B后SFTP_NOT_VERIFIED，候选清理未核清使graph保守UNKNOWN。原task `c9cc0696-0ae8-4950-b48b-355fdc643f66`，旧安全phase/provider/status/transferId未留存，不能补造或推断底层SFTP原因 |
| 01候选补偿 | 原恢复仍UNKNOWN，旧观察REFUSED/other-root-execution-chain的candidatePresent=false不是不存在证明。唯一新后继经新观察、明确确认、独立审批/单次许可核精确候选，持久SUCCEEDED回执candidatePresent=false/quiescent=true；原task最终ENDED/FAILED、blockers=[]、exit0，未重构建/发布。见compensation-01及successor-01日志/receipt |
| 完整02基础入口 | JUnit 1/0/0/0，1084.294s、exit0，但ASSISTED分支实际仅65536B；当时称64MiB不准确。保留app `wtl-assisted-file-transfer-48mcjw8nc` 原导出，后续同app补业务，不把基础通过算正式规模通过 |
| 02正式补充01 | 原64MiB/1MiB流程在前16个分块循环的响应头读取超时，aux1，无完整marker；共用代码行不能证明具体chunk序号。原upload9ccb…后续只读仍receiving/chunks=[]，未重传旧块；cleanup01产品核STOPPED/DISABLED、exit0 |
| 单次START与后续query | 原START `783f007d-c597-4b18-8c68-3826d50af767` 已归档SUCCEEDED/management FINISHED；辅助exit1属于后续inspect超时，不能重发START。原query `a2710088-5ba1-4829-b3e9-2621e8591f6d` 后经registered reconcile结ENDED/FAILED、INTERRUPTED、blockers=[]、aux0。没有以health200代原query终态 |
| 02正式补充02 | 64个chunk全200（响应头最大17644ms/中位2939.5ms）、部分上传重启、10并发complete和首版本通过；首次下载自然短EOF仅18828509/67108864B，aux1，未到第二版本/取消/真实502。原版本只读实测完整64MiB/原SHA、uid987/inode/mtime稳定，故传输截断已证，Go/Node唯一原因当时未证。cleanup02产品核STOPPED/DISABLED、exit0 |
| 完整03 | source06下新ASSISTED入口发布root `1de11bc2-a4f2-4be2-aa1a-9dfd3cb913f5` SUCCEEDED、app `wtl-assisted-file-transfer-7m859btls`；64chunk全200后业务未完成。JUnit 1/1失败/0错误/0跳过，1120.253s、exec1；finally中的STOP UNKNOWN覆盖原business异常，原异常正文不可恢复，不能推断其具体断言 |
| 03原STOP恢复与独立cleanup | 原STOP `ae2127ee-bd4c-4a28-8267-1c42c5dfc2fe` 经产品恢复03结ENDED/FAILED、DEPLOYMENT_MANAGEMENT_NOT_STARTED、blockers=[]、aux0，未SSH/重发生命周期。之后独立cleanup01核同app/revision STOPPED/DISABLED、exec0，发布成功和业务失败均保留 |
| 完整04 | 10:22Z启动，source07/原LOW配置；16块上传及重启后状态保留通过，续传到index47均200；index48等待响应头20001ms超时，JUnit error1/exec1，未到complete/下载/第二版本。原finally STOP及两次观察均成功STOPPED/DISABLED，业务失败保留 |
| 完整05 | source08下发布SUCCEEDED、64块200，首下载仅BEGIN；用户要求暂停后精确停止本轮Surefire，wrapper97287 exit1，无JUnit终态。不能改成自然业务FAILED或完整PASS；原模型/审批/预算/超时/规模/无retry保持，远端收尾另待 |

01后继补偿回执operation为 `2663c71e-2701-358e-ac5c-9907ec6c3dd3`，receipt SHA `f6c386df39ef5d87f80f7a12e2438e9f75ce1c1d7ce5a0b868890b174a6585c5`。以最终STATIC_LIVE_RECONCILED、持久回执、PRODUCT_ORIGINAL_RECOVERY_COMPLETE及exit0为结清依据；早期PUBLIC_RESULT UNKNOWN仍保留，不能覆盖后续终态。SFTP新诊断只记录实际task/outcome/event和严格白名单安全元数据，不存problem正文或任意context，logger失败不改变业务结果，不为01追补历史字段。

### 原03对象的追加只读下载证据

原对象version `444ac7d0bf5ab828f69c68c2f9ff250c` 的metadata是completed，声明64MiB及原SHA。初次业务journal记录Go写失败acceptedBodyBytes=10510949、`*net.OpError timeout=false`，不足以证明该次是Go idle超时。

诊断GET01额外设120s总cap，120005ms收到16616992B、末次接收距退出8ms、exit3；这是诊断主动结束，不能当原客户端合同复现。随后另行授权的GET02移除该诊断cap，其余仍是Proxy.NO_PROXY、connect10000/read20000、32KiB buffer、同URL/对象/输入SHA，无重试。HTTP200/Content-Length67108864/keep-alive，405281ms自然EOF只收46624797B，未抛read异常，exit2；实际SHA `075a9b6ffdd1c3fef55c92ae45797e151d755092828c43faeb6fcadacd8b9196` 不符预期。

`file-transfer-03-business-read-only-03.log` 的09:38:22Z后端journal同时记录该version acceptedBodyBytes=46624797、`*net.OpError timeout=true`，与GET02短EOF字节吻合，支持这次backend写失败与截断对应。它不恢复原03被覆盖的异常，也不把原timeout=false或其他历史故障改判同因。两个GET均已退出并关闭自有连接，没有POST、重部署或重传。

### 完整04：上传响应头超时，原异常完整保留

原 `file-transfer-retest-04.log` SHA为 `491e9312d7942e3cbb533b358b078075a7a4159174dc28d04515be1440a8a33a`。本轮新upload `3915daf8ecc6be4a0ba829681f624454` 仍为64MiB/1MiB分块：前16块全200，产品重启后原16块状态保留；续传到index47均200。index48（第49块）于10:41:03.351316Z进入RESPONSE_WAIT，10:41:23.353Z报SocketTimeoutException，等候20001ms、无响应头。本地write约2ms返回只证明交给本地缓冲，不证明已完整送达服务器。此次finally修复生效，JUnit保留原Read timed out为primary，不再被收尾错误覆盖；未进入complete、下载、第二版本或管理成功导出。

原finally中的产品STOP由RUNNING变STOPPED，task `9fd92f41-d0b3-4cbe-8bd2-dbad35450859` SUCCEEDED；后续inspect `28867199-390c-492b-ae2c-45b6efee79c7`、`5fc7d94b-dfa9-4c67-8257-1be6be554bde` 均STOPPED/DISABLED。`tools/file-transfer-04-finally-stop-receipt-01.json` 仅摘录原run实际结果，并非另发STOP或补造成功管理导出。身份为revision `08d7fbf69bc2dc6d2c3faab2111c1fa0b32b8b12f72d5799ac8500be343e5694`、ownership `d362c6a2182915e4c1d23f9c44aa5e1b04e9f52c177837ea0b6861a1a9c70df9`，原应用数据/发布保留。

父已另行授权只读取证，`file-transfer-04-upload-read-only-01.log` actual exit0：只查这两个unit的show/journal及精确原upload的chunks。两unit MainPID0/inactive，物理分块恰0–47、各1048576B/uid979，无48；这不能证明数据库commit，也没有重启、重传。旧journal缺上传相位日志，尚不能定位请求体未收全、后端处理或响应传播中的具体停点。

同轮chunk45/46/47本地write仅2/2/3ms，等待200仍分别13.493/5.676/8.608s，说明本地write耗时不能当线上传输耗时。20s响应等待可能包含实际上传和后端处理；1MiB若实际平均链路低于约51.2KiB/s，即可能耗尽原20s，现有证据既不能确认环境原因，也不能保证必有代码修复。下载客户端30秒合同不适用于上传；Go全局30s、Node30s和Java20s均未调整。

### 完整05前新增上传相位观察

新改动只补可区分收body与收齐后处理的被动诊断。Go记录sourceRead/stagingWrite调用耗时、Sync、metadata、reply阶段及ctx/errorType，单请求最多32条；Node仅对32位小写hex upload及安全chunk路径记录incoming body/结束、本地OS finish、响应头和cancel，最多12条。data计数器在原pipe之后挂接，unpipe/终态移除；不记录payload/header/任意错误正文或路径，不硬编码UID，不改变锁、落盘/提交顺序、超时、规模、重试或审核。调用耗时不是纯线上传输时间，日志也不是客户端回执；04真实原因仍未知。

作者冻结五源清单 `tools/file-transfer-upload-observation-01/source-sha256.json`，SHA `492e79bd5201d2148296d4baf3b1751b7e8142c730f5babc88206e53b08f469b`。本地Go3个指定方法通过，真实Node→Go取消链路在32768B时ctxCancelled=true/committedChunks=0，原destroy两次保持、unpipe后data observer为0；新Node相位方法通过。首轮Node测试错误假设固定502，实际既有链路可ECONNRESET，后仅改测试接受原失败结果，02通过，没有生产fallback。server-only TypeScript5.9.3固定依赖编译exit0、五输入零漂移；Go root `go test -mod=readonly . -run ^$` exit0/0测试。没有重跑client/browser/GET或旧组，所有本机证据不等于05通过。

05当前已发布身份为revision `4419245542a25cee77b572beb937683ddc0eaf8debba57bf2d5b648c9eb7003b`、ownership `e016476951696a9bb9cebed221bb94832c3d6b0311cf010c03c0545fee0cbe9d`。发布root SUCCEEDED和分块HTTP200属于局部进展，本身不证明原重启任务终态或完整业务；原重启及最终STOP另由后续产品恢复/cleanup03证实。11:25:05Z原05 upload982f…的indices0–63全部64×1MiB HTTP200，进入FILE_TRANSFER_DOWNLOAD_BEGIN，version `01a1a17dde8b3c332fc9297fbe02c616`、expectedBytes67108864；尚无download return/SHA或V2完成，不据此称04上传根因已修复。

随后按用户指示暂停完整V2。当前首下载没有返回/SHA；其后仍有第二版本64MiB上传、另4次完整下载校验、取消和重启持久化、错误SHA拒绝、真实后端停/启及downstream502、待处理会话恢复、原管理导出等流程，不能称只剩简单收尾。用户中断不会补成JUnit失败或完整业务结论，尚未完成的步骤继续留空。

暂停收尾现已完成：inventory唯一原RESTART `1c3d7b34-4e02-42a3-8820-98a1b201bc37` 在中断后为UNKNOWN；`tools/file-transfer-05-observation/inventory-local-01.log` 实际exit2，表示清单读取成功但close按原资源占用保留。随后只对这一条调用产品RECOVERY，`file-transfer-05-original-restart-reconcile-01.log` actual0：ENDED/SUCCEEDED、blockerCount=0，原恢复release/close完成、newLifecycleSubmissions=0，没有重发RESTART。

再以正常产品cleanup03单独提交新STOP `f24658bc-ba03-40ea-8880-e8ad79dbb9f6`，前inspect `cdf7808f-24c4-4f5c-9fa3-00cfece74b69`、后inspect `84af1484-88b8-4e18-af09-19e7d57a6e9e`，actual0、最终STOPPED/DISABLED。独立回执 `tools/file-transfer-05-user-requested-cleanup-receipt-01.json` SHA `70f3b525b9104cb34d89fb2cddeca3e00e41b053ebab7300fd2a7c16574a5656`，是收尾摘要而非完整管理导出；旧cleanup01 wrongcwd、02 MOD_RECOVERY_READY/UNKNOWN保留但已不阻塞移交。通用status仍把简写原恢复行标UNCONFIRMED，是格式未识别，真实结清依据上述原task/回执及正常release/close，不据摘要器否认终态。

`file-transfer-05-user-pause-read-only-01.log` actual0保存了正确fingerprint、两原unit路径、212条Go/40条Node安全日志（0reject/0truncation），无远端SQL/文件/服务写入；其中PID状态采于停服前，不能当作停服后PID证据。后续STOPPED/DISABLED依据cleanup03的产品结果，未删除原数据/发布。

## 修复摘要与验证边界

fixture的ASSISTED finally现保留原acceptance异常身份/类型/栈，把closingFailure加为suppressed；无原异常仍抛closingFailure，同对象不自抑制；Agent isolation路径及UNKNOWN资源保护保留。4个实际共享逻辑定点方法通过，这是诊断修复，不能改写03业务结果。Java下载阶段日志和Node被动终止顺序日志用于识别收到的字节、背压及首个本地观察事件，均不把本地事件顺序/缓冲字节说成客户端收到或网络唯一根因。

早期Go方案改为逐Write续同长deadline，历史本地通过但新完整03仍失败。用户随后明确选择**最终客户端下载方按连续30秒无非空body进展取消**：浏览器独立AbortController，仅非空read续期，响应头/空块不续期；完整EOF、长度和SHA均成立才生成Blob保存。Node仅在已确认成功下载响应体路径取消会因背压误触发的上游socket期限；Go只在有效下载路径清除继承的写deadline，保留写失败诊断/取消传播。其他路由、连接/响应头等既有期限和Java验收connect10/read20不变，没有增加产品运行依赖。裸API调用方也需实现30秒或更严格的空闲取消；浏览器冻结/主线程阻塞时无法保证墙钟实时调度。

固定TypeScript5.9.3/原lock构建exit0；实际Chrome156 UI中64MiB保存字节/SHA通过，短EOF/错SHA均无保存，按钮恢复。独立真实停滞观测最后非空read至原生abort=30000.0ms，原请求ERR_ABORTED，0保存；四场景原35.6s是检查时刻而非触发时刻。Node helper9叶场景、代理2方法、Go2方法/5叶场景及main仅编译、finally4方法均有直接日志；既有产品修复Java/Python、各次失败修正和工具契约详见[本地验证记录](ASSISTED-FOUR-FIXES-20261023-LOCAL-VALIDATION.md)。不累计重跑成唯一总数，不表述为全套通过。

旧本机小socket缓冲/人工500ms期限探针未形成预期backend背压，测试exit1、MECHANISM_NOT_REPRODUCED；backend已接受完整字节，但客户端只读小部分，未自然EOF/验完整SHA即主动清理。该负例和早期Windows Connection:close差异均不能当远端因果证明；日志/清理证据保留，不通过调参数反复试到预期结果。

## 主机、配置和源码来源

初始只读核验 `preflight-01.log`/`original-resources-02.log` 绑定服务器45.192.105.213:22及指纹 `SHA256:vXPqXzyMykqjCaD8J8k0cBUZKwyp9TCdJnh7+McB5wk`；根分区可用22774788096B，未见网站监听，旧asset单元inactive/MainPID0、身份/标记/current/FragmentPath已核。旧asset已停止禁自启，其他三项没有发布服务，因此未触发条件性完整删除；没有删公共helper/工具链或旧数据证据。04前新预检只见SSH/DNS监听、可用约21.68GB，不能替代04之后的最终环境观察。安装、构建、发布、业务写入、生命周期和原任务结清均由产品负责，监督侧没有手工SSH部署或改SQL结清。

产品部署及独立审批使用deepseek-flash、LOW/LOW，端点https://api.deepseek.com/chat/completions；普通/扩展/审批生产输出预算32768/65536/65536、审批600s。本轮live请求上限65536、每站已知Token观察阈值1500000、等待90min，阈值不是提供方总量。独立审批和单次许可未放宽。Codex协作按gpt-6-1-sol-router使用Sol High/XHigh；Astra High仅专项恢复/身份/后继补偿及下载合同因果判断，不常驻终审，不使用Ultra；与真实DeepSeek调用分账。

04的历史来源为source07共2361路径，启动前及Maven后逐项零漂移，证据 `tools/source07-post-maven-start-check-01.json`。`execution-source-07-verified.json` SHA `0c4615ea582c43aeb0db6908a862a561926518656198086a2ed1c6aeffd7467b` 只纠正原07继承摘要，files未变；原07 SHA `2bf7bf48efd855047eb2415424bc59a3f25daf4e40c4f443c6388399a2058eca`保留。旧source01–07不覆盖，也不把metadata修订记为新部署。

05使用 `execution-source-08.json`，SHA `f27042f8dcacc3cad340556710def68c355d07e75d1af179e83c3022766e1a8c`；2363路径在启动前及Maven后均逐项零漂移，后者见 `tools/source08-post-maven-start-check-01.json`。sample34文件/150755B，快照manifest SHA `e27fbff9fd683b2a9db8e980896ac3c93ca99ad3b323da29f456a3ed5d9456ab`。只同步六个f24/test普通样例副本：Go http/uploads、Node server和README四旧文件，另两份新上传测试；已保存旧bytes/新absence，4 changed+2 new，范围外依赖漂移0，非junction。README仅补相位含义及各轮ASSISTED真实报告链接，原bytes留存；不是功能或验收通过修改。

Java fixture和四环境/API适配、公共helper16/SHA `4b44004b76783ff13c7698d54b19a9af2fda640d20b15841755a504724be0d3c`、补偿guard `12693e8ace3f7943dd8df02e6ed74701f8e75790ed9bde26611a959e96a565c7` 均沿用07。适配仅原credentialFile引用、数据父目录realpath、profile七参数null/null、assignment enabled=true，不复制密钥/数据库或改预算；Maven实际换行hash与主树分开记录。05前预检指纹仍匹配、可用21577596928B，旧units无活动网站监听、exit0；不能代05之后的最终观察。

源码增量基于本轮编辑前混合现场逐文件原bytes，**不是原HEAD独立可构建补丁**。configureLiveModel其他任务迁移只在delivery projection恢复原baseline方法，物理源不改。主树Approval/Scope/PublishPolicyContext三处外部改动已排除，f24实际仍等于candidate07旧target；证据 `tools/source07-scope-drift-02/comparison.json`，SHA `e5228678b98410328b4b3d97f1551741290a2480f14c4a4c5166b117c1d0737a`。candidate08以三处精确frozenTargetOverrides绑定已验证旧target，不吸收无关迁移，不覆盖主树。

10:25Z后主fixture的configureLiveModel又被其他任务修改：source07冻结main SHA为 `362cc6d6270fc98ca9c4db150ed8e4ee0e205f21d7a7ec195fbbe71413e25845`，当时主树变为 `d28c17ce83fdc16964cbf17fd0bbeebc050a5874c93ee1e40014e5e82373496a`。逆四适配重建证明方法外原bytes全同，见 `tools/source07-fixture-projection-drift-01/comparison.json`；沿既有方法投影排除，不修改物理main/f24，不改变04来源或重跑部署。因此“主树与执行来源一致”仅适用于扣除上述明确排除项、环境适配及换行差异的对应范围。

source08的[正式patch](patches/ASSISTED-FOUR-FIXES-20261023.patch)与[正式SOURCE](ASSISTED-FOUR-FIXES-20261023-SOURCE.json)已由父冻结并替换：79文件（54旧/25新）、695328B，patch SHA `51de3b841d82a5efe9c526c6e05077322ebd6803dd53f0558dc346fecba95f2e`，SOURCE SHA `f13a108a6ff9bd84af89425a6f46148fa2f0037c88c65d5395ae5396ba013019`。`tools/source-delta-final-source08-01/` 的actual final仅指该源码快照：真实apply均0、当前源及输入证据无漂移，不证明四项业务通过。旧55正式文件移存 `tools/formal-artifacts-before-source08-01/`，不删除历史；candidate08及更早候选亦保留。

candidate09原记录仍是UNFROZEN/final=false历史，不追改成final：80归属/79入patch，原生apply/79目标bytes、185输入无漂移已验证。三policy旧tested目标及fixture仅cfg投影/唯一if换行target6eca30a6…2b6006b的排除边界沿用于正式源码交付，物理main/f24不改。辅助验证01/02的schema-null/LF-CRLF断言缺口及cleanup01不可用CIM记录，分别由验证03和cleanup02纠正，不归业务失败；详见LOCAL。正式源码冻结另有新目录及SOURCE，不覆盖候选历史。

## 暂停交接与实际用量

本轮完整V2按用户要求暂停，三项完整PASS、file-transfer未完整验收；05远端原任务恢复和产品STOP/禁自启已完成，可移交这一暂停状态。无JUnit终态的用户中断不改记自然业务失败，也不补原完整管理成功导出。

`usage-audit-user-pause-01.log` actual0，共12/12本地已有保护库按共享只读打开；`usage-user-pause-01.json` 合并原stdout及持久记录，按generation ID+attempt去重，561条可识别物理尝试均在双方匹配（stdoutOnly=0、persistedOnly=0）。只计cutoff `2026-10-22T20:01:07.1162968Z`（1792699267116ms）后本轮记录，失败/UNKNOWN/恢复/补偿/各轮完整与补充入口/cleanup阶段独立保留。

| 可核计量 | 暂停时已知值 |
| --- | ---: |
| 输入Token | 5,062,722 |
| 输出Token | 492,393 |
| 输入+输出 | 5,555,115 |
| 缓存命中输入（输入子集） | 2,740,479 |
| 缓存未命中输入（输入子集） | 2,322,243 |
| 推理输出（输出子集） | 421,238 |
| Token/物理请求未知的能力探测 | 44 |

缓存/推理不再叠加到输入+输出。额外请求数、物理请求总量、账单金额和Codex自身用量均未知/未包含，不能把561或已知Token说成提供方账单总量。审计 `persistentAuditComplete=true`、`finalTaskProcessCheckVerified=true`，unresolvedOriginalTaskIds/runningOrUnconfirmedProcesses/unavailableUsageRecords均空；`allFourFullRetestsCompleted=false`、`readyForFinalAccounting=false` 因05用户中断而保持原值，不为交接改成true。

独立normal-Windows CIM `process-observation-user-pause-01.json` actual0，11:44:15Z queryCompleted=true、matchingProcessCount=0，证明声明范围内当时无可识别本轮本地进程；不是全服务器或永久保证，也不推断业务成功。CSV旧preparation03原PID/exit仍不可恢复，CIM只证明现范围内进程缺席，不补exit0。此前Parser+5case、局部CIM和失败CIM记录保留，不能替代此次独立观察。

本地证据和DPAPI保护state保留于证据根及 `src/app/db/data`，不复制/打印凭证；**不是远端数据/日志备份**。若要保留远端数据/证据，重装前须另备份；重装后旧远端证据无法补查。重新部署时重新核fingerprint、产品helper/工具链和新审批/许可，不沿用旧grant或重放原部署。

source08正式源码冻结不变（patch51de…95f2e / SOURCEf13a…3019），本轮本地提交范围限定为patch/SOURCE/main/LOCAL/HANDOFF五个正式文件，不推送；实际提交号保存在项目进度入口 `.ai-workspace/progress/CURRENT.md`。当前暂停交接完成，未继续V2或扩大测试。
