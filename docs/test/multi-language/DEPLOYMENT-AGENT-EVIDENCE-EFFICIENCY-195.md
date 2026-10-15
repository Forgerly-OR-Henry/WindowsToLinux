# 195：审批批量取证、Skill正文去重和源码／回执导航

第一阶段三项实现、定点验证、打包及物理收尾完成。**最终有效32次用例通过，非全量验证**；没有新增provider或真实服务器调用，不能宣称token、缓存、费用或审批≤20%已达标。[194真实运行](DEPLOYMENT-AGENT-EFFICIENCY-LIVE-194.md)的失败窗口及UNKNOWN保持原样；其审批63.3229%／缓存69.3927%不能替代新版完整业务验收。

用户已在“调研本地审批模型方案”明确授权本阶段三项实现及定点交付，并要求将范围发送到本任务。接收后核对了实际人类原文；先完成194在途运行与本地收尾，之后才改源码。固定控制器版本化安装与短调用另列后续方向，本轮不实施、不自动追加付费试跑。

## 三项实际改动

只修改五个文件：`DeploymentAgentApproval.java`、`DeploymentSourceBrowser.java`、所属 `approval-agent.md` 和两类直接测试。193的固定证据前缀、短输入预置、Skill后置，以及NativeHistory、InputCoverage、共享Skills、主部署编排、配置和依赖均未改。

**批量取证复用原生多调用能力。** 原 `MAX_CALLS_PER_BATCH=4` 保持；动态尾部增加 `maxCallsThisBatch=min(4, remainingTaskTools)`，说明只有参数已知、互不依赖的只读取证适合同批，需要前一结果的读取继续串行。模型自主选择读取内容和批次大小，不要求凑满4个，不新增批处理工具或合并远端执行动作。

规则及 `submit_result` 工具说明再次明确：最终裁决必须是该批唯一调用，不能与read、Skill、notes混合。原混批拒绝仍在任何批内工具效果和裁决返回之前；没有为194的无效批次添加放行回退。完整callId／结果顺序、取消、送达、窗口和最终编码守门保留。

**Skill只在审批的新工具回执中去掉重复正文。** 原 `skills.read()` 继续验证原task、role、ID及取消后激活；审批局部投影移除content，保留task／role／ID、激活状态、注册文档SHA256及下一请求可信SYSTEM的位置。摘要对应注册的UTF8/LF完整文档，包含frontmatter，不冒称SYSTEM body自身摘要。完整可信body仍由原 `skills.system()` 每请求提供一次，未预载其它Skill、重写旧结果或改变主部署调用者。激活元数据不等于实际送达、理解、安全或授权。contract保留完整自主复读入口，并标明原文已在基础SYSTEM中。

**源码导航增加真实续读位置。** 审批 `source_list` 使用冻结目录下的递归路径索引；新增窄 `indexPage`／`searchPage`，旧共享list／index／search／read／readPage方法正文保持不变。搜索返回真实文件与文件内行游标、实际起止位置、hasMore和文本不可用成员。单文件达到100个匹配或编码容量限制时，下一页从尚未呈现的位置继续，不用offset+limit猜测扫描完成。

导航结果保守限制为12,000字符且12,000 UTF8字节。单条中文等多字节预览过预算时，按实际JSON编码和codepoint边界有界缩短，并明确 `previewComplete=false`；原源码读取不被替代。路径索引、搜索位置及预览均标记metadata，不记为完整源码READ或可执行输入覆盖。原冻结身份、目录边界、内容改变与凭据排除规则保留。

**回执按模型显选事件读取。** 审批新增 `receipt_index`／`receipt_event`，仅使用本次已捕获的 `{events, modelCommandReceipt}`，不回查活数据库。请求同时核对reviewBinding与receiptSha256；原binding继续绑定task、目标、源码、配置及完整当前请求。事件ID必须在原快照中唯一且为正整数；外部ID、跨快照、额外task字段拒绝，歧义ID闭合。

索引有界分页覆盖整个已保留快照，不筛选成功或最近事件。原事件schema只有id／type／action／detail，没有通用resource或status；导航明确将这两项标为UNKNOWN，不从无类型detail猜测。模型选择ID后按原页规则读取完整原事件，包括FAILED／DENY／UNKNOWN；超长转义仍完整续页。原 `receipts` 全根读取及其中modelCommandReceipt继续可达，索引不能替代原始证据。

每个完整动作仍独立审批；修正命令和stdin必须取得绑定新动作的单次grant。CLOSED DENY、OPAQUE、源／身份／配置／回执变化检查和取消守门不放宽，STATIC／ASSISTED／AGENT继续独立编排，没有风险评分或跨动作快速放行。

## 定点验证与原失败

`implementation/planned-selectors.json` 显式选择两个测试类中的19个具体方法，没有全类、模块或全项目测试。实际范围覆盖：

- 1／2／4个只读取证调用、完整独立裁决、混批先于工具效果拒绝，以及原超批、失败／裁剪／未送达无授权、旧DENY和改请求重新授权。
- Skill重读后的SYSTEM正文单份、注册摘要与位置、其它role仍返回原文、伪造／外国role／取消拒绝；contract原文继续可读。
- 深层路径分页、单文件超过100匹配、空匹配、二进制不可用、中文及emoji容量续读、凭据排除、冻结内容变化；导航不能制造READ或可执行覆盖。
- 135个事件的完整索引、首事件长转义分页、模型显选后页eventId=135完整FAILED原事件，以及跨binding／快照／task、外部ID、重复ID拒绝；原整根与modelCommandReceipt可读。

| 轮次 | 实际执行 | 结果 | 后续处理 |
|---|---:|---|---|
| test-01 | 32 | 31 PASS／1 FAIL／0 ERROR／0 SKIP | 保留原日志、XML及当时五份owner字节 |
| test-02 | 3 | 3 PASS | 只重跑失败方法的三个参数场景 |
| test-03 | 1 | 1 PASS | 只重跑补充后页事件读取断言的原方法 |

唯一首轮失败是旧 `nativeReadHistoryAndResponseFailureRetainSafeStageWithoutGrant[HISTORY_RETAIN]` 夹具：四份7,000中文结果实际上仍低于原262,144字节上限，没有触发预期历史错误，后续无配置响应才在RESPONSE_CHECK结束。只在该参数夹具增加公开opaque continuation计量，使完整批次确实超原预算；生产预算、算法和错误传播未改。其同方法两个原PASS因方法selector随之重跑，不冒称未重复。

父审另补原receipt方法对后页非首ID的完整正例，防止只验证首事件而遗漏显选语义。**总实际36执行，其中35 PASS和1个保留原失败；最终有效32 PASS=方法外首轮28次复用＋重跑3＋重跑1。** 最终生产／资源及SourceBrowserTest与test-01逐字相同；ApprovalTest随后仅这两个方法变化。最终3299项源码与test-03执行前后逐项一致。

受控mock的同一正文被分成5页：分别选择每批1／2／4调用时，断言实际生成请求为6／4／3次（包含最后单独裁决）。这验证已有批量机制的机会，不代表真实模型会作同样选择，也不代表供应商token或费用收益。

三轮均只对四个Java owner做格式write/check，全部exit0；规则资源重读。正常用户CIM核60组已观测验证PID／creation UTC ticks全部退出，严格使用root身份与父子创建先后；自有TMP／短UDS清，cleanup进程另核退出。没有Runtime、调查数据库、SSH、API key或真实模型操作。

## 独核、打包与隔离

父独核120份原件、当前3299项与五owner边界、原XML／重跑范围和方法外字节，并在自有scratch执行Git apply正逆check及实际应用，逐字还原原／最终五文件，scratch已清。辅助校验曾因Git不接受Windows扩展路径参数、以及父读取closure字段名称假设而中断；各原错误记录保留，改用实际路径／schema后完成核验，未修改产品或重跑已通过测试。

随后唯一执行 `-B -pl src/mod/deployment -am -DskipTests package`，Maven exit0、16.379秒，前后源码守门0、14处跳过测试、0次测试执行。正常CIM另核12组自有PID／ticks全退，精确TMP／UDS已清；原数据删除0。

最终 `deployment.jar` SHA256 `a118322d0d6b32c142a5154b39639f1a0af31205d769cf71ee37ae810160cfeb`。修改后的Approval／SourceBrowser及全部9个SourceBrowser类成员与target一致，规则资源与source一致；InputCoverage／NativeHistory、宿主AI JAR与两HTTP类、验收fixture保持原字节。不是provider或真实产品部署验证。

实际工作树仍保留迁移和其它任务修改。提交仅包含本记录、[本轮精确增量补丁](patches/AGENT-EVIDENCE-EFFICIENCY-195.patch)与该路径的 `-text` 属性；补丁相对本轮before原字节，不将混合dirty源码整体暂存，不声称干净HEAD可独立构建，没有推送。

原件根 `.ai-workspace/agent-live-evidence/agent-autonomy-20261012/`：

- `approval-evidence-efficiency-195/implementation/after-manifest.json`：3299项／仅五owner变化，SHA256 `d92673139cfd8332a08565ef9b640c62af674f27c848f0c8a247cc6084b14b13`；patch `4cc45687c6d03ac627ffb919275c492d7293fcf6f601119a7639383348a8ffc8`。
- 实现seal `46b0879a817550263ccd7ef10fe6c91b8e38652fe8e51c72b7883b572d299508`；父 `parent-verification195.json` 为 `e85d6bc76250739591f1ed628e801ef92dfe1868c065253364f3d2452c81ab34`。
- `approval-evidence-package-195/parent-package-verification195.json` 为 `f783f225766f76e910de0721a31ae291d64b68b27520f311f2b94aa911f318c4`，独核21份打包原件及构件、跳测与物理收尾。

## 后续第二阶段方向，尚未实现或验证

用户补充的方向是固定工具链控制器版本化安装与短调用，先核controller的实际来源、安装引导依赖、捕获layout及恢复路径；resolver／dependencies是否处理另凭实际收益决定，不顺带重构。

首次安装或升级须独立审批完整实际安装动作、全部可执行原文、目录、权限和副作用。后续prepare／bind等短调用仍提交完整当前参数、版本及源码摘要、资源目标和状态证据，每次独立审阅并取得新grant。不能用现有inline renderer一致性、摘要或“随包源码”标签替代完整输入审阅；应先真正缩小当前执行输入。

程序与版本目录须受保护，校验路径链、拒绝符号链接／可写替换，执行前核精确身份；禁止通过隐藏exec、外部下载或可变模块绕回未审可执行输入。沿用任务捕获 `DeploymentRemoteLayout` 和既有锁、归属、跨任务引用、完成回执与失败／UNKNOWN生命周期，不按名称或路径删除其它任务依赖，不自动重放未结动作。仅复用匹配实现与明确入口的窄受控程序契约。

后续先做本地受控反例：首装／同版复用／升级再审批、程序／目录／权限／摘要篡改、非默认layout、并发／跨任务引用、取消／安装失败／UNKNOWN、不完整回执及不同参数／目标的新grant。首次安装成本、后续调用成本和整次部署成本分开记录；方向性要求不直接释放新的服务器或provider验收。

整个原目标仍未完成：195新版真实收益、其余七项目、冻结后全新服务器八项目、非默认目录实际部署和整机重启恢复均待验，194资源与UNKNOWN仍需按原任务正式核查。
