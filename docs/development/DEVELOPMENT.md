# WindowsToLinux 开发总纲

<a id="navigation"></a>

本文维护当前开发状态、分期导航和文档职责，不重复定义产品合同。现行事实以当前工作区代码、清单和适用证据为准；历史验收只证明对应版本、环境与范围。整理日期：2026-10-09。

- [当前状态](#status) · [分期导航](#roadmap) · [历史模块索引](#modules) · [现行规范入口](#rules) · [开发与验证](#workflow) · [文档职责](#documentation)

<a id="status"></a>

## 1. 当前产品与开发状态

活动源码采用 APP/appapi/Mod 结构，根聚合为 `src/appapi`、`src/app` 和 `src/mod`；`src/moddev` 是独立作者开发区。平台负责桌面、任务、连接、文件、AI、安全和 Mod 宿主，部署、Git 与文件管理按各自 Mod 的职责运行。

1.3 的 P0 和 S0～S6 已完成适用本机验收与收尾，具体范围见 [1.3 记录](PHASE-1.3.md#acceptance)。后续工作按当前源码单独确认范围，不继续将 S6 列为待实施。1.1 的备份/迁移 Mod、正式 JAR/APP/SDK 发行和独立维护执行器仍属后置范围；不同期数的 S6/S7 不能混为同一任务。

当前发布配置值为 `1.0.0`，未发布开发阶段统一维护数据库 Schema 1，直接维护完整建库基准，不实现开发迭代升级迁移。数据库字段排列规则见 [File.md](../reference/File.md#database-structure)，发布后的通用数据迁移合同见 [Mod.md](../reference/Mod.md#schema-version)。协议、执行语义、Schema 与软件版本各自独立。

现行模型支持显式选择 Chat Completions、Responses 和 Anthropic Messages，并独立验证文字及原生工具能力；部署协议为 `autonomous-deployment-3.0`、执行语义 4，helper 为 16。当前三协议及新数据基准的验证是本地受控结果，没有新版真实模型或 SSH 部署验收；对应实现及清理证据见[原生协议本地归档](../../.ai-workspace/progress/history/2026-10-09-native-protocols-deployment-schema1-local-complete.md)。

真实 STATIC、AI 辅助和旧 Agent 结果分别见[测试文档](../test/multi-language/README.md)。本机通过、历史 STATIC 成功、旧辅助 6/8 及旧 Agent 0/8 各有版本和场景边界，不能相互替代或改写为当前新版成功。全软件助手与业务操作 API 仍是[独立后期规划](APP-ASSISTANT-DEVELOPMENT.md)，尚未排期或实施。

<a id="roadmap"></a>

## 2. 分期导航与承接

| 工程 | 保留内容 | 状态与阅读边界 |
| --- | --- | --- |
| [0.1](PHASE-0.1.md) | 基础源码、Ubuntu/systemd、AI 和桌面闭环 | 冻结旧工程的实现与验收记录 |
| [0.2](PHASE-0.2.md) | Git、配置/秘密、部署类型和多 Provider | 历史记录，非当前入口证明 |
| [0.3](PHASE-0.3.md) | 原生生态、高级语言和多组件 | 历史记录，保留当时环境与未覆盖范围 |
| [0.4](PHASE-0.4.md) | 自动部署、工具链、数据库、备份与维护 | 历史安全语义和验收依据；底层资源不等于备份 Mod 已交付 |
| [0.5](PHASE-0.5.md) | 旧回环 Web 服务台 | 源码在冻结历史中，不参与当前构建 |
| [0.6](PHASE-0.6.md) | 旧官网、认证、下载和维护规划 | 未实施的历史规划，不继续补齐旧账号方向 |
| [1.1](PHASE-1.1.md) | 平台化、Schema 所有权和旧源码归档 | 已完成范围及后置事项以该期记录为准 |
| [1.2](PHASE-1.2.md) | APP/API 归包、文件、救援、语音和开发库 | 保留实施与验收；现行规则使用主题文档 |
| [1.3](PHASE-1.3.md) | 窗口、ModDev、Git、部署、文件管理和商店 | P0、S0～S6 已完成适用本机验收与收尾 |

缓存实验保留于 [审批缓存记录](APPROVAL-CACHE-VALIDATION.md)，类名变更映射保留于 [历史映射](CLASS-RENAMES.md)。二者用于追溯，不另立一份现行规范。

<a id="phase-numbering"></a>
### 工程编号对应表

| 历史称呼/文件 | 现行工程名称 | 现行文件 |
| --- | --- | --- |
| 一期 / `PHASE-1.md` | 0.1 期工程 | [PHASE-0.1.md](PHASE-0.1.md) |
| 二期 / `PHASE-2.md` | 0.2 期工程 | [PHASE-0.2.md](PHASE-0.2.md) |
| 三期 / `PHASE-3.md` | 0.3 期工程 | [PHASE-0.3.md](PHASE-0.3.md) |
| 四期 / `PHASE-4.md` | 0.4 期工程 | [PHASE-0.4.md](PHASE-0.4.md) |
| 五期 / `PHASE-5.md` | 0.5 期工程 | [PHASE-0.5.md](PHASE-0.5.md) |
| 六期 / `PHASE-6.md` | 0.6 期工程 | [PHASE-0.6.md](PHASE-0.6.md) |
| 平台化改造 | 1.1 期工程 | [PHASE-1.1.md](PHASE-1.1.md) |
| APP 后续整体调整 | 1.2 期工程 | [PHASE-1.2.md](PHASE-1.2.md) |
| 部署完整迁移、文件管理、Git 与可选依赖、ModDev/Mod 划分、单入口工作区及商店连同下载 | 1.3 期工程 | [PHASE-1.3.md](PHASE-1.3.md) |

旧文件名仅在本对应表解释历史名称，不再作为在用链接。历史日志、原始验收输出和旧进度记录保留原称呼、路径文本和验证状态，按本表追溯。工程编号不修改 Maven 软件版本、数据库 schema、helper 或归档协议版本。

后期承接已实现行为，不承接未落地承诺。旧工程的原因与证据保留；被替代的规划标明对应承接工程，不把规划改写为交付。1.1 的源码归档要求及结果保留在原文档，不因建立 1.2 重做归档。

<a id="modules"></a>

## 3. 0.x 历史模块演进索引

本表记录 0.x 工程，0.6 列为已被替代的历史规划，不是待开发清单。1.1 的迁移对应见[新工程迁移表](PHASE-1.1.md#migration)。模块名称使用当前职责。早期功能所在模块的现名不表示该模块在当时已独立拆出；无法证明拆分期次的不推断。表内“沿用”表示无新的已确认增量；“未引入”不要求创建空实现；Web 早期骨架只表示工程基线。

| 模块                      | 0.1 期                                                                     | 0.2 期                                                                | 0.3 期                                                                    | 0.4 期                                                                                      | 0.5 期                                                             | 0.6 期                                                                    |
| ------------------------- | ------------------------------------------------------------------------ | ------------------------------------------------------------------- | ----------------------------------------------------------------------- | ----------------------------------------------------------------------------------------- | ---------------------------------------------------------------- | ----------------------------------------------------------------------- |
| `shared/ai`               | [新增：单 Provider 结构化建议](PHASE-0.1.md#ai)                            | [增强：多 Provider 和类型化只读 Agent 工具](PHASE-0.2.md#ai)          | [增强：三个固定角色、最小上下文和冲突裁决](PHASE-0.3.md#ai)               | [增强：缺项建议、上下文与响应读取界限](PHASE-0.4.md#ai)                                     | [复用](PHASE-0.5.md#api)                                           | 沿用                                                                    |
| `shared/standard/analyze` | [新增：Maven Spring Boot 识别与阻断条件](PHASE-0.1.md#source)              | [增强：六类项目、语言证据和显式运行时建议](PHASE-0.2.md#analysis)     | [增强：高级语言、原生构建、组件冲突及依赖分析](PHASE-0.3.md#components)   | [增强：全版本声明、数据库需求及安全排除事实](PHASE-0.4.md#database)                         | [复用](PHASE-0.5.md#api)                                           | 沿用                                                                    |
| `shared/backup`           | 未引入                                                                   | 未引入                                                              | 未引入                                                                  | [新增：schema v6 归档、一致性、秘密信封、恢复与迁移状态机](PHASE-0.4.md#backup)             | [复用](PHASE-0.5.md#api)                                           | 沿用                                                                    |
| `shared/config`           | 未引入                                                                   | [新增：不可变配置、秘密引用及发布绑定](PHASE-0.2.md#configuration)    | 沿用                                                                    | [增强：资源、数据库和运行事实的精确绑定](PHASE-0.4.md#backup)                               | [复用](PHASE-0.5.md#api)                                           | 沿用                                                                    |
| `shared/deploy`           | [新增：计划、短停机事务、健康与失败恢复](PHASE-0.1.md#deployment)          | [增强：更多项目与容器适配，回滚旧运行参数](PHASE-0.2.md#deployment)   | [增强：整应用构建/切换/健康/回滚与依赖生命周期](PHASE-0.3.md#transaction) | [增强：动态工具链准备、身份约束、恢复事务及失败状态](PHASE-0.4.md#isolation)                | [复用](PHASE-0.5.md#api)                                           | 沿用                                                                    |
| `shared/git`              | 未引入                                                                   | [新增：无凭据 URL、固定 Commit、受限 Git 快照](PHASE-0.2.md#git)      | 沿用                                                                    | [修复：中断传播、子进程及临时工作区收尾](PHASE-0.4.md#security)                             | [复用](PHASE-0.5.md#api)                                           | 沿用                                                                    |
| `shared/linux`            | [新增：SSH、能力、构建发布与生命周期窄契约](PHASE-0.1.md#deployment)       | [增强：运行类型、容器与更多目标能力](PHASE-0.2.md#linux)              | [增强：多组件事务与高级运行类型契约](PHASE-0.3.md#transaction)            | [结构调整：仅依赖 model，备份/恢复端口与原生 DB 失败契约](PHASE-0.4.md#architecture)        | [复用](PHASE-0.5.md#api)                                           | 沿用                                                                    |
| `shared/linux-sshd`       | [新增：Ubuntu、SFTP、受控 helper 与 systemd 实现](PHASE-0.1.md#deployment) | [增强：语言构建、Docker/Podman 与发行版准备](PHASE-0.2.md#deployment) | [增强：原生构建 Renderer、更多发行版与运行协议](PHASE-0.3.md#ecosystem)   | [增强：工具链、低权限构建、动态运行、DB 与备份恢复协议](PHASE-0.4.md#isolation)             | [复用](PHASE-0.5.md#api)                                           | 沿用                                                                    |
| `shared/model`            | [新增：项目、健康、受管身份与生命周期契约](PHASE-0.1.md#source)            | [增强：扩展构建、运行时、语言事实与配置绑定](PHASE-0.2.md#analysis)   | [增强：精确构建架构、分级支持、组件图和角色事实](PHASE-0.3.md#ecosystem)  | [增强：工具链目录/绑定、运行身份、数据库与结构化失败模型](PHASE-0.4.md#toolchains)          | [复用](PHASE-0.5.md#api)                                           | 沿用                                                                    |
| `shared/source`           | [新增：可重复归档及路径、秘密排除边界](PHASE-0.1.md#source)                | [增强：Git 与本地共用来源摘要和排除规则](PHASE-0.2.md#git)            | 沿用                                                                    | [修复：秘密文件排除与有界快照输入](PHASE-0.4.md#security)                                   | [复用](PHASE-0.5.md#api)                                           | 沿用                                                                    |
| `app/db`                  | [新增：服务器与受管应用持久化](PHASE-0.1.md#lifecycle)                     | [增强：配置修订、秘密绑定和发布身份](PHASE-0.2.md#configuration)      | [增强：角色分配和成功整应用图持久化](PHASE-0.3.md#transaction)            | [增强：SQLite v16，运行时编码 v5、资源/健康/秘密/身份及桌面清单持久化](PHASE-0.4.md#backup) | 沿用                                                             | 沿用                                                                    |
| `app/main`                | [新增：CLASS/JAR/APP 启动装配](PHASE-0.1.md#desktop)                       | [增强：新增共享能力装配](PHASE-0.2.md#deployment)                     | [增强：生态注册与跨模块验收入口](PHASE-0.3.md#architecture)               | [修复：跨模块装配、结构门禁与验收连接收尾](PHASE-0.4.md#architecture)                       | 沿用                                                             | [规划：正式维护产品入口及外部执行器交接](PHASE-0.6.md#maintenance)        |
| `app/secret`              | [新增：SSH/AI 凭据存储](PHASE-0.1.md#desktop)                              | [增强：应用秘密的标识和修订](PHASE-0.2.md#configuration)              | 沿用                                                                    | [增强：精确修订解析及受管凭据删除；备份密码格式归 shared/backup](PHASE-0.4.md#maintenance)  | 沿用                                                             | 沿用                                                                    |
| `app/service`             | [新增：桌面用例编排与状态登记](PHASE-0.1.md#deployment)                    | [增强：Git、配置、容器和 AI 用例接线](PHASE-0.2.md#deployment)        | [增强：组件审阅、整应用部署及生命周期入口](PHASE-0.3.md#transaction)      | [增强：自动编排、数据库补全、完整备份恢复与离线迁移](PHASE-0.4.md#automatic)                | 沿用                                                             | [规划：正式维护产品入口及外部执行器交接](PHASE-0.6.md#maintenance)        |
| `app/ui`                  | [新增：Swing 输入、结果与受管操作](PHASE-0.1.md#desktop)                   | [增强：来源、运行时及配置审阅](PHASE-0.2.md#analysis)                 | [增强：组件图、运行时审阅和角色配置](PHASE-0.3.md#components)             | [增强：一键部署、高级侧栏、帮助、缺项表单与备份页面](PHASE-0.4.md#desktop)                  | 沿用                                                             | [规划：正式维护产品入口及外部执行器交接](PHASE-0.6.md#maintenance)        |
| `app/windows`             | [新增：本地源码准备和固定工作目录](PHASE-0.1.md#desktop)                   | 沿用                                                                | 沿用                                                                    | [增强：备份材料和无覆盖发布、更新卸载安全核心](PHASE-0.4.md#maintenance)                    | 沿用                                                             | [规划：生产更新/卸载执行器、打包及真实替换恢复](PHASE-0.6.md#maintenance) |
| `web/api`                 | 目标骨架，无业务                                                         | 目标骨架，无业务                                                    | 目标骨架，无业务                                                        | 目标骨架，无业务                                                                          | [同源业务接口、任务提交和 SSE](PHASE-0.5.md#api)                   | [规划：正式服务边界、发布下载接线；部署形态后定](PHASE-0.6.md#release)    |
| `web/auth`                | 目标骨架，无业务                                                         | 目标骨架，无业务                                                    | 目标骨架，无业务                                                        | 目标骨架，无业务                                                                          | [规划：本期不实现认证，生产身份功能后移 0.6 期](PHASE-0.5.md#runtime) | [规划：上线身份、会话与访问控制；具体方案后定](PHASE-0.6.md#security)     |
| `web/db`                  | 目标骨架，无业务                                                         | 目标骨架，无业务                                                    | 目标骨架，无业务                                                        | 目标骨架，无业务                                                                          | [专用测试 SQLite、作用域关系与任务/配置修订](PHASE-0.5.md#tasks)   | [规划：生产数据、凭据和制品管理分工待实施前确认](PHASE-0.6.md#security)   |
| `web/file`                | 目标骨架，无业务                                                         | 目标骨架，无业务                                                    | 目标骨架，无业务                                                        | 目标骨架，无业务                                                                          | [受限上传和只读源码工作区](PHASE-0.5.md#source)                    | [规划：生产数据、凭据和制品管理分工待实施前确认](PHASE-0.6.md#security)   |
| `web/frontend`            | [工程骨架](PHASE-0.1.md#desktop)                                           | [工程骨架](PHASE-0.1.md#desktop)                                      | [工程骨架](PHASE-0.1.md#desktop)                                          | [工程骨架](PHASE-0.1.md#desktop)                                                            | [内部服务台页面及 HTTP/SSE 交互](PHASE-0.5.md#frontend)            | [规划：官网与正式服务入口](PHASE-0.6.md#website)                          |
| `web/main`                | 目标骨架，无业务                                                         | 目标骨架，无业务                                                    | 目标骨架，无业务                                                        | 目标骨架，无业务                                                                          | [回环启动、单实例与依赖装配](PHASE-0.5.md#runtime)                 | [规划：正式服务边界、发布下载接线；部署形态后定](PHASE-0.6.md#release)    |
| `web/secret`              | 目标骨架，无业务                                                         | 目标骨架，无业务                                                    | 目标骨架，无业务                                                        | 目标骨架，无业务                                                                          | [加密 SSH/AI/备份凭据；Git 只读 HTTPS](PHASE-0.5.md#secrets)       | [规划：生产数据、凭据和制品管理分工待实施前确认](PHASE-0.6.md#security)   |
| `web/service`             | 目标骨架，无业务                                                         | 目标骨架，无业务                                                    | 目标骨架，无业务                                                        | 目标骨架，无业务                                                                          | [Web 输入与 shared 用例组合](PHASE-0.5.md#api)                     | [规划：正式服务边界、发布下载接线；部署形态后定](PHASE-0.6.md#release)    |
| `web/task`                | 目标骨架，无业务                                                         | 目标骨架，无业务                                                    | 目标骨架，无业务                                                        | 目标骨架，无业务                                                                          | [任务持久化、事件、取消与重启重新验证](PHASE-0.5.md#tasks)         | 沿用                                                                    |

<a id="rules"></a>

## 4. 现行行为与模块边界入口

当前模块、包、依赖和资源归属见 [File.md](../reference/File.md)，实际生产文件见 [AllFile.md](../reference/AllFile.md)。旧 `shared`、Web 和 SQLite 平台实现只在历史索引中保留，不作为活动模块或兼容入口。

运行数据、安全、任务结果与 UNKNOWN、SSH 救援、三种部署模式、原生工具及完成门禁统一见 [Runtime.md](../reference/Runtime.md)。作者能力、权限和资源生命周期见 [Mod.md](../reference/Mod.md)。配置、交付布局和用户操作分别见 Config、APP 和产品手册，不在本总纲复制机制细则。

<a id="workflow"></a>

## 5. 开发流程与完成定义

先核对当前工作区、产品入口、所属合同及既有改动，再按授权范围实现最小完整闭环。任务结果、资源收尾和真实验证分开判定；实现缺口或必要检查未通过不能记为完成。

默认仅执行直接受影响的定点测试和必要编译、结构、格式或文档检查，测试命令显式限定范围。只有明确要求全量时才使用全项目或模块完整测试入口，历史全量命令不是默认要求。失败后只重跑失败组及直接受影响的检查，完整规则见 [AGENTS.md](../../AGENTS.md)。

分期记录保留目标、关键决定、最终成果、适用验收、失败结论和未完成事项；已完成阶段不重新执行。临时进度只用于续接，正式结论须写入所属文档并引用稳定证据。交付前停止本任务启动的验证进程并清理临时文件，不终止用户或无关进程。

<a id="documentation"></a>

<a id="card-inventory"></a>

### 5.1 卡片清单规范

统一正文已整理到 [UI 设计与交互规范](../reference/UI.md)：[卡片选择、拖动与滚动](../reference/UI.md#card-inventory)、[排序草稿与保存反馈](../reference/UI.md#card-order)、[服务器与 AI 页面实例](../reference/UI.md#page-examples)。本节及原 `card-inventory` 锚点保留为入口，不再重复维护规则。

相关字段、离线维护和模型任务合同的入口见 [UI 规范：复用入口](../reference/UI.md#implementation)，业务细则仍由字段规范、运行约定及产品说明书维护。

### 5.2 Swing 字体规范

字体族、字号层级、缩放、卡片尺寸及按钮对齐统一见 [UI 规范：字体、尺寸与对齐](../reference/UI.md#typography)。本节标题保留为旧链接入口；浅／深主题的中性灰阶及控件映射见[颜色规范](../reference/UI.md#colors)。

## 6. 文档维护与冲突处理

同一长期规则只维护一份正文，其他文档用链接引用。主题文档按当前功能组织；日期、版本和提交只作为必要证据属性，不在首尾追加执行流水。冲突须沿产品入口核对代码、清单、资源和证据后修正文档，不倒改代码以迎合旧说明。

| 文档 | 唯一维护的内容 |
| --- | --- |
| [File.md](../reference/File.md) | 源码与文件结构、命名、依赖、资源归属及数据库字段列序；建议示例与实际结构明确区分 |
| [AllFile.md](../reference/AllFile.md) | 当前实际非测试生产文件索引，不收录计划文件、生成物或冻结源码 |
| [UI.md](../reference/UI.md) | 通用视觉与交互、颜色标准和实现差异、字体与尺寸、卡片规则及服务器／AI 实例 |
| [Runtime.md](../reference/Runtime.md) | 当前运行数据、安全、业务行为、原生模型与 Agent 工具门禁、资源加载规则 |
| [Mod.md](../reference/Mod.md) | 公开 API、作者开发、包格式、加载、生命周期、Schema 所有权及迁移合同 |
| [Config.md](../reference/Config.md) | 配置资源位置、字段、默认值与填写方式 |
| [APP.md](../reference/APP.md) | APP/JAR/SDK 交付物、发行布局、维护和官网服务范围 |
| [PRODUCT-MANUAL.md](../reference/PRODUCT-MANUAL.md) | 用户操作、输入和反馈；机制细则引用 Runtime |
| 本总纲和分期文档 | 总纲维护状态与导航；各期保留自身范围、关键决定、实施及验收 |
| [APP 智能助手规划](APP-ASSISTANT-DEVELOPMENT.md) | 独立后期需求、候选方案和待定边界，不作为已实现 API |
| 测试说明与验证记录 | 操作说明、场景合同和按版本/环境限定的实际结果；保留原失败与原始证据入口 |

整理时先确保唯一正文覆盖全部有效内容，再移除重复专题、临时步骤和无用流水，并同步所有链接、锚点与章节引用。结构检查依赖的 `current-source-tree` 和 `module-dependencies` 锚点必须唯一且保留。

历史记录不得用新版本名称或当前通过结果倒推改写；不同批次不相加，明确区分本机、受控接口和真实环境。冻结 `history-src`、原始日志和运行时提示词不参与普通文档清理。仍有效的待办保留在所属期或独立后期专题，正文不混入任务收尾提醒。

<a id="references"></a>

## 7. 参考资料


- Java 21 `jpackage`：<https://docs.oracle.com/en/java/javase/21/docs/specs/man/jpackage.html>
- Spring Boot：<https://docs.spring.io/spring-boot/>
- Spring Security CSRF：<https://docs.spring.io/spring-security/reference/features/exploits/csrf.html>
- Docker 重启策略：<https://docs.docker.com/engine/containers/start-containers-automatically/>
- Podman Quadlet：<https://docs.podman.io/en/latest/markdown/podman-quadlet-basic-usage.7.html>
- Ubuntu 生命周期：<https://ubuntu.com/about/release-cycle>
- Debian 13 发布与生命周期：<https://www.debian.org/releases/trixie/>
- CentOS Stream：<https://www.centos.org/centos10/>
- x86-64 psABI 微架构级别：<https://gitlab.com/x86-psABIs/x86-64-ABI/-/blob/master/x86-64-ABI/low-level-sys-info.tex>
- Rocky Linux 版本指南：<https://wiki.rockylinux.org/rocky/version/>
- AlmaLinux 发布说明：<https://wiki.almalinux.org/release-notes/>
- AlmaLinux 10.2 x86-64-v2：<https://wiki.almalinux.org/release-notes/10.2>
- Oracle Linux 10 更新模型：<https://docs.oracle.com/en/operating-systems/oracle-linux/10/>
- Oracle Linux 10 系统要求：<https://docs.oracle.com/en/operating-systems/oracle-linux/10/install/install-SystemRequirements.html>

历史 FHS/SQLite 与旧协议验收见 [0.4 期记录](PHASE-0.4.md#fhs-sqlite)；当前路径和协议以 [Runtime.md](../reference/Runtime.md)及活动资源为准。
