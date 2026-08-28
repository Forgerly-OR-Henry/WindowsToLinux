# WindowsToLinux

WindowsToLinux 是面向个人和小型自托管场景的 Windows 桌面部署管理工具。它从本地目录或固定 Git 提交准备源码，在用户明确审阅后连接目标 Linux，完成环境检查、受控构建、发布、健康检查、失败恢复和受管应用生命周期操作。

> [!IMPORTANT]
> 项目仍在开发中，尚未提供正式安装包。当前 helper v5 的真实 Linux、容器和数据库执行仍为 `RUNTIME-PENDING`；请只在可清理的测试环境中评估，不要把本地自动化测试等同于生产验收。

## 核心能力

- 对本地目录和 Git 来源创建固定提交、摘要绑定的源码快照；
- 静态识别 Java、Node.js、Python、静态站点、容器及多种试验运行时；
- 通过 Apache SSHD 检查目标 Linux、固定主机指纹，并执行类型化的环境准备和部署协议；
- 执行候选构建、健康检查、版本切换和失败恢复，不向界面暴露任意 Shell；
- 管理受管应用的状态刷新、启动、停止、重启及开机自启；
- 保存不可变普通配置，并通过 Windows Credential Manager 或加密存储处理秘密；
- 编排多组件应用，以及版本化备份、恢复和双服务器离线迁移；
- 允许接入可选 AI Provider 提供结构化建议，但 AI 不构成部署授权。

## 当前状态

| 范围 | 状态 |
| --- | --- |
| Swing 桌面端 | 已具备部署、生命周期、备份、恢复和迁移入口 |
| 本地验证 | JDK 21 多模块 Maven 门禁和前端测试链已建立 |
| Linux 实机证据 | 历史 helper v3 在部分 Ubuntu 24.04、CentOS Stream 9 精确夹具上通过；不可外推到当前 helper v5 |
| Web 前端 | Vue 3 / TypeScript / Vite 测试骨架，尚无业务后端 |
| 正式发布 | 官网、下载链和 Windows 生产更新/卸载执行器尚未完成 |

完整的能力边界、历史证据和待办事项以 [产品说明书](docs/PRODUCT-MANUAL.md) 与 [开发总纲](docs/DEVELOPMENT.md) 为准。

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
- [开发总纲](docs/DEVELOPMENT.md)：工程状态、路线、质量门禁和完成定义；
- [项目文件结构](docs/File.md)：模块、包、依赖方向和维护规则；
- [四期实机验收模板](docs/development/PHASE-4-RUNTIME-ACCEPTANCE.md)：真实环境证据要求。

## 参与贡献

欢迎提交 Issue、修复、测试、文档和设计建议。贡献代码前请：

1. 从 `main` 创建范围明确的分支；
2. 保持既有模块职责与依赖方向；
3. 为行为变化补充相应测试；
4. 运行受影响门禁，并明确区分本地验证与真实环境证据；
5. 不提交密码、Token、私钥、数据库、诊断数据或本地运行目录。

## 开源协议

本项目采用 [MIT License](LICENSE)。你可以自由使用、修改、分发和商业使用本项目，但必须保留版权和许可声明。
