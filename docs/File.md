# WindowsToLinux 源码模块与包结构

> **阅读入口：** [当前源码树](#current-source-tree) · [命名与归包](#package-rules) · [Mod 源码规范](#mod-source-layout) · [依赖方向](#module-dependencies) · [结构检查](#structure-checks) · [规划边界](#planned-boundaries)。

## 文档信息

本文是当前源码模块、包、命名、依赖和资源归属的结构规范。APP、appapi 与官方 deployment 的现行结构按实际源码核对，最新已确认规则优先于早期改造模板；历史迁移和验收见开发文档。目录或类型存在、编译及静态检查通过不代表真实 Linux、模型提供方或完整业务验收完成。

- 当前活动根聚合 `src/appapi`、`src/app`、`src/mod`，共有 13 个 Maven 叶子模块；平台装配在 `app/main`，Mod 公开合同在 `appapi`，部署业务在 `mod/deployment`。
- APP 与平台自有 API 使用本文命名和分包规则；官方 deployment 采用已确认类名词表，第三方 Mod 的内部命名与组织由作者决定。
- 本文只维护结构；[AllFile.md](AllFile.md)索引实际生产文件，[Mod.md](Mod.md)维护作者与加载合同，[Runtime.md](Runtime.md)维护运行行为、数据与安全，[APP.md](APP.md)维护发布布局和构建交付；[Config.md](Config.md) 是配置字段和填写方式的唯一集中说明。
- 更新日期：2026-10-03。现行规则与本机交付见 [1.2 开发文档](development/PHASE-1.2.md#app-structure-rules)，历史改造与后置任务见 [1.1 开发文档](development/PHASE-1.1.md)。

## 1. 当前源码结构

### 1.1 节点标记与阅读方式

树中省略叶子模块重复的 `src/main/java/gold/debug/windowstolinux/...` 路径，直接展示模块根包的真实子包；通用物理布局见第 4.1 节。根和聚合 POM 固定置顶，包按英文名称排序。当前树只列实际结构，后置规划见第 7 节，不为规划创建空目录。

<a id="phase-1-1-reading"></a>

| 标记 | 含义与阅读边界 |
| --- | --- |
| `[目录]` | 文件系统分组，不自动产生 Maven 模块或 Java 包。 |
| `[聚合]` | 有 POM 的 Maven 聚合模块，组织子模块构建，不承载业务 Java 实现。 |
| `[模块]` | 有自身 POM 的 Java 叶子模块，不等于可单独安装的 Mod。 |
| `[包]` | 所在模块内部的 Java 职责包，没有独立 POM。 |
| `[公开包]` | appapi 或 Mod 实际导出的契约包，不混入私有实现。 |
| `[构建文件]` | Maven 模块、依赖或构建声明。 |
| `[入口类]` | Mod 主模块根包内的 `Mod.java`，负责装配和生命周期。 |
| `[资源]` | Maven 资源目录或所属 JAR 的资源，不是 Java 包。 |
| `[文档]`、`[文件组]` | 说明文件或示例中一起列出的说明、记录与配置文件。 |
| `[归档]` | 冻结历史副本，不参与活动构建或生产发布。 |

每行使用 `# [节点类型] 中文职责`，同一树内的 `#` 和职责文字分别对齐；以空格按显示宽度补齐，不使用制表符。

<a id="phase-1-1-target"></a>
<a id="phase-1-1-tree"></a>
<a id="current-source-tree"></a>

```text
WindowsToLinux/                       # [聚合]     工程构建入口，统一组织平台与官方 Mod
├─ pom.xml                            # [构建文件] Maven 模块与依赖声明
├─ docs/                              # [目录]     现行结构、API、产品与开发文档
├─ history-src/                       # [归档]     冻结历史源码，仅作参考，不参与当前构建
└─ src/                               # [目录]     当前活动 Maven 模块与源码
   ├─ app/                            # [聚合]     平台实现与桌面宿主的模块分组
   │  ├─ pom.xml                      # [构建文件] Maven 模块与依赖声明
   │  ├─ ai/                          # [模块]     模型、图像与音频的协议和消息处理
   │  │  ├─ pom.xml                   # [构建文件] Maven 模块与依赖声明
   │  │  ├─ http/                     # [包]       共用 HTTP 地址校验与有界响应读取
   │  │  ├─ model/                    # [包]       模型用途、能力状态与通用消息编解码
   │  │  ├─ vision/                   # [包]       图像校验、消息编码与图像能力验证样本
   │  │  └─ voice/                    # [包]       音频 HTTP 转写、实时会话与转写结果组装
   │  ├─ config/                      # [模块]     独立 YAML 加载与不可变启动配置
   │  │  └─ pom.xml                   # [构建文件] Maven 模块与依赖声明
   │  ├─ db/                          # [模块]     平台 H2 数据存取与存储能力装配
   │  │  ├─ pom.xml                   # [构建文件] Maven 模块与依赖声明
   │  │  ├─ ai/                       # [包]       模型配置、用途选择与能力验证记录
   │  │  ├─ connect/                  # [包]       H2 连接、事务和数据库生命周期
   │  │  ├─ error/                    # [包]       数据库失败的结构化原因映射
   │  │  ├─ file/                     # [包]       文件操作、传输、链接与恢复依据的存取
   │  │  ├─ init/                     # [包]       平台数据库初始化、保护与重开校验
   │  │  ├─ log/                      # [包]       日志记录、保留与应急日志编码
   │  │  ├─ mod/                      # [包]       Mod 安装、版本、重启和 Schema 核对记录
   │  │  ├─ schema/                   # [包]       Schema 命名空间、受限访问与结构检查
   │  │  ├─ security/                 # [包]       主密码设置、秘密载荷与秘密引用登记
   │  │  ├─ server/                   # [包]       服务器配置、可信身份与业务引用存取
   │  │  ├─ setting/                  # [包]       平台界面、安装及 Mod 导航偏好存取
   │  │  ├─ sshrecover/               # [包]       SSH 救援会话和原任务恢复依据
   │  │  └─ task/                     # [包]       任务账本、资源依赖、归档及清理状态
   │  ├─ file/                        # [模块]     平台文件引擎、传输计划与本机后端
   │  │  ├─ pom.xml                   # [构建文件] Maven 模块与依赖声明
   │  │  ├─ model/                    # [包]       文件后端、操作日志、链接维护及回收回执的内部合同
   │  │  └─ source/                   # [包]       已选来源目录、工作目录与冻结来源
   │  ├─ linux/                       # [模块]     平台 SSH、SFTP 与实际远程执行能力
   │  │  ├─ pom.xml                   # [构建文件] Maven 模块与依赖声明
   │  │  ├─ sftp/                     # [包]       复用受管 SSH 会话的远端文件协议与传输
   │  │  └─ ssh/                      # [包]       SSH 会话、命令执行、信任与救援
   │  │     ├─ connect/               # [包]       SSH 连接、主机信任与认证
   │  │     └─ recover/               # [包]       SSH 救援状态、会话与原任务恢复编排
   │  │        ├─ ai/                 # [包]       救援专用模型调用、提示及响应协议
   │  │        ├─ browser/            # [包]       救援浏览器、终端会话与所属进程控制
   │  │        └─ model/              # [包]       救援目标、动作、探测与终端观测的共享值
   │  ├─ main/                        # [模块]     平台入口、能力装配与运行生命周期
   │  │  ├─ pom.xml                   # [构建文件] Maven 模块与依赖声明
   │  │  ├─ error/                    # [包]       平台失败的安全证据和实时日志绑定
   │  │  ├─ mod/                      # [包]       Mod 宿主上下文、生命周期与 Schema 检查装配
   │  │  ├─ runtime/                  # [包]       开发与安装运行模式、数据根和运行路径解析
   │  │  └─ start/                    # [包]       启动、数据库保护、主窗口与平台能力总装
   │  ├─ mod/                         # [模块]     Mod 配置、发现、安装、加载与运行基础
   │  │  ├─ pom.xml                   # [构建文件] Maven 模块与依赖声明
   │  │  ├─ config/                   # [包]       Mod 描述读取、版本约束与配置值
   │  │  ├─ dev/                      # [包]       开发源码发现、Maven 输入与开发装载
   │  │  ├─ install/                  # [包]       Mod 包读取、检查、打包与安装素材处理
   │  │  ├─ load/                     # [包]       类加载隔离、入口和公开导出的检查
   │  │  ├─ process/                  # [包]       Mod 所属辅助进程的固定通信协议
   │  │  ├─ run/                      # [包]       Mod 依赖计划、运行会话、启停与资源归属
   │  │  ├─ service/                  # [包]       跨 Mod 服务值的受控复制与传递
   │  │  └─ version/                  # [包]       Mod 版本解析和兼容范围判断
   │  ├─ secret/                      # [模块]     平台秘密存取、主密码保护与秘密生命周期
   │  │  └─ pom.xml                   # [构建文件] Maven 模块与依赖声明
   │  ├─ service/                     # [模块]     将公开能力绑定到原任务、授权与资源的服务
   │  │  ├─ pom.xml                   # [构建文件] Maven 模块与依赖声明
   │  │  ├─ ai/                       # [包]       模型配置访问、用途路由与受管 AI 调用
   │  │  ├─ db/                       # [包]       任务所属 Mod 的受限数据库访问
   │  │  ├─ error/                    # [包]       平台服务的结构化失败与安全诊断
   │  │  ├─ file/                     # [包]       文件选择、受管执行、传输与恢复服务
   │  │  │  └─ check/                 # [包]       文件审批交互、删除审阅与后端准入
   │  │  ├─ linux/                    # [包]       任务所属远程会话与命令执行服务
   │  │  ├─ log/                      # [包]       所属日志访问、维护与应急记录回填
   │  │  ├─ mod/                      # [包]       Mod 管理入口、服务公开与任务所属能力
   │  │  │  ├─ install/               # [包]       Mod 安装、删除、重装及所属资源清理
   │  │  │  └─ store/                 # [包]       Mod 商店查询、获取及验证的适配
   │  │  ├─ process/                  # [包]       任务所属辅助进程的受管访问
   │  │  ├─ secret/                   # [包]       秘密标识、业务引用与原任务授权绑定
   │  │  ├─ security/                 # [包]       解锁、主密码、加密设置与安全会话
   │  │  ├─ server/                   # [包]       服务器配置编辑、选择、标识与业务引用
   │  │  ├─ setting/                  # [包]       文件、传输与导航偏好的读写服务
   │  │  └─ task/                     # [包]       任务准入、授权、执行、取消与资源收尾
   │  ├─ ui/                          # [模块]     平台 Swing 界面与公共控件
   │  │  ├─ pom.xml                   # [构建文件] Maven 模块与依赖声明
   │  │  ├─ ai/                       # [包]       模型清单、用途选择与能力状态展示
   │  │  ├─ control/                  # [包]       主题、布局、卡片与通用 Swing 控件
   │  │  ├─ debug/                    # [包]       受管 CLASS 模式的界面调试预览
   │  │  ├─ display/                  # [包]       语言、主题和显示设置
   │  │  ├─ error/                    # [包]       结构化失败的本地化显示
   │  │  ├─ language/                 # [包]       界面文案目录与语言选择
   │  │  ├─ mod/                      # [包]       Mod 管理、页面挂载和页面生命周期
   │  │  ├─ server/                   # [包]       服务器配置、选择、编辑与 SSH 救援界面
   │  │  ├─ setting/                  # [包]       平台文件等设置界面
   │  │  ├─ task/                     # [包]       通用任务启动、授权、后台执行和恢复界面
   │  │  └─ window/                   # [包]       主窗口、导航、标题和设置分类
   │  └─ windows/                     # [模块]     Windows 原生文件、安全、窗口与安装维护
   │     ├─ pom.xml                   # [构建文件] Maven 模块与依赖声明
   │     ├─ file/                     # [包]       Windows 文件身份、链接、回收和发布
   │     ├─ security/                 # [包]       当前用户与 Windows 原生数据保护
   │     ├─ uninstall/                # [包]       Windows 卸载审阅、准备、交接与执行
   │     │  └─ model/                 # [包]       卸载决策、状态、事件及结构化失败合同
   │     ├─ update/                   # [包]       Windows 更新准备、交接与执行
   │     │  ├─ check/                 # [包]       发布清单、信任策略与更新包验证
   │     │  └─ model/                 # [包]       更新状态、事件、版本及结构化失败合同
   │     ├─ window/                   # [包]       Windows 窗口状态与进程显示集成
   │     └─ handoff/                  # [包]       更新与卸载共用的认证交接合同
   ├─ appapi/                         # [模块]     供 Mod 使用的平台公开 Java 合同
   │  ├─ pom.xml                      # [构建文件] Maven 模块与依赖声明
   │  ├─ ai/                          # [公开包]   共享模型调用与配置修订查询入口
   │  │  ├─ model/                    # [公开包]   通用模型用途、消息、请求、响应与用量
   │  │  ├─ vision/                   # [公开包]   视觉请求使用的不可变图像输入
   │  │  └─ voice/                    # [公开包]   音频输入、转写结果与实时会话合同
   │  ├─ db/                          # [公开包]   任务限定的日常受管数据库访问入口
   │  │  └─ schema/                   # [公开包]   Schema 身份、初始化、校验与迁移合同
   │  ├─ file/                        # [公开包]   任务限定的通用文件能力入口
   │  │  ├─ action/                   # [公开包]   文件审批意图、操作策略、记录与结果
   │  │  ├─ model/                    # [公开包]   带主机身份的文件位置与对象观测
   │  │  ├─ query/                    # [公开包]   任务绑定的文件观测与有界读取
   │  │  ├─ snapshot/                 # [公开包]   不可变来源快照的请求与资源持有合同
   │  │  ├─ source/                   # [公开包]   已授权来源的选择、列举与有界读取
   │  │  └─ work/                     # [公开包]   可写暂存工作区及实际关闭合同
   │  ├─ linux/                       # [公开包]   任务授权的远程连接与未完成传输查询入口
   │  │  ├─ action/                   # [公开包]   远程命令、传输与恢复的精确审批意图
   │  │  ├─ command/                  # [公开包]   精确远程命令请求与已观察执行结果
   │  │  ├─ session/                  # [公开包]   受管 SSH 会话及已认证主机观测
   │  │  └─ transfer/                 # [公开包]   传输请求、进度、冲突、停滞与恢复依据
   │  ├─ mod/                         # [公开包]   Mod 获取宿主能力及宿主调用启停的双向入口
   │  │  ├─ lifecycle/                # [公开包]   启动模式、停止上下文与停止原因
   │  │  ├─ model/                    # [公开包]   Mod 稳定身份
   │  │  └─ service/                  # [公开包]   跨 Mod 服务的注册、发现与受管调用
   │  ├─ secret/                      # [公开包]   秘密创建、业务引用与授权短期读取入口
   │  │  ├─ access/                   # [公开包]   短期秘密字节访问回调
   │  │  ├─ model/                    # [公开包]   绑定精确用途的不透明秘密标识
   │  │  └─ ref/                      # [公开包]   保护共享凭据的持久业务引用
   │  ├─ server/                      # [公开包]   服务器查询与业务引用保护入口
   │  │  ├─ model/                    # [公开包]   服务器标识与不含秘密的配置观测
   │  │  └─ ref/                      # [公开包]   服务器专属业务引用
   │  ├─ task/                        # [公开包]   任务准入、查询与恢复的服务入口
   │  │  ├─ action/                   # [公开包]   精确动作意图、审批许可、权限模式与输入交互
   │  │  ├─ log/                      # [公开包]   所属日志登记、事件及实际写入结果
   │  │  ├─ model/                    # [公开包]   任务准入请求、进度状态与不可变快照
   │  │  ├─ process/                  # [公开包]   受管进程启动、协议消息与实际退出合同
   │  │  ├─ query/                    # [公开包]   共用查询分页结果与继续游标
   │  │  ├─ ref/                      # [公开包]   业务提交与核对共用的资源引用状态
   │  │  ├─ recover/                  # [公开包]   原任务核对回调、恢复注册与有界检查点
   │  │  ├─ result/                   # [公开包]   业务结果、失败原因与公开异常合同
   │  │  └─ run/                      # [公开包]   任务执行上下文、取消、结果观察与资源持有
   │  └─ ui/                          # [公开包]   页面登记、打开与环境查询入口
   │     └─ page/                     # [公开包]   页面创建、登记句柄、描述、环境更新与关闭合同
   └─ mod/                            # [聚合]     官方 Mod 的 Maven 模块分组
      ├─ pom.xml                      # [构建文件] Maven 模块与依赖声明
      └─ deployment/                  # [模块]     官方部署 Mod：分析、部署与应用管理
         ├─ pom.xml                   # [构建文件] Maven 模块与依赖声明
         ├─ Mod.java                  # [入口类]   根包装配、公开服务注册和 Mod 生命周期
         ├─ ai/                       # [包]       部署 AI 提示、提案解析与可执行动作约束
         ├─ api/                      # [公开包]   向其他 Mod 公开的应用与部署管理合同
         ├─ db/                       # [包]       部署私有 Schema、业务记录与发布恢复依据
         ├─ file/                     # [包]       部署素材筛选、封存、归档与来源核对
         │  └─ git/                   # [包]       通过平台能力准备和冻结 Git 来源
         ├─ linux/                    # [包]       通过平台远程会话执行固定部署协议
         ├─ secret/                   # [包]       部署秘密修订、引用与发布绑定
         ├─ service/                  # [包]       部署分析、审阅、执行与管理流程
         │  ├─ agent/                 # [包]       自主模式的提案、工具调用与执行门禁
         │  ├─ core/                  # [包]       各部署模式共用的流程和纯值
         │  │  ├─ config/             # [包]       部署配置值、作用范围与不可变配置快照
         │  │  ├─ delivery/           # [包]       各模式共用的部署交付与固定内容
         │  │  ├─ execution/          # [包]       原任务来源采集、管理操作与恢复流程
         │  │  └─ model/              # [包]       跨部署模式共用的纯数据类型
         │  │     ├─ analysis/        # [包]       静态分析事实与来源信息
         │  │     ├─ assessment/      # [包]       项目报告、问题与分析结论
         │  │     ├─ capability/      # [包]       主机能力、支持等级与匹配结果
         │  │     ├─ ecosystem/       # [包]       语言、构建和运行生态的类型信息
         │  │     │  └─ db/           # [包]       原生数据库实例、软件包与状态事实
         │  │     ├─ language/        # [包]       源码语言类别与观察事实
         │  │     ├─ message/         # [包]       部署文案与结构化消息
         │  │     ├─ project/         # [包]       项目分类、声明与部署单元信息
         │  │     │  └─ component/    # [包]       多组件的声明、依赖、资源和隔离要求
         │  │     ├─ server/          # [包]       目标服务器与主机能力数据
         │  │     │  └─ security/     # [包]       服务器安全机制与受限能力信息
         │  │     └─ toolchain/       # [包]       工具链版本、绑定与准备数据
         │  └─ standard/              # [包]       确定性分析、辅助建议与标准部署流程
         │     ├─ analyze/            # [包]       不执行构建的源码和项目分析
         │     │  ├─ component/       # [包]       多组件发现、归属与依赖检查
         │     │  ├─ contract/        # [包]       静态分析的输入与规则合同
         │     │  │  ├─ policy/       # [包]       来源与组件的分析准入规则
         │     │  │  └─ spi/          # [包]       按项目类型扩展静态检查器的合同
         │     │  ├─ core/            # [包]       跨语言分析顺序、规则应用与结果汇总
         │     │  ├─ ecosystem/       # [包]       源码语言识别及其构建架构分析
         │     │  │  ├─ c/            # [包]       C/C++ 语言识别与构建架构分派
         │     │  │  │  └─ cmake/     # [包]       静态检查 CMake preset 与目标，不执行构建
         │     │  │  ├─ db/           # [包]       读取数据库声明与服务端点，不保留凭据
         │     │  │  ├─ dotnet/       # [包]       .NET/C# 语言识别与构建架构分派
         │     │  │  │  └─ dotnetsdk/ # [包]       静态检查 .NET Web SDK，不执行构建
         │     │  │  ├─ go/           # [包]       Go 语言识别与构建架构分派
         │     │  │  │  └─ gomodule/  # [包]       静态检查 Go Module，不执行构建
         │     │  │  ├─ java/         # [包]       Java 语言识别与构建架构分派
         │     │  │  │  ├─ gradle/    # [包]       静态检查 Gradle 构建脚本与 Wrapper，不执行构建
         │     │  │  │  ├─ jar/       # [包]       静态检查 既有 JAR 与清单，不执行构建
         │     │  │  │  ├─ jdk/       # [包]       静态检查 JDK 源码入口，不执行构建
         │     │  │  │  └─ maven/     # [包]       静态检查 Maven POM 与构建入口，不执行构建
         │     │  │  ├─ kotlin/       # [包]       Kotlin 语言识别与构建架构分派
         │     │  │  │  ├─ gradle/    # [包]       静态检查 Gradle 构建脚本与 Wrapper，不执行构建
         │     │  │  │  └─ kotlinc/   # [包]       静态检查 Kotlin 编译器声明，不执行构建
         │     │  │  ├─ node/         # [包]       Node.js/JavaScript/TypeScript 语言识别与构建架构分派
         │     │  │  │  ├─ npm/       # [包]       静态检查 npm 锁文件，不执行构建
         │     │  │  │  ├─ pnpm/      # [包]       静态检查 pnpm 锁文件，不执行构建
         │     │  │  │  └─ yarn/      # [包]       静态检查 Yarn 锁文件，不执行构建
         │     │  │  ├─ php/          # [包]       PHP 语言识别与构建架构分派
         │     │  │  │  ├─ composer/  # [包]       静态检查 Composer 锁文件，不执行构建
         │     │  │  │  └─ phpcli/    # [包]       静态检查 PHP CLI 入口，不执行构建
         │     │  │  ├─ python/       # [包]       Python 语言识别与构建架构分派
         │     │  │  │  ├─ pip/       # [包]       静态检查 pip 依赖锁定，不执行构建
         │     │  │  │  ├─ pipenv/    # [包]       静态检查 Pipenv 锁文件，不执行构建
         │     │  │  │  ├─ poetry/    # [包]       静态检查 Poetry 锁文件，不执行构建
         │     │  │  │  └─ uv/        # [包]       静态检查 uv 锁文件，不执行构建
         │     │  │  ├─ ruby/         # [包]       Ruby 语言识别与构建架构分派
         │     │  │  │  ├─ bundler/   # [包]       静态检查 Bundler 锁文件，不执行构建
         │     │  │  │  └─ rubycli/   # [包]       静态检查 Ruby CLI 入口，不执行构建
         │     │  │  └─ rust/         # [包]       Rust 语言识别与构建架构分派
         │     │  │     └─ cargo/     # [包]       静态检查 Cargo 锁文件与构建声明，不执行构建
         │     │  ├─ extension/       # [包]       静态分析实现的装配与选择
         │     │  │  └─ registry/     # [包]       按项目类型注册和选择检查实现
         │     │  ├─ preview/         # [包]       不能直接进入部署的只读识别预览
         │     │  ├─ service/         # [包]       语言无关的服务元数据与报告组装
         │     │  ├─ source/          # [包]       源码树安全遍历与有界元数据读取
         │     │  ├─ toolchain/       # [包]       读取显式工具链声明，不执行构建
         │     │  └─ workload/        # [包]       容器和静态站点的确定性工作负载识别
         │     └─ deploy/             # [包]       标准部署的输入、计划、构建与发布
         │        ├─ assistance/      # [包]       辅助模式建议、审阅与有界执行
         │        ├─ build/           # [包]       类型化构建描述与构建脚本生成
         │        │  ├─ contract/     # [包]       构建输入与生成内容的合同
         │        │  │  └─ spi/       # [包]       按构建架构扩展文本生成实现的合同
         │        │  ├─ ecosystem/    # [包]       按语言和工具生成构建脚本
         │        │  │  ├─ java/      # [包]       生成 Java 的受控构建脚本
         │        │  │  ├─ kotlin/    # [包]       生成 Kotlin 的受控构建脚本
         │        │  │  ├─ node/      # [包]       生成 Node.js/JavaScript/TypeScript 的受控构建脚本
         │        │  │  ├─ php/       # [包]       生成 PHP 的受控构建脚本
         │        │  │  ├─ python/    # [包]       生成 Python 的受控构建脚本
         │        │  │  └─ ruby/      # [包]       生成 Ruby 的受控构建脚本
         │        │  ├─ extension/    # [包]       构建文本生成实现的装配与选择
         │        │  │  └─ registry/  # [包]       注册并选择构建脚本生成实现
         │        │  ├─ generation/   # [包]       共用构建脚本与固定协议内容
         │        │  │  └─ script/    # [包]       构建脚本片段与受控外壳
         │        │  └─ workload/     # [包]       容器和静态站点的构建内容生成
         │        ├─ contract/        # [包]       部署计划、输入与执行的扩展合同
         │        │  └─ spi/          # [包]       受控部署实现的可替换合同
         │        ├─ database/        # [包]       原生数据库选择、审阅与准备协议
         │        ├─ extension/       # [包]       部署能力的适配、注册与选择
         │        │  ├─ adapter/      # [包]       将部署类型接入既有受控能力
         │        │  └─ registry/     # [包]       按部署类型注册和选择适配实现
         │        ├─ input/           # [包]       部署声明、普通配置与秘密输入的解析和校验
         │        ├─ plan/            # [包]       已审阅计划、主机绑定与计划身份
         │        ├─ publication/     # [包]       发布事务、原效果核对、收尾与恢复
         │        ├─ support/         # [包]       根据主机事实判断部署支持程度
         │        │  ├─ distro/       # [包]       按 Linux 发行版应用支持规则
         │        │  └─ runtime/      # [包]       匹配运行时需求与已采集主机能力
         │        └─ toolchain/       # [包]       已审阅工具链的准备与精确绑定
         └─ ui/                       # [包]       部署审阅、自主模式、任务与应用管理界面
```

### 1.2 独立验收夹具

根目录 `test` 保存不参与 WindowsToLinux Maven reactor 的独立验收夹具，分为 `single-language` 和 `multi-language`。单语言场景以 `success-` 或 `failure-` 标明预期部署结果，后接功能名称；多语言每种组合只保留一个完整成功样例。完整场景及用途见 [测试夹具说明](../test/README.md)。

125 组源码项目均通过入口、配置、请求处理、业务和模型协作。C/C++ 的 `include` 放自定义头文件，`src` 的多个编译单元链接成一个目标；其余语言采用自身包/模块机制。包管理器样例的运行依赖和真实锁文件放在各自项目内。`src/app/main/src/test/python` 保存跨语言 HTTP、破坏输入和原生组件检查脚本；逐组合证据见 [验证记录](../test/single-language/VERIFICATION.md)。

```text
test/                                                                                # [目录]   独立验收夹具，不参与 Maven reactor
├─ README.md                                                                         # [文档]   场景组织、使用方法与验证边界
├─ single-language/                                                                  # [目录]   25 个组合、125 个预期成功或失败场景
│  ├─ README.md / VERIFICATION.md / matrix.json                                      # [文件组] 说明、验证记录与场景矩阵
│  └─ <language>/<build-tool>/<framework-or-function>/<expected-result>-<function>/  # [目录]   按实际语言、构建和预期结果组织
└─ multi-language/                                                                   # [目录]   8 个成功项目，覆盖 12 种源码语言
   ├─ README.md / VERIFICATION.md / matrix.json                                      # [文件组] 说明、验证记录与场景矩阵
   └─ success-<function>/                                                            # [目录]   各项目的源码、依赖锁、样本及运行说明
```

多语言项目包括五个网页和三个控制台工具。Java、Go、C#、PHP、Kotlin、Python 分别拥有项目内 SQLite，Rust/C/C++ 工具通过子进程 JSON 协议协作；不引入数据库服务。`src/app/main/src/test/python/polyglot_runner.py` 在隔离副本构建、分配回环端口并执行验收，`src/app/main/src/test/browser` 独立管理与仓库同版本的 Playwright 依赖。源码覆盖由 `MultiLanguageFixtureMatrixTest` 门禁检查；本地行为与 Linux 产品部署证据分开维护，见 [多语言验证记录](../test/multi-language/VERIFICATION.md)。

## 2. 命名规范

第 2 节的内部命名词表及类型偏好适用于 APP 与平台自有 API；Mod 的根包、第一层职责、入口与公开导出遵守第 4.4 节，其内部词语、层级及自有类型由作者决定。平台公开 API 优先选用常见、简短而职责明确的词，保留 File / Link、任务与结果、授权请求与许可等必要区分；Java 关键字、字面量及受限标识符由 JDK 21 编译验证，不设置机械字符上限。 本次官方 deployment 的自有类型按同一词表整理，属于该 Mod 的命名选择，不扩大第三方 Mod 的内部命名门禁。

命名清单按功能分组；同一功能组内的英文标准名称按不区分大小写的字母顺序排列。阶段流、规则执行顺序和带编号规范保留语义顺序。

### 2.1 总体与模块命名

| 编号 | 规范                                                     |
| ---- | -------------------------------------------------------- |
| N-01 | 模块表达依赖、技术、运行或安全边界。                     |
| N-02 | 包表达模块内部的功能和职责。                             |
| N-03 | 类名使用对象＋职责；数据类型使用对象＋用途。                           |
| N-04 | 命名必须使用准确、稳定、唯一的英文含义。                 |
| N-05 | 同一个单词在不同模块中必须保持相同语义。                 |
| N-06 | 正式生产名称使用英文；简体中文仅用于 UI 映射和双语注释。 |
| N-07 | 不得使用期数、临时状态或开发阶段作为生产名称。           |
| N-08 | 不得为了目录整齐而复制逻辑或建立空分类。                 |

| 编号 | 模块命名规范                                               | 正确示例                      | 禁止示例                 |
| ---- | ---------------------------------------------------------- | ----------------------------- | ------------------------ |
| M-01 | 模块名使用小写英文。                                       | `deploy`                      | `Deploy`                 |
| M-02 | 模块名表达稳定功能，采用准确的常见名词或动词。             | `file`、`mod`                  | `tools`                  |
| M-03 | 模块名表达稳定能力边界。                                   | `config`、`secret`            | `tools`、`functions`     |
| M-04 | 模块名不得包含实现阶段。                                   | `deploy`                      | `phase3-deploy`          |
| M-05 | 模块名不得包含临时状态。                                   | `analyze`                     | `new-analyze`            |
| M-06 | 模块名不得表达普通内部分类。                               | `linux`                       | `linux-renderers`        |
| M-07 | 模块仅因已确认的独立职责调整；名称整理本身不构成拆分理由。 | `mod/deployment` 保持完整模块 | 将 `policy` 拆成独立模块 |
| M-08 | 平台实现模块使用明确技术限定词。                           | `linux-sshd`                  | `linux-impl`             |

<a id="package-rules"></a>

### 2.2 包结构与功能归包

| 范围 | 现行组织方式 |
| --- | --- |
| `src/app` | 模块内先确定实际功能与所有权，默认只保留模块下面第一层必要功能包；接口、专属数据与实现就近归包。模块本身已准确表达单一职责且不需分类时，具体实现可以直接位于模块 Java 根包。 |
| `src/appapi` | 保留 9 个一级能力包，在其下按实际职责使用已登记的二级包；根包只保留主要入口，具体合同归二级职责包；当前共 41 个公开包，不新增三级包。 |
| `src/mod` | 第一层职责包与相应平台模块同名，按需使用 `ai`、`db`、`file`、`linux`、`secret`、`service`、`ui`、`windows`；`api` 是 Mod 专属公开包，根包入口是 `Mod.java`。第一层之后由作者自主组织，APP 的词表、父子包模板、层数与内部命名门禁均不适用，也不提供同类推荐词表。 |

APP 不强制套用 `contract`、`generation`、`extension`、`execution`、`persistence` 中间层。只有实际独立功能、所有权、访问隔离或依赖边界需要时才增加子包；默认一层不是禁止子包，类数达到 6～8 个时检查职责；只有 1～2 个类型时也核对是否适合合并。数量只触发检查，不决定合并或拆分，不凑数，不把无关类型塞进同一包；职责独立的小包保留。包合并前核对包内可见成员、重名、资源定位与引用，不能通过扩大可见性代替职责整理。

appapi 的数量也只触发职责检查，不设置每包类型数门禁；`query`、`work`、`vision` 等独立单类包可以保留。各 `model` 包只保存对应功能的值，不能承接无关合同；任务失败与结果统一归 `task/result`，不另设 `task/error`。公开包须精确登记，未登记包及三级包由架构检查拒绝。

APP 包名使用小写英文，采用常见、准确的名词或动词；`install`、`start`、`init`、`load`、`recover`、`error`、`file`、`db`、`language`、`http`、`check`、`rule`、`model`、`vision`、`voice`、`dev`、`run`、`config`、`connect`、`control`、`window`、`source`、`browser`、`setting`、`store` 为已确认用词。`schema`、`crypto` 等准确专业词保留。词表不是目录模板，没有实际内容时不建空包。禁止 `util`、`common`、`misc`、`impl` 等无明确归属的 APP 杂物包。

| APP 功能 | 当前归属与边界 |
| --- | --- |
| 文件基础 | `app/file` 根包保存引擎、传输计划和本机后端；`file/model` 仅承载后端、操作日志、链接维护及回收回执的内部合同，`file/source` 保存已选及冻结来源。文件公开合同在 `appapi/file`；任务和受管执行归 `service/file`，审批及后端准入归 `service/file/check`。 |
| 模型调用 | `app/ai/model` 保存模型能力、用途、常规文字调用及共用消息协议；`vision` 保存图片校验、视觉消息编码和能力挑战；`voice` 保存音频转写与实时会话；`http` 保存共用端点校验和有界响应读取。SSH 救援专用模型和协议归 `app/linux/ssh/recover/ai`，共享动作及目标值归 `recover/model`，通过启动装配注入已授权通用模型调用。 |
| 数据库 | `app/db/connect` 保存连接与事务基础，`schema` 保存命名空间和访问隔离；记录与读写按 `ai`、`server`、`security`、`setting`、`sshrecover`、`task`、`file` 等实际功能归包。 |
| 平台服务 | 模型配置与通用调用适配归 `app/service/ai`；其它接口与专属数据分别归 `server`、`file`、`security`、`task` 等功能；文件、传输与导航偏好服务归 `setting`；通用服务错误归 `error`。完整 SSH 救援归 `app/linux/ssh/recover`，Service 不另建救援包。 |
| SSH 与 SFTP | `app/linux/ssh/connect` 和 `ssh/recover` 有实际独立职责；`ssh/recover` 根包持有救援状态机和原任务恢复；`ai` 保存专用模型与协议，`browser` 保存浏览器及终端控制，`model` 保存共享目标、动作及观测值；`app/linux/sftp` 使用已有受管 SSH 会话承担远端文件协议。DB 的救援存储保留 `db/sshrecover`，救援对话框归 `ui/server`；Service 不保留救援专属包，通用 AI 模块不保存救援专属协议。 |
| 界面 | 控件和包内协作归 `control`，主窗口归 `window`，通用受管任务启动控件、后台与通用恢复归 `task`，Mod 挂载归 `mod`，调试归 `debug`，语言与错误分别归 `language`、`error`；服务器配置和 SSH 救援对话框归 `server`。 |
| Mod 平台 | 安装、开发发现、运行与配置分别归 `app/mod/install`、`dev`、`run`、`config`；功能不同不因同词合并模块。服务中的安装、删除、重装和清理成组归 `service/mod/install`，商店适配归 `service/mod/store`。构建命令入口 `ModPackageTool` 归 `app/mod/dev`；`db/mod/ModDeletionLedger` 决定持久删除核验点，通过内部 `FileEvidence` 调用安装层 `PlatformModDeletionFileEvidence`，DB 不依赖 Service，也不自行读取安装文件属性。 |

Windows 更新的清单及签名验证归 `windows/update/check`，状态、事件、版本及失败值归 `windows/update/model`；卸载共享决策、状态及失败值归 `windows/uninstall/model`。流程编排保留各自根包。主密码加密归 `secret`，Mod 导航持久化归 `db/setting`。`db/init`、`db/sshrecover`、`main/runtime`、`ui/debug`、`windows/security` 和 `windows/window` 继续保留独立职责。

同一功能不同时保留浅层包和加层后的同义包。Java 声明与物理路径一致、类型可见性、平台公开合同、Mod 入口和导出、权限、任务归属及实际依赖检查均保留。根包允许实现不表示实现自动公开。

### 2.3 功能与依赖边界

目录词表不能替代实际职责检查：纯规则与不可变契约不执行 I/O，数据库仓库不编排业务，编解码和协议解析不反向依赖流程入口，UI 不直达数据库或原始执行能力。APP 加层须说明所表达的独立职责与依赖方向；业务已经交给 Mod 时，平台不预留其空目录或业务分类。

官方 deployment 当前的语言、构建工具、发行版和运行机制分类是该实现自己的组织方式。其既有功能覆盖与 helper 协议继续验收，但这些内部目录不作为其他 Mod 的命名、父子关系或深度模板。Mod 的内部自由不允许直接依赖 APP 实现或其他 Mod 私有包，跨 Mod 调用仍要求声明依赖和公开导出。

### 2.4 命名方式与职责边界

类名优先使用容易理解的常见词：行为类为“对象＋职责”，数据类为“对象＋用途”。技术或范围限定词只用于表达已有实现差异，不强制每个类都拼成三段，也不设机械长度上限。

```text
对象＋职责：HttpUrlChecker、VoiceTranscriptBuilder、ServerProfileStore
对象＋用途：AiModelInfo、TransferConfig、ResourceRefState
```

包说明功能归属，类说明具体对象及其职责。类名保留跨包调用需要的对象信息，不统一缩成 Checker、Info、Store。AppMain、Mod 等既定入口按现有合同保留。

规则、提示、脚本、注册和检查按实际功能归包；本节不再要求 contract.definition、contract.validation、generation.renderer 等固定层级。只使用第一节已有包，不为词表建立空包或委托壳。目录归属、I/O、纯值、任务、授权和依赖边界仍按实际实现检查。

### 2.5 行为类命名

| 功能组 | 角色词 | 中文含义与职责 | 示例 |
| --- | --- | --- | --- |
| 边界与交互 | `Access` | 聚合功能的访问入口 | `SecurityAccess` |
| 边界与交互 | `Client` | 调用网络协议或 API | `HttpClient` |
| 边界与交互 | `Controller` | 接收 UI 或 API 输入 | `PageNavigationController` |
| 边界与交互 | `Gateway` | 外部系统的能力边界 | `LinuxGateway` |
| 边界与交互 | `Session` | 有明确生命周期的会话 | `VoiceSocketSession` |
| 边界与交互 | `Transport` | 底层传输 | `HttpTransport` |
| 分析与转换 | `Builder` | 创建或组装对象、文本 | `VoiceTranscriptBuilder` |
| 分析与转换 | `Checker` | 检查输入、结构或事实；返回检查结果或拒绝 | `ModCodeChecker` |
| 分析与转换 | `Collector` | 收集多个来源的事实 | `HostFactCollector` |
| 分析与转换 | `Copier` | 复制有明确约束的值 | `ServiceValueCopier` |
| 分析与转换 | `Mapper` | 转换类型或错误原因 | `FailureMapper` |
| 分析与转换 | `Observer` | 只读观察权威状态 | `TaskObserver` |
| 分析与转换 | `Parser` | 将外部文本解析成对象 | `DeploymentStaticInputParser` |
| 分析与转换 | `Probe` | 主动读取实时事实或明确的探测输入 | `ModelCapabilityProbe` |
| 分析与转换 | `Resolver` | 解析唯一目标、路径或身份 | `RunModeResolver` |
| 分析与转换 | `Writer` | 生成最终文本；文件 I/O 由实际接口明确 | `MavenBuildWriter` |
| 决策与选择 | `Adapter` | 对接外部形态或扩展合同 | `ContainerAdapter` |
| 决策与选择 | `Flow` | 编排多个参与者完成流程 | `PlatformModFlow` |
| 决策与选择 | `Matcher` | 根据既有事实判断需求是否匹配 | `DeploymentHostMatcher` |
| 决策与选择 | `Policy` | 允许、禁止或支持规则 | `SourcePolicy` |
| 决策与选择 | `Registry` | 注册并选择实现 | `DeploymentAdapterRegistry` |
| 创建与准备 | `Factory` | 按构造规则创建对象 | `DesktopPartFactory` |
| 创建与准备 | `Finder` | 查找对象或发现来源 | `ProjectPartFinder` |
| 创建与准备 | `Planner` | 生成确定性计划 | `DeploymentStandardPlanner` |
| 创建与准备 | `Setup` | 执行前准备，不表示已经执行完毕 | `DeploymentSourceSetup` |
| 执行与业务 | `Migrator` | 执行版本迁移 | `SchemaMigrator` |
| 执行与业务 | `Runner` | 执行命令、协议或计划 | `DesktopTaskRunner` |
| 执行与业务 | `Service` | 可复用业务流程 | `PlatformModelService` |
| 执行与业务 | `UseCase` | 完成一个用户目标 | `DeploymentUseCase` |
| 持久化与展示 | `Presenter` | 将结果转换为展示内容 | `FailurePresenter` |
| 持久化与展示 | `Storage` | 组合存储能力与生命周期 | `PlatformStorage` |
| 持久化与展示 | `Store` | 存取一类明确对象或记录 | `ServerProfileStore` |

Checker 表示执行检查的角色；检查数据使用 CheckFacts、CheckResult，不把执行类和结果对象都简称为 Check。组装报告的类使用 ReportBuilder。规则计算与主动读事实的 I/O 仍分开，不能因角色词简化而合并流程。

### 2.6 数据类命名与对象词

| 功能组 | 用词 | 中文含义与使用边界 | 简单示例 |
| --- | --- | --- | --- |
| 事实与判断 | `CheckFacts`、`CheckResult` | 检查所得事实或结果，不执行检查 | `SourceCheckFacts`、`CheckResult` |
| 事实与判断 | `Decision` | 根据规则形成的决定 | `DeploymentHostMatchDecision` |
| 事实与判断 | `Evidence` | 可核对的事实依据 | `AnalysisEvidence` |
| 事实与判断 | `Facts` | 已观察事实，不包含判断 | `RemoteFacts` |
| 事实与判断 | `Report` | 基于事实形成的分析报告 | `DeploymentProjectReport` |
| 输入与授权 | `Approval` | 对明确内容的批准 | `DeploymentAssistedApproval` |
| 输入与授权 | `Arguments` | 类型化参数 | `DeploymentRuntimeArguments` |
| 输入与授权 | `Context` | 一次协作需要的输入与归属 | `TaskContext` |
| 输入与授权 | `Request` | 一次操作的完整输入 | `AiRequest` |
| 计划与执行 | `Action` | 明确的可执行动作 | `LifecycleAction` |
| 计划与执行 | `ActionLog` | 操作历史与恢复依据，不是普通展示日志 | `FileActionLog` |
| 计划与执行 | `Gate` | 必须满足的执行条件 | `ExecutionGate` |
| 计划与执行 | `Plan` | 执行前形成的有序计划 | `TransferPlan` |
| 计划与执行 | `Result` | 操作结果；TaskResult 与资源清理状态独立 | `TaskResult` |
| 状态与事件 | `Event` | 已发生的事实 | `LogEvent` |
| 状态与事件 | `Snapshot` | 某个时点的不可变状态 | `TaskSnapshot` |
| 状态与事件 | `State` | 对象的组合状态 | `ResourceRefState` |
| 状态与事件 | `Status` | 单一有限状态 | `AiCapabilityStatus` |
| 配置与定义 | `Config` | 已选定、可以实际应用的配置值 | `TransferConfig` |
| 配置与定义 | `Definition` | 范围、类型与默认规则定义 | `DeploymentRuntimeDefinition` |
| 配置与定义 | `Profile` | 可以命名、保存、选择和复用的配置组合 | `AiModelProfile` |
| 配置与定义 | `Rules` | 不执行行为的规则数据 | `DeploymentDistributionMatchRules` |
| 配置与定义 | `Scope` | 配置或操作的适用范围 | `TaskCapabilityScope` |
| 配置与定义 | `Spec` | 显式声明的要求或规格 | `ProcessSpec` |
| 标识与分类 | `Id` | 用于选择对象的标识，可携带必要用途或归属信息 | `SecretId` |
| 标识与分类 | `Identity` | 完整身份信息，例如服务器地址和可信主机指纹 | `SchemaIdentity` |
| 标识与分类 | `Kind`、`Type` | 明确的类别 | `DeploymentProjectKind` |
| 标识与分类 | `Level` | 有序等级 | `LogLevel` |
| 标识与分类 | `Mode` | 工作模式 | `AuthorizationMode` |
| 描述与引用 | `Catalog` | 有限权威支持项集合 | `DeploymentSupportCatalog` |
| 描述与引用 | `Entry` | 集合中的一项 | `FileEntry` |
| 描述与引用 | `Info` | 有明确对象的描述信息，例如 AiModelInfo | `AiModelInfo` |
| 描述与引用 | `List` | 清单及其所属信息；注册实现仍使用 Registry | `DeploymentApplicationList` |
| 描述与引用 | `Manifest` | 有既定格式的包或制品清单 | `ModManifest` |
| 描述与引用 | `Ref` | 指向资源、版本或业务关系的引用 | `ServerRef` |

ServerId / SecretId 是资源标识；ServerRef / SecretRef 保存业务引用与状态。二者保持不同类型，不合并字段或授权约束。Identity 不缩成 Id。完整模型信息使用 AiModelInfo，其中的可复用连接配置仍使用 AiModelProfile。

已有分析阶段可读为 Facts → Evidence → Report → Decision → Plan → Result；这不是建立全部对象或包的要求。

Registry、Parser、Deployment、Capability、Application、Profile、Snapshot、Identity、Transcript 保留。其他已确认调整包括 Publish、Match、Part、Secret、Finder、Spec、Setup、ActionLog；未讨论的单词保持现状。实际逐项改名见[类名调整清单](development/CLASS-RENAMES.md)。

### 2.7 限定词、接口与实现

| 功能组       | 限定词             | 唯一含义                     | 使用边界                 |
| ------------ | ------------------ | ---------------------------- | ------------------------ |
| 审阅与状态   | `Current`          | 当前成功或当前生效状态       | 不得代替实时远端状态     |
| 审阅与状态   | `Reviewed`         | 已经用户明确审阅和批准       | 未审阅对象不得使用       |
| 审阅与状态   | `Successful`       | 已完成并通过最终验证         | 中间状态不得使用         |
| 所有权与位置 | `Local`            | 属于本地 Windows 或源码侧    | 远端对象不得使用         |
| 所有权与位置 | `Managed`          | 由产品创建、验证并接管       | 外部非受管资源不得使用   |
| 所有权与位置 | `Remote`           | 来自目标 Linux 主机          | 本地对象不得使用         |
| 所有权与位置 | `Stored`           | 持久化层存储记录             | 不得用于公共领域模型     |
| 约束与保证   | `Bounded`          | 输入范围封闭且具有拒绝条件   | 不得作为普通强调词       |
| 约束与保证   | `Controlled`       | 操作范围由固定协议限制       | 不得接受任意命令         |
| 约束与保证   | `Immutable`        | 创建后不可变                 | 必须由类型结构保证       |
| 约束与保证   | `Safe`             | 实现明确安全不变量和拒绝路径 | 必须有对应验证           |
| 实现与协议   | `Default`          | 注册表明确选择的默认实现     | 不得表示当前唯一实现     |
| 实现与协议   | `OpenAiCompatible` | 使用 OpenAI 兼容协议         | 不表示由 OpenAI 官方提供 |

| 编号 | 接口与实现命名规范             | 正确示例            | 禁止示例                     |
| ---- | ------------------------------ | ------------------- | ---------------------------- |
| I-01 | 接口不得添加 `I` 前缀。        | `LinuxGateway`      | `ILinuxGateway`              |
| I-02 | 实现类不得添加 `Impl` 后缀。   | `SshdLinuxGateway`  | `LinuxGatewayImpl`           |
| I-03 | 实现类使用技术或行为限定词。   | `ContainerAdapter`  | `DefaultAdapterImpl`         |
| I-04 | SPI 接口使用真实行为角色命名。 | `DeploymentAdapter` | `DeploymentPluginInterface`  |
| I-05 | 实现类名称必须体现实现差异。   | `SshdLinuxGateway`  | `ConcreteLinuxGateway`       |
| I-06 | 不得使用宽泛抽象基类名称。     | 使用接口和组合      | `BaseService`                |
| I-07 | 抽象能力优先使用接口和组合。   | `DeploymentAdapter` | `AbstractGenericAdapterBase` |

### 2.8 单复数、缩写及禁限用名称

| 编号 | 类型单复数规范                     | 正确示例                    | 禁止示例             |
| ---- | ---------------------------------- | --------------------------- | -------------------- |
| C-01 | 普通类名使用单数。                 | `DeploymentUseCase`         | `DeploymentUseCases` |
| C-02 | 单个领域对象使用单数。             | `ServerProfile`             | `ServerProfiles`     |
| C-03 | 有限权威集合使用 `Catalog`。       | `DeploymentSupportCatalog`  | `DeploymentSupports` |
| C-04 | 实现映射使用 `Registry`。          | `DeploymentAdapterRegistry` | `DeploymentAdapters` |
| C-05 | 序列化清单使用 `Manifest`。        | `SourceManifest`            | `SourceFiles`        |
| C-06 | 固定集合数据使用 `Sets`。          | `AptPackageSets`            | `PackageData`        |
| C-07 | 静态操作集合不得通过复数类名表达。 | `ServerAccess`       | `ServerUseCases`     |

| 概念                    | 统一形式 | 禁止形式           |
| ----------------------- | -------- | ------------------ |
| Artificial Intelligence | `Ai`     | `AI`               |
| API                     | `Api`    | `API`              |
| CentOS                  | `Centos` | `CentOS`、`CentOs` |
| CPU                     | `Cpu`    | `CPU`              |
| .NET                    | `DotNet` | `Dotnet`           |
| HTTP                    | `Http`   | `HTTP`             |
| ID                      | `Id`     | `ID`               |
| JAR                     | `Jar`    | `JAR`              |
| JSON                    | `Json`   | `JSON`             |
| JVM                     | `Jvm`    | `JVM`              |
| OpenAI                  | `OpenAi` | `OpenAI`、`Openai` |
| SFTP                    | `Sftp`   | `SFTP`             |
| SHA-256                 | `Sha256` | `SHA256`           |
| SQLite                  | `Sqlite` | `SQLite`           |
| SSH                     | `Ssh`    | `SSH`              |
| SSHD                    | `Sshd`   | `SSHD`             |
| TCP                     | `Tcp`    | `TCP`              |
| TLS                     | `Tls`    | `TLS`              |
| URL                     | `Url`    | `URL`              |

缩写检查按 PascalCase 单词边界判断。`CLanguageChecker` 中的 `C` 是语言名称，后续 `Language` 是独立单词；不得将相邻的 `C` 与 `L` 误判为缩写 `CL`。真正的 `SSHSession`、`JavaJARChecker` 等全大写缩写仍违反规范。

| 名称                     | 使用规则                           | 例外                                     |
| ------------------------ | ---------------------------------- | ---------------------------------------- |
| `Base`                   | 禁止作为宽泛父类名称               | 明确稳定继承协议                         |
| `Bean`、`Object`         | 禁止                               | 无                                       |
| `Checker`                | 允许对象＋Checker；不允许顶层类只叫 Checker | 检查器与 CheckFacts / CheckResult 分开 |
| `Common`、`Misc`         | 禁止用于类型、包和受维护资源文件名 | 无                                       |
| `Concrete`               | 禁止表示具体实现                   | 使用技术或功能限定词                     |
| `Controller`             | 限制为 UI 或 API 输入边界          | 非 UI 或 API 操作使用 `Runner`         |
| `Data`                   | 不用于不明确的普通数据对象         | `DataPath` 等真实领域术语                |
| `Info`                   | 允许对象＋Info；不允许顶层类只叫 Info | AiModelInfo 等明确描述对象              |
| `Generic`                | 禁止表示不明确通用实现             | 无                                       |
| `Handler`                | 限制为事件或框架入口               | UI、HTTP、事件处理入口                   |
| `Helper`                 | 禁止表示普通辅助类                 | Managed Helper 正式协议                  |
| `Impl`、`Implementation` | 禁止                               | 无                                       |
| `Manager`                | 禁止表示普通协调器                 | Credential Manager 等正式名称            |
| `Legacy`、`New`、`Old`   | 禁止                               | 无                                       |
| `Processor`              | 禁止表示不明确行为                 | 明确消息处理协议                         |
| `Support`                | 禁止表示工具集合                   | 支持矩阵领域概念                         |
| `Temp`、`Temporary`      | 禁止作为正式类型名                 | 明确临时文件领域对象                     |
| `Util`、`Utils`          | 禁止                               | 无                                       |
| `V1`、`V2`、`Phase1`     | 禁止进入生产类型和包名             | 外部版本化 API 契约                      |

| 编号 | 资源文件命名规范                                  | 正确示例                      | 禁止示例                           |
| ---- | ------------------------------------------------- | ----------------------------- | ---------------------------------- |
| R-01 | 受维护资源文件名必须表达稳定功能。                | `00-protocol-foundation.sh`   | `00-common.sh`                     |
| R-02 | 禁限用名称同样适用于生产和测试资源文件名。        | `deployment-input.properties` | `deployment-utils.properties`      |
| R-03 | 资源文件名不得包含开发阶段或内部版本。            | `managed-helper.sh`           | `phase2-helper.sh`、`helper-v3.sh` |
| R-04 | `Helper` 仅允许表达 Managed Helper 正式协议资源。 | `execution/protocol/helper`   | 普通辅助资源使用 `helper`          |

### 2.9 异常、枚举和测试类命名

| 编号 | 异常命名规范                                                                              | 正确示例                                   | 禁止示例                               |
| ---- | ----------------------------------------------------------------------------------------- | ------------------------------------------ | -------------------------------------- |
| E-01 | 异常使用 `Exception` 后缀。                                                               | `LinuxOperationException`                  | `LinuxOperationError`                  |
| E-02 | 名称必须说明失败边界。                                                                    | `SecretStoreException`                     | `OperationException`                   |
| E-03 | 公共异常不得直接以底层实现细节命名。                                                      | `LinuxOperationException`                  | `SshChannelException`                  |
| E-04 | 安全异常名称不得包含秘密内容。                                                            | `SecretStoreException`                     | `InvalidPasswordValueException`        |
| E-05 | 1.1 受控失败以公开实例方法 `Problem problem()` 暴露安全原因。本地化消息与安全诊断分离。   | `ApiException`、`BrowserRecoveryException` | 原始 `exception.getMessage()` 用户回退 |
| E-06 | 异常使用 `[FailureBoundary]Exception`，名称说明模块内失败边界。                           | `SourceArchiveException`                   | `SourceError`、`OperationException`    |
| E-07 | 模块错误枚举使用 `[Boundary]FailureType`。                                                | `LinuxOperationFailureType`                | `LinuxError`、`FailureKind`            |
| E-08 | 只有重复的确定性转换逻辑才建立 `[BoundaryOrTechnology]FailureMapper`。                    | `SqliteFailureMapper`                      | 单次使用的通用 `ErrorManager`          |
| E-09 | 恢复判断使用 `[Operation]FailureRecoveryPolicy` 与 `[Operation]FailureRecoveryDecision`。 | `DeploymentFailureRecoveryPolicy`          | `RecoveryProcessor`                    |
| E-10 | 业务类型不得使用 `Error`、`Manager`、`Processor` 等泛化错误治理名称。                     | `DesktopFailurePresenter`                  | `ErrorManager`、`FailureProcessor`     |

任务持久化的 `TaskLedger.DependencyBusyException`、`TaskLedger.FinalizationException` 与私有 `PlatformTaskRuntime.AdmissionRejectedException` 是明确的内部 SQL 回滚信号，分别保留依赖占用、原归档凭据与准入拒绝；均在任务宿主内转为公开失败或待收尾状态，它们不实现旧共享契约，也不是允许其他异常省略结构化原因的通用例外。架构检查按真实返回类型检查公开、非静态、无参数的 `problem()`，保留异常命名要求及旧错误枚举登记检查。

| 枚举性质   | 统一后缀      | 示例                         |
| ---------- | ------------- | ---------------------------- |
| 可执行动作 | `Action`      | `LifecycleAction`            |
| 规则决定   | `Decision`    | `HostKeyDecision`            |
| 处置结果   | `Disposition` | `CollaborationDisposition`   |
| 已发生事件 | `Event`       | `DeploymentTraceEvent`       |
| 封闭类别   | `Kind`        | `RuntimeKind`                |
| 有序等级   | `Level`       | `DeploymentSupportLevel`     |
| 操作结果   | `Result`      | `TaskResult`                 |
| 工作模式   | `Mode`        | `RunMode`                    |
| 命名配置   | `Profile`     | `EcosystemCapabilityProfile` |
| 适用范围   | `Scope`       | `ConfigScope`         |
| 选择来源   | `Source`      | `SecretSource`           |
| 当前状态   | `State`       | `RuntimeState`               |
| 操作状态   | `Status`      | `DeploymentStatus`           |
| 领域类型   | `Type`        | `DeploymentProjectType`      |

| 编号 | 枚举命名规范                                             | 正确示例                                       | 禁止示例                   |
| ---- | -------------------------------------------------------- | ---------------------------------------------- | -------------------------- |
| G-01 | 枚举优先使用“领域对象＋语义后缀”；已确认 SDK 短名按精确类型登记。                       | `LinuxDistroType`                              | `LinuxDistro`              |
| G-02 | 常用语义后缀优先；职责已明确的短名按精确类型登记，不能因浅层迁移强制加后缀。           | `SourceLanguageType`                           | `SourceLanguage`           |
| G-03 | 生产、测试、顶级和嵌套枚举使用同一规则。                 | `PartIssue.SeverityLevel`                 | `PartIssue.Severity`  |
| G-04 | APP 与平台 API 的顶级简单名称保持唯一；Mod 类型以完整限定名区分。                       | `DeploymentPlanAction`、`DeploymentTraceEvent` | 两个 `DeploymentStep`      |
| G-05 | 嵌套类型以外部类型形成唯一作用域；语义明确的 SDK 短名可精确登记。 | `PartDataPath.AccessMode`                 | `PartDataPath.Access` |

平台文件合同的 `appapi.file.action.DeleteEffect` 表达回收或永久删除的实际效果，`FileChoice.Directory` / `FileChoice.Conflict` 分别表达两级冲突选择；它们依外部作用域保留简短语义名。`appapi.file.model.FileInfo` 是请求路径、实际对象、属性与链接链的不可变观测，和 `appapi.server.model.ServerInfo` 一样使用明确对象＋Info；Info 不再依赖这两个精确类型的特例。枚举短名仍按完整限定名登记，不因此允许任意 OtherChoice 或放宽其他命名、可见性和权限检查。

枚举常量统一使用：

```text
UPPER_SNAKE_CASE
```

| 测试类型 | 统一格式                     | 示例                                |
| -------- | ---------------------------- | ----------------------------------- |
| 验收测试 | `<Capability>AcceptanceTest` | `ReviewedDeploymentAcceptanceTest`  |
| 架构测试 | `<Rule>ArchitectureTest`     | `PackageNamingArchitectureTest`     |
| 契约测试 | `<Contract>ContractTest`     | `ManagedRemoteContractTest`         |
| 集成测试 | `<Subject>IntegrationTest`   | `DesktopStorageIntegrationTest` |
| 回归测试 | `<Subject>RegressionTest`    | `DeploymentRollbackRegressionTest`  |
| 单元测试 | `<Subject>Test`              | `SourceBoundaryCheckerTest`       |

## 3. 模块职责与边界

### 3.1 appapi 公开合同

`appapi` 定义 Mod 的初始化、迁移、生命周期、页面、任务、数据库、交互、AI、素材、秘密、服务器及远程能力合同；不依赖具体 APP 实现或部署业务。API 版本与内部 Mod 用途枚举分别管理。

一级能力继续使用 `task`、`file`、`linux`、`server`、`db`、`mod`、`ai`、`secret`、`ui`。`TaskService`、`FileService`、`ServerService`、`ModDatabase`、`AiService`、`LinuxService`、`SecretService`、`UiService` 分别作为各自根包的唯一入口；`mod` 根包保留 `ModContext` 与 `ModEntrypoint`，对应作者获取宿主能力与宿主调用作者生命周期两个方向。其他合同按职责进入二级包；任务运行、恢复、查询、引用、值、结果、动作、日志和进程合同分别归对应二级包。任务基础和恢复合同不反向依赖 `TaskService`。

`FileService` 只通过 `queries()`、`actions()`、`sources()`、`snapshots()`、`workspaces()` 返回原启用绑定的职责接口，并提供聚合清理 `closeTaskResources(TaskContext)`；不继承职责接口，不保留平面操作方法。`query` 负责文件观测与有界读取，`action` 负责副作用、原操作记录与恢复；`source` 表达已授权来源读取，`snapshot` 表达不可变快照，`work` 表达可写暂存工作区，保留各自授权、资源持有与实际关闭合同。宿主组合入口借用同一个文件所有者，不创建额外连接、审批或资源寿命；获得职责接口不替代实际任务与审批核验。

`task` 同时提供任务执行与核对的基础合同。`task/ref` 保存服务器和秘密业务提交与核对共用的引用状态与结果，不保存具体资源存取或释放逻辑；`server/ref` 依赖 `server/model` 与 `task/ref`，秘密引用依赖 `secret/model` 与 `task/ref`。持久业务引用仍归服务器和秘密能力管理，任务结束不代表引用自动释放。`task/query` 的 `PageSlice` 是可供任务、服务器等能力复用的通用分页结果，仅依赖 JDK，不反向依赖任务模型或服务入口。`db/schema` 保存 Schema 生命周期合同，`SchemaAccess` 的操作作用域与 `ModDatabase` 的任务作用域保持分离。

`ai/model` 保存通用模型用途、消息、请求、响应与用量，`ai/vision` 仅保存图像输入，视觉仍复用通用消息调用链；`ai/voice` 保存音频输入和实时会话合同。`linux/session` 保存会话与主机观测，`linux/action` 保存精确审批意图。Mod 身份归 `mod/model`，启动模式、停止上下文与原因归 `mod/lifecycle`，跨 Mod 服务归 `mod/service`。`ui/page` 成组保存页面创建、登记、描述与环境合同；秘密标识、业务引用和短期读取回调分别归 `secret/model`、`secret/ref`、`secret/access`。二级合同不反向依赖所属能力的根包入口，按第 5.2 节的实际依赖维护包图。

### 3.2 APP 平台模块与装配

| 叶子模块  | 当前职责                                                        |
| --------- | --------------------------------------------------------------- |
| `ai`      | 通用模型消息、图像编码、音频转写与 HTTP 通信                     |
| `db`      | H2 平台仓库、受管 Schema、任务、引用与操作账本                  |
| `linux`   | Apache SSHD 连接、脚本标准输入、命令输出、SFTP 及所属救援浏览器 |
| `main`    | 入口、固定运行根、平台装配、Mod 协调及默认窗口                  |
| `mod`     | 原包、描述、类加载、依赖与生命周期基础                    |
| `secret`  | 受保护凭据、当前用户保护、主密码与秘密存取                      |
| `service` | 原任务授权、公开能力适配、平台配置和安全用例                    |
| `file`    | 通用文件规则、受管本机目录、素材冻结、只读与归档能力               |
| `ui`      | 平台 Swing 组件、服务表单、页面挂载及本地化                     |
| `windows` | 原生保护与窗口隐私、文件/进程边界、签名与更新卸载安全核心       |

`app/ui` 的普通平台操作通过服务协作，SSH 救援仅使用第 5.1 节的窄类型；平台装配可组合必要实现，低层实现不反向依赖装配。`app/linux/sftp` 复用受管 SSH 会话承担远端文件协议。Windows 更新、卸载的安全核心存在不等于 S7 独立执行器已经交付。

H2 引擎、连接与事务由 `app/db/connect/PlatformDatabase` 持有；根包 `PlatformStorage` 组合 Store 和生命周期。任务、日志和 Schema 依赖连接层，连接层不反向引用业务存取；口令派生只接收 Schema 层的精确已编码数据库用户名，保留命名空间和算法。

`app/main/mod/PlatformModHost` 注入 Mod 上下文与启动图所需能力，不反向引用 `main/start/PlatformRuntime`。`RunModeResolver` 用 main 模块自身代码源定位，数据根使用 DB 模块锚点。`app/service/server`、`ai` 保存非秘密配置值、摘要和窄接口；`security` 管安全会话，`task` 管任务授权，`secret` 和 `process` 绑定实际能力，任务基础不反向依赖这些能力实现或日志服务。

完整平台救援由 `app/linux/ssh/recover/SshRecovery` 核对原任务、服务器选择和会话状态；启动层通过 `RecoveryHost` 接入平台授权与持久凭据，Linux 不反向引用 Service 或 DB。临时状态与固定宿主恢复记录归 `db/sshrecover`，`BrowserRecoveryArchival` 在原中央任务事务内归档摘要和清理事件；不新增作者 API 或 Maven 模块。

`app/ui/window/PlatformNavigationShell` 管导航、标题和设置分类，保留已挂载页面；`DesktopHomePane` 展示首页封面，Logo 导航返回首页。窗口锁定通过透明输入门禁保留导航与页面标签，仅禁用内容交互。`control/icons` 的 SVG 按 `lock`、`navigation`、`server`、`ai`、`mod`、`security`、`file`、`action` 分类，`control/brand` 保存独立项目图标和 16:9 封面副本；`control` 管卡片、主题控件及操作区布局，`display` 持有全局圆形开关绘制和动画时钟，`control` 单向使用 `display`；`mod/ModPageRegistry` 只管 Mod 页面生命周期，由 window 注入挂载界面，不反向依赖 window。导航偏好由 `app/db/setting/ModNavigationPreferences` 持久化，导航和删除事务共用同一实现；`service/setting/StartupPageService` 经原桌面偏好存储读写非秘密页面身份，独立 `StartupPageLocation` 为 UI 提供纯值，主窗口捕获正常关闭位置，启动装配在 Mod 发布完成后恢复。官方部署的 `ui/DeploymentWorkspace` 持有内部三个按需创建的实际页面，负责环境转发及清理重试；`ui/DeploymentPageStyle` 只使用 Swing 主题值，不依赖宿主 UI 类。

CLASS 调试预览经 `appapi.ui.UiService.registerPreview` 独立登记，受原作者提交、锁定与关闭约束；不进入普通导航，JAR/APP 未装预览宿主。平台预览只持显示控件，Mod 预览只捕获不可变示例，不持任务、数据库、SSH 或模型能力。

### 3.3 官方 deployment Mod

部署 Mod 独占分析、辅助与自主算法、部署配置、应用与发布表、秘密业务引用及固定 helper 协议；通过原任务的公开能力取得素材、审批、服务器、Schema 和远程会话。常规停用只释放 Mod 自有资源，保留已提交远端应用和业务数据。

| 实际归属 | 实现职责与所有权 |
| --- | --- |
| `ai` | 严格模型协议、提案值及可执行动作格式校验。 |
| `file`、`file/git` | 本地筛选、固定 Git 取材、清单及归档；平台持目录、快照和临时空间寿命。 |
| `service/core/model`、`config` | 共用纯值、配置和不可变修订；config 可依赖 model，model 不反向依赖 config。 |
| `service/core` | 共用业务接线；`DeploymentProjectSourceRecorder` 接通取材和 `db/DeploymentProjectStore`，纯值与仓库不依赖取材实现。 |
| `service/core/execution` | 受管应用管理、原任务执行和恢复流程。 |
| `service/core/delivery` | 已审阅内容的整应用交付和原任务正式提交，通过窄端口装配存储、远端与恢复能力。 |
| `service/standard/analyze` | 确定性源码事实、语言和构建识别，不执行构建。 |
| `service/standard/deploy` | 运行输入、计划、候选构建、发布；assistance 承担只读建议与修订门禁。 |
| `service/agent` | 自主提案、任务、工具及恢复编排，不调用标准引擎算法。 |
| `db` | 私有 Schema、业务事务、不可变发布历史与原操作账本；只访问本 Mod 的表。 |
| `secret` | 精确用途、不可变秘密修订、业务引用及发布绑定。 |
| `linux` | 固定 helper 和回执协议，通过公开受管会话执行，不持原始 SSHD 对象。 |
| `ui` | 本 Mod 用例和公开 UI 合同；不直达 db、模型执行或远端能力。 |

`DeploymentReleaseSnapshot`、`DeploymentReleaseRecord`、`DeploymentReleaseIdentity` 保留完整运行图及配置/秘密引用；`DeploymentReleaseCodec` 管版本和内容摘要，Store 管历史与复用，Checker 只读核验跨记录一致性。`DeploymentJsonDocument` 共用严格 JSON 原语；`DeploymentUnitCommit` 仅为 db 写入输入，不使 model 依赖 config。

页面只收集 Git、数据库和秘密引用输入；实际检查点、取材、源码/SQL 校验和发布在原后台任务完成。秘密输入只在独立创建任务准入后取得并按所有权清零，发布只消费已存修订。具体操作、格式、恢复和 UNKNOWN 规则统一见 [Mod 运行与能力合同](Mod.md#public-api-spec)、[运行约定](Runtime.md)及 [1.1 实施与验收](development/PHASE-1.1.md#review-decisions)。

### 3.4 配置、存储与秘密边界

1. APP 固定启动配置保存于 `app/config` 的 `src/main/resources/gold/debug/windowstolinux/app/config/config.yaml`，随 Config JAR 发布，由加载器通过 classpath 读取；main 加载一次并注入不可变快照。Config 不依赖其他项目模块，消费者单向依赖。字段与允许值仅维护于 [Config.md](Config.md)。APP 用户设置、模型目录和运行状态由平台 H2 仓库保存；deployment 的配置实例、不可变修订、运行事实和发布账本由其自有 Schema 保存。平台不按表名读取 Mod 私有业务。
2. Mod 可按需建立对应 APP 的第一层 `config` 职责包，在入口加载自身 YAML 与不可变配置，不依赖 APP Config 实现，也不访问其他 Mod 私有配置；默认 YAML 随所属 JAR 资源发布。现有 deployment 业务配置快照及载荷编码继续归 `service/core/config`，来源绑定运行默认值归 `service/standard/deploy/input`；这些纯规则不读写平台数据库，不暴露原始远程能力。
3. 普通配置只保存精确不可变秘密 ID/修订引用。APP 的秘密能力负责保护与权限，Mod 只保存所属业务引用；明文值、可清零缓冲和调用方借用数组按真实所有权处理，不用显示文本表达秘密身份。
4. APP 的 CLASS/JAR 根解析归 `app/main/runtime`，装配归 `app/main/start`。平台 UI 使用所属功能中的服务接口、只读值与明确门面，Mod UI 使用自己的用例和原公开 TaskContext，不绕过授权访问内部仓库。

服务器和秘密的精确原资源/业务键找回由 appapi 声明、service 校验原任务与代次、db 查询自身登记；不新增预留或秘密。Mod 保存原恢复事实与自己的业务结果，平台不读取 Mod 私有表。

### 3.5 本地化与错误契约

1. 英文是桌面端生产代码、内部校验、程序生成诊断和默认消息资源的基准语言；`app/ui/language/messages/Messages.properties` 是消息目录的规范来源，简体中文只保存在 `Messages_zh_CN.properties`。
2. 生产模块不得提前拼接英文或中文界面句子。1.1 失败通过公开 `Problem` 传递稳定事件码、安全英文诊断、上下文及原诊断身份；UI 按 `problem.<eventCode>` 查找固定本地化说明，未登记事件使用脱敏后的安全诊断。UI 是唯一负责解析显示语言的边界。
3. 部署步骤、部署状态、置信度、运行状态、自启状态和生命周期动作必须以稳定代码、`record` 或 `enum` 跨模块传递，不得依赖 `Object.toString()` 或显示字符串表达业务状态。

语言选择、原始诊断处理等运行规则见 [运行约定](Runtime.md)。

公开失败合同使用 `appapi/task/result` 的 `Problem`、`ProblemCode`、`ApiException`；错误识别、安全转换和私有异常归产生错误的模块，不新增中央 error 模块。事件码不可复用、改义或静默删除，原操作、服务器、资源和 UNKNOWN 诊断身份不得被显示文本替换。

作者任务、Mod 生命周期、页面及 Schema 回调中的普通异常和非致命 `Error` 进入各自既有失败合同。任务回调退出后记录已返回，恢复回调退出后释放核对互斥；未知效果及未释放资源继续保留，完成通知仍以原持久终态为准。`VirtualMachineError`、`ThreadDeath` 在必要清理和最小状态保留后原样传播，不作为可继续的普通业务失败，也不压入普通异常的 suppressed 列表。`DesktopTaskRunner` 在独立 finally 中清除线程引用；业务失败保持为主异常，普通授权关闭失败附加其后。

宿主失败报告复用 `app/db/log` 应急编码与 `app/service/log` 回填，`app/main/error` 只提取有界安全证据并绑定实时日志，不新增日志框架。静态失败门禁及人工审阅见第 6 节。

## 4. 源码组织与分包

### 4.1 Maven 通用物理布局

以下为 Java 叶子模块示例；无实际资源或测试时不创建空目录。

```text
src/<group>/<module>/  # [模块]     Java 叶子模块；appapi 的路径为 src/appapi/
├─ pom.xml             # [构建文件] 所属模块的依赖与构建声明
└─ src/                # [目录]     Maven 标准源码与测试分组
   ├─ main/            # [目录]     生产源码与资源
   │  ├─ java/         # [目录]     按完整 Java 根包存放源码
   │  └─ resources/    # [资源]     有生产资源时创建，随所属制品打包
   └─ test/            # [目录]     所属模块测试
      ├─ java/         # [目录]     镜像所验证的生产包
      └─ resources/    # [资源]     有测试资源时创建
```

Java 包前缀按 `gold.debug.windowstolinux.appapi`、`app.<module>`、`mod.<mod-id>` 表达所属模块；第三方 Mod 根包见第 4.4 节。测试镜像生产包，独立根 test 夹具见第 1.2 节。

### 4.2 适配协作与扩展组织

语言生态、构建架构、工作负载、运行机制、发行版与 CPU 架构保持正交。Mod 的分析和构建生态共享 `service/core/model` 的不可变事实；平台 Linux 负责机械远程能力与所属 SSH 救援，不承载 Mod 部署业务，业务 helper 只经原 TaskContext 与实际审批发送。

1. 分析生态负责识别与预览；每个真实独立构建架构在其语言分类内命名，不用目录对称或类数建立空分组。
2. 构建 SPI、注册与脚本生成归 `service/standard/deploy/build/{contract,extension,generation}`，生态和工作负载差异分别归 `ecosystem`、`workload`，不得复制通用候选、SSH、任务或 helper 准入逻辑。
3. 工具链与发行版事实通过已审阅类型协作；环境准备、精确绑定、构建预算和人工审批等待分别核对。未知准备或绑定保持原意图，不能通过重发或重置预算掩盖。
4. 静态分析未正式支持的组合只给预览或不支持结果；进入辅助或自主流程仍需其自己的来源、模型、人工审阅和受管能力准入，不把静态拒绝变成未经授权的自动执行。
5. 平台 `app/linux` 独占 Apache SSHD；公开合同和 deployment `linux` 不引用其具体客户端、会话、通道或 SFTP 类型。Mod 不能绕过公开 RemoteSession 取得不受控宿主能力。

<a id="package-boundaries"></a>

### 4.3 分包规则

1. Maven 模块表达依赖、技术和安全边界；Java 包只负责模块内部组织，仍遵守第 5 节依赖方向。
2. APP 默认按第一层实际功能集中接口、专属数据和实现；模块单一职责明确时，根包允许具体实现。纯规则、数据库访问与编排的边界根据实际依赖保留。
3. 测试包镜像生产包；场景按真实功能和预期结果组织。Java 声明、文件与物理路径保持一致，生产包图不得成环。
4. APP 与平台自有 API 的名称遵守第 2 节；Mod 第一层、入口与导出遵守第 4.4 节，第一层之后不施加 APP 的内部词表、固定父子包或层数要求。
5. 平台 `appapi/linux` 保存远程合同，`app/linux` 独占 SSHD 机械连接、命令和 SFTP；Mod 的 `linux` 仅通过公开受管会话发送业务协议，不暴露原始客户端、通道或 SFTP 类型。
6. 新增包、合并包及资源移动按实际职责核对包内协作、重名、类可见性、资源路径及依赖，不为目录对称创建空包或委托壳。
7. UI 使用明确的服务接口、数据合同与平台门面；SSH 救援仅允许第 5.1 节精确登记的窄类型，不因此开放底层 Linux、DB 或其他执行实现，也不能绕过平台准入直接调用公开 SDK 执行能力。
8. Mod 入口发现、公开包导出、Mod 数据归属、任务、权限与资源生命周期不因内部命名自由而放宽。调用其他 Mod 必须经过已声明依赖和已导出接口。
9. 普通实现类保留 25 个实例字段、30 个直接声明的实现方法、单方法 80 个 JDK AST 语句上限。公开 API 接口的声明不按实现方法数限制；实现类仅排除实际 SDK 接口中匹配的公共 `@Override` 单语句委托：须直接返回调用结果或仅委托调用，接收者可由既有访问器取得，参数中的调用只允许精确登记的无参数不可变默认值 `java.util.Optional.empty()` 与 `appapi.file.action.FileChoice.ask()`，且不含内联校验分支、赋值、构造、数组分配或 lambda。所有字段、委托方法本身的语句数和承担实际工作的实现均继续检查；不能用带校验的 wrapper、无关方法、其它同名工厂或虚构接口规避。行数、文件数和层数不能替代语义判断。
10. 官方 deployment 已有的纯值与引擎、只读分析与执行、协议与业务编排、仓库与界面依赖边界继续验收；其当前目录是实际实现事实，不作为 Mod 作者内部组织模板。

<a id="mod-source-layout"></a>

### 4.4 Mod 入口、第一层职责与根包

本节适用于官方和第三方 Mod。入口、第一层职责及平台边界为共同约定，内部包名、子包深度、类型名和组织方式由作者决定；不附 APP 内部词表或推荐目录。官方 deployment 的当前组织见第 1 节，本机与真实远端验收边界见开发文档。

#### 主模块根包示例

主模块采用第 4.1 节的 Maven 布局；下方只展示 Java 根包内的入口与第一层职责。十类包均按需使用，不要求建齐，不创建空包；进入任一职责包后由作者自主组织。

```text
com/example/windowstolinux/mod/backup/  # [包]     Mod 主模块的 Java 根包示例
├─ Mod.java                             # [入口类] 唯一指定的根包入口，负责装配与生命周期
├─ ai/                                  # [包]     业务模型输入与结果适配
├─ api/                                 # [公开包] 本 Mod 导出的公开合同
├─ config/                              # [包]     可选固定 YAML 解析与不可变配置
├─ db/                                  # [包]     本 Mod 私有 Schema 与业务存取
├─ file/                                # [包]     业务素材规则与平台文件能力使用
├─ linux/                               # [包]     通过公开远程能力执行业务协议
├─ secret/                              # [包]     业务秘密用途、修订与引用
├─ service/                             # [包]     业务用例及公开合同实现
├─ ui/                                  # [包]     本 Mod 页面与交互
└─ windows/                             # [包]     通过公开本机能力执行业务协作
```

第一层职责包与对应平台模块同名，但不允许直接依赖平台实现；Mod 调用 `appapi`。`api` 为 Mod 专属位置，APP 无需建立同名实现模块。`app/main` 和 `app/mod` 不要求 Mod 增建同名包；各职责包不独立建立 POM。入口例为 `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/Mod.java`。

#### 入口与职责边界

入口、各职责包的实现责任及业务调用规则统一见 [Mod.md：入口与职责包](Mod.md#51-入口与职责包)、[公开 API](Mod.md#52-区分平台-api-与本-mod-的-api)和[AI 分工](Mod.md#53-使用-ai-时如何分工)。本文维护结构与命名，依赖统一见第 5 节。

<a id="mod-package-naming"></a>

#### 命名与多模块边界

官方与第三方 Mod 的标准 Java 根包统一采用：

```text
<顶级域名>.<一级域名>[.<其他域名层级>...].windowstolinux.mod.<modname>
```

域名前缀按倒序排列；可选的其他域名层级位于固定的 `windowstolinux.mod` 之前。上面的尖括号、方括号和省略号只是格式说明，不是实际包名字符。`modname` 是该 Mod 的包名段，沿用小写且符合 Java 包名的命名约束。

| 域名示例            | Mod 名       | 标准根包                                      | 入口完整类名                                      |
| ------------------- | ------------ | --------------------------------------------- | ------------------------------------------------- |
| `debug.gold`        | `backup`     | `gold.debug.windowstolinux.mod.backup`        | `gold.debug.windowstolinux.mod.backup.Mod`        |
| `example.com`       | `deployment` | `com.example.windowstolinux.mod.deployment`   | `com.example.windowstolinux.mod.deployment.Mod`   |
| `tools.example.com` | `backup`     | `com.example.tools.windowstolinux.mod.backup` | `com.example.tools.windowstolinux.mod.backup.Mod` |

根包与稳定 Mod ID 的关系、发布后的身份保持及数据归属统一见 [Mod.md：身份与版本](Mod.md#mod-identity-version)。

1. 入口类名固定为 `Mod`，源文件名严格为 `Mod.java`；第一层职责包使用上述固定名称。第一层内部由作者自行命名和分包，仍遵守 Java、构建、公开导出及依赖合同；不以 APP 的名称或层数门禁限制作者。
2. 各 Mod 使用独有根包，并遵守本节标准格式。官方 Mod 使用 `gold.debug.windowstolinux.mod.<modname>`；第三方使用自己的域名倒序前缀，并保留 `windowstolinux.mod` 固定段。
3. 不同 Mod 可以分别声明 `Mod`，以完整限定名区分，例如 `com.example.windowstolinux.mod.backup.Mod` 与 `com.example.windowstolinux.mod.deployment.Mod`。主模块只提供一个指定入口，不依赖简单类名的全局唯一性定位插件。
4. 同作者从模块用于复用或隔离实际职责，不要求每个从模块再建立入口和全部职责包。只有插件主模块承担入口装配；从模块不能反向依赖主模块。普通职责分包不自动升级为 Maven 模块。
5. 官方备份和部署均遵循主模块根包入口规范；部署的 `service.core`、`service.standard`、`service.agent` 保留各自职责，两个引擎互不依赖。新增从模块或独立 API 制品仍需根据实际调用和依赖需要评审，不能按包名自动拆出多个模块。

### 4.5 资源、构建与物理会话装配

生产资源位于所属模块的 Maven resources 内，路径与实际加载归属一致；Mod 包资源与运行后数据分开。`DeploymentManagedHelperBundle` 按显式清单和顺序组装；语言分派归 `fragments/ecosystem`，原生数据库归 `fragments/database`，其余基础、输入、发布、运行、备份及恢复片段按实际职责分组。纯路径迁移保留字节、顺序、协议与摘要；行为改变才同步身份与合同，历史资源核对见 [1.1 结构迁移证据](development/PHASE-1.1.md#structure-gates)。

`DeploymentPublishEvidenceChannel` 负责原任务/独立操作证据及实际准入发送，`DeploymentPublishReceipt` 解析固定回执；不把协议解析和业务编排混在一起。CLASS/JAR loader、SSH 客户端、物理会话、通道、线程、进程及 JDBC 连接由实际所有者关闭并核对退出；发出停止请求或返回 close 不表示账本已清理。

根 POM 管聚合及构建期插件依赖，开发机使用系统 Maven 与本地仓库；不创建项目专用仓库或 Maven Wrapper。官方 deployment 继承 `.mvn/wtlmod-author-parent/pom.xml`；appapi 为 provided，私有编解码库按真实依赖声明，app/main 仅用于测试宿主，test-support 附件不进入生产依赖。作者工具复用 `app/mod/install`，命令行不启动 APP；当前工具与发行后置范围见 [APP 构建契约](APP.md#build-contract)。

## 5. 依赖方向

<a id="phase-1-1-dependencies"></a>
<a id="phase-1-1-module-graph"></a>
<a id="module-dependencies"></a>

### 5.1 Maven 模块依赖

以下是当前项目生产依赖的允许上界；实际 POM 只保留真实使用边，不为凑齐上界增加依赖。箭头仅表示模块依赖，包间依赖另列。

```text
appapi ──→ none
app/config ──→ none
app/ai ──→ app/config, appapi
app/file ──→ app/windows, appapi
app/linux ──→ app/config, app/file, app/windows, appapi
app/mod ──→ appapi
app/ui ──→ app/config, app/ai, app/service, app/linux, appapi
app/windows ──→ appapi
app/db ──→ app/ai, appapi
app/secret ──→ app/db, appapi
app/service ──→ app/config, app/ai, app/db, app/linux, app/mod, app/secret, app/file, app/windows, appapi
app/main ──→ app/config, app/ai, app/db, app/linux, app/mod, app/secret, app/service, app/file, app/ui, app/windows, appapi
mod/deployment ──→ appapi
```

1. `appapi` 是独立公开合同，不反向依赖 APP 实现或业务 Mod。官方 deployment 的生产依赖仅为 appapi 和其明确外部编解码依赖；APP 的 Maven 主体不依赖业务 Mod。测试宿主依赖不构成生产授权。
2. `app/main` 是实际装配根。平台 Linux、来源、秘密及 AI 实现不反向引用 UI，业务 Mod 不读取 APP 私有实现或其它 Mod 私有数据。
3. `app/config` 仅提供启动配置解析与不可变值，不依赖其他项目模块。`app/ai` 包含平台模型调用及纯值，不依赖数据库或界面。DB 和 UI 显式依赖 `app/ai`，复用 `AiCapabilityType`、`AiCapabilityStatus`、`AiCapabilityCheck`、`AiPurposeType`、`AiPurposeAssignment` 的能力证据及用途值；内部用途名称不替代公开 Mod 的 `AiPurpose` 合同。
4. APP UI 的普通平台操作经 `app/service` 发起；SSH 救援只可访问 Linux 的受控 `SshRecovery`、`SshRecoverySession` 及其选择、快照和终端纯值。不能直接使用 DB、Windows、Linux 传输、浏览器、宿主注入接口、来源、秘密或 Mod 执行实现，也不能绕过平台准入直接调用公开 SDK 的 Linux、进程、来源写入或秘密能力。SDK 的任务、问题、UI、审阅和只读值合同可按实际用途使用，不设旧业务整包例外。
5. 服务通过实际任务、审批、秘密与引用账本维护授权；不能靠传递依赖取得未声明的直接生产依赖。平台与业务均禁止循环依赖和全局静态状态绕过边界。
6. 辅助分析只使用原冻结源码的只读 list/read/search 和有界 AI 请求；原候选构建、纠正、恢复和正式发布由已审阅的执行路径负责，分析不能取得执行、秘密或可写源码能力。

依赖门禁按 `module-dependencies` 锚点读取这一份模块图；构建/运行的第三方依赖见 [APP 构建契约](APP.md#build-contract)。

<a id="appapi-package-dependencies"></a>

### 5.2 appapi 公开包依赖

| 包 | 当前同模块直接依赖 |
| --- | --- |
| `ai` | `ai.model`、`ai.voice`、`task.result`、`task.run` |
| `ai.model` | `ai.vision` |
| `ai.vision` | 无，仅使用 JDK。 |
| `ai.voice` | `task.result` |
| `db` | `db.schema`、`task.result`、`task.run` |
| `db.schema` | 无，仅使用 JDK。 |
| `file` | `file.action`、`file.query`、`file.snapshot`、`file.source`、`file.work`、`task.result`、`task.run` |
| `file.action` | `file.model`、`server.model`、`task.action`、`task.result`、`task.run` |
| `file.model` | `server.model` |
| `file.query` | `file.model`、`task.result`、`task.run` |
| `file.snapshot` | `file.source`、`task.result`、`task.run` |
| `file.source` | `task.result`、`task.run` |
| `file.work` | `file.source`、`task.result`、`task.run` |
| `linux` | `linux.session`、`linux.transfer`、`server.model`、`task.result`、`task.run` |
| `linux.action` | `linux.command`、`linux.transfer`、`server.model`、`task.action` |
| `linux.command` | 无，仅使用 JDK。 |
| `linux.session` | `linux.command`、`linux.transfer`、`task.action`、`task.result` |
| `linux.transfer` | `server.model` |
| `mod` | `ai`、`db`、`file`、`linux`、`mod.lifecycle`、`mod.model`、`mod.service`、`secret`、`server`、`task`、`task.action`、`task.log`、`task.process`、`ui` |
| `mod.lifecycle` | 无，仅使用 JDK。 |
| `mod.model` | 无，仅使用 JDK。 |
| `mod.service` | `task.result`、`task.run` |
| `secret` | `secret.access`、`secret.model`、`secret.ref`、`task.ref`、`task.result`、`task.run` |
| `secret.access` | 无，仅使用 JDK。 |
| `secret.model` | 无，仅使用 JDK。 |
| `secret.ref` | `secret.model`、`task.ref` |
| `server` | `server.model`、`server.ref`、`task.query`、`task.ref`、`task.result`、`task.run` |
| `server.model` | 无，仅使用 JDK。 |
| `server.ref` | `server.model`、`task.ref` |
| `task` | `task.model`、`task.query`、`task.recover`、`task.result`、`task.run` |
| `task.action` | `task.result`、`task.run` |
| `task.log` | `task.result` |
| `task.model` | `task.result` |
| `task.process` | `task.result`、`task.run` |
| `task.query` | 无，仅使用 JDK。 |
| `task.recover` | `task.model`、`task.result`、`task.run` |
| `task.ref` | 无，仅使用 JDK。 |
| `task.result` | 无，仅使用 JDK。 |
| `task.run` | `task.model`、`task.result` |
| `ui` | `task.result`、`ui.page` |
| `ui.page` | `task.result` |

跨能力限制按一级能力归属检查，实际包图仍按所有一级与二级包检查无环，不反向引用 ModContext 或具体 Mod；db 中 JDBC、ui 中 Swing 均为 JDK 合同，不引入 H2 或 APP 实现。内部模型用途与公开 AiPurpose 分开，Id / Ref / Identity 的类型区分见第 2.6 节。

### 5.3 官方 deployment 内部依赖

下表描述官方实现的既有依赖与所有权，不作为第三方 Mod 的内部包模板。Mod 均只经 appapi 使用平台能力，其他 Mod 调用须声明依赖并使用实际导出包。

| 使用方 | 可依赖的边界                                                                                                                           | 禁止反向渗透                                                                             |
| ------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------- |
| `deployment/api`                      | 平台 API 的必要公共契约                                                                                                                | 不依赖或暴露部署私有实现、数据库实体及备份类型                                           |
| `deployment/ai`                       | 平台公开 AI 契约、本包的请求与结果类型                                                                                                 | 不直接依赖 APP 的 AI 实现、Provider 客户端、`db` 或业务流程                              |
| `deployment/db`                       | 平台公开 JDBC/凭据契约、本包存储映射及 service/core 下 config/model 纯数据值                                                           | 不依赖 core 业务编排、UI 或 AI，不访问其他 Mod 私有表                                    |
| `deployment/linux`                    | 平台 Linux/服务器/任务审核契约、service/core/model 所需纯值与固定 helper、构建及发布回执协议适配                                       | 不接管 SSHD、连接、指纹或独立救援；不调用 UI 或两个部署引擎                              |
| `deployment/secret`                   | 平台秘密/任务契约、本 Mod db 与 service/core/model 纯值、一次性秘密修订创建及原任务恢复                                                | 不持有 APP 主密钥，不持久化或长期留存明文，不绕过凭据归属/任务授权，不调用 UI 或部署引擎 |
| `deployment/file`                   | 平台 File/任务契约、业务取材规则及 service/core/model 纯值                                                                           | 不接管平台快照寿命，不调用 UI 或部署引擎；Git 固定取材属于本包                           |
| `deployment/windows`                  | 平台公开 File/进程/交互等契约及本机业务适配                                                                                          | 不依赖 app/windows 私有类，不接管平台更新、卸载和窗口隐私                                |
| `deployment/service/core`             | 平台 API、部署公开契约及必要业务能力                                                                                                   | 不反向依赖 standard 或 agent；具体存储/远端实现经窄端口接入                              |
| `deployment/service/standard/analyze` | 平台任务 API、原冻结素材句柄及 `service/core/model` 纯值                                                                               | 保持确定性静态分析，不依赖 agent 或 UI；不接管原快照寿命                                 |
| `deployment/service/standard/deploy`  | 标准分析、共用 core、部署 API、所需 ai/db/linux/secret/file/windows 及平台 API；已审阅计划、候选构建、单/多组件发布与有界 assistance | 不依赖 agent 或 UI，普通标准流程保持无 AI 路径                                           |
| `deployment/service/agent`            | 共用 core、部署 API、所需 ai/db/linux/secret/file/windows 及平台 API                                                                 | 不依赖 standard 或 UI；多轮业务决策和工具执行留在本包                                    |
| `deployment/ui`                       | 本 Mod 的 service 及平台 UI 契约                                                                                                       | 不直接访问 db 或调用 ai 执行业务                                                         |
| `deployment/Mod.java`                 | 本 Mod 的各层及平台注册契约                                                                                                            | 负责装配与生命周期，不把两个引擎互相耦合或承载业务实现                                   |

core 共用纯值与窄端口，standard 和 agent 不反向依赖彼此；注册/装配可以依赖具体实现，底层值、编解码和协议解析不反向依赖入口。标准分析向计划提供确定性事实，已审阅计划交给候选、构建及发布；辅助分析只有原快照的只读素材和有界模型调用，不能取得执行、秘密或可写素材能力。

## 6. 结构检查与审阅

<a id="后续门禁衔接"></a>
<a id="structure-checks"></a>

本节汇总活动结构的检查范围；现行 Mod 规范见第 4.4 节，迁移及验收流程见 [1.1 开发文档](development/PHASE-1.1.md#structure-gates)。历史门禁不覆盖现行组织规则。

1. 普通 Maven 叶子模块的目录名使用一个英文单词；只有把具体实现从公共契约模块独立隔离时，才使用 `<contract>-<implementation>`，例如 `linux-sshd`。
2. Java 类存在接口与实现不代表必须拆分 Maven 模块；只有具体实现需要独立依赖或装配时才建立连字符实现模块。`file` 当前不因归档格式拆分成独立模块，源码归档格式属于其内部职责。
3. 只有经过确认的目录、模块名称、职责和依赖方向才能写入正式结构；禁止通过复制模型、反射或静态全局状态绕开依赖约束。
4. `.idea/`、`.ai-workspace/`、`target/`、`node_modules/`、`dist/`、缓存、日志和其他开发辅助或生成目录不纳入正式项目结构。
5. 分期是开发路线与验收文档的组织方式，不是产品运行时架构。APP 和平台自有 API 的模块、包、类、方法、字段、枚举、消息键、配置键、资源名、脚本名和测试名不得以 `PhaseOne`、`PhaseTwo`、`phase1`、`phase2`、0.1 期、0.2 期等期数命名；必须按稳定职责命名。正式文档保留分期标题，内部按功能组织；日期和版本仅为必要证据属性，不维护时间流水账，不把期数泄漏为代码 API 或持久化契约。
6. `DeploymentAnalysisFlow` 不导入具体项目类型实现；`ProjectLanguageChecker` 只组合确定性的语言事实检查器。低层语言与构建 Checker 不修改调用方拒绝集合，生产与测试包镜像，包依赖不成环，不按开发阶段或宽泛类别聚合实现。标准 `plan` 不构造具体适配器，`app/linux/ssh/connect` 与 `sftp` 机械层不保存 Mod 业务状态，低层命令不反向依赖会话组合入口；不以兼容壳保留旧类型。
7. `PackageStructureArchitectureTest` 对 APP 与平台 API 检查适用命名，对所有活动 Java 源码保留文件/类型/物理目录一致、Mod 第一层和根包入口、生产依赖环、测试包镜像、反向依赖、旧引用/物理路径与产品入口检查。Mod 第一层之后不执行 APP 名称、父子词表或深度门禁；同名 Mod 类型以完整限定名区分。官方部署的功能、helper 资源身份及依赖检查继续保留。

| 验证方式 | 覆盖范围与边界 |
| --- | --- |
| `PackageStructureArchitectureTest` | 正反例覆盖 APP 功能包和根包、杂物包、公开 API 能力包、Mod 自由内部命名/深度与受限第一层/入口；所有活动代码保留 Java 结构、依赖与适用功能门禁。 |
| `DocumentStructureArchitectureTest` | 根据 `current-source-tree` 双向核对模块与生产包，并检查实际节点类型、中文职责及两列注释对齐；显式规划不要求空实现，根包实现也必须记录。 |
| `ModuleDependencyArchitectureTest` | 根据唯一 `module-dependencies` 锚点核对实际 POM、跨模块引用、UI 窄入口与执行边界；锚点缺失、重复或图缺失均失败，章节改名不影响解析。保留 Mod 私有实现和权限反例。 |
| 人工审阅 | 核对功能所有权、加层必要性、可见性、纯规则无 I/O、仓库无编排、权限和公开 API 短名的语义；静态门禁不证明这些判断或真实远端结果。 |

`FailureContractArchitectureTest` 保留实际 AST、异常类型、Problem 出口、事件登记、双语消息与捕获检查。各门禁通过只证明其覆盖的静态或本机合同，不表示目标机运行完成或未知操作已经结清。

结构变更同步声明、物理路径、引用、资源、测试与索引；退出活动构建的 shared/web 和历史 SQLite 不保留兼容壳。类型唯一性按 APP/appapi 简单名和 Mod 完整限定名区分；Mod 内部自由不豁免入口、导出、权限、数据归属、任务与资源生命周期。

## 7. 规划与历史边界

<a id="planned-boundaries"></a>

本文只保留当前结构和下表中的规划边界，不再另列早期目标树或重复模块图。旧 `phase-1-1-*` 显式锚点作为对应主题的定位别名保留；历史结构、迁移和验收事实由开发文档维护，不能用当前命名或回归结果覆盖当时记录。

| 内容 | 当前边界与正文位置 |
| --- | --- |
| 冻结旧工程 | `history-src` 不参与活动 Maven、IDE 包树或生产发布，不改旧包声明；保存范围见 [1.1 归档](development/PHASE-1.1.md#archive)。 |
| 备份、恢复和迁移 Mod | S6 暂缓，不建立空模块；已确认采用单主模块、根包 Mod 入口，跨 Mod 协作只依赖部署导出 API，不读私有表；任务见 [1.1 阶段计划](development/PHASE-1.1.md#implementation)。 |
| 作者工具、SDK 和发行交付 | CLASS 描述构建已接线；pack/verify、SDK 和 APP/JAR 打包仍属于 S7，Windows 更新/卸载安全核心不代表独立发行执行器已交付；见 [APP 构建契约](APP.md#build-contract)及 [1.1 打包步骤](development/PHASE-1.1.md#packaging-steps)。 |
| 官网与 Web | 后置官网、教程、下载和投稿，不接入 APP/Mod 的用户服务器执行链，也不挂空 Maven 模块；见 [1.1 网站范围](development/PHASE-1.1.md#website-service)。 |
| 真实运行验收 | 本机、受控回包、真实 Linux、模型/语音提供方和云控制台分开记录；SSH Git 凭据与信任接线仍按现行证据保留未验证范围。见 [1.1 当前状态](development/PHASE-1.1.md)与 [1.2 开发文档](development/PHASE-1.2.md)。 |

运行数据根和目录行为统一见 [Runtime.md](Runtime.md#desktop-data)，发布/安装目录见 [APP.md](APP.md)，不在源码树重复维护。


### 当前合同与依赖检查

- Service向UI只返回服务自身的不可变观测值及公开appapi合同。包检验、Mod清单解析、H2日志登记/记录等实现对象不透出；观察值不替代签发服务、一次性审批、原文件身份及摘要复核。
- `db.connect` 持有H2引擎、保护材料、实例锁及连接生命周期；`db.init`通过借用连接执行当前DDL、严格结构检查及启动恢复，不反向依赖`db.connect`。
- `windows.handoff`持有共用认证交接封套及交接失败；update/uninstall复用该包，交接字节、用途隔离及认证合同保持。
- 包和模块图使用显式引用与JDK解析后的实际类型共同检查，包含`var`、泛型、嵌套类型、成员所属类型及返回合同。任意长度环均拒绝；共用基础类型的菱形依赖允许。目标是无环有向图，不强行复制基础能力组成单一树。
- appapi继续登记41个公开包，一级根包只保留已登记的主要入口，不新增三级包；活动结构和产物不保留旧平台SQL、workspace路径、缺任务/取消/审阅名称的兼容入口或旧版部署执行账本读取分支。冻结历史源码和当时验收事实单独保留。

远端助手仅接受当前 `identity-v2` 完整身份与工作负载；旧 Gradle 简写、缺运行参数的发布、旧 unit 用户推导和无工具链版本回退不进入发布、回滚或生命周期。运行参数涉及的语言工具必须有当前已核验绑定；原生构建、受管进程、现行发行版及容器适配继续使用其明确合同。结构门禁同时扫描活动 Shell 和消息资源，助手内容摘要按当前资源登记。
