# WindowsToLinux 开发总纲

## 文档信息

- 项目名称：WindowsToLinux
- 文档角色：产品边界、五期路线、跨期规则与完整开发流程的唯一总入口
- 文档版本：`2.16.0-centos-stream-acceptance`
- 文档状态：**当前 Ubuntu 24.04 x86-64 与 CentOS Stream 9 x86-64 产品入口验收完成；其他发行版实机测试按当前范围延后**
- 更新日期：2026-08-14
- 项目结构：[File.md](File.md)

> 文档中的“支持”必须具有实现和验收证据。2026-08-10/12 已由产品入口在新装 Ubuntu 24.04 x86-64 上验证迁移前的一期 Maven/Spring Boot 以及二期 Gradle Spring Boot、普通 JAR、Node.js、Python、静态站点和 Dockerfile 容器链路，这些记录作为历史证据保留。2026-08-13 已由当前产品入口和 helper v3 验证六种高级语言试验适配器、两组件整应用事务、统一 Spring Boot Reviewed 链路及 Podman Quadlet；证据只覆盖验收夹具、Ubuntu 24.04 和 x86-64，不升级为未声明的框架或其他发行版支持。2026-08-14 的 CentOS Stream 9 x86-64 目标已由当前产品入口完成两次环境准备、两组件发布、故障候选整应用回滚、应用/数据库重启、生命周期与自启切换；SELinux 和防火墙态均在准备前后复核为未改变。该证据仅覆盖精确夹具，不外推到 Stream 10 或其他发行版。按当前范围，Debian/Rocky/Alma/Oracle 实机测试延后；备份、迁移与 Web 业务尚未实现。

## 1. 产品定位

WindowsToLinux 是面向个人和小型自托管场景的部署管理工具。用户选择源码或 Git 版本并提供目标 Linux 服务器后，软件负责确定性分析、连接检查、目标机环境准备、源码传输、目标机构建、受控发布、健康检查和失败恢复。部署成功的内容进入“已部署应用”，可查看远端实际状态并执行启动、停止、重启、启用自启和停用自启。

产品不是通用 SSH 终端、服务器面板、任意 systemd/容器管理器或官方托管 SaaS。所有修改只能作用于具有 WindowsToLinux 受管标识且能够验证资源归属的应用。

## 2. 当前工程状态

| 项目 | 当前状态 | 可以据此声称的结论 |
| --- | --- | --- |
| Maven | 当前 reactor 由根工程、3 个聚合模块和 24 个叶子模块组成，共 28 个 POM；二期复用既有叶子模块，没有新增 Maven 模块 | `File.md` 的正式目标模块结构保持不变；`backup` 和 Web Java 模块仍为 POM-only |
| Java | Java 21；shared 与桌面代码已按职责分包；Spring Boot 只保留 Reviewed 类型化分析和部署链路 | 迁移前六类路径保留历史实机证据；统一 Spring Boot Reviewed/helper v3 已在 Ubuntu 24.04 x86-64 完成当前产品入口验收 |
| Web 前端 | Vue 3、TypeScript、Vite、Vitest、Playwright 骨架 | 只可展示骨架页，尚无业务接口 |
| 桌面/Web 业务 | Swing 已提供单组件与多组件独立页面，以及十二类项目的类型选择、静态分析、类型化计划审阅、已保存凭据提交、整应用结果和依赖安全生命周期；Web 业务未实现 | 桌面入口统一使用 Reviewed API；试验适配器每次请求都需确认专用测试环境；Web 不可部署或管理应用 |
| Linux 运行验证 | 迁移前 Ubuntu 24.04 x86-64 已实际验证环境准备、六类二期构建发布及代表性生命周期；当前 helper v3 已验证六种高级语言、两组件整应用、统一 Spring Boot 与 Podman Quadlet；CentOS Stream 9 已由产品入口完成环境准备、发布、回滚和生命周期验收 | 证据仅适用于 Ubuntu 24.04 与 CentOS Stream 9 的精确 x86-64 夹具；其他发行版实机测试按当前范围延后 |
| 三期支持分级 | 支持等级、精确目标验证范围、不可执行识别预览及 Go/Rust/.NET/Kotlin/PHP/Ruby 固定试验适配器已接入；helper v3 不接受任意命令 | 六种语言在 Ubuntu 24.04 x86-64 上分别和联合完成构建、发布、回滚、生命周期、秘密脱敏与状态重启恢复；仍只称试验适配，不外推框架/发行版支持 |
| 三期混合项目与多组件 | 稳定组件清单、冲突/依赖环拦截、确定性依赖计划、整应用构建/快照/切换/健康/恢复事务、依赖安全生命周期及桌面产品入口已通过本地门禁和两组件实机验收；SQLite v7 原子保存成功图并支持重启后恢复 | Ubuntu 24.04 x86-64 已验证两组件发布、组件故障整应用回滚、图重载与生命周期；共享数据库迁移和跨服务器恢复不在三期范围 |
| 三期多模型协作 | 三个固定角色可独立绑定命名 Provider/模型；最小上下文、严格结构化输出、输入摘要证据和确定性优先冲突裁决已接入 SQLite v6、服务与桌面配置页 | AI 仅为建议；失败不跨 Provider 回退，冲突不得自动转成执行授权 |

### 2.1 正式目标架构与当前实现边界

[File.md](File.md) 是完整目标目录、模块职责、依赖方向、内部包结构和模块命名的唯一来源；本节只保留供开发流程使用的同步摘要，不复制完整目录树。

- `shared` 正式目标模块：`ai`、`analyze`、`backup`、`config`、`deploy`、`git`、`linux`、`linux-sshd`、`model`、`source`。
- `app` 正式目标模块：`db`、`main`、`secret`、`service`、`ui`、`windows`。
- `web` 正式目标模块：`api`、`auth`、`db`、`file`、`frontend`、`main`、`secret`、`service`、`task`；其中 `frontend` 是独立的 Vue 工程，不是 Java Maven 叶子模块。

关键边界固定为：

1. 平台无关的配置定义、校验、快照和差异规则归 `shared/config`；配置实例与历史版本归平台 `db`，敏感值归平台 `secret`。
2. 源码快照、可重复归档和安全校验归 `shared/source`；桌面本地入口、Web 上传工作区和 Git 仓库来源分别归 `app/windows`、`web/file` 和 `shared/git`。源码归档不与 `shared/backup` 的应用数据备份语义混用。
3. `shared/linux` 只定义公共契约，`shared/linux-sshd` 承接 Apache SSHD 具体实现；`deploy`、`app/service` 和 `web/service` 只依赖 `shared/linux`，只有 `app/main`、`web/main` 负责选择并装配 `shared/linux-sshd`。

`shared/source` 已承接源码快照、归档与安全校验，`shared/linux-sshd` 已承接 Apache SSHD、十二类项目的有界构建、发布/回滚协议、容器运行及 Ubuntu、Debian、CentOS Stream、Rocky Linux、AlmaLinux、Oracle Linux 的独立固定环境准备；Spring Boot 与六种高级语言试验适配器共用唯一 Reviewed/helper v3 路径，且高级语言命令由独立固定片段渲染。发行版探测保留包架构、累计 CPU、AppArmor/SELinux、防火墙和容器事实，自动准备不关闭既有安全机制。`shared/config` 定义类型化普通配置快照和不透明秘密引用；桌面 SQLite v7 保存配置实例、秘密修订元数据、发布身份摘要、命名 AI Provider/角色外键及成功整应用的不含秘密组件图，并从 v4 的旧发布列名无损迁移，原始秘密值仍只经 `app/secret` 短时处理。

开发 WindowsToLinux 本身使用开发机安装的系统 Maven 及其系统本地仓库，不由项目覆盖仓库位置，也不把 Maven Wrapper 作为本项目构建入口；同时使用 JDK 21、Node 和相应测试工具。产品处理的用户项目不得在 Windows 桌面主机或 Web 后端主机安装依赖、执行项目脚本或构建；用户项目构建只发生在目标 Linux，届时可按受控适配规则使用用户项目自带的 Wrapper。

## 3. 五期路线

| 阶段 | 细化文档 | 核心交付 | 明确后移 |
| --- | --- | --- | --- |
| 一期 | [PHASE-1.md](development/PHASE-1.md) | Swing 最小闭环；本地源码、Ubuntu 24.04、Maven Spring Boot 可执行 JAR、systemd、目标机构建、短停机发布、基础 AI、单组件生命周期 | Git、应用配置/密钥、数据库迁移、容器、多组件、备份、Web |
| 二期 | [PHASE-2.md](development/PHASE-2.md) | Git、配置快照与共享密钥、Gradle/普通 JAR、Node/Python/静态站点/Dockerfile、Docker/Podman、更多 Ubuntu/CentOS、多 API 与可选 Agent | 高级语言、多组件、多模型、数据库、备份迁移、Web |
| 三期 | [PHASE-3.md](development/PHASE-3.md) | 高级语言分级适配、多语言多组件编排、多模型协作、Debian/Rocky/Alma/Oracle | 数据库一致性、备份迁移、桌面升级、Web |
| 四期 | [PHASE-4.md](development/PHASE-4.md) | 数据库、版本化备份、恢复、离线一致迁移、桌面签名升级和卸载 | Web 访问与 Web 运维 |
| 五期 | [PHASE-5.md](development/PHASE-5.md) | 单实例单管理员 Web 版、REST/SSE、持久化任务、上传/认证安全、人工升级 | SaaS、多租户、集群、开放平台 |

阶段继承规则：

1. 后一期继承前一期已经实现并通过验收的行为，不继承尚未实现的文档承诺。
2. 新项目类型只有通过“分析、目标机构建、启动、健康检查、切换、失败恢复、生命周期”端到端验收后，才能列为正式支持。
3. 阶段新增范围不得反向扩大早期阶段；例如二期配置能力不能被写成一期能力。
4. 所有界面共享同一套 `shared` 规则，桌面与 Web 不得形成互相矛盾的部署语义。

## 4. 跨期行为基线

### 4.1 确定性分析与 AI

- 优先读取构建文件、锁文件、部署文件、CI 和官方项目配置，再读取文档和示例；记录事实来源、冲突、置信度和缺失项。
- AI 是可选分析补充，不是部署授权。未配置 AI 时，正式支持范围内的确定性流程必须可用。
- AI 不接触 SSH、Git、数据库或 API 原始凭据，不获得任意 Shell，不得绕过类型、路径、命令、权限、影响范围和资源归属校验。
- AI 输出必须转成结构化建议，经确定性校验和必要用户确认后才能进入计划。

### 4.2 用户项目构建边界

- 桌面和 Web 可以读取用户主动提供的源码快照做静态分析，但不得在平台主机执行其中的脚本、插件、依赖安装或构建。
- 所有构建在目标 Linux 的受管工作目录完成，使用阶段允许的固定构建入口，并绑定目标服务器、项目标识和源码摘要/Commit。
- 允许用户明确选择 root 直接构建。每次必须展示项目、源码修订、目标机和影响，不能保存“永久同意”。执行器清理继承环境，不转发 SSH Agent，不注入无关平台凭据，并限制目录、时间、进程数、磁盘、内存和输出。
- Maven 插件、Wrapper 和项目脚本本质上能够执行任意代码；以 root 构建时，上述限制不能形成可靠安全沙箱。界面与文档必须原样说明风险，不能使用“隔离安全”措辞。
- 产品不提供任意命令输入框或浏览器 SSH 终端。固定命令白名单约束的是产品执行器，不能证明不可信项目代码安全。

### 4.3 发布与回滚

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

### 4.4 健康检查

健康检查按以下顺序选择：

1. 项目明确提供 HTTP 健康端点时，校验协议、地址、状态码、超时和响应约束。
2. 没有端点时，由用户确认监听端口，同时验证 TCP、服务主进程归属和稳定观察窗口。
3. 只有进程存在、只有端口开放或只有 systemd 显示 active，均不足以独立判定发布成功。

启动和重启必须检查健康；停止必须确认停止；修改自启后必须重新查询远端自启事实。

### 4.5 受管生命周期

- 状态刷新、启动、停止、重启、启用自启、停用自启只作用于可验证归属的受管资源。
- 启动和停止不改变自启；启用或停用自启不改变当前运行状态；重启保留自启设置。
- 数据库中保存的是最后观测值，目标服务器实时查询结果才是当前事实。
- 资源缺失、标识不符、外部修改、连接失败或结果无法复核时返回未知/异常，不猜测执行、不自动重建、不标记成功。
- 不提供远程删除、通用日志控制台、批量生命周期或任意服务/容器管理能力，除非后续另行确认范围。

### 4.6 安全、秘密与恢复

- SSH 首次连接固定主机指纹；指纹变化必须停止并重新确认。
- 平台凭据由 `app/secret` 或 `web/secret` 管理，日志、事件、错误、AI 上下文和 HTTP 响应必须脱敏。
- 路径操作使用规范化后的受管根目录和不可伪造的归属标记；清理、覆盖或替换前再次校验目标。
- 取消、超时、断连和进程中断都进入明确状态；不能把“命令已发送”当成“操作成功”。
- 检查和备份散列只能证明内容是否变化，不能单独证明备份来自可信方。

## 5. 完整开发流程

### 5.1 开始条件

每个纵向功能开始前必须确认：所属阶段、输入与输出、支持矩阵、模块责任、权限影响、失败状态、回滚策略和验收环境。若需要新增模块，先按 [File.md](File.md) 的治理流程与用户讨论，不得先创建再补文档。

### 5.2 实施顺序

1. **冻结需求**：把用户要求转成可检查验收项，明确非目标和风险决定。
2. **冻结边界**：确认模块依赖、模型/接口契约、受管路径、命令模板和状态机；必要时同步文档。
3. **实现纵向切片**：按“源码准备→确定性分析→部署决策与编排→Linux 公共契约与具体实现→平台服务→界面/API”贯通一个真实用例，不堆积无调用入口的横向占位代码。
4. **集中验证**：完成较大纵向切片后统一运行单元、模块集成、静态检查、前端和真实 Linux 验收；修复后只重跑受影响范围和必要总门禁。
5. **失败演练**：覆盖断连、超时、构建失败、健康失败、回滚失败、状态漂移和权限不足。
6. **更新状态**：文档和进度必须区分规划、开发中、静态已验证、真实环境待验证、已验收。

### 5.3 质量门禁

- Java：必须使用 JDK 21，执行完整 Maven reactor `verify`，禁止循环依赖和未声明的反向依赖。
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

### 5.4 完成定义

功能只有同时满足代码完成、自动化门禁通过、失败路径验证、文档同步和必要真实环境验收，才能标记完成。缺少真实 Linux、systemd、容器、数据库或迁移证据时，必须标记“待运行环境验证”，不能以 Windows 编译或模拟结果代替。

## 6. 文档与结构维护

1. 总纲负责跨期规则；分期文档负责该期输入、流程、失败、非目标和验收。
2. [File.md](File.md) 是完整目标目录、模块职责、依赖方向、内部包结构和模块命名的唯一来源；本总纲只保存跨期规则、架构摘要和当前 reactor 状态，不复制完整结构树。未经用户讨论确认，不得新增模块或改变依赖方向。
3. 创建、移动、删除正式目录或改变 reactor 状态时同步更新 `File.md` 与本总纲；跨期能力变化同步更新所有受影响分期。正式目标与当前实现必须分别表述，不得因目标目录已确认而宣称模块、迁移或功能已经落地。
4. 修订必须更新版本、日期、状态和变更记录。历史记录只追加，不伪造完成状态。
5. `.ai-workspace`、`.idea`、`target`、`node_modules`、缓存、日志和构建产物不是正式架构。

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

## 8. 文档版本记录

| 版本 | 日期 | 阶段 | 状态 | 说明 |
| --- | --- | --- | --- | --- |
| 2.16.0-centos-stream-acceptance | 2026-08-14 | 二期至三期 | CentOS Stream 9 产品入口验收完成 | 精确 x86-64 夹具经两次环境准备后完成两组件发布、故障候选整应用回滚、应用/数据库重启、生命周期与自启切换；准备前后 SELinux 和防火墙态保持观测值。修复空 nftables 规则集识别、Java 21 默认运行时、可省略 `VARIANT_ID` 和只读 SSH 短暂超时；不外推到 Stream 10 或其他发行版。 |
| 2.15.0-centos-stream-recovery-blocked | 2026-08-14 | 二期至三期 | CentOS 准备回归受目标 SSH 状态阻断 | 经用户授权的固定测试环境引导后，产品入口复验 Stream 9 为 SELinux Enforcing；同时清除省略 `VARIANT_ID` 时准备脚本仍拒绝目标的技术债，并让 APT/DNF 失败回传非秘密阶段及标准错误/输出。后续目标在密钥交换前主动关闭 SSH，未以手工部署替代，故完整验收和部署成功仍为 `RUNTIME-PENDING`。 |
| 2.14.0-centos-stream-safety-stop | 2026-08-14 | 二期至三期 | CentOS Stream 9 只读探测完成；安全前置条件阻断部署 | 修复 CentOS Stream 9/10 镜像省略 `VARIANT_ID` 时被识别为 OTHER 的技术债；实际目标为 x86-64-v3，但 SELinux Disabled，产品入口在环境准备前安全停止，未安装、上传或发布。其他发行版实机测试按用户当前范围延后。 |
| 2.13.0-reviewed-ubuntu-acceptance | 2026-08-13 | 一期至三期 | 当前 Ubuntu 24.04 x86-64 产品入口验收完成；其他发行版待验收 | 统一 Spring Boot Reviewed/helper v3 完成环境准备、发布、恢复、回滚、生命周期、Wrapper、资源、归属和信任验收；Podman Quadlet 完成部署、HTTP、回滚、生命周期与自启验收。 |
| 2.12.0-phase3-ubuntu-fixture-regression | 2026-08-13 | 三期 | 抽取后的 Ubuntu 两组件夹具实机回归通过；新增发行版实机待执行 | 原授权 Ubuntu 24.04 x86-64 保持不重装，经 `DesktopApplicationService → SshdLinuxGateway → controlled helper v3` 重跑共享两组件事务，1/1 通过、0 失败/错误、206.2 秒；验证发布、故障候选整应用回滚、SQLite v7 图重载、生命周期和自启切换，应用保持运行且关闭自启动。该结果不外推到新增发行版。 |
| 2.11.0-phase3-distribution-harness | 2026-08-13 | 三期 | 通用实机验收框架静态门禁通过；新增发行版实机待执行 | 非 Ubuntu 验收要求每次明确给出发行版/版本/包架构/CPU/准备预期，先经产品入口准备两次并保持安全/防火墙状态，再复用两组件发布、故障回滚和生命周期事务；AlmaLinux 10 x86-64-v2 只能验证自动准备拒绝，普通 Maven 门禁绝不连接服务器。 |
| 2.10.0-phase3-acceptance | 2026-08-13 | 三期 | Ubuntu 24.04 x86-64 产品入口验收完成；其余精确矩阵待验收 | 六种高级语言试验适配器分别及联合通过构建、发布、HTTP、故障回滚、生命周期、秘密脱敏和桌面状态重启恢复；两组件整应用通过发布、故障回滚、SQLite v7 图重载与生命周期。Gradle 官方分发采用固定 SHA-256 和受管内容缓存，Windows SSH 关闭竞态通过 12 次真实连接专用回归；未重装服务器，新增发行版不外推实机结论。 |
| 2.9.0-phase3-product-entry | 2026-08-13 | 三期 | 多组件桌面/服务/SQLite 聚焦门禁通过；真实 Linux 待验收 | 桌面可显式编辑组件图、执行静态准入和逐组件审阅，并提交整应用事务/生命周期；成功结果将组件拓扑与所有发布状态原子写入 SQLite v7，应用重启后从持久图和目标机封存标记恢复控制，不保存构建参数或秘密值。 |
| 2.8.0-phase3-distribution-matrix | 2026-08-13 | 三期 | 独立静态矩阵与聚焦门禁通过；各发行版真实 Linux 待验收 | 冻结 Debian 13、Rocky 9.8/10.2、AlmaLinux 9.8/10.2、Oracle Linux 9/10；加入包架构、累计 CPU、AppArmor/SELinux、防火墙和容器证据，拆分兼容策略与准备适配器，EL 非 enforcing 和 Alma 10 v2 第三方依赖边界均安全停止。 |
| 2.7.0-phase3-multi-model-core | 2026-08-13 | 三期 | AI/数据库/服务/UI 聚焦门禁通过；真实 Provider 运行不作为部署验收前提 | 三个固定角色可独立绑定命名 Provider/模型，最小脱敏上下文进入严格结构化输出校验并保留凭据无关证据；确定性停止优先，冲突需用户决定，失败不跨 Provider 回退；SQLite 升至 v6。 |
| 2.6.0-phase3-multi-component-core | 2026-08-13 | 三期 | JDK 21 全量离线门禁 28/28 通过；产品入口与真实 Linux 待验收 | 加入混合组件结构化分析、组件级停止原因、确定性依赖顺序、整应用短停机事务/健康/恢复和部分生命周期汇总；修正 helper v3 能力判断，未外推为实机支持。 |
| 2.5.0-phase3-experimental-adapters | 2026-08-13 | 三期 | JDK 21 全量离线门禁 28/28 通过；真实 Linux 待验收 | Go、Rust、.NET、Kotlin、PHP、Ruby 接入锁文件静态分析、类型化运行时、固定构建渲染、helper v3、实时工具链探测与逐次试验风险确认；结构门、双语目录和 helper 字节身份已通过，未声明正式支持。 |
| 2.4.0-phase3-support-preview | 2026-08-13 | 三期 | 本地集中门禁通过；高级适配与实机待验收 | 新增支持等级、精确验证范围与不可执行语言识别预览；预览不会创建归档、构建、发布或生命周期路径。 |
| 2.3.0-spring-boot-reviewed-convergence | 2026-08-13 | 一期至二期 | 本地实现完成；新链路实机待验收 | Maven 与 Gradle Spring Boot 收敛为一个 Reviewed/helper v2 链路；迁移前 Ubuntu 证据保留但不外推，新链路标记 `RUNTIME-PENDING`。 |
| 2.2.0-reviewed-runtime-acceptance | 2026-08-12 | 二期 | Ubuntu 24.04 x86-64 实机验收完成；其余矩阵待验收 | 产品入口完成本地与公开 Git 固定 Commit 源码的类型化分析、构建、发布、健康、观测、代表性生命周期及失败回滚；Podman、Ubuntu 22.04 与 CentOS Stream 9/10 保持 `RUNTIME-PENDING`。 |
| 2.1.0-local-execution-contracts | 2026-08-12 | 二期 | 本地实现与自动化验证完成；运行环境待验收 | 六类项目的受控目标机构建、发布、快照、回滚、健康和生命周期代码均已接入；类型化主机矩阵在上传前执行，Ubuntu 22.04/24.04 与 CentOS Stream 9/10 具有固定环境准备脚本。真实目标机仍未连接。 |
| 2.0.9-phase2-local-implementation | 2026-08-12 | 二期 | 本地实现与自动化验证完成；运行环境待验收 | Git 快照、六类项目的静态分析/计划、配置/秘密修订、Linux/容器契约、命名 Provider 和只读 Agent 已落地；未连接二期目标机，不能作正式支持结论。 |
| 2.0.8-structure-implementation | 2026-08-11 | 一期至五期 | 正式目标结构已落地；一期能力边界不变 | 记录 28-POM reactor、`shared/source` 与 `shared/linux-sshd` 迁移、桌面层职责分包和组合根注入；`shared/config` 仅建 POM，本次不执行真实 Ubuntu 操作。 |
| 2.0.7-architecture-sync | 2026-08-11 | 一期至五期 | 正式目标架构摘要已同步；一期状态不变 | 同步 `shared/config`、`shared/source`、`shared/linux-sshd` 及配置、源码归档和 Linux 契约/实现边界；不创建模块，不迁移源码，不改变阶段范围。 |
| 2.0.6-phase1-complete | 2026-08-10 | 一期 | 一期真实验收完成 | 新装 Ubuntu 24.04 上由产品完成环境准备和全链路部署验证；不进入二期或 Web 实施。 |
| 2.0.5-phase1-runtime-partial | 2026-08-10 | 一期 | 大部分真实 Ubuntu 验收完成；复杂/Wrapper 成功路径待 DNS 恢复 | 由一期程序实际验证发布、回滚、断连、资源限制、TCP、启动失败、归属、主机信任及全生命周期；不以人工部署替代失败的 Maven Central 下载。 |
| 2.0.4-phase1-dependency-security-pending | 2026-08-08 | 一期 | 依赖安全修复与真实 Ubuntu 待验收 | `npm audit` 发现锁定的 Vite/Vitest 开发工具漏洞；升级下载超时，未改写锁文件。 |
| 2.0.3-phase1-gates | 2026-08-08 | 一期 | 本地实现与完整自动化门禁完成；真实 Ubuntu 待验收 | 补齐现有前端的类型、单测、生产构建与 Chromium Playwright 证据，未扩展一期范围。 |
| 2.0.2-phase1-hardening | 2026-08-08 | 一期 | 本地实现与自动化验证完成；真实 Ubuntu 待验收 | 收紧静态源码、Linux Wrapper、工作区、JAR 摘要和 Java 运行时的一期安全边界。 |
| 2.0.1-phase1-implementation | 2026-08-08 | 一期 | 本地实现与自动化验证完成；真实 Ubuntu 待验收 | 记录一期桌面端的当前实现状态，保留运行环境验收边界。 |
| 2.0.0-roadmap-rebaseline | 2026-08-08 | 一期至五期 | 五期需求基线，骨架已初始化 | 重整阶段范围，统一目标机构建、短停机事务、分层健康、配置/密钥、备份迁移和 Web 安全；建立完整开发流程。 |
| 1.7.0-managed-lifecycle | 2026-08-07 | 一期至四期 | 历史需求基线 | 增加受管应用状态、启动、停止、重启和开机自启。 |
| 1.6.0-web-structure | 2026-08-07 | 一期至四期 | 历史结构基线 | 确认 Web 模块及 `shared/backup`。 |
| 1.5.0-app-storage | 2026-08-07 | 一期至四期 | 历史结构基线 | 确认桌面模块、固定数据目录和秘密边界。 |
| 1.4.0-doc-split | 2026-08-07 | 一期至四期 | 历史文档结构 | 拆分总纲和四份分期文档。 |
| 1.3.0-phase4 | 2026-08-07 | 一期至四期 | 历史需求基线 | 增加原四期 Web 设想。 |
| 1.2.0-phase3 | 2026-08-07 | 一期至三期 | 历史需求基线 | 增加语言、Linux、备份迁移设想。 |
| 1.1.1-phase2 | 2026-08-07 | 二期 | 历史需求修订 | 增加多 API 配置要求。 |
| 1.1.0-phase2 | 2026-08-07 | 一期至二期 | 历史需求基线 | 增加语言、Git、文档和 Agent 设想。 |
| 1.0.0-phase1 | 2026-08-07 | 一期 | 历史规划基线 | 初始一期规划。 |
