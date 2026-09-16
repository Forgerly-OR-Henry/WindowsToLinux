# WindowsToLinux 开发总纲

<a id="navigation"></a>

## 文档信息与导航

- 文档结构版本：`3.0.0-functional-phases`；整理日期：2026-09-10。
- 职责：产品边界、六期路线、模块演进索引、通用开发规则和完成定义。
- 依据：现行功能以当前工作区代码和适用实际行为为准；历史验收只证明其对应版本、环境和夹具。
- 开发目录只维护本总纲与六期主文档。功能细则、追加开发、验收方法和结果均进入所属期数。

- [1. 产品定位与现行状态](#status)
- [2. 六期路线与导航](#roadmap)
- [3. 模块演进索引](#modules)
- [4. 通用行为与模块边界](#rules)
- [5. 开发流程与完成定义](#workflow)
- [6. 文档维护与冲突处理](#documentation)
- [7. 现行实现校准记录](#alignment)
- [8. 参考资料](#references)
- [9. 功能演进与历史版本记录](#history)

<a id="status"></a>

## 1. 产品定位与现行状态

WindowsToLinux 是面向个人和小型自托管场景的部署管理工具。用户选择源码或 Git 版本并提供目标 Linux 服务器后，软件负责确定性分析、连接检查、目标机环境准备、源码传输、目标机构建、受控发布、健康检查和失败恢复。部署成功的内容进入“已部署应用”，可查看远端实际状态并执行启动、停止、重启、启用自启和停用自启。

产品不提供通用 SSH 终端、任意命令执行或官方托管 SaaS。部署、更新、回滚、备份与开机自启仍要求 WindowsToLinux 受管契约和资源归属验证。经扫描并由用户选择接管的外部 systemd 服务或 Docker 容器只开放状态、访问入口、启动、停止、重启；每次操作复核服务器及实际运行目标身份，保留原配置。

| 范围 | 当前代码与入口 | 验证边界 |
| --- | --- | --- |
| 工程与桌面 | Java 21、28-POM；Swing 自动部署、手工 Reviewed、多组件、生命周期及备份页面已接线 | 代码核对不等于本次重跑全部产品测试 |
| 源码与 AI | 本地/Git 快照、确定性分析、结构化建议及缺项表单 | 不执行用户项目代码；私有 Git 凭据和完整真实 AI 组合未接通或未验证 |
| 部署与身份 | 目标 Linux 构建；root 管理、临时构建身份、独立运行身份；helper v7 | 代表性 Ubuntu 24 部署有既有记录；其他系统和完整攻击/故障矩阵不能外推 |
| 工具链 | `ToolchainSupportCatalog` 修订 `2026-09-09.1`，按需求选择、准备和固定精确版本 | 允许分支不等于每个补丁、框架和环境都实测 |
| 持久化与归档 | SQLite v15、运行时及备份激活配置 v3、manifest schema v4 | v13–v15 增加服务器摘要、外部接管和 AI 优先级；旧版本有明确读取及缺失语义，不把当前版本倒写到历史证据 |
| 备份恢复迁移 | 完整桌面用例已接入共享归档及 Linux 端口 | 实际数据库备份恢复及双服务器迁移仍为 RUNTIME-PENDING |
| 数据库管理 | 原生 PostgreSQL/MySQL/MariaDB/Redis 需求、认证、审批与准备链路 | 管理范围不等于一致性备份范围；SQLite 完整远端备份、多数据库归档仍受限 |
| Web | Java 目标模块仅有 POM，Vue 前端只有工程骨架 | 五期业务未实现，六期生产方向未开始 |
| 正式维护 | 四期具备更新/卸载安全核心 | 官网、官方下载安装与生产维护执行器属于六期，当前不能称为可用 |

实际功能和证据以各期对应章节为准：[四期验收矩阵](PHASE-4.md#acceptance-matrix)。历史 helper v5 的 17 条运行路径与 helper v7 的 25 种源码代表组合分开保存，不相互替代。

<a id="roadmap"></a>

## 2. 六期路线与导航

| 期数 | 本期主要增量 | 承接与后续 |
| --- | --- | --- |
| [一期](PHASE-1.md) | 本地源码、Maven Spring Boot、Ubuntu/systemd、基础 AI、受管生命周期、桌面最小闭环 | 二期扩展来源和部署类型 |
| [二期](PHASE-2.md) | Git、配置/秘密、Java/Node/Python/静态/容器适配、Linux 扩展、多 Provider | 三期处理高级语言与整应用编排 |
| [三期](PHASE-3.md) | 原生生态构建、高级语言、混合组件、整应用事务、多模型、职责分包 | 四期扩展自动化、工具链、数据保护和身份 |
| [四期](PHASE-4.md) | 自动部署、AI 补全、DB、动态工具链、身份隔离、职责修正、备份恢复迁移及维护安全核心 | Web 归五期；生产网站和维护执行器归六期 |
| [五期](PHASE-5.md) | 规划内部回环 Web 服务台、API/SSE、任务、上传、业务复用 | 不含生产认证、官网和正式发布 |
| [六期](PHASE-6.md) | 规划官网、生产认证、正式发布下载与 Windows 生产维护 | 详细方案在实际实施前确认 |

后期继承前期已经实现的行为，不继承未落地承诺。当前代码出现后期增强时，在首次引入功能的章节链接说明，但不能改写其历史完成时间。阶段划分表达开发增量，当前统一代码只有一套有效执行链。

<a id="modules"></a>

## 3. 模块演进索引

模块名称使用当前职责。早期功能所在模块的现名不表示该模块在当时已独立拆出；无法证明拆分期次的不推断。表内“沿用”表示无新的已确认增量；“未引入”不要求创建空实现；Web 早期骨架只表示工程基线。

| 模块 | 一期 | 二期 | 三期 | 四期 | 五期 | 六期 |
| --- | --- | --- | --- | --- | --- | --- |
| `shared/ai` | [新增：单 Provider 结构化建议](PHASE-1.md#ai) | [增强：多 Provider 和类型化只读 Agent 工具](PHASE-2.md#ai) | [增强：三个固定角色、最小上下文和冲突裁决](PHASE-3.md#ai) | [增强：缺项建议、上下文与响应读取界限](PHASE-4.md#ai) | [规划复用](PHASE-5.md#api) | 沿用 |
| `shared/analyze` | [新增：Maven Spring Boot 识别与阻断条件](PHASE-1.md#source) | [增强：六类项目、语言证据和显式运行时建议](PHASE-2.md#analysis) | [增强：高级语言、原生构建、组件冲突及依赖分析](PHASE-3.md#components) | [增强：全版本声明、数据库需求及安全排除事实](PHASE-4.md#database) | [规划复用](PHASE-5.md#api) | 沿用 |
| `shared/backup` | 未引入 | 未引入 | 未引入 | [新增：schema v4 归档、一致性、秘密信封、恢复与迁移状态机](PHASE-4.md#backup) | [规划复用](PHASE-5.md#api) | 沿用 |
| `shared/config` | 未引入 | [新增：不可变配置、秘密引用及发布绑定](PHASE-2.md#configuration) | 沿用 | [增强：资源、数据库和运行事实的精确绑定](PHASE-4.md#backup) | [规划复用](PHASE-5.md#api) | 沿用 |
| `shared/deploy` | [新增：计划、短停机事务、健康与失败恢复](PHASE-1.md#deployment) | [增强：更多项目与容器适配，回滚旧运行参数](PHASE-2.md#deployment) | [增强：整应用构建/切换/健康/回滚与依赖生命周期](PHASE-3.md#transaction) | [增强：动态工具链准备、身份约束、恢复事务及失败状态](PHASE-4.md#isolation) | [规划复用](PHASE-5.md#api) | 沿用 |
| `shared/git` | 未引入 | [新增：无凭据 URL、固定 Commit、受限 Git 快照](PHASE-2.md#git) | 沿用 | [修复：中断传播、子进程及临时工作区收尾](PHASE-4.md#security) | [规划复用](PHASE-5.md#api) | 沿用 |
| `shared/linux` | [新增：SSH、能力、构建发布与生命周期窄契约](PHASE-1.md#deployment) | [增强：运行类型、容器与更多目标能力](PHASE-2.md#linux) | [增强：多组件事务与高级运行类型契约](PHASE-3.md#transaction) | [结构调整：仅依赖 model，备份/恢复端口与原生 DB 失败契约](PHASE-4.md#architecture) | [规划复用](PHASE-5.md#api) | 沿用 |
| `shared/linux-sshd` | [新增：Ubuntu、SFTP、受控 helper 与 systemd 实现](PHASE-1.md#deployment) | [增强：语言构建、Docker/Podman 与发行版准备](PHASE-2.md#deployment) | [增强：原生构建 Renderer、更多发行版与运行协议](PHASE-3.md#ecosystem) | [增强：工具链、低权限构建、动态运行、DB 与备份恢复协议](PHASE-4.md#isolation) | [规划复用](PHASE-5.md#api) | 沿用 |
| `shared/model` | [新增：项目、健康、受管身份与生命周期契约](PHASE-1.md#source) | [增强：扩展构建、运行时、语言事实与配置绑定](PHASE-2.md#analysis) | [增强：精确构建架构、分级支持、组件图和角色事实](PHASE-3.md#ecosystem) | [增强：工具链目录/绑定、运行身份、数据库与结构化失败模型](PHASE-4.md#toolchains) | [规划复用](PHASE-5.md#api) | 沿用 |
| `shared/source` | [新增：可重复归档及路径、秘密排除边界](PHASE-1.md#source) | [增强：Git 与本地共用来源摘要和排除规则](PHASE-2.md#git) | 沿用 | [修复：秘密文件排除与有界快照输入](PHASE-4.md#security) | [规划复用](PHASE-5.md#api) | 沿用 |
| `app/db` | [新增：服务器与受管应用持久化](PHASE-1.md#lifecycle) | [增强：配置修订、秘密绑定和发布身份](PHASE-2.md#configuration) | [增强：角色分配和成功整应用图持久化](PHASE-3.md#transaction) | [增强：SQLite v15，运行时编码 v3、资源/健康/秘密/身份及桌面清单持久化](PHASE-4.md#backup) | 沿用 | 沿用 |
| `app/main` | [新增：CLASS/JAR/APP 启动装配](PHASE-1.md#desktop) | [增强：新增共享能力装配](PHASE-2.md#deployment) | [增强：生态注册与跨模块验收入口](PHASE-3.md#architecture) | [修复：跨模块装配、结构门禁与验收连接收尾](PHASE-4.md#architecture) | 沿用 | [规划：正式维护产品入口及外部执行器交接](PHASE-6.md#maintenance) |
| `app/secret` | [新增：SSH/AI 凭据存储](PHASE-1.md#desktop) | [增强：应用秘密的标识和修订](PHASE-2.md#configuration) | 沿用 | [增强：备份加密、精确修订解析及受管凭据删除](PHASE-4.md#maintenance) | 沿用 | 沿用 |
| `app/service` | [新增：桌面用例编排与状态登记](PHASE-1.md#deployment) | [增强：Git、配置、容器和 AI 用例接线](PHASE-2.md#deployment) | [增强：组件审阅、整应用部署及生命周期入口](PHASE-3.md#transaction) | [增强：自动编排、数据库补全、完整备份恢复与离线迁移](PHASE-4.md#automatic) | 沿用 | [规划：正式维护产品入口及外部执行器交接](PHASE-6.md#maintenance) |
| `app/ui` | [新增：Swing 输入、结果与受管操作](PHASE-1.md#desktop) | [增强：来源、运行时及配置审阅](PHASE-2.md#analysis) | [增强：组件图、运行时审阅和角色配置](PHASE-3.md#components) | [增强：一键部署、高级侧栏、帮助、缺项表单与备份页面](PHASE-4.md#desktop) | 沿用 | [规划：正式维护产品入口及外部执行器交接](PHASE-6.md#maintenance) |
| `app/windows` | [新增：本地源码准备和固定工作目录](PHASE-1.md#desktop) | 沿用 | 沿用 | [增强：备份材料和无覆盖发布、更新卸载安全核心](PHASE-4.md#maintenance) | 沿用 | [规划：生产更新/卸载执行器、打包及真实替换恢复](PHASE-6.md#maintenance) |
| `web/api` | 目标骨架，无业务 | 目标骨架，无业务 | 目标骨架，无业务 | 目标骨架，无业务 | [规划：同源业务接口、任务提交和 SSE](PHASE-5.md#api) | [规划：正式服务边界、发布下载接线；部署形态后定](PHASE-6.md#release) |
| `web/auth` | 目标骨架，无业务 | 目标骨架，无业务 | 目标骨架，无业务 | 目标骨架，无业务 | [规划：本期不实现认证，生产身份功能后移六期](PHASE-5.md#runtime) | [规划：上线身份、会话与访问控制；具体方案后定](PHASE-6.md#security) |
| `web/db` | 目标骨架，无业务 | 目标骨架，无业务 | 目标骨架，无业务 | 目标骨架，无业务 | [规划：专用测试 SQLite 与任务/配置记录](PHASE-5.md#tasks) | [规划：生产数据、凭据和制品管理分工待实施前确认](PHASE-6.md#security) |
| `web/file` | 目标骨架，无业务 | 目标骨架，无业务 | 目标骨架，无业务 | 目标骨架，无业务 | [规划：受限上传和只读源码工作区](PHASE-5.md#source) | [规划：生产数据、凭据和制品管理分工待实施前确认](PHASE-6.md#security) |
| `web/frontend` | [工程骨架](PHASE-1.md#desktop) | [工程骨架](PHASE-1.md#desktop) | [工程骨架](PHASE-1.md#desktop) | [工程骨架](PHASE-1.md#desktop) | [规划：内部服务台页面及 HTTP/SSE 交互](PHASE-5.md#frontend) | [规划：官网与正式服务入口](PHASE-6.md#website) |
| `web/main` | 目标骨架，无业务 | 目标骨架，无业务 | 目标骨架，无业务 | 目标骨架，无业务 | [规划：回环启动、单实例与依赖装配](PHASE-5.md#runtime) | [规划：正式服务边界、发布下载接线；部署形态后定](PHASE-6.md#release) |
| `web/secret` | 目标骨架，无业务 | 目标骨架，无业务 | 目标骨架，无业务 | 目标骨架，无业务 | [规划：测试 SSH/Git/AI/备份凭据](PHASE-5.md#secrets) | [规划：生产数据、凭据和制品管理分工待实施前确认](PHASE-6.md#security) |
| `web/service` | 目标骨架，无业务 | 目标骨架，无业务 | 目标骨架，无业务 | 目标骨架，无业务 | [规划：Web 输入与 shared 用例组合](PHASE-5.md#api) | [规划：正式服务边界、发布下载接线；部署形态后定](PHASE-6.md#release) |
| `web/task` | 目标骨架，无业务 | 目标骨架，无业务 | 目标骨架，无业务 | 目标骨架，无业务 | [规划：任务持久化、事件、取消与恢复](PHASE-5.md#tasks) | 沿用 |

<a id="rules"></a>

## 4. 通用行为与模块边界

### 确定性分析与 AI

- 优先读取构建文件、锁文件、部署文件、CI 和官方项目配置，再读取文档和示例；记录事实来源、冲突、置信度和缺失项。
- AI 是可选分析补充，不是部署授权。未配置 AI 时，正式支持范围内的确定性流程必须可用。
- AI 不接触 SSH、Git、数据库或 API 原始凭据，不获得任意 Shell，不得绕过类型、路径、命令、权限、影响范围和资源归属校验。
- AI 输出必须转成结构化建议，经确定性校验和必要用户确认后才能进入计划。
- 运行时共用启用模型顺序；角色保留独立上下文、提示和结果校验。只有连接、HTTP 或校验失败才切换模型；有效拒绝及风险判断直接返回，取消终止整条链。

### 用户项目构建边界

- 桌面和 Web 可以读取用户主动提供的源码快照做静态分析，但不得在平台主机执行其中的脚本、插件、依赖安装或构建。
- 所有构建在目标 Linux 的受管工作目录完成，使用阶段允许的固定构建入口，并绑定目标服务器、项目标识和源码摘要/Commit。
- 新部署要求 root 登录管理；项目代码只能在临时低权限身份中构建，取消 root 构建选项和永久项目 sudoers 授权。普通构建使用 DynamicUser；容器后端仅在临时账号的 UID/GID 映射启动阶段保留必要权限，项目入口禁止提权。
- Maven 插件、Wrapper 和项目脚本按不可信代码处理。上传前预留固定容量 ext4 卷；root 控制器独立实施时间、累计输出和进程组限制，systemd 控制内存及任务数，目录、缓存和镜像层均不得突破卷预算。旧 root 构建记录仅适用于当时版本。
- 产品不提供任意命令输入框或浏览器 SSH 终端。固定命令白名单约束的是产品执行器，不能证明不可信项目代码安全。

### 发布与回滚

单组件统一使用短停机发布事务：

```text
准备源码和候选目录
→ 在目标机完成构建及静态产物校验
→ 保存当前受管版本、服务定义和自启状态
→ 停止当前版本并确认停止
→ 切换受管指针/服务定义并启动候选
→ 执行分层健康检查
→ 成功后提交当前版本；失败则恢复旧指针和旧服务并重新验证
```

- 第一次部署没有旧版本，失败时清理本次受管候选并保留证据。
- 回滚失败必须进入“需要人工处理”，同时保留新旧版本、服务状态和诊断证据；不得宣称已恢复。
- 不承诺固定端口应用的新旧实例并行、零停机或通用原子切换。需要代理、滚动更新或集群能力时另立范围。
- 当前成功版本不得作为构建目录或被直接覆盖。生命周期动作可以改变其运行/自启状态，但不能修改其文件内容。

### 健康检查

健康检查按以下顺序选择：

1. 项目明确提供 HTTP 健康端点时，校验协议、地址、状态码、超时和响应约束。
2. 没有端点时，由用户确认监听端口，同时验证 TCP、服务主进程归属和稳定观察窗口。
3. 只有进程存在、只有端口开放或只有 systemd 显示 active，均不足以独立判定发布成功。

启动和重启必须检查健康；停止必须确认停止；修改自启后必须重新查询远端自启事实。

### 受管生命周期

- 受管生命周期继续验证原归属；外部接管仅允许绑定精确 systemd 定义摘要或 Docker 容器 ID 的状态、启动、停止、重启。身份变化要求重新扫描，外部应用不具备受管自启、更新、回滚和备份能力。
- 启动和停止不改变自启；启用或停用自启不改变当前运行状态；重启保留自启设置。
- 数据库中保存的是最后观测值，目标服务器实时查询结果才是当前事实。
- 资源缺失、标识不符、外部修改、连接失败或结果无法复核时返回未知/异常，不猜测执行、不自动重建、不标记成功。
- 不提供远程删除、通用日志控制台、批量生命周期或任意服务/容器管理能力，除非后续另行确认范围。

### 安全、秘密与恢复

- SSH 首次连接固定主机指纹；指纹变化必须停止并重新确认。
- 平台凭据由 `app/secret` 或 `web/secret` 管理，日志、事件、错误、AI 上下文和 HTTP 响应必须脱敏。
- 路径操作使用规范化后的受管根目录和不可伪造的归属标记；清理、覆盖或替换前再次校验目标。
- 取消、超时、断连和进程中断都进入明确状态；不能把“命令已发送”当成“操作成功”。
- CentOS 系统准备通过独立且默认拒绝的确认框批准 SELinux 配置与重启，按服务器、启动和配置摘要校验后由产品执行；重连固定原主机指纹，完成标签与新 SSH 登录验证后才持久化 Enforcing。普通安装批准不扩大为系统变更授权，失败保留恢复状态。实现与当前实机进度见[四期验收](PHASE-4.md#acceptance)。
- 检查和备份散列只能证明内容是否变化，不能单独证明备份来自可信方。

现行结构与职责：共享 `source` 负责源码快照，`backup` 负责应用数据归档，两者不混用；`linux` 只依赖 `model`，配置通过 deploy 的类型化投影进入远端契约。服务用例和 UI 仅依赖 Linux 窄契约，具体 SSHD 由启动模块装配。正式结构描述以实际代码校准后的[File.md](../File.md)维护，完整文件索引见[AllFile.md](../AllFile.md)。

<a id="workflow"></a>

## 5. 开发流程与完成定义

### 开始条件

每个纵向功能开始前必须确认：所属阶段、输入与输出、支持矩阵、模块责任、权限影响、失败状态、回滚策略和验收环境。若需要新增模块，先按 [File.md](../File.md) 的治理流程与用户讨论，不得先创建再补文档。

### 实施顺序

1. **冻结需求**：把用户要求转成可检查验收项，明确非目标和风险决定。
2. **冻结边界**：确认模块依赖、模型/接口契约、受管路径、命令模板和状态机；同步所属期数的功能说明、模块增量和验收条件。
3. **实现纵向切片**：按“源码准备→确定性分析→部署决策与编排→Linux 公共契约与具体实现→平台服务→界面/API”贯通一个真实用例，不堆积无调用入口的横向占位代码。
4. **集中验证**：完成较大纵向切片后统一运行单元、模块集成、静态检查、前端和真实 Linux 验收；修复后只重跑受影响范围和必要总门禁。
5. **失败演练**：覆盖断连、超时、构建失败、健康失败、回滚失败、状态漂移和权限不足。
6. **更新状态**：文档和进度必须区分规划、开发中、静态已验证、真实环境待验证、已验收。

### 质量门禁

- Java：使用 JDK 21；代码交付的整体门禁执行完整 Maven reactor `verify`，纯文档整理按影响执行文档和相关架构定向检查，禁止循环依赖和未声明的反向依赖。
- 前端：锁定依赖，执行类型检查、单元测试、生产构建和关键 Playwright 流程。
- 文档：本地链接、代码围栏、版本、阶段导航、状态和交叉引用一致。
- 安全：所有外部输入按不可信处理；路径、归档、命令、凭据、日志、AI 和主机指纹具有负向测试。
- Linux：只有真实发行版/版本/架构上的构建、启动、健康、回滚和生命周期证据才能把适配器升级为正式支持。

Windows 开发机使用系统工具执行门禁：

```powershell
# 先确认 Maven 确实运行在 JDK 21
mvn.cmd -version
mvn.cmd verify

Set-Location src/web/frontend

# 首次锁定依赖；package-lock.json 生成后必须保留
npm.cmd install

# 后续可重复门禁
npm.cmd ci
npm.cmd run typecheck
npm.cmd run test
npm.cmd run build
npm.cmd run install:e2e-browser
npm.cmd run test:e2e
```

Maven 同步和 `verify` 使用系统 Maven 本地仓库。依赖尚未同步时允许联网补齐；离线验证只有在插件及其传递依赖已经完整缓存后才具有意义。

Playwright 浏览器固定保存在 `src/web/frontend/.playwright-browsers`，不写入用户目录，也不纳入版本控制。浏览器版本变化后重新执行 `npm.cmd run install:e2e-browser`。

### 完成定义

功能只有同时满足代码完成、自动化门禁通过、失败路径验证、文档同步和必要真实环境验收，才能标记完成。缺少真实 Linux、systemd、容器、数据库或迁移证据时，必须标记“待运行环境验证”，不能以 Windows 编译或模拟结果代替。

<a id="documentation"></a>

## 6. 文档维护与冲突处理

1. 只维护 `DEVELOPMENT.md` 和 `PHASE-1.md` 至 `PHASE-6.md`；追加开发与验收直接进入所属期数，不再创建独立补充或日期命名验收文档。
2. 每期使用目标与承接、模块增量、功能说明、实施顺序、验收、剩余边界的统一结构；跨模块功能完整说明一次，其他入口只链接。
3. 现行功能以当前代码和适用实际行为为准。文档冲突时沿产品入口核对代码、资源、配置及证据，修正文档；不得为了符合旧文档倒改代码或削弱门禁。
4. 分开记录当前实现、真实验证和历史分期。类型或类存在不等于产品入口可用，本地测试不证明 Linux、数据库或 UI 全流程。代码缺陷如实标明，本次整理不自动授权修复。
5. 规划中的五期、六期仍保留已确认需求。旧文档未落地设想不自动转成本期开发任务；变更发生期次不能确认时标为期次未核实。
6. 日期仅作为元信息或证据字段；正文按功能组织，修复后的规则直接写回对应段落，必要的旧规则和失败/复验按功能保留。
7. 当前状态、现行模块依赖与目录根据代码校准。结构文档保留治理职责，明确的未来节点继续标为 `[PLANNED]`；不创建空包、不把目标节点算作实现。
8. 新增或调整功能时先更新所属期数，再修改代码，最后更新实际结果；过程中的实现决定变化后须补正文，不能只在顶部追加变更流水。
9. 文档调整同步导航、产品说明、结构索引和本地锚点；历史测试计数、版本与运行范围不因合并重算。独有证据必须有正式文档去向。
10. `.ai-workspace` 只保存必要辅助工作和阶段进度；正式规则与交付证据不能只在那里。保留其他任务未完成状态，临时验证进程和文件交付前清理。

<a id="alignment"></a>

## 7. 现行实现校准记录

| 原文问题 | 当前代码或证据依据 | 整理结论 |
| --- | --- | --- |
| 一/二期仍把 root 直接构建确认作为现行准入 | `ReviewedDeploymentService` 拒绝 runAsRoot 和非 root 管理连接；工作卷和运行身份协议已接线 | 原规则仅留历史；现行规则归四期身份隔离 |
| 备份部分仍写配置 v2、SQLite v11、helper v5 | `BackupConfigurationCodec` 激活版本 3；运行时编码 3；schema migrator 12；helper 协议 7 | 现行正文按各自定义更正，历史批次不批量替换版本 |
| Git 需求清单被读成私有凭据、Submodule/LFS 已支持 | 快照执行器无产品凭据注入接口，feature policy 拒绝子模块/LFS/符号链接 | 明确实际输入和未接通边界；不承诺隔离全部宿主 Git 配置 |
| Compose 被归入三期，容易误读为三期可执行 | `ContainerDeploymentInspector` 遇到 Compose 文件直接拒绝 | 区分受管组件图与 Compose 自动执行，后者不宣称可用 |
| 旧 CentOS 写成完整兼容尝试 | `DistributionSupportEvaluator` 返回 LEGACY_RISK_CONFIRMATION_REQUIRED，准备目录只有六种具名实现 | 保留风险识别，不声称存在完整准备/部署链 |
| 三期 C/C++ 仍统一列为预览，或 Spring Boot 被笼统称正式支持 | 当前支持目录按项目类型和构建工具区分，CMake 和 Spring Boot 为试验适配 | 以精确目录等级表述，不用实机单例升级通用支持 |
| 总纲、说明书仍称身份方案无任何实机证据 | 四期已有最终 helper 的代表部署、容器及 Yarn 复验记录 | 写明已有精确范围，数据库迁移等未覆盖项继续保留 |
| 早期前端依赖升级仍写作未落地 | 当前 package.json 声明 Vite ^7.3.6、Vitest ^3.2.7 | 旧下载失败保留历史；本次未运行新的漏洞审计 |

核对入口和源码链接在对应功能章节提供。本次不连接服务器重跑验证；不确定的原始历史版本归属保留原记录并说明限制，不能仅以现行代码倒推。早期功能映射到现名模块属于职责回溯，不是模块创建时间的证明。

具体历史口径待核实：原四期 `2.25.0-managed-restore-migration` 版本记录在 2026-08-22 写作“配置文档 v3”，而相邻历史恢复正文使用 v2，后续身份调整记录才明确升级到 v3。本次确认当前编码器写 v3，但不能据此证明旧批次已使用该版本；原记录保留于四期历史表，旧批次编码版本与准确引入期次仍未核实。

### 文档整理验证

| 检查 | 结果与范围 |
| --- | --- |
| 文档归并 | 14 份收敛为总纲和六期主文档；170 个原主章节及 1 个跨期验收子节有迁移去向 |
| 代码与证据校准 | 核对产品门面、自动编排、Git、支持目录、发行版准入、身份、持久化与备份恢复入口；没有修改生产或测试代码 |
| 结构与模块依赖 | JDK 21 定向运行 DocumentStructureArchitectureTest、ModuleDependencyArchitectureTest、PackageStructureArchitectureTest，21 项全部通过，0 失败、错误或跳过 |
| 链接与历史证据 | 项目入口、docs 和样例说明的 442 处本地链接及锚点通过；33 个历史提交或制品摘要保留；目录、表格和代码围栏检查通过 |
| 修改边界 | 基线中的 2,071 个非文档维护文件内容未改；其他任务的原进度及证据保留 |
| 未执行 | 未重跑业务全量、前端、真实 SSH/Linux、数据库、迁移或 GUI 验收；历史测试计数不是本次结果 |

<a id="references"></a>

## 8. 参考资料

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

<a id="history"></a>

## 9. 功能演进与历史版本记录

以下按功能归组保留原版本、日期和阶段变化。各行仅表示当时的设计或验收范围；若旧记录无法证明变更期次或版本归属，不能用当前实现反推。

### 配置秘密与数据库

| 来源 / 版本 | 日期 | 历史变化与证据 |
| --- | --- | --- |
| DEV / 2.43.0-phase4-automatic-desktop | 2026-09-07 | 先落档四期补充，再完成 Swing 新手首页和高级帮助、自动单/多组件编排、AI 缺项与原生 DB 管理。本地 Maven 417 项通过、27 项条件跳过，Python 离线 11 项通过；真实 SSH/DB 与发布验收待配置测试环境。 |
