# WindowsToLinux 项目文件结构

## 文档信息

- 文档版本：`2.2.2-bilingual-comments`
- 文档状态：**正式目标模块、职责、依赖方向和叶子模块内部目标包结构已确认；28 个 Maven reactor 工程及一期已有代码的职责迁移已落地；桌面端采用英文基准与简体中文 UI 映射，代码注释采用中英双语；一期 Ubuntu 24.04 真实验收结论保持，本次改造未重新连接远程主机**
- 已确认范围：`shared` 共用模块、`app` Windows 桌面应用模块、`web` Web 应用模块
- 已确认能力边界：受管应用生命周期复用既有模块，不新增独立 Maven 模块
- 更新日期：2026-08-12
- 开发总纲：[DEVELOPMENT.md](DEVELOPMENT.md)

> 本文是正式目标目录、模块职责、依赖方向和内部包结构的来源。当前 reactor 已包含根工程、3 个聚合模块和 24 个叶子模块，共 28 个 POM。`shared/source` 和 `shared/linux-sshd` 及一期相关代码迁移已落地；`shared/config` 已进入 reactor，但按当前范围仅有 POM 和依赖边界，没有配置 API、空包或数据库表。`shared/git`、`shared/backup` 和 Web Java 叶子模块仍只保留 POM。

## 1. 完整目标结构

当前已确认的完整目标结构如下。叶子模块下直接展示的是对应 Java 根包的目标子包，省略重复的 Maven `src/main/java` 路径；物理目录模板见第 3.1 节。`pom.xml` 作为聚合入口固定置顶，其余模块和内部包按英文名称排序。

```text
WindowsToLinux/
├─ pom.xml
├─ docs/
│  ├─ development/
│  ├─ DEVELOPMENT.md
│  └─ File.md
└─ src/
   ├─ app/
   │  ├─ pom.xml              桌面应用模块聚合入口
   │  ├─ db/                  SQLite 数据访问与迁移
   │  │  ├─ connection/       SQLite 连接和事务基础
   │  │  ├─ entity/           仅供桌面持久化使用的存储记录
   │  │  ├─ migration/        版本化表结构迁移
   │  │  └─ repository/       按聚合划分的数据访问实现
   │  ├─ main/                启动入口、运行模式和模块装配
   │  │  ├─ bootstrap/        桌面应用启动与模块装配
   │  │  ├─ config/           启动配置和固定目录解析
   │  │  └─ runtime/          CLASS、JAR、APP 运行模式识别
   │  ├─ secret/              敏感信息、加密和凭据管理
   │  │  ├─ api/              秘密存取公共契约与异常
   │  │  ├─ crypto/           Argon2id、AES-GCM 和密钥处理
   │  │  ├─ store/            加密数据库秘密存储
   │  │  └─ windows/          Windows Credential Manager 适配
   │  ├─ service/             桌面业务流程整合
   │  │  ├─ ai/               AI 配置与分析用例
   │  │  ├─ concurrency/      同服务器互斥、后台执行和取消
   │  │  ├─ deployment/       部署用例
   │  │  ├─ environment/      环境准备用例
   │  │  ├─ lifecycle/        受管应用和生命周期用例
   │  │  ├─ server/           服务器资料、信任和能力用例
   │  │  └─ source/           源码准备用例
   │  ├─ ui/                  桌面界面与用户交互
   │  │  ├─ ai/               AI 配置和解释结果界面
   │  │  ├─ appearance/       主题、外观和系统偏好
   │  │  ├─ component/        可复用的桌面界面组件
   │  │  ├─ deployment/       源码选择、分析、确认和部署交互
   │  │  ├─ i18n/             消息目录和本地化边界
   │  │  ├─ managed/          受管应用列表与生命周期交互
   │  │  ├─ server/           服务器配置和能力验证界面
   │  │  ├─ settings/         桌面设置界面
   │  │  └─ shell/            主窗口、导航和页面装配
   │  └─ windows/             Windows 文件、打包和本机环境采集
   │     ├─ environment/      Windows 本机环境事实采集
   │     ├─ packaging/        桌面安装包和升级相关平台能力
   │     └─ workspace/        工作目录与本地文件边界
   ├─ shared/
   │  ├─ pom.xml              共用模块聚合入口
   │  ├─ ai/                  AI 调用、脱敏与结果解析
   │  │  ├─ client/           HTTP 客户端和调用边界
   │  │  ├─ collaboration/    后续多模型角色和冲突处理
   │  │  ├─ parser/           结构化结果解析与校验
   │  │  ├─ prompt/           结构化提示构建
   │  │  ├─ provider/         Provider 配置和协议适配
   │  │  └─ redaction/        最小上下文与脱敏
   │  ├─ analyze/             项目语言识别与环境推导
   │  │  ├─ build/
   │  │  │  ├─ gradle/        Gradle 与 Gradle Wrapper 分析
   │  │  │  ├─ maven/         Maven、POM 与 Maven Wrapper 分析
   │  │  │  ├─ node/          npm、pnpm、yarn、锁文件和脚本事实分析
   │  │  │  └─ python/        Python 依赖、锁定方式和虚拟环境事实分析
   │  │  ├─ core/             分析流程、分析器注册与结果汇总
   │  │  ├─ framework/
   │  │  │  └─ springboot/    Spring Boot 框架事实与风险识别
   │  │  ├─ language/
   │  │  │  ├─ java/          Java 语言事实识别
   │  │  │  ├─ node/          Node.js、JavaScript 和 TypeScript 生态识别
   │  │  │  └─ python/        Python 语言生态识别
   │  │  ├─ source/           有界源码遍历、安全文本读取和源码树事实
   │  │  └─ workload/
   │  │     ├─ container/     Dockerfile 单容器工作负载识别
   │  │     └─ staticweb/     静态站点工作负载识别
   │  ├─ backup/              备份、恢复与跨服务器迁移
   │  │  ├─ format/           版本化备份格式与归档成员
   │  │  ├─ manifest/         环境、数据和恢复清单
   │  │  ├─ migration/        跨服务器迁移编排
   │  │  ├─ restore/          恢复计划与候选恢复编排
   │  │  └─ validation/       完整性、安全性和兼容性校验
   │  ├─ config/              共用配置定义、校验、版本与差异规则
   │  │  ├─ definition/       类型化配置定义、适用范围和默认规则
   │  │  ├─ revision/         不可变快照、版本、摘要和差异规则
   │  │  ├─ secretref/        不透明秘密引用类型，不读取秘密内容
   │  │  └─ validation/       完整性、兼容性和约束校验
   │  ├─ deploy/              环境准备与部署流程编排
   │  │  ├─ adapter/
   │  │  │  ├─ container/     单容器部署形态
   │  │  │  ├─ javajar/       普通 Java JAR 部署形态
   │  │  │  ├─ node/          Node.js 服务部署形态
   │  │  │  ├─ python/        Python 服务部署形态
   │  │  │  ├─ springboot/    Maven 或 Gradle Spring Boot 部署形态
   │  │  │  └─ staticweb/     静态站点部署形态
   │  │  ├─ compatibility/    支持矩阵匹配与正式支持范围校验
   │  │  ├─ environment/      环境准备流程
   │  │  ├─ lifecycle/        受管应用生命周期编排
   │  │  ├─ plan/             部署计划生成与校验
   │  │  ├─ result/           部署事件与结果转换
   │  │  └─ transaction/      上传、构建、发布、健康和回滚事务
   │  ├─ git/                 Git 项目拉取与版本准备
   │  │  ├─ reference/        分支、Tag 和 Commit 固定
   │  │  ├─ remote/           仓库来源和远端访问
   │  │  ├─ snapshot/         只读源码快照准备
   │  │  └─ validation/       来源、引用和工作树边界校验
   │  ├─ linux/               Linux 远程能力公共契约
   │  │  ├─ build/            各构建体系的请求、结果和执行契约
   │  │  ├─ capability/       发行版、CPU、工具和运行能力采集契约
   │  │  ├─ connection/       Linux Gateway、会话、端点、凭据和主机信任契约
   │  │  ├─ distro/           发行版事实与环境准备契约
   │  │  ├─ protocol/         类型化高权限操作及结果契约
   │  │  ├─ runtime/          systemd、Docker、Podman 和静态服务生命周期契约
   │  │  └─ transfer/         受控传输请求与结果契约
   │  ├─ linux-sshd/          Apache SSHD Linux 远程能力实现
   │  │  ├─ build/            Maven、Gradle、Node.js、Python 和容器远程构建实现
   │  │  ├─ capability/       通过受控远程探测采集 Linux 能力
   │  │  ├─ connection/       Apache SSHD 客户端、会话、认证和主机指纹实现
   │  │  ├─ distro/           apt、dnf、软件源和安全机制实现
   │  │  ├─ protocol/         高权限辅助程序生成、安装和类型化调用实现
   │  │  ├─ runtime/          systemd、Docker、Podman 和静态服务远程实现
   │  │  └─ transfer/         Apache SSHD SFTP 与受控传输实现
   │  ├─ model/               部署数据模型与属性定义
   │  │  ├─ analysis/         项目分析结果、证据、冲突和支持判断
   │  │  ├─ archive/          源码包、备份包和摘要描述模型
   │  │  ├─ deployment/       部署请求、计划、状态和结果模型
   │  │  ├─ health/           健康检查与访问地址模型
   │  │  ├─ lifecycle/        运行状态、自启状态和生命周期动作模型
   │  │  ├─ managed/          受管应用、受管身份和运行配置模型
   │  │  ├─ message/          本地化消息模型
   │  │  ├─ project/          源码项目、组件、语言、构建体系和框架事实
   │  │  ├─ security/         凭据模式、确认和安全相关纯数据模型
   │  │  └─ server/           服务器身份与能力模型
   │  └─ source/              平台无关的源码快照、归档与安全校验
   │     ├─ archive/          源码归档创建与读取
   │     ├─ manifest/         清单、摘要和排除项
   │     ├─ snapshot/         规范化源码快照
   │     └─ validation/       路径、链接、特殊文件和大小校验
   └─ web/
      ├─ pom.xml              Web 后端模块聚合入口
      ├─ api/                 REST API、SSE 与错误转换
      │  ├─ ai/               AI 配置与分析接口
      │  ├─ backup/           备份、恢复与迁移接口
      │  ├─ deployment/       分析、计划和部署接口
      │  ├─ error/            安全错误转换
      │  ├─ managed/          受管应用接口
      │  ├─ server/           服务器资料与能力接口
      │  └─ task/             持久化任务与事件接口
      ├─ auth/                登录、会话与访问安全
      │  ├─ csrf/             CSRF 防护
      │  ├─ initialization/   唯一管理员初始化
      │  ├─ login/            登录流程与限速协调
      │  ├─ ratelimit/        认证请求限速
      │  └─ session/          服务端会话与超时策略
      ├─ db/                  SQLite 数据访问与迁移
      │  ├─ connection/       SQLite 连接和事务基础
      │  ├─ entity/           Web 持久化记录
      │  ├─ migration/        版本化表结构迁移
      │  └─ repository/       按聚合划分的数据访问实现
      ├─ file/                上传下载与受管文件目录
      │  ├─ cleanup/          临时文件清理
      │  ├─ download/         受控流式下载
      │  ├─ quota/            上传和工作区配额
      │  ├─ upload/           受控流式上传
      │  └─ workspace/        受管服务端工作目录
      ├─ frontend/            Vue 前端界面
      │  └─ src/
      │     ├─ features/
      │     │  ├─ ai/
      │     │  ├─ backup/
      │     │  ├─ deployment/
      │     │  ├─ managed/
      │     │  ├─ server/
      │     │  ├─ settings/
      │     │  └─ task/
      │     └─ shared/
      │        ├─ api/
      │        ├─ i18n/
      │        └─ ui/
      ├─ main/                后端启动、配置与模块装配
      │  ├─ bootstrap/        Spring Boot 启动与模块装配
      │  ├─ config/           服务端启动配置
      │  └─ runtime/          后端运行时与生命周期
      ├─ secret/              加密、主密钥与服务端凭据
      │  ├─ credential/       服务端凭据存取
      │  ├─ crypto/           加密与密钥处理
      │  ├─ masterkey/        主密钥生命周期
      │  └─ password/         管理员密码哈希
      ├─ service/             Web 业务流程整合
      │  ├─ ai/               AI 配置与分析用例
      │  ├─ backup/           备份用例
      │  ├─ deployment/       部署用例
      │  ├─ lifecycle/        受管应用生命周期用例
      │  ├─ migration/        迁移用例
      │  ├─ server/           服务器用例
      │  └─ source/           源码准备用例
      └─ task/                持久化后台任务与调度
         ├─ event/            持久化任务事件
         ├─ model/            任务状态模型
         ├─ recovery/         重启恢复
         ├─ repository/       任务持久化接口
         └─ scheduler/        调度、互斥和取消
```

- 项目根目录的 [pom.xml](../pom.xml) 作为 Maven 父工程和总聚合入口。
- `src/app/pom.xml` 作为桌面应用模块聚合入口，使用 Maven `pom` 打包类型，不放业务源码。
- `db`、`main`、`secret`、`service`、`ui` 和 `windows` 都是独立 Maven 叶子模块。
- `src/shared/pom.xml` 作为共用模块聚合入口，使用 Maven `pom` 打包类型，不放业务源码。
- `ai`、`analyze`、`backup`、`config`、`deploy`、`git`、`linux`、`linux-sshd`、`model` 和 `source` 都是正式目标 Maven 叶子模块。
- `shared/config` 的 artifactId 为 `windowstolinux-shared-config`，Java 根包为 `gold.debug.windowstolinux.shared.config`；`shared/linux-sshd` 的 artifactId 为 `windowstolinux-shared-linux-sshd`，Java 根包为 `gold.debug.windowstolinux.shared.linux.sshd`；`shared/source` 的 artifactId 为 `windowstolinux-shared-source`，Java 根包为 `gold.debug.windowstolinux.shared.source`。
- `src/web/pom.xml` 作为 Web 后端模块聚合入口，使用 Maven `pom` 打包类型，不放业务源码。
- `api`、`auth`、`db`、`file`、`main`、`secret`、`service` 和 `task` 都是独立 Maven 叶子模块。
- `frontend` 是由 `package.json` 管理的 Vue 3、TypeScript 和 Vite 工程，不套用 Java Maven 叶子模块目录；其生产构建产物由 `web/main` 在打包时整合。`web/frontend/src/shared` 是前端复用目录，不是 `src/shared` Maven 聚合模块。
- Maven 坐标统一使用 `gold.debug.windowstolinux`，根父工程为 `windowstolinux-parent:0.1.0-SNAPSHOT`，编译目标为 Java 21。
- WindowsToLinux 自身构建使用开发机的系统 Maven 和系统本地仓库；项目 POM 不声明仓库位置，不创建项目专用 Maven 仓库，也不新增 Maven Wrapper 作为本项目构建入口。
- 构建插件确需额外构建期依赖时，在根 POM 对应插件的 `<dependencies>` 中显式声明，供系统 Maven 同步；不得为了补插件缓存而把依赖加入业务叶子模块的运行时 classpath。
- 一期正式 Java 源码已按第 1 节的实际职责分包迁移；模块根包只保留 `DesktopDatabase`、`DesktopApplicationService` 等稳定门面。`SafeSourceArchiver` 与源码归档类型已进入 `shared/source.archive`，Apache SSHD 实现已进入 `shared/linux-sshd`。
- `shared/git`、`shared/backup` 和 Web Java 叶子模块目前只有 POM；`shared/config` 也仅有 POM，不创建空 `src` 目录。`web/frontend` 已包含最小页面、单元测试和浏览器测试。

## 2. 模块职责

### 2.1 `shared` 共用模块

| 模块 | 职责 | 明确不负责 |
| --- | --- | --- |
| `model` | 定义项目、语言、运行环境、服务器、受管应用、稳定配置标识与摘要、运行与自启状态、部署计划、生命周期操作、执行步骤、事件、风险和结果等纯数据模型与枚举。 | 不执行分析、SSH、AI、配置规则、部署、生命周期操作或持久化。 |
| `config` | 定义平台无关的类型化配置、适用范围、默认规则、完整性与兼容性校验、不可变快照、版本、摘要、差异和不透明秘密引用。 | 不读写文件或数据库，不定义持久化实现，不管理用户、权限或会话，不执行加密、读取秘密或承载平台启动配置。 |
| `source` | 管理平台无关的源码快照、可重复归档、路径与成员安全校验、清单、摘要、排除规则和规范化处理。 | 不选择 Windows 本地文件，不管理 Web 上传、配额或工作区，不拉取 Git 仓库，不分析、构建、部署或远程传输项目，也不处理应用数据备份与恢复。 |
| `git` | 解析 Git 仓库来源，拉取或更新源码，固定分支、Tag 或 Commit，并准备供后续分析和部署使用的本地项目快照。 | 不执行项目脚本，不识别语言和环境，不构建或部署项目。 |
| `analyze` | 识别语言和构建体系，分析项目结构与依赖，根据项目事实和服务器信息推导运行环境需求及部署条件。 | 不建立 SSH 连接，不上传文件，不执行远程命令，不负责部署流程。 |
| `ai` | 管理 AI Provider 调用、最小数据发送、脱敏、结构化结果解析和可选的多模型协作。 | 不读取凭据，不连接服务器，不直接执行命令，不绕过确定性分析和安全校验。 |
| `linux` | 定义 Linux Gateway、会话、连接、传输、能力采集、构建、运行方式、发行版和高权限操作的类型化公共契约、请求、结果与异常。 | 不依赖 Apache SSHD，不实现 SSH/SFTP、远程命令、软件包、systemd 或容器操作，不决定部署顺序、资源归属或失败恢复策略。 |
| `linux-sshd` | 使用 Apache SSHD 实现 `linux` 契约，包括连接、认证、主机指纹、SFTP、受控远程命令、能力采集、目标机构建、发行版环境准备、systemd、容器和高权限辅助程序操作。 | 不向上层暴露 Apache SSHD、原始 Shell 或原始 SFTP 类型，不判断项目需求、资源归属、部署顺序、版本切换或失败恢复策略。 |
| `deploy` | 编排环境准备、计划校验、风险确认、上传、构建、启动、健康检查、版本切换和失败恢复；校验受管应用身份并编排状态刷新、启动、停止、重启和开机自启变更。 | 不重复实现语言识别、AI 协议或底层 SSH/SFTP、systemd、Docker、Podman 能力。 |
| `backup` | 管理版本化备份与环境清单，创建、解析和校验备份包，编排恢复、候选版本验证及跨服务器迁移。 | 不管理平台本地目录，不处理主密钥或秘密加解密，不重复实现 SSH/SFTP 和部署执行。 |

`model` 应优先使用不可变 Java `record` 和 `enum` 表达结构化事实，不得演变为任意键值、可随意修改的公共属性容器。`model` 只保留跨模块稳定的配置标识与摘要，详细配置定义、快照、版本和差异规则归 `config`，不得复制两套配置模型。

`source` 只处理平台无关的源码快照与归档规则。`app/windows` 负责桌面本地文件入口，`web/file` 负责上传、配额和服务端工作区，`git` 负责仓库来源；三者复用 `source`，不得复制源码归档格式或安全校验规则。源码归档服务于源码传输和目标机构建，`backup` 管理的备份归档服务于应用数据恢复与迁移，两者不得混用格式、清单或生命周期语义。

`linux` 已只保留连接、会话、传输、能力、构建、运行、发行版和高权限操作的公共契约。`SshdPhaseOneLinuxGateway`、SSHD Session、受控命令执行、SFTP、Maven 构建、Ubuntu 环境准备、systemd 运行和高权限 helper 均已迁入 `linux-sshd` 对应包。`DesktopApplicationService` 只接收 `PhaseOneLinuxGateway`，由 `app/main/bootstrap` 构造并注入具体 SSHD 实现。

### 2.2 `app` 桌面应用模块

| 模块 | 职责 | 明确不负责 |
| --- | --- | --- |
| `ui` | 实现 Swing/FlatLaf 界面、输入校验、进度展示和用户决定交互。 | 不直接访问 SQLite、SSH、AI、加密算法或 Windows Credential Manager。 |
| `windows` | 选择和访问 Windows 本地项目、备份及工作目录，采集文件与环境事实，并通过 `shared/source` 准备待上传的项目包。 | 不自行定义源码归档格式或安全校验规则，不执行远程上传，不解释备份格式，不处理加密、密钥、敏感信息或凭据。 |
| `db` | 管理 SQLite 连接、表结构、版本化迁移和事务，保存非敏感数据、配置实例与历史版本、受管应用标识、最后观测状态及由 `secret` 生成的加密数据。 | 不定义共用配置规则，不执行加解密，不派生或保存明文密钥，不直接访问 Windows Credential Manager，不把最后观测状态当作远端事实。 |
| `secret` | 统一处理主密码、Argon2id 密钥派生、AES-256-GCM 加解密、DEK 包装、敏感信息存取和 Windows Credential Manager 平台适配。 | 不负责普通业务数据、界面、项目打包、部署或远程连接。 |
| `service` | 实现桌面端部署和受管应用生命周期等用例、后台任务、同服务器修改互斥、事件、取消和模块协作，整合 `app` 与 `shared` 能力。 | 不自行实现界面、SQLite、加密算法、凭据平台接口、SSH/SFTP 或 Linux 生命周期动作。 |
| `main` | 提供应用启动入口，识别运行模式、解析应用与数据目录，装配模块和 `linux-sshd` 实现并管理生命周期。 | 不承载具体业务规则、界面逻辑、持久化、SSH/SFTP 或加密实现。 |

桌面端所有加密、解密、密钥派生、密钥包装、敏感信息存取和 Windows Credential Manager 调用都必须位于 `secret`。`windows` 只处理非敏感的 Windows 本地能力；不得为了平台调用方便把任何安全实现放入 `windows`。

### 2.3 `web` Web 应用模块

| 模块 | 职责 | 明确不负责 |
| --- | --- | --- |
| `frontend` | 实现 Vue 3/TypeScript 页面、用户交互、流式上传交互和 SSE 事件展示。 | 不保存原始凭据，不直接访问数据库、文件系统、SSH/SFTP 或共用执行模块。 |
| `api` | 实现版本化 REST API、SSE 传输、受管应用生命周期接口、请求响应转换、输入边界校验和安全错误输出。 | 不直接访问 SQLite、受管文件目录、凭据或部署执行器，不承载业务流程或直接执行生命周期动作。 |
| `auth` | 管理唯一管理员初始化、登录、服务端会话、登录限速、CSRF、会话超时和近期认证策略。 | 不自行实现密码哈希、加密算法、主密钥或普通业务授权逻辑。 |
| `service` | 实现 Web 部署、配置访问控制和受管应用生命周期等用例并整合 `db`、`file`、`secret` 与 `shared` 能力。 | 不实现 HTTP/SSE、任务调度、SQLite、文件底层操作、密码学算法、SSHD 或 Linux 生命周期动作。 |
| `task` | 管理部署和生命周期等持久化任务的状态、调度、同服务器互斥、受控取消、重启恢复和结构化事件。 | 不传输 SSE，不重复实现分析、部署、生命周期、备份或迁移规则。 |
| `db` | 管理 Web 端 SQLite 连接、表结构、版本化迁移、事务和持久化仓储，包括配置实例与历史版本、项目与环境关联、受管应用标识和最后观测状态。 | 不定义共用配置规则，不执行加解密，不管理上传文件、后台调度或接口协议，不把最后观测状态当作远端事实。 |
| `file` | 管理流式上传下载、配额、临时文件清理和受管工作目录，并通过 `shared/source` 校验与解压源码归档。 | 不自行定义源码归档格式或安全校验规则，不负责 SFTP、远程 Linux 文件、备份恢复语义或秘密加解密。 |
| `secret` | 统一处理管理员密码哈希、主密钥、认证加密、服务端凭据存取和必要脱敏能力。 | 不负责登录会话流程、普通数据库业务、上传文件、部署或远程连接。 |
| `main` | 提供 Spring Boot 入口，加载启动配置，装配后端模块、`linux-sshd` 实现与前端产物，管理健康检查、启动和关闭。 | 不承载具体业务、接口、任务、持久化、文件、SSH/SFTP 或密码学实现。 |

Web 端所有密码哈希、加密、解密、主密钥和服务端凭据操作都必须位于 `secret`。`auth` 只决定身份认证与会话策略，通过 `secret` 使用密码学能力；其他 Web 模块不得直接接触明文密钥或实现加密算法。

### 2.4 配置规则、存储与秘密边界

1. `shared/config` 只提供平台无关的配置类型与规则，不读写文件或数据库，也不定义 Repository、数据库表或具体持久化技术。
2. 桌面端配置实例及历史版本由 `app/db` 统一保存；Web 配置实例、项目、环境关联及历史版本由 `web/db` 统一保存，不为每名用户创建独立配置文件。Web 后续支持多用户或租户时，所有权关联同样由 `web/db` 保存。
3. Web 配置访问权限由 `web/auth` 和 `web/service` 校验；`shared/config` 不感知具体用户、租户、会话或授权策略。
4. 敏感值由 `app/secret` 或 `web/secret` 处理；普通配置只保存秘密 ID、版本等不透明引用，不保存明文秘密。
5. `app/main/config` 和 `web/main/config` 继续处理各自应用的启动配置，不属于 `shared/config`。

### 2.5 桌面端本地化与诊断边界

1. 英文是桌面端生产代码、内部校验、程序生成诊断和默认消息资源的基准语言；`app/ui/i18n/messages/Messages.properties` 是消息目录的规范来源，简体中文只保存在 `Messages_zh_CN.properties`。
2. 生产模块不得提前拼接英文或中文界面句子。固定用户文案必须使用稳定消息键和命名参数表达；跨模块失败通过 `LocalizedFailure` 分离 `userMessage()` 与不含秘密的 `diagnostic()`，UI 是唯一负责将消息键解析为显示语言的边界。
3. SSH、systemd、HTTP、第三方 Provider 和远端命令返回的原始技术内容属于诊断证据，不翻译、不改写、不作为消息键。UI 以本地化摘要加原始技术详情展示，并在两个部分继续执行密码、私钥、凭据和 API Key 脱敏。
4. 部署步骤、部署状态、置信度、运行状态、自启状态和生命周期动作必须以稳定代码、`record` 或 `enum` 跨模块传递，不得依赖 `Object.toString()` 或显示字符串表达业务状态。
5. AI 提示模板使用英文编写，只发送经过既有边界脱敏的结构化事实；调用时显式传入受限响应语言，当前桌面语言为 `zh-CN` 时请求 Simplified Chinese，为 `en` 时请求 English。
6. 桌面端目前只支持 `en` 与 `zh-CN`。没有已保存偏好时，中文系统语言使用 `zh-CN`，其他系统语言使用 `en`；用户明确选择后，以持久化偏好为准，运行期间不跟随系统语言自动变化。

## 3. 叶子模块约定

### 3.1 Maven 通用目录结构

每个 Java 叶子模块使用 Maven 标准目录：

```text
src/<group>/<module>/
├─ pom.xml
└─ src/
   ├─ main/
   │  ├─ java/
   │  └─ resources/     # 有资源文件时创建
   └─ test/
      ├─ java/
      └─ resources/     # 有测试资源时创建
```

`shared`、`app` 和 `web` 的 Java 叶子模块没有实现内容时不提前创建空目录。`web/frontend` 使用前端工程自己的 `package.json`、源码和测试结构，不适用上述 Maven 目录。Java 包前缀固定为 `gold.debug.windowstolinux`；第 1 节固定正式目标包结构，但不代表所有目录已经存在，已实现模块的公开类型和现有源码在对应迁移完成前保持当前状态。

### 3.2 适配协作边界

语言、构建工具、框架、部署形态、运行方式、发行版和 CPU 架构是相互独立的适配维度，不得通过 `java/ubuntu/x86_64` 一类组合包复制整套流程。各模块按以下边界协作：

```text
analyze                        识别语言、构建工具、框架和工作负载事实
linux.connection/transfer     定义连接和受控传输契约
linux.capability              定义发行版、版本、CPU 和运行能力采集契约
linux.build                   定义受控目标机构建契约
linux.runtime                 定义运行方式生命周期契约
linux.distro                  定义发行版事实与环境准备契约
linux-sshd.*                  通过 Apache SSHD 实现上述远程能力
deploy.compatibility          匹配已经声明并完成验收的支持组合
deploy.adapter                按部署形态生成类型化部署计划
deploy.transaction            编排上传、构建、发布、健康检查和回滚
```

1. Spring Boot 部署适配器复用发布、健康检查和回滚语义；Maven 与 Gradle 的差异由 `linux.build` 定义类型化执行契约，并由 `linux-sshd.build` 承接具体远程执行。
2. 普通 Java JAR、Node.js、Python、静态站点和单容器分别使用对应部署形态适配器，不按语言名称猜测构建或运行方式。
3. systemd、Docker、Podman 和静态站点服务按运行方式定义 `linux.runtime` 契约，由 `linux-sshd.runtime` 实现远程操作，不为每种项目语言复制生命周期实现。
4. `linux.distro` 只定义发行版事实和环境准备契约；Ubuntu、CentOS、Debian、Rocky Linux、AlmaLinux 和 Oracle Linux 的包管理器、软件源与安全机制具体实现由 `linux-sshd.distro` 承接，只有实际实现对应能力时才创建包和目录。
5. CPU 架构、指令集和平台能力通过 `linux.capability` 契约采集，具体远程实现归 `linux-sshd.capability`，并作为 `deploy.compatibility` 的支持矩阵维度；没有独立执行逻辑时不创建 `x86_64`、`arm64` 等执行包。
6. 没有匹配到正式支持组合时，只返回识别预览或不支持结果，不得进入环境安装、构建、发布或生命周期接管。
7. `model` 保存通用、类型化的项目和目标机事实；`app`、`web`、数据库、认证、秘密、Git 和 AI 不复制语言专用分析、构建或部署流程。
8. `backup` 继续按格式、清单、校验、恢复和迁移分包；语言运行时、发行版和目标架构兼容性由 `validation` 处理，不为每种语言复制备份流程。
9. `linux` 公共契约不得引用 Apache SSHD 类型，也不得向上层暴露任意 Shell、原始 SFTP 或不受控 systemd、Docker、Podman 操作；具体远程实现只能位于 `linux-sshd`。

### 3.3 分包规则

1. Maven 模块表达依赖、技术和安全边界；Java 包和前端目录只负责模块内部组织，必须遵守第 4 节依赖方向。
2. 模块根包只保留稳定入口、门面或确需跨内部包使用的公共契约，具体实现进入职责明确的子包。
3. 测试包镜像对应生产包；测试夹具按语言、构建工具、框架或功能归类。
4. 禁止创建含义宽泛的 `util`、`common`、`misc` 包，也不得把大量无关实现集中到单一 `impl` 包。
5. `analyze` 按语言、构建工具、框架和工作负载组织识别能力；`deploy` 按部署形态组织适配器；`linux` 按连接、传输、能力、构建、运行方式、发行版和协议组织公共契约，`linux-sshd` 按实际需要组织对应实现。
6. 禁止按语言、部署形态、发行版和 CPU 架构的笛卡尔组合创建包；具体支持范围由 `deploy.compatibility` 依据已经验收的支持矩阵判断。
7. 界面、数据库、认证、秘密和普通业务用例不得按被部署项目的语言复制结构。
8. 包结构不用于绕开模块职责。跨模块能力仍通过既有依赖和类型化契约协作，不复制模型，不向上层开放任意 Shell、原始 SFTP 或不受控 systemd、Docker、Podman 操作。
9. `linux` 的接口、请求、结果和异常不得导入或暴露 Apache SSHD 类型；`linux-sshd` 可以依赖 Apache SSHD，但不得把具体客户端、会话、通道或 SFTP 类型传递给上层模块。

## 4. 依赖方向

共用模块依赖固定为：

```text
config  ──→ model
source  ──→ model
git     ──→ model
git     ──→ source
analyze ──→ model
ai      ──→ model
linux   ──→ model

linux-sshd ──→ linux
linux-sshd ──→ model
linux-sshd ──→ Apache SSHD

deploy  ──→ model
deploy  ──→ config
deploy  ──→ git
deploy  ──→ analyze
deploy  ──→ ai
deploy  ──→ linux

backup  ──→ model
backup  ──→ config
backup  ──→ linux
backup  ──→ deploy
```

桌面应用模块依赖固定为：

```text
windows ──→ shared/model
windows ──→ shared/source
db      ──→ shared/model
db      ──→ shared/config
secret  ──→ db
secret  ──→ shared/model

service ──→ windows
service ──→ db
service ──→ secret
service ──→ shared/{model,git,analyze,ai,config,linux,deploy,backup}

ui      ──→ service
ui      ──→ shared/model

main    ──→ ui
main    ──→ windows
main    ──→ db
main    ──→ secret
main    ──→ service
main    ──→ shared/{model,git,analyze,ai,config,linux,linux-sshd,deploy,backup}
```

Web 模块依赖固定为：

```text
frontend ──HTTP/SSE──→ api

api     ──→ auth
api     ──→ service
api     ──→ task
api     ──→ shared/model

auth    ──→ secret
auth    ──→ db

task    ──→ service
task    ──→ db
task    ──→ shared/model

service ──→ file
service ──→ secret
service ──→ db
service ──→ shared/{model,git,analyze,ai,config,linux,deploy,backup}

file    ──→ shared/model
file    ──→ shared/source
secret  ──→ db
secret  ──→ shared/model
db      ──→ shared/model
db      ──→ shared/config

main    ──→ api
main    ──→ auth
main    ──→ service
main    ──→ task
main    ──→ db
main    ──→ file
main    ──→ secret
main    ──→ shared/{model,git,analyze,ai,config,linux,linux-sshd,deploy,backup}
```

依赖规则：

1. `model` 不依赖其他业务模块。
2. `config`、`source`、`analyze`、`ai` 和 `linux` 只依赖 `model`；`git` 只依赖 `model` 与 `source`。除 `git → source` 外，这些模块彼此不直接依赖；`source` 不得依赖 `git`、`analyze`、`deploy`、`backup`、`app` 或 `web`。
3. `deploy` 负责部署编排；只有 `backup` 可以调用它完成恢复候选版本的启动、健康检查和切换，`deploy` 不得反向依赖 `backup`。
4. 禁止循环依赖，也不得通过复制模型或静态全局状态规避依赖边界。
5. `deploy` 对 `ai` 的代码依赖不代表运行时必须配置 AI；没有可用 AI 时，受支持项目的确定性分析和部署流程仍须可用。
6. `backup` 只处理平台无关的备份与迁移语义，平台本地目录和秘密加解密分别由调用端的文件模块与 `secret` 负责。
7. `shared` 不依赖 `app` 或 `web`；平台适配、数据存储和界面能力不得反向进入共用模块。
8. `app/service` 只能通过 `app/secret` 使用敏感信息，不得直接处理加密算法、明文密钥或 Windows Credential Manager API。
9. `app/ui` 只能通过 `app/service` 发起业务操作，不得直接调用 `app/db`、`app/secret` 或 `shared` 的执行型模块。
10. `web/api` 不直接访问 `web/db`、`web/file`、`web/secret` 或 `shared` 的执行型模块；异步业务经 `web/task` 调用 `web/service`。
11. `web/auth` 通过 `web/secret` 使用密码学能力，`web/file` 不实现 SFTP 或备份恢复规则，`web/task` 不承担 SSE 传输。
12. `linux-sshd` 只依赖 `linux`、`model` 和 Apache SSHD；Apache SSHD 类型不得进入 `linux` 公共契约，也不得传递给上层模块。
13. `deploy`、`backup`、`app/service` 和 `web/service` 只通过 `linux` 公共契约使用远程能力，不得直接依赖或构造 `linux-sshd`。
14. 只有 `app/main` 和 `web/main` 作为组合根选择并注入 `linux-sshd`；具体 SSHD 实现不得进入业务服务、数据库、界面或 API 模块。
15. `app/db` 和 `web/db` 可以依赖 `shared/config` 保存配置实例和版本；`shared/config` 不得反向依赖平台数据库、秘密、认证或服务模块。

## 5. 桌面端数据目录

桌面应用不允许自定义 `data` 位置，也不使用注册表、命令行参数或路径指针文件覆盖默认位置。`main` 根据运行模式解析唯一数据目录：

| 运行模式 | `data` 目录 |
| --- | --- |
| CLASS | `src/app/main/data`，与 `src/app/main/target` 同级。 |
| JAR | 主 JAR 文件所在目录下的 `data`。 |
| APP | `jpackage` 启动器 EXE 所在目录下的 `data`。 |

- `jpackage` 安装包启用按当前用户安装和安装目录选择，避免默认安装到普通用户不可写的 `Program Files`。
- CLASS 模式优先依据 Maven `target/classes`/`target/test-classes` 定位模块；JetBrains 等 IDE 输出不在模块内时，只有验证工作区中的 `windowstolinux-app-main` POM 后才解析到 `src/app/main`，不会直接把工作目录当作应用目录。
- 应用启动时必须验证解析出的 `data` 目录可以创建和写入；验证失败则停止启动并提示用户重新安装到可写目录。
- 不得静默回退到用户目录、临时目录或其他位置，避免同一安装出现多个不一致的数据副本。
- APP 模式下数据始终跟随 EXE 安装目录；升级和卸载时按四期文档定义的规则处理。

## 6. Git 项目准备边界

Git 项目进入分析和部署流程前，由 `git` 模块统一准备：

```text
deploy 提交 Git 来源和目标版本
→ git 解析并拉取仓库
→ git 将分支或 Tag 固定为具体 Commit
→ git 通过 source 生成并校验规范化源码快照
→ git 返回源码快照和版本信息
→ deploy 将项目快照交给 analyze
```

- `git` 只负责取得确定版本的项目源码，不执行仓库内脚本或构建命令。
- `git` 通过 `source` 复用源码快照、归档和安全校验规则，不复制对应实现。
- `analyze` 只读取准备好的项目快照，不自行连接 Git 仓库。
- `deploy` 负责编排 Git 准备、项目分析和后续部署步骤。

## 7. 环境部署边界

环境准备由分析、部署决策、Linux 公共契约和 SSHD 实现四类职责协作完成：

```text
linux-sshd 通过 linux 契约采集服务器已有环境
→ analyze 比较项目需求与服务器事实
→ deploy 生成环境准备计划并处理风险确认
→ linux-sshd 通过 linux 契约执行受控安装和配置操作
→ deploy 验证结果并决定继续、停止或恢复
```

- `analyze` 决定项目**需要什么环境**，输出结构化环境需求和不兼容项。
- `linux` 定义**服务器有什么环境**以及**允许怎样执行 Linux 操作**的类型化契约、请求和结果。
- `linux-sshd` 使用 Apache SSHD 实现环境采集、受控传输、安装、配置和复核，不向上层开放原始 Shell 或 SFTP。
- `deploy` 决定**是否安装、按什么顺序安装、何时确认以及失败后怎么办**。

因此，环境部署的业务流程归 `deploy`，公共远程能力边界归 `linux`，具体 Linux 安装、配置和检查动作归 `linux-sshd`。

## 8. 受管应用生命周期边界

部署成功后，启动、停止、重启、状态刷新和开机自启管理继续复用既有模块：

```text
平台端 service 提交受管应用和生命周期动作
→ deploy 校验应用标识、资源归属、动作语义和影响范围
→ linux-sshd 通过 linux 契约查询目标服务器上的实际运行与自启状态
→ linux-sshd 通过 linux 契约执行经过校验的 systemd、Docker 或 Podman 动作
→ deploy 执行健康检查、停止确认或自启状态复核
→ 平台端 service 记录结果并更新界面
```

- `model` 表达受管应用标识、运行方式、实际运行状态、开机自启状态、生命周期动作及结果，不包含执行逻辑。
- `linux` 只定义经过校验的资源查询和生命周期动作契约；`linux-sshd` 负责具体远程执行，不通过同名服务、容器或目录自行推断资源归属。
- `deploy` 统一处理单组件生命周期语义和安全校验；三期多组件操作仍由它依据组件依赖图编排，不新增独立生命周期模块。
- `app/service` 或 `web/service` 提交用例并展示结果，平台 `db` 只保存受管标识和最后观测值；目标服务器实时查询结果才是运行与自启状态的事实来源。
- `web/task` 在五期把生命周期修改作为持久化任务调度，并与同一服务器上的部署、恢复、迁移等修改任务互斥。
- 启动不改变开机自启，停止不关闭开机自启，重启保留原有开机自启设置；单独修改自启状态也不得静默改变当前运行状态。
- 启动和重启后必须执行健康检查，停止后必须确认目标已经停止，自启变更后必须复核 systemd 单元或受控容器自启单元、策略的实际状态。
- 资源丢失、标识不匹配、检测到外部修改或无法连接时必须返回明确的未知或异常结果，不得猜测执行、自动重建或标记成功。
- 生命周期能力只管理由 WindowsToLinux 部署、恢复或迁移且能够验证归属的应用，不扩展为任意 systemd 服务、任意容器或通用服务器管理入口。

## 9. 备份、恢复与迁移边界

备份、恢复和跨服务器迁移由 `backup` 统一编排：

```text
平台端 service 提交备份、恢复或迁移请求
→ backup 生成版本化清单和受控计划
→ linux-sshd 通过 linux 契约采集或传输远程应用数据
→ backup 创建或校验备份包并准备候选恢复
→ deploy 启动候选版本、执行健康检查并安全切换
→ backup 返回完整性、兼容性、恢复或迁移结果
```

- `backup` 负责备份格式、清单、校验、恢复计划、兼容性结果和跨服务器迁移流程，不管理桌面或 Web 主机上的固定存储目录。
- `app/windows` 或 `web/file` 只向 `backup` 提供经过边界校验的输入、输出或工作位置，不解释备份内容，不执行远程 SFTP。
- `app/secret` 或 `web/secret` 在平台边界内完成秘密加解密；`backup` 只接收和返回不透明的加密秘密成员，不接触主密钥或明文秘密。
- `linux` 定义远程采集、传输和受控 Linux 操作契约，`linux-sshd` 负责具体实现，`deploy` 负责候选版本的启动、健康检查和切换；三者不重复实现备份包格式。

## 10. 维护规则

1. 开发必须沿用本文已经确认的 `shared`、`app`、`web` 和叶子模块结构，不为实现方便随意增加模块。
2. 普通 Maven 叶子模块的目录名使用一个英文单词；只有把具体实现从公共契约模块独立隔离时，才使用 `<contract>-<implementation>`，例如 `linux-sshd`。
3. Java 类存在接口与实现不代表必须拆分 Maven 模块；只有具体实现需要独立依赖或装配时才建立连字符实现模块。`source` 当前不拆分为 `source-tar`，源码归档格式属于其内部职责。
4. 确需新增模块时，必须先向用户说明原因、现有模块内实现的替代方案、依赖方向、迁移成本和文档影响；经讨论明确同意后才能创建。
5. 创建、移动或删除正式项目目录时必须同步更新本文，不能先改结构后把文档作为补记。
6. 只有经过确认的目录、模块名称、职责和依赖方向才能写入正式结构；禁止通过复制模型、反射或静态全局状态绕开依赖约束。
7. `.idea/`、`.ai-workspace/`、`target/`、`node_modules/`、`dist/`、缓存、日志和其他开发辅助或生成目录不纳入正式项目结构。
8. 目录、职责或依赖方向变化时，必须同步检查开发总纲和全部受影响分期文档。
9. 新增或修改用户文案时必须先更新英文基准消息，并在同一次提交补齐简体中文映射；两个目录的消息键集合和每个键的命名参数必须完全一致。
10. `shared`、`app/db`、`app/secret`、`app/service` 和 `app/main` 不得硬编码任何本地化文案；其用户可见结果必须返回消息键或结构化状态，内部诊断必须使用英文。
11. 原始 SSH、systemd、HTTP、第三方响应和远端命令输出不得为适配界面语言而翻译或改写，也不得与本地化用户摘要混为同一字段。
12. 每次本地化变更必须通过英文/中文键与占位符一致性测试、两种语言渲染测试、缺键和缺参数失败测试、未知语言回退英文测试，以及生产字符串与文本资源汉字边界测试。
13. 除 `Messages_zh_CN.properties` 外，`src/**/src/main` 下的 Java 字符串、字符和文本块以及非 Java 文本资源不得包含汉字；中英双语 Java 注释、文档、测试和专门验证中文翻译的夹具不受此限制。
14. 本地化改造不得改变 SQLite schema、部署与安全流程、凭据所有权或秘密传递边界；需要改变这些边界时必须另行评审。
15. 项目代码注释统一采用中英双语，包含 `//`、块注释和 Javadoc；英文说明在前，简体中文说明紧随其后，并在同一注释内表达相同含义。标识符、命令、协议名和原始诊断保持原文，不为满足双语格式而翻译；注释不属于 UI 文案，不进入消息目录。新增或修改注释时必须遵守本规则。

## 11. 文档版本记录

| 版本 | 日期 | 说明 |
| --- | --- | --- |
| 2.2.2-bilingual-comments | 2026-08-12 | 明确 Java 行注释、块注释和 Javadoc 采用英文在前、简体中文紧随其后的双语格式；同步将生产汉字门禁限定为 Java 非注释内容和非 Java 文本资源，注释不进入 UI 消息目录。 |
| 2.2.1-i18n-policy | 2026-08-12 | 固定桌面端英文基准与简体中文 UI 映射，规定消息键/参数同步、结构化用户消息与英文/原始诊断分离、AI 响应语言传递及生产目录汉字门禁；不新增模块，不修改 SQLite schema、部署、安全或凭据边界。 |
| 2.2.0-structure-implementation | 2026-08-11 | 落地 28-POM reactor，新增 `shared/config`、`shared/source`、`shared/linux-sshd`，完成一期共享层、桌面层、组合根和测试的职责分包与原子迁移；`shared/config` 仅接通 POM 依赖，SQLite 仍为 schema v3，本次不执行真实 Ubuntu 操作。 |
| 2.1.2-unified-structure | 2026-08-11 | 将模块和内部目标包统一合并到第 1 节并按英文名称排序，重排叶子模块约定、依赖方向、桌面数据目录及业务边界章节，移除已由 `shared/source` 接管的旧源码归档目标包；不改变模块依赖或当前实现。 |
| 2.1.1-source-module | 2026-08-11 | 将 `shared/source` 纳入正式目标架构，固定源码快照、归档和安全校验边界及模块命名规则；模块和源码迁移仍未实施。 |
| 2.1.0-module-package-layout | 2026-08-11 | 将叶子模块内部分包、`shared/config` 和 `shared/linux-sshd` 整合为正式目标架构，明确配置规则与存储边界以及 Linux 契约与 Apache SSHD 实现边界；新模块和源码迁移仍未实施。 |
| 2.0.10-linux-sshd-module-draft | 2026-08-11 | 新增未来 `shared/linux-sshd` Apache SSHD 实现模块草案，将 `shared/linux` 的目标职责收敛为公共契约；不改变正式模块、依赖、POM 或当前实现。 |
| 2.0.9-config-module-draft | 2026-08-11 | 新增未来 `shared/config` 共用配置规则模块草案，明确配置规则归 `shared/config`、实例存储归平台 `db`、秘密处理归平台 `secret`；不改变正式模块、依赖、数据库或当前实现。 |
| 2.0.8-adapter-layout-draft | 2026-08-11 | 细化部署形态、目标机构建、运行方式、发行版和 CPU 架构适配分包；草案仍待审阅，不改变正式模块、依赖或当前实现。 |
| 2.0.7-package-layout-draft | 2026-08-11 | 新增叶子模块内部包结构待审阅草案；不改变正式目录、模块、依赖方向或当前源码结构。 |
| 2.0.5-phase1-runtime-partial | 2026-08-10 | 同步一期真实 Ubuntu 验收的部分完成状态；未改变目录、模块名称或依赖方向。 |
| 2.0.3-phase1-gates | 2026-08-08 | 同步一期完整自动化门禁状态；未改变目录、模块名称或依赖方向。 |
| 2.0.2-phase1-hardening | 2026-08-08 | 同步一期源码分析、归档与 Linux 构建边界加固；未改变目录、模块名称或依赖方向。 |
| 2.0.1-phase1-implementation | 2026-08-08 | 同步一期已实现模块与真实 Ubuntu 待验收状态；未改变目录、模块名称或依赖方向。 |
| 2.0.0-roadmap-rebaseline | 2026-08-08 | 结构由设计状态转为已初始化，固定 Maven 坐标、Java 包前缀、RunModeDetector 位置和新增模块讨论规则。 |
| 1.7.0-managed-lifecycle | 2026-08-07 | 历史结构基线，确认模块职责、依赖、生命周期和数据目录。 |
