# task-board 纯部署后的真实业务验证 36

2026-10-13 08:59:16+08，`task-board-business36-01` 实际失败：1 个定点测试，0 failure、1 error、0 skip，342.3 秒。它复用[纯部署 34](DEPLOYMENT-AGENT-MAIN-ONLY-RESULT-34.md)的正式 App，没有重新部署，也没有恢复审批。34 的发布、健康检查和归档通过结论保留；36 证明该 App 的完整业务仍未通过，不能据此认定审批是唯一问题。

本轮绑定原 task `11269562-2a68-49b8-97c5-d2c92ad0e786`、App `wtl-agent-main-only-diagnostic-task-board-epa06i8dg`、数据库 `agent-main-only-diagnostic-live-task-board-epa0562t4` 和 Web `http://45.192.105.213:46700`。唯一 selector 为 `DeploymentStaticLiveAcceptanceTest#verifiesRecoveredTaskBoardThroughProduct`。通过产品管理入口核实原成功归档及正式完成任务身份，再 START、ENABLE、检查健康；没有调用部署或恢复入口，没有创建模型配置或能力探测。

首个业务检查 GET `/runtime-config.json` 的响应不能解析为 JSON，错误为 `Unexpected character '<'`，位置 1:1。现有 HTTP 辅助方法显式使用 `Proxy.NO_PROXY`，在返回正文前检查最终 HTTP 状态为 2xx。实际日志没有保存本次 Content-Type、最终重定向 URL 或完整正文，因此不能断言具体返回了目录索引还是其他 HTML。业务 POST、STOP/START/RESTART 数据持久化序列和浏览器验收尚未执行。

37 的只读源码调查与 38 的精确正式记录投影收敛了原因。现有共享资源契约中 `FILE` 表示持久目录树，`CONFIGURATION` 表示有原源码种子和摘要的只读普通文件；正式发布先复制制品，再用资源挂载替换声明的逻辑路径。34 在候选阶段确实读取过普通文件 `dist/runtime-config.json`，HTTP 200、`application/json`，内容含外部 API 地址 `http://45.192.105.213:46701`。候选文件正常不能证明正式挂载后的同一路径正常。

38 使用原 `DeploymentReleaseCodec` 校验正式 runtime/health 整体摘要，再用 `DeploymentResourceCodec` 解码正式资源，核实该 App 的唯一前端资源 `runtime-config` 实际声明如下：

| 正式字段 | 实际值 |
| --- | --- |
| logicalPath | `dist/runtime-config.json` |
| resourceType | `FILE` |
| access | `READ_WRITE` |
| seedFile / contentSha256 | 均为空 |
| locationType / locationPath | `DEFAULT` / 空 |

这会按现有产品契约创建空持久目录并覆盖该逻辑文件路径，与业务 JSON 解析失败相符。正式 release `43e3c71b-7d6b-4d31-85db-20b5167446ec` 的 SHA256 为 `c77fbb0aa078a2117ac06d0a6d6877bb5dd57bde543576990686865f510032af`，整体摘要校验通过，匹配 34 原产品 revision。不是根据模型说明或日志省略的计划正文推断资源类型。

38 没有启动 Runtime、provider 或 SSH。原 Mod schema 为 READY、版本 1；只读连接使用 `IFEXISTS;ACCESS_MODE_DATA=r;AUTO_SERVER=false;DB_CLOSE_ON_EXIT=false`，持有原数据库锁。投影前后 MDB SHA256 均为 `6e48aedf03286528b6dc2d2cfbdca735510cc2688fd9e86294a69ec4916251b2`。最初两次辅助读数失败（误认 schema 状态、UUID 误按数字读取）保留；修正读取方法后 read-03 通过，三次均未改变数据库字节。

35 的业务入口关闭 AI，沿现有代理在传输前拒绝任意模型调用。36 日志中的模型请求、生成 attempt 和能力 probe 均为 0；但 36 使用的早期 35 版本在异常后没有执行 Runtime 关闭后的最终计数断言，因此不伪称该断言已通过。随后只将断言放入外层 `finally`，并把日志中的新部署策略文字改为 `NOT_REQUESTED`；该后续版本仍需新的真实业务运行验证。36 的 3282 个源码/Skill/样例条目冻结前后差异为 0，没有运行中修改。

36 的普通产品收尾确认本轮 App STOPPED/DISABLED，正式 App、发布、制品和持久数据保留。Runtime/Maven 已退出，按 8 个已捕获 PID 与 UTC 创建时间核实自有验证进程 0，没有 terminate。未创建业务浏览器页或临时凭据副本。未恢复成功的旧 UNKNOWN、其他 App、资源和调查记录保留。

实际证据在 `.ai-workspace/agent-live-evidence/agent-autonomy-20261012/` 的 `task-board-business36-01*`、`agent-business-model-free-35/`、`agent-runtime-config-binding-37/` 和 `agent-runtime-config-projection-38/`。父复核原 XML、log、冻结及进程记录通过；原 XML SHA256 为 `a3b120232983e1950a61d2e8d1f4a4edec8e232d93cb97194f0e61e17a343ac6`，log SHA256 为 `b76de4640af1418304bde4b51ccd5f6d0a29b82b75d162ad16d81c3d9893df6a`。这是失败轮记录及只读归因，不是修复后的真实通过证明。

后续[限定修复 39](DEPLOYMENT-AGENT-MANAGED-RESOURCE-CONTRACT-39.md)只修改 Agent 独立工具参数说明、固定契约和按需发布验证 Skill，保持共享资源语义、三模式编排、数据库版本及生产独立审批不变。限定本地验证和本地提交后，以全新 task-board 任务重新识别并自主部署，审批仍关闭；监督者不接手服务器或提供固定部署步骤。完整业务、重启和浏览器通过后才能继续后续阶段。
