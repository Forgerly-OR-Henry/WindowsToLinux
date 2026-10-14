# Agent 静态拒绝的可信规则反馈（119）

118 的一次宿主命令被静态拒绝并确认 NOT_SENT，模型随后因容量不足停止。完整命令正文未留存，不能确定命中哪条规则或断言全部删除对象安全。源码核对确认：静态门禁不是系统包安装白名单，实际检查灾难性操作、物理设备破坏及受保护目录的递归删除等。119 修复拒绝反馈缺少具体规则，以及 host_command 描述过窄的问题；没有放宽静态门禁。

共享 `DeploymentCommandStaticGate` 增加不可由模型构造的 typed Rejection 和六个固定 Rule；所有原接受/拒绝条件、顺序、固定/自由命令限制及 `getMessage()` 原事件码保持。此共享部分只标识既有解析器实际命中的规则，不改变各模式策略；定点覆盖固定与自由入口的原拒绝行为。Agent 独立包的 RemoteTools 只在真实 typed 拒绝时返回有界规则、NOT_SENT、原任务和 body/envelope SHA，不返回脚本、操作数或秘密。其它异常继续原行为，独立审批、单次 grant、命令回执和 UNKNOWN 处理均未改变。

host_command 的描述与已有 host-command 契约对齐，说明宿主前置条件、诊断及有证据的部署维护仍须静态准入和完整独立审批；UNKNOWN、正式应用和持久数据不因此得到删除授权。沿用 skill-creator 窄更新现有 `deployment-diagnosis/SKILL.md`：收到静态拒绝规则时，模型可据实际规则自行修正完整请求，不能靠包装或编码规避门禁，修正后仍需新审批；静态拒绝不等于审批拒绝，也不结清旧 UNKNOWN。该 Skill 继续按需加载，没有固定部署步骤或模型特化。

只运行新增的 `DeploymentAgentPlanReconnectTest#hostCommandStaticRuleFeedbackDoesNotBypassReview` 一次，1 PASS/0 FAIL/0 ERROR/0 SKIP，12.906 秒，Maven 39.043 秒。测试使用真实 RemoteTools、产品任务和独立审批路径，本地 Runtime/开发 Mod 配合受控 AI/SSH：受保护根 glob、相对根递归清理、物理设备、破坏性重定向和非法形状返回准确规则，APP/SSH 均零且反馈无正文；修正的只读命令进入正式独立 APP，受控 DENY 后 SSH 仍零，没有未确认命令。此方法验证反馈和审批边界，不冒称复现 118 未知完整脚本。未重跑 117 方法、整类、模块或小规模全量。

最终 format-02 四 Java owner write/check/freeze 均 exit 0；package-01（20:37:05—20:37:36 +08）明确 skipTests、exit 0。最终源码 3299 条仅五个授权 owner 变化，Agent-01 测试字节与最终版本一致，原报告 SHA 已父核。最终 deployment.jar SHA `67123d75998a9988af639ebc08f6b869aaa76e772c7f420e4076ff91326c48cd`；1046→1048 class 仅新增 Rule/Rejection，变化限于生产 owner 和该 Skill，其它 116 资源不变，JAR class 与 fresh compiled 字节相同。

验证环境失败原证保留：format-01 用 PowerShell 5 时缺 Start-ThreadJob，尚未格式化或测试；改用已有 PowerShell 7 后通过。前两次官方 Skill validator 缺 PyYAML，另一次 pip 被沙箱临时文件权限阻止；父只在工作区临时目录安装 PyYAML 6.0.3，官方 quick_validate 第三轮 exit 0，未添加生产或全局依赖。该依赖目录、pip 临时目录及 p119 补丁验证目录已按精确路径和 reparse 边界清理；日志所列首轮沙箱临时目标均已不存在。

正常用户 CIM 正核本轮 28 组已知 PID/创建 ticks 全部退出，未终止其它进程；父的依赖与 validator 执行也已结束。本阶段没有真实服务器、provider、旧数据库读取或历史记录清理。精确增量补丁已正反应用验证 before/final 原字节，见 [AGENT-STATIC-REFUSAL-FEEDBACK-119.patch](patches/AGENT-STATIC-REFUSAL-FEEDBACK-119.patch)。仅本地提交本文和增量补丁，保留原迁移与其它任务改动，不推送，不宣称干净 HEAD 可独立构建。

证据：`.ai-workspace/agent-live-evidence/agent-autonomy-20261012/static-rule-feedback-119`，包括最终报告、`parent-final-verification.json`、`own-process-final.json`、`jar-differences.json`、`skill-validation-03.json` 及临时清理记录。task-board 完整独立审批部署仍未通过；下一 120 只启动一次新 task-board 产品部署，模型自主选择步骤与工具，Root 监控而不接手。
