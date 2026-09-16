# WindowsToLinux 二期开发文档

<a id="navigation"></a>

## 文档信息与导航

- 文档结构版本：`3.0.0-functional-phases`；整理日期：2026-09-10。
- 状态：二期功能已进入现有桌面链路；Ubuntu/Stream 验收保留精确历史范围，现行部署与身份规则见四期。
- 依据：现行行为以当前代码和适用的运行证据为准；本期引入范围与后期调整分开说明。
- [开发总纲](DEVELOPMENT.md) · [现行结构](../File.md) · [1期](PHASE-1.md) · [3期](PHASE-3.md) · [4期](PHASE-4.md) · [5期](PHASE-5.md) · [6期](PHASE-6.md)

- [1. 本期目标与承接关系](#goals)
- [2. 模块增量总表](#modules)
- [3. 功能开发说明](#features)
  - [3.1 Git 来源与源码快照](#git)
  - [3.2 项目事实、支持范围与运行时建议](#analysis)
  - [3.3 配置快照与应用秘密](#configuration)
  - [3.4 多类型构建、容器发布与生命周期](#deployment)
  - [3.5 Linux 范围与旧系统兼容边界](#linux)
  - [3.6 多 Provider 与只读 Agent](#ai)
- [4. 实施顺序与依赖](#implementation)
- [5. 验收标准与验证记录](#acceptance)
- [6. 剩余事项与后续边界](#remaining)
- [7. 功能演进与历史版本记录](#history)

<a id="goals"></a>

## 1. 本期目标与承接关系

二期扩展部署宽度，同时继承一期已经验证的受管身份、目标机构建、健康检查、失败恢复和生命周期规则。新增 Git 来源、应用配置与密钥、更多常用项目类型、Docker/Podman 和更多 Ubuntu/CentOS 版本；不引入多组件编排、数据库一致性、备份迁移或 Web 版。

<a id="modules"></a>

## 2. 模块增量总表

| 模块 | 已有基础 | 变化类型 | 本期具体增量 | 功能入口 |
| --- | --- | --- | --- | --- |
| `shared/model` | 一期项目模型 | 增强 | 扩展构建、运行时、语言事实与配置绑定 | [项目事实](#analysis) |
| `shared/analyze` | Maven Spring Boot | 增强 | 六类项目、语言证据和显式运行时建议 | [项目事实](#analysis) |
| `shared/git` | 本地来源 | 新增 | 无凭据 URL、固定 Commit、受限 Git 快照 | [Git 来源](#git) |
| `shared/source` | 本地归档 | 增强 | Git 与本地共用来源摘要和排除规则 | [Git 来源](#git) |
| `shared/config` | 基础输入 | 新增 | 不可变配置、秘密引用及发布绑定 | [配置与秘密](#configuration) |
| `shared/deploy` | 单组件事务 | 增强 | 更多项目与容器适配，回滚旧运行参数 | [构建与发布](#deployment) |
| `shared/linux` | Ubuntu/systemd 契约 | 增强 | 运行类型、容器与更多目标能力 | [Linux 范围](#linux) |
| `shared/linux-sshd` | Maven/Ubuntu 执行 | 增强 | 语言构建、Docker/Podman 与发行版准备 | [构建与发布](#deployment) |
| `shared/ai` | 单 Provider 建议 | 增强 | 多 Provider 和类型化只读 Agent 工具 | [AI 扩展](#ai) |
| `app/db` | 服务器和应用记录 | 增强 | 配置修订、秘密绑定和发布身份 | [配置与秘密](#configuration) |
| `app/secret` | SSH/AI 平台凭据 | 增强 | 应用秘密的标识和修订 | [配置与秘密](#configuration) |
| `app/service` | 本地单类型部署 | 增强 | Git、配置、容器和 AI 用例接线 | [构建与发布](#deployment) |
| `app/ui` | 一期界面 | 增强 | 来源、运行时及配置审阅 | [项目事实](#analysis) |
| `app/main` | 桌面装配 | 增强 | 新增共享能力装配 | [构建与发布](#deployment) |

<a id="features"></a>

## 3. 功能开发说明

继承一期受管身份、健康、回滚及生命周期；只列二期新增或增强部分。

<a id="git"></a>

### 3.1 Git 来源与源码快照

**涉及模块与分工：** `shared/git` 固定来源，`shared/source` 归档，`app/service` 合并本地/Git 准备路径。

代码依据：[GitSnapshotPreparer.java](../../src/shared/git/src/main/java/gold/debug/windowstolinux/shared/git/snapshot/GitSnapshotPreparer.java)、[GitRemote.java](../../src/shared/git/src/main/java/gold/debug/windowstolinux/shared/git/GitRemote.java)、[GitRepositoryFeaturePolicy.java](../../src/shared/git/src/main/java/gold/debug/windowstolinux/shared/git/snapshot/GitRepositoryFeaturePolicy.java)。URI 解析接受 https/ssh/file，但实际产品准入和认证仍受请求及执行器限制，不能据此声称私有仓库可用。

#### 输入和准备

当前 Git 请求使用无凭据远端 URI、分支/Tag/Commit 和受限工作区策略。私有凭据注入未接通；Submodule、LFS 和符号链接条目由策略拒绝，不能把原需求清单当成已支持。

```text
解析仓库来源
→ 使用受限平台工作区取得只读分析快照
→ 将分支或 Tag 固定为 Commit
→ 静态分析，不执行仓库代码
→ 目标机接收平台准备的同一 Commit 归档并校验摘要
→ 在目标机执行构建
```

- 桌面或 Web 工作区可以为静态分析读取源码，但不得安装依赖、执行 Hook、构建脚本、Wrapper 或项目二进制。
- 禁用 Git Hook 并设置 GIT_TERMINAL_PROMPT=0；当前拒绝 Submodule 和 LFS，未来支持必须先建立受控物化策略。
- 发布请求必须保存 Commit、源码摘要和无凭据来源，不得只保存可变化的分支名；Submodule 当前被拒绝，因而不会产生未受控的子模块记录。
- 平台分析快照与目标机构建快照不一致时停止部署。

<a id="analysis"></a>

### 3.2 项目事实、支持范围与运行时建议

**涉及模块与分工：** `shared/analyze` 提取事实，`shared/model` 表达类型和证据，`app/ui` 展示审阅输入。

本节描述二期引入范围，当前枚举还包含三期生态；当前动态版本选择见四期。

| 类型 | 二期项目目标与必要条件 |
| --- | --- |
| Spring Boot | Maven 与 Gradle 不得并存；Gradle 使用完整 Wrapper，Maven 使用完整 Wrapper 或目标机系统 Maven；只接受唯一且具有 Spring Boot 2/3 Launcher 的可执行 JAR |
| 普通 Java JAR | Java 版本、主类、启动参数和健康策略都可确定 |
| Node.js | 锁文件、包管理器、构建/启动脚本和监听端口明确 |
| Python | Python 版本、锁定依赖、入口、虚拟环境和健康策略明确 |
| 静态站点 | 构建产物目录确定；构建型站点具有精确或人工确认的 Node 主版本，纯静态站点不要求 Node |
| Dockerfile | 单镜像、单容器、端口/健康/持久化目录明确 |

没有锁文件、入口冲突、需要用户自定义任意命令或无法确定产物的项目只能展示分析结果，不能标记正式支持。多组件受管图属于三期；当前 ContainerDeploymentInspector 仍拒绝 Compose 文件，不能把组件图能力当成 Compose 支持。

分析优先级固定为：

```text
明确部署配置
→ 语言版本和锁文件
→ 构建配置
→ CI
→ README/INSTALL/DEPLOYMENT/docs
→ 示例配置
→ AI 建议
```

每项结论记录来源、置信度、冲突和缺失信息。文档或源码中的提示文本均视为不可信数据，不能改变工具权限、安全规则或命令白名单。无法自动决定且会改变入口、数据、网络或权限的事项才询问用户。

#### 基础语言事实边界

- 二期的生态范围为 `JAVA`、`NODE_JS`、`PYTHON`；二期的源码语言范围为 `JAVA`、`JAVASCRIPT`、`TYPESCRIPT`、`PYTHON`。
- `.java` 与 JAR Manifest 产生 Java 证据；`package.json`、JavaScript/TypeScript 扩展名与 `tsconfig` 产生 Node 生态及源码语言证据；`pyproject.toml` 与 `.py` 产生 Python 证据。
- 语言事实是确定性集合和 `AnalysisEvidence`，不计算“主要语言”、比例或支持等级，不据此自动改变用户选择的 `DeploymentProjectType`。
- 多语言根、语言支持等级和自动项目类型选择仍属于三期，不在本期提前实现。

<a id="configuration"></a>

### 3.3 配置快照与应用秘密

**涉及模块与分工：** `shared/config` 定义不可变值与秘密引用；`app/db` 存实例和修订，`app/secret` 存秘密，`app/service` 绑定发布。

二期历史持久化基线为 SQLite v5；当前已为 v12，运行时编码 v3。四期进一步保存资源、健康和运行身份，不把这些字段倒计为二期新增。

#### 普通配置快照

- 端口、Profile、启动参数和非敏感环境变量在部署计划中结构化校验。
- 每个发布版本生成不可变普通配置快照，并记录模式版本和摘要；运行时引用该版本快照。
- 回滚恢复旧二进制和旧普通配置快照，避免新版配置格式破坏旧版本。
- 配置值不得扩展为任意 Shell 片段，路径和环境变量名称使用类型化白名单。

#### 共享密钥

- 密钥位于发布版本之外的受限共享目录，由受管应用运行身份最小权限读取；禁止进入普通配置、unit 明文、命令行、日志或归档源码。
- 每个密钥具有稳定标识和不可变修订号。发布清单只引用“标识+修订”，不复制原始密钥到版本目录。
- 轮换创建新修订，不原地覆盖。仍被可回滚版本引用的修订必须保留；无引用且超出回滚窗口后才能受控删除。
- 部署、回滚和启动前验证所有引用存在且权限正确。缺失或无法解密时停止，不回退到空值或最新修订。
- 原始密钥仅在录入或替换时进入 `app/secret`/`web/secret`，保存后界面只显示名称、状态、修订和更新时间。

<a id="deployment"></a>

### 3.4 多类型构建、容器发布与生命周期

**涉及模块与分工：** `shared/deploy` 复用事务，`shared/linux-sshd` 执行语言和容器适配；服务层持久化版本身份。

所有类型继续只在目标 Linux 构建；原一期/二期 root 构建确认只保留为历史规则，当前执行四期临时低权限构建及独立运行身份。各适配器只能调用固定工具入口，必须限制资源、清理继承环境并验证产物。进入事务前会采集发行版、架构、包管理器、systemd、容器、Quadlet 和 CPU 事实；不匹配矩阵时在上传前拒绝。

- Java：Maven/Gradle Wrapper优先，普通 JAR 必须固定主类和 JVM 参数结构，不能接受一段任意启动命令。
- Node.js：锁定 npm/pnpm/yarn 类型和锁文件；生产依赖安装与构建在候选目录完成。
- Python：使用候选版本专属虚拟环境和锁定依赖；不得污染系统 Python。
- 静态站点：只发布分析确定的产物目录，禁止把源码根目录直接暴露；Node 构建型站点必须验证审阅的显式主版本，纯静态站点不启动 Node 构建。
- Docker：构建单镜像，使用受管标签/摘要和显式卷、端口、健康、用户配置；自启通过受控 restart policy 实现并复核。
- Podman：优先使用 Quadlet 描述受管容器，在 `[Install]` 配置目标；不得对生成的临时 service 错误执行普通 `systemctl enable` 并宣称自启成功。

当前 Docker/Podman 构建采用四期 rootless 构建流程；容器运行用户必须明确为非 root，相关数据映射与身份边界见四期。Docker 风险确认仍保留。禁止 privileged、宿主根目录挂载、Docker socket 挂载、host PID/IPC 和未审查设备访问。

- systemd 类型继续使用一期短停机事务；发布目录保存 root 所有的运行参数，快照同时保存旧 unit、运行参数、运行状态和自启状态。
- 单容器发布先构建并校验镜像，再停止旧容器、以受管定义启动候选、健康检查；失败时恢复旧镜像摘要、端口/卷定义和自启状态，不复用候选版本参数。
- 持久化卷和外部数据不随容器版本覆盖；涉及不可逆数据格式变更时停止并提示四期数据库/备份能力。
- 新增部署类型成功后进入同一受管列表，提供状态、启动、停止、重启和自启，不提供删除、通用日志或任意容器管理。
- 自启动作保持独立语义：修改 Docker restart policy 或 Quadlet 安装状态不能静默启动/停止当前容器。

<a id="linux"></a>

### 3.5 Linux 范围与旧系统兼容边界

**涉及模块与分工：** `shared/model` 表达能力与支持分级，`shared/linux-sshd` 探测和准备，`shared/deploy` 在写入前阻断不匹配目标。

当前代码校准：旧 CentOS 返回 LEGACY_RISK_CONFIRMATION_REQUIRED 风险状态，不能将历史“完整兼容尝试”当作已接通自动准备；具体系统能力不足时停止。发行版适配目标不等于已有实机证据。

- 正式适配目标：Ubuntu 22.04/24.04、CentOS Stream 9/10 的 x86-64。
- CentOS Stream 10 必须检查其实际 CPU 指令集要求；不同 EL10 衍生发行版不得套用同一结论。
- 旧 CentOS 保留分类和停止维护风险识别；当前没有对应的自动环境准备实现，不能声称完整兼容部署。
- 发行版名称、版本、架构、包管理器、systemd、容器和软件源必须实时采集；不能只按名称推断支持。

- 每次展示停止维护、软件源和已知安全风险；不得把 Vault/归档源描述为仍获安全维护。
- 历史兼容设想要求用户明确确认归档源及恢复方案；当前没有对应执行器，本节不授权软件实际更换软件源。
- 将来接通兼容尝试仍须执行完整构建、发布、健康和生命周期验收；环境能力不足时停止并建议迁移或受控容器方案。
- 不为兼容旧系统静默降低 SSH 主机校验、TLS、秘密保护、路径或命令安全标准。

<a id="ai"></a>

### 3.6 多 Provider 与只读 Agent

**涉及模块与分工：** `shared/ai` 提供结构化调用与只读工具，`app/service` 选择 Provider，`app/secret` 短时提供密钥。

- 可以保存多条 OpenAI 兼容 API 记录及默认模型；自动调用只使用用户预先指定的记录，不在失败时静默把数据发送到另一服务。
- API Key 通过平台 `secret` 保存，调用模块只获得短时使用能力，不向界面返回原始值。
- AI 默认提供结构化分析；可选垂直 Agent 只能调用类型化的只读分析和计划工具，不能拥有任意 Shell、直接 SSH、凭据读取或跳过确认的执行权限。
- 多模型协作和角色编排属于三期。

<a id="implementation"></a>

## 4. 实施顺序与依赖

1. 扩展项目事实、支持等级、配置和密钥引用模型。
2. 实现 Git 固定 Commit 与平台只读分析快照。
3. 逐个实现 Java、Node、Python、静态和单容器适配器，每个适配器独立端到端验收。
4. 实现配置快照、密钥修订引用和回滚保留策略。
5. 扩展 Linux 发行版/版本/CPU 能力矩阵和容器生命周期。
6. 实现多 API 与受控 Agent，集中执行安全和失败测试。

<a id="acceptance"></a>

## 5. 验收标准与验证记录

> 历史记录：仅证明下列版本、环境和夹具的结果，不作为当前工作区的新验证。

### Git、配置和密钥

- [x] 分支/Tag 在平台侧固定为 Commit 并产生摘要受限的只读快照；公开 Git 固定 Commit 已由目标机取得同一归档并成功构建、发布和观测。
- [~] 平台主机不执行 Git 项目代码，Hook、符号链接、Submodule、LFS 和 URL 凭据入口已受限；公开无凭据远端已实机通过，私有远端仍待运行环境测试。
- [x] 普通配置以模式版本和摘要不可变存储；SQLite 测试覆盖重复写入与改写拒绝。
- [x] 密钥以标识+修订保存元数据并绑定发布，重复覆盖被拒绝；平台存储与本地负向测试已覆盖，Node.js 实机验收确认运行身份可读取指定修订且公开证据不含秘密原文。

### 项目和容器适配

- [x] 六个项目类型都有类型化静态分析、确定性计划、受控构建、快照、发布、健康、失败恢复和生命周期契约；桌面端可选择类型、提交受限运行时定义和非秘密配置，并先展示确定性计划；六种类型的本地事务、脚本/参数、UI 状态和消息映射测试已通过。
- [x] Java、Node.js/JavaScript/TypeScript、Python 基础语言事实进入源码与部署模型，并可在中英文桌面摘要中显示；混合 JavaScript/TypeScript 不会升级为多组件分析。
- [x] Node 构建型静态站点没有默认版本：精确版本可推导，范围或缺失版本要求人工填写，纯静态站点不要求 Node。
- [x] 迁移前 Gradle Spring Boot、普通 JAR、Node、Python、静态站点和 Dockerfile 容器已在 Ubuntu 24.04 x86-64 由产品入口完成目标机端到端验收；统一 Spring Boot Reviewed/helper v3 也已在该精确目标完成独立端到端验收。
- [x] Docker restart policy 已完成发布、回滚、自启与桌面持久化重开后的生命周期验收；Podman Quadlet 也已在 Ubuntu 24.04 x86-64 完成部署、HTTP、回滚、生命周期与自启验收。
- [x] 容器规格没有 privileged、Docker socket、host PID/IPC 或任意挂载字段，Docker 计划要求显式守护进程风险确认。
- [x] 有界源码检查会拒绝 schema 脚本、迁移目录和 Flyway、Liquibase、Alembic、Prisma、Knex 等自动数据库变更信号；不可逆数据格式变更仍明确指向四期。

### Linux 与旧系统

- [~] Ubuntu 24.04 x86-64 与当时 Reviewed 链路、CentOS Stream 9 x86-64 的环境准备/发布/回滚/生命周期均已由产品入口实机验收；Ubuntu 22.04 与 CentOS Stream 10 仍为 `RUNTIME-PENDING`，CentOS Stream 10 继续要求逐机 x86-64-v3 运行时能力审阅。
- [~] 旧版 CentOS 进入独立风险状态；软件源变更和恢复尚未连接真实主机验证。
- [x] 不支持的架构、包管理器、缺失容器能力或未完成 CPU 审阅会返回保守状态，不能进入运行环境验证。

### AI 与架构

- [x] 命名 Provider 只按指定标识调用，缺失时不回退；API Key 不可回读；AI 只接受 `DeploymentProjectFacts` 并发送脱敏结构化事实；Agent 工具面只含有界分析和计划，未含 Shell/SSH/凭据读取。
- [x] 所有新增代码位于既有 Maven 叶子模块与 `File.md` 规定的包结构内，未新增模块或循环依赖。

### 项目、Git、容器与系统回归证据

> 历史记录：仅证明下列版本、环境和夹具的结果，不作为当前工作区的新验证。

- 已完成本地实现与负向测试：Git 分支/Tag 固定 Commit、禁用 Hook、拒绝未经支持的 Submodule/LFS 和 URL 凭据；桌面端可选择本地目录或无凭据网络 Git 来源，Git 来源的 URI、Commit 和归档摘要会绑定进发布请求；六种类型的有界静态识别、冲突/缺失输入与确定性计划，以及唯一且受支持的 Java/Node/Python/静态/容器运行时建议；桌面端只在源码分析后回填这些建议，允许人工覆核和修改，填写受限结构化运行时/非秘密配置/秘密修订引用，审阅确定性计划并以已保存凭据提交；SQLite v5 保存不可变普通配置、秘密修订绑定和发布身份摘要，并无损迁移 v4 旧值；Linux 事实采集与 Ubuntu、CentOS Stream、旧 CentOS 的保守矩阵；Ubuntu 22.04/24.04 与 CentOS Stream 9/10 的固定环境准备脚本；Docker/Podman 不可混淆的自启契约；命名 Provider 与仅含分析/计划工具的可选 Agent 表面。
- 六种项目类型均已有受控目标机构建、发布、快照、回滚、健康和生命周期代码：Node 的 npm/pnpm/yarn 与锁文件保持一致，Python 仅在候选目录创建虚拟环境，静态站点仅暴露已审阅的产物目录，容器使用受管镜像标签及 Docker restart policy 或 Podman Quadlet。发布快照保存旧的运行参数与自启状态，回滚不得复用新版本配置。
- 基础语言事实已统一进入 `DeploymentProjectFacts`：只从有界源码路径、JAR Manifest、`package.json`/`tsconfig`、`pyproject.toml` 和扩展名收集确定性证据，不读取任意二进制、不执行源码、不猜测主要语言。JavaScript 与 TypeScript 可同时显示，但不会自动拆成多组件，也不会替代用户显式项目类型选择。
- Node 构建型静态站点不再具有隐藏默认版本：只有精确 `engines.node` 才能回填主版本，范围或缺失值必须由用户填写；纯静态站点不要求且不得携带 Node 主版本。
- 2026-08-12 使用 JDK 21 执行 `mvn.cmd -B -ntp -o verify`，28 个 Maven 模块全部成功；该次生成的 44 份 Surefire 报告共 160 项测试，0 失败、0 错误、2 项因当时平台能力跳过。前端另行完成离线 `npm.cmd ci`、类型检查、Vitest（1 项）、生产构建和项目本地 Chromium Playwright（1 项）。`git diff --check`、生产源码期数命名与旧大类扫描、无隐藏 Node 20 默认值边界、435 个中英文消息键及非空值边界、包结构与 `File.md` 一致性、helper 固定 SHA-256 均通过。
- 迁移前实机验收使用新装 Ubuntu 24.04 x86-64，并严格从当时的 `DesktopApplicationService` 与 Apache SSHD 网关进入：本地普通 JAR、Node.js、Python、纯静态站点、Dockerfile 容器，以及公开 Git 固定 Commit 的 Gradle Spring Boot 均完成分析、归档、目标机构建、发布、健康和远端观测。该证据不证明后续统一链路。
- 2026-08-13 当时产品入口在同一精确 Ubuntu 24.04 x86-64 目标完成统一 Spring Boot Reviewed/helper v3 的环境准备、发布、健康、失败恢复、回滚、生命周期、Wrapper、资源限制、归属安全和主机信任验收；Podman Quadlet 亦完成部署、HTTP、回滚、生命周期和自启验收。Ubuntu 22.04、CentOS Stream 9/10、私有 Git 凭据及其他发行版仍为 `RUNTIME-PENDING`，不能从该证据外推。
- 2026-08-14 当时 CentOS Stream 9 目标由产品入口识别为 x86-64-v3；经用户授权的固定测试环境引导后，产品入口复验 SELinux Enforcing。准备回归随即暴露并修复准备脚本仍要求可省略 `VARIANT_ID` 的缺口；后续目标在 SSH 密钥交换前主动关闭连接，无法继续确认准备、发布、回滚或生命周期，且未使用手工部署替代。该状态不是 CentOS 成功验收；其余发行版实机测试按当时范围延后。

Stream 9 连接中断属于中间失败。后续成功证据已记录于[三期发行版验收](PHASE-3.md#acceptance-linux)，保留失败经过并以该精确目标复验更新汇总，不扩展到其他系统。

<a id="remaining"></a>

## 6. 剩余事项与后续边界

- 多组件、多容器 Compose、跨组件依赖图和部分回滚。
- Go、Rust、.NET、Kotlin、PHP、Ruby及其他高级语言正式支持。
- 多模型协作、数据库一致性、备份、迁移和桌面升级。
- Web 版本、SaaS、多用户、Kubernetes或任意服务器终端。

私有 Git 凭据、Submodule、LFS 未接通；旧 CentOS 没有自动准备和完整部署证据。新版身份隔离后的语言组合结果见四期。

<a id="history"></a>

## 7. 功能演进与历史版本记录

以下按功能归组保留原版本、日期和阶段变化。各行仅表示当时的设计或验收范围；若旧记录无法证明变更期次或版本归属，不能用当前实现反推。

### 阶段范围与开发规范

| 来源 / 版本 | 日期 | 历史变化与证据 |
| --- | --- | --- |
| P2 / 1.1.0-phase2 至 1.7.0-managed-lifecycle | 2026-08-07 | 历史需求，范围已由五期路线重新分配。 |

### 源码与工具链

| 来源 / 版本 | 日期 | 历史变化与证据 |
| --- | --- | --- |
| P2 / 2.2.0-local-execution-contracts | 2026-08-12 | 补齐六类项目的受控目标机构建、发布、快照、回滚、健康和生命周期代码；容器与非容器快照保存旧运行参数和自启状态；接入类型化主机矩阵及 Ubuntu 22.04/24.04、CentOS Stream 9/10 固定环境准备脚本。本地自动化验证完成，真实目标机验收仍为 `RUNTIME-PENDING`。 |

### 配置秘密与数据库

| 来源 / 版本 | 日期 | 历史变化与证据 |
| --- | --- | --- |
| P2 / 2.7.4-centos-stream-acceptance | 2026-08-14 | Stream 9 x86-64 通过产品入口两次环境准备、两组件发布、故障候选整应用回滚、应用/数据库重启和生命周期验收；SELinux 与防火墙态保持验收前观测值，未外推至 Stream 10。 |
| P2 / 2.7.3-centos-stream-recovery-blocked | 2026-08-14 | Stream 9 经授权测试环境引导后已由产品入口复验 SELinux Enforcing；修复准备脚本遗漏的可选 `VARIANT_ID` 条件，并保留失败阶段与标准错误/输出。目标随后在 SSH 密钥交换前关闭连接，完整验收仍为 `RUNTIME-PENDING`。 |
| P2 / 2.6.0-reviewed-runtime-acceptance | 2026-08-12 | 完成从用户选定本地/Git 源码、基础语言事实、审阅计划、不可变配置/秘密输入到完整部署的职责闭环；Ubuntu 24.04 x86-64 已实机验证六类项目、公开 Git 固定 Commit、代表性生命周期和失败回滚，未验收矩阵保持 `RUNTIME-PENDING`。 |
| P2 / 2.5.0-responsibility-boundaries | 2026-08-12 | 补齐 Java、Node.js/JavaScript/TypeScript、Python 基础语言事实和中英文摘要；取消构建型静态站点的 Node 默认版本；按职责拆分分析、桌面页面、SQLite 仓库、Git 快照、六类构建、systemd 与 helper，并增加结构门禁。真实目标机验收仍为 `RUNTIME-PENDING`。 |
| P2 / 2.4.0-reviewed-source-inference | 2026-08-12 | 将无凭据网络 Git 来源接入桌面至发布请求的相同审阅链路，并用 `SourceRevision` 绑定固定 Commit、来源和归档摘要；补齐可审阅的源码运行时建议和桌面回填，去除 Node、Python、Java、静态站点、容器端口及配置表单中的无依据默认值；秘密修订引用不再固定为空。真实目标机验收仍为 `RUNTIME-PENDING`。 |
| P2 / 2.3.0-desktop-typed-workflow | 2026-08-12 | 补齐桌面端六类项目的类型选择、静态分析、安全归档、结构化运行时/非秘密配置、计划审阅和已保存凭据提交；AI 对类型化事实只发送脱敏应用标识、项目类型和固定构建入口。目标机端到端验收仍为 `RUNTIME-PENDING`。 |
| P2 / 2.1.0-phase2-local-implementation | 2026-08-12 | 实现并本地验证 Git 只读快照、六类静态分析/计划、配置与密钥修订、Linux/容器契约和受限 Provider/Agent；所有二期真实运行环境验收保持 `RUNTIME-PENDING`。 |
| P2 / 2.0.0-phase2 | 2026-08-08 | 聚焦部署宽度，加入 Git、配置快照/共享密钥、常用类型、容器和 Ubuntu/CentOS；移除平台本机构建。 |

### 部署与生命周期

| 来源 / 版本 | 日期 | 历史变化与证据 |
| --- | --- | --- |
| P2 / 2.7.2-centos-stream-safety-stop | 2026-08-14 | 修复 CentOS Stream 9/10 的可选 `VARIANT_ID` 识别技术债，并加入精确 CentOS 产品入口验收契约。当前 Stream 9 因 SELinux Disabled 在准备前安全停止，未形成部署成功证据；其他发行版测试按当前范围延后。 |
| P2 / 2.7.1-reviewed-v3-podman-ubuntu-acceptance | 2026-08-13 | 统一 Spring Boot Reviewed/helper v3 与 Podman Quadlet 由产品入口在 Ubuntu 24.04 x86-64 完成独立验收；未运行的 Ubuntu、CentOS 与其他发行版矩阵仍为 `RUNTIME-PENDING`。 |

### 备份恢复与维护

| 来源 / 版本 | 日期 | 历史变化与证据 |
| --- | --- | --- |
| P2 / 2.7.0-spring-boot-reviewed-convergence | 2026-08-13 | 将 Maven/Gradle Spring Boot 统一为一个 Reviewed 项目类型和三个固定构建入口，接入 helper v2、发布身份 v2 与 SQLite v5；迁移前实机证据保留但不外推，新链路为 `RUNTIME-PENDING`。 |
