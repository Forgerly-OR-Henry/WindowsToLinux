# task-board 双 Agent 对照结果 24

2026-10-13 05:46:27+08，`task-board-dual-agent-24-01` 已结束：1 个定点测试失败，0 error/skip，测试耗时 4444.606 秒，平台任务保留 UNKNOWN。此次仅部署 task-board，通过正常产品 Agent 入口使用真实独立审批，MANUAL_REVIEW、remember=false；未安装纯主 Agent 诊断许可。源码、配置和 Skill 冻结 3281 项，运行前后零变化，与[纯主 Agent 成功轮 23](DEPLOYMENT-AGENT-MAIN-ONLY-RESULT-23.md)的产品字节相同，测试入口仅使用独立端口 46500。

原任务 `9301bf86-73ca-46a6-9b14-2bc122781859`，App `wtl-agent-task-board-9qrowmp4g`，DB 目录 `src/app/db/data/agent-live-task-board-9qrov2f4c`，产品声明 HTTP 入口 `http://45.192.105.213:46500`。主 Agent 自主识别、计划、处理工具链准备失败、执行获授权命令、声明数据库、封存两个组件、交付、发布和验证；没有监督代理接手 SSH、提供现场部署步骤或修改运行中的代码及 Skill。

主 Agent 有 50 条 LANGUAGE 响应，审批有 321 条 APPROVAL 响应。65 条实际 submit_result 中，63 次 APPROVE、2 次 DENY；次数描述的是审查提交，不代表 65 个部署业务阶段。最终主 Agent 调用 unable，平台保留两个源码快照占用与 Business result requires explicit reconciliation；没有 finish、成功任务归档、真实业务操作或浏览器验收。此前发布回执包含 backend/frontend PUBLISH_RESULT、HEALTH_RESULT RUNNING 和 GRAPH_HEALTH_RESULT HEALTHY，不能将这些旧观察替代尚未执行的新检查、最终结清或业务验收。

本轮确认两个不同问题：

- 日志第 5172 行的第一次 DENY 将待审批 runtime 观察缺少自己的执行回执作为拒绝理由，并声称同请求已有拒绝。固定 SYSTEM 合同明确属于 PRE_EXECUTION，未发送的请求没有自身结果；摘要确实不能证明运行状态，但要求该检查审批前先完成自身构成循环证据要求。此前捕获的正式提交均为 APPROVE；模型自述的 REQUIRED_EVIDENCE_UNAVAILABLE 和“之前已拒绝”不能当作正式历史 verdict。当次完整分页和 previousVerdict 是否送齐仍未确认，不能由此宣称整个审批门有缺陷或必须放行。
- 第 5696 行拒绝主模型提出的 root systemctl restart：单元名没有绑定正式发布身份，resourceTargets 也不足，且 verify UNKNOWN 本身不能证明重启必要性。这一拒绝有独立依据，应保留。正常 Agent 发布循环调用 verifyPublished 时没有接收已有 diagnostics 回调，实际审批异常会折叠成 DEPLOYMENT_GRAPH_HEALTH_UNAVAILABLE，主模型只得到健康不可用摘要。应在 Agent 独立层补充准确、有界的原因反馈，不能据此修改共享审批门或伪造健康结果。

构建期间约 30 分钟的等待发生在 Maven BUILD SUCCESS 之后：主模型用 java -jar 的 --help 作为检查，而该样例正常启动 Spring 服务，没有帮助退出分支。随后主模型自主改用有时限的后台健康检查。该等待不能归因于审批拒绝或编译失败。工具链隔离的宿主空间不足属于独立工具链卷，不是候选工作区只能固定 4 GB 的证据。

| 本轮捕获 provider usage | 部署 | 审批 | 合计 |
| --- | ---: | ---: | ---: |
| 输入 token | 2,562,093 | 4,188,959 | 6,751,052 |
| 输出 token | 117,359 | 112,163 | 229,522 |
| 已知输入与输出 | 2,679,452 | 4,301,122 | 6,980,574 |
| 缓存命中输入 token | 1,425,792 | 2,852,992 | 4,278,784 |
| 缓存未命中输入 token | 1,136,301 | 1,335,967 | 2,472,268 |
| 捕获输入缓存命中率 | 55.65% | 68.11% | 63.38% |

审批占已知输入与输出的 61.62%，普通部署单次输出上限 32768、审批 8192。371 条监控响应均有输入、输出和缓存 usage；部署 reasoning 91,656 是输出子集，321 条审批的 reasoning 字段均 UNKNOWN，不能把已知值求和 0 当作实测零。四项配置能力探测成功，但 provider usage 和 transport 次数 UNKNOWN。以上仅为当前失败开发轮，不能混入最终冻结新服务器八项目统计，也不能通过关闭审批声称正式审批占比达标。

本轮 Runtime 和 Maven 已退出。正常用户 CIM 根据捕获的精确 PID 与 CreationDate 核对，本轮自有验证进程为 0，未终止其他进程。临时受保护凭据副本已按精确工作区/GUID 路径清除，原凭据源 SHA 不变；IAB 临时浏览器标签已不存在。因任务 UNKNOWN，未执行本轮 App 的停止、禁用或删除，不清除已发布应用、候选、正式记录、持久数据、原 16/19/21 调查或未结清资源。保持 DB 版本 1。

原始 log/XML、冻结前像与结果、usage、result.json、processes-final.json、credential-cleanup.json 和父核证明保留在 `.ai-workspace/agent-live-evidence/agent-autonomy-20261012/task-board-dual-agent-24-01/` 及同名前缀文件；审批只读审计在 `agent-verify-review-audit-26/REPORT.md`。父核重新解析现有结果并核对 3281 项当前字节，没有请求模型或服务器。log SHA256 `4cd10eecbf6b5b191aa49e77d03a3be7d610aae8c3c9be2802ab73bc7d7a003c`，XML SHA256 `e7a128a58cb035346a508e46ed4c66d37f77baabfc8c27d9822115eb203dd3ac`。

方向检查：先关闭审批隔离部署问题的目标已由 23 的实际成功结果满足，不重复付费取证；24 同版本对照用于定位具体审批问题。纯部署成功证明当前主 Agent 在本条件下可以自主完成部署，不能保证所有项目、工具和业务验收都成功。下一步仅修复实际阻塞的按需 Skill 与 Agent 原因反馈，继续 task-board 的完整验收；真实前后端业务与浏览器操作、全部工具覆盖、成功恢复历史清理、非默认目录真机部署与重启、其余七项目及最终冻结八项目仍未完成。原恢复身份审计另见[记录 25](DEPLOYMENT-AGENT-RECOVERY-OUTER-AUDIT-25.md)，不得把只读核对当恢复成功或删除许可。
