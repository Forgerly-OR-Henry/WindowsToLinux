# ASSISTED 多语言部署修复与复验（2026-10-20）

**本阶段首轮完整复验为 7/8，八项未全部一次性通过。** Survey 的最后 NPM 构建审批耗尽 32768 输出预算。随后增加独立审批资源上限，经三次 API-only 门槛后，追加一个新的 Survey 完整入口通过。至此八个项目均有本阶段完整成功结果；不能写成同一配置的一轮 8/8 首次成功。更早的 [2/8 原始复验](ASSISTED-FIRSTPASS-20261020.md)、本轮 7/8、试跑、对账、辅助错误及追加复验均保留。

## 两轮及预算

| 阶段 | 完整入口 | 实际结果 | 普通/扩展/审批请求预算 |
| --- | ---: | --- | --- |
| 中间 CSV 修复试跑 | 1 | 通过，早于后续修复，不冒充最终共同版本 | 32768 / 65536 / 32768；live profile 上限 32768 |
| verification-01 | 8 | 7 通过，Survey 失败 | 32768 / 65536 / 32768；live profile 上限 32768 |
| verification-02 | 1（仅 Survey） | 通过，前七项未重部署 | 32768 / 65536 / 65536；live profile 上限 65536 |

各阶段模型为 deepseek-flash、部署/独立审批 LOW/LOW、审批 600 秒，原生 submit_result、独立 DENY 终止、精确绑定及每次宿主许可保持。网站测试端已知 Token 观察阈值在任何网站启动前改为 150 万，CLI 100 万；它也影响测试拦截器的输入字节监控余量，不是生产模型单次输出预算。安装、SDK/依赖补齐、构建、发布、业务及生命周期写入均由产品负责，未手工部署。

## 最终成功交付

| 项目 | 源码语言 | 成功阶段/审批预算 | JUnit 秒数 | 产品状态 | 产品网站 |
| --- | --- | --- | ---: | --- | --- |
| 任务看板 | TYPESCRIPT + JAVA | verification-01 / 32768 | 1067.252 | RUNNING / DISABLED | [http://45.192.105.213:46100](http://45.192.105.213:46100) |
| 文件收发 | TYPESCRIPT + GO | verification-01 / 32768 | 868.704 | RUNNING / DISABLED | [http://45.192.105.213:46110](http://45.192.105.213:46110) |
| 资产借还 | JAVASCRIPT + CSHARP | verification-01 / 32768 | 956.252 | RUNNING / DISABLED | [http://45.192.105.213:46120](http://45.192.105.213:46120) |
| CSV 检查 | JAVASCRIPT + PHP + PYTHON | verification-01 / 32768 | 1003.385 | RUNNING / DISABLED | [http://45.192.105.213:46130](http://45.192.105.213:46130) |
| 问卷评分 | TYPESCRIPT + KOTLIN + RUBY | verification-02 / 65536 | 1505.466 | RUNNING / DISABLED | [http://45.192.105.213:46140](http://45.192.105.213:46140) |
| 日志分析 | RUST + CPP | verification-01 / 32768 | 445.012 | INSTALLED / NOT_APPLICABLE | 见产品 CLI 前缀 |
| 目录差异 | PYTHON + CPP | verification-01 / 32768 | 412.795 | INSTALLED / NOT_APPLICABLE | 见产品 CLI 前缀 |
| 二进制检查 | RUST + C | verification-01 / 32768 | 315.017 | INSTALLED / NOT_APPLICABLE | 见产品 CLI 前缀 |

每个成功入口 JUnit 均为 1 项、0 失败/错误/跳过；Survey 的第一次失败另外保留，未覆盖其 XML。[完整产品管理载荷](ASSISTED-REPAIR-20261020-management.json)保留各组件、端点、应用/单元命令、修订和所有权摘要。最终五个网站按产品 URL 只读 GET 均为 200；具体组件状态以各载荷 observedAt 为准，最后服务器单元观察另存证据。

三项 CLI 以下为产品 ON_DEMAND 原样前缀；在 `--` 后附样例支持的参数及服务器输入路径。该摘要属于对应单元 manifest，不能替换为应用修订摘要。

日志分析：

```sh
sudo /usr/local/lib/windowstolinux/managed-helper app-run wtl-assisted-log-analyzer-aqh8g1w6o 7b7c373fbc1734b9eee7abeec0cd8fbac4f3520a602a7e5a81fb3a696870ca39 --
```

目录差异：

```sh
sudo /usr/local/lib/windowstolinux/managed-helper app-run wtl-assisted-directory-diff-ax4mhsk5g 7abbdb20c52ddeff644bc96479622138e9312a9bed85333d4d8857f2a0a3274c --
```

二进制检查：

```sh
sudo /usr/local/lib/windowstolinux/managed-helper app-run wtl-assisted-binary-inspector-b2gvf9q64 94a7f51bd9cb255412d44762fc07e977d3d96f7ba3a2658614f10b79421f1023 --
```

## 已定位问题与改动

- CSV 配置暂存的接收代码调用 assert_storage_parent，但独立审批提供的实现片段未包含其定义。证据补入实际固定 helper 的 input/17-managed-content.sh，完整暴露符号链接、root 属主和可写性检查。未改 helper 执行字节或协议摘要。回归断言修复前失败，修复后通过；真实完整入口 csv-inspector-repair-01 通过。
- 文件收发的来源 logicalRootName 与部署组件应用 ID 被混同。增加固定计划器验证后的应用、组件归档、原快照、任务、服务器和目录布局对应事实；来源名称只标识来源，独立审批仍须检查精确绑定、目标与原授权。不存在/变化的绑定仍拒绝，独立 DENY 仍终止授权。
- 日志分析与目录差异的 CMake/Ninja 缺口只出现在部署模型反馈，独立审批没有看到产品实际探测结果。增加原任务、计划和来源绑定的完整准备后主机观测；语言 SDK 清单之外的主机构建依赖与语言 SDK 的受控目录准备分开描述。补齐仍需独立审查完整脚本、官方来源解析和实际版本后置检查，原 prepare 必须重新验证后才允许候选上传。
- Survey 的旧通用 INVALID 出现在容量读取之后。原可用空间 2002104×4096 字节，而验收明确三个组件各 4 GiB，另加 1 GiB 主机余量，共需 13 GiB。增加数值型容量拒绝分类，保留原配额，不静默压低。空间恢复后的新入口已越过此停点，追加完整验收通过；容量限制没有取消。
- TaskBoard 的失败检查在 SSH 通道打开阶段确认作者载荷未发送。固定只读 inspect 在严格验证 NOT_SENT、发送线程未创建、没有输出/退出且未取消后，可重新取得新许可再观察一次；最多两次，写操作和 UNKNOWN 不重发。故障矩阵验证新许可、次数边界和拒绝重发条件。SSH 底层间歇原因仍未证明，不能据成功重跑声称根治。
- TaskBoard 原日志为 backend=start/attempted=true/verified=false、frontend=stop/attempted=true/verified=true。对账只在原所有权、当前状态与自启动可验证，且确有已确认停机组件时结算当前部分失败；已尝试 start 的当前状态必须为 RUNNING。原确认标记不改，不推断历史启动成功，不重发原动作。所有组件已运行但重启确认缺失的旧场景继续 UNKNOWN。10 项相关受控平台/H2 检查通过；同一原任务最终 ENDED/FAILED、blockers=[]。

## Survey 输出耗尽与追加复验

verification-01 原任务 `24b11301-d71a-4dfa-ab61-4ce087c58672` 在 NPM 构建审批失败。generation `b8d00444-737b-4e3c-8ad6-171368dde445` 输入 17135、输出 32768，其中推理 32768，finish=length、没有完整原生决定；没有为这份未完成审批发放宿主许可。后续产品完成原资源收尾，已知 FAILED 保留，不把任务当成成功，也不自动重试 length。该次不是旧容量 INVALID 或独立安全 DENY。

[DeepSeek 官方参数文档](https://api-docs.deepseek.com/api/create-chat-completion/)说明思考模式默认输出预算为 64K，且不支持强制指定具体工具。LOW 不能保证在 32K 内结束。此次保留 LOW 和完整审批材料，默认审批改为 65536；普通部署仍 32768，扩展仍 65536。配置的审批上限改为不超过扩展预算，避免为增加审批资源同时抬高普通部署预算。实际仍受 APP 和所选模型 profile 上限约束；本轮新 live profile 为 65536。这是资源余量调整，不是降低独立安全标准，单次审批允许消耗更多 Tokens；也不保证模型永不耗尽。该默认配置会影响未来其他模式的审批请求，其他模式未做真机复验，Agent 继续暂停。

追加完整入口的NPM审批输入17108、输出1954（推理1808），实际未触及旧32768上限；因此追加成功不能单独证明预算提高是唯一原因，更不能证明模型偶发耗尽已根治。

追加前的三次 API-only 门槛使用生产 StaticSite/NPM 构建器、完整 shell stdin 和原生审批路径，输出分别 2184、2013、1276，每次一个物理尝试、完整原生决定；Linux/SSH 访问 0，许可没有用于部署。它们是新任务/快照/应用身份的代表性组装，使用保留实测 Node/Python 字面选择，工具绑定摘要与失败请求相同；不是原失败请求逐字节重放，也不声称新鲜主机准备。门槛只证明这些样本，不证明所有模型调用稳定。

前三个辅助入口在能力验证的本地 HttpClient 初始化阶段 UNKNOWN，未进入构建审批。诊断为 UncheckedIOException/IOException/SocketException；无密钥探针复现回环初始化失败，IPv4 配置无效，短可写 jdk.net.unixdomain.tmpdir 后 Selector/HttpClient 创建通过。三原辅助任务均经产品 ModelService 对账 ENDED/FAILED，不重放模型或访问 SSH。失败、首次 stderr 捕获不足及修正记录保留；临时编译类、注解处理器索引及短 socket 目录已清理。

## 环境、历史和定点检查

用户明确授权付费测试、DeepSeek 样例源码/部署上下文/诊断外发及必要服务器清理。服务器 `45.192.105.213:22`，固定指纹 `SHA256:mA22eywGncT1fkyeabnZDrv6s6E1Qd9OX2PaBfy7z18`；清理和最终只读观察分别核验。测试前旧网站通过产品 STOP/禁自启清理，未手改数据库。原 TaskBoard 重启任务 `6fb2a6ce-c78c-4fc3-8eab-52464d879b61` 的失败对账 02 保留，修复对账 03 才结算 ENDED/FAILED、blockers=[]；原 start 的 verified=false 不改。恢复测试在只读对账后还使用全新产品 STOP，整个方法不能统称只读。

中间 CSV 试跑 JUnit 936.617 秒通过（65 次已知尝试、629242 Tokens），早于生命周期修复，未算作最终共同版本八项。第一次 CSV 清理辅助入口漏设 connectionOnly=false，遗留只读查询 `a2ff1f4c-eaf2-42b2-9c32-c13659463f1f`；辅助进程退出后已产品对账 ENDED/FAILED，再新 STOP 清理。各辅助、复现及测试 fixture 错误与真实项目结果分开。

CMake 3.28.3-1build7、Ninja 1.11.1-2 的先前产品安装历史、精确包版本和模拟仅两包移除已核实。在 Log Analyzer 前、其通过后且 Directory Diff 前分别只移除二包，保留依赖及数据，并验证命令不存在。两项目均由产品独立 APPROVE 后补齐，EXIT=0，重探测 CMake3.28.3/Ninja1.11.1，并重新 prepare 后才上传/构建/发布。操作分别为 `b5c11297-92df-46f8-bfdd-b9bf868022d3`、`17dcb095-aae0-4487-9a10-1c15665f4f20`，完整脚本摘要和收据保留，未手工安装。

本地分阶段定点验证共36项：原阶段28项直接相关回归、预算阶段6项、live结束后API-only夹具结构检查2项。原组包括暂存证据1、来源/主机观测15、容量1、部分重启及既有 UNKNOWN 防护10、只读再观察1（含5类故障）。新增预算首组6项中5通过、1旧测试写死8192/普通上限失败，按新独立审批约束修改后仅重跑失败方法通过；所有失败 XML 保留。live结束并完成v2冻结核对后，仅将API-only测试夹具写死32768的断言改为读取配置审批上限，随后2项结构检查通过；生产/live入口字节与v2相同，最终源码冻结SHA256为 `322d7a21b2d1c0903290527000c8278b31539878ff5d59a4c2a05c11f8b0f581`。docs/Config.md两行对齐审批64K/600秒，首次触及前原文/时间单列，不回填任务开始字节快照。这些是分阶段定点结果，不是一次完整测试套件。

真机仅按项目筛选 `DeploymentAssistedRegressionLiveAcceptanceTest#deploysAssistedWebsitesForManualAcceptance`、`#deploysAssistedCliForManualAcceptance`，以及必要原任务恢复入口；Maven 均显式 -Dtest、-pl src/mod/deployment -am、-Dsurefire.failIfNoSpecifiedTests=false。前七项已经通过，追加只重跑失败 Survey。网站覆盖代表性业务、重启后持久化及产品生命周期/管理信息；文件收发为64KiB上传下载，问卷84.21分及持久化，CSV报告/worker重启；CLI为原COMMAND健康和期望业务输出。未运行全项目/全模块测试、64MiB标准规模全业务/完整故障集、人工浏览器点按、STATIC或AGENT新部署。

## 受测工作树与本地提交边界

起始 HEAD `ed5bae4814c9d6d464e775352a66866ba0f8761e`，保留889个既有已跟踪改动及大量原有未跟踪文件。verification-01 的2535文件平台Java/资源清单 SHA256为 `424eeeca928f683e432782d75afcbb0adea0026c616ec6c4a5c5eca8aaec3c9a`；verification-02 为 `04552d53c38f6016debb256208c287733afd768fd8111e3292cea65ccbde390a`，仅预算配置及两项配套测试共4文件不同。各阶段运行期间对应冻结检查一致。

另保留全部214个多语言样例文件的全类型/二进制SHA和Git Blob，初始/当前对起始HEAD无样例改动；补充清单记录实际检查时间，不回填首个入口前时间。各成功复制源核对一致，只单列已冻结测试方法有意改写的运行参数文件，实际产品归档SHA另留证。最初误排除合法Java build目录的旧清单保留，补充基线来源明确。

[精确任务增量](ASSISTED-REPAIR-20261020-task.patch)和[基线/当前摘要及提交方式](ASSISTED-REPAIR-20261020-source.json)覆盖21个源码/测试/配套文档文件：12个可独立提交源码/测试，9个混合既有内容或任务前已存在的未跟踪文件只提交精确任务补丁，工作树实际改动保留。补丁与直接提交内容有重叠，不能在本次提交后整体再apply。验收属于所记录的脏工作树与两阶段配置，不宣称纯HEAD可独立重建。仅本地提交，不推送。

## 提供方用量

按generation ID + attempt去重，完整stdout与保留类型化日志取并集并校对重叠数值；保留失败Survey和追加/API结果。原任务对账/清理的旧状态库已另审计，排除上一目标已统计记录。能力检查用量及物理请求数未知不补零；缓存/推理分别包含于输入/输出，不重复相加。不含Codex自身用量，未取得提供方账单金额。

| 阶段 | 已知物理尝试 | 输入 | 输出 | 已知Tokens合计 |
| --- | ---: | ---: | ---: | ---: |
| 中间CSV修复试跑 | 65 | 560011 | 69231 | 629242 |
| 首轮8入口（含Survey失败） | 483 | 3974908 | 486603 | 4461511 |
| 三次NPM审批API门槛 | 3 | 45768 | 5473 | 51241 |
| Survey追加完整入口 | 104 | 907477 | 100711 | 1008188 |
| 本阶段合计 | 655 | 5488164 | 662018 | 6150182 |

另有47项能力检查任务（包括辅助入口失败）的提供方数值计量/物理请求数按原记录保留未知。全部已知尝试的单次输出最大32768，finish=length记录1条；失败并未删除。持久日志每类别最多100条，重叠校对644次，stdout独有11次，持久记录独有0次。完整逐次来源、缓存/推理和未知边界见[用量导出](ASSISTED-REPAIR-20261020-usage.json)。上一轮3061684个已知Tokens仍只在旧报告中，未混入本阶段合计。

## 最终证据和限制

证据根 `.ai-workspace/evidence/assisted-repair-20261020/` 保留基线、全部失败复现/JUnit、原任务对账、精确清理、两阶段源码/样例检查、逐次模型记录和API门槛、所有产品载荷、只读服务器/HTTP、临时目录及进程检查。交付前本地验收与诊断进程全部退出；远端成功网站作为产品交付保留RUNNING/自启DISABLED。

SSH底层间歇通道打开问题仍未证明根治；本次仅对已证明未发送的固定只读检查允许一次新许可后再观察，并完善部分重启失败结算。模型输出预算也仍是有限资源；提高到64K和这些通过样本不能证明任意后续多语言项目都会一次通过。
