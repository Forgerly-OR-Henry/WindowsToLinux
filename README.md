# WindowsToLinux

WindowsToLinux 是面向个人和小型自托管场景的部署管理工具，以 Windows 桌面 APP 提供通用能力，业务由 Mod 扩展。当前检出已完成 [1.2 期 APP 整体调整](docs/development/PHASE-1.2.md)的源码实施及本机适用验收；阶段状态和验证边界以该文档为准，尚未提供正式安装包。

## 当前平台能力

- H2 平台数据库、主密码设置与锁定、Windows 当前用户保护和任务所属秘密引用。
- 服务器资料保存、独立 SSH 认证检查，以及检查失败后直接进入同一服务器的网页终端救援；保存不会自动连接，救援不依赖业务 Mod。
- 一个模型清单，分别配置文字/视觉、文件音频转写和实时转写地址。保存与验证分离：保存不发送探测请求，各能力独立验证；纯语音模型可以保存，用途从符合已验证能力的模型中明确选择。
- 统一 `FileService` 提供本机及受管 SFTP 文件操作，区分文件目标与链接本身。复制、移动、传输、回收及许可都有原任务归属；未知结果先核对，不能通过重放掩盖中断。
- 中英文界面、主题与输入状态保留，中央任务、进度、日志与人工确认；部署的审批和授权策略由部署工作区独立设置。
- Mod 发现、已选版本启动、公开 API 与依赖准入、页面发布、Schema 准备及迁移、停止、安装和数据清理。发行安装与普通维护发现不自动启用；明确开启 CLASS 自动加载时，新调试身份按唯一有效构建建立首次启用选择，必要准备和已有用户选择仍受原流程约束。

官方部署 Mod 的验证只覆盖已记录的本机及受控协议环境。真实 Linux、真实语音提供方与云控制台仍单独待验收；本机 HTTP/WebSocket、SSH/SFTP 夹具通过不等于生产环境全场景通过。商店连同下载及真实客户端接线已通过1.3 S5本机受控HTTP与桌面验收，正式商店服务未验证；备份迁移 Mod、官网投稿/审核/公开发布和正式发行打包仍属后续范围。

详细使用流程见[产品说明书](docs/PRODUCT-MANUAL.md)，作者契约见 [Mod 开发规范](docs/Mod.md)，运行目录及安全合同见[运行约定](docs/Runtime.md)。

## 当前项目结构

```text
WindowsToLinux/
├─ src/appapi/         # 公开 Mod API，包括 file、ai、task 等能力
├─ src/app/            # config、ai、db、file、linux、main、mod、secret、service、ui、windows
├─ src/mod/deployment/ # 官方部署 Mod
├─ src/moddev/         # 独立 Mod 开发与定向验证，不进入默认根构建/加载
├─ docs/               # 产品、架构、开发计划和验收记录
├─ test/               # 受控验证夹具
├─ history-src/        # 冻结的旧源码，不进入活动构建
└─ pom.xml             # 当前 Maven Reactor
```

APP 按实际功能使用浅层包，Linux 保留 `ssh/connect`、`ssh/recover` 和独立 `sftp`；Mod 只统一第一层功能包，内部自主组织。实际目录与依赖图见 [File.md](docs/File.md)，逐文件索引见 [AllFile.md](docs/AllFile.md)。

通用 AI 代码按 `model`、`vision`、`voice`、`http` 分工：常规文字及共用消息调用归 `model`，图像专属处理归 `vision`，音频转写归 `voice`，公共网络边界归 `http`；SSH 救援专用提示、响应解析和动作类型归 `app/linux/ssh/recover`，完整救援也统一归该包，由启动装配注入已授权的通用 AI、任务和存储能力；Service 不保留救援专属包。文字与图像共用调用实现，不另建 `text` 包。

## 开发与验证

需要 Windows、JDK 21、系统 Maven 和 Git。当前根不包含旧 Web 前端模块，不需要为当前 APP 安装 Node.js 前端构建链。

在仓库根使用 PowerShell：

```powershell
mvn.cmd -version
mvn.cmd -B -ntp clean verify -Pidea-check
```

第一条必须显示 Maven 使用 JDK 21。`clean verify -Pidea-check` 清除旧产物，执行完整测试、打包、架构/Javadoc 和 IDEA 格式检查。Main 与官方部署 Mod 的 Surefire 显式从 reactor 的 classes 加载 APP/appapi，测试支持也使用 Main 的 test-classes，避免 package 阶段造成 DB 来自 JAR、Main 来自 classes 的混合布局；生产启动识别仍拒绝不一致布局，发行布局由隔离入口测试验证。原生 DPAPI、回收、Swing 和浏览器测试需要实际当前用户的本机环境，不能把受限环境下的失败改成跳过。验证入口和记录方式见 [1.2 期验证约定](docs/development/PHASE-1.2.md#54-验证入口与记录方式)。

Windows 的部署辅助脚本测试需要可运行的 Git Bash 和 Python 3。使用现有测试属性指定两个解释器的完整路径，避免 `/bin/bash` 或 Python 商店别名；下例路径需换成本机实际安装位置。`DeploymentHelperBehaviorTest` 会从 Maven 运行当前数据库、存储和容器生命周期的 Python/Bash 行为测试，使用测试专有临时目录并在结束时关闭验证进程。Windows 夹具显式使用 MSYS 链接实现并通过同一 Bash 核对链接目标；Linux 目录属主与权限元数据、包管理器、数据库客户端和容器引擎使用明确受控边界，文件内容、目录创建及清理仍实际执行。测试不会安装本机软件或启动真实容器，也不证明真实 Linux 权限执行。

若本机 JDK 21 的回环初始化在 `PipeImpl` / `UnixDomainSockets` 报 `Invalid argument`，为单次验证进程设置 `JAVA_TOOL_OPTIONS`，将 `java.io.tmpdir` 和 `jdk.net.unixdomain.tmpdir` 指向已创建的短路径、可写临时目录。验证后清理本次目录；不写入产品或系统配置。

```powershell
mvn.cmd -B -ntp clean verify -Pidea-check '-Dmanaged.test.bash=E:/Program/Git/bin/bash.exe' '-Dmanaged.test.python=E:/Program/Python/Python3.12/python.exe'
```

<a id="运行桌面端"></a>

## 运行桌面 APP

推荐使用 IntelliJ IDEA：

1. 用 JDK 21 打开根 `pom.xml` 并完成 Maven 导入。
2. 创建 Application 配置，主类为 `gold.debug.windowstolinux.app.main.AppMain`，模块为 `windowstolinux-app-main`。
3. Config 模块资源目录中的 [config.yaml](src/app/config/src/main/resources/gold/debug/windowstolinux/app/config/config.yaml) 默认在 CLASS 关闭 Mod，仅运行主 APP；填写说明见 [Config.md](docs/Config.md)。需要开发 Mod 时把 class.mod.auto-load 改为 true，重新构建并重启，VM 参数添加 `-Dwtl.workspace=<本仓库绝对路径>`；含空格时给整项加双引号。该值用于核验开发 Mod 来源，必须与实际 APP、DB 模块工作区一致。自动加载开关仅允许 class 区填写，JAR/APP 固定执行 Mod 加载流程。
4. 只启动主 APP 时，启动前运行 Maven `-pl src/app/main -am process-classes`；开启 Mod 调试时还须构建 `src/mod` 中本次参与集成的项目及其依赖，也可在根执行 `process-classes` 生成默认集成区的有效描述。`src/moddev` 不随根构建或运行发现参与。使用此 Maven 前置任务时，可以关闭该运行配置的 IDEA Build 前置任务。

CLASS 数据根默认 `src/app/db/data/`，通过 Config 模块的 classpath YAML 及已验证路径锚点解析；YAML 随 Config JAR 发布，程序根不用放配置文件。程序根来自已验证工作区，不由任意启动目录或数据目录覆盖参数决定。CLASS/JAR 混合来源会被拒绝；JAR 与 APP 的正式布局见 [APP.md](docs/APP.md)。

首次启动在设置页配置主密码。模型先保存，再分别验证需要的能力及配置用途；完整文件转写和实时转写分别验证，验证会实际发送所选样本音频。纯语音模型可以不填写文字输出上限。服务器检查失败时，可以从原检查结果发起同目标救援，命令仍需适用的批准。

开发库重建按 [1.2 期数据库调整](docs/development/PHASE-1.2.md#database-reset)处理：先关闭 APP 和占用该库的验证进程，核实运行模式、精确数据目录及文件归属，再按已确认范围清理开发库与配对材料，使用实际入口重建并重开验证。`windowstolinux.mv.db` 与 `db-access.bin` 有配对关系；已有库缺少或无法验证原材料时必须停止，不能生成新材料冒充恢复。用户密码不写入源码、文档或验收证据。

Mod 主模块可继承 [.mvn/wtlmod-author-parent/pom.xml](.mvn/wtlmod-author-parent/pom.xml)接入 CLASS 描述生成。启用、选中版本与构建成功是不同状态；作者接线和校验见 [APP 作者构建约定](docs/APP.md#82-作者构建和校验工具)。

独立开发使用 [src/moddev/README.md](src/moddev/README.md)的定向入口，主模块和同作者从模块保持一份源码。完成本项目验证后移入 `src/mod`、更新聚合声明并按新路径重建，才参与真实 APP 集成调试。根默认构建不聚合 ModDev，也不执行其业务测试；正式结构与格式检查覆盖两个区域。

## 安全与验收边界

- 首次 SSH 连接核对主机指纹，变化时停止连接。
- 远端修改受固定协议、实际路径、任务权限和资源归属约束；AI 不能绕过确定性校验、独立审批或人工确认。
- 用户文件普通删除时，可回收则选择回收或永久删除，不可回收则确认永久删除；前置许可仍优先回收，不能静默降级。
- 锁定会使旧确认失效；未知副作用、未闭资源和配对材料故障保留原证据并要求核对。
- 不提交密码、Token、私钥、数据库、诊断数据或本地运行目录。

<a id="运行-web-内部服务台"></a>

## 0.x 历史源码与验证

旧 `shared/web` 和旧部署、备份实现已冻结在 [history-src](history-src/ARCHIVE.md)，不参与当前根构建，不在冻结目录执行构建或修改。旧 Web 服务台的 Spring Boot/SQLite、前端构建链和运行命令属于历史版本；原配置仅可从[冻结的 application.yml](history-src/src/web/main/src/main/resources/application.yml)查阅，不能作为当前 APP 配置。

helper v9 曾在 Ubuntu 24 上完成八个多语言原生项目的代表性部署及业务验收，见[多语言记录](test/multi-language/VERIFICATION.md)；其他历史工具链证据见 [0.4 期验收](docs/development/PHASE-0.4.md#acceptance)和 [0.5 期 Web 文档](docs/development/PHASE-0.5.md)。这些记录只证明当时的精确夹具与环境，不替代 1.2 期真实远端、回收条件或语音提供方验收。

## 文档与贡献

正式文档按[唯一职责](docs/development/DEVELOPMENT.md#documentation)维护：

- [产品说明书](docs/PRODUCT-MANUAL.md)：功能、使用流程和边界。
- [1.2 期开发文档](docs/development/PHASE-1.2.md)：文件、语音、救援、数据库调整及逐项验收。
- [1.3 期执行计划](docs/development/PHASE-1.3.md)：窗口与工作区先在 P0 独立处理，再按 S0～S6 承接 ModDev、可选依赖与 Git、部署完整迁移、文件管理、商店连同下载及集成验收；完整本地验收通过即可交付，真机验证独立安排。P0、正式 S0～S5 已完成适用本地验收，独立可选 Git、受管素材交接、部署迁移及独立文件管理已有有效证据；S6 综合入口与收尾继续推进并本地提交。
- [APP 智能助手开发规划](docs/development/APP-ASSISTANT-DEVELOPMENT.md)：全软件操作助手与 APP/Mod 业务操作 API 的独立后期规划；不纳入 1.3，尚未排期或实施。
- [开发总纲](docs/development/DEVELOPMENT.md)：各期导航、工程演进和通用规则。
- [File.md](docs/File.md)、[Mod.md](docs/Mod.md)、[APP.md](docs/APP.md)、[Runtime.md](docs/Runtime.md)：结构、作者、交付和运行合同。
- [AllFile.md](docs/AllFile.md)：当前非测试生产文件索引。

欢迎提交 Issue、修复、测试和设计建议。贡献代码时从 `main` 创建范围明确的分支，遵守模块职责，运行受影响验证，并区分本机、受控协议与真实环境结果。

## 开源协议

本项目采用 [MIT License](LICENSE)，允许自由使用、修改、分发和商业使用，须保留版权和许可声明。
