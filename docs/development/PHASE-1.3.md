# WindowsToLinux 1.3 期执行计划：窗口前置、ModDev、Git、部署、文件管理与依赖下载

> 状态：P0 已完成；2026-10-05 启动正式目标，第 2.8 节的既有静态门禁失败已关闭，S0、S1 已完成；S2～S6 尚未完成。窗口尺寸与非关键视觉精修后置；本期完成全部适用本地验收即可交付，真机与正式服务验证另行安排。
> 建立日期：2026-10-04。
> 更新日期：2026-10-05。
> 定位：按“执行入口 → 窗口前置 → 正式阶段与交付物 → 功能规格 → 验收 → 开工核实”组织，供目标模式逐项实施；任务完成状态须由实际实现与验证更新。

- [开发总纲](DEVELOPMENT.md) · [1.1 平台化改造](PHASE-1.1.md) · [1.2 APP 调整](PHASE-1.2.md) · [实际源码结构](../File.md#current-source-tree) · [Mod 开发规范](../Mod.md) · [运行约定](../Runtime.md)
- [范围与使用方式](#scope) · [首版界面优先级](#ui-first-delivery) · [本地完成门槛](#local-acceptance) · [目标模式执行入口](#goal-execution) · [P0 开工前窗口阶段](#mod-workspace) · [正式执行计划 S0～S6](#delivery) · [部署规格](#deployment-migration) · [Git 与依赖规格](#git-mod) · [ModDev 规格](#mod-development-layout) · [文件管理规格](#file-explorer) · [商店连同下载](#dependency-download) · [验收](#acceptance) · [后期工程](#deferred) · [按阶段核实](#pending)

<a id="scope"></a>

## 1. 开发范围与计划使用方式

本期范围包括六项工作；执行次序以第 2、3 节为准：

1. 实现独立、可选的文件管理 Mod，即前文讨论的资源管理器 Mod，复用 APP 文件、连接与传输能力提供用户界面。
2. 完善现有部署 Mod，将历史代码中已经实现的部署功能及对应界面完整迁移到当前 APP/appapi/Mod 架构，补齐 1.1 阶段未完整承接的部分。
3. 将 Git 专用功能从部署 Mod 中独立提取为 Git Mod，供部署及其他 Mod 通过公开接口复用；新增可选依赖，部署未接入 Git 时仍可使用本地源码。
4. 实施 ModDev 开发区与 Mod 集成调试区划分，配套调整构建、测试与调试加载入口。
5. 调整 APP 与 Mod 的界面组织：APP 管理最左侧主导航，每个提供界面的 Mod 只注册一个工作区入口，内部功能页由 Mod 自行组织。
6. 实现 Mod 商店连同下载：必需依赖自动纳入下载清单，可选依赖由用户选择，配套完成版本核对、获取安装及结果反馈。

SQL、Swing 封装暂时用不上，明确移至后期工程；Git 独立化和 ModDev/Mod 划分在 1.3 实施，不暂缓。既有 Git 部署取材在独立化后继续完整接入部署链。此前包含 SQL/Swing 封装的同期开发计划由本节替代。

### 1.1 功能与架构边界

| 部分 | 本期归属与要求 |
| --- | --- |
| APP | 保留基础文件操作、Windows/Linux 连接与传输、任务、权限、资源、数据库引擎和页面挂载；只为本期目标的具体缺口做必要适配。 |
| 部署 Mod | 保留一套完整部署闭环，分析、工具链、数据库准备、构建、发布、健康检查、回滚和应用管理继续在同一 Mod 中组织，允许职责分包和同作者从包；Git 来源通过独立 Git Mod 衔接。 |
| 文件管理 Mod | 独立、可选，提供通用文件浏览与管理界面，调用 APP 基础 API；其他业务不因文件选择或传输而必须安装它。 |
| SQL 与 Swing | 官方 Mod 继续自行编写所需 SQL 和 Swing 界面，不依赖后期封装；平台基础 API 和受管边界继续适用。 |
| Git Mod | 本期独立提取 Git 专用能力；部署项目保存、分析与发布编排仍属部署 Mod，素材交接、依赖和恢复合同在实施前明确。 |
| Mod 依赖与商店 | APP 本期新增可选依赖及其可用性判断；商店自动补齐必需依赖，用户选择可选依赖，复用既有安装与启用规则。 |
| ModDev/Mod | 本期区分作者开发项目与接入 APP 的集成调试项目，构建、测试、描述生成与自动调试加载配套适配。 |
| Mod 工作区 | 在 P0 开工前窗口阶段优先处理。APP 提供统一外壳、主导航和内容容器；Mod 注册一个根工作区，内部侧栏、标签与功能内容由作者控制，平台保留挂载与生命周期合同。 |
| 后期工程 | SQL/Swing 封装、全软件助手及通用 Mod 业务操作 API，以及原 1.1 S6/S7 的独立业务、作者工具、SDK 和正式发行范围保留后置。商店连同下载及其必要客户端接线已明确纳入本期，官网投稿、审核和公开发布体系继续后置。 |

### 1.2 既有状态与历史依据

[1.1 文首](PHASE-1.1.md)记录了其本机适用合同验收，并明确将接口外形、动画和非必要调试展示精修后置；其 S6/S7、真实远端及 SSH Git 凭据等未验证或暂缓边界继续保留。用户本次将商店连同下载明确纳入 1.3，其实施和验收由本计划承接，不改写 1.1 的历史状态。[1.2](PHASE-1.2.md)保留 APP 调整及对应实施验收。本期不将 1.1 的验收直接等同于历史部署功能与界面的完整覆盖。

历史功能的核对依据是冻结的 `history-src` 源码、资源、既有测试及[历史产品说明](../../history-src/docs/PRODUCT-MANUAL.md)。迁移期间保持历史归档不变，在当前活动源码中适配和补齐；历史未实现的计划与只用于演示的固定数据不自动成为已实现功能。

期数仅组织开发文档，不自动修改软件、API、Schema 或 helper 协议版本。本文当前是计划；类存在、编译通过或文档记录不等于功能完成。

<a id="ui-first-delivery"></a>

### 1.3 首版界面优先级

用户已确认：首次制作的界面可能与预想有差异，本期先确保功能和关键交互正确，外观尽量整洁、美观，视觉细节在后期根据实际使用反馈逐步修整。

参考截图用于确定界面结构和使用方式，不要求首版逐像素复刻或一次达到最终外观。首版应做到布局清楚、基本对齐与间距协调、文字和控件可辨识；图标细节、颜色和圆角微调、装饰及过渡动画等视觉精修可以后置，不单独阻塞本期功能交付。

本期已确认的单 Mod 单入口、单栏/按需双栏、列表显示、拖拽和复制/剪切/粘贴仍是功能要求。已发现的功能错误、错误操作结果、关键操作不可达或内容遮挡导致无法操作等问题须在本期解决，不能归入后期视觉精修。部署界面的完整迁移继续要求业务入口与交互承接完整，不以外观打磨替代功能覆盖。

### 1.4 使用本计划

1. 先单独处理第 2 节 P0 窗口与工作区调整，完成布局、最小接线和相应验证；该阶段属于 1.3 范围，安排在正式开发目标启动之前。
2. 启动后续正式开发目标时，从第 3 节 S0 开始，核对并承接 P0 成果，再按 S1～S6 推进。已完成且已验证的窗口工作不重复实施，只处理实际发现的接线或回归缺口。
3. 第 2、3 节是执行清单，第 4～8 节是对应功能规格，第 9 节是验收依据，第 11 节是所属阶段开工时的核实清单；以任务 ID 定位当前步骤，不把规格正文另算一组任务。
4. 每阶段先核实所需合同，再实施适用任务、验证结果并交接成果。复用已经迁入的有效实现，只做必要适配和受影响回归，不为清单重新实现一套代码。
5. 勾选任务必须同时满足实现、适用验证及记录要求。文档整理、尺寸测量、编译通过、模拟成功或历史测试记录不能单独证明功能完成；具体判定见第 3.1 节。

<a id="local-acceptance"></a>

### 1.5 本期完成门槛（已确认）

用户已明确：1.3 完成规定功能实现并通过完整的适用本地测试后，即可交付并认定本期目标达成。真实 Linux、真实模型/语音提供方及正式商店服务的验证由用户另行安排，需要时单独下达任务，不属于本期完成前置条件。

本地验收覆盖适用构建、静态检查、单元/集成测试及本机实际界面操作；远端协议、模型和商店交互可使用本机受控服务或测试夹具验证。测试支持不能替代应有的生产实现，空入口、未实现通道或只返回模拟成功不满足功能完成条件。

交付分别报告“1.3 本地验收完成”和具体“真机/真实服务未验证”范围。后者不阻止本期任务完成，也不能被写成本地结果已经证明真实环境通过；后续真机任务独立维护其范围、状态与证据。

<a id="goal-execution"></a>

### 1.6 目标模式启动与续接

P0 前置目标已完成；交付停止于前置验收与交接。用户以后明确启动正式开发目标时，执行范围可表述为：

> 按本计划完成 1.3 正式开发：从 S0 核对并承接已完成的 P0 窗口成果，按 S1～S6 实现本期功能，完成全部适用本地验收后交付；真机和真实服务验证另行安排。

执行时遵循以下规则：

- **首次进入**：核对届时源码、构建配置、未提交改动、P0 交接和现有进度记录；不能把本文建立时的基线直接当作开工事实。保留已有工作，P0 若只有影响后续的基础缺口，按第 2.6 节先补该缺口。
- **推进步骤**：默认按 S0 → S1 → S2 → S3 → S4 → S5 → S6 逐阶段执行，在阶段内沿任务 ID 推进；依赖任务的完成条件未满足时先补齐前置，不跳过缺口。完成当前阶段后继续下一阶段，不以交付局部样例代替整个目标。
- **常规设计**：第 11 节的精确接口、目录组织和交互细节，优先依据当前规范、代码和已确认规则确定，写回所属规格后实施，不反复询问已经确认的选择。若发现必须改变本期范围、已确认行为或数据处理边界，先说明具体影响并澄清，继续不依赖该决定的工作。
- **进度与续接**：源码实施开始后按项目工作约定维护已有进度与交接，不覆盖其他未完成任务。记录已完成任务的依据、未完成项、实际障碍和下一任务 ID；恢复执行时核对受影响代码及记录，从首个未完成且前置满足的任务继续，不重做仍然有效的成果。
- **验证入口**：开工时按 [README 开发与验证](../../README.md)核对 JDK、Maven、Git、部署脚本解释器及本机 UI 条件，沿用当前入口。S1 调整构建范围后同步定向命令；S6 复用前面阶段仍有效的证据，只补受影响或缺失的检查。
- **结束条件**：达到第 1.5、9 节的功能与本地验收门槛，完成 S6 收尾后才认定本期目标达成。实现或必要本地验证存在缺口时保留未完成状态；缺少另行安排的真机验证不延长本期目标。
- **阶段提交（2026-10-05 用户要求）**：每个正式阶段满足完成条件并通过适用验证后执行一次本地 Git 提交。第一次提交同步纳入此前未提交的工作；核对敏感数据、数据库和生成产物不进入提交。记录各阶段提交，不自动推送远端。

<a id="mod-workspace"></a>

## 2. P0：正式开工前的窗口与工作区调整

窗口部分已完成并作为正式 S0～S6 的基础。本阶段覆盖 APP 外壳、主导航、Mod 根工作区挂载及必要 UI 接线；文件管理业务和历史部署功能补齐分别在 S4、S3 实施，商店连同下载在 S5 实施。

### 2.1 已确认界面结构

参考本次提供的 Codex 截图，采用“APP 统一外壳 + Mod 内嵌工作区”。这里的窗口指主窗口内的内容区域；进入一个 Mod 后，可由该 Mod 在内部设置侧栏、标签页或其他选择控件，组织多个功能页面。

| 区域 | 归属与要求 |
| --- | --- |
| 最左侧主导航 | 由 APP 提供并管理，保留平台自身入口；每个提供界面的 Mod 仅有一个入口，不按内部功能增加主导航项。 |
| 中间工作区容器 | 由 APP 挂载所选 Mod 的根界面；容器内的完整布局和业务内容交给 Mod，切换 Mod 时切换对应工作区。 |
| Mod 内部导航与内容 | 由 Mod 自行组织，可包含多个功能页、选择控件及自有业务设置，不要求逐页向 APP 主导航注册。 |
| 平台管理界面 | Mod 启停、版本、权限等平台职责继续由 APP 提供，不交由作者工作区控制。 |

例如，主导航只有一个“部署”入口，进入后在内部选择部署模式、应用管理等功能。无界面的服务 Mod 不创建空入口；只有提供界面的 Git Mod 才登记其工作区。

### 2.2 UI API 收敛方向

对 APP 导航的接入操作收敛为登记一个 Mod 根工作区。登记使用原 Mod 身份，提供入口所需展示信息及根界面创建方式；Mod 成功启用且有效根工作区登记已发布后，由 APP 生成其唯一主导航入口，不要求逐个功能页登记或另行手动固定才能进入。登记元数据不提前创建作者视图，入口生成也不以首次视图创建完成为条件；视图仍在用户首次进入时创建。

Mod 内部的页面、标签和选择控件不再要求宿主预留各自的导航注册接口。

Mod 可以直接组织完整 Swing 内容区域。平台不为每个业务控件提供一套描述或注册 API，也不要求依赖后期 Swing 封装；新接口名称、参数和 API 版本调整在实施规格中确定，优先复用既有组件工厂与环境合同。

“只注册一个窗口”描述的是导航接入方式。平台仍需保留根工作区的挂载/撤销、EDT 创建与释放、主题/语言/锁定通知及资源生命周期；作者负责将环境变化传递给内部组件并释放自身资源。切换工作区不等于停用 Mod 或结束其后台任务。

用户已确认根工作区按需创建并保留：登记入口时不创建视图，首次进入才创建实例；切换到其他工作区只隐藏原视图，不释放或重建。再次进入复用原实例，保留输入、选择和浏览状态。明确关闭工作区、撤销登记、停用 Mod 或正常退出 APP 时按平台合同释放；明确关闭后的再次进入才重新创建。视图保留不表示数据永远不刷新，业务仍须核对当前事实；后台任务继续使用独立生命周期。

### 2.3 现行接入与旧入口归并

当前 `UiService.register(PageSpec, PageFactory)` 的普通登记表示唯一根工作区，`ModPage.create(UiEnvironment)` 返回完整 `JComponent`，内部多个功能页由 Mod 自行组织。成功发布根后自动生成主导航；元数据登记与列表刷新不提前创建作者视图。

现有部署登记一个 `workspace` 根，由 `ui/DeploymentWorkspace` 在内部标签持有标准/辅助、自主部署与应用管理。APP 不再为这些内部功能分别登记业务标签或侧栏入口。文件管理等后续 UI Mod 使用相同单入口方式，P0 不创建这些正式占位 Mod。

旧 `mod_navigation_pins` 保留原 JSON 结构，当前已发布所有者按最早位置归并为其根，后续旧项合并；暂未发布所有者的原项保留，新根追加。P0 只提供排序与折叠，不提供单独隐藏根的开关；具体事务、损坏处理、设置与预览隔离及关闭规则见第 2.7 节。

### 2.4 当前尺寸参考

本次讨论已按当前代码进行 100% 缩放、启用 Mod 的隔离布局测量（2026-10-04）。以下为调整前的参考值，未作为 1.3 必须固定的尺寸；P0 改动后重新记录实际可用区域。

| 区域 | 默认窗体、导航展开 | 默认窗体、导航折叠 |
| --- | --- | --- |
| 整个窗体 | 1120×760 | 1120×760 |
| 主导航右侧完整工作区 | 936×685 | 1040×685 |
| 当前 Mod 实际绘制区 | 936×559 | 1040×559 |

当前最小窗体为 800×560。两种工作区高度的差别来自平台顶部标题约 90 和旧业务标签区域约 36；是否保留这些宿主区域，在 P0 布局与根工作区方案中确定。测量未启动业务或数据库，不代表已经完成新的窗口实现。

### 2.5 P0 执行清单

- [x] **P0-01 确定窗口方案**：落实主窗默认/最小尺寸、主导航及内容区域、标题和底部反馈的归属、关闭入口及未保存内容处理。落实首次进入创建、切换保留；沿用单 Mod 单入口和首版界面优先级，记录调整后的实际挂载区域。
- [x] **P0-02 完成宿主接入**：整理根工作区注册/打开接口、唯一入口生成、旧页面固定偏好归并、自有设置和 CLASS 预览去向；保持环境通知、锁定与释放合同。
- [x] **P0-03 完成最小真实接线**：将现有部署的标准/辅助、自主、应用管理页面收进一个根工作区，作为真实调用方验证。此处只做窗口和导航所需适配，历史功能补齐留给 S3，不创建正式占位 Mod。
- [x] **P0-04 验证并交接**：通过实际界面核对入口、内部切换、默认/最小窗体、导航展开/折叠及受影响的主题、语言、缩放、锁定和关闭行为；记录实现落点、最终 UI 合同、实际尺寸、有效验证和明确未完成项。

### 2.6 完成条件与正式开发承接

P0 完成时，主导航、根工作区和现有部署页面的最小接线可实际使用，关键操作可达，环境变化与关闭不破坏现行资源合同。外观尽量整洁，非关键视觉精修可以后置；功能或关键交互缺陷仍需解决。

正式 S0 只核对这份窗口交接及当前源码。若用户已提前完成 P0，不因启动目标模式重做窗口；若实际尚有影响后续开发的基础缺口，先补齐该缺口，再继续依赖它的任务。最终尺寸以 P0 实现与测量为准，不能继续把上表参考值当作新窗口结果。

### 2.7 P0 现行实现合同

- API 开发版推进到 `1.0.0-dev.2`，对应普通多页登记收敛为唯一根的不兼容行为；同步宿主 Maven 依赖、作者父工程及官方部署最低 API 范围。软件、业务 Schema 及 helper 版本保持原身份，正式发行仍属后期。
- 复用 `UiService.register(PageSpec, PageFactory)` / `open(localPageId)`。`settingsPage=false` 表示所属 Mod 唯一根工作区；第二个普通登记拒绝，撤销原登记后才可登记新根。`settingsPage=true` 继续挂载 APP 的 Mod 设置分区，不生成主导航入口。`registerPreview` 保留 CLASS 隔离预览，不占根登记，不成为普通导航。
- 成功发布的根元数据自动生成入口，服务 Mod 没有空入口。P0 不提供单独隐藏根入口的开关；可调整根入口顺序、折叠主导航，停用/撤销后入口随发布状态消失。关闭视图保留入口，重开创建新实例。
- `mod_navigation_pins` 继续使用原有 JSON 结构。对当前已发布所有者，以其最早旧固定项的位置保留一个根身份；后续重复旧项合并，新根追加；暂未发布所有者的原项保留，待其发布再归并。解锁后在原准入事务持久化，锁定只依据发布元数据和缓存展示，不越过存储授权。损坏偏好报错且保留原值。
- APP 保留最左导航、根工作区的紧凑标题/关闭入口和全局底部诊断反馈；内部标签、业务标题及状态由 Mod 控制。取消旧宿主业务标签层和根容器的重复主导航按钮。默认 1120×760、最小 800×560 暂沿用，只保证缩放及滚动下关键操作可达，测量值不冻结后续设计。
- 根视图首次进入创建，切换只隐藏；环境变化广播给保留实例。明确关闭默认先确认，提示未保存界面输入会丢弃、已接受后台任务继续；取消保留实例。撤销/停用/退出按原生命周期清理，不以交互确认阻止平台收尾。`ModPage.navigationId()` / `restoreNavigation(id)` 在 EDT 捕获/恢复有界非秘密内部页面标识，复用 `ui_last_page.detailId`，首次恢复在 `create` 前应用，普通切换不重置内部选择。仅保存导航身份，不保存草稿；旧部署业务页转入新根后仍恢复对应标签，无所有者的旧空容器位置回到首页。
- 部署使用一个 `workspace` 根，内部标准/辅助、自主、应用管理三个标签按首次选择创建，切换保留；作者负责向已创建内部页传递环境并逐项释放，清理失败保留失败实例供重试。历史功能补齐不在 P0 实施。

### 2.8 P0 验收与正式阶段交接（2026-10-05）

P0-01～04 的窗口与最小真实接线已完成。正式开发可从 S0 承接，不重做工作区；Git、ModDev、文件管理、商店下载及历史部署补齐均未开始。窗口比例和非关键视觉/动画精修继续后置，当前尺寸仅为兼容性基线。

**实现落点**：公开合同在 `appapi/ui/UiService`、`ui/page/PageSpec` 和 `ModPage`；宿主登记、自动导航和根容器在 `app/ui/mod/ModPageRegistry`、`ModNavigationPane`、`ExtensionPagePane`；窗口衔接在 `PlatformFrame` / `PlatformNavigationShell`；旧偏好归并在 `ModNavigationPreferences` / `PlatformModIntents`。部署 `Mod` 只登记 `workspace`，`DeploymentWorkspace` 复用三个现有真实页面。内部页面恢复沿用 `StartupPageService` 的原 JSON，不新增数据表或独立文件。

**实际区域**：以下为含实际 APP 底部反馈、部署根标题和内部标签的 Swing 挂载测量，单位为当前缩放下的像素。容器包含 APP 紧凑根标题，Mod 根区域扣除了该标题，仍包含 Mod 内部标签及业务内容；业务内容通过滚动访问，内部标签采用滚动标签布局。默认/最小窗体仍为 1120×760 / 800×560，没有冻结后续尺寸。

| 缩放 | 默认展开：容器 / Mod 根 | 默认折叠：容器 / Mod 根 | 最小展开：容器 / Mod 根 | 最小折叠：容器 / Mod 根 |
| --- | --- | --- | --- | --- |
| 100% | 936×685 / 936×647 | 1040×685 / 1040×647 | 616×485 / 616×447 | 720×485 / 720×447 |
| 150% | 852×652 / 852×601 | 1008×652 / 1008×601 | 532×452 / 532×401 | 688×452 / 688×401 |
| 200% | 768×623 / 768×561 | 976×623 / 976×561 | 448×423 / 448×361 | 656×423 / 656×361 |

**定点结果**：JDK 21 下 12 个受影响测试类、73 项全部通过，0 失败/错误/跳过；没有运行全量业务测试。覆盖单根拒绝与发布、多个所有者、设置/预览隔离、旧顺序归并、锁定准入、停用/重发、按需创建/保留、关闭取消/确认/重开、清理失败重试、内部页面恢复与旧身份兼容。真实部署窗口矩阵覆盖中英文、浅深主题、100%/150%/200% 共 12 组，默认/最小窗体及导航收放；保留 96 行区域测量与 252 张窗口渲染截图，锁定鼠标/输入及内部标签键盘导航另经 Robot 验证。全部采用隔离测试存储，未连接真实 Linux、模型或商店，没有重验部署业务全流程。

定向入口（按 README 配置 JDK 21）：

```powershell
mvn -pl src/mod/deployment -am test '-Dtest=DeploymentWorkspaceTest,DeploymentRestyledWindowTest,ModPageRegistryTest,PlatformModNavigationTest,PlatformLockedNavigationWindowTest,PlatformHomeStartupWindowTest,PlatformNavigationMotionTest,NavigationOrderPaneTest,DesktopTextArchitectureTest,PlatformModIntentsTest,DocumentStructureArchitectureTest,ModuleDependencyArchitectureTest' '-Dsurefire.failIfNoSpecifiedTests=false' -q
```

布局、截图、测试摘要和门禁报告保留在 [P0 本地证据](../../src/mod/deployment/target/phase13-p0/README.md)。`target` 是本地生成证据，执行 clean 会清除；交接合同及实测值以本节为准。当前源码/API 开发版为 `1.0.0-dev.2`，原多普通页的作者需改为一个根并重新编译/调整兼容范围；没有发布或覆盖旧的同版本发行包。

**仍存在的既有门禁问题**：补充运行 `JavadocCoverageTest,PackageStructureArchitectureTest` 共 42 项，36 项通过、6 项失败，0 错误/跳过；doclint 本身通过。失败检查涉及此前 UI 的双语注释缺项、`ui.control` / `ui.display` 依赖环、窗口包固定清单、带 `v13` 的资源命名、首页位置类型的 UI/service 边界，以及现有 launcher/theme 方法与 Frame/Shell 字段复杂度。本次 P0 新增的 service 方法数及 Frame 构造复杂度违规已消除，P0 改动声明未出现在最终注释违规清单；没有修改检查规则放行。完整项目门禁目前不绿，这些问题须纳入正式开工核实并在 S6 前关闭，不能将 P0 功能验收当作全项目验收。

**正式开工修复（2026-10-05）**：上述 6 个静态失败已修复，`JavadocCoverageTest,PackageStructureArchitectureTest` 42 项全通过，doclint 通过。未降低检查限额或修改依赖/注释规则；窗口固定清单仅补真实已有组件。消除 `display`→`control` 的反向依赖，将动画/全局开关绘制归 `display`；将首页位置提取为 `StartupPageLocation` 纯值；资源去掉阶段后缀；补双语注释并拆分原主题/关闭装配及窗口状态职责。

正常 Windows 当前用户上下文中，12 个相关测试类的 95 个独立用例最终通过；首轮 94 项通过，旧 `PlatformWindowTest` 仍假设启动进入密码页，适配为从当前默认首页经设置导航进入密码页后，该项复测通过。开关、拖动、首页/内部恢复、锁定导航和生命周期保留原行为断言。受限执行中的原生点击失败及正常上下文对照保留在 `phase13-evidence/prior-gates/`，没有将失败或跳过计为通过；未做全量业务验收。正式文档收尾及格式另作相应检查。

**承接次序**：正式 S0 核对本节、现有未提交改动与历史逐项对照，再按 S1～S6 推进。仅出现实际新的窗口接线或回归缺口时补对应 P0 项；已有成果不重做。

<a id="delivery"></a>

## 3. 正式开发执行计划：S0～S6

### 3.1 阶段顺序与当前状态

P0 已完成，正式开发按下面的默认次序执行；表中前置条件说明实际依赖。阶段完成后交接可核验成果，再继续后续任务。正式目标已启动，前序门禁修复与 S0 核对已完成；后续按实际实现与验收更新。

| 阶段 | 主要工作 | 前置条件 | 完成条件 | 当前状态 |
| --- | --- | --- | --- | --- |
| P0 | 窗口、主导航、根工作区及最小部署接线 | 正式开发目标启动前单独处理 | 最小真实接线与窗口验证完成，形成第 2 节交接 | 已完成（本地定点验收） |
| S0 | 承接窗口、建立历史清单与本期验收范围 | P0 成果及当前源码可核对 | 形成可执行的功能对照、范围和验证门槛 | 已完成（核对/矩阵，未代替后续功能验收） |
| S1 | ModDev/Mod 开发与调试区域 | S0 已明确工程范围 | 单项开发、构建与集成调试路径实际可用 | 已完成 |
| S2 | 可选依赖、Git 独立化与部署接入 | S1 开发/调试入口可用，S0 边界明确 | 可选依赖及素材交接可用，Git 来源得到承接，本地部署可独立使用 | 未开始 |
| S3 | 历史部署业务与界面完整迁移 | S0 历史对照、P0 工作区及 S2 来源接线 | 对照清单内真实功能及交互逐项补齐并验证 | 未开始 |
| S4 | 文件管理 Mod | P0 工作区、S1 调试入口及 S0 文件场景范围 | 单栏/按需双栏、列表、新建、重命名、删除、拖拽及剪贴操作闭环可用 | 未开始 |
| S5 | 商店连同下载 | S2 依赖合同；落实查询/下载协议及本机受控验证来源 | 必需依赖自动补齐、可选依赖选择，客户端获取安装闭环本地验收通过 | 未开始 |
| S6 | 集成回归与交付核对 | P0 及 S0～S5 的适用阶段成果 | 关闭适用缺口，文档、结构和实际验证一致 | 未开始 |

Git 独立化和 ModDev 划分仍属于 1.3 正式开发，不暂缓。SQL/Swing 封装不作为任何阶段的前置条件。

#### 阶段交接与本地验证重点

| 阶段 | 应留下的成果 | 本地验证重点 |
| --- | --- | --- |
| P0 | 最终 UI 合同、旧入口/偏好归并规则、实际窗口与内容区尺寸、部署最小接线及验证记录。 | 实际主导航进入、内部切换、按需创建/保留/关闭，默认与最小窗口、主题/语言/锁定及资源释放。 |
| S0 | 第 4 节逐项历史对照、文件场景支持范围、第 9 节本地验收矩阵，以及各阶段需要补齐的实现和环境清单。 | 对照项可追到历史与当前产品入口，区分真实实现/未实现设想；核实可用命令、UI 和受控服务条件，不以旧结果证明新实现。 |
| S1 | 开发/调试目录与 Maven 组织、定向构建测试命令、移入后的有效描述及首次调试启用合同。 | 单个真实 Mod 定向构建/测试，移入重新构建并接入；开关关闭、已有禁用/版本选择、必要准备、单 Mod 多包及构建隔离。 |
| S2 | 可选依赖清单/服务/任务合同，Git 独立身份与公开接口，受管素材引用及原任务恢复分工。 | Git 缺失时实际本地任务可完成，Git 可用时取材交接至部署；大素材、无权/失效引用、取消/UNKNOWN、原版本缺失阻塞及资源释放。 |
| S3 | 历史对照逐项补齐，标明当前业务/界面落点、必要适配和迁移后证据。 | 标准/辅助/自主及手工审阅、多组件、准备/发布/管理/恢复的适用路径，经本机界面和受控协议验证；对照内遗漏不能完成。 |
| S4 | 文件管理实际入口、支持场景矩阵、共用动作及链接自身/维护范围规则。 | 实际单栏/双栏浏览和文件结果，新建/重命名/删除、工具栏/右键/拖拽、链接及失败/取消/部分完成/待核对。 |
| S5 | 查询/版本/依赖/包验证协议、真实客户端通道、确定的下载清单和本机受控服务验证记录。 | 必需/可选与传递依赖，最高兼容版本定位、冲突调整、实际下载校验安装、兼容复用、取消/部分失败/重试，安装不自动启用。 |
| S6 | 完整本地验收结果、正式文档与实际结构、真实环境未验证范围及清理记录。 | 从产品入口贯通已确认闭环；关闭全部适用实现/验证缺口，确认临时进程退出、历史归档未被改写，完成交付。 |

本表列出交接和验证重点，完整本地用例以第 9 节及 S0 形成的验收矩阵为准，不能只挑表内一个样例认定阶段完成。

统一完成判定：任务实现或有效既有实现已承接、适用验证通过、结果与事实一致且有记录，才勾选任务；阶段内任务及阶段完成条件全部满足，才将阶段标为完成。每项验证至少标明关联任务/功能、代码落点、入口或命令、环境、预期与实际结果、证据位置和未覆盖项。失败、跳过和未运行不能算通过；确认不适用的检查说明依据，不能借“不适用”删去本期功能。

功能对照、精确合同与验收结论写回本文所属章节，实际发布 API/结构再同步正式规范；详细日志沿既有证据组织保留并引用。进度记录用于续接，不另存一份相互矛盾的产品规格。

### 3.2 S0：开工核对与功能清单

- [x] **S0-01 承接 P0**：核对窗口代码、UI 接口、现有部署工作区、实际尺寸和验证记录；保留已完成成果，列出真正影响后续功能的余项。
- [x] **S0-02 建立历史对照**：按第 4 节核对冻结历史源码、资源、说明和有效测试，逐项登记历史入口、当前落点、已迁/部分迁/未迁/历史未实现状态及适配差异。
- [x] **S0-03 核对实施边界**：承接第 4～8 节已确认范围，核对文件管理场景支持矩阵及商店元数据/下载来源等实现缺口；按第 11 节归入所属阶段落实精确合同，不重新把已确认功能列为候选。
- [x] **S0-04 确定本地验证范围**：核对当前验证命令和本机条件，列明可复用测试、实际界面路径及所需受控协议/模型/商店夹具，在第 9 节形成任务/功能、预期结果、环境与证据相对应的完整验收矩阵；真实目标和正式服务不作为本期完成前置。

**完成条件**：P0 承接情况明确；形成逐项历史对照和具体任务依据，能够指出各阶段要修改什么、验证什么。该对照不能只列类名或文件数，未审计的功能不能直接标记已迁移。

### 3.3 S1：ModDev 开发区与 Mod 调试区

- [x] **S1-01 确定并调整工程组织**：按第 6 节落实目录、Maven 聚合和必要从模块关系，保持一份权威源码。
- [x] **S1-02 建立定向开发入口**：单个 ModDev 项目可独立构建、运行单元测试和静态检查，只引入其实际依赖；这些检查不强制启动 APP，开发项目不进入默认 APP 加载、集成构建和测试集合。
- [x] **S1-03 适配移入调试**：完成移入后的构建输入、开发描述生成/校验与调试加载；明确开启开发调试自动加载后，新项目自动发现、完成必要准备后启用，保留用户已有禁用与版本选择。验证开关关闭、无效构建和待确认准备的行为，不复用旧路径的无效输出。
- [x] **S1-04 核对相关工具与检查**：使 IDE 导入、格式、架构检查和实际结构索引识别两个区域；验证单 Mod 多包及必要依赖的完整调试流程。

2026-10-05：真实作者项目已独立构建/单元测试/字节码准入，包含两个从包及一个私有库；物理移入后拒绝旧描述，按新位置重建并完成 CLASS 首次选择、实际 start/stop。首次数据准备沿原审阅/重启账本接通，取消与锁定撤销、占用及原迁移来源保护通过。18 类、160 个独立 Java 用例最终通过；Python 格式工具 6 项、独立项目 3 文件及最终受影响 25 文件格式检查通过。证据见第 9.8 节；正式文档最终复核后阶段提交。

**完成条件**：真实项目能完成“开发区定向验证 → 移入调试区 → 按新位置构建 → APP 发现并接入”；构建与测试隔离实际生效，不能只增加目录。

### 3.4 S2：可选依赖与独立 Git Mod

- [ ] **S2-01 实现可选依赖合同**：按第 5 节适配依赖声明、版本和启动判断、加载顺序、静态代码准入、公开类型绑定及可选服务发现，并同步适配任务准入、原任务依赖记录和恢复图；正常业务中缺少可选提供方时，基础入口及不使用该提供方的任务仍可运行。
- [ ] **S2-02 落实 Git 素材合同**：按已确认的平台受管素材引用方案，落实读取授权、持有期限、释放和原操作恢复与部署恢复分工；复用现有受管服务机制，引用值符合跨 Mod 值合同，不能以归档字节或裸路径交接替代。
- [ ] **S2-03 提取 Git 专用能力**：将仓库/引用校验、受管 worker、抓取、检出和固定提交取材迁入独立 Git Mod；部署项目记录、源码筛选、分析及发布编排保留业务归属。
- [ ] **S2-04 接通部署来源**：部署通过公开接口消费 Git 素材，并保持本地目录入口、原来源项目和冻结素材的正确接线，不访问 Git 私有实现或表。
- [ ] **S2-05 验证边界与恢复**：核对 Git 未安装、未启用、版本不兼容和启动失败时，正常本地部署可实际发起并完成任务、Git 功能明确不可用；验证依赖可用后的实际取材，以及固定提交、大于 16 MiB 素材交接、归档上限、无权/失效引用、取消、未知结果、素材释放和原操作恢复。使用过 Git 的未完成任务须核对原提供方版本；原版本缺失时明确阻塞恢复，不能改用新版 Git 或本地来源替代原操作。

**完成条件**：平台可选依赖机制实际可用，Git 具有独立身份和公开合同，部署 Git 来源走新协作路径，缺少 Git 不阻断本地部署；不能保留一套旧内部实现作为独立化替代。首版仅满足部署源码拉取，提交、推送留至后续，不作为 1.3 实施或验收项。

### 3.5 S3：历史部署功能与界面完整迁移

- [ ] **S3-01 来源与分析**：补齐对照表中的项目保存/修订、来源筛选、单/多组件识别、运行配置和输入审阅，承接 S2 Git 来源。
- [ ] **S3-02 模式与环境准备**：补齐标准、辅助、自主模式及手工审阅、多组件编辑，核对环境、工具链、数据库声明和准备流程。
- [ ] **S3-03 构建与发布**：补齐素材归档、上传、目标机构建、原生/容器发布、健康检查、失败恢复和回滚。
- [ ] **S3-04 应用管理与过程恢复**：补齐应用列表、状态/访问、启停重启/自启动、已有外部发现与有限接管，以及任务、日志、取消、资源清理和中断核对。
- [ ] **S3-05 完整界面接线**：在 P0 根工作区内承接页面、弹窗、字段/默认值、帮助、高级选项、多组件和动态反馈；复用最小接线，不重新改造 APP 外壳。
- [ ] **S3-06 逐项关闭缺口**：通过真实产品入口核对业务与交互，将适用证据和未验证范围写回对照表；已迁入的有效实现只做必要适配与受影响回归。

**完成条件**：第 4 节历史真实部署清单的业务和界面都有当前落点及适用验证。不能以完成一个来源样例、一个部署模式或服务方法代替整套清单；独立备份/恢复/双服务器迁移仍不自动加入。

### 3.6 S4：文件管理 Mod

- [ ] **S4-01 单栏浏览**：在独立根工作区实现位置选择、路径与目录浏览，固定名称/类型两列，先完成单个目录的实际列表闭环。
- [ ] **S4-02 按需展开双栏**：右侧按钮展开结构对称的第二栏，两侧目录与选择独立，明确第二栏目录及收起状态规则。
- [ ] **S4-03 工具栏与右键**：实现共用动作的复制/剪切/粘贴、活动栏上下文及可用状态；普通项操作 File，链接项复制或剪切后粘贴操作 Link 自身。明确剪贴内容共享范围，剪切准备不修改源文件。
- [ ] **S4-04 新建目录与空文件**：提供新建文件夹、新建空白文件两个入口，文件名和扩展名由用户输入；复用平台创建接口，核对名称、同名冲突和实际创建结果，不增加文件类型模板列表。
- [ ] **S4-05 重命名与删除**：接通选中项的重命名、删除及相应确认/反馈，普通项操作 File，链接项默认调用 renameLink/deleteLink，仅操作链接自身；复用平台回收或永久删除合同，据实际结果刷新，不能由界面另写删除底座。
- [ ] **S4-06 跨栏拖拽**：默认移动到对侧当前目录，与剪切后粘贴共用平台移动流程；普通项移动 File，链接项移动 Link 自身，原件移动仅维护文件管理自身登记或本次明确纳入且获准更新的链接。界面和结果说明维护范围，跨主机无法表达新指向时尽可能提前拒绝；显式复制通过复制/粘贴提供，不另写文件或 SSH/SFTP 底座。
- [ ] **S4-07 扩展到已确认场景**：以本机目录及本机受控远端协议环境验证平台连接 Linux 的支持组合和管理操作，真实 Linux 单独验证；不默认增加两台远端间传输、同步或备份。
- [ ] **S4-08 核对结果与异常**：验证权限、同名冲突、链接语义、失败、取消、部分完成、待核对与实际列表刷新，覆盖受影响主题、锁定和关闭行为。

**完成条件**：第 7 节已确认的单栏/按需双栏、列表、新建文件夹/空白文件、重命名、删除、顶部与右键剪贴操作、默认拖拽移动，经实际界面和文件结果形成完整闭环。无此 Mod 时平台文件/连接能力及其他业务继续可用。

### 3.7 S5：Mod 商店连同下载

- [ ] **S5-01 确定获取与解析合同**：按第 8 节落实版本/依赖元数据、查询下载协议与包验证，准备本机受控目录/下载服务；列明客户端未接通项，形成可执行的完整依赖清单，正式远端来源验证另行安排。
- [ ] **S5-02 接入下载选择界面**：必需依赖自动加入清单，可选依赖由用户选择；需要获取依赖时，默认推荐满足完整依赖约束的最高可获取兼容版本，进入依赖详情/版本选择时定位并选中该版本。显示依赖用途、版本、约束及已安装状态，选择变化后同步更新其必需依赖；只有不存在满足要求的方案时才提示具体冲突，由用户调整并重新确认有效清单后下载。
- [ ] **S5-03 完成实际获取安装**：按确认后的清单复用既有下载、校验与安装流程，复用已安装兼容版本，逐包反馈实际结果；保留安装与启用的区别。
- [ ] **S5-04 验证失败与重复操作**：通过本机受控服务覆盖多级必需依赖、可选选择、重复依赖、最高兼容版本推荐、最新版本不兼容但旧版可用、精确/区间约束、版本冲突及用户调整后重新确认、依赖来源不可用、取消、部分失败与重试；冲突清单不启动下载，不静默删除可选项或切换运行版本。同时验证实际客户端通道，不以模拟适配器替代应有实现。

**完成条件**：客户端功能实现完整，经本机商店界面和受控服务完成主 Mod、必需/选中可选依赖的查询、下载、验证与安装，实际结果正确；未勾选的可选依赖不获取。完整本地验收通过即可完成 S5，正式商店服务未验证不阻塞。客户端通道未实现或只能模拟成功时，仍保留相应未完成状态。

### 3.8 S6：集成回归与交付核对

- [ ] **S6-01 执行适用检查**：沿用现有构建、测试、格式、架构及 API 文档检查入口；新目录和接口变化在前面阶段适配后纳入，按实际受影响范围验证。
- [ ] **S6-02 验证完整入口链**：从 APP 主导航进入部署、文件管理等有界面的 Mod，核对工作区环境/生命周期及业务入口；无界面的 Git 通过部署受管调用验收，不增加空入口。同时核对必需/可选依赖、Git 取材接部署、完整部署及 APP 商店连同下载。
- [ ] **S6-03 核对本地验收证据**：按第 9 节核对完整本机、受控服务和本机界面结果；真实 Linux/模型/正式商店服务作为单独未验证范围说明，不阻塞本期完成，也不用历史结果替代迁移后结果。
- [ ] **S6-04 同步正式文档**：更新实际落地的 File.md、AllFile.md、Mod.md、产品说明和本期完成状态；不提前索引计划目录，软件/API/Schema/helper 版本只按实际改动调整。
- [ ] **S6-05 完成收尾**：清理本任务产生的临时验证进程和文件，核对历史归档未被改写，记录后期视觉精修及其他后置范围。

**完成条件**：本期功能清单、实现、用户入口与完整适用本地验收一致，即可交付并认定 1.3 目标达成；必须完成的实现或本地验证缺口不能留在“已完成”状态。真实目标验证另行安排。必要检查通过后结束验证，只因新改动、失败或具体未决问题增加检查，不把全量重复测试当默认步骤。

<a id="deployment-migration"></a>

## 4. 部署功能与界面迁移规格

### 4.1 已确认目标

以历史部署的完整使用链为基准，逐项核对当前部署 Mod，保留已迁入且符合现行合同的实现，补齐遗漏和不完整接线，覆盖业务、界面、资源及实际使用入口。不能只完成服务方法、测试调用或简化页面就认定完整迁移，也不把已经迁入的功能重新实现一套。

### 4.2 历史功能对照范围

以下是已核对历史目录和说明后的功能分组，用于组织后续完整清单；它不是已经确认的缺失列表。

| 功能组 | 需要逐项核对的历史能力与入口 |
| --- | --- |
| 来源与项目 | 本地/Git 取材、固定提交、来源筛选与声明、项目保存及来源修订，分析与执行使用一致素材。 |
| 分析与运行配置 | 语言、构建工具、项目类型和运行时识别，单组件/多组件、配置补全、BUILD/RUNTIME 输入与秘密引用。 |
| 部署模式与审阅 | 标准、辅助、自主三种模式，以及已有手工审阅、多组件编辑、计划确认和必要审批的实际路径。 |
| 环境与数据库 | 受支持目标的环境检查、工具链准备与精确绑定、数据库声明/结构审阅、实例或版本检查及既有准备流程。 |
| 构建与发布 | 冻结素材归档、上传、目标机构建、资源和身份限制、原生/容器发布、健康检查、失败恢复和回滚。 |
| 应用管理 | 应用列表、状态与访问入口、启停重启及自启动、展示信息，以及历史已有的外部应用发现与有限接管。 |
| 过程与恢复 | 任务、进度、日志、错误与人工处理、取消及资源释放、未知结果和中断后的原操作核对。 |
| 部署界面 | 来源/服务器卡片、模式选择、表单与输入校验、高级选项、多组件编辑、应用管理、相关弹窗、动态反馈、结果及错误提示。 |

实现前建立逐项对照表，每项记录：历史功能与产品入口、源码/资源/测试依据、当前实现与入口、已迁移/部分迁移/未迁移/历史未实现状态、差异及适配理由、验收依据和未验证范围。引用历史版本或摘要，避免只以类名和文件数量证明覆盖。

备份、恢复和双服务器迁移作为独立业务仍按原 1.1 S6 后置。部署本身所需的回滚、恢复及已有公开交接合同属于本期核对范围，不以独立备份 Mod 暂缓为由删去部署能力。

### 4.3 界面迁移要求

- 按历史真实部署入口核对功能分组、导航衔接、字段与默认值、帮助、按钮、弹窗、高级选项及多组件交互，并补齐缺失接线；页面布局在本期工作区结构下适配。
- 保留模式差异、运行中状态、取消、进度日志、交付入口、错误与人工恢复反馈；成功提示必须与实际部署结果一致。
- 核对语言、浅深主题、窗口缩放与最小尺寸、锁定和页面关闭期间的行为；状态、进度与结果等动态反馈纳入功能对照，纯动画表现和外形细节按[首版界面优先级](#ui-first-delivery)后期精修。
- 部署界面挂载于当前 APP 平台，复用其服务器、模型、任务、审批和页面生命周期；共享平台界面通过现行接口衔接，不复制一套旧桌面宿主。
- 部署只占一个主导航入口；标准/辅助、自主部署、应用管理及相关功能收在部署工作区内部。历史交互按新的导航层级承接，仍须逐项核对功能覆盖。

完整迁移指历史功能与交互在当前架构下得到完整承接。旧 `shared` 结构、旧 SQLite 存储、私有宿主访问和过时安全行为不照搬；与现行合同冲突时记录适配方案、差异与验证，不能用架构调整掩盖功能删减。

### 4.4 S0 逐项历史对照（2026-10-05）

核对冻结历史源码、资源、产品说明与有效测试，形成以下 36 项功能及真实入口对照。冻结产品说明 SHA-256 为 `328F13ABA26BF75B346E006922EF6EBD5B5F86B2485E16E2D69F7530E4766A05`，冻结集合 2836 文件校验通过。详细只读审计证据保留于 `.ai-workspace/progress/phase13-evidence/history-deployment-audit.md`。

“已迁移”在本表表示代码与产品入口链已追踪，不表示当前本机验收通过；“部分迁移”“未迁移”保留具体缺口。S3 按实际实施及验收更新本表，并记录有效命令/结果。与历史架构冲突的合法适配保持现行 APP/H2/公开 API 合同；历史预览或未实现设想不作为已实现功能。

#### 路径缩写


以下缩写仅用于表格压缩，均为仓库根的真实路径：

- `HUI` = `history-src/src/app/ui/src/main/java/gold/debug/windowstolinux/app/ui/`；`HSVC` = `history-src/src/app/service/src/main/java/gold/debug/windowstolinux/app/service/`。
- `HSTD` = `history-src/src/shared/standard/`；`HDEP` = `history-src/src/shared/deploy/src/main/java/gold/debug/windowstolinux/shared/deploy/`；`HAG` = `history-src/src/shared/agent/src/main/java/gold/debug/windowstolinux/shared/agent/`。
- `D` = `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/`；`DR` = `src/mod/deployment/src/main/resources/gold/debug/windowstolinux/mod/deployment/`。
- `MAN` = 冻结产品说明 `history-src/docs/PRODUCT-MANUAL.md`；`HT` = 对应历史模块 `src/test/java`；`DT` = `src/mod/deployment/src/test/java/gold/debug/windowstolinux/mod/deployment/`。
- 当前实际 UI：APP 主导航“部署”→`D/ui/DeploymentWorkspace`→标准/辅助、自主、应用管理内部页。`Mod.onEnable` 已注册原发布、Git、秘密、标准查询、自主、应用查询/操作恢复处理器。P0 单入口不重复实现。

#### 功能和产品入口逐项表

| ID / 用户功能及历史入口 | 历史依据与实际行为 | 当前路径、方法及真实 UI 入口 | 状态、差异和必要适配 | 本机验收（受控服务/隔离数据） |
| --- | --- | --- | --- | --- |
| HD-01 本地源码选择、冻结并一致分析执行 | MAN §6；HUI `deployment/automatic/DeploymentSourceCard` 的选择/识别；HSVC `source/SourcePreparationUseCase` 与 `ReviewedSourcePreparation` 绑定归档/修订 | 标准页 `DeploymentStaticPage.submit`→`DeploymentStaticWorkflow.prepare`→APP `sources.selectDirectory`→`D/file/DeploymentSourceSetup.prepare`→冻结快照→归档；自主 `submitLocal` 同样用受管来源 | **部分迁移**。实际选材、冻结已接入；选择器发生在启动任务后，主卡片不能预先展示已选目录、清除、更换、拖入/粘贴。业务冻结可复用；补来源状态及交互，不能仅加路径文本伪造授权 | DT `DeploymentSourceSetupTest`、`DeploymentSourceSelectionMigrationTest`、`DeploymentStaticWorkflowTest`；选材取消、源文件随后变化、反复选择与已选时拒绝新拖入/粘贴、清除后重选 |
| HD-02 Git 默认分支/分支/标签/提交，分析前固定提交 | MAN §6；历史 `shared/git/.../GitSnapshotPreparer`，HUI 来源卡片与高级引用字段；历史 `SourcePreparationUseCaseTest` 核验实际 commit | 标准来源模式 GIT→`DeploymentGitSourceForm.source`→`DeploymentGitSourceFlow.prepareForPublication`→`file/git/DeploymentGitSetup`；自主 `DeploymentAgentGitForm`→`submitGit` | **部分迁移（S2 前置）**。固定提交链已接入，Git 实现仍在部署私有包；需 S2 抽取独立可选 Git Mod 与平台素材引用，保持大素材/冻结身份及本地独立可用 | DT `DeploymentGitSourceFlowTest`、`DeploymentGitSetupTest`、`DeploymentGitCancellationTest`；默认分支、三种明确引用、分支改变后仍分析/发布原 commit、无 Git Mod 本地部署、>16 MiB 引用交接 |
| HD-03 来源筛选与显式 source.include | MAN §6、末尾 source.include 限32条精确路径且不越秘密边界；历史安全归档与 `ApplicationDeclaration` | `D/file/DeploymentSourceSetup.prepare/explicit`→`DeploymentSourcePolicy.explicit`；归档 `DeploymentStaticSourceSetup.archives` 按同一冻结素材 | **已迁移（代码链）**。受管文件/快照 API 取代旧 shared/source；默认排除与显式纳入仍属部署业务 | DT `DeploymentSourceArchiverTest`、`DeploymentSourceSetupTest`；样例日志显式纳入、密钥/链接/越界/超限拒绝、源清单和 tar 身份一致 |
| HD-04 项目保存及来源修订 | 历史桌面记录 `SourceRevision` 随发布持久化；未找到独立“项目列表/修订来源”桌面入口。历史 Web `WebSourceService.begin/upload/prepareGit/prepareAnalysis` 有 SOURCE 资源及修订；不等于桌面已有项目 CRUD | `D/db/DeploymentProjectStore.create/update/find/list`；`DeploymentGitSourceFlow.PublishSource.save` 发布前保存；`submit(... UUID project...)` 支持更新原记录；现有 UI 仅 Git 新来源发布保存，无已有项目列表/来源修订入口，本地发布 project 为空 | **部分迁移**。Git 后端保存/原记录修订已实现，普通产品入口缺失。需按第4节落实项目保存/来源修订可达入口；勿误写历史桌面已存在完整项目 CRUD，也勿把旧 Web 整体迁入 | DT `DeploymentProjectStoreTest`、`DeploymentGitSourceFlowTest`；保存后列表→选原项目→修订来源→身份/时间 CAS→分析/发布同一素材；本地项目保存合同开工时明确 |
| HD-05 语言、构建工具、运行类型与支持级别 | MAN §7 十四种运行时；HSTD analyze 的 `DeploymentAnalysisCoordinator`、语言/锁文件/入口检查；历史 `MultilingualDeploymentPlanningTest`、单/多语言 fixture | `D/service/standard/analyze/core/DeploymentAnalysisFlow`；`ProjectPartFinder`、各生态 checker；`DeploymentStaticUnitResolver`→标准 `prepareSingle/graphPrompts`；自主不要求先过静态识别 | **已迁移（代码链）**。保留语言/锁文件准入与正式/试验差异；不能把“语言存在”推成任意框架支持 | DT `DeploymentEcosystemFixtureMatrixTest`、`DeploymentStandardAnalyzerTest`、`DeploymentMultilingualPlanningTest`；十四类代表性识别、冲突与不支持拒绝，静态模式不可执行本地源码 |
| HD-06 普通配置 BUILD/RUNTIME 类型、不可变修订/摘要 | MAN §8；历史配置 parser/renderer，发布绑定实际 SHA-256 | 标准运行审阅 `DeploymentStaticPageReviewer.PartForm` 的 configuration→`DeploymentStaticInputParser`→`DeploymentConfigSnapshot`→`DeploymentConfigStore`；`answers` 获取下一修订；发布器持久绑定 | **已迁移（代码链）**。旧 SQLite 改为所属 Mod 受管 H2；配置文本编辑仍真实转换为类型/作用域 | DT `DeploymentConfigSnapshotTest`、`DeploymentConfigStoreTest`；文本/整数/布尔、BUILD只进入构建、RUNTIME运行注入、敏感键拒绝、旧修订不被覆盖 |
| HD-07 秘密保存、精确修订引用与校验 | MAN §8；HUI `DeploymentPage.saveSecret`，高级 secretReferences；历史秘密缓冲与引用链 | 标准高级 `DeploymentStaticSecretControls`→`DeploymentStaticPageService.createSecret`→`DeploymentSecretProvisionFlow`；运行表单 `DeploymentSecretRefField`；发布前 `credentials` 核对 credential ID/purpose/revision | **已迁移（代码链）**。值交 APP 凭据能力，业务保存精确引用；须验证锁定/关闭和失败后恢复 | DT 秘密流程相关测试及 `DeploymentOrdinaryRuntimeFormTest`；多修订共存、缺修订/错 purpose 拒绝、一次性密码不进状态/日志、取消/未知创建在原任务核对 |
| HD-08 运行高级字段及补全输入 | HUI `DeploymentPage.configureAdvanced`：类型、applicationDeclaration、HTTP/TCP/UDP/PROCESS/COMMAND、endpoint/status/timeout/stability、访问URL、主/次入口/版本/JVM目标/JVM参数/应用参数、容器引擎/ports/volumes、配置/DB/秘密、试验风险；`DeploymentInputDialog` 保留同任务修正 | 标准 `DeploymentStaticWorkflow.prompt`→`DeploymentStaticRuntimeResolver.defaults/corrections/missing`→`DeploymentStaticPageReviewer.review/PartForm`，校验失败留同一表单；构建修正限制 primary/secondary/version/jvmTarget | **部分迁移**。字段解析大体承接，改为任务中逐组件审阅弹窗，不能在启动前预填/保持旧高级草稿；历史 tooltip/helpKey 未用于 PartForm。补合理预填与帮助，不必复刻旧侧栏布局 | DT `DeploymentOrdinaryRuntimeFormTest`、`DeploymentBuildCorrectionPageTest`；逐字段默认值/边界/错误回显、同任务修正、预填优先、长声明多行可编辑、键盘操作/帮助 |
| HD-09 APP/一次性工具、端点/输入/配套C/worker 声明 | MAN“已部署应用、后台 APP 与一次性工具”；历史 ApplicationDeclaration 与相关 helper input/runtime/health 片段 | `D/.../input/ApplicationSpec.read/applyText/resolve` 支持 mode、endpoints、inputs、companions、workers、source.include；当前 helper `input/16-application-input.sh`、`runtime/41-application-runtime.sh`、`44-application-health.sh`；发布核对 ON_DEMAND 为 INSTALLED | **部分迁移**。声明/构建/原生运行生产链承接；产品交付与应用管理的命令、端点和一次性按钮差异见HD-27。不需给无端口 APP 添加占位 port | DT 应用 bundle/runtime/planning 测试及 helper 离线测试；ON_DEMAND安装自检、参数中字面空格/中文/$、worker统一终止/重启、只读输入、UDP监听及响应、容器多进程仍由镜像管理 |
| HD-10 自动发现多组件、依赖与健康代表 | MAN §10；历史 `ProjectComponentDiscovery`、`MultiComponentDeploymentUseCase`、依赖检查/全图原子持久化 | 标准 `ProjectPartFinder.discover`→`reviewer.select`→`SelectionForm` 选每组件类型/依赖/健康代表→`prepareGraph`→`PartGraphChecker`→多组件 runner/publisher | **已迁移（代码链）**。单根冻结素材、最多16组件、同一任务已接入；图确认不等于只有单组件循环 | DT `ProjectPartFinderTest`、`MixedProjectCheckerTest`、`DeploymentMultiPartPublishRunnerTest`；重复、端口冲突、未知依赖/环、依赖顺序、整图健康失败不提交 |
| HD-11 多组件手工编辑、增删、源码相对根、必需项、产物/端口及草稿失效 | HUI `DeploymentPage.configureAdvanced` 手工按钮通过 `DesktopPageCoordinator` 实际打开 components；`MultiComponentPage.createPanel/graphPanel/addOrUpdate/removeSelected/analyze/deploy`；草稿字段含应用根/ID、组件ID/relativeRoot、type、artifacts、ports、dependencies、required、运行配置/DB | 当前只有 `SelectionForm` 对发现结果选择类型、填写依赖、健康代表；没有手工 components 页面、增删组件/相对根/required/artifacts 编辑入口；`validSelection` 要求选择集等于发现集 | **未迁移（手工入口）/部分迁移（图后端）**。补工作区内部手工编辑器，复用分析/规划/原发布事务；不能靠原发现列表弹窗认定承接全部手工功能 | 迁移历史 `ComponentFormUseCaseTest`、`MultiComponentDeploymentUseCaseTest`；增删改后旧审阅失效、每组件DB显式范围、组件源码不能越冻结根、必需项/依赖/健康全图测试及实际UI |
| HD-12 静态、AI辅助、自主模式和偏好快照 | MAN §16；历史 `DeploymentModeSelector`、`DeploymentPage.load/savePreferences`；任务冻结模式/审批 | 根内部标准/辅助页有 assisted开关+三种审批下拉；自主独立内部页；`DeploymentPreferenceSelection` 与 `DeploymentPreferences` 保存失败还原；任务 `submit` 抓取值 | **部分迁移**。三条真实执行路径存在，单入口适配合理；模式呈现拆成页面+开关，需确保保存AGENT偏好与当前内部页一致，以及三模式的默认/描述/冻结行为真实一致 | DT `DeploymentPreferencePageTest`、`DeploymentStaticPageTest`、`DeploymentAgentPageTest`；首次辅助+自动、非法值拒绝不覆写、保存失败回滚、跨页/语言/主题不改输入、运行中冻结 |
| HD-13 静态模式全程无AI | MAN §16.1；历史静态链无模型调用，包括失败分支 | 标准 `submit(...assisted=false...)`→`prepare` advice为空→静态 candidate/publish/recovery；实际 AI仅 assisted/agent路由 | **已迁移（代码链）**。仍须用拒绝全部AI请求的夹具完整验证失败/恢复分支 | DT static/recovery/candidate/publish 测试；缺模型仍静态可用，AI调用计数为0，包括构建失败/健康失败/回滚 |
| HD-14 辅助分析补参、部署前检查、失败修正及有限重试 | MAN §16.1；HSVC `AssistedDeploymentBoundary`；只读冻结源码、证据绑定、参数校验/用户值优先、最多两次有效重试，未知不重放 | `DeploymentStaticAssistance`→`deploy/assistance/DeploymentAssistedAnalysis/Advisor`；`DeploymentAssistedPublishBuild/Attempts` 有原候选/重试预算；`manualContinuation` 明确宿主批准后人工继续 | **已迁移（主要代码链）；审批差异见HD-16**。不能因此认为完整模型/真实Linux验证通过 | DT `DeploymentAssistedBuildWorkflowTest`、`DeploymentAssistedEnvironmentRecoveryRestartTest`；有效修正才重试、无变化停止、同任务两次总预算、模型不可用明确人工继续、UNKNOWN仅核对 |
| HD-15 自主读取/搜索/分页、计划、命令、受保护源码补丁、发布全图健康 | MAN §16.1；HAG autonomous session、SourcePatchApproval；HSVC `AutonomousDeploymentUseCase` 交公共发布 | 自主页 `submitLocal/submitGit`→`DeploymentAgentWorkflow`→`DeploymentAgentSession`、`DeploymentAgentRemoteTools.read/command/patch/seal/deliver`→公共 graph publisher | **已迁移（主要代码链）；审批差异见HD-16**。真实自主逻辑存在，不只是固定计划；补丁仅改任务Linux副本，归档仍冻结原身份 | DT `DeploymentAgentWorkflowTest`、`DeploymentAgentPlanProtocolTest`、`DeploymentAgentTargetAdmissionTest`；不支持静态类型仍可读并规划、真实失败改命令、补丁前摘要、身份/修订变化使旧构建失效 |
| HD-16 AI三审批策略：所有实际命令/脚本与源码补丁均需独立AI，人工模式再确认 | MAN §16.2；HDEP `approval/CommandApprovalAudit.before` 无条件逐段 `reviewer.review`→`DeploymentApprovalGate.admit`，再 `needsHuman`；HAG `SourcePatchApproval.approve` 同样无条件AI后逐行人工。现行 `docs/Mod.md:779/1250` 明确迁移不能削弱独立AI，平台授权与AI独立；现行PRODUCT-MANUAL:509仍保留固定工具命令要求 | `DeploymentAgentReview.command/patch` 用 `mode.equals("MANUAL_REVIEW") || review(...)` 短路；`DeploymentAssistedApproval.approve` 人工模式直接host.approve；`DeploymentAssistedScope.freeze` 和 `DeploymentAgentModels` 人工模式不要求APPROVAL；自主 RemoteTools 使用原 `context.interaction()` 执行固定helper操作 | **部分迁移，规范/实现/测试语义冲突，等待用户确认**。人工模式跳过独立AI是当前测试明确断言的行为；自主固定工具安装、候选/发布命令也未找到独立AI包装。未找到较晚文档明确豁免人工模式或固定工具命令；固定静态命令仅需平台授权不等于AI流程内可免独立审批。不能未经确认直接改已测试合同，亦不能把测试通过写为符合现行文字；详见下方冲突证据 | DT `DeploymentAgentReviewTest`、`DeploymentAgentEffectEvidenceTest`；按最终确认政策核对每条命令/完整stdin、补丁、模型缺失/拒绝、配置/目标/修订改变重审；若遵循现行文字，需新增三策略均调用审批模型、人工AI拒绝远端0发送及固定helper审核断言 |
| HD-17 暂停/恢复、模型顺序刷新/接替、事件/任务历史、原操作核对 | MAN §16.4；HUI `DeploymentTaskControls` 任务按钮/历史/inspect；HSVC `AutomaticDeploymentTaskService`、agent control/session | 自主页 `DeploymentAgentEvidencePanel` 提供 pause/resume/refreshModels/history/events/inspect→`DeploymentAgentPageService`→`DeploymentAgentControl`、`DeploymentAgentHistory`；恢复 `DeploymentAgentRecovery` | **已迁移（代码链）**。当前以业务ID和非秘密事件通过平台任务观察；历史旧AGENT不自动续写 | DT `DeploymentAgentControlTest`、`DeploymentAgentHistoryTest`、`DeploymentAgentRecoveryRestartTest`；仅安全边界暂停、刷新明确采用新修订、原任务继续、未知命令只核对不重放、重开历史 |
| HD-18 服务器选择、首次指纹、已保存服务器就地新增/清除/摘要 | MAN §2/5；HUI 来源右侧 ServerSelectionCard/ServerProfileDialog；历史首次主机人工信任、不一致拒绝 | 标准/自主页 service.servers分页读取APP server→JComboBox；连接由APP linux.connect。APP `PlatformServersPane`/`ServerProfileDialog` 负责服务器资料 | **部分迁移**。基础存储/连接为APP适配；部署卡片无就地添加、明确清除、不自动选中及环境悬停摘要/刷新列表入口。须用公开UI合同跳转/共享弹窗，不能复制旧宿主或读私有资料 | DT 页面测试+APP服务器/主机信任测试；无服务器→就地新增→返回可选；清除后刷新不自动选中；指纹改变0业务发送；UI遮罩不泄资料 |
| HD-19 目标环境事实及支持拒绝 | MAN §5/9.5；历史 Linux能力事实与HostSupportEvaluator | `DeploymentPlatformCapabilityProbe/Parser`、`DeploymentCandidateChannel.hostFacts`→`DeploymentHostMatcher`；标准 candidate准备先核对发行版/CPU/安全/空间/运行时 | **已迁移（代码链）**。使用APP受管session取实际事实；不可把残留environment文案当流程 | DT candidate/platform matcher等测试；未支持系统/架构、安全态不满足、空间不足时无源码上传 |
| HD-20 系统工具/APT-DNF/OpenSSH更新、准备后新连接验证 | MAN §5.1；HSVC `execution/environment/EnvironmentSetupUseCase`→HDEP `EnvironmentSetupService.prepare`→目标固定 prepareEnvironment→重新认证/重采能力；HUI `SystemPreparationDialog` 默认取消 | 标准 `DeploymentProtocolBootstrap.ensure`→`DeploymentManagedHelper.installTracked`→`DeploymentHelperInstallerScript` 当前APT/DNF固定包安装helper；未找到旧环境完整流程；APP `environment.confirm` 只是消息资源残留 | **部分迁移**。helper安装/原回执恢复存在；历史DNF9兼容OpenSSH更新、CRB事务及安装后重认证合同缺接线。补Mod环境准备业务+平台连接重认证合同 | 历史 `EnvironmentSetupServiceTest` 定向迁移；本机受控命令/连接夹具：默认拒绝0副作用、批准包集合及发行版正确、断线需新受信连接才报告成功、已安装回执UNKNOWN不重放 |
| HD-21 CentOS SELinux独立变更批准、标签修复、重启与强制态重认证、断点续接 | MAN §5.1；HDEP `SelinuxPreparationService.prepare` 明确单独批准，准备/重启/enforce/commit；历史 `SelinuxPreparationServiceTest` 覆盖拒绝/超时/指纹变化/中断续接 | 当前 `DeploymentDistributionMatchRules` 要求enforcing，未找到生产SELinux准备/恢复服务或弹窗；当前资源有运行安全entry不等于系统准备流程 | **未迁移（准备流程）**。当前非enforcing拒绝是安全拒绝，不能代替历史已实现准备功能；S3补经独立批准的持久原操作链，不静默reboot或改安全态 | 迁移历史上述8种以上场景，离线/受控连接模拟重启、标签修复、两次认证；无需真实CentOS作为本期门槛 |
| HD-22 工具链项目要求、目录候选、准备和精确绑定 | MAN §7；历史 ProjectToolchainRequirements、ToolchainSelectionPolicy及helper toolchain片段 | `DeploymentProjectToolchainRequirements`→`DeploymentToolchainSetup`→`DeploymentBuildCommandChannel` 固定prepare/bind→`DeploymentResolvedToolchains`；candidate准备前后复核 | **已迁移（代码链）**。项目声明/选择/实际安装分离，不改源码/lock；仍需准备风险在HD-16全链审核 | DT `ToolchainSpecCheckerTest`、multilingual/candidate测试、helper离线测试；复用精确版本、版本不匹配/摘要改变拒绝、官方目录受限、安装原操作恢复 |
| HD-23 DB声明/缺项/初始化文件/结构审批，显式空范围 | MAN §9.2/9.3；历史 DatabaseProjectInspector、AutomaticDatabaseUseCase，Db缺项同任务纠正、已库结构单独批准 | 标准 `DeploymentDatabaseStages.local/native`→`DatabaseProjectChecker`/`DeploymentDatabaseSourceInputs`；reviewer.databaseInputs/existingSchema；显式bindings/空范围进入plan | **已迁移（代码链）**。数据库源码声明与连接输入独立审阅，不自动把外部端点改成本地 | DT `DatabaseProjectCheckerTest`、`DeploymentDatabaseSourceInputsTest`、`DeploymentStaticWorkflowTest`；多个同名/未知初始化需补填、每库SQL对应、Redis拒SQL、DB范围冲突、已有结构独立批准 |
| HD-24 原生PG/MySQL/MariaDB/Redis实例发现/启动/复用/安装/替换/人工恢复 | MAN §9.3；历史 NativeDatabasePreparationService 与明确替换三项确认 | `DeploymentNativeDatabaseSetup`→`DeploymentNativeDatabaseChannel`→固定helper native-db；reviewer.choose/install/replacement/restored/administratorPassword/applicationPassword；replacement三独立初始false且同表单错误 | **已迁移（代码链）**。未知版本不当未安装、多个实例选择、停止也算安装，替换绑实例/version并复核。UI使用checkbox代替旧左右开关是展示适配，独立确认须保留 | DT `DeploymentNativeDatabaseOperationTest`、`DeploymentNativeDatabaseRecoveryRestartTest`、Python native_db测试；安装/替换/中断/UNKNOWN只原status；三项未齐不能执行、不清data、手工恢复后实测再继续 |
| HD-25 SQLite/FHS、持久文件/只读配置、容器受管bind/种子 | MAN §9.4；历史 storage declaration、SqlitePreparation、helper managed-storage | `DeploymentStoragePlan`、`DeploymentStaticReviewBuilder`、`DeploymentDatabaseStages`/SQLite setup；reviewer.confirmSqliteInitialization、resource file rows；当前helper resource片段 | **已迁移（代码链）**。原默认FHS和受限CUSTOM仍在；不搬迁旧远端布局，配置不可变/数据保留 | DT `DeploymentSqliteSetupTest`、`DeploymentResourceCodecTest`、helper sqlite/storage测试；默认/custom路径拒绝越界、种子仅一次、初始化后重试只验证、容器路径/只读根、更新回滚保留数据 |
| HD-26 冻结归档上传、目标机构建、容量/身份/资源限额 | MAN §9.2/9.5；历史 safe archive、build renderer、ReviewedDeploymentService，低权临时构建/每组件运行账号 | 标准 `DeploymentStaticSourceSetup.archives`→`DeploymentStandardCandidateRunner.prepare/build`→`DeploymentStandardBuildRunner`/toolchain→candidate.create/upload/verify/build；PartForm默认1800s/1024进程/4096MiB/4MiB输出/4GiB空间 | **已迁移（代码链）**。容量先核后传、构建Linux而非本地；预算和清理独立；AI模式审核另见HD-16 | DT `DeploymentStandardCandidateRunnerTest`、candidate/build/budget测试与helper离线测试；超限/摘要不符/低权身份/输出截断/构建失败、上传前容量拒绝、业务成功但清理未完成单列 |
| HD-27 原生/容器发布、应用整体健康、失败旧版恢复、历史版回滚 | MAN §9/10/11；HSTD deploy ReviewedDeploymentService/ReviewedMultiComponentDeploymentService，历史 `ReviewedDeploymentServiceTest`/回滚实机记录仅参考 | `DeploymentStandardPublishRunner`→`DeploymentStandardPublishTransaction`；graph runner/publisher；`DeploymentOriginalPublishCommit`；应用页history→`DeploymentRetainedReleases.switchRelease`；helper release/health/restore片段 | **已迁移（代码链）**。原生/systemd及Docker/Podman均有实际操作协议，持久图/配置/秘密/资源绑验证结果；业务失败、回滚成功、清理未完/UNKNOWN不能混成成功 | DT `DeploymentStandardPublishRunnerTest`、`DeploymentMultiPartPublishRunnerTest`、`DeploymentUnknownHealthPublishTest`、`DeploymentPreviousRunningPublishTest`、restart recovery测试；旧版原运行/停止状态、端口冲突、图中间失败、回滚健康失败、选历史退休版 |
| HD-28 成功交付网址/多端点/服务器命令，点击/复制且只成功显示 | MAN §2/9/APP工具；HUI `DeploymentPage.showHandoff` 和 `ManagedPage.addUsage`/open/copyCommand，失败/取消无成功入口 | 标准/自主页refresh仅显示 task ID/state/outcome/progressText；应用页`showSelection`仅文本 accessUrl/资源/状态，没有browse/clipboard/usage command调用 | **部分迁移（持久运行事实）/未迁移（交付交互）**。补根据已验证运行声明生成的访问/使用信息和操作，不以模型文字或task SUCCEEDED生成任意URL/命令；多组件需分别列入口 | DT增 product UI+服务结果测试；成功HTTP(S)点击/复制，TCP/UDP端点，后台client/管理命令，ON_DEMAND受管命令；失败/UNKNOWN/取消/回滚不显示新发布成功交付 |
| HD-29 应用列表、网站/APP和服务器筛选、状态/归属/时间/资源与展示信息编辑 | MAN §11/APP分类；HUI `ManagedPage.refresh/render/card`；ApplicationInventoryPageTest；历史 presentation dialog | 应用管理 `DeploymentApplicationsPage.refresh/filter/showSelection/edit`→`DeploymentApplicationsService.inventory/presentation/edit`→ApplicationList/Presentation，支持managed/external、类别/服务器、名称/URL及时间 | **已迁移（主要代码链）**。分类由经审阅服务声明决定，不能编辑managed类型绕过；详情/命令缺口HD-28 | DT `DeploymentApplicationListTest`、`DeploymentApplicationPresentationTest`、页面测试；组合过滤保留选项/无匹配清空、 stopped也保留、保存冲突、URL编辑/按声明分类 |
| HD-30 启停重启、自启动及ON_DEMAND正确可用性 | MAN §11/APP工具；HUI `ManagedPage.card` 对usage.lifecycle=false禁用非refresh；历史 lifecycle服务按真实身份/运行时回观测 | 应用页枚举全部生命周期→service.lifecycle→`DeploymentManagedOperations`，受管与外部都真实接入；`availability`只按busy/lock/managed/external部分控制，无ON_DEMAND禁用逻辑 | **部分迁移**。常驻功能已接，ON_DEMAND界面仍展示并启用启停/重启/自启；需按运行声明隐藏/禁用，并显示“已安装可用”与命令。不得把helper最终拒绝当作正确产品交互 | DT `DeploymentApplicationLifecycleTest`、`DeploymentPublishedApplicationStopTest`；常驻六动作权威观测、图顺逆序、ON_DEMAND无不适用动作且不发远端、旧缺声明提示重分析 |
| HD-31 外部应用发现、只读问题反馈、有限接管及生命周期/展示编辑 | HUI `ApplicationScanDialog`→HSVC `ExternalApplicationUseCase`；历史 `shared/linux-sshd` external port；非受管仅有限控制，不复制数据 | 应用管理scan/adopt→`DeploymentApplicationsService`→`DeploymentExternalApplications.scan/adopt/register/execute/edit`；固定Python inventory资源；操作前复核kind/identity/fingerprint/endpoint；reattach独立审阅 | **已迁移（代码链）**。有限外部登记保留，reattach绑原指纹/服务器；不等于迁移后持久接管或全管理 | DT `DeploymentExternalApplicationsTest`；systemd/docker/podman扫描、拒受管重复/陌生指纹、接管前重核变化、只允许固定动作、展示编辑、原服务器资料改变需独立重绑 |
| HD-32 任务进度、持续日志、错误与人工处理可达 | MAN §2/17；HUI DeploymentPage.append+DeploymentTaskControls，历史请求事件/错误报告、自动流程持续动作反馈 | 标准refresh仅快照文本，调用于创建/开始/结束/手动refresh；自主同样快照，EvidencePanel有events/history；应用页任务区也快照。没有标准页持续事件订阅/轮询与任务详情人工处理入口 | **部分迁移**。平台任务与事件业务存储可复用，补标准/自主页持续反馈、事件错误上下文、原任务详情/核对入口，秘密过滤/锁遮罩必须继承；不把固定preview反馈算实现 | DT static/agent/application page相关测试；受控长任务阶段可见、无重复事件、错误/WAITING/UNKNOWN可读且指向原操作、重新打开恢复原任务历史、语言状态映射 |
| HD-33 用户显式取消、视图关闭/锁定、资源释放 | 历史 DeploymentTaskControls.cancel；MAN §2/16/17/锁定段：后台任务继续、等待人工输入，不能自动批准 | 自主页cancel→current.requestCancel；应用页cancel；标准页仅revoke时取消unconfirmed，无显式取消按钮，但APP“任务与恢复”有请求取消。已confirmed关闭不取消；标准lock会revoke未答审阅→取消 | **部分迁移（内部任务控制入口）/有依据适配（锁定关闭）**。标准取消可经APP任务页，工作区缺直接控制；补关联原任务的可达跳转/控制，不另造任务系统。现行PRODUCT-MANUAL“任务与恢复”明确锁定/关闭撤销未提交确认，P0关闭确认后丢输入且已接受后台任务继续，故不能直接套历史等待行为认定回归；仍须验证取消/等待/继续实际状态与现行合同一致 | DT workspace/static/agent/task恢复；确认前/构建中/发布中取消、取消不等强杀/清理成功、锁定弹窗不批准、关页后台保留、APP原任务取消可达、资源仅确定收尾后释放 |
| HD-34 未知结果/中断原操作核对、防重放和重启恢复 | MAN §16/17；历史 CommandApprovalAudit.unknown/inspect任务，恢复失败人工处理 | Mod.onEnable恢复注册→`DeploymentStaticTaskRecovery`、`DeploymentStandardPublishRecovery`、`DeploymentPublishAttemptRecovery`、`DeploymentNativeDatabaseSetup.reconcile`、`DeploymentAgentRecovery`、management reconcile；原operation receipt/status与素材占用 | **已迁移（恢复核心代码链）/部分迁移（标准UI入口）**。保留原operation/task/源身份，不重新拉取或生成新写操作；UI需HD-32可达。Git独立化后保持原操作归属 | DT `DeploymentStaticTaskRecoveryTest`、publish/native-db/agent restart恢复、Python `test_managed_current_gate.py`、`test_handoff_operation_fence.py`；重启后原状态查证、缺素材/身份错拒绝、UNKNOWN不自动重放、已核失败清理/释放 |
| HD-35 高级选项/帮助/校验/弹窗与动态反馈 | MAN §2；历史 AdvancedOptionsPane长标签/独立问号、聚焦帮助、收起保参/修改提示；系统/DB/风险/输入/主机指纹弹窗 | 当前 `DeploymentPageStyle.Advanced` 所属modeless窗口默认未开且hide保控件；标准ReviewForm/SelectionForm/DB/secret弹窗真实；PartForm标签未使用DeploymentReviewField.helpKey，少量tooltip不构成全帮助 | **部分迁移**。独立高级窗口为当前布局适配无需旧侧栏；补真实字段帮助、键盘聚焦、修改提示、长字段可读、校验定位、风险/取消默认与运行可用性。所有动态反馈也须本地化 | DT `DeploymentRestyledWindowTest`、`DeploymentRuntimePreviewTest`、`DeploymentOrdinaryRuntimeFormTest`并实际UI；浅深/中英/缩放/最小窗口，弹窗不遮挡、帮助和输入保留、锁定从属窗口隐藏、运行反馈 |
| HD-36 独立服务器/AI/主机信任/审批等平台界面与预览 | MAN §2/5/16，HUI ServerProfileDialog、AiProviderDialog、UiDebugDialog；固定示例不执行真实业务 | APP保留服务器/模型/信任/任务/权限/锁定；Mod只经公开上下文调用，RuntimePreview以纯固定值展示运行表单 | **部分迁移（部署跨入口衔接）**。平台功能不复制到Mod；补缺模型、缺服务器时明确跳转/共享入口及返回；preview不证明实际业务实现或提供方可用 | 实际UI受控模型/服务器配置→回部署刷新可用、信任拒绝、审批拒绝；CLASS preview零副作用；JAR/APP不暴露CLASS调试入口 |

#### 历史未实现与后置范围

| 范围 | 历史事实与本期处理 |
| --- | --- |
| 独立备份、受管恢复产品页、双服务器离线迁移 | 历史有实际业务实现，但 PHASE-1.3 明确后置，不因归档存在拉入S3。部署内部健康失败恢复/回滚、已存在公开handoff合同及其受影响回归仍在本期。 |
| 人工外部切流后的目标持久接管 | MAN §15.3/19明确未实现；不要与已实现的外部应用有限接管/重新绑定混淆，不补入本期。 |
| 提交/推送、独立全功能Git工作区 | Git首版仅部署取材，当前计划已明确后置；历史shared/git取材不证明日常Git操作功能存在。 |
| MongoDB自动安装、Redis/多数据库完整备份 | MAN §9.3/19明确缺少；现有拒绝边界保留，不因DB模型枚举存在新增为本期部署功能。 |
| 旧Web整体宿主/在线执行服务台、官网生产服务、安全上线/更新卸载入口 | MAN §19/21区分旧内部Web与历史未实施规划；第4节迁移部署业务/交互，不搬旧Web宿主。Web source资源/修订只作为HD-04历史依据；新平台/正式官网发行继续原计划。 |
| UI预览扫描固定应用、固定环境/DB数据 | MAN §2明确预览不执行保存/部署/接管/真实AI；不得作为已迁移或已完成验收证据。 |
| 实际Linux/数据库/模型完整矩阵 | 历史MAN §18和test VERIFICATION均明确版本/夹具与RUNTIME-PENDING边界。本次不补历史实机记录，也不把历史数量写成当前验收。1.3适用本机验收完成即可，真实服务另行安排。 |

### 4.5 S0 分派与未决合同

- **S2 前置**：HD-02/04/34 的 Git 提取、素材持有/修订与原操作恢复，保持原程序与原图，不给旧任务事后更改归属。
- **S3-01、02、05**：HD-01/04/08/11/12/18/35/36 的项目/来源、目标选择、手工多组件、运行输入、模式/帮助与平台入口衔接，复用当前后端。普通本地来源保存为项目的最小入口在 S3 落实，不声称历史桌面已有独立项目 CRUD 页面。
- **S3-02、03**：HD-20/21 的系统环境/OpenSSH 更新及 SELinux 准备，在原任务中独立审阅系统变更、保留未知结果并重新认证；历史能力未被当前 helper 安装替代，也未发现明确后置决定，须补当前架构接线。
- **S3-04、05**：HD-28/30/32/33 的交付网址、命令、ON_DEMAND 操作可用性、持续任务反馈和内部取消/核对入口。平台中央任务继续为权威来源，视图关闭不结束已接受任务。
- **审批冲突 HD-16**：现行 Mod 规范及产品说明要求 AI 部署先独立审批；人工分支与当前测试则允许无审批模型。已向用户提出按文档补齐或保留当前行为的选择，等待决定后落实 S3，未擅自改变该分支。自主固定工具仅宿主授权的差异也随最终合同覆盖；静态部署继续不引入 AI。
- **S3-06、S6**：本表全部适用行及第 9.8 节矩阵取得当前有效本机证据后关闭；代码追踪、历史结果和 UI 预览不能代替迁移后验收。

<a id="git-mod"></a>

## 5. 独立 Git Mod 规格

Git 独立化属于 1.3 实施范围。本期将已有 Git 专用功能提取为独立 Mod，并将部署来源接线迁到公开协作接口；不能以保留旧内部实现替代本期独立化交付。

### 5.1 已确认方向

- 将 Git 专用功能从部署 Mod 中独立提取，作为独立 Git Mod 承载。
- Git 专用业务不归入 APP，目标结构中也不继续作为部署 Mod 的内部实现。
- 本期完成独立提取和部署接入；具体公开接口、素材交接及验收范围在实施前明确。
- 部署将 Git 声明为可选依赖；Git 可用时提供仓库取材，不可用时本地目录部署仍能独立使用。
- 用户已确认首版只做部署所需的源码拉取：获取仓库指定引用，确定实际提交并交付冻结源码，先让部署 Mod 可用。已有仓库校验、抓取、检出和固定提交能力作为该取材链的必要组成，不扩展为日常 Git 操作套件。
- 提交、推送明确作为后续开发方向，不在 1.3 实施；后续排期与独立操作界面另定。

### 5.2 当前实现基线

截至本文建立时，当前源码中的 Git 专用实现位于 `src/mod/deployment`：

- `deployment/file/git` 负责仓库与引用校验、受管 worker、原生 Git 抓取与检出，以及固定提交取材。
- `deployment/service/core/DeploymentGitSourceFlow` 负责将 Git 来源接入部署项目保存、原发布任务和持久恢复。
- APP 经公开 API 提供通用文件、工作目录、源码快照、进程、任务及资源生命周期能力；当前 APP 聚合中没有独立 Git 服务模块。

当前 Git 实现的用途是部署源码取材。本文中的独立 Git Mod 和后续 Git 功能均为开发目标，尚不存在对应交付。实际源码树继续由 [File.md](../File.md#current-source-tree)维护，生产文件索引继续只记录已存在文件。

### 5.3 职责划分

以下职责边界已确认，实施时据此列出精确迁移清单和公开接口：

| 部分 | 职责 |
| --- | --- |
| APP | 通用文件、工作目录、快照、进程、任务、凭据保护和资源生命周期；为 Mod 提供基础能力。 |
| Git Mod | 仓库与引用管理、抓取、检出、固定提交等 Git 专用功能；通过公开服务供其他 Mod 使用。 |
| 部署 Mod | 部署项目记录、业务源码筛选、组件分析、构建和发布；调用 Git Mod 获取来源。 |

迁移按实际职责划分：Git 操作相关逻辑由 Git Mod 承载，部署项目保存、组件归档和发布编排保留部署业务归属。Git 操作恢复与部署发布恢复之间的具体分工仍需设计。

1.3 首批公开能力限定为部署源码取材，优先通过对外服务供部署使用，不要求交付完整独立仓库管理界面。提交、推送已明确后置；仓库状态、差异查看等其他功能仍为后续候选，不计入首批验收。

### 5.4 协作与依赖边界

优先复用现有[跨 Mod 服务机制](../Mod.md#public-api-spec)，通过声明依赖、公开导出和受管服务调用协作，不访问其他 Mod 的私有实现或数据表。具体合同以现行 Mod 规范和 `appapi.mod.service` 源码为准。

当前跨 Mod 调用要求同步完成相应工作，跨边界返回不可变结果或已登记的业务操作 ID，提供方的资源不能泄漏到调用外。因此，现有 Git 素材对象不能只改名后直接作为跨 Mod 返回值；本期按下述已确认方向落实源码交接、授权、持有和释放的精确接口。

当前 `ServiceValueCopier` 的二进制值总预算为 16 MiB，既有 Git 请求允许归档上限达到 2 GiB。用户已确认通过平台受管素材引用交接：跨 Mod 返回符合值合同的引用和固定提交等不可变元数据，实际冻结源码/归档由平台素材能力承接，不直接返回整份归档字节、活动句柄或把裸路径当作读取授权。S2 需保持既有取材规模，不以扩大服务值预算或依赖后期助手的通用操作 API 替代此合同。

素材引用的读取准入绑定允许的消费方及原任务，核对素材身份、固定提交/摘要、持有状态和授权；知道引用 ID 或路径不等于获得访问权。部署按平台合同取得自己的受管读取持有，直到分析及后续执行结束，或原任务已经核实并完成收尾后释放；取消、失败及重复释放沿原资源合同处理，仍被活动或待核对任务占用的素材不能仅按超时自动删除。具体引用类型、授权/持有接口及期限在 S2 明确，不直接泄漏 Git 私有资源。

恢复继续核对原素材与原操作依据，保留必要引用、身份和持有记录；素材失效、缺失或身份不符时报告不可用/待核对，不从路径重建授权、不重新拉取替代原冻结内容。Git 专用操作恢复与部署业务提交、素材释放的分工在 S2 落实，并覆盖大于服务值预算的实际素材交接、归档上限及异常收尾。

当前服务发现要求声明必需依赖，尚不支持本次确认的可选依赖。当前任务准入及恢复图也按清单全部依赖核验，因此 S2 需同步调整清单解析、依赖计划、启动协调、加载器与静态代码准入、受管服务发现，以及任务准入、原依赖记录与恢复图；不能只在清单中增加标记或只让入口启动便认定可选功能已经可用。

### 5.5 已确认的可选依赖规则

用户已确认增加可选依赖。依赖关系区分为：

| 关系 | 本期目标行为 |
| --- | --- |
| 无依赖 | 不声明该提供方，不获得其公开类型或服务的隐式访问。 |
| 必需依赖 | 提供方缺失、未启用、版本不兼容或启动失败时，消费方无法正常启用；保持现有必需依赖的基本语义。 |
| 可选依赖 | 提供方已启用、版本兼容且启动成功时，消费方可使用对应附加功能；否则该功能不可用，消费方的基础功能仍能启动并使用。 |

Git 是部署的可选依赖：缺少 Git 只影响 Git 来源，本地源码的分析、构建和部署路径继续可用。依赖存在于安装目录不等于运行可用；界面可反馈缺失、未启用、不兼容或启动失败等具体原因。

用户已确认新业务与原任务恢复分别判断：正常业务中，本地新部署不使用 Git，也不因缺少 Git 被任务准入拒绝；使用过 Git 的未完成任务，仍按原依赖版本、程序身份、Schema 代次及原授权核对恢复。原 Git 版本缺失或原依赖图无法核实时，明确保留恢复阻塞和诊断，不改用当前所选新版、重新拉取或本地来源替代原任务。未决恢复对相关 Mod 和任务准入的限制继续遵守现行生命周期，不以可选依赖规则绕过。

原任务依赖记录区分必需依赖与本任务实际使用的可选提供方；不能把全部可选声明无条件当作新任务必需前置，也不能遗漏已经参与操作的可选提供方。使用可选服务前须按原受管调用合同持久记录其精确身份与占用，避免副作用发生后无原图可恢复；具体记录及闭包接口在 S2 落实。迁移前已有未完成记录按其原程序与原图核对，不事后改归属给新 Git Mod。

以下为实现约束与待落实细节，不能作为已完成 API：

- 声明仍包含提供方身份和支持版本范围；表示可选性的精确字段与接口在 S2 确定，未标记为可选的声明保留原必需语义。
- 有效的可选提供方按协作所需顺序启动并绑定公开类型；不可用时不将失败传播为消费方整体失败。循环、公开包冲突和无效清单的判据须明确，不能用“可选”绕过原代码准入与身份校验。
- 作者将依赖专用代码与基础入口隔离；可选类型不成为入口、基础页面或本地部署路径加载的必需条件。服务可用性判断、公开类型共享和静态检查规则配套验证。
- 原任务、权限、提供方占用和跨 Mod 值合同继续适用；可选依赖不授予额外权限。运行中启停、更新及可用性重新计算按现行生命周期适配，精确时机在 S2 明确，不默认支持热插拔。

商店如何获取必需与可选依赖由第 8 节维护；依赖获取、安装和运行可用性分别判断。

<a id="mod-development-layout"></a>

## 6. ModDev 开发区与 Mod 调试区规格

开发区与调试区分离属于 1.3 实施范围，配套调整模块组织、构建测试、开发描述与调试加载入口。S1 已落实目录和第 6.5 节的首次版本/准备合同并通过适用回归；阶段完成状态与实际验收见第 3、9.8 节。

### 6.1 划分方向

ModDev 是开放给开发者使用的开发工程区，承载 Mod 编写、独立构建、单元测试与静态检查；这些定向验证不强制启动 APP 或把其他开发项目一并纳入。需要接入真实 APP 进行界面、平台接口和跨 Mod 集成调试时，才将项目移入 Mod 区域。开发好的 Mod 移入后可以按调试规则自动加载，目标是将开发中的项目与参与集成调试的项目分开。

ModDev 是与现有 `src/mod` 并列的源码工程分组，不是独立运行的业务 Mod。按下面的目录组织开发与调试项目，使用独立聚合及作者父 POM 提供定向构建和测试支持。宿主的 `src/app/mod` 仍负责 Mod 加载、生命周期及相关平台机制，与作者项目区域区分。

### 6.2 目标目录与使用流程

当前使用 `src/moddev` 和既有 `src/mod`，完整实际结构由 [File.md](../File.md#current-source-tree)维护；区域用途如下：

```text
src/
├─ appapi/                 # 平台公开接口，保持现有职责
├─ app/                    # APP 及平台实现，保持现有职责
├─ moddev/                 # 独立开发中的 Mod 与同作者从模块
└─ mod/                    # 接入 APP 集成调试的 Mod 与所需从模块
   └─ deployment/          # 已接入集成调试的部署 Mod
```

执行流程为“在 ModDev 开发与定向测试 → 移入 Mod 调试区 → 按新位置重新构建 → 启动或重启已开启开发调试自动加载的 APP → 自动发现，新项目完成必要准备后启用”。首轮沿用启动时发现的方式，目录监听和热重载不纳入本期。

同一个 Mod 及其从模块保持一份权威源码，不因开发区与调试区划分而长期维护两份副本。移入后重新核对 POM、同作者从模块、依赖和资源，并重新生成开发描述；旧路径的编译输出不能当作新位置的有效构建。

源码 `src/mod` 是调试项目区，发行物中的 `mod/` 仍是安装目录，二者用途不同。新增 ModDev 不改变单个 Mod 的身份、主 JAR 与从包组成、公开依赖或发行安装合同。

### 6.3 构建与测试隔离要求

- ModDev 不加入默认 APP 集成构建、加载发现和测试集合；不能只增加目录却仍在根构建中默认聚合全部开发项目。
- ModDev 中的单个项目可以显式构建、运行单元测试与静态检查，只纳入它实际需要的 API、工具和声明依赖；实际入口见 [ModDev README](../../src/moddev/README.md)。作者父 POM 提供 JUnit/Surefire 版本和生产字节码准入，不启动 APP。
- Mod 调试区只接纳本次准备参与集成调试的 Mod 及其必要依赖；平台基础检查继续保留。
- 单项开发验证使用目标模块与必要依赖，跨 Mod 接口变化及交付仍执行适用的集成回归，不以开发区隔离为理由跳过必要检查。
- Maven 构建范围、IDE 项目导入、格式检查、架构扫描和文档索引需要一致地识别两个区域的用途；具体哪些检查覆盖 ModDev，在实施规格中列明。

### 6.4 当前机制与已确认的首次启用目标

当前 `DevelopmentModFinder.scan` 仅扫描工作区 `src/mod`，按 `mod.json` 与当前编译描述发现候选；发现本身不执行作者代码。`DevelopmentInfo` 和 Maven 作者输入工具接受两个区域的描述生成及校验，主/从模块限同一区域，独立开发区不加入运行发现。

当前 CLASS 配置默认 `mod.auto-load: false`，关闭时完全不扫描 Mod；开启后仍遵守启用选择、版本、依赖和生命周期规则。启动专用刷新仅为首次新身份建立启用选择，普通维护刷新不自动启用，具体版本与准备规则见第 6.5 节。

用户已确认：仅在明确开启开发调试自动加载的 CLASS 调试入口中，移入 `src/mod` 且按新位置完成有效构建的新项目自动发现，并在完成必要平台准备后自动启用、接入 APP；未开启时不扫描或自动启用。新项目指尚无已保存启用选择的身份，用户已有禁用选择、已选版本和待重启意图继续保留，不因移入、重新构建或重启被覆盖。

自动接入仍核对身份、构建描述、宿主/API、依赖与 Schema 等现行条件。必须由用户确认的初始化、迁移或授权步骤继续走平台流程，未满足时显示待处理原因，不绕过确认或把尚未启动写成运行成功；无效、歧义或缺少必需依赖的项目不自动执行。首次调试启用选择可以自动建立，实际启动仍遵守现行启动/重启时机；需要先在界面完成准备的项目，准备完成后在下一次适用启动接入，不因此热加载。已保存版本不自动切换，新身份的首次版本按第 6.5 节唯一有效构建确定，配置填写见 [Config.md](../Config.md)。发行物 `mod/` 的安装、启用与版本选择合同保持原规则，不继承开发调试首次自动启用。

默认调试区保留现有部署项目，ModDev 是独立开发聚合入口，不添加占位业务。作者项目独立聚合自身主/从模块；移入后在 `src/mod/pom.xml` 登记本次参与集成的项目并重建。根全源码结构、格式与索引识别两个区域，根默认 reactor 不编译或执行 ModDev 的业务测试。首次版本与准备按下述合同执行。

### 6.5 S1 具体实施合同

`src/moddev/pom.xml` 是独立聚合入口，不加入根默认 reactor；根默认集成继续只聚合 `src/mod`。作者描述工具接受两个区域的主模块，但必须包含所在区域的聚合 POM，作者从模块必须在同一区域。构建描述绑定绝对输入路径及摘要；移动主模块与从模块后更新聚合声明并重建，旧路径描述拒绝使用。格式和结构索引覆盖两个区域，默认运行发现仍只扫描 `src/mod`。

CLASS 明确开启自动加载时，启动专用刷新才允许为首次出现、尚无保存选择的身份建立启用选择。首次版本取本次发现的唯一有效构建版本；歧义、无效构建不建立自动启用选择。普通维护刷新只登记候选，不自动启用。已保存的禁用、版本及待重启操作不覆盖，包括此前登记保存的默认禁用选择。

首次建立启用选择之后，原依赖、版本、Schema 与启动准备仍逐项核对。缺少 Schema 或需要用户确认的准备时保留选择并显示待处理原因，通过明确的“准备数据”入口审阅首次初始化或迁移，完成原准备流程后在下一次适用启动接入。该入口仅接受未运行、无占用和待执行意图、已启用且目标数据尚不存在的精确选择；取消准备保留启用及版本选择。沿用重启账本，普通无数据变化的同值切换仍拒绝。不热加载、不把候选登记或启用选择当成启动成功。发行模式继续使用原安装与用户选择合同。本节规定实现和验收目标，实际通过结果记录于第 9.8 节。

<a id="file-explorer"></a>

## 7. 文件管理（资源管理器）Mod 规格

### 7.1 定位与底座

本期实现独立、可选的文件浏览与管理界面，面向本机及平台已连接的 Linux 服务器，复用 APP 的统一文件、连接、传输、任务和权限 API。用户按需安装和启用，未安装时不影响 APP 文件底座或其他业务的文件选择与传输。

文件管理 Mod 自行实现所需 SQL 和 Swing，不等待或依赖后期封装。单栏及按需展开的双栏列表浏览、新建文件夹/空白文件、重命名、删除、跨栏拖拽移动和显式复制已纳入本期；独立链接管理等其他扩展范围另行明确。

### 7.2 已确认界面与显示范围

- 参考本次提供的 Windows 资源管理器截图，采用相近的目录导航、路径栏与文件列表样式；截图中的全部工具和功能不自动纳入。
- 在文件管理 Mod 的一个根工作区内默认使用单栏；只选择一个文件夹时，仅显示该目录的一个浏览区域，不强制显示空的第二栏。
- 工作区右侧提供第二栏展开按钮，用户点击后才在当前工作区内展开双栏。展开后左右默认等宽、结构对称，两栏各自选择位置、浏览目录和显示当前路径，切换一侧目录不强制改变另一侧。
- 固定使用列表形式。每栏列表保留“名称”和“类型”两列，类型是唯一附加文件信息；不显示修改时间、创建时间、大小等附加列。
- 不提供大图标、小图标、平铺等其他视图，也不增加视图模式切换。名称旁的必要识别图标不构成另一个视图模式。
- 类型列说明文件夹、链接或文件类型，具体类型文案映射在实施规格中明确；列表显示范围的精简不删除平台操作所需的内部属性和身份核对。

单栏/双栏指目录浏览区域的数量，与每个文件列表的名称、类型两列区分。

### 7.3 顶部工具栏与右键菜单

在文件管理工作区上方提供工具栏，放置“复制”“剪切”“粘贴”按钮，采用资源管理器式的操作组织。列表右键菜单也提供这三个选项，两种入口共用同一组动作和可用状态，不各自实现一套行为；单栏及展开后的双栏均可使用。

复制、剪切以当前活动栏的选中项为来源；粘贴以当前活动栏的目录为目标。右键条目或列表空白区域时，操作上下文明确对应所在栏及相应选择/当前目录，避免左右栏之间混淆。

复制准备后，粘贴执行复制；剪切准备后，粘贴执行移动。点击剪切仅登记待移动来源，不立即移走或删除文件。按钮和菜单的可用状态随选择及待粘贴内容变化，粘贴前按平台合同重新核对来源与目标。

### 7.4 新建、重命名与删除

用户已确认三类管理操作纳入 1.3，创建入口保持简单：

- 新建文件夹：在当前活动栏目录中创建文件夹，由用户命名。
- 新建空白文件：创建普通空文件，由用户指定名称并自行填写扩展名；不提供 DOC、TXT 等文件类型选择列表或模板生成。这里将讨论中的“无名文件”按空白文件理解，实际文件必须有名称；默认名称与编辑方式在 S4 确定。
- 重命名：修改选中项的名称，文件扩展名也作为名称的一部分处理；普通项调用 renameFile，链接项默认调用 renameLink，只改链接自身名称并保留原目标，必要时调整相对指向文本。
- 删除：删除选中项，普通项调用 deleteFile，链接项默认调用 deleteLink，只删除链接自身、保留其指向的文件或目录；沿用平台操作授权、确认、回收/永久删除及结果合同。具体删除提示在 S4 明确，不另行实现一套回收机制。

新建只创建空文件，扩展名不自动生成 Word 等专用格式的内部结构。各操作复用现有 `FileActionService`，在实际任务中核对名称、权限及冲突，完成后按真实结果刷新列表；输入名称的交互不允许静默覆盖已有项。

用户已确认文件列表选中链接后的复制、剪切/拖拽移动、重命名和删除均默认操作链接自身：复制调用 copyLink，移动调用 moveLink，重命名调用 renameLink，删除调用 deleteLink。复制、移动和重命名后的链接保持原目标，删除链接不删除目标内容；不能因链接指向目录而把这些动作改为操作该目录。工具栏和右键共用此规则，身份与授权绑定链接对象；1.3 不因此增加操作实际目标的另一组入口。链接类型范围沿 1.2 的符号链接与平台支持的目录联接点合同，不自动扩展为所有特殊文件。

### 7.5 跨栏拖拽与操作执行

用户已确认：拖拽默认移动，复制另选。双栏展开后，在平台支持的源/目标组合中，将左栏文件拖入右栏列表区域时，默认移动到右栏当前目录；对称方向采用相同规则，不按所在磁盘或主机自动改成复制。

显式复制通过顶部工具栏或右键菜单的“复制 → 粘贴”提供，“剪切 → 粘贴”执行移动。跨栏拖拽、复制粘贴和剪切粘贴均调用 APP 文件 API，由原任务执行权限核对、冲突处理、取消和结果记录，不在界面另写一套文件或传输实现。

用户已确认拖拽复用剪切后粘贴的实际移动流程，不新增另一套剪切底座：拖普通文件/目录调用 File 移动，拖链接调用 Link 移动，只移动链接自身并保持原指向，必要时调整相对地址。拖原件时，受管链接按原件实际新位置更新；剪切按钮只记录来源，粘贴或拖拽才执行移动。

链接维护复用 [1.2 的既有合同](PHASE-1.2.md)。用户已确认 1.3 文件管理只维护本 Mod 自身登记或本次明确纳入、经身份和更新授权核对的链接；跨 Mod 归属的 APP 协调机制后置，不承诺更新全 APP 登记或系统内所有链接。跨主机等无法表达新指向时，尽可能在副作用前拒绝并说明原因，不自动跳过应维护链接后报告成功；执行中异常分别记录文件移动与链接维护结果，链接更新失败或未知不能报告整项全部成功。

当前 `FileStore.links` 按调用方 `owner_id` 和主机查询登记，`PlatformFileFlow` 绑定原调用方身份，与本期选定的维护范围一致。S4 落实自身链接登记和本次纳入的操作流程，沿平台入口核对身份、权限、当前指向及原任务授权；本次纳入不构成越过归属读取或改写其他 Mod 登记的许可。不能把浏览到链接视为已获得更新权限，也不为本期增加跨归属私有查询或整盘扫描。

在移动操作界面和结果中说明仅维护本 Mod 登记或此次获准纳入的链接，报告实际维护清单及结果；未纳入链接不保证跟随，不声称已发现或修复其他归属的全部链接。具体展示和纳入交互在 S4 确定，不因此增加独立链接管理功能。

移动结果按实际完成情况刷新两侧列表，区分成功、失败、取消、部分完成与待核对；不能因接收到拖拽就提前认定源文件已移走，也不能在移动失败后自动改为复制或追加删除。进度和操作结果继续显示，列表不展示时间或大小不影响任务反馈。

### 7.6 其他边界

按需提供界面是为了减少无关入口和学习负担；新手误操作风险需结合实际交互验证。可选安装不能替代权限、操作确认、冲突处理、取消、错误反馈和可恢复操作等平台机制。

跨 Mod 归属链接维护的 APP 协调机制明确后置。独立回收项管理 API、两台远端之间传输、同步及备份等尚未确认的扩展不自动纳入。若首批界面确需平台补充能力，先记录具体缺口和影响，不重复实现文件或 SSH/SFTP 底座。

### 7.7 S0 文件场景范围与底座核对

2026-10-05 核对现行 `FileQueryService`、`FileActionService`、`PlatformFileFlow.copyAdmitted` 及本机/受控 SFTP 用例。下表是 S4 的实施与验收范围，尚不代表文件管理界面已实现。

| 来源 / 目标 | 浏览与管理 | 复制 / 移动 | 链接与验证边界 |
| --- | --- | --- | --- |
| 本机 / 本机 | 实际目录列表、空文件/目录创建、重命名、删除。 | File 与 Link 分别走原平台动作；跨栏默认移动。 | 链接自身及原目标保持；登记维护仅限本 Mod 或本次获准纳入，覆盖符号链接及平台支持的目录联接点。 |
| 单台已保存 Linux / 同一台 Linux | 同样的目录与管理操作，由任务绑定该服务器及其 SSH/SFTP 准入。 | 同服务器平台 File/Link 动作。 | 使用本机受控 SSH/SFTP 服务验证实际协议、链接、错误和取消；真实 Linux 留给独立验收。 |
| 本机 / 单台 Linux，及反向 | 两栏分别独立定位本机与同一服务器。 | 复用平台跨主机复制/移动与传输记录，不将拖拽改成复制。 | 链接自身或需维护的关联链接无法在目标主机表达原指向时，尽可能在副作用前明确拒绝；不静默跟随目标或跳过维护。 |
| 不同两台 Linux | 不纳入首批跨栏传输范围。 | 当前底座明确拒绝 cross-server，界面提前说明并拒绝。 | 不增加远端间传输底座或将该项伪装成本机中转。 |

目录列举已有“不跟随子项链接”的元数据合同，界面只显示名称/类型，保留内部身份核对。任务结果、审批、取消及 UNKNOWN 复用平台，列表须按真实结果刷新。S4 再落实初始目录、第二栏保留/收起、内部剪贴共享范围及快捷键、类型文案、批量落点、命名输入与链接纳入交互；这些细节先写回本节再实施。

<a id="dependency-download"></a>

## 8. Mod 商店连同下载规格

用户已确认本功能在 1.3 实施。它依赖 S2 新增的必需/可选依赖语义，属于 APP 的获取与安装功能，不拆成另一个业务 Mod。

### 8.1 当前基线与本期边界

当前有商店界面及查询/下载/验证接口，默认接入明确不可用的实现；测试适配器验证过获取与安装流程，但没有正式查询/下载通道。当前安装流程不会自动启用 Mod 或切换已有选择。具体基线见 [Mod 获取与管理](../Mod.md#package-acquisition)及 `ModStoreService`、`UnavailableModStoreGateway` 源码。

现有 `ModStoreGateway.Release` 仅包含身份、版本、标题和说明，没有必需/可选依赖及其版本范围字段。S5 必须补齐依赖元数据、版本解析的数据合同与查询接线；已有搜索列表不能直接当作完整依赖解析输入。

本期完成客户端的依赖清单、选择界面、查询/下载通道与现有安装流程的必要接线。目录/依赖元数据、服务端对接协议和本机受控验证服务在 S0 识别、S5 实施前落实；正式远端商店地址及真实服务验收留给独立任务，不要求先上线商店。官网投稿、审核、账号体系与公开发布基础设施不自动纳入本项。

### 8.2 已确认的下载规则

- 用户下载一个 Mod 时，其缺少的必需依赖自动加入同一下载清单，不要求用户逐个寻找；多级必需依赖一并解析。
- 可选依赖提供选择，用户决定是否连同下载；未选择时不下载，不阻断主 Mod 的基础功能。
- 勾选可选 Mod 后，该 Mod 自身的必需依赖一并加入清单；其他可选项仍由用户选择，不因选择一个可选 Mod 就下载全部可选分支。
- 已有满足本次要求的已安装兼容版本可以复用，重复依赖合并处理；已安装但未启用的状态单独显示，不把“无需再下载”当作“已运行可用”。

### 8.3 清单、版本与操作反馈

以下为实施所需的配套规则，具体交互在 S5 固定：

- 下载前展示主 Mod、必需依赖、可选依赖及其用途、版本、已安装状态和获取来源，依据选择形成确定的清单；可选项的默认勾选策略尚未确认。
- 版本解析同时满足参与清单的全部要求。冲突、循环或没有可获取的兼容版本时给出具体原因，不静默更换用户已选运行版本或下载不兼容版本。
- 用户已确认需要获取依赖时默认推荐满足要求的最高可获取兼容版本，依赖下载清单默认采用该推荐版本；进入该依赖的详情或版本选择界面时优先定位并选中推荐版本，不跳到不符合要求的全局最新版本。例如商店已有 `2.0.0`，消费方精确要求 `1.9.0` 时推荐并选中 `1.9.0`；若声明允许一段范围，则在同时满足参与清单的全部依赖约束、宿主/API 和既有预发布准入规则的可获取版本中，优先推荐最高兼容候选。版本排序及精确/区间语法沿用 [Mod 身份与版本规范](../Mod.md#mod-identity-version)，不以字符串或发布时间代替版本比较。
- 最新版本超出要求但存在兼容旧版，不算无法解决的冲突；解析时继续寻找可行兼容方案，不能只尝试全局最新版。版本选择变化后重新解析其传递依赖，清单展示实际推荐版本及对应约束，并在下载前确认；用户手动选择不兼容版本时明确反馈，不继续下载。已安装兼容版本仍可按第 8.2 节复用，不仅为追求更高版本自动升级；已安装但不兼容的版本不能误当作依赖已满足，也不自动更改其运行选择。
- 用户已确认版本冲突在下载前解决：显示发生冲突的 Mod、要求该版本的依赖关系和不兼容范围，由用户取消相关可选项或调整拟获取版本；重新解析必需依赖、去重与版本约束，并重新确认有效清单后才开始下载。不能静默取消已选可选项，也不先下载冲突清单中的主 Mod 或部分分支；同一项同时承担必需依赖时不能将其当可选项直接移除。调整获取清单不自动改变已有启用或运行版本选择。
- 每个包沿用现有下载、完整性/来源验证与安装流程，元数据和实际包声明须一致；不完整或校验失败的包不能登记为已安装。
- 显示逐包进度和实际结果。必需依赖失败时，主 Mod 不能被标记为依赖已齐备；可选依赖失败时说明对应附加功能不可用。取消、部分完成与重试保留已完成事实，不自动删除已安装成功的包。
- 商店下载完成后沿用既有自动解压安装规则；安装成功仍不自动打开 Mod 开关或更改版本选择。用户启用时继续核对依赖、初始化/迁移及生命周期，连同下载不取消这些步骤。

### 8.4 验证与交付边界

S5 在本机同时验证清单解析、选择变化、实际客户端查询/下载通道及界面获取安装，记录各包落地结果。可用本机受控 HTTP 服务或协议夹具完成验收；客户端实现完整且本地测试通过，即满足本阶段交付，正式商店服务验收另行安排。测试适配器可用于定向用例，但占位界面、未实现通道或模拟成功不能替代完整本地验收。

### 8.5 S0 客户端缺口核对

当前 `ModStoreGateway.Release` 只有身份、版本、标题和说明；`ModStoreService.install` 已串联下载、验证、包审阅和安装，但默认 gateway 明确不可用，不能作为生产查询/下载实现。`PlatformModInstallation` 及安装后不自动启用的规则继续复用；CLASS 保持不安装发行包，本机商店安装验收须使用隔离发行布局及隔离数据库。

S5 必须实现包含精确版本、依赖类型/用途/范围、宿主/API 要求及包完整性依据的目录元数据和真实客户端通道；推荐/传递依赖解析、确定清单、选择与冲突调整、逐包结果、取消/部分失败/重试均尚缺。现有测试 gateway 仅作为测试工具，不替代通道。协议与可选项默认选择在 S5 前固定，沿既有配置入口接入，不预设正式服务已上线。

<a id="acceptance"></a>

## 9. 验收方向与完成定义

### 9.1 部署迁移

- 历史部署范围内每项真实功能、界面和必要资源都有当前落点及适用证据；未实现历史设想单独标记，遗漏或不完整接线不能标完成。
- 标准、辅助、自主、手工审阅和多组件的适用路径分别核对；覆盖从来源、输入与审阅到构建发布、健康检查、结果及失败恢复。
- 应用管理和部署相关弹窗通过实际产品入口核验；界面文字、可用状态和结果与后台事实一致。
- 取消、关闭、审批等待、清理失败、UNKNOWN 与中断核对保留现行任务和资源合同，不重放未知操作。

### 9.2 文件管理 Mod

- 未安装或未启用时，APP 基础文件和连接能力及其他业务继续可用。
- 默认单栏，选择一个文件夹时不出现第二栏；点击右侧展开按钮后，在同一个根工作区出现结构对称的左右双栏，路径与浏览状态各自独立。
- 单栏及双栏均固定列表形式，每栏仅有名称、类型两列，没有时间、大小附加列或大/小图标等视图切换。
- 经实际界面创建文件夹和空白文件、重命名及删除，核对实际目录结果；新建文件仅有普通空文件入口，名称/扩展名自行填写，不生成文件类型模板。
- 经工具栏和右键复制/剪切后粘贴、重命名及删除选中的链接，均核对链接自身结果；新位置仍指向原目标，删除后目标文件/目录保留，断链与目录链接沿平台 Link 合同处理，不能误调用 File 入口改动目标内容。
- 在已确认的本机和本机受控远端协议场景中，经实际界面完成双栏浏览、默认拖拽移动和显式复制，核对两侧实际文件结果；移动与复制行为和用户选择一致。真实 Linux 的验证独立安排。
- 顶部工具栏和右键菜单均可执行复制、剪切、粘贴；在左右栏切换操作时，来源、目标和可用状态一致，剪切准备阶段不修改源文件。
- 拖拽与剪切后粘贴共用移动流程：链接项只移动自身且保持原目标；原文件移动维护本 Mod 自身登记或本次明确纳入且获准更新的链接，界面说明范围，实际维护清单和文件/链接结果分别核对，维护失败不报整项全部成功。验证跨主机无法表达指向时尽可能提前拒绝；不获取其他 Mod 私有登记或承诺全局自动维护。
- 同名冲突、失败、取消、部分完成及待核对时，列表刷新和任务反馈与实际结果一致，不提前移除源条目或自动替换操作语义。
- 文件、链接、权限、确认、取消与资源释放遵循 APP API；官方实现不使用 SQL/Swing 封装。

### 9.3 Git 独立化与可选依赖

- Git 专用能力具有独立 Mod 身份与公开接口，部署通过声明依赖和受管调用使用，不访问其私有实现或数据表。
- 既有 Git 取材、固定提交、冻结素材与部署接线在独立化后继续有效；通过平台受管素材引用完成大于 16 MiB 的实际交接，并核对既有归档上限。读取授权、持有期限、所有权、释放及任务恢复分工明确并验证，无权/失效引用不能读取，不以裸路径或重新拉取替代原授权与素材。
- Git 未安装、未启用、版本不兼容或启动失败时，部署基础功能和本地目录来源仍可独立使用，Git 来源明确不可用；Git 可用后实际走受管公开服务。
- 正常业务中缺少 Git 仍能实际发起并完成本地部署任务，不能只验证页面可打开；任务记录准确包含实际使用的可选提供方。使用过 Git 的未完成任务按原版本图恢复，原提供方缺失时明确阻塞，不切换新版、不重拉或改本地来源替代；未决恢复的关联准入限制保持现行合同。
- 缺少可选类型时，基础入口及页面可正常加载；必需依赖的失败阻断、公开包冲突、循环和原资源/恢复规则继续经过适用验证。

### 9.4 ModDev/Mod 划分

- ModDev 项目可独立构建、运行单元测试与静态检查，不强制启动 APP，不因存在于开发区就进入默认 APP 加载、集成构建或测试集合。
- 开启开发调试自动加载后，Mod 调试区新项目经实际移入、重新构建、启动/重启和必要准备，自动发现并启用接入；关闭自动加载时不扫描或启用。已有禁用选择与所选版本保留，无效项目或待确认准备不绕过平台条件；发行安装仍由用户按原规则选择启用。
- 单 Mod 多包、开发描述、Maven/IDE 导入和检查范围与区域划分一致；不长期保留两份权威源码或使用旧路径输出。

### 9.5 P0 窗口成果的承接与回归

- 同时启用多个有界面的 Mod 时，可用根工作区登记后由 APP 提供其唯一入口；增加内部功能页不会增加主导航项，无界面 Mod 没有空入口。
- 经真实主导航进入部署、文件管理等工作区，内部选择和页面切换可用；历史部署入口及功能保持完整覆盖。
- 未进入的工作区不提前创建，首次进入创建一次；切到其他 Mod 再返回复用原实例并保留输入、选择和浏览状态，切换不触发释放。明确关闭、撤销或停用按合同清理，重开重新创建；主题、语言、锁定覆盖内部视图，后台任务遵循独立生命周期。
- 旧页面固定偏好归并后不出现重复或失效的功能入口，业务设置和调试预览不突破单入口规则。

### 9.6 商店连同下载

- 主 Mod 的多级必需依赖自动加入清单，可选依赖可选择；勾选项的必需依赖随之加入，未选择的可选分支不获取。
- 需要获取依赖时，详情/版本选择默认定位并选中满足完整约束的最高可获取兼容版本；商店最新为 `2.0.0` 而消费方精确要求 `1.9.0` 时推荐 `1.9.0`。区间依赖推荐最高兼容候选，不将存在兼容旧版当成无解冲突；已安装不兼容版本不误复用，已有兼容版本不被强制升级，已有运行版本不自动切换。
- 已安装兼容版本与重复依赖正确复用/合并，版本冲突和来源不可用有明确结果。选中可选依赖导致冲突时，下载前由用户取消相关可选项或调整获取版本，重新解析并确认有效清单后再下载；冲突未解决不开始下载、不静默取消选择或切换已有运行版本。
- 经本机受控服务与实际商店界面完成客户端查询、下载、验证和安装，逐包实际状态与结果一致；安装仍不自动启用。正式商店远端验证不作为本期完成条件。
- 完整本地用例覆盖取消、校验失败、必需/可选依赖失败、部分完成和重试；不将模拟成功或未实现客户端通道标记为完成。

### 9.7 证据与交付

优先复用当前测试入口及历史有效用例，按现行架构适配，不只做与实现重复的静态断言。分开记录源码覆盖、编译、静态检查、本机、受控服务、真实 Linux、模型提供方和界面验证；历史结果不能直接作为迁移后结果。

S0 已在本节建立本地验收矩阵，包含任务/功能、用例或真实界面入口、环境与命令、预期结果、实际结果/状态、证据和未覆盖项；已通过的前置核对与回归按实际证据填写。S1～S5 随实际验证更新对应行，S6 核对本期功能均有有效证据，当前仍失败、未执行或因测试支持不完整而跳过的必要检查必须补齐。已通过且相关实现、环境和合同未变化的结果可复用，变更后补受影响回归，不要求每个阶段重复全量测试。

本期以完整功能实现及适用本地验收通过为完成条件；满足后即可交付并认定目标达成。真实 Linux、模型/语音提供方及正式商店服务的验证均由独立任务安排，缺少这些环境不阻塞 1.3 完成，交付仍须列明具体未验证范围，不能把本机结果写成全面实机通过。商店客户端连同下载按第 8 节纳入本地验收；正式发行、官网投稿/审核/公开发布和原 1.1 S7 的其他后置范围另行处理。

界面交付按[首版界面优先级](#ui-first-delivery)区分功能及基本可用性问题与后期视觉精修。验收仍核对真实操作和结果；允许记录待打磨的外观细节，不以参考截图的像素一致性作为本期完成条件。

### 9.8 S0 本地验收矩阵（2026-10-05 建立）

环境已核对：Windows 11 当前用户；Maven 3.9.12 使用 `E:\Program\Java\JDK21` 的 JDK 21.0.10；Git Bash 和 Python 3.12 解释器存在。`.mvn/archive/verify.py` 已通过 2836 个冻结文件的集合及 SHA-256 检查。所有运行使用隔离测试数据库与临时工作区，保持真实开发/生产数据库和原用户进程。JDK 回环临时目录按 README 仅设置于验证进程。

以下为完整验收分组；“待执行”表示尚无本目标的有效通过结果。阶段实施后补入精确测试、实际界面入口、命令和证据；测试类名只列当前已存在入口，新场景须增加实际行为验证。证据统一保留于 `.ai-workspace/progress/phase13-evidence/`，正式结论仍以本表及所属规格为准。

| 任务 / 功能 | 用例与预期实际结果 | 入口 / 环境 | 实际状态与证据 |
| --- | --- | --- | --- |
| 前序失败，S6-01 | 注释、包环、位置类型、资源命名、结构及复杂度门禁全绿，保持原检查标准。 | `JavadocCoverageTest,PackageStructureArchitectureTest`；JDK 21。 | 42 项通过、doclint 通过；`prior-gates/normal-user-regression.log`。 |
| S0-01，P0 回归 | 单 Mod 唯一入口；首次创建、切换保留、关闭确认/重建、恢复内部页、锁定导航与业务禁用。 | 前置 §2.8 及正式开工的真实窗口/导航定向 reactor；实际 Swing/Robot。 | 本目标正常用户上下文 12 类、95 个独立相关用例最终通过；首轮及旧首屏测试适配复测分别见 `prior-gates/normal-user-regression.log`、`startup-window-final.log`。 |
| S0-02，S3 全部 | 每项历史真实能力可追到当前用户入口、代码与用例，历史设想和独立后置范围分开。 | 冻结 `history-src` 与活动部署；§4.4 的 HD-01～36。 | 36 项代码/入口链审计完成并分派；功能与 UI 迁移后验收仍按各行执行，不将审计算业务通过。 |
| S1-01～02 | 单个真实 ModDev 项目可独立构建/单元/静态验证；不默认参与 APP 集成、不启动 APP。 | 作者父 POM、`MavenDevelopmentInputsTest,DevelopmentModFinderTest,PlatformModDevelopmentTest,MavenModuleGraphTest`；真实独立 Maven `verify`。 | 已通过；真实两从包/私有库/作者单元和字节码准入、无关项目隔离及默认 reactor 保护。`s1/final-test-summary.json`、`development-preparation-user-5.log`；格式工具 6 项及 3 文件真实定向格式检查通过，见 `format-unit-final.log`、`format-project-probe-check.log`。 |
| S1-03～04 | 项目实际移入、按新位置重建描述并接入；单 Mod 多包；旧输出拒绝；关闭不扫描；首次新身份选择、准备等待、已有禁用/版本保留。 | `PlatformModDevelopmentTest,PlatformModCatalogTest,PlatformModFlowTest,PlatformModManagementTest,PlatformModPreparationTest,ConfiguredRuntimeTest`，隔离工作区/数据库及实际 Swing。 | 已通过；S1 共18类160个独立Java用例最终通过（跨轮仅取每类最后通过结果，不将失败轮整体记通过）。原数据初始化/迁移/重试、准备确认/取消/锁撤销、原任务/跨所有者占用及普通同值操作保护通过；关闭配置以损坏调试区证明不扫描/登记/构造Mod图。`s1/final-test-summary.json`、`final-targeted-user-8.log`及各行原日志；最终25文件格式通过。 |
| S2-01 | 必需与可选缺失、禁用、不兼容、启动失败；循环/公开包冲突拒绝；可选类型隔离和运行可用服务发现。 | `ManifestReaderTest,ModDependencyPlanTest,ModClassLoaderTest,ModCodeCheckerTest`；受管 Mod 启动。 | 待实施/执行。 |
| S2-01、05 | 不使用 Git 的本地新任务能实际完成；使用可选提供方的任务持久记录精确身份；原版本缺失恢复阻塞，不切新版/重拉。 | 任务准入、依赖记录及恢复图；本机隔离 Mod/任务集成。 | 待实施/执行。 |
| S2-02、05 | 大于 16 MiB 冻结素材真实跨 Mod 交接；既有 2 GiB 上限；无权/失效/身份变化拒绝；持有/释放/取消/UNKNOWN/恢复不重放。 | Git 公开服务、平台素材 API、受管 worker；本机实际 Git 仓库。 | 待实施/执行。 |
| S2-03～04 | Git 独立身份，无旧私有实现替代；固定提交源码到部署；本地目录保持可用。 | 部署来源选择与独立 Git 调用，本机仓库与分析/发布任务。 | 待实施/执行。 |
| S3-01 | 项目保存/修订、来源筛选、分析、运行输入/秘密、单/多组件选择与素材一致。 | 当前部署标准页及相关源码/组件编辑，现有对应服务与 UI 用例。 | 待逐项核对/执行，落点写入 §4。 |
| S3-02 | 标准/辅助/自主、手工审阅/计划确认；工具链与数据库准备实际走对应交互。 | 部署工作区各模式/弹窗；本机模型及 SSH/SFTP 协议夹具。 | 待逐项核对/执行。 |
| S3-03 | 冻结/上传/构建、原生与容器发布、健康失败/回滚及中断核对，各结果与记录一致。 | 现有部署 runner/helper 用例及受控服务；不要求真实 Linux。 | 待逐项核对/执行。 |
| S3-04～06 | 应用列表/访问/启停/自启动、外部发现/有限接管；持续任务日志/取消/等待/UNKNOWN/资源清理；真实 UI 接线。 | 应用管理与任务入口、当前服务/Robot 用例，历史对照全部适用行。 | 待逐项核对/执行。 |
| S4-01～02 | 默认单栏；按钮展开等宽双栏、独立路径/选择；列表仅名称/类型；收起及重开保留规则。 | 新文件管理根工作区，实际主导航与 Swing/Robot。 | 待实施/执行。 |
| S4-03、06 | 工具栏/右键共用复制剪切粘贴；剪切无副作用；活动栏准确；两向拖拽默认移动与实际结果一致。 | 实际本机目录与跨栏输入，任务/文件 API；验证源与目标文件。 | 待实施/执行。 |
| S4-04～05 | 新建空文件/目录、名称/扩展名、重命名、冲突、删除授权/回收/永久删除与真实刷新。 | 文件管理用户入口；`FileOperationsIntegrationTest,FileRecycleIntegrationTest,FileApprovalIntegrationTest`。 | 待实施/执行。 |
| S4-03～06 | 复制/移动/重命名/删除链接自身保留目标；移动原件的自身登记/获准纳入维护；未纳入提示及不可表达指向提前拒绝。 | 实际符号链接/目录联接点；`FileLinkAssociationIntegrationTest`，本机与受控 SFTP。 | 待实施/执行。 |
| S4-07～08 | §7.7 所有支持组合，权限/同名/断链/失败/取消/部分完成/UNKNOWN；拒绝不同远端传输。 | 文件管理入口 + `FileRemoteIntegrationTest,FileReconciliationIntegrationTest`，受控 SSH/SFTP。 | 待实施/执行。 |
| S4-08，S6-02 | 文件管理缺失不阻断 APP/其他 Mod；主题、语言、100%/150%/200%、最小窗口、锁定与关闭/资源释放。 | 实际 APP 多 Mod 导航和保留视图。 | 待实施/执行。 |
| S5-01～02 | 多级必需、选中可选及其必需分支；未选不获取；去重、已装兼容复用、最高兼容推荐/定位、精确及区间。 | 新解析用例与商店真实清单界面；本机受控目录服务。 | 待实施/执行。 |
| S5-02、04 | 最新不兼容但旧版可用；无解冲突及来源缺失；用户取消可选/调整版本后重新确认；冲突无下载、运行选择不变。 | 真实客户端查询与商店选择/确认。 | 待实施/执行。 |
| S5-03～04 | 经真实通道下载、完整性/声明验证和安装；逐包失败、取消/部分完成/重试；安装不自动启用或切换选择。 | 隔离发行布局、商店真实 UI、本机受控 HTTP/HTTPS 服务；既有 `ModStoreTest` 适配。 | 待实施/执行。 |
| S6-01～04 | 适用单元/集成、API doclint、Maven 构建、格式/架构、正式结构/文档链接，所有必要行有有效结果。 | 当前 README 完整入口；阶段新目录/测试命令同步后执行。 | 待执行；仍有效证据复用，变更补受影响回归。 |
| S6-05 | 所有任务临时进程退出，临时文件清理；冻结历史 2836 文件未变，真实环境未验证范围明确。 | 任务进程身份核对、冻结校验、正式交付与 CURRENT。 | 开工冻结校验通过；收尾待执行。 |

实际命令遵循 `mvn.cmd -pl <目标> -am test '-Dtest=<本行实际用例>' '-Dsurefire.failIfNoSpecifiedTests=false'`，全项目交付使用 README 的适用入口，部署脚本指定本机 Git Bash/Python。S1 后补齐独立 ModDev 命令；尚未创建的模块和用例不以计划命令冒充运行记录。

<a id="deferred"></a>

## 10. 后期工程与非本期范围

SQL/Swing 封装保留后置方向；全软件助手及通用 Mod 业务操作 API 由 [APP 智能助手开发规划](APP-ASSISTANT-DEVELOPMENT.md)独立维护，不属于本期实施或验收清单。后期工程的编号、接口、排期和发行安排尚未确定，不创建占位模块。Git 提交/推送、跨 Mod 归属链接协调、真实环境独立验证及正式发行等边界分别按第 5、7、9 节和原 1.1 后置范围执行；Git 独立化、ModDev 和客户端商店连同下载仍在本期，窗口 P0 仍先单独处理。

<a id="author-wrappers"></a>

### 10.1 SQL 与 Swing 封装

两个封装暂时没有当前使用需求，移至后期工程，不在 1.3 实现，各自保持独立 Mod 身份，供其他 Mod 作者按需选择。平台仍提供基础 JDBC、Swing 页面接口及受管边界，作者可以直接使用基础 API，也可以显式依赖对应封装。

| Mod | 目标 | 边界 |
| --- | --- | --- |
| SQL 封装 | 为作者提供可复用的数据访问操作，降低重复编写 SQL 和相关调用代码的成本。 | 业务数据、Schema、事务意图及连接释放保持使用方归属，不以封装者身份访问其他 Mod 私有数据；首批操作与公开接口待定。 |
| Swing 封装 | 为作者提供可复用的组件与界面构建能力。 | 页面与组件生命周期保持使用方归属，遵守平台挂载、EDT、主题/语言、锁定与释放合同；首批组件与公开接口待定。 |

官方 Mod 所需的 SQL 和 Swing 界面自行编写，不依赖或调用这两个封装。该选择保留官方业务的直接控制，并使平台基础 API 本身继续得到实际使用；自写代码仍需维护 SQL 参数化、事务、界面线程和资源释放等必要规则。

封装不会天然取消自由度，实际约束取决于接口设计。官方选择自写也意味着这两个封装需要独立的测试调用方验证实际使用，不能把官方 Mod 的运行结果当成封装验收。示例与测试调用方的具体安排待实施规格明确，不增加正式官方业务 Mod 的封装依赖。

现有受管跨 Mod 服务仅传递符合值合同的结果，不能直接传递 JDBC Connection 或 Swing JComponent 等活动对象。作者侧封装如何工作在使用方的 Schema、任务及页面生命周期内，需要先确定公开接口形态；不因保留后续方向就默认现有服务机制已经满足要求。

<a id="pending"></a>

## 11. 按阶段核实与落实的事项

本表列出开工时需要核对的事实及精确实施合同，供所属阶段直接处理。已确认规则以第 2、4～8 节为准，不再作为待选方案；不用等所有后续阶段的接口全部确定才开始 P0 或 S0。每项在影响它的任务实施前核实并写回正文，改变已确认范围或行为时才按第 1.6 节澄清。

| 核实阶段 | 需要核实或落实的内容 | 核实依据与应产出结果 |
| --- | --- | --- |
| P0，S0 承接时复核 | 最终窗口/内容区尺寸，标题与反馈区归属，入口排序/隐藏、旧固定偏好归并、自有设置/预览去向、关闭与未保存内容交互，以及精确 UI 接口。 | 核对当前注册、导航和生命周期实现，按第 2.7～2.8 节核对已完成合同与交接；首次创建、切换保留及单 Mod 单入口不重新决定。 |
| S0 | 届时工作区与 P0 状态，历史真实功能到当前业务/界面的映射，实际本地验证条件及文件场景范围。 | 核对源码/资源/有效用例、构建与配置，不覆盖已有工作；形成第 4 节逐项对照、第 9 节验收矩阵，并分派后续实现缺口。 |
| S1 | 已落实 `src/moddev` 与 `src/mod` 的 Maven/IDE 组织、定向测试支持、移动后的描述校验、调试配置及首次版本/必要准备接线。 | 第6节为实际合同，第9.8节记录18类160个独立Java用例、格式工具及真实定向探针；默认开发区不进入集成 reactor 或运行扫描。 |
| S2 | 可选声明/发现的精确字段与接口、类型隔离、正常任务准入与原依赖图；受管素材引用的读取授权、持有期限、占用/释放及恢复接口。 | 核对清单、加载器、服务值、任务和素材底座，先落实公开合同再提取；验证缺少 Git 的本地实际任务、大素材交接和原版本恢复，不能只放宽加载判断。 |
| S3 | 各历史模式/页面/弹窗的实际缺口，现行 H2、任务、权限、快照和 helper 适配差异。 | 以 S0 对照逐项核对当前入口，记录适配理由及迁移后证据；历史未实现项与独立后置业务分开，不修改冻结归档。 |
| S4 | 本机/远端支持矩阵、初始目录、第二栏展开/收起、剪贴共享范围/快捷键、类型文案、批量与目录落点、新建名称编辑、操作提示及自身链接纳入交互。 | 核对 File/Link、主机/任务准入及登记合同，列明每个支持组合的实际行为、失败反馈和用例；工具栏/右键共用动作，维护范围固定为自身登记/本次获准纳入。 |
| S0 识别，S5 落实 | 目录/依赖/版本元数据、查询下载与包验证协议、本机受控服务、可选项默认勾选、推荐定位/冲突反馈及取消/部分失败接口。 | 核对现有 Release/获取安装能力，补齐真实客户端协议及夹具；以精确/区间依赖验证最高兼容推荐，不把模拟适配器或正式服务未上线当作客户端已完成。 |
| S0 固定，S6 关闭缺口 | 完整适用本地验收矩阵、当前有效证据、临时资源收尾与真实环境未覆盖范围。 | 逐项对应任务、功能、环境、入口及实际结果；必要本地缺口补齐后交付，真实 Linux/模型/正式商店验证独立安排。 |

P0 与正式 S0、S1 已完成；S2～S6 待推进。文件场景范围、商店客户端缺口与本地验收矩阵已分别记录于第 7.7、8.5、9.8 节，36 项历史对照及后续缺口已记录于第 4.4～4.5 节；其余精确合同按所属阶段落实，计划与待执行验收不代表功能已经交付。
