# task-board 纯主 Agent 部署结果 23

2026-10-13 04:20:35+08，task-board-main-only-23-01 从产品入口完成纯主 Agent 部署验收：1 个定点测试通过，0 failure/error/skip，测试耗时 935.955 秒。运行期间源码和 Skill 冻结 3281 项，前后没有变化。此次只验证部署闭环，审批模型暂时关闭；未启动其他项目。

原任务为 `8da5c944-f76a-4812-97b7-2bcfb9fdc88d`，正式 App 为 `wtl-agent-main-only-diagnostic-task-board-95t3sd7gk`，服务器身份为 `static-live`，产品记录的 HTTP 入口为 `http://45.192.105.213:46400`。主 Agent 自主识别源码、声明数据库和网络、选择工具或获授权命令、处理拒绝和环境失败、构建并封存两个组件、交付、发布、验证和结束任务。监督代理未接手 SSH、给出现场部署步骤或运行中修改代码和 Skill。

真实回执确认 backend/frontend 均健康，主任务自然 ENDED/SUCCEEDED，并存在 PLATFORM_TASK_SUCCEEDED 成功归档及匹配 App/server/revision/ownership 的正式已完成任务使用记录。MAIN_AGENT_ONLY_DEPLOYMENT_PASS 和 MAIN_AGENT_ONLY_DEPLOYMENT_ONLY_COMPLETE 均已输出，19 的 HTTP 入口缺口与 21 的重复数据绑定发布失败没有在本轮重现；不把此前失败记录改成成功。

审批发送前硬拒绝只适用于用户授权的隔离诊断，不改变生产完整独立审批。主循环共有 30 条捕获 LANGUAGE 响应，69 次诊断 domain 判断；Runtime 关闭后审批尝试、provider 调用和 token 均为 0。普通静态边界、身份、回执及授权约束仍有效，不能将该轮解释为关闭所有产品监管。

通过普通产品管理入口仅停止本轮新 App 并禁用自启，最终为 STOPPED/DISABLED；安装记录、正式发布身份和持久数据库保留。Runtime 已关闭，正常用户 CIM 按捕获的本轮 PID 与 CreationDate 核对，自有验证进程为 0，没有终止其他进程。本轮临时受保护凭据副本已按精确工作区/GUID 目录清除，原凭据源双文件 SHA 不变；未删除原 16/19 App、21 UNKNOWN、其他未结清资源或调查证据。

| 当前轮捕获 provider usage | 实际值 |
| --- | ---: |
| 输入 token | 1,420,368 |
| 输出 token | 75,553 |
| 已知输入与输出合计 | 1,495,921 |
| 缓存命中输入 token | 986,624 |
| 缓存未命中输入 token | 433,744 |
| 捕获输入缓存命中率 | 69.46% |
| 审批捕获占比 | 0% |
| reasoning 输出子集 | 60,404 |

30 个监控请求均有捕获 usage，普通部署单次实际输出上限为 32768。两项 DEPLOYMENT 配置能力探测的 provider usage 和 transport 次数仍 UNKNOWN，不能当成已测得的 0。以上是本轮开发对照结果，不混入历史失败轮或最终冻结新服务器八项目统计；审批关闭的占比不能用来证明正式双 Agent 成本达标。

证据保留于 `.ai-workspace/agent-live-evidence/agent-autonomy-20261012/`：本轮 log、冻结前像及结果、独立 run 文件夹内 result.json、原 XML、processes-final.json、credential-cleanup.json、parent-result-verification.json，以及 task-board-main-only-23-01-usage.json。父核仅复用这些已存在的证据，不再次请求模型或服务器。log SHA256 为 `e1fb8e486c5687fbb0de708e2c3a3a0b9b3dafceaaddd6f50a397318c25e0eef`；XML SHA256 为 `98def4d1efb0ee18bcf890ed7634586476773e87d53d26cd51495f1a7773cc5c`。

当前版本主 Agent 在本条件下已经能自主完成部署。随后应以同版本的 task-board 完整双 Agent 对照隔离审批阻塞；一次纯部署成功不能独立证明审批过严或定位具体审批缺陷。真实前后端业务操作和浏览器交互、完整工具覆盖、成功恢复历史清理、非默认目录真机部署与重启、其余七项目及最终冻结新服务器八项目仍未完成。保持 DB 版本 1，不重写或重放原冻结错误计划。

相关实现和失败证据见[纯部署入口 19](DEPLOYMENT-AGENT-MAIN-ONLY-DEPLOYMENT-PROBE-19.md)、[HTTP 契约修复 21](DEPLOYMENT-AGENT-HTTP-PLAN-CONTRACT-21.md)及[数据绑定契约修复 22](DEPLOYMENT-AGENT-MANAGED-DATA-CONTRACT-22.md)。这些修复已按各自限定范围验证并本地提交，原迁移混合文件只交付精确增量；尚未证明独立干净 checkout 构建。本次仅新增结果文档并同步交接，默认不推送。
