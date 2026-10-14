# 125：本轮用户维护要求绑定到原 AGENT 验收入口

122 的真实独立审批轮因容量阻塞停止；原观测产品状态根约 23 GiB、可用约 743 MiB。用户随后明确授权核查并尝试有充分证明的压缩或清理，暂不方便扩容。原 live fixture 只传固定部署要求，未将该新意图传给模型。23 GiB 不是可删除空间，旧 UNKNOWN、正式 App、持久数据及未结证据仍须保留。

## 最小改动与边界

唯一源码 owner 是 `DeploymentStaticLiveAcceptanceTest.java`。AGENT 请求装配新增可选的 `managed.live.userRequirementsFile`、`managed.live.userRequirementsSha256`、`managed.live.userRequirementsRun`，只在既有 requirements 中追加固定字段 `userMaintenanceRequirements`。不覆盖 applicationId、goal、network、publicUrl 或 acceptance，也不修改 AUTOMATIC 独立审批、rememberAuthorization 或生产工具合同。未提供这三个属性时保持原 requirements。

校验先于 fixture 复制、Runtime enable/open 及模型能力探测：三个属性必须完整，inputRun、附加 run、目录 leaf 相同，input/browser checkpoint 为同一本轮目录且位于现有 evidence 根内；仅接收固定 `user-requirements.txt`。逐祖先拒绝符号链接、Windows reparse 与 canonical 不一致；文件须为 1—4096 字节的普通文件，SHA-256 完全匹配、严格 UTF-8、非空白、无 NUL，并遵守原 Request 的字段数和字符预算。只打印 run、SHA、字节数和字符数，不打印正文。该入口不接收任意 JSON 配置合并。

本次没有生产源码、Protocol、Workflow、Skill、配置、数据库版本或审批规则修改。主模型仍自主调查和提出动作，独立审批仍审阅完整具体动作、输入和证据，修正动作必须取得新 grant。补充自然语言要求不能证明旧对象归属、结清、跨库引用覆盖或授予旧 task 恢复权；压缩同样属于写操作。

## 实际定点验证

- 只运行新增纯本地方法 `DeploymentStaticLiveAcceptanceTest#userStorageRequirementsBindToOneRunAndCannotOverrideDeploymentInputs`：1 PASS、0 FAIL、0 ERROR、0 SKIP，suite 0.201 秒。精确 selector 与专属 local gate 同时满足才启用该测试；staticLive/agentLive 均 false。
- 覆盖缺省不变、合法附加、核心值逐字节保持、原 Request 审批策略保持、错 SHA/run/目录/文件名、缺 companion、空文件/NUL/错误 UTF-8/超限拒绝，以及看似核心键的 JSON 正文仅作为文本保留。
- 仅该 owner 格式 write/check 通过；`-pl src/mod/deployment -am -DskipTests package` 通过。没有整类、模块全套或全项目测试，没有重跑 117/119/121 已通过方法。
- 前后源码清单均 3299 项，只有该 owner 改变，最终源码与该方法实测字节一致。重新打包的 deployment.jar SHA 为 `4549c7641ef1320107e1d9c9a1c69b33c4717808eaff9b25db41769970a2c068`；生产源码未变，重新打包本身不等于新增生产行为。
- JAR 的 1048 个 class 与当前编译字节、113 个资源与实际源码资源一致。正常非管理员 CIM 正控制核对 29 组 PID/创建时刻，全部退出；自有临时目录已清理，原数据库目录未删除。

本地证据在 `.ai-workspace/agent-live-evidence/agent-autonomy-20261012/user-storage-requirements-125/`；保留 before-owner、清单、原日志及 XML/TXT。精确源码增量见 [AGENT-USER-STORAGE-REQUIREMENTS-125.patch](patches/AGENT-USER-STORAGE-REQUIREMENTS-125.patch)，父核原报告 SHA、实测源码及补丁正反应用的原字节，隔离原迁移和其它任务改动。

## 尚未验证

125 没有新 provider、SSH 或数据库调用，没有实际服务器清理或业务部署。126 仅准备新独立审批轮的单轮用户要求和 helpers；最终 Git/source/JAR/输入封定前不准入。下一轮须自主识别具体对象及其证明，动作后重新核容量；没有合法候选则保留并明确缺项，不降低 1 GiB 主机预留、不改写旧 UNKNOWN，也不把清理完成当 task-board 业务验收通过。
