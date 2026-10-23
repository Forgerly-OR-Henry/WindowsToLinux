# 服务器顺序字段的一次性离线维护

这是开发阶段 schema 1 的一次性工具，不是正常启动迁移，也不添加公开 appapi。仅接受旧正式 DDL 的精确 SHA-256 `b11e78af458ee53f029d5815b6990573a898e2957fe913b245025419890c96e0`、原数据库 generation、READY 历史行、CLEAN 停机状态及完整匹配的旧 APP 元数据。新正式 DDL 只能比此参照多出服务器 `display_order` 这一行；改过结构、摘要、状态或已处理的数据库都会拒绝，不会通过重建空库规避检查。

服务器第 4 列新增 `display_order INTEGER NOT NULL UNIQUE CHECK(display_order >= 0)`，位于 `display_name` 与 `host` 之间。已有服务器按原来的 ID 顺序初始化为 0..N−1。H2 2.5.250 的单条 `ALTER TABLE ... ADD ... USING CASE ... BEFORE host` 在引擎内部重建原表并还原约束和引用。没有 DROP TABLE、全库脚本回放或清空业务数据。

工具先取得原 `windowstolinux.lock`，并以 Windows 不共享读写的句柄确认两个原文件均未被其他工具打开，复制并核验 **原 `windowstolinux.mv.db` + 原 `db-access.bin` 的完整字节备份**。随后在同卷工作副本中执行重建、所有业务表逐行摘要比较、Mod 元数据比较、独立正式 DDL 的 APP 列序/约束/FK/索引校验；只有这些检查通过后才 CAS 更新唯一的 `app_schema_history` 版本 1 READY 行的 `script_sha256`。该行的版本、状态、起止时间和其他值保持原样，其余表数据不变。工作副本直接使用原 generation 和原受保护凭据材料，是原文件的复制，**不是新建或初始化另一个数据库**。

所有 H2 连接关闭后，待发布文件保留原 `.mv.db` 的 owner/Windows ACL；无法保留时拒绝发布。工具再次独占核验原配对文件及已验证副本的 hash，并持续持有原文件和副本的阻写句柄，使用 Windows `SetFileInformationByHandle(FileRenameInfoEx, REPLACE_IF_EXISTS | POSIX_SEMANTICS)` 完成同卷原子名称替换，随后复核文件身份和副本 hash。普通 JDK `ATOMIC_MOVE` 在 Windows 目标仍有打开句柄时会被拒绝；这里复用既有 JNA 调用专用于此工具的原生重命名，不通过关闭阻写句柄来制造发布窗口。卷不支持 POSIX 重命名或原生调用失败时直接拒绝，没有复制后删除或非原子移动的回退。原 `db-access.bin` 字节始终不改。当前用户 DPAPI 通过既有 `CurrentUserProtection` 解保护引导材料；无需应用主密码，不读取或输出凭据明文。维护入口不调用 `PlatformDatabase.open`、`PlatformRuntime.open` 或正常启动恢复，不更新启动标记或 UNKNOWN 任务。

维护和恢复只支持具备上述卷能力的 Windows 10 1607/Windows 11 本地卷。阻写句柄覆盖普通数据库客户端的读写打开，不防御同一 Windows 账户主动恶意替换目录路径；数据目录和私有备份目录必须受控。receipt 的原子替换使用没有长期打开句柄的普通 JDK `ATOMIC_MOVE`，其失败也不会降级覆盖。

原生名称切换采用微软对[POSIX 重命名](https://learn.microsoft.com/en-us/windows-hardware/drivers/ddi/ntifs/ns-ntifs-_file_rename_information)中“旧句柄继续访问旧对象、新打开访问替换后的对象”的规则，并检查[卷能力](https://learn.microsoft.com/en-us/windows/win32/api/fileapi/nf-fileapi-getvolumeinformationw)。这里验证的是进程异常和硬退出后的显式恢复，不宣称已经完成断电或存储硬件故障验证。

## 运行

必须先完成直接依赖模块的编译，提供 JDK 21 或以上及现有 Maven 缓存；脚本自身不执行 Maven、不下载依赖。`CompiledRoot` 可指向已编译的隔离 checkout，默认是当前 checkout。正式 DDL 始终取运行工具所在 checkout 的正式资源。

先运行专用定点夹具；它只创建并最终删除系统临时目录下的合成数据库，不连接实际数据目录，不调用 Windows DPAPI：

```powershell
& ./.mvn/tools/server-order/server-order.ps1 -Mode test `
  -JavaHome 'E:/Program/Java/JDK21' -MavenRepository 'E:/Program/Java/maven/repository'
```

可用 `-Case preservesRowsReferencesAndFormalSchema` 或逗号分隔的精确场景选择。7 个场景为：

- `preservesRowsReferencesAndFormalSchema`：原配置、共享凭据字节、认证信任、检查结果、APP/Mod 引用、Mod BLOB/CLOB、UNKNOWN 任务与其他表数据保留；新列位置、顺序、非空/唯一/非负约束、FK 有效；显式字节恢复。
- `atomicPublicationKeepsBothFileObjectsGuarded`：纯字节夹具核对名称替换前后两个文件对象都持续持有阻写句柄，外部普通写打开被拒绝，旧句柄仍读旧内容，新名称读取验证后的内容。
- `rejectsFingerprintAndStructureDrift`：旧 SHA 不同、额外列、非 CLEAN、已完成的新结构全部拒绝，原文件不变。
- `refusesActiveOwnersAndTamperedBackups`：原实例锁、绕过该锁打开的 H2 引擎、损坏配对备份拒绝。
- `rejectsOriginalChangeBeforeAtomicPublication`：工作期间外部修改原文件，发布前复核拒绝覆盖，显式恢复也不覆盖未知变动。
- `exceptionsRestoreOriginalBytes`：ALTER 后及原子发布后注入异常，原文件恢复为备份的精确字节。
- `hardInterruptRecoveryBeforeAndAfterPublication`：专用子进程在 ALTER 后及发布后 `halt`，确认原目录仅有原图像或验证完成的图像，再由显式恢复返回原字节；子进程均等待退出。

实际执行前，由用户正常退出 APP 和数据库客户端，不由工具杀进程。备份目录必须是同卷、预先不存在的私有目录，且不在源码 checkout 或 `.ai-workspace` 证据目录内。备份含原数据库及受保护材料，按原数据库的敏感程度保管，不提交 Git、不上传报告。父目录必须已存在；工具不创建新的实际数据库。

```powershell
& ./.mvn/tools/server-order/server-order.ps1 -Mode apply `
  -DataDirectory 'I:/Program/WindowsToLinux/src/app/db/data' `
  -BackupDirectory 'I:/PrivateBackups/WindowsToLinux-server-order-20261023' `
  -JavaHome 'E:/Program/Java/JDK21' -MavenRepository 'E:/Program/Java/maven/repository'
```

上述实际目录仅为明确执行时的入口，测试命令不会使用它。退出码 0 才表示已完成；备份和 `receipt.properties` 永久保留供用户检查，工具只清理自己的工作副本和编译临时目录。receipt 只含根目录、generation、阶段及前后 hash/DDL 摘要，不含密码、配置正文或凭据字节。

## 异常与硬中断的显式恢复

H2 DDL 隐式提交，因此不能声称 `rollback()` 会撤销表重建。本工具在副本承受 DDL：发布前中断时原文件未改；发布原子操作中断后原文件只可能是旧图像或 receipt 已记录 hash 的验证图像。普通异常会在实例锁内尝试恢复已知原字节；所有失败均保留配对备份和可用的 receipt，禁止自动启动或盲目重新 apply。

先保持 APP/数据库客户端关闭，再显式执行：

```powershell
& ./.mvn/tools/server-order/server-order.ps1 -Mode restore `
  -DataDirectory 'I:/Program/WindowsToLinux/src/app/db/data' `
  -BackupDirectory 'I:/PrivateBackups/WindowsToLinux-server-order-20261023' `
  -JavaHome 'E:/Program/Java/JDK21' -MavenRepository 'E:/Program/Java/maven/repository'
```

恢复会重新验证备份、receipt 的目录/DDL 绑定、原引导文件 hash 和 generation，且只接受当前数据库 hash 正好等于原图像或已验证新图像；恢复文件同样经复制/hash/原子替换，不修改引导文件。若备份不完整、receipt 缺失/损坏、原配对材料或图像出现未知变动，则拒绝覆盖，保留现状，交给人工核对。最早期的备份阶段中断尚未产生 receipt 时，原文件尚未进行任何 JDBC 打开或发布，保持原样；不要把零散文件当作有效恢复集。

成功后若已经重新打开应用或数据库客户端，H2 或业务写入会改变文件 hash；此时恢复拒绝回滚，以免覆盖后来数据。没有“强制恢复”“跳过校验”“兼容旧库启动”选项。正常启动对旧 DDL 不匹配的拒绝行为保持原样；这是 schema 1 开发阶段的一次性维护，不建立长期迁移机制。

## AI 模型表专用重建

`ai-model.ps1` 复用相同独占锁、配对备份、同卷工作副本校验及受保护原子替换流程；不接入启动迁移，不新增 Schema 版本。旧 DDL 固定为 `platform-v1-before-ai-model.sql`（服务器排序后的正式结构），原服务器工具的两端 DDL 也保持固定，不受后续 AI 基准改变影响。

AI 重建物理删除两个音频 URL 和四个用途 enabled 列，保留唯一必填 `endpoint_url` 及四个可空、唯一、非负的用途顺序。先移出旧停用成员，再将不同旧端点拆成相邻独立模型，同端点去重；优先保留文字端点原 ID，随后完整音频、实时音频。旧实时传输实际附带的 `intent=transcription` 固化为明确 URL，之后运行时不再追加。语言/审批/视觉保留原文字端点成员，语音按文件、实时展开；缺失对应端点的失效旧成员保留主记录。精确对应端点的原证据保留状态、原因、时间及原修订，过期证据不会变有效；新记录共享旧不透明凭据引用，更换 Key 时由模型服务创建新引用。

重建验证 AI 目标行与预期排列完全相等，其他业务表、服务器顺序、凭据、任务、Mod 元数据和授权不变；只允许复制必要的模型偏好，再条件更新 Schema 摘要。普通失败保留原库及验证过的恢复材料。不要在 APP、数据库客户端或相关测试使用实际库时执行；工具不会终止它们。

```powershell
& ./.mvn/tools/server-order/ai-model.ps1 -Mode test -Case split `
  -JavaHome 'E:/Program/Java/JDK21' -MavenRepository '<isolated-maven-repository>' -CompiledRoot '<isolated-build-root>'
& ./.mvn/tools/server-order/ai-model.ps1 -Mode test -Case failure `
  -JavaHome 'E:/Program/Java/JDK21' -MavenRepository '<isolated-maven-repository>' -CompiledRoot '<isolated-build-root>'
& ./.mvn/tools/server-order/ai-model.ps1 -Mode test -Case guards `
  -JavaHome 'E:/Program/Java/JDK21' -MavenRepository '<isolated-maven-repository>' -CompiledRoot '<isolated-build-root>'
```

`split` 覆盖拆分、同 URL 去重、用途保序、旧停用成员、失效成员和证据/凭据隔离；`failure` 注入备份后、表改动后、发布前、发布后异常并验证原图像逐字节恢复；`guards` 验证独占锁及未知 Schema 摘要拒绝。均只使用新建合成数据库。

实际 apply/restore 参数与服务器工具相同，入口改为 `ai-model.ps1`，必须另选未存在的私有备份子目录。配对数据库、`db-access.bin` 和 receipt 不进入 Git 或 AI 证据目录，不覆盖此前服务器维护的恢复材料。成功后若已发生业务写入，显式恢复仍拒绝覆盖未知的新数据。
