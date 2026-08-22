# WindowsToLinux 四期真实环境验收准备

## 文档信息

- 文档版本：`1.7.0-managed-remote-backup`
- 文档状态：**部分入口可执行；四期端到端仍为 `RUNTIME-PENDING`**
- 更新日期：2026-08-22
- 结构权威：[File.md](../File.md)
- 四期需求：[PHASE-4.md](PHASE-4.md)

本文只记录当前源码能够实际执行的产品入口、显式配置、命令和证据格式。单元测试、直接 SSH、手工 systemd、直接调用 helper 或伪造平台端口均不能替代产品入口证据。

## 1. 当前准备结论

| 验收路径 | 环境到位后是否可直接执行 | 当前边界 |
| --- | --- | --- |
| JDK 21 全仓静态门禁 | 是 | `mvn -q -B -ntp -o verify` 已通过 397 项测试，25 项真实环境条件测试默认跳过；共读取 126 份 Surefire 报告 |
| 桌面受管应用已保存备份输入检查 | 是 | 输入应用标识后只读桌面 SQLite v11，逐项显示运行时、文件/数据库资源范围、配置、秘密和整应用健康探针缺失；完整结果只允许继续远端检查 |
| 桌面本地归档完整校验、秘密密码认证、隔离候选准备与精确删除 | 是 | schema v4 按精确“标识 + 修订”认证，schema v3 保留旧标识认证；后台返回前立即清零解码秘密。两种格式都只管理一个从未激活的本地候选，不连接服务器，也不在应用重启后扫描未知旧目录 |
| helper v5 安装、精确协议、发行版身份及安全状态复核 | 是 | 复用现有 `DesktopApplicationFacade → SshdLinuxGateway` 产品入口；新增受管备份动词未执行前不证明远端取材成功 |
| 远端完整备份创建 | 是（桌面交互） | 已保存 PostgreSQL/MySQL/MariaDB 应用可从备份页执行默认短停写、PAX/OCI/数据库取材、原运行状态恢复、两级健康和最终归档复验；必须记录真实证据。SQLite 因缺少已审阅应用相对物理路径会在连接前显式拒绝 |
| SQLite、PostgreSQL、MySQL/MariaDB 候选恢复 | 否 | 固定适配契约与 helper v5 数据库候选协议已存在，但真实恢复产品组合和候选端口链尚未接通；不能用完整备份创建替代恢复证据 |
| 远端候选启动、两级健康与提交 | 否 | schema v4 组件发布和秘密归属已精确绑定并由 deploy 独立复核；候选端口混合策略已获批准但正在实现，禁止复用正式端口强行启动。schema v3 或 v4 声明秘密但无 `secrets.enc` 时禁止远端暂存或激活 |
| 双服务器离线迁移 | 否 | 平台无关状态机已存在，双端产品用例、最终同步和目标候选执行端口尚未接通 |
| 桌面更新与卸载 | 否 | 两阶段核心、HMAC-SHA256 认证交接载荷及固定 `WindowsToLinux/*` 删除原语已存在；生产 Ed25519 公钥、独立 jpackage 更新/卸载执行器身份、认证密钥交付、交接文件 ACL/一次性消费及 Credential Manager 调用接线尚缺 |

因此，当前不能声称“只提供服务器即可完成四期全链路验收”。能够执行的 helper v5 基线与桌面完整备份应按产品入口分别取证；恢复、迁移和维护必须等对应产品入口落地后再加入命令，不能以底层协议测试替代。

## 2. 通用配置

秘密只通过当前进程环境变量提供，不写入命令行、文档、Git、Surefire 报告或 `.ai-workspace/`。

| 名称 | 类型 | 要求 |
| --- | --- | --- |
| `JAVA_HOME` | 环境变量 | 指向 JDK 21；当前开发机为 `E:\Program\Java\JDK21` |
| `WINDOWSTOLINUX_TEST_SSH_PASSWORD` | 环境变量 | 必填，只用于测试目标 SSH 登录 |
| `WINDOWSTOLINUX_TEST_MASTER_PASSWORD` | 环境变量 | 建议显式提供，用于验收临时桌面数据库的主密码 |
| `managed.ssh.host` | JVM 系统属性 | 必填，目标服务器地址 |
| `managed.ssh.user` | JVM 系统属性 | 必填，目标登录用户 |
| `managed.root-build` | JVM 系统属性 | 仅 root 登录时必须显式设为 `true` |

运行前还必须记录：当前 Git Commit、`docs/File.md` 版本、目标发行版/版本/架构、是否允许环境准备、目标是否为可清理的专用测试环境，以及测试完成后的保留或清理决定。

## 3. 当前可直接执行的 helper v5 产品入口

### 3.1 Ubuntu 24.04 x86-64 环境准备复验

该入口只经桌面门面和生产 SSHD 网关执行两次固定环境准备，并复核当前 `ManagedHelperProtocolVersion.CURRENT`、发行版事实和准备幂等性。

```powershell
$env:JAVA_HOME='E:\Program\Java\JDK21'
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
发布/健康/回滚/生命周期结果：
执行后安全模块与防火墙状态：
目标机保留内容与清理决定：
RUNTIME-PENDING 未覆盖项：
```

失败记录必须保留测试类、operationId、结构化错误码、安全诊断、恢复处置和精确未完成阶段；不得抄录密码、秘密值、私钥、未脱敏远端输出或第三方响应正文。

## 5. 四期端到端解锁条件

以下条件全部满足后，才能把本文状态改为“环境到位可直接完整验收”：

1. `BackupApplicationFacade` 具备从受管应用创建数据库一致性归档的产品用例，并由 UI 或显式产品验收入口调用。
2. 最新 `File.md` 明确候选服务如何使用与当前版本不冲突的端口，随后接通 deploy/Linux 的实际启动、两级健康、提交和失败恢复。
3. 离线迁移具备源端停写、最终同步、目标候选、源端恢复的双服务器产品入口，且不自动切外部流量或删除源端。
4. 发布方提供真实 Ed25519 公钥与签名元数据流程，仓库具备安装目录外运行的独立 jpackage 更新/卸载执行器、认证密钥安全交付和具有受限 ACL/一次性消费语义的交接文件，并由该执行器调用现有 Credential Manager 固定命名空间删除原语。
5. 每个新增入口在未配置环境时保持显式跳过，配置完整时从桌面/service 组合根进入，不添加手工 SSH 或 helper 旁路。
