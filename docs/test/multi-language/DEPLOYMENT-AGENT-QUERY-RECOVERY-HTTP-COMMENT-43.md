# 原管理查询恢复与 task-board HTTP 评论修复 43

2026-10-13 10:26:22+08，唯一 `task-board-management43-01` 真实产品轮 PASS：1 个定点测试，0 failure/error/skip，166.416 秒，3282 源码/Skill/样例冻结差异 0。它只结清[41 的原中断状态查询](DEPLOYMENT-AGENT-MAIN-ONLY-BUSINESS-41.md)，再核[40 的原 App](DEPLOYMENT-AGENT-MAIN-ONLY-RESULT-40.md)完整身份并停止、禁用该 App；没有重新部署或调用模型。新样例源码的外部 HTTP 评论行为仍待新部署证明。

新增验收入口 `reconcilesOriginalManagementQueryAndStopsItsApplicationThroughProduct` 在既有 LiveAcceptanceTest 中精确绑定原数据库、query `ef1a3a79-f928-4033-8514-228c04ae8de3`、owner/version、QUERY_TASK、UNKNOWN 及原 request_json 的 serverIds，只调用该 UUID 的正式 product reconcile。原查询实际结束为 `ENDED/FAILED`，归档原因 `DEPLOYMENT_APPLICATION_QUERY_INTERRUPTED`，原未释放 lease 为 0；没有将中断检查改成成功。随后按产品门释放恢复组，正常重启。

正常重启核原部署 task `4f22fd0a-e484-4041-8f83-9da6f5485761` 的成功归档，以及正式完成使用记录的 App/server/revision/ownership、两个 component/unit 和 Web 46800，再新建 Inspect 核当前完整身份。原 App `wtl-agent-main-only-diagnostic-task-board-gdfu7m50c` 当时 RUNNING/ENABLED、健康；随后仅该 App STOP、DISABLE_AUTOSTART，最终新 Inspect 为 STOPPED/DISABLED。原正式发布、持久数据库、41 浏览器创建的项目/任务及其他应用保留。41 原传输 UNKNOWN 诊断仍保留在原记录和 43 日志前部，不当作本轮新 UNKNOWN。

整个入口 AI 发送前硬拒，两次 Runtime 关闭后的最终模型 attempts 断言实际为 0，没有新增配置能力 probe。原查询恢复回调只按严格身份结清原宿主资源并结束查询，没有模型或 SSH 命令；后续实际 Inspect/STOP/DISABLE 都经产品管理入口。没有遍历其他 UNKNOWN 或 App，没有恢复原 40 Agent、旧 33 或重放 publication。任何新 UNKNOWN 都会终止本轮，实际没有新 UNKNOWN，也没有第二次真实恢复尝试。

样例仅修改 `test/multi-language/success-task-board/frontend/src/main.ts` 的评论请求标识一行：用 `crypto.getRandomValues` 生成 128 位强随机字节，编码为 32 字符十六进制。后端原约束是非空且最多 100 字符，不要求 UUID；原评论幂等规则保持。[MDN](https://developer.mozilla.org/en-US/docs/Web/API/Crypto/getRandomValues)明确该 API 可用于非安全环境，适配样例既有 HTTP 运行方式。不使用弱随机、新依赖或额外生产 helper，没有热改原 40 App。

本地限定验证：两次必要 `-pl src/mod/deployment -am -DskipTests test-compile` PASS，第二次覆盖新增的原归档/组件 unit 身份门；一个 Java owner 格式检查 PASS。前端在精确隔离副本中沿原 lockfile npm ci/build，TypeScript/Vite PASS，原 fixture 未创建 node_modules/dist。验证器运行真实 main.ts 评论表单和 api.ts 的表单/请求处理，控制 VM DOM/fetch seam，crypto 只有真实 Node WebCrypto getRandomValues、没有 randomUUID；两次提交均产生正确 POST 正文、不同 32 字符标识，并清空输入、恢复按钮，无网络。初版验证器模块 exports 作用域错误的辅助 FAIL 保留，修正验证器后 PASS，没有为了验证改生产逻辑。

上述本地验证不代替公网浏览器新版本验证；[41 真实评论失败](DEPLOYMENT-AGENT-MAIN-ONLY-BUSINESS-41.md)继续保留。下一轮由主部署 Agent 消费新源码自主部署，审批仍关闭，监督者只监控，不给固定部署步骤。

仅两个 source owner。完整 3282 before 逐字节等于 39 after-final；after/current/真实 43 freeze 完全相同，nonowner 为 0。父独立核所有原像、当前字节、原 XML/TXT 的 SHA/修改时间，以及 Git 在隔离 scratch 正反应用[精确增量](DEPLOYMENT-AGENT-QUERY-RECOVERY-HTTP-COMMENT-43.patch)逐字节通过，scratch 已清除。patch SHA256 `3acf8d67a773b584f9f9125484886719142e8855f08aa4b68d24e3267c5b248c`。样例 main.ts 的 before 等于 HEAD，可直接提交；混合原迁移的 LiveAcceptanceTest 只交付本次精确 patch，不整体暂存。

正常用户 CIM 按 7 个已捕获 PID 与 UTC 创建时间确认自有进程 0，本任务标记及准确 selector 残留 0，无 terminate。隔离前端依赖/dist 副本精确清除，原 fixture 两目录仍不存在。旧 35 guard、宽恢复入口、生产 SSH、Skill、模式和 DB 版本 1 保持。原 SSH 通道关闭的具体根因没有新增证明，不修未确证的传输机制。

原 log SHA256 `49a6a73c2e170cadaab1c6fde405dce71ae3c2a2ef80029742341809fac3a22a`，XML SHA256 `d821999a7ae383ef631fe621953e3bc8d5142330921eb7c8899a0ebbc15b44a9`。完整证据保留在 `.ai-workspace/agent-live-evidence/agent-autonomy-20261012/agent-management-query-recovery-and-http-comment-43/` 和 `task-board-management43-01*`。本轮未运行全模块或全项目测试，未使用 GPT-6-Astra；完整 task-board 业务、生产审批、真实旧 Agent 历史清理、非默认目录真机及最终八项目标准仍待。限定本地提交，不推送。
