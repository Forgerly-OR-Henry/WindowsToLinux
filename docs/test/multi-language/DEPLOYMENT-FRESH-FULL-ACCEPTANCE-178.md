# 178：重装后首轮完整 Agent 验收在模型能力检查阶段失败

2026-10-15（UTC+08）。本轮仅通过正式产品入口执行 task-board 的 FULL AGENT/AUTOMATIC 验收。新服务器 SSH 连接成功；首个部署模型 TEXT 能力探测进入 UNKNOWN，测试随即失败，尚未提交业务部署 Agent 或取得独立 APP 业务审批。**task-board 完整验收未通过。**

## 授权与冻结输入

用户已明确确认重装后的实际新指纹 `SHA256:sCRc3ICCT9OMtsaGxjciDIQd0NMVO5/+pDe/fNMgRYc`，目标为 `45.192.105.213:22/root`。176 原首次信任拒绝记录保持不变，由新的用户授权证据承接。

自动审批曾在 CreateProcess 前拒绝验收启动，理由是具体第三方模型目的地及数据范围尚未明确获准。用户随后明确授权本轮 task-board 测试源码、部署指令、服务器观测及独立审批证据发送至当前 DeepSeek 服务。实际配置为 `https://api.deepseek.com/chat/completions`、`deepseek-flash`；测试夹具为 `test/multi-language/success-task-board` 的21份文件、91874字节。SSH密码和API密钥不作为模型正文发送。原拒绝及释放前授权文件保留，没有通过其它入口绕过审查。

父最终准入绑定新主机确认、新176受保护 source及其关闭后的pair、实际源码和已验证工具。正式配置仍为 main LOW、APP NONE、部署普通单次上限32768、APP实际请求上限8192；ordinary observedTokenLimit 为 Long.MAX_VALUE，不因普通用量自动 STOP。48900只是本轮请求端口，未向模型提供已空闲的结论，也未注入旧组件、API、CORS、监听或命令答案。

| 对象 | 冻结值 |
|---|---|
| HEAD | `d9acdba4e78d75130dc152268ca2b938f9e487bd` |
| 实际源码 | 3299项，与175 after-manifest一致 |
| 源码清单SHA | `6bba98ed41eeaf56b4ec8ce1e30548a0ad373b7a6255187bf0ee14eea598c831` |
| deployment.jar SHA | `1e3f04a95201d5fdd131229093107130143636a48f67bfc8441125be8367af47` |
| fixture class SHA | `b300a41c48dda597a562b82d6c6bb047f4cffa7d59d6861b3eecfe489affd421` |
| baseline SHA | `d99bf89bcbe751b10a20ee6b02e970b10f5636660b74f06be168db1a1cbc314e` |
| offline preparation SHA | `ef4b4f18e1f693a5bc478d0569a437c2db21c63c6295f9cf58a45a7f1aad17cf` |
| 新主机人类确认SHA | `e972cda823a7b6b846f600af3ce01c51b6c731ff1eb67ebb32fde03621684f89` |
| 模型出站人类授权SHA | `1488e3b20991504481c2be64a264edc599a9531eb5ad4bae1ce71f4e53b0e29c` |

保留已有860个 tracked dirty路径，不假定干净HEAD可构建当前产品，不清理原迁移或其它任务改动。before-copy、before-run与运行后source freeze均通过；本轮产品源码及JAR没有变化。

## 唯一实际执行

selector 为 `DeploymentStaticLiveAcceptanceTest#deploysWebsitesThroughAgentEntry`，只选 task-board。原先被拒绝的 CreateProcess 没有运行，本轮只有一次实际测试，没有重放旧业务任务或付费补探测。

| 步骤 | 实际证据 |
|---|---|
| 正式 SSH 连接 | Task `ffeb054b-c030-43fc-8f00-124f365653c2` SUCCEEDED，实际wire key与新授权一致 |
| 部署模型 TEXT 能力检查 | Task `4b911f22-3021-4858-b0f7-a50b3b4bd93f` 于04:20:42.065Z发起，随后 UNKNOWN，blocker为 Business result requires explicit reconciliation |
| 其余必要能力检查 | 未执行；不能按完整四项已通过计 |
| 业务部署、独立APP、浏览器、STOP/DISABLE | 未到达 |
| JUnit | 1 FAIL、0 ERROR、0 SKIP，40.86s |
| Maven / source freeze | exit1 / exit0；Maven总时长45.606s |

Runtime关闭还报告 `Unfinished platform tasks require reconciliation before shutdown`。UNKNOWN保持原状，不能解释为审批拒绝、容量不足或业务部署失败原因已经确定。

179随后仅对本轮原DB做正常用户DPAPI bootstrap及只读JDBC取证，没有启动Runtime或查询凭据表。原诊断为 `AI_TIMEOUT / TIMEOUT`、`timeoutPhase=REQUEST_BUDGET`，平台attempt `17e68c29-38a6-4167-8c7e-403020214658` 耗时29956ms、encodedRequestBytes181、effectiveMaxOutputTokens8192。原task最终为 `TASK_EFFECT_UNCERTAIN / UNKNOWN`，没有HTTP状态、底层异常类别或服务响应可确定外部根因；`MODEL_PROBE_NETWORK`只是TIMEOUT的固定分类。

原CONNECTION与CUSTOM占用已有实际released_at，MOD占用随UNKNOWN保留；不将物理释放解释为业务成功。原DB/access/零字节lock的SHA、native lock身份及目录项前后相同，schema仍1；normal三组launcher/javac/java PIDticks全退，自有TMP/classes已清。详见同证据根 `fresh-probe-investigation-179/REPORT.md` 与 `probe-projection.json`。不重跑旧探测或为旧账单补费。

## 原证、用量与资源

本轮证据为 `.ai-workspace/agent-live-evidence/agent-autonomy-20261012/fresh-full-business-live-178/task-board-fresh-178-01/`。原XML SHA为 `19c0f99dd8bb9578335399abe92f4030b0e96561d1259ba5d53548202f1af3df`，原TXT SHA为 `4fd83053f024cceac69f96abbeda2a618964f0da5f38d64c114972c6d35305bc`，原报告复制受本轮开始、结束时间及实际测试类绑定。

只观测到一项必要逻辑probe。Maven投影没有generation attempt记录，179从原DB补得一项实际平台attempt；它不证明JDK内部物理HTTP发送次数或服务商完整账单。所有input/output/cache/reasoning用量仍UNKNOWN，reasoning不重复计入output。本轮是失败调查，不计最终冻结八项目指标。

正常用户CIM已核10个自有PID与CreationTicks全部退出，本轮marker绑定TMP和短UDS已精确清理。Runtime `src/app/db/data/agent-live-task-board-jeun4kjco`、source及UNKNOWN调查保留；未写入一个可运行完成App。新176保护source、原配置、备份与旧任务不动。

凭据副本Cleanup首次在 `COPY_CREATION_DRIFT` 守门处退出。180正常用户取证确认：原JSON文本、PowerShell自动解析的UTC DateTime、实际文件创建时间的ticks均为 `639276335321675265`；旧字符串比较因日期表示不同误拒。最小修复仅改为精确UTC ticks比较，保留漂移守门；4个公开定点验证字符串/已解析日期同瞬时通过、两种相差1 tick情形拒绝，AST错误0。原178 helper保持不变，限定Cleanup的180候选SHA为 `bbd9bcc6890f424d3b381590fc4da8702fd00bb1fd41e180595aaadf33dbf2fc`。

父审后180实际清理成功，仅删除marker、私有ACL、GUID和创建ticks绑定的自有副本，原176 source pair前后不变，调查Runtime未删。首次 `sealed-evidence.json` 如实记录failure及当时closure=false，保持原字节；追加 `resource-closure-after-180.json` 绑定原seal、10进程退出、TMP/UDS及180清理回执，承接最终资源闭合，不改写原UNKNOWN或失败结果。

本轮没有执行全量测试。实际完成的是新主机产品连接和一次失败的单项目验收尝试；task-board完整独立审批业务、其余七项目、冻结版本后的全新服务器八项目、非默认目录以及整机重启恢复仍待验。
