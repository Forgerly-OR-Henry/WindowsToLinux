# 129：首次选择性备份清单失败

用户重装前只要求导回必要数据，不启动部署或复制整个约 23 GiB 状态根。129 通过唯一产品入口 `inventorySelectiveBackupThroughProduct` 执行 BACKUP_ONLY 元数据盘点，没有读取文件正文、下载、部署、清理或旧任务恢复。备份未完成，不能暂停作为可重装信号。

实际 run `backup-inventory-129-01`，2026-10-15 00:48:29.8842880—00:50:24.6386071 +08。唯一方法 1 FAIL、0 ERROR、0 SKIP，suite 109.4 秒；Maven 1、source freeze 0。Task `a260ad29-a043-435a-a5e5-2c4db9d5ad30` 进入 UNKNOWN、outcome 为空，Runtime 关闭报告该任务仍持有 Mod 资源；原任务及调查库保留，不追补结清。

## 已证阻塞与判断边界

原第三次 LANGUAGE 响应明确包含 7 个 native `submit_result`。`DeploymentAiJson.submit` 要求恰好 1 个，数量不符立即抛出 `A complete native result submission is required`。清单驱动错误地将自然的多项只读工具动作送到单结果提交适配器；后续只修 BACKUP_ONLY 主模型调用，不放宽共享适配器或独立 APP 单一裁决。

原日志证明后续 UNKNOWN 和 Mod 清理阻塞；没有读取原 task problem payload，不编造其事件码或存储正文。TaskRequest 第四个参数实际是 cancellable，不是“要求业务提交”。TaskLedger 对任何 UNKNOWN 显示 `Business result requires explicit reconciliation`，该通用提示不能证明本轮发生部署或业务写入。

`inventory.json` 未生成，已选数量、字节和完整覆盖均 UNKNOWN，不能记为 0 或从旧日志重造清单。未来主模型需支持完整 native 只读批次，逐项保留真实观察和拒因，并及时保存有界中间元数据，避免失败丢掉已取得的信息；不添加任意路径、脚本或部署动作。

## 原证、用量与收尾

运行时 HEAD `ec26fe2e9c6cad1abbb7e95a566941b497d0b4f2`，3299 项源码前后完全一致；JAR SHA `2b2bfeccc224ec0530d6783932ab41f03d90f4e3efdbf97f717a1b8eddfb7068`。新 backup task 捕获父显式冻结的完整布局，真实 SSH 指纹与已授权值一致，不冒充旧 102/74 布局或本轮业务通过。

已知 LANGUAGE 3 次物理尝试全部有 usage：input 11342、output 5864、合计 17206；cache hit 768、miss 10574，命中率 6.7712925%。reasoning 4810 是 output 子集，不重复计入。未观测到 APPROVAL generation；4 次必要配置能力探测的 usage、transport 和完整费用 UNKNOWN，不算 0、不为补证重新付费。该失败调试与最终八项目指标分开。

exec 14424 已退出 1；正常非管理员 CIM 正控制核对本轮精确 PID/创建时刻全部退出，未终止其它进程。自有 GUID 加密配置副本与精确 UDS 临时目录已清，原受保护配置 pair 未变，调查数据库删除 0。逻辑 UNKNOWN/资源证据保留，不能把物理进程退出解释成原任务已结清。

原 XML/TXT、日志、source before/after、runner/preflight、配置复制/清理、CIM 与逐轮 provider 记录在 `.ai-workspace/agent-live-evidence/agent-autonomy-20261012/selective-remote-inventory-129/backup-inventory-129-01/`。父已核原报告 SHA 和失败状态，后续仅定点修复此入口及其直接受影响场景，不重跑旧部署或整类测试。
