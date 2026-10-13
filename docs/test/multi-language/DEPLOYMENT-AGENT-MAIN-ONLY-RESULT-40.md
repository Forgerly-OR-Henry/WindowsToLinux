# task-board 修复后纯主 Agent 部署 40

2026-10-13 09:58:09+08，`task-board-main-only-40-01` 通过：1 个定点测试 PASS，0 failure/error/skip，843.786 秒。只部署 task-board，审批模型关闭；3282 个源码/Skill/样例条目冻结前后差异 0，启动版本逐 SHA 等于[限定修复 39](DEPLOYMENT-AGENT-MANAGED-RESOURCE-CONTRACT-39.md)的 after-final。监督者只监控，没有在运行中修改代码/Skill、接手 SSH 或给模型补充部署步骤。

原 task `4f22fd0a-e484-4041-8f83-9da6f5485761`、数据库 `agent-main-only-diagnostic-live-task-board-gdfu5zwsg`、正式 App `wtl-agent-main-only-diagnostic-task-board-gdfu7m50c`、实际 Web `http://45.192.105.213:46800`。模型自主读取及识别源码、规划数据库和网络、使用工具或命令构建、封存、按需加载 Skill、发布、验证和 finish。产品确认 backend/frontend 健康，平台任务 ENDED/SUCCEEDED，原成功归档和正式完成任务业务身份相符；revision SHA256 `b0d84bfbea07c2e1ab8f634a8c851eac6c7c08b95160ab7c87b0518266ca73e1`，ownership SHA256 `f3238cacf4410ad2fd5b76daa42e5b0fe59b615808c41a724c7a5c39a243c25a`。

实际自主排障包括：工具链准备报告 `HOST_SPACE_REQUIRED`，当时 volume 所需 9,663,676,416 字节、可用 1,696,399,360 字节；模型自行通过获授权命令核实服务器现有工具并继续构建，没有人工释放服务器数据。前端封存发现 esbuild 硬链接，模型自行修正后通过。原失败观察保留，没有把失败工具调用改成通过。

诊断代理在发送前拒绝全部 APPROVAL 请求，Runtime 关闭后实际 attempts/providerCalls/tokens 均为 0。72 次产品诊断授权决策不等于审批模型审查。生产完整独立审批保持原实现；本轮只证明主 Agent 在这些条件下的自主部署结果，不证明所有审批过严，也不计入正式双 Agent 验收。

| 本轮实际捕获 provider 用量 | 数值 |
| --- | ---: |
| 物理生成 attempt / 已捕获响应 | 36 / 36 |
| 输入 Token | 1,729,861 |
| 输出 Token | 53,633 |
| 已知输入与输出合计 | 1,783,494 |
| 缓存命中 / 未命中输入 | 1,052,672 / 677,189 |
| 捕获输入缓存命中率 | 60.85% |
| reasoning 输出子集 | 36,322 |
| 审批请求 / 调用 / Token | 0 / 0 / 0 |

按物理 attempt 统计，不与最终逻辑响应重复累加。两项 DEPLOYMENT 配置能力 probe 的 provider usage 和 transport 次数仍 UNKNOWN，不记作 0。普通单次输出上限 32768。本轮是开发诊断，不能与历史失败消耗混为同批，也不能据审批关闭的 0% 宣称最终八项目审批成本达标。

普通产品管理入口仅将本轮 App STOPPED/DISABLED，正式 App、发布、安装制品和持久数据保留。Runtime/Maven 自然退出，正常用户 CIM 按 7 个已捕获 PID 与 UTC 创建时间核自有验证进程 0，无 terminate；本轮自建受保护配置 GUID 副本精确删除，原 clean 配置双 SHA 不变。其他 App、旧 UNKNOWN、占用资源和调查证据保持。

父离线复核原 XML/TXT、log、用量、完整冻结、进程与凭据清理通过，没有重复付费取证。log SHA256 `443bc850ef9b6905815657b0982819f552daea1f99a83f052847243c61d646b8`，XML SHA256 `bc186a1fa7c0aa92ce8d0a8e108fead45f00b098eccc344706b248caa83be1b9`。证据在 `.ai-workspace/agent-live-evidence/agent-autonomy-20261012/task-board-main-only-40-01*` 与 `main-only-resource-live-40/`。

[41 同 App 业务轮](DEPLOYMENT-AGENT-MAIN-ONLY-BUSINESS-41.md)已实际失败：进入业务写入前的产品状态查询因 SSH 通道关闭、无退出回执而 UNKNOWN，最终模型调用为 0。另行真实浏览器核正式配置/API可用、创建项目和任务成功，评论因样例在公网 HTTP 调用 `crypto.randomUUID()` 失败。原查询和全部失败证据保留，精确恢复及样例兼容性修复另记；没有把部分浏览器成功当作完整通过。[36 原失败](DEPLOYMENT-AGENT-MAIN-ONLY-BUSINESS-36.md)保留；40 的部署通过不能代替完整业务通过。

原历史恢复清理的真实入口、非默认目录真机及重启、完整工具/权限、生产审批闭环、其余七项目和最终冻结新服务器八项目综合标准仍待。本轮未恢复审批或部署其他项目，数据库仍为 1。修复 39/35 已本地提交 `311dacb0`；本结果与 41、43 记录及本次自有增量统一隔离提交，不推送。
