# WindowsToLinux

WindowsToLinux 是面向个人和小型自托管场景的部署管理工具，提供 Windows 桌面端和第五期回环内部测试 Web 服务台。首页选择本地源码或 Git 地址及目标服务器后，可一键完成识别、补全、环境准备、构建、发布与健康检查。技术参数放入默认收起的右侧高级选项，每项提供问号帮助。

> [!IMPORTANT]
> 项目仍在开发中，尚未提供正式安装包。当前代码已切换 helper v9 的 FHS/SQLite/APP 流程，Ubuntu 24 八个多语言原生项目已通过代表性部署与业务验收，见[多语言记录](test/multi-language/VERIFICATION.md)；其他平台及备份迁移矩阵仍待验证。helper v7 的代表性部署和历史 helper v5 基线见[四期验收](docs/development/PHASE-4.md#acceptance)；数据库、备份恢复和迁移仍为 `RUNTIME-PENDING`。证据仅覆盖记录中的精确夹具与环境。

## 核心能力

- 首页统一自动识别单应用和多组件，持续输出日志，成功后提供可打开/复制的网址或服务器启动命令；
- 对本地目录和 Git 来源创建固定提交、摘要绑定的源码快照；
- 静态识别 Java、Node.js、Python、静态站点、容器及多种试验运行时；
- 通过 Apache SSHD 检查目标 Linux、固定主机指纹，并执行类型化的环境准备和部署协议；
- 执行候选构建、健康检查、版本切换和失败恢复，正常部署使用类型化协议；
- 管理受管应用的状态刷新、启动、停止、重启及开机自启；
- 以卡片浏览和筛选服务器、应用及 AI 模型；拖入目录或粘贴 Git 地址，导航可收起为图标；
- 扫描并原地接管 systemd 服务和 Docker 容器的基本生命周期，外部应用保留原配置，每次操作复核身份；
- 保存不可变普通配置，并通过 Windows Credential Manager 或加密存储处理秘密；
- 编排多组件应用，以及版本化备份、恢复和双服务器离线迁移；
- 允许接入可选 AI Provider 补全有源码依据的参数；无 AI 或建议无效时合并弹窗询问；
- AI 分常规与独立视觉两组，分别启停和排序；图片识别可设置组间优先顺序，识别结果再交常规模型诊断；
- APP 提供用户登录交接、逐项确认命令的[网页 SSH 救援](docs/PRODUCT-MANUAL.md#ssh-rescue)，使用专用浏览器控制 VNC/Web SSH 终端；真实平台与发行版矩阵仍待验收；
- 检查、复用或安装系统 DB（PostgreSQL/MySQL/MariaDB/Redis），旧版替换必须另行确认，已有数据不得误初始化；
- 应用采用 `/opt`、`/etc/opt`、`/var/opt` 默认布局，支持受限自定义路径，以及 SQLite 文件型部署、备份、候选恢复和离线迁移；声明示例与限制见[产品手册](docs/PRODUCT-MANUAL.md#94-fhs-路径与-sqlite)；
- 所有静态 UI 文案通过中英文消息表映射，语言和主题切换保留输入。

## 当前状态

[四期动态工具链](docs/development/PHASE-4.md#toolchains) 本地实现与门禁已通过：全版本声明识别与受控构建分离，支持范围集中维护，后续兼容新分支优先追加目录和测试。当前允许分支以代码目录为准；代表组合的实机结果见四期验收，未覆盖版本及环境不外推。

| 范围 | 状态 |
| --- | --- |
| Swing 桌面端 | 一键部署与高级侧栏已接入生命周期、备份、恢复和迁移入口 |
| 本地验证 | JDK 21 多模块 Maven 门禁和前端测试链已建立 |
| Linux 实机证据 | helper v7 已有 Ubuntu 24.04 x86-64 的 25 种源码代表组合及 JAR/静态/容器记录，分项范围见四期验收；不等于 125 场景全部通过，不外推其他发行版 |
| Web 服务台 | 已接通部署、服务器、应用、AI、备份迁移、任务和设置；仅回环内部测试，真实目标验收待做 |
| 正式发布 | 官网、下载链和 Windows 生产更新/卸载执行器尚未完成 |

完整的能力边界、历史证据和待办事项以 [产品说明书](docs/PRODUCT-MANUAL.md) 与 [开发总纲](docs/development/DEVELOPMENT.md) 为准。

## 技术栈

- Java 21、Swing、Maven；
- Apache SSHD、SQLite、Bouncy Castle；
- Vue 3、TypeScript、Vite、Vitest、Playwright；
- JUnit 5 和模块级架构门禁。

## 项目结构

```text
WindowsToLinux/
├─ src/shared/         # 平台无关的模型、分析、配置、部署、备份和 Linux 契约
├─ src/app/            # Windows 桌面端、数据库、秘密、服务、界面和启动装配
├─ src/web/            # Web 内部服务台、独立持久化与六期结构预留
├─ docs/               # 产品边界、架构、路线和验收记录
├─ test/               # 受控验收夹具
└─ pom.xml             # Maven 多模块构建入口
```

详细模块职责和依赖方向见 [项目文件结构](docs/File.md)。

## 开发环境

必需：

- Windows 开发机；
- JDK 21；
- 系统安装的 Maven；
- Git。

仅开发 Web 前端时还需要 Node.js `>=22 <25` 和 npm。真实部署或验收另需专用 Linux 测试服务器；不要直接使用承载重要业务的主机。

## 构建与验证

在 PowerShell 中从仓库根目录执行：

```powershell
mvn.cmd -version
mvn.cmd verify
```

`mvn.cmd -version` 必须显示 Maven 正在使用 JDK 21。项目使用系统 Maven 本地仓库，不以 Maven Wrapper 作为自身构建入口。

前端验证：

```powershell
Set-Location src/web/frontend
npm.cmd ci
npm.cmd run typecheck
npm.cmd run test
npm.cmd run build
npm.cmd run install:e2e-browser
npm.cmd run test:e2e
```

## 运行桌面端

当前仓库尚未提供正式安装包，推荐使用 IntelliJ IDEA：

1. 使用 JDK 21 打开根目录的 `pom.xml`，等待 Maven 导入完成；
2. 创建 **Application** 运行配置；
3. 主类选择 `gold.debug.windowstolinux.app.main.AppMain`；
4. 模块选择 `windowstolinux-app-main`；
5. 启动后，CLASS 模式的数据会写入 DB 模块下的 `src/app/db/data/`。

为避免日常启动触发全项目测试和打包，可把 `APP` 配置为“先增量编译，再直接启动”：

1. 在 **Settings → Build, Execution, Deployment → Build Tools → Maven → Runner** 中关闭 **Delegate IDE build/run actions to Maven**，**JRE** 使用 **Project JDK（21）**。
2. 在 **Run → Edit Configurations → APP → Before Launch** 中禁用默认 **Build**，添加 **Run Maven Goal**。
3. 选择根目录 `pom.xml`，目标填写 `-pl src/app/main -am compile`；保留主类、模块及其他运行设置。
4. 通过 `APP` 的运行按钮启动。Maven 先检查桌面端及其依赖，复用未变化的编译结果；编译成功后，IDEA 直接从模块的 `target/classes` 启动，编译失败则停止启动。

这项启动前任务只到 `compile`，不会执行测试、打包 JAR 或安装到本地仓库；需要完整测试和制品时仍执行 `mvn.cmd verify`。`compile` 生成 `.class`，`package` 阶段才生成 JAR；JAR 插件默认也会复用输入未变化的现有 JAR。配置与机制见 [JetBrains Before Launch 文档](https://www.jetbrains.com/help/idea/run-debug-configuration-java-application.html)、[Maven 生命周期](https://maven.apache.org/guides/introduction/introduction-to-the-lifecycle.html)及 [JAR 增量打包说明](https://maven.apache.org/plugins/maven-jar-plugin/jar-mojo.html#forceCreation)。

此设置只让 `APP` 启动绕过 IDEA 内置 JPS 构建，并不代表修复了 IDE 本身的“Finished, saving caches”等待问题；手动全项目构建与测试使用 Maven 工具窗口或上述命令。恢复原启动方式时，移除这项 Maven 前置任务并重新启用 **Build** 即可。

`data/` 可能包含服务器资料、操作历史和秘密引用，不要提交、公开或随意复制该目录。原始秘密不会作为普通字段写入 SQLite。

## 运行 Web 内部服务台

Web 使用 JDK 21、Spring Boot 4.1.1、Spring MVC、MyBatis-Plus 3.5.17 和 SQLite。先在仓库根完成构建，Maven 会安装构建专用 Node、执行 npm ci、前端类型检查和 Vite 构建：

```powershell
mvn.cmd -pl src/web/main -am package -DskipTests
```

在 IntelliJ IDEA 中直接运行 `gold.debug.windowstolinux.web.main.WebMain`，模块选择 `windowstolinux-web-main`、JDK 21。程序参数和 VM 参数留空，无需配置主密钥环境变量。前端修改后重新执行上述构建；运行时不启动 Vite 或 npm。

运行配置统一位于 [`application.yml`](src/web/main/src/main/resources/application.yml)：

```yaml
server:
  address: 127.0.0.1
  port: 8765
w2l:
  mode: internal-test
  storage:
    root: db.data
  secrets:
    directory: ${user.home}/.windowstolinux-web-test-keys
```

`db.data` 在 CLASS 模式表示 `src/web/db/data`；在 JAR 模式表示实际加载的 Web DB JAR 同级 `data`。`db.data/abc` 表示其下 `abc`，也支持绝对路径。解析以 DB 模块固定类的代码来源为准，不依赖启动工作目录；不支持 `db/data`、APP 模式或嵌套 DB JAR。只配置数据根，主库固定为 `windowstolinuxweb.db`，上传文件和备份分别位于固定的 `files/`、`backups/`，不能单独配置。

构建产物位于 `src/web/main/target/web`，复制完整目录即可分发：

```text
web/
├─ web.jar
└─ lib/
   ├─ windowstolinux-web-db-版本.jar
   ├─ 其他依赖.jar
   └─ data/                         # 首次运行自动建立
      ├─ windowstolinuxweb.db
      ├─ files/
      └─ backups/
```

在分发目录运行 `java -jar web.jar`。修改源码 YAML 后重新构建；分发后的配置也可使用 Spring Boot 标准的外置 `application.yml`（放在启动工作目录或其 `config/` 下），仅覆盖需要修改的设置。YAML 的查找遵循 Spring Boot 规则，`db.data` 的解析始终锚定 DB 模块，两者互不混淆。

浏览器打开 [内部服务台](http://127.0.0.1:8765)。五期仅允许 `127.0.0.1`，没有登录和公网部署。端口、HTTP 限制、上传配额、任务并发、超时和临时源码保留时间在 YAML 配置。关闭页面不取消后台任务，进程重启后未完成任务要求重新验证。

首次启动自动生成主密钥，保存在当前用户专用目录，Windows 使用当前用户 ACL，POSIX 使用目录 700、文件 600。数据根保存非秘密的 `.master-key-id` 关联标识；密钥不在数据根、YAML、数据库或普通备份内。重启复用原密钥；已有数据库缺少或无法验证原密钥时停止启动，不生成新密钥覆盖。搬迁数据时保留整个数据根，跨机搬迁另外安全转移对应的密钥文件。

详细限制、冷备份回滚和验证记录见[五期文档](docs/development/PHASE-5.md)。六期账号、工作区、授权、会话、审计和发布表及包仍只预留结构。

## 网站与 APP

已部署应用统一保留网站、长期后台 APP 和一次性工具。长期进程提供启停与自启动；一次性工具显示安装可用性和可复制的受管命令。HTTP(S) 服务显示网址，TCP/UDP 服务显示协议和地址。分类以审阅后的服务范围为准，与语言、主动联网或是否填写网址分开。

`windowstolinux-application.properties` 声明运行方式、参数、工作目录、网络端点、验证入口、C/C++ 配套产物和外部只读输入；桌面与 Web 共用契约。写入继续受 FHS/自定义路径边界约束。新 APP/UDP/容器运行矩阵仍为 `RUNTIME-PENDING`，不扩大现有语言支持等级。完整示例见[产品说明书](docs/PRODUCT-MANUAL.md#已部署应用后台-app-与一次性工具)。

## 安全边界

- 首次 SSH 连接必须由用户核对主机指纹，指纹变化会停止连接；
- Windows 主机只进行静态分析和安全归档，用户项目构建发生在目标 Linux；
- 所有远端修改都受固定协议、类型、路径和资源归属约束；
- AI 不接触原始凭据，也不能绕过确定性校验和用户确认；
- 自动 DNS、代理或负载均衡切换不在当前产品范围内；
- 备份、恢复和迁移的当前代码不代表已经完成真实环境生产验收。

## 文档

正式文档按功能、模块职责和使用场景维护。开发文档保留总纲与一至六期，各期内部按功能查阅；日期和版本只限定验证证据，不作为正文分类。

- [产品说明书](docs/PRODUCT-MANUAL.md)：功能、使用流程、安全边界和限制；
- [开发总纲与文档导航](docs/development/DEVELOPMENT.md)：六期路线、模块演进索引、通用规则；各期主文档包含功能增量与验收；
- [项目文件结构](docs/File.md)：模块、包、依赖方向和维护规则；
- [四期面向新手的一体化自动部署](docs/development/PHASE-4.md#automatic)：新手界面、一体化自动部署、AI 补全与 DB 管理的已确认实施目标；
- [四期验收方法与证据](docs/development/PHASE-4.md#acceptance-methods)：真实环境证据要求。
- [四期历史部署基线](docs/development/PHASE-4.md#acceptance-baseline)：同机部署矩阵、实际修复和验证边界。

## 参与贡献

欢迎提交 Issue、修复、测试、文档和设计建议。贡献代码前请：

1. 从 `main` 创建范围明确的分支；
2. 保持既有模块职责与依赖方向；
3. 为行为变化补充相应测试；
4. 运行受影响门禁，并明确区分本地验证与真实环境证据；
5. 不提交密码、Token、私钥、数据库、诊断数据或本地运行目录。

## 开源协议

本项目采用 [MIT License](LICENSE)。你可以自由使用、修改、分发和商业使用本项目，但必须保留版权和许可声明。
