# WindowsToLinux 一期开发文档

<a id="navigation"></a>

## 文档信息与导航

- 文档组织：按功能与模块维护，期数表示能力增量；整理日期：2026-09-19。
- 状态：一期最小闭环有历史产品入口验收；现有产品已由后续期数扩展，当前实现核对不等于重跑实机。
- 依据：现行行为以当前代码和适用的运行证据为准；本期引入范围与后期调整分开说明。
- [开发总纲](DEVELOPMENT.md) · [现行结构](../File.md) · [2期](PHASE-2.md) · [3期](PHASE-3.md) · [4期](PHASE-4.md) · [5期](PHASE-5.md) · [6期](PHASE-6.md)

- [1. 本期目标与承接关系](#goals)
- [2. 模块增量总表](#modules)
- [3. 功能开发说明](#features)
  - [3.1 源码、确定性分析与计划](#source)
  - [3.2 目标机构建、发布与健康检查](#deployment)
  - [3.3 受管应用生命周期](#lifecycle)
  - [3.4 基础 AI 建议](#ai)
  - [3.5 桌面入口、凭据与运行布局](#desktop)
- [4. 功能依赖与实现约束](#implementation)
- [5. 验收标准与验证记录](#acceptance)
- [6. 剩余事项与后续边界](#remaining)

<a id="goals"></a>

## 1. 本期目标与承接关系

一期交付 Windows Swing 桌面端的最小可信闭环：用户选择本地源码目录，软件在 Windows 上只做静态读取和打包，通过 SSH/SFTP 将源码发送到 Ubuntu 24.04 目标机，在目标机使用 Maven/Maven Wrapper 构建 Spring Boot 可执行 JAR，以 systemd 完成短停机发布和失败恢复，并管理受管应用的状态、启动、停止、重启和开机自启。

一期只正式支持单组件、单 systemd 服务、单个 Spring Boot 可执行 JAR。AI 只提供一个 OpenAI 兼容 API 的基础结构化分析，未配置或失败时必须回到确定性流程。

一期从空白产品建立单组件基线；后续项目类型、自动编排和身份隔离增强分别见二至四期。

<a id="modules"></a>

## 2. 模块增量总表

下表按当前模块职责回溯一期功能归属，不表示各模块或包名在一期已经以现名存在；独立拆包的发生期次无法由现状倒推。

| 模块 | 已有基础 | 变化类型 | 本期具体增量 | 功能入口 |
| --- | --- | --- | --- | --- |
| `shared/model` | 无部署领域基线 | 新增 | 项目、健康、受管身份与生命周期契约 | [源码与计划](#source) |
| `shared/analyze` | 无静态分析链 | 新增 | Maven Spring Boot 识别与阻断条件 | [源码与计划](#source) |
| `shared/source` | 静态读取需求 | 新增 | 可重复归档及路径、秘密排除边界 | [源码与计划](#source) |
| `shared/linux` | 无远程契约 | 新增 | SSH、能力、构建发布与生命周期窄契约 | [目标机部署](#deployment) |
| `shared/linux-sshd` | 远程实现需求 | 新增 | Ubuntu、SFTP、受控 helper 与 systemd 实现 | [目标机部署](#deployment) |
| `shared/deploy` | 领域事实与远程契约 | 新增 | 计划、短停机事务、健康与失败恢复 | [目标机部署](#deployment) |
| `shared/ai` | 确定性分析 | 新增 | 单 Provider 结构化建议 | [基础 AI](#ai) |
| `app/db` | 无本地状态 | 新增 | 服务器与受管应用持久化 | [应用管理](#lifecycle) |
| `app/secret` | 平台认证需求 | 新增 | SSH/AI 凭据存储 | [桌面与凭据](#desktop) |
| `app/service` | 共享能力 | 新增 | 桌面用例编排与状态登记 | [目标机部署](#deployment) |
| `app/ui` | 桌面服务 | 新增 | Swing 输入、结果与受管操作 | [桌面与凭据](#desktop) |
| `app/windows` | Windows 运行环境 | 新增 | 本地源码准备和固定工作目录 | [桌面与凭据](#desktop) |
| `app/main` | 模块骨架 | 新增 | CLASS/JAR/APP 启动装配 | [桌面与凭据](#desktop) |

<a id="features"></a>

## 3. 功能开发说明

一期新增能力如下；后续调整在对应段落明确指出。

<a id="source"></a>

### 3.1 源码、确定性分析与计划

**涉及模块与分工：** `shared/analyze` 产生事实，`shared/source` 归档，`shared/model` 表达输入与边界；`app/service` 组织审阅。

现行入口：[SourcePreparationUseCase.java](../../src/app/service/src/main/java/gold/debug/windowstolinux/app/service/source/SourcePreparationUseCase.java) → [DeploymentAnalysisCoordinator.java](../../src/shared/analyze/src/main/java/gold/debug/windowstolinux/shared/analyze/core/DeploymentAnalysisCoordinator.java)；Windows 仅做静态读取和打包。

#### 必需输入

- 用户主动选择的本地源码目录。
- 目标 Ubuntu 24.04 x86-64 服务器地址、SSH 端口、账号和认证材料。
- 首次连接确认后的 SSH 主机指纹。
- 可确定的 Maven 根目录、构建入口和唯一 Spring Boot 可执行 JAR。
- 明确 HTTP 健康端点，或由用户确认的监听端口。

#### 部署前置检查

部署只能在以下条件全部满足后开始：

1. 源码路径可读取，归档边界明确，未包含父目录、越界链接或特殊设备。
2. 项目能确定为 Maven 或 Maven Wrapper 管理的 Spring Boot 可执行 JAR。
3. 项目不依赖必须由平台注入的外部应用配置或应用密钥。
4. 未检测到 Flyway、Liquibase 或启动期自动结构迁移；无法判断是否会修改数据库结构时按不支持处理。
5. 目标机确认为 Ubuntu 24.04 x86-64，具有 systemd、足够空间和可用 Java 21/安装条件。
6. 历史一期要求逐项目审阅构建身份与提权影响；当前新部署禁止 root 直接构建，必须符合四期身份隔离要求。
7. 健康检查方案完整，不接受“只要进程启动”作为方案。

不满足时必须停止并给出原因、缺失项和对应后续阶段，不能生成猜测命令继续执行。

```text
选择源码
→ 规范化路径并检查归档边界
→ 识别 pom.xml、mvnw、Spring Boot 插件和构建产物
→ 检测外部配置、应用密钥和数据库迁移
→ 生成结构化项目事实、风险和目标机需求
→ 可选调用基础 AI 补充说明
→ 用户确认最终计划
```

- Windows 端不得运行 `mvnw`、Maven 插件、项目脚本或安装项目依赖。
- 默认排除 `.git`、`.idea`、`target`、依赖缓存、日志、私钥、`.env` 和已知凭据文件；检测到可能影响构建的排除项时必须提示而非静默遗漏。
- AI 只接收完成脱敏且确有必要的项目事实，不接收完整私有源码或凭据。

<a id="deployment"></a>

### 3.2 目标机构建、发布与健康检查

**涉及模块与分工：** `shared/deploy` 编排事务，`shared/linux` 定义窄端口，`shared/linux-sshd` 执行受控协议；`app/service` 登记结果。

现行执行入口：[ReviewedDeploymentService.java](../../src/shared/deploy/src/main/java/gold/debug/windowstolinux/shared/deploy/execution/transaction/ReviewedDeploymentService.java)；请求不得使用 `runAsRoot`，新部署管理连接要求 root。

1. 在 WindowsToLinux 受管工作根下创建绑定应用标识和源码摘要的候选目录。
2. SFTP 上传受控源码归档，在候选目录内安全解压并复核文件清单。
3. 清理继承环境和代理凭据，不转发 SSH Agent，不向构建进程暴露保存的 SSH/AI 密钥。
4. 优先使用项目 Maven Wrapper；没有 Wrapper 时使用受控安装或已验证的 Maven。固定执行批处理验证/构建入口，不接受用户任意 Shell。
5. 设置超时、输出上限、进程数、内存和磁盘限制；超限进入失败并保存脱敏证据。
6. 校验只产生一个预期的 Spring Boot 可执行 JAR，记录摘要、Java 版本和构建证据。

现行行为由四期增强：root 仅执行受控管理，普通项目使用临时 DynamicUser 构建并在上传前预留固定容量构建卷。旧 root 直接构建确认不再是当前可选路径；见[四期身份隔离](PHASE-4.md#isolation)。

受管目录至少区分工作候选、不可变发布版本、当前指针和归属清单。当前根路径由 Linux 协议实现固定，详见结构文档，但不得允许用户输入越界绝对路径覆盖系统内容。

```text
候选 JAR 校验完成
→ 记录旧版本、旧 unit 内容摘要、运行状态和自启状态
→ 写入并校验候选发布目录及受管清单
→ 停止旧服务并确认停止
→ 切换 current 指针/受管 unit 到候选版本
→ daemon-reload 并启动候选
→ 分层健康检查
→ 成功则提交；失败则停止候选、恢复旧指针/unit、启动并验证旧版本
```

- 候选构建期间旧版本可以继续运行，但固定端口下不同时启动新旧版本。
- 第一次部署失败时没有旧版本可恢复，只清理本次明确归属的候选并返回失败。
- 候选健康失败而旧版本恢复成功时，结果必须区分“部署失败、回滚成功”。
- 旧版本也无法恢复时进入“需要人工处理”，保留新旧目录、unit、实际状态和诊断证据。
- 当前成功发布目录不可作为工作目录，不可被后续构建或上传直接覆盖。
- 默认保留最近三个成功版本；删除前再次校验受管标识、引用状态和当前指针。

- 明确 HTTP 端点时，检查协议、地址、预期状态码、超时和必要响应约束，并确认响应来自候选进程。
- 没有 HTTP 端点时，用户必须确认端口；系统同时验证 TCP、systemd 主进程归属和稳定观察窗口。
- systemd active、PID 存在或端口开放任何单项均不能独立判定成功。
- 超时、短暂成功后退出、端口被其他进程占用或无法证明进程归属均判定失败。

<a id="lifecycle"></a>

### 3.3 受管应用生命周期

**涉及模块与分工：** `shared/deploy` 编排动作，`shared/linux-sshd` 查询和执行；`app/db` 保存最后观测，`app/service` 返回真实结果。

当前增强：显式 STOP 的 systemd failed 状态收尾及读取失败保护见[四期生命周期修复](PHASE-4.md#lifecycle)。

#### 受管身份与实时状态

成功部署后保存应用标识、服务器标识、发布清单、unit 标识、当前版本、健康方案、业务访问 URL（如适用）、自启状态和最后观测时间。每次显示或修改前必须通过 SSH 查询 systemd 实际状态，并核对 unit、受管目录和清单；SQLite 中的状态只作历史展示。

#### 动作语义

| 动作 | 前置条件 | 成功条件 | 不得附带的变化 |
| --- | --- | --- | --- |
| 启动 | 资源归属有效且当前已停止 | 启动命令成功并通过健康检查 | 不启用自启 |
| 停止 | 资源归属有效 | systemd 与受管进程均确认停止 | 不停用自启 |
| 重启 | 资源归属有效 | 重启并通过健康检查 | 不改变自启 |
| 启用自启 | unit 可受控且归属有效 | 远端复核为 enabled | 不启动当前服务 |
| 停用自启 | unit 可受控且归属有效 | 远端复核为 disabled | 不停止当前服务 |

连接失败、指纹变化、unit 丢失、清单不符、外部修改或结果无法复核时停止操作并显示未知/异常。应用列表不提供远程删除、原始日志控制台、批量动作、任意 unit 管理或自动重建。

<a id="ai"></a>

### 3.4 基础 AI 建议

**涉及模块与分工：** `shared/ai` 执行结构化分析；`app/service` 提供调用入口，`app/secret` 短时提供凭据。

一期只增加一个 OpenAI 兼容 Provider 的辅助分析。输入限定为必要且脱敏的事实；输出不改变支持目录、部署权限或成功判定。未配置、调用失败或结构化响应无效时保留确定性分析结果，不调用任意命令。多 Provider 与只读 Agent 见二期，角色协作见三期，缺项表单见四期。

<a id="desktop"></a>

### 3.5 桌面入口、凭据与运行布局

**涉及模块与分工：** `app/ui` 收集展示；`app/secret` 管理平台凭据，`app/windows` 准备本地工作区；`app/main` 装配，`app/db` 持久化。

- Swing 界面保留部署、已部署应用、服务器、AI 配置和设置五类入口，但不提前冻结页面数量和布局。
- SSH 和 AI 凭据属于平台凭据，仍由 `app/secret` 统一保存；一期“不支持应用密钥”不等于可以明文保存平台凭据。
- 首次使用敏感存储时，用户选择主密码加密模式或 Windows Credential Manager 模式。主密码不得保存；SQLite 只能保存版本化密文和必要元数据。
- 错误、进度、日志、崩溃报告和 AI 上下文不得泄露原始密码、私钥、口令或 API Key。

`app/main` 使用 `RunModeResolver` 解析固定位置；CLASS/JAR 以 `app/db` 的 `DesktopPersistence` 类实际加载位置为锚点，APP 仍以启动器 EXE 为准，不提供系统属性、命令行、注册表或指针文件覆盖：

| 模式 | applicationHome | data |
| --- | --- | --- |
| CLASS | `src/app/db` | `src/app/db/data` |
| JAR | DB 模块 JAR 所在目录 | DB JAR 同级 `data` |
| APP | jpackage EXE/app-image 根目录 | EXE 同级 `data` |

无法识别或目录不可写时停止启动，不回退到当前目录、用户目录或临时目录。jpackage 使用按当前用户安装和可选择安装目录，避免把固定数据位置放到普通用户不可写目录。

<a id="implementation"></a>

## 4. 功能依赖与实现约束

| 功能 | 依赖与约束 |
| --- | --- |
| 源码分析与归档 | 不可变项目事实、固定工作目录和受限静态读取先于部署计划。 |
| 构建与发布 | 受信 SSH、目标能力和源码摘要先于构建；构建产物校验先于短停机切换，健康检查决定提交或恢复。 |
| 桌面与生命周期 | 服务层组合凭据、SQLite 和远程契约；登记的受管身份用于实时状态核验，不用缓存代替远端结果。 |

<a id="acceptance"></a>

## 5. 验收标准与验证记录

下列勾选保留一期及 Reviewed/helper v3 当时的验证结果；不表示当前 helper v7 已重跑相同矩阵。现行身份规则的验收见四期。

> 历史记录：仅证明下列版本、环境和夹具的结果，不作为当前工作区的新验证。

### 工程和分析

- [x] 完整 Maven reactor 使用 JDK 21 通过 `verify`，模块依赖符合 `File.md`。
- [x] CLASS/JAR/APP/UNKNOWN 运行布局及固定 `data` 目录测试通过。
- [x] Windows 端只做静态读取和归档，不执行用户项目代码。
- [x] 只接受可确定的 Maven Spring Boot 可执行 JAR；其余项目给出明确后续阶段。
- [x] 外部应用配置、应用密钥或自动数据库迁移被可靠识别并停止。

### 真实 Ubuntu 24.04 闭环

- [x] 首次主机指纹确认、变化阻断和凭据脱敏已通过真实 SSH 握手验证。
- [x] 一期产品在新装 Ubuntu 24.04 x86-64 上完成环境准备、固定受控工具集安装和幂等能力复核；未以外部 SSH 引导脚本作为证据。
- [x] 目标机 Maven/Maven Wrapper 构建、产物唯一性和资源限制均通过真实验证；复杂 Spring Boot 与标准 Wrapper 成功路径均已完成。
- [x] 固定端口服务已按短停机事务真实发布，未并行启动新旧版本。
- [x] HTTP 健康以及 TCP+进程+稳定窗口两种路径均已有真实成功和失败测试。
- [x] 构建失败、启动失败、健康失败和断连均未覆盖旧版本；回滚结果已被真实区分。
- [x] HTTP 成功结果交付显式业务 URL，并已从桌面环境真实访问；TCP 成功结果交付受管 systemd 启动指令。

### 生命周期与安全

- [x] 状态、启动、停止、重启、启用/停用自启均先验证归属并复核远端结果。
- [x] 启停、自启之间已由真实生命周期演练确认不存在静默联动。
- [x] 外部修改、资源丢失和查询失败已由真实远端演练确认不会使用 SQLite 旧值伪装当时状态。
- [x] root 构建逐项目、逐修订、逐目标机确认与风险提示已由自动化测试覆盖，界面不将其声称为安全沙箱。
- [x] 受限远端契约与自动化测试确认不存在任意 Shell、任意 systemd 管理、远程删除或原始秘密读取入口。

### 产品入口与工程证据

| 功能 | 版本与环境 | 已保留证据及限制 |
| --- | --- | --- |
| Maven/Spring Boot 部署与恢复 | 一期 2.0.6，2026-08-10，Ubuntu 24.04 x86-64 | 由产品完成裸机工具准备与幂等复核、复杂 Spring Boot 和官方 Maven Wrapper 部署、HTTP/TCP 健康、故障回滚、断连恢复、资源限制及受管归属检查。 |
| Reviewed 部署与生命周期 | 一期 2.0.8，2026-08-13，同一精确 Ubuntu 目标，helper v3 | 独立完成主机信任、准备、发布、首次失败恢复、旧版回滚、Wrapper、生命周期及业务 URL 交付；不能由迁移前结果代替。 |
| 源码与制品校验 | 上述一期入口 | Wrapper 需同时存在脚本和 properties；固定 Maven 3.9.12 及分发摘要。拒绝自动数据库结构变更、越界读取及不安全归档；复核 systemd Java 路径和发布前后 JAR 摘要。 |
| 桌面与前端工程 | 一期本地门禁 | JDK 21 Maven verify、前端类型检查、Vitest、构建及 Chromium 流程已有记录；前端当时仅为骨架，不构成五期业务证据。 |

HTTP 成功入口曾由桌面实际 GET 验证返回 200 和业务标记，不能以内网健康地址代替用户访问地址；TCP 方案交付受管服务启动指令。历史 root 构建测试只说明当时的批准边界，现行身份规则与验收以[四期](PHASE-4.md#isolation)为准。

<a id="remaining"></a>

## 6. 剩余事项与后续边界

- Git 来源、Gradle、普通 JAR、Node、Python、静态站点、Docker/Podman。
- 应用外部配置注入、应用密钥、数据库安装、备份或迁移。
- Flyway、Liquibase 或其他启动期数据库结构迁移。
- 多组件、多服务器、零停机、流量代理、集群或滚动更新。
- 远程应用删除、日志控制台、终端、文件管理器和通用运维面板。
- Web 版本、桌面自动升级和卸载管理。

- 现行产品新增身份隔离与工具链绑定后，不能沿用一期历史测试计数证明新版全部通过。
