# WindowsToLinux 三期生态构建补全开发文档

## 文档信息

- 文档版本：`1.0.0-native-architecture-baseline`
- 结构基线：`File.md 3.22.0-ecosystem-architecture-packages`
- 代码基线：`7f04b6c`
- 文档状态：**待实施；本文件定义三期生态补全顺序，不声明新增语言、构建架构、发行版或运行能力已经可用**
- 更新日期：2026-08-20
- 上级文档：[三期工程细化文档](PHASE-3.md)
- 正式结构规范：[项目文件结构](../File.md)

## 1. 目标

三期生态补全遵守“原生架构优先，扩展架构后置”：每种已经存在部署路径的语言，必须先具有一个不依赖第三方框架的受控构建基线，再新增该语言的其他构建工具或框架路径。

本文件只补全语言与构建架构，不改变模块边界、发行版分类、工作负载分类、运行机制、数据库、AI、备份迁移或 Web 范围。

## 2. 术语与强制规则

| 术语 | 定义 |
| --- | --- |
| 语言生态 | Java、Node、Python、Go、Rust、DotNet、Kotlin、PHP、Ruby、C/C++ 等语言相关实现的稳定归属。 |
| 原生架构 | 该语言官方工具链或事实上的基础工具链，可以在不引入应用框架的前提下完成受控构建或直接运行。 |
| 扩展架构 | Maven、Gradle、pnpm、Yarn、Poetry、Composer、Bundler 等在原生基线之上的依赖、构建或框架路径。 |
| 交付架构 | 接收已经产生的制品并验证、发布和运行，不负责从源码编译该制品。 |
| 架构包 | `analyze.ecosystem.<language>.<architecture>` 中以工具或架构规范名命名的包。 |

1. 每个分析构建架构必须使用自身规范名包，不得把多个工具隐藏在无名 `build` 包或一个参数化大类中。
2. 语言识别器、跨架构选择器和框架协调器留在语言包；架构专属事实与检查进入架构包。
3. 同一语言尚未完成原生架构时，不新增该语言的其他扩展架构。
4. `jar` 是 Java 的原生制品交付架构，但不是纯 Java 源码构建架构；纯源码必须由 `jdk` 架构使用受控 `javac` 与 `jar` 完成。
5. 架构名称固定使用全小写规范名：`bundler`、`cargo`、`cmake`、`composer`、`dotnetsdk`、`gradle`、`gomodule`、`jar`、`jdk`、`kotlinc`、`maven`、`npm`、`phpcli`、`pip`、`pipenv`、`pnpm`、`poetry`、`rubycli`、`uv`、`yarn`。
6. C 与 C++ 统一归 `ecosystem.c`，首个构建架构为 `cmake`；C++ 是独立语言能力，不通过 Java 类型继承表达。
7. 架构支持必须同时贯通静态分析、类型化模型、目标机构建、能力探测、环境准备、制品验证、运行计划、失败恢复和证据记录；只增加枚举或目录不算完成。
8. APT/DNF 包名只进入 `distro`，语言命令和版本判断只进入 `capability.ecosystem`，不得建立语言与发行版组合包。
9. 新架构默认从识别预览或试验适配开始；本地测试和静态门禁不得升级真实运行支持声明。

## 3. 当前实现审计

| 生态 | 当前分析架构 | 当前执行情况 | 原生基线判定 | 必须补全 |
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

结论：当前纯 Java 源码确实不能通过 `JAVA_JAR` 路径迁移；该路径要求输入已经是可执行 JAR。三期补全必须新增 `jdk`，不能把 JAR 文件交付误写成源码编译支持。

## 4. 目标结构

### 4.1 分析层

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

- 语言包只保存语言识别、跨架构选择和框架协调。
- 架构包保存该工具独有的元数据、锁文件、入口、制品和拒绝规则。
- CMake 的 C 与 C++ 事实共同位于 `c.cmake`，但输出必须保留精确源码语言集合。

### 4.2 构建执行层

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

### 4.3 能力与环境准备

- `capability.ecosystem` 检测 `javac/jar`、`node/npm`、`python/pip`、`go`、`cargo/rustc`、`dotnet`、`kotlinc`、`php`、`ruby`、`cmake` 和底层 C/C++ 编译器的实际版本。
- 同一语言需要多个独立探测策略时才建立 `capability.ecosystem.<language>`；共享版本解析不复制到每个架构。
- `distro.apt` 与 `distro.dnf` 只提供固定包集合及能力要求，不运行语言命令，不判断项目类型。
- 环境准备继续保持幂等，不改变 SELinux、AppArmor 或防火墙策略，不使用任意 Shell 入口。

## 5. 类型化模型变更

### 5.1 构建工具身份

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

### 5.2 项目类型

- 新增纯 Java 源码项目类型，不复用 `JAVA_JAR`；两者的输入、构建责任和证据不同。
- Kotlin、PHP、Ruby 和 Python 继续使用各自语言服务类型，由构建工具身份选择架构。
- CMake 使用独立项目类型，初始只允许一个经审阅的服务可执行文件；库、多二进制和安装脚本留在识别预览。
- 支持目录必须逐项描述语言、架构、框架、目标发行版、CPU 架构和证据，不以语言枚举值推导支持等级。

## 6. 各原生架构最低契约

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

任何架构只要需要任意自定义 Shell、构建期网络下载、宿主特权、未固定依赖、越界源码路径或多个不确定制品，就必须在修改目标机前停止。

## 7. 实施批次

### 7.1 批次 A：模型与门禁

1. 冻结当前公开签名、枚举常量、支持目录、helper 摘要、协议版本、POM 和数据库迁移基线。
2. 为新架构增加类型化构建工具与项目事实；原子更新生产、测试、UI 映射和现行文档。
3. 将 `jdk`、`phpcli`、`rubycli` 加入架构包白名单；`kotlinc`、`cmake` 已保留为规范名。
4. 门禁禁止架构检查器直接回到语言包，禁止旧 FQCN、参数化大类、未命名工具分支和跨维度组合包。

### 7.2 批次 B：当前语言原生基线

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

### 7.3 批次 C：既有扩展架构规范化

- Node 将聚合 Renderer 拆为 npm、pnpm、Yarn 三个具名 Renderer。
- Python 将聚合 Renderer 拆为 pip、Pipenv、Poetry、uv 四个具名 Renderer，并让模型身份与实际锁文件一致。
- Kotlin、PHP、Ruby 的现有 Gradle、Composer、Bundler 路径迁入多架构执行语言包。
- Java Maven、Gradle、JAR 的现有行为保持不变，只与新增 JDK 路径共享安全脚本外壳和注册表。

### 7.4 批次 D：C/CMake 试验适配

1. 将 C/C++ 从通用预览标记升级为 `c.cmake` 的独立静态检查器。
2. 首版只允许无构建期下载、无自定义安装脚本、唯一可执行目标和显式健康契约。
3. 接入 CMake/编译器能力探测、APT/DNF 固定包集合、受控构建、制品检查、systemd 发布和回滚。
4. 支持等级保持试验适配，直至精确发行版与 CPU 矩阵完成产品入口验收。

## 8. 测试与证据

### 8.1 每个架构的自动化

- 架构检查器：正确项目、缺失元数据、冲突锁文件、多入口、越界路径和不安全声明。
- 构建 Renderer：固定命令、环境清空、超时、CPU/内存/工作区/输出限制、失败证据和制品路径。
- 能力探测：缺失工具、错误版本、命令失败、APT/DNF 选择与发行版正交性。
- 事务：上传、构建、发布、健康失败、候选清理、旧版本恢复和生命周期。
- 结构：包深度、架构名、文件与类型同名、顶级类型唯一、旧 FQCN、包环和反向依赖。
- 测试夹具：`test/<language>/<architecture>/<framework-or-function>/<fixture>`，不得用一个夹具替代多个架构证据。

### 8.2 本地门禁

每个批次至少运行：

1. 受影响模块测试。
2. `PackageStructureArchitectureTest` 全部 12 项。
3. JDK 21 离线 `mvn.cmd -B -ntp -o verify`。
4. 生产源码旧名称、旧 FQCN、旧路径和空目录扫描。
5. `docs/AllFile.md` 非测试 `src/` 完整性与不区分大小写排序检查。
6. `git diff --check`，并确认 POM、数据库迁移、helper 内容和协议版本无非预期变化。

### 8.3 真实运行验收

- 只能通过 `DesktopApplicationFacade → SshdLinuxGateway → controlled helper v3` 产品入口执行。
- 不使用手工 SSH、手工 systemd、手工容器命令或测试专用生产旁路替代产品能力。
- 每个架构独立验证精确工具版本、锁定依赖、目标机构建、制品、启动、HTTP/TCP 健康、故障回滚、启停/重启、自启、秘密脱敏和桌面重启恢复。
- 当前 Ubuntu 24.04 x86-64 与 CentOS Stream 9 x86-64 的既有证据只覆盖原验收夹具，不自动覆盖新增架构。
- 新架构在完成精确产品入口证据前必须保持 `RUNTIME-PENDING` 或试验适配，不得写入正式支持目标列表。

## 9. 完成标准

- [ ] Java 纯源码可以通过 `jdk` 架构生成并部署受控可执行 JAR；`jar` 继续只表示预构建制品交付。
- [ ] Node 的 npm、pnpm、Yarn 分别具有架构事实、构建工具身份和具名 Renderer，且 npm 为原生基线。
- [ ] Python 的 pip、Pipenv、Poetry、uv 分别具有架构事实、构建工具身份和具名 Renderer，且 pip 为原生基线。
- [ ] Go、Rust、DotNet 的现有原生路径通过完整回归，不被为目录对称而再次拆深。
- [ ] Kotlin、PHP、Ruby 分别补齐 `kotlinc`、`phpcli`、`rubycli`，现有 Gradle、Composer、Bundler 保持扩展架构。
- [ ] C/C++ 只在当前语言原生基线全部完成后进入 `c.cmake` 试验适配。
- [ ] 所有架构遵守 File 3.22.0 的语言聚合、架构名包、受控深度和正交维度规则。
- [ ] 自动化、本地完整验证和逐架构产品入口证据彼此分开记录，支持目录不存在无证据升级。

## 10. 非目标

- 不支持任意 Makefile、任意构建脚本或用户提供 Shell。
- 不在首批支持多目标 CMake、动态插件、原生库发布或跨编译。
- 不因同一语言出现多个枚举值复制部署事务、SSH、systemd、容器或发行版代码。
- 不修改数据库、备份迁移、AI、多组件事务和 Web 边界，除非后续架构实现出现明确且单独评审的需求。
- 不把本文件中的目标包、类型或矩阵描述当作已实现或已验收能力。

## 11. 版本记录

| 版本 | 日期 | 说明 |
| --- | --- | --- |
| 1.0.0-native-architecture-baseline | 2026-08-20 | 以 File 3.22.0 和提交 `7f04b6c` 为基线，冻结原生架构优先顺序；明确 JAR 交付不等于纯 Java 源码构建，规划 JDK、kotlinc、PHP CLI、Ruby CLI 原生补全，Node/Python 显式架构身份与 Renderer 规范化，以及后置的 C/CMake 试验适配。 |
