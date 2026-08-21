# WindowsToLinux 四期真实环境验收准备

## 文档信息

- 文档版本：`1.0.0-runtime-readiness`
- 文档状态：**部分入口可执行；四期端到端仍为 `RUNTIME-PENDING`**
- 更新日期：2026-08-22
- 结构权威：[File.md](../File.md)
- 四期需求：[PHASE-4.md](PHASE-4.md)

本文只记录当前源码能够实际执行的产品入口、显式配置、命令和证据格式。单元测试、直接 SSH、手工 systemd、直接调用 helper 或伪造平台端口均不能替代产品入口证据。

## 1. 当前准备结论

| 验收路径 | 环境到位后是否可直接执行 | 当前边界 |
| --- | --- | --- |
| JDK 21 全仓静态门禁 | 是 | `mvn -q -B -ntp -o verify` 已通过 335 项测试，25 项真实环境条件测试默认跳过 |
| 桌面本地归档完整校验与隔离候选准备 | 是 | 只验证所选归档并准备从未激活的本地候选，不连接服务器 |
| helper v4 安装、精确协议、发行版身份及安全状态复核 | 是 | 复用现有 `DesktopApplicationFacade → SshdLinuxGateway` 产品入口；不证明四期数据库、恢复或迁移成功 |
| SQLite、PostgreSQL、MySQL/MariaDB 真实一致性导出与候选恢复 | 否 | helper v4 与数据库适配契约已存在，但尚无桌面/service 备份创建产品入口 |
| 远端候选启动、两级健康与提交 | 否 | 候选无冲突端口策略未确定，禁止复用正式端口强行启动 |
| 双服务器离线迁移 | 否 | 平台无关状态机已存在，双端产品用例、最终同步和目标候选执行端口尚未接通 |
| 桌面更新与卸载 | 否 | 两阶段交接核心已存在，生产 Ed25519 公钥、独立 jpackage 更新/卸载执行器及 Credential Manager 删除接线尚缺 |

因此，当前不能声称“只提供服务器即可完成四期全链路验收”。能够直接执行的 helper v4 基线复验应先完成；其余项目必须等对应产品入口落地后再加入命令，不能以底层协议测试替代。

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

## 3. 当前可直接执行的 helper v4 产品入口

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
产品调用链：DesktopApplicationFacade -> SshdLinuxGateway -> managed helper v4
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
4. 发布方提供真实 Ed25519 公钥与签名元数据流程，仓库具备安装目录外运行的独立 jpackage 更新/卸载执行器及 Credential Manager 删除接线。
5. 每个新增入口在未配置环境时保持显式跳过，配置完整时从桌面/service 组合根进入，不添加手工 SSH 或 helper 旁路。
