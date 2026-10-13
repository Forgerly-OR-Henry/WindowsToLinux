# task-board 新 App 业务轮 41：原状态查询 UNKNOWN，浏览器发现样例缺陷

2026-10-13 10:03:18+08，`task-board-business41-01` 自然失败退出：1 个定点测试，1 failure、0 error/skip，213.9 秒，3282 源码/Skill/样例冻结差异 0。它复用[纯部署 40](DEPLOYMENT-AGENT-MAIN-ONLY-RESULT-40.md)的原 task、App、数据库和 Web 46800，不重新部署、不调用模型或能力 probe。Runtime 关闭后的最终模型调用断言真实通过，attempts 为 0。

原部署 task 为 `4f22fd0a-e484-4041-8f83-9da6f5485761`，App 为 `wtl-agent-main-only-diagnostic-task-board-gdfu7m50c`，数据库为 `agent-main-only-diagnostic-live-task-board-gdfu5zwsg`。原成功归档及正式完成业务身份核实通过，产品 START 与 ENABLE_AUTOSTART 返回成功；随后检查自启状态的原 Inspect task `ef1a3a79-f928-4033-8514-228c04ae8de3` 进入 UNKNOWN。

该查询的 helper probe 成功，协议 16、SHA 与原 helper 匹配。之后 `deployment.handoff.managed-current-status.v1` 返回 `SSH_COMMAND_EXIT_UNKNOWN`：会话认证、通道打开已确认；elapsed 19624 ms，timeout 120000 ms，inputState=SENDING，无输出或退出回执，通道已关闭、sessionOpen=false。这些事实不能证明命令没发送，也不能证明应用停止或服务器损坏；尚无具体传输根因证据。

测试 finally 按原规则保留不确定性，断言 `Retain uncertainty before new cleanup` 失败，未执行 STOP/DISABLE。Runtime.close 同时报告原查询仍持有 Mod 资源，不伪称 Runtime 业务清理成功。本地 Maven 和验证进程均已退出，正常用户 CIM 按 7 个已捕获 PID 与 UTC 创建时间确认活动进程 0，无 terminate。远端 App、正式发布、持久数据和 UNKNOWN 原资源继续保留；当前停止/自启状态需产品恢复后重新核实。

42 的只读源码调查确认已有产品恢复接线：注册的 QUERY_TASK 回调前，宿主先严格结清原任务的本地 SSH/AI/workflow 资源；旧 boot 的 canonical `ssh.v1` lease 仅按原 task、owner 和版本结清。作者查询恢复随后只在 `resourcesReleased` 成立时返回 `FAILED / DEPLOYMENT_APPLICATION_QUERY_INTERRUPTED`，不重新执行 Inspect，不调用模型或 SSH 命令，不删正式 App、文件或持久数据库。实际 lease 是否满足门仍需真实原查询恢复验证；不能据源码就将 UNKNOWN 改为成功。

原宽恢复测试会遍历所有 UNKNOWN 查询及全部 App，且启用模型，不适合本次精确范围。后续限定 43 只恢复该原查询，核合法 ENDED/FAILED 与资源结清，再正常重启核原 40 完整 App/revision/ownership 身份，并仅停止和禁用该 App。其他 UNKNOWN、旧 Agent 恢复 33 与其他应用不进入本轮。

41 的业务 HTTP 写入、STOP/START/RESTART 持久化序列和 browser READY checkpoint 尚未执行。另行从该 App 的实际公开入口打开真实浏览器，核页面通过正式 runtime-config 加载了两个原项目和统计，并在独立“Agent41 浏览器验收项目”中成功创建任务 4。随后发表评论真实失败，页面显示 `crypto.randomUUID is not a function`。原 `frontend/src/main.ts` 在构造 POST 的 JSON 正文时直接调用该方法，异常先于 API 请求；没有评论成功记录。

这与样例的 HTTP 运行要求不兼容：[MDN 的 randomUUID 文档](https://developer.mozilla.org/en-US/docs/Web/API/Crypto/randomUUID)说明该方法限安全环境；[getRandomValues 文档](https://developer.mozilla.org/en-US/docs/Web/API/Crypto/getRandomValues)说明强随机字节方法可用于非安全环境。后端 `TaskStore` 的请求标识要求非空、最多 100 字符，并无 UUID 类型要求。后续只修样例的随机请求标识生成，保留强随机和业务幂等语义，不热改原 App 或重放旧部署。

上述真实浏览器读取和两次创建证明当前正式配置与浏览器 API 调用已可用，但不构成完整业务通过，也不抹去 41 的管理 UNKNOWN。已保留 DOM、JPEG 和 partial failure JSON，没有写 browser success checkpoint；唯一自建浏览器页已关闭，创建的业务记录保留。

原 XML SHA256 `61ceddb75dc48707add79ca50aa8c2c43c0daeaf38d96108cd24bb73ca668565`，log SHA256 `ff1bb424dc18c3f15e85d86470f59ac7a0556ec5c00fa787ee2e95e82936b0c4`，原 XML 精确时长 213.882 秒。父复核原报告及修改时间、冻结、精确进程、模型计数与浏览器 partial FAIL 通过。证据在 `.ai-workspace/agent-live-evidence/agent-autonomy-20261012/task-board-business41-01*`、`main-only-business-live-41/` 和 `agent-management-status-recovery-audit-42/`；浏览器证据在该 run 的 `browser-observed-after-failure/`。40 的实际部署通过、41 的失败和 36 的旧配置失败均分开保留。当前仍只推进 task-board、审批关闭，DB 版本 1；不得将本轮表述为完整成功或审批原因已全部定位。

后续[43 精确产品恢复](DEPLOYMENT-AGENT-QUERY-RECOVERY-HTTP-COMMENT-43.md)已真实结清本原查询为 ENDED/FAILED + QUERY_INTERRUPTED，严格原未释放 lease 为 0，再核原 App 完整身份并仅停止/禁用该 App。原失败诊断和数据保留，41 本轮失败结论不改写；样例评论修复只有本地证据，外部 HTTP 新部署仍待。
