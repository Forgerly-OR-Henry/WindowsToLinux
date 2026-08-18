# Linux 部署链生态化分包修订

## 文档信息

- 版本：`1.2.0`
- 状态：**已实施（简化命名迁移与本地结构验证完成）**
- 日期：2026-08-18
- 正式目标结构：[File.md](../File.md)
- 关联修订：[ANALYZE-PACKAGE-REVISION.md](ANALYZE-PACKAGE-REVISION.md)

> 实施更新（2026-08-15）：本文原始 1.0.x 记录的是待迁移设计。当前源码、测试和 helper 资源已按其责任边界迁移，旧 `Advanced*` 公共模型和命名已原子清除，helper 协议版本仍为 3。JDK 21 的 28 模块离线验证通过；本地验证不构成新的 Linux 或产品入口运行证据。

> 命名更新（2026-08-18）：部署支持矩阵、构建配置、发行版设置和受控 SSH 命令实现分别使用 `support`、`config`、`setup` 与 `command` 包；通用环境类型和六个具体发行版实现都使用 `Setup`。模块、helper 协议和真实环境支持范围不变。

## 1. 修订目的与边界

本修订把 `gold.debug.windowstolinux.shared.deploy`、`gold.debug.windowstolinux.shared.linux`、`gold.debug.windowstolinux.shared.linux.sshd` 整理为相互正交的部署形态、公共契约、SSHD command/session、技术生态、发行版族、运行机制和协议职责。目标是消除按引入批次聚合的实现、包级循环依赖和远程组合职责混放，同时保持类型化远程边界及现有安全语义。

1.0.x 设计记录当时只修改文档；实施更新后的当前代码遵循以下不变边界：

- 不移动或修改生产源码、测试、Shell 资源和 POM。
- 不改变 helper 协议版本、verb、参数顺序、参数语义、sudoers 白名单或远端路径。
- 不改变 SQLite schema、项目支持等级、产品入口、运行行为或既有验收结论。
- 第 3 节是最终目标结构，不是当前已落地目录；第 5 节记录当前源码到目标位置的待迁移映射。
- 主迁移范围限于三个共享模块。第 7 节仅纳入删除 `Advanced*` 所必需的 `shared/model`、`shared/analyze` 和 `app/ui` 引用，不整理这些模块的其他包环。

## 2. 当前问题

### 2.1 `shared.deploy`

1. `plan.ReviewedDeploymentPlanner` 直接构造全部具体 Adapter，而 Adapter 又依赖 `plan` 中的请求和结果，形成 `plan ↔ adapter` 包级双向依赖。
2. Spring Boot、普通 JAR、Node、Python 的 Adapter 及六种 `AdvancedServiceAdapter` 实例使用相同计划生成逻辑；继续按语言复制只会产生薄适配器。
3. 六个发行版兼容策略、策略契约、公共规则和注册逻辑全部平铺在 `support`，独立策略边界只能依靠类名识别。

### 2.2 `shared.linux`

1. `connection` 同时保存 Gateway、端点、凭据、公共异常和组合会话。
2. capability、distro、runtime、transfer 为抛出 `LinuxOperationException` 反向依赖 `connection`，而 `LinuxRemoteSession` 又从 `connection` 依赖所有能力包，形成公共契约包环。
3. 公共契约本身没有 Apache SSHD 泄漏；本次必须保持该安全边界。

### 2.3 `shared.linux-sshd`

1. `connection` 同时保存低层 `SshCommandExecutor`、Gateway 和聚合全部远程能力的 `SshdLinuxRemoteSession`，使全部实现包与 `connection` 双向依赖。
2. `AdvancedServiceBuildRenderer` 集中保存 Go、Rust、.NET、Kotlin、PHP、Ruby 六套构建脚本和构建工具映射。
3. `PlatformCapabilityProbeScript`、`PreparationRuntimeProfile` 和 `35-advanced-runtime.sh` 再次把多种生态的探测、安装后检查和启动规则聚合到同一实现。
4. 六个发行版准备类虽已独立，但与 APT/DNF 渲染器、配置、包目录、运行时检查和执行选择器平铺在同一包；`ManagedEnvironmentExecutor` 直接 `switch` 全部发行版。
5. protocol 和 runtime 中的工作区、输入、发布、容器、systemd、运行参数、helper 拼装职责仍是扁平结构。

## 3. 最终目标结构

只在有实际实现时创建目录，不预建空包。技术生态、部署形态、发行版、运行机制和 CPU 架构是独立维度，禁止创建 `jvm/ubuntu/x86_64` 等组合目录。

### 3.1 `shared.deploy`

```text
shared.deploy/
├─ adapter/
│  ├─ service/                 数据驱动的普通 systemd 服务适配器
│  └─ workload/
│     ├─ container/           单容器部署形态
│     └─ staticweb/           静态站点部署形态
├─ support/
│  ├─ HostSupportChecker
│  ├─ HostSupport
│  └─ distro/
│     ├─ policy/               具体发行版兼容策略
│     │  ├─ almalinux/
│     │  ├─ centosstream/
│     │  ├─ debian/
│     │  ├─ oraclelinux/
│     │  ├─ rocky/
│     │  └─ ubuntu/
│     ├─ registry/
│     ├─ rule/
│     └─ spi/
├─ contract/                   请求、审批、步骤和不可变计划
├─ environment/
├─ lifecycle/
├─ plan/                       计划生成、发布身份和校验
├─ registry/                   Adapter 默认装配
├─ result/
├─ spi/                        DeploymentAdapter 契约
└─ transaction/
```

`support.distro` 只在根层保存公共机制：`spi` 定义单发行版策略契约，`rule` 保存 CPU、安全及共用事实判断，`registry` 负责装配和完整性校验；所有具体实现统一进入 `policy/<distro>`，不得与这些公共包平铺。普通 systemd 服务通过一个受限的 Adapter 类型和不可变 Profile 覆盖 Spring Boot、普通 JAR、Node、Python、Go、Rust、.NET、Kotlin、PHP、Ruby；每个 Profile 仍只对应一个 `DeploymentProjectType`。静态站点和容器因源码发布及容器发布语义不同而保留独立实现。

### 3.2 `shared.linux`

```text
shared.linux/
├─ build/
├─ capability/
├─ connection/                 Gateway、端点、凭据、主机信任
├─ distro/
├─ error/                      LinuxOperationException
├─ protocol/
├─ runtime/
├─ session/                    LinuxRemoteSession、DeploymentRemoteSession
└─ transfer/
```

`linux` 继续只定义公共契约、请求、结果和值类型；不得导入 Apache SSHD、原始 Shell、原始 SFTP 或具体 systemd/container 实现。

### 3.3 `shared.linux-sshd`

```text
shared.linux.sshd/
├─ build/
│  ├─ config/
│  ├─ registry/
│  ├─ shell/
│  └─ spi/
├─ capability/
│  ├─ parser/
│  ├─ probe/
│  ├─ registry/
│  └─ spi/
├─ connection/                 SSH 客户端、认证和主机指纹
├─ distro/
│  ├─ setup/
│  │  ├─ apt/{debian,ubuntu}/
│  │  └─ dnf/{almalinux,centosstream,oraclelinux,rocky}/
│  ├─ profile/
│  ├─ registry/
│  ├─ shell/
│  └─ spi/
├─ ecosystem/
│  ├─ dotnet/{build,capability,runtime}/
│  ├─ go/{build,capability,runtime}/
│  ├─ jvm/
│  │  ├─ build/{gradle,jar,kotlin,maven,springboot}/
│  │  ├─ capability/
│  │  └─ runtime/
│  ├─ node/{build,capability,runtime}/
│  ├─ php/{build,capability,runtime}/
│  ├─ python/{build,capability,runtime}/
│  ├─ ruby/{build,capability,runtime}/
│  └─ rust/{build,capability,runtime}/
├─ protocol/{helper,input,release,runtime,workspace}/
├─ runtime/{container,dispatch,systemd}/
├─ session/                    SshdLinuxRemoteSession
├─ transfer/
├─ command/                  SshCommandExecutor
└─ workload/{container,staticweb}/
```

`distro` 根层只保存公共机制：`spi` 定义单发行版准备契约，`profile` 保存准备事实和生态检查选择，`registry` 负责装配与选择，`shell` 保存 helper 安装、安全观测等共用机械流程。所有具体执行实现统一进入 `setup`；其中 `apt` 和 `dnf` 只复用各自包管理器的安装与安全复核流程，六个具体发行版继续独立验证身份、版本、软件包、仓库和安全前置条件。

`ecosystem` 只保存生态专属的目标机构建、工具链探测和受控启动规则。构建执行、SSH command、发行版包名、helper 调度、systemd 生命周期、容器生命周期和发布/回滚机械流程保持生态无关。

## 4. 目标依赖方向

箭头表示左侧可以依赖右侧，反向依赖禁止：

```text
deploy.adapter        ──→ deploy.spi ──→ deploy.contract ──→ shared.model
deploy.registry       ──→ deploy.spi + deploy.adapter
deploy.plan           ──→ deploy.contract + deploy.registry
deploy.transaction    ──→ deploy.plan + deploy.result + shared.linux
deploy.support.HostSupportChecker ──→ deploy.support.distro.registry
deploy.support.distro.registry   ──→ deploy.support.distro.{policy,spi}
deploy.support.distro.policy     ──→ deploy.support.distro.{spi,rule}

linux.connection      ──→ linux.session
linux.session         ──→ linux.{build,capability,distro,protocol,runtime,transfer}
linux.* operations    ──→ linux.error ──→ model.message

linux-sshd.command  ──→ linux.error + Apache SSHD
linux-sshd.ecosystem  ──→ linux-sshd.{build.spi,capability.spi} + command
linux-sshd.workload   ──→ linux-sshd.build.spi + command
linux-sshd.distro.setup ──→ linux-sshd.distro.{spi,profile,shell} + linux-sshd.capability.spi + command
linux-sshd.distro.registry    ──→ linux-sshd.distro.{preparation,spi}
linux-sshd.distro.ManagedEnvironmentExecutor ──→ linux-sshd.distro.registry
linux-sshd.{protocol,runtime,transfer} ──→ command
linux-sshd.session    ──→ linux-sshd.{build,capability,distro,protocol,runtime,transfer}
linux-sshd.connection ──→ linux-sshd.session + command
```

默认注册表可以装配具体实现，但 SPI、不可变契约、错误和 command 不得反向依赖注册表、会话或上层编排。`HostSupportChecker` 继续负责跨运行时、发行版、CPU、安全和容器能力的最终匹配，不进入任何单一生态或发行版包。

## 5. 当前源码迁移映射

### 5.1 `shared.deploy` 生产类

| 当前类型 | 最终目标 |
| --- | --- |
| `adapter.DeploymentAdapter` | `spi.DeploymentAdapter` |
| `adapter.DeploymentPlanSupport` | 重命名为 `adapter.DeploymentPlanFactory`，只依赖 `contract` |
| `adapter.advanced.AdvancedServiceAdapter` | 删除；由 `adapter.service.ServiceDeploymentAdapter` 的固定 Profile 覆盖六种项目类型 |
| `adapter.springboot.SpringBootAdapter` | 删除；并入 `adapter.service.ServiceDeploymentAdapter` |
| `adapter.javajar.JavaJarAdapter` | 删除；并入 `adapter.service.ServiceDeploymentAdapter` |
| `adapter.node.NodeServiceAdapter` | 删除；并入 `adapter.service.ServiceDeploymentAdapter` |
| `adapter.python.PythonServiceAdapter` | 删除；并入 `adapter.service.ServiceDeploymentAdapter` |
| `adapter.staticweb.StaticSiteAdapter` | `adapter.workload.staticweb.StaticSiteAdapter` |
| `adapter.container.ContainerAdapter` | `adapter.workload.container.ContainerAdapter` |
| `compatibility.DistributionCompatibilityPolicy` | `support.distro.spi.DistributionSupportPolicy` |
| `compatibility.DistributionPolicySupport` | 重命名为 `support.distro.rule.DistributionSupportRules` |
| `compatibility.DistributionSupportPolicies` | 重命名为 `support.distro.registry.DistributionSupportRegistry` |
| `compatibility.UbuntuCompatibilityPolicy` | `support.distro.policy.ubuntu.UbuntuSupportPolicy` |
| `compatibility.DebianCompatibilityPolicy` | `support.distro.policy.debian.DebianSupportPolicy` |
| `compatibility.CentosStreamCompatibilityPolicy` | `support.distro.policy.centosstream.CentosStreamSupportPolicy` |
| `compatibility.RockyLinuxCompatibilityPolicy` | `support.distro.policy.rocky.RockyLinuxSupportPolicy` |
| `compatibility.AlmaLinuxCompatibilityPolicy` | `support.distro.policy.almalinux.AlmaLinuxSupportPolicy` |
| `compatibility.OracleLinuxCompatibilityPolicy` | `support.distro.policy.oraclelinux.OracleLinuxSupportPolicy` |
| `compatibility.HostCompatibility` | 重命名为 `support.HostSupportChecker`，改为依赖发行版注册表 |
| `compatibility.HostSupport` | `support.HostSupport` |
| `environment.EnvironmentPreparationService` | `environment.EnvironmentSetupService` |
| `lifecycle.ManagedComponentLifecycle` | 保持 `lifecycle.ManagedComponentLifecycle` |
| `lifecycle.ManagedLifecycleService` | 保持 `lifecycle.ManagedLifecycleService` |
| `lifecycle.MultiComponentLifecycleService` | 保持 `lifecycle.MultiComponentLifecycleService` |
| `plan.ApplicationHealthGate` | `contract.ApplicationHealthGate` |
| `plan.DeploymentApproval` | `contract.DeploymentApproval` |
| `plan.DeploymentStep` | `contract.DeploymentStep` |
| `plan.MultiComponentDeploymentPlan` | `contract.MultiComponentDeploymentPlan` |
| `plan.ReviewedDeploymentPlan` | `contract.ReviewedDeploymentPlan` |
| `plan.ReviewedDeploymentRequest` | `contract.ReviewedDeploymentRequest` |
| `plan.MultiComponentDeploymentPlanner` | 保持 `plan.MultiComponentDeploymentPlanner` |
| `plan.ReviewedDeploymentPlanner` | 保持 `plan.ReviewedDeploymentPlanner`，只依赖 contract 与 registry |
| `plan.ReviewedReleaseIdentity` | 保持 `plan.ReviewedReleaseIdentity` |
| `result.ComponentDeploymentResult` | 保持 `result.ComponentDeploymentResult` |
| `result.ComponentLifecycleResult` | 保持 `result.ComponentLifecycleResult` |
| `result.ComponentTransactionState` | 保持 `result.ComponentTransactionState` |
| `result.DeploymentEvent` | 保持 `result.DeploymentEvent` |
| `result.DeploymentResult` | 保持 `result.DeploymentResult` |
| `result.LifecycleActionResult` | 保持 `result.LifecycleActionResult` |
| `result.MultiComponentDeploymentResult` | 保持 `result.MultiComponentDeploymentResult` |
| `result.MultiComponentLifecycleResult` | 保持 `result.MultiComponentLifecycleResult` |
| `transaction.ReviewedComponentDeployment` | 保持 `transaction.ReviewedComponentDeployment` |
| `transaction.ReviewedDeploymentService` | 保持 `transaction.ReviewedDeploymentService` |
| `transaction.ReviewedMultiComponentDeploymentService` | 保持 `transaction.ReviewedMultiComponentDeploymentService` |

新增 `registry.DeploymentAdapterRegistry`、`adapter.service.ServiceDeploymentProfile` 和 `adapter.service.ServiceDeploymentAdapter`。注册表必须验证每个可部署 `DeploymentProjectType` 恰好有一个适配 Profile，静态站点和容器不得进入普通 service Profile。

### 5.2 `shared.deploy` 测试

| 当前测试 | 最终目标 |
| --- | --- |
| `support.HostSupportCheckerTest` | 保持并增加 `support.distro.policy.*` 策略及 `support.distro.registry` 直接测试 |
| `environment.EnvironmentSetupServiceTest` | 保持原包 |
| `plan.MultiComponentDeploymentPlannerTest` | 保持原包，契约类型改从 `contract` 导入 |
| `plan.ReviewedDeploymentPlannerTest` | 保持原包，并覆盖注册完整性、重复 Profile 和缺失 Profile |
| `plan.ReviewedReleaseIdentityTest` | 保持原包，后置阶段覆盖六种独立运行时记录 |
| `transaction.ReviewedDeploymentServiceTest` | 保持原包 |
| `transaction.ReviewedMultiComponentDeploymentServiceTest` | 保持原包 |

必须新增 `spi`、`registry`、`adapter.service`、两个 workload Adapter 和 `support.distro.policy.*` 六个发行版策略的直接测试；不得只通过事务测试间接覆盖。

### 5.3 `shared.linux` 生产类与测试

| 当前类型 | 最终目标 |
| --- | --- |
| `build.DeploymentBuildResult` | 保持 `build.DeploymentBuildResult` |
| `capability.LinuxCapabilityOperations` | 保持原包，异常改从 `error` 导入 |
| `capability.LinuxPlatformCapabilityOperations` | 保持原包，异常改从 `error` 导入 |
| `connection.DeploymentLinuxGateway` | 保持 `connection.DeploymentLinuxGateway`，返回 `session.DeploymentRemoteSession` |
| `connection.LinuxGateway` | 保持 `connection.LinuxGateway`，返回 `session.LinuxRemoteSession` |
| `connection.HostKeyDecision` | 保持原包 |
| `connection.HostKeyVerifier` | 保持原包 |
| `connection.SshCredential` | 保持原包 |
| `connection.SshEndpoint` | 保持原包 |
| `connection.LinuxOperationException` | `error.LinuxOperationException` |
| `connection.LinuxRemoteSession` | `session.LinuxRemoteSession` |
| `connection.DeploymentRemoteSession` | `session.DeploymentRemoteSession` |
| `distro.LinuxEnvironmentOperations` | 保持原包，异常改从 `error` 导入 |
| `protocol.ManagedHelperProtocol` | 保持原包 |
| `protocol.ReleaseSnapshot` | 保持原包 |
| `protocol.RemoteStepResult` | 保持原包 |
| `runtime.ContainerAutostart` | 保持原包 |
| `runtime.HealthCheckResult` | 保持原包 |
| `runtime.LinuxContainerRuntimeOperations` | 保持原包，异常改从 `error` 导入 |
| `runtime.LinuxRuntimeOperations` | 保持原包，异常改从 `error` 导入 |
| `transfer.LinuxTransferOperations` | 保持原包，异常改从 `error` 导入 |
| `transfer.RemoteWorkspace` | 保持原包 |
| `transfer.UploadReceipt` | 保持原包 |
| `connection.ManagedRemoteContractTest` | 移入 `session.ManagedRemoteContractTest`，验证组合接口只依赖能力契约 |
| `runtime.ContainerAutostartTest` | 保持原包 |

迁移后 `connection`、`session`、capability、distro、runtime、transfer 之间不得存在双向包导入；公共测试同时检查 `linux` 源码不存在 `org.apache.sshd` 导入。

### 5.4 `shared.linux-sshd` 生产类

| 当前类型 | 最终目标 |
| --- | --- |
| `build.DeploymentBuildExecutor` | 保持 `build.DeploymentBuildExecutor`，仅依赖 renderer registry 与 command |
| `build.DeploymentBuildRenderer` | `build.spi.DeploymentBuildRenderer` |
| `build.DeploymentBuildRendererRegistry` | `build.registry.DeploymentBuildRendererRegistry` |
| `build.BuildConfigurationEnvironment` | `build.config.BuildConfigEnvironment` |
| `build.SafeBuildScriptEnvelope` | `build.shell.SafeBuildScriptEnvelope` |
| `build.SpringBootBuildRenderer` | `ecosystem.jvm.build.springboot.SpringBootBuildRenderer` |
| `build.JavaJarBuildRenderer` | `ecosystem.jvm.build.jar.JavaJarBuildRenderer` |
| `build.NodeBuildRenderer` | `ecosystem.node.build.NodeBuildRenderer` |
| `build.NodePackageBuildScript` | `ecosystem.node.build.NodePackageBuildScript` |
| `build.PythonBuildRenderer` | `ecosystem.python.build.PythonBuildRenderer` |
| `build.StaticSiteBuildRenderer` | `workload.staticweb.StaticSiteBuildRenderer` |
| `build.ContainerBuildRenderer` | `workload.container.ContainerBuildRenderer` |
| `build.AdvancedServiceBuildRenderer` | 删除并拆为 `ecosystem.go.build.GoBuildRenderer`、`ecosystem.rust.build.RustBuildRenderer`、`ecosystem.dotnet.build.DotNetBuildRenderer`、`ecosystem.jvm.build.kotlin.KotlinBuildRenderer`、`ecosystem.php.build.PhpBuildRenderer`、`ecosystem.ruby.build.RubyBuildRenderer` |
| `capability.CapabilityProbeScript` | 重命名为 `capability.probe.ManagedHostCapabilityProbe` |
| `capability.PlatformCapabilityProbeScript` | 拆为平台 Probe、容器工作负载 Probe 及八个 `ecosystem.*.capability` Probe，由 capability 注册表组合 |
| `capability.SshdCapabilityCollector` | 保持 `capability.SshdCapabilityCollector`，解析委托给 `capability.parser` |
| `capability.SshdPlatformCapabilityCollector` | 保持 `capability.SshdPlatformCapabilityCollector`，不再循环 `AdvancedRuntimeKind` |
| `connection.SshCommandExecutor` | `command.SshCommandExecutor` |
| `connection.SshdLinuxGateway` | 保持 `connection.SshdLinuxGateway`，只负责连接、认证和主机信任 |
| `connection.SshdLinuxRemoteSession` | `session.SshdLinuxRemoteSession` |
| `distro.ManagedEnvironmentExecutor` | 保持 `distro.ManagedEnvironmentExecutor`，改为依赖准备注册表而非具体发行版 `switch` |
| `distro.DistributionPreparationProfile` | `distro.profile.DistributionSetupProfile` |
| `distro.EnvironmentPreparationShellSupport` | `distro.shell.SetupShellSupport` |
| `distro.AptEnvironmentPreparationRenderer` | `distro.setup.apt.AptSetupRenderer` |
| `distro.DnfEnvironmentPreparationRenderer` | `distro.setup.dnf.DnfSetupRenderer` |
| `distro.UbuntuEnvironmentPreparation` | `distro.setup.apt.ubuntu.UbuntuSetup` |
| `distro.DebianEnvironmentPreparation` | `distro.setup.apt.debian.DebianSetup` |
| `distro.CentosStreamEnvironmentPreparation` | `distro.setup.dnf.centosstream.CentosStreamSetup` |
| `distro.RockyLinuxEnvironmentPreparation` | `distro.setup.dnf.rocky.RockyLinuxSetup` |
| `distro.AlmaLinuxEnvironmentPreparation` | `distro.setup.dnf.almalinux.AlmaLinuxSetup` |
| `distro.OracleLinuxEnvironmentPreparation` | `distro.setup.dnf.oraclelinux.OracleLinuxSetup` |
| `distro.PreparationPackageCatalog` | 拆为 APT 基线、Ubuntu 扩展和 DNF 基线目录，分别归 `distro.setup.apt`、`distro.setup.apt.ubuntu`、`distro.setup.dnf` |
| `distro.PreparationRuntimeProfile` | 删除；由发行版 Profile 选择已注册生态检查片段，具体版本检查归 `ecosystem.*.capability` |
| `protocol.CandidateWorkspaceController` | `protocol.workspace.CandidateWorkspaceController` |
| `protocol.DeploymentConfigurationRenderer` | `protocol.input.DeploymentConfigurationRenderer` |
| `protocol.DeploymentInputArguments` | `protocol.input.DeploymentInputArguments` |
| `protocol.DeploymentInputProtocolExecutor` | `protocol.input.DeploymentInputProtocolExecutor` |
| `protocol.DeploymentReleaseProtocolExecutor` | `protocol.release.DeploymentReleaseProtocolExecutor` |
| `protocol.ContainerReleaseProtocolExecutor` | `protocol.release.ContainerReleaseProtocolExecutor` |
| `protocol.DeploymentRuntimeArguments` | `protocol.runtime.DeploymentRuntimeArguments` |
| `protocol.ContainerRuntimeArguments` | `protocol.runtime.ContainerRuntimeArguments` |
| `protocol.ManagedRuntimeController` | `protocol.runtime.ManagedRuntimeController` |
| `protocol.ManagedHelperBundle` | `protocol.helper.ManagedHelperBundle` |
| `runtime.ContainerRuntimeExecutor` | `runtime.container.ContainerRuntimeExecutor` |
| `runtime.ManagedRuntimeExecutor` | `runtime.dispatch.ManagedRuntimeExecutor` |
| `runtime.ManagedRuntimeIdentity` | `runtime.dispatch.ManagedRuntimeIdentity` |
| `runtime.ManagedRuntimeKindProbe` | `runtime.dispatch.ManagedRuntimeKindProbe` |
| `runtime.SystemdHealthChecker` | `runtime.systemd.SystemdHealthChecker` |
| `runtime.SystemdHealthScriptRenderer` | `runtime.systemd.SystemdHealthScriptRenderer` |
| `runtime.SystemdLifecycleExecutor` | `runtime.systemd.SystemdLifecycleExecutor` |
| `runtime.SystemdOwnershipObserver` | `runtime.systemd.SystemdOwnershipObserver` |
| `runtime.SystemdUnitRenderer` | `runtime.systemd.SystemdUnitRenderer` |
| `transfer.LocalArchivePolicy` | 保持 `transfer.LocalArchivePolicy` |
| `transfer.SshdSourceTransfer` | 保持 `transfer.SshdSourceTransfer`，改为依赖 command 与 workspace 协议实现 |

新增窄契约及注册表：

- `capability.spi.EcosystemCapabilityProbe` 与 `capability.registry.EcosystemCapabilityProbeRegistry`。
- `distro.spi.DistributionSetup` 与 `distro.registry.DistributionSetupRegistry`。
- 每个发行版 Profile 显式选择需安装的软件包及需执行的生态检查，不引用其他发行版实现。
- 构建注册表继续要求全部可部署项目类型恰好一个 Renderer；具体 Renderer 不得依赖执行器或会话。

### 5.5 `shared.linux-sshd` 测试

| 当前测试 | 最终目标 |
| --- | --- |
| `build.BuildConfigurationEnvironmentTest` | `build.config.BuildConfigEnvironmentTest` |
| `build.DeploymentBuildRendererTest` | 拆为 `build.registry` 覆盖测试及各 `ecosystem.*.build`、`workload.*` 直接测试 |
| `capability.SshdPlatformCapabilityCollectorTest` | 保持 capability 门面测试，并新增平台、容器和八个生态 Probe 直接测试 |
| `connection.SshdLinuxGatewayTest` | 保持 `connection.SshdLinuxGatewayTest`，只验证连接、认证、指纹和关闭 |
| `connection.UbuntuManagedDiagnosticsIT` | `session.UbuntuManagedDiagnosticsIT` |
| `connection.UbuntuSshShutdownAcceptanceIT` | 保持 `connection.UbuntuSshShutdownAcceptanceIT` |
| `protocol.ContainerProtocolContractTest` | `protocol.release.ContainerProtocolContractTest` |
| `protocol.DeploymentConfigurationRendererTest` | `protocol.input.DeploymentConfigurationRendererTest` |
| `protocol.ManagedHelperBundleTest` | `protocol.helper.ManagedHelperBundleTest` |
| `runtime.SystemdHealthScriptRendererTest` | `runtime.systemd.SystemdHealthScriptRendererTest` |

必须新增 command、session、发行版注册表、`distro.setup` 下的 APT/DNF 家族与六个发行版实现、runtime dispatch/systemd/container 以及 protocol 子职责的直接测试。所有测试包镜像生产包，不建立只为测试存在的生产 API。

### 5.6 helper 资源

| 当前资源 | 最终职责 |
| --- | --- |
| `00-common.sh` | 保留在 `protocol/helper/fragments`，只保存身份、路径、参数和所有权公共校验；生态启动规则迁出 |
| `10-typed-release.sh` | `protocol/helper/fragments/release/10-typed-release.sh`，生态制品校验委托对应生态片段 |
| `15-deployment-input.sh` | `protocol/helper/fragments/input/15-deployment-input.sh` |
| `20-candidate-workspace.sh` | `protocol/helper/fragments/workspace/20-candidate-workspace.sh` |
| `30-ordinary-release.sh` | `protocol/helper/fragments/release/30-ordinary-release.sh` |
| `35-advanced-runtime.sh` | 删除；拆为八个 `ecosystem/*/runtime` 固定片段及 `protocol/helper/fragments/runtime/35-ecosystem-dispatch.sh` |
| `40-typed-runtime.sh` | `protocol/helper/fragments/runtime/40-typed-runtime.sh` |
| `50-container-release.sh` | `protocol/helper/fragments/release/50-container-release.sh` |
| `55-podman-quadlet.sh` | `runtime/container/helper/55-podman-quadlet.sh` |
| `60-lifecycle.sh` | `runtime/systemd/helper/60-lifecycle.sh` |
| `70-command-dispatch.sh` | `protocol/helper/fragments/70-command-dispatch.sh` |

资源移动和拆分不得改变 helper verb、参数数量、参数顺序、退出码语义、远端根目录、所有权校验或 sudoers 白名单。只要线协议保持不变，`ManagedHelperProtocolVersion.CURRENT` 继续为 3；bundle 字节变化必须同步更新固定 SHA-256、资源顺序测试和安装测试，并由用户通过产品“环境准备”显式替换远端 helper。

## 6. 目标接口与行为约束

1. `DeploymentAdapter` 的行为保持为“一个已审阅请求生成一个类型化计划”，但契约移入 `deploy.spi`，输入和结果来自 `deploy.contract`。
2. `DeploymentAdapterRegistry`、`DeploymentBuildRendererRegistry`、`EcosystemCapabilityProbeRegistry`、`DistributionSetupRegistry` 分别持有自己的 `defaults()` 装配和完整性校验；它们必须拒绝空实现、重复类型、缺失类型及实现自报类型不匹配。Planner、Executor 和 Session 构造器不得逐项列举具体生态或发行版实现。
3. `DistributionSupportPolicy` 只读取已采集事实并返回支持判断；`DistributionSetup` 只渲染一个固定发行版的受控准备脚本。兼容策略不得执行远程操作，准备实现不得决定产品支持等级。
4. `LinuxGateway`、`DeploymentLinuxGateway`、`LinuxRemoteSession`、`DeploymentRemoteSession`、`SshdLinuxGateway` 的行为和方法集合保持不变；包迁移后一次性更新仓内导入，不保留旧包兼容壳。
5. 新增包私有 `session.SshdLinuxRemoteSessionFactory` 作为 SSHD 实现唯一组合根。Gateway 完成连接、主机信任和认证后，只把 client、session、端点及指纹交给该 Factory；Factory 创建 command、各默认注册表、能力执行器和最终会话门面。
6. `SshCommandExecutor` 仍只执行实现持有的预渲染脚本，不公开给 `deploy`、`app`、`web` 或 `linux` 契约；session 只委托类型化能力。
7. build、capability、distro、protocol、runtime 和 transfer 只能依赖 command，不得依赖具体 Gateway 或 session。
8. systemd、Docker、Podman、发布、回滚、保留、配置和秘密输入逻辑保持语言无关；生态包不得复制这些流程。

## 7. `Advanced*` 后置原子迁移

该阶段属于最终结构的一部分，但不得与前述包移动混成不可审阅的大批次。它不需要数据库迁移，却会改变多个公共 Java 类型和 helper bundle 字节，必须在一个代码阶段内原子完成。

| 当前公共或生产引用 | 最终决定 |
| --- | --- |
| `model.project.AdvancedRuntimeKind` | 删除，不新增另一种按成熟度或引入批次聚合的替代枚举 |
| `DeploymentRuntimeSpecification.AdvancedService` | 替换为 `GoService`、`RustService`、`DotNetService`、`KotlinService`、`PhpService`、`RubyService` 六个类型化记录 |
| `DeploymentRuntimeSuggestion.RuntimeInput.ADVANCED_*` | 替换为稳定的 `SERVICE_VERSION`、`SERVICE_ARTIFACT`、`SERVICE_ENTRYPOINT`、`SERVICE_PORT` |
| `LinuxCapabilities.advancedRuntimeVersions` | 替换为以 `DeploymentProjectType` 为键的 `serviceRuntimeVersions`；Java、Node、Python、Go、Rust、.NET、Kotlin/JVM、PHP、Ruby 使用显式事实，不以“高级”集合分组 |
| `analyze.language.advanced.AdvancedLanguageDeploymentInspector` | 按分析修订文档拆入六个生态，输出稳定 RuntimeInput |
| `analyze.core.DeploymentAnalysisCoordinator` | 通过分析注册表装配六个生态检查器，不导入 `AdvancedRuntimeKind` |
| `deploy.adapter.advanced.AdvancedServiceAdapter` | 已由数据驱动 service Adapter 取代 |
| `deploy.support.HostSupportChecker` | 对六种类型化运行时分别读取 `RuntimeToolchainCapabilities` |
| `deploy.plan.ReviewedReleaseIdentity` | 对六种类型化运行时生成与现有字段等价的发布身份输入 |
| `linux-sshd.build.AdvancedServiceBuildRenderer` | 已拆为六个生态 Renderer |
| `linux-sshd.capability` 的 `ADVANCED_*` 探测键 | 改为稳定生态键，由对应 Probe 解析 |
| `linux-sshd.protocol.DeploymentRuntimeArguments` | 对六种类型化运行时生成现有 `go/rust/dotnet/kotlin/php/ruby` 参数，不改变线协议 |
| `app.ui.deployment.DeploymentRuntimeParser` | 按所选项目类型构造对应运行时记录 |
| `app.ui.deployment.DeploymentPage` | 使用稳定 RuntimeInput，不改变现有表单字段和状态保留规则 |

所有受影响测试和产品入口夹具必须在同一阶段更新；不得保留 `AdvancedRuntimeKind`、`AdvancedService`、`ADVANCED_*`、`AdvancedServiceBuildRenderer`、`advanced-runtime` 或旧包转发类型。此次迁移不改变 `DeploymentProjectType`、支持等级、数据库内容、helper verb 或真实环境支持范围。

## 8. 后续实施阶段

### 8.1 公共契约解环

1. 先移动 `deploy.contract`、`deploy.spi`、`linux.error` 和 `linux.session`，机械更新仓内导入及测试。
2. 建立包依赖审计，确认 `deploy.plan ↔ adapter` 与 `linux.connection ↔ capability/distro/runtime/transfer` 已消失。
3. 不保留旧包兼容壳；一个公开类型在迁移后只能有一个定义。

### 8.2 SSHD command/session 解环

1. 将 `SshCommandExecutor` 移入 command，将 `SshdLinuxRemoteSession` 移入 session。
2. Gateway 只创建已认证会话；session 组合各实现；具体能力只依赖 command。
3. 验证 Apache SSHD 类型没有越过 `shared/linux-sshd` 模块边界。

### 8.3 deploy SPI、注册表与部署形态

1. 建立 Adapter 注册表和固定 service Profile，删除重复语言 Adapter 与 Planner 直接装配。
2. 移动静态站点、容器和发行版兼容能力；公共契约、规则、注册表分别进入 `distro.spi`、`distro.rule`、`distro.registry`，六个具体实现统一进入 `distro.policy.<distro>`。
3. 保持单组件、多组件计划、事务、回滚、生命周期和结果语义不变。

### 8.4 技术生态迁移

1. 移动 JVM、Node、Python 的现有构建器并抽取 Maven/Gradle 共用机械流程。
2. 将 `AdvancedServiceBuildRenderer` 拆为六个独立 Renderer，不共享语言构建命令。
3. 拆分生态能力 Probe；平台 Probe 只保留发行版、CPU、安全、防火墙和 helper 等语言无关事实。

### 8.5 发行版、runtime 与 protocol

1. 新增发行版准备 SPI/注册表，把具体实现迁入 `distro.setup`，再按 APT/DNF 包管理器家族和具体发行版分层，删除集中 `switch`。
2. 删除 `PreparationRuntimeProfile`，由发行版 Profile 选择生态检查片段。
3. 将 systemd、container、dispatch 及 protocol 子职责归位，保持执行结果和错误本地化键不变。

### 8.6 `Advanced*` 原子清理

1. 先替换公共 model，再同步迁移 analyze、deploy、linux-sshd、UI 和测试。
2. 拆分 helper 生态资源但保持线协议 v3；更新 bundle 摘要和固定顺序。
3. 静态搜索必须确认生产源码和资源不存在全部禁用名称。

### 8.7 测试与文档收尾

1. 测试包镜像生产包，补齐 SPI、注册表、各生态、发行版和运行机制的直接测试。
2. 更新 `File.md` 当前落地说明为已实施；只有发生功能范围或运行证据变化时才修改 DEVELOPMENT 或分期文档。
3. 删除迁移期间产生的临时桥接，重新执行包依赖无环审计和完整离线门禁。

## 9. 未来代码验收门禁

- [ ] `deploy.plan` 不导入具体 Adapter；Adapter 不导入 `deploy.plan`。
- [ ] `deploy.support.distro` 的 SPI、公共规则和注册表不与具体发行版平铺；六个具体策略只位于 `policy.<distro>`，依赖方向保持 `HostSupportChecker → registry`、`registry → policy/spi`、`policy → spi/rule`。
- [ ] `linux.connection` 只保存连接契约，不保存异常或组合会话。
- [ ] `linux-sshd.connection` 不保存命令 command 或总会话。
- [ ] build、capability、distro、protocol、runtime、transfer 与 connection/session 不存在包环。
- [ ] 每个可部署项目类型恰好一个 Adapter Profile 和一个 Build Renderer。
- [ ] JVM、Node、Python、Go、Rust、.NET、PHP、Ruby 的生态代码只位于目标生态；容器和静态站点只位于 workload。
- [ ] APT/DNF 只共享机械流程，六个发行版各自验证身份、版本、包架构、CPU 和安全前置条件。
- [ ] `linux-sshd.distro` 的 SPI、Profile、注册表和公共 Shell 机制不与具体实现平铺；具体实现只位于 `preparation.{apt,dnf}.<distro>`。
- [ ] 不存在语言/发行版/CPU 笛卡尔组合包。
- [ ] 不存在 `advanced`、`additional`、`AdvancedRuntimeKind`、`AdvancedService`、`ADVANCED_*` 或 `advanced-runtime` 生产名称。
- [ ] helper verb、参数顺序、退出码、安全路径、所有权和 sudoers 白名单保持不变；协议版本仍为 3。
- [ ] helper bundle 字节变化后更新固定 SHA-256，并只能经产品“环境准备”显式安装。
- [ ] 数据库 schema 和持久化语义不变，不新增兼容壳或迁移脚本。
- [ ] JDK 21 系统 Maven 执行 `mvn.cmd -B -ntp -o verify` 全量通过。
- [ ] 构建与静态验证不替代真实 Linux 证据；helper 字节变化后的运行结论在产品入口复验前保持 `RUNTIME-PENDING`。

## 10. 本次文档验收

- [x] `File.md` 版本为 `3.13.2-linux-sshd-distro-preparation-layout`，日期为 2026-08-15，状态明确为目标待审核、源码未迁移。
- [x] `File.md` 的 deploy、linux、linux-sshd 目标树、协作边界、分包规则、依赖方向和版本记录相互一致。
- [x] 本文覆盖三个模块全部现有生产类、测试和 helper 资源，并记录必要的跨模块 `Advanced*` 引用。
- [x] 两份文档使用相对链接且目标存在，Markdown 表格和代码块完整。
- [x] 现有 `ANALYZE-PACKAGE-REVISION.md` SHA-256 仍为 `0F90AB913E65F643FED0D6D4E0549573E9698DB9B8C215408264602D56AFB8D2`。
- [x] `git diff --check` 通过。
- [x] `git status --short` 和文件清单确认没有源码、测试、POM、资源或其他文档变化。

## 11. 版本记录

| 版本 | 日期 | 状态 | 说明 |
| --- | --- | --- | --- |
| 1.2.0 | 2026-08-18 | 已实施（本地结构验证完成） | 简化部署支持、构建配置、发行版设置与 SSH 命令实现包名，并同步对应类型名；模块、helper 协议、持久化语义和真实 Linux 支持边界不变。 |
| 1.1.0 | 2026-08-15 | 已实施（本地结构验证完成） | 完成 deploy、linux、linux-sshd 的职责分包、注册表装配、生态服务运行时类型替换及 helper 资源归位；JDK 21 离线 28 模块验证通过，真实 Linux 运行结论保持原范围。 |
| 1.0.2 | 2026-08-15 | 草案，待审核 | 将 `linux-sshd.distro` 的具体实现统一下沉到 `preparation/{apt,dnf}/<distro>`，与 SPI、Profile、注册表和公共 Shell 机制分离，并同步依赖方向、迁移映射、测试要求和验收门禁；本次仍不修改代码或运行行为。 |
| 1.0.1 | 2026-08-15 | 草案，待审核 | 将 `deploy.compatibility.distro` 的具体发行版策略统一下沉到 `policy/<distro>`，与 SPI、公共规则和注册表分离，并同步目标依赖、迁移映射、测试要求和验收门禁；本次仍不修改代码或运行行为。 |
| 1.0.0 | 2026-08-15 | 草案，待审核 | 定义 Linux 部署链的最终职责分包、模块级技术生态、发行版族、单向依赖、完整迁移映射、`Advanced*` 后置原子清理和验收门禁；本次不修改代码或运行行为。 |
