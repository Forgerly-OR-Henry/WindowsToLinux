# S30 总结与暂停交接

用户于 2026-11-08 要求：完成第三十轮总结后暂停，本对话只指出问题；代码冗余清理、重构及修正计划由用户另开对话处理。本次不以八项通过为暂停前提；原验收目标尚未完成，不标记 complete。本次仅完成原操作的本机收尾与总结，不启动下一轮冻结、业务验收或 AI。

S30 的准确结果是：**准备阶段一次已知 FAILED；八项业务全部 UNLAUNCHED，业务 PASS 为 0。** 后续独立挂载诊断和卷释放尝试分别记账，不能算作业务 PASS，也不能把前几轮的通过项拼入 S30。

| 轮次或操作 | 原结果 | 已证实的原因或表现 | 尚未证明的部分 |
| --- | --- | --- | --- |
| S27 SurveyScoring | 构建 FAILED；整轮 7 PASS、1 FAILED | Gradle 发行包下载的两次尝试被选定源的 destination/redirect allowlist 拒绝，构建未完成。 | 没有保存精确被拒 URL，不能还原全部重定向链。 |
| S28 SurveyScoring | 构建 FAILED | Gradle 8.10.2 发行包已下载并启动；生成的 `mirrors.gradle` 第 6 行拒绝 task repository protocol。这是后续仓库适配问题。 | 尚未由完整新一轮业务通过证明后续修正有效。 |
| S28 DirectoryDiff | UNKNOWN；整轮 5 PASS、1 known FAILED、1 UNKNOWN、1 UNLAUNCHED | helper probe 的 channel 已打开、请求已写，未收到退出码或输出，session 关闭。21.012 秒小于当次 30 秒预算，不能归为预算到期。 | 底层关闭发起方及远端实际效果未知；payload 可能已发送。 |
| S29 TaskBoard | UNKNOWN；整轮 0 PASS、1 UNKNOWN、7 UNLAUNCHED | 两个组件已发布；最后一次 observe 在 `CHANNEL_OPENING` 遇到 socket EOF，19.410 秒小于 20 秒预算。最后这条 observe 的业务 payload 未发送。 | EOF 来自服务端、网络还是客户端仍未知；最后一条未发送不能外推整个任务无副作用。 |
| S30 首次 inventory | 准备 FAILED，远端 exit65 | `TARGET_CONTAINS_MOUNT`：受管根下存在挂载，清场门禁在删除前拒绝。SSH 认证、指纹及连接已成功。 | 仅该盘点不能证明挂载归属或可以安全卸载。 |
| S30 独立挂载观察 E01 | FAILED，远端 exit65 `MALFORMED_RECORD` | 解析器存在三个合法 Linux 输入兼容缺陷：nsfs 的非绝对 root、按 `splitlines` 处理 CR、缺少 source 的 `043` 转义。 | 原回执没有保存具体坏行，不能断言本次由哪一个缺陷触发。 |
| S30 受控卷释放 E04 | REFUSED，远端 exit65 `LOOP_DEVICE` | 前置设备节点准入拒绝；`effectsIssued=[]`，两个目标均 `UNTOUCHED`，没有发出 stop、umount 或 detach。 | 同一错误码涵盖多个判断，实际失败子条件和节点 GID/模式未返回。 |

S27、S28 的原矩阵及失败证据见 [STATIC 历史记录](STATIC-POST-R16-20261106.md)。S29 的最后一次 observe 与此前发布效果的区别，见[原传输评估](../../../.ai-workspace/evidence/post-r16-static-gradle-repositories-execution-inputs-20261108-01/task-board-transport-assessment.PARENT.md)。所有 UNKNOWN、FAILED、未启动项及原数据库保留，未重放或 reconcile。

E02 挂载观察和 E03 归属查询原次成功：两个 mutable 分别关联 loop2 和 loop7；候选任务标记、镜像路径及内核 backing inode/设备身份均与原任务匹配。两个构建单元不存在，两个正式服务当时仍运行。这些点快照证明了归属关系，未证明所有占用者不存在。正式服务可能保留 mount namespace 引用，但尚未证明它们就是本次残留的实际 holder。

E04 用户批准后仅执行一次。原工具 `adc133/session53104 → b9bb9f` 已退出 1；Java 原 Process 34512 已确认退出 1，没有超时或 kill，输出排空完成。远端任务 `95184606-88f7-4856-bc73-82a8f116cc5e` 返回完整 REFUSED 回执，产品客户端已记录 `REFUSED_NO_REPLAY`。客户端外层随后因 FAILED 任务结果抛出泛化 UNKNOWN 提示；该提示不覆盖已完整取得的远端前置拒绝证据。详见[实际回执](../../../.ai-workspace/evidence/static-s30-owned-volume-release-proposal-20261108-01/remote-receipt.json)及[修正后的父结果记录](../../../.ai-workspace/evidence/static-s30-owned-volume-release-proposal-20261108-01/owned-volume-release-result.CORRECTED-PARENT-REVIEW.json)。单次额度已消费，没有重试。

本次设备门禁已经发现一个确定的本地代码问题：它要求 loop 块设备 `uid=gid=0`。systemd v249 默认规则会把块设备交给 `disk` 组，因此合法的 root:disk 节点会被拒绝。这是合法 OS 反例，不是服务器实际 GID 的观测；本次未确认服务器的具体失败子条件。[默认规则](https://github.com/systemd/systemd/blob/v249/rules.d/50-udev-default.rules.in#L61)、[非零组默认 0660 的实现](https://github.com/systemd/systemd/blob/v249/src/udev/udev-event.c#L849-L851)支持这一判断。

相应合成测试使用 `FakeIO.loop`，没有运行 `RealIO.loop` 的真实节点准入逻辑。此前 Python 的 57 项及后续受影响 50 项通过，不能证明真实设备节点可准入；Java 的 50 项通过只证明回执校验。另有两个静态可见缺口：节点条件没有检查 world-write 位，ioctl 返回后没有再次核对 fd 和同一路径。它们都不应冒充本次故障原因；本次未修改这些代码或新增远端探针。

恢复路径也存在职责耦合：现有 `candidate-cleanup-task` 会先进入 stop/freeze 等处理，之后才验证完整卷关系；没有独立 volume-only 外部入口。另一个 unpublished-only 补偿路径又不适用于本次已经发布的候选。UNKNOWN 保留资源与下一轮 inventory 拒绝残留挂载分别符合原规则，但两者之间的恢复链路没有闭合，需要额外的一次性诊断和恢复编排。这是本次遇到的结构性问题，不能通过把 UNKNOWN 改为成功或取消挂载保护来解释掉。

S30 隔离代码提交为 `a410f070c2bfe06208ceade217d857ca7a4bbf7a`，本轮 8 文件，其中 3 个生产文件、5 个测试文件，新增 492 行、删除 31 行。已修正并在本地验证的内容包括：SFTP 同一真实 channel 的 peer CLOSE 确认、已核验结果尚未进入 FutureTask done 时的误中止尾窗，以及 Graph 中安全保留原因类型和诊断上下文。它们解决了可复现的本地缺陷；因 S30 业务未启动，不能据此宣称 S29 EOF 已解决。原 UNKNOWN、期限和失败门禁未放宽。

关于代码冗余，目前能确认的是改动规模和编排复杂度，尚未做系统重复代码审查。相对共同基线 `42181ccd83fce23fc2071679cf97681e04750bc5`，截至隔离提交 `a410f070…` 的 `src` 累计差异为：

| 范围 | 文件数 | 新增行 | 删除行 |
| --- | ---: | ---: | ---: |
| 测试路径 | 74 | 9,013 | 301 |
| 生产代码及运行资源 | 56 | 2,983 | 240 |
| 合计 | 130 | 11,996 | 541 |

约 75% 的新增行位于测试路径。这个统计不是去重结果，不能直接把新增内容判为冗余。辅助验收层确有重复维护成本：不同轮次重复派生目录准备、启动、冻结关联、回执校验及临时目录收尾脚本；摘要关联漏更新、JVM 临时参数拼接错误也曾使本机验证失败。目前已指出的维护问题是职责交叉、跨文件常量关联多、状态与异常多级包装，以及合成夹具绕过真实 OS 边界；尚未形成生产重复代码的系统审计结论。本次不实施清理、重构或修正计划。

MAIN 尚未集成上述完整累计源码。隔离代码及其原验证证据保留；MAIN 当前提交主要为验收记录，不能将其文档更新视为生产源码集成。E04 单次执行额度已消费；本次没有授权后续重试。

[本机收尾父审](../../../.ai-workspace/evidence/static-s30-owned-volume-release-proposal-20261108-01/owned-volume-release-local-tmp-closed.PARENT-REVIEW.json)已完成：原关闭工具 `513a01` 实际退出 0，fresh 精确 PID34512 为 ABSENT，自有 TEMP 与空父目录已删除并独立核实；没有观察、打开或删除诊断库。短排他探针在删除前释放，不提供持续锁或原子保证。所有正常库、失败库及原始证据保留；本次没有发出新的远端命令。总结完成后按用户请求登记 goal paused，原验收目标仍未完成。
