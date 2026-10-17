# Agent 保留 UNKNOWN 的隔离验收准入（280）

用户已明确允许保留原238 UNKNOWN及旧5项资源、两次DENY/NOT_SENT，另建隔离环境继续 File Transfer→CSV；两个完整验收后暂停。原194已结清，不恢复194/234，不重放238/279。本轮实现该新验收入口所需的 Agent 条件闸门，尚未实机验收，不能计为 BUSINESS 通过或原任务恢复。

## 行为

- 可信调用方显式传入新应用与保留对象 policy。实际接受的计划、选定配额、真实 Task/目标/源码/配置/layout、受管 unit/candidate、解析后的文件/SQLite 路径及全部声明端口派生 scope；额外路径必须属于该组件的新命名空间。
- 首个变更前，已有共享 helper 必须经独立 APP 和新 grant 实测匹配 READY；随后固定只读隔离探针也须独立 APP、新 grant及严格完整 READY 回执。缺失、未知、冲突、漂移均不准入；不能安装或替换 helper 来绕过。
- 新路径检查所有权、符号链接、既存对象及 systemd 单元/drop-in；所有 TCP/UDP 表必须可解析且所需端口空闲。候选和发布配额按实际文件系统合并需求、单次保留余量，不把旧占用从实际 free 重扣。
- 工具链只复用已核实的不可变安装记录，建立新任务绑定，保留原 PROBE、前后完整性检查、动作审批及 UNKNOWN 占用。缺少记录时在创建前拒绝冷安装，控制器缺失或依赖安装也拒绝。既有默认 controller/resource/version 不变。
- 新隔离证据通过 `retainedEvidence` 随每个实际动作保留；后续 producer 替换当前证据不会丢掉保护条件，同名冲突拒绝。共用审批注册与静态检查只为本 Agent 新 operation 增加精确 factory 分支；旧构造和 STATIC/ASSISTED 默认行为不变。
- 验收持有真实 Workflow 实例的准入事实，并核最终 Task。业务、真实浏览器及 STOP/DISABLE 后，再用独立真实只读 Task、APP 和新 grant比较旧保护元数据首基线。该复核只能 OBSERVED，不能重新 READY、发 grant或结清原238；业务和收尾失败同时保留。没有 STOPPED/DISABLED 收尾不得通过。
- File Transfer 的10路完成请求增加就绪/起跑屏障，其余既有64MiB双版本及真实组件故障业务场景保留。

## 证据边界

内存 floor 明确为当前串行 build/PROBE 的2GiB限制加256MiB余量，CPU仅核至少1个逻辑处理器；实际固定资源来源变化则拒绝旧预算。它们是点时容量准入，**不是常驻服务合计内存上限、CPU预留、持续保证或OS沙箱**。现有 DAEMON 没有对应硬限额，本轮未新增 cgroup 管理。

旧保护集最多48行，候选/发布名称有限枚举；仅比较 lstat、链接目标摘要、白名单 owner/task 小元数据摘要及4项 systemd 状态，不读取旧配置、业务数据或秘密正文，不做全树内容哈希。它证明有限保护集的观察结果，不能冒充旧数据全内容证明。单Task内存准入不能跨重启或 successor 复用，任何后续效果仍需独立 APP和全新精确一次性 grant。

## 实际本地验证

只运行10个新增相关方法，没有全项目/模块全量测试：Isolation核心4、CommandEvidence2、PrepareReview2、ToolchainInputEvidence2。最终全部通过，最后一次 `package` 实际退出0，`deployment.jar`为4241065字节。

- test-01：10例，8P/2F/0E/0Skip；失败为新Python测试peer缺resolver常量，以及旧测试替身误判新增保留证据。
- test-02：只重跑上述2方法，1P/1F；真实Python行为成功退出，剩余断言未处理Windows CRLF。
- test-03：只重跑剩余1方法，1P/0F/0E/0Skip，package成功。修正均仅测试封装，生产代码未因这些失败改变。

总13例次=10P/3F，原失败不改判。另有一次辅助脚本 selector 守门在 Maven 启动前拒绝单方法名称，修正其精确准入后启动 test-03，不算JUnit执行。Python AST/受控真实函数 peer 行为检查、Java编译通过。10:01:44Z核本轮Java为0，未终止进程；已清理自有c280a/u280t，原证据保留。

受保护凭据副本、真实Linux探针、实服APP/grant、File Transfer/CSV完整业务、浏览器及事后保护比较均尚未执行。当前仍9次历史FULL、0个完整BUSINESS通过，不能给本轮FULL审批占比或缓存率。DeepSeek endpoint/model仅安全核元数据为已授权的 `https://api.deepseek.com/chat/completions` / `deepseek-flash`，没有模型或SSH调用。

## 交付与下一步

工作树含既有迁移，本地提交只保存本轮18路径增量patch、本说明及提交时统一的限定范围摘要；不暂存原迁移或个人AGENTS，不推送。配套 `.patch` 可逆向检查当前实现，`.sha256` 仅18个受影响源码、最终JAR及patch/说明，不作全源码或运行CP盘点。

证据根：`.ai-workspace/agent-live-evidence/agent-autonomy-20261012/file-transfer-isolated-acceptance-280/`。`local-validation.json`、三轮原XML/日志、`local-cleanup.json`及A/B来源和测试修正说明保留。

下一步先受保护复制现有产品凭据，再冻结源码并通过产品唯一启动File Transfer FULL；实际隔离拒绝不能降低条件或冒充READY。每个远端动作仍独立APP和新grant，密码/API密钥不进模型正文。File Transfer完整通过及收尾后才进入CSV；两个完成后暂停，按FULL窗口报告APP IO/(DEP+APP IO)、输入加权hit/input、已知/未知覆盖，失败FULL与纯恢复分别统计。
