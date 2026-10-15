# 208—209：两个不同项目的串行部署样本

2026-10-16，本阶段按用户最新指令，在同一源码和 JAR 上分别尝试 CSV Inspector、File Transfer，各一次完整产品入口调用。**两项真实验收均失败；已知失败窗口缓存均超过60%，审批占比数值下降，但没有完整业务成本改善的结论。** 本阶段两个项目已用完，不自动启动第三项或重试原任务。

## 本阶段规则与比较口径

用户在三轮优化结束后授权先试1—2个其它项目，按终态已知且一致的 provider input 计算缓存，严格高于60%支持继续；审批占比暂作观察项，不再单独以20%中断本阶段尝试。原八项目全部通过、冻结后新服务器复验等目标仍未完成。这是新的项目采样阶段，没有追加第四轮优化或宣布降低完整验收要求。

完整具体输入、独立审批、修改动作重新申请 grant、真实前后端业务、实际浏览器以及产品 STOP/DISABLE 的要求保持。FULL/AUTOMATIC、remember=false、DEP LOW/APP NONE、32768/8192输出上限、四项新能力检查、90分钟运行上限和15分钟浏览器检查点保持。模型自主选择工具、组件、配额和动作；监控方没有代为部署、给出命令答案或重放旧计划。

两个样本均重新验证重装后已授权的SSH指纹，四项能力检查实际成功。沿用人类已授权的七项目源码、样例与审批证据出站范围，目标仍是 DeepSeek `https://api.deepseek.com/chat/completions`、`deepseek-flash`；未把SSH密码或API密钥作为模型正文发送。207新范围记录明确最多两个项目串行。第二项准入依赖第一项原闭合证据及其SHA，不能把第一项失败误作业务通过。

| 样本 | 唯一run | 原XML | 已知 input+output | 缓存命中率 | 审批占比 |
|---|---|---|---:|---:|---:|
| CSV Inspector 208 | `csv-inspector-serial-208-01` | 1FAIL/0ERROR/0SKIP，403.185秒 | 999416 | 78.7416% | 44.5785% |
| File Transfer 209 | `file-transfer-serial-209-01` | 1FAIL/0ERROR/0SKIP，121.526秒 | 239285 | 71.9452% | 22.6939% |

最近的[206 task-board失败窗口](DEPLOYMENT-AGENT-OPEN-PHASE-LIVE-206.md)为缓存72.7503%、审批64.0679%。审批占比在这三个失败截面中依次数值下降；本阶段缓存都超过继续线，但第二项低于第一项。技术栈、服务器暖机状态、自主动作数、停止阶段及窗口覆盖不同，不能把差额全部归因于代码优化、证明稳定趋势或视为最终八项目指标。209更早停止，也不能以总Token较少宣称完整部署更便宜。

缓存口径为所有已知一致行的 `cachedInputTokens / inputTokens`；审批口径为APP的input+output占DEP与APP总input+output。reasoning属于output子集，不重复加总。四项必要能力探测的usage、完整物理HTTP次数及整单费用均未知，不补0，不为补证重复付费。

## 208实际结果

- 2026-10-16 01:39:35.8901950—01:46:24.4418260（UTC+8），唯一exec55941终态exit1。
- Task `107bc437-662a-4ea9-8ebf-2ed95d9fb359` 原状态UNKNOWN，Goal BLOCKED；两个FILE快照及业务对账blocker保留。
- Runtime `src/app/db/data/agent-live-csv-inspector-68t9l6s08`，App引用 `wtl-agent-csv-inspector-68t9mtqm4`，均不作为正式发布证明。
- web归档verify及agent-open实际SDK成功、exit0、未截断，SOURCE_REVISION已返回。后来analyzer candidate-create成功，最后candidate-query-task被独立审批的结构化DENY阻断。

最后 `submit_result` 为 `call_00_HRbeC8hdarL0WdNcSKdI1862`，结构化决定DENY，原理由314字符却以“因此可批准。”结束。决定与理由不一致是已观察事实；模型内部成因未知，不能据此断言生产解析器错误，不能用正文代替grant或重写DENY。不是206同一个控制器安装拒绝点，也没有取得本轮控制器安装或64MiB专属probe成功证明。

| 角色 | 唯一generation | input | output | cached input | cache miss |
|---|---:|---:|---:|---:|---:|
| DEP/LANGUAGE | 15 | 536639 | 17252 | 420224 | 116415 |
| APP | 36 | 430413 | 15112 | 341248 | 89165 |
| 合计 | 51 | 967052 | 32364 | 761472 | 205580 |

APP为445525 Token。51行cache已知且一致，未知及不一致行均为0；DEP reasoning10726包含在output，APP36行reasoning未知。没有完整业务、浏览器或产品STOP/DISABLE通过回执。按新规则，缓存达继续线、且实际阻塞不同于206，因此只准入第二个不同项目，未重试208。

## 209实际结果与最小调查

- 2026-10-16 01:55:37.5528473—01:57:44.6057172（UTC+8），唯一exec31420终态exit1。
- Task `01114b7a-c15d-4f99-a950-4a2c32efb103` 原公开结果FAILED，`completedGoal`返回FAILED；没有208那种UNKNOWN对账记录或单独BLOCKED回执，不套用其状态。
- Runtime `src/app/db/data/agent-live-file-transfer-6l31ati1g`，App引用 `wtl-agent-file-transfer-6l31cequc`保留，不能充当正式App发布证明。
- 前两次独立审批按工具协议提交APPROVE，相应主机识别和只读容量命令实际成功。一次plan参数因PORT无效被拒，模型自行修正，下一次参数检查通过。

最后一次容量命令审阅的APP响应 `finishReason=stop`，正文是带 `decision: APPROVE` 的JSON，没有完成native `submit_result`。产品记录 `DEPLOYMENT_COMMAND_REVIEW_INVALID`、`reviewCode=NATIVE_BATCH_INCOMPLETE`、`validationStage=response`、`reviewStage=RESPONSE_CHECK`，没有为该正文签发grant或执行这次请求。

只读核对当前实际 `DeploymentAgentApproval.java` 第417—418行：审批循环在判定verdict前要求 `finishReason=tool_calls` 且工具调用非空，否则抛出该错误。日志与此防线一致；最后正文不能当有效审批，也没有放宽为自动批准。provider生成成功不等于审批协议完成。模型为什么改变输出形式、完整原wire输入是否影响它，尚无证明。未修改产品代码、协议或配置，未重跑业务。后续宜先离线研究协议约束及有限纠错的可行性，仍须完整输入、独立判断和有效的新grant，不能靠增加付费重试掩盖此阻塞。

| 角色 | 唯一generation | input | output | cached input | cache miss |
|---|---:|---:|---:|---:|---:|
| DEP/LANGUAGE | 8 | 175502 | 9480 | 123520 | 51982 |
| APP | 5 | 51871 | 2432 | 40064 | 11807 |
| 合计 | 13 | 227373 | 11912 | 163584 | 63789 |

APP为54303 Token。13行cache已知且一致，未知及不一致行均为0；DEP reasoning5239包含在output，APP5行reasoning未知。未到浏览器检查点，没有写浏览器成功结果，没有完整业务或产品STOP/DISABLE成功回执。

## 输入冻结、工具验证与本地收尾

两次运行HEAD均为 `3765f7b101d28a364e8f7a03fc5b6cff79238009`。源码3307项，manifest SHA `0c2fac8e64ffd210bc08dd03210f85c480a1d1ef25587512ecf979a9cf0493cf`；deployment.jar SHA `f040260c14433052440b9fc92903f216e652966f5a99cc2df0e2c4367b50430e`。每次source-before/after相同，freeze exit0，父核当前源/JAR及原usage逐generation ID、逐字段一致。当前实际源码包含原迁移改动，不能用干净HEAD替代当前产品。

每次新捕获39个host classpath项、1041文件、20个生产组及6个资源；原XML实际55项classpath解析19个关键类的首次匹配和字节。此为静态解析证据，不声称JVM CodeSource插桩。当前205审批policy为 `f116b4ed48492f3800d95cc12419010e852b302793091a4ac613bf327cedb250`。无binding冲突或已观察输出预算违规；没有重跑205已通过测试、重建JAR或执行全量测试。

208准备工具3个PowerShell AST、5个Python AST、16个纯解析例通过。209为3个PowerShell AST、6个Python AST、22个项目/前样本闭合解析例通过；缓存函数字节未改，复用208缓存例而未重复执行。209父初次读取来源元数据沿用旧schema发生KeyError，仅改父读取；首次离线capture又因父漏绑fixture SHA在第57行拒绝，0运行/凭据copy/provider。保留失败原件，补实际b300…fixture摘要后只重离线capture，未放宽守门。原工具报告的false准入/授权表示准备时点；当前实际baseline、单次授权及before-copy/run成功回执另行保留，不混淆时点。

两次分别以正常非管理员CIM正控制核验10组本轮PID+creation UTC ticks全部退出，无强制终止；带精确marker的TMP/短UDS和各自私有凭据副本已清，原176受保护配置两文件逐SHA未变。保留所有原Runtime、失败/UNKNOWN、候选、上传、占用及调查证据，不删除远端资源。进程清理不等于产品STOP/DISABLE通过；209协议失败也不证明其它资源已结清。

## 封存与交付边界

辅助证据根为 `.ai-workspace/agent-live-evidence/agent-autonomy-20261012/` 下的 `serial-project-selection-207/`、`csv-inspector-serial-live-208/` 和 `file-transfer-serial-live-209/`。原记录及父独立核对分别封存，209最终清单69原件；208最终清单66原件。以下摘要可用于恢复时核对，不替代原实际业务回执。

| 原件 | SHA256 |
|---|---|
| 208 原XML | d1c82be78957e733b86909f83f948422f12cd8e8810a7d45f0c0ef3ef0be4b25 |
| 208 parent-failure-closure208.json | 5a9f01eda3165a9d944bad469a1673dbe0a9ce018c996c5262a9fd5bdb2b1331 |
| 208 sealed-first-sample208.json | c6318f97564ef8322f438a7023434cc4e4e2213110c4e4de7c3c0f946589a9c5 |
| 209 原maven.log | 1fba96d185fc38bfeab5566e3b5e69cf004dc027831dbdb6cd39181ff0f0a74b |
| 209 原XML | 73e12291fc2a723d5bbeedda120b2ba82d27baf8c4b7f0bfc67f9ed77cf50b8e |
| 209 run/sealed-evidence.json | 68a76e9ffeb114c3777a439de59d99f73ebb93539aebc4bc16895a810dd3acf4 |
| 209 parent-failure-closure209.json | 6da3229096d8d789aa500a7b78760f9b6ca4e27330c605ce1e8d9d0cdcbed965 |
| 209 sealed-second-sample209.json | 8b69a91640c41c51b3dec7415c8dd01dbade4362c9c77e4a42181e07acdd455c |

本阶段交付正式记录的本地提交，不推送，不暂存原迁移和其它任务改动。当前没有本阶段验证进程或浏览器待关闭，两个样本均已完成本地收尾；原完整Goal未完成。后续需解决实际审批阻塞后再决定部署范围，不能把缓存达线当作业务通过；task-board、其余项目完整验收、冻结后全新系统八项目、非默认目录、整机重启恢复及安全资源结清均仍待完成。
