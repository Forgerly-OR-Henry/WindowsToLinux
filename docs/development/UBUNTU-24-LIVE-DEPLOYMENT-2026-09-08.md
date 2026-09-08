# Ubuntu 24 全语言真实部署验证

- 状态：完成。17 条运行路径真实部署、错误修复复测、全仓门禁和测试进程清理均通过。
- 日期：2026-09-08。
- 基础提交：`44014e75cb85772db147110ed5b320a4d40dd0fc`，基于任务开始时已有未提交修改继续验证。
- 路径：`DesktopApplicationFacade → SshdLinuxGateway → managed helper v5`；关闭 AI 依赖。
- 限制：同一服务器连续安装、复用和修复，不重装；不删除原有数据，不绕过产品部署入口手工发布。

## 环境基线

- Ubuntu 24.04.1 LTS，x86_64 / amd64，CPU 能力 x86-64-v3。
- 内存 3916 MiB、无 swap；根文件系统 29 GiB，初始空闲约 26 GiB。
- 初始只有 SSH 22 和本地 DNS 53 监听；未发现受管业务应用。
- AppArmor 启用，UFW 状态 inactive；后续不得以关闭安全模块解决部署错误。
- 产品只读能力探测：`ManagedDeploymentCapabilityInspectionAcceptanceTest`，1 项通过。
- 初始无 Java 21/Maven/Node/Go/Rust/.NET/Kotlin/PHP/Ruby/容器工具链，仅已安装 Python3。
- 登录秘密只通过隐藏标准输入传入测试进程环境，不写入项目和报告。

## 矩阵

当前枚举有 14 种可部署项目类型；`RECOGNITION_PREVIEW` 没有部署路径。

| 语言或路径 | 验证结果 |
| --- | --- |
| Java：Spring Boot、JAR、JDK 源码 | 三条路径修复后发布、HTTP 和本地清单登记通过；JDK 源码还验证生命周期、故障回滚与重连管理 |
| JavaScript/Node.js | 修复后发布、HTTP、生命周期、故障候选回滚、秘密脱敏和重连后管理通过 |
| TypeScript/Node.js | npm/tsc 构建、发布、HTTP 和停止清理通过 |
| Python | 修复后发布、HTTP 和本地清单登记通过 |
| Go | 发布、HTTP、生命周期、故障候选回滚和重连后管理通过 |
| Rust | 发布、HTTP、生命周期、故障候选回滚和重连后管理通过 |
| C#/.NET | 发布、HTTP、生命周期、故障候选回滚和重连后管理通过 |
| Kotlin/JVM | kotlinc 2.0.21 发布、HTTP、生命周期、故障候选回滚和重连后管理通过 |
| PHP | 发布、HTTP、生命周期、故障候选回滚和重连后管理通过 |
| Ruby | 发布、HTTP、生命周期、故障候选回滚和重连后管理通过 |
| C/C++：CMake | C 发布、HTTP、生命周期、故障候选回滚和重连后管理通过；C++ 构建、发布、HTTP 和停止清理通过 |
| 静态站点 | 修复后发布、HTTP 和本地清单登记通过 |
| Dockerfile：Docker/Podman | 两种引擎的构建、发布、HTTP、故障回滚、生命周期、重连管理和正常停止清理全部通过 |

## 成功发布证据

以下为成功部署的保留应用及当前发布摘要前 12 位。产品验收断言完整 SHA-256，随后以只读 SSH 核对 `apps/<应用>/current` 的实际发布目录。根目录为 `/var/lib/windowstolinux/apps`；失败候选不会作为成功证据。

| 路径 | 保留应用标识 | 发布 SHA-256 前缀 |
| --- | --- | --- |
| Java/JDK | `wtl-ext-java-jdk-4vez2ftog` | `c7c20ed79302` |
| Java/Spring Boot | `wtl-live-spring-54k396dtg` | `276947789daf` |
| Java/JAR | `wtl-java-56q1x6cxs` | `9ab6d8a5d072` |
| JavaScript | `wtl-node-4z6k3vio4` | `bb9c3ea591f1` |
| TypeScript | `wtl-live-typescript-54k396dtg` | `7cd63d48ad55` |
| Python | `wtl-python-56q1x6cxs` | `5198f7d5a650` |
| Go | `wtl-service-go-4lhiahs7s` | `e98d41fba0eb` |
| Rust | `wtl-service-rust-4lhiahs7s` | `32eb47b94647` |
| C#/.NET | `wtl-service-dotnet-4lhiahs7s` | `01d1a7d89d82` |
| Kotlin | `wtl-ext-kotlin-kotlinc-4eqhocrqk` | `4beab31df5f1` |
| PHP | `wtl-service-php-4lhiahs7s` | `2e5b1cc15e29` |
| Ruby | `wtl-service-ruby-4lhiahs7s` | `e15591d0cce2` |
| C | `wtl-ext-cmake-4eqhocrqk` | `a2d78149acf9` |
| C++ | `wtl-live-cpp-54k396dtg` | `8e03ebdc0c96` |
| 静态站点 | `wtl-static-56q1x6cxs` | `4fbd98a0d921` |
| Podman/Quadlet | `wtl-podman-5l1xsotww` | `988780616f40` |
| Docker | `wtl-docker-5l1xsotww` | `ea30ae00fc8c` |

## 已复现问题

### 1. 环境准备脚本超过 Linux 单参数限制

首次 `UbuntuManagedEnvironmentSetupAcceptanceTest` 失败：`/bin/bash: Argument list too long`。失败发生在脚本启动前，软件安装尚未开始。当前 helper 含完整多段脚本，被拼接进 `bash -lc` 单个参数，超过目标机限制。

修复：`SshCommandExecutor.execScript` 经标准输入传输完整命令组，使用 Bash `-s` 读取；命令组重定向到 `/dev/null`，防止安装器读取后续脚本文本。环境准备改用该入口，既有协议载荷输入仍使用原入口。新增本地 SSH/Bash 回归，覆盖超过 128 KiB、中文和引号、子命令读输入、非零退出码、独立协议载荷。

依据：[Linux execve 参数限制](https://www.man7.org/linux/man-pages/man2/execve.2.html)、[GNU Bash 的 -s 输入方式](https://www.gnu.org/software/bash/manual/html_node/Invoking-Bash.html)。修复后同一服务器连续两次环境准备通过。

### 2. 旧验收步骤断言类型不匹配

当前 `DeploymentEvent.step()` 为 `DeploymentTraceEvent` 枚举，8 个旧真实验收类仍将其直接与字符串比较。改为比较 `step().code()`，避免成功步骤被误报缺失；这项检查修复本身不构成部署成功证据。

### 3. Kotlin 系统包不满足产品版本要求

Ubuntu 仓库安装的 Kotlin 包为 `1.3.31+ds1-1ubuntu1`，实际命令报告 `kotlinc-jvm 1.3-SNAPSHOT`；产品需要 Kotlin 1.9/2.x 与 Java 21。环境准备现在保留系统编译器，必要时下载独立的 Kotlin 2.0.21，先校验官方 SHA-256，再安装到产品专用目录。能力探测和构建使用同一个选择器；重试复用已完成安装。

官方校验值：`0352c0a45bd22f80f6b26e485cd04da8047baa5de54865281fb9f89a4a7bcf2a`，来源为 [JetBrains 发布附件](https://github.com/JetBrains/kotlin/releases/download/v2.0.21/kotlin-compiler-2.0.21.zip.sha256)。

APT 安装明确使用非交互前端，`needrestart` 仅列出待重启服务，避免自动重启已有业务；诊断输出在脱敏后保留头尾，防止大量安装日志挤掉末尾失败检查码。

### 4. 配置保存精度与顺序导致本地应用登记回滚

首次 Node.js 远端发布和 HTTP 成功，但生命周期报没有受管应用。离线新增用例复现：先保存含纳秒时间、非键序条目的配置，成功部署登记时再次保存，同一对象因 SQLite 毫秒精度和键序读取被 `equals` 错判为修改不可变修订，整个登记事务回滚。现在按已验证的规范内容摘要和毫秒时间比较，仍拒绝内容或存储时间改变。秘密修订元数据重复保存也按相同存储精度核对，并保留凭据引用、模式和时间不可变检查。

首次部署观察记录改为在应用登记之后保存。实机测试现要求成功结果没有非致命告警，并能从本地产品清单找到应用，不再只断言远端终态。

### 5. JVM 默认内存预留与受限构建冲突

在同机对 JDK 21 执行 3 GiB 虚拟地址空间限制，`javac -version` 和 `jar --version` 均因 native thread 创建失败而无法启动。明确约束堆、元空间、压缩类空间和代码缓存后，同一限制下两项命令通过。JVM 构建脚本按用户给出的上限生成有界设置，保留 `ulimit`；修复后 Java 源码完整发布/回滚测试通过，Spring Boot 已构建、发布并通过外部 HTTP 检查。

### 6. 防火墙状态探测修正

同机只读检查显示 `systemctl is-active ufw` 为 active，而 `LC_ALL=C ufw status` 为 inactive。前者是 oneshot 单元状态，不能代表实际规则状态。探测已改为读取 UFW 自身状态，普通权限失败时仅尝试非交互 sudo 的同一只读命令；权限不足或输出不明返回 unknown。Bash 回归覆盖启用、停用、权限拒绝、未知输出和 sudo 读取成功；没有启停防火墙或修改规则。

修复后通过产品入口断言 `managed.expect.firewall-state=INACTIVE`，实机结果与 UFW 自身一致。

### 7. npm 生成的命令链接与无符号链接发布规则冲突

TypeScript 已在目标机完成 `npm ci` 和编译，但第一次发布被 `MANAGED_HELPER_REJECT=source-symlink` 拒绝。npm 即使使用 `--ignore-scripts` 仍会生成 `node_modules/.bin` 命令链接。现在在构建后将此类链接转换为指向依赖树内真实可执行文件的可搬移命令脚本；保留目标位置和参数，先检查目标范围、存在性和可执行性，以临时文件原子替换。其他链接仍由原发布规则拒绝。

同机隔离回归已执行生产转换代码，检查含空格/引号的路径及参数在目录搬移后仍正确，越界、悬空和循环链接均被拒绝；临时目录自动清理。新增 Linux/macOS JUnit 回归，Windows 普通门禁条件跳过此真实链接用例。

### 8. 新增验收的 Windows HTTP 客户端初始化失败

C++ 和 Spring Boot 已发布成功，但新测试使用 `java.net.http.HttpClient` 时，Windows JVM 在创建 Selector 所需的本地连接中报 `UnixDomainSockets Invalid argument: connect`，尚未向目标应用发送 HTTP 请求。C++ 的服务器本机和 Windows curl 均返回 200/正确内容。测试改为复用既有验收使用的 `HttpURLConnection`，并保留连接、读取超时与显式断开；这属于本机测试执行问题，不算远端产品修复。

### 9. 容器标签模板转义与首次发布前的回滚

Docker 和 Podman 均完成镜像构建，但镜像归属校验中的单引号 Go 模板保留了多余的反斜线，引擎报 `unexpected "\\" in operand`。已修正镜像、卷及相同原因的备份卷标签查询，保持标签值和归属校验不变，并加入模板转义门禁。备份卷的同源修复不构成备份端到端成功证据。

该失败发生在应用目录创建之前，原首次回滚无条件要求应用根目录和 releases 目录存在，误报 `MANUAL_RECOVERY_REQUIRED`。现在允许这些目录尚未创建；若目录存在仍校验所有者及无链接边界，若存在 Docker/Podman 同名容器或 Quadlet 则拒绝自动清理。Bash 回归验证无目录回滚与两种引擎冲突拒绝。实机只读确认本次失败应用没有发布根目录、容器或 Quadlet，因此可以修复后继续产品部署，无需用户处理或重装。

此修复改变 helper 内容，20 段资源独立组装后的 SHA-256 为 `d7fd7ae66b0ddc17f08d78e7450cb08bc92418bccccc6a1abb58b18206764ce7`；11 个发行版安装脚本的固定身份同步更新。前面的语言验收使用相同协议 v5 的原 helper `ad6692acedf1749c663559c67e114c12525660d5eda048e751d6c21fd3ab2fa8`，新变更只涉及容器标签查询、容器首次回滚及同源备份卷查询。

### 10. 容器测试夹具的正常退出

初版 Python 容器夹具作为 PID 1 没有处理 SIGTERM，停止操作在 10 秒后以 SIGKILL 结束进程，Podman 单元因此保留 exit 137/failed；当时已核对 MainPID=0、容器不存在、临时转发规则已清理。没有将强制终止伪装成正常退出。现为夹具增加 SIGTERM 退出处理，并在容器完整验收结束时通过产品入口禁用自启和停止，再复测收尾状态。旧夹具的已停止单元仅对其精确名称清除 failed 记录。

新 Podman 夹具最终经产品停止后，同机只读核对 `Result=success`、`ExecMainStatus=0`、`MainPID=0`、`ActiveState=inactive`，且自启 drop-in 不存在。

## 验证与清理

- 最终 JDK 21 全仓 `mvn -q -B -ntp -o -Dmanaged.test.bash=E:\Program\Git\bin\bash.exe clean verify`：146 份新报告，517 项测试，485 通过、32 条件跳过，0 失败、0 错误。此次 clean 后重新统计，没有混入旧实机或已删除测试类的 XML。
- 离线门禁不会连接真实服务器；跳过项包含未启用的 opt-in 实机入口和 Windows 不执行的真实 Unix 符号链接测试。实机成功依据下列独立运行结果，不使用离线跳过来替代。
- 逐路径最终结果：12 种语言、14 个可部署类型、17 条精确路径全部通过构建/产物检查、产品发布、HTTP 和本地清单登记。
- 其中 Java 源码、Kotlin、C、Go、Rust、C#、PHP、Ruby、JavaScript、Docker、Podman 共 11 条路径还通过故障候选回滚、生命周期、自启切换和客户端重连管理。`FAILED_ROLLED_BACK` 是这些负向候选的预期结果，不是将失败算作成功发布。
- 最新容器整组：2 项通过，0 失败/错误/跳过，418.4 秒；改进前版本也通过 2 项完整测试，耗时 470.4 秒，后续因发现夹具停止行为问题再次验证。
- 最新附加语言：C++、Spring Boot、TypeScript 共 3 项通过，169.7 秒；Go/Rust/.NET/PHP/Ruby 5 项通过，1073 秒；Java 源码 1 项通过，220.2 秒；JavaScript 1 项通过，202.9 秒。
- 新 helper 安装与重复准备：1 项通过，68.85 秒；只读能力/UFW 实际状态断言：1 项通过，25.80 秒。
- 数据库 helper 的 Python 离线回归：13 项通过；它们仍不能替代数据库实机验收。
- 服务器最终检查：无运行或 failed 状态的本次受管 systemd 应用，无运行中的 Docker/Podman 容器；最新 Docker 为 `running=false`、`exit=0`、`restart=no`。公开监听仅 SSH 22，另有系统 DNS 和 containerd 的本机监听。
- 未重装、未添加 swap。最终根盘约用 7.6 GiB、剩余 22 GiB；AppArmor 保持 enabled，UFW 保持 inactive。工具链、受管发布和构建镜像保留，测试应用停止且自启关闭。
- 文档路径检查 118 项有效；AllFile 覆盖 716 个非测试维护文件，缺漏、冗余、重复与排序差异为 0；按仓库行尾配置执行 `git diff --check` 通过。临时 npm 缓存、旧空夹具目录及临时 known-hosts 已清理，SSH/Maven 验证进程均退出。保留任务前既有修改，未提交 Git。

## 尚未覆盖

当前覆盖 12 种语言、14 种可部署项目类型中的 17 条精确运行路径。未将每种语言的所有框架、包管理器和版本组合都纳入验证；例如 Gradle/Kotlin Gradle、pnpm/yarn、Pipenv/Poetry/uv 等未运行的精确架构不能从同语言结果外推。`RECOGNITION_PREVIEW` 只有识别能力，不计入可部署目标。

数据库安装与替换、数据库备份恢复、双服务器迁移、其他发行版和架构、Windows 发布与更新均不在本次全语言部署结论内。测试通过生产门面执行已审阅部署，不等于已完成 Swing 一键 UI、AI 缺项和 DB 编排的组合验收。

## 复现入口

从仓库根目录使用 JDK 21 和 Maven，设置目标 `managed.ssh.host`、`managed.ssh.port`、`managed.ssh.user`，通过秘密环境变量 `WINDOWSTOLINUX_TEST_SSH_PASSWORD` 提供登录凭据。以下开关均默认关闭，普通离线构建不会连接服务器。不要将密码写入 Maven 参数或报告。

| 验收类 | 启用与选择 |
| --- | --- |
| `ManagedDeploymentCapabilityInspectionAcceptanceTest` | `managed.runtime.capabilities=true` |
| `UbuntuManagedEnvironmentSetupAcceptanceTest` | `managed.runtime.environment-provision=true`；复用环境使用 `managed.expect-bare=false` |
| `UbuntuTypedDeploymentAcceptanceTest` | `managed.runtime.typed=true`；本次选择本地 JAR、Python、静态站点、Node.js、Docker 和 Podman 方法，不启用需额外固定 Commit 的 Git/Gradle 方法 |
| `UbuntuEcosystemServiceAcceptanceTest` | `managed.runtime.service=true`，`managed.runtime.service.type=GO_SERVICE,RUST_SERVICE,DOTNET_SERVICE,PHP_SERVICE,RUBY_SERVICE` |
| `EcosystemExtensionProductEntryAcceptanceTest` | `managed.runtime.extension=true`，`managed.runtime.extension.type=java-jdk,kotlin-kotlinc,cmake` |
| `UbuntuAdditionalLanguageAcceptanceTest` | `managed.runtime.additional=true`；本地 Maven/Spring Boot、TypeScript/npm、C++/CMake |

各类通过 `-pl :windowstolinux-app-main -am -Dtest=<验收类或方法> -Dsurefire.failIfNoSpecifiedTests=false test` 执行。选择列表含未知项时直接失败，避免拼写错误导致零项执行而误报成功。本机 root 构建显式使用 `managed.root-build=true`，不代表非 root/sudo 路径已完成实机验收。

新增语言夹具是独立的最小 HTTP 服务；TypeScript 锁定 TypeScript 5.7.3 并在目标机运行 `npm ci`、`tsc`，C++ 按现有产品支持的 C++20 固定 CMake 约束构建。夹具初版的 DOM 类型冲突与 C++17 配置被确定性分析/编译正确拒绝，修正夹具不算产品自动恢复能力。
