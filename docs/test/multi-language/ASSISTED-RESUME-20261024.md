# 重装后 ASSISTED 文件传输续验

文件传输第五次新机完整 ASSISTED 验收 PASS：五次64MiB下载、两版本、十并发complete、重启持久化、取消/错误SHA、真实后端502及组件恢复均完成；Maven actual0、JUnit 1项通过，实际管理导出已生成，之后独立产品STOP核验STOPPED/DISABLED。最终用量和全归档审计已绑定，所属验证及只读审计进程均已退出。前四轮FAILED及第四轮UNKNOWN→原任务核对→独立清理保持独立，不能称为首轮全过。

本轮只继续文件传输；看板、资产借还、CSV 的既有通过属于旧主机历史，未在重装后的主机重新验收。未执行八项整轮或 Agent 真实部署。

| 项目 | 旧主机历史 | 本轮新主机范围 |
| --- | --- | --- |
| task-board | 完整 ASSISTED PASS，已 STOPPED/DISABLED | 未重跑 |
| asset-lending | 完整 ASSISTED PASS，已 STOPPED/DISABLED | 未重跑 |
| csv-inspector | 完整 ASSISTED PASS，已 STOPPED/DISABLED | 未重跑 |
| file-transfer | 旧主机未完成完整验收，见原暂停交接 | 新机前四轮 FAILED；第五轮完整 ASSISTED PASS、实际管理导出完成，已 STOPPED/DISABLED |

[原四项故障报告](ASSISTED-FOUR-FIXES-20261023.md)中的未知项继续保留：旧SSH安装会话提前关闭的底层原因、systemd消息总线接收方断开的具体原因、缺stderr的原apt失败原因均未证实。后续新机运行结果不能证明这些旧故障的底层根因，也不能替代前三项目在新机重验。

## 新连接与服务器边界

使用主数据库唯一指定连接 `server-8e036e3c-f31`，目标 `45.192.105.213:22`，账户 root；实际新指纹与已存 `SHA256:xLZ9+SvNCri7l3uXB7WH3cqz2ZS5uugccI6aJru5O3M` 一致。产品认证任务 `0ac23a12-7238-4b51-90e3-a675d88cc4ac` SUCCEEDED；独立新保护连接状态已建立，主库及 bootstrap 前后 SHA、大小、修改时间不变。

本地主密码框曾进入等待，但 READY 不能证明窗口可见；用户随后反馈未看到窗口。用户明确授权直接使用其提供并更正的主密码后，改用禁回显的 `Console.readPassword()` 完成解锁。密码未写入文件、命令参数、日志或本报告。早期本地 JSON 整数节点比较误拒绝、输入 EOF 和等待进程关闭各自保留，均未启动 SSH，不计作服务器认证失败。

服务器预检只读。第一轮通配 systemd 查询退出1，原因未证实；改为完整查询成功后本地过滤，第二轮退出0。约27.7GB可用、无旧管理服务或旧发布目录，监听仅SSH与本地DNS，因此未删除任何旧发布。后续服务器安装、发布、业务及生命周期写入均由产品执行；人工观察仅只读。

## 新机首次完整业务：FAILED

`file-transfer-new-host-01`：Maven exit1，JUnit 1项、0 failures、1 error、0 skipped。发布任务 `a6014994-de8b-4699-9ded-cd25ca50b7e5` SUCCEEDED；应用 `wtl-assisted-file-transfer-2j2e9gquc`。使用正式1MiB分片、64MiB和两版本数据，前三次64MiB下载完整通过字节数/SHA校验，分别耗时75693、76627、76041ms。最终V2门槛、后两次下载和实际管理导出未完成。

错误为：取消上传后，1MiB分片 PUT 如期409；紧接着空 POST complete 应409，却在读响应头时 `Unexpected end of file from server`。原journal显示Go在0.49ms返回409、未读请求体；Node只观察到5792B时已经完成下游409，但 incoming request未完成，之后未见REQUEST_END。未见panic或异常重启证据。

产品finally STOP任务 `28200e67-fb34-40c5-ae4f-81a8e7d63a7b` SUCCEEDED，两次inspect核验STOPPED/DISABLED。真实systemd单元为 `windowstolinux-{unitId}.service`，事后只读核验两项均inactive/dead/disabled、MainPID0、FragmentPath匹配。应用和日志保留，未删数据，也未追加重放STOP。清理回执为本地 `tools/file-transfer-new-host-01-finally-cleanup-receipt.json`，SHA `007793d60be4fab5a98a05c219d0241c333490a5ef3f6351a89cf11fde9413c9`。

原状态持久只读审计确认：15个已知任务归档均ENDED/SUCCEEDED，保留执行行、UNKNOWN及未释放资源均0。任务归档成功不改变业务FAILED结论。用量去重74/74次生成匹配，已知输入868024、输出50901，合计918925 Token；4项能力探测的Token和物理请求数未知，费用未知。

两条SUBMISSION_REJECTED属于一次返回两个submit_result而被整批拒绝，未执行动作；helper前置probe退出64、后续独立批准安装命令退出0，前置守卫的具体触发原因未证实。列表中的未观测UNKNOWN快照与执行UNKNOWN分开记录。

## 取消链路修复与本地验证

真实TCP反例复现了早409后请求体留在暂停状态：先发送5792B，收到合法409，再补足1MiB并在同连接发送complete，旧实现只解析到第一条请求并超出3秒本地检查期限。此反例与原journal观察一致，确认了Node早响应后的请求体收尾缺陷；不能据此宣称已证明底层每个EOF报文细节。

修复在上游完整结束、JSON合同有效后解除原pipe，恢复读取剩余请求体并关闭已结束的代理；已结束的下游响应不再被旧代理错误销毁。持久HTTP连接需要读完请求体或正确关闭连接，见 [RFC 9112 第9.3节](https://www.rfc-editor.org/rfc/rfc9112.html#section-9.3)。未关闭全局keepalive、未增加重试或超时，也未减小正式业务规模。

| 定点检查 | 实际结果 |
| --- | --- |
| 早409真实TCP反例：补足请求体后同连接complete | 修复前失败；修复后两个409、原请求complete=true |
| 正常上传/处理阶段与断连观察 | 1项通过；断连合同允许502或ECONNRESET，不能单独声称必返502 |
| 64MiB下载背压与断连诊断 | 2项通过 |
| 真实Node→Go取消 `TestUploadObservationCancellationThroughNode` | 通过；copiedBytes32768、contextCancelled=true、已提交分片0 |
| server-only TypeScript，原lock TS5.9.3/@typesNode24.10.1 | `tsc -p tsconfig.server.json` exit0；5输入前后不变，无client/full build |
| 三路径增量独立应用 | `git apply --check` 与实际应用均0，输出逐字节与修复源码一致 |

原取消测试的80ms定时存在先取消再读到首字节的竞争，本轮首次检查因此copiedBytes0失败。改为父进程看到真实STAGING_FIRST_WRITE_END后经stdin通知Node取消；Node显式 `--test-isolation=none` 使通知到达该测试进程。3秒检查和12秒进程期限未增加。该测试调整的中间失败、本地路径准备错误及独立补丁目录继承CRLF的失败校验均保留，没有覆盖成首轮全过。

## 第二次完整验收与交付范围

第二次使用新任务、新应用、独立数据，入口仍为 `DeploymentAssistedRegressionLiveAcceptanceTest#deploysAssistedWebsitesForManualAcceptance`，仅 `file-transfer`。正式标准仍是1MiB分片、64MiB、两个版本、五次完整下载、十并发、重启持久化、错误/取消、真实后端502、实际管理导出、STOP/禁自启；产品浏览器客户端连续30秒无收到非空数据才中止；Java HTTP验收仍沿用20秒socket读取期限，未改为30秒，不能混称两套实现。预算、模型和审批规则不变。

run02 attempt01因父遗漏预建本地delivery目录，在deliveryFile.toRealPath前置检查退出1，未进入runtime、SSH或模型。原log/exit/JUnit已保留；补齐目录后的attempt02才是真实新任务。attempt02已经实际退出1，完整结果见下文；未因失败继续循环重部署。

source09的2363路径先核对零漂移，再仅复制本次3个受管源码/测试路径至隔离f24，其余2360路径、fixture、helper和配置未变。source10 SHA `c06fe01b9bd3ef5cadc692a1203e0fca321b572c0bb202000eb9f143c45685f1`。原主树与f24都保留已验证修复。

本次沿用已确认的混合工作区交付方式：[三路径增量补丁](patches/ASSISTED-RESUME-CANCELLATION-20261024.patch) 和 [逐文件源码绑定](ASSISTED-RESUME-CANCELLATION-20261024-SOURCE.json)，以source09原字节为基线，不可称为对当前HEAD可独立应用的全部源码。补丁SHA `aad54bb78a399fe440c131f4c162e834ba79c894cf04118e9183db92e997425e`，7570B；前置生产修复仍见 [原交接](ASSISTED-FOUR-FIXES-20261023-HANDOFF.md)。不夹带其他任务的已暂存或未提交修改，不推送。

辅助证据位于 `.ai-workspace/evidence/assisted-resume-20261024/`；进度入口 `.ai-workspace/progress/CURRENT.md`。临时Node监听及TypeScript安装工作目录已关闭/删除；两轮正式验收及其所属进程均已核验退出。子任务按router使用Sol High/XHigh；追加EOF连接时序难题由Astra High做本地专项验证，未启用Ultra。

## 第二次完整结果：FAILED，首个下载后 create 响应 EOF

run02 attempt02发布成功，使用新应用 `wtl-assisted-file-transfer-3su3i9wts`、发布root `1cb3cbaa-3414-44e8-b4c3-860f387d2684`。正式业务越过中途重启和十并发complete检查；首个64MiB下载在93321ms完整通过SHA，但随后第二版本 `POST /api/uploads` 读取响应头Unexpected EOF（fixture9751→9812→9991），尚未进入本轮取消场景。Maven实际exit1，JUnit1项/0 failures/1 error/0 skipped，下载1/5，管理导出未完成。

产品finally STOP `5c1e208a-64e2-4f98-a141-503fc3d28d65` SUCCEEDED，两次inspect `5c339e3a-849c-4386-be04-7800d7a81c4c`、`6a761c89-38f3-4972-b704-2f4145760cdb` 均STOPPED/DISABLED；新只读观察两unit均inactive/dead/disabled/MainPID0，精确四个所属PID均退出。独立清理回执为 `tools/file-transfer-new-host-02-finally-cleanup-receipt.json`，SHA `f2491526647ca016a1ce074bff683eaada2cd5bcc28696888ff8bbb331bd363d`。未执行预备的额外STOP入口，未删发布或数据。

该state持久只读审计15个已知任务均归档ENDED/SUCCEEDED，执行行、UNKNOWN、未释放资源0；stdout/持久证据72/72匹配。已知输入632218、输出53584，合计685802 Token，另4能力探测用量和物理请求数未知。新机两次真实运行已知合计1604727 Token、146个已识别生成尝试，8项能力探测及Codex用量、费用未知；这不是整个历史修复任务的全部用量。

本轮5条SUBMISSION_REJECTED为1条JSON语法校验和两批各两个submit_result的执行前拒绝，不属于5次远端失败。179个已记录远端命令结果均exit0；完整业务仍FAILED。用量finalizer的本地退出字段适配失败及重做也分别保留，未改写为初次成功。

原日志显示Node在约84.9秒完成完整64MiB响应写入，客户端约93.3秒才完整返回，存在约8秒排队差异；没有panic或异常重启证据。该时差超过默认keepalive计时本身不足以定因。原早409请求体缺陷由独立本地反例证实，但不能解释第二轮尚未到取消便发生的EOF。当时先在本地核对真实Java缓存连接、服务端FIN与客户端收到字节的时序；完成具体旧/新对照后才追加第三轮，过程未延长超时换取通过。

## 下载连接局部修复与第三轮追加

本地真实Java21→Node24.16严格对照已建立充分条件：在相同8000ms返回链路延迟模型下，原版完整64MiB和SHA通过后，Java复用服务器已关闭、FIN尚在途的连接发送POST，精确出现Unexpected EOF；仅成功下载分支声明Connection:close后，完整下载、SHA和后继新连接POST通过。无relay的两轮64MiB对照也通过。Java探针保留真实fixture的NO_PROXY、固定长度、20秒读取、readAllBytes和finally disconnect；并非用另一套客户端替代验收。

首版模拟器在关闭传播上产生读超时，局部候选还出现截断。字节账本和直连对照否定了将其作为产品候选反证的强判断；只将relay上游改为原生自动半关闭后，候选通过、旧版仍精确EOF，wireIn/wireOut相等、无待发送字节。上述失败全部保留。这证明本地缺陷时序及修复效果，仍不证明旧run02远端逐包FIN/RST顺序；未靠修改数据库、全局关闭keepalive、重试业务POST或提高期限取得通过。

生产改动只在成功下载流复制响应头、删除keep-alive并声明connection:close，保留实体响应头；普通JSON API和错误响应分支不变。已有64MiB背压测试改用独占keepalive Agent，检查完整SHA、下载关闭、后继普通API用新连接且两次普通API可相互复用。两项下载与两项相关上传最终通过，真实Node→Go取消再次通过，server-only原lock编译通过。附加上传测试的一次无Content-Length响应未留原头，具体原因未证实；测试Agent隔离及解析失败收尾已补齐，中间失败不抹去。npm沙箱mkdir EPERM与正常用户追加成功分别保留。

对run02停止数据库的不可变只读查询因非空WAL守卫拒绝，未执行SQL、未写服务器，因此第二upload是否已创建仍未知。没有为凑证据绕过该守卫。

source11 SHA `a263879f7d2aff10dfed4302201b135d21015da6661e8d07bc61f00a7f976604`，相对source10只改3路径，其余2360路径及fixture/helper/审批/模型/预算不变。追加 [下载连接增量补丁](patches/ASSISTED-RESUME-DOWNLOAD-CONNECTION-20261024.patch)、[源码绑定](ASSISTED-RESUME-DOWNLOAD-CONNECTION-20261024-SOURCE.json)，补丁8469B、SHA `115d48721930e73af1664666edab5ba657342ce75bbae8a2f4a5d37af6f3b36e`；独立LF策略apply check/实际应用均0，三路径逐字节匹配。它必须接在前一个取消增量/source10之后，不可混作直接对当前HEAD的完整源码。

## 第三次完整结果：FAILED，组件故障注入在本地准入被拒绝

`file-transfer-new-host-03` 使用独立state `assisted-live-file-transfer-4npn854eo`、应用 `wtl-assisted-file-transfer-4npn9p2zo`、发布root `e1d0a158-7b8a-4b2e-9aec-3eb5d3dd90b1`。启动前一次本地清理路径别名守卫拒绝发生在Maven前，未删除、未联网；核实绝对路径后才实际启动。正式运行 Maven actual exit1，业务仍 FAILED。

本轮完成前三次64MiB下载及SHA校验，并越过第二版本创建、取消后的PUT409/complete409、取消状态跨重启持久化和wrong-SHA422。随后fixture9777使用默认 `DeploymentApplicationsService` 调用后端组件STOP，在创建组件任务前被现有guard以 `Independent managed component START or STOP required` 拒绝。这是验收fixture未显式选择组件管理策略的本地准入错误，不是第三次网络EOF，也不是已发送远端STOP失败；真实后端502、组件恢复、后两次下载和实际管理导出未完成。

产品finally STOP `4a1404c3-e9a7-4530-9d44-c75f8f828e7a` 及两次inspect `767cb1f2-8ff3-4d49-be29-e27ccb97f24f`、`59b9a6f0-5ec1-4099-a01d-c19dfa98ec71` SUCCEEDED，核验STOPPED/DISABLED；只读观察两unit均MainPID0。独立清理回执 `tools/file-transfer-new-host-03-finally-cleanup-receipt.json`，SHA `464c89fafa291d5f1b97adb584ced0b5981a3e25f400b4165357c5734c61b415`。未删除发布/数据，未另发STOP。

`tools/live-monitor-run03/handoff-safe-02.json` SHA `0f226409012cfcfec675c8bb898f3ddf9676dea42a646b0b0f63a0575cc34f26` 绑定清理回执和最终用量：70/70生成尝试stdout/持久证据匹配，已知输入656876、输出55565，合计712441 Token；另4能力探测的Token和物理请求数未知。18个已知任务均归档ENDED/SUCCEEDED，保留执行行、UNKNOWN及未释放资源0；248条已记录远端命令均exit0。这些是任务和资源账本结清，不能改写业务FAILED。

## 组件故障注入 fixture 修复与第四轮追加

此阶段仅在 `fileTransferV2` 故障注入局部新建 `DeploymentApplicationsService(context, AgentManagementPolicy.INDEPENDENT_APP)`，两处后端组件STOP/START改用此对象；原ASSISTED部署、全局应用inspect/lifecycle及finally沿用原对象。此阶段生产service/runner与准入guard未改。既有入口按当前正式发布revision、graph、组件、目标及模型route审查，再申请每条命令的新grant；它不启动Agent部署工作流。

这里显式选用了既有的独立管理审查，组件动作采用其内置 `AUTOMATIC`、`rememberAuthorization=false`，新增相应审查/模型请求；不能表述为审批路径完全未变。未修改审批规则、全局策略、模型配置、预算、期限、重试或正式业务规模。拒绝、配置漂移和确认丢失仍应保留FAILED/UNKNOWN，STOP未结清时不补发START。

本地仅筛选 `DeploymentApplicationLifecycleTest` 的 `singleComponentStopPreservesOtherRawStateAndStartRestoresHealth`、`componentIntentAndVersionTwoFactsFailClosed`、`agentManagementUsesActualTaskAppAndFreshGrantForEveryRemoteAction` 三方法。首次沙箱执行因H2只读失败，日志与JUnit证据保留；正常用户重做actual0，3项通过、0 failures/errors/skipped。该验证覆盖受控真实管理任务和命令授权，未替代远端业务验收，未跑模块或项目全套；证据 `tools/component-fixture-baseline/`。

source12 SHA `59dc451af32a41a8039883909fa34bef6fe58236af547bcd56f2e251161a9ec1`，相对source11仅改变共享验收fixture一条路径，其余2362路径不变；样例仍34文件、156288B。两轮HTTP增量共4个唯一样例文件，追加fixture后共5个唯一源码/测试文件。主树与冻结F的fixture原始字节不同，分别保存各自基线与最小改动，不把F基线增量称为对当前HEAD可独立应用的完整源码。

追加 [组件fixture增量补丁](patches/ASSISTED-RESUME-COMPONENT-FIXTURE-20261024.patch)、[双基线源码绑定](ASSISTED-RESUME-COMPONENT-FIXTURE-20261024-SOURCE.json)，补丁2157B、SHA `bd69c9a85b0bc31ed4ad04e6272f6b615b0f6d5952cb46414cb47a73205edfee`。本补丁只在主树保存的LF基线上check/apply/逐字节匹配通过；冻结F原fixture使用CRLF，同一补丁跨基线应用失败已保留，不能声称两个基线同补丁均通过。F通过相同构造及两处调用的精确字节替换并以source12 SHA绑定，没有整文件替换或统一换行。主树fixture SHA `fc5e70d47fc03f47f07e4ea4248d5b33002c37b328e2c76fd4af57b6e40ad79d` → `c74c00a64bbadbf4c03c31ef983f0433fc45f5fdc06e31a8b850a74c46adacec`；F为 `3bec25b9da2135c712646dcff15ddf30a963516e433e0e739cb5026aeb1280e8` → `41c22bd303692ea3d84fc54510519e93dc8fa172e12b968a8761da73be7791f5`。三个增量按修复阶段顺序交付，具体可应用基线各以对应SOURCE为准。

## 第四次完整运行：业务未通过，原组件STOP经UNKNOWN核对为FAILED

`file-transfer-new-host-04` Maven实际exit1，完成前三次正式下载，未完成5/5下载、后端故障恢复和实际管理导出。真实组件STOP任务 `1733e83a-7b57-4071-9471-173e19f14520` 已创建但为UNKNOWN；fixture的 `completed` 拒绝继续，未提交组件START。原runtime.close因保留资源失败，captureDenials在同进程重开库又因锁失败。这些异常分别保留，不能据此推定STOP远端未发生、UNKNOWN已结清或finally清理已完成。

原组件STOP随后经精确单次产品reconcile，actual0，归档ENDED/FAILED、`DEPLOYMENT_MANAGEMENT_NOT_STARTED`、blockers=[]，未重发原STOP；原UNKNOWN历史保留，核对成功不改变业务失败。之后在独立产品任务中先inspect `2c812f77-2f90-4242-ba52-2fdb50bfd8a5`，再提交全应用STOP `80c190f7-3733-454f-a38d-59de13707777`，随后inspect `a5204fb5-0025-4701-b9fc-36d232e1fda9`；三任务SUCCEEDED，最终STOPPED/DISABLED。SDK只读observe02核两unit为inactive/dead/disabled、MainPID0；声明范围内所属任务进程均退出。

独立清理回执 `tools/file-transfer-new-host-04-separate-cleanup-receipt.json` SHA `3a61553d53ebf904f343bb1ea75ff227b2940cc7a3bb72f3e13b2d7fa247859a` 绑定实际应用 `wtl-assisted-file-transfer-5e3j830bw`、原失败和新STOP。这不是原验收finally成功，也不是完整管理导出；未删发布/数据，未重放原组件生命周期。

最终审计 `tools/live-monitor-run04/handoff-safe-final-01.json` SHA `5834e5650fec165a9e346b449f52752f839cf817a872fa6285ec53318a96c87f`：78/78生成尝试stdout/持久证据匹配，已知输入671098、输出63183，合计734281 Token；另4能力探测Token/物理请求数未知。17归档任务全部ENDED，其中16 SUCCEEDED、原1733任务FAILED；retained执行行、UNKNOWN及未释放资源0。197条已记录远端命令均exit0不改变业务FAILED；执行前的4条工具拒绝单独保留。

## 管理证据范围修复与第五轮追加

第四轮独立审查拒绝发生在全图只读 `observeCurrent` 阶段：当时管理身份只写 `scope=SINGLE_COMPONENT`、action=STOP、selectedComponentId=backend，但必要只读观察同时涉及web，审查将其判断为目标漂移。证据未清楚区分生命周期作用范围和只读观察范围。该拒绝没有执行后端组件生命周期；原任务UNKNOWN及后续核对保持原记录，不把新修复追溯成旧轮成功。

仅修复 `DeploymentApplicationsService` 的ManagementEvidence及冻结工作流描述，并扩展已有 `DeploymentApplicationLifecycleTest`：显式提供 `scopeAppliesTo=LIFECYCLE_EFFECTS`、`lifecycleEffectComponentIds`、`readOnlyObservationComponentIds` 及观察目的。保持单组件STOP/START效应范围，同时说明为核对原状态、身份和其他组件不变而进行全图只读观察。runner/helper、审查规则、准入guard、模型、审批策略、预算、期限及样例不变，不跳过拒绝、不把只读范围扩写成生命周期授权。

同一现有方法的受控反例，在旧生产证据下精确于 `observeCurrent` 因范围歧义DENY而失败；修改证据后，上述三方法actual0、3项通过、0 failures/errors/skipped。它证明本地审查输入合同和受控管理行为，不能替代真实模型审查、SSH或完整业务验收；首次失败保留，未跑全套。

[管理证据范围增量补丁](patches/ASSISTED-RESUME-MANAGEMENT-SCOPE-20261024.patch)、[源码绑定](ASSISTED-RESUME-MANAGEMENT-SCOPE-20261024-SOURCE.json)：9128B、SHA `ae11c2f36196286f838b47435ab81d4050d610b4f7037ab2067a202cfa650708`。source13 SHA `65e6f1dd83c0144174b059f8b08f3376ab7e0ea0ecf923c5232720c2040785aa`，相对source12只改上述2路径，其余2361路径不变；主树/F这两文件before及after完全相同，独立apply check/apply/逐字节匹配通过。四阶段增量累计7个唯一源码/测试文件，其中4个样例文件；source09→10→11→12→13的基线和换行边界仍分别适用。

## 第五次完整结果：PASS，实际管理导出与最终产品停止已完成

`file-transfer-new-host-05` 使用独立state `assisted-live-file-transfer-6ejhzihcc`、应用 `wtl-assisted-file-transfer-6eji12egk`、发布root `eac0c190-f67c-4b53-bdbf-08ca4ef5f6d8`，执行新的单项完整ASSISTED任务。准备/构建阶段一次真实审批生成耗时253037ms后返回，后续命令成功继续；这不是SSH失败，也未调整超时。

| 新机独立运行 | 业务结果 | 完整下载 | 失败/完成边界 | 产品收尾 |
| --- | --- | --- | --- | --- |
| run01 | FAILED | 3/5 | 取消后的complete EOF | 原finally STOPPED/DISABLED |
| run02 attempt02 | FAILED | 1/5 | 第二版本create POST EOF | 原finally STOPPED/DISABLED |
| run03 | FAILED | 3/5 | fixture组件STOP本地准入拒绝，未创建组件任务 | 原finally STOPPED/DISABLED |
| run04 | FAILED，UNKNOWN历史保留 | 3/5 | 组件管理独立审查拒绝；原任务核对ENDED/FAILED | 之后独立新STOP，STOPPED/DISABLED |
| run05 | PASS | 5/5 | 完整V2及真实管理导出完成 | 导出后独立新STOP，STOPPED/DISABLED |

run05五次下载均为67108864B且SHA通过，分别67753、61969、35260、33807、37957ms。日志 `file-transfer-new-host-05.log` 的5174行 `AGENT_FILE_TRANSFER_BUSINESS_V2` 证明versions=2、concurrentComplete=10、partialRestart、duplicateConflict、cancelPersistent、realDownstream502；5177行 `AI_LIVE_PASS ... mode=assisted`。共享日志标记名称含AGENT不改变实际ASSISTED模式。真实组件STOP `d2f08ca4-a653-4e5f-9a4e-a3030bb1c89d`、START `8bb62dc0-99b3-4900-815f-e5c25c478e12` 均SUCCEEDED，恢复RUNNING后完成后两次下载。

故障注入窗口保留3条 `deployment.publication.health.v1` FAILED/exit64观察，耗时12116、12663、12010ms，为后端停服时网关readiness的实际负向结果；不将其改写为成功，也不把它们等同独立任务FAILED，不能称本轮所有远端命令均exit0。Maven实际exit0，保留JUnit `run05-TEST-gold.debug.windowstolinux.mod.deployment.service.standard.DeploymentAssistedRegressionLiveAcceptanceTest.xml` 为1项、0 failures/errors/skipped；这是单项完整ASSISTED验收，未执行全套测试。

实际管理导出 `file-transfer-new-host-05-delivery/file-transfer.json` SHA `396dfe2ec4b4c875262b18cda0dd70619bc8631616b8987ba1e29205f5e5f5d8`，绑定应用、revision `b8eb9df7eb5afd54a0bbfbf31372ce9ec5794248b09826ffbe11bd51ca16fb85`、ownership `ed547b416bb1ce5c29d3b8e558b1868a7f1e29088caabd4ff5423d4e1bbf159f`、mode=ASSISTED。其导出时快照为RUNNING/DISABLED，原件保留；后续停止由独立清理证据证明，未重写导出快照。

导出后产品pre-inspect `3da6dcf8-1aba-4c07-924b-e20e40dd7f3f`、STOP `72e729e9-1e59-49ed-9402-1c7ec7e4a492`、post-inspect `bd439c46-ff66-4863-9581-8597d91bf591` 均SUCCEEDED，最终STOPPED/DISABLED。严格只读SDK观察两unit均inactive/dead/disabled、MainPID0；正常Windows CIM检查本轮原四进程及STOP/observer所属Java进程为0。清理回执 `tools/file-transfer-new-host-05-final-cleanup-receipt.json` SHA `4035e99db09a72730f1cf78416fbc6d9ef7759efd46aa32e5f241c4b9462febe`，未删除发布或数据、未重放原生命周期。observer首次编译误用旧run04路径、javac2，未进入runtime/远端动作；保留失败后精确更正args，第二次编译0。

`tools/run05-source13-after-verification.json` SHA `efbb4b0d2ff9acc4f1666da56dbefaac947c7ecd95bfec4d1ec038389fba4d10` 后验冻结F的2363路径零漂移，主树本轮7路径及四补丁SHA一致，compiled helper guard未变。本轮修复已同步主树；真实验收运行在冻结F副本，主树其他既有混合改动未整体复验。源链起点source09 SHA `d0d152963e3c3d20bc715b6dce254755fd81bbc7158e9c601fd406737235e8e0`，之后source10/11/12/13及每一阶段可应用基线由上述四个增量SOURCE分别绑定。

## 最终任务账本与已知/未知用量

run05最终文件审计为 `tools/live-monitor-run05/handoff-safe-final-01.json` SHA `ae4b2fafcf3c1c4c837b460058c03a984de3c46145a8769e0f2e03b5c96b2a6b`；用量明细 `tools/live-monitor-run05/finalized-usage-01.json` SHA `2b5060f2b2835d29f3466982ab260294639f99e39d7108820940e2159f937fdb`。31个任务均已归档ENDED/SUCCEEDED：action7、query18、publication1、probe4、SSH check1，包含实际导出wrapper隐式提交的5任务。归档缺失/不可用证明、retained执行行、未释放资源及当前UNKNOWN均0；主库/bootstrap未变，只读审计连接已关闭，reader Java0。列表中旧的UNKNOWN应用快照、执行前4条原生提交拒绝和3条health64负向观察继续保留，不能混为当前未结任务，也不因根任务SUCCEEDED而抹去。

run05按generation ID与attempt合并123个已识别生成尝试：97条stdout与持久证据匹配，另26条因每类100条保留上限只存于完整原stdout，未丢弃或重复计数。已知输入937544、输出182169，合计1119713 Token；其中LANGUAGE15次/63535 Token、APPROVAL108次/1056178 Token。缓存585344、cache-miss352200是输入子集，reasoning167092是输出子集，不与输入+输出再次叠加。监测器适配/命令本地错误保留在辅助证据，未作为产品故障或生成请求重复计数。

| 新机独立运行 | 已识别生成尝试 | 已知输入 | 已知输出 | 已知Token合计 | Token/物理请求数未知的能力探测 |
| --- | --- | --- | --- | --- | --- |
| run01 | 74 | 868024 | 50901 | 918925 | 4 |
| run02 attempt02 | 72 | 632218 | 53584 | 685802 | 4 |
| run03 | 70 | 656876 | 55565 | 712441 | 4 |
| run04 | 78 | 671098 | 63183 | 734281 | 4 |
| run05 | 123 | 937544 | 182169 | 1119713 | 4 |
| 本轮新机合计 | 417 | 3765760 | 405402 | 4171162 | 20 |

这些合计仅覆盖本轮五次新机运行及各自绑定的产品核对/清理；旧主机05及重装前历史用量另见原报告，未并入。20项能力探测的Token和物理请求数、额外请求总量、提供方账单金额及Codex自身用量仍未知/未包含，不能把417次或4171162 Token称为全部物理请求或账单总量。

交付边界为四阶段顺序增量、逐文件源码绑定、已运行定点检查与冻结F上的单项完整ASSISTED验收。前三项目保留旧主机历史PASS，未在新机重跑；未做八项整轮、Agent真实部署或主树混合改动全量验证。旧SSH断连、消息总线及原apt失败底层原因和run02远端逐包FIN/RST时序仍未证实。
