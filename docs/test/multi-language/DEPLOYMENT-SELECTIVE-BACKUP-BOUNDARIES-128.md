# 128：重装前选择性备份入口与本地边界验证

用户决定先保全再重装：不启动新部署，在根 `test/data` 保存必要远端数据，不导回整个约 23 GiB 状态目录。只有全部必要备份导回并验证后才暂停；暂停将作为用户自行重装的完成信号。当前尚未执行远端盘点或下载，不能提示可重装。

## 实现范围

仅修改 `DeploymentStaticLiveAcceptanceTest.java`，复用真实 TaskService、FileQueryService、ConfigCapture、模型和 SFTP API。新增独立 BACKUP_ONLY 任务，不进入部署 Goal，不调用 plan、prepare、publish、delete、restore 或旧任务恢复。生产 API、Skill、配置与数据库版本未变，未写正式 App 表。

首先准入 `inventorySelectiveBackupThroughProduct`：只做 SDK stat/list，由主模型自主选择已观测 entryId。模型能看到真实 listed/selected 状态与有界反馈；缺目录、拒绝、链接、特殊项或上限均保留 UNKNOWN，不跟随链接，不把失败当空目录。布局来自父显式选择并封定的完整 canonical v1，捕获到真实新 backup task；不能冒称旧 102/74 布局，也不补用当前 YAML 或 legacy fallback。

未来 `downloadSelectiveBackupThroughProduct` 要求父封定清单 SHA、独立 APP 完整审核转移事实、宿主新一次性 grant 和实际 SDK 下载。普通查询及普通 grant 不冒称 APP。单文件 ≤64 MiB、最多 256 件、导出 ≤256 MiB；固定新 `test/data/<run>/remote/` 目标，不覆盖已有文件，拒绝路径逃逸、reparse 和未批准/UNKNOWN dispatch。文件正文不进入模型或日志；用真实读取字节计算 SHA，核下载回执、本机字节及前后源观测。实际秘密配置保护、SQLite 一致性、必要范围覆盖和下载均未验证，此入口尚不准入。

## 实际验证

唯一新增本地方法 `DeploymentStaticLiveAcceptanceTest#backupOnlyBoundariesRequireIndependentApprovalAndVerifiedBytes` 最终 test-03：1 PASS、0 FAIL、0 ERROR、0 SKIP，0.275 秒。覆盖固定目录及真实临时 NTFS junction、no-follow、预算、真实状态反馈、载荷不进元数据/意图、完整 binding、DENY/UNKNOWN 不 dispatch、grant 不重用、无覆盖及同长度错误内容 SHA。

test-01 为新增辅助 hash 方法的受检异常编译错误，0 实际测试；test-02 为受控 UNKNOWN 夹具缺少必需 Problem，1 ERROR。原失败日志/报告保留，未放宽生产构造器。只重跑该方法和受影响单文件格式；最终 format-05 write/check 与显式 `-DskipTests package` 均通过，没有整类、模块全套或全项目测试，没有重跑 117/119/121/125 已通过方法。

源码清单 3299 项，只有该 owner 改变；最终字节与 test-03 实测及 package-01 前后完全一致。deployment.jar SHA `2b2bfeccc224ec0530d6783932ab41f03d90f4e3efdbf97f717a1b8eddfb7068`，1048 个 class 与当前编译集合/字节、113 个资源与源码一致。正常非管理员 CIM 正控制确认 83 组已知 PID/创建时刻全部退出，自有临时目录已清。无 Runtime、DPAPI、DB、provider、SSH 或真实导出。

原证在 `.ai-workspace/agent-live-evidence/agent-autonomy-20261012/selective-product-backup-128/`。本地提交只隔离本文与 [精确源码增量补丁](patches/SELECTIVE-BACKUP-BOUNDARIES-128.patch)，保留原迁移和其它任务改动，不推送。父核原日志/报告 SHA、最终实测源码以及补丁正反向原字节；不将本工作树构建表述为干净 HEAD 构建。

## 后续条件

129 只读清单尚待实际执行；盘点成功不等于备份完成。必须覆盖全部正式 App 持久数据、必要受保护配置、关键原回执/身份及本地不可可靠重建的小型修改源。依赖和生成产物的排除要有实际版本、lock、输入或原 source identity 依据；未知可能业务或自定义材料不能漏掉。

SQLite 有 WAL/SHM、活动写入或源变化时不能把朴素单文件复制当一致性备份；需实际一致性证明及本机恢复/完整性检查。所有必要对象导回、保护和校验完成并收尾前，`backupComplete=false`，`test/data` 保持 PREPARING，Goal 不暂停。新系统及新连接不能自动结清、改写或重放旧 UNKNOWN。
