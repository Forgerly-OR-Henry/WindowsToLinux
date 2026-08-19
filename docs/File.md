# WindowsToLinux 项目文件结构

## 文档信息

- 文档版本：`3.21.0-ecosystem-architecture`
- 文档状态：**28-POM 模块边界保持不变；ecosystem 职责、命名、受控深度和源码迁移已完成，三期扩展文档必须以本版为结构基线；不得据此扩大运行支持声明**
- 已确认范围：`shared` 共用模块、`app` Windows 桌面应用模块、`web` Web 应用模块
- 已确认能力边界：受管应用生命周期复用既有模块，不新增独立 Maven 模块
- 更新日期：2026-08-19
- 开发总纲：[DEVELOPMENT.md](DEVELOPMENT.md)
- 分析包修订：[ANALYZE-PACKAGE-REVISION.md](development/ANALYZE-PACKAGE-REVISION.md)
- Linux 部署链分包修订：[LINUX-DEPLOY-PACKAGE-REVISION.md](development/LINUX-DEPLOY-PACKAGE-REVISION.md)

> 本文是正式目标目录、模块职责、依赖方向、包结构和命名规则的唯一来源。开发总纲、分期扩展文档和源码迁移必须先符合本文；运行能力与实机证据仍以相应分期文档为准，不得由目标目录反推支持结论。

> 本版先固定 `ecosystem` 的稳定边界：语言识别、构建架构、框架分析、目标机构建和工具链探测按职责归位；共享模型、部署编排、发行版、工作负载及运行机制保持正交。该结构不改变模块、协议、持久化、远端路径或既有实机证据。

## 1. 完整目标结构

当前已确认的完整目标结构如下。叶子模块下直接展示的是对应 Java 根包的目标子包，省略重复的 Maven `src/main/java` 路径；物理目录模板见第 4.1 节。`pom.xml` 作为聚合入口固定置顶，其余模块和内部包按英文名称排序。

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
   │  │  └─ repository/       按服务器、偏好、AI、配置、秘密和受管应用职责划分的数据访问实现
   │  ├─ main/                唯一 AppMain 入口、运行模式和模块装配
   │  │  ├─ startup/          桌面应用启动与模块装配
   │  │  └─ runtime/          RunModeResolver 与 RuntimePathResolver
   │  ├─ secret/              存储契约、异常及数据库/Windows 存储实现
   │  │  └─ crypto/           Argon2id、AES-GCM 和密钥处理
   │  ├─ service/             桌面业务流程整合
   │  │  ├─ ai/               AI 配置与分析用例
   │  │  ├─ config/           配置快照与秘密修订用例
   │  │  ├─ contract/         UI 按功能依赖的五个窄应用门面
   │  │  ├─ deployment/       部署用例入口与共享受管身份解析
   │  │  │  ├─ multi/         多组件审阅、拓扑和受管应用数据契约
   │  │  │  └─ single/        单组件部署结果与安全交接数据契约
   │  │  ├─ environment/      环境准备用例
   │  │  ├─ lifecycle/        受管应用和生命周期用例
   │  │  ├─ lock/             同服务器操作互斥注册表
   │  │  ├─ server/           服务器资料、信任和能力用例
   │  │  └─ source/           源码准备用例
   │  ├─ ui/                  桌面界面与用户交互
   │  │  ├─ ai/               AI 配置和解释结果界面
   │  │  ├─ display/       主题、外观和系统偏好
   │  │  ├─ component/        可复用的桌面界面组件
   │  │  ├─ deployment/       共享审阅上下文与类型化输入解析
   │  │  │  ├─ multi/         多组件编辑、状态、页面和结果呈现
   │  │  │  └─ single/        单组件表单、状态、页面和分析呈现
   │  │  ├─ i18n/             消息目录和本地化边界
   │  │  ├─ managed/          受管应用列表与生命周期交互
   │  │  ├─ server/           服务器配置和能力验证界面
   │  │  ├─ setting/          桌面设置界面
   │  │  └─ shell/            主窗口、导航和页面装配
   │  └─ windows/             Windows 平台边界
   │     └─ workspace/        工作目录与本地文件边界
   ├─ shared/
   │  ├─ pom.xml              共用模块聚合入口
   │  ├─ ai/                  AI 调用、脱敏与结果解析
   │  │  ├─ client/           OpenAI 兼容分析与角色客户端
   │  │  ├─ collaboration/    确定性优先的 AI 协调入口与最终处置
   │  │  │  ├─ advice/        经验证的有界建议决策和输出
   │  │  │  ├─ invocation/    调用状态、凭据无关证据和单次调用结果
   │  │  │  └─ role/          固定角色、绑定及最小脱敏上下文
   │  │  ├─ parser/           结构化结果解析与校验
   │  │  ├─ prompt/           结构化提示构建
   │  │  ├─ provider/         Provider 配置和协议适配
   │  │  ├─ redaction/        最小上下文与脱敏
   │  │  └─ transport/        角色聊天 HTTP 传输契约、实现和结果
   │  ├─ analyze/             项目生态、构建、框架、工作负载与部署条件的静态分析
   │  │  ├─ component/        混合项目组件分析、资源冲突和依赖图校验
   │  │  ├─ core/             分析协调器、跨语言事实汇总、阶段顺序与结果聚合
   │  │  ├─ ecosystem/        只保存按语言维护的识别、构建架构和框架分析
   │  │  │  ├─ dotnet/        .NET SDK 事实与服务部署检查
   │  │  │  ├─ go/            Go Module 事实与服务部署检查
   │  │  │  ├─ java/          Java 语言与 Spring Boot 框架分析
   │  │  │  │  ├─ gradle/     Gradle 构建事实与静态检查
   │  │  │  │  ├─ jar/        JAR 原生交付架构静态检查
   │  │  │  │  └─ maven/      Maven 构建事实与静态检查
   │  │  │  ├─ kotlin/        Kotlin/Gradle 应用事实与服务部署检查
   │  │  │  ├─ node/          Node.js 语言、包管理器与服务项目分析
   │  │  │  ├─ php/           Composer 事实与 PHP 服务部署检查
   │  │  │  ├─ python/        Python 语言、依赖与服务项目分析
   │  │  │  ├─ ruby/          Bundler 事实与 Ruby 服务部署检查
   │  │  │  └─ rust/          Cargo 事实与 Rust 服务部署检查
   │  │  ├─ policy/           跨生态源码变更与部署停止策略
   │  │  ├─ preview/      不可执行语言标记目录与识别预览结果
   │  │  ├─ registry/         默认类型检查器的唯一装配与完整性校验
   │  │  ├─ service/          跨语言服务事实、元数据读取和结果组装
   │  │  ├─ source/           有界源码遍历、元数据、归档检查与项目身份推导
   │  │  ├─ spi/              类型检查器及其局部结果窄契约
   │  │  └─ workload/         容器与静态站点工作负载识别
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
   │  │  ├─ adapter/          systemd、容器与静态站点部署形态实现
   │  │  ├─ support/          主机支持判断编排
   │  │  │  ├─ distro/       六种独立发行版策略、规则及选择器
   │  │  │  └─ runtime/      语言和运行时工具版本能力判断
   │  │  ├─ contract/         请求、审批、步骤和不可变部署计划
   │  │  ├─ environment/      环境准备流程
   │  │  ├─ lifecycle/        单组件及依赖安全的整应用生命周期编排
   │  │  ├─ plan/             单组件与多组件计划生成、发布身份及校验
   │  │  ├─ registry/         部署适配器的唯一默认装配
   │  │  ├─ result/           公开结果职责分组入口（不直接放置类型）
   │  │  │  ├─ compatibility/ 主机支持状态与有序证据
   │  │  │  ├─ deployment/    部署事件、事务状态及单/多组件部署结果
   │  │  │  └─ lifecycle/     单资源、组件和整应用生命周期结果
   │  │  ├─ spi/              部署适配器窄契约
   │  │  └─ transaction/      单组件与整应用上传、构建、发布、健康和回滚事务
   │  ├─ git/                 Git 来源、引用值对象及校验
   │  │  └─ snapshot/         Git 命令、受控工作区、策略与只读快照协调
   │  ├─ linux/               Linux 远程能力公共契约
   │  │  ├─ build/            目标机构建结果和执行契约
   │  │  ├─ capability/       发行版、CPU、工具和运行能力采集契约
   │  │  ├─ connection/       Gateway、端点、凭据和主机信任契约
   │  │  ├─ distro/           发行版事实与环境准备契约
   │  │  ├─ error/            全部受控 Linux 操作的公共失败类型
   │  │  ├─ protocol/         类型化高权限操作及结果契约
   │  │  ├─ runtime/          systemd、Docker、Podman 和静态服务生命周期契约
   │  │  ├─ session/          组合各项类型化能力的远程会话契约
   │  │  └─ transfer/         受控传输请求与结果契约
   │  ├─ linux-sshd/          Apache SSHD Linux 远程能力实现
   │  │  ├─ build/            受控目标机构建入口
   │  │  │  ├─ ecosystem/     按语言聚合的构建架构实现；单架构实现直接位于本包
   │  │  │  │  ├─ java/       Java 的 JAR、Maven 与 Gradle 构建差异
   │  │  │  │  ├─ node/       Node.js 的 npm、pnpm 与 Yarn 构建差异
   │  │  │  │  └─ python/     Python 的 pip、Pipenv、Poetry 与 uv 构建差异
   │  │  │  ├─ registry/      构建实现的唯一装配与完整性检查
   │  │  │  ├─ script/        配置环境、超时、资源限制和安全脚本外壳
   │  │  │  ├─ spi/           单项目类型构建渲染窄契约
   │  │  │  └─ workload/      容器与静态站点构建形态
   │  │  ├─ capability/       平台、发行版、CPU、安全和 helper 能力采集
   │  │  │  └─ ecosystem/     语言与构建工具链探测、版本解析和检查脚本生成
   │  │  ├─ command/          仅供实现层使用的受控 SSH 命令执行
   │  │  ├─ connection/       Apache SSHD 客户端、认证和主机指纹实现
   │  │  ├─ distro/           发行版环境准备职责入口
   │  │  │  ├─ apt/           APT 渲染、固定包集合与 Debian 家族配置目录
   │  │  │  ├─ dnf/           DNF 渲染、固定包集合与企业 Linux 配置目录
   │  │  │  ├─ profile/       不可变发行版准备与生态能力配置
   │  │  │  ├─ registry/      完整发行版配置装配与唯一注册表
   │  │  │  └─ script/        通用发行版准备脚本生成
   │  │  ├─ protocol/         候选工作区及类型化远程协议实现
   │  │  │  ├─ helper/        固定 helper 资源拼装与摘要校验；生态片段按 ecosystem 分组
   │  │  │  ├─ input/         配置和秘密修订输入协议
   │  │  │  ├─ release/       普通与容器发布、快照和回滚协议
   │  │  │  └─ runtime/       类型化运行参数和保留协议
   │  │  ├─ runtime/          容器分派及语言无关运行机制
   │  │  │  └─ systemd/       systemd 健康、归属、生命周期及单元渲染
   │  │  ├─ session/          SSHD 类型化远程会话组合与关闭职责
   │  │  └─ transfer/         Apache SSHD SFTP 与受控传输实现
   │  ├─ model/               部署数据模型与属性定义
   │  │  ├─ analysis/         项目/组件分析事实与证据
   │  │  ├─ archive/          源码包、备份包和摘要描述模型
   │  │  ├─ assessment/       分析评估、冲突和支持判断结果
   │  │  ├─ capability/       Linux 与服务器能力快照
   │  │  ├─ deployment/       部署请求、计划、状态和结果模型
   │  │  ├─ health/           健康检查与访问地址模型
   │  │  ├─ lifecycle/        单组件及整应用运行、自启汇总和生命周期动作模型
   │  │  ├─ language/         语言生态、源码语言及确定性语言事实
   │  │  ├─ managed/          受管应用、受管身份和运行配置模型
   │  │  ├─ message/          本地化消息模型
   │  │  ├─ project/          部署项目、运行时、支持声明、源码修订及 component 子包
   │  │  ├─ security/         凭据模式、确认和安全相关纯数据模型
   │  │  └─ server/           发行版、CPU、协议版本和服务器身份
   │  │     └─ security/      强制访问控制与防火墙安全态势
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
      │  ├─ startup/        Spring Boot 启动与模块装配
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

根目录 `test` 保存不参与 WindowsToLinux Maven reactor 的独立验收夹具，当前按源码语言、构建工具和框架使用以下结构：

```text
test/
└─ java/
   └─ maven/
      └─ spring-boot/
         └─ <fixture>/
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
- 第 1 节是正式目标结构。结构迁移必须原子更新包声明、物理路径、导入、测试和门禁；旧包前缀与薄包装类不保留兼容壳。
- `shared/backup` 和 Web Java 叶子模块目前只有 POM。`shared/config`、`shared/git` 已有二期 API；`web/frontend` 已包含最小页面、单元测试和浏览器测试。

### 1.1 稳定职责边界

- `shared.analyze` 只读取有界源码并生成确定性事实；跨语言协调归 `core`，语言和构建架构实现归 `ecosystem`，工作负载识别归 `workload`。
- `shared.linux` 只定义平台无关的类型化 Linux 契约；Apache SSHD、Shell 渲染和目标机实现只位于 `shared.linux-sshd`。
- `shared.linux-sshd.build` 保留构建执行入口、SPI、安全脚本和注册表；生态构建归 `build.ecosystem`，容器与静态站点构建归 `build.workload`。
- `shared.linux-sshd.capability` 保留平台能力采集；语言、构建工具链及其版本解析归 `capability.ecosystem`，APT/DNF 包名不得进入该包。
- `shared.linux-sshd.distro` 只负责发行版识别、软件包选择和环境准备；APT 与 DNF 分别形成完整扩展单元，不实现语言构建命令。
- `shared.deploy` 按部署形态组织 Adapter，按发行版与运行时组织支持判断，并通过类型化 Linux 契约编排事务；不得镜像语言生态目录。
- `model` 保存跨模块共享的纯事实和值对象；语言枚举、项目事实、部署计划和 UI 模型不得因生态实现而迁入 `ecosystem`。
- helper 的协议基础、输入、发布、运行和生命周期片段保持职责分组；只把语言或工具链专属片段归入资源 `ecosystem` 分组。资源移动不得改变组装字节、顺序、协议版本或固定摘要。
- UI、数据库、秘密、Git、备份及应用用例继续按自身职责分包，不按被部署项目的语言复制结构。
- 运行能力与实机证据不由包结构决定；新增生态或构建架构必须在对应分期文档中单独定义实现、测试和验收范围。

## 2. 统一命名规范

命名清单按功能分组；同一功能组内的英文标准名称按不区分大小写的字母顺序排列。阶段流、规则执行顺序和带编号规范保留语义顺序。

### 2.1 总体与模块命名

| 编号 | 规范 |
| --- | --- |
| N-01 | 模块表达依赖、技术、运行或安全边界。 |
| N-02 | 包表达模块内部的功能和职责。 |
| N-03 | 类名表达领域对象及其行为角色。 |
| N-04 | 命名必须使用准确、稳定、唯一的英文含义。 |
| N-05 | 同一个单词在不同模块中必须保持相同语义。 |
| N-06 | 正式生产名称使用英文；简体中文仅用于 UI 映射和双语注释。 |
| N-07 | 不得使用期数、临时状态或开发阶段作为生产名称。 |
| N-08 | 不得为了目录整齐而复制逻辑或建立空分类。 |

| 编号 | 模块命名规范 | 正确示例 | 禁止示例 |
| --- | --- | --- | --- |
| M-01 | 模块名使用小写英文。 | `deploy` | `Deploy` |
| M-02 | 模块名使用单数名词。 | `source` | `sources` |
| M-03 | 模块名表达稳定能力边界。 | `config`、`secret` | `tools`、`functions` |
| M-04 | 模块名不得包含实现阶段。 | `deploy` | `phase3-deploy` |
| M-05 | 模块名不得包含临时状态。 | `analyze` | `new-analyze` |
| M-06 | 模块名不得表达普通内部分类。 | `linux` | `linux-renderers` |
| M-07 | 模块不得因命名整理而拆分、合并或新增。 | `shared/deploy` 保持完整模块 | 将 `policy` 拆成独立模块 |
| M-08 | 平台实现模块使用明确技术限定词。 | `linux-sshd` | `linux-impl` |

### 2.2 包结构与标准职责包

包结构统一为：

```text
<模块根包>.<功能>[.<子功能>]
```

| 编号 | 包命名规范 | 正确示例 | 禁止示例 |
| --- | --- | --- | --- |
| P-01 | 包首先按功能划分。 | `deploy.lifecycle` | `deploy.classes` |
| P-02 | 子包按功能内部的独立职责划分。 | `build.ecosystem` | `build.objects` |
| P-03 | 包名使用小写英文。 | `validation` | `Validation` |
| P-04 | 包名使用单数名词。 | `policy` | `policies` |
| P-05 | 模块根包只放稳定入口、门面或公共契约。 | `DesktopPersistence` | 大量具体实现 |
| P-06 | 包名不得使用期数或版本。 | `protocol` | `protocol-v3` |
| P-07 | 包名不得使用语言、工作负载、运行机制、发行版和架构的笛卡尔组合。 | `ecosystem.java` | `java.service.systemd.ubuntu.x86_64` |
| P-08 | Java 包声明必须与物理目录完全一致。 | `build.ecosystem.java` | 路径与声明不一致 |
| P-09 | 包名不得使用宽泛容器词。 | `policy`、`validation` | `util`、`common`、`misc`、`impl` |
| P-10 | 包名不得重复模块名已经表达的含义。 | `deploy.plan` | `deploy.deployment.plan` |

| 功能组 | 包名 | 唯一职责 | 允许内容 | 禁止内容 |
| --- | --- | --- | --- | --- |
| 规则与契约 | `capability` | 能力事实 | 平台、工具、运行能力 | 支持策略 |
| 规则与契约 | `contract` | 类型化公共契约 | 请求、审批、计划、接口 | 具体实现 |
| 规则与契约 | `definition` | 静态定义 | 范围、默认值、允许值 | 运行时执行 |
| 规则与契约 | `policy` | 业务决策规则 | 允许、禁止、支持判断 | I/O、持久化 |
| 规则与契约 | `profile` | 配置组合 | 命名配置和差异数据 | 执行逻辑 |
| 规则与契约 | `spi` | 可替换实现接口 | 窄扩展契约 | 注册和默认实现 |
| 规则与契约 | `validation` | 合法性校验 | 输入和不变量检查 | 业务计划生成 |
| 内容生成 | `prompt` | AI 提示 | 提示定义、提示构建 | Provider 调用 |
| 内容生成 | `renderer` | 内容生成 | 配置、脚本、单元文件渲染 | 执行生成内容 |
| 内容生成 | `script` | 脚本组成 | Shell 片段、安全外壳 | 业务决策 |
| 内容生成 | `template` | 原始模板 | 固定文本、占位符 | 渲染和执行 |
| 适配与注册 | `adapter` | 技术或形态适配 | SPI 实现、模型转换 | 总流程编排 |
| 适配与注册 | `registry` | 实现注册 | 查找、选择、去重、完整性检查 | 具体业务执行 |
| 执行与流程 | `environment` | 环境准备 | 环境检查和准备流程 | 应用发布 |
| 执行与流程 | `lifecycle` | 生命周期 | 启停、重启、自启、观察 | 构建分析 |
| 执行与流程 | `migration` | 版本迁移 | 数据结构迁移 | 普通查询 |
| 执行与流程 | `protocol` | 类型化协议 | 固定请求、参数和结果 | 任意命令 |
| 执行与流程 | `runtime` | 运行机制 | 生命周期和运行时操作 | 静态分析 |
| 执行与流程 | `transaction` | 原子业务事务 | 发布、恢复、回滚协调 | UI 展示 |
| 执行与流程 | `transfer` | 受控传输 | 上传、下载、传输结果 | 部署决策 |
| 存储与结果 | `connection` | 连接基础 | 连接创建、事务基础 | 领域仓库 |
| 存储与结果 | `repository` | 持久化访问 | 聚合查询和事务写入 | 业务编排 |
| 存储与结果 | `result` | 公开结果 | 类型化结果和状态 | 执行器 |

### 2.3 正交功能维度

| 标准包名 | 唯一维度 | 包含内容 | 不包含内容 |
| --- | --- | --- | --- |
| `distro` | Linux 发行版 | Ubuntu、Debian、CentOS Stream、Rocky Linux、AlmaLinux、Oracle Linux 等身份、版本与准备差异 | 编程语言、部署形态、CPU 架构 |
| `ecosystem` | 技术生态 | C/C++、Java、Node、Python、Go、Rust、DotNet、Kotlin、PHP、Ruby 等语言的识别、构建架构、框架与工具链实现 | 共享模型、部署编排、工作负载、运行机制、发行版、CPU 架构 |
| `runtime` | 实际运行机制 | systemd、Docker、Podman 等运行与生命周期机制 | 源码语言分析、发行版身份、支持等级 |
| `workload` | 工作负载形态 | 容器、静态站点、普通服务等项目形态 | 编程语言、包管理器、Linux 发行版 |

| 技术生态 | 统一包名 |
| --- | --- |
| .NET | `dotnet` |
| C、C++ | `c` |
| Go | `go` |
| Java | `java` |
| Kotlin | `kotlin` |
| Node.js、JavaScript、TypeScript | `node` |
| PHP | `php` |
| Python | `python` |
| Ruby | `ruby` |
| Rust | `rust` |

| 编号 | 正交维度规范 | 正确示例 | 禁止示例 |
| --- | --- | --- | --- |
| O-01 | `ecosystem` 只保存行为会因语言、构建架构、框架或工具链而变化的具体实现。 | `analyze.ecosystem.java` | 把共享协调器迁入 `ecosystem` |
| O-02 | 共享枚举、模型、项目事实、部署计划、事务、UI、发行版和运行机制保持在原职责包。 | `model.language` | `ecosystem.model` |
| O-03 | 分析层先按语言完整聚合；同一语言存在多个独立构建架构时，各架构进入以工具名命名的子包。 | `ecosystem.java.maven`、`ecosystem.java.jar` | 横向 `build.maven` |
| O-04 | 构建架构包使用工具或架构的规范英文名全小写。 | `maven`、`npm`、`cmake`、`cargo` | `mavenbuild`、`rust-build` |
| O-05 | 分析层的语言识别器和跨架构选择器留在语言包；框架实现留在所属语言包，除非框架自身形成多个独立扩展职责。 | Spring Boot 位于 `ecosystem.java` | `ecosystem.springboot` |
| O-06 | 目标机构建统一归 `linux-sshd.build.ecosystem`；仅有一个独立构建架构的语言直接放置具名 Renderer，存在两个及以上架构时建立一个语言子包，各架构 Renderer 直接位于该语言包。 | `build.ecosystem.CargoBuildRenderer`、`build.ecosystem.java.*Renderer` | `build.ecosystem.rust.cargo.renderer` |
| O-07 | 语言与构建工具链探测、版本解析和检查脚本生成统一归 `linux-sshd.capability.ecosystem`。 | `capability.ecosystem` | 在 `distro` 中执行 `go version` |
| O-08 | 发行版只选择包集合和能力要求；APT/DNF 包名不得进入生态实现，语言命令不得进入发行版实现。 | `distro.apt` + `capability.ecosystem` | `distro.ubuntu.java` |
| O-09 | helper 中仅语言或工具链专属的资源片段进入 `ecosystem` 分组；协议基础、输入、发布、运行和生命周期片段保持原职责分组。 | `fragments/ecosystem` | 将 `00-protocol-foundation.sh` 移入生态目录 |
| O-10 | 是否建立语言分组由独立构建架构数量决定，不按枚举值、文件数或目录对称决定；不得为满足数量门禁制造陪衬类型。 | Java 多架构建立语言包 | 为单个类创建空 Facts |
| O-11 | C 与 C++ 统一属于 `c` 生态，C++ 作为独立能力扩展，不以 Java 继承关系代替构建架构；CMake 架构包名为 `cmake`。 | `ecosystem.c.cmake` | `ecosystem.cpp` 或 `Cpp extends C` |
| O-12 | 工作负载只表达容器、静态站点和普通服务等项目形态。 | `analyze.workload`、`build.workload` | `ecosystem.container` |
| O-13 | `runtime` 只表达 systemd、Docker、Podman 等实际运行机制；`distro` 只表达发行版；CPU 架构归 `capability`。 | `runtime.systemd`、`distro.apt` | `ecosystem.systemd`、`distro.x86_64` |
| O-14 | `ecosystem`、`workload`、`runtime`、`distro` 相互正交，不得建立跨维度笛卡尔组合包。 | `ecosystem.java` + `runtime.systemd` | `java.service.systemd.ubuntu.x86_64` |

### 2.4 约束与模板命名

| 功能组 | 约束类型 | 包名 | 类名后缀 | 唯一含义 |
| --- | --- | --- | --- | --- |
| 规则定义 | 默认值 | `definition` | `Defaults`、`Definition` | 声明默认配置 |
| 规则定义 | 静态范围 | `definition` | `Definition`、`Scope` | 声明可用范围 |
| 规则定义 | 执行前置条件 | `contract` 或所属功能包 | `Gate` | 表达必须通过的条件 |
| 规则定义 | 允许或禁止规则 | `policy` | `Policy` | 产生规则决定 |
| 规则定义 | 不可变规则数据 | 所属功能包 | `Rules` | 只保存规则数据 |
| 规则定义 | 输入合法性 | `validation` | `Validator` | 拒绝非法输入 |
| 能力判断 | 支持能力计算 | 所属功能包 | `Evaluator` | 根据事实产生决定 |
| 扩展契约 | 扩展实现约束 | `spi` | 实际角色名称 | 约束可替换实现 |

| 功能组 | 内容类型 | 包名 | 类名后缀 | 唯一含义 |
| --- | --- | --- | --- | --- |
| 内容生成 | AI 提示 | `prompt` | `Prompt` | 构建模型提示 |
| 内容生成 | 模板选择 | `registry` | `Registry` | 按类型选择模板或渲染器 |
| 内容生成 | 类型化内容生成 | `renderer` | `Renderer` | 输入对象生成最终文本 |
| 内容生成 | Shell 脚本片段 | `script` | `Script` | 表达脚本组成 |
| 内容生成 | 安全脚本外壳 | `script` | `ScriptEnvelope` | 包装环境和安全边界 |
| 内容生成 | 原始固定模板 | `template` | `Template` | 保存文本和占位符 |
| 内容执行 | 生成内容执行 | 所属执行包 | `Executor` | 执行已经生成的内容 |

### 2.5 行为类命名

行为类名称统一为：

```text
[范围或技术限定词] + [领域对象] + [角色后缀]
```

| 功能组 | 后缀 | 唯一含义 | 返回或产生 |
| --- | --- | --- | --- |
| 边界与交互 | `Client` | 调用具体网络协议或 API | 协议响应 |
| 边界与交互 | `Controller` | 接收 UI 或 API 输入并调用应用入口 | 界面或接口响应 |
| 边界与交互 | `Facade` | 聚合多个用例或端口 | 统一应用入口 |
| 边界与交互 | `Gateway` | 定义外部系统领域边界 | 类型化远端能力 |
| 边界与交互 | `Transport` | 执行底层数据传输 | 原始传输响应 |
| 分析与转换 | `Assembler` | 组合多个类型对象 | 聚合对象 |
| 分析与转换 | `Collector` | 聚合多个事实来源 | 能力或状态快照 |
| 分析与转换 | `Inspector` | 静态检查源码或配置 | `Facts` |
| 分析与转换 | `Mapper` | 将一种类型转换为另一种类型 | 目标类型 |
| 分析与转换 | `Observer` | 只读观察权威状态 | 当前状态 |
| 分析与转换 | `Parser` | 将外部文本转换为类型对象 | 类型化对象 |
| 分析与转换 | `Probe` | 主动读取一次实时事实 | 探测结果 |
| 分析与转换 | `Renderer` | 将类型对象转换为文本 | 配置、脚本、单元文件 |
| 分析与转换 | `Resolver` | 将输入解析为唯一规范目标 | 路径、身份或类型 |
| 分析与转换 | `Validator` | 校验输入和不变量 | 成功或异常 |
| 决策与选择 | `Adapter` | 实现 SPI 或外部形态适配 | 领域契约结果 |
| 决策与选择 | `Coordinator` | 安排多个参与者的调用顺序 | 协作结果 |
| 决策与选择 | `Evaluator` | 根据事实计算结论 | `Decision`、`Level` |
| 决策与选择 | `Policy` | 执行允许、禁止或支持判断 | `Decision` |
| 决策与选择 | `Registry` | 注册并选择实现 | 唯一实现 |
| 创建与准备 | `Factory` | 创建具有构造规则的对象 | 新对象 |
| 创建与准备 | `Planner` | 生成确定性执行计划 | `Plan` |
| 创建与准备 | `Preparer` | 将原始输入准备成受控对象 | 已准备对象 |
| 执行与业务 | `Executor` | 执行命令、协议或计划 | 执行结果 |
| 执行与业务 | `Migrator` | 执行版本化结构迁移 | 迁移结果 |
| 执行与业务 | `Service` | 执行可复用业务流程 | 领域结果 |
| 执行与业务 | `UseCase` | 完成一个用户目标 | 应用操作结果 |
| 持久化与展示 | `Presenter` | 将结果转换为展示内容 | 展示模型或文本 |
| 持久化与展示 | `Repository` | 持久化领域聚合 | 聚合或事务结果 |
| 持久化与展示 | `Store` | 保存秘密、载荷或简单值 | 存取结果 |

### 2.6 数据类命名与阶段

| 功能组 | 后缀 | 唯一含义 |
| --- | --- | --- |
| 事实与判断 | `Assessment` | 基于事实形成的分析结论 |
| 事实与判断 | `Decision` | `Policy` 或 `Evaluator` 产生的决定 |
| 事实与判断 | `Evidence` | 可审计的来源、证明或调用证据 |
| 事实与判断 | `Facts` | 已观察到的确定性事实，不包含判断 |
| 输入与授权 | `Approval` | 用户对明确内容的批准 |
| 输入与授权 | `Arguments` | 固定协议或命令的类型化参数 |
| 输入与授权 | `Context` | 一次协作所需的最小输入 |
| 输入与授权 | `Request` | 一次操作的完整类型化输入 |
| 计划与执行 | `Action` | 可执行的封闭动作 |
| 计划与执行 | `Gate` | 执行前必须满足的条件 |
| 计划与执行 | `Outcome` | 面向应用或 UI 的最终操作摘要 |
| 计划与执行 | `Plan` | 执行前生成的确定性有序计划 |
| 计划与执行 | `Result` | 底层或可复用操作结果 |
| 状态与事件 | `Event` | 已经发生的事实 |
| 状态与事件 | `Snapshot` | 某个时间点的不可变状态 |
| 状态与事件 | `State` | 对象当前的组合状态 |
| 状态与事件 | `Status` | 单一有限状态值 |
| 配置与定义 | `Catalog` | 有限且权威的支持项集合 |
| 配置与定义 | `Configuration` | 已选择并可以实际应用的配置 |
| 配置与定义 | `Definition` | 类型、范围和默认规则定义 |
| 配置与定义 | `Profile` | 可选择、可复用的命名配置组合 |
| 配置与定义 | `Rules` | 不执行行为的不可变规则数据 |
| 配置与定义 | `Scope` | 定义配置或操作的适用范围 |
| 配置与定义 | `Specification` | 希望达到的目标规格 |
| 标识与分类 | `Identity` | 稳定且规范化的对象身份 |
| 标识与分类 | `Kind` | 领域对象的封闭类别 |
| 标识与分类 | `Level` | 有序等级 |
| 标识与分类 | `Mode` | 用户或系统选择的工作模式 |
| 标识与分类 | `Type` | 协议或模型定义的类型分类 |
| 描述与引用 | `Descriptor` | 对资源属性的不可变描述 |
| 描述与引用 | `Entry` | `Manifest`、`Snapshot` 或存储结构中的单个条目 |
| 描述与引用 | `Manifest` | 可序列化的条目清单 |
| 描述与引用 | `Reference` | 指向外部或不透明对象的稳定引用 |

数据阶段名称统一按以下顺序使用：

```text
Facts → Evidence → Assessment → Decision → Plan → Result/Outcome
```

| 顺序 | 类型 | 唯一职责 |
| --- | --- | --- |
| 1 | `Facts` | 保存确定性事实 |
| 2 | `Evidence` | 保存事实依据 |
| 3 | `Assessment` | 形成分析结论 |
| 4 | `Decision` | 形成规则决定 |
| 5 | `Plan` | 形成执行计划 |
| 6 | `Result` | 保存底层执行结果 |
| 7 | `Outcome` | 形成应用最终摘要 |

### 2.7 限定词、接口与实现

| 功能组 | 限定词 | 唯一含义 | 使用边界 |
| --- | --- | --- | --- |
| 审阅与状态 | `Current` | 当前成功或当前生效状态 | 不得代替实时远端状态 |
| 审阅与状态 | `Reviewed` | 已经用户明确审阅和批准 | 未审阅对象不得使用 |
| 审阅与状态 | `Successful` | 已完成并通过最终验证 | 中间状态不得使用 |
| 所有权与位置 | `Local` | 属于本地 Windows 或源码侧 | 远端对象不得使用 |
| 所有权与位置 | `Managed` | 由产品创建、验证并接管 | 外部非受管资源不得使用 |
| 所有权与位置 | `Remote` | 来自目标 Linux 主机 | 本地对象不得使用 |
| 所有权与位置 | `Stored` | 持久化层存储记录 | 不得用于公共领域模型 |
| 约束与保证 | `Bounded` | 输入范围封闭且具有拒绝条件 | 不得作为普通强调词 |
| 约束与保证 | `Controlled` | 操作范围由固定协议限制 | 不得接受任意命令 |
| 约束与保证 | `Immutable` | 创建后不可变 | 必须由类型结构保证 |
| 约束与保证 | `Safe` | 实现明确安全不变量和拒绝路径 | 必须有对应验证 |
| 实现与协议 | `Default` | 注册表明确选择的默认实现 | 不得表示当前唯一实现 |
| 实现与协议 | `OpenAiCompatible` | 使用 OpenAI 兼容协议 | 不表示由 OpenAI 官方提供 |

| 编号 | 接口与实现命名规范 | 正确示例 | 禁止示例 |
| --- | --- | --- | --- |
| I-01 | 接口不得添加 `I` 前缀。 | `LinuxGateway` | `ILinuxGateway` |
| I-02 | 实现类不得添加 `Impl` 后缀。 | `SshdLinuxGateway` | `LinuxGatewayImpl` |
| I-03 | 实现类使用技术或行为限定词。 | `ContainerAdapter` | `DefaultAdapterImpl` |
| I-04 | SPI 接口使用真实行为角色命名。 | `DeploymentAdapter` | `DeploymentPluginInterface` |
| I-05 | 实现类名称必须体现实现差异。 | `SshdLinuxGateway` | `ConcreteLinuxGateway` |
| I-06 | 不得使用宽泛抽象基类名称。 | 使用接口和组合 | `BaseService` |
| I-07 | 抽象能力优先使用接口和组合。 | `DeploymentAdapter` | `AbstractGenericAdapterBase` |

### 2.8 单复数、缩写及禁限用名称

| 编号 | 类型单复数规范 | 正确示例 | 禁止示例 |
| --- | --- | --- | --- |
| C-01 | 普通类名使用单数。 | `DeploymentUseCase` | `DeploymentUseCases` |
| C-02 | 单个领域对象使用单数。 | `ServerProfile` | `ServerProfiles` |
| C-03 | 有限权威集合使用 `Catalog`。 | `DeploymentSupportCatalog` | `DeploymentSupports` |
| C-04 | 实现映射使用 `Registry`。 | `DeploymentAdapterRegistry` | `DeploymentAdapters` |
| C-05 | 序列化清单使用 `Manifest`。 | `SourceManifest` | `SourceFiles` |
| C-06 | 固定集合数据使用 `Sets`。 | `AptPackageSets` | `PackageData` |
| C-07 | 静态操作集合不得通过复数类名表达。 | `ServerUseCaseFacade` | `ServerUseCases` |

| 概念 | 统一形式 | 禁止形式 |
| --- | --- | --- |
| Artificial Intelligence | `Ai` | `AI` |
| API | `Api` | `API` |
| CentOS | `Centos` | `CentOS`、`CentOs` |
| CPU | `Cpu` | `CPU` |
| .NET | `DotNet` | `Dotnet` |
| HTTP | `Http` | `HTTP` |
| ID | `Id` | `ID` |
| JAR | `Jar` | `JAR` |
| JSON | `Json` | `JSON` |
| JVM | `Jvm` | `JVM` |
| OpenAI | `OpenAi` | `OpenAI`、`Openai` |
| SFTP | `Sftp` | `SFTP` |
| SHA-256 | `Sha256` | `SHA256` |
| SQLite | `Sqlite` | `SQLite` |
| SSH | `Ssh` | `SSH` |
| SSHD | `Sshd` | `SSHD` |
| TCP | `Tcp` | `TCP` |
| TLS | `Tls` | `TLS` |
| URL | `Url` | `URL` |

| 名称 | 使用规则 | 例外 |
| --- | --- | --- |
| `Base` | 禁止作为宽泛父类名称 | 明确稳定继承协议 |
| `Bean`、`Object` | 禁止 | 无 |
| `Checker` | 禁止 | 使用 `Validator`、`Evaluator` 或 `Probe` |
| `Common`、`Misc` | 禁止用于类型、包和受维护资源文件名 | 无 |
| `Concrete` | 禁止表示具体实现 | 使用技术或功能限定词 |
| `Controller` | 限制为 UI 或 API 输入边界 | 非 UI 或 API 操作使用 `Executor` |
| `Data`、`Info` | 禁止表示普通数据对象 | `DataPath` 等真实领域术语 |
| `Generic` | 禁止表示不明确通用实现 | 无 |
| `Handler` | 限制为事件或框架入口 | UI、HTTP、事件处理入口 |
| `Helper` | 禁止表示普通辅助类 | Managed Helper 正式协议 |
| `Impl`、`Implementation` | 禁止 | 无 |
| `Manager` | 禁止表示普通协调器 | Credential Manager 等正式名称 |
| `Legacy`、`New`、`Old` | 禁止 | 无 |
| `Processor` | 禁止表示不明确行为 | 明确消息处理协议 |
| `Support` | 禁止表示工具集合 | 支持矩阵领域概念 |
| `Temp`、`Temporary` | 禁止作为正式类型名 | 明确临时文件领域对象 |
| `Util`、`Utils` | 禁止 | 无 |
| `V1`、`V2`、`Phase1` | 禁止进入生产类型和包名 | 外部版本化 API 契约 |

| 编号 | 资源文件命名规范 | 正确示例 | 禁止示例 |
| --- | --- | --- | --- |
| R-01 | 受维护资源文件名必须表达稳定功能。 | `00-protocol-foundation.sh` | `00-common.sh` |
| R-02 | 禁限用名称同样适用于生产和测试资源文件名。 | `deployment-input.properties` | `deployment-utils.properties` |
| R-03 | 资源文件名不得包含开发阶段或内部版本。 | `managed-helper.sh` | `phase2-helper.sh`、`helper-v3.sh` |
| R-04 | `Helper` 仅允许表达 Managed Helper 正式协议资源。 | `protocol/helper` | 普通辅助资源使用 `helper` |

### 2.9 异常、枚举和测试类命名

| 编号 | 异常命名规范 | 正确示例 | 禁止示例 |
| --- | --- | --- | --- |
| E-01 | 异常使用 `Exception` 后缀。 | `LinuxOperationException` | `LinuxOperationError` |
| E-02 | 名称必须说明失败边界。 | `SecretStoreException` | `OperationException` |
| E-03 | 公共异常不得直接以底层实现细节命名。 | `LinuxOperationException` | `SshChannelException` |
| E-04 | 安全异常名称不得包含秘密内容。 | `SecretStoreException` | `InvalidPasswordValueException` |
| E-05 | 本地化失败与原始诊断分离。 | `LocalizedOperationException` | 中文异常类名 |

| 枚举性质 | 统一后缀 | 示例 |
| --- | --- | --- |
| 可执行动作 | `Action` | `LifecycleAction` |
| 规则决定 | `Decision` | `HostKeyDecision` |
| 处置结果 | `Disposition` | `CollaborationDisposition` |
| 已发生事件 | `Event` | `DeploymentTraceEvent` |
| 封闭类别 | `Kind` | `RuntimeKind` |
| 有序等级 | `Level` | `DeploymentSupportLevel` |
| 工作模式 | `Mode` | `RunMode` |
| 命名配置 | `Profile` | `EcosystemCapabilityProfile` |
| 适用范围 | `Scope` | `ConfigurationScope` |
| 选择来源 | `Source` | `CredentialSource` |
| 当前状态 | `State` | `RuntimeState` |
| 操作状态 | `Status` | `DeploymentStatus` |
| 领域类型 | `Type` | `DeploymentProjectType` |

| 编号 | 枚举命名规范 | 正确示例 | 禁止示例 |
| --- | --- | --- | --- |
| G-01 | 枚举必须使用“领域对象＋语义后缀”。 | `LinuxDistroType` | `LinuxDistro` |
| G-02 | 统一后缀表是穷举白名单，不允许清晰领域名例外。 | `SourceLanguageType` | `SourceLanguage` |
| G-03 | 生产、测试、顶级和嵌套枚举使用同一规则。 | `ComponentIssue.SeverityLevel` | `ComponentIssue.Severity` |
| G-04 | 顶级类型简单名称在仓库内必须唯一。 | `DeploymentPlanAction`、`DeploymentTraceEvent` | 两个 `DeploymentStep` |
| G-05 | 嵌套类型以外部类型形成唯一作用域，但仍必须使用语义后缀。 | `ComponentDataPath.AccessMode` | `ComponentDataPath.Access` |

枚举常量统一使用：

```text
UPPER_SNAKE_CASE
```

| 测试类型 | 统一格式 | 示例 |
| --- | --- | --- |
| 验收测试 | `<Capability>AcceptanceTest` | `ReviewedDeploymentAcceptanceTest` |
| 架构测试 | `<Rule>ArchitectureTest` | `PackageNamingArchitectureTest` |
| 契约测试 | `<Contract>ContractTest` | `ManagedRemoteContractTest` |
| 集成测试 | `<Subject>IntegrationTest` | `DesktopPersistenceIntegrationTest` |
| 回归测试 | `<Subject>RegressionTest` | `DeploymentRollbackRegressionTest` |
| 单元测试 | `<Subject>Test` | `SourceBoundaryValidatorTest` |

## 3. 模块职责

### 3.1 `shared` 共用模块

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

在模块边界上，`linux` 只保留连接、会话、传输、能力、构建、运行、发行版和高权限操作的公共契约。`DeploymentLinuxGateway` 扩展 `LinuxGateway` 并直接返回具有构建、快照、发布、回滚和保留能力的 `DeploymentRemoteSession`；`SshdLinuxGateway`、SSHD Session、受控命令执行、SFTP、构建、六种发行版环境准备、systemd 运行和高权限 helper 均位于已收紧的 `linux-sshd` 内部包。`DesktopApplicationFacade` 直接接收 `DeploymentLinuxGateway`，由 `app/main/startup` 构造并注入具体 SSHD 实现。

### 3.2 `app` 桌面应用模块

| 模块 | 职责 | 明确不负责 |
| --- | --- | --- |
| `ui` | 实现 Swing/FlatLaf 界面、输入校验、进度展示和用户决定交互。 | 不直接访问 SQLite、SSH、AI、加密算法或 Windows Credential Manager。 |
| `windows` | 选择和访问 Windows 本地项目、备份及工作目录，采集文件与环境事实，并通过 `shared/source` 准备待上传的项目包。 | 不自行定义源码归档格式或安全校验规则，不执行远程上传，不解释备份格式，不处理加密、密钥、敏感信息或凭据。 |
| `db` | 管理 SQLite 连接、表结构、版本化迁移和事务，保存非敏感数据、配置实例与历史版本、受管应用标识、最后观测状态及由 `secret` 生成的加密数据。 | 不定义共用配置规则，不执行加解密，不派生或保存明文密钥，不直接访问 Windows Credential Manager，不把最后观测状态当作远端事实。 |
| `secret` | 统一处理主密码、Argon2id 密钥派生、AES-256-GCM 加解密、DEK 包装、敏感信息存取和 Windows Credential Manager 平台适配。 | 不负责普通业务数据、界面、项目打包、部署或远程连接。 |
| `service` | 实现桌面端部署和受管应用生命周期等用例、后台任务、同服务器修改互斥、事件、取消和模块协作，整合 `app` 与 `shared` 能力。 | 不自行实现界面、SQLite、加密算法、凭据平台接口、SSH/SFTP 或 Linux 生命周期动作。 |
| `main` | 提供应用启动入口，识别运行模式、解析应用与数据目录，装配模块和 `linux-sshd` 实现并管理生命周期。 | 不承载具体业务规则、界面逻辑、持久化、SSH/SFTP 或加密实现。 |

桌面端所有加密、解密、密钥派生、密钥包装、敏感信息存取和 Windows Credential Manager 调用都必须位于 `secret`。`windows` 只处理非敏感的 Windows 本地能力；不得为了平台调用方便把任何安全实现放入 `windows`。

桌面调用方向固定为 `UI → app/service/contract → 窄用例 → shared 领域/契约`。`AiApplicationFacade`、`DeploymentApplicationFacade`、`MultiComponentApplicationFacade`、`ServerApplicationFacade` 和 `ManagedApplicationFacade` 隔离页面所需能力；`DesktopApplicationFacade` 作为稳定门面和组合根实现这些门面，只转发到窄用例，不承载业务算法。

### 3.3 `web` Web 应用模块

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

### 3.4 配置规则、存储与秘密边界

1. `shared/config` 只提供平台无关的配置类型与规则，不读写文件或数据库，也不定义 Repository、数据库表或具体持久化技术。
2. 桌面端配置实例及历史版本由 `app/db` 统一保存；Web 配置实例、项目、环境关联及历史版本由 `web/db` 统一保存，不为每名用户创建独立配置文件。Web 后续支持多用户或租户时，所有权关联同样由 `web/db` 保存。
3. Web 配置访问权限由 `web/auth` 和 `web/service` 校验；`shared/config` 不感知具体用户、租户、会话或授权策略。
4. 敏感值由 `app/secret` 或 `web/secret` 处理；普通配置只保存秘密 ID、版本等不透明引用，不保存明文秘密。
5. `app/main/config` 和 `web/main/config` 继续处理各自应用的启动配置，不属于 `shared/config`。

### 3.5 桌面端本地化与诊断边界

1. 英文是桌面端生产代码、内部校验、程序生成诊断和默认消息资源的基准语言；`app/ui/i18n/messages/Messages.properties` 是消息目录的规范来源，简体中文只保存在 `Messages_zh_CN.properties`。
2. 生产模块不得提前拼接英文或中文界面句子。固定用户文案必须使用稳定消息键和命名参数表达；跨模块失败通过 `LocalizedFailure` 分离 `userMessage()` 与不含秘密的 `diagnostic()`，UI 是唯一负责将消息键解析为显示语言的边界。
3. SSH、systemd、HTTP、第三方 Provider 和远端命令返回的原始技术内容属于诊断证据，不翻译、不改写、不作为消息键。UI 以本地化摘要加原始技术详情展示，并在两个部分继续执行密码、私钥、凭据和 API Key 脱敏。
4. 部署步骤、部署状态、置信度、运行状态、自启状态和生命周期动作必须以稳定代码、`record` 或 `enum` 跨模块传递，不得依赖 `Object.toString()` 或显示字符串表达业务状态。
5. AI 提示模板使用英文编写，只发送经过既有边界脱敏的结构化事实；调用时显式传入受限响应语言，当前桌面语言为 `zh-CN` 时请求 Simplified Chinese，为 `en` 时请求 English。
6. 桌面端目前只支持 `en` 与 `zh-CN`。没有已保存偏好时，中文系统语言使用 `zh-CN`，其他系统语言使用 `en`；用户明确选择后，以持久化偏好为准，运行期间不跟随系统语言自动变化。

## 4. 叶子模块约定

### 4.1 Maven 通用目录结构

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

`shared`、`app` 和 `web` 的 Java 叶子模块没有实现内容时不提前创建空目录。`web/frontend` 使用前端工程自己的 `package.json`、源码和测试结构，不适用上述 Maven 目录。Java 包前缀固定为 `gold.debug.windowstolinux`；第 1 节展示正式目标包结构，未实现职责不创建空包。

### 4.2 适配协作边界

语言生态、构建架构、工作负载、运行机制、发行版和 CPU 架构是相互独立的适配维度。分析、目标机构建和能力探测可以分别拥有 `ecosystem`，但必须共享模型、部署契约和机械执行流程。

```text
analyze                        识别语言、构建工具、框架和工作负载事实
linux.connection/session      定义连接、信任和类型化组合会话契约
linux.transfer                定义受控传输契约
linux.capability              定义发行版、版本、CPU 和运行能力采集契约
linux.build                   定义受控目标机构建契约
linux.runtime                 定义运行方式生命周期契约
linux.distro                  定义发行版事实与环境准备契约
linux-sshd.command            实现不向上层暴露的受控 SSH 命令机械流程
linux-sshd.build.ecosystem    实现语言与构建架构差异
linux-sshd.build.workload     实现容器与静态站点构建形态
linux-sshd.capability         实现平台能力采集
linux-sshd.capability.ecosystem 实现工具链探测、版本解析和检查脚本生成
linux-sshd.distro             定义准备渲染契约并执行受管环境准备
linux-sshd.distro.apt         完整保存 APT 机械流程、包集合与 Debian 家族差异
linux-sshd.distro.dnf         完整保存 DNF 机械流程、包集合与企业 Linux 差异
linux-sshd.distro.profile     保存不可变发行版与生态能力配置
linux-sshd.distro.registry    完成发行版配置装配与唯一注册
linux-sshd.distro.script      生成发行版通用准备脚本
linux-sshd.runtime            实现语言无关生命周期
deploy.support                编排架构、systemd、发行版和运行时支持判断
deploy.support.distro         独立评估六种发行版的版本、CPU 和安全规则
deploy.support.runtime        匹配语言、容器和静态站点所需工具与版本
deploy.adapter                按部署形态生成类型化计划，不镜像语言生态
deploy.transaction            编排上传、构建、发布、健康检查和回滚
```

1. `analyze.ecosystem` 先按语言聚合识别、构建事实和框架分析；同一语言的独立构建架构按第 2.3 节使用工具名子包。Java 的 Maven、Gradle 与 JAR 必须形成平行架构，不得把 JAR 留作语言包中的特例。
2. `linux-sshd.build` 通过 `DeploymentBuildRenderer` SPI、注册表和安全脚本外壳组织构建。生态差异进入 `build.ecosystem`，容器与静态站点进入 `build.workload`；构建执行器、SSH command、systemd 生命周期和 helper 调度不得复制到各生态。
3. `linux-sshd.capability.ecosystem` 实现语言与工具链命令、版本解析和能力检查脚本；`distro` 只提供软件包集合与所需能力配置，两者通过窄契约组合。
4. `linux-sshd.distro.apt` 完整保存 Ubuntu/Debian 的准备差异，`distro.dnf` 完整保存 CentOS Stream、Rocky Linux、AlmaLinux 与 Oracle Linux 的准备差异；具体发行版不得互相充当别名。
5. `deploy.adapter` 只按部署形态组织，`linux-sshd.runtime` 只按实际运行机制实现生命周期；两者都不得镜像语言生态。
6. CPU 架构、指令集和平台能力通过 `linux.capability` 契约采集；没有独立策略与实现时，不创建 `x86_64`、`arm64` 等执行包。
7. 没有匹配到正式支持组合时，只返回识别预览或不支持结果，不得进入环境安装、构建、发布或生命周期接管。
8. `linux` 公共契约不得引用 Apache SSHD 类型，也不得向上层暴露任意 Shell、原始 SFTP 或不受控 systemd、Docker、Podman 操作；具体远程实现只能位于 `linux-sshd`。

### 4.3 分包规则

1. Maven 模块表达依赖、技术和安全边界；Java 包和前端目录只负责模块内部组织，必须遵守第 5 节依赖方向。
2. 模块根包只保留稳定入口、门面或确需跨内部包使用的公共契约，具体实现进入职责明确的子包。
3. 测试包镜像对应生产包；根目录测试夹具使用 `test/<language>/<build-tool>/<framework-or-function>/<fixture>` 分类，新增语言、构建工具、框架或功能时创建对应同级目录，不创建没有夹具的空分类。
4. 模块、包、类、接口、枚举、异常和测试类名称统一遵守第 2 节，不得另立同义词、临时名称或兼容名称。
5. `analyze` 的跨语言公共流程按 `core`、`spi`、`registry`、`source`、`service`、`policy`、`component`、`workload` 与 `preview` 分包；`ecosystem` 内按语言聚合，并按第 2.3 节为真实独立构建架构建立工具名子包。
6. `deploy` 将不可变输入/计划、SPI、注册表、部署形态、支持矩阵和事务编排分离；适配器统一进入 `adapter`，`support` 只保留支持判断门面，发行版策略和规则进入 `support.distro`，运行时工具与版本判断进入 `support.runtime`，公开结果分别进入 `result.compatibility`、`result.deployment`、`result.lifecycle`，`plan` 不得直接构造具体实现。
7. `linux` 按连接、会话、错误、传输、能力、构建、运行机制、发行版和协议组织公共契约；`linux-sshd` 的生态实现只可进入 `build.ecosystem`、`capability.ecosystem` 和 helper 资源 `ecosystem` 分组，工作负载构建只可进入 `build.workload`。
8. 建立构建架构包和执行层语言分组时以独立架构数量为依据，不以类数量为依据；不得为满足目录对称或门禁数量新增空分类、空接口、委托壳或无独立语义的数据类型。
9. 界面、数据库、认证、秘密和普通业务用例不得按被部署项目的语言复制结构。
10. 包结构不用于绕开模块职责。跨模块能力仍通过既有依赖和类型化契约协作，不复制模型，不向上层开放任意 Shell、原始 SFTP 或不受控 systemd、Docker、Podman 操作。
11. `linux` 的接口、请求、结果和异常不得导入或暴露 Apache SSHD 类型；`linux-sshd` 可以依赖 Apache SSHD，但不得把具体客户端、会话、通道或 SFTP 类型传递给上层模块。
12. 生产包依赖不得成环；组合门面只能依赖下游窄契约和实现，低层 command、SPI、不可变契约与错误类型不得反向依赖注册表、默认实现、会话或业务编排。
13. 模块 Java 根包以下默认最多两层子包。第三层只允许 `analyze.ecosystem.<language>.<architecture>`、`linux-sshd.build.ecosystem.<language>` 和 `linux-sshd.capability.ecosystem.<language>`：分析层以独立架构为边界，构建与能力层仅在同一语言存在两个及以上独立架构时建立语言分组。其他第三层包必须先修改本文并单独评审；总包数、单包类型数量和目录对称不作为硬门禁。
14. 普通类最多 25 个实例字段、30 个直接声明方法，单方法最多 80 个 JDK AST 语句；稳定门面 `DesktopApplicationFacade` 只豁免直接声明方法数。
15. 合并依据是行为完全一致且差异可由受校验数据表达，拆分依据是存在可独立测试和命名的职责；领域记录、枚举、状态类型和 SPI 不因文件短小而合并，类也不因行数较长而机械拆分。

### 4.4 Linux 部署链内部依赖方向

以下箭头表示左侧包可以依赖右侧包；反向依赖均禁止：

```text
deploy.adapter        ──→ deploy.spi ──→ deploy.contract ──→ model
deploy.registry       ──→ deploy.spi + deploy.adapter
deploy.plan           ──→ deploy.contract + deploy.registry
deploy.transaction    ──→ deploy.plan + deploy.result.deployment + linux
deploy.lifecycle      ──→ deploy.result.lifecycle + linux
deploy.support        ──→ deploy.support.{distro,runtime} + deploy.result.compatibility + model
deploy.support.distro ──→ deploy.result.compatibility + model
deploy.support.runtime ──→ model

linux.connection      ──→ linux.session ──→ linux.{build,capability,distro,protocol,runtime,transfer}
linux.* operations    ──→ linux.error ──→ model.message

linux-sshd.command          ──→ linux.error + Apache SSHD
linux-sshd.build            ──→ linux-sshd.{build.ecosystem,build.registry,build.script,build.spi,build.workload,command}
linux-sshd.build.ecosystem  ──→ linux-sshd.{build.script,build.spi}
linux-sshd.build.workload   ──→ linux-sshd.{build.script,build.spi}
linux-sshd.capability       ──→ linux-sshd.capability.ecosystem
linux-sshd.distro           ──→ linux-sshd.{distro.script,capability.ecosystem,command}
linux-sshd.distro.{apt,dnf} ──→ linux-sshd.{distro,distro.profile,distro.script}
linux-sshd.distro.registry  ──→ linux-sshd.{distro,distro.apt,distro.dnf}
linux-sshd.distro.script    ──→ linux-sshd.{distro.profile,protocol.helper}
linux-sshd.{protocol,runtime,transfer} ──→ linux-sshd.command
linux-sshd.session          ──→ linux-sshd.{build,capability,distro,protocol,runtime,transfer}
linux-sshd.connection       ──→ linux-sshd.session + linux-sshd.command
```

本次平衡分包的内部方向固定如下；父包保存共享入口时可依赖职责子包，纯数据子包不得反向依赖协调入口：

```text
ai.collaboration            ──→ ai.collaboration.{invocation,advice}
ai.collaboration.invocation ──→ ai.collaboration.{advice,role}
app.ui.deployment.{single,multi} ──→ app.ui.deployment
app.service.deployment      ──→ app.service.deployment.{single,multi}
deploy.{support,transaction,lifecycle} ──→ deploy.result.{compatibility,deployment,lifecycle}
model.project               ──→ model.language
model.capability            ──→ model.server.security
```

## 5. 依赖方向

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
16. 桌面页面只能依赖对应的 `app/service/contract` 窄门面；门面依赖用例和 shared 契约，具体用例、注册表或 SSHD 实现不得反向依赖 UI。

## 6. 桌面端数据目录

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

## 7. Git 项目准备边界

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

## 8. 环境部署边界

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

## 9. 受管应用生命周期边界

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

## 10. 备份、恢复与迁移边界

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

## 11. 维护规则

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
16. 分期是开发路线与验收文档的组织方式，不是产品运行时架构。`src/` 中的模块、包、类、方法、字段、枚举、消息键、配置键、资源名、脚本名和测试名不得以 `PhaseOne`、`PhaseTwo`、`phase1`、`phase2`、一期、二期等期数命名；必须按稳定职责命名。正式文档可保留分期标题和历史记录，但不得把期数泄漏为代码 API 或持久化契约。
17. `app/ui/deployment` 只能收集并构造已声明的 `DeploymentProjectType`、`DeploymentRuntimeSpecification`、`ConfigurationSnapshot`、`SecretReference` 和计划审阅输入；不得暴露任意 Shell、启动命令、主机路径挂载或未审阅的秘密文本。项目类型、运行时字段和容器选项改变后，必须重新进行静态源码分析；桌面页面状态切换外观或语言时必须保留这些尚未提交的表单值。
18. 类型化部署分析必须把确定的源码元数据作为可审阅的 `DeploymentRuntimeAssessment` 返回，而非由桌面表单写死语言版本、入口、产物目录、端口或卷。仅在值唯一、受支持、边界安全且具有 `AnalysisEvidence` 时才可回填；范围、冲突、任意脚本和文档文字只能作为未解决的用户输入，绝不转换为命令。
19. 本地目录和 Git 来源都必须在 `app/service/source` 汇合为同一 `ReviewedSourcePreparation`，并以归档摘要绑定 `SourceRevision`。网络 Git 来源必须使用无凭据 URI、允许主机、固定 Commit 和受控工作目录；桌面 UI 不得调用 Git 进程、数据库或秘密存储实现。
20. `DeploymentAnalysisCoordinator` 不得导入具体项目类型实现；`ProjectLanguageInspector` 只负责组合确定性的语言事实检查器。低层语言与构建 Inspector 不得修改调用方提供的拒绝集合，生产包与测试包必须镜像，包依赖不得成环，且不得恢复按阶段或宽泛类别聚合实现的包。`deploy.plan` 不得构造具体适配器，`linux.connection` 不得保存异常或组合会话，`linux-sshd.connection` 不得保存 command 或总会话；禁止以兼容壳保留旧类型。
21. 用户可见和持久化语义统一使用“发布身份摘要”（`release_sha256`）；“制品”仅描述构建过程中待验证的文件，不得再把已发布身份称为制品摘要。桌面 SQLite 当前 schema 为 v7；v5 保留发布身份语义，v6 增加受约束的 AI 角色到命名 Provider 外键，v7 增加成功整应用的组件/依赖图并与全部组件发布状态原子提交；v4 的 `artifact_sha256` 已通过列重命名无损迁移并继续表示既有发布身份。
22. `DeploymentSupportProfile` 是语言、框架、支持等级与真实验收目标范围的唯一共享声明；`RECOGNITION_PREVIEW` 只能由 `analyze` 读取有界路径和固定元数据，必须使用 `NONE_PREVIEW`，不得创建源码归档、部署适配器、远端构建渲染器、helper 参数或生命周期入口。Shell 文件只可作为识别证据，不能转换成命令。
23. `PackageStructureArchitectureTest` 使用 JDK 编译器 AST、物理路径和生产导入图自动检查文件与顶级类型同名、仓库级顶级类型唯一性、顶级及嵌套枚举语义后缀、禁限用词及封闭例外、资源文件名、复数后缀、缩写、测试后缀、默认两层与第 4.3 节受控第三层、语言/构建架构/发行版分类轴、禁用包名、全部包依赖环、测试包镜像、职责映射、反向依赖、旧 FQCN、旧包、已删除包装类以及唯一 `AppMain.main`；不设置总包数或单包类型数量硬上限，不得通过文本豁免隐藏结构回归。

## 12. 文档版本记录

| 版本 | 日期 | 说明 |
| --- | --- | --- |
| 3.21.0-ecosystem-architecture | 2026-08-19 | 在三期扩展开发文档之前确立并迁移 ecosystem 正式基线：Java JAR 与 Maven/Gradle 平行，目标机构建与能力探测分别使用 `build.ecosystem`、`capability.ecosystem`，helper 仅将语言专属片段归入资源 ecosystem 分组；六种参数化服务构建改为具名原生架构 Renderer，C/C++ 统一归 `c`。同步删除旧参数化 Renderer、固定 Java 白名单、现状计数和运行证据长段等过时或重复规则；不改变 helper 字节、协议、持久化或运行支持范围。 |
| 3.20.0-strict-naming-compliance | 2026-08-19 | 将生产、测试、顶级与嵌套枚举统一为穷举语义后缀，消除两个不同职责的 `DeploymentStep` 及其余无后缀枚举，并将 `00-common.sh` 更名为 `00-protocol-foundation.sh`；架构门禁新增枚举后缀、顶级类型唯一性、受维护资源名和禁限用词封闭例外检查。模块、包结构、枚举常量、helper 内容与摘要、协议、持久化和运行支持范围不变。 |
| 3.19.1-naming-rule-order | 2026-08-19 | 将统一命名规范中的职责包、约束与模板、行为后缀、数据后缀、限定词、缩写、禁限用名称、枚举及测试格式按功能归组，并在各功能组内按英文标准名称排序；阶段流和带编号规则保持原有语义顺序，不改变任何命名规则、源码、模块、API、协议或运行能力。 |
| 3.19.0-naming-migration | 2026-08-19 | 完成统一类型/文件命名、应用门面与桌面设置分包、AI 传输职责分离、语言生态完整聚合及 APT/DNF 发行版扩展单元迁移；17 个可选真实主机测试统一使用 `AcceptanceTest` 后缀并保留系统属性门禁，架构测试同步覆盖命名、路径、深度、分类轴、反向依赖与包循环。本次不改变 Maven 模块、协议、持久化、helper 资源、安全边界或运行支持声明。 |
| 3.18.0-naming-standard | 2026-08-19 | 将模块、包、职责包、技术生态、工作负载、运行机制、发行版、约束、模板、行为类、数据类、限定词、接口、实现、缩写、异常、枚举和测试类命名统一为正式目标规范，并顺延后续章节编号；本次仅修改文档，不改变 Maven 模块、源码、测试、API、协议、安全边界或运行能力，现有源码命名迁移留待后续独立任务。 |
| 3.17.0-balanced-package-depth | 2026-08-19 | 将 AI 协作、部署 UI、应用部署契约、部署结果、语言模型和服务器安全模型按共同职责收进浅层子包；父包保留稳定入口与共享契约，新增类型职责映射、旧 FQCN/路径及反向依赖门禁，移除总包数和单包类型数量硬门禁。业务行为、协议、持久化、helper、安全与运行支持边界不变。 |
| 3.16.0-deploy-support-layout | 2026-08-19 | 将 `shared.deploy.support` 从发行版、语言运行时和公开结果混合包拆为编排门面、`support.distro`、`support.runtime` 与 `result`；保留六种独立发行版策略，以单一参数化检查器匹配语言工具版本，不改变支持状态、证据顺序、异常、安全与远端行为。 |
| 3.15.0-language-ecosystem-layout | 2026-08-19 | 将 `shared.analyze` 调整为 ecosystem 外保存跨语言公共职责、ecosystem 内按九种语言组织；Java 的 Maven/Gradle 建立受门禁约束的第三层真实扩展点，六种服务语言由各自事实类型和检查器承担解析，删除参数化大类并保持分析结果与部署边界不变。 |
| 3.14.3-profile-package-sets | 2026-08-19 | 将只承载发行版软件包数据的 `DistributionPackageSets` 从 `distro.script` 迁入 `distro.profile` 并收紧为包内可见；脚本包只保留 APT/DNF Renderer 与共享 Shell 生成逻辑，软件包集合和生成脚本不变。 |
| 3.14.2-debian-family-profile-name | 2026-08-19 | 将内部 `AptDistributionProfiles` 更名为 `DebianFamilyProfiles`，使 Profile 按发行版谱系命名、Renderer 按 APT/DNF 命令机制命名；不改变发行版规则、脚本或支持边界。 |
| 3.14.1-distro-extension-layout | 2026-08-19 | 将 `linux-sshd` 发行版准备按 contract、profile、script、execution 分包；APT 族与企业 Linux 族以数据化 Profile 表达差异，APT/DNF Renderer 只保留共用命令机械流程，不创建按单个发行版划分的深层包。脚本、版本矩阵、helper、协议、安全和运行支持边界保持不变。 |
| 3.14.0-package-structure-consolidation | 2026-08-19 | 将生产包收紧为 108 个，合并等价检查器/渲染器/发行版包装类，拆分多组件事务、恢复、生命周期、UI 表单与运行路径职责，加入五个 UI 窄端口及 JDK AST/依赖环门禁；28 个 Maven 模块、helper v3、持久化、协议、安全和既有实机证据边界不变。 |
| 3.13.4-simple-package-names | 2026-08-18 | 将首批职责包简化为 `display`、`startup`、`locking`、`config`、`preview`、`setup`、`support` 与 `command`，并同步对应类型名；Maven 模块、运行协议、持久化语义和真实环境支持结论保持不变。 |
| 3.13.3-package-structure-implementation | 2026-08-15 | 实施 `shared.analyze` 与 Linux 部署链的目标责任分包，完成 SPI/注册表、生态服务运行时和 helper 资源迁移；JDK 21 离线 28 模块验证通过。未新增真实 Linux 或产品入口验收结论。 |
| 3.13.2-linux-sshd-distro-preparation-layout | 2026-08-15 | 将 `linux-sshd.distro` 的具体发行版准备实现统一下沉到 `preparation/{apt,dnf}/<distro>`，使其与 SPI、Profile、注册表和公共 Shell 机制明确分离；APT/DNF 只表达包管理器机械流程，六个发行版继续保留独立规则。本次仍只修改文档，源码尚未迁移。 |
| 3.13.1-linux-deploy-distro-policy-layout | 2026-08-15 | 将 `deploy.compatibility.distro` 中的六个具体发行版兼容策略统一收进 `policy/<distro>`，使其与同级的 SPI、公共规则和注册表明确分离；同步补充 `HostCompatibility → registry`、`registry → policy/spi`、`policy → spi/rule` 的目标依赖方向。本次仍只修改文档，源码尚未迁移。 |
| 3.13.0-linux-deploy-ecosystem-layout | 2026-08-15 | 将 `shared/deploy`、`shared/linux`、`shared/linux-sshd` 的最终目标结构修订为部署形态、公共契约、SSHD transport/session、模块级技术生态、发行版族、运行机制和协议职责的正交分包；新增专项待迁移清单，并把删除 `Advanced*` 公共模型列为后置原子阶段。本次仅修改文档，源码、测试、POM、helper 协议、数据库、运行行为和既有验收结论均未改变。 |
| 3.12.0-analyze-ecosystem-layout | 2026-08-15 | 确认 `shared.analyze` 采用公共职责与技术生态两级组织：JVM 统一收录 Java、Kotlin、Maven、Gradle、Spring Boot 和普通 JAR，其他语言进入独立生态，容器、静态站点、源码、策略、注册和组件分析保持语言无关；本次仅修订目标结构并新增待迁移清单，不修改源码、POM、测试、运行能力或既有验收结论。 |
| 3.11.0-phase3-closeout | 2026-08-14 | 完成本轮三期代码与验证收尾；非 Ubuntu 发行版的产品入口夹具仍保留，但用户明确将其真实目标机执行延后为后续独立任务，未扩大运行支持声明。 |
| 3.10.0-remove-centos-bootstrap | 2026-08-14 | 删除仅用于一次性恢复测试环境 SELinux 的直接 root 远程引导测试；当前 CentOS 验收已由产品入口完成，后续产品测试不保留该旁路。 |
| 3.9.0-centos-stream-acceptance | 2026-08-14 | 精确 CentOS Stream 9 x86-64 夹具经产品入口完成两次准备、两组件发布、故障候选整应用回滚和生命周期；SELinux 与防火墙态保持验收前观测值。辅助协议统一使用受管 Java 21 运行时，且只读 SSH 采集仅重试短暂传输超时；其他发行版仍待独立实机验收。 |
| 3.8.0-centos-stream-recovery-blocked | 2026-08-14 | 仅测试的固定 CentOS SELinux 引导先使用产品已验证主机指纹且不暴露任意命令；准备脚本与分类器共同接受省略 `VARIANT_ID` 的 Stream 9/10，APT/DNF 失败保留无秘密阶段及标准错误/输出。Stream 9 已 Enforcing，但后续目标关闭 SSH 握手，完整验收仍待恢复。 |
| 3.7.0-centos-stream-safety-stop | 2026-08-14 | `SshdPlatformCapabilityCollector` 将版本 9/10 的 CentOS 正确识别为 Stream，即使镜像省略 `VARIANT_ID`；新增精确 CentOS 产品入口契约和只读能力探测。当前 Stream 9 因 SELinux Disabled 在准备前安全停止，未改变远端；其他发行版实机矩阵延后。 |
| 3.6.0-reviewed-ubuntu-acceptance | 2026-08-13 | 同步当前 helper v3 的 Ubuntu 24.04 x86-64 产品入口证据：统一 Spring Boot Reviewed 链路与 Podman Quadlet 已完成声明的验收；不改变模块、包、协议或其他发行版 `RUNTIME-PENDING` 边界。 |
| 3.5.0-phase3-ubuntu-fixture-regression | 2026-08-13 | 不改变模块、生产代码或依赖方向；已抽取的 `app/main` 两组件产品入口夹具在原授权 Ubuntu 24.04 x86-64 上经 `DesktopApplicationService` 与 SSHD 装配复跑通过（1/1，206.2 秒），再次覆盖发布、故障候选整应用回滚、SQLite v7 图重载、生命周期与自启切换。服务器未重装，应用保持运行且关闭自启动；新增发行版实机仍为 `RUNTIME-PENDING`。 |
| 3.4.0-phase3-distribution-harness | 2026-08-13 | 不改变 28-POM、生产模块或依赖方向；在既有 `app/main` 测试 `bootstrap` 职责内抽取两组件整应用事务夹具并加入显式非 Ubuntu 发行版产品入口验收，复用现有 `DesktopApplicationService` 与 SSHD 装配，不新增手工 SSH、任意 Shell 或测试专用生产 API。 |
| 3.3.0-phase3-acceptance | 2026-08-13 | 不改变 28-POM 或依赖方向；在既有 `linux-sshd/build` 中加入仅限官方域名和固定 SHA-256 的 Gradle 分发下载/受管缓存，在 `connection` 中加入 Windows NIO2 有界关闭排空。六种高级语言及两组件整应用已通过 Ubuntu 24.04 x86-64 产品入口验收，新增发行版因未重装唯一授权服务器而保持实机 `RUNTIME-PENDING`。 |
| 3.2.0-phase3-product-entry | 2026-08-13 | 在既有 `app/ui/deployment`、`app/service/deployment`、`shared/deploy` 与 `app/db` 职责内接入多组件桌面产品入口；新增聚焦的整应用图仓库和 SQLite v7 原子拓扑，桌面重启后可从组件依赖与既有健康契约恢复生命周期，而实际运行时类型和状态仍以目标机封存标记及实时观测为准。 |
| 3.1.0-phase3-distribution-matrix | 2026-08-13 | 在既有 `model/server`、`deploy/compatibility`、`linux-sshd/capability` 与 `linux-sshd/distro` 职责内加入软件包架构、累计 CPU、安全/防火墙事实，按发行版拆分支持策略与准备适配器；APT/DNF、受控 helper 与安全状态复核仅共享固定机械流程，不增加模块、CentOS 别名或任意 Shell 入口。 |
| 3.0.0-phase3-multi-model-core | 2026-08-13 | 在既有 `shared/ai`、`app/db`、`app/service` 和 `app/ui/ai` 职责内加入三个固定 AI 角色、最小脱敏上下文、严格结构化输出、调用证据、冲突裁决、命名 Provider 外键绑定与桌面配置入口；SQLite 升至 v6，API Key 仍只归平台秘密存储，失败不跨 Provider 回退，模型不获得执行授权。 |
| 2.9.0-phase3-multi-component-core | 2026-08-13 | 在既有 `model/analyze/deploy` 职责内加入稳定组件记录、目标机修改前冲突拦截、精确依赖图、独立候选、多组件短停机事务/整体健康/逐组件恢复，以及依赖安全的应用生命周期和部分运行/自启汇总；同时清除能力层残留的 helper v2 判断并由单一 v3 常量约束。JDK 21 全量离线门禁 28/28 通过，桌面产品入口和真实 Linux 验收仍待完成。 |
| 2.8.0-phase3-experimental-adapters | 2026-08-13 | 在既有职责包中接入 Go、Rust、.NET、Kotlin、PHP、Ruby 的锁文件分析、试验支持声明、适配器、固定构建渲染、helper v3 与 Ubuntu 24.04 工具链准备；逐次风险确认和实时版本探测保持安全边界，JDK 21 全量离线门禁 28/28 通过，真实目标机验收仍待完成。 |
| 2.7.0-phase3-support-preview | 2026-08-13 | 在既有 `shared/model`、`shared/analyze` 和 `app/ui` 边界内加入支持等级、精确真实验收范围和不可执行语言识别预览；覆盖三期全部候选语言，明确预览没有构建工具、归档、适配器、渲染器或 helper 入口。 |
| 2.6.0-spring-boot-reviewed-convergence | 2026-08-13 | 将 Maven/Gradle Spring Boot 合并为 `SPRING_BOOT` 并由构建工具区分固定入口；删除旧分析、源码和部署 API，接入 helper v2、发布身份 v2 与 SQLite v5。迁移前 Ubuntu 实机证据保留为历史记录，统一后的 Reviewed 链路标记 `RUNTIME-PENDING`。 |
| 2.5.1-test-fixture-layout | 2026-08-13 | 将根目录 23 个 Java、Maven、Spring Boot 独立验收夹具统一归入 `test/java/maven/spring-boot`，明确后续按语言、构建工具、框架或功能扩展；不修改夹具内容、生产模块或 Maven reactor。 |
| 2.5.0-reviewed-runtime-acceptance | 2026-08-12 | 完成从本地或 Git 选定源码、语言与运行事实分析、确定性计划、不可变配置/秘密输入、目标机构建、发布、健康、观测、生命周期和失败恢复的职责闭环；Ubuntu 24.04 x86-64 已由产品入口完成六类项目实机验收，其余主机矩阵保持 `RUNTIME-PENDING`。 |
| 2.4.0-responsibility-boundaries | 2026-08-12 | 将源码分析、桌面页面、SQLite 仓库、Git 快照、六类远程构建、systemd 和高权限 helper 按稳定职责拆分；补齐 Java、Node.js/JavaScript/TypeScript、Python 基础语言事实及 UI 映射，固定静态站点 Node 版本约束和 helper 拼装哈希；不修改 SQLite schema、远程协议、安全或凭据边界。 |
| 2.3.4-reviewed-source-inference | 2026-08-12 | 补齐本地与 Git 源码到同一经审阅归档/来源身份的服务路径；新增有证据、可人工复核的 Java、Node、Python、静态站点和容器运行时建议，移除桌面表单中的语言、入口、产物、端口和配置硬编码；桌面可录入不可变秘密修订并在发布请求中传递显式引用。未连接真实目标机。 |
| 2.3.3-desktop-typed-workflow | 2026-08-12 | 同步桌面部署页的六类项目选择、类型化运行时和配置、计划审阅、类型化 AI 脱敏事实以及表单状态保留边界；不改变模块结构、SQLite schema、凭据归属或真实目标机验收状态。 |
| 2.3.2-local-execution-contracts | 2026-08-12 | 同步二期六类项目的受控构建、发布、快照、回滚、健康、生命周期和发行版固定环境准备实现；维持分期只属于文档、不得泄漏到 `src/` API 或资源的命名规则，真实目标机验收仍待执行。 |
| 2.3.1-unified-naming | 2026-08-12 | 将正式源码、测试和资源从期数命名重构为职责命名；新增规则：分期仅属于开发文档，不得进入 `src/` 的 API、资源或持久化契约。 |
| 2.3.0-phase2-local-implementation | 2026-08-12 | 同步二期已实现的 Git、配置、分析、部署计划、Linux/容器和桌面服务包；明确所有二期真实运行环境验收仍待执行。 |
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
