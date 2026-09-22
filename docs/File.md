# WindowsToLinux 项目文件结构

## 文档信息

- 文档版本：`4.0.0-standard-agent`
- 文档状态：**31-POM 结构迁移与自主 Agent 本地验证完成；四期产品用例已接通并有代表性部署证据，五期已接通回环内部测试 Web 功能，六期大致承接官网、上线认证、发布下载和生产维护；真实 Linux/数据库备份恢复迁移证据仍标记 `RUNTIME-PENDING`；历史 helper v5 全语言部署见 [实机记录](development/PHASE-4.md#acceptance-baseline)**
- 已确认范围：`shared` 共用模块、`app` Windows 桌面应用模块、`web` Web 应用模块
- 已确认能力边界：受管应用生命周期复用既有模块，不新增独立 Maven 模块
- 更新日期：2026-09-22
- 开发总纲：[DEVELOPMENT.md](development/DEVELOPMENT.md)
- 功能分工：[分析与 Linux 部署链](development/PHASE-3.md#architecture)

> 本文统一维护正式目标目录、模块职责、依赖方向、包结构和命名规则。现行结构说明以当前代码核对，文档冲突时修正文档，不为了符合旧描述倒改实现；明确的未来节点仍保留规划标记。后续结构迁移须同步代码、本文及门禁。运行能力以当前实现和适用的实际行为为准，不得由目标目录或旧验收推定支持。

## 1. 完整目标结构

当前已确认的完整目标结构如下。叶子模块下直接展示的是对应 Java 根包的目标子包，省略重复的 Maven `src/main/java` 路径；物理目录模板见第 4.1 节。`pom.xml` 作为聚合入口固定置顶，其余模块和内部包按英文名称排序。

```text
WindowsToLinux/
├─ pom.xml
├─ docs/
│  ├─ development/
│  │  ├─ DEVELOPMENT.md                         开发总纲与文档导航
│  │  ├─ PHASE-1.md                             一期主文档
│  │  ├─ PHASE-2.md                             二期主文档
│  │  ├─ PHASE-3.md                             三期主文档
│  │  ├─ PHASE-4.md                             四期主文档
│  │  ├─ PHASE-5.md                             五期主文档
│  │  └─ PHASE-6.md                             六期主文档
│  ├─ AllFile.md                               全文件架构索引
│  ├─ File.md                                  项目文件结构
│  └─ PRODUCT-MANUAL.md                        产品说明书
└─ src/
   ├─ app/
   │  ├─ pom.xml
   │  ├─ db/
   │  │  ├─ entity/
   │  │  ├─ execution/
   │  │  │  └─ migration/
   │  │  ├─ failure/
   │  │  └─ persistence/
   │  │     ├─ connection/
   │  │     ├─ repository/
   │  │     └─ serialization/
   │  ├─ main/
   │  │  ├─ diagnostic/
   │  │  ├─ runtime/
   │  │  └─ startup/
   │  ├─ secret/
   │  │  └─ crypto/
   │  ├─ service/
   │  │  ├─ ai/
   │  │  ├─ backup/
   │  │  ├─ config/
   │  │  ├─ contract/
   │  │  │  └─ definition/
   │  │  ├─ deployment/
   │  │  │  ├─ automatic/
   │  │  │  ├─ multi/
   │  │  │  └─ single/
   │  │  ├─ execution/
   │  │  │  ├─ environment/
   │  │  │  └─ lifecycle/
   │  │  ├─ failure/
   │  │  ├─ lock/
   │  │  ├─ recovery/
   │  │  ├─ server/
   │  │  └─ source/
   │  ├─ ui/
   │  │  ├─ ai/
   │  │  ├─ backup/
   │  │  ├─ component/
   │  │  ├─ deployment/
   │  │  │  ├─ automatic/
   │  │  │  └─ multi/
   │  │  ├─ diagnostic/
   │  │  ├─ display/
   │  │  ├─ i18n/
   │  │  ├─ managed/
   │  │  ├─ recovery/
   │  │  ├─ server/
   │  │  ├─ setting/
   │  │  └─ shell/
   │  └─ windows/
   │     ├─ recovery/
   │     ├─ uninstall/
   │     ├─ update/
   │     └─ workspace/
   ├─ shared/
   │  ├─ pom.xml
   │  ├─ agent/
   │  │  ├─ approval/
   │  │  ├─ execution/
   │  │  │  └─ protocol/
   │  │  └─ tool/
   │  ├─ ai/
   │  │  ├─ client/
   │  │  ├─ collaboration/
   │  │  │  ├─ advice/
   │  │  │  ├─ invocation/
   │  │  │  └─ role/
   │  │  ├─ execution/
   │  │  │  └─ protocol/
   │  │  ├─ generation/
   │  │  │  └─ prompt/
   │  │  ├─ parser/
   │  │  ├─ provider/
   │  │  ├─ recovery/
   │  │  ├─ redaction/
   │  │  └─ transport/
   │  ├─ backup/
   │  │  ├─ contract/
   │  │  │  ├─ definition/
   │  │  │  ├─ spi/
   │  │  │  └─ validation/
   │  │  ├─ crypto/
   │  │  ├─ execution/
   │  │  │  ├─ collection/
   │  │  │  └─ migration/
   │  │  ├─ extension/
   │  │  │  ├─ adapter/
   │  │  │  └─ registry/
   │  │  ├─ format/
   │  │  ├─ manifest/
   │  │  └─ restore/
   │  ├─ config/
   │  │  ├─ contract/
   │  │  │  └─ definition/
   │  │  ├─ input/
   │  │  ├─ persistence/
   │  │  │  └─ serialization/
   │  │  ├─ resource/
   │  │  ├─ revision/
   │  │  └─ secretref/
   │  ├─ deploy/
   │  │  ├─ approval/
   │  │  ├─ contract/
   │  │  │  ├─ result/
   │  │  │  │  ├─ deployment/
   │  │  │  │  └─ lifecycle/
   │  │  │  └─ spi/
   │  │  ├─ delivery/
   │  │  ├─ error/
   │  │  ├─ execution/
   │  │  │  ├─ environment/
   │  │  │  ├─ lifecycle/
   │  │  │  └─ transaction/
   │  │  ├─ publication/
   │  │  └─ task/
   │  ├─ git/
   │  │  └─ snapshot/
   │  ├─ linux-sshd/
   │  │  ├─ backup/
   │  │  │  ├─ execution/
   │  │  │  │  └─ protocol/
   │  │  │  └─ generation/
   │  │  │     └─ script/
   │  │  ├─ capability/
   │  │  │  └─ ecosystem/
   │  │  ├─ command/
   │  │  ├─ connection/
   │  │  ├─ distro/
   │  │  │  └─ dnf/
   │  │  ├─ execution/
   │  │  │  ├─ protocol/
   │  │  │  │  ├─ database/
   │  │  │  │  ├─ helper/
   │  │  │  │  ├─ input/
   │  │  │  │  ├─ release/
   │  │  │  │  ├─ restore/
   │  │  │  │  └─ runtime/
   │  │  │  └─ transfer/
   │  │  ├─ runtime/
   │  │  │  └─ systemd/
   │  │  ├─ session/
   │  │  └─ workspace/
   │  ├─ linux/
   │  │  ├─ build/
   │  │  ├─ capability/
   │  │  ├─ command/
   │  │  ├─ connection/
   │  │  ├─ distro/
   │  │  ├─ ecosystem/
   │  │  │  └─ db/
   │  │  ├─ error/
   │  │  ├─ protocol/
   │  │  │  ├─ backup/
   │  │  │  ├─ database/
   │  │  │  └─ restore/
   │  │  ├─ runtime/
   │  │  ├─ session/
   │  │  ├─ transfer/
   │  │  └─ workspace/
   │  ├─ model/
   │  │  ├─ agent/
   │  │  ├─ ai/
   │  │  ├─ analysis/
   │  │  ├─ archive/
   │  │  ├─ assessment/
   │  │  ├─ capability/
   │  │  ├─ deployment/
   │  │  ├─ ecosystem/
   │  │  │  └─ db/
   │  │  │     ├─ other/
   │  │  │     └─ sql/
   │  │  ├─ failure/
   │  │  ├─ health/
   │  │  ├─ language/
   │  │  ├─ lifecycle/
   │  │  ├─ managed/
   │  │  ├─ message/
   │  │  ├─ project/
   │  │  │  ├─ application/
   │  │  │  └─ component/
   │  │  ├─ recovery/
   │  │  ├─ security/
   │  │  ├─ server/
   │  │  │  └─ security/
   │  │  └─ toolchain/
   │  ├─ source/
   │  │  ├─ archive/
   │  │  ├─ browse/
   │  │  ├─ contract/
   │  │  │  └─ validation/
   │  │  ├─ manifest/
   │  │  └─ snapshot/
   │  └─ standard/
   │     ├─ pom.xml
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
   │        │  └─ execution/
   │        │     └─ protocol/
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
   │        │  ├─ result/
   │        │  │  └─ compatibility/
   │        │  └─ spi/
   │        ├─ distro/
   │        │  ├─ apt/
   │        │  ├─ contract/
   │        │  │  └─ profile/
   │        │  ├─ dnf/
   │        │  ├─ extension/
   │        │  │  └─ registry/
   │        │  └─ generation/
   │        │     └─ script/
   │        ├─ execution/
   │        │  ├─ environment/
   │        │  └─ transaction/
   │        ├─ extension/
   │        │  ├─ adapter/
   │        │  └─ registry/
   │        ├─ input/
   │        ├─ plan/
   │        ├─ support/
   │        │  ├─ distro/
   │        │  └─ runtime/
   │        └─ toolchain/
   └─ web/
      ├─ pom.xml
      ├─ api/
      │  ├─ account/
      │  ├─ audit/
      │  ├─ config/
      │  ├─ controller/
      │  ├─ error/
      │  ├─ filter/
      │  ├─ release/
      │  └─ workspace/
      ├─ auth/
      │  ├─ authorization/
      │  ├─ csrf/
      │  ├─ identity/
      │  ├─ initialization/
      │  ├─ login/
      │  ├─ ratelimit/
      │  └─ session/
      ├─ db/
      │  ├─ config/
      │  ├─ entity/
      │  ├─ execution/
      │  │  └─ migration/
      │  ├─ persistence/
      │  │  ├─ mapper/
      │  │  └─ repository/
      │  └─ runtime/
      ├─ file/
      │  ├─ quota/
      │  ├─ upload/
      │  └─ workspace/
      ├─ main/
      │  ├─ config/
      │  ├─ runtime/
      │  └─ startup/
      ├─ secret/
      │  ├─ credential/
      │  ├─ crypto/
      │  ├─ masterkey/
      │  └─ password/
      ├─ service/
      │  ├─ account/
      │  ├─ ai/
      │  ├─ audit/
      │  ├─ backup/
      │  ├─ config/
      │  ├─ contract/
      │  │  └─ validation/
      │  ├─ deployment/
      │  ├─ execution/
      │  │  └─ lifecycle/
      │  ├─ interaction/
      │  ├─ persistence/
      │  │  └─ serialization/
      │  ├─ release/
      │  ├─ server/
      │  ├─ source/
      │  └─ workspace/
      └─ task/
         ├─ model/
         └─ scheduler/
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

- 项目根目录的 [pom.xml](../pom.xml) 作为 Maven 父工程和总聚合入口。
- `src/app/pom.xml` 作为桌面应用模块聚合入口，使用 Maven `pom` 打包类型，不放业务源码。
- `db`、`main`、`secret`、`service`、`ui` 和 `windows` 都是独立 Maven 叶子模块。
- `src/shared/pom.xml` 作为共用模块聚合入口，使用 Maven `pom` 打包类型，不放业务源码。
- `ai`、`analyze`、`backup`、`config`、`deploy`、`git`、`linux`、`linux-sshd`、`model` 和 `source` 都是正式目标 Maven 叶子模块。
- `shared/config` 的 artifactId 为 `windowstolinux-shared-config`，Java 根包为 `gold.debug.windowstolinux.shared.config`；`shared/linux-sshd` 的 artifactId 为 `windowstolinux-shared-linux-sshd`，Java 根包为 `gold.debug.windowstolinux.shared.linux.sshd`；`shared/source` 的 artifactId 为 `windowstolinux-shared-source`，Java 根包为 `gold.debug.windowstolinux.shared.source`。
- `src/web/pom.xml` 作为 Web 后端模块聚合入口，使用 Maven `pom` 打包类型，不放业务源码。
- `api`、`auth`、`db`、`file`、`main`、`secret`、`service` 和 `task` 都是独立 Maven 叶子模块。
- `frontend` 是由 `package.json` 管理的 Vue 3、TypeScript 和 Vite 工程，不套用 Java Maven 叶子模块目录；`web/main` 的 Maven 构建安装本地 Node、执行 npm ci、类型检查和 Vite 构建，将产物打入 Spring Boot 静态资源。运行时只启动 Java，分发为 `web.jar` 与外置 `lib`；正式发布仍属六期。`web/frontend/src/shared` 是前端复用目录，不是 `src/shared` Maven 聚合模块。
- Maven 坐标统一使用 `gold.debug.windowstolinux`，根父工程为 `windowstolinux-parent:0.1.0-SNAPSHOT`，编译目标为 Java 21。
- WindowsToLinux 自身构建使用开发机的系统 Maven 和系统本地仓库；项目 POM 不声明仓库位置，不创建项目专用 Maven 仓库，也不新增 Maven Wrapper 作为本项目构建入口。
- 构建插件确需额外构建期依赖时，在根 POM 对应插件的 `<dependencies>` 中显式声明，供系统 Maven 同步；不得为了补插件缓存而把依赖加入业务叶子模块的运行时 classpath。
- 第 1 节是正式目标结构。结构迁移必须原子更新包声明、物理路径、导入、测试和门禁；旧包前缀与薄包装类不保留兼容壳。
- `shared/backup` 已实现平台无关归档、数据库适配、候选恢复和离线迁移核心；桌面 `app/service.backup` 已组合完整远端备份、受管目标恢复和双服务器离线迁移，`app/ui.backup` 已提供对应产品入口。`shared/backup.crypto` 使用独立调用级备份密码实现 Argon2id 64 MiB/3 次/1 路派生、AES-256-GCM 认证加密及严格秘密修订载荷，服务对象不持有密码或派生密钥。Windows 更新/卸载安全核心已存在，生产独立执行器属于六期。Web 已实现五期回环内部测试服务台；`auth`、账号/空间/审计/发布和登录密码包仅有六期包文档预留。数据库先建立多用户表和作用域外键，第五期只启用固定内部身份。公共官网和正式多用户使用属于六期。

### 1.1 稳定职责边界

- `shared.standard.analyze` 只读取有界源码并生成确定性事实；跨语言协调归 `core`，规则和 SPI 归 `contract`，注册归 `extension`，语言和构建架构实现归 `ecosystem`，数据库声明检查归 `ecosystem.db`，工作负载识别归 `workload`。
- `shared.linux` 只定义平台无关的类型化 Linux 契约；受管备份制品只以固定种类、受管身份、摘要、长度和流式传输契约表达，Apache SSHD、Shell 渲染、目标机目录选择及 PAX/OCI 命令实现只位于 `shared.linux-sshd`。
- `shared.standard.deploy.build` 保留构建执行入口；SPI、注册表和安全脚本分别归 `build.contract`、`build.extension`、`build.generation`，生态构建归 `build.ecosystem`，容器与静态站点构建归 `build.workload`。
- `shared.linux-sshd.capability` 保留平台能力采集；语言、构建工具链及其版本解析归 `capability.ecosystem`，APT/DNF 包名不得进入该包。
- `shared.standard.deploy.distro` 负责标准流程的发行版软件包选择和安装策略；APT 与 DNF 分别形成完整扩展单元，不实现语言构建命令。六种发行版各有一个具名准备类，直接位于 `distro.apt` 或 `distro.dnf`；不增加单文件发行版子包。具体类持有版本、包集合和额外准备选择，公共 Renderer 复用包管理器流程，`DistributionSetupCatalog` 仅装配这六种已有实现。
- `shared.deploy` 保存公共任务/审批、发布与生命周期，静态专属请求、Adapter、注册表和支持判断归 `shared.standard.deploy`；不得以共用命名掩盖引擎职责。
- `model` 保存跨模块共享的纯事实和值对象；DB 专属类型与规则统一归 `model.ecosystem.db`，语言枚举、项目事实、部署计划和 UI 模型保持既有职责包。
- helper 的协议基础、输入、发布、运行和生命周期片段保持职责分组；语言或工具链分派片段位于 `execution/protocol/helper/fragments/ecosystem`，原生 DB 片段位于 `execution/protocol/helper/fragments/database`。仅 classpath 路径迁移不得改变组装字节、顺序、协议版本或固定摘要；身份与工作区协议如有行为变化，须同步对应协议、摘要及快照。
- 服务器原生 DB 的模型、分析、远程契约使用各模块的 `ecosystem.db`；SSHD 实现归 `execution.protocol.database`，native helper 归 `execution/protocol/helper/fragments/database/`。UI、本地持久化、秘密、Git、备份及应用用例继续按自身职责分包，不按被部署项目的语言复制结构。
- 运行能力与实机证据不由包结构决定；新增生态或构建架构必须在对应分期文档中单独定义实现、测试和验收范围。

实际生产 Java 包必须出现在目标树；`[PLANNED]` 节点及其子树只声明未来职责。Web 只有包说明的六期节点继承该标记，前端实际文件由 [AllFile.md](AllFile.md) 记录。文档节点与依赖由反例测试检查，不因包或类型存在而宣称运行验收通过。

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
| M-07 | 模块仅因已确认的独立职责调整；名称整理本身不构成拆分理由。 | `shared/deploy` 保持完整模块 | 将 `policy` 拆成独立模块 |
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

桌面持久化使用 `persistence.connection` 与 `persistence.repository`；Web 使用 `persistence.mapper`、`persistence.repository` 和 Spring 受管事务，连接池在 Web 启动配置中建立。平台无关运行时文档编解码位于 `shared.config.persistence.serialization`。六期未实现职责只保留包说明。

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

| 编号 | 异常命名规范                                                                              | 正确示例                          | 禁止示例                                             |
| ---- | ----------------------------------------------------------------------------------------- | --------------------------------- | ---------------------------------------------------- |
| E-01 | 异常使用 `Exception` 后缀。                                                               | `LinuxOperationException`         | `LinuxOperationError`                                |
| E-02 | 名称必须说明失败边界。                                                                    | `SecretStoreException`            | `OperationException`                                 |
| E-03 | 公共异常不得直接以底层实现细节命名。                                                      | `LinuxOperationException`         | `SshChannelException`                                |
| E-04 | 安全异常名称不得包含秘密内容。                                                            | `SecretStoreException`            | `InvalidPasswordValueException`                      |
| E-05 | 受控失败必须以 `FailureCarrier` 暴露结构化描述，本地化消息与安全诊断分离。                | `ApplicationServiceException`     | 中文异常类名、原始 `exception.getMessage()` 用户回退 |
| E-06 | 异常使用 `[FailureBoundary]Exception`，名称说明模块内失败边界。                           | `SourceArchiveException`          | `SourceError`、`OperationException`                  |
| E-07 | 模块错误枚举使用 `[Boundary]FailureType`。                                                | `LinuxOperationFailureType`       | `LinuxError`、`FailureKind`                          |
| E-08 | 只有重复的确定性转换逻辑才建立 `[BoundaryOrTechnology]FailureMapper`。                    | `SqliteFailureMapper`             | 单次使用的通用 `ErrorManager`                        |
| E-09 | 恢复判断使用 `[Operation]FailureRecoveryPolicy` 与 `[Operation]FailureRecoveryDecision`。 | `DeploymentFailureRecoveryPolicy` | `RecoveryProcessor`                                  |
| E-10 | 业务类型不得使用 `Error`、`Manager`、`Processor` 等泛化错误治理名称。                     | `DesktopFailurePresenter`         | `ErrorManager`、`FailureProcessor`                   |

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

### 3.1 `shared` 共用模块

| 模块               | 职责                                                                           |
| ------------------ | ------------------------------------------------------------------------------ |
| `model`            | 服务器、受管运行记录、配置/备份共享的数据契约；无引擎依赖                      |
| `source`           | 目录冻结、快照摘要、受控目录/分页读取/搜索和源码归档                           |
| `git`              | Git 取材与版本化源码准备                                                       |
| `config`           | 版本化配置、秘密引用和资源绑定                                                 |
| `ai`               | Provider、传输、通用结构化协议；不承载部署决策循环                             |
| `linux`            | 命令/结果、传输、远端源码补丁、观察、受管运行端口                              |
| `linux-sshd`       | SSHD 会话、受管 helper、身份/目录隔离及端口实现                                |
| `deploy`           | 实际命令审批、任务控制、候选发布、健康检查、回滚和生命周期                     |
| `backup`           | 既有应用备份恢复和迁移，依赖公共部署层                                         |
| `standard`         | Maven 聚合，无 Java 源码                                                       |
| `standard/analyze` | 独立 Maven 模块，静态识别与标准项目证据                                        |
| `standard/deploy`  | 独立 Maven 模块，计划、适配器、构建命令、工具链、发行版安装策略及 `assistance` |
| `agent`            | 独立 Maven 模块；内部以 Java 包组织自主循环、上下文、工具协议及部署提示词      |

`standard/deploy` 可以依赖 `standard/analyze`。`agent` 不依赖任何 `standard` 模块；两个引擎不得相互依赖。公共模块不得反向依赖两个引擎。标准流程把识别结果转换为公共交付输入，自主 Agent 根据实际读取证据构造通用进程/容器描述，公共层重新验证并执行发布。

### 3.2 `app` 桌面应用模块

| 模块      | 职责                                                                                                                                                                   | 明确不负责                                                                                                                                         |
| --------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------- |
| `ui`      | 实现 Swing/FlatLaf 界面、输入校验、进度展示和用户决定交互。                                                                                                            | 不直接访问 SQLite、SSH、AI、加密算法或 Windows Credential Manager。                                                                                |
| `windows` | 选择和访问 Windows 本地项目、备份及工作目录，采集文件与环境事实，通过 `shared/source` 准备待上传项目包；持有桌面包签名/版本/架构验证、独立更新事务和受管卸载文件边界。 | 不自行定义源码归档格式，不执行远程上传，不解释备份格式，不处理加密、私钥、明文秘密或 Credential Manager 实现；卸载凭据只能经 `secret` 窄端口编排。 |
| `db`      | 管理 SQLite 连接、表结构、版本化迁移和事务，保存非敏感数据、配置实例与历史版本、受管应用标识、最后观测状态及由 `secret` 生成的加密数据。                               | 不定义共用配置规则，不执行加解密，不派生或保存明文密钥，不直接访问 Windows Credential Manager，不把最后观测状态当作远端事实。                      |
| `secret`  | 统一处理主密码、Argon2id 密钥派生、AES-256-GCM 加解密、DEK 包装、敏感信息存取和 Windows Credential Manager 平台适配。                                                  | 不负责普通业务数据、界面、项目打包、部署或远程连接。                                                                                               |
| `service` | 实现桌面端部署和受管应用生命周期等用例、后台任务、同服务器修改互斥、事件、取消和模块协作，整合 `app` 与 `shared` 能力。                                                | 不自行实现界面、SQLite、加密算法、凭据平台接口、SSH/SFTP 或 Linux 生命周期动作。                                                                   |
| `main`    | 提供应用启动入口，识别运行模式、解析应用与数据目录，装配模块和 `linux-sshd` 实现并管理生命周期。                                                                       | 不承载具体业务规则、界面逻辑、持久化、SSH/SFTP 或加密实现。                                                                                        |

桌面端所有加密、解密、密钥派生、密钥包装、敏感信息存取和 Windows Credential Manager 调用都必须位于 `secret`。`windows` 只处理非敏感的 Windows 本地能力；不得为了平台调用方便把任何安全实现放入 `windows`。

桌面调用方向固定为 `UI → app/service/contract → 窄用例 → shared 领域/契约`。`AiApplicationFacade`、`BackupApplicationFacade`、`DeploymentApplicationFacade`、`MultiComponentApplicationFacade`、`ServerApplicationFacade` 和 `ManagedApplicationFacade` 隔离页面所需能力；`DesktopApplicationFacade` 作为稳定门面和组合根实现这些门面，只转发到窄用例，不承载业务算法。

### 3.3 `web` Web 应用模块

| 模块       | 职责                                                                                                                                                            | 明确不负责                                                                                           |
| ---------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------- |
| `frontend` | 实现 Vue 3/TypeScript 页面、用户交互、流式上传交互和 SSE 事件展示。                                                                                             | 不保存原始凭据，不直接访问数据库、文件系统、SSH/SFTP 或共用执行模块。                                |
| `api`      | 通过 Spring MVC Controller 实现版本化 REST API、SSE 及输入输出边界，Filter 执行来源和容量检查，统一提供脱敏错误。                                               | 不直接访问 SQLite、受管文件目录、凭据或部署执行器，不承载业务流程或直接执行生命周期动作。            |
| `auth`     | 六期上线方向中的身份、登录、会话与访问安全边界；具体账号模型和策略在六期实施前确定。                                                                            | 五期内部功能测试不得提前创建伪认证；本模块不自行实现密码哈希、加密算法、主密钥或普通业务编排。       |
| `service`  | 实现 Web 部署、配置访问控制和受管应用生命周期等用例并整合 `db`、`file`、`secret` 与 `shared` 能力。                                                             | 不实现 HTTP/SSE、任务调度、SQLite、文件底层操作、密码学算法、SSHD 或 Linux 生命周期动作。            |
| `task`     | 管理部署和生命周期等持久化任务的状态、调度、同服务器互斥、受控取消、重启恢复和结构化事件。                                                                      | 不传输 SSE，不重复实现分析、部署、生命周期、备份或迁移规则。                                         |
| `db`       | 使用受管数据源、Spring 事务和 MyBatis-Plus Mapper 管理 Web SQLite、版本化迁移及持久化仓储，包括配置实例与历史版本、项目与环境关联、受管应用标识和最后观测状态。 | 不定义共用配置规则，不执行加解密，不管理上传文件、后台调度或接口协议，不把最后观测状态当作远端事实。 |
| `file`     | 管理流式上传下载、配额、临时文件清理和受管工作目录，对上传容器执行配额和路径检查，完成后通过 `shared/source` 生成规范源码归档。                                 | 不定义第二套规范源码归档格式，不负责 SFTP、远程 Linux 文件、备份恢复语义或秘密加解密。               |
| `secret`   | 五期为内部功能测试处理测试主密钥和服务端凭据；六期再按批准方案承接上线认证所需的密码学和主密钥能力。                                                            | 不负责登录会话流程、普通数据库业务、上传文件、部署或远程连接；不得因五期无登录而明文保存秘密。       |
| `main`     | 提供 Spring Boot 无参入口及 YAML 绑定，装配后端模块、`linux-sshd`、数据源与前端静态资源，管理启动检查、健康检查和关闭。                                         | 不承载具体业务、接口、任务、持久化、文件、SSH/SFTP 或密码学实现。                                    |

Web 主密钥和本地凭据加解密由 `web/secret` 独占；可移植备份密码格式由 App/Web 共用的 `shared/backup.crypto` 独占。五期不实现登录或管理员密码；六期若按届时批准方案启用 `auth`，密码哈希与认证密码学仍只能通过 `secret` 使用。其他 Web 模块不得直接接触明文密钥或实现加密算法。

### 3.4 配置规则、存储与秘密边界

1. `shared/config` 提供平台无关的配置类型、文本解析、规则和载荷编码，不读写文件或数据库，也不定义 Repository、数据库表或具体持久化技术；`persistence` 仅表示可复用编码，不承担平台存储。
2. 桌面端配置实例及历史版本由 `app/db` 统一保存；Web 配置实例、项目、环境关联及历史版本由 `web/db` 统一保存，不为每名用户创建独立配置文件。Web 后续支持多用户或租户时，所有权关联同样由 `web/db` 保存。
3. 五期 Web 只能在明确回环内部测试模式访问配置，由 `web/service` 保持业务边界；六期上线权限由届时批准的 `web/auth` 与 `web/service` 共同校验。`shared/config` 不感知具体用户、租户、会话或授权策略。
4. 敏感值由 `app/secret` 或 `web/secret` 处理；普通配置只保存秘密 ID、版本等不透明引用，不保存明文秘密。
5. 桌面启动和固定数据根解析分别由 `app/main/startup` 与 `app/main/runtime` 承担；Web 启动配置位于 `web/main/config`，Spring 装配位于 `web/main/startup`，CLASS/JAR 数据根解析位于 `web/db/runtime`。这些应用启动职责不属于 `shared/config`。

平台 UI 通过现有 `service.contract` 门面提交类型化表单，只使用门面显式暴露的纯数据契约。平台服务负责交互、凭据和存储记录构造；标准运行时及默认值解析归 `shared/standard/deploy/input`，普通配置文本解析和平台无关载荷编码归 `shared/config/input`、`shared/config/persistence`，数据库审阅模式归 `shared/model/deployment`。这些共享规则不读写平台数据库，不向 UI 开放解析器、远程端口或 SSHD 实现。

### 3.5 桌面端本地化与诊断边界

1. 英文是桌面端生产代码、内部校验、程序生成诊断和默认消息资源的基准语言；`app/ui/i18n/messages/Messages.properties` 是消息目录的规范来源，简体中文只保存在 `Messages_zh_CN.properties`。
2. 生产模块不得提前拼接英文或中文界面句子。固定用户文案必须使用稳定消息键和命名参数表达；跨模块失败通过 `FailureCarrier.failure()` 暴露 `FailureDescriptor`，UI 是唯一负责将消息键解析为显示语言的边界。
3. SSH、systemd、HTTP、第三方 Provider 和远端命令返回的原始技术内容不得直接成为用户消息或部署事件。已知低层文本只可用于模块内分类；跨边界后仅保留受控安全诊断，未知异常的原始消息不得写入 UI 或诊断报告。
4. 部署步骤、部署状态、置信度、运行状态、自启状态和生命周期动作必须以稳定代码、`record` 或 `enum` 跨模块传递，不得依赖 `Object.toString()` 或显示字符串表达业务状态。
5. AI 提示模板使用英文编写，只发送经过既有边界脱敏的结构化事实；调用时显式传入受限响应语言，当前桌面语言为 `zh-CN` 时请求 Simplified Chinese，为 `en` 时请求 English。
6. 桌面端目前只支持 `en` 与 `zh-CN`。没有已保存偏好时，中文系统语言使用 `zh-CN`，其他系统语言使用 `en`；用户明确选择后，以持久化偏好为准，运行期间不跟随系统语言自动变化。

### 3.6 结构化错误治理与恢复边界

1. 错误识别、低层异常转换和安全恢复属于产生错误的模块，不新增中央 `error` Maven 模块，也不通过复制真实包结构集中保存模块私有异常。`shared.model.failure` 只提供 `OperationIdentity`、`FailureDefinition`、`FailureDescriptor`、`FailureCarrier`、`FailureSeverityLevel`、`FailureRecoveryAction` 和 `FailureRecoveryDisposition` 七个稳定最小契约。
2. 错误码固定为 `<domain>.<operation>.<reason>`：全部小写，`reason` 使用 kebab-case，例如 `linux.connection.authentication-failed`。消息键固定为 `<domain>.error.<lowerCamelReason>`。已发布错误码不得复用、改义或静默删除；同一错误码在仓库内必须唯一。
3. 系统级错误只由 `app/main/diagnostic/DesktopSystemFailureType` 管理，覆盖启动布局、数据目录、数据库初始化、UI 初始化、未知运行时异常、诊断报告写入、资源耗尽和关闭失败。运行时错误继续由 `ai`、`config`、`db`、`deploy`、`git`、`linux`、`secret`、`service`、`source`、`windows` 等产生错误的模块自己的 `FailureType` 管理。
4. 调用链固定为“模块内识别与恢复 → 共用失败契约 → `deploy`/`service` 编排 → `app/ui/diagnostic` 安全展示 → `app/main/diagnostic` 本地报告”。`DeploymentEvent` 只接受 `DeploymentTraceEvent`，可以携带 `FailureDescriptor`；部署与生命周期结果必须携带一个 `OperationIdentity` 和非致命警告列表，所含失败统一绑定到该操作标识。
5. 自动恢复仅适用于幂等、可验证、可回滚行为，包括有界重试、候选清理、事务回滚、部署回滚、重连和状态复核。认证失败、权限不足、主机指纹拒绝、协议不兼容、完整性失败、数据库损坏、较新 schema、未知远端状态、回滚/清理不可验证和 JVM 致命错误不得冒险自动修复。
6. 恢复结果必须记录为 `NOT_REQUIRED`、`NOT_ATTEMPTED`、`SUCCEEDED`、`FAILED` 或 `UNVERIFIED`。回滚或清理无法验证时部署终态固定为 `MANUAL_RECOVERY_REQUIRED`；不得以异常被捕获、命令已发出或重连成功冒充业务恢复成功。
7. SSH 连接与只读能力采集最多重试 3 次、固定间隔 250 ms；认证、主机指纹拒绝、协议不兼容和线程中断不重试，中断必须恢复线程标记。Git 瞬时网络失败最多重新创建临时工作区重试 2 次、固定间隔 500 ms；每次失败都先完成可验证清理，工具缺失、引用无效、完整性失败和中断不重试。
8. SQLite 连接必须设置 5 秒 `busy_timeout`，启动执行 `quick_check`，事务保证提交或回滚。锁定允许有界等待；损坏、较新 schema、磁盘不可用或回滚失败必须明确停止并请求处理，不自动修复数据库，也不修改现有 schema 作为错误治理手段。
9. 固定数据目录下的 `data/error-logs/` 保存 UTF-8 文本报告：单份最多 256 KiB，最多保留 50 份，启动和写入后清理最旧文件；临时文件完整写入后使用原子移动发布。报告写入失败不得递归生成新报告。
10. 报告只包含结构化字段、安全诊断、异常类名和栈帧；不得包含未知异常原始消息、密码、私钥、API Key、秘密配置、源码正文或未脱敏第三方响应。UI 展示本地化消息、错误码、operationId、安全摘要、恢复结果和报告位置；平台支持时可打开错误日志目录，否则保留可复制路径。
11. `VirtualMachineError`、`LinkageError` 等致命 JVM 错误只做尽力记录后退出，不承诺继续运行。建议型 AI 失败只生成 `ai.*` 描述并保留确定性分析结果，不获得执行授权。App Agent 的独立决策与审批协议只在登记的事务边界选择后续动作；本地验证、实际执行及回滚仍由确定性代码负责，模型拒绝或未知结果不能被错误分类绕过。
12. `FailureContractArchitectureTest` 以 JDK AST 遍历全部生产失败定义并与登记表双向核对，新增未登记、漏登记和失效登记均失败；按类型继承关系检查顶级及嵌套异常的 `Exception` 后缀和 `FailureCarrier`，不得按文件名筛选。继续检查错误码格式和唯一性、模块归属、类型命名、中英文消息键一致、用户边界及静默捕获，并在 `target/failure-catalog.md` 生成不跟踪的失败目录。原生 DB 六类原因由 `linux.error` 提供结构化描述，服务边界保留既有 `service.database.*` 及恢复建议。
13. 故障注入至少覆盖数据库锁定/损坏/回滚失败、目录不可写、归档中断/清理失败、Git 工具缺失/超时、SSH 瞬时断线/认证失败、健康失败回滚、回滚不可验证、AI 不可用、报告截断/轮转/脱敏、启动失败和未知 UI 异常。局部测试只证明本地错误语义；真实 Linux 修改路径仍必须通过现有产品入口验收。
14. `shared/backup` 的归档、加密、恢复和离线迁移使用共用失败契约与窄端口。当前 manifest 为 schema v6，激活配置 v5、运行时持久化 v5、资源载荷 v2、helper v10；桌面数据库 schema 与这些协议版本独立。旧归档不转换且拒绝读取，不伪造缺失路径、发布或秘密绑定。组件发布 SHA-256、精确秘密修订、应用 `releaseSetSha256` 和秘密并集必须一致；秘密值只在整体认证后交接可清零载荷。Jackson/Commons Compress 只处理严格有界的本地编码与归档，不决定宿主路径；远端操作通过 `linux.protocol`，`linux-sshd` 不反向依赖 `backup`。本地归档先完整复验，再无覆盖原子发布并独立回读。恢复重新解析经审阅存储绑定，具体 FHS/SQLite 规则见 6.1。能显式覆盖监听端口的组件可使用回环候选端口，其他 SQLite 应用先停旧图，再启动访问独立数据的同端口候选；不猜测监听参数。SQLite 在候选健康后提交，服务器数据库沿用整应用短停写提交；正式端口仍需二次组件及整应用健康。失败先停写，再回退数据库、配置和发布；停写或回退无法证明则保留现场并返回人工恢复。离线迁移复用完整备份与恢复，保留源图，只返回等待人工流量切换；远端成功与本地接管状态分别记录。Web 使用相同规则，不另写恢复实现。
15. `app/windows.update` 只在软件包大小/SHA-256、Ed25519 固定信任根、签名有效期、撤销状态、版本策略和架构全部通过后返回验证证据；等版本和未批准降级必须拒绝，紧急回退同时需要签名清单标记与用户批准。主进程只允许停收任务、成对备份程序/SQLite 并形成不可变交接；更新和卸载交接必须使用严格版本化、有界、用途隔离且经 HMAC-SHA256 认证的跨进程文档，认证通过前不得重建路径、决定或更新证据，错误密钥、篡改、截断、跨用途重放和认证后畸形载荷均失败关闭。编解码器不持有调用方密钥；密钥安全交付、交接文件位置/ACL 和一次性消费由生产独立执行器规格负责，不得把测试密钥或当前 JVM 内存传递冒充生产接线。替换、迁移、健康和成对回滚只允许在独立更新器验证自身身份、主进程退出和交接真实性后执行。`app/windows.uninstall` 不设置数据决定默认值；凭据范围固定为唯一 `WindowsToLinux/*`，不得由调用方缩窄、扩大或改名。外部执行器验证自身身份、主进程退出和交接真实性后还必须重新验证 jpackage、安装/数据标记及该固定命名空间，再按所选范围删除并报告精确残留。`app/secret` 的 Credential Manager 适配只删除符合应用生成键规则的目标，命名空间内其他目标报告为残留；源码、独立备份和远端应用不进入卸载端口能力。
16. 五期只允许构建回环内部测试 Web 功能服务台，不实现登录、管理员初始化、会话、CSRF、官网、正式下载或公开部署；不得把无认证测试入口作为可上线能力。六期大致承接官网、上线认证、发布下载和 Windows 生产维护，但具体模块内容、接口、部署和安全方案必须在六期实施前根据届时源码重新修订本文并取得用户批准。

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

`shared`、`app` 的 Java 叶子模块没有实现内容时不提前创建空目录。Web 按用户确认的六期结构规划、只实现五期功能；六期职责允许用 `package-info.java` 或目录 `README.md` 预留，不创建伪实现。`web/frontend` 使用前端工程自己的 `package.json`、源码和测试结构，不适用 Maven 目录。Java 包前缀固定为 `gold.debug.windowstolinux`。Web 的账号、工作区、授权、审计和发布预留以[六期文档](development/PHASE-6.md#security)为准。

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
linux.protocol.database       定义数据库固定远程操作、制品流和证据契约
linux-sshd.command            实现不向上层暴露的受控 SSH 命令机械流程
linux-sshd.backup             实现数据库固定协议、证据解析和流式制品传输
standard.deploy.build.contract     定义目标机构建 SPI
standard.deploy.build.ecosystem    实现语言与构建架构差异
standard.deploy.build.extension    装配并校验构建实现
standard.deploy.build.generation   生成安全构建脚本
standard.deploy.build.workload     实现容器与静态站点构建形态
linux-sshd.capability         实现平台能力采集
linux-sshd.capability.ecosystem 实现工具链探测、版本解析和检查脚本生成
linux-sshd.distro             定义准备渲染契约并执行受管环境准备
standard.deploy.distro.apt         完整保存 APT 机械流程、包集合与 Debian 家族差异
standard.deploy.distro.dnf         完整保存 DNF 机械流程、包集合与企业 Linux 差异
standard.deploy.distro.contract.profile    保存不可变发行版与生态能力配置
standard.deploy.distro.extension.registry  完成发行版配置装配与唯一注册
standard.deploy.distro.generation.script   生成发行版通用准备脚本
linux-sshd.execution.protocol 实现候选工作区和类型化远程协议
linux-sshd.execution.transfer 实现 Apache SSHD 受控传输
linux-sshd.runtime            实现语言无关生命周期
standard.deploy.support                编排架构、systemd、发行版和运行时支持判断
standard.deploy.support.distro         独立评估六种发行版的版本、CPU 和安全规则
standard.deploy.support.runtime        匹配语言、容器和静态站点所需工具与版本
standard.deploy.extension.adapter      按部署形态生成类型化计划，不镜像语言生态
deploy.execution.transaction  编排上传、构建、发布、健康检查和回滚
```

1. `analyze.ecosystem` 在语言分组内聚合识别、构建事实和框架分析，数据库声明分析集中于同级 `db`。每个独立语言构建架构都按第 2.3 节使用工具或架构规范名子包。Java 的 Maven、Gradle 与 JAR 必须形成平行架构，Node 的 npm、pnpm 与 Yarn、Python 的 pip、Pipenv、Poetry 与 uv 也不得混为一个无名实现。
2. `standard.deploy.build` 通过 `build.contract.spi`、`build.extension.registry` 和 `build.generation.script` 组织构建。生态差异进入 `build.ecosystem`，容器与静态站点进入 `build.workload`；构建执行器、SSH command、systemd 生命周期和 helper 调度不得复制到各生态。
3. `linux-sshd.capability.ecosystem` 实现语言与工具链命令、版本解析和能力检查脚本；`distro` 只提供软件包集合与所需能力配置，两者通过窄契约组合。
4. `standard.deploy.distro.apt` 完整保存 Ubuntu/Debian 的准备差异，`distro.dnf` 完整保存 CentOS Stream、Rocky Linux、AlmaLinux 与 Oracle Linux 的准备差异；具体发行版不得互相充当别名。
5. `standard.deploy.extension.adapter` 只按部署形态组织，`linux-sshd.runtime` 只按实际运行机制实现生命周期；两者都不得镜像语言生态。
6. CPU 架构、指令集和平台能力通过 `linux.capability` 契约采集；没有独立策略与实现时，不创建 `x86_64`、`arm64` 等执行包。
7. 标准流程没有匹配到正式支持组合时，只返回识别预览或不支持结果，不得进入环境安装、构建、发布或生命周期接管。
8. `linux` 公共契约不得引用 Apache SSHD 类型，向引擎提供绑定任务、身份和限制的命令/补丁端口，不暴露原始 SSHD、SFTP 或不受控宿主操作；具体远程实现只能位于 `linux-sshd`。

### 4.3 分包规则

1. Maven 模块表达依赖、技术和安全边界；Java 包和前端目录只负责模块内部组织，必须遵守第 5 节依赖方向。
2. 模块根包只保留稳定入口、门面或确需跨内部包使用的公共契约，具体实现进入职责明确的子包。
3. 测试包镜像对应生产包；根目录源码服务夹具使用 `test/single-language/<language>/<build-tool>/<framework-or-function>/<expected-result>-<function>` 分类，每个语言/工具组合固定三组正常部署与两组失败部署；由 `test/single-language/matrix.json` 及矩阵测试对照实际支持目录核对覆盖。正常组使用 `success-deployment-smoke`、`success-json-api`、`success-runtime-config`，失败组使用 `failure-health-rollback`、`failure-configuration-rejected`，具体故障按工具的健康检查与配置准入问题分配。`success-` / `failure-` 表示预期部署结果，不表示自动化测试应通过或报错。测试场景、文件、包及构建标识使用功能名称，不使用开发期数或执行批次；额外运行版本元数据放入模块测试资源，由验收辅助类实例化，不增加顶层场景数量。
4. 模块、包、类、接口、枚举、异常和测试类名称统一遵守第 2 节，不得另立同义词、临时名称或兼容名称。
5. `analyze` 的跨语言公共流程按 `core`、`source`、`service`、`component`、`workload` 与 `preview` 分包；规则和 SPI 进入 `contract.policy`、`contract.spi`，默认装配进入 `extension.registry`。`ecosystem` 内按语言及 `db` 分类；每个真实独立语言构建架构均按第 2.3 节建立架构名子包，语言识别器、跨架构公共事实、选择器和框架协调器留在语言根包，DB 声明检查归 `ecosystem.db`。`ProjectLanguageInspector` 固定汇总全部 10 个语言生态的检查器；`PreviewLanguageMarkerCatalog` 只识别尚无独立生态的长尾语言。`source.SourceLanguageEvidence` 只收集路径证据，具体语言规则由语言检查器提供。
6. `deploy` 将不可变输入/计划、SPI、注册表、部署形态、支持矩阵和事务编排分离；请求及公共计划直接位于 `contract`，公开结果进入 `contract.result.{compatibility,deployment,lifecycle}`，SPI 进入 `contract.spi`，适配器与注册表进入 `extension.{adapter,registry}`，环境、生命周期与事务进入 `execution.{environment,lifecycle,transaction}`。`support` 只保留支持判断门面，发行版策略和规则进入 `support.distro`，运行时工具与版本判断进入 `support.runtime`，`plan` 不得直接构造具体实现。
7. `linux` 按连接、会话、错误、传输、能力、构建、运行机制、发行版和协议组织公共远程契约，原生 DB 契约归 `ecosystem.db`，不因其中出现 `connection`、`protocol` 或 `transfer` 而迁入普通功能组；`linux-sshd` 的协议与传输实现进入 `execution.{protocol,transfer}`，语言生态实现进入 `build.ecosystem`、`capability.ecosystem` 和 `execution.protocol.helper` 资源 `ecosystem` 分组，原生 DB 的 SSHD 实现归 `execution.protocol.database`、资源归 `execution/protocol/helper/fragments/database`，工作负载构建只可进入 `build.workload`。
8. 分析层发现真实独立构建架构时必须建立架构名子包；执行层和能力层建立语言分组时以独立架构数量为依据，不以类数量为依据。不得为满足目录对称或门禁数量新增空分类、空接口、委托壳或无独立语义的数据类型。
9. 界面、数据库、认证、秘密和普通业务用例不得按被部署项目的语言复制结构。
10. 包结构不用于绕开模块职责。跨模块能力仍通过既有依赖和类型化契约协作，不复制模型，不开放原始 SFTP 或不受控 systemd、Docker、Podman 操作；Agent 生成的命令只能经绑定任务、身份与修订的受限命令接口执行。
11. `linux` 的接口、请求、结果和异常不得导入或暴露 Apache SSHD 类型；`linux-sshd` 可以依赖 Apache SSHD，但不得把具体客户端、会话、通道或 SFTP 类型传递给上层模块。
12. 生产包依赖不得成环；组合门面只能依赖下游窄契约和实现，低层 command、SPI、不可变契约与错误类型不得反向依赖注册表、默认实现、会话或业务编排。
13. 模块 Java 根包以下默认最多三层子包：第一层表达功能组或既有正交功能，第二层表达独立职责，第三层表达职责内部的真实分类或扩展轴。`contract.result.deployment`、`analyze.ecosystem.java.maven` 和 `execution.protocol.helper` 均属于标准三层结构；第四层或更深结构必须先修改本文并单独评审。总包数、单包类型数量和目录对称不作为硬门禁。
14. `contract`、`generation`、`extension`、`execution`、`persistence` 的直接子包只能使用第 2.2 节允许的职责名；标准职责不得绕过父功能组。`shared/linux` 公共远程契约、`shared/model.capability`、`shared/model.lifecycle`、`linux-sshd.capability` 与 `linux-sshd.connection` 是经评审的语义例外；`ecosystem`、`workload`、`runtime`、`distro` 保持正交，尤其不得建立 `execution.runtime`。`PackageStructureArchitectureTest` 已同步检查三层深度、功能组职责、例外、物理路径、旧包和测试镜像。
15. 普通类最多 25 个实例字段、30 个直接声明方法，单方法最多 80 个 JDK AST 语句；稳定门面 `DesktopApplicationFacade` 只豁免直接声明方法数。
16. 合并依据是行为完全一致且差异可由受校验数据表达，拆分依据是存在可独立测试和命名的职责；领域记录、枚举、状态类型和 SPI 不因文件短小而合并，类也不因行数较长而机械拆分。

### 4.4 Linux 部署链内部依赖方向

以下箭头表示左侧包可以依赖右侧包；反向依赖均禁止：

```text
standard.deploy.extension.adapter  ──→ deploy.contract.spi ──→ deploy.contract ──→ model
standard.deploy.extension.registry ──→ deploy.contract.spi + standard.deploy.extension.adapter
standard.deploy.plan               ──→ deploy.contract + standard.deploy.extension.registry
deploy.execution.transaction ──→ standard.deploy.plan + deploy.contract.result.deployment + linux
deploy.execution.lifecycle   ──→ deploy.contract.result.lifecycle + linux
standard.deploy.support             ──→ standard.deploy.support.{distro,runtime} + deploy.contract.result.compatibility + model
standard.deploy.support.distro      ──→ deploy.contract.result.compatibility + model
standard.deploy.support.runtime ──→ model

linux.connection      ──→ linux.session ──→ linux.{build,capability,distro,protocol,runtime,transfer}
linux.protocol.database ──→ linux.error
linux.* operations    ──→ linux.error ──→ model.failure

linux-sshd.command          ──→ linux.error + Apache SSHD
linux-sshd.backup           ──→ linux.protocol.database + linux-sshd.command
standard.deploy.build ──→ standard.deploy.{build.contract.spi,build.ecosystem,build.extension.registry,build.generation.script,build.workload} + linux.command
standard.deploy.build.ecosystem  ──→ standard.deploy.{build.contract.spi,build.generation.script}
standard.deploy.build.workload   ──→ standard.deploy.{build.contract.spi,build.generation.script}
linux-sshd.capability       ──→ linux-sshd.capability.ecosystem
linux-sshd.distro           ──→ linux-sshd.{distro.generation.script,capability.ecosystem,command}
linux-sshd.distro.{apt,dnf} ──→ linux-sshd.{distro,distro.contract.profile,distro.generation.script}
standard.deploy.distro.extension.registry ──→ linux-sshd.{distro,distro.apt,distro.dnf}
standard.deploy.distro.generation.script  ──→ linux-sshd.{distro.contract.profile,execution.protocol.helper}
linux-sshd.{execution.protocol,execution.transfer,runtime} ──→ linux-sshd.command
linux-sshd.session          ──→ linux-sshd.{backup,build,capability,distro,execution.protocol,execution.transfer,runtime}
linux-sshd.connection       ──→ linux-sshd.session + linux-sshd.command
```

本次平衡分包的内部方向固定如下；父包保存共享入口时可依赖职责子包，纯数据子包不得反向依赖协调入口：

```text
ai.collaboration            ──→ ai.collaboration.{invocation,advice}
ai.collaboration.invocation ──→ ai.collaboration.{advice,role}
app.ui.deployment.{automatic,multi} ──→ app.ui.deployment
app.service.deployment      ──→ app.service.deployment.{single,multi}
standard.deploy.support               ──→ deploy.contract.result.compatibility
deploy.execution.transaction ──→ deploy.contract.result.deployment
deploy.execution.lifecycle   ──→ deploy.contract.result.lifecycle
model.project               ──→ model.language
model.capability            ──→ model.server.security
```

### 4.5 平台兼容资源与会话装配

| 功能                      | 所属模块与资源                                                                        |
| ------------------------- | ------------------------------------------------------------------------------------- |
| AppArmor 用户命名空间检查 | `linux-sshd` 的 `execution/protocol/helper/fragments/workspace/apparmor-namespace.sh` |
| systemd 管理接口隔离      | `runtime/systemd/helper/systemd-manager-isolation.sh`                                 |
| SELinux 普通服务入口      | `runtime/systemd/helper/selinux-command-entry.sh`                                     |
| CentOS 源码依赖仓库       | `distro/dnf/centos-source-repositories.py`                                            |

`ManagedHelperBundle` 按固定资源展开标记，`DnfSetupRenderer` 生成 CRB 事务参数，`CentosStreamSetupRenderer` 持有发行版选择；装配不依赖 runtime/distro 的 Java 实现类，不保留临时包装类。按职责拆片段并遵守默认 300 行门禁，不能用压缩代码规避。本次 Eclipse/shfmt 展开同行语句后，`PackageStructureArchitectureTest.FORMATTED_LINE_CAPS` 对 1 个 Java 文件和 7 个 helper 片段登记格式化后的固定上限；不增加业务容量，不提供后续增长余量，其余文件沿用原门槛。后续超限必须审查职责，不能自动更新该表。

会话持有已认证连接，通过 `databaseOperations()`、`backupArtifacts()`、`restoreActivation()` 返回绑定原会话的窄端口，不继承三组接口或复制转发。运行流程归 `runtime.ManagedRuntimeExecutor`，会话类遵守 30 方法上限；端口保留批准、归属与服务端检查，不暴露给 UI。资源一致性及本地验证见[四期架构验收](development/PHASE-4.md#acceptance-architecture)。

## 5. 依赖方向

共用模块依赖固定为：

```text
shared/agent ──→ shared/ai, shared/config, shared/deploy, shared/linux, shared/model, shared/source
shared/ai ──→ shared/model
shared/backup ──→ shared/config, shared/deploy, shared/linux, shared/model
shared/config ──→ shared/model
shared/deploy ──→ shared/ai, shared/config, shared/linux, shared/model
shared/git ──→ shared/model, shared/source
shared/linux ──→ shared/model
shared/linux-sshd ──→ shared/linux, shared/model
shared/model ──→ none
shared/source ──→ shared/model
shared/standard/analyze ──→ shared/model
shared/standard/deploy ──→ shared/ai, shared/config, shared/deploy, shared/git, shared/linux, shared/model, shared/source, shared/standard/analyze
```

桌面应用模块依赖固定为：

```text
app/db ──→ shared/config, shared/model
app/main ──→ app/db, app/secret, app/service, app/ui, app/windows, shared/agent, shared/ai, shared/backup, shared/config, shared/deploy, shared/git, shared/linux, shared/linux-sshd, shared/model, shared/standard/analyze, shared/standard/deploy
app/secret ──→ app/db, shared/backup, shared/config, shared/model
app/service ──→ app/db, app/secret, app/windows, shared/agent, shared/ai, shared/backup, shared/config, shared/deploy, shared/git, shared/linux, shared/model, shared/source, shared/standard/analyze, shared/standard/deploy
app/ui ──→ app/service, shared/model
app/windows ──→ shared/model, shared/source
```

Web 模块依赖固定为：

```text
web/api ──→ shared/model, web/auth, web/service, web/task
web/auth ──→ web/db, web/secret
web/db ──→ shared/config, shared/model
web/file ──→ shared/model, shared/source
web/main ──→ shared/ai, shared/backup, shared/config, shared/deploy, shared/git, shared/linux, shared/linux-sshd, shared/model, shared/standard/analyze, shared/standard/deploy, web/api, web/auth, web/db, web/file, web/secret, web/service, web/task
web/secret ──→ shared/model, web/db
web/service ──→ shared/ai, shared/backup, shared/config, shared/deploy, shared/git, shared/linux, shared/model, shared/source, shared/standard/analyze, shared/standard/deploy, web/db, web/file, web/secret
web/task ──→ shared/model, web/db, web/service
```

依赖规则：

1. `model` 不依赖其他业务模块。
2. `config`、`source`、`standard/analyze`、`ai` 和 `linux` 只依赖 `model`；`git` 只依赖 `model` 与 `source`。除 `git → source` 外，这些模块彼此不直接依赖；`source` 不得依赖 `git`、`analyze`、`deploy`、`backup`、`app` 或 `web`。
3. `deploy` 负责模式无关的受管发布与生命周期；只有 `backup` 可以调用它完成恢复候选版本的启动、健康检查和切换，`deploy` 不得反向依赖 `backup`。
4. 禁止循环依赖，也不得通过复制模型或静态全局状态规避依赖边界。
5. `deploy` 对 `ai` 的代码依赖不代表运行时必须配置 AI；没有可用 AI 时，受支持项目的确定性分析和部署流程仍须可用。
6. `backup` 处理平台无关备份格式、迁移和归档秘密加密格式。平台本地目录、服务端凭据加密及主密钥仍由调用端文件模块与 `secret` 负责；备份密码只在调用期间存在。
7. `shared` 不依赖 `app` 或 `web`；平台适配、数据存储和界面能力不得反向进入共用模块。
8. `app/service` 只能通过 `app/secret` 使用敏感信息，不得直接处理加密算法、明文密钥或 Windows Credential Manager API。
9. `app/ui` 只能通过 `app/service` 发起业务操作，不得直接调用 `app/db`、`app/secret` 或 `shared` 的执行型模块。门面公开的 `AutomaticDeploymentInteraction` 是纯输入/确认/秘密回调契约，和其他已登记数据契约一样可由 UI 实现，不授权 UI 调用部署引擎。
10. `web/api` 不直接访问 `web/db`、`web/file`、`web/secret` 或 `shared` 的执行型模块；异步业务经 `web/task` 调用 `web/service`。
11. `web/auth` 属于六期上线方向，实施后只能通过 `web/secret` 使用密码学能力；五期仅保留包职责说明，不实现认证接口。`web/file` 不实现 SFTP 或备份恢复规则，`web/task` 不承担 SSE 传输。
12. `linux-sshd` 的项目依赖仅为 `linux`、`model`，外部依赖为 Apache SSHD 和 JSON 协议编解码；Apache SSHD 类型不得进入 `linux` 公共契约，也不得传递给上层模块。
13. `deploy`、`backup`、`app/service` 和 `web/service` 只通过 `linux` 公共契约使用远程能力，不得直接依赖或构造 `linux-sshd`。
14. 只有 `app/main` 和 `web/main` 作为组合根选择并注入 `linux-sshd`；具体 SSHD 实现不得进入业务服务、数据库、界面或 API 模块。
15. `app/db` 和 `web/db` 可以依赖 `shared/config` 保存配置实例和版本；`shared/config` 不得反向依赖平台数据库、秘密、认证或服务模块。
16. 桌面页面只能依赖对应的 `app/service/contract` 窄门面；门面依赖用例和 shared 契约，具体用例、注册表或 SSHD 实现不得反向依赖 UI。已批准的跨模块纯数据类型例外固定为 `AiCollaborationRoleKind`、`AiRoleInvocationResult`、`ProjectAnalysisRoleContext`、`DeploymentInputRoleContext`、`ComponentAnalysisRequest`、`ApplicationHealthGate`、`ManagedDatabaseBinding`、`ManagedDatabaseConnection`、`MultiComponentDeploymentResult` 和 `MultiComponentLifecycleResult` 及其嵌套类型；这些类型不要求 UI 增加执行模块依赖，也不允许扩大为整包例外。

17. `app/service → shared/source` 是源码冻结和清理的显式复用边界，必须在 POM 声明。所有生产模块引用均须属于第 5 节允许的方向并有直接 POM 依赖，前述 UI 具体纯数据类型例外除外；传递依赖不自动授权新的业务调用方向。

`ModuleDependencyArchitectureTest` 从本节读取允许方向，并核对所有叶子 POM、生产导入与显式全限定类型引用；`DocumentStructureArchitectureTest` 双向检查实际生产包与第 1 节目标树，未实现节点需明确标记 `[PLANNED]`。门禁包含禁止依赖、遗漏 POM、遗漏包节点与未标记规划目录的反例。

## 6. 桌面端数据目录

桌面应用不允许自定义 `data` 位置，也不使用注册表、命令行参数或路径指针文件覆盖默认位置。`main` 根据运行模式解析唯一数据目录：

| 运行模式 | `data` 目录                                          |
| -------- | ---------------------------------------------------- |
| CLASS    | `src/app/db/data`，与 `src/app/db/target` 同级。     |
| JAR      | DB 模块 JAR 文件（如 `DB.jar`）所在目录下的 `data`。 |
| APP      | `jpackage` 启动器 EXE 所在目录下的 `data`。          |

- `jpackage` 安装包启用按当前用户安装和安装目录选择，避免默认安装到普通用户不可写的 `Program Files`。
- CLASS/JAR 模式固定使用 `app/db` 的 `DesktopPersistence` 类作为代码源锚点，不依据主入口模块的位置或固定 JAR 文件名猜测数据目录。
- CLASS 模式优先依据 DB 模块的 Maven `target/classes`/`target/test-classes` 定位模块；JetBrains 等 IDE 输出不在模块内时，只有验证工作区中的 `windowstolinux-app-db` POM 后才解析到 `src/app/db`，不会直接把工作目录当作数据基准目录。
- 应用启动时必须验证解析出的 `data` 目录可以创建和写入；验证失败则停止启动并提示用户重新安装到可写目录。
- 不得静默回退到用户目录、临时目录或其他位置，避免同一安装出现多个不一致的数据副本。
- APP 模式下数据始终跟随 EXE 安装目录；升级和卸载时按四期文档定义的规则处理。

解析完成后的本地持久化结构固定如下；该结构不改变上述三种运行模式的根目录规则：

```text
<resolved-data>/
├─ windowstolinux.db
├─ work/
├─ backups/
└─ error-logs/
```

- `main` 只能从 `RunModeResolver` 已解析的唯一 `data` 根派生这些固定子项；不得写死 `/var/lib`、Windows 盘符、用户目录或临时目录，也不得新增覆盖入口或静默回退。
- 桌面 SQLite 保存文件绑定和数据库绑定等管理记录；分析得到的逻辑 `ComponentDataPath` 作为经审阅元数据保存，不得直接拼成桌面物理路径。数据库连接保存非秘密信息和精确 `SecretReference`，不保存密码值。
- 实际部署应用的持久化文件、容器卷和数据库保留在目标服务器或对应数据库服务中。桌面端通过 `work` 收集、验证和暂存备份及恢复材料，通过备份归档保存时间点副本。

### 6.1 Linux 受管数据与取材目录

受管应用标识记为 `A`；多组件继续使用各组件现有独立标识。`ManagedStorageLocation` 和 `ManagedStoragePlan` 为桌面、Web、原生及容器提供同一份路径契约。

| 内容                 | 默认宿主位置                                                                                 |
| -------------------- | -------------------------------------------------------------------------------------------- |
| 程序、版本及当前入口 | `/opt/windowstolinux/apps/A/{releases/<摘要>,current}`                                       |
| 部署环境及秘密配置   | `/etc/opt/windowstolinux/apps/A/` 下的不可变修订                                             |
| 显式配置文件         | `/etc/opt/windowstolinux/apps/A/files/<绑定>/revisions/<内容摘要>/value`                     |
| 普通文件             | `/var/opt/windowstolinux/apps/A/files/<绑定>/`                                               |
| SQLite               | `/var/opt/windowstolinux/apps/A/databases/<数据库标识>/application.db`；显式文件名按声明保存 |
| 自定义相对路径的实体 | 安装根内 `persistent/{configuration,files,databases}/<绑定>/`                                |

- 默认、明确自定义和未确定分别保存。未确定、变量未解析、冲突、越界及异常文件一律报错，不回退默认位置。自定义绝对位置必须位于本应用安装根的严格子路径，排除 `releases`、`current`、`persistent` 和控制目录；自定义配置通过安装根内的不可变修订映射到声明的访问位置。
- `windowstolinux-storage.properties` 声明配置和文件的资源类型、应用访问位置、环境变量接入、宿主选择、读写模式及可选种子；SQLite 在 `windowstolinux-db.properties` 声明。创建目录不等于完成接入，默认位置必须交付到应用已经支持的环境变量或明确访问路径。配置模板只在明确声明时复制。
- 客户端和 helper v10 均校验应用归属、路径组件、物理父目录、符号链接、硬链接和特殊文件；远端只允许自身创建并记录的精确映射。执行前再次验证，现有未受管目录不能自动接管。原生服务使用固定不可登录的独立系统账号，程序和配置 root 持有，只向已声明数据开放写入；系统保护和临时目录隔离保持生效。
- Docker/Podman 使用经过校验的宿主 bind mount，保留镜像内访问路径，配置只读、根文件系统只读、临时写入使用 tmpfs。SQLite 使用独立宿主目录和容器内精确别名；必要时从验证后的镜像生成只读目录视图，避免遮住程序文件。镜像种子只在首次创建时导入，现有数据不重新播种；未声明的引擎卷或额外挂载拒绝。
- SQLite 使用宿主 `sqlite3` 一致性备份和完整性检查，业务镜像不需要包含该工具。种子及初始化 SQL 仅在目标数据库首次创建时使用；已有数据库不覆盖、不重跑初始化。应用驱动由项目依赖提供。
- 完整备份仍至多包含一个受支持数据库。整应用停写后收集发布、文件、配置、OCI 和数据库制品；普通文件归档排除 SQLite 文件及 WAL/SHM/journal、受管别名和可重建的容器视图，数据库单独保存为一致性制品。
- 恢复按审阅绑定重建路径。原生候选使用独立账号和 systemd 路径映射，容器只挂候选目录；硬编码绝对路径不能访问正式数据库。SQLite 候选健康通过后停写，在目标文件系统暂存并切换，保留原文件和伴随文件；失败先确认进程停止，再恢复旧数据库、配置和发布并检查健康。无法证明完整则返回人工恢复状态。
- 旧 `/var/lib/windowstolinux/{apps,configurations,data,secrets}` 布局明确拒绝，不迁移、不删除。旧 helper、旧运行时/资源载荷及旧备份格式明确拒绝，不自动清空桌面或 Web 本地数据。新的默认布局遵循 FHS `/opt` 配套规则；允许用户在安装根内自定义持久位置是本产品例外。
- 部署工具的候选工作区、事务记录、身份登记和 `/var/lib/windowstolinux/backups/` 暂存仍归工具管理。构建临时身份及限容卷不改变；数据库服务器内部数据目录、Docker/Podman 引擎数据根不在迁移范围。
- 新布局真实 Ubuntu 24、CentOS Stream 9、原生/Docker/Podman 及双服务器恢复矩阵均为 `RUNTIME-PENDING`；旧 helper 的实机记录不作为新布局证据。

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
- `linux-sshd` 使用 Apache SSHD 实现环境采集、受控传输、安装、配置和复核，不开放原始 SSH 通道或 SFTP；自主命令经受管任务沙箱与公共审批边界执行。
- `deploy` 决定**是否安装、按什么顺序安装、何时确认以及失败后怎么办**。

因此，环境部署的业务流程归 `deploy`，公共远程能力边界归 `linux`，具体 Linux 安装、配置和检查动作归 `linux-sshd`。

### 8.1 系统准备与安全策略

- 发行版准备只在明确批准下执行固定安装和系统前置操作，能力采集保持只读。系统包安装与 SELinux/重启分别确认；普通安装授权不能替代系统变更授权。
- CentOS Stream 9/10 的 SELinux 计划绑定服务器、启动标识、配置摘要和阶段。原始配置及非秘密进度只放入 `/var/lib/windowstolinux/system-preparation/selinux/`，不保存源码、应用数据或凭据；恢复和新指纹认证规则见[四期系统准备](development/PHASE-4.md#system-preparation)。
- DNF 9 固定包事务同时更新官方 OpenSSH 包并检查 sshd，服务层须经新 SSH 认证重新采集能力。CRB 只在明确 CentOS 9/10 的事务中临时启用既有仓库，不增加仓库或永久改配置。
- SELinux 下的 systemd 隔离使用只读临时 `/run/systemd` 并仅映射必要身份查询目录；类型化服务经 `/usr/bin/env` 使用发行版普通服务域。审计明确读取日志，不关闭隔离、不放宽策略；Enforcing 不等于应用专属 SELinux 策略。Ubuntu 和其他系统保持各自适用实现。

### 8.2 构建、运行与恢复身份

管理、临时构建和运行身份分别表达；构建不使用 deployer 或运行服务的固定 UID。workspace helper 负责限容卷、身份记录和清理，build 负责构建编排，公共脚本归 generation.script，容器构建归 build.workload；运行身份分别归 runtime.systemd 和 runtime.container。

运行服务使用每组件固定系统账号；不再依赖 `/var/lib/private` 或动态运行 UID。候选使用单独账号与隔离映射，只有已声明的数据目录可写。清理核验进程、挂载、身份归属及关联数据，不删除仍持有应用数据的账号。详见 6.1 与[四期 FHS/SQLite](development/PHASE-4.md#fhs-sqlite)。

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
- 原生 systemd 观察必须保留 ActiveState、SubState、Result、ExecMainCode、ExecMainStatus 和 MainPID；failed 及失败重启等待显示 ERROR，信息缺失或查询失败显示 UNKNOWN。旧 helper 必须提示先准备环境，容器观察规则不变。ERROR 只允许刷新、停止和关闭自启，确认 STOP 后再 START，仍执行归属和依赖保护。
- 只有明确 STOP 才能在 MainPID=0、控制组无进程且读取成功后，按需 reset-failed 当前归属单元并复核 inactive；停止前后结果与退出码进入现有 observation evidence。刷新不得清理失败，不允许全局 reset-failed 或把 Yarn 129 统一视为成功退出。
- 回环 SSH 服务器夹具只存在于测试源码，显式跟踪包含重挂起在内的 accept 回调，并在关闭执行器前有界排空；后台未处理异常必须导致测试失败，原始断言为主因、收尾失败为 suppressed，最终恢复原处理器。生产 SSH 客户端保持同一会话边界，Maven 模块现为 31 个 POM。
- 资源丢失、标识不匹配、检测到外部修改或无法连接时必须返回明确的未知或异常结果，不得猜测执行、自动重建或标记成功。
- 完整受管生命周期只用于可验证归属的 WindowsToLinux 应用。经用户扫描选择并绑定实际身份的外部 systemd/Docker 对象仅开放状态、入口、启动、停止和重启，保留原配置，不开放受管更新、回滚、备份或自启；每次操作复核身份，不能作为任意服务器管理入口。

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
- `app/secret` 或 `web/secret` 管理平台凭据；可移植备份密码派生和秘密信封由 `shared/backup.crypto` 共用，平台服务提供受控秘密修订并负责及时清零。归档成员保持加密，不把平台主密钥写入备份。
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
15. 项目自有代码的自然语言注释采用中英双语（生产非 Java 资源按第 13 条保持英文；第三方、生成内容及协议标记除外），包含 `//`、块注释和 Javadoc；英文说明在前，简体中文说明紧随其后，并在同一注释内表达相同含义。标识符、命令、协议名和原始诊断保持原文，不为满足双语格式而翻译；注释不属于 UI 文案，不进入消息目录。新增或修改注释时必须遵守本规则。`shared`、`app`、`web` 的生产 Java 须覆盖所有显式具名类型、构造器、方法、字段及枚举项，使用标准 Javadoc 并补齐适用标签；record 组件通过类型的 `@param` 说明。`JavadocCoverageTest` 在 AST 层验证覆盖和双语标签，并运行 JDK 21 doclint 检查语法与引用；不要求局部变量或编译器生成成员注释。
16. 分期是开发路线与验收文档的组织方式，不是产品运行时架构。`src/` 中的模块、包、类、方法、字段、枚举、消息键、配置键、资源名、脚本名和测试名不得以 `PhaseOne`、`PhaseTwo`、`phase1`、`phase2`、一期、二期等期数命名；必须按稳定职责命名。正式文档保留分期标题，内部按功能组织；日期和版本仅为必要证据属性，不维护时间流水账，不把期数泄漏为代码 API 或持久化契约。
17. `app/ui/deployment` 只收集 `contract.definition` 中声明的表单输入并通过现有服务门面提交；运行时、配置、秘密引用、数据库范围和 Git 引用由服务用例解析，UI 只展示门面暴露的纯数据结果；标准表单不得接受自由 Shell 或任意主机挂载；Agent 界面展示实际命令及源码差异，经公共审批后执行，不接收数据库密码明文或未审阅秘密文本。标准单/多组件部署必须明确区分数据库范围“尚未审阅”“已审阅且为空”和一个或多个已审阅的服务器 DB 绑定；尚未审阅时不得进入部署。标准模式的项目类型、运行时字段和容器选项改变后，必须重新进行静态源码分析；自主 Agent 重新校验有文件证据的通用交付描述，不调用静态分析；桌面页面状态切换外观或语言时必须保留这些尚未提交的表单值。
18. 类型化部署分析必须把确定的源码元数据作为可审阅的 `DeploymentRuntimeAssessment` 返回，而非由桌面表单写死语言版本、入口、产物目录、端口或卷。仅在值唯一、受支持、边界安全且具有 `AnalysisEvidence` 时才可回填；范围、冲突、任意脚本和文档文字只能作为未解决的用户输入，绝不转换为命令。
19. 本地目录和 Git 来源都必须在 `app/service/source` 汇合为同一 `ReviewedSourcePreparation`，并以归档摘要绑定 `SourceRevision`。网络 Git 来源必须使用无凭据 URI、允许主机、固定 Commit 和受控工作目录；桌面 UI 不得调用 Git 进程、数据库或秘密存储实现。
20. `DeploymentAnalysisCoordinator` 不得导入具体项目类型实现；`ProjectLanguageInspector` 只负责组合确定性的语言事实检查器。低层语言与构建 Inspector 不得修改调用方提供的拒绝集合，生产包与测试包必须镜像，包依赖不得成环，且不得恢复按阶段或宽泛类别聚合实现的包。`standard.deploy.plan` 不得构造具体适配器，`linux.connection` 不得保存异常或组合会话，`linux-sshd.connection` 不得保存 command 或总会话；禁止以兼容壳保留旧类型。
21. 用户可见和持久化语义统一使用“发布身份摘要”（`release_sha256`）；“制品”仅指构建中待验证的文件。桌面 SQLite 当前 schema 为 v20，版本化迁移按功能保存：发布身份（v4→v5 无损列重命名）、AI 旧角色绑定（v6）与统一顺序/启用/验证记录（v15）、成功组件依赖图（v7）、非秘密运行时和数据绑定（v8–v10）、独立整应用健康探针（v11）、运行身份及指纹格式（v12）、服务器名称与检查（v13）、外部接管及显示覆盖（v14）、APP 类型化运行记录（v16）、非秘密救援记录（v17）、模型库存及独立用途/能力修订（v18）、Agent 任务事件（v19）与执行语义及命令/补丁修订（v20）。资源、配置和图与成功发布原子保存，旧记录的缺失值不得猜补为最新配置、默认身份或目标机观测。秘密只用已审阅的精确修订引用；数据库范围区分未审阅与显式为空；历史指纹仅在同一公钥认证后迁移。旧图保留并提示重新分析；缺少经审阅的运行方式时不开放生命周期动作，备份缺必要绑定时必须报告缺失；整应用探针不得从单组件反推，桌面物理路径不得替代逻辑文件路径。
22. `DeploymentSupportProfile` 是标准部署的语言、框架、支持等级与真实验收目标范围声明；自主 Agent 不受此类型目录约束。`RECOGNITION_PREVIEW` 只能由 `analyze` 读取有界路径和固定元数据，必须使用 `NONE_PREVIEW`，不得创建源码归档、部署适配器、远端构建渲染器、helper 参数或生命周期入口。标准分析中的 Shell 文件只作为识别证据，不直接转换成命令；自主 Agent 生成命令另经独立审批及受限执行。
23. `PackageStructureArchitectureTest` 使用 JDK 编译器 AST、物理路径和生产导入图自动检查文件与顶级类型同名、仓库级顶级类型唯一性、顶级及嵌套枚举语义后缀、禁限用词及封闭例外、资源文件名、复数后缀、缩写、测试后缀、模块根包以下最多三层、五个功能组及合法职责、远程契约和领域模型例外、语言/构建架构/发行版分类轴、禁用包名、全部包依赖环、测试包镜像、职责映射、反向依赖、旧 FQCN、旧物理包、旧 helper 资源路径、已删除包装类以及仅允许的 `AppMain.main` / `WebMain.main` 产品入口。Java 职责规模门禁仅排除 AST 识别的 Javadoc 行，正文、实现注释及嵌入脚本仍计入现有上限，脚本文件上限保持不变；双语文档另由 `JavadocCoverageTest` 负责。门禁不设置总包数或单包类型数量硬上限，不得通过文本豁免隐藏结构回归；通过结果只证明当前本地静态结构，不构成新的 Linux 运行证据。
24. 标准部署中每个可进入计划的源码路径必须产生一个精确 `DeploymentArchitectureType`，由 `DeploymentProjectType × DeploymentBuildToolType` 唯一标识；分析注册表、构建 Renderer 注册表、主机生态工具版本和运行时能力判断必须对该身份闭合，禁止恢复宽泛构建工具身份或以参数化 Renderer 隐藏架构差异。新增身份在逐目标产品入口证据完成前保持试验适配或 `RUNTIME-PENDING`。

25. 新增功能、修正规则和验证结果直接更新所属功能章节，并同步引用；不新增日期标题、追加批次、迁移流水账或独立版本表。已完成步骤与旧树删除，保留必要理由及带版本、环境的证据。开发文档组织规则见[开发总纲](development/DEVELOPMENT.md#documentation)。

## APP 运行契约与目录职责

- `shared/model/project/application`：分类、运行方式、结构化 argv、工作目录、协议端点、自检、只读输入及配套构建单元；与语言构建类型及健康策略正交。
- `shared/standard/analyze/component/ApplicationBundleInspector`：明确主构建目录与配套源码归属；配套单元不进入独立运行组件图。
- `shared/standard/deploy/input/ApplicationDeclaration`：桌面/Web 共用声明解析、补填与校验；两端不得维护另一套路径或分类规则。
- `shared/config/persistence/serialization`：工作负载、健康策略、运行配置的有界版本化编码，参与发布摘要和备份。
- `shared/linux-sshd` 的 `16-application-input`、`41` 至 `45`、`57-application-restore`：远端重复校验、任务互斥、原生/容器命令、自检与候选隔离。Linux 上业务程序仍使用受管服务账号或容器非 root 身份。
- `shared/model/managed/ApplicationUsage`：统一列表分类、生命周期能力、命令和端点交付；桌面/Web 仅负责展示。
- 安装及数据目录继续使用现有 FHS 结构；helper 的任务锁和维护标记属于部署工具工作区，不是应用数据。外部输入只保存声明，不归档其内容。

当前 helper 协议 10，部署运行时载荷 5，备份激活配置 5，备份 manifest schema 6，桌面管理库 schema 20。未改变的存储资源载荷仍为 2；外部输入绑定在运行契约中，不另造一份可变资源映射。

### APP 网页控制台救援边界

`app/windows/recovery` 独占 Playwright Java 1.58.0 对象，并在专用线程处理页面事件和终端输入；不接管个人浏览器配置。`app/service/recovery` 使用原有服务器锁，持有救援状态、预算、动作确认和 SSH 验证；`SshRecoveryApplicationFacade`、`SshRecoverySession` 与 `RecoverySnapshot` 是 UI 使用的救援契约。`shared/ai/recovery` 仅生成观察和建议，不执行命令；`shared/linux` 与 `shared/deploy` 不引用 Windows。

桌面模型用途以 `AiPurposeType` 明确划分部署、审批和视觉；`AiCapabilityType` 区分文字/图像测试。显示排序与用途调用顺序分离，任务持有精确配置快照。视觉识别只返回受控观察，部署和审批保持独立上下文。SQLite v18 保存用途与能力修订，v19 保存非秘密 Agent 任务事件，v20 区分旧版受限执行与新引擎语义；旧 v17 救援记录保持。三模式自动部署不接入浏览器救援，独立救援继续要求用户逐项授权。

## 共享备份采集与结构化救援错误

`shared/backup/execution/collection/BackupCollectionService` 统一准入、原始运行状态观察、依赖顺序停机、发布和资源及数据库采集、运行恢复、健康复核及远端清理。`contract/definition` 的输入携带已审阅绑定、维护标记归属及整应用健康契约；`contract/spi` 由各端提供私有素材输出、配额、取消和进度。桌面及 Web 保留原有整应用健康来源，不从组件配置推导新规则。

取消仅终止采集，必要恢复完成后才向调用者返回取消。恢复无法验证时报告人工恢复状态并保留远端素材与维护标记。只释放本次持有的维护标记；外层迁移持有的标记不在采集步骤释放。原始失败、恢复失败与各项清理失败均保留。

SSH 探测携带原始 `FailureDescriptor`；只有明确连接失败进入重试，认证、主机指纹、凭据、中断及其他失败分别处理。`RecoverySnapshot` 分开表达生命周期与可选失败原因，UI 使用既有安全错误展示。浏览器、观察、持久化记录及清理错误按所属模块登记；数据库和诊断报告不记录终端内容、命令正文或模型响应。

Web JSON 编解码由 `persistence/serialization/WebJsonCodec` 承担；`contract/validation/WebRequestValidator` 处理字段及非秘密输入校验；`interaction/WebTaskInteractionService` 转换确认、输入与进度交互。任务接口仍位于 `contract`，HTTP 与任务协议不变。桌面 AI 门面公开全部模型展示顺序、各用途成员/启用/顺序、独立能力验证及冻结调用链；历史默认配置与角色绑定继续由数据库迁移和持久化测试保护。

### App 部署模式及 Agent 边界

- App 服务层统一分流静态、AI 辅助、自主 Agent，并负责凭据、数据库和 Swing 交互适配；默认仍为 AI 辅助。Web 迁移依赖并保持现有功能，本次不增加 Agent 界面。
- `standard/deploy/assistance` 独立维护只读分析、参数建议、部署前检查及失败恢复协议，不依赖 `shared/agent`，不新增 Maven 模块。标准程序推进正常步骤；辅助模型只在歧义、缺项、冲突或失败时分析，并在失败后选择登记恢复工具。静态流程不创建模型会话，辅助流程不接受自由命令、自定义健康命令或源码补丁。
- `agent` 串行读取源码、检查远端、提出组件依赖方案、生成命令、读取实际结果并修正；不调用静态分析兜底。成功需要受管发布、全部组件健康及方案中显式声明的整应用健康探针证据。
- `deploy/approval` 在实际远端命令边界执行本地验证和独立 AI 审批。**人工审核**逐串确认；**自动审批**仅高危需确认；**完全控制**在 AI 通过后执行。完整脚本为一次审核单元，命令、目标、身份或证据变化后重新审核。
- Linux 任务源码只通过专用补丁接口修改。人工审核逐行确认；自动审批首次获得任务授权，后续高危再次确认；完全控制不需人工确认。修改前摘要、差异、独立审批和执行结果绑定同一修订；修订变化使旧构建和批准失效。
- 管理连接沿用 root 身份；项目命令在 helper 隔离的临时非 root 身份下执行，运行服务使用受管独立身份。
- 本地原项目与冻结快照只读；构建输出单独可写。选定模型仅收到按需读取且过滤秘密的文本；持久日志只记录非秘密摘要、状态及修订，不保存完整源码、提示词或秘密。
- 数据库 schema 20 为任务保存执行语义版本：旧记录为 1（旧版受限执行，不续写），新任务为 2。运行描述格式 7 支持通用进程，兼容既有版本的明确字段；helper 协议为 10。
- 未知写入结果禁止自动重放；无法证明恢复的任务保留人工恢复状态。真实模型、Linux 权限及端到端可靠性仍为 `RUNTIME-PENDING`，历史验收不能替代新模式验收。

### AI 命令审批长期规则

今后所有涉及 AI 命令执行的新增或调整功能，统一使用“人工审核、自动审批、完全控制”三档，名称及语义不得另行定义。

- **人工审核**：每条实际命令或完整脚本均须用户审核通过后执行。
- **自动审批**：只有高危命令需要用户审核。
- **完全控制**：AI 审核通过后直接执行，无须人工确认。

三档均须通过本地校验和独立 AI 审核。AI 拒绝、审核不可用或本地禁止时不得执行。阶段批准不能代替实际命令批准；命令内容、目标、执行身份或相关证据变化后重新审核。保留现有枚举和持久化值，不因文案或内部类名调整改变历史含义。公共审批归 `shared/deploy/approval`；Agent 专属源码补丁授权归 `shared/agent`。

### 辅助分析与恢复边界

- `SourceReadPort` 只公开快照修订校验、目录分页、字面搜索和分页读取。分析会话不持有原始路径、SSH、凭据、写入、补丁或部署执行端口；公共 `SourceBrowser` 负责边界、秘密排除、脱敏及读取上限。
- `AssistedAnalysisSession` 最多读取 12 页、累计 48000 个字符；建议仅引用本轮实际读取或搜索证据，目录列表不能作为参数依据。源码修订变化即拒绝旧结果。源码及日志中的指令均视为不可信数据。
- 多个受支持类型可由 AI 结合证据选择，入口、构建输出、版本、依赖、端口及既有健康参数继续进入标准契约校验。业务意图、凭据、数据授权、资源归属、权限及外部暴露范围须由用户确定。用户显式值发生冲突时先展示建议依据，再请求澄清。已有数据库结构变更及 SQLite 首次初始化的业务授权仍由用户确认；这不代替后续实际命令的三档审批，完全控制也不允许模型补造数据授权。
- 单组件及多组件共用修正策略。正常路径不调用模型选择固定步骤；已验证计划和实际服务器证据进行一次部署前检查，相关事实改变后重查。失败后只允许有证据的参数修正或已验证环境变化触发新计划；最多两次修正后重试，分析、预检、失败解释及恢复共用 30 次模型决策额度，模型接替不重置。
- 结果未知、恢复无法验证、预算耗尽、没有新进展或人工接管时停止自动继续。多组件始终复核依赖图和整应用健康门槛，不通过削弱健康条件制造成功。

### 全仓格式与检查

项目自有源码、测试、脚本、配置和多语言样例统一使用根 `.editorconfig` 与 `.gitattributes`。Java 四空格，`package`、导入区、类型声明之间各保留一个空行，普通与静态导入分组；Web 两空格、Python 四空格，Go 和 Makefile 保留所需制表符。Shell 使用 LF，Windows 批处理使用 CRLF。

固定工具版本、配置、片段处理和例外范围见 [格式工具说明](../.mvn/formatting/README.md)。`python .mvn/formatting/format.py --check` 只检查；`--write` 才修改。工具缺失须报错，不隐式安装。Java 检查进入 Maven `verify`，前端提供 `format:check`。排除生成产物、第三方、锁文件、二进制和保留证据，不整体排除测试。格式化不得改变字符串、文本块、heredoc、协议载荷和故意失败样例的含义；helper 内容变更同步校验摘要，纯格式变化不升级协议版本。交付前必须全仓检查通过，第二次写入为零差异。
