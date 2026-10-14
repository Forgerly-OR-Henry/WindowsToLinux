# 131：真实备份盘点与只读参数拒绝

唯一产品方法 `inventorySelectiveBackupThroughProduct` 实际 1 FAIL、0 ERROR、0 SKIP，168.8 秒；运行时间 2026-10-15 01:08:07.8247284 至 01:11:01.8541492 +08。Maven 1、sourceFreeze 0。Task `64d0f2b1-e6ff-44b9-80bc-5c8885259b38` 为 UNKNOWN、outcome empty，原资源未逻辑结清；不能将通用 snapshot 提示解释为发生业务写入。

本轮通过产品重新认证授权 wire 指纹，五个根目录均真实 stat/list 成功，直接孩子数分别为 helper 3、apps 19、state 8、config 24、data 13。已保存 task 绑定的 72 项局部元数据（69 目录、3 文件），包括 102 的 App/config/data 目录。state 已见 identities、model-command-operations、storage、work 等，但尚未深入，不能据目录名称判定可删除或完整备份范围。

首个五调用批次全部 LIST_SUCCEEDED，随后两项 PAGE_SELECTED。第四个 LANGUAGE 响应的 32 项批次被整批拒绝、零 dispatch，原拒因是 `BACKUP_NATIVE_UNKNOWN_ID_OR_NONZERO_PAGE`；现有安全投影不能区分两个分支，不猜具体参数。入口将可反馈的只读请求错误直接终止，模型没有得到纠正请求的机会。未来最小修复应保留全部校验和零 dispatch，把可信的完整拒绝回执交还模型自行提出新请求；不自动改参数、放宽边界或重放本轮 UNKNOWN。

最终 `inventory.json` 未生成。partial SHA `289451b1b1ebe39672c4319fb6621fe355ac54a544c2d7e2da92c323f278956d` 保留原 72 项观测、动作回执和终止状态。selectedCount/bytes 的 0 仅描述局部进度，不证明没有必要数据；coverage UNKNOWN，backupComplete false。没有正文读取、下载、新部署或远端写。

已知 LANGUAGE 物理 4 次：input 30707、output 8902，总 39609 tokens；cacheHit 4352、cacheMiss 26355。reasoning 为 output 子集，不重复加入。APP generation observed 0，四次必要 capability probes 的 provider usage/transport 和完整账单仍 UNKNOWN；本轮为备份工具调试，不计最终八项目指标，不补费重测用量。

运行期间源码 3299 项、HEAD `f120583b1b8627049d76937175256b9ea13fa285`、JAR `790540d08d0eed34a8a59a3433e3a9f7c07c347c958d7f0367580fa98582c761` 冻结。正常非管理员 CIM 确认 10 组自有 PID/创建时刻均退出，唯一加密 GUID 配置副本及 UDS 已精确清理，原配置与全部调查数据库保留。物理收尾不改 UNKNOWN。

原证位于 `.ai-workspace/agent-live-evidence/agent-autonomy-20261012/selective-remote-inventory-131/`，父核原报告、日志、partial SHA 和收尾。必要数据仍未导回，不能暂停或提示可以重装。
