# WindowsToLinux

WindowsToLinux 是面向个人和小型自托管场景的 Windows 桌面部署管理工具。首页选择本地源码或 Git 地址及目标服务器后，可一键完成识别、补全、环境准备、构建、发布与健康检查。技术参数放入默认收起的右侧高级选项，每项提供问号帮助。

> [!IMPORTANT]
> 项目仍在开发中，尚未提供正式安装包。helper v7 的代表性部署和历史 helper v5 基线见[四期验收](docs/development/PHASE-4.md#acceptance)；数据库、备份恢复和迁移仍为 `RUNTIME-PENDING`。证据仅覆盖记录中的精确夹具与环境。

## 核心能力

- 首页统一自动识别单应用和多组件，持续输出日志，成功后提供可打开/复制的网址或服务器启动命令；
- 对本地目录和 Git 来源创建固定提交、摘要绑定的源码快照；
- 静态识别 Java、Node.js、Python、静态站点、容器及多种试验运行时；
- 通过 Apache SSHD 检查目标 Linux、固定主机指纹，并执行类型化的环境准备和部署协议；
- 执行候选构建、健康检查、版本切换和失败恢复，不向界面暴露任意 Shell；
- 管理受管应用的状态刷新、启动、停止、重启及开机自启；
- 以卡片浏览和筛选服务器、应用及 AI 模型；拖入目录或粘贴 Git 地址，导航可收起为图标；
- 扫描并原地接管 systemd 服务和 Docker 容器的基本生命周期，外部应用保留原配置，每次操作复核身份；
- 保存不可变普通配置，并通过 Windows Credential Manager 或加密存储处理秘密；
- 编排多组件应用，以及版本化备份、恢复和双服务器离线迁移；
- 允许接入可选 AI Provider 补全有源码依据的参数；无 AI 或建议无效时合并弹窗询问；
- AI 模型经固定请求测试后保存，支持启停和拖动排序；运行时按启用顺序尝试，首个有效结果结束调用，取消会终止整条链；
- 检查、复用或安装系统 DB（PostgreSQL/MySQL/MariaDB/Redis），旧版替换必须另行确认，已有数据不得误初始化；
- 所有静态 UI 文案通过中英文消息表映射，语言和主题切换保留输入。

## 当前状态

[四期动态工具链](docs/development/PHASE-4.md#toolchains) 本地实现与门禁已通过：全版本声明识别与受控构建分离，支持范围集中维护，后续兼容新分支优先追加目录和测试。当前允许分支以代码目录为准；代表组合的实机结果见四期验收，未覆盖版本及环境不外推。

| 范围 | 状态 |
| --- | --- |
| Swing 桌面端 | 一键部署与高级侧栏已接入生命周期、备份、恢复和迁移入口 |
| 本地验证 | JDK 21 多模块 Maven 门禁和前端测试链已建立 |
| Linux 实机证据 | helper v7 已有 Ubuntu 24.04 x86-64 的 25 种源码代表组合及 JAR/静态/容器记录，分项范围见四期验收；不等于 125 场景全部通过，不外推其他发行版 |
| Web 前端 | Vue 3 / TypeScript / Vite 测试骨架，尚无业务后端 |
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
├─ src/web/            # Web 目标模块与当前前端测试骨架
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
5. 启动后，CLASS 模式的数据会写入 `src/app/main/data/`。

为避免日常启动触发全项目测试和打包，可把 `APP` 配置为“先增量编译，再直接启动”：

1. 在 **Settings → Build, Execution, Deployment → Build Tools → Maven → Runner** 中关闭 **Delegate IDE build/run actions to Maven**，**JRE** 使用 **Project JDK（21）**。
2. 在 **Run → Edit Configurations → APP → Before Launch** 中禁用默认 **Build**，添加 **Run Maven Goal**。
3. 选择根目录 `pom.xml`，目标填写 `-pl src/app/main -am compile`；保留主类、模块及其他运行设置。
4. 通过 `APP` 的运行按钮启动。Maven 先检查桌面端及其依赖，复用未变化的编译结果；编译成功后，IDEA 直接从模块的 `target/classes` 启动，编译失败则停止启动。

这项启动前任务只到 `compile`，不会执行测试、打包 JAR 或安装到本地仓库；需要完整测试和制品时仍执行 `mvn.cmd verify`。`compile` 生成 `.class`，`package` 阶段才生成 JAR；JAR 插件默认也会复用输入未变化的现有 JAR。配置与机制见 [JetBrains Before Launch 文档](https://www.jetbrains.com/help/idea/run-debug-configuration-java-application.html)、[Maven 生命周期](https://maven.apache.org/guides/introduction/introduction-to-the-lifecycle.html)及 [JAR 增量打包说明](https://maven.apache.org/plugins/maven-jar-plugin/jar-mojo.html#forceCreation)。

此设置只让 `APP` 启动绕过 IDEA 内置 JPS 构建，并不代表修复了 IDE 本身的“Finished, saving caches”等待问题；手动全项目构建与测试使用 Maven 工具窗口或上述命令。恢复原启动方式时，移除这项 Maven 前置任务并重新启用 **Build** 即可。

`data/` 可能包含服务器资料、操作历史和秘密引用，不要提交、公开或随意复制该目录。原始秘密不会作为普通字段写入 SQLite。

## 安全边界

- 首次 SSH 连接必须由用户核对主机指纹，指纹变化会停止连接；
- Windows 主机只进行静态分析和安全归档，用户项目构建发生在目标 Linux；
- 所有远端修改都受固定协议、类型、路径和资源归属约束；
- AI 不接触原始凭据，也不能绕过确定性校验和用户确认；
- 自动 DNS、代理或负载均衡切换不在当前产品范围内；
- 备份、恢复和迁移的当前代码不代表已经完成真实环境生产验收。

## 文档

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
