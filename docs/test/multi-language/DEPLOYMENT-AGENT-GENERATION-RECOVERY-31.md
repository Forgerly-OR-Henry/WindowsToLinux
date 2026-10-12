# Agent 纯生成传输恢复 31

2026-10-13：本轮完成 HTTP 正文截断后的有界纯生成恢复及直接本地验证，没有新发付费部署、访问真实 SSH 或操作原调查库。[23](DEPLOYMENT-AGENT-MAIN-ONLY-RESULT-23.md) 已证明本条件下纯主 Agent 部署闭环；[28](DEPLOYMENT-AGENT-DUAL-RESULT-28.md) 则在审批响应 HTTP_CHUNK_INCOMPLETE 后进入 UNKNOWN，主模型没有后续反馈。28 的 24 条正式审批均 APPROVE，不能把这一传输失败解释成 DENY，也不能据此判断主模型不会部署。

App 的单次逻辑生成现在可显式选择额外生成次数。仅无完整 AiResponse、IO_FAILURE/AI_TRANSPORT_UNKNOWN 且分类为 HTTP_BODY_INCOMPLETE 或 HTTP_CHUNK_INCOMPLETE 的当前纯客户端函数协议可进入此路径。每次创建独立 ModelHttpCall，每个实例仍至多发送一次；确认上一客户端实际关闭、持久租约释放后，沿用原输入字节、模型及配置绑定、工具历史、输出预算和总截止时间，并重新核对任务授权、取消与绑定。完整响应才会解码及接纳，旧工具结果不会重放。取消、超时、响应上限、格式错误、完整拒绝、清理不明和业务效果未知均不触发新生成。

旧 AiRequest 构造入口及普通 DeploymentAiJson 默认额外次数为 0。部署 Mod 配置 `ai.requests.generation-recovery-attempts` 默认 1，允许 0..16；0 关闭，最多保留 17 次物理尝试。Agent 主循环与独立审批分别在自己的入口显式选择该配置。ASSISTED 原调用仍为 0，STATIC 保持人工审阅及确定性执行；没有将部署编排移入 App，也没有给 STATIC 引入 AI。字段已完整注释，原普通输出 32768、审批 8192 不变。DB 仍为版本 1，原环境及目录配置捕获、冻结计划、正式身份和历史 UNKNOWN 不被重写。

AiResponse 的类型化 generationAttempts，以及失败 Problem 中对应的有界编码，保留各次原结果、模型/协议、序号、耗时、请求字节和 provider usage。首个 UNKNOWN 与缺失用量不会被后次成功覆盖。重试耗尽或守卫停止仍保留 UNKNOWN；已有其他未知效果不会清除。DeploymentAiCallMetrics 和真实验收观察器可读取成功及异常轨迹，按物理尝试的已知用量小计统计，最终逻辑响应不重复累加。未知字段及未测量调用单列；诊断存储失败也不被当作完整计费证据。

| 直接验证范围 | 实际结果 |
| --- | --- |
| 真实本地 HTTP BODY/CHUNK 截断后新生成、独立连接及旧租约释放、原字节及工具历史 | PASS；只接纳新的完整工具响应批次 |
| 额外次数 0/1/16、耗尽、取消、原期限、配置变化、真实租约释放失败、已有 UNKNOWN | PASS；不越过相应守卫 |
| HTTP 拒绝、schema、length、响应上限、空 headers、完整 DENY | PASS；不因这些结果重试 |
| 三协议原生续接、续接保存失败、旧单 HTTP 实例不重发 | PASS；没有跨模型回退 |
| Agent main/APP 选择配置、普通旧入口关闭、独立审批闭合 DENY、配置边界 | PASS；未放宽审批或 coverage |
| 成功及异常的物理用量、未知保留、真实监控累加和预算停止 | PASS；最终响应不重复计费 |

实际限定执行六轮 Maven 方法/场景 selector，44 个不同逻辑 case 最新全部 PASS，并非模块或全仓库全量测试。合计 65 次执行：62 次有原 XML，另外原 Maven 日志记录 3 次 SKIP；2 FAIL、1 ERROR 保留。首轮两项失败是成功归档后仍期望 working resource 表保留已释放记录，调整测试断言时机；另一项错误是审批夹具的 completeExecutableInput 与 stdin 不一致，修正夹具后单独通过，生产 coverage 守卫未改。监控三场景因类级开关先 SKIP，后来仅对三个本地 fake 方法显式启用入口后通过，没有付费模型或真实服务器调用。

辅助脚本早期复制了旧 XML，父核按本轮 Running 类别排除八份旧报告。后续文件名拼接错误造成 04–06 未复制报告，修正后仅从原 target 按类别及实际修改时间恢复仍存在的 XML。04/05 的 SKIP XML 已被 06 覆盖，不能恢复，保留原日志的 1/2 次 SKIP，未补造 XML。父辅助验证器的长路径及报告缺失失败也保留，不作为业务失败或抹除后的通过。格式化两次受限用户 socket 权限失败保留；正常用户运行后 15 个 owner Java 文件 write/check 通过。

客户端物理关闭失败没有注入模拟；该分支停止并保留占用由代码检查确认，不能将真实租约释放失败测试当作物理关闭失败实测。外来或已结束 context、owner 撤销及外层 CUSTOM scope 释放失败没有新增专用夹具，保留原守卫，不能宣称本轮这些场景实测通过。此次没有证明提供方截断根因、未知请求实际账单、新生成一定成功，或真实服务器部署恢复。公开轨迹不是执行授权，新的响应仍须经过原独立审批、绑定和宿主授权。

父独立核对 3281 原像与 28 冻结一致、3282 后像、当前全范围文件清单及原始字节，仅 16 个 owner 改动，非 owner 差异和删除为 0。精确补丁正反向应用后逐字节一致，检查临时目录已按工作区边界清除。补丁 SHA256 `4baf87f6cc35312af3f90f70d209f7db305b31bbc2e9dc3fdd6452555d95a1f4`；后像 manifest SHA256 `d0e17961833e6c55f9dfec51d10327523312de66d94d8f39fd8806fbad13d7c8`。正常用户 CIM 核对本轮自有临时进程 0，没有终止其他进程。辅助 usage parser 合成验证未知后成功只计已知小计一次，原 28 统计仍为 2231316/72.51%；未重发付费请求。

源码、报告、失败轮及父核证明保留在 `.ai-workspace/agent-live-evidence/agent-autonomy-20261012/agent-generation-recovery-31/`。交付隔离原迁移：8 个原像与 HEAD 相同或本轮新增的文件可独立提交，其余 8 个混合文件保留原修改，通过[精确增量补丁](DEPLOYMENT-AGENT-GENERATION-RECOVERY-31.patch)交付，不整暂存原迁移。验证针对当前工作区，不能表述为仅检出本次提交即可独立构建的证明。本地提交后不推送。

接下来先实施[25 已确证的外层 delivery 恢复结清缺口](DEPLOYMENT-AGENT-RECOVERY-OUTER-AUDIT-25.md)，仅在原成功操作的全部资源和回执结清后归档可删除明细；随后从产品入口真实恢复并继续 task-board 部署及业务验收。旧 UNKNOWN、未结占用、正式 App 和持久数据及调查继续保留。完整 task-board、成功恢复历史清理、非默认目录真机与重启、全部工具、其余七项目及最终冻结新服务器八项目和综合用量标准仍未完成。
