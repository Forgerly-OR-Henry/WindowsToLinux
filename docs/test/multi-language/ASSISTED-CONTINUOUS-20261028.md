# ASSISTED 连续首过验证记录

状态：进行中，尚未达成目标。仅通过测试代码执行真实模型、SSH、产品部署及既定业务验证，不使用真实界面或浏览器。

验收口径：每轮清除一次性测试机已盘点的部署及测试软件环境，在冻结代码和参数下执行既定八个项目。只有同一轮八项首次完整通过才达成；失败后修复、恢复及外层重试单独计数，下一轮重新清场。模型SDK内部自动请求重试及只读观察另记，不自动等同整项目重试；原任务终止失败、UNKNOWN、取消或人工补救不能计首过。额外保留无内部异常/重试指标。清场不等于重装操作系统。

## 逐轮结果

| 轮次 | 冻结版本 | 清场复查 | 首次结果 | 结论 |
| --- | --- | --- | --- | --- |
| round-01 | 5f1f66119cfd20dcdfdc6e424f7ea7a0f178baa3 | 清场执行及独立复查通过 | 八项各一次：首过4、失败/UNKNOWN4 | 未达成；保留失败，修复已合入，新轮另记 |
| round-02 | d5257d7ff4cecec8f2c75e31b92308c5743743e9 | 产品清场及独立复查通过 | 八项各一次：首过7、失败/UNKNOWN1 | 未达成；CSV内部恢复1，额外strict=false；FTP修订已合入 |
| round-03 | 37ecb0c1b0bc21dfbf0f40622b2bfdd94e48f537 | 预检P01失败，维护后完整清场及独立复查通过 | 八项各一次：首过4、失败/UNKNOWN4 | 未达成；四项失败保留，三项改动轮末合入，准备R04 |

## 失败原因与解决方案

从本轮开始持续记录。每项记录包含原任务身份和证据位置、失败现象与直接错误、确认原因与仍未知之处、实际解决方案、相关定点验证及后续完整轮次是否复现。测试准备或清理失败也单列；不会把诊断增强表述为底层故障已修复。

| 记录 | 阶段与现象 | 确认原因与未知项 | 解决方案 | 复验结果 |
| --- | --- | --- | --- | --- |
| round-01/P01 | 连接预检被固定指纹守卫拒绝；connection-01退出1，SSH_TRUST_CANCELLED，无远端清理或部署 | 当前端点返回的SSH密钥与历史预期不同；密钥变化原因未知。不是已证实的产品部署缺陷 | 用户明确确认目标及新指纹后，建立新隔离连接状态并固定获准指纹，继续产品认证；保留旧指纹拒绝证据 | connection-02用最新图片凭据产品认证成功、退出0；该预检阶段无部署尝试 |
| round-01/F01 | FTP部署成功，业务backend STOP任务UNKNOWN | 当前首次派发前过早登记attempted，独立审批将其误认为历史未确认重放 | 在APP及fresh grant后、同一请求execute前，通过现有guard进行attempted CAS；旧UNKNOWN不重放 | 提交7eb991c8，22个不同定点最终通过；R02构建拒绝、R03上传超时均未到选定STOP，完整业务待R04 |
| round-01/F02 | Asset Lending审批HTTP_CHUNK_INCOMPLETE、accepted0，原任务UNKNOWN | 外部断流具体位置未知；ASSISTED调用者未选择平台已有恢复能力 | 显式选择现有有界纯生成恢复；保留原Problem、尝试历史及未知usage，禁止业务重放 | 部分正文CHUNK本地恢复通过；R02原任务完整首过但未触断流；零正文CHUNK忠实注入未验证 |
| round-01/F03 | CSV Inspector审批HTTP_CHUNK_INCOMPLETE、accepted4083，原任务UNKNOWN | 与F02同类传输错误，外部是否同因未知 | 同F02；不将原UNKNOWN改写为成功 | R02同一原任务内真实CHUNK恢复后完整首过；accepted字节数未知，旧失败保留 |
| round-01/F04 | Log Analyzer原部署UNKNOWN，无完整业务验收 | 独立审批完整DENY补装命令；标准准备跳过SYSTEM_COMPILER，未自动补native前置工具；不是断流 | 实际缺口时一次受控NATIVE_BUILD准备、严格receipt及fresh probe；完整source/当前scope/APP和fresh grant保留 | 7a321fa1及61相关定点；R02新native准备→fresh probe/Matcher→build→完整CLI首次通过，旧UNKNOWN保留 |
| round-02/F01 | FTP原部署在构建前完整DENY后UNKNOWN，无下载业务 | 审批将Agent参数布局误套标准build-run，判断与源码不符；审阅说明仅显式列Agent布局，可能促成混淆 | 补清标准/Agent布局及已校验请求的有序参数事实，保留完整审核和绑定，不改实际argv/重试DENY | 提交117a01cb，12定点通过；R03原部署SUC、已跨拒绝点，完整FTP业务因上传超时仍未过 |
| round-03/P01 | 清场inventory因受管根中挂载而拒绝，项目尚0启动 | R02 FTP UNKNOWN保留候选ext4 loop卷；reset在stop之前拒绝含挂载根，守卫有效 | 新维护任务调用完整校验的既有candidate-cleanup，正常卸载/分离，再执行完整清场与独立复查 | 维护exit0/CANDIDATE_CLEANED=1；后续reset及独立after通过，原UNKNOWN保留 |
| round-03/F01 | TaskBoard最后工具链bind完整DENY后UNKNOWN | 审批以backend JAVA要求审查NODE/PYTHON绑定；bind缺当前组件计划锚点，跨组件混淆为源码链支持的工程判断 | 精确绑定当前typed计划、canonical实际准备回执与命令；两准备路径捕获原计划observer，保留全图和独立审批 | eb21655e、38去重定点有适用通过证据；轮末合入，新轮实机待验证 |
| round-03/F02 | FTP部署SUC，首1MiB上传响应20秒超时，无完整业务 | Node/Go约30秒仅读入213808字节，读耗时远大于写；具体网络/client/Node原因未知 | 保留原失败及窄窗口证据；新增被动流控/有界进度诊断，不改负载/期限 | 3a1f3f05、12相关场景适用通过；仅诊断增强，根因未定，额外下载夹具失败保留 |
| round-03/F03 | CSV publication.inspect SSH输入等待20秒超时，原UNKNOWN | 通道已开、输入线程等远端窗口；具体SDK/链路/远端原因未知，日志遗漏SDK已有结构字段 | 补严格typed安全日志白名单及正反例，不改SDK/期限/权限/重试 | e587ead1、3方法修后全过，原红证据保留；诊断改善，具体根因未解 |
| round-03/F04 | Survey prepare完整DENY后UNKNOWN | 拒绝理由与既有fd桥接合同不符；源码/挂载链无已证矛盾，末次APP全文未独立导出 | 真只读审计并核既有桥接/合同/挂载，不复制已有说明、不强制APP、不重试DENY；新轮另证 | 原3文件前后及最终审计全同、诊断exit0；未发现可证新代码缺陷，不声称根治模型误判 |

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

本轮driver17758正常quit并打印RUNNER_EXIT、进程退出0；独立核查本轮驱动和主仓测试JVM匹配0，clean28r01已按绝对路径及非reparse检查删除。原失败数据库、UNKNOWN及所有原证据保留；未重放失败效果。相关回执见runner-exit.json、main-live-process-final-01.json和temporary-cleanup-final-01.json。随后修复合入主仓，再清场执行独立round-02（结果见后文）。

### F04定点与下一轮准备

7a321fa1包含8个核心Java文件、4个直接测试文件、原样移动的两资源及必要说明/索引。61个相关JUnit场景分组都有最新适用通过证据，并非单批61全绿：包括完整source/argv/profile/目标篡改拒绝、当前plan/source/archive/layout/task绑定、APPROVE单次grant与DENY零grant、严格receipt、取消优先级、fresh后态和正式recoverSelection。完整receipt后取消为CANCELLED，不完整receipt同时取消仍为UNKNOWN；二者均零后续probe/upload/build/第二事务。

首两批测试失败涉及夹具元数据/typed诊断入口、来源伴随声明及测试断言，原失败保留；生产保护未因此放宽。父代码审阅发现并修复的两项直接边界为receipt确证前取消优先级、32位纯数字operation的JSON类型校验。两资源原字节SHA不变、R100移动，最终隔离打包通过且JAR仅含新共享路径。证据见native-prereq-verification-data.json及native-prereq-*原始命令/XML/退出文件。n28已清、自有进程0，源码工作树干净。

主仓已由冻结5f快进至7a321fa1，包含F01、恢复接入、组合反例及F04；论文已有修改保留、未推送。下一步只清部署编译输出，保留其他任务的target截图/证据，跳过测试重建实际CLASS/Mod描述符；之后在新冻结HEAD下产品清场、独立复查，再执行八项首次完整新轮。这是R02启动前的准备状态；后续实机结果见round-02记录。

round-02已冻结在报告提交d5257d7f（源码为7a321fa1），主仓实际输出重建通过，Maven0/28.126秒、测试跳过；新JAR及CLASS资源无旧路径残留、descriptor及55项classpath已核。仅清理部署编译目录，其他target截图/验证资料保留。driver14832使用原获准指纹及隔离凭据，先执行清场及独立复查，再启动项目。下一轮独立原始证据位于`.ai-workspace/evidence/assisted-continuous-20261028/round-02/`，不复用本轮业务结论。

随后plan/apply/独立after均通过。计划SHA9d64d8999a519e1a793dfd1b56cc5dc0dc307358194e56d5c93e347525cf13cd，实际清除5受管根/9账户/10服务文件，停止10服务、卸载40开发包并清APT缓存。新任务独立盘点与postInventory相同，受管根、服务、账户、目标包与缓存均空；initial-clean-verification-01.json及严格核查通过。06:06启动Task Board原root a9ef5190-1359-4c27-8dfe-410e321bc271，其余七项已排队，仅实际launch才计尝试；该时点尚无本轮完整PASS；最终结果见下表。

### round-02 项目结果

| 项目 | 首次结果 | 原始验证及收尾 |
| --- | --- | --- |
| task-board | PASS | JUnit1/0失败/0错误/0跳过，1693.642秒，Maven0；业务/重启持久化/交付及额外cleanup0 STOPPED/DISABLED，内部异常/重试0 |
| file-transfer | NOT_PASS | 构建请求完整DENY，原部署UNKNOWN；JUnit1失败/0错误/0跳过，394.754秒、Maven1，无下载/业务/交付 |
| asset-lending | PASS | JUnit1/0失败/0错误/0跳过，1240.842秒，Maven0；完整业务/交付及额外cleanup0 STOPPED/DISABLED，内部异常/重试0 |
| csv-inspector | PASS | JUnit1/0失败/0错误/0跳过，1355.917秒，Maven0；业务/交付及额外cleanup0 STOPPED/DISABLED；有一次SDK内部CHUNK恢复，整任务eligible=true、额外strict=false |
| survey-scoring | PASS | JUnit1/0失败/0错误/0跳过，2182.627秒，Maven0；业务/交付及额外cleanup0 STOPPED/DISABLED，内部异常/重试0 |
| log-analyzer | PASS | JUnit1/0失败/0错误/0跳过，825.757秒，Maven0；新native准备及完整CLI业务/CLI_CLEAN、交付INSTALLED/N/A通过，内部异常/重试0 |
| directory-diff | PASS | JUnit1/0失败/0错误/0跳过，423.554秒，Maven0；完整CLI业务/CLI_CLEAN及交付INSTALLED/N/A通过，内部异常/重试0 |
| binary-inspector | PASS | JUnit1/0失败/0错误/0跳过，378.235秒，Maven0；完整CLI业务/CLI_CLEAN及交付INSTALLED/N/A通过，内部异常/重试0 |

Task Board原root a9ef5190-1359-4c27-8dfe-410e321bc271，应用wtl-assisted-task-board-9w0efnlbk，修订33e313225f42f6c9139c2f5d860807e9079ba55ec887f88ba46962bd5bfc87e4，隔离库assisted-live-task-board-9w0edym3s；严格eligible及无内部异常指标true。known tokens109955+870229=980184，90个generation attempt id，统计边界与R01一致；不将其与R01的其他PASS拼接。

### round-02/F01：FTP构建请求被完整拒绝

原root6a99ea19-e827-430f-a0ac-6d4e91a632dc，应用wtl-assisted-file-transfer-aj4jz64vw，隔离库assisted-live-file-transfer-aj4jxhsic；known tokens82942+247243=330185，47个generation attempt id。原部署UNKNOWN，无任何下载业务，尚未到上一轮的后端STOP；不能据此说旧时序修复复现或通过。

原库UTC22:43:02.024的DEPLOYMENT_COMMAND_REVIEW_DENIED认为build-run的8参数缺少source revision、candidate/task重复且顺序与规定不符；随后ACCESS_DENIED及DEPLOYMENT_GRAPH_REMOTE_UNCONFIRMED。最后模型生成SUCCEEDED/transport NONE不代表审批APPROVE，本命令未派发。该理由已保留，真实参数合同仍待源码对照，不把审查者判断直接当成已证构造缺陷。原库task/execution均UNKNOWN，真只读审计3根文件SHA/mtime/长度不变、Java自然退出0；旧效果不重放。调查及修复在隔离树完成，本轮全部结束前主仓维持冻结。

源码对照已确认：标准build-run的9参为application,candidate,seconds,processes,memory,output,engine,inputSha256,binding，生产者与22-restricted-build.sh消费一致；Agent agent-run的另一9参包含task/sourceRevision，并由适配器转标准入口。合同SYSTEM文本只显式列Agent布局，审批的参数判断与标准合同不符。实际每个值未从原exactAction独立导出，合法布局结论来自审批前强制collector round-trip和源码链；不把推导写成原日志完整取值，也不将缺说明认定为可读出的模型内部因果。修复仅澄清两入口及已校验请求的有序参数导航，保留源/任务/工作流完整链、字节和目标守卫、独立DENY；nb28隔离实施并验证；本轮全部结束前main源码/HEAD维持冻结。

隔离分支已提交117a01cbfb4ee56d0d810011da923b43965f7d49，2生产+3测试、108+/3-；唯一一批相关12项通过、19.853秒、Maven0，不是全类/全模块测试。导航仅在完整collector render round-trip与stdin SHA校验后生成，明确原task/workflow/sourceArchive须另行核验，不形成执行许可。完整APPROVE、DENY零grant及6种字节/参数篡改反例保留；实际命令、两个消费者、原守卫、权限与预算未改。消费者原字节不变，Java producer段的首次CRLF审计差异已在audit02按LF及gitdiff核清，原audit01仍保留，不当作产品失败。自有进程0/b28已清，详见build-review-fix-verification.md及原JUnit/命令/退出码。R02全八项完成并正常退出后，主仓已快进117a01cb；完整业务和收尾效果待第三轮清场首测。

Asset Lending原root dade8824-7a7a-4aa4-9d55-b98aa1f93a86，应用wtl-assisted-asset-lending-ao8py7w14，修订c0514b91b31e78cd1beef4fc122cb675f2339a1c120272bd49437df26825ab41，隔离库assisted-live-asset-lending-ao8pwoj7g；严格eligible及无内部异常指标true。known tokens70469+659047=729516，79个generation attempt id，统计不是完整账单。R01原F02仍保留，不因新任务通过改写原UNKNOWN；本次没有观察到不完整响应，不能称实机零正文恢复分支已经验证。

CSV Inspector（原root f010db63-1ec0-4694-8119-1fb0438f4129）运行中已观察到一次真实SDK纯生成恢复：同一APPROVAL/candidate.host-probe窗口及generation ID71e781a7-fedf-405a-ad62-3f7fd0d505e6，number1为UNKNOWN/HTTP_CHUNK_INCOMPLETE、46961毫秒，number2为SUCCEEDED、7713毫秒，最终logical outcome SUCCEEDED；accounting为physicalAttempts2、unknownUsageAttempts1。原任务仍运行，没有进入UNKNOWN或retained uncertainty，后续继续工具链步骤。此为内部恢复而非整项目重试，额外无内部异常指标已false；项目最终是否完整首过仍待业务/交付/收尾。accepted body字节未打印，不能宣称零正文复现或直接观测了重试输入完整hash；随后另一个独立成功请求不是本恢复证据。最终统计将区分唯一generation IDs与物理事件数，未知usage不补0。

CSV最终完整首过通过，应用wtl-assisted-csv-inspector-b5kgn2t8g，修订0c91665f63808518346d2a7df7a5fb0a88db39b099564f4127f438371bc16d39，隔离库assisted-live-csv-inspector-b5kglit3w；原task从未转UNKNOWN，完整业务/交付/最终STOPPED-DISABLED和退出均通过，既定整任务首过口径eligible=true。内部恢复事实不删除，因此额外无内部异常指标false。known tokens40386+642593=682979，唯一generation IDs65、原始物理event66、唯一(id,number)66；源码统计逐physical累加已知usage，第二次成功调用未漏计，未知usage不填0。R01原CSV UNKNOWN依旧保留，此新任务不重放旧效果。

Survey Scoring原root9732c5a0-77c9-456c-ab66-8b42cb0be25e，应用wtl-assisted-survey-scoring-bof5rdzyf，修订788c79e52cc87935f3d1f14374077bf20fda070fbb9a5c169948b35e76901829，隔离库assisted-live-survey-scoring-bof5pi7dc；eligible/无内部异常指标均true。known tokens93049+1074223=1167272，118唯一generation IDs及118物理events，额外调用/能力探测不含。Log Analyzer已启动原root667e6513-cae3-4ca8-989d-b73e125e2404，后续实际native准备及业务结果仍待证明。

Log Analyzer已真实执行新增受控原生准备：原日志882行直接NATIVE_BUILD/APT回执，operation51eba14403dc4def9c747f2b2d5dbbe5；请求包cmake3.28.3-1build7、ninja-build1.11.1-2、gcc/g++4:13.2.0-7ubuntu1，紧邻命令SUCCEEDED/exit0/未截断。其后fresh host-probe及candidate create/query/upload/build已出现；源码强制严格receipt、重新探测及完整Matcher门禁，因此可推导准备准入验证已过。回执字段是直接观察，完整门禁是源码加控制流证据；不把4个请求包当完整依赖闭包或4次安装，不伪造未打印的独立fresh grant marker。native-prerequisites-runtime-proof-01.json保留父核查；该项随后完整CLI业务首次通过；R01原F04 UNKNOWN仍保留。

Log Analyzer最终完整首次通过：root667e6513-cae3-4ca8-989d-b73e125e2404，应用wtl-assisted-log-analyzer-ciha39k9w，隔离库assisted-live-log-analyzer-ciha1o800；eligible及无内部异常指标true。known tokens47900+368536=416436，40唯一generation IDs=40物理events/对；完整CLI业务、CLI_CLEAN、INSTALLED/N/A及实际app-run用法核对通过。R01缺口对应的新受控准备和完整业务现有同一首次任务实机证据，未使用旧任务恢复或重试覆盖原失败。

Directory Diff原root26b7c4e3-fa9d-4b04-8607-c0dc9fcd955d，应用wtl-assisted-directory-diff-ct3ggz96o，隔离库assisted-live-directory-diff-ct3gfayfg；eligible及无内部异常指标true。known tokens76475+297578=374053，42唯一generation IDs=42物理events/对；完整CLI业务、CLI_CLEAN与交付用法匹配。Binary Inspector随后首次完整通过；本轮仍保留FTP首次失败，不能与下一轮结果拼接。


### round-02 整轮核查与收尾

Binary Inspector原root aa9151da-dbda-466c-ab79-7b40fe8a404a，应用wtl-assisted-binary-inspector-cykt1y0xw，隔离库assisted-live-binary-inspector-cykt09cww；eligible及无内部异常指标均true，known tokens73511+288809=362320，40唯一generation IDs及40物理事件。

八项目各一次，无外层retry/recovery；清场、冻结HEAD/源码/参数/模型配置及尝试数门槛全部通过。monitor-final-inspection-01.json仅因allAttemptsEligible拒绝，本轮7/8、goalEligible=false。CSV的内部纯生成恢复不改变整任务首次通过，但额外无内部异常指标false，不能称八项无异常通过。

monitor-final-usage-01.json：known tokens5042945，唯一generation IDs521、物理事件522、唯一(id,number)522；CSV有1次未知usage。32条能力探测的用量与实际调用数未知，额外收尾不在此统计内，因此不是完整账单。

driver14832正常quit，RUNNER_EXIT及实际退出0均已观察；独立CIM核查驱动和主仓验证消费者0，clean28r02经绝对路径与非reparse检查后删除。回执为runner-exit.json、main-live-process-final-01.json、temporary-cleanup-final-01.json。原失败数据库、UNKNOWN与原始证据继续保留。

轮末主仓由d5257d7f快进至117a01cb，包含FTP构建审阅合同澄清，论文既有修改保留且未推送。第三轮脚本已准备；下一步重建实际CLASS/Mod输出、执行新一轮产品清场及独立复查，再在新的冻结HEAD下八项目各首次执行，不复用前两轮PASS。


## round-03 准备阶段

冻结版本37ecb0c1b0bc21dfbf0f40622b2bfdd94e48f537（含117a01cb）。实际CLASS/JAR/Mod输出重建成功，Maven0、19.917秒、测试跳过；新standard合同与helperInvocation事实均在实际JAR和CLASS核验，55项classpath有效。driver83083/Python35772，仅测试代码。

### round-03/P01：清场预检发现保留候选挂载

首次inventory在inventory-roots阶段以TARGET_CONTAINS_MOUNT拒绝/var/lib/windowstolinux，JUnit1失败、Maven1；八项目尚0启动，无部署失败或部署重试。没有放宽挂载守卫或强制删除。

通过产品SDK新只读维护任务核明：唯一受管挂载为R02失败FTP backend候选wtl-assisted-file-transfer-aj4jz64vw-backend-1448e1dbcc8a5d42/mutable，ext4 /dev/loop3，BACK-FILE精确对应候选.workspace.ext4。不是PrivateMounts；成功的systemctl list-units windowstolinux*/wtl*仅列inactive/dead且MainPID0的workspace-recovery服务。R02其他七项均清过候选，原UNKNOWN任务保留候选卷；通用reset在stop之前拒绝含挂载根，因此需显式清理该自有构建卷。

授权清场维护以新的产品任务742a965d-4269-4685-87d7-32e81bc2bea9调用已有DeploymentManagedHelperBundle.verifiedCommand固定candidate-cleanup：完整已安装helper字节/ownership守卫、精确app/candidate、原生stop/freeze及受控正常umount/detach、候选删除。结果SUCCEEDED、exit0、未截断、CANDIDATE_CLEANED=1，维护JVM自然退出0；无lazy/force umount，未重放原部署/build业务，原UNKNOWN库及日志保留。该流程的findmnt观察仅限当前mount namespace，不宣称全局所有namespace核验。原证据为mount-observation-01、mount-cleanup-01及host-before-01各launch/log/exit/回执。

随后重做host-before-02，只在完整plan/apply及独立after均通过后启动八项目；预检失败不删除，也不写成首次清场无异常。

R03清场planSHA01ee9dd57006dcc044103bed1a1fd597d7c8d16d505b992ff822d46e0c6ad8cf；apply停止13服务、删除13服务文件/12账户/5根、卸载40开发包并清缓存。独立after与postInventory精确一致，8根均无、服务/账户/目标包/缓存均空，initial-clean-verification-01.json及核查器通过。预检失败另记；八项目在此后才启动。


### round-03/F01：Task Board 工具链绑定阶段 UNKNOWN

原root95024adc-5fca-4b81-9f0f-c494a8872fbf，应用wtl-assisted-task-board-deanvb05s，隔离库assisted-live-task-board-deantluuk；JUnit1失败、0错误/跳过、Maven1，无完整业务/交付。known tokens514793、54唯一generation IDs/54物理事件、未知usage0（能力探测等仍不含）。

静默后原prepare返回成功，NODE24.22.1及PYTHON3.12.16回执已出现；最终最后阶段deployment.toolchain.bind.v1，原任务UNKNOWN/retained uncertainty，具体原因待只读原失败库与源码核实。APPROVAL角色生成SUCCEEDED不等于审批APPROVE，captureDenials重叠文件锁是最终断言后的次生问题。原任务不重放，主仓本轮37ecb0c1继续冻结；FTP已实际启动，其余场景继续，不将此项重试覆盖首次失败。

F01真只读原库已确认UTC01:03:03.351Z完整DEPLOYMENT_COMMAND_REVIEW_DENIED：最后绑定仅NODE24.22.1/PYTHON3.12.16，而审批认为backend JAVA21覆盖缺失。先前JAVA21 prepare及bind已有成功回执；最后绑定未派发。诊断JVM退出0、实际3根文件SHA/mtime/长度全同。源码Scope只为prepare呈现当前已验证计划，bind缺对应组件/requirements/runtimeDependencyReason事实，且各计划仅绑定自身selections；跨组件混淆为源码链支持的工程判断，末次完整审批输入未独立转储。隔离tb28修复将绑定事实限定到当前严格计划、实际canonical准备回执及精确请求，同时保留全图审查和完整DENY；main本轮保持37ecb0c1，不在活动轮合入。


### round-03/F02：FTP首个上传分块响应超时

原部署root7c8d7a42-c071-45c5-a07d-8089d0712113为SUCCEEDED，应用wtl-assisted-file-transfer-dngnjumyk，隔离库assisted-live-file-transfer-dngni3ft0。本次已越过R02构建拒绝点，但首次完整业务NOT_PASS：JUnit0失败/1错误/0跳过、1024.233秒、Maven1；known tokens721597、77唯一generation IDs/77物理事件、未知usage0。没有下载VERIFIED、完整业务PASS或交付；没有retained uncertainty，与原部署UNKNOWN区别保留。finally打印该应用STATIC_LIVE_CLEAN stopped/disabled，仅证明当时收尾核验，不是当前主机再次盘点；没有额外成功交付cleanup回执。

原客户端chunk0声明1048576字节，5毫秒本地body write返回不证明服务端收到；随后RESPONSE_WAIT阶段20010毫秒SocketTimeoutException。产品SDK新只读任务提取原web/backend的UTC01:17:30–01:19:00日志，精确同uploadId/chunkIndex，无HTTP重发、服务启停或原任务恢复，JVM退出0。

网关requestBodyBytes与Go sourceReadBytes均仅213808、请求不完整。Go BODY_COPY_END约30000.829毫秒，sourceRead调用累计29995.227毫秒、stagingWrite累计3.840毫秒；锁/会话查询/创建暂存及sync耗时很小。网关约30006.790毫秒记录上游ECONNRESET，后端读超时并400。证据说明完整请求体未到达，不能将瓶颈归为已证磁盘写慢；这些壁钟调用时长也不等于纯网络耗时，尚不能唯一划分网络、客户端及代理调度原因。客户端与服务端时钟不当作已同步时间轴，未修改业务负载、chunk大小或超时来掩盖。

原始ftp-upload-journal-01.json及ftp-upload-phase-proof-01.json保留，精确窗口程序/launch/log/exit亦保留。该原因目前未确证为产品代码缺陷，继续调查；Asset及后续原队列不受此诊断改变，R03仍保持冻结37ecb0c1。

R03/F01已在隔离分支提交eb21655e6e5a92cef07b82af9e830b7115477f4a：当前binding须匹配完整canonical真实receipt与原组件typed计划；初次prepare及recoverSelection使用捕获原计划的observer，回调前/许可后跨计划替换均failclosed。原无计划observer仅供诊断和有限展示，不能铸造绑定receipt。3生产/资源+5直接测试/harness，38个去重场景有最终适用通过证据，含真实本地工作流两路径；不是单批全绿或实机首过。多批夹具元数据/旧诊断协议/指纹错误全部保留，实际命令/接口/codec/SDK准备/消费者/APP权限及预算未改；最终skipTests打包通过，进程0/cb28已清。父核查生产diff和原JUnit，当前主仓本轮不合入；详细component-bind-fix-verification.md及parent-review-01.json，真实APP判定和业务效果待新的清场轮次。

Astra对R03/F02的只读裁决见ftp-upload-timeout-assessment.md：Go约30秒body read timeout与既有ReadTimeout一致，Node后ECONNRESET更像级联，不能倒作最初网络断连；累计77次read和首末值不能证明全程匀速慢入。尚不足以按已证根因更改业务或放宽期限。下一轮拟补两文件的被动pause/resume/drain/队列/有界进度及tick-gap观察，原12事件额度/终止信息保留，timer unref及全路径清理；排队字节不是peer receipt，tick-gap不唯一指向event-loop故障。仅诊断增强，main R03不改，原失败不覆盖。

### round-03 Asset Lending首次完整通过

原rootb7142583-113a-41a5-9ff3-2c1d2bec7a23，应用wtl-assisted-asset-lending-e0mdef6ro，隔离库assisted-live-asset-lending-e0mdcn9os，修订a285d3d7abc29305fcb42ac39e94632f76c94be219655d55cc474b2e1eca23c2。JUnit1/0失败/0错误/0跳过、1387.932秒、Maven0；完整业务/交付及额外cleanup退出0、STOPPED/DISABLED均通过，eligible/额外strict均true。known tokens715252，78唯一generation IDs/78物理事件、未知usage0；能力探测及额外操作不含。当前本轮1完整首过+2失败，后续CSV及余项继续，不与旧轮PASS拼接。


### round-03/F03：CSV发布后inspect的SSH输入等待超时

原root2f3d3ef4-6326-4bd2-9b3f-0d17e7ebaf4e，应用wtl-assisted-csv-inspector-ejugvlr0w，隔离库assisted-live-csv-inspector-ejugtuzrk。JUnit1失败/0错误/0跳过、879.286秒、Maven1；无完整业务/交付，原UNKNOWN及retained uncertainty保留。known tokens565845，52唯一generation IDs/52物理事件，未知usage0（能力探测/额外操作仍不含）。

最后APPROVAL生成2ed2fbea…成功返回16389毫秒，随后deployment.publication.inspect.v1的实际SDK CommandResult为UNKNOWN、exit未知、未截断；原Problem SSH_COMMAND_TIMEOUT/20000毫秒。channelOpenConfirmed/sessionOpen/sessionAuthenticated=true，无输出/退出；inputState=SENDING、线程TIMED_WAITING，在Apache SSHD RemoteWindow.waitForSpace，transportReadCompletedAgeMillis15972。这不是已证审批拒绝，亦不能由线程采样唯一确定SDK缺陷、链路慢或远端读入原因。

父用共享文件锁+H2 ACCESS_MODE_DATA=r真正只读原失败库，3根文件SHA、完整mtime及长度前后全同，JVM自然退出0；task UNKNOWN、execution EXECUTING/UNKNOWN，business_committed/cleanup_complete=false，事件DEPLOYMENT_GRAPH_HEALTH_UNCONFIRMED，无原任务重放。csv-inspect-unknown-readonly-01、csv-inspect-timeout-proof-01保留原证据；后处理曾有一次可选空message事实解析KeyError，修正为get后重读同一只读输出，没有重开原库或改原结果。

源码进一步确认：SDK已有scriptBytes/stdinBytes、frameWritePhase、authorPayloadState及remoteWindowOpen/Failure数值，但测试日志safeOriginalCallCauses白名单未放行；该方法无limit截断，不能误记成Map顺序造成。下一轮拟只补两个测试代码文件的严格类型白名单及正反例，保留无载荷/地址/凭据日志边界，不改SDK、命令权限、期限或重试。长度/写阶段/窗口快照不证明实际完整发送或远端执行；此为诊断改进，尚未解决具体SSH根因。Survey及其余原队列继续，主仓37ecb0c1不变。

R03/F02被动诊断已提交3a1f3f05371f9e9d6624a7f0aa96e4f38e0d159e，仅server.ts和upload-proxy.mjs。每秒unref采样、最多首个+最近15快照，首次观察结束另输出一次FLOW诊断；pause/resume/drain仅监听，原12事件/终止信息及pipe/超时/请求响应/下载代码不变。12适用去重定点通过（分批，不称单批全绿），12源不变项与语法检查通过、进程0/up28已清；全部失败保留。额外下载夹具reset未唯一归因，最终下载仅静态guard+health，不称下载功能稳定验收；本地未跑tsc/npm build，实际新轮构建另证。父审生产diff和原Node结果，详见upload-progress-fix-verification.md及parent-review01；main本轮未合入，FTP根因仍未知。

R03/F03日志改进已独立提交e587ead135a4e10bac1ef0071e7da0a87cc13c3d：仅两个测试Java文件增加严格enum/boolean/数字白名单及nested正反例，保留脚本/输入长度、帧发送阶段与远端窗口等结构字段，不包含载荷、地址、路径或任意错误消息。先用3方法重现新字段被过滤（2PASS/1FAIL），修复后同3方法全PASS、Maven0、14.223秒，原红证据保留，非6个场景。父审全部diff/原JUnit；5源不变审计通过，自有进程0/up28/sw28已清。没有SDK、权限、超时或重试改变，SSH根因未因此解决；详见csv-window-diagnostics-verification.md及parent-review01。main本轮未合入，三项隔离提交链eb21655e→3a1f3f05→e587ead1待八项结束及driver退出后再合入、清场新轮。

### round-03/F04：Survey Scoring准备阶段UNKNOWN

原root07b5dff1-8de6-419c-8b73-809f60b06dc9，应用wtl-assisted-survey-scoring-ev57wn8nw，隔离库assisted-live-survey-scoring-ev57ux9q0。JUnit1失败/0错误/0跳过、862.042秒、Maven1，无完整业务/交付；原UNKNOWN及retained uncertainty保留。known tokens383980、56唯一generation IDs/56物理事件、未知usage0。最后阶段toolchain.prepare.v1，生成完成不等于审批APPROVE或准备完成；具体直接原因待原库真正只读审计与源码核实。主仓继续冻结37ecb0c1，后三CLI照原队列首次执行，不重发Survey原任务。

R03/F04真正只读审计已确证完整APP DENY，公开理由认为prepare-diagnosed的canonical JSON只在fd0、未进入fd3，无法执行精确请求绑定。原库DEPLOYMENT_COMMAND_REVIEW_DENIED→OPERATION_FAILURE→GRAPH_REMOTE_UNCONFIRMED；最后生成SUCCEEDED不等于APPROVE。诊断JVM42104自然退出0、自有进程0，3根文件SHA、完整mtime、长度前后与最终复核一致。

限定源码核查发现本轮基线已包含926c8875的固定桥接说明：helper真实执行 `/usr/bin/python3 -I - "$@" 3<&0 <<'WTL_OFFICIAL_TOOLCHAINS'`，先从caller stdin复制到fd3，再将here-doc用作Python程序；tracked consumer读取dup(3)。既有真实Bash round-trip及删除/调换桥接反例已覆盖，合同明确caller无需额外fd3重定向。PreparationReview.facts校验完整literal command/targets/plan/args/limits及同canonical stdin，生态补充没有相反要求；AiJson对APPROVAL无条件挂载该SYSTEM合同，预算超限拒绝而非裁剪，没有发现机械漏挂。

因此本次完整拒绝理由与已固定合同语义不符，尚未发现可证的新代码缺陷。末次完整APP输入及当前组件未独立导出，不能唯一解释这一判断，也不另造相同说明、强制APP或重试完整DENY。实际解决处理为保留原失败、核对已有契约及挂载链、后续清场冻结新轮验证；不能称已根治模型误判。证据及限制见survey-prepare-unknown-analysis.md和survey-prepare-unknown-readonly相关原回执。

R03 Log Analyzer运行中直接观察同一APPROVAL/build.run生成151c4211-2462-4959-b396-11e531d43406内部恢复：#1 UNKNOWN/AI_TRANSPORT_UNKNOWN/HTTP_CHUNK_INCOMPLETE、47028毫秒、usage未知；#2 SUCCEEDED/NONE、6982毫秒、known input16876+output678。accounting为2物理尝试/1未知usage，最终logical generation成功；原root仍未终态，不能将生成成功写成APPROVE/部署或业务成功。extra strict已false，外层未重试，原UNKNOWN任务未重放；accepted body及运行时完整输入hash未打印，不当零正文忠实复现。log-generation-recovery-proof-01.json保留父核同原行与索引。

### round-03 Log Analyzer首次完整通过（内部生成恢复另记）

原roote8a782d0-b608-4ec7-8eff-2e4294ccef2c，应用wtl-assisted-log-analyzer-f68127k24，隔离库assisted-live-log-analyzer-f6810ig24。JUnit1/0失败/0错误/0跳过、715.618秒、Maven0；完整CLI业务、交付/实际app-run用法与CLI_CLEAN INSTALLED/NOT_APPLICABLE均通过，eligible=true。known tokens397786，43唯一generation IDs、44物理事件/唯一(id,number)，未知usage1；能力探测/额外操作仍不含。同id纯生成恢复保留，因此额外strict=false。新受控NATIVE_BUILD/APT已在原日志930行receipt、operation ef1ae11d1d214815a433636663d5593f、4请求包，邻接SDK SUC/exit0/未截断；后续fresh probe/Matcher门禁及完整CLI流程通过，不把4请求包当全部依赖或4次安装。累计本轮2完整首过+4失败，Directory及Binary继续。

### round-03 Directory Diff首次完整通过

原root8faf1fe9-7c3d-4e79-8f62-e97ef7cb2be6，应用wtl-assisted-directory-diff-fffmc67e4，隔离库assisted-live-directory-diff-fffmamvhs；JUnit1/0失败/0错误/0跳过、430.643秒、Maven0。完整CLI业务、交付/实际app-run用法及CLI_CLEAN INSTALLED/NOT_APPLICABLE通过，eligible和extra strict均true。known tokens383950，39唯一generation IDs/39物理事件，未知usage0；能力探测及额外操作不含。累计3完整首过+4失败，Binary最后一项继续。

### round-03 Binary Inspector首次完整通过

原root6b0dc67f-a4a5-4a20-b3ae-0801354025c4，应用wtl-assisted-binary-inspector-fl08ta034，隔离库assisted-live-binary-inspector-fl08roo1k；JUnit1/0失败/0错误/0跳过、332.762秒、Maven0，完整CLI业务/交付/实际app-run用法/CLI_CLEAN INSTALLED与NOT_APPLICABLE均通过，eligible及extra strict均true。known tokens363958，38唯一generation IDs/38物理事件，未知usage0；能力探测/额外操作仍不含。八项至此全部终止，本轮4完整首过、4失败/UNKNOWN，不能达成目标，等待整轮核查及正常退出后清场新轮。

### round-03 最终逐项结果及整轮收尾

| 项目 | 首次结果 | 原始验证和边界 |
| --- | --- | --- |
| task-board | NOT_PASS | JUnit 1/1失败/0错误/0跳过，712.149秒，Maven1；绑定阶段完整DENY，原任务UNKNOWN；新组件证据修复在轮末合入，实机待R04 |
| file-transfer | NOT_PASS | JUnit 1/0失败/1错误/0跳过，1024.233秒，Maven1；原部署SUC，首1MiB上传响应超时，无完整业务；finally stopped/disabled原marker保留 |
| asset-lending | PASS | JUnit 1/0失败/0错误/0跳过，1387.932秒，Maven0；完整业务/交付/额外cleanup0，STOPPED/DISABLED |
| csv-inspector | NOT_PASS | JUnit 1/1失败/0错误/0跳过，879.286秒，Maven1；inspect SSH输入等待20秒超时，原UNKNOWN；新增安全日志字段不称根治 |
| survey-scoring | NOT_PASS | JUnit 1/1失败/0错误/0跳过，862.042秒，Maven1；prepare完整DENY与既有fd桥接合同不符；未发现可证新代码缺陷 |
| log-analyzer | PASS | JUnit 1/0失败/0错误/0跳过，715.618秒，Maven0；完整CLI/交付/INSTALLED+N/A；有1次纯生成恢复，额外strict=false |
| directory-diff | PASS | JUnit 1/0失败/0错误/0跳过，430.643秒，Maven0；完整CLI/交付/INSTALLED+N/A，额外strict=true |
| binary-inspector | PASS | JUnit 1/0失败/0错误/0跳过，332.762秒，Maven0；完整CLI/交付/INSTALLED+N/A，额外strict=true |

八项目各一次，无完整部署外层retry或原UNKNOWN远端重放。初始清场、冻结37ecb0c1、源码/参数/模型配置一致性均通过；immutable inspector保留goalEligible=false，自动拒绝allAttemptsEligible及noRecoveryOrUnclassifiedLaunches。后一项因task-board-bind-readonly-01不是既定Maven观察白名单格式而REVIEW_REQUIRED，独立源码/只读H2与文件审计证明它只是原失败库诊断，不把该人工解释伪称自动gate已通过。四项实际失败已足以拒绝目标。

8份原assessment合计known tokens4047161，唯一generation IDs437、物理事件/唯一(id,number)438；Log有1次未知usage，32条能力探测的实际调用/usage未知，额外操作不含，不是完整账单。各单项和整轮额外strict区别保留。

driver83083/Python35772正常quit并实际exit0，RUNNER_EXIT已观察；采样器41604在ALL_EIGHT_TERMINAL自然exit0，主机CIM核driver/采样器及主仓消费者0。clean28r03经绝对路径和全链非reparse检查后删除，原失败数据库/UNKNOWN与全部原证据保留。runner-exit.json、main-live-process-final-01.json、temporary-cleanup-final-01.json及monitor-sampler原回执保存。

所有8项终止且自有进程退出后，主仓才从37ecb0c1快进e587ead1，合入eb21655e组件绑定证据、3a1f3f05上传被动诊断、e587ead1安全SSH字段日志；源码与配套测试已分别本地提交，未推送，论文既有修改保留。后两项仅诊断改善、FTP/CSV具体根因仍未定；Survey未发现可证新代码缺陷，完整DENY未重试。本轮4/8不能与旧轮PASS拼接。

第四轮脚本仅准备，下一步提交本轮报告、重建实际CLASS/Mod输出，清理已确认受管候选卷（如存在）后完整plan/apply/独立after，在新冻结版本开始八项首次测试。当前目标仍active。
