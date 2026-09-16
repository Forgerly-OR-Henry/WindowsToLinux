# WindowsToLinux 三期开发文档

<a id="navigation"></a>

## 文档信息与导航

- 文档结构版本：`3.0.0-functional-phases`；整理日期：2026-09-10。
- 状态：生态构建、多组件和多模型已接入；历史 helper v3 验收限定精确夹具。后续 v5/v7 回归及动态工具链增强归四期。
- 依据：现行行为以当前代码和适用的运行证据为准；本期引入范围与后期调整分开说明。
- [开发总纲](DEVELOPMENT.md) · [现行结构](../File.md) · [1期](PHASE-1.md) · [2期](PHASE-2.md) · [4期](PHASE-4.md) · [5期](PHASE-5.md) · [6期](PHASE-6.md)

- [1. 本期目标与承接关系](#goals)
- [2. 模块增量总表](#modules)
- [3. 功能开发说明](#features)
  - [3.1 语言范围、构建架构与支持分级](#ecosystem)
  - [3.2 混合项目分析与组件审阅](#components)
  - [3.3 多组件部署事务与生命周期](#transaction)
  - [3.4 多模型协作](#ai)
  - [3.5 发行版能力与环境准备](#linux)
  - [3.6 分析与部署链职责演进](#architecture)
- [4. 实施顺序与依赖](#implementation)
- [5. 验收标准与验证记录](#acceptance)
  - [生态构建自动化与入口](#acceptance-ecosystem)
  - [高级语言、整应用与发行版实机证据](#acceptance-linux)
- [6. 剩余事项与后续边界](#remaining)
- [7. 结构与生态演进明细](#architecture-history)
- [8. 功能演进与历史版本记录](#history)

<a id="goals"></a>

## 1. 本期目标与承接关系

三期处理高级语言、混合项目、多组件生命周期和多模型协作。它扩展的是适配器和编排能力，不改变目标机构建、受管身份、短停机、健康检查、秘密保护和失败状态基线。

三期采用分级支持：识别到语言或生成计划不等于可部署；只有端到端验收完成的语言/框架/发行版组合才是正式支持。

三期生态补全遵守“原生架构优先，扩展架构后置”：每种已经存在部署路径的语言，必须先具有一个不依赖第三方框架的受控构建基线，再新增该语言的其他构建工具或框架路径。

生态补全部分只补全语言与构建架构，不改变模块边界、发行版分类、工作负载分类、运行机制、数据库、AI、备份迁移或 Web 范围。

<a id="modules"></a>

## 2. 模块增量总表

| 模块 | 已有基础 | 变化类型 | 本期具体增量 | 功能入口 |
| --- | --- | --- | --- | --- |
| `shared/model` | 二期项目与配置模型 | 增强 | 精确构建架构、分级支持、组件图和角色事实 | [生态构建](#ecosystem) |
| `shared/analyze` | 基础语言分析 | 增强 | 高级语言、原生构建、组件冲突及依赖分析 | [混合项目](#components) |
| `shared/deploy` | 单组件事务 | 增强 | 整应用构建/切换/健康/回滚与依赖生命周期 | [多组件事务](#transaction) |
| `shared/linux` | 单组件远程边界 | 增强 | 多组件事务与高级运行类型契约 | [多组件事务](#transaction) |
| `shared/linux-sshd` | 二期语言和容器 | 增强 | 原生构建 Renderer、更多发行版与运行协议 | [生态构建](#ecosystem) |
| `shared/ai` | 多 Provider 与只读 Agent | 增强 | 三个固定角色、最小上下文和冲突裁决 | [多模型协作](#ai) |
| `app/db` | 配置与单应用身份 | 增强 | 角色分配和成功整应用图持久化 | [多组件事务](#transaction) |
| `app/service` | 单组件桌面用例 | 增强 | 组件审阅、整应用部署及生命周期入口 | [多组件事务](#transaction) |
| `app/ui` | 单组件和 Provider 表单 | 增强 | 组件图、运行时审阅和角色配置 | [混合项目](#components) |
| `app/main` | 共享能力装配 | 增强 | 生态注册与跨模块验收入口 | [职责演进](#architecture) |

结构调整同时涉及 `shared/analyze`、`shared/deploy`、`shared/linux` 与 `shared/linux-sshd`，具体迁移映射见本期附录。

<a id="features"></a>

## 3. 功能开发说明

当前包结构以代码核对后的结构文档为准；本期历史设计不定义四期的新身份或工具链策略。

<a id="ecosystem"></a>

### 3.1 语言范围、构建架构与支持分级

**涉及模块与分工：** `shared/model` 定义支持目录与构建身份；`shared/analyze` 提取架构事实；`shared/linux-sshd` 完成探测、受控构建和制品校验。

代码依据：[DeploymentSupportCatalog.java](../../src/shared/model/src/main/java/gold/debug/windowstolinux/shared/model/project/DeploymentSupportCatalog.java)、[DeploymentAdapterRegistry.java](../../src/shared/deploy/src/main/java/gold/debug/windowstolinux/shared/deploy/extension/registry/DeploymentAdapterRegistry.java)。当前 Spring Boot 的 Maven/Gradle、纯 JDK、六类高级语言和 CMake 仍标为试验适配；预构建 JAR、npm、pip、静态站点和单 Dockerfile 容器的正式标记只覆盖目录列明的历史 Ubuntu 矩阵。工具链目录准入与真实运行证据是不同维度。三期原生 JDK 基线是 Java 21；四期扩展 JDK 8 等版本后使用相应原生参数，不再把 `--release 21` 当成全版本命令。

| 等级 | 可以提供 | 禁止声称 |
| --- | --- | --- |
| 未识别 | 安全停止、收集最小事实 | 已分析、可部署 |
| 识别预览 | 展示语言、构建系统、可能入口、缺失信息和计划预览 | 自动安装、构建、发布或正式支持 |
| 试验适配 | 用户明确确认后在专用测试环境执行，保留全部证据 | 生产可用、兼容所有框架 |
| 正式支持 | 在声明的语言/框架/发行版/架构矩阵内完成全流程 | 超出矩阵的泛化支持 |

升级为正式支持必须同时通过：版本识别、锁定依赖、目标机构建、产物验证、启动、健康检查、短停机切换、失败恢复、生命周期、秘密脱敏和真实 Linux 验收。

#### 高级语言适配

- Go
- Rust
- .NET
- Kotlin（非二期已支持的普通 Java 兼容路径）
- PHP
- Ruby

#### 长尾识别与预览边界

- C/C++（初始为预览；本期生态补全后，受限 CMake 服务路径升级为试验适配，其他形态仍预览）
- Scala、Clojure
- Elixir
- Dart
- Lua、Perl
- Swift
- Shell 项目

上述清单是适配路线，不是一次性正式支持承诺。每个适配器必须声明语言版本、构建工具、锁文件、产物、运行身份、健康方式、配置/密钥接口、支持发行版和不支持特性。Shell 项目不得因为存在脚本就获得任意命令执行入口。

| 术语 | 定义 |
| --- | --- |
| 语言生态 | Java、Node、Python、Go、Rust、DotNet、Kotlin、PHP、Ruby、C/C++ 等语言相关实现的稳定归属。 |
| 原生架构 | 该语言官方工具链或事实上的基础工具链，可以在不引入应用框架的前提下完成受控构建或直接运行。 |
| 扩展架构 | Maven、Gradle、pnpm、Yarn、Poetry、Composer、Bundler 等在原生基线之上的依赖、构建或框架路径。 |
| 交付架构 | 接收已经产生的制品并验证、发布和运行，不负责从源码编译该制品。 |
| 架构包 | `analyze.ecosystem.<language>.<architecture>` 中以工具或架构规范名命名的包。 |

1. 每个分析构建架构必须使用自身规范名包，不得把多个工具隐藏在无名 `build` 包或一个参数化大类中。
2. 每个已有语言生态的纯语言识别统一放在语言根包；跨架构公共事实、选择器和框架协调器也留在语言根包，架构专属事实与检查进入架构包。语言识别器不得依赖构建架构解析。
3. 同一语言尚未完成原生架构时，不新增该语言的其他扩展架构。
4. `jar` 是 Java 的原生制品交付架构，但不是纯 Java 源码构建架构；纯源码必须由 `jdk` 架构使用受控 `javac` 与 `jar` 完成。
5. 架构名称固定使用全小写规范名：`bundler`、`cargo`、`cmake`、`composer`、`dotnetsdk`、`gradle`、`gomodule`、`jar`、`jdk`、`kotlinc`、`maven`、`npm`、`phpcli`、`pip`、`pipenv`、`pnpm`、`poetry`、`rubycli`、`uv`、`yarn`。
6. C 与 C++ 统一归 `ecosystem.c`，首个构建架构为 `cmake`；C++ 是独立语言能力，不通过 Java 类型继承表达。
7. 架构支持必须同时贯通静态分析、类型化模型、目标机构建、能力探测、环境准备、制品验证、运行计划、失败恢复和证据记录；只增加枚举或目录不算完成。
8. APT/DNF 包名只进入 `distro`，语言命令和版本判断只进入 `capability.ecosystem`，不得建立语言与发行版组合包。
9. 新架构默认从识别预览或试验适配开始；本地测试和静态门禁不得升级真实运行支持声明。

#### 构建工具身份

`DeploymentBuildToolType` 按原子迁移增加或细化以下身份；不保留旧名称别名：

| 生态 | 目标身份 |
| --- | --- |
| Java | `JDK`；保留 `JAVA` 表示预构建 JAR 交付，保留 Maven/Gradle 身份 |
| Kotlin | `KOTLINC`；保留 `GRADLE_KOTLIN_WRAPPER` |
| Node | 保留 `NPM`、`PNPM`、`YARN` |
| PHP | `PHP_CLI`；保留 `COMPOSER_LOCKED` |
| Python | 以 `PIP_LOCKED`、`PIPENV_LOCKED`、`POETRY_LOCKED`、`UV_LOCKED` 替换宽泛 `PYTHON_VENV` |
| Ruby | `RUBY_CLI`；保留 `BUNDLER_LOCKED` |
| C/C++ | `CMAKE` |

#### 项目类型

- 新增纯 Java 源码项目类型，不复用 `JAVA_JAR`；两者的输入、构建责任和证据不同。
- Kotlin、PHP、Ruby 和 Python 继续使用各自语言服务类型，由构建工具身份选择架构。
- CMake 使用独立项目类型，初始只允许一个经审阅的服务可执行文件；库、多二进制和安装脚本留在识别预览。
- 支持目录必须逐项描述语言、架构、框架、目标发行版、CPU 架构和证据，不以语言枚举值推导支持等级。

| 架构 | 静态输入 | 受控构建或运行 | 合格制品 |
| --- | --- | --- | --- |
| `jdk` | 显式源码根、唯一主类、固定 Java 版本；首版禁止外部依赖和注解处理器 | `javac --release 21` 后使用 JDK `jar` 生成可执行 JAR | 单一可执行 JAR、确定清单和主类 |
| `npm` | `package.json`、唯一 `package-lock.json`、精确 Node 主版本和固定脚本名 | `npm ci`，只调用经审阅的固定 build/start 入口 | 受审阅 Node 服务目录 |
| `pip` | `pyproject.toml`、唯一 `requirements.lock`、全部依赖哈希和精确 Python 次版本 | 隔离 venv 与 `pip --require-hashes` | 无外部符号链接的项目 venv |
| `gomodule` | `go.mod`、`go.sum`、唯一 main package | 只读模块模式构建 | 单一 ELF 可执行文件 |
| `cargo` | `Cargo.toml`、`Cargo.lock`、唯一 binary target | `cargo build --locked` | 单一 ELF 可执行文件 |
| `dotnetsdk` | 唯一项目文件、锁文件、精确目标框架 | locked restore 与受控 publish | 单一发布目录和固定入口 |
| `kotlinc` | Kotlin 源码根、唯一主入口、精确 JVM 目标；首版禁止外部依赖 | 固定 `kotlinc` 编译并生成可运行 JAR | 单一 Kotlin/JVM 可执行 JAR |
| `phpcli` | 显式入口和文档根；首版禁止 Composer 依赖 | PHP CLI 语法检查与受控服务入口 | 受审阅 PHP 源码目录 |
| `rubycli` | 显式入口和精确 Ruby 版本；首版禁止 Gem 依赖 | Ruby 语法检查与受控服务入口 | 受审阅 Ruby 源码目录 |
| `cmake` | `CMakeLists.txt`、固定 preset、唯一目标；首版禁止下载依赖和自定义安装脚本 | `cmake` configure/build，生成器和编译器来自受审阅能力事实 | 单一 ELF 可执行文件及动态依赖清单 |

受控架构拒绝任意自定义 Shell、未批准的网络取材、宿主特权、未固定依赖、越界源码路径和不确定制品；锁定依赖的受控安装与官方工具链下载不能被笼统称为禁止的构建期网络访问。原生 JDK/kotlinc 等零依赖架构仍保持其独立限制。

<a id="components"></a>

### 3.2 混合项目分析与组件审阅

**涉及模块与分工：** `shared/analyze` 发现组件和依赖，`shared/model` 保存稳定身份与冲突；`app/service`、`app/ui` 组织审阅。

代码入口：[ProjectComponentDiscovery.java](../../src/shared/analyze/src/main/java/gold/debug/windowstolinux/shared/analyze/component/ProjectComponentDiscovery.java)、[MultiComponentDeploymentPlanner.java](../../src/shared/deploy/src/main/java/gold/debug/windowstolinux/shared/deploy/plan/MultiComponentDeploymentPlanner.java)；多组件不等于任意 Docker Compose 自动执行。

#### 输入

输入可以包含多个语言根、多个构建文件、多个服务声明和共享资源。分析输出必须是结构化组件清单，而不是选择“主要语言”后忽略其余内容。

每个组件至少具有：稳定组件标识、源码根、语言/框架、构建入口、产物、运行方式、端口、健康策略、配置快照、密钥引用、数据路径、依赖组件和支持等级。

#### 冲突和停止条件

- 组件根重叠且会互相覆盖产物。
- 端口冲突、循环依赖或启动顺序不确定。
- 共享数据具有不兼容写入者或不可逆格式变化。
- 任何必需组件仅达到识别预览而用户请求正式部署。
- 组件需要任意自定义 Shell、宿主特权或未受控网络/设备权限。

发现以上情况时停止修改目标环境并给出组件级原因。AI 可以解释冲突，但不能擅自删组件、改端口或生成越权命令。

<a id="transaction"></a>

### 3.3 多组件部署事务与生命周期

**涉及模块与分工：** `shared/deploy` 编排整应用事务及依赖顺序；Linux 端执行受管步骤；`app/db` 保存成功图，服务层重载和管理。

现行入口：[MultiComponentDeploymentUseCase.java](../../src/app/service/src/main/java/gold/debug/windowstolinux/app/service/deployment/MultiComponentDeploymentUseCase.java) → [ReviewedMultiComponentDeploymentService.java](../../src/shared/deploy/src/main/java/gold/debug/windowstolinux/shared/deploy/execution/transaction/ReviewedMultiComponentDeploymentService.java)；[MultiComponentLifecycleUseCase.java](../../src/app/service/src/main/java/gold/debug/windowstolinux/app/service/deployment/MultiComponentLifecycleUseCase.java) 管理成功图。SQLite v7 是三期历史图语义，当前 v12 的新增资源与身份字段归四期。

#### 计划

`shared/deploy` 根据无环依赖图生成构建顺序、停止顺序、启动顺序、健康门和回滚顺序：

- 构建可在资源限制内并行，但每个组件使用独立候选目录和产物清单。
- 停止按依赖图逆序执行；启动按拓扑顺序执行。
- 所有受影响组件的旧版本、运行状态、自启状态、配置快照和密钥修订必须在切换前记录。
- 共享基础组件只有在所有调用方计划都兼容时才能升级。

#### 切换和失败

```text
全部候选构建并静态校验
→ 停止受影响旧组件
→ 按依赖顺序切换/启动候选
→ 每层通过健康门后继续
→ 执行整体业务健康
→ 成功提交；失败则逆序停止候选并恢复旧组件
```

- 不承诺多组件零停机；有固定端口冲突时采用明确短停机窗口。
- 未受影响且不存在依赖风险的组件可以保持运行，但必须在计划中列出依据。
- 回滚结果按组件记录；任何组件恢复失败时整体结果为“需要人工处理”，不得用多数成功掩盖失败。
- 对共享持久化数据存在不可逆变化时停止并转入四期数据库/备份方案。

2026-08-13 的 Ubuntu 24.04 x86-64 产品入口验收使用两个普通 Java JAR 组件：健康版本整应用发布成功，Web 组件的故障候选触发整应用回滚并保留两个旧版本；关闭并重新打开桌面状态库后，SQLite v7 组件图、目标机封存运行时标记和实时状态共同恢复控制，整应用启停、重启及自启开关均通过。该证据不包含共享数据库格式迁移或跨服务器恢复。

同日，在抽取该整应用事务为跨发行版复用夹具后，仍在同一授权 Ubuntu 24.04 x86-64 实例上经 `DesktopApplicationService → SshdLinuxGateway → controlled helper v3` 重新执行该夹具，结果为 1/1 通过、0 失败、0 错误、206.2 秒。它再次验证两组件发布、故障候选整应用回滚、SQLite v7 图重载、启停和自启开关；应用保持运行但关闭自启动，服务器未重装。该回归不构成任何新增发行版的实机证据。

- 应用级启动按依赖顺序，停止按逆序，重启只影响计划明确的组件集合。
- 单组件动作若会破坏依赖，界面必须解释影响并要求选择安全的应用级动作或取消。
- 应用自启状态由各组件实际状态汇总；混合 enabled/disabled 必须显示“部分启用”，不能简化为已启用。
- systemd、Docker、Podman 组件继续各用对应适配器；不得通过同名资源猜测归属。
- 生命周期任务与同服务器部署、恢复和迁移互斥，状态事实始终来自目标机实时查询。

<a id="ai"></a>

### 3.4 多模型协作

**涉及模块与分工：** `shared/ai` 管理角色上下文、结构化调用和冲突；`app/db` 保存命名 Provider/角色，服务和 UI 提供选择入口。

#### 角色

用户可以为“项目分析、部署风险复核、错误解释”配置不同 API 记录和模型。协调器只发送该角色所需的最小脱敏事实，并保留提供方、模型、输入摘要、结构化输出和校验结果。

当前桌面 AI 页可保存多个命名 Provider，并把三个固定角色分别绑定到其中一个 Provider；三期 SQLite v6 引入端点、模型、秘密存储键和角色外键，API Key 仍由平台秘密存储持有。共享客户端每次只接收一个明确绑定且只调用一次，保留角色、Provider、模型、脱敏摘要、摘要 SHA-256、固定模式校验状态与已验证输出，不保留原始响应正文。项目分析已从桌面入口调用该链路；部署风险和错误说明使用同一服务入口接受各自的最小上下文，不获得部署执行能力。

三期图持久化基线为 SQLite v7：在 v6 Provider/角色边界之上增加组件标识、受管应用标识、依赖边和业务健康组件标识。后续 v8 从新成功请求保存完整非秘密运行时，为四期备份准备输入；当前代码已经使用 v12，v8 及后续字段不倒计为三期新增。v7 旧图迁移后该定义保持缺失，生命周期仍可依赖原拓扑、既有健康契约及目标机 root-owned 封存标记，但不得用这些压缩事实猜测备份运行时。

#### 决策规则

- 模型意见冲突时不能投票后直接执行；确定性事实优先，冲突项交给用户决定或安全停止。
- 一个模型不可看到另一个模型不需要的秘密或完整上下文。
- 任何模型不可获得 SSH、任意 Shell、文件系统越界、平台密钥或跳过确认能力。
- 模型不可用、超时、格式错误或安全校验失败时，正式支持范围回到确定性流程；不能静默切换到未授权服务。

<a id="linux"></a>

### 3.5 发行版能力与环境准备

**涉及模块与分工：** `shared/linux-sshd` 采集事实并选择具名准备实现；`shared/deploy` 根据精确目标准入。

六种具名准备实现为 Ubuntu、Debian、CentOS Stream、Rocky Linux、AlmaLinux、Oracle Linux；APT/DNF 公共流程复用，不从发行版名称推定实机支持。动态工具链准备和后期结构调整见四期。

三期加入 Debian、Rocky Linux、AlmaLinux 和 Oracle Linux 适配，仍以 x86-64 为范围。下表列的是代码内置目标矩阵，与当前发行版策略核对一致；其维护标签不是本次重新查询的上游政策，“可进入运行验收”不等于正式支持。

| 发行版 | 静态适配版本 | 包与 CPU 前置条件 | 安全与容器证据 | 当前验证状态 |
| --- | --- | --- | --- | --- |
| CentOS Stream | 9、10 | DNF、`x86_64`；9 为 v2，10 为 v3 | 自动准备要求 SELinux enforcing，采集 firewalld 与 Podman | Stream 9 x86-64 已完成产品入口准备、发布、回滚、生命周期及安全态保持验收；Stream 10 仍为 `RUNTIME-PENDING` |
| Debian | stable 13；点版本事实参考 13.6，`VERSION_ID=13` | APT、`amd64`、x86-64-v1 | 采集 AppArmor/防火墙；固定 Docker 准备 | 静态通过，实机 `RUNTIME-PENDING` |
| Rocky Linux | 代码内置小版本 9.8、10.2 | DNF、`x86_64`；9 为 v1，10 为 v3 | 自动准备要求 SELinux enforcing，采集 firewalld 与 Podman | 静态通过，实机 `RUNTIME-PENDING` |
| AlmaLinux | 代码内置小版本 9.8、10.2 | DNF；9 默认 v1；10 默认 `x86_64` 为 v3 | `x86_64_v2` 可识别但因第三方依赖边界仅返回 CPU 审阅，不自动准备；其余 EL 安全边界同上 | 静态通过，实机 `RUNTIME-PENDING` |
| Oracle Linux | 代码内置更新快照 9.7、10.2 | DNF、`x86_64`；9 为 v1，10 为 v3；旧更新快照必须先重新评审 | 自动准备要求 SELinux enforcing，采集 firewalld 与 Podman | 静态通过，实机 `RUNTIME-PENDING` |

版本依据：[Debian 13 发布与生命周期](https://www.debian.org/releases/trixie/)、[Rocky Linux 版本指南](https://wiki.rockylinux.org/rocky/version/)、[AlmaLinux 发布说明](https://wiki.almalinux.org/release-notes/)、[AlmaLinux 10.2 x86-64-v2 说明](https://wiki.almalinux.org/release-notes/10.2)、[Oracle Linux 10 更新模型](https://docs.oracle.com/en/operating-systems/oracle-linux/10/) 与 [Oracle Linux 10 系统要求](https://docs.oracle.com/en/operating-systems/oracle-linux/10/install/install-SystemRequirements.html)。

- 不把所有 EL 系统一律当成 CentOS；软件源、模块流、SELinux、CPU 基线和容器能力按发行版/主版本采集。
- EL10 系列可能存在 x86-64-v2/v3 差异，必须依据具体发行版官方要求和实际 CPU 检测决定。
- 非 x86-64、停止维护版本或生命周期不明版本默认只做识别预览，除非用户另行确认适配范围。
- AppArmor/SELinux、防火墙和包管理变化必须进入计划；禁止为求成功静默关闭安全机制。
- `ManagedDistributionProductEntryAcceptanceTest` 仅在 `managed.runtime.distribution-acceptance=true` 时运行；每次必须给出无秘密的发行版、版本、包架构、CPU 基线与准备预期。它先采集精确身份和安全/防火墙事实，再经 `DesktopApplicationFacade → SshdLinuxGateway` 执行两次环境准备，复核运行版本的 `ManagedHelperProtocolVersion.CURRENT`（当前代码为 helper v7；原方法登记时为 v4）与安全状态不变，最后复用两组件整应用发布、故障回滚和生命周期事务。AlmaLinux 10 的 x86-64-v2 目标只验证“自动准备被拒绝”，不进入发布成功路径。该框架不是实机证据，普通 Maven 验证不会连接服务器；历史实机结论仍只覆盖当时 helper v3。

<a id="architecture"></a>

### 3.6 分析与部署链职责演进

**涉及模块与分工：** `shared/analyze` 分离语言识别、构建架构与组件；`shared/deploy` 分离计划、事务和生命周期；`shared/linux` 仅定义契约；`shared/linux-sshd` 分离构建、发行版和协议执行。

三期完成生态化职责拆分，原生语言识别与构建架构解析互不倒置，固定适配器通过注册表装配。源码、策略、组件及无执行预览各有明确责任；语言生态、容器/静态工作负载与操作系统准备使用独立维度。

现行目录与依赖见[项目结构](../File.md)，并由架构门禁核对。旧目标树、类名和迁移表保留于[结构演进附录](#architecture-history)，只用于理解迁移过程。四期进一步收窄 Linux/config 依赖、移动原生数据库协议并整理失败契约，不能视为三期已具备。

<a id="implementation"></a>

## 4. 实施顺序与依赖

1. 建立支持等级、组件模型、依赖图和组件级结果。
2. 优先逐项完成 Go、Rust、.NET、Kotlin、PHP、Ruby 适配和真实环境验收。
3. 实现多语言根识别、冲突检测和无环编排。
4. 实现多组件短停机、整体健康、组件级回滚和生命周期。
5. 实现多模型角色、最小上下文和冲突处理。
6. 扩展发行版适配并建立版本/CPU/安全机制证据矩阵。
7. 其余语言从识别预览逐项升级，不批量声明正式支持。

### 批次 A：模型与门禁

1. 冻结当前公开签名、枚举常量、支持目录、helper 摘要、协议版本、POM 和数据库迁移基线。
2. 为新架构增加类型化构建工具与项目事实；原子更新生产、测试、UI 映射和现行文档。
3. 将 `jdk`、`phpcli`、`rubycli` 加入架构包白名单；`kotlinc`、`cmake` 已保留为规范名。
4. 门禁禁止架构检查器直接回到语言包，禁止旧 FQCN、参数化大类、未命名工具分支和跨维度组合包。

### 批次 B：当前语言原生基线

实施顺序固定为：

1. Java `jdk`。
2. Node `npm` 显式 Renderer 与身份核对。
3. Python `pip` 显式身份与 Renderer。
4. Go `gomodule` 回归核对。
5. Rust `cargo` 回归核对。
6. DotNet `dotnetsdk` 回归核对。
7. Kotlin `kotlinc`。
8. PHP `phpcli`。
9. Ruby `rubycli`。

本批次全部通过前，不增加新的语言扩展架构。

### 批次 C：既有扩展架构规范化

- Node 将聚合 Renderer 拆为 npm、pnpm、Yarn 三个具名 Renderer。
- Python 将聚合 Renderer 拆为 pip、Pipenv、Poetry、uv 四个具名 Renderer，并让模型身份与实际锁文件一致。
- Kotlin、PHP、Ruby 的现有 Gradle、Composer、Bundler 路径迁入多架构执行语言包。
- Java Maven、Gradle、JAR 的现有行为保持不变，只与新增 JDK 路径共享安全脚本外壳和注册表。

### 批次 D：C/CMake 试验适配

1. C/C++ 语言标记由 `c.CLanguageInspector` 独立提供，CMake 构建与目标约束由 `c.cmake` 的独立静态检查器处理。
2. 首版只允许无构建期下载、无自定义安装脚本、唯一可执行目标和显式健康契约。
3. 接入 CMake/编译器能力探测、APT/DNF 固定包集合、受控构建、制品检查、systemd 发布和回滚。
4. 支持等级保持试验适配，直至精确发行版与 CPU 矩阵完成产品入口验收。

<a id="acceptance"></a>

## 5. 验收标准与验证记录

> 历史记录：仅证明下列版本、环境和夹具的结果，不作为当前工作区的新验证。

### 分级支持

- [x] 桌面单组件和多组件审阅/结果均显示语言、框架、支持等级及精确验证范围；已验收的 Ubuntu 24.04 x86-64 组合记录精确证据，其余目标组合保持 `RUNTIME-PENDING`。
- [x] 识别预览不会进入源码归档、自动环境安装、构建、发布或生命周期入口。
- [x] 六种试验适配器具有 Ubuntu 24.04 x86-64 的完整构建、发布、回滚、生命周期、秘密脱敏和客户端重启恢复证据；由于发行版与框架矩阵未扩展，仍不声明为正式支持。

### 多组件

- [x] 循环依赖、端口冲突、重叠产物和不安全共享数据由本地自动化证明在修改前被阻止；真实产品入口已验证安全的两组件图可执行。
- [x] 构建、停止、启动、健康和回滚顺序由确定性计划、故障注入和真实两组件事务共同证明符合依赖图。
- [x] 中间组件失败时，全部已停止或尝试发布的组件逆序恢复且结果逐组件保留；真实故障候选已证明两个 v1 发布均恢复。
- [x] 部分自启和部分运行/异常由专用汇总状态保留，不会被显示为整体正常；真实入口已验证整应用启停、重启和自启状态切换。
- [x] 成功整应用图与全部组件发布状态自 SQLite v7 起单事务保存；当时 v8 为新成功组件在同一事务增加完整非秘密已审阅运行时，旧图保持明确缺失；生命周期仍以目标机实时归属和运行状态为准。

### 多模型与 Linux

- [x] 多模型冲突不会未经确认转成执行，失败不静默跨服务；已由严格解析、单 Provider 调用和裁决器自动化证明。
- [x] 三个角色上下文不含源码路径/内容或平台凭据，错误诊断在发送前脱敏并限长；已由负向测试证明。
- [x] Debian/Rocky/Alma/Oracle 已按具体版本、软件包架构、累计 CPU 级别、安全机制、防火墙和容器事实完成独立静态策略/脚本验证，不套用 CentOS 结论。
- [x] CentOS Stream 9 x86-64 已通过产品入口的精确识别、两次准备、两组件发布、故障候选整应用回滚、生命周期和安全态保持验收；已修复省略 `VARIANT_ID`、空 nftables 规则集、Java 21 默认运行时与只读 SSH 短暂超时的运行路径技术债。
- [~] Debian/Rocky/Alma/Oracle 具有同一显式产品入口实机验收框架；用户已明确将实机测试延后至后续独立任务，仍保持 `RUNTIME-PENDING`，不构成该轮三期收尾的完成声明。<a id="acceptance-ecosystem"></a>

### 生态构建自动化与入口

> 历史记录：仅证明下列版本、环境和夹具的结果，不作为当前工作区的新验证。

#### 每个架构的自动化

- 架构检查器：正确项目、缺失元数据、冲突锁文件、多入口、越界路径和不安全声明。
- 构建 Renderer：固定命令、环境清空、超时、CPU/内存/工作区/输出限制、失败证据和制品路径。
- 能力探测：缺失工具、错误版本、命令失败、APT/DNF 选择与发行版正交性。
- 事务：上传、构建、发布、健康失败、候选清理、旧版本恢复和生命周期。
- 结构：包深度、架构名、文件与类型同名、顶级类型唯一、旧 FQCN、包环和反向依赖。
- 测试夹具：`test/<language>/<build-tool>/<framework-or-function>/<expected-result>-<function>`，场景目录以 `success-` / `failure-` 标明预期部署结果，每个源码语言/工具组合三组正常、两组失败，覆盖清单由 `test/matrix.json` 与真实分析器核对；具体用途及失败分配见 [测试夹具说明](../../test/README.md)。不得用一个夹具替代多个架构的实机证据。

#### 本地门禁

每个批次至少运行：

1. 受影响模块测试。
2. `PackageStructureArchitectureTest` 全部 12 项。
3. JDK 21 离线 `mvn.cmd -B -ntp -o verify`。
4. 生产源码旧名称、旧 FQCN、旧路径和空目录扫描。
5. `docs/AllFile.md` 非测试 `src/` 完整性与不区分大小写排序检查。
6. `git diff --check`，并确认 POM、数据库迁移、helper 内容和协议版本无非预期变化。

2026-08-20 静态验收结果：

- JDK 21 离线 `mvn.cmd -B -ntp -o verify` 完成全部 28 个模块：243 项测试、0 失败、0 错误、25 项条件跳过。跳过项均为真实服务器、真实操作系统或相关运行条件用例；其中 `EcosystemExtensionProductEntryAcceptanceTest` 在未提供服务器参数时按设计跳过。
- `PackageStructureArchitectureTest` 12/12 通过；12 种变更架构的独立夹具矩阵和健康/503 变体实例化 3/3 通过。
- 使用真实工具核验 npm `ci`、pnpm 10.15.1 frozen install、Yarn 4.9.2 immutable install，以及 Pipenv 2025.0.4、Poetry 2.1.3、uv 0.8.12 对 Python 3.12/3.11 锁文件的原生命令校验；JDK 21 对 Java 夹具完成真实编译与可执行 JAR 装配。Node 脚本与全部 14 个 Python 源文件完成语法检查。
- `docs/AllFile.md` 与 480 个非测试维护文件逐文件计数一致，428 个生产 Java 文件和 13 个生产资源文件均无遗漏，206 个同级分组的不区分大小写顺序无异常。
- 生产源码中的旧构建 Renderer、`PYTHON_VENV`、旧路径和空生产目录均为 0；相对代码基线的 POM 与数据库迁移变更均为 0。
- helper 继续使用协议 v3，逐字节摘要为 `339153b9ddd5073fb1c046f5d271dfd23e9e6ece62dfced18cc9a068022b41d8`；11 组发行版准备脚本快照已同步并通过。构建与 systemd 运行统一使用固定 `/usr/local/bin:/usr/bin:/bin` 工具查找边界。
- `git diff --check` 通过。以上均为本地静态或工具级证据，不构成 Linux/systemd/SSH 真实运行支持证据。

#### 真实运行验收

- 只能通过 `DesktopApplicationFacade → SshdLinuxGateway → controlled helper v3` 产品入口执行。
- 不使用手工 SSH、手工 systemd、手工容器命令或测试专用生产旁路替代产品能力。
- 每个架构独立验证精确工具版本、锁定依赖、目标机构建、制品、启动、HTTP/TCP 健康、故障回滚、启停/重启、自启、秘密脱敏和桌面重启恢复。
- 当时 Ubuntu 24.04 x86-64 与 CentOS Stream 9 x86-64 的既有证据只覆盖原验收夹具，不自动覆盖新增架构。
- 新架构在完成精确产品入口证据前必须保持 `RUNTIME-PENDING` 或试验适配，不得写入正式支持目标列表。

#### 初始生态夹具版本门

当前直接验收入口及四期身份增强见[四期生态验收方法](PHASE-4.md#acceptance-extension)。以下只保留原三期夹具范围。

| 架构 | 验收前置版本门 |
| --- | --- |
| Java JDK | Java/Javac 21 与 JDK `jar` |
| Node npm | Node 18–24 与可探测 npm |
| Node pnpm | Node 18–24 与 pnpm 9–11 |
| Node Yarn | Node 18–24 与 Yarn 4 |
| Python pip/Pipenv | Python 3.12 或 3.11（含 venv）与对应工具 |
| Python Poetry | Python 3.12 或 3.11（含 venv）与 Poetry 2 |
| Python uv | Python 3.12 或 3.11（含 venv）与 uv 0.4 或更高版本 |
| Kotlin kotlinc | Java 21 与精确 Kotlin 1.9.x/2.x 编译器 |
| PHP/Ruby CLI | 受支持的精确 PHP 8.2–8.4 或 Ruby 3.2–3.4 版本 |
| CMake | CMake 3.25 或更高版本、Ninja 与 C 编译器；C++ 项目还需 C++ 编译器 |

- [x] Java 纯源码可以通过 `jdk` 架构生成受控可执行 JAR；`jar` 继续只表示预构建制品交付。真实目标部署待验收。
- [x] Node 的 npm、pnpm、Yarn 分别具有架构事实、构建工具身份和具名 Renderer，且 npm 为原生基线。
- [x] Python 的 pip、Pipenv、Poetry、uv 分别具有架构事实、构建工具身份和具名 Renderer，且 pip 为原生基线。
- [x] Go、Rust、DotNet 的现有原生路径通过回归，不被为目录对称而再次拆深。
- [x] Kotlin、PHP、Ruby 分别补齐 `kotlinc`、`phpcli`、`rubycli`，现有 Gradle、Composer、Bundler 保持扩展架构。
- [x] C/C++ 在当前语言原生基线完成后进入 `c.cmake` 试验适配。
- [x] 所有架构遵守 File 3.23.0 的语言聚合、架构名包、受控深度和正交维度规则。
- [x] 自动化、本地验证和逐架构产品入口证据分开记录，支持目录不存在无证据升级。

<a id="acceptance-linux"></a>

### 高级语言、整应用与发行版实机证据

> 历史记录：仅证明下列版本、环境和夹具的结果，不作为当前工作区的新验证。

2026-08-13 在授权的全新 Ubuntu 24.04 x86-64 服务器上，六种试验适配器全部经 `DesktopApplicationService → SshdLinuxGateway → controlled helper v3` 产品入口完成健康版本发布、HTTP 响应、启停/重启、故障版本自动回滚、秘密脱敏和桌面状态库重启恢复。单项验收通过后又执行同一进程的六项联合回归，结果为 6/6、0 失败、0 错误、1184 秒。该证据只覆盖验收夹具声明的精确版本、构建工具、Ubuntu 24.04 和 x86-64，不把适配器升级为正式支持，也不外推到任意框架。

同日，统一 Spring Boot Reviewed/helper v3 也在该产品入口完成环境准备幂等性、发布/HTTP 业务响应、首次失败恢复、旧版回滚、断连恢复、启动与 TCP 健康、构建资源限制、归属安全、Maven Wrapper、生命周期和主机信任验收；Podman Quadlet 完成部署、HTTP、回滚、生命周期与自启验收。它们同样仅证明该精确 Ubuntu 目标与验收夹具，不能替代 Debian、Rocky、Alma、Oracle、Ubuntu 22.04 或 CentOS Stream 的实机矩阵。

2026-08-14 的 CentOS Stream 9 目标经同一产品入口完成只读能力采集、两次环境准备、两组件发布、故障候选整应用回滚、应用/数据库重启、启动/停止与自启切换验收。目标的 DNF、`x86_64`、x86-64-v3 与 SELinux Enforcing 均符合精确验收夹具；准备前后复核的 SELinux 和防火墙态保持不变。期间修复了省略 `VARIANT_ID` 的 Stream 9 镜像分类/准备缺口、已安装但规则集为空的 nftables 探测、包管理器默认 Java 版本不随 Java 21 安装切换，以及只读 SSH 采集的短暂传输超时。该证据仅覆盖该次精确 CentOS Stream 9 x86-64 夹具，不外推到 Stream 10 或其他发行版；其余发行版实机测试仍按当时范围延后。

Kotlin 夹具固定 Gradle 8.10.2 Wrapper、官方二进制分发 SHA-256 和官方分发域名；目标机下载受超时、重试、断点续传与内容校验约束，只有校验通过的内容寻址 ZIP 才进入加锁受管缓存。PHP 故障夹具返回 HTTP 503，Ruby 锁定 Rack/WEBrick 并显式启动公共监听，确保回滚由真实健康门触发。

<a id="remaining"></a>

## 6. 剩余事项与后续边界

- 数据库一致性快照、备份包、恢复、跨服务器迁移。
- 桌面应用升级/卸载和 Web 版本。
- Kubernetes、服务网格、自动扩缩容、跨节点调度或零停机集群。
- 未经端到端验收的语言/框架正式支持。
- AI 自动修改源码或绕过部署计划直接执行。

- 不支持任意 Makefile、任意构建脚本或用户提供 Shell。
- 不在首批支持多目标 CMake、动态插件、原生库发布或跨编译。
- 不因同一语言出现多个枚举值复制部署事务、SSH、systemd、容器或发行版代码。
- 不修改数据库、备份迁移、AI、多组件事务和 Web 边界，除非后续架构实现出现明确且单独评审的需求。
- 不把本文件中的目标包、类型或矩阵描述当作已实现或已验收能力。

后续 helper v5/v7 产品回归见[四期继承能力回归](PHASE-4.md#acceptance-baseline)；原生构建的全版本准备和新身份策略只以四期现行说明及相应证据为准。

<a id="architecture-history"></a>

## 7. 结构与生态演进明细

### 生态补全前后对照

> 历史记录：仅证明下列版本、环境和夹具的结果，不作为当前工作区的新验证。

| 生态 | 当时分析架构 | 当时执行情况 | 原生基线判定 | 必须补全 |
| --- | --- | --- | --- | --- |
| Java | `jar`、`maven`、`gradle` | JAR 交付及 Spring Boot Maven/Gradle 已有固定 Renderer | **不完整**：`jar` 不编译纯 Java 源码 | 新增 `jdk` 纯源码构建；保留 `jar` 为交付架构 |
| Node | `npm`、`pnpm`、`yarn` | 三种锁文件已识别，执行仍由一个 Node Renderer 分派 | **已具备**：`npm` | 显式拆分三种构建工具身份和 Renderer，确保 npm 始终是基础路径 |
| Python | `pip`、`pipenv`、`poetry`、`uv` | 四种锁文件已识别，模型仍统一报告 `PYTHON_VENV`，执行由一个 Renderer 分派 | **行为存在、身份不完整**：`pip` | 为四种架构建立独立构建工具身份和 Renderer |
| Go | `gomodule` | Go Module 锁定分析与固定 Renderer 已存在 | **已具备** | 保持单一原生架构，不新增无依据分类 |
| Rust | `cargo` | Cargo.lock 分析与固定 Renderer 已存在 | **已具备** | 保持 Cargo 原生基线 |
| DotNet | `dotnetsdk` | SDK 锁定恢复与发布 Renderer 已存在 | **已具备** | 保持 .NET SDK 原生基线 |
| Kotlin | `gradle` | 仅有 Gradle Wrapper 应用路径 | **不完整**：缺少 Kotlin 编译器基础路径 | 新增 `kotlinc`，Gradle 保留为扩展架构 |
| PHP | `composer` | 仅有 Composer 锁定服务路径 | **不完整**：零依赖 PHP 源码仍被 Composer 前置条件阻止 | 新增 `phpcli`，Composer 保留为扩展架构 |
| Ruby | `bundler` | 仅有 Bundler 锁定 Rack 路径 | **不完整**：零依赖 Ruby 源码仍被 Bundler 前置条件阻止 | 新增 `rubycli`，Bundler 保留为扩展架构 |
| C/C++ | 仅识别预览 | 无类型化构建、发布或运行入口 | **尚未进入部署支持** | 在现有语言完成原生基线后实现 `c.cmake` 试验路径 |

基线结论：提交 `7f04b6c` 的纯 Java 源码不能通过 `JAVA_JAR` 路径迁移；该路径要求输入已经是可执行 JAR。因此该次新增独立 `JAVA_SOURCE × JDK`，并继续把 `JAVA_JAR × JAVA` 限定为预构建制品交付。

该次结果：27 个受支持目录条目现在都具有精确 `DeploymentArchitectureType`；新增或拆分的 12 种架构均已贯通静态分析、构建工具身份、具名 Renderer、目标能力门、helper v3 制品/运行分派、失败恢复与产品入口验收编排。该结果只证明代码与静态入口完备，不替代真实 Linux 运行证据。

> 历史记录：仅证明下列版本、环境和夹具的结果，不作为当前工作区的新验证。

#### 分析层

```text
shared.analyze.ecosystem
├─ c
│  └─ cmake
├─ dotnet
│  └─ dotnetsdk
├─ go
│  └─ gomodule
├─ java
│  ├─ gradle
│  ├─ jar
│  ├─ jdk
│  └─ maven
├─ kotlin
│  ├─ gradle
│  └─ kotlinc
├─ node
│  ├─ npm
│  ├─ pnpm
│  └─ yarn
├─ php
│  ├─ composer
│  └─ phpcli
├─ python
│  ├─ pip
│  ├─ pipenv
│  ├─ poetry
│  └─ uv
├─ ruby
│  ├─ bundler
│  └─ rubycli
└─ rust
   └─ cargo
```

- 语言根包保存语言识别、跨架构公共事实、跨架构选择和框架协调。现有 C/C++、.NET、Go、Java、Kotlin、Node、PHP、Python、Ruby、Rust 全部具有独立 `*LanguageInspector`。
- 架构包保存该工具独有的元数据、锁文件、入口、制品和拒绝规则。
- C/C++ 通用源码与头文件标记归 `c.CLanguageInspector`；`c.cmake` 只根据实际目标源码选择编译语言，并校验 preset、`LANGUAGES`、编译标准和目标边界。目录中的其他源码或头文件不得扩大目标语言集合。
- JAR 文件名可作为 Java 生态标记；清单入口与 JDK 版本解析归 `java.jar.JavaJarManifestInspector`，由 JAR 部署检查器调用，纯语言识别和识别预览不打开 JAR。
- Node 的 `engines.node` 和 Python 的 `requires-python`、模块入口是跨工具公共语言事实，仍归各语言根包；npm/pnpm/Yarn、pip/Pipenv/Poetry/uv 的锁文件与专属构建规则分别归架构子包。
- 已有语言生态的标记不再放在 `PreviewLanguageMarkerCatalog`；该目录只保留尚无独立生态的长尾标记。预览入口通过公共语言汇总器仍可识别全部已有语言，支持等级和可执行边界不变。

#### 构建执行层

| 情况 | 结构 |
| --- | --- |
| 单一架构语言 | Renderer 直接位于 `linux-sshd.build.ecosystem`，如 `CargoBuildRenderer`、`GoBuildRenderer`。 |
| 多架构语言 | 建立一个语言包，各架构 Renderer 直接位于该包，不再增加 `renderer` 或架构子包。 |
| 工作负载 | 容器和静态站点继续位于 `build.workload`，不得迁入语言生态。 |

补全后的多架构执行包：

```text
linux-sshd.build.ecosystem
├─ java      # Gradle、JAR、JDK、Maven
├─ kotlin    # Gradle、kotlinc
├─ node      # npm、pnpm、Yarn
├─ php       # Composer、PHP CLI
├─ python    # pip、Pipenv、Poetry、uv
└─ ruby      # Bundler、Ruby CLI
```

CMake 在首个架构阶段使用 `CmakeBuildRenderer` 直接位于 `build.ecosystem`；只有 C 生态出现第二种独立架构时才建立执行层 `c` 语言包。

#### 能力与环境准备

- `capability.ecosystem` 检测 `javac/jar`、`node/npm`、`python/pip`、`go`、`cargo/rustc`、`dotnet`、`kotlinc`、`php`、`ruby`、`cmake` 和底层 C/C++ 编译器的实际版本。
- 同一语言需要多个独立探测策略时才建立 `capability.ecosystem.<language>`；共享版本解析不复制到每个架构。
- `distro.apt` 与 `distro.dnf` 只提供固定包集合及能力要求，不运行语言命令，不判断项目类型。
- 环境准备继续保持幂等，不改变 SELinux、AppArmor 或防火墙策略，不使用任意 Shell 入口。

### 分析层结构演进

现行职责已在功能正文和结构文档收敛；以下是该次迁移的原始约束、目标、映射、实施与门禁。明确标为历史的路径不作为现行路径。

#### 目标与边界

> 历史记录：仅证明下列版本、环境和夹具的结果，不作为当前工作区的新验证。

`gold.debug.windowstolinux.shared.analyze` 采用“公共流程按稳定职责分包、语言专属能力按技术生态聚合、同一语言的独立构建架构按工具名分包”的结构。语言、工作负载、运行机制、发行版和 CPU 架构保持正交。

该次后续代码迁移必须保留以下对外入口的行为与签名：

- `DeploymentAnalysisCoordinator`
- `MixedProjectInspector`
- `ComponentAnalysisRequest`

分析层只读取有界源码并生成静态事实；实际 Maven、Gradle、Cargo、Composer、Bundler、Go、.NET 等构建执行归 `shared/linux-sshd.build.ecosystem`。该次不改变数据库结构、支持等级或真实环境验收结论。

#### 迁移前待清理问题（历史）

> 历史记录：仅证明下列版本、环境和夹具的结果，不作为当前工作区的新验证。

1. `core` 直接装配分布在 `build`、`framework`、`language`、`workload` 中的具体类型检查器，而这些实现反向依赖 `core` 中的检查器契约，形成包级循环依赖。
2. 完整项目类型检查器分散在不同维度包：Java JAR 位于 `language`，Node/Python 位于 `build`，Spring Boot 位于 `framework`，静态站点/容器位于 `workload`，预览与六种三期语言再次位于 `language`。
3. `language.advanced.AdvancedLanguageDeploymentInspector` 通过一个运行时枚举和多个 `switch` 同时处理 Go、Rust、.NET、Kotlin、PHP、Ruby；`language.additional.AdditionalLanguageInspector` 用一个宽泛分支表混合已支持语言和仅预览语言。
4. Maven、Gradle、Node、Python 的低层构建检查器在异常传播、`Optional`、可变拒绝集合和结果命名方面没有统一约定。
5. `DeploymentAnalysisCoordinator` 保存具体数据库迁移策略，Spring Boot 检查器又包含部分重复规则；协调器没有保持纯编排职责。
6. `BoundedProjectMetadata` 同时负责文件读取、ZIP 检查、项目身份、证据和消息创建；`SourceInspection` 同时携带通用源码事实、Maven Wrapper 和数据库脚本事实。
7. `MixedProjectAnalyzer` 同时承担组件编排、根路径校验、资源冲突、数据安全、依赖环和受管身份生成。
8. `build`、`source`、`preview` 等能力缺少与生产包一一对应的直接测试，现有结构边界测试只校验部分文件集合和行数，没有约束包依赖方向。

#### 目标结构（历史，已由 `File.md` 替代）

> 历史记录：仅证明下列版本、环境和夹具的结果，不作为当前工作区的新验证。

```text
gold.debug.windowstolinux.shared.analyze
├─ component/
│  ├─ ComponentAnalysisRequest
│  ├─ ComponentConflictValidator
│  ├─ ComponentDependencyValidator
│  └─ MixedProjectAnalyzer
├─ core/
│  └─ DeploymentAnalysisCoordinator
├─ ecosystem/
│  ├─ LanguageInspector
│  ├─ ProjectLanguageInspector
│  ├─ dotnet/
│  │  ├─ build/
│  │  ├─ language/csharp/
│  │  └─ project/service/
│  ├─ go/
│  │  ├─ build/
│  │  ├─ language/
│  │  └─ project/service/
│  ├─ jvm/
│  │  ├─ build/gradle/
│  │  ├─ build/maven/
│  │  ├─ framework/springboot/
│  │  ├─ language/java/
│  │  ├─ language/kotlin/
│  │  ├─ project/jar/
│  │  └─ project/kotlin/
│  ├─ node/
│  │  ├─ build/
│  │  ├─ language/
│  │  └─ project/service/
│  ├─ php/
│  │  ├─ build/
│  │  ├─ language/
│  │  └─ project/service/
│  ├─ python/
│  │  ├─ build/
│  │  ├─ language/
│  │  └─ project/service/
│  ├─ ruby/
│  │  ├─ build/
│  │  ├─ language/
│  │  └─ project/service/
│  └─ rust/
│     ├─ build/
│     ├─ language/
│     └─ project/service/
├─ policy/
│  └─ SourceMutationPolicy
├─ preview/
│  ├─ PreviewLanguageMarkerCatalog
│  └─ PreviewInspector
├─ registry/
│  └─ DeploymentTypeInspectorRegistry
├─ source/
│  ├─ BoundedSourceInspector
│  ├─ SourceInspection
│  └─ metadata/
│     ├─ BoundedMetadataReader
│     └─ ProjectIdentityResolver
├─ spi/
│  ├─ DeploymentTypeInspection
│  └─ DeploymentTypeInspector
└─ workload/
   ├─ container/
   │  └─ ContainerDeploymentInspector
   └─ staticweb/
      └─ StaticWebDeploymentInspector
```

只在存在实际实现时创建目标目录，不预建空包。尚无独立生态的长尾语言由声明式 `PreviewLanguageMarkerCatalog` 管理；某种语言建立独立生态后，其通用识别规则必须同步迁入该语言根包，不因支持等级仍为试验适配而留在预览目录。

#### 目标依赖方向（历史）

> 历史记录：仅证明下列版本、环境和夹具的结果，不作为当前工作区的新验证。

```text
component ──→ core
core ───────→ ecosystem, policy, registry, source, spi
registry ───→ ecosystem, preview, spi, workload
preview ─→ source, spi
workload ───→ ecosystem.node.build, source, spi
ecosystem.*.project ─→ 同生态 language/build/framework, source, spi
ecosystem.*.language/build/framework ─→ source
policy ─────→ source
```

- `core` 不得导入任何具体项目类型检查器，由 `registry` 提供经过完整性和重复性校验的固定集合。
- `registry` 只负责装配，不执行源码分析、策略判断或结果汇总。
- `spi` 不依赖 `core`、`registry` 或任何具体生态。
- 生态内允许共享构建和框架事实，但不得复制跨生态机械逻辑，也不得引入发行版、CPU、SSH、SFTP、systemd、容器执行或任意 Shell 能力。
- `workload.staticweb` 可以复用 `ecosystem.node.build` 的 Node 静态构建事实，但 Node 生态不得反向依赖工作负载包。

#### 生产代码迁移映射（历史）

> 历史记录：仅证明下列版本、环境和夹具的结果，不作为当前工作区的新验证。

下表路径均相对于 `gold.debug.windowstolinux.shared.analyze`。没有列为新类的目标不得创建兼容壳；原类型完成迁移后直接删除旧路径。

| 当时类型 | 目标类型或处理方式 |
| --- | --- |
| `build.gradle.GradleProjectInspection` | `ecosystem.jvm.build.gradle.GradleBuildFacts` |
| `build.gradle.GradleProjectInspector` | `ecosystem.jvm.build.gradle.GradleBuildInspector` |
| `build.maven.MavenProjectInspection` | `ecosystem.jvm.build.maven.MavenBuildFacts`；移除 Spring Boot 专属布尔值，由框架检查器解释通用插件事实 |
| `build.maven.MavenProjectInspector` | `ecosystem.jvm.build.maven.MavenBuildInspector` |
| `build.node.NodeProjectInspection` | `ecosystem.node.build.NodeBuildFacts` |
| `build.node.NodeProjectInspector` | `ecosystem.node.build.NodeBuildInspector` |
| `build.node.NodeServiceDeploymentInspector` | `ecosystem.node.project.service.NodeServiceDeploymentInspector` |
| `build.python.PythonProjectInspection` | `ecosystem.python.build.PythonBuildFacts` |
| `build.python.PythonProjectInspector` | `ecosystem.python.build.PythonBuildInspector` |
| `build.python.PythonServiceDeploymentInspector` | `ecosystem.python.project.service.PythonServiceDeploymentInspector` |
| `component.ComponentAnalysisRequest` | 保持原包、名称、字段和校验契约 |
| `component.MixedProjectAnalyzer` | 保持为编排入口；资源/数据冲突提取到 `ComponentConflictValidator`，依赖与环检查提取到 `ComponentDependencyValidator` |
| `core.DeploymentAnalysisCoordinator` | 保持对外包名、类名和 `analyze` 行为；移除具体检查器构造和具体策略正则，改为使用 `registry` 与 `policy` |
| `core.DeploymentTypeInspection` | `spi.DeploymentTypeInspection` |
| `core.DeploymentTypeInspector` | `spi.DeploymentTypeInspector` |
| `framework.springboot.SpringBootDeploymentInspector` | `ecosystem.jvm.framework.springboot.SpringBootDeploymentInspector` |
| `language.ProjectLanguageInspector` | `ecosystem.ProjectLanguageInspector`，通过新的 `ecosystem.LanguageInspector` 固定组合全部语言事实检查器 |
| `language.additional.AdditionalLanguageInspector` | 删除；已支持语言进入独立生态，仅预览标记进入 `preview.PreviewLanguageMarkerCatalog` |
| `language.advanced.AdvancedLanguageDeploymentInspector` | 删除并拆为 `GoServiceDeploymentInspector`、`RustServiceDeploymentInspector`、`DotNetServiceDeploymentInspector`、`KotlinServiceDeploymentInspector`、`PhpServiceDeploymentInspector`、`RubyServiceDeploymentInspector`，分别进入对应生态的 `project` 包 |
| `language.java.JavaJarDeploymentInspector` | `ecosystem.jvm.project.jar.JavaJarDeploymentInspector` |
| `language.java.JavaLanguageInspector` | `ecosystem.jvm.language.java.JavaLanguageInspector` |
| `language.node.NodeLanguageInspector` | `ecosystem.node.language.NodeLanguageInspector` |
| `language.preview.RecognitionPreviewInspector` | `preview.PreviewInspector` |
| `language.python.PythonLanguageInspector` | `ecosystem.python.language.PythonLanguageInspector` |
| `source.BoundedProjectMetadata` | 删除；文件/ZIP 操作进入 `source.metadata.BoundedMetadataReader`，项目 ID 进入 `source.metadata.ProjectIdentityResolver`，`required` 改为直接构造 `LocalizedMessage`，证据由使用方按其领域直接构造 |
| `source.BoundedSourceInspector` | 保持包名和类名；只负责有界、安全、只读的源码遍历和文本采集 |
| `source.SourceInspection` | 保持包名和类名；改为通用路径与带来源文本事实，移除 Maven Wrapper 和数据库策略专属字段 |
| `workload.container.ContainerDeploymentInspector` | 保持原包和类名，继续处理语言无关的单容器工作负载 |
| `workload.staticweb.StaticWebDeploymentInspector` | 保持原包和类名；复用 `ecosystem.node.build.NodeBuildFacts`，不复制 Node 构建解析 |

三期六种生态拆分时新增并独立测试以下低层事实检查器：

- `ecosystem.go.language.GoLanguageInspector` 与 `ecosystem.go.build.GoBuildInspector`
- `ecosystem.rust.language.RustLanguageInspector` 与 `ecosystem.rust.build.CargoBuildInspector`
- `ecosystem.dotnet.language.csharp.CSharpLanguageInspector` 与 `ecosystem.dotnet.build.DotNetBuildInspector`
- `ecosystem.jvm.language.kotlin.KotlinLanguageInspector`，构建事实复用 JVM Gradle 检查器
- `ecosystem.php.language.PhpLanguageInspector` 与 `ecosystem.php.build.ComposerBuildInspector`
- `ecosystem.ruby.language.RubyLanguageInspector` 与 `ecosystem.ruby.build.BundlerBuildInspector`

低层 `LanguageInspector` 和 `*BuildInspector` 只返回不可变事实或抛出有界读取异常，不接收、保存或修改调用方的 `List<RejectionReason>`。缺失、冲突和安全停止原因由项目类型检查器或 `policy` 转换。

#### 测试迁移映射（历史）

> 历史记录：仅证明下列版本、环境和夹具的结果，不作为当前工作区的新验证。

测试包必须镜像生产包，不保留旧包测试或只为兼容旧类型而存在的测试壳。

| 当时测试 | 目标测试或处理方式 |
| --- | --- |
| `component.MixedProjectAnalyzerTest` | 保持原测试；把资源/数据冲突和依赖/环用例分别下沉到两个新 Validator 测试 |
| `core.DeploymentAnalysisCoordinatorTest` | 保持核心流程测试，新增“注册表完整、无重复、未知类型失败”边界 |
| `core.SpringBootDeploymentAnalysisTest` | 移为 `ecosystem.jvm.framework.springboot.SpringBootDeploymentInspectorTest` |
| `language.advanced.AdvancedLanguageDeploymentInspectorTest` | 删除并拆为 Go、Rust、.NET、Kotlin、PHP、Ruby 六个项目类型测试 |
| `language.java.JavaLanguageInspectorTest` | 移为 `ecosystem.jvm.language.java.JavaLanguageInspectorTest` |
| `language.node.NodeLanguageInspectorTest` | 移为 `ecosystem.node.language.NodeLanguageInspectorTest` |
| `language.python.PythonLanguageInspectorTest` | 移为 `ecosystem.python.language.PythonLanguageInspectorTest` |
| `workload.container.ContainerDeploymentInspectorTest` | 保持原包和测试 |
| `workload.staticweb.StaticWebDeploymentInspectorTest` | 保持原包和测试；验证只复用 Node 构建事实 |

必须新增构建事实、六种新语言事实、`SourceMutationPolicy`、`PreviewLanguageMarkerCatalog`、`DeploymentTypeInspectorRegistry`、`BoundedMetadataReader`、`ProjectIdentityResolver`、`ComponentConflictValidator` 和 `ComponentDependencyValidator` 的直接测试。结构边界测试必须从固定文件名检查扩展到目标包位置、禁用包名和单向导入规则。

#### 实施阶段（历史，已完成）

> 历史记录：仅证明下列版本、环境和夹具的结果，不作为当前工作区的新验证。

#### SPI 与装配解耦

1. 将类型检查契约原子移动到 `spi`。
2. 新增唯一 `DeploymentTypeInspectorRegistry`，接管完整性、重复项目类型和类型匹配校验。
3. `DeploymentAnalysisCoordinator` 只依赖注册表返回的窄契约，不再导入具体实现。

#### 现有生态归位

1. 迁移 JVM、Node、Python 的语言与构建事实，统一 `*BuildFacts` 命名。
2. 将 Spring Boot、普通 JAR、Node 服务和 Python 服务归入各自生态。
3. 静态站点和容器继续留在 `workload`，现有运行建议与安全行为保持不变。

#### 六种三期语言拆分

1. 建立 Go、Rust、.NET、Kotlin、PHP、Ruby 的独立语言、构建和项目类型实现。
2. 删除 `AdvancedLanguageDeploymentInspector` 与 `AdditionalLanguageInspector`，不保留委托壳。
3. 首轮继续使用现有 `AdvancedRuntimeKind` 和 `ADVANCED_*` 公共模型，避免把本包整理扩大到 UI、部署和远程协议。

#### 源码、策略与组件职责收敛

1. 拆除 `BoundedProjectMetadata`，使 `SourceInspection` 恢复通用源码事实。
2. 将通用数据库迁移/schema mutation 停止规则移入 `SourceMutationPolicy`，删除 Spring Boot 中已经被全局策略覆盖的重复检查。
3. 从 `MixedProjectAnalyzer` 提取冲突和依赖验证器，保留其组件级编排入口。

#### 测试、架构门禁与文档收尾

1. 原子迁移测试包并补齐直接测试。
2. 增加包依赖、禁用旧包、测试镜像和 `core` 纯协调职责门禁。
3. 更新 `File.md` 当时落地状态和本文件清单；只有全部门禁通过后才标记迁移完成。

#### 验收清单

> 历史记录：仅证明下列版本、环境和夹具的结果，不作为当前工作区的新验证。

- [ ] `core` 只保留 `DeploymentAnalysisCoordinator`，且不导入具体项目类型实现。
- [ ] `spi` 与 `registry` 已分离，注册表拒绝缺失、重复和类型不匹配实现。
- [ ] 所有已支持语言进入对应技术生态；生产源码中不存在 `language.advanced` 或 `language.additional`。
- [ ] Spring Boot、普通 JAR、Kotlin、Maven、Gradle 统一位于 JVM 生态，且 Maven/Gradle 分析逻辑没有按语言复制。
- [ ] 低层语言和构建 Inspector 不修改外部拒绝集合，所有结果为不可变事实。
- [ ] `BoundedProjectMetadata` 已删除，源码读取、项目身份、策略和证据职责不再集中。
- [ ] `MixedProjectAnalyzer` 只保留编排，资源/数据冲突与依赖图验证具有独立测试。
- [ ] 生产包和测试包镜像，旧类型和兼容壳静态搜索结果为零，包级依赖无环。
- [ ] `DeploymentAnalysisCoordinator`、`MixedProjectAnalyzer`、`ComponentAnalysisRequest` 对外行为与签名保持不变。
- [ ] JDK 21 下 `mvn.cmd -B -ntp -o verify` 通过全部 28 个模块。
- [ ] `git diff --check` 通过；正式文档与当时落地状态一致。

本迁移是本地结构重构，不产生新的 Linux、systemd、SSH、构建发布或运行支持证据，不需要以真实目标机执行替代本地门禁；任何后续运行行为变化必须独立评审和验收。

#### 状态维护

> 历史记录：仅证明下列版本、环境和夹具的结果，不作为当前工作区的新验证。

- 开始代码迁移时将状态改为“实施中”，只勾选已有源码和测试证据支持的项目。
- 迁移中断或遇到跨模块模型阻塞时记录具体未完成项，不以兼容壳或重复路径暂时宣称完成。
- 全部验收完成后将状态改为“已实施”，同步把 `File.md` 的当时落地说明改为目标结构已经落地，并在两个文档中追加同日版本记录。

### Linux 部署链结构演进

现行职责已在功能正文和结构文档收敛；以下是该次迁移的原始约束、目标、映射、实施与门禁。明确标为历史的路径不作为现行路径。

#### 修订目的与边界

> 历史记录：仅证明下列版本、环境和夹具的结果，不作为当前工作区的新验证。

本修订把 `gold.debug.windowstolinux.shared.deploy`、`gold.debug.windowstolinux.shared.linux`、`gold.debug.windowstolinux.shared.linux.sshd` 整理为相互正交的部署形态、公共契约、SSHD command/session、技术生态、发行版族、运行机制和协议职责。目标是消除按引入批次聚合的实现、包级循环依赖和远程组合职责混放，同时保持类型化远程边界及现有安全语义。

1.0.x 设计记录当时只修改文档；实施过程始终遵守以下行为边界：

- 不改变 helper 协议版本、verb、参数顺序、参数语义、sudoers 白名单或远端路径。
- 不改变 SQLite schema、项目支持等级、产品入口、运行行为或既有验收结论。
- 本附录的旧目标结构、映射、实施与门禁保留历史背景，不再定义现行目标。
- 现行目录、职责、依赖方向和分包门禁统一由 `File.md` 与 `PackageStructureArchitectureTest` 定义。

#### 迁移前问题（历史）

> 历史记录：仅证明下列版本、环境和夹具的结果，不作为当前工作区的新验证。

#### `shared.deploy`

1. `plan.ReviewedDeploymentPlanner` 直接构造全部具体 Adapter，而 Adapter 又依赖 `plan` 中的请求和结果，形成 `plan ↔ adapter` 包级双向依赖。
2. Spring Boot、普通 JAR、Node、Python 的 Adapter 及六种 `AdvancedServiceAdapter` 实例使用相同计划生成逻辑；继续按语言复制只会产生薄适配器。
3. 六个发行版兼容策略、策略契约、公共规则和注册逻辑全部平铺在 `support`，独立策略边界只能依靠类名识别。

#### `shared.linux`

1. `connection` 同时保存 Gateway、端点、凭据、公共异常和组合会话。
2. capability、distro、runtime、transfer 为抛出 `LinuxOperationException` 反向依赖 `connection`，而 `LinuxRemoteSession` 又从 `connection` 依赖所有能力包，形成公共契约包环。
3. 公共契约本身没有 Apache SSHD 泄漏；该次必须保持该安全边界。

#### `shared.linux-sshd`

1. `connection` 同时保存低层 `SshCommandExecutor`、Gateway 和聚合全部远程能力的 `SshdLinuxRemoteSession`，使全部实现包与 `connection` 双向依赖。
2. `AdvancedServiceBuildRenderer` 集中保存 Go、Rust、.NET、Kotlin、PHP、Ruby 六套构建脚本和构建工具映射。
3. `PlatformCapabilityProbeScript`、`PreparationRuntimeProfile` 和 `35-advanced-runtime.sh` 再次把多种生态的探测、安装后检查和启动规则聚合到同一实现。
4. 六个发行版准备类虽已独立，但与 APT/DNF 渲染器、配置、包目录、运行时检查和执行选择器平铺在同一包；`ManagedEnvironmentExecutor` 直接 `switch` 全部发行版。
5. protocol 和 runtime 中的工作区、输入、发布、容器、systemd、运行参数、helper 拼装职责仍是扁平结构。

#### 1.0.x 目标结构（历史，已由 `File.md` 替代）

> 历史记录：仅证明下列版本、环境和夹具的结果，不作为当前工作区的新验证。

只在有实际实现时创建目录，不预建空包。技术生态、部署形态、发行版、运行机制和 CPU 架构是独立维度，禁止创建 `jvm/ubuntu/x86_64` 等组合目录。

#### `shared.deploy`

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

#### `shared.linux`

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

#### `shared.linux-sshd`

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

#### 1.0.x 目标依赖方向（历史）

> 历史记录：仅证明下列版本、环境和夹具的结果，不作为当前工作区的新验证。

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

#### 源码迁移映射（历史）

> 历史记录：仅证明下列版本、环境和夹具的结果，不作为当前工作区的新验证。

#### `shared.deploy` 生产类

| 当时类型 | 最终目标 |
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
| `compatibility.HostSupport` | `result.compatibility.HostSupportStatus` |
| `environment.EnvironmentPreparationService` | `environment.EnvironmentSetupService` |
| `lifecycle.ManagedComponentLifecycle` | 保持 `lifecycle.ManagedComponentLifecycle` |
| `lifecycle.ManagedLifecycleService` | 保持 `lifecycle.ManagedLifecycleService` |
| `lifecycle.MultiComponentLifecycleService` | 保持 `lifecycle.MultiComponentLifecycleService` |
| `plan.ApplicationHealthGate` | `contract.ApplicationHealthGate` |
| `plan.DeploymentApproval` | `contract.DeploymentApproval` |
| `plan.DeploymentStep` | `contract.DeploymentPlanAction` |
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

#### `shared.deploy` 测试

| 当时测试 | 最终目标 |
| --- | --- |
| `support.HostSupportCheckerTest` | 保持并增加 `support.distro.policy.*` 策略及 `support.distro.registry` 直接测试 |
| `environment.EnvironmentSetupServiceTest` | 保持原包 |
| `plan.MultiComponentDeploymentPlannerTest` | 保持原包，契约类型改从 `contract` 导入 |
| `plan.ReviewedDeploymentPlannerTest` | 保持原包，并覆盖注册完整性、重复 Profile 和缺失 Profile |
| `plan.ReviewedReleaseIdentityTest` | 保持原包，后置阶段覆盖六种独立运行时记录 |
| `transaction.ReviewedDeploymentServiceTest` | 保持原包 |
| `transaction.ReviewedMultiComponentDeploymentServiceTest` | 保持原包 |

必须新增 `spi`、`registry`、`adapter.service`、两个 workload Adapter 和 `support.distro.policy.*` 六个发行版策略的直接测试；不得只通过事务测试间接覆盖。

#### `shared.linux` 生产类与测试

| 当时类型 | 最终目标 |
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

#### `shared.linux-sshd` 生产类

| 当时类型 | 最终目标 |
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

#### `shared.linux-sshd` 测试

| 当时测试 | 最终目标 |
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

#### helper 资源

| 当时资源 | 最终职责 |
| --- | --- |
| `00-common.sh` | 更名为 `protocol/helper/fragments/00-protocol-foundation.sh`，只保存身份、路径、参数和所有权协议基础；生态启动规则迁出 |
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

#### 目标接口与行为约束（历史）

> 历史记录：仅证明下列版本、环境和夹具的结果，不作为当前工作区的新验证。

1. `DeploymentAdapter` 的行为保持为“一个已审阅请求生成一个类型化计划”，但契约移入 `deploy.spi`，输入和结果来自 `deploy.contract`。
2. `DeploymentAdapterRegistry`、`DeploymentBuildRendererRegistry`、`EcosystemCapabilityProbeRegistry`、`DistributionSetupRegistry` 分别持有自己的 `defaults()` 装配和完整性校验；它们必须拒绝空实现、重复类型、缺失类型及实现自报类型不匹配。Planner、Executor 和 Session 构造器不得逐项列举具体生态或发行版实现。
3. `DistributionSupportPolicy` 只读取已采集事实并返回支持判断；`DistributionSetup` 只渲染一个固定发行版的受控准备脚本。兼容策略不得执行远程操作，准备实现不得决定产品支持等级。
4. `LinuxGateway`、`DeploymentLinuxGateway`、`LinuxRemoteSession`、`DeploymentRemoteSession`、`SshdLinuxGateway` 的行为和方法集合保持不变；包迁移后一次性更新仓内导入，不保留旧包兼容壳。
5. 新增包私有 `session.SshdLinuxRemoteSessionFactory` 作为 SSHD 实现唯一组合根。Gateway 完成连接、主机信任和认证后，只把 client、session、端点及指纹交给该 Factory；Factory 创建 command、各默认注册表、能力执行器和最终会话门面。
6. `SshCommandExecutor` 仍只执行实现持有的预渲染脚本，不公开给 `deploy`、`app`、`web` 或 `linux` 契约；session 只委托类型化能力。
7. build、capability、distro、protocol、runtime 和 transfer 只能依赖 command，不得依赖具体 Gateway 或 session。
8. systemd、Docker、Podman、发布、回滚、保留、配置和秘密输入逻辑保持语言无关；生态包不得复制这些流程。

#### `Advanced*` 后置原子迁移（历史，已完成）

> 历史记录：仅证明下列版本、环境和夹具的结果，不作为当前工作区的新验证。

该阶段属于最终结构的一部分，但不得与前述包移动混成不可审阅的大批次。它不需要数据库迁移，却会改变多个公共 Java 类型和 helper bundle 字节，必须在一个代码阶段内原子完成。

| 当时公共或生产引用 | 最终决定 |
| --- | --- |
| `model.project.AdvancedRuntimeKind` | 删除，不新增另一种按成熟度或引入批次聚合的替代枚举 |
| `DeploymentRuntimeSpecification.AdvancedService` | 替换为 `GoService`、`RustService`、`DotNetService`、`KotlinService`、`PhpService`、`RubyService` 六个类型化记录 |
| `DeploymentRuntimeSuggestion.RuntimeInput.ADVANCED_*` | 替换为稳定的 `SERVICE_VERSION`、`SERVICE_ARTIFACT`、`SERVICE_ENTRYPOINT`、`SERVICE_PORT` |
| `LinuxCapabilities.advancedRuntimeVersions` | 替换为以 `DeploymentProjectType` 为键的 `serviceRuntimeVersions`；Java、Node、Python、Go、Rust、.NET、Kotlin/JVM、PHP、Ruby 使用显式事实，不以“高级”集合分组 |
| `analyze.language.advanced.AdvancedLanguageDeploymentInspector` | 按分析修订文档拆入六个生态，输出稳定 `RuntimeInputType` |
| `analyze.core.DeploymentAnalysisCoordinator` | 通过分析注册表装配六个生态检查器，不导入 `AdvancedRuntimeKind` |
| `deploy.adapter.advanced.AdvancedServiceAdapter` | 已由数据驱动 service Adapter 取代 |
| `deploy.support.HostSupportChecker` | 对六种类型化运行时分别读取 `RuntimeToolchainCapabilities` |
| `deploy.plan.ReviewedReleaseIdentity` | 对六种类型化运行时生成与现有字段等价的发布身份输入 |
| `linux-sshd.build.AdvancedServiceBuildRenderer` | 已拆为六个生态 Renderer |
| `linux-sshd.capability` 的 `ADVANCED_*` 探测键 | 改为稳定生态键，由对应 Probe 解析 |
| `linux-sshd.protocol.DeploymentRuntimeArguments` | 对六种类型化运行时生成现有 `go/rust/dotnet/kotlin/php/ruby` 参数，不改变线协议 |
| `app.ui.deployment.DeploymentRuntimeParser` | 按所选项目类型构造对应运行时记录 |
| `app.ui.deployment.DeploymentPage` | 使用稳定 `RuntimeInputType`，不改变现有表单字段和状态保留规则 |

所有受影响测试和产品入口夹具必须在同一阶段更新；不得保留 `AdvancedRuntimeKind`、`AdvancedService`、`ADVANCED_*`、`AdvancedServiceBuildRenderer`、`advanced-runtime` 或旧包转发类型。此次迁移不改变 `DeploymentProjectType`、支持等级、数据库内容、helper verb 或真实环境支持范围。

#### 实施阶段（历史，已完成）

> 历史记录：仅证明下列版本、环境和夹具的结果，不作为当前工作区的新验证。

#### 公共契约解环

1. 先移动 `deploy.contract`、`deploy.spi`、`linux.error` 和 `linux.session`，机械更新仓内导入及测试。
2. 建立包依赖审计，确认 `deploy.plan ↔ adapter` 与 `linux.connection ↔ capability/distro/runtime/transfer` 已消失。
3. 不保留旧包兼容壳；一个公开类型在迁移后只能有一个定义。

#### SSHD command/session 解环

1. 将 `SshCommandExecutor` 移入 command，将 `SshdLinuxRemoteSession` 移入 session。
2. Gateway 只创建已认证会话；session 组合各实现；具体能力只依赖 command。
3. 验证 Apache SSHD 类型没有越过 `shared/linux-sshd` 模块边界。

#### deploy SPI、注册表与部署形态

1. 建立 Adapter 注册表和固定 service Profile，删除重复语言 Adapter 与 Planner 直接装配。
2. 移动静态站点、容器和发行版兼容能力；公共契约、规则、注册表分别进入 `distro.spi`、`distro.rule`、`distro.registry`，六个具体实现统一进入 `distro.policy.<distro>`。
3. 保持单组件、多组件计划、事务、回滚、生命周期和结果语义不变。

#### 技术生态迁移

1. 移动 JVM、Node、Python 的现有构建器并抽取 Maven/Gradle 共用机械流程。
2. 将 `AdvancedServiceBuildRenderer` 拆为六个独立 Renderer，不共享语言构建命令。
3. 拆分生态能力 Probe；平台 Probe 只保留发行版、CPU、安全、防火墙和 helper 等语言无关事实。

#### 发行版、runtime 与 protocol

1. 新增发行版准备 SPI/注册表，把具体实现迁入 `distro.setup`，再按 APT/DNF 包管理器家族和具体发行版分层，删除集中 `switch`。
2. 删除 `PreparationRuntimeProfile`，由发行版 Profile 选择生态检查片段。
3. 将 systemd、container、dispatch 及 protocol 子职责归位，保持执行结果和错误本地化键不变。

#### `Advanced*` 原子清理

1. 先替换公共 model，再同步迁移 analyze、deploy、linux-sshd、UI 和测试。
2. 拆分 helper 生态资源但保持线协议 v3；更新 bundle 摘要和固定顺序。
3. 静态搜索必须确认生产源码和资源不存在全部禁用名称。

#### 测试与文档收尾

1. 测试包镜像生产包，补齐 SPI、注册表、各生态、发行版和运行机制的直接测试。
2. 更新 `File.md` 当时落地说明为已实施；只有发生功能范围或运行证据变化时才修改 DEVELOPMENT 或分期文档。
3. 删除迁移期间产生的临时桥接，重新执行包依赖无环审计和完整离线门禁。

#### 代码验收门禁（历史，现由架构测试接管）

> 历史记录：仅证明下列版本、环境和夹具的结果，不作为当前工作区的新验证。

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

#### 1.0.x 文档验收（历史）

> 历史记录：仅证明下列版本、环境和夹具的结果，不作为当前工作区的新验证。

- [x] `File.md` 版本为 `3.13.2-linux-sshd-distro-preparation-layout`，日期为 2026-08-15，状态明确为目标待审核、源码未迁移。
- [x] `File.md` 的 deploy、linux、linux-sshd 目标树、协作边界、分包规则、依赖方向和版本记录相互一致。
- [x] 本文覆盖三个模块全部现有生产类、测试和 helper 资源，并记录必要的跨模块 `Advanced*` 引用。
- [x] 两份文档使用相对链接且目标存在，Markdown 表格和代码块完整。
- [x] 当时分析层分包修订文档的 SHA-256 为 `0F90AB913E65F643FED0D6D4E0549573E9698DB9B8C215408264602D56AFB8D2`（历史快照摘要，不用于校验当时文档）。
- [x] `git diff --check` 通过。
- [x] `git status --short` 和文件清单确认没有源码、测试、POM、资源或其他文档变化。

<a id="history"></a>

## 8. 功能演进与历史版本记录

以下按功能归组保留原版本、日期和阶段变化。各行仅表示当时的设计或验收范围；若旧记录无法证明变更期次或版本归属，不能用当前实现反推。

### 阶段范围与开发规范

| 来源 / 版本 | 日期 | 历史变化与证据 |
| --- | --- | --- |
| P3 / 1.2.0-phase3 至 1.7.0-managed-lifecycle | 2026-08-07 | 历史需求，范围已由五期路线重新分配。 |

### 架构与工程

| 来源 / 版本 | 日期 | 历史变化与证据 |
| --- | --- | --- |
| 生态构建 / 1.3.0-doc-naming | 2026-09-08 | 统一三期补充文档命名、标题和导航；实现状态、支持边界与历史验收证据不变。 |

### 源码与工具链

| 来源 / 版本 | 日期 | 历史变化与证据 |
| --- | --- | --- |
| P3 / 2.1.0-doc-naming（文档结构） | 2026-09-08 | 统一三期补充文档命名并补齐生态构建、分析层分包与 Linux 部署链分包导航；阶段基线、实现和验收记录不变。 |
| P3 / 2.15.0-helper-v4-acceptance-readiness | 2026-08-22 | 将非 Ubuntu 产品入口验收说明从固定 helper v3 改为绑定 `ManagedHelperProtocolVersion.CURRENT`，明确当前源码为 v4、历史实机结果仍只覆盖 v3；命令与证据格式统一收录至四期真实环境验收准备文档，不新增实机结论。 |
| P3 / 2.2.0-phase3-experimental-adapters | 2026-08-13 | 六种优先语言接入有界锁文件分析、精确版本/产物/入口、类型化计划、固定构建脚本、helper v3 systemd 运行参数、主机工具链探测与专用测试环境逐次确认；JDK 21 全量离线门禁 28/28 通过，真实 Linux 验收前仍保持试验级和 `RUNTIME-PENDING`。 |
| P3 / 2.1.0-phase3-support-preview | 2026-08-13 | 完成支持等级/验证范围模型与不可执行识别预览；识别 Go、Rust、C#/.NET、Kotlin、PHP、Ruby 以及 C/C++、Scala、Clojure、Elixir、Dart、Lua、Perl、Swift、Shell，且不创建源码归档、部署计划、候选目录或远端动作。高级语言适配器、多组件、多模型和新增发行版仍待实施。 |
| 生态构建 / 1.3.1-language-inspection-boundary | 2026-09-08 | 统一全部已有生态的根包语言识别与架构子包解析边界，区分 C/C++ 通用标记和 CMake 目标语言，将 JAR 清单解析迁入 jar 架构。 |
| 生态构建 / 1.2.0-static-readiness | 2026-08-20 | 完成部署前反向审计与 28 模块离线静态验收；补齐 CMake 3.25/Ninja/编译器能力门、固定 preset 和语言标准合同，统一构建与 systemd 运行工具 PATH，并记录精确测试、文件索引、helper 和零边界漂移证据。真实服务器验收仍保持后置。 |
| 生态构建 / 1.1.0-static-implementation | 2026-08-20 | 完成 27 个精确架构身份与新增/拆分 12 种架构的静态闭环；加入独立源码夹具、Python 3.11 变体及仅通过产品入口执行的部署/生命周期/失败回滚/重连验收编排。新增架构继续保持试验适配或 `RUNTIME-PENDING`，等待用户提供服务器后执行真实验收。 |
| 生态构建 / 1.0.0-native-architecture-baseline | 2026-08-20 | 以 File 3.22.0 和提交 `7f04b6c` 为基线，冻结原生架构优先顺序；明确 JAR 交付不等于纯 Java 源码构建，规划 JDK、kotlinc、PHP CLI、Ruby CLI 原生补全，Node/Python 显式架构身份与 Renderer 规范化，以及后置的 C/CMake 试验适配。 |

### 配置秘密与数据库

| 来源 / 版本 | 日期 | 历史变化与证据 |
| --- | --- | --- |
| P3 / 2.16.0-sqlite-v8-runtime-persistence | 2026-08-22 | 当前 SQLite 升至 v8，在不改变既有 v7 拓扑和生命周期语义的前提下，为新成功组件保存完整非秘密已审阅运行时；旧图保持缺失且不得猜测。本次新增路径只有本地静态证据，不改写 helper v3/SQLite v7 历史实机结果。 |
| P3 / 2.13.0-phase3-centos-stream-acceptance | 2026-08-14 | CentOS Stream 9 x86-64 通过产品入口两次环境准备、两组件发布、故障候选整应用回滚、应用/数据库重启、生命周期与自启切换验收；SELinux 与防火墙态在准备前后保持观测值。修复空 nftables 规则集探测、包管理器 Java 21 默认运行时、可省略 `VARIANT_ID` 和只读 SSH 短暂超时；其他发行版仍不外推。 |
| P3 / 2.12.0-phase3-centos-stream-recovery-blocked | 2026-08-14 | Stream 9 已由产品入口复验 SELinux Enforcing；修复准备脚本对可省略 `VARIANT_ID` 的遗留要求，并保留 APT/DNF 的无秘密失败阶段。目标随后在 SSH 密钥交换前关闭连接，未以手工发布替代，完整验收保持 `RUNTIME-PENDING`。 |
| P3 / 2.9.0-phase3-ubuntu-fixture-regression | 2026-08-13 | 跨发行版复用夹具在原授权 Ubuntu 24.04 x86-64 上再次通过产品入口实机回归（1/1，206.2 秒）：验证两组件发布、故障候选整应用回滚、SQLite v7 图重载、生命周期与自启切换；服务保持运行且关闭自启动，服务器未重装。新增发行版仍无实机证据。 |
| P3 / 2.4.0-phase3-multi-model-core | 2026-08-13 | 为项目分析、部署风险复核和错误说明建立独立命名 Provider/模型绑定、最小脱敏上下文、精确 JSON 模式、凭据无关调用证据和确定性优先的冲突裁决；SQLite 升至 v6，桌面可保存 Provider、分配角色并显示项目分析调用证据。提供者失败或输出无效时不重试其他服务，模型不能授予执行权限；共享 AI、数据库、服务和 UI 聚焦门禁通过。 |

### 部署与生命周期

| 来源 / 版本 | 日期 | 历史变化与证据 |
| --- | --- | --- |
| P3 / 2.14.0-phase3-closeout | 2026-08-14 | 完成本轮三期代码、结构、静态验证与 Ubuntu 24.04/CentOS Stream 9 精确产品入口验收；删除一次性 CentOS 直接 root 引导测试，避免保留非产品部署旁路。用户明确将 Debian、Rocky、Alma、Oracle 实机验收延后为后续独立任务，全部维持 `RUNTIME-PENDING`。 |
| P3 / 2.11.0-phase3-centos-stream-safety-stop | 2026-08-14 | CentOS Stream 9 产品入口只读探测发现并修复省略 `VARIANT_ID` 时的分类技术债。目标 x86-64-v3 但 SELinux Disabled，精确验收在准备前安全停止，未执行安装或部署；其他发行版按当前范围延后。 |
| P3 / 2.8.0-phase3-distribution-harness | 2026-08-13 | 抽取已验证的两组件产品入口事务为可复用夹具，新增精确非 Ubuntu 发行版验收入口：显式校验发行版/版本/包架构/CPU，准备两次并复核 helper v3、AppArmor/SELinux 与防火墙不变，然后执行发布、故障回滚和生命周期；AlmaLinux 10 x86-64-v2 固定为拒绝准备的负向用例。静态矩阵单元门禁通过，尚无新增发行版实机证据。 |
| P3 / 2.5.0-phase3-distribution-matrix | 2026-08-13 | 新增 Debian 13、Rocky 9.8/10.2、AlmaLinux 9.8/10.2 与 Oracle Linux 9/10 的独立分类、版本/包架构/累计 CPU/安全/防火墙/容器事实，按发行版拆分兼容策略和准备适配器；EL 自动准备要求 SELinux enforcing，Alma 10 x86-64-v2 保持单独 CPU 审阅，所有准备均验证既有安全状态未被关闭。静态聚焦门禁通过，发行版实机仍为 `RUNTIME-PENDING`。 |

### 备份恢复与维护

| 来源 / 版本 | 日期 | 历史变化与证据 |
| --- | --- | --- |
| P3 / 2.10.0-phase3-reviewed-ubuntu-acceptance | 2026-08-13 | 当前 helper v3 的统一 Spring Boot Reviewed 链路完成 Ubuntu 24.04 x86-64 产品入口环境准备、发布、恢复、回滚、生命周期、Wrapper、资源、归属与信任验收；Podman Quadlet 完成部署、HTTP、回滚、生命周期和自启验收。其他发行版矩阵不外推。 |
| P3 / 2.7.0-phase3-acceptance | 2026-08-13 | 六种高级语言试验适配器在 Ubuntu 24.04 x86-64 上分别及联合通过产品入口构建、发布、HTTP、故障回滚、生命周期、秘密脱敏和客户端状态重启恢复；两组件整应用通过发布、组件故障整应用回滚、SQLite v7 图重载和生命周期。Kotlin 增加官方 SHA-256 约束与受管内容缓存，Windows SSH NIO2 关闭经过 12 次真实连接专用回归。新增发行版仍因不重装唯一授权服务器而保持实机 `RUNTIME-PENDING`。 |
| P3 / 2.6.0-phase3-product-entry | 2026-08-13 | 接入独立桌面多组件页、产品服务边界、逐组件安全归档与审阅、整应用部署和生命周期；SQLite 升至 v7 并在成功事务中原子保存不含构建/秘密值的组件图，桌面重启后从持久图和目标机封存运行时标记恢复依赖安全生命周期。聚焦数据库、服务、UI 与结构门禁通过，真实多组件部署仍为 `RUNTIME-PENDING`。 |
| P3 / 2.3.0-phase3-multi-component-core | 2026-08-13 | 新增稳定组件记录、组件级冲突/越权/共享数据/依赖环停止原因、确定性依赖波次和独立候选；整应用事务先完成所有构建与快照，再短停机切换并执行整体健康，中间失败恢复所有受影响旧组件，恢复不确定性转人工处理。生命周期使用实时归属观测、依赖影响拦截和部分运行/自启汇总；修复 helper v3 能力层历史判断。JDK 21 全量离线门禁 28/28 通过，桌面入口和实机仍待验收。 |
| P3 / 2.0.0-phase3 | 2026-08-08 | 聚焦语言分级、多组件和多模型；将备份迁移及桌面升级移至四期。 |
