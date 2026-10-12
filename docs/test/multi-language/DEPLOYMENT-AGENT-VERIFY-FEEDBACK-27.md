# Agent 验证失败原因反馈 27

本次修复[双 Agent 对照 24](DEPLOYMENT-AGENT-DUAL-RESULT-24.md)中确认的反馈缺口：正常发布 verify 没有承接已有 Publisher 诊断回调，审批异常折叠成健康不可用，主模型下一轮无法区分检查被拒绝与检查后的运行异常。本次仅在 Agent 独立 PublishRunner 中接入原回调，不修改共享 Publisher、审批、回执、清理或数据库协议。

模型当前状态新增单次 `lastVerificationFailure`。每次验证清空；当前原任务绑定的真实 typed 拒绝提供脱敏且有界的原因、缺失证据摘要、审查 binding 和实际 OPEN/CLOSED/UNKNOWN 讨论状态。只有该被拒绝调用可以标注未发送；已有发布、其他验证调用和旧副作用继续以原记录为准。其他 API 异常仅返回 code/受限 event，普通异常仅返回受控分类，未知传输不会凭空产生拒绝或未发送证明。数据明确 DATA、authority NONE，不包含任意异常正文，也不授予健康、替代执行或清理权限。

原八字段 publication receipt 及其完整 JSON、PUBLICATION_NOT_VERIFIED、verified=false、finish 门保持。下次验证成功不会遗留上次拒绝。按需 publication-verification Skill 增加执行前观察与执行后健康结论的区分；已关闭 DENY 不重开，不规定工具序列，也不包含 task-board 的现场部署答案。技能未加载时，正式提交和回执门仍成立。

直接本地验证限定已有 DeploymentAgentPublishRunnerTest 的相关方法：下一轮真实 native AiRequest 可见原拒绝与 OPEN 讨论状态，拒绝前不发送，未验证状态不能 finish；typed OPEN 后受控独立健康验证成功清除原失败；普通 ACCESS_DENIED、外来任务拒绝、普通异常及 transport UNKNOWN 不造审批原因、未发送或健康证明，不输出合成秘密；原 Skill 按需加载场景仍通过。此处使用本地 H2 与受控能力替身，没有真实 provider、SSH 或原 live DB。

| 轮次 | 实际范围与结果 |
| --- | --- |
| focused-01 | 6 项，5 PASS/1 FAIL；唯一失败为夹具预期 CLOSED，而原审批真实返回 OPEN |
| focused-02 | 只纠正夹具并重跑该失败方法，1 PASS |
| focused-03 | 直接相关原 Skill 加载方法，1 PASS |

最终 7 个独立逻辑场景最新全部通过，8 次实际执行中的 1 次失败保留；未重复其余已通过场景，没有全模块或全项目测试。每轮冻结 3281 项且零变化，最终两项 Java 格式写入/检查退出 0，自有验证进程 0。

官方 skill-creator quick_validate 在本地 Python 和桌面自带 Python 都因缺 PyYAML 未成功运行，原 traceback 留存，没有安装依赖或伪造模块。严格现有双字段 plain frontmatter、UTF-8、名称/描述界限及前像一致性检查通过，产品实际按需加载由 focused-03 验证。格式检查与受控加载不能证明真实审批模型已正确理解新 Skill。

父核前后 3281 项完整 raw/SHA/current 字节，确认只有 PublishRunner、原 Skill 和已有 PublishRunnerTest 三个 owner 变化，未新增/删除源码或改变其他迁移。父核独立解析各轮原 XML，隔离精确补丁正反向后逐字节相等。辅助补丁检查最初因仓库目录发现和全局 core.autocrlf 转换未得到正确字节，失败事实保留；明确隔离 Git 目录发现及 core.autocrlf=false 后通过，没有改变实际源码或全局 Git 配置。三处辅助临时检查目录已按精确工作区路径清除，调查证据保留。

完整精确增量见[补丁](patches/DEPLOYMENT-AGENT-VERIFY-FEEDBACK-27.patch)，SHA256 `7ca20cfdcfea77c15af00475bc433402c83f89c2222f43e0b171e2a87ae99eee`；after manifest SHA256 `5ec2a125d06047aaf3b6762f5d72bccbe48ef9879517dce55a3bbd18bb9382a4`。报告、原 XML/log、双 raw、冻结与保护、格式、校验限制、CIM 和父核记录在 `.ai-workspace/agent-live-evidence/agent-autonomy-20261012/agent-publication-verify-feedback-27/`。本地提交仅包含本次增量：与 HEAD 前像相同的 owner 可独立暂存，其余混合文件用精确补丁交付，不整体提交原迁移；未证明独立干净 checkout 构建，默认不推送。

纯主 Agent 的实际部署通过仍以[23](DEPLOYMENT-AGENT-MAIN-ONLY-RESULT-23.md)为证据。本地 7 PASS 不是新双 Agent 真机成功。24 UNKNOWN、原恢复、已发布应用、正式记录、当前配置、持久数据和所有未结清资源保留，DB 版本仍为 1。下一轮仍仅 task-board，由真实部署与审批模型自行闭环；真实业务、浏览器、成功恢复历史清理、非默认目录与重启、其余七项目和最终冻结八项目尚待验收。
