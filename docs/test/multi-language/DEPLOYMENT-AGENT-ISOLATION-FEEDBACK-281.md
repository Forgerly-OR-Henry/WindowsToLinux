# Agent 确认隔离拒绝回传（281）

280的固定只读probe实际成功执行，却返回REFUSED/DISK_SPACE。旧流程在PREPARE_EXECUTING之后才检查准入，通用异常将其转为UNKNOWN，主模型无法根据额外64MiB预算重新规划。本轮修复该Agent反馈，不改写原238或280的UNKNOWN，不减少实际预算或审批。

实际RemoteTools在当前请求通过完整transport/receipt严格解析、且status确为REFUSED后，才能构造私有typed拒绝。完整安全回执与选定workspace/额外disk需求分段留原journal；主模型收到实际各文件系统available/required/reserve及额外64MiB需求，可同Task重新规划，随后仍须新的独立APP和grant。未知、传输异常、截断、身份不符或不完整回执继续保持UNKNOWN。

Session在PREPARE/COMMAND/CAPABILITY/PATCH/SEAL/DELIVERY的变更标记前调用该准入；真实变更入口仍复核原闸门。没有为未发生的变更新增RESULT编码：旧交付证明会消费PATCH_RESULT版本链，不能用REJECTED记录冒充一次patch。旧构造和无policy路径保留；STATIC/ASSISTED原生逻辑、数据库表、公开产品API与UI均未改变。

下一轮可信fixture同时保护238与280。280的backend/web unit由实际PLAN40及WORKSPACE_QUOTA41/42只读导出，未猜候选或当作已部署；采用新的49282/49283端口，仍须实际观察。两项目完整通过、真实浏览器及STOP/DISABLE后暂停的目标不变。

仅运行两个新增定点方法，实际2P/0F/0E/0Skip、JUnit12.775秒，Maven package退出0，JAR4246695字节：

- `confirmedIsolationRefusalReturnsBudgetsAndAllowsFreshPlanWithoutEffects`：同真实本地Task拒绝后减少quota，两个独立完整审批与新的grant/action；无candidate/install/upload/业务effect标记，完整拒绝回执重建及public journal消费者检查。
- `unconfirmedIsolationProbeNeverBecomesKnownBusinessRefusal`：UNKNOWN不成为typed拒绝，不转成已确认业务反馈。

这是受控模型/传输的本地Task/H2验证，未调用真实provider或SSH；复用280已通过的10个直接相关方法，未重跑核心或模块全量。测试和reader进程均已退出、匹配Java0，临时classes/空sourcepath/JNA目录及c281a/u281t已按owner/边界/no-reparse核后清理，原日志保留。

280事后reader实际javac0/read0，既有空锁、existing-only bootstrap、H2 READONLY、public事件/身份/正式row codec及锁内/关闭后属性守门均通过；未读取凭据行或打开Runtime。结论见 `DEPLOYMENT-AGENT-ISOLATION-LIVE-280.md`：仍UNKNOWN、5资源未释放、无正式App/组件/发布/execution/receipt，不能据0本地派发索引推断远端空态或结清。

本轮4路径增量（RemoteTools、Session、PrepareReviewTest、Live fixture）及限定摘要在Git提交时统一生成；既有迁移和个人AGENTS不暂存、不推送。摘要仅4源码、JAR、patch、本说明及280LIVE记录，非全源码/CP盘点。辅助证据在 `.ai-workspace/agent-live-evidence/agent-autonomy-20261012/file-transfer-isolation-feedback-281/`。

下一步冻结源码，经现有受保护凭据源启动一次新的File Transfer FULL，不能重放280调用或恢复原238。真实隔离、业务、浏览器及收尾仍未通过；CSV继续后置，最终FULL审批/缓存报告仍待两个完整验收，失败FULL与纯恢复另列。
