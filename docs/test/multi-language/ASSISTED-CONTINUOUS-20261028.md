# ASSISTED 连续首过验证记录

状态：进行中，尚未达成目标。仅通过测试代码执行真实模型、SSH、产品部署及既定业务验证，不使用真实界面或浏览器。

验收口径：每轮清除一次性测试机已盘点的部署及测试软件环境，在冻结代码和参数下执行既定八个项目。只有同一轮八项首次完整通过才达成；失败后修复、恢复及外层重试单独计数，下一轮重新清场。模型SDK内部自动请求重试及只读观察另记，不自动等同整项目重试；原任务终止失败、UNKNOWN、取消或人工补救不能计首过。额外保留无内部异常/重试指标。清场不等于重装操作系统。

## 逐轮结果

| 轮次 | 冻结版本 | 清场复查 | 首次结果 | 结论 |
| --- | --- | --- | --- | --- |
| round-01 | 5f1f66119cfd20dcdfdc6e424f7ea7a0f178baa3 | 清场执行及独立复查通过 | 八项各一次：首过4、失败/UNKNOWN4 | 本轮未达成；原驱动正常退出，等待修复验证后清场新轮 |

## 失败原因与解决方案

从本轮开始持续记录。每项记录包含原任务身份和证据位置、失败现象与直接错误、确认原因与仍未知之处、实际解决方案、相关定点验证及后续完整轮次是否复现。测试准备或清理失败也单列；不会把诊断增强表述为底层故障已修复。

| 记录 | 阶段与现象 | 确认原因与未知项 | 解决方案 | 复验结果 |
| --- | --- | --- | --- | --- |
| round-01/P01 | 连接预检被固定指纹守卫拒绝；connection-01退出1，SSH_TRUST_CANCELLED，无远端清理或部署 | 当前端点返回的SSH密钥与历史预期不同；密钥变化原因未知。不是已证实的产品部署缺陷 | 用户明确确认目标及新指纹后，建立新隔离连接状态并固定获准指纹，继续产品认证；保留旧指纹拒绝证据 | connection-02用最新图片凭据产品认证成功、退出0；该预检阶段无部署尝试 |
| round-01/F01 | FTP部署成功，业务backend STOP任务UNKNOWN | 当前首次派发前过早登记attempted，独立审批将其误认为历史未确认重放 | 在APP及fresh grant后、同一请求execute前，通过现有guard进行attempted CAS；旧UNKNOWN不重放 | 隔离提交7eb991c8，22个不同定点最终通过；新轮实机待验证 |
| round-01/F02 | Asset Lending审批HTTP_CHUNK_INCOMPLETE、accepted0，原任务UNKNOWN | 外部断流具体位置未知；ASSISTED调用者未选择平台已有恢复能力 | 显式选择现有有界纯生成恢复；保留原Problem、尝试历史及未知usage，禁止业务重放 | 部分正文CHUNK本地恢复通过；零正文CHUNK忠实注入未验证，新轮实机待验证 |
| round-01/F03 | CSV Inspector审批HTTP_CHUNK_INCOMPLETE、accepted4083，原任务UNKNOWN | 与F02同类传输错误，外部是否同因未知 | 同F02；不将原UNKNOWN改写为成功 | 同F02，新轮实机待验证 |
| round-01/F04 | Log Analyzer原部署UNKNOWN，无完整业务验收 | 独立审批完整DENY补装命令；标准准备跳过SYSTEM_COMPILER，未自动补native前置工具；不是断流 | 实际缺口时一次受控NATIVE_BUILD准备、严格receipt及fresh probe；完整source/当前scope/APP和fresh grant保留 | 7a321fa1已本地提交并合入，61相关JUnit场景分组有通过证据；新轮实机待验证 |

自动审批曾拒绝“固定未经用户确认的新指纹后继续清理和部署”，该命令未执行。随后用户明确确认目标及具体新指纹，才继续产品认证和后续操作；没有绕过指纹检查。

## round-01

基线为本地提交5f1f66119cfd20dcdfdc6e424f7ea7a0f178baa3。保留另一项论文工作的已有未提交修改。沿用LOW/LOW、1500000已知Token监督阈值、65536最大输出及90分钟部署等待，保留原业务负载。四项模型能力探测用量不计入已知Token统计，统计不是完整账单。

原连接及历史隔离库目录当前已不存在，原因未知；保留旧报告与日志，重新创建独立连接状态。所有新凭据通过既有产品凭据接口使用，不写入证据。

证据位置：`.ai-workspace/evidence/assisted-continuous-20261028/round-01/`。连接失败见connection-01.log及connection-01.exit；当前新指纹为SHA256:DyqPjWTN6VdHe3atLFDCXWOa7Ur/kuK4+Km/FqK4jKg，旧指纹为SHA256:E9Abpbby0C0I4R3OUBIEv52qTv7mKjvRPh70bEEGPFE。用户确认后connection-02认证SUCCEEDED，新指纹与获准值一致；图片不含指纹，此项证明该端点与最新凭据对应，不是云控制台的独立身份核验。随后完成产品清场及独立盘点：8受管根、账户、服务、目标包及APT缓存均为空，清场实际0删除，保留系统基础环境；同一冻结版本才开始八项测试。

### round-01 项目结果

| 项目 | 首次结果 | 原始验证及收尾 |
| --- | --- | --- |
| task-board | PASS | JUnit1/0失败/0错误/0跳过，1984.096秒，Maven0；业务及重启持久化通过，额外产品STOPPED/DISABLED，内部异常/重试0 |
| file-transfer | NOT_PASS | 原部署SUCCEEDED；backend STOP任务UNKNOWN；JUnit1失败/0错误/0跳过，1582.191秒，Maven1；前三次64MiB下载及SHA通过，第4/5未执行，无完整业务/收尾通过 |
| asset-lending | NOT_PASS | 审批模型HTTP_CHUNK_INCOMPLETE；原部署UNKNOWN，JUnit1失败/0错误/0跳过，827.607秒，Maven1；无业务通过 |
| csv-inspector | NOT_PASS | 审批模型HTTP_CHUNK_INCOMPLETE，原部署UNKNOWN；JUnit1失败/0错误/0跳过，652.373秒，Maven1，无业务通过 |
| survey-scoring | PASS | JUnit1/0失败/0错误/0跳过，2207.671秒，Maven0；完整业务及交付通过，额外产品STOPPED/DISABLED，内部异常/重试0 |
| log-analyzer | NOT_PASS | 原部署UNKNOWN，JUnit1失败/0错误/0跳过，599.710秒，Maven1，无业务PASS或交付 |
| directory-diff | PASS | JUnit1/0失败/0错误/0跳过，544.585秒，Maven0；业务及CLI_CLEAN通过，交付INSTALLED/NOT_APPLICABLE，内部异常/重试0 |
| binary-inspector | PASS | JUnit1/0失败/0错误/0跳过，573.955秒，Maven0；业务及CLI_CLEAN通过，交付INSTALLED/NOT_APPLICABLE，内部异常/重试0 |

task-board原部署root为943c9e60-772b-46f8-a729-6f2186440bf4，应用wtl-assisted-task-board-61xqa52lg，修订4aceb1b50b9bf2779ec9a353fec2004ddfac81cfc9ad10acdcf643796931dc01。已知监测Token为部署128378、审批900559，共1028937，95个generation attempt id；能力探测及未纳入该监测的额外操作不计入，不是完整账单。原始launch、JUnit、退出、delivery及cleanup回执保留于本轮目录，严格核查eligibleAttempt与cleanExecutionWithoutInternalRetry均为true。

### round-01/F01：File Transfer 后端停止任务 UNKNOWN

原部署root7e400ecb-b767-4f70-8b21-61b867ed6363为SUCCEEDED，应用wtl-assisted-file-transfer-6spwoi77s，隔离库assisted-live-file-transfer-6spwmqxlc。真实业务Component backend STOP任务58a02cee-ff19-4076-84f5-87a8432ee5d3进入UNKNOWN，最后断言为Original task requires reconciliation。该任务于UTC2026-10-27T20:04:56.122Z开始；原UNKNOWN及状态库保留，没有重发STOP/START或将其写成成功。

已完成的三次64MiB下载分别实收67108864字节且SHA一致，耗时41558、49607、60464毫秒，顺序为旧版、旧版、新版；第4/5次未执行。JUnit1项失败、0错误/跳过、1582.191秒，Maven1，无完整业务PASS或交付收尾。已知监测Token855475、101个generation attempt id，额外用量不含。

已确认直接原因为审批将当前首次停止请求误认为历史未确认重放。原失败库20:07:17.052Z记录DEPLOYMENT_COMMAND_REVIEW_DENIED：前一次backend停止尝试仍未验证、禁止重放并要求先只读确认。该时点journal为LIFECYCLE_STOP/EFFECTING，backend attempted=true/verified=false/verb=stop；源码在channel.lifecycle首次审批及派发之前就advance到该状态，提供给审批的证据未区分当前预执行标记与历史未确认副作用。前一次只读observe已成功，最后生命周期模型请求返回后没有命令结果，拒绝发生在本次派发之前，不归因为SSH丢失。captureDenials的数据库锁异常是最终断言后的次生错误。原失败库H2只读诊断进程自然退出0，实际3个根文件前后SHA、mtime及长度一致。Astra XHigh已作具体安全边界裁决：复用现有派发前回调，在APP和fresh grant后、同一生命周期请求execute前CAS登记attempted，不新增proof或弱化审批。修复已完成本地定点及提交并合入主仓，具体结果见下文；新轮实机尚未执行，旧失败不被后续恢复或重试覆盖。

### round-01/F02：Asset Lending 审批模型响应正文不完整

原root6616c81f-b584-4d3a-b485-0856f9cb2722，应用wtl-assisted-asset-lending-7czkev9i4，原库assisted-live-asset-lending-7czkdcy3o保留。工具链审批模型物理attempt3adc2820-00d9-432f-a628-f7bfb622ce23返回UNKNOWN/AI_TRANSPORT_UNKNOWN，HTTP_CHUNK_INCOMPLETE，耗时497222毫秒；HTTP状态200，headers已收到、accepted body bytes=0，IOException/EOFException，输入输出用量未知。不能将其描述为远端安装命令超时、已知账单为0，或确定由某一网络/服务商组件造成。

JUnit1失败、0错误/跳过、827.607秒，Maven1，原部署UNKNOWN且无业务通过/交付收尾。已知监测Token204752、37个generation attempt id，其中包含本次未知用量物理调用；完整用量仍未知。

平台已有仅适用于纯客户端函数协议、当前逻辑生成原期限内的不完整HTTP正文恢复：每次旧连接及durable lease完全释放后，复验task/cancel/route/原deadline，才以相同冻结输入建立新的物理调用，保留全部attempt及未知用量；完整拒绝、取消、超时、格式错误和响应上限不进入该恢复。本轮旧版ASSISTED采用默认0，未选择该能力。修复让ASSISTED调用者显式选择现有配置预算（默认1，可0关闭），保持普通/STATIC默认0、独立编排及业务不重放；定点结果见下文，现已随7a321fa1提交链合入主仓，不宣称修复了外部断流。旧F02任务不重放，后续新轮实测另记。

### round-01/F03：CSV Inspector 审批模型部分正文断流

原root0469dc14-8370-4bc1-8dcd-8689872166fe，应用wtl-assisted-csv-inspector-7nmiesgpc，原库assisted-live-csv-inspector-7nmid1zbk保留。工具链绑定审批物理attempt2b0fb00f-92d2-4b7c-a2fc-e986955129ca为UNKNOWN/AI_TRANSPORT_UNKNOWN、HTTP_CHUNK_INCOMPLETE，16153毫秒，HTTP200/headers已收到、正文接收4083字节，IOException/EOFException，usage未知。与F02是同类不完整HTTP正文，但不能确定同一外部根因。

JUnit1失败、0错误/跳过、652.373秒，Maven1，无业务PASS、交付或收尾。已知监测Token208357、22个generation attempt id，未知attempt用量不补0。F02所述ASSISTED显式选择既有纯生成恢复不按正文已收字节数分支；部分正文CHUNK恢复已有本地定点证据，实测零正文CHUNK的忠实本地注入尚未验证，是否实机恢复仍待新轮确认。不重放此旧UNKNOWN。

### F02/F03 隔离修复验证

ar28仅修改ASSISTED调用者的恢复选择及固定审批入口的显式预算重载，并对齐配置注释；普通/STATIC默认0、平台单次HTTP发送/当前原deadline/纯生成恢复实现不改。必要隔离reactor在-DskipTests下package成功、退出0，不计业务测试通过。

local-01显式18项：16通过、2失败、0错误/跳过。12个完整DENY跨模式用例、配置边界、分析决策预算及一个断流分支通过；一项旧测试把raw call的自动tool choice误断言成强制submit_result（该call生产实现未改），已按原接口修正测试。另一个断流集成分支出现原task UNKNOWN，继续调查，不能宣称修复已通过。

local-02只重跑失败方法组并追加直接相关平台chunk/原生历史3项：总6项，5通过、1失败、0错误/跳过。平台3项及修正的raw调用测试均通过；当时ASSISTED集成仍有一个分支失败，记录显示1次模型请求、0个完整审批、0远端发送。local-03补原callback结束同步和精确结果审计，仅重跑两参数场景并通过，不重复此前通过组；该次通过没有掩盖后续间歇失败。

local-03两项通过后，为前次原任务UNKNOWN的具体疑点加入恢复期只读轮询，local-04两项失败；增加原模型结果审计后local-05两项通过，同源码针对间歇问题复核local-06仍两项失败。旧审计proxy又把后续配置查询的TASK_EFFECT_UNCERTAIN覆盖了原模型Problem，修正该诊断后local-07两项通过，12.123秒。以上结果完整保留，不能据最后一次通过认定稳定。

Astra确认独立诊断缺陷：ASSISTED固定审批返回UNKNOWN后仍无条件后验verify，因宿主此时已经保留不确定状态，配置查询抛TASK_EFFECT_UNCERTAIN掩盖原Problem。修复仅在成功交付许可时保留后验复验，非成功结果原样返回，UNKNOWN与零派发保护不变。raw ServerSocket CHUNK反例的结果如下；原HttpServer短写BODY截断并未被证实是旧间歇失败的根因。平台AI/Task核心未改，不放宽恢复分类或预算。

F01隔离修复本地提交7eb991c80e02afb94481b16a4bff3ef43d5cb7b2，仅两个生产类及两个直接测试文件；22个不同定点场景有最终通过证据（18 lifecycle+4 channel，分组复验累计）。原失败、夹具/权限环境修正及实际命令见ftp-stop-fix-verification.md和ftp-stop-fix-commands-and-results.json；验证进程0、fx28已清。当前已随7a321fa1提交链合入主仓，新轮实机尚未执行。

后续raw CHUNK注入中，16字节部分正文后断开、APPROVE/DENY两场景均产生UNKNOWN/HTTP_CHUNK_INCOMPLETE→SUCCEEDED，输入相同、旧client/lease先释放，只有完整批准后发送一次业务，完整拒绝零发送。另有原Problem保留2项、成功后配置复验1项、原生管理预算及授权1项通过。零正文注入在本机JDK却被交付为完整空响应，或未及时取得原结果，local-09/10/11失败证据全部保留；不把它改记为通过，不扩大恢复分类、超时或研究范围。实测Asset的accepted0/CHUNK忠实本地注入未验证，下一轮实机另证。

隔离修复源提交c2b99a9cb2c049c131b533ad2321b977597ede01，13文件；6个直接场景及19个未受后续整理影响的相关场景已有通过证据，去重为25个，不能称单批全绿。local-12仅重跑整理raw fixture影响的两个16字节场景，2/2通过、12.244秒、Maven0。完整命令、各批失败及原JUnit见generation-recovery-verification.md和generation-recovery-commands-and-results.json。自有验证进程0、a28已删除；后续cherry-pick为37039deb并经组合验证，现已合入主仓，新轮实机待验证。

Survey Scoring原root e7a172a2-b0a7-492b-bbf4-7438f1991e7e，应用wtl-assisted-survey-scoring-7w1gnpm04，修订86f6d1a53dbbc61e275287b472314a3079a23984955dc30c547439ffd82f0107；完整业务、交付和额外产品收尾均通过。已知监测Token为部署96227、审批1053980，共1150207，118个generation attempt id；统计边界与前述一致。所有原始回执保留，严格首过及无内部异常指标均true。

### round-01/F04：Log Analyzer 命令提案后原任务 UNKNOWN

原root5dcf429d-4ca5-4f0c-b5b2-8e2f7a7e2ea7，应用wtl-assisted-log-analyzer-8qe3cfz6g，原隔离库assisted-live-log-analyzer-8qe3av9z4保留。JUnit1失败、0错误/跳过、599.710秒，Maven1，无完整业务或交付；known tokens232576，30个generation attempt id，额外操作不含。

最后部署进度为AI_COMMAND_PROPOSED，拟为原生伴随组件补装CMake>=3.25和Ninja；最后审批生成已成功返回，不能据此断定实际命令已派发或失败根因。Sol先只读调查原回执和精确任务元数据，不读取活动Directory库、不重放原UNKNOWN。captureDenials的重叠文件锁是最终断言后的次生错误，不能替代原任务原因。

已取得原库确证：UTC21:21:53.535Z的DEPLOYMENT_COMMAND_REVIEW_DENIED拒绝该root apt-get update/install，理由涉及未验证maintainer scripts、缺版本固定/官方digest及root-owned不可写安装后态，要求使用受控catalog准备路径。随后OPERATION_FAILURE(ACCESS_DENIED)及PUBLICATION_SESSION_UNCONFIRMED；模型生成SUCCEEDED不等于审批APPROVE。真只读shared lock诊断进程自然退出0，实际3根文件前后SHA/mtime/长度一致。持久任务行RUNNING与测试投影UNKNOWN分别保留，不改写为同一状态。后续源码定位确认的准备缺口见下文；审批是否过严仍不据此推断。

源码已确认需求包含native C/CPP，但标准SDK准备跳过SYSTEM_COMPILER，后探测才反馈CMake/Ninja缺口。现有preparation-gap允许完整审查的non-catalog补充，且没有取得末次脚本全文，因此不把审查者所有缺证理由或“必须catalog”当作独立验证的事实。详见log-analyzer-unknown-analysis.md。

Astra对具体跨协议接入作只读裁决（native-prerequisite-design.md）：复用既有dependencies叶子程序/包表，不让standard直接依赖Agent引擎；新增仅NATIVE_BUILD的common操作，机械绑定完整source、argv、operation、精确targets及当前source/plan/task。仅post-SDK实际探测缺口才准备，严格receipt后fresh probe/完整Matcher，首次与recoverSelection同门禁；不满足后态专门终止，不再自由补装。Sol已在nb28实施并提交7a321fa168927bbfdacec9a32179f54a386d5446，主仓已快进合入；不放松原DENY或扩大预算，新轮实机待验证。

两项修复已在隔离组合分支形成b445857faf0868c4fb8ce0a69ad26064f3843aaf（包含F01、恢复接入及一行既有严格时序开关）。只运行agentManagementUsesActualTaskAppAndFreshGrantForEveryRemoteAction，1/1通过、46.829秒、Maven0，实际同时核对审批前未attempted、execute前CAS、恢复预算、APP及fresh grant。组合无失败重跑，自有进程0、cm28已清；round-01运行期间主仓保持5f；轮末已随7a321fa1合入。详细命令与JUnit见combined-fix-verification.md。

Directory Diff原root1c3c1d71-8549-419a-a406-9161c398f50f，应用wtl-assisted-directory-diff-8y4jib0jc，隔离库assisted-live-directory-diff-8y4jglk1c；完整业务、CLI_CLEAN、交付身份及实际app-run用法核对通过。known tokens79010+403443=482453，51个generation attempt id，额外操作不含。严格首过及无内部异常指标均true；没有用它的通过覆盖此前Log Analyzer的拒绝。

Binary Inspector原root5533f7a1-4dfb-4300-bb9f-6fdc154d206b，应用wtl-assisted-binary-inspector-955oapjvc，隔离库assisted-live-binary-inspector-955o90pa8；完整业务、CLI_CLEAN、交付身份及实际app-run用法核对通过。known tokens115403+328796=444199，42个generation attempt id。严格首过及无内部异常指标均true。

### round-01 整轮核查与收尾

八项目各只有一次launch，没有外层retry/recovery启动；初始清场、HEAD、源码、实际参数及模型配置一致性均通过。monitor-final-inspection-01.json给出goalEligible=false，唯一拒绝门为allAttemptsEligible；不能把已通过4项与后续新轮拼为8/8。monitor-final-usage-01.json记录known tokens合计4606956、496个唯一generation attempt id；Asset/CSV的2次物理调用usage未知，32条能力探测的provider usage/调用数亦未知，因此不是完整账单。

本轮driver17758正常quit并打印RUNNER_EXIT、进程退出0；独立核查本轮驱动和主仓测试JVM匹配0，clean28r01已按绝对路径及非reparse检查删除。原失败数据库、UNKNOWN及所有原证据保留；未重放失败效果。相关回执见runner-exit.json、main-live-process-final-01.json和temporary-cleanup-final-01.json。随后修复已合入主仓，尚未清场或开始round-02。

### F04定点与下一轮准备

7a321fa1包含8个核心Java文件、4个直接测试文件、原样移动的两资源及必要说明/索引。61个相关JUnit场景分组都有最新适用通过证据，并非单批61全绿：包括完整source/argv/profile/目标篡改拒绝、当前plan/source/archive/layout/task绑定、APPROVE单次grant与DENY零grant、严格receipt、取消优先级、fresh后态和正式recoverSelection。完整receipt后取消为CANCELLED，不完整receipt同时取消仍为UNKNOWN；二者均零后续probe/upload/build/第二事务。

首两批测试失败涉及夹具元数据/typed诊断入口、来源伴随声明及测试断言，原失败保留；生产保护未因此放宽。父代码审阅发现并修复的两项直接边界为receipt确证前取消优先级、32位纯数字operation的JSON类型校验。两资源原字节SHA不变、R100移动，最终隔离打包通过且JAR仅含新共享路径。证据见native-prereq-verification-data.json及native-prereq-*原始命令/XML/退出文件。n28已清、自有进程0，源码工作树干净。

主仓已由冻结5f快进至7a321fa1，包含F01、恢复接入、组合反例及F04；论文已有修改保留、未推送。下一步只清部署编译输出，保留其他任务的target截图/证据，跳过测试重建实际CLASS/Mod描述符；之后在新冻结HEAD下产品清场、独立复查，再执行八项首次完整新轮。当前没有round-02业务结果，所有实机修复效果仍待新轮证明。
