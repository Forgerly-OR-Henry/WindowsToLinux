# task-board 主 Agent 自主完整部署验收 102

2026-10-14，本轮通过产品入口完成 task-board 的纯部署、真实业务、浏览器、应用重启持久化和产品停止/禁用。审批在整个新 Goal 的 transport 前关闭，Runtime 关闭后审批尝试、provider 调用、Token 均为 0。本轮不构成独立审批协作验收，也不构成最终八项目验收。

## 实际运行与冻结范围

- 唯一方法：`DeploymentStaticLiveAcceptanceTest#deploysWebsiteThroughMainAgentDiagnostic`；`mainOnlyDeploymentOnly=false`。
- run：`task-board-main-only-102-01`，实际时间 `2026-10-14T14:41:38.8820132+08:00` 至 `15:12:12.3543133+08:00`。
- 原报告：1 个测试，失败/错误/跳过均 0，XML 用时 1820.222 秒；Maven 和源码冻结检查均退出 0。
- 运行时 HEAD：`009417aeaacc8dcc17debbe43435696948cb3476`。3297 份源码与恢复 100 的 `source-after.json` 一致，运行前后无源码、Skill 或配置热改。
- 部署 JAR SHA-256：`734e3fd65709061f476f077cb512f31c5df92361bbc6f5068ae37cc45e0b7f47`。
- Task：`921ad4ad-d73b-484a-8f79-5bbfb9ef0f03`；App：`wtl-agent-main-only-diagnostic-task-board-9s4sr4hqc`；公开入口：`http://45.192.105.213:48200`。
- 发布 revision SHA-256：`1fc0217edf3573f56d4b033c6180f043c9e19c31b3191b91628cb41fadce46d4`；ownership SHA-256：`b748a84e95e519bb1962a367d5609e642c073780e72f7184d23d25a2981b321b`。

保留原迁移和其他任务工作区改动；本记录提交只包含本文，不将当前工作区构建表述为干净 HEAD 构建。

## 自主闭环与实际业务

Root 只启动验收、监控模型与产品回执，没有接手 SSH 部署或提供逐步修复答案。模型自主读取源码、识别组件、重新观测容量并调整方案。两组件 AUTO 方案因容量不足被拒后，它尝试显式配额及单组件方案，进入准备阶段；托管工具链安装因空间门槛被拒后，它通过产品命令能力观测现有 Java/Maven/Node，自主构建。

后端 Maven 重打包实际遇到 `No space left on device`。模型自行切换为运行时依赖与 `javac` 编译布局，又追查并修复参数名缺失引发的 API 500、静态文件复制顺序引发的 404。它完成候选运行检查、封存、交付；交付模型自主调用 `publish`、`verify`、`finish`。这些修正发生在模型获授权的候选工作区内，产品代码和验收源码保持冻结。

实际产品回执确认原 Task `ENDED`、成功归档、健康组件 `task-board`，并由正式完成任务业务入口读取本 App 的发布身份与公开 URL。外部业务检查沿浏览器真实 API 配置创建任务编号 4“静态真机持久化”，执行产品 `STOP`、`START`、`RESTART` 后，HTTP/API 仍读取到该记录；同源 API 及真实浏览器数据读取通过。

真实浏览器从公开入口读取项目及任务数据，确认任务编号 4；随后通过前端表单创建“浏览器验收102”（编号 5、版本 1、标签 `browser102`），点击“开始任务”后变为进行中、版本 2。刷新页面后仍显示任务 4 和 5，观测到的浏览器 error 日志为空。完整页面截图、浏览器记录和与实际 App/URL 绑定的回执均已保留。浏览器曾有一次错误定位将任务标题当成链接，超时后按实际可访问性文本校准；未因此重跑业务或付费调用。

最终产品检查确认本 App `STOPPED`、自启动 `DISABLED`，输出 `STATIC_LIVE_CLEAN ... stopped/disabled`。初始和活动采样记录的 7 个唯一 PID/创建时间组合均已退出，正常用户 CIM 正向控制通过，未终止其他进程；短时子进程采样不作为穷尽证明。临时浏览器标签已关闭。本轮唯一 GUID 加密配置副本已精确删除，原加密配置双 SHA 未变；发布应用、持久数据、原调查数据库和失败证据保留。

## 实际用量

| 范围 | 实际值 |
|---|---:|
| LANGUAGE 物理尝试 | 42 |
| 已知输入 Token | 2,346,526 |
| 已知输出 Token | 122,217 |
| 已知合计 | 2,468,743 |
| 缓存命中输入 | 1,270,784 |
| 缓存未命中输入 | 1,075,742 |
| 已知输入缓存命中率 | 54.1559735541% |
| reasoning 输出子集 | 97,468 |
| APP 尝试 / provider 调用 / Token | 0 / 0 / 0 |
| 能力探测 | 2 次；provider usage 和 transport 次数 UNKNOWN |

42 次观测到的生成尝试逐字段 usage 均已知，输入等于缓存命中加未命中；未把未知能力探测当成 0，reasoning 不重复加到输出。单次输出上限 32768。本轮为调试诊断，其成本和此前恢复 100 的独立审批用量均不混入最终冻结八项目轮。54.16% 不是最终缓存指标达标证明。

## 原证与未验收项

原证在 `.ai-workspace/agent-live-evidence/agent-autonomy-20261012/`：

- `task-board-main-only-102-01.log` SHA-256：`8c623567ada2d71b249077833e5a83c0880d63bca7d67ed833a0f322e5df349d`。
- `task-board-main-only-102-01/reports/` 原 XML SHA-256：`7ef4fbf7e2e4624483408565a702ed726097af5053c9095810571afe1c6b34cd`；TXT：`d6e67e8f912151ac563550e50be8e20e67ba5c726df907c53f51b02837af8eab`。两报告修改时间均落在本轮实际运行区间。
- `task-board-main-only-102-01/browser102-full-page.jpg`、`browser102-verification.json`、`browser-ready.json`、`browser-result.json`。
- `main-only-full-business-live-102/task-board-main-only-102-01-usage-summary.json`、`sealed-verification.json`、`parent-verification.json`。父核对原证、12 辅助脚本封印、完整源码清单、JAR、报告和生命周期标记均通过；没有新增业务测试、模型、SSH 或数据库调用。

本轮证明主 Agent 在此冻结版本和实际服务器状态下可自主完成 task-board 闭环，不能推出审批是此前唯一问题。下一阶段逐步恢复独立审批，观测主模型与审批模型协作。不同模式下的动态配额选择仍需各自实际证据；本轮不推断 STATIC/ASSISTED 的真实部署结果。应用重启已验，服务器整机重启、非默认目录真实部署/重启恢复尚未验；其他七项目和全新服务器冻结八项目轮尚未启动。

原 86 准备恢复及恢复明细清理分别沿 [恢复 100](DEPLOYMENT-AGENT-ORIGINAL-PREPARATION-RECOVERY-100.md) 和 [定点核对 101](DEPLOYMENT-AGENT-SETTLEMENT-DETAIL-AUDIT-101.md) 的已有真实证据，不把它们算成本轮新恢复验收，不改写原失败部署结局。
