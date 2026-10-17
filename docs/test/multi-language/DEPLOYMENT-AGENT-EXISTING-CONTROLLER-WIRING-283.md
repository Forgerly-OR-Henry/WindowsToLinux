# Agent 隔离工具链接线与事后核对取消 283

282 实机在已有 controller READINESS 返回 READY 后被隔离门禁拒绝。确认 Agent RemoteTools 构造 BuildTools 时漏传已有的 `reuseExistingOnly` 开关，默认 false 使后续动作选择禁止的共享 controller.prepare 路径。最小生产修正只补传 `isolationPolicy != null`：有可信隔离 policy 使用既有 existing-only 路径，无 policy 仍为 false。禁止集合、scope 复核、独立 APP、新 exact grant 和 STATIC/ASSISTED 原生编排不变。该缺陷与实机失败位置一致；原始异常没有完整 cause，不追认唯一历史原因。

## 直接相关改动

- 真实 RemoteTools → Session prepare_tools → BuildTools → Commands 定点贯穿严格 Go resolver 和 controller READY 回执，核 existing-only operation、精确 stdin、单独 APP 及不同的新 grant，在实际 prepare 发送边界受控停止。不伪造工具链或业务成功。测试直接调用 Session，callback 内 journal RUNNING，CAPABILITY 未结清；外层 Workflow 尚未归类，不能将这个观察点称为 UNKNOWN 或业务完成。
- 实机事后隔离查询改用可取消的真实 TaskRequest。停止监视器仍只对明确 opt-in 的查询调用产品取消；停止标记记录中性 `STOP_FILE_PRESENT`，不会把自动收尾表述成人工暂停。
- 真实 H2/平台 Task 定点先持有同服务器资源，使查询排队，再通过真实 handle 取消；验证 QUEUED → CANCELLED/ENDED、查询 handler 未执行、查询无遗留资源，持有任务独立成功释放。无模型或 SSH。
- 将实际 282 Task/App/units/backend 候选和端口纳入第四保护集合。有限 metadata 边界 Java/Python 同步为 96 行、24576 B，未放宽 mandatory、排序、命名空间、路径、截断或授权检查。实际 282 的 59 行为 12467 B；追加第四集合的保守 78 行投影为 16221 B，原 16384 B 只余 163 B，因此显式扩为 24 KiB。

## 定点验证边界

仅筛选上述接线方法、真实排队取消方法及两个四保护边界方法，使用 offline Maven `-pl src/mod/deployment -am -Dtest=... -Dsurefire.failIfNoSpecifiedTests=false package`。没有全量测试、provider、SSH 或远端动作。当前四个不同方法最终均通过：

| 实际调用 | 范围 | 实际结果 |
| --- | --- | --- |
| test-01 | 四保护 metadata 的 Java/Python 边界两方法 | 2P/0F/0E/0Skip，复用后未重跑 |
| test-04 | 真实排队查询取消一方法 | 1P/0F/0E/0Skip，JUnit 4.951s，package 0 |
| test-05 | RemoteTools existing-only 接线一方法 | 1P/0F/0E/0Skip，JUnit 14.979s，package 0 |

五次实际调用共七个 JUnit 例次：4P/1F/2E/0Skip，无编译失败；失败证据保留。最后 package UTC 14:00:49.6947809Z 至 14:01:22.9033972Z，deployment.jar 4246809 B、实际落在此窗口，五个限定源在开始后均未修改。时间比较使用 UTC epoch，未将父工具两次混合 DateTime Kind 的元数据检查错误当构建失败或重跑理由。

保留所有初次失败原证据：test-01 为四保护 2P、接线 1F（在 callback 内错误预期 UNKNOWN）；test-02 真实取消之后 holder 的 `success(null)` 触 `Optional.of` NPE，1E；test-03 接线断言全部通过，但刻意未结清导致真实运行库拒绝关闭，1E。仅修测试观察边界、两处显式无 payload 返回，以及该故障注入模式精确绑定任务的关闭拒绝断言，不增加 timeout、不强制结清或忽略其他失败。test-04 真实排队取消 1P、package 退出 0。原错误不改判。

## 交付与后续

工作区存在前序包迁移等大量未提交改动，继续交付本阶段五路径相对于保存原字节的窄源码 patch，避免混入其它改动。仅在本地提交时生成限定摘要，不做逐次/全源/依赖路径 hash；不推送。CIM 核验本轮 Java 0、terminated 0；在 owner、绝对边界及无 reparse 检查后仅清理 c283a/u283t，保留原始失败与证据。辅助证据根为 `.ai-workspace/agent-live-evidence/agent-autonomy-20261012/file-transfer-isolation-controller-wiring-283/`。

原 238/280/281/282 与排队 postQuery 继续保留，不恢复原 194，不重放旧动作。下一次唯一 File Transfer 实机使用新 scope/49286/49287，并逐动作独立 APP 和全新 grant；实际远端隔离及业务结果必须重新观测。File Transfer 完成 BUSINESS v2、真实浏览器及 STOP/DISABLE 后才开始 CSV；两个项目完成后暂停并报告最终审批占比、输入加权缓存命中率及已知/未知覆盖。失败 FULL 与纯恢复单列。
