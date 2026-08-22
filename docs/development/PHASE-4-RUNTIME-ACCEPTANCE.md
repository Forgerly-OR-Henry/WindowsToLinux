# WindowsToLinux 四期真实环境验收准备

## 文档信息

- 文档版本：`1.9.0-product-entry-acceptance`
- 文档状态：**部分入口可执行；四期端到端仍为 `RUNTIME-PENDING`**
- 更新日期：2026-08-23
- 结构权威：[File.md](../File.md)
- 四期需求：[PHASE-4.md](PHASE-4.md)

本文只记录当前源码能够实际执行的产品入口、显式配置、命令和证据格式。单元测试、直接 SSH、手工 systemd、直接调用 helper 或伪造平台端口均不能替代产品入口证据。

## 1. 当前准备结论

| 验收路径 | 环境到位后是否可直接执行 | 当前边界 |
| --- | --- | --- |
| JDK 21 全仓静态门禁 | 是 | `mvn -q -B -ntp -o verify` 已通过 430 项测试，0 失败、0 错误，27 项真实环境条件测试默认跳过；共读取 133 份 Surefire 报告 |
| 桌面受管应用已保存备份输入检查 | 是 | 输入应用标识后只读桌面 SQLite v11，逐项显示运行时、文件/数据库资源范围、配置、秘密和整应用健康探针缺失；完整结果只允许继续远端检查 |
| 桌面本地归档完整校验、秘密密码认证、隔离候选准备与精确删除 | 是 | schema v4 按精确“标识 + 修订”认证，schema v3 保留旧标识认证；后台返回前立即清零解码秘密。两种格式都只管理一个从未激活的本地候选，不连接服务器，也不在应用重启后扫描未知旧目录 |
| helper v5 安装、精确协议、发行版身份及安全状态复核 | 是 | 复用现有 `DesktopApplicationFacade → SshdLinuxGateway` 产品入口；新增受管备份动词未执行前不证明远端取材成功 |
| 远端完整备份创建 | 是（桌面交互） | 已保存 PostgreSQL/MySQL/MariaDB 应用可从备份页执行默认短停写、PAX/OCI/数据库取材、原运行状态恢复、两级健康和最终归档复验；必须记录真实证据。SQLite 因缺少已审阅应用相对物理路径会在连接前显式拒绝 |
| PostgreSQL、MySQL/MariaDB 候选恢复 | 是（桌面交互） | 选择 schema v4 完整归档、目标服务器并认证备份密码后，从桌面产品入口执行零写入预检、秘密/配置/制品暂存、数据库候选、两级健康、正式提交和失败恢复；真实证据尚未采集。SQLite 协议存在，但当前产品创建因物理路径审阅不足而先行拒绝，不能伪造归档验收 |
| 远端候选启动、两级健康与提交 | 是（桌面交互） | 容器以及静态站点、PHP、Ruby 的显式端口采用回环并行候选；其他运行时使整应用短停机。候选健康仅为预检，正式端口提交后必须再次完成组件和整应用健康；schema v3 或缺少必要 `secrets.enc` 的 v4 不得激活 |
| 双服务器离线迁移 | 是（桌面交互） | 从受管源应用创建初始备份，得到明确停写批准后停止源端并创建最终停写归档，再通过同一恢复链激活目标；成功只等待人工外部流量切换，源端保持停止且保留，真实双服务器证据尚未采集 |
| 文件型备份/恢复/迁移自动验收 | 是（显式 opt-in） | `ManagedBackupRestoreMigrationAcceptanceTest` 从 `DesktopApplicationFacade` 生产组合根部署静态站点，创建完整归档并恢复到第二服务器，再独立执行停写迁移；默认不连接服务器，启用但缺少任一环境输入时直接失败 |
| 桌面更新与卸载 | 否 | 两阶段核心、HMAC-SHA256 认证交接载荷及固定 `WindowsToLinux/*` 删除原语已存在；生产 Ed25519 公钥、独立 jpackage 更新/卸载执行器身份、认证密钥交付、交接文件 ACL/一次性消费及 Credential Manager 调用接线尚缺 |

因此，当前可以在环境到位后分别执行 helper v5 基线、桌面完整备份、受管恢复和双服务器离线迁移取证，但这些入口尚未产生新协议的真实成功证据。四期全链路仍缺真实备份/恢复/迁移矩阵以及 Windows 独立更新/卸载执行器；不能以单元测试、直接 helper 或手工 SSH 替代。

## 2. 通用配置

秘密只通过当前进程环境变量提供，不写入命令行、文档、Git、Surefire 报告或 `.ai-workspace/`。

| 名称 | 类型 | 要求 |
| --- | --- | --- |
| `JAVA_HOME` | 环境变量 | 指向 JDK 21；当前开发机验证路径为 `C:\Program Files\Java\latest\jdk-21` |
| `WINDOWSTOLINUX_TEST_SSH_PASSWORD` | 环境变量 | 必填，只用于测试目标 SSH 登录 |
| `WINDOWSTOLINUX_TEST_MASTER_PASSWORD` | 环境变量 | 建议显式提供，用于验收临时桌面数据库的主密码 |
| `WINDOWSTOLINUX_TEST_TARGET_SSH_PASSWORD` | 环境变量 | 自动化恢复/迁移验收必填，只用于第二台目标服务器 SSH 登录 |
| `WINDOWSTOLINUX_TEST_BACKUP_PASSWORD` | 环境变量 | 自动化恢复/迁移验收必填，至少 12 个字符，只用于本次归档加密 |
| `managed.ssh.host` | JVM 系统属性 | 必填，目标服务器地址 |
| `managed.ssh.user` | JVM 系统属性 | 必填，目标登录用户 |
| `managed.ssh.port` | JVM 系统属性 | 可选，源服务器 SSH 端口，默认 `22` |
| `managed.root-build` | JVM 系统属性 | 仅 root 登录时必须显式设为 `true` |
| `managed.target.ssh.host` | JVM 系统属性 | 自动化恢复/迁移验收必填，第二台目标服务器地址 |
| `managed.target.ssh.user` | JVM 系统属性 | 可选，第二台目标登录用户，默认 `root` |
| `managed.target.ssh.port` | JVM 系统属性 | 可选，第二台目标 SSH 端口，默认 `22` |
| `managed.target.root-build` | JVM 系统属性 | 第二台目标使用 root 登录时必须显式设为 `true` |

运行前还必须记录：当前 Git Commit、`docs/File.md` 版本、目标发行版/版本/架构、是否允许环境准备、目标是否为可清理的专用测试环境，以及测试完成后的保留或清理决定。

## 3. 当前可直接执行的 helper v5 产品入口

### 3.1 Ubuntu 24.04 x86-64 环境准备复验

该入口只经桌面门面和生产 SSHD 网关执行两次固定环境准备，并复核当前 `ManagedHelperProtocolVersion.CURRENT`、发行版事实和准备幂等性。

```powershell
$env:JAVA_HOME='C:\Program Files\Java\latest\jdk-21'
$env:WINDOWSTOLINUX_TEST_SSH_PASSWORD='<由用户在本机设置>'
$env:WINDOWSTOLINUX_TEST_MASTER_PASSWORD='<由用户在本机设置>'
mvn.cmd -B -ntp -o -pl :windowstolinux-app-main -am `
  '-Dtest=UbuntuManagedEnvironmentSetupAcceptanceTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' `
  '-Dmanaged.runtime.environment-provision=true' `
  '-Dmanaged.expect-bare=false' `
  '-Dmanaged.ssh.host=<服务器地址>' `
  '-Dmanaged.ssh.user=<登录用户>' test
```

若目标是刚安装且应无运行时工具，可把 `managed.expect-bare` 改为 `true`。root 登录还必须增加 `-Dmanaged.root-build=true`。

### 3.2 非 Ubuntu 精确发行版复验

`ManagedDistributionProductEntryAcceptanceTest` 会先复核明确选择的发行版矩阵，再通过产品入口执行两次准备；成功矩阵还会执行共享两组件发布、故障候选回滚和生命周期事务。

除通用配置外，必须显式提供：

- `managed.runtime.distribution-acceptance=true`
- `managed.distro.expected`
- `managed.distro.expected-version`
- `managed.distro.expected-package-architecture`
- `managed.distro.expected-cpu`
- `managed.distro.preparation-expectation`

各选择值必须与 [PHASE-3.md](PHASE-3.md) 的精确发行版矩阵一致。普通 Maven 门禁不设置可选开关，因此不会连接服务器。

### 3.3 文件型备份、恢复和迁移自动验收入口

该入口不是底层 helper 测试。它创建临时桌面 SQLite，通过 `DesktopApplicationFacade → SshdLinuxGateway → managed helper v5` 生产组合根依次完成源码分析、部署、完整备份、目标恢复和离线迁移。两个测试使用随机应用标识及高位业务端口；一个验证源应用保留时的目标恢复，另一个验证源端停止、最终归档保留、目标健康和“等待人工切流”。

源端与目标端必须是两台授权写入且可保留测试应用的专用服务器，端口应能从开发机访问，并已分别通过 3.1 或 3.2 的当前 helper v5 环境准备复验。测试不会自动删除源端、目标端或替用户切换外部流量；这正是产品安全边界。当前夹具显式审阅“无数据库”，因此只证明普通文件/发布制品链，不能替代 PostgreSQL 或 MySQL/MariaDB 的数据库矩阵。

```powershell
$env:JAVA_HOME='C:\Program Files\Java\latest\jdk-21'
$env:WINDOWSTOLINUX_TEST_SSH_PASSWORD='<源服务器密码>'
$env:WINDOWSTOLINUX_TEST_TARGET_SSH_PASSWORD='<目标服务器密码>'
$env:WINDOWSTOLINUX_TEST_MASTER_PASSWORD='<临时桌面数据库主密码>'
$env:WINDOWSTOLINUX_TEST_BACKUP_PASSWORD='<至少 12 个字符的独立备份密码>'
mvn.cmd -B -ntp -o -pl :windowstolinux-app-main -am `
  '-Dtest=ManagedBackupRestoreMigrationAcceptanceTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' `
  '-Dmanaged.backup-restore-migration-acceptance=true' `
  '-Dmanaged.ssh.host=<源服务器地址>' `
  '-Dmanaged.ssh.port=22' `
  '-Dmanaged.ssh.user=root' `
  '-Dmanaged.root-build=true' `
  '-Dmanaged.target.ssh.host=<目标服务器地址>' `
  '-Dmanaged.target.ssh.port=22' `
  '-Dmanaged.target.ssh.user=root' `
  '-Dmanaged.target.root-build=true' test
```

普通离线门禁不设置 `managed.backup-restore-migration-acceptance`，两个测试必须显示为跳过；一旦启用，任何缺失秘密、服务器资料、登录失败、归档失败、恢复失败、源端状态错误、目标 HTTP 失败或错误终态都必须使 Maven 失败。正式证据取自完整命令、Surefire XML、结构化 operationId 和目标环境观测，不能只记录进程退出码。

### 3.4 完整备份与受管恢复桌面入口

通过桌面“备份”页面执行，禁止用测试代码、直接 SSH 或 helper 命令代替：

1. 输入已经成功部署并保存在当前 `data/windowstolinux.db` 的应用标识，先执行只读输入检查。
2. 创建完整备份时选择最终归档路径，输入独立备份密码及凭据存储主密码；记录原运行状态恢复、组件健康、整应用健康及最终归档复验结果。
3. 恢复时选择 schema v4 完整归档和已保存的目标服务器，输入独立备份密码及凭据存储主密码；先查看目标零写入预检，再由同一后台任务完成候选、数据库、健康、正式提交和失败恢复。
4. 记录候选模式（回环并行或整应用短停机）、正式提交后的二次健康、本地秘密登记和整应用图接管状态。远端成功而本地接管失败时不得只记录为普通失败。

每次验收使用全新应用标识或经审阅的专用目标。当前产品不能从 SQLite 绑定创建完整远端备份，不能通过手工构造归档绕过该拒绝。

### 3.5 双服务器离线迁移桌面入口

通过同一桌面“备份”页面选择受管源应用、已保存的目标服务器、初始及最终归档位置，并明确确认停写窗口：

1. 未确认停写时必须证明没有建立 SSH 会话或执行远端操作。
2. 确认后先创建源端运行态初始备份，再停止并验证源端无活跃写入，随后创建最终停写完整归档。
3. 目标恢复复用 3.4 的正式链路；失败必须分别记录目标候选清理和源端恢复证据。
4. 成功终态必须是 `READY_FOR_MANUAL_TRAFFIC_SWITCH`。测试人员手工处理外部流量；软件不得自动切流、启动回源或删除源端。
5. 源端保留期间目标本地接管可为 `DEFERRED_SOURCE_RETAINED`，这是防止双重权威图的明确限制，不是可忽略的警告。

## 4. 逐次证据模板

每次真实执行使用以下字段记录，不保存任何秘密：

```text
执行时间：
Git Commit：
File.md 版本：
测试类与完整非秘密参数：
目标发行版/版本/架构/CPU：
登录用户是否 root：
产品调用链：DesktopApplicationFacade -> SshdLinuxGateway -> managed helper v5
执行前安全模块与防火墙状态：
观测到的 helper 协议版本：
执行阶段与测试统计：
备份/候选模式/数据库/发布/健康/回滚/生命周期结果：
本地秘密登记与整应用图接管状态：
迁移源端停止/恢复、最终归档与人工切流状态：
执行后安全模块与防火墙状态：
目标机保留内容与清理决定：
RUNTIME-PENDING 未覆盖项：
```

失败记录必须保留测试类、operationId、结构化错误码、安全诊断、恢复处置和精确未完成阶段；不得抄录密码、秘密值、私钥、未脱敏远端输出或第三方响应正文。

## 5. 四期端到端解锁条件

备份、恢复和迁移产品入口已经接通。以下条件全部满足后，才能把本文状态改为“四期端到端真实验收完成”：

1. 通过桌面产品入口在声明的发行版/架构上完成 PostgreSQL 与 MySQL/MariaDB 完整备份，并保存原状态恢复、两级健康和最终归档复验证据。
2. 通过桌面产品入口分别完成回环并行候选和整应用短停机恢复，覆盖数据库候选、正式提交二次健康、失败回滚及不可验证时的人工恢复终态。
3. 通过双服务器产品入口完成明确停写、最终归档、目标恢复、失败时源端恢复及成功后人工外部流量切换；源端不得被自动删除。
4. 发布方提供真实 Ed25519 公钥与签名元数据流程，仓库具备安装目录外运行的独立 jpackage 更新/卸载执行器、认证密钥安全交付和具有受限 ACL/一次性消费语义的交接文件，并由该执行器调用现有 Credential Manager 固定命名空间删除原语。
5. 每个真实入口在未配置环境时保持显式跳过，配置完整时从桌面/service 组合根进入，不添加手工 SSH 或 helper 旁路。
