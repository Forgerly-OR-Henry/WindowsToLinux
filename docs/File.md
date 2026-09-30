# WindowsToLinux 源码模块与包结构

> **1.1 目标结构：** [目录标记说明](#phase-1-1-reading) · [完整目标目录及逐项职责](#phase-1-1-tree) · [目标依赖关系](#phase-1-1-dependencies) · [已确认的 Mod 源码规范](#mod-source-layout)。第 1～7 节记录当前活动源码及维护规则；第 8 节保留 1.1 职责设计与 Mod 规范，未实现内容明确标记。

## 文档信息

当前根 Maven 聚合已切换为 `src/appapi`、`src/app` 和 `src/mod`；旧 shared/web 与 SQLite JDBC 已退出活动依赖。APP 默认入口保持 H2 2.5.250、原任务与能力授权、常驻服务器/模型配置、救援和 Mod 生命周期。官方 deployment Mod 已承接确定性、辅助和自主模式及部署后管理；功能闭环按各批实际证据记录，当前源码结构不代表未通过的跨 Mod、恢复或最终 GUI 验收已经完成。

- 文档状态：1.1 源码迁移进行中；活动根已切换，完整干净根编译、结构门禁及剩余本机行为仍待集中验证。
- 当前实现范围：12 个 Maven 叶子模块；平台装配在 `app/main`，公开 Mod 合同在 `appapi`，业务实现及其 helper、资源和 H2 Schema 在 `mod/deployment`。目录只列实际源码包，不创建空职责包。
- 历史边界：`history-src` 保持冻结；仍存的旧 `src/shared`/`src/web` 文件不属于根活动构建，S6 备份 Mod 与 S7 发行打包仍暂缓。
- 服务器/秘密引用支持按精确原资源和业务键只读找回 UUID，由 appapi 声明、service 校验原任务与代次、db 查询自身登记；显式恢复拒绝新增预留和秘密创建。Mod 负责保存原恢复事实及核对自己的业务结果，平台不读取业务私有表。
- 已确认能力边界：受管应用生命周期复用既有模块，不新增独立 Maven 模块
- 更新日期：2026-09-30（活动根切换；最终验收未完成）
- 开发总纲：[DEVELOPMENT.md](development/DEVELOPMENT.md)
- 功能分工：[分析与 Linux 部署链](development/PHASE-0.3.md#architecture)

> 本文只规定源码模块、包的结构及命名，包含与之直接相关的模块职责、依赖方向和资源放置规则；发布布局、安装、运行数据及业务行为由各自文档维护。现行结构说明以当前代码核对，文档冲突时修正文档，不为了符合旧描述倒改实现；明确的未来节点仍保留规划标记。迁移与门禁更新步骤见 [1.1 开发文档](development/PHASE-1.1.md#structure-gates)。运行能力以当前实现和适用的实际行为为准，不得由目标目录或旧验收推定支持。

## 1. 完整目标结构

叶子模块下展示对应 Java 根包的真实子包，省略重复的 Maven `src/main/java` 路径；物理模板见第 4.1 节。根与聚合 `pom.xml` 固定置顶，内部包按英文名称排序。此树覆盖当前活动 Maven 源码，不以旧 shared/web 目录或规划包替代实际类型。

```text
WindowsToLinux/
├─ pom.xml
├─ docs/
├─ history-src/
└─ src/
   ├─ app/
   │  ├─ pom.xml
   │  ├─ ai/
   │  │  ├─ pom.xml
   │  │  ├─ catalog/
   │  │  ├─ execution/
   │  │  │  └─ protocol/
   │  │  ├─ recovery/
   │  │  └─ transport/
   │  ├─ db/
   │  │  ├─ pom.xml
   │  │  ├─ bootstrap/
   │  │  ├─ entity/
   │  │  ├─ execution/
   │  │  │  └─ schema/
   │  │  ├─ failure/
   │  │  ├─ log/
   │  │  ├─ mod/
   │  │  ├─ persistence/
   │  │  │  ├─ connection/
   │  │  │  └─ repository/
   │  │  └─ task/
   │  ├─ linux/
   │  │  ├─ pom.xml
   │  │  ├─ recovery/
   │  │  └─ ssh/
   │  ├─ main/
   │  │  ├─ pom.xml
   │  │  ├─ diagnostic/
   │  │  ├─ mod/
   │  │  ├─ runtime/
   │  │  └─ startup/
   │  ├─ mod/
   │  │  ├─ pom.xml
   │  │  ├─ development/
   │  │  ├─ execution/
   │  │  │  └─ lifecycle/
   │  │  ├─ installation/
   │  │  ├─ loader/
   │  │  ├─ manifest/
   │  │  ├─ process/
   │  │  ├─ service/
   │  │  └─ version/
   │  ├─ secret/
   │  │  ├─ pom.xml
   │  │  └─ crypto/
   │  ├─ service/
   │  │  ├─ pom.xml
   │  │  ├─ ai/
   │  │  ├─ contract/
   │  │  │  ├─ definition/
   │  │  │  ├─ profile/
   │  │  │  ├─ result/
   │  │  │  ├─ security/
   │  │  │  └─ spi/
   │  │  ├─ database/
   │  │  ├─ linux/
   │  │  ├─ log/
   │  │  ├─ mod/
   │  │  ├─ process/
   │  │  ├─ recovery/
   │  │  ├─ secret/
   │  │  ├─ security/
   │  │  ├─ server/
   │  │  ├─ source/
   │  │  └─ task/
   │  ├─ source/
   │  │  ├─ pom.xml
   │  │  ├─ boundary/
   │  │  └─ snapshot/
   │  ├─ ui/
   │  │  ├─ pom.xml
   │  │  ├─ ai/
   │  │  ├─ component/
   │  │  ├─ diagnostic/
   │  │  ├─ display/
   │  │  ├─ i18n/
   │  │  ├─ mod/
   │  │  ├─ recovery/
   │  │  ├─ server/
   │  │  ├─ setting/
   │  │  └─ shell/
   │  └─ windows/
   │     ├─ pom.xml
   │     ├─ filesystem/
   │     ├─ security/
   │     ├─ uninstall/
   │     ├─ update/
   │     ├─ window/
   │     └─ workspace/
   ├─ appapi/
   │  ├─ pom.xml
   │  ├─ ai/
   │  ├─ db/
   │  ├─ linux/
   │  ├─ mod/
   │  ├─ secret/
   │  ├─ server/
   │  ├─ source/
   │  ├─ task/
   │  └─ ui/
   └─ mod/
      ├─ pom.xml
      └─ deployment/
         ├─ pom.xml
         ├─ ai/
         ├─ api/
         ├─ db/
         ├─ linux/
         ├─ secret/
         ├─ service/
         │  ├─ agent/
         │  ├─ core/
         │  │  ├─ config/
         │  │  ├─ delivery/
         │  │  ├─ execution/
         │  │  └─ model/
         │  │     ├─ analysis/
         │  │     ├─ assessment/
         │  │     ├─ capability/
         │  │     ├─ ecosystem/
         │  │     │  └─ db/
         │  │     ├─ language/
         │  │     ├─ message/
         │  │     ├─ project/
         │  │     │  └─ component/
         │  │     ├─ server/
         │  │     │  └─ security/
         │  │     └─ toolchain/
         │  └─ standard/
         │     ├─ analyze/
         │     │  ├─ component/
         │     │  ├─ contract/
         │     │  │  ├─ policy/
         │     │  │  └─ spi/
         │     │  ├─ core/
         │     │  ├─ ecosystem/
         │     │  │  ├─ c/
         │     │  │  │  └─ cmake/
         │     │  │  ├─ db/
         │     │  │  ├─ dotnet/
         │     │  │  │  └─ dotnetsdk/
         │     │  │  ├─ go/
         │     │  │  │  └─ gomodule/
         │     │  │  ├─ java/
         │     │  │  │  ├─ gradle/
         │     │  │  │  ├─ jar/
         │     │  │  │  ├─ jdk/
         │     │  │  │  └─ maven/
         │     │  │  ├─ kotlin/
         │     │  │  │  ├─ gradle/
         │     │  │  │  └─ kotlinc/
         │     │  │  ├─ node/
         │     │  │  │  ├─ npm/
         │     │  │  │  ├─ pnpm/
         │     │  │  │  └─ yarn/
         │     │  │  ├─ php/
         │     │  │  │  ├─ composer/
         │     │  │  │  └─ phpcli/
         │     │  │  ├─ python/
         │     │  │  │  ├─ pip/
         │     │  │  │  ├─ pipenv/
         │     │  │  │  ├─ poetry/
         │     │  │  │  └─ uv/
         │     │  │  ├─ ruby/
         │     │  │  │  ├─ bundler/
         │     │  │  │  └─ rubycli/
         │     │  │  └─ rust/
         │     │  │     └─ cargo/
         │     │  ├─ extension/
         │     │  │  └─ registry/
         │     │  ├─ preview/
         │     │  ├─ service/
         │     │  ├─ source/
         │     │  ├─ toolchain/
         │     │  └─ workload/
         │     └─ deploy/
         │        ├─ assistance/
         │        ├─ build/
         │        │  ├─ contract/
         │        │  │  └─ spi/
         │        │  ├─ ecosystem/
         │        │  │  ├─ java/
         │        │  │  ├─ kotlin/
         │        │  │  ├─ node/
         │        │  │  ├─ php/
         │        │  │  ├─ python/
         │        │  │  └─ ruby/
         │        │  ├─ extension/
         │        │  │  └─ registry/
         │        │  ├─ generation/
         │        │  │  └─ script/
         │        │  └─ workload/
         │        ├─ contract/
         │        │  └─ spi/
         │        ├─ database/
         │        ├─ extension/
         │        │  ├─ adapter/
         │        │  └─ registry/
         │        ├─ input/
         │        ├─ plan/
         │        ├─ publication/
         │        ├─ support/
         │        │  ├─ distro/
         │        │  └─ runtime/
         │        └─ toolchain/
         ├─ source/
         │  └─ git/
         └─ ui/
```

根目录 `test` 保存不参与 WindowsToLinux Maven reactor 的独立验收夹具，分为 `single-language` 和 `multi-language`。单语言场景以 `success-` 或 `failure-` 标明预期部署结果，后接功能名称；多语言每种组合只保留一个完整成功样例。完整场景及用途见 [测试夹具说明](../test/README.md)。

125 组源码项目均通过入口、配置、请求处理、业务和模型协作。C/C++ 的 `include` 放自定义头文件，`src` 的多个编译单元链接成一个目标；其余语言采用自身包/模块机制。包管理器样例的运行依赖和真实锁文件放在各自项目内。`src/app/main/src/test/python` 保存跨语言 HTTP、破坏输入和原生组件检查脚本；逐组合证据见 [验证记录](../test/single-language/VERIFICATION.md)。

```text
test/
├─ README.md
├─ single-language/                 25 个组合、125 个原有场景
│  ├─ README.md / VERIFICATION.md / matrix.json
│  └─ <language>/<build-tool>/<framework-or-function>/<expected-result>-<function>/
└─ multi-language/                  8 个成功项目，覆盖全部 12 种源码语言
   ├─ README.md / VERIFICATION.md / matrix.json
   └─ success-<function>/           各自源码、依赖锁、样本及运行说明
```

多语言项目包括五个网页和三个控制台工具。Java、Go、C#、PHP、Kotlin、Python 分别拥有项目内 SQLite，Rust/C/C++ 工具通过子进程 JSON 协议协作；不引入数据库服务。`src/app/main/src/test/python/polyglot_runner.py` 在隔离副本构建、分配回环端口并执行验收，`src/app/main/src/test/browser` 独立管理与仓库同版本的 Playwright 依赖。源码覆盖由 `MultiLanguageFixtureMatrixTest` 门禁检查；本地行为与 Linux 产品部署证据分开维护，见 [多语言验证记录](../test/multi-language/VERIFICATION.md)。

- 根 `pom.xml` 聚合 `src/appapi`、`src/app`、`src/mod`；后两项为不含业务源码的聚合模块。
- 当前 12 个叶子模块为 `appapi`、`app/ai`、`app/db`、`app/linux`、`app/main`、`app/mod`、`app/secret`、`app/service`、`app/source`、`app/ui`、`app/windows`、`mod/deployment`；坐标统一使用 `gold.debug.windowstolinux`，Java 编译目标为 21。
- Maven 使用开发机的系统安装和系统本地仓库；POM 不声明仓库位置，不创建项目专用仓库或 Maven Wrapper。构建插件的必要构建期依赖在根 POM 对应插件中声明，不加入业务运行时 classpath。
- 结构迁移原子更新包声明、物理路径、导入、测试和门禁；退出活动图的 shared/web 及历史 SQLite 不作为兼容壳继续装配。`history-src` 保持冻结，S6 备份 Mod 与 S7 发行打包暂缓。

### 1.1 稳定职责边界

- `appapi` 只定义公开 Mod 合同；原任务、资源、Schema、秘密、引用、素材和远程会话由 APP 实现并准入，Mod 不取得 H2 管理连接或原始 SSHD 对象。
- `app/main` 装配实际平台；`app/service` 绑定任务、授权和能力，`app/db` 保存平台 H2 记录，`app/mod` 实现安装及生命周期。平台不读取 deployment 私有业务表。
- `mod/deployment/service/standard/analyze` 只生成来源绑定的确定性事实；跨语言协调、规则、注册、生态与工作负载分别归其现有职责包。辅助与自主模式各自拥有提案和执行门禁，不反向依赖彼此的算法。
- `mod/deployment/service/core/model` 保存多个引擎共用纯值；公共交付归 `service/core/delivery`，业务记录归 `db`，秘密引用归 `secret`。Source 和 Git 编排归 `source`，不复制平台素材能力。
- 标准计划、构建、工具链、输入与发布分别归 `service/standard/deploy` 的实际职责包。发行版和运行时支持判断分别归 `support/distro`、`support/runtime`；不因类数或目录对称新增模块、空包或委托壳。
- `app/linux` 独占 Apache SSHD 与物理命令、传输实现；Mod `linux` 通过公开受管 RemoteSession 发送固定业务协议。语言或工具链差异不得复制 SSH/SFTP、任务准入或通用执行逻辑。
- helper 按显式清单组装；语言分派位于 `fragments/ecosystem`，原生数据库位于 `fragments/database`，其余基础、输入、发布、运行、备份和恢复片段按实际职责分组。仅资源迁移不改变组装字节、顺序、协议或固定摘要；行为变更需同步身份与合同。
- 文件、包和类型存在不证明运行验收完成；真实 Linux 与本机受控回包分别记录。实际活动包必须出现在第一节，独立验收夹具不属于 Maven 业务模块。

## 2. 统一命名规范

命名清单按功能分组；同一功能组内的英文标准名称按不区分大小写的字母顺序排列。阶段流、规则执行顺序和带编号规范保留语义顺序。

### 2.1 总体与模块命名

| 编号 | 规范                                                     |
| ---- | -------------------------------------------------------- |
| N-01 | 模块表达依赖、技术、运行或安全边界。                     |
| N-02 | 包表达模块内部的功能和职责。                             |
| N-03 | 类名表达领域对象及其行为角色。                           |
| N-04 | 命名必须使用准确、稳定、唯一的英文含义。                 |
| N-05 | 同一个单词在不同模块中必须保持相同语义。                 |
| N-06 | 正式生产名称使用英文；简体中文仅用于 UI 映射和双语注释。 |
| N-07 | 不得使用期数、临时状态或开发阶段作为生产名称。           |
| N-08 | 不得为了目录整齐而复制逻辑或建立空分类。                 |

| 编号 | 模块命名规范                                               | 正确示例                     | 禁止示例                 |
| ---- | ---------------------------------------------------------- | ---------------------------- | ------------------------ |
| M-01 | 模块名使用小写英文。                                       | `deploy`                     | `Deploy`                 |
| M-02 | 模块名使用单数名词。                                       | `source`                     | `sources`                |
| M-03 | 模块名表达稳定能力边界。                                   | `config`、`secret`           | `tools`、`functions`     |
| M-04 | 模块名不得包含实现阶段。                                   | `deploy`                     | `phase3-deploy`          |
| M-05 | 模块名不得包含临时状态。                                   | `analyze`                    | `new-analyze`            |
| M-06 | 模块名不得表达普通内部分类。                               | `linux`                      | `linux-renderers`        |
| M-07 | 模块仅因已确认的独立职责调整；名称整理本身不构成拆分理由。 | `mod/deployment` 保持完整模块 | 将 `policy` 拆成独立模块 |
| M-08 | 平台实现模块使用明确技术限定词。                           | `linux-sshd`                 | `linux-impl`             |

### 2.2 包结构与标准职责包

模块根包以下的目标结构统一为：

```text
<模块根包>.<功能组>[.<职责>[.<分类>]]
```

第一层功能组表达一组稳定的共同职责，第二层职责包表达组内可独立命名和测试的具体责任，第三层分类包只表达该职责内部的真实分类或扩展轴。没有实际内容时不得创建空功能组、空职责包或空分类包。第 1 节目标树与已实现生产包保持同步，未实现目标以 `[PLANNED]` 明示。

| 编号 | 包命名规范                                                       | 正确示例                            | 禁止示例                             |
| ---- | ---------------------------------------------------------------- | ----------------------------------- | ------------------------------------ |
| P-01 | 包首先按标准功能组划分。                                         | `standard.deploy.extension.adapter` | `deploy.classes`                     |
| P-02 | 职责包按功能组内部可独立命名和测试的责任划分。                   | `contract.result.deployment`        | `build.objects`                      |
| P-03 | 包名使用小写英文。                                               | `validation`                        | `Validation`                         |
| P-04 | 包名使用单数名词。                                               | `policy`                            | `policies`                           |
| P-05 | 模块根包只放稳定入口、门面或公共契约。                           | `DesktopPersistence`                | 大量具体实现                         |
| P-06 | 包名不得使用期数或版本。                                         | `protocol`                          | `protocol-v3`                        |
| P-07 | 包名不得使用语言、工作负载、运行机制、发行版和架构的笛卡尔组合。 | `ecosystem.java`                    | `java.service.systemd.ubuntu.x86_64` |
| P-08 | Java 包声明必须与物理目录完全一致。                              | `build.ecosystem.java`              | 路径与声明不一致                     |
| P-09 | 包名不得使用宽泛容器词。                                         | `policy`、`validation`              | `util`、`common`、`misc`、`impl`     |
| P-10 | 包名不得重复模块名已经表达的含义。                               | `standard.deploy.plan`              | `deploy.deployment.plan`             |

| 大功能     | 父包名        | 收纳职责                                                           | 明确不收纳                               |
| ---------- | ------------- | ------------------------------------------------------------------ | ---------------------------------------- |
| 规则与契约 | `contract`    | 公共契约、能力事实、静态定义、规则、配置组合、公开结果、SPI 和校验 | 具体实现、I/O 和持久化                   |
| 内容生成   | `generation`  | 提示、渲染器、脚本和原始模板                                       | 生成内容的执行                           |
| 适配与注册 | `extension`   | 技术或形态适配、实现注册和选择                                     | 总流程编排                               |
| 执行与流程 | `execution`   | 环境准备、生命周期、迁移、协议、事务和传输                         | 规则定义、内容生成、持久化和正交运行机制 |
| 持久化     | `persistence` | 数据库或事务连接、领域仓储                                         | 远程连接、公开结果和业务编排             |

#### 2.2.1 规则与契约：`contract`

`contract` 同时是规则与契约功能组父包及类型化公共契约的直接承载包。请求、审批、计划和接口可以直接位于该包；不得在其下再建立同名职责包。

| 职责包       | 唯一职责       | 允许内容             | 禁止内容           |
| ------------ | -------------- | -------------------- | ------------------ |
| `capability` | 能力事实       | 平台、工具、运行能力 | 支持策略           |
| `definition` | 静态定义       | 范围、默认值、允许值 | 运行时执行         |
| `policy`     | 业务决策规则   | 允许、禁止、支持判断 | I/O、持久化        |
| `profile`    | 配置组合       | 命名配置和差异数据   | 执行逻辑           |
| `result`     | 公开结果       | 类型化结果和状态     | 执行器、持久化记录 |
| `spi`        | 可替换实现接口 | 窄扩展契约           | 注册和默认实现     |
| `validation` | 合法性校验     | 输入和不变量检查     | 业务计划生成       |

#### 2.2.2 内容生成：`generation`

| 职责包     | 唯一职责 | 允许内容                 | 禁止内容      |
| ---------- | -------- | ------------------------ | ------------- |
| `prompt`   | AI 提示  | 提示定义、提示构建       | Provider 调用 |
| `renderer` | 内容生成 | 配置、脚本、单元文件渲染 | 执行生成内容  |
| `script`   | 脚本组成 | Shell 片段、安全外壳     | 业务决策      |
| `template` | 原始模板 | 固定文本、占位符         | 渲染和执行    |

#### 2.2.3 适配与注册：`extension`

| 职责包     | 唯一职责       | 允许内容                     | 禁止内容     |
| ---------- | -------------- | ---------------------------- | ------------ |
| `adapter`  | 技术或形态适配 | SPI 实现、模型转换           | 总流程编排   |
| `registry` | 实现注册       | 查找、选择、去重、完整性检查 | 具体业务执行 |

#### 2.2.4 执行与流程：`execution`

| 职责包        | 唯一职责     | 允许内容               | 禁止内容 |
| ------------- | ------------ | ---------------------- | -------- |
| `environment` | 环境准备     | 环境检查和准备流程     | 应用发布 |
| `lifecycle`   | 生命周期     | 启停、重启、自启、观察 | 构建分析 |
| `migration`   | 版本迁移     | 数据结构迁移           | 普通查询 |
| `protocol`    | 类型化协议   | 固定请求、参数和结果   | 任意命令 |
| `transaction` | 原子业务事务 | 发布、恢复、回滚协调   | UI 展示  |
| `transfer`    | 受控传输     | 上传、下载、传输结果   | 部署决策 |

实际运行机制继续使用第 2.3 节的正交 `runtime` 维度，不归入本功能组。

#### 2.2.5 持久化：`persistence`

| 职责包          | 唯一职责       | 允许内容                                       | 禁止内容                   |
| --------------- | -------------- | ---------------------------------------------- | -------------------------- |
| `connection`    | 持久化连接基础 | 数据库连接创建、事务基础                       | 远程主机连接、领域仓库     |
| `mapper`        | 受管 SQL 映射  | MyBatis-Plus 按表 Mapper、复合键和条件更新 SQL | 业务编排、手动 JDBC 连接   |
| `repository`    | 持久化访问     | 聚合查询和事务写入                             | 业务编排                   |
| `serialization` | 持久化序列化   | 有界、版本化的复杂列编解码                     | 网络协议、任意对象反序列化 |

`persistence.connection` 只表示数据库或事务连接。`linux.connection` 等远程连接契约继续按所属功能命名，不迁入持久化功能组；公开结果统一归 `contract.result`。

APP H2 连接基础与仓库分别归当前 `app/db` 的连接及 `persistence.repository` 职责；Mod 自有 Schema 的仓库及有界复杂列编解码归 `mod/deployment/db`。退出活动图的 Web/MyBatis 结构不作为当前实现例外，也不因通用职责名提前创建空包。

### 2.3 正交功能维度

`ecosystem`、`workload`、`runtime` 和 `distro` 是独立于上述功能组的正交维度，可以按所属模块职责形成第一层或后续分类层，但不得互相嵌套，也不得为了目录整齐并入普通执行功能组。

| 标准包名    | 唯一维度     | 包含内容                                                                                   | 不包含内容                                                                                        |
| ----------- | ------------ | ------------------------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------- |
| `distro`    | Linux 发行版 | Ubuntu、Debian、CentOS Stream、Rocky Linux、AlmaLinux、Oracle Linux 等身份、版本与准备差异 | 编程语言、部署形态、CPU 架构                                                                      |
| `ecosystem` | 技术生态     | 语言的识别、构建架构、框架与工具链实现，以及 `db` 数据库生态的专属模型、分析和远程契约     | 跨生态公共模型、部署编排、工作负载、运行机制、发行版、CPU 架构；SSHD 原生 DB 协议实现按 O-15 归属 |
| `runtime`   | 实际运行机制 | systemd、Docker、Podman 等运行与生命周期机制                                               | 源码语言分析、发行版身份、支持等级                                                                |
| `workload`  | 工作负载形态 | 容器、静态站点、普通服务等项目形态                                                         | 编程语言、包管理器、Linux 发行版                                                                  |

| 技术生态                        | 统一包名 |
| ------------------------------- | -------- |
| .NET                            | `dotnet` |
| C、C++                          | `c`      |
| 数据库                          | `db`     |
| Go                              | `go`     |
| Java                            | `java`   |
| Kotlin                          | `kotlin` |
| Node.js、JavaScript、TypeScript | `node`   |
| PHP                             | `php`    |
| Python                          | `python` |
| Ruby                            | `ruby`   |
| Rust                            | `rust`   |

| 编号 | 正交维度规范                                                                                                                                                                                                                                                                                      | 正确示例                                                                        | 禁止示例                                                                               |
| ---- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------- |
| O-01 | `ecosystem` 保存语言、构建架构、框架和工具链实现，以及数据库生态的专属模型、分析和远程契约；SSHD 原生 DB 协议实现按 O-15 归属。                                                                                                                                                                   | `analyze.ecosystem.java`、`analyze.ecosystem.db`                                | 把共享协调器迁入 `ecosystem`                                                           |
| O-02 | DB 专属类型归 `model.ecosystem.db`；跨生态共享枚举、模型、项目事实、部署计划、事务、UI、发行版和运行机制保持原职责包。                                                                                                                                                                            | `model.language`、`model.ecosystem.db`                                          | `ecosystem.model`                                                                      |
| O-03 | 分析层的语言部分先按语言完整聚合；每个独立构建架构必须进入以工具或架构规范名命名的子包，不因当前只有一种架构而省略该层。DB 按 O-15 归类。                                                                                                                                                         | `ecosystem.java.jar`、`ecosystem.java.maven`、`ecosystem.rust.cargo`            | `ecosystem.rust` 直接放置 Cargo 检查器                                                 |
| O-04 | 构建架构包使用工具或架构的规范英文名全小写。                                                                                                                                                                                                                                                      | `maven`、`npm`、`cmake`、`cargo`                                                | `mavenbuild`、`rust-build`                                                             |
| O-05 | 分析层每个已有语言生态均在 `ecosystem.<language>` 根包保存独立语言识别器；纯语言规则不得留在 `preview` 或构建架构包。跨架构公共事实、选择器和框架协调器也留在语言根包；工具专属解析进入架构子包。                                                                                                 | `ecosystem.c.CLanguageInspector`、`ecosystem.java.jar.JavaJarManifestInspector` | `c.cmake.CLanguageInspector`、在 Java 语言识别器中读取 JAR 清单                        |
| O-06 | 目标机构建统一归 `standard.deploy.build.ecosystem`；仅有一个独立构建架构的语言直接放置具名 Renderer，存在两个及以上架构时建立一个语言子包，各架构 Renderer 直接位于该语言包。                                                                                                                     | `build.ecosystem.CargoBuildRenderer`、`build.ecosystem.java.*Renderer`          | `build.ecosystem.rust.cargo.renderer`                                                  |
| O-07 | 语言与构建工具链探测、版本解析和检查脚本生成统一归 `linux-sshd.capability.ecosystem`。                                                                                                                                                                                                            | `capability.ecosystem`                                                          | 在 `distro` 中执行 `go version`                                                        |
| O-08 | 语言工具链的发行版适配只选择包集合和能力要求；APT/DNF 包名不得进入语言生态实现，语言命令不得进入发行版实现。发行版准备以具名类直接放入 `distro.apt` / `distro.dnf`，专属规则不得集中进入家族 Catalog 或公共包管理器 Renderer；注册目录只负责装配。原生 DB 的固定安装协议按 O-15 归属。            | `distro.apt.UbuntuSetupRenderer` + `capability.ecosystem`                       | `distro.apt.ubuntu`、`distro.ubuntu.java`                                              |
| O-09 | helper 的语言与工具链分派片段位于 `fragments/ecosystem`，原生 DB 片段与数据库一致性操作片段位于 `execution/protocol/helper/fragments/database/`；协议基础、输入、发布、运行和生命周期片段保持原职责分组。片段按显式清单组装，目录排序不改变执行顺序；native 片段保留嵌入 Python 的字面内容。      | `fragments/ecosystem`、`fragments/database`                                     | 原生 DB 资源根 `db`、将 `00-protocol-foundation.sh` 移入生态目录                       |
| O-10 | 分析层的语言部分固定使用“语言＋架构”边界；执行层和能力层是否建立语言分组由独立架构数量决定，不按枚举值、文件数或目录对称决定；不得为满足数量门禁制造陪衬类型。                                                                                                                                    | `analyze.ecosystem.go.gomodule`、`build.ecosystem.GoBuildRenderer`              | 为单个类创建空 Facts                                                                   |
| O-11 | C 与 C++ 统一属于 `c` 生态，C++ 作为独立能力扩展，不以 Java 继承关系代替构建架构；CMake 架构包名为 `cmake`。                                                                                                                                                                                      | `ecosystem.c.cmake`                                                             | `ecosystem.cpp` 或 `Cpp extends C`                                                     |
| O-12 | 工作负载只表达容器、静态站点和普通服务等项目形态。                                                                                                                                                                                                                                                | `analyze.workload`、`build.workload`                                            | `ecosystem.container`                                                                  |
| O-13 | `runtime` 只表达 systemd、Docker、Podman 等实际运行机制；`distro` 只表达发行版；CPU 架构归 `capability`。                                                                                                                                                                                         | `runtime.systemd`、`distro.apt`                                                 | `ecosystem.systemd`、`distro.x86_64`                                                   |
| O-14 | `ecosystem`、`workload`、`runtime`、`distro` 相互正交，不得建立跨维度笛卡尔组合包。                                                                                                                                                                                                               | `ecosystem.java` + `runtime.systemd`                                            | `java.service.systemd.ubuntu.x86_64`                                                   |
| O-15 | 服务器原生 DB 在 `model`、`analyze`、`linux` 下归 `ecosystem.db`；`linux-sshd` 固定远程协议实现归 `execution.protocol.database`，不建立根 db 或只包含 db 的 ecosystem 外壳；各层仍各守职责。分类使用 `sql/document/other`，只按已实现职责建包；桌面和 Web 本地持久化模块仍为 `app/db`、`web/db`。 | `model.ecosystem.db.sql`、`linux.sshd.execution.protocol.database`              | `shared.standard.analyze.db`、`shared.linux.sshd.db`、`shared.linux.sshd.ecosystem.db` |

### 2.4 约束与模板命名

| 功能组   | 约束类型       | 包名                    | 类名后缀                 | 唯一含义           |
| -------- | -------------- | ----------------------- | ------------------------ | ------------------ |
| 规则定义 | 默认值         | `contract.definition`   | `Defaults`、`Definition` | 声明默认配置       |
| 规则定义 | 静态范围       | `contract.definition`   | `Definition`、`Scope`    | 声明可用范围       |
| 规则定义 | 执行前置条件   | `contract` 或所属功能包 | `Gate`                   | 表达必须通过的条件 |
| 规则定义 | 允许或禁止规则 | `contract.policy`       | `Policy`                 | 产生规则决定       |
| 规则定义 | 不可变规则数据 | 所属功能包              | `Rules`                  | 只保存规则数据     |
| 规则定义 | 输入合法性     | `contract.validation`   | `Validator`              | 拒绝非法输入       |
| 能力判断 | 支持能力计算   | 所属功能包              | `Evaluator`              | 根据事实产生决定   |
| 扩展契约 | 扩展实现约束   | `contract.spi`          | 实际角色名称             | 约束可替换实现     |

| 功能组   | 内容类型       | 包名                  | 类名后缀         | 唯一含义               |
| -------- | -------------- | --------------------- | ---------------- | ---------------------- |
| 内容生成 | AI 提示        | `generation.prompt`   | `Prompt`         | 构建模型提示           |
| 内容生成 | 模板选择       | `extension.registry`  | `Registry`       | 按类型选择模板或渲染器 |
| 内容生成 | 类型化内容生成 | `generation.renderer` | `Renderer`       | 输入对象生成最终文本   |
| 内容生成 | Shell 脚本片段 | `generation.script`   | `Script`         | 表达脚本组成           |
| 内容生成 | 安全脚本外壳   | `generation.script`   | `ScriptEnvelope` | 包装环境和安全边界     |
| 内容生成 | 原始固定模板   | `generation.template` | `Template`       | 保存文本和占位符       |
| 内容执行 | 生成内容执行   | 所属执行包            | `Executor`       | 执行已经生成的内容     |

### 2.5 行为类命名

行为类名称统一为：

```text
[范围或技术限定词] + [领域对象] + [角色后缀]
```

| 功能组       | 后缀          | 唯一含义                          | 返回或产生           |
| ------------ | ------------- | --------------------------------- | -------------------- |
| 边界与交互   | `Client`      | 调用具体网络协议或 API            | 协议响应             |
| 边界与交互   | `Controller`  | 接收 UI 或 API 输入并调用应用入口 | 界面或接口响应       |
| 边界与交互   | `Facade`      | 聚合多个用例或端口                | 统一应用入口         |
| 边界与交互   | `Gateway`     | 定义外部系统领域边界              | 类型化远端能力       |
| 边界与交互   | `Transport`   | 执行底层数据传输                  | 原始传输响应         |
| 分析与转换   | `Assembler`   | 组合多个类型对象                  | 聚合对象             |
| 分析与转换   | `Collector`   | 聚合多个事实来源                  | 能力或状态快照       |
| 分析与转换   | `Inspector`   | 静态检查源码或配置                | `Facts`              |
| 分析与转换   | `Mapper`      | 将一种类型转换为另一种类型        | 目标类型             |
| 分析与转换   | `Observer`    | 只读观察权威状态                  | 当前状态             |
| 分析与转换   | `Parser`      | 将外部文本转换为类型对象          | 类型化对象           |
| 分析与转换   | `Probe`       | 主动读取一次实时事实              | 探测结果             |
| 分析与转换   | `Renderer`    | 将类型对象转换为文本              | 配置、脚本、单元文件 |
| 分析与转换   | `Resolver`    | 将输入解析为唯一规范目标          | 路径、身份或类型     |
| 分析与转换   | `Validator`   | 校验输入和不变量                  | 成功或异常           |
| 决策与选择   | `Adapter`     | 实现 SPI 或外部形态适配           | 领域契约结果         |
| 决策与选择   | `Coordinator` | 安排多个参与者的调用顺序          | 协作结果             |
| 决策与选择   | `Evaluator`   | 根据事实计算结论                  | `Decision`、`Level`  |
| 决策与选择   | `Policy`      | 执行允许、禁止或支持判断          | `Decision`           |
| 决策与选择   | `Registry`    | 注册并选择实现                    | 唯一实现             |
| 创建与准备   | `Factory`     | 创建具有构造规则的对象            | 新对象               |
| 创建与准备   | `Planner`     | 生成确定性执行计划                | `Plan`               |
| 创建与准备   | `Preparer`    | 将原始输入准备成受控对象          | 已准备对象           |
| 执行与业务   | `Executor`    | 执行命令、协议或计划              | 执行结果             |
| 执行与业务   | `Migrator`    | 执行版本化结构迁移                | 迁移结果             |
| 执行与业务   | `Service`     | 执行可复用业务流程                | 领域结果             |
| 执行与业务   | `UseCase`     | 完成一个用户目标                  | 应用操作结果         |
| 持久化与展示 | `Presenter`   | 将结果转换为展示内容              | 展示模型或文本       |
| 持久化与展示 | `Repository`  | 持久化领域聚合                    | 聚合或事务结果       |
| 持久化与展示 | `Store`       | 保存秘密、载荷或简单值            | 存取结果             |

### 2.6 数据类命名与阶段

| 功能组     | 后缀            | 唯一含义                                      |
| ---------- | --------------- | --------------------------------------------- |
| 事实与判断 | `Assessment`    | 基于事实形成的分析结论                        |
| 事实与判断 | `Decision`      | `Policy` 或 `Evaluator` 产生的决定            |
| 事实与判断 | `Evidence`      | 可审计的来源、证明或调用证据                  |
| 事实与判断 | `Facts`         | 已观察到的确定性事实，不包含判断              |
| 输入与授权 | `Approval`      | 用户对明确内容的批准                          |
| 输入与授权 | `Arguments`     | 固定协议或命令的类型化参数                    |
| 输入与授权 | `Context`       | 一次协作所需的最小输入                        |
| 输入与授权 | `Request`       | 一次操作的完整类型化输入                      |
| 计划与执行 | `Action`        | 可执行的封闭动作                              |
| 计划与执行 | `Gate`          | 执行前必须满足的条件                          |
| 计划与执行 | `Outcome`       | 面向应用或 UI 的最终操作摘要                  |
| 计划与执行 | `Plan`          | 执行前生成的确定性有序计划                    |
| 计划与执行 | `Result`        | 底层或可复用操作结果                          |
| 状态与事件 | `Event`         | 已经发生的事实                                |
| 状态与事件 | `Snapshot`      | 某个时间点的不可变状态                        |
| 状态与事件 | `State`         | 对象当前的组合状态                            |
| 状态与事件 | `Status`        | 单一有限状态值                                |
| 配置与定义 | `Catalog`       | 有限且权威的支持项集合                        |
| 配置与定义 | `Configuration` | 已选择并可以实际应用的配置                    |
| 配置与定义 | `Definition`    | 类型、范围和默认规则定义                      |
| 配置与定义 | `Profile`       | 可选择、可复用的命名配置组合                  |
| 配置与定义 | `Rules`         | 不执行行为的不可变规则数据                    |
| 配置与定义 | `Scope`         | 定义配置或操作的适用范围                      |
| 配置与定义 | `Specification` | 希望达到的目标规格                            |
| 标识与分类 | `Identity`      | 稳定且规范化的对象身份                        |
| 标识与分类 | `Kind`          | 领域对象的封闭类别                            |
| 标识与分类 | `Level`         | 有序等级                                      |
| 标识与分类 | `Mode`          | 用户或系统选择的工作模式                      |
| 标识与分类 | `Type`          | 协议或模型定义的类型分类                      |
| 描述与引用 | `Descriptor`    | 对资源属性的不可变描述                        |
| 描述与引用 | `Entry`         | `Manifest`、`Snapshot` 或存储结构中的单个条目 |
| 描述与引用 | `Manifest`      | 可序列化的条目清单                            |
| 描述与引用 | `Reference`     | 指向外部或不透明对象的稳定引用                |

数据阶段名称统一按以下顺序使用：

```text
Facts → Evidence → Assessment → Decision → Plan → Result/Outcome
```

| 顺序 | 类型         | 唯一职责         |
| ---- | ------------ | ---------------- |
| 1    | `Facts`      | 保存确定性事实   |
| 2    | `Evidence`   | 保存事实依据     |
| 3    | `Assessment` | 形成分析结论     |
| 4    | `Decision`   | 形成规则决定     |
| 5    | `Plan`       | 形成执行计划     |
| 6    | `Result`     | 保存底层执行结果 |
| 7    | `Outcome`    | 形成应用最终摘要 |

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
| C-07 | 静态操作集合不得通过复数类名表达。 | `ServerUseCaseFacade`       | `ServerUseCases`     |

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

缩写检查按 PascalCase 单词边界判断。`CLanguageInspector` 中的 `C` 是语言名称，后续 `Language` 是独立单词；不得将相邻的 `C` 与 `L` 误判为缩写 `CL`。真正的 `SSHSession`、`JavaJARInspector` 等全大写缩写仍违反规范。

| 名称                     | 使用规则                           | 例外                                     |
| ------------------------ | ---------------------------------- | ---------------------------------------- |
| `Base`                   | 禁止作为宽泛父类名称               | 明确稳定继承协议                         |
| `Bean`、`Object`         | 禁止                               | 无                                       |
| `Checker`                | 禁止                               | 使用 `Validator`、`Evaluator` 或 `Probe` |
| `Common`、`Misc`         | 禁止用于类型、包和受维护资源文件名 | 无                                       |
| `Concrete`               | 禁止表示具体实现                   | 使用技术或功能限定词                     |
| `Controller`             | 限制为 UI 或 API 输入边界          | 非 UI 或 API 操作使用 `Executor`         |
| `Data`、`Info`           | 禁止表示普通数据对象               | `DataPath` 等真实领域术语                |
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

| 编号 | 异常命名规范                                                                                                             | 正确示例                                   | 禁止示例                               |
| ---- | ------------------------------------------------------------------------------------------------------------------------ | ------------------------------------------ | -------------------------------------- |
| E-01 | 异常使用 `Exception` 后缀。                                                                                              | `LinuxOperationException`                  | `LinuxOperationError`                  |
| E-02 | 名称必须说明失败边界。                                                                                                   | `SecretStoreException`                     | `OperationException`                   |
| E-03 | 公共异常不得直接以底层实现细节命名。                                                                                     | `LinuxOperationException`                  | `SshChannelException`                  |
| E-04 | 安全异常名称不得包含秘密内容。                                                                                           | `SecretStoreException`                     | `InvalidPasswordValueException`        |
| E-05 | 1.1 受控失败以公开实例方法 `Problem problem()` 暴露安全原因。本地化消息与安全诊断分离。 | `ApiException`、`BrowserRecoveryException` | 原始 `exception.getMessage()` 用户回退 |
| E-06 | 异常使用 `[FailureBoundary]Exception`，名称说明模块内失败边界。                                                          | `SourceArchiveException`                   | `SourceError`、`OperationException`    |
| E-07 | 模块错误枚举使用 `[Boundary]FailureType`。                                                                               | `LinuxOperationFailureType`                | `LinuxError`、`FailureKind`            |
| E-08 | 只有重复的确定性转换逻辑才建立 `[BoundaryOrTechnology]FailureMapper`。                                                   | `SqliteFailureMapper`                      | 单次使用的通用 `ErrorManager`          |
| E-09 | 恢复判断使用 `[Operation]FailureRecoveryPolicy` 与 `[Operation]FailureRecoveryDecision`。                                | `DeploymentFailureRecoveryPolicy`          | `RecoveryProcessor`                    |
| E-10 | 业务类型不得使用 `Error`、`Manager`、`Processor` 等泛化错误治理名称。                                                    | `DesktopFailurePresenter`                  | `ErrorManager`、`FailureProcessor`     |

任务持久化的 `TaskLedger.DependencyBusyException`、`TaskLedger.FinalizationException` 与私有 `PlatformTaskRuntime.AdmissionRejectedException` 是明确的内部 SQL 回滚信号，分别保留依赖占用、原归档凭据与准入拒绝；均在任务宿主内转为公开失败或待收尾状态，它们不实现旧共享契约，也不是允许其他异常省略结构化原因的通用例外。架构检查按真实返回类型检查公开、非静态、无参数的 `problem()`，保留异常命名要求及旧错误枚举登记检查。

平台救援的临时状态由 `app/db/persistence/repository/BrowserRecoveryRepository` 保存，平台写入入口先核对原活动任务与服务器；`app/db/task/BrowserRecoveryArchival` 在原中央任务事务内完成摘要及事件清理。其固定宿主协议由此数据边界定义，service 准入及救援总装共用，不产生新的作者 API 或 Maven 模块。

H2 引擎及实际连接所有权由 `app/db/persistence/connection/PlatformDatabase` 承担；模块根包的 `PlatformPersistence`、`DesktopPersistence` 只组合仓库。任务、日志和 Schema 实现依赖连接层，连接层不反向引用仓库、任务或 Schema 类型；口令派生只接收 Schema 层提供的精确已编码数据库用户名，沿用原命名空间和算法。

宿主启动通过 `app/main/mod/PlatformModHost` 向 Mod 上下文与启动图注入已有能力和原会话访问，不由 `main/mod` 反向引用 `main/startup/PlatformRuntime`。`RunModeResolver` 用自身代码源定位所在的 main 模块，数据根仍用 DB 模块锚点。`app/ui/mod/ModPageRegistry` 只管理 Mod 页面及其生命周期，由 shell 注入挂载界面；Mod 管理页面不反向引用 shell。

`app/service/contract/profile` 保存服务器和模型的非秘密配置值，`contract/result` 保存模型清单摘要，`contract/spi` 保存平台配置所需的窄访问接口；这些契约不反向引用 service 的服务器或模型实现。`service/security` 管理安全会话，`service/task` 管理任务与授权；任务绑定的秘密适配归 `service/secret`，实际辅助进程适配归 `service/process`，二者依赖任务基础，任务基础不反向引用能力实现或日志服务。

| 枚举性质   | 统一后缀      | 示例                         |
| ---------- | ------------- | ---------------------------- |
| 可执行动作 | `Action`      | `LifecycleAction`            |
| 规则决定   | `Decision`    | `HostKeyDecision`            |
| 处置结果   | `Disposition` | `CollaborationDisposition`   |
| 已发生事件 | `Event`       | `DeploymentTraceEvent`       |
| 封闭类别   | `Kind`        | `RuntimeKind`                |
| 有序等级   | `Level`       | `DeploymentSupportLevel`     |
| 工作模式   | `Mode`        | `RunMode`                    |
| 命名配置   | `Profile`     | `EcosystemCapabilityProfile` |
| 适用范围   | `Scope`       | `ConfigurationScope`         |
| 选择来源   | `Source`      | `CredentialSource`           |
| 当前状态   | `State`       | `RuntimeState`               |
| 操作状态   | `Status`      | `DeploymentStatus`           |
| 领域类型   | `Type`        | `DeploymentProjectType`      |

| 编号 | 枚举命名规范                                             | 正确示例                                       | 禁止示例                   |
| ---- | -------------------------------------------------------- | ---------------------------------------------- | -------------------------- |
| G-01 | 枚举必须使用“领域对象＋语义后缀”。                       | `LinuxDistroType`                              | `LinuxDistro`              |
| G-02 | 统一后缀表是穷举白名单，不允许清晰领域名例外。           | `SourceLanguageType`                           | `SourceLanguage`           |
| G-03 | 生产、测试、顶级和嵌套枚举使用同一规则。                 | `ComponentIssue.SeverityLevel`                 | `ComponentIssue.Severity`  |
| G-04 | 顶级类型简单名称在仓库内必须唯一。                       | `DeploymentPlanAction`、`DeploymentTraceEvent` | 两个 `DeploymentStep`      |
| G-05 | 嵌套类型以外部类型形成唯一作用域，但仍必须使用语义后缀。 | `ComponentDataPath.AccessMode`                 | `ComponentDataPath.Access` |

枚举常量统一使用：

```text
UPPER_SNAKE_CASE
```

| 测试类型 | 统一格式                     | 示例                                |
| -------- | ---------------------------- | ----------------------------------- |
| 验收测试 | `<Capability>AcceptanceTest` | `ReviewedDeploymentAcceptanceTest`  |
| 架构测试 | `<Rule>ArchitectureTest`     | `PackageNamingArchitectureTest`     |
| 契约测试 | `<Contract>ContractTest`     | `ManagedRemoteContractTest`         |
| 集成测试 | `<Subject>IntegrationTest`   | `DesktopPersistenceIntegrationTest` |
| 回归测试 | `<Subject>RegressionTest`    | `DeploymentRollbackRegressionTest`  |
| 单元测试 | `<Subject>Test`              | `SourceBoundaryValidatorTest`       |

## 3. 模块职责

### 3.1 `appapi` 公开合同

`appapi` 定义 Mod 的初始化、迁移、生命周期、页面、任务、数据库、交互、AI、素材、秘密、服务器及远程能力合同；不依赖具体 APP 实现或部署业务。API 版本与内部 Mod 用途枚举分别管理。

### 3.2 `app` 平台模块

| 叶子模块 | 当前职责 |
| --- | --- |
| `ai` | 平台配置目录值、通用模型消息、HTTP 传输与救援协议 |
| `db` | H2 平台仓库、受管 Schema、任务、引用与操作账本 |
| `linux` | Apache SSHD 连接、脚本标准输入、命令输出、SFTP 及所属救援浏览器 |
| `main` | 入口、固定运行根、平台装配、Mod 协调及默认窗口 |
| `mod` | 原包、描述、类加载、安装登记、依赖与生命周期 |
| `secret` | 受保护凭据、当前用户保护、主密码与秘密存取 |
| `service` | 原任务授权、公开能力适配、平台配置和安全用例 |
| `source` | 受管本机目录、进程、Git、素材冻结、只读与归档能力 |
| `ui` | 平台 Swing 组件、服务表单、页面挂载及本地化 |
| `windows` | 原生保护与窗口隐私、文件/进程边界、签名与更新卸载安全核心 |

`app/ui` 通过平台服务协作；平台装配可组合必要实现，低层实现不反向依赖装配。Windows 更新、卸载的安全核心存在不等于 S7 独立执行器已经交付。

### 3.3 `mod/deployment` 官方部署 Mod

部署 Mod 独占静态分析、辅助与自主算法、部署配置、应用和发布业务表、秘密引用及固定 helper 协议。原任务通过 APP 公开能力取得素材、审批、服务器、Schema 和远程会话，正常停用只释放 Mod 自有资源，不能擅自停止已经部署的远端应用。备份 Mod 不在当前迁移范围；退出活动构建的旧 shared/web 不列为当前能力。

### 3.4 配置规则、存储与秘密边界

1. APP 平台配置与模型目录由平台 H2 仓库保存；deployment 的配置实例、不可变修订、运行事实和发布账本由其自有 Schema 保存。平台不按表名读取 Mod 私有业务。
2. Mod 配置解析与载荷编码归 `service/core/config`，来源绑定运行默认值归 `service/standard/deploy/input`；这些纯规则不读写平台数据库，不暴露原始远程能力。
3. 普通配置只保存精确不可变秘密 ID/修订引用。APP 的秘密能力负责保护与权限，Mod 只保存所属业务引用；明文值、可清零缓冲和调用方借用数组按真实所有权处理，不用显示文本表达秘密身份。
4. APP 的 CLASS/JAR 根解析归 `app/main/runtime`，装配归 `app/main/startup`。平台 UI 使用 `service/contract` 门面，Mod UI 使用自己的用例和原公开 TaskContext，不绕过授权访问内部仓库。

### 3.5 桌面端本地化与诊断边界

1. 英文是桌面端生产代码、内部校验、程序生成诊断和默认消息资源的基准语言；`app/ui/i18n/messages/Messages.properties` 是消息目录的规范来源，简体中文只保存在 `Messages_zh_CN.properties`。
2. 生产模块不得提前拼接英文或中文界面句子。1.1 失败通过公开 `Problem` 传递稳定事件码、安全英文诊断、上下文及原诊断身份；UI 按 `problem.<eventCode>` 查找固定本地化说明，未登记事件使用脱敏后的安全诊断。UI 是唯一负责解析显示语言的边界。
3. 部署步骤、部署状态、置信度、运行状态、自启状态和生命周期动作必须以稳定代码、`record` 或 `enum` 跨模块传递，不得依赖 `Object.toString()` 或显示字符串表达业务状态。

语言选择、原始诊断处理等运行规则见 [运行约定](Runtime.md)。

### 3.6 结构化错误契约与模块归属

1. 公开失败使用 `appapi/task` 的 Problem、ProblemCode、ApiException；错误识别与安全转换由产生错误的模块负责，不新增中央 error 模块或复制模块私有异常。
2. 稳定事件码、分类、上下文及原诊断身份不能被显示文本或重写后的错误替代。已发布事件码不复用、改义或静默删除；内部 cause 不得越过公开安全边界暴露秘密。
3. UI 按实际 `problem.<eventCode>` 本地化键呈现；未知事件使用脱敏后的安全诊断。原生 DB、发布和恢复失败保留原操作、请求、服务器、资源及 UNKNOWN 身份。
4. `FailureContractArchitectureTest` 对当前活动生产源码执行真实 AST、异常类型、Problem 出口、事件登记、双语消息与捕获门禁。门禁通过只证明相应静态合同，不证明目标机结果或未知操作已经结清。

重试、恢复、日志及运行数据规则见 [运行约定](Runtime.md)。

## 4. 叶子模块约定

### 4.1 Maven 通用目录结构

本节描述当前活动 Java 叶子模块的物理目录约定；未实施的 Mod 与发行工具不提前建立空源码目录。

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

Java 包前缀固定为 `gold.debug.windowstolinux`，其后按 `appapi`、`app.<module>`、`mod.<mod-id>` 表达所属模块。没有实现内容时不提前创建空目录；历史 shared/web 保留源码不代表其仍参与当前 Maven 活动图。

### 4.2 适配协作边界

语言生态、构建架构、工作负载、运行机制、发行版与 CPU 架构保持正交。Mod 的分析和构建生态共享 `service/core/model` 的不可变事实；平台 Linux 只负责机械远程能力，业务 helper 只经原 TaskContext 与实际审批发送。

1. 分析生态负责识别与预览；每个真实独立构建架构在其语言分类内命名，不用目录对称或类数建立空分组。
2. 构建 SPI、注册与脚本生成归 `service/standard/deploy/build/{contract,extension,generation}`，生态和工作负载差异分别归 `ecosystem`、`workload`，不得复制通用候选、SSH、任务或 helper 准入逻辑。
3. 工具链与发行版事实通过已审阅类型协作；环境准备、精确绑定、构建预算和人工审批等待分别核对。未知准备或绑定保持原意图，不能通过重发或重置预算掩盖。
4. 静态分析未正式支持的组合只给预览或不支持结果；进入辅助或自主流程仍需其自己的来源、模型、人工审阅和受管能力准入，不把静态拒绝变成未经授权的自动执行。
5. 平台 `app/linux` 独占 Apache SSHD；公开合同和 deployment `linux` 不引用其具体客户端、会话、通道或 SFTP 类型。Mod 不能绕过公开 RemoteSession 取得不受控宿主能力。

### 4.3 分包规则

1. Maven 模块表达依赖、技术和安全边界；Java 包和前端目录只负责模块内部组织，必须遵守第 5 节依赖方向。
2. 模块根包只保留稳定入口、门面或确需跨内部包使用的公共契约，具体实现进入职责明确的子包。
3. 测试包镜像对应生产包；根目录源码服务夹具使用 `test/single-language/<language>/<build-tool>/<framework-or-function>/<expected-result>-<function>` 分类，每个语言/工具组合固定三组正常部署与两组失败部署；由 `test/single-language/matrix.json` 及矩阵测试对照实际支持目录核对覆盖。正常组使用 `success-deployment-smoke`、`success-json-api`、`success-runtime-config`，失败组使用 `failure-health-rollback`、`failure-configuration-rejected`，具体故障按工具的健康检查与配置准入问题分配。`success-` / `failure-` 表示预期部署结果，不表示自动化测试应通过或报错。测试场景、文件、包及构建标识使用功能名称，不使用开发期数或执行批次；额外运行版本元数据放入模块测试资源，由验收辅助类实例化，不增加顶层场景数量。
4. 模块、包、类、接口、枚举、异常和测试类名称统一遵守第 2 节，不得另立同义词、临时名称或兼容名称。
5. `analyze` 的跨语言公共流程按 `core`、`source`、`service`、`component`、`workload` 与 `preview` 分包；规则和 SPI 进入 `contract.policy`、`contract.spi`，默认装配进入 `extension.registry`。`ecosystem` 内按语言及 `db` 分类；每个真实独立语言构建架构均按第 2.3 节建立架构名子包，语言识别器、跨架构公共事实、选择器和框架协调器留在语言根包，DB 声明检查归 `ecosystem.db`。`ProjectLanguageInspector` 固定汇总全部 10 个语言生态的检查器；`PreviewLanguageMarkerCatalog` 只识别尚无独立生态的长尾语言。`source.SourceLanguageEvidence` 只收集路径证据，具体语言规则由语言检查器提供。
6. `service.standard.deploy` 将不可变输入/计划、SPI、注册、支持判断、候选构建和发布编排分离。SPI 进入 `contract.spi`，适配器与注册进入 `extension.{adapter,registry}`，发行版及运行时判断分别进入 `support.{distro,runtime}`；`plan` 不直接构造具体实现。公共值归 `service.core.model`，正式持久事实归 Mod `db`。
7. `appapi.linux` 保存平台远程合同，`app/linux` 实现 SSHD 机械执行与传输；Mod `linux` 保存部署业务的固定 helper 请求、回执与实际远程发送边界。语言分派与数据库脚本按 helper 实际资源职责分组，原生 DB 资源仍归 `execution/protocol/helper/fragments/database`。
8. 分析层发现真实独立构建架构时必须建立架构名子包；执行层和能力层建立语言分组时以独立架构数量为依据，不以类数量为依据。不得为满足目录对称或门禁数量新增空分类、空接口、委托壳或无独立语义的数据类型。
9. 界面、数据库、认证、秘密和普通业务用例不得按被部署项目的语言复制结构。
10. 包结构不用于绕开模块职责。跨模块能力仍通过既有依赖和类型化契约协作，不复制模型，不开放原始 SFTP 或不受控 systemd、Docker、Podman 操作；Agent 生成的命令只能经绑定任务、身份与修订的受限命令接口执行。
11. 公开 `appapi.linux` 与 deployment 的请求、结果、协议和异常不导入或暴露 Apache SSHD 类型；`app/linux` 可以依赖 Apache SSHD，但不把具体客户端、会话、通道或 SFTP 类型传递给 Mod。
12. 生产包依赖不得成环；组合门面只能依赖下游窄契约和实现，低层 command、SPI、不可变契约与错误类型不得反向依赖注册表、默认实现、会话或业务编排。
13. 模块 Java 根包以下默认最多三层子包：第一层表达功能组或既有正交功能，第二层表达独立职责，第三层表达职责内部的真实分类或扩展轴。官方 deployment 的 `service.standard.analyze` 与 `service.standard.deploy` 是各自逻辑职责根，其后分别最多三层（如 `ecosystem.java.maven`、`build.generation.script`）；`service.core.model` 是多个引擎共用纯值的逻辑职责根，其后最多两层（如 `project.component`、`ecosystem.db`）。三个精确锚点不放宽其他包的三层上限，标准算法不得进入 core/model。其中 `service.core.model.capability` 仅保存 Linux/生态能力纯值，按该已审模型根归属，不映射为执行算法；实际应用生命周期操作归 `service.core.execution`。第四层或更深结构若不属于这些已审锚点，须先修改本文并单独评审；总包数、单包类型数量和目录对称不作为硬门禁。
14. `contract`、`generation`、`extension`、`execution`、`persistence` 的直接子包只使用第 2.2 节允许的职责名；标准职责不得绕过父功能组。公开 API 的任务、远程、资源与模型契约及上述三个 Mod 逻辑根按实际语义核对，不能恢复旧模块的整包豁免。`ecosystem`、`workload`、`runtime`、`distro` 保持正交，尤其不建立 `execution.runtime`。
15. 普通类最多 25 个实例字段、30 个直接声明方法，单方法最多 80 个 JDK AST 语句；退出活动图的旧门面不保留规模豁免。
16. 合并依据是行为完全一致且差异可由受校验数据表达，拆分依据是存在可独立测试和命名的职责；领域记录、枚举、状态类型和 SPI 不因文件短小而合并，类也不因行数较长而机械拆分。

### 4.4 Linux 部署链内部依赖方向

平台依赖按第 5 节实际模块图检查。deployment 的公共模型、输入值与 Linux 机械合同不反向依赖 UI 或引擎编排；辅助分析不调用自主算法，自主循环不调用静态分析。

标准分析向计划提供确定性事实；已审阅计划交给候选、构建和发布用例，公共交付核对完整应用图并正式提交。业务仓库只通过所属受管 Schema 访问自己的表；远端协议只通过原任务与审批发送。注册与组合入口可以依赖具体实现，底层纯值、编解码和协议解析不能反向依赖入口，生产包图不得成环。

### 4.5 helper 资源与物理会话装配

`DeploymentManagedHelperBundle` 以显式顺序组装固定资源，`DeploymentPublicationEvidenceChannel` 与 `DeploymentPublicationReceipt` 分担原任务／独立操作证据和固定回执解析；Channel 保留实际远程准入与发送。协议 14 当前有 58 个维护资源，组装 SHA 与资源集合分别核对。37 项未改资源的完整路径和原始 SHA 对应已提交 081 基线，不能用更早 shared 版本代替。

CLASS/JAR loader、SSH 客户端、物理会话、通道、线程、进程及 JDBC 连接分别由实际所有者关闭并核对退出；返回 close 或发出停止请求不自动清零资源账本。Mod.stop 只释放 Mod 所属资源，已经提交的远端部署和平台 H2 数据保留。

## 5. 依赖方向

活动模块的允许生产依赖为：

```text
appapi ──→ none
app/ai ──→ appapi
app/source ──→ app/windows, appapi
app/linux ──→ app/ai, app/windows, appapi
app/mod ──→ appapi
app/ui ──→ app/ai, app/service, appapi
app/windows ──→ appapi
app/db ──→ app/ai, appapi
app/secret ──→ app/db, appapi
app/service ──→ app/ai, app/db, app/linux, app/mod, app/secret, app/source, app/windows, appapi
app/main ──→ app/ai, app/db, app/linux, app/mod, app/secret, app/service, app/source, app/ui, app/windows, appapi
mod/deployment ──→ appapi
```

1. `appapi` 是独立公开合同，不反向依赖 APP 实现或业务 Mod。官方 deployment 的生产依赖仅为 appapi 和其明确外部编解码依赖；APP 的 Maven 主体不依赖业务 Mod。测试宿主依赖不构成生产授权。
2. `app/main` 是实际装配根。平台 Linux、来源、秘密及 AI 实现不反向引用 UI，业务 Mod 不读取 APP 私有实现或其它 Mod 私有数据。
3. `app/ai` 仅包含平台模型调用与库存纯值，不依赖数据库或界面。DB 和 UI 显式依赖 `app/ai`，仅复用 `AiCapabilityType`、`AiPurposeType`、`AiPurposeAssignment` 库存值；内部用途名称不替代公开 Mod 的 `AiPurpose` 合同。
4. APP UI 经 `app/service` 发起平台操作，不能直接使用 DB、Windows、Linux、来源、秘密或 Mod 执行实现，也不能绕过服务直接调用公开 SDK 的 Linux、进程、来源写入或秘密能力。SDK 的任务、问题、UI、审阅和只读值合同可按实际用途使用，不设旧业务整包例外。
5. 服务通过实际任务、审批、秘密与引用账本维护授权；不能靠传递依赖取得未声明的直接生产依赖。平台与业务均禁止循环依赖和全局静态状态绕过边界。
6. 辅助分析只使用原冻结源码的只读 list/read/search 和有界 AI 请求；原候选构建、纠正、恢复和正式发布由已审阅的执行路径负责，分析不能取得执行、秘密或可写源码能力。
7. 原 Windows 签名、协调及认证交接算法保留在平台职责中；当前未接生产安装器/后台更新 HostPort。旧 backup 工作区和业务实现的延后边界以冻结归档与 S6/S7 任务记录为准。

`ModuleDependencyArchitectureTest` 读取本节允许方向并检查真实 Maven 叶子 POM、生产引用及安全反例；`DocumentStructureArchitectureTest` 双向核对实际包与第 1 节目录树。未实现节点不能通过建立空包消除门禁。

## 6. 桌面端数据目录

本标题保留为现有模块依赖测试的解析结束标记。实际运行数据目录与行为规则统一见 [运行约定](Runtime.md#desktop-data)，不在本文维护。

## 7. 源码结构约束

当前活动结构见第 1～6 节，1.1 职责设计与 Mod 规范见第 8 节。结构变更、模块评估和门禁更新流程见 [1.1 开发文档](development/PHASE-1.1.md#structure-gates)。

1. 普通 Maven 叶子模块的目录名使用一个英文单词；只有把具体实现从公共契约模块独立隔离时，才使用 `<contract>-<implementation>`，例如 `linux-sshd`。
2. Java 类存在接口与实现不代表必须拆分 Maven 模块；只有具体实现需要独立依赖或装配时才建立连字符实现模块。`source` 当前不拆分为 `source-tar`，源码归档格式属于其内部职责。
3. 只有经过确认的目录、模块名称、职责和依赖方向才能写入正式结构；禁止通过复制模型、反射或静态全局状态绕开依赖约束。
4. `.idea/`、`.ai-workspace/`、`target/`、`node_modules/`、`dist/`、缓存、日志和其他开发辅助或生成目录不纳入正式项目结构。
5. 分期是开发路线与验收文档的组织方式，不是产品运行时架构。`src/` 中的模块、包、类、方法、字段、枚举、消息键、配置键、资源名、脚本名和测试名不得以 `PhaseOne`、`PhaseTwo`、`phase1`、`phase2`、0.1 期、0.2 期等期数命名；必须按稳定职责命名。正式文档保留分期标题，内部按功能组织；日期和版本仅为必要证据属性，不维护时间流水账，不把期数泄漏为代码 API 或持久化契约。
6. `DeploymentAnalysisCoordinator` 不导入具体项目类型实现；`ProjectLanguageInspector` 只组合确定性的语言事实检查器。低层语言与构建 Inspector 不修改调用方拒绝集合，生产与测试包镜像，包依赖不成环，不按开发阶段或宽泛类别聚合实现。标准 `plan` 不构造具体适配器，APP SSH 机械层不保存业务状态，低层命令不反向依赖会话组合入口；不以兼容壳保留旧类型。
7. `PackageStructureArchitectureTest` 使用 JDK 编译器 AST、物理路径和生产导入图自动检查文件与顶级类型同名、仓库级顶级类型唯一性、顶级及嵌套枚举语义后缀、禁限用词及封闭例外、资源文件名、复数后缀、缩写、测试后缀、模块根包以下最多三层、五个功能组及合法职责、远程契约和领域模型例外、语言/构建架构/发行版分类轴、禁用包名、全部包依赖环、测试包镜像、职责映射、反向依赖、旧 FQCN、旧物理包、旧 helper 资源路径、已删除包装类以及仅允许的 `AppMain.main` 产品入口。Java 职责规模门禁仅排除 AST 识别的 Javadoc 行，正文、实现注释及嵌入脚本仍计入现有上限，脚本文件上限保持不变；双语文档另由 `JavadocCoverageTest` 负责。门禁不设置总包数或单包类型数量硬上限，不得通过文本豁免隐藏结构回归；通过结果只证明当前本地静态结构，不构成新的 Linux 运行证据。

<a id="phase-1-1-target"></a>

## 8. 1.1 职责设计与 Mod 规范（实际结构见第 1 节）

**本节全部为尚未实施的目标设计**，不进入第 1 节当前目录树或第 5 节依赖解析区域，也不要求现在创建目录或模块。[1.1 开发文档](development/PHASE-1.1.md)维护迁移表、实施步骤、归档、技术细化和验收；本节是新结构唯一详细目录来源。

<a id="phase-1-1-reading"></a>

### 8.1 目录标记与阅读方式

已认可保留现有 `src/` 作为活动源码的容器，“顶层 `app`、`appapi`、`mod`、`web`”指其下四个职责分组。与 `app` 并列的插件源码聚合模块名称已确认为 `mod`，路径为 `src/mod`，统一收纳各 Mod 的源码模块。仓库根仍容纳文档、验收夹具和构建工具；`history-src` 位于根目录并退出活动构建。Linux 单模块及两个官方 Mod 各自采用一个主模块已确认；补全方案采用本文目标结构，不因 api/ai/db 职责包而额外拆模块。同作者从模块只服务实际复用或隔离需求，不固定全工程 POM 数量。

每一行的 `#` 后都注明节点类型与职责，按下表区分“目录、模块、包”：

| 标记       | 含义                                 | 阅读时注意                                                            |
| ---------- | ------------------------------------ | --------------------------------------------------------------------- |
| `[目录]`   | 源码、文档或工具的文件系统分组       | 不因有目录就产生独立 Maven 模块。                                     |
| `[聚合]`   | 拟设 Maven 聚合模块                  | 管理子模块构建；自身不承载业务 Java 实现。                            |
| `[模块]`   | 拟设独立 Maven 叶子模块              | 有自己的 POM、源码和测试；不等于用户要单独安装的插件。                |
| `[包]`     | 所在模块内部的 Java 职责包           | 没有独立 POM；包名按所属模块及职责展开。                              |
| `[入口类]` | Mod 主模块根包内的 `Mod.java`        | 负责装配与生命周期；不是独立模块或业务实现包。                        |
| `[公开包]` | 平台 API 或 Mod 向调用方开放的契约包 | 只承载公开契约，不混入主体或插件私有实现；不自动成为独立 Maven 模块。 |
| `[资源]`   | 所在模块的资源目录                   | 对应 Maven `src/main/resources`，不是 Java 包；清单规范见 Mod.md。    |
| `[文档]`   | 现有文件在目标结构中的职责           | 文件已存在，但其中的目标设计不表示代码已完成。                        |
| `[归档]`   | 旧工程冻结副本                       | 不参与新构建、IDE 活动包树或生产发布。                                |

下方省略每个 Java 叶子模块重复的 `src/main/java/gold/debug/windowstolinux/...` 和 `src/test/java/...` 路径，直接展示模块根包下的职责包。尚未细化子包的模块只说明模块责任，不据此推定要把所有类平铺在一个包里。文档文件名逐项展开，方便审阅各份文档的归属；社区作者的开发说明统一放在 `Mod.md`。

<a id="phase-1-1-tree"></a>

### 8.2 完整目标目录与逐项职责

```text
WindowsToLinux/                             # [PLANNED] 1.1 目标结构；尚未实施
├─ pom.xml                                 # [构建文件] 聚合新活动模块，不聚合 history-src
├─ README.md                               # [文档] 项目介绍、当前能力、构建运行和文档导航
├─ LICENSE                                 # [许可文件] 项目开源许可证
├─ .mvn/                                   # [目录] 活动构建与检查工具，显式排除历史归档
├─ docs/                                   # [目录] 正式产品、架构、工程、发布与 Mod 开发文档
│  ├─ development/                         # [目录] 仅存放开发总纲与各期工程文档
│  │  ├─ DEVELOPMENT.md                    # [文档] 开发总纲；分期导航、开发规则和完成定义
│  │  ├─ PHASE-0.1.md                      # [文档] 历史最小闭环；本地源码、基础部署与桌面入口
│  │  ├─ PHASE-0.2.md                      # [文档] 历史扩展；Git、配置、多部署类型与多 Provider
│  │  ├─ PHASE-0.3.md                      # [文档] 历史扩展；原生生态、多组件和职责分包
│  │  ├─ PHASE-0.4.md                      # [文档] 部署、Agent、备份迁移及维护核心的历史实现与证据
│  │  ├─ PHASE-0.5.md                      # [文档] 历史回环 Web 服务台的实现、限制和验收
│  │  ├─ PHASE-0.6.md                      # [文档] 已被 1.1 取代的历史上线规划；不作为开发任务
│  │  └─ PHASE-1.1.md                      # [文档] 改造步骤、迁移对应、归档注销、技术规格和验收
│  ├─ AllFile.md                           # [文档] 只索引实际存在的生产文件，不提前收入计划节点
│  ├─ APP.md                                 # [文档] 后期发布布局、启动配置、打包和发布验收
│  ├─ File.md                             # [文档] 仅规定源码模块、包结构、依赖方向及命名
│  ├─ Mod.md                                 # [文档] 官方与第三方 Mod 开发、分包职责、包格式及调试规范
│  ├─ PRODUCT-MANUAL.md                    # [文档] 使用者操作手册；区分当前可用功能与规划能力
│  └─ Runtime.md                          # [文档] 运行数据、业务约定、数据库与安全机制
├─ history-src/                            # [归档] 完整旧工程冻结副本；保存范围见 1.1 主文档
├─ src/                                    # [目录] 新活动源码，仅组织 app、appapi、mod、web 四分组
│  ├─ app/                                 # [聚合] APP 平台主体，提供插件运行所需的公共基础
│  │  ├─ ai/                               # [模块] 通用模型调用实现；不承载部署专用 Agent
│  │  │  ├─ invocation/                    # [包] 管理调用预算、取消、结果与通用脱敏
│  │  │  ├─ provider/                      # [包] 适配不同 AI Provider 的请求与响应
│  │  │  └─ transport/                     # [包] 处理模型通信、流式传输、超时与连接释放
│  │  ├─ db/                               # [模块] 统一管理 H2、Schema、连接与事务，保存平台配置和插件状态
│  │  ├─ linux/                            # [模块] 唯一 Linux 连接模块，内含正常和救援两个通道
│  │  │  ├─ recovery/                      # [包] 内置浏览器救援、控制台交接和恢复复核，无正常连接前置
│  │  │  ├─ ssh/                           # [包] 正常 SSH 会话、SFTP 传输、主机指纹校验和资源释放
│  │  │  └─ resources/                     # [资源] 内置救援所需资源，随 APP 交付准备
│  │  ├─ main/                             # [模块] APP 启动、运行模式、数据根、主体装配与关闭
│  │  ├─ mod/                              # [模块] Mod 运行基础；包结构已确认，框架适配和完整契约待验证
│  │  │  ├─ development/                   # [包] CLASS 构建描述、输入摘要及工作区发现
│  │  │  ├─ installation/                  # [包] 校验插件包、版本兼容和依赖，维护安装状态
│  │  │  ├─ lifecycle/                     # [包] 组织重启生效的启停/切换、依赖停用和资源释放
│  │  │  └─ registry/                      # [包] 登记插件扩展点及公开服务，供宿主与插件发现
│  │  ├─ secret/                           # [模块] 主密码、加解密、凭据保护及会话/任务密钥寿命
│  │  ├─ service/                          # [模块] 平台用例与 API 宿主适配，协调 UI 和底层能力
│  │  │  ├─ ai/                            # [包] 模型配置、用途选择与通用调用接线
│  │  │  ├─ interaction/                   # [包] 承载审批、用户输入和任务与界面的交接
│  │  │  ├─ mod/                           # [包] 组织插件安装、启停和状态查询用例
│  │  │  ├─ recovery/                      # [包] 接通常驻救援入口与 Linux 救援能力，不依赖业务插件
│  │  │  ├─ security/                      # [包] 管理解锁会话、界面锁定和任务授权
│  │  │  ├─ server/                        # [包] 维护服务器资料并组织连接、断开和事实查询
│  │  │  └─ task/                          # [包] 跨插件调度、同服务器互斥、取消、事件与脱敏日志
│  │  ├─ source/                           # [模块] 通用路径受限读取、不可变快照与摘要，不解释部署业务
│  │  ├─ ui/                               # [模块] 平台界面外壳、基础页面和插件页面挂载
│  │  │  ├─ ai/                            # [包] 展示与编辑模型配置、用途和启用状态
│  │  │  ├─ mod/                           # [包] Mod 已下载/原始包/商店入口、版本管理及业务页打开/固定
│  │  │  ├─ recovery/                      # [包] 常驻 SSH 救援入口、观察展示和用户交互
│  │  │  ├─ server/                        # [包] 服务器列表、配置与连接状态界面
│  │  │  ├─ settings/                      # [包] 平台偏好、安全设置及由用户手动触发的 APP 重启
│  │  │  ├─ shell/                         # [包] 主窗口、导航、主题/语言、锁定遮罩与扩展页面挂载
│  │  │  └─ task/                          # [包] 通用任务状态、进度、日志和取消入口
│  │  └─ windows/                          # [模块] 通用文件/窗口等本机适配及维护基础，不承载救援流程
│  ├─ appapi/                              # [模块] 插件作者引用的最小公开契约，不包含主体实现
│  │  ├─ ai/                               # [公开包] 模型调用的请求、结果、预算和取消契约
│  │  ├─ db/                               # [公开包] Mod Schema 归属、标准 JDBC 连接及版本迁移契约
│  │  ├─ linux/                            # [公开包] 受控远端操作与结果，不暴露 SSHD 客户端类型
│  │  ├─ mod/                              # [公开包] Mod 身份、生命周期和扩展声明契约
│  │  ├─ secret/                           # [公开包] 带归属和用途的凭据引用及授权契约，不暴露主密钥
│  │  ├─ server/                           # [公开包] 服务器引用、连接状态及非秘密事实
│  │  ├─ source/                           # [公开包] 有界读取、不可变快照和来源摘要契约
│  │  ├─ task/                             # [公开包] 任务、取消、审批、事件及错误契约
│  │  └─ ui/                               # [公开包] 页面、操作、设置和任务视图的贡献契约
│  ├─ mod/                                 # [聚合] 与 app 并列的 Mod 源码模块；第三方无需合入本仓库
│  │  ├─ backup/                           # [模块] 一个备份插件，负责备份、恢复和离线迁移
│  │  │  ├─ Mod.java                       # [入口类] 注册备份插件、装配各层并释放插件资源
│  │  │  ├─ db/                            # [包] 备份目录、操作记录及业务偏好的数据访问
│  │  │  ├─ linux/                         # [包] 备份采集、恢复所需的业务远端操作适配
│  │  │  ├─ secret/                        # [包] 可移植归档的密码保护及业务秘密处理，独立于 APP 主密码
│  │  │  ├─ service/                       # [包] 备份业务实现，以下职责分包仍属同一模块
│  │  │  │  ├─ archive/                    # [包] 归档格式、清单读取、完整性和结构检查
│  │  │  │  ├─ backup/                     # [包] 备份用例及结果，通过部署公开 API 协作
│  │  │  │  ├─ collection/                 # [包] 一致性采集、停写协作和采集后的运行恢复
│  │  │  │  ├─ migration/                  # [包] 双服务器迁移编排，组合备份与恢复能力
│  │  │  │  └─ restore/                    # [包] 恢复素材准备、候选恢复和失败补偿编排
│  │  │  ├─ source/                        # [包] 备份与恢复素材选择、来源核验及取材策略
│  │  │  ├─ ui/                            # [包] 备份、恢复和迁移页面及业务交互
│  │  │  ├─ windows/                       # [包] 备份、恢复所需的本机业务协作
│  │  │  └─ resources/                     # [资源] 所属 JAR 的图标、模板等素材，不是 Java 包
│  │  └─ deployment/                       # [模块] 一个部署 Mod 主模块，业务按 Java 包划分
│  │     ├─ Mod.java                       # [入口类] 在根包装配部署能力、注册公开服务及释放资源
│  │     ├─ ai/                            # [包] 部署提示词、模型输入适配、平台 AI 调用及结果格式校验
│  │     ├─ api/                           # [公开包] 向其他 Mod 提供应用快照、生命周期与恢复协作契约
│  │     ├─ db/                            # [包] 部署配置、应用拓扑、版本及完整原发布模板的数据访问
│  │     ├─ linux/                         # [包] 固定 helper、候选、发布、原任务状态观察与健康回执
│  │     ├─ secret/                        # [包] 部署秘密的用途、引用及短期注入策略
│  │     ├─ service/                       # [包] 部署业务编排、公开契约实现及任务执行
│  │     │  ├─ agent/                      # [包] 自主部署循环、任务状态、工具执行与审批，独立于 standard
│  │     │  ├─ core/                       # [包] 标准与自主流程共用的部署业务
│  │     │  │  ├─ config/                  # [包] 部署配置、不可变修订、秘密引用和资源绑定
│  │     │  │  ├─ delivery/                # [包] 受管发布、候选健康验证、版本切换和回滚
│  │     │  │  ├─ execution/               # [包] 受管应用的状态、启停、重启及生命周期校验
│  │     │  │  └─ model/                   # [包] 部署、应用、发布与业务状态的私有模型
│  │     │  │     ├─ analysis/            # [包] 准入、证据及拒绝纯值
│  │     │  │     ├─ assessment/          # [包] 单项目与多组件类型化评估
│  │     │  │     ├─ capability/          # [包] Linux 与生态运行能力纯值
│  │     │  │     ├─ ecosystem/           # [包] 纯生态声明
│  │     │  │     │  └─ db/               # [包] 数据库要求与模式审阅纯值
│  │     │  │     ├─ language/            # [包] 语言及版本事实
│  │     │  │     ├─ message/             # [包] 非秘密本地化消息纯值
│  │     │  │     ├─ project/             # [包] 项目构建与运行事实
│  │     │  │     │  └─ component/        # [包] 独立分析组件纯值
│  │     │  │     ├─ server/              # [包] 目标发行版与 CPU 纯值
│  │     │  │     │  └─ security/        # [包] 防火墙和安全模块纯值
│  │     │  │     └─ toolchain/           # [包] 工具链要求纯值
│  │     │  └─ standard/                   # [包] 标准部署流程，区分分析和执行
│  │     │     ├─ analyze/                 # [包] 静态识别、证据提取、冲突和缺项分析
│  │     │     │  ├─ component/           # [包] 多组件发现、检查与图准入
│  │     │     │  ├─ contract/            # [包] 分析边界与类型检查器契约
│  │     │     │  │  ├─ policy/           # [包] 静态源码安全策略
│  │     │     │  │  └─ spi/              # [包] 类型检查器契约
│  │     │     │  ├─ core/                # [包] 分析协调与语言汇总
│  │     │     │  ├─ ecosystem/           # [包] 十种语言和数据库声明识别
│  │     │     │  │  ├─ c/                # [包] C 与 C++ 语言识别
│  │     │     │  │  │  └─ cmake/         # [包] CMake 固定架构检查
│  │     │     │  │  ├─ db/               # [包] 数据库声明检查
│  │     │     │  │  ├─ dotnet/           # [包] .NET 语言识别
│  │     │     │  │  │  └─ dotnetsdk/     # [包] .NET SDK 固定架构检查
│  │     │     │  │  ├─ go/               # [包] Go 语言识别
│  │     │     │  │  │  └─ gomodule/      # [包] Go Module 固定架构检查
│  │     │     │  │  ├─ java/             # [包] Java 语言识别
│  │     │     │  │  │  ├─ gradle/        # [包] Gradle 构建检查
│  │     │     │  │  │  ├─ jar/           # [包] JAR 交付检查
│  │     │     │  │  │  ├─ jdk/           # [包] 纯 JDK 架构检查
│  │     │     │  │  │  └─ maven/         # [包] Maven 构建检查
│  │     │     │  │  ├─ kotlin/           # [包] Kotlin 语言识别
│  │     │     │  │  │  ├─ gradle/        # [包] Kotlin Gradle 架构检查
│  │     │     │  │  │  └─ kotlinc/       # [包] 原生编译器架构检查
│  │     │     │  │  ├─ node/             # [包] Node.js 语言识别
│  │     │     │  │  │  ├─ npm/           # [包] npm 架构检查
│  │     │     │  │  │  ├─ pnpm/          # [包] pnpm 架构检查
│  │     │     │  │  │  └─ yarn/          # [包] Yarn 架构检查
│  │     │     │  │  ├─ php/              # [包] PHP 语言识别
│  │     │     │  │  │  ├─ composer/      # [包] Composer 架构检查
│  │     │     │  │  │  └─ phpcli/        # [包] PHP CLI 架构检查
│  │     │     │  │  ├─ python/           # [包] Python 语言识别
│  │     │     │  │  │  ├─ pip/           # [包] pip 架构检查
│  │     │     │  │  │  ├─ pipenv/        # [包] Pipenv 架构检查
│  │     │     │  │  │  ├─ poetry/        # [包] Poetry 架构检查
│  │     │     │  │  │  └─ uv/            # [包] uv 架构检查
│  │     │     │  │  ├─ ruby/             # [包] Ruby 语言识别
│  │     │     │  │  │  ├─ bundler/       # [包] Bundler 架构检查
│  │     │     │  │  │  └─ rubycli/       # [包] Ruby CLI 架构检查
│  │     │     │  │  └─ rust/             # [包] Rust 语言识别
│  │     │     │  │     └─ cargo/         # [包] Cargo 架构检查
│  │     │     │  ├─ extension/           # [包] 默认检查器装配
│  │     │     │  │  └─ registry/         # [包] 14种部署类型及预览登记
│  │     │     │  ├─ preview/             # [包] 只识别预览
│  │     │     │  ├─ service/             # [包] 通用服务事实组装
│  │     │     │  ├─ source/              # [包] 有界遍历和元数据读取
│  │     │     │  ├─ toolchain/           # [包] 工具链声明读取
│  │     │     │  └─ workload/            # [包] 静态站点和容器工作负载检查
│  │     │     └─ deploy/                  # [包] 确定性输入、标准计划、候选构建、单/多组件发布及有界 assistance
│  │     │        ├─ database/           # [包] 冻结源码数据库声明补全与 SQLite 首次初始化审阅准备
│  │     │        ├─ input/              # [包] 运行输入规则、缺项与原任务审阅组装
│  │     │        ├─ plan/               # [包] 审阅与远端发布内容身份
│  │     │        ├─ publication/        # [包] 单/多组件原任务发布、健康、回滚及显式恢复
│  │     │        └─ support/            # [包] 主机、发行版与运行时准入政策
│  │     │           ├─ distro/         # [包] 发行版精确版本与架构政策
│  │     │           └─ runtime/        # [包] 准备工具后的运行能力政策
│  │     ├─ source/                        # [包] 部署取材、排除规则及平台快照的业务使用
│  │     │  └─ git/                        # [包] Git 版本选择及固定来源准备
│  │     ├─ ui/                            # [包] 部署、受管应用及部署配置页面
│  │     ├─ windows/                       # [包] 本机构建工具及部署素材准备的业务协作
│  │     └─ resources/                     # [资源] 所属 JAR 的提示词、素材与业务 helper
│  └─ web/                                 # [目录] 官网、教程、教学演示、下载和投稿收集；内部结构暂缓
└─ test/                                   # [目录] 新活动工程的验收夹具，旧夹具另完整归档
```

文档各自的职责与唯一正文位置见[开发总纲](development/DEVELOPMENT.md#documentation)。本文维护源码结构，AllFile.md 保留实际文件索引；作者实现规则引用 Mod.md，不把拟改造方案写成现行行为。

<a id="phase-1-1-dependencies"></a>

### 8.3 目标依赖与边界说明

目标依赖仅作设计说明，不由现有第 5 节解析器读取。表中的平台 API 指独立 `src/appapi` 契约模块；`deployment/` 以下的路径表示部署主模块内的 Java 包：

`src/app/mod` 是 APP 内部负责 Mod 安装、加载、生命周期和服务登记的叶子模块，Java 根包为 `gold.debug.windowstolinux.app.mod`；`src/mod` 是与 APP 并列、收纳官方 Mod 源码的聚合模块，各 Mod 使用自己的标准根包。两者按完整路径和职责区分，平台运行模块本身不作为可安装的业务 Mod。

| 使用方                                | 可依赖的边界                                                                                                                           | 禁止反向渗透                                                                             |
| ------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------- |
| APP 的平台实现                        | API、职责明确的 APP 内部模块；`main`/`service` 负责装配                                                                                | 不引用部署/备份私有类型；`linux` 不直接构造平台 UI 或插件引擎                            |
| API                                   | 最小必要的公共类型                                                                                                                     | 不依赖 APP、Web、插件实现、SSHD 或数据库实现                                             |
| `deployment/api`                      | 平台 API 的必要公共契约                                                                                                                | 不依赖或暴露部署私有实现、数据库实体及备份类型                                           |
| `deployment/ai`                       | 平台公开 AI 契约、本包的请求与结果类型                                                                                                 | 不直接依赖 APP 的 AI 实现、Provider 客户端、`db` 或业务流程                              |
| `deployment/db`                       | 平台公开 JDBC/凭据契约、本包存储映射及 service/core 下 config/model 纯数据值                                                           | 不依赖 core 业务编排、UI 或 AI，不访问其他 Mod 私有表                                    |
| `deployment/linux`                    | 平台 Linux/服务器/任务审核契约、service/core/model 所需纯值与固定 helper、构建及发布回执协议适配                                       | 不接管 SSHD、连接、指纹或独立救援；不调用 UI 或两个部署引擎                              |
| `deployment/secret`                   | 平台秘密/任务契约、本 Mod db 与 service/core/model 纯值、一次性秘密修订创建及原任务恢复                                                | 不持有 APP 主密钥，不持久化或长期留存明文，不绕过凭据归属/任务授权，不调用 UI 或部署引擎 |
| `deployment/source`                   | 平台 Source/任务契约、业务取材规则及 service/core/model 纯值                                                                           | 不接管平台快照寿命，不调用 UI 或部署引擎；Git 固定取材属于本包                           |
| `deployment/windows`                  | 平台公开 Source/进程/交互等契约及本机业务适配                                                                                          | 不依赖 app/windows 私有类，不接管平台更新、卸载和窗口隐私                                |
| `deployment/service/core`             | 平台 API、部署公开契约及必要业务能力                                                                                                   | 不反向依赖 standard 或 agent；具体存储/远端实现经窄端口接入                              |
| `deployment/service/standard/analyze` | 平台任务 API、原冻结素材句柄及 `service/core/model` 纯值                                                                               | 保持确定性静态分析，不依赖 agent 或 UI；不接管原快照寿命                                 |
| `deployment/service/standard/deploy`  | 标准分析、共用 core、部署 API、所需 ai/db/linux/secret/source/windows 及平台 API；已审阅计划、候选构建、单/多组件发布与有界 assistance | 不依赖 agent 或 UI，普通标准流程保持无 AI 路径                                           |
| `deployment/service/agent`            | 共用 core、部署 API、所需 ai/db/linux/secret/source/windows 及平台 API                                                                 | 不依赖 standard 或 UI；多轮业务决策和工具执行留在本包                                    |
| `deployment/ui`                       | 本 Mod 的 service 及平台 UI 契约                                                                                                       | 不直接访问 db 或调用 ai 执行业务                                                         |
| `deployment/Mod.java`                 | 本 Mod 的各层及平台注册契约                                                                                                            | 负责装配与生命周期，不把两个引擎互相耦合或承载业务实现                                   |
| `backup`                              | 平台 API、部署公开 API                                                                                                                 | 不依赖 deployment 的任何未导出实现包或私有表                                             |
| Web                                   | 后续官网设计自身需要的内容与服务                                                                                                       | 不接入 APP/插件的用户服务器执行链                                                        |

备份与部署均采用一个 Mod 主模块，`Mod.java` 位于各自 Java 根包；部署的 core、standard、agent 是 `service` 下的职责包，不再设置独立 runtime 入口模块。包间依赖仍须由架构检查约束，不能因合并构建而取消两个引擎的边界。`deployment/api` 首先是公开契约包，不自动生成独立 JAR；公开类型共享和依赖声明见 [Mod.md](Mod.md#loader-adaptation)。以后只有确需独立构建或隔离依赖时才提取同作者从模块。第三方开发者无需把源码合入本仓库，官网投稿与 APP 执行能力分开建设。

默认静态页面的 Git 表单只解析明确的 URL、引用和本机工具路径；原任务持久检查点、实际取材、项目来源保存及发布编排仍在 deployment 的 `source/git` 与 `service` 职责中。页面不启动 Git 子进程，也不凭工作目录重新取得 Source 授权。

默认页面的秘密输入组件只在独立 `deployment-secret-revision` 原任务准入后显示密码框；`ui` 只持有业务标识、修订和已存用途的纯值，不直接读取 `db` 或平台不透明凭据。只读修订列表经 `service` 查询，创建由 `secret` 流程保护并清零明文；发布审阅仅选择已保存的精确修订，原 `deployment-publication` 任务不另开同服务器的创建任务。

默认页面的数据库表单只收集冻结源码声明的补充值及独立的 SQLite 首次初始化确认；路径、SQL 总量、组件源码摘要和最终受管身份由 `service/standard/deploy/database` 在原后台任务内校验。`ui` 不直接访问数据库持久层，也不从普通运行绑定推断结构变更已获批准。

Linux 的 `ssh` 与 `recovery` 是**同一个模块里的两个包**，没有各自的 POM，也不是插件。救援依赖随 APP 预备，UI 和服务层留有固定入口；通用 Windows 窗口处理可以由 `app/windows` 提供，但具体救援流程统一归 `app/linux/recovery`。

`gold.debug.windowstolinux` 仍为项目包前缀。后续活动代码按所属 `app`、`appapi`、`mod` 职责命名；公开接口以平台 API 契约为边界。冻结归档中的旧 `shared.*` 等包声明不改名。API 不另建第二套平台实现，不为尚无调用方的扩展点或通用 Agent 创建空模块。

当前与目标结构分别描述实际状态，不重复登记同一实现；AllFile 只收录实际存在的生产文件，归档不属于活动源码。

<a id="phase-1-1-module-graph"></a>

#### 目标 Maven 模块图与 API 包依赖

根仅聚合 `src/appapi`、`src/app`、`src/mod`；app 聚合其十个叶子模块，mod 聚合 deployment 与 backup。Web 实施后置，不挂空模块。下表列出平台模块可声明的项目依赖上界，实际 Maven 仅保留真实使用边，不为凑齐上界添加依赖；第三方版本及发行物清单见 [APP 构建契约](APP.md#build-contract)。

| 模块           | 允许的直接项目依赖                                                                  |
| -------------- | ----------------------------------------------------------------------------------- |
| appapi         | 无，仅 JDK 的 java.base、java.desktop、java.sql 等所需标准类型。                    |
| app/db         | appapi；引导凭据由装配方注入，不反向调用 windows 或 secret。                        |
| app/windows    | appapi。                                                                            |
| app/secret     | appapi、app/db。                                                                    |
| app/ai         | appapi、app/db、app/secret。                                                        |
| app/source     | appapi、app/windows。                                                               |
| app/linux      | appapi、app/ai、app/secret、app/windows；ai 用于平台救援能力。                      |
| app/mod        | appapi、app/db；任务准入和 ModContext 工厂由装配注入，不能反向依赖 service/ui。     |
| app/service    | appapi、app/ai、app/db、app/linux、app/mod、app/secret、app/source、app/windows。   |
| app/ui         | appapi、app/service；平台能力通过用例适配，不能引用业务 Mod 实现。                  |
| app/main       | appapi 及以上 APP 叶子模块，仅负责构建装配与启动关闭。                              |
| mod/deployment | appapi（provided）；不依赖任何 APP 实现。                                           |
| mod/backup     | appapi、mod/deployment（均为 provided，后者仅导出 API 包）；运行由 Mod 加载器解析。 |

API 包保持无环：task 只依赖 JDK，审批目标使用精确 String serverId，与 TaskRequest 的服务器身份一致；server、ui、db、ai、source 依赖 task；secret 依赖 task/server；linux 依赖 task/server；mod 汇总各能力。公开能力不能反向引用 ModContext 或具体 Mod；平台内部可以使用装配端口衔接。db 中的标准 JDBC 类型是 JDK 类型，不是 H2 实现依赖；UI 中 Swing 类型同理。

官方 deployment 当前采用独立 `wtlmod-author-parent`，生产依赖为 `appapi`（provided）和私有 Jackson 2.18.1（沿用项目已有版本），后者用于执行记录的严格版本化 JSON；CLASS 描述列出 databind/core/annotations 三个 Maven 库。`app/main` 仅作为 test 依赖用于真实平台 CLASS 生命周期回归，不进入 Mod 私有运行库。`db/DeploymentSchema` 以 Schema 3 初始化及只读核验，固定 SQL 资源位于同名资源包；Schema 1、2资源冻结，显式 `DeploymentSchemaMigration12`、`DeploymentSchemaMigration23` 共用固定迁移复制器，经受限来源读者与独立新目标写者迁移，不改原源结构或猜测旧秘密用途；结构3新增空管理操作账本，2→3路径尚待本机验证；业务启动绑定当前数据身份、deployment 日志类别及固定 deployment-publication、deployment-git-source、deployment-secret-revision 原任务恢复器。`service/core/config` 放确定性配置条目、修订摘要及文本解析；`service/core/model` 放运行定义、健康契约、资源位置/绑定、精确秘密修订引用及共用输入失败。config 可引用 model，model 不反向引用 config；db 仓库和版本化编解码器可使用这些纯值，纯值不反向依赖 db。持久单元模型只保存精确平台 ServerRef，应用图仓库仅读取本 Mod 表，以同一事务替换已匹配单元的拓扑；配置/信任观测须经公开能力读取，不以 APP 私有表 JOIN 还原。仓库经原任务打开 ModDatabase，使用 H2 事务及条件更新维护不可变配置/秘密修订和精确发布绑定；发布仓库在同一事务内保存全部单元和拓扑，DeploymentUnitCommit 仅为 db 写入输入，不使 model 反向依赖 config。秘密元数据只存不透明凭据 ID、原精确用途及修订时间，不解析或读取平台存储；新修订用途不可变，旧行缺用途可只读但不得据标识猜用途或进入发布。 `DeploymentExecutionRepository` 在任何引用预留前保存精确原任务、审阅摘要及资源/用途/业务键，逐项记录原预留 UUID；开始副作用、业务提交、实际清理分别持久化。正式成功结果与整应用发布、完整历史登记及旧历史退役同一事务；DeploymentReleaseSnapshot/Record/Identity 保留原运行图及配置/秘密引用，ReleaseCodec 维护规范版本和内容摘要，ReleaseRepository 管理不可变历史与复用，ReleaseValidation 仅 SELECT 核验跨记录一致性，共用 DeploymentJsonDocument 严格 JSON 原语。失败或未知结果不伪装成功；不支持的 JSON 版本仅保留只读证据，不允许变更。提交或回滚无法确认时返回公开 UNKNOWN，不能自动覆盖或重试原业务。

单组件发布的原执行记录以格式 5 嵌套保存完整非秘密发布模板；整图执行新增格式 6 和整图模板格式 2，保留原格式 5／模板格式 1 的规范字节与准入。整图执行在首次远端效果前登记一个根检查点和每个唯一组件的短检查点，在停止旧运行或发布前保存一次完整非秘密整图模板；真实执行准入最多 16 组件，分析与持久应用图的 64 组件模型上限不变。整图先预检所有来源和总容量、构建全部候选并封存配置，再保存全部旧快照、逆序停止、拓扑发布与逐组件健康、整图复查及独立整应用健康；只在全部已知成功时一次提交正式业务。已知失败按原快照逆序恢复，远端未知不盲目重发。标准发布恢复经 `service/core` 的窄端口由 Mod 装配，宿主在调用原作者核对前释放原任务旧 SSH 连接；启动只登记恢复器，不自动访问远端。显式原任务恢复须经原任务身份、精确审批和协议 11 固定只读观察核对原服务器、应用、候选、当前版本、旧快照、维护及运行设施；整图还须逐项核实原索引与全图结论，混合或缺失证据保持 UNKNOWN，不重发构建、发布或回滚。业务已核实而本机正式提交丢失时，按原完整模板及必要远端事实补原事务；若原远端收尾已持久核实、检查点已释放，只补本机事务，不重复连远端。清理只推进原检查点允许的原资源。`app/main` 的受限 `test-support` 附件只向本 Mod 的测试作用域提供 loopback SSH 夹具，不进入生产依赖。

官方 deployment 的 `source` 当前包含实际本地筛选和冻结流程：经公开 SourceService 的原任务目录句柄逐层发现，业务排除与 source.include 由 Mod 决定，平台持有目录读取、不可变快照及释放。source 仅使用 appapi 和 service/core/model 的纯相对路径值规则，不引用 APP 私有实现；core/model 不反向依赖 source。素材返回原平台句柄和明确文件清单，后续分析/执行共享直到关闭。确定性 ustar/gzip 归档写入平台 SourceWorkspace 并冻结成独立 SourceSnapshot，保留实际归档字节 SHA，临时文件归属/释放仍在 APP source/service。`source/git` 提供受管 worker 固定 Git 提交的本机取材构件及同一筛选/归档复用。`service/core/DeploymentProjectSourceRecorder` 将真实本地选择或 Git 固定取材与 `db/DeploymentProjectRepository` 接通；`service/core/model/DeploymentProjectSource` 只保存来源纯值，db 不依赖取材实现。执行账本冻结已审阅完整项目修订；同一项目后续更新不会改写旧执行。固定 Git 来源原任务现持久化原请求、工作目录/进程身份、物理回执和完整项目保存边界，并在 Mod 启动时登记只读原任务恢复；SSH 凭据/信任、部署页面及引擎调用仍待实现，不能把来源任务视为完整部署入口。

作者工具复用 `app/mod/installation` 的包读取/校验能力，以命令行模式运行，不启动 APP。构建配置和 SDK 父 POM 归 `.mvn/mod/` 维护，不新增业务叶子模块；正式源码任务才创建这些文件，AllFile 不提前登记工具类或示例。完整结构门禁映射见 [工程门禁](development/PHASE-1.1.md#129-工程门禁映射)。

当前 app/db/mod/ModDeletionLedger 已实现普通安装版本删除的持久阶段、停用确认、最后 Schema 引用判断、独立 DDL 清理、引用释放和固定摘要归档。账本自身不删除程序文件，推进前核验登记安装目录实际不存在；CLASS 源码不是安装目录。PlatformModDeletion 现已由安装服务持有，接入单版本程序文件清理、可选原包清理及默认已下载页。整 Mod 删除数据库范围现与单版本共用账本，增加全部版本及孤立 Schema、专属诊断、非共享自有凭据和固定导航的原范围清理；ModNavigationPreferences 是导航入口和删除事务的共同持久化实现。安装服务已复用实际运行时日志实例的按所有者删除作用域，阻止旧回填快照重建文件；ModOwnedCleanup 已接整 Mod 登记程序、专属数据根及原日志类别文件的有界审阅和剩余子集清理，原 DELETE_MOD 保存原文件证据并支持跨启动恢复；默认已下载页已接整 Mod 范围确认与继续原清理，PlatformModIntents 在登记已移除时仍展示原 DELETE_MOD；界面分别刷新原包和导航偏好。

`app/db/mod/ModProgramMaintenance` 复用删除和重装的已停止所有者、任务/资源与依赖准入；`ModReinstallationLedger` 保存同版本修复原范围和有序检查点，只提交原版本程序状态/原包摘要及固定终态，不修改数据和选择。账本不执行物理文件替换；`app/service/mod/PlatformModReinstallation` 已由安装服务持有，复用原包读取、原生目录发布和精确范围清理，实际替换、保留旧副本、明确恢复及独立原包清理均已接通。默认已下载重装/旧程序恢复入口及原包页 REINSTALL 核对路由已接入；PlatformModIntents 提供只读原修订停用确认，UI 不以草稿关闭代替。安装版默认启动已通过 PlatformModPrograms 接入同一生命周期，按登记内容验证实际目录，并支持原关闭状态的重启确认。

当前 `app/mod/installation` 已实现原包读取、嵌套 JAR 索引与新目录暂存，直接复用工程集中管理的 Commons Compress 1.26.1；包内 ZIP 视图借用原文件句柄，不生成临时 JAR 副本。读取结果不代表已安装或可启用。`app/service/mod/PlatformModInstallation` 已串联原 INSTALL 记录、原生目录发布、版本登记、留存设置和明确中断核对，CLASS 装配不使用该安装来源；`app/ui/mod/ModOriginalsPane` 已接 JAR/APP 原包扫描、搜索、确认安装、原包删除、留存设置及原操作核对；`app/mod/loader` 使用 Maven 集中管理的 ASM 9.10.1，在原包、原安装恢复、CLASS 构建/发现和启用前共用入口继承与公开签名静态准入；`app/service/mod/ModInstallationCleanup` 为未登记安装的明确撤销捕获和复核原文件范围；原 INSTALL 保存 CANCELLING 后才删除，UI 接原清理核对，完整行为见 [Mod 包校验](Mod.md#package-installation)。

<a id="mod-source-layout"></a>

### 8.4 已确认的 Mod 源码结构与命名

本节适用于 1.1 官方与第三方 Mod 的主 JAR 源码。结构规则已确认；官方 deployment 已迁入固定取材、秘密修订、完整静态分析、确定性审阅输入与计划、已审阅单/多组件执行构件，并在 NORMAL 模式登记默认静态页面。页面已接同一原任务的本地与固定 Git 来源、SQLite 首次初始化确认，以及服务器数据库全图预检、库存/精确版本、安装或替换与人工恢复、目标/SQL/保护修订和单/多组件发布。本机 CLASS/H2 受控 helper 与实际 owner 模态已验证本轮接线，入口 UNKNOWN 留存与独立正式跨 JVM 原回执恢复分别验证；真实 Linux 操作、完整 S5 源码迁移及全部本机门禁仍待验收。这不改变第 1～7 节当前代码的结构门禁。九类职责包继续按需出现，不为补齐结构建空包。分发包目录和清单另见 [Mod 包格式](Mod.md#contents-draft)，本文仅规定源码组织。

#### 主模块结构

以下模板以备份、恢复、迁移等业务说明九类标准职责包的位置；九类包均按实际需要选用，不要求每个 Mod 全部具备，不为补齐结构创建空包。目标目录树同样表示可采用的职责位置，不作为九包齐全的验收清单；架构检查约束实际存在包的命名、职责和依赖。`com.example.windowstolinux.mod.backup` 仅为符合标准格式的第三方根包示例，实际作者使用自己的域名倒序前缀，具体业务子包按需建立。

```text
mod-project/                              # [模块] Mod 主 JAR 的 Maven 工程，项目目录名仅作示例
├─ pom.xml                                # [构建文件] 声明主模块及编译所需依赖
└─ src/                                   # [目录] 源码、资源和测试的物理分组
   ├─ main/                               # [目录] 生产代码与资源
   │  ├─ java/                            # [源码根] Java 包路径从这里展开
   │  │  └─ com/example/windowstolinux/mod/backup/ # [根包] 域名倒序＋固定段＋Mod 名
   │  │     ├─ Mod.java                   # [入口类] 初始化、装配、注册与资源释放
   │  │     ├─ ai/                        # [包，可选] 本 Mod 的提示词组织、平台 AI 调用及结果适配
   │  │     ├─ api/                       # [公开包，可选] 其他 Mod 可依赖的接口及跨边界数据类型
   │  │     ├─ db/                        # [包] 插件数据访问、存储映射与持久化
   │  │     ├─ linux/                     # [包，可选] 本业务的远端动作、脚本及结果适配
   │  │     ├─ secret/                    # [包，可选] 本业务的秘密用途、引用及可移植保护格式
   │  │     ├─ service/                   # [包] 业务实现，按职责继续细分
   │  │     │  ├─ backup/                 # [包] 备份规则、流程及业务结果
   │  │     │  ├─ migration/              # [包] 迁移流程，按需组合备份和恢复能力
   │  │     │  └─ restore/                # [包] 恢复规则、流程及业务结果
   │  │     ├─ source/                    # [包，可选] 来源选择、业务排除规则及快照使用
   │  │     ├─ ui/                        # [包] 页面、表单输入、状态与进度展示
   │  │     └─ windows/                   # [包，可选] 本机工具、素材和业务辅助操作适配
   │  └─ resources/                       # [资源] 随所属 JAR 打包的图标、语言文件、模板等
   └─ test/                               # [目录] 按实际测试需要建立
      ├─ java/                            # [测试根] 测试包镜像对应生产包
      └─ resources/                       # [资源] 仅供测试使用的夹具
```

`Mod.java` 与 `ai`、`api`、`db`、`linux`、`secret`、`service`、`source`、`ui`、`windows` 位于同一个 Mod 根包下；这九类职责包不各自建立 POM。入口位于 Java 根包，实际物理路径仍遵循 Maven 结构，例如 `src/mod/deployment/src/main/java/gold/debug/windowstolinux/mod/deployment/Mod.java`。没有对应能力需求时省略该包；名称与 APP 对应便于定位职责，APP 实现通用能力，Mod 实现业务规则和适配，不复制平台实现。

#### 入口与职责边界

入口、各职责包的实现责任及业务调用规则统一见 [Mod.md：入口与职责包](Mod.md#51-入口与职责包)、[公开 API](Mod.md#52-区分平台-api-与本-mod-的-api)和[AI 分工](Mod.md#53-使用-ai-时如何分工)。本文只维护源码结构、命名及[模块依赖方向](#phase-1-1-dependencies)。

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

1. 入口类名固定为 `Mod`，源文件名严格为 `Mod.java`，不是 `Mod.Java`。九类职责包及其子包均使用小写英文名称；其他类按明确职责命名，文件与顶级类同名。
2. 各 Mod 使用独有根包，并遵守本节标准格式。官方 Mod 使用 `gold.debug.windowstolinux.mod.<modname>`；第三方使用自己的域名倒序前缀，并保留 `windowstolinux.mod` 固定段。
3. 不同 Mod 可以分别声明 `Mod`，以完整限定名区分，例如 `com.example.windowstolinux.mod.backup.Mod` 与 `com.example.windowstolinux.mod.deployment.Mod`。主模块只提供一个指定入口，不依赖简单类名的全局唯一性定位插件。
4. 同作者从模块用于复用或隔离实际职责，不要求每个从模块再建立入口和全部职责包。只有插件主模块承担入口装配；从模块不能反向依赖主模块。普通职责分包不自动升级为 Maven 模块。
5. 官方备份和部署均遵循主模块根包入口规范；部署的 `service.core`、`service.standard`、`service.agent` 保留各自职责，两个引擎互不依赖。新增从模块或独立 API 制品仍需根据实际调用和依赖需要评审，不能按包名自动拆出多个模块。

<a id="后续门禁衔接"></a>

#### 当前与目标门禁的适用范围

当前门禁适用于第 1～7 节的 0.x 结构。1.1 的已登记 Mod 入口允许简单类名同为 `Mod`，其他类型继续遵守既有命名约束；具体测试调整与验证见 [1.1 开发文档](development/PHASE-1.1.md#structure-gates)。

### S5-03/S5-04 当前模式源码

部署 Mod 的严格模型协议与纯提案值归 `ai`，自主决策、任务与恢复编排归 `service/agent`，辅助模式的只读建议与修订门禁归 `service/standard/deploy/assistance`。公共整图发布与原任务正式提交归 `service/core/delivery`，只接收已审阅不可变描述；两个引擎不反向调用彼此。源码阅读器只读原快照并拒绝内容漂移，发送门禁在实际 SSH 边界复核原审批。默认页面新增惰性自主部署页，仍按实际 Mod 所有者登记。活动根已退出旧 shared/web；完整全局门禁与本机验收仍属 S3 待收尾项。

### S3 平台库存值接线

`app/ai/catalog` 保存实际平台配置与模型调用使用的三个纯值。数据库和 UI 均显式依赖 `app-ai`，平台服务继续通过既有 AI 能力适配；不反向引入 DB/UI 依赖，也不向 `appapi` 混入内部 DEPLOYMENT 枚举。旧 AiModelGroupType 无活动生产调用，移除其测试未用 import，未在新包建立占位类型。旧 shared/web 已退出活动图；最终测试迁移与全局门禁仍待收尾。
