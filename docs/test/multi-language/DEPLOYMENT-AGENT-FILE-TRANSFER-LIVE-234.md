# File Transfer 实服验收234

2026-10-16至17日，当前会话取得真人直接授权后，产品实际执行一次File Transfer FULL。唯一JUnit为1失败、0错误、0跳过，源码冻结检查通过；原任务进入UNKNOWN，真实HTTP业务、浏览器及产品STOP/DISABLE均未完成。两个所选项目累计八次实际FULL、零完整业务通过。233创建进程前被拒的启动不计FULL。

## 授权、来源与执行范围

真人明确授权八个验收项目源码、样例、必要修正版、部署指令及观测审批证据发送至`https://api.deepseek.com/chat/completions`、`deepseek-flash`，由产品在`45.192.105.213:22/root`串行部署、调试和收尾；密码/API密钥不进入模型正文，每个完整动作仍独立审批并取得新grant。当前仅File Transfer，CSV Inspector排队；原194已结清，不再恢复，旧221不自动恢复。本轮没有Root直接SSH、给部署模型具体命令或复用批准。

启动HEAD为`60582e99eea3c5a9c68ab6e8fcda0dc09d958218`，索引为空，现有混合迁移工作树保留。3310项源码、六JAR和来源组与233/232核同；源码清单SHA256为`30569190ae41ff690f56ebc901d7fb0397fd3a42d50161a629a8fc3fe3f4cf68`，deployment.jar为3755629字节、SHA256 `1299e3225748d0019a60097a610990c4dbee0613e9812a5ea4069066dd050ca2`。前后冻结覆盖30部署组、3AI族、4平台族、5完整宿主模块，54个classpath入口、3210文件，并核对本次JUnit XML中的实际JVM classpath属性；这不等同逐类CodeSource证明。未修改产品源码、样例或配置，未重新打包或重跑旧定点业务组。

唯一入口为`DeploymentStaticLiveAcceptanceTest#deploysWebsitesThroughAgentEntry`，仅file-transfer。AUTOMATIC、remember=false，DEP LOW/APP NONE，输出合同32768/8192，90分钟部署窗口、15分钟浏览器检查点，无自动重试。本轮本地辅助工具新增34项授权边界检查、PowerShell AST及15项精确字面量核验通过；早期身份替换误伤Long.MAX_VALUE已在产品启动前修正并保留失败记录。

运行窗口为2026-10-16 23:38:39至2026-10-17 00:06:49（+08:00）。Runtime为`agent-live-file-transfer-6x03gzuwk`，App为`wtl-agent-file-transfer-6x03iogb4`，唯一原Task为`5872e71e-8dcd-4353-9894-b9685e20f69c`，无已证续接任务。请求端口48936不证明已发布或监听成功。产品SSH检查成功且固定信任指纹匹配；DEP/APP各TEXT与TOOL_CALLING共四项必要探测成功，实际角色端点/model与授权一致。

## 失败证据与判断边界

模型自主读取源码、规划候选、声明数据库并尝试构建。记录到27次原生APP批准及27份SDK命令回执，其中25成功、2失败；计数本身不代表模型用量或完整HTTP数。

两次工具链冷安装均由VOLUME门禁拒绝：首次可用7607418880字节，第二次7607222272字节，均小于9663676416字节所需，即固定8GiB INSTALL镜像加1GiB宿主保留。两份命令均FAILED、exit65、未截断。此检查发生于创建该工具链操作目录/镜像之前，不能据此宣布整个候选或任务cleanup_complete，也不能推算已回收容量。

只读源码审计确认候选plan仅准入工作区配额，没有组合后续工具链容量；已建候选也不能通过plan原地缩小物理配额。现有普通command允许空managed-tool binding，host_command仍要求精确所有权、效果结清、完整独立审批和新grant。因此本轮证据支持容量协调缺口，但不能断言不存在合法自主纠错路线，或必须降低隔离/保留要求。模型在门禁失败后继续自主观察和评估，父未注入部署方案，运行中保持代码冻结。

直接终态原因是最后一次LANGUAGE generation `c6a9726e-62dc-4739-9548-1e3cc97e677f`在约120秒REQUEST_BUDGET到期后出现AI_TIMEOUT/UNKNOWN：已收到HTTP200响应头及1字节body，usage全未知，不能解释为未发送、未计费或可安全重放。产品保留UNKNOWN及“Business result requires explicit reconciliation”，目标观察为BLOCKED，唯一JUnit因原目标需协调而失败。两次容量拒绝是前置阻点，并非这次AI_TIMEOUT的已证根因。

## 已知用量与未知覆盖

原日志记录126个generation attempts：DEP27、APP99；125条input/output/cache已知，最后一条未知。按generation/attempt/purpose去重和冲突守门，父另用独立回调解析器核对126条及全部原行SHA，结果一致。

| 范围 | 已知input | 已知output | 已知合计 |
| --- | ---: | ---: | ---: |
| 全部记录到的已知回调 | 3197222 | 155834 | 3353056 |
| task/time绑定主详情78条 | 2340379 | 115911 | 2456290 |
| 仅回调48条中的47条已知 | 856843 | 39923 | 896766 |

DEP已知合计1522125，APP1830931。已知一致行按input加权的缓存为2069120/3197222，即64.7161817353%；APP占已知input+output为54.6048440587%。reasoning已知107290已包含于output，不重复相加；100条reasoning未知。四项能力探测usage、完整物理HTTP、账单和费用仍UNKNOWN，失败窗口不能证明完整成本或优化收益。

末100条有界诊断仅包含78条usage主详情，不能覆盖全部126条回调。原严格汇总先实际失败，证据保留；随后只对本地汇总器加入显式bounded模式，分列主详情绑定与仅回调层，交集逐字段核同。48条仅回调记录不伪造独立task/time，其中最后一条不补零。新增10项局部消费者通过。首次CRLF文本补丁导致NameError，原脚本和失败记录保留，修正后单独检查及实际汇总成功；未为补证增加外部调用。

## 本地收尾与接续

唯一runner实际退出1，冻结退出0，原JUnit XML/txt按result散列复制保留。正常用户CIM按PID及创建时间核对14组已观察身份均退出，没有强杀。精确marker、绝对路径、无重解析和散列核验后，只清除本轮TMP/UDS、fresh234私有配置副本及未使用的64MiB浏览器临时样本；原176配置pair散列未变。浏览器样本owner marker与外部manifest仅末尾换行不同，首次拒删发生于删除前，保留原脚本及拒删证据后固定两个原SHA并复核规范化文本、完整文件集及各文件字节。未创建浏览器页或后台验证服务。

本轮单次ready/authorized/credentialCreateAuthorized和父执行开关已撤回false，消耗前状态另存；真人对任务范围的授权保持。UNKNOWN调查Runtime、来源快照、失败审批和远端未结清资源保留，没有按名称或路径清理远端，也未把本地进程退出当作业务收尾。

证据根为`.ai-workspace/agent-live-evidence/agent-autonomy-20261012/file-transfer-live-234/`。主要入口为`human-authorization234.json`、`baseline.json`、`tools/offline-binding.json`、唯一run的before/after-metadata、maven.log、result、terminal及junit，另有`parent-terminal-review234.json`、`parent-usage-crosscheck234.json`、`usage/REPORT.md`、`parent-final-process-verification.json`。原日志SHA256为`2ff1b6690543a49ba7a858d7e2fce5ae0ca433524e56c0a53836ef534cd414fb`，result SHA256为`d374fe3fc320efcab0b67c17369013696b6622eba7b09fad4d74342a5b170b54`。

接下来先通过产品现有能力核对234真实账本与未结清效果，再决定最小修复及独立审批的协调/收尾；不盲重开FULL、不启动CSV、不恢复194或旧221。File Transfer标准仍包括64MiB、两版本、暂停重启续传、重复/冲突分块、10路完成竞争、取消及下游故障，当前live HTTP小样本不能替代。

最终目标未变：两个所选项目分别完成真实业务、浏览器和产品STOP/DISABLE后核算FULL用量，cache>60%可继续，APP仍明显高则暂停讨论；本次失败窗口不满足该业务前提。最终在同一冻结版本、全新服务器连续验收八项目，中途不改代码；理想cache≥80%且APP<10%，最低cache≥60%且APP≤20%。非默认目录、整机重启及其它模式动态容量仍待验。
