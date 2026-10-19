# ASSISTED 多语言首次完整部署复验（2026-10-20）

**结果：2/8首次完整验收通过，6/8未通过，未达到“多语言项目全部一次性通过”。** 通过项目为资产借还、二进制检查。五个UNKNOWN原任务经产品对账，四个结清为ENDED/FAILED；任务看板的生命周期任务仍UNKNOWN，其后端仍运行，原现场保留。没有将对账、恢复或后续环境改善计作首次通过。

生成时间：2026-10-20T02:23:01+08:00（Asia/Singapore）。

## 受测范围与配置

只运行8个多语言项目的ASSISTED真机定点入口，每项目仅一个实际部署主任务，顺序为本表顺序。各项目使用隔离的本地产品状态目录和唯一应用ID，共用同一台起初为空的服务器；后续项目可复用前项已由产品安装的工具链。首轮通过指唯一一次完整部署入口通过，包含正常多轮模型调用、独立审批及产品自动补齐，不表示只有一次API响应。

- 模型deepseek-flash，部署/独立审批LOW/LOW，输出上限32768，审批期限600秒；每项目已知Token观察阈值1000000，入口等待90分钟。运行期间未改配置或源码。
- 服务器45.192.105.213:22，Ubuntu24.04.1；新主机指纹`SHA256:mA22eywGncT1fkyeabnZDrv6s6E1Qd9OX2PaBfy7z18`。首次只读观察后固定该指纹。预检无产品应用/单元、网站端口空闲，根分区可用27G。
- 工作树起始HEAD为`b6e7e8c308a0fff3fb43956b53587b5b1e9da91c`，存在大量既有未提交改动，均保留。受测源码快照2220文件前后摘要未变；快照清单SHA256为`efefc51076e18ce654e84fe555fec6f2ef101843e8fb88d1fc1edbb6589b6179`。本轮结论针对该工作树，不宣称纯HEAD复建通过。
- 所有安装、补齐、构建、发布和业务写入由产品执行。监督侧仅启动、观察、读取证据及只读诊断。已取得用户明确授权向配置的DeepSeek服务发送验收所需样例源码、部署上下文和诊断信息；报告不含明文凭据。

## 首次结果

8个JUnit均实际执行、无跳过；2通过、6失败、0错误。每个成功结果均匹配产品管理导出。

| 项目 | 源码语言 | 首次完整结果 | JUnit秒数 | 实际停点/通过范围 |
| --- | --- | --- | ---: | --- |
| 任务看板 | TypeScript + Java | 未通过 | 1671.677 | 部署主任务成功；业务重启中的状态检查SSH打开超时，生命周期任务UNKNOWN |
| 文件收发 | TypeScript + Go | 未通过 | 759.797 | 独立审批拒绝发布快照：认为来源logicalRootName与组件应用标识不一致；原任务UNKNOWN |
| 资产借还 | JavaScript + C# | 通过 | 1132.775 | 完整入口通过；业务、重启持久化、生命周期及管理导出齐全 |
| CSV检查 | JavaScript + PHP + Python | 未通过 | 711.315 | 独立审批拒绝配置暂存：认为未提供assert_storage_parent实现；原任务UNKNOWN |
| 问卷评分 | TypeScript + Kotlin + Ruby | 未通过 | 45.709 | 审阅阶段FAILED：DEPLOYMENT_STATIC_REVIEW_INVALID；未构建发布 |
| 日志分析 | Rust + C++ | 未通过 | 1587.657 | 缺CMake/Ninja，独立审批拒绝补齐脚本；原任务UNKNOWN |
| 目录差异 | Python + C++ | 未通过 | 114.210 | 缺CMake/Ninja，独立审批拒绝补齐脚本；原任务UNKNOWN |
| 二进制检查 | Rust + C | 通过 | 380.647 | 同一原任务内经独立审批补齐CMake/Ninja后通过构建、COMMAND健康输出及管理导出 |

任务看板部署根`6fcab371-9c67-414b-8fc0-8bc8223f2840`已SUCCEEDED。后续RESTART任务`6fb2a6ce-c78c-4fc3-8eab-52464d879b61`中，启动动作执行后，`deployment.publication.inspect.v1`在SSH通道打开阶段约20004ms失败；日志为`SSH_COMMAND_AUTHOR_PAYLOAD_NOT_SENT`、`inputState=NOT_STARTED`、`channelOpenConfirmed=false`。这证明该次检查载荷未发送，不证明之前的重启动作未执行，故保留整项UNKNOWN。SSH打开失败的根因仍未证明。

另外四项已从持久化产品日志确认`DEPLOYMENT_COMMAND_REVIEW_DENIED`。表中描述的是独立模型的拒绝理由，不等同于这些技术判断已被逐条证实，也不把它们误归为SSH故障。问卷评分的错误来自共享审阅校验；原生建议语法有效，现有记录尚不能确定具体哪个审阅字段触发异常。

日志分析首次安装Rust1.95.0成功，安装器一段约64秒的只读采样中，打开文件从49,283,072增至50,331,648字节；后续停点是补齐CMake/Ninja的独立审批。二进制检查后来在自己的原任务内提交了不同补齐命令并完成发布，未重跑前两个CLI项目。

## 成功交付

- 资产借还：[http://45.192.105.213:46120](http://45.192.105.213:46120)；产品导出RUNNING、自启DISABLED。最终只读GET主页返回200。
- 二进制检查：产品导出INSTALLED、ON_DEMAND，自启NOT_APPLICABLE。实际启动命令如下；需在该服务器执行并按样例README追加业务参数。

```bash
sudo /usr/local/lib/windowstolinux/managed-helper app-run wtl-assisted-binary-inspector-8tun4unj4 ed13711b212243de5101637a0ac07eff5c222428a76a39ce1699c34b5d13c1be --
```

两个完整、未改写的产品管理载荷见[管理导出](ASSISTED-FIRSTPASS-20261020-management.json)。未给失败项目的预设端口标记成功网址。

## 原任务对账与现场

对账只针对同一原任务、同一状态目录，使用现有产品入口`reconcilesOriginalTaskThroughProduct`；没有重新发起部署。

| 项目 | 对账原任务 | 对账后状态/结果 | 对账JUnit |
| --- | --- | --- | --- |
| 任务看板 | `6fb2a6ce-c78c-4fc3-8eab-52464d879b61` | UNKNOWN/未定 | 未通过 |
| 文件收发 | `61643e1b-233c-48d2-a399-d516218d0870` | ENDED/FAILED | 通过 |
| CSV检查 | `c2d01fae-c3bb-4027-bbb2-8dfa5bc9a6ee` | ENDED/FAILED | 通过 |
| 日志分析 | `31dd0ac9-f921-4b84-a3b1-1d930d47ad1a` | ENDED/FAILED | 通过 |
| 目录差异 | `133e9f51-aba4-4973-bc16-a0543cdd92f7` | ENDED/FAILED | 通过 |

TaskBoard对账未完成，未强制清除资源、修改数据库或手工停止服务器进程。最终只读观察：资产借还两个单元active/running；TaskBoard后端active/running、前端未运行，两单元自启disabled；二进制检查为按需单元（unit文件状态static）；没有活动构建单元或遗留候选工作目录。`/opt/windowstolinux/apps`保留五个实际部署目录。产品workspace-recovery单元inactive/dead。

本轮本地Java/Maven/PowerShell验收及只读辅助进程已全部退出；最后进程检查为0。首次进程检查与最终只读辅助执行并行，曾记录到该辅助进程，待其正常退出后重新核对为0；两个快照均保留。未创建待清理的任务临时目录，复现脚本和证据保留在证据根。

## 提供方用量

按generation ID + attempt去重，合并完整stdout与持久化日志。已知物理尝试的输入、输出、缓存、推理指标均已记录；能力检查用量/请求数未知，单列不补零。不是Codex自身用量，账单金额未知。

| 阶段 | 已知物理尝试 | 输入 | 输出 | 已知合计 |
| --- | ---: | ---: | ---: | ---: |
| 8项首次完整入口 | 343 | 2,631,477 | 320,617 | 2,952,094 |
| 原任务对账/收尾 | 18 | 102,050 | 7,540 | 109,590 |
| 合计 | 361 | 2,733,527 | 328,157 | 3,061,684 |

另有32项能力检查的用量及物理请求数未知。缓存命中1,598,976、未命中1,134,551包含在输入；推理281,764包含在输出，不能重复相加。已知尝试中单次输出最大29113，距32768余3655；保留记录未见输出耗尽。本轮没有测量费用节省。

| 项目首次入口 | 已知物理尝试 | 已知Token |
| --- | ---: | ---: |
| 任务看板 | 83 | 846,616 |
| 文件收发 | 51 | 410,443 |
| 资产借还 | 73 | 622,942 |
| CSV检查 | 39 | 317,914 |
| 问卷评分 | 14 | 71,007 |
| 日志分析 | 18 | 133,450 |
| 目录差异 | 19 | 144,776 |
| 二进制检查 | 46 | 404,946 |

[逐次用量](ASSISTED-FIRSTPASS-20261020-usage.json)包含全部361次已知尝试及未知能力检查。日志每类别保留窗口有限，首轮343次均有stdout物理尝试记录；对账新增18次由持久化审计补齐。

## 验证边界与证据

只执行两个首轮方法`DeploymentAssistedRegressionLiveAcceptanceTest#deploysAssistedWebsitesForManualAcceptance`、`#deploysAssistedCliForManualAcceptance`，均通过`managed.live.projects`逐项目筛选；另执行原任务对账方法及连接定点检查。没有全项目/全模块测试，没有STATIC或AGENT真实部署；Agent继续暂停。未运行标准规模数据、全套故障场景或浏览器人工点按；本轮成功仅代表现有真机入口的业务/持久化/生命周期或CLI健康输出范围。

本地准备曾在沙箱DPAPI创建数据库时失败（JUnit1错误），模型请求及产品SSH审批均为0；正常用户上下文连接检查随后通过（JUnit1，0失败/错误/跳过）。辅助配置读取曾有一次键名空格引发StopIteration；修正的是任务辅助脚本，未开始部署。自动审查首次拒绝外发目的地授权不足的队列，实际未执行；用户明确授权后才启动八项队列。这些准备记录与实际部署主任务分开，未隐藏失败。

证据根`.ai-workspace/evidence/assisted-firstpass-20261020/`保留所有原日志、JUnit、管理载荷、首次/最终用量审计、独立拒绝事件、对账结果、源码摘要和进程检查。程序源码未改；只新增本轮正式验收记录和导出，原工作树改动保持。

| 项目 | 实际部署主任务 | 应用ID |
| --- | --- | --- |
| 任务看板 | `6fcab371-9c67-414b-8fc0-8bc8223f2840` | `wtl-assisted-task-board-6oi3gmqdw` |
| 文件收发 | `61643e1b-233c-48d2-a399-d516218d0870` | `wtl-assisted-file-transfer-79wl0sc7o` |
| 资产借还 | `f4fcce83-b0cb-4081-b8c5-362eacda1752` | `wtl-assisted-asset-lending-7jo1k3m6s` |
| CSV检查 | `c2d01fae-c3bb-4027-bbb2-8dfa5bc9a6ee` | `wtl-assisted-csv-inspector-7y70o7u10` |
| 问卷评分 | `5f9e4f8d-9e83-45e1-8c0e-7f9eab195f06` | `wtl-assisted-survey-scoring-87c898cds` |
| 日志分析 | `31dd0ac9-f921-4b84-a3b1-1d930d47ad1a` | `wtl-assisted-log-analyzer-87zpjkd9o` |
| 目录差异 | `133e9f51-aba4-4973-bc16-a0543cdd92f7` | `wtl-assisted-directory-diff-8sbmrb7r4` |
| 二进制检查 | `59737722-a275-4797-939e-0bd303420260` | `wtl-assisted-binary-inspector-8tun4unj4` |

后续定位应以保留证据为起点：先区分来源逻辑标识与部署组件标识，核对安全函数及工具链缺口/版本校验材料，再定位问卷审阅字段和TaskBoard的SSH/恢复链路。本轮未放宽独立审批、重试失败部署或手工补齐服务器。
