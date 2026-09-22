# WindowsToLinux 三期开发文档

## 追加开发：APP 运行与 UDP 服务（2026-09-19）

状态：代码实现和本地完整门禁已通过（2026-09-20）；新增运行方式的实机验收仍为 `RUNTIME-PENDING`。

- 分类与运行方式独立：对应用外提供 HTTP(S)/TCP/UDP（含局域网）归 WEBSITE，其余默认 APP；主动联网、内部通信和本机健康接口不改变分类。
- 增加 `windowstolinux-application.properties` 共享声明：按需/常驻、结构化入口与参数、工作目录、端点、安装验证、随包构建单元和外部只读输入。覆盖现有语言构建适配器，保留支持等级。
- 按需工具返回受控命令，不预先启动业务任务；在隔离数据上验证安装。常驻无端口程序以进程稳定性/明确自检判断可用。原生、Docker、Podman 同步支持。
- 配套程序与主程序共同发布且不独立启动；补齐标准库 Python 和三个混合样例需要的受限 CMake 构建能力。
- 端点包含 TCP/UDP、绑定地址、宿主/容器端口；UDP 要求实际协议探测，更新端口冲突、归属、容器映射及 Podman CNI，禁止将发包成功或本机健康等同外部可达。
- 不增加产品内终端、任务执行表单、定时调度或 GUI 支持。测试以共享契约/helper、两端产品入口及干净 Linux 矩阵分层记录，未执行实机项为 RUNTIME-PENDING。

本轮验证统计与未执行范围见[第四期 APP 任务与维护互斥](PHASE-4.md#追加开发app-任务与维护互斥2026-09-19)。

<a id="navigation"></a>

## 文档信息与导航

- 文档组织：按功能与模块维护，期数表示能力增量；整理日期：2026-09-19。
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
  - [3.6 分析与部署链职责](#architecture)
- [4. 功能依赖与实现约束](#implementation)
- [5. 验收标准与验证记录](#acceptance)
  - [生态构建自动化与入口](#acceptance-ecosystem)
  - [高级语言、整应用与发行版实机证据](#acceptance-linux)
- [6. 剩余事项与后续边界](#remaining)

<a id="goals"></a>

## 1. 本期目标与承接关系

三期处理高级语言、混合项目、多组件生命周期和多模型协作。它扩展的是适配器和编排能力，不改变目标机构建、受管身份、短停机、健康检查、秘密保护和失败状态基线。

三期采用分级支持：识别到语言或生成计划不等于可部署；只有端到端验收完成的语言/框架/发行版组合才是正式支持。

三期生态补全遵守“原生架构优先，扩展架构后置”：每种已经存在部署路径的语言，必须先具有一个不依赖第三方框架的受控构建基线，再新增该语言的其他构建工具或框架路径。

生态补全部分只补全语言与构建架构，不改变模块边界、发行版分类、工作负载分类、运行机制、数据库、AI、备份迁移或 Web 范围。

<a id="modules"></a>

## 2. 模块增量总表

| 模块                      | 已有基础                 | 变化类型 | 本期具体增量                             | 功能入口                   |
| ------------------------- | ------------------------ | -------- | ---------------------------------------- | -------------------------- |
| `shared/model`            | 二期项目与配置模型       | 增强     | 精确构建架构、分级支持、组件图和角色事实 | [生态构建](#ecosystem)     |
| `shared/standard/analyze` | 基础语言分析             | 增强     | 高级语言、原生构建、组件冲突及依赖分析   | [混合项目](#components)    |
| `shared/deploy`           | 单组件事务               | 增强     | 整应用构建/切换/健康/回滚与依赖生命周期  | [多组件事务](#transaction) |
| `shared/linux`            | 单组件远程边界           | 增强     | 多组件事务与高级运行类型契约             | [多组件事务](#transaction) |
| `shared/linux-sshd`       | 二期语言和容器           | 增强     | 原生构建 Renderer、更多发行版与运行协议  | [生态构建](#ecosystem)     |
| `shared/ai`               | 多 Provider 与只读 Agent | 增强     | 三个固定角色、最小上下文和冲突裁决       | [多模型协作](#ai)          |
| `app/db`                  | 配置与单应用身份         | 增强     | 角色分配和成功整应用图持久化             | [多组件事务](#transaction) |
| `app/service`             | 单组件桌面用例           | 增强     | 组件审阅、整应用部署及生命周期入口       | [多组件事务](#transaction) |
| `app/ui`                  | 单组件和 Provider 表单   | 增强     | 组件图、运行时审阅和角色配置             | [混合项目](#components)    |
| `app/main`                | 共享能力装配             | 增强     | 生态注册与跨模块验收入口                 | [职责演进](#architecture)  |

分析与部署链的职责分工见[功能说明](#architecture)，现行目录统一由结构文档维护。

<a id="features"></a>

## 3. 功能开发说明

当前包结构以代码核对后的结构文档为准；本期历史设计不定义四期的新身份或工具链策略。

<a id="ecosystem"></a>

### 3.1 语言范围、构建架构与支持分级

**涉及模块与分工：** `shared/model` 定义支持目录与构建身份；`shared/standard/analyze` 提取架构事实；`shared/linux-sshd` 完成探测、受控构建和制品校验。

代码依据：[DeploymentSupportCatalog.java](../../src/shared/model/src/main/java/gold/debug/windowstolinux/shared/model/project/DeploymentSupportCatalog.java)、[DeploymentAdapterRegistry.java](../../src/shared/standard/deploy/src/main/java/gold/debug/windowstolinux/shared/standard/deploy/extension/registry/DeploymentAdapterRegistry.java)。当前 Spring Boot 的 Maven/Gradle、纯 JDK、六类高级语言和 CMake 仍标为试验适配；预构建 JAR、npm、pip、静态站点和单 Dockerfile 容器的正式标记只覆盖目录列明的历史 Ubuntu 矩阵。工具链目录准入与真实运行证据是不同维度。三期原生 JDK 基线是 Java 21；四期扩展 JDK 8 等版本后使用相应原生参数，不再把 `--release 21` 当成全版本命令。

| 等级     | 可以提供                                         | 禁止声称                       |
| -------- | ------------------------------------------------ | ------------------------------ |
| 未识别   | 安全停止、收集最小事实                           | 已分析、可部署                 |
| 识别预览 | 展示语言、构建系统、可能入口、缺失信息和计划预览 | 自动安装、构建、发布或正式支持 |
| 试验适配 | 用户明确确认后在专用测试环境执行，保留全部证据   | 生产可用、兼容所有框架         |
| 正式支持 | 在声明的语言/框架/发行版/架构矩阵内完成全流程    | 超出矩阵的泛化支持             |

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

| 术语     | 定义                                                                                          |
| -------- | --------------------------------------------------------------------------------------------- |
| 语言生态 | Java、Node、Python、Go、Rust、DotNet、Kotlin、PHP、Ruby、C/C++ 等语言相关实现的稳定归属。     |
| 原生架构 | 该语言官方工具链或事实上的基础工具链，可以在不引入应用框架的前提下完成受控构建或直接运行。    |
| 扩展架构 | Maven、Gradle、pnpm、Yarn、Poetry、Composer、Bundler 等在原生基线之上的依赖、构建或框架路径。 |
| 交付架构 | 接收已经产生的制品并验证、发布和运行，不负责从源码编译该制品。                                |
| 架构包   | `analyze.ecosystem.<language>.<architecture>` 中以工具或架构规范名命名的包。                  |

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

| 生态   | 目标身份                                                                              |
| ------ | ------------------------------------------------------------------------------------- |
| Java   | `JDK`；保留 `JAVA` 表示预构建 JAR 交付，保留 Maven/Gradle 身份                        |
| Kotlin | `KOTLINC`；保留 `GRADLE_KOTLIN_WRAPPER`                                               |
| Node   | 保留 `NPM`、`PNPM`、`YARN`                                                            |
| PHP    | `PHP_CLI`；保留 `COMPOSER_LOCKED`                                                     |
| Python | 以 `PIP_LOCKED`、`PIPENV_LOCKED`、`POETRY_LOCKED`、`UV_LOCKED` 替换宽泛 `PYTHON_VENV` |
| Ruby   | `RUBY_CLI`；保留 `BUNDLER_LOCKED`                                                     |
| C/C++  | `CMAKE`                                                                               |

#### 项目类型

- 新增纯 Java 源码项目类型，不复用 `JAVA_JAR`；两者的输入、构建责任和证据不同。
- Kotlin、PHP、Ruby 和 Python 继续使用各自语言服务类型，由构建工具身份选择架构。
- CMake 使用独立项目类型，初始只允许一个经审阅的服务可执行文件；库、多二进制和安装脚本留在识别预览。
- 支持目录必须逐项描述语言、架构、框架、目标发行版、CPU 架构和证据，不以语言枚举值推导支持等级。

| 架构        | 静态输入                                                                     | 受控构建或运行                                            | 合格制品                          |
| ----------- | ---------------------------------------------------------------------------- | --------------------------------------------------------- | --------------------------------- |
| `jdk`       | 显式源码根、唯一主类、固定 Java 版本；首版禁止外部依赖和注解处理器           | `javac --release 21` 后使用 JDK `jar` 生成可执行 JAR      | 单一可执行 JAR、确定清单和主类    |
| `npm`       | `package.json`、唯一 `package-lock.json`、精确 Node 主版本和固定脚本名       | `npm ci`，只调用经审阅的固定 build/start 入口             | 受审阅 Node 服务目录              |
| `pip`       | `pyproject.toml`、唯一 `requirements.lock`、全部依赖哈希和精确 Python 次版本 | 隔离 venv 与 `pip --require-hashes`                       | 无外部符号链接的项目 venv         |
| `gomodule`  | `go.mod`、`go.sum`、唯一 main package                                        | 只读模块模式构建                                          | 单一 ELF 可执行文件               |
| `cargo`     | `Cargo.toml`、`Cargo.lock`、唯一 binary target                               | `cargo build --locked`                                    | 单一 ELF 可执行文件               |
| `dotnetsdk` | 唯一项目文件、锁文件、精确目标框架                                           | locked restore 与受控 publish                             | 单一发布目录和固定入口            |
| `kotlinc`   | Kotlin 源码根、唯一主入口、精确 JVM 目标；首版禁止外部依赖                   | 固定 `kotlinc` 编译并生成可运行 JAR                       | 单一 Kotlin/JVM 可执行 JAR        |
| `phpcli`    | 显式入口和文档根；首版禁止 Composer 依赖                                     | PHP CLI 语法检查与受控服务入口                            | 受审阅 PHP 源码目录               |
| `rubycli`   | 显式入口和精确 Ruby 版本；首版禁止 Gem 依赖                                  | Ruby 语法检查与受控服务入口                               | 受审阅 Ruby 源码目录              |
| `cmake`     | `CMakeLists.txt`、固定 preset、唯一目标；首版禁止下载依赖和自定义安装脚本    | `cmake` configure/build，生成器和编译器来自受审阅能力事实 | 单一 ELF 可执行文件及动态依赖清单 |

受控架构拒绝任意自定义 Shell、未批准的网络取材、宿主特权、未固定依赖、越界源码路径和不确定制品；锁定依赖的受控安装与官方工具链下载不能被笼统称为禁止的构建期网络访问。原生 JDK/kotlinc 等零依赖架构仍保持其独立限制。

<a id="components"></a>

### 3.2 混合项目分析与组件审阅

**涉及模块与分工：** `shared/standard/analyze` 发现组件和依赖，`shared/model` 保存稳定身份与冲突；`app/service`、`app/ui` 组织审阅。

代码入口：[ProjectComponentDiscovery.java](../../src/shared/standard/analyze/src/main/java/gold/debug/windowstolinux/shared/standard/analyze/component/ProjectComponentDiscovery.java)、[MultiComponentDeploymentPlanner.java](../../src/shared/standard/deploy/src/main/java/gold/debug/windowstolinux/shared/standard/deploy/plan/MultiComponentDeploymentPlanner.java)；多组件不等于任意 Docker Compose 自动执行。

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

现行入口：[MultiComponentDeploymentUseCase.java](../../src/app/service/src/main/java/gold/debug/windowstolinux/app/service/deployment/MultiComponentDeploymentUseCase.java) → [ReviewedMultiComponentDeploymentService.java](../../src/shared/standard/deploy/src/main/java/gold/debug/windowstolinux/shared/standard/deploy/execution/transaction/ReviewedMultiComponentDeploymentService.java)；[MultiComponentLifecycleUseCase.java](../../src/app/service/src/main/java/gold/debug/windowstolinux/app/service/deployment/MultiComponentLifecycleUseCase.java) 管理成功图。SQLite v7 是三期历史图语义，当前 SQLite v15 中的资源、身份及桌面清单增强归四期。

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

两组件发布、故障回滚、图重载和生命周期的精确实机范围见[多组件验收](#acceptance)。

- 应用级启动按依赖顺序，停止按逆序，重启只影响计划明确的组件集合。
- 单组件动作若会破坏依赖，界面必须解释影响并要求选择安全的应用级动作或取消。
- 应用自启状态由各组件实际状态汇总；混合 enabled/disabled 必须显示“部分启用”，不能简化为已启用。
- systemd、Docker、Podman 组件继续各用对应适配器；不得通过同名资源猜测归属。
- 生命周期任务与同服务器部署、恢复和迁移互斥，状态事实始终来自目标机实时查询。

三期 SQLite v7 保存组件标识、受管应用标识、依赖边和业务健康组件标识，并与成功发布图原子提交。v8 起为新成功请求保存完整非秘密运行时，四期进一步增加资源和身份；现行 schema 为 v15。旧图缺失的绑定保持缺失，生命周期可使用原拓扑和目标机封存标记，备份不能从这些事实猜测运行时或资源。

<a id="ai"></a>

### 3.4 多模型协作

**涉及模块与分工：** `shared/ai` 管理角色上下文、结构化调用和冲突，平台服务与 UI 管理模型选择，平台秘密存储持有 API Key。

#### 角色与调用边界

“项目分析、部署风险复核、错误解释”保留独立提示和最小脱敏上下文，记录角色、Provider、模型、输入摘要、摘要 SHA-256 及固定模式校验结果，不保留原始响应。共享调用层每次只接收一个明确提供者，风险和错误解释没有部署执行能力。

三期引入的 Provider/角色绑定属于当时选择机制；现行桌面端使用统一启用顺序，角色绑定只留作迁移资料，详见[四期模型管理](PHASE-4.md#ai)。当前选择规则不改写三期历史单 Provider 测试的范围。

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

| 发行版        | 静态适配版本                                    | 包与 CPU 前置条件                                          | 安全与容器证据                                                                        | 当前验证状态                                                                                               |
| ------------- | ----------------------------------------------- | ---------------------------------------------------------- | ------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------- |
| CentOS Stream | 9、10                                           | DNF、`x86_64`；9 为 v2，10 为 v3                           | 自动准备要求 SELinux enforcing，采集 firewalld 与 Podman                              | Stream 9 x86-64 已完成产品入口准备、发布、回滚、生命周期及安全态保持验收；Stream 10 仍为 `RUNTIME-PENDING` |
| Debian        | stable 13；点版本事实参考 13.6，`VERSION_ID=13` | APT、`amd64`、x86-64-v1                                    | 采集 AppArmor/防火墙；固定 Docker 准备                                                | 静态通过，实机 `RUNTIME-PENDING`                                                                           |
| Rocky Linux   | 代码内置小版本 9.8、10.2                        | DNF、`x86_64`；9 为 v1，10 为 v3                           | 自动准备要求 SELinux enforcing，采集 firewalld 与 Podman                              | 静态通过，实机 `RUNTIME-PENDING`                                                                           |
| AlmaLinux     | 代码内置小版本 9.8、10.2                        | DNF；9 默认 v1；10 默认 `x86_64` 为 v3                     | `x86_64_v2` 可识别但因第三方依赖边界仅返回 CPU 审阅，不自动准备；其余 EL 安全边界同上 | 静态通过，实机 `RUNTIME-PENDING`                                                                           |
| Oracle Linux  | 代码内置更新快照 9.7、10.2                      | DNF、`x86_64`；9 为 v1，10 为 v3；旧更新快照必须先重新评审 | 自动准备要求 SELinux enforcing，采集 firewalld 与 Podman                              | 静态通过，实机 `RUNTIME-PENDING`                                                                           |

版本依据：[Debian 13 发布与生命周期](https://www.debian.org/releases/trixie/)、[Rocky Linux 版本指南](https://wiki.rockylinux.org/rocky/version/)、[AlmaLinux 发布说明](https://wiki.almalinux.org/release-notes/)、[AlmaLinux 10.2 x86-64-v2 说明](https://wiki.almalinux.org/release-notes/10.2)、[Oracle Linux 10 更新模型](https://docs.oracle.com/en/operating-systems/oracle-linux/10/) 与 [Oracle Linux 10 系统要求](https://docs.oracle.com/en/operating-systems/oracle-linux/10/install/install-SystemRequirements.html)。

- 不把所有 EL 系统一律当成 CentOS；软件源、模块流、SELinux、CPU 基线和容器能力按发行版/主版本采集。
- EL10 系列可能存在 x86-64-v2/v3 差异，必须依据具体发行版官方要求和实际 CPU 检测决定。
- 非 x86-64、停止维护版本或生命周期不明版本默认只做识别预览，除非用户另行确认适配范围。
- AppArmor/SELinux、防火墙和包管理变化必须进入计划；禁止为求成功静默关闭安全机制。
- `ManagedDistributionProductEntryAcceptanceTest` 仅在 `managed.runtime.distribution-acceptance=true` 时运行；每次必须给出无秘密的发行版、版本、包架构、CPU 基线与准备预期。它先采集精确身份和安全/防火墙事实，再经 `DesktopApplicationFacade → SshdLinuxGateway` 执行两次环境准备，复核运行版本的 `ManagedHelperProtocolVersion.CURRENT`（当前代码为 helper v10；原方法登记时为 v4）与安全状态不变，最后复用两组件整应用发布、故障回滚和生命周期事务。AlmaLinux 10 的 x86-64-v2 目标只验证“自动准备被拒绝”，不进入发布成功路径。该框架不是实机证据，普通 Maven 验证不会连接服务器；历史实机结论仍只覆盖当时 helper v3。

<a id="architecture"></a>

### 3.6 分析与部署链职责

**涉及模块与分工：** `shared/standard/analyze` 产出静态事实，`shared/deploy` 生成计划并组织事务，`shared/linux` 定义窄契约，`shared/linux-sshd` 实现受控构建与远程操作。现行包名、目标树和依赖图统一见[项目结构](../File.md)。

#### 语言与构建分析

- 语言根包负责语言标记、跨工具事实和选择协调；工具子包负责专属元数据、锁文件、入口、产物及拒绝规则。Node engines 与 Python requires-python 属于公共语言事实，不按包管理器复制。
- C/C++ 标记由语言检查器提供，CMake 只按实际目标源码、preset、LANGUAGES、标准及唯一目标判定；其他源码和头文件不能扩大构建目标。JAR 名称可以标记 Java 生态，清单入口和版本由 JAR 架构解析；预览不打开 JAR。
- 协调器只编排，注册表负责固定实现装配、完整性和重复性校验；低层 Inspector 返回不可变事实，不修改调用方的拒绝集合。路径读取、项目身份、策略、证据和组件冲突各有独立职责。
- 预览目录只维护没有独立生态的长尾标记；已有生态通过公共语言汇总器参加识别。分析层不执行构建、SSH、systemd 或任意 Shell。

#### 构建与环境适配

- Java 的 `jdk` 编译源码，`jar` 交付预构建 JAR；二者不能互相代替。npm/pnpm/Yarn、pip/Pipenv/Poetry/uv 使用各自的构建身份和具名 Renderer；Kotlin、PHP、Ruby 的原生入口与 Gradle、Composer、Bundler 扩展入口分开。
- 单架构 Renderer 保持直接归属；多架构语言按生态聚合，不增加无职责的层级。容器和静态站点按工作负载组织，发行版与运行机制保持独立。
- 能力探测只报告实际工具、版本和系统事实；发行版策略只判断支持，准备实现只生成受控安装操作，不能反向决定支持等级。系统变更确认及隔离增强见[四期系统准备](PHASE-4.md#system-preparation)。

#### 部署契约与远程执行

- 适配器将已审阅输入映射为类型化计划；注册表拒绝空、重复、缺失及自报类型不匹配的实现，Planner、Executor 和 Session 不逐项装配具体生态。
- 网关完成连接、信任及认证，会话组合能力并返回窄端口；底层命令执行器不向应用层或公共 Linux 契约开放。
- systemd、容器、发布、回滚、配置与秘密处理保持语言无关，不按生态复制事务。生产与测试包镜像，禁止包环和旧包兼容壳。
- 四期对 Linux/config 依赖、原生 DB 协议和失败契约的增强见[四期模块职责](PHASE-4.md#architecture)。当前职责名用于说明功能归属，不倒推模块创建期次。

**结构证据：** 三期生态补全的 27 个支持条目具有精确架构身份，新增或拆分的 12 种架构接入分析、Renderer、目标能力和产品验收入口；该结论是代码与本地门禁证据。真实范围见[生态验收](#acceptance-ecosystem)，不因目录完备而升级支持等级。

<a id="implementation"></a>

## 4. 功能依赖与实现约束

| 功能       | 依赖与约束                                                                                                       |
| ---------- | ---------------------------------------------------------------------------------------------------------------- |
| 生态构建   | 类型化语言和工具事实先于适配器；每个可部署组合闭合分析、Renderer、能力和运行契约，逐目标验收后才能调整支持等级。 |
| C/CMake    | 唯一可执行目标和显式健康契约；拒绝构建期下载、自定义安装脚本、多目标和跨编译，保持试验适配。                     |
| 多组件事务 | 先验证根路径、资源冲突及无环依赖，再生成构建、切换、健康、回滚和生命周期顺序。                                   |
| 多模型协作 | 固定角色只处理必要事实，冲突裁决先于执行授权。                                                                   |
| 结构维护   | 新架构同步生产、测试、UI 映射和文档；遵守现行包规则，不恢复参数化大类、无名工具分支或跨维度组合包。              |

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
- [~] Debian/Rocky/Alma/Oracle 具有同一显式产品入口实机验收框架；用户已明确将实机测试延后至后续独立任务，仍保持 `RUNTIME-PENDING`，不构成真实运行验收。

<a id="acceptance-ecosystem"></a>

### 生态构建自动化与入口

> 历史记录：仅证明下列版本、环境和夹具的结果，不作为当前工作区的新验证。

#### 每个架构的自动化

- 架构检查器：正确项目、缺失元数据、冲突锁文件、多入口、越界路径和不安全声明。
- 构建 Renderer：固定命令、环境清空、超时、CPU/内存/工作区/输出限制、失败证据和制品路径。
- 能力探测：缺失工具、错误版本、命令失败、APT/DNF 选择与发行版正交性。
- 事务：上传、构建、发布、健康失败、候选清理、旧版本恢复和生命周期。
- 结构：包深度、架构名、文件与类型同名、顶级类型唯一、旧 FQCN、包环和反向依赖。
- 测试夹具：`test/single-language/<language>/<build-tool>/<framework-or-function>/<expected-result>-<function>`，场景目录以 `success-` / `failure-` 标明预期部署结果，每个源码语言/工具组合三组正常、两组失败，覆盖清单由 `test/single-language/matrix.json` 与真实分析器核对；具体用途及失败分配见 [测试夹具说明](../../test/README.md)。不得用一个夹具替代多个架构的实机证据。

#### 本地门禁

生态或结构变更按影响范围验证：

1. 受影响模块测试。
2. `PackageStructureArchitectureTest`，测试数量以本次报告为准。
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

| 架构              | 验收前置版本门                                                    |
| ----------------- | ----------------------------------------------------------------- |
| Java JDK          | Java/Javac 21 与 JDK `jar`                                        |
| Node npm          | Node 18–24 与可探测 npm                                           |
| Node pnpm         | Node 18–24 与 pnpm 9–11                                           |
| Node Yarn         | Node 18–24 与 Yarn 4                                              |
| Python pip/Pipenv | Python 3.12 或 3.11（含 venv）与对应工具                          |
| Python Poetry     | Python 3.12 或 3.11（含 venv）与 Poetry 2                         |
| Python uv         | Python 3.12 或 3.11（含 venv）与 uv 0.4 或更高版本                |
| Kotlin kotlinc    | Java 21 与精确 Kotlin 1.9.x/2.x 编译器                            |
| PHP/Ruby CLI      | 受支持的精确 PHP 8.2–8.4 或 Ruby 3.2–3.4 版本                     |
| CMake             | CMake 3.25 或更高版本、Ninja 与 C 编译器；C++ 项目还需 C++ 编译器 |

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

| 功能                         | 版本与目标                                      | 实机证据和限制                                                                                                                                                                     |
| ---------------------------- | ----------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Go/Rust/.NET/Kotlin/PHP/Ruby | 2026-08-13，Ubuntu 24.04 x86-64，helper v3      | 六类各自完成部署、HTTP、启停/重启、故障回滚、秘密脱敏及管理库重开；同进程联合回归 6/6，0 失败/错误，1184 秒。只覆盖精确夹具，不升级框架或发行版支持等级。                          |
| 两组件事务                   | 同日、同一 Ubuntu 目标，两个 Java JAR 组件      | 发布、Web 故障候选触发整应用回滚、两个旧版本保留、SQLite v7 图重载和生命周期通过；共享夹具独立回归 1/1，206.2 秒。收尾关闭自启、应用保留运行，不含数据库格式迁移或跨服务器恢复。   |
| Spring Boot 与 Podman        | 同日、同一产品入口与 helper v3                  | Reviewed 准备、发布、首次失败恢复、回滚、断连、HTTP/TCP、资源/归属/Wrapper/信任检查，以及 Podman Quadlet 生命周期和自启通过；二期功能的独立证据，不计作新增语言。                  |
| CentOS Stream 9 整应用       | 2026-08-14，x86_64/X86_64_V3，SELinux Enforcing | 两次准备、双组件发布、故障回滚、应用/数据库重启及生命周期通过，安全态保持。VARIANT_ID 可省略、空 nftables、Java 默认版本和只读 SSH 短暂超时已修复；不外推 Stream 10 或其他发行版。 |

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

helper v5/v7 的部署覆盖见[四期部署验收](PHASE-4.md#acceptance-live)；原生构建的全版本准备和新身份策略只以四期现行说明及相应证据为准。

## 多语言成功样例补充（2026-09-19）

已批准范围：现有125个单语言样例迁入 test/single-language；新增 test/multi-language 下8个独立复杂成功项目。组合为 TS/Java、TS/Node/Go、JS/Node/C#、JS/PHP/Python、TS/Kotlin/Ruby、Rust/C++、Python/C++、Rust/C。需要持久化时统一SQLite。仅实现样例、测试和文档；Windows真实构建、浏览器及控制台业务验证，Linux实机和产品控制台部署另行安排。每个混合项目只保留一份成功源码，故障在临时副本中注入。实施及证据见测试目录说明。

本地交付：八项目通过独立构建与统一业务验收，五个网页通过浏览器流程，三个控制台通过真实文件与子进程故障检查；迁移相关及混合矩阵共 139 项 JUnit 测试通过。入口见 [多语言样例](../../test/multi-language/README.md)，工具版本、结果与未验证范围见 [验证记录](../../test/multi-language/VERIFICATION.md)。该记录仅属于样例与测试基础设施，不扩展产品部署支持结论。

### 中型业务验收升级（2026-09-19）

已批准直接升级原八项目，业务规范先行；新增多实体状态流、并发竞争、规模数据及中断恢复验收。保持 SQLite 与现有语言组合，不兼容旧样例数据、不变更产品部署能力。最终以八项目 Windows 标准验收和五个浏览器流程为准，旧通过记录不能代替新版验证。

2026-09-20：八项目连续 Windows 标准验收及五个浏览器流程通过，原 125 个场景内文件与锁文件摘要保留一致。规模、故障、分次回归结果及并行产品编译阻断见[新版验证记录](../../test/multi-language/VERIFICATION.md)；不推定 Linux 或产品部署通过。
