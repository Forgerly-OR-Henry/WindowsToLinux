# WindowsToLinux 二期工程细化文档

## 文档信息

- 阶段基线版本：`2.7.0-spring-boot-reviewed-convergence`
- 文档结构版本：`2.0.0-roadmap-rebaseline`
- 文档状态：**统一 Spring Boot Reviewed/helper v3 与 Podman Quadlet 已完成当前 Ubuntu 24.04 x86-64 产品入口验收；迁移前 Ubuntu 证据保留，其余主机矩阵待执行**
- 当前实现：本地目录与无凭据网络 Git 来源均进入唯一 Reviewed 分析/计划路径；Spring Boot 由一个项目类型和三种固定构建工具入口表达，发布身份与 SQLite v5 已收敛；当前 Ubuntu 24.04 x86-64 证据不外推到 Ubuntu 22.04、CentOS Stream 9/10 或其他发行版，后者均为 `RUNTIME-PENDING`
- 更新日期：2026-08-13
- 上级文档：[开发总纲](../DEVELOPMENT.md)

## 文档导航

- [项目结构](../File.md)
- [一期](PHASE-1.md)
- [三期](PHASE-3.md)
- [四期](PHASE-4.md)
- [五期](PHASE-5.md)

## 1. 二期目标

二期扩展部署宽度，同时继承一期已经验证的受管身份、目标机构建、健康检查、失败恢复和生命周期规则。新增 Git 来源、应用配置与密钥、更多常用项目类型、Docker/Podman 和更多 Ubuntu/CentOS 版本；不引入多组件编排、数据库一致性、备份迁移或 Web 版。

### 1.1 当前交付状态

- 已完成本地实现与负向测试：Git 分支/Tag 固定 Commit、禁用 Hook、拒绝未经支持的 Submodule/LFS 和 URL 凭据；桌面端可选择本地目录或无凭据网络 Git 来源，Git 来源的 URI、Commit 和归档摘要会绑定进发布请求；六种类型的有界静态识别、冲突/缺失输入与确定性计划，以及唯一且受支持的 Java/Node/Python/静态/容器运行时建议；桌面端只在源码分析后回填这些建议，允许人工覆核和修改，填写受限结构化运行时/非秘密配置/秘密修订引用，审阅确定性计划并以已保存凭据提交；SQLite v5 保存不可变普通配置、秘密修订绑定和发布身份摘要，并无损迁移 v4 旧值；Linux 事实采集与 Ubuntu、CentOS Stream、旧 CentOS 的保守矩阵；Ubuntu 22.04/24.04 与 CentOS Stream 9/10 的固定环境准备脚本；Docker/Podman 不可混淆的自启契约；命名 Provider 与仅含分析/计划工具的可选 Agent 表面。
- 六种项目类型均已有受控目标机构建、发布、快照、回滚、健康和生命周期代码：Node 的 npm/pnpm/yarn 与锁文件保持一致，Python 仅在候选目录创建虚拟环境，静态站点仅暴露已审阅的产物目录，容器使用受管镜像标签及 Docker restart policy 或 Podman Quadlet。发布快照保存旧的运行参数与自启状态，回滚不得复用新版本配置。
- 基础语言事实已统一进入 `DeploymentProjectFacts`：只从有界源码路径、JAR Manifest、`package.json`/`tsconfig`、`pyproject.toml` 和扩展名收集确定性证据，不读取任意二进制、不执行源码、不猜测主要语言。JavaScript 与 TypeScript 可同时显示，但不会自动拆成多组件，也不会替代用户显式项目类型选择。
- Node 构建型静态站点不再具有隐藏默认版本：只有精确 `engines.node` 才能回填主版本，范围或缺失值必须由用户填写；纯静态站点不要求且不得携带 Node 主版本。
- 2026-08-12 使用 JDK 21 执行 `mvn.cmd -B -ntp -o verify`，28 个 Maven 模块全部成功；本次生成的 44 份 Surefire 报告共 160 项测试，0 失败、0 错误、2 项因当前平台能力跳过。前端另行完成离线 `npm.cmd ci`、类型检查、Vitest（1 项）、生产构建和项目本地 Chromium Playwright（1 项）。`git diff --check`、生产源码期数命名与旧大类扫描、无隐藏 Node 20 默认值边界、435 个中英文消息键及非空值边界、包结构与 `File.md` 一致性、helper 固定 SHA-256 均通过。
- 迁移前实机验收使用新装 Ubuntu 24.04 x86-64，并严格从当时的 `DesktopApplicationService` 与 Apache SSHD 网关进入：本地普通 JAR、Node.js、Python、纯静态站点、Dockerfile 容器，以及公开 Git 固定 Commit 的 Gradle Spring Boot 均完成分析、归档、目标机构建、发布、健康和远端观测。该证据不证明后续统一链路。
- 2026-08-13 当前产品入口在同一精确 Ubuntu 24.04 x86-64 目标完成统一 Spring Boot Reviewed/helper v3 的环境准备、发布、健康、失败恢复、回滚、生命周期、Wrapper、资源限制、归属安全和主机信任验收；Podman Quadlet 亦完成部署、HTTP、回滚、生命周期和自启验收。Ubuntu 22.04、CentOS Stream 9/10、私有 Git 凭据及其他发行版仍为 `RUNTIME-PENDING`，不能从该证据外推。

## 2. 支持矩阵

### 2.1 项目类型

| 类型 | 二期最小正式支持条件 |
| --- | --- |
| Spring Boot | Maven 与 Gradle 不得并存；Gradle 使用完整 Wrapper，Maven 使用完整 Wrapper 或目标机系统 Maven；只接受唯一且具有 Spring Boot 2/3 Launcher 的可执行 JAR |
| 普通 Java JAR | Java 版本、主类、启动参数和健康策略都可确定 |
| Node.js | 锁文件、包管理器、构建/启动脚本和监听端口明确 |
| Python | Python 版本、锁定依赖、入口、虚拟环境和健康策略明确 |
| 静态站点 | 构建产物目录确定；构建型站点具有精确或人工确认的 Node 主版本，纯静态站点不要求 Node |
| Dockerfile | 单镜像、单容器、端口/健康/持久化目录明确 |

没有锁文件、入口冲突、需要用户自定义任意命令或无法确定产物的项目只能展示分析结果，不能标记正式支持。Docker Compose 和多容器项目属于三期多组件范围。

### 2.2 Linux 范围

- 正式适配目标：Ubuntu 22.04/24.04、CentOS Stream 9/10 的 x86-64。
- CentOS Stream 10 必须检查其实际 CPU 指令集要求；不同 EL10 衍生发行版不得套用同一结论。
- CentOS Linux 7、CentOS Linux 8、CentOS Stream 8保留完整兼容尝试，但始终展示停止维护风险并使用独立能力矩阵。
- 发行版名称、版本、架构、包管理器、systemd、容器和软件源必须实时采集；不能只按名称推断支持。

## 3. Git 来源

### 3.1 输入和准备

Git 输入包括仓库地址、凭据引用、分支/Tag/Commit、Submodule 和 Git LFS 需求。私有凭据不得写入 URL、命令日志或普通配置。

```text
解析仓库来源
→ 使用受限平台工作区取得只读分析快照
→ 将分支或 Tag 固定为 Commit
→ 静态分析，不执行仓库代码
→ 目标机取得/接收同一 Commit 内容并校验摘要
→ 在目标机执行构建
```

- 桌面或 Web 工作区可以为静态分析读取源码，但不得安装依赖、执行 Hook、构建脚本、Wrapper 或项目二进制。
- 禁用或绕过本地 Git Hook；Submodule 和 LFS 必须受路径、域名、体积和凭据边界控制。
- 发布请求必须保存 Commit、源码摘要和无凭据来源，不得只保存可变化的分支名；Submodule 当前被拒绝，因而不会产生未受控的子模块记录。
- 平台分析快照与目标机构建快照不一致时停止部署。

## 4. 项目事实与计划

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

### 4.1 基础语言事实边界

- `LanguageEcosystem` 只包含 `JAVA`、`NODE_JS`、`PYTHON`；`SourceLanguage` 只包含 `JAVA`、`JAVASCRIPT`、`TYPESCRIPT`、`PYTHON`。
- `.java` 与 JAR Manifest 产生 Java 证据；`package.json`、JavaScript/TypeScript 扩展名与 `tsconfig` 产生 Node 生态及源码语言证据；`pyproject.toml` 与 `.py` 产生 Python 证据。
- 语言事实是确定性集合和 `AnalysisEvidence`，不计算“主要语言”、比例或支持等级，不据此自动改变用户选择的 `DeploymentProjectType`。
- 多语言根、语言支持等级和自动项目类型选择仍属于三期，不在本期提前实现。

## 5. 应用配置与密钥

### 5.1 普通配置快照

- 端口、Profile、启动参数和非敏感环境变量在部署计划中结构化校验。
- 每个发布版本生成不可变普通配置快照，并记录模式版本和摘要；运行时引用该版本快照。
- 回滚恢复旧二进制和旧普通配置快照，避免新版配置格式破坏旧版本。
- 配置值不得扩展为任意 Shell 片段，路径和环境变量名称使用类型化白名单。

### 5.2 共享密钥

- 密钥位于发布版本之外的受限共享目录，由受管应用运行身份最小权限读取；禁止进入普通配置、unit 明文、命令行、日志或归档源码。
- 每个密钥具有稳定标识和不可变修订号。发布清单只引用“标识+修订”，不复制原始密钥到版本目录。
- 轮换创建新修订，不原地覆盖。仍被可回滚版本引用的修订必须保留；无引用且超出回滚窗口后才能受控删除。
- 部署、回滚和启动前验证所有引用存在且权限正确。缺失或无法解密时停止，不回退到空值或最新修订。
- 原始密钥仅在录入或替换时进入 `app/secret`/`web/secret`，保存后界面只显示名称、状态、修订和更新时间。

## 6. 目标机构建与运行适配

所有类型继续只在目标 Linux 构建，并继承一期 root 直接构建的逐项目、逐修订、逐主机确认和风险说明。各适配器只能调用固定工具入口，必须限制资源、清理继承环境并验证产物。进入事务前会采集发行版、架构、包管理器、systemd、容器、Quadlet 和 CPU 事实；不匹配矩阵时在上传前拒绝。

- Java：Maven/Gradle Wrapper优先，普通 JAR 必须固定主类和 JVM 参数结构，不能接受一段任意启动命令。
- Node.js：锁定 npm/pnpm/yarn 类型和锁文件；生产依赖安装与构建在候选目录完成。
- Python：使用候选版本专属虚拟环境和锁定依赖；不得污染系统 Python。
- 静态站点：只发布分析确定的产物目录，禁止把源码根目录直接暴露；Node 构建型站点必须验证审阅的显式主版本，纯静态站点不启动 Node 构建。
- Docker：构建单镜像，使用受管标签/摘要和显式卷、端口、健康、用户配置；自启通过受控 restart policy 实现并复核。
- Podman：优先使用 Quadlet 描述受管容器，在 `[Install]` 配置目标；不得对生成的临时 service 错误执行普通 `systemctl enable` 并宣称自启成功。

容器内 root 不等于宿主 root，但 Docker daemon 权限本身接近宿主高权限，必须单独确认。禁止 privileged、宿主根目录挂载、Docker socket 挂载、host PID/IPC 和未审查设备访问。

## 7. 发布、回滚与生命周期

- systemd 类型继续使用一期短停机事务；发布目录保存 root 所有的运行参数，快照同时保存旧 unit、运行参数、运行状态和自启状态。
- 单容器发布先构建并校验镜像，再停止旧容器、以受管定义启动候选、健康检查；失败时恢复旧镜像摘要、端口/卷定义和自启状态，不复用候选版本参数。
- 持久化卷和外部数据不随容器版本覆盖；涉及不可逆数据格式变更时停止并提示四期数据库/备份能力。
- 新增部署类型成功后进入同一受管列表，提供状态、启动、停止、重启和自启，不提供删除、通用日志或任意容器管理。
- 自启动作保持独立语义：修改 Docker restart policy 或 Quadlet 安装状态不能静默启动/停止当前容器。

## 8. 多 API 与可选 Agent

- 可以保存多条 OpenAI 兼容 API 记录及默认模型；自动调用只使用用户预先指定的记录，不在失败时静默把数据发送到另一服务。
- API Key 通过平台 `secret` 保存，调用模块只获得短时使用能力，不向界面返回原始值。
- AI 默认提供结构化分析；可选垂直 Agent 只能调用类型化的只读分析和计划工具，不能拥有任意 Shell、直接 SSH、凭据读取或跳过确认的执行权限。
- 多模型协作和角色编排属于三期。

## 9. 旧版 CentOS 兼容模式

- 每次展示停止维护、软件源和已知安全风险；不得把 Vault/归档源描述为仍获安全维护。
- 只有用户明确确认后才可调整到指定归档源，且变更内容、备份和恢复方法必须进入计划。
- 兼容尝试仍执行完整构建、发布、健康和生命周期验收；环境能力不足时停止并建议迁移或受控容器方案。
- 不为兼容旧系统静默降低 SSH 主机校验、TLS、秘密保护、路径或命令安全标准。

## 10. 实施顺序

1. 扩展项目事实、支持等级、配置和密钥引用模型。
2. 实现 Git 固定 Commit 与平台只读分析快照。
3. 逐个实现 Java、Node、Python、静态和单容器适配器，每个适配器独立端到端验收。
4. 实现配置快照、密钥修订引用和回滚保留策略。
5. 扩展 Linux 发行版/版本/CPU 能力矩阵和容器生命周期。
6. 实现多 API 与受控 Agent，集中执行安全和失败测试。

## 11. 二期不包含

- 多组件、多容器 Compose、跨组件依赖图和部分回滚。
- Go、Rust、.NET、Kotlin、PHP、Ruby及其他高级语言正式支持。
- 多模型协作、数据库一致性、备份、迁移和桌面升级。
- Web 版本、SaaS、多用户、Kubernetes或任意服务器终端。

## 12. 验收标准

### 12.1 Git、配置和密钥

- [x] 分支/Tag 在平台侧固定为 Commit 并产生摘要受限的只读快照；公开 Git 固定 Commit 已由目标机取得同一归档并成功构建、发布和观测。
- [~] 平台主机不执行 Git 项目代码，Hook、符号链接、Submodule、LFS 和 URL 凭据入口已受限；公开无凭据远端已实机通过，私有远端仍待运行环境测试。
- [x] 普通配置以模式版本和摘要不可变存储；SQLite 测试覆盖重复写入与改写拒绝。
- [x] 密钥以标识+修订保存元数据并绑定发布，重复覆盖被拒绝；平台存储与本地负向测试已覆盖，Node.js 实机验收确认运行身份可读取指定修订且公开证据不含秘密原文。

### 12.2 项目和容器适配

- [x] 六个项目类型都有类型化静态分析、确定性计划、受控构建、快照、发布、健康、失败恢复和生命周期契约；桌面端可选择类型、提交受限运行时定义和非秘密配置，并先展示确定性计划；六种类型的本地事务、脚本/参数、UI 状态和消息映射测试已通过。
- [x] Java、Node.js/JavaScript/TypeScript、Python 基础语言事实进入源码与部署模型，并可在中英文桌面摘要中显示；混合 JavaScript/TypeScript 不会升级为多组件分析。
- [x] Node 构建型静态站点没有默认版本：精确版本可推导，范围或缺失版本要求人工填写，纯静态站点不要求 Node。
- [x] 迁移前 Gradle Spring Boot、普通 JAR、Node、Python、静态站点和 Dockerfile 容器已在 Ubuntu 24.04 x86-64 由产品入口完成目标机端到端验收；统一 Spring Boot Reviewed/helper v3 也已在该精确目标完成独立端到端验收。
- [x] Docker restart policy 已完成发布、回滚、自启与桌面持久化重开后的生命周期验收；Podman Quadlet 也已在 Ubuntu 24.04 x86-64 完成部署、HTTP、回滚、生命周期与自启验收。
- [x] 容器规格没有 privileged、Docker socket、host PID/IPC 或任意挂载字段，Docker 计划要求显式守护进程风险确认。
- [x] 有界源码检查会拒绝 schema 脚本、迁移目录和 Flyway、Liquibase、Alembic、Prisma、Knex 等自动数据库变更信号；不可逆数据格式变更仍明确指向四期。

### 12.3 Linux 与旧系统

- [~] Ubuntu 24.04 x86-64 与 CPU/运行时事实已实机验收；Ubuntu 22.04、CentOS Stream 9/10 仍为 `RUNTIME-PENDING`，CentOS Stream 10 继续要求逐机 x86-64-v3 运行时能力审阅。
- [~] 旧版 CentOS 进入独立风险状态；软件源变更和恢复尚未连接真实主机验证。
- [x] 不支持的架构、包管理器、缺失容器能力或未完成 CPU 审阅会返回保守状态，不能进入运行环境验证。

### 12.4 AI 与架构

- [x] 命名 Provider 只按指定标识调用，缺失时不回退；API Key 不可回读；AI 只接受 `DeploymentProjectFacts` 并发送脱敏结构化事实；Agent 工具面只含有界分析和计划，未含 Shell/SSH/凭据读取。
- [x] 所有新增代码位于既有 Maven 叶子模块与 `File.md` 规定的包结构内，未新增模块或循环依赖。

## 13. 版本记录

| 版本 | 日期 | 说明 |
| --- | --- | --- |
| 2.7.1-reviewed-v3-podman-ubuntu-acceptance | 2026-08-13 | 统一 Spring Boot Reviewed/helper v3 与 Podman Quadlet 由产品入口在 Ubuntu 24.04 x86-64 完成独立验收；未运行的 Ubuntu、CentOS 与其他发行版矩阵仍为 `RUNTIME-PENDING`。 |
| 2.7.0-spring-boot-reviewed-convergence | 2026-08-13 | 将 Maven/Gradle Spring Boot 统一为一个 Reviewed 项目类型和三个固定构建入口，接入 helper v2、发布身份 v2 与 SQLite v5；迁移前实机证据保留但不外推，新链路为 `RUNTIME-PENDING`。 |
| 2.6.0-reviewed-runtime-acceptance | 2026-08-12 | 完成从用户选定本地/Git 源码、基础语言事实、审阅计划、不可变配置/秘密输入到完整部署的职责闭环；Ubuntu 24.04 x86-64 已实机验证六类项目、公开 Git 固定 Commit、代表性生命周期和失败回滚，未验收矩阵保持 `RUNTIME-PENDING`。 |
| 2.5.0-responsibility-boundaries | 2026-08-12 | 补齐 Java、Node.js/JavaScript/TypeScript、Python 基础语言事实和中英文摘要；取消构建型静态站点的 Node 默认版本；按职责拆分分析、桌面页面、SQLite 仓库、Git 快照、六类构建、systemd 与 helper，并增加结构门禁。真实目标机验收仍为 `RUNTIME-PENDING`。 |
| 2.4.0-reviewed-source-inference | 2026-08-12 | 将无凭据网络 Git 来源接入桌面至发布请求的相同审阅链路，并用 `SourceRevision` 绑定固定 Commit、来源和归档摘要；补齐可审阅的源码运行时建议和桌面回填，去除 Node、Python、Java、静态站点、容器端口及配置表单中的无依据默认值；秘密修订引用不再固定为空。真实目标机验收仍为 `RUNTIME-PENDING`。 |
| 2.3.0-desktop-typed-workflow | 2026-08-12 | 补齐桌面端六类项目的类型选择、静态分析、安全归档、结构化运行时/非秘密配置、计划审阅和已保存凭据提交；AI 对类型化事实只发送脱敏应用标识、项目类型和固定构建入口。目标机端到端验收仍为 `RUNTIME-PENDING`。 |
| 2.2.0-local-execution-contracts | 2026-08-12 | 补齐六类项目的受控目标机构建、发布、快照、回滚、健康和生命周期代码；容器与非容器快照保存旧运行参数和自启状态；接入类型化主机矩阵及 Ubuntu 22.04/24.04、CentOS Stream 9/10 固定环境准备脚本。本地自动化验证完成，真实目标机验收仍为 `RUNTIME-PENDING`。 |
| 2.1.0-phase2-local-implementation | 2026-08-12 | 实现并本地验证 Git 只读快照、六类静态分析/计划、配置与密钥修订、Linux/容器契约和受限 Provider/Agent；所有二期真实运行环境验收保持 `RUNTIME-PENDING`。 |
| 2.0.0-phase2 | 2026-08-08 | 聚焦部署宽度，加入 Git、配置快照/共享密钥、常用类型、容器和 Ubuntu/CentOS；移除平台本机构建。 |
| 1.1.0-phase2 至 1.7.0-managed-lifecycle | 2026-08-07 | 历史需求，范围已由五期路线重新分配。 |
