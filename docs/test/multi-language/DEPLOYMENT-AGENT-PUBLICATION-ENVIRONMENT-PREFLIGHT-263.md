# Agent 原238：独立只读环境预检 v2（263）

本次新增固定只读预检 `deployment.agent.publication-recovery-preflight.v2`。本地定点验证、跳过测试打包和源码/产物/类路径核对已通过；**尚未对原任务执行新预检，不构成远端环境通过或业务验收完成**。

原 File Transfer 238 仍 UNKNOWN。原结果272保持 FAILED/UNKNOWN，安装266保持 READY，不重装、不重发原回滚、不覆盖旧 ACK。194、234不恢复；File Transfer 完整完成后才处理 CSV Inspector，两项真实 BUSINESS v2、浏览器及必要收尾完成后暂停，分别报告 FULL 审批 IO 占比和缓存命中率，失败 FULL 与恢复窗口独列。

## 实现边界

旧 v1 诊断源码、启动器、解码器和历史事件保持原字节。新协议使用独立 `PUBLICATION_RECOVERY_PREFLIGHT`、formatVersion 2，绑定原 task/execution/plan/pending intent/result，以及完整且验证通过的旧 v1 ENVIRONMENT 失败 REQUEST、END、receipt 摘要。每份旧失败证据只允许一次新请求；每个实际动作仍须独立 APP 和新的单次精确主机 grant。

SDK 固定提供 `BASH_ENV=/dev/null`、`ENV=/dev/null`，旧诊断却按这两个键存在即拒绝。新固定 Bash 先精确核对两个值，拒绝 LD 两键的任何存在，之后只移除这两个 SDK 哨兵，再启动固定 `python3 -I -S`。Python 在构造 Probe、读取诊断文件、获取锁或调用 systemctl 前完整、有界地枚举环境；导出函数、超限或无法完整分类均拒绝。只输出固定类别，无变量名称、值或秘密摘要。

回执区分 Shell 拒绝、Python 已启动但环境拒绝、进入 guard 检查三种阶段。保留原27项只读检查；FULL_PARAMETER_VALIDATION、UNIT_RENDER_MATCH、MANAGED_DATA_LINKS 始终 UNKNOWN/NOT_IMPLEMENTED。未执行的检查不伪称漂移；stable=false 仍保留已确认的 ENVIRONMENT/ROOT_IDENTITY 事实。

这不能消除初始 SDK/Bash 启动前未知 LD 影响，也不能还原旧回滚的确切内部失败点。只读回执不授权补偿、清理、ACK 替换或结清。本次处理任何结果后返回 UNKNOWN；已尝试只阻止重复预检，不永久阻断后续显式协调的原目标观察路径。未新增数据库表、公开 API、UI、配置或通用 UNKNOWN 重试机制。

## 实际定点验证

三类、10个明确方法，共26个最终有效通过案例；非全套测试。命令显式指定 `-Dtest=Class#method...`、`-pl src/mod/deployment -am`，关闭真实 staticLive/agentLive。打包仅使用 `-DskipTests package`。

- 首轮85257：2026-10-17 00:09:13.3485561Z—00:13:37.6748518Z，26例14P/12F/0E/0skip，未打包，源码冻结检查0。
- 首轮原始 JUnit 和源码清单保留，first evidence SHA256 `2abcf9f17a1dd7c4a1f8c7f4c1899f93bbbe699290a476f3787d1583cc5861c5`。
- 确认并只修3条路径：新 journal 和新夹具误用要求 formatVersion 的业务文档解析器读取固定诊断回执；改为原严格验证后读取安全 JSON。新 SDK 测试的 Windows argv 传递丢失嵌套引号；改为从本轮临时文件读取完全相同的实际 SDK 命令字节，不改生产 SDK。
- 第二轮42841：00:16:15.8928781Z—00:18:18.4741908Z，仅失败组及直接共用日志的旧 v1 回归5方法、21例，全部通过；跳过测试打包0、源码冻结0。
- 最终覆盖26例；原始两轮合计47例35P/12F/0E/0skip，保留全部失败。已通过且未受影响的 Evidence 两例、Program 三例未重复执行。

新增检查覆盖历史身份、源码和旧 receipt 漂移、重复请求、grant/action 复用、旧证据不完整或非环境失败、DENY/timeout/partial/complete，以及后续原目标路径未永久阻断。旧 v1 历史和审批交付路径直接回归9例。

SDK 边界方法内部包含11个环境场景，JUnit 计为1例。测试从实际编译的 SDK class 中取得命令和 bootstrap，经真实两层 `-p` Git Bash 及相同 framing；Windows 显式桥接最终 Python 路径、fcntl/signal 与 guard 结果。其结论限于启动和环境分类边界，不等于真实 Linux flock/no-follow/systemd 或 SSH 验收。测试管道在发送完整输入时即并行有界读取输出；早拒绝的 broken pipe 只有实际 exit0 和严格完整的 Shell 拒绝回执才接受。

## 最终字节与交付

只改7条已捕获路径并新增4条路径，共8条生产、3条测试；其余原3323条源保持不变，无删除。最终3327条源码清单 SHA256 `b987aab20f74ffb64f4ec495bb1f1edf7e436bd994aa71363b9ab21340cf0130`。

- 新 Python/sourceVersion：`370092779f2d733a063b5c127581a0fbfa7fae68d7c78bfd960966f1b4fa1c1a`。这是产品对实际 stdin 的预期摘要，不是远端独立执行测量。
- deployment.jar：3996971B，SHA256 `156eb5717e91a31c2c1dc7d1dc9bc2e27e8a1f8d6ce820387359260496fcd810`；五个 host JAR 不变。
- 包元数据：`ae3a8702b9ed4fa910b9e73280c297507c27b22ac1f55011f1cafb971d342da6`，54 deployment/3 AI/4 platform/5完整 host 组。
- 实际 JUnit 类路径54项、3269文件，清单 SHA256 `b5351f43264108680b27fd799218e3f2d3a5b4e5af56e7c482f845d48ae448bd`。源码/target/JAR/首个类路径解析字节对应已核；不是 JVM CodeSource 证明。live fixture 只编译，未执行。
- [精确11路径补丁](DEPLOYMENT-AGENT-PUBLICATION-ENVIRONMENT-PREFLIGHT-263.patch)：156413B，SHA256 `68be8d9a3dc64e9a033a262c012f6b8bd1eb0e8b9ce736cf2275dd00272ee20e`。所有 hunk 对捕获的 preimage 独立重建，git apply numstat 确认11路径；Git 文本使用 LF，物理源码摘要单独保留。

旧安装程序的纯字节身份链接续实际0：原255 OPEN/fingerprint/真实通过的旧 JUnit 硬锚，接到本次263 unchanged Program、HelperBundle完整族、旧测试族和10个资源；RemoteLayout通过原255完整 CP 与263实际 CP/target/JAR逐成员对应，不伪造缺失的 owner 条目。proof SHA256 `5627508b257a31abceb7e7c1cdea3f2968878ade03311be578be999a5cf94a51`，verification SHA256 `6b97232e4ee833e87375408c6d50d139b90caefd24f21bf5fb0e2d331ed2bd15`。本轮未重跑旧 Program JUnit，已审纯工具和消费绑定完整副本保留在263/proof-tools。

证据位于 `.ai-workspace/agent-live-evidence/agent-autonomy-20261012/agent-publication-environment-preflight-263`。两轮正常收尾和独立普通非管理员 CIM 分别核12、26个已记录精确进程身份全部退出；合并包元数据进程及最终收尾后，再核41个精确记录身份全部退出。四个本轮专用 TMP/UDS 目录均删除，验证、元数据和 proof 的执行授权均已关闭，未终止预先存在或无关进程。本轮未访问原任务 DB、DPAPI、真实模型、SSH 或服务器，未产生新 FULL/BUSINESS 或可计费模型用量。
