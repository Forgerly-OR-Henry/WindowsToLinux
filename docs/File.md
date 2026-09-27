# WindowsToLinux 源码模块与包结构

> **1.1 目标结构：** [目录标记说明](#phase-1-1-reading) · [完整目标目录及逐项职责](#phase-1-1-tree) · [目标依赖关系](#phase-1-1-dependencies) · [已确认的 Mod 源码规范](#mod-source-layout)。第 1～7 节记录当前源码结构及维护规则；第 8 节记录已确认目标结构，二者不混用。

## 文档信息

> 当前实现与 1.1 设计分开维护：第 1 节当前目录树和第 5 节依赖方向继续供现有架构门禁读取；新的详细目录只见文末 [1.1 目标结构](#phase-1-1-target)。当前仍有 `src/shared`；已增量加入 `src/appapi`、`src/app/mod`、`src/app/linux`、`src/app/ai`、`src/app/source` 及平台基础实现，默认入口已切换 H2 安全外壳、常驻模型配置/能力验证、服务器配置/SSH 认证检查、连接与传输阈值设置、独立平台授权偏好、日志查看/清理和保留任务取消/核对，受管辅助进程已接任务账本及日志，跨 Mod 同步服务已接真实类身份和逐调用资源占用；平台任务范围授权已接真实 SSH，官方业务 Mod 接线仍待完成。下方当前包树已补入真实存在的 1.1 节点；旧 shared/web 仍活动，依赖门禁及完整活动图尚待 S3 切换。0.x 的未实施规划如与 1.1 冲突，已由 [1.1 工程](development/PHASE-1.1.md)取代。

- 文档版本：`4.0.0-standard-agent`
- 文档状态：1.1 增量改造进行中；旧 shared/web 尚在根构建，完整活动模块与结构门禁待切换。0.x 业务尚未全部接入新默认入口；本机证据与远端未验收边界见 1.1 开发文档。
- 当前实现范围：根构建增量包含 `appapi`、`app`、业务 `mod` 聚合、旧 `shared` 与旧 `web`；`app/mod` 已承载运行基础，`app/linux` 已承载独立 SSH 认证、命令及 SFTP，`app/ai` 已承载通用模型编解码与有界 HTTP 调用，service 已接公开任务、审批、服务器引用、秘密及 AiService 适配。默认 CLASS 与安装版入口已接已选 Mod 的依赖启动和逆序停止；`src/mod/pom.xml` 是明确开发工作区的聚合锚点，目前没有业务子模块或空职责包。旧 shared/ai 暂时复用 app/ai 的正文限额接收器，随业务迁移移除此过渡依赖。目标活动根为 `appapi`、`app` 和官方业务 `mod`，Web 后置。
- 已确认能力边界：受管应用生命周期复用既有模块，不新增独立 Maven 模块
- 更新日期：2026-09-27（1.1 增量实施；完整目标图仍待切换）
- 开发总纲：[DEVELOPMENT.md](development/DEVELOPMENT.md)
- 功能分工：[分析与 Linux 部署链](development/PHASE-0.3.md#architecture)

> 本文只规定源码模块、包的结构及命名，包含与之直接相关的模块职责、依赖方向和资源放置规则；发布布局、安装、运行数据及业务行为由各自文档维护。现行结构说明以当前代码核对，文档冲突时修正文档，不为了符合旧描述倒改实现；明确的未来节点仍保留规划标记。迁移与门禁更新步骤见 [1.1 开发文档](development/PHASE-1.1.md#structure-gates)。运行能力以当前实现和适用的实际行为为准，不得由目标目录或旧验收推定支持。

## 1. 完整目标结构

本节保留当前 0.x 实现对应的目录及明确标记的历史预留，不代表 1.1 新结构。标题保留以兼容现有文档解析器。叶子模块下直接展示的是对应 Java 根包的子包，省略重复的 Maven `src/main/java` 路径；物理目录模板见第 4.1 节。`pom.xml` 作为聚合入口固定置顶，其余模块和内部包按英文名称排序。

```text
WindowsToLinux/
├─ pom.xml
├─ docs/
│  ├─ development/                          开发总纲与各期工程文档
│  │  ├─ DEVELOPMENT.md                    开发总纲与文档导航
│  │  ├─ PHASE-0.1.md                             0.1 期主文档
│  │  ├─ PHASE-0.2.md                             0.2 期主文档
│  │  ├─ PHASE-0.3.md                             0.3 期主文档
│  │  ├─ PHASE-0.4.md                             0.4 期主文档
│  │  ├─ PHASE-0.5.md                             0.5 期主文档
│  │  ├─ PHASE-0.6.md                             0.6 期历史主文档
│  │  └─ PHASE-1.1.md                             1.1 期平台化设计与修改计划，待实施
│  ├─ AllFile.md                               全文件架构索引
│  ├─ APP.md                                APP 发布与安装布局
│  ├─ File.md                                 源码模块、包结构与命名
│  ├─ Mod.md                                官方与第三方共同使用的 Mod 包格式设计
│  ├─ PRODUCT-MANUAL.md                       产品说明书
│  └─ Runtime.md                            运行约定、数据库与安全机制
└─ src/
   ├─ app/
   │  ├─ pom.xml
   │  ├─ ai/
   │  │  ├─ protocol/
   │  │  ├─ recovery/
   │  │  └─ transport/
   │  ├─ db/
   │  │  ├─ bootstrap/
   │  │  ├─ entity/
   │  │  ├─ execution/
   │  │  │  └─ schema/
   │  │  ├─ failure/
   │  │  ├─ log/
   │  │  ├─ mod/
   │  │  ├─ persistence/
   │  │  │  ├─ connection/
   │  │  │  ├─ repository/
   │  │  │  └─ serialization/
   │  │  ├─ schema/
   │  │  └─ task/
   │  ├─ linux/
   │  │  ├─ recovery/
   │  │  └─ ssh/
   │  ├─ main/
   │  │  ├─ diagnostic/
   │  │  ├─ mod/
   │  │  ├─ runtime/
   │  │  └─ startup/
   │  ├─ mod/
   │  │  ├─ development/
   │  │  ├─ installation/
   │  │  ├─ lifecycle/
   │  │  ├─ loader/
   │  │  ├─ manifest/
   │  │  ├─ process/
   │  │  ├─ service/
   │  │  └─ version/
   │  ├─ secret/
   │  │  └─ crypto/
   │  ├─ service/
   │  │  ├─ ai/
   │  │  ├─ backup/
   │  │  ├─ config/
   │  │  ├─ contract/
   │  │  │  ├─ definition/
   │  │  │  ├─ profile/
   │  │  │  ├─ result/
   │  │  │  ├─ security/
   │  │  │  └─ spi/
   │  │  ├─ database/
   │  │  ├─ deployment/
   │  │  │  ├─ automatic/
   │  │  │  ├─ multi/
   │  │  │  └─ single/
   │  │  ├─ execution/
   │  │  │  ├─ environment/
   │  │  │  └─ lifecycle/
   │  │  ├─ failure/
   │  │  ├─ linux/
   │  │  ├─ lock/
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
   │  │  ├─ boundary/
   │  │  └─ snapshot/
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
   │  │  ├─ mod/
   │  │  ├─ recovery/
   │  │  ├─ server/
   │  │  ├─ setting/
   │  │  └─ shell/
   │  └─ windows/
   │     ├─ filesystem/
   │     ├─ security/
   │     ├─ uninstall/
   │     ├─ update/
   │     ├─ window/
   │     └─ workspace/
   ├─ appapi/
   │  ├─ ai/
   │  ├─ db/
   │  ├─ linux/
   │  ├─ mod/
   │  ├─ secret/
   │  ├─ server/
   │  ├─ source/
   │  ├─ task/
   │  └─ ui/
   ├─ mod/                                  业务 Mod 聚合；当前仅有工作区锚点
   │  └─ pom.xml
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
- `frontend` 是由 `package.json` 管理的 Vue 3、TypeScript 和 Vite 工程，不套用 Java Maven 叶子模块目录；`web/main` 的 Maven 构建安装本地 Node、执行 npm ci、类型检查和 Vite 构建，将产物打入 Spring Boot 静态资源。运行时只启动 Java，分发为 `web.jar` 与外置 `lib`；正式发布尚未实现，旧 0.6 期方向见历史文档，新官网方向见 1.1。`web/frontend/src/shared` 是前端复用目录，不是 `src/shared` Maven 聚合模块。
- Maven 坐标统一使用 `gold.debug.windowstolinux`，根父工程为 `windowstolinux-parent:0.1.0-SNAPSHOT`，编译目标为 Java 21。
- WindowsToLinux 自身构建使用开发机的系统 Maven 和系统本地仓库；项目 POM 不声明仓库位置，不创建项目专用 Maven 仓库，也不新增 Maven Wrapper 作为本项目构建入口。
- 构建插件确需额外构建期依赖时，在根 POM 对应插件的 `<dependencies>` 中显式声明，供系统 Maven 同步；不得为了补插件缓存而把依赖加入业务叶子模块的运行时 classpath。
- 第 1 节是正式目标结构。结构迁移必须原子更新包声明、物理路径、导入、测试和门禁；旧包前缀与薄包装类不保留兼容壳。
- `shared/backup` 已实现平台无关归档、数据库适配、候选恢复和离线迁移核心；桌面 `app/service.backup` 已组合完整远端备份、受管目标恢复和双服务器离线迁移，`app/ui.backup` 已提供对应产品入口。`shared/backup.crypto` 使用独立调用级备份密码实现 Argon2id 64 MiB/3 次/1 路派生、AES-256-GCM 认证加密及严格秘密修订载荷，服务对象不持有密码或派生密钥。Windows 更新/卸载安全核心已存在，生产独立执行器尚未实现，旧 0.6 期规划仅作历史依据。Web 已实现 0.5 期回环内部测试服务台；`auth`、账号/空间/审计/发布和登录密码包仅有 0.6 期包文档预留。数据库先建立多用户表和作用域外键，0.5 期只启用固定内部身份。公共官网尚未实现；原正式多用户执行方向已由 1.1 工程取代。

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

实际生产 Java 包必须出现在目标树；`[PLANNED]` 节点及其子树声明未实施职责；旧 Web 预留已被新方向取代，仅为解释现有包说明而保留标记，不要求继续实现。Web 只有包说明的 0.6 期节点继承该标记，前端实际文件由 [AllFile.md](AllFile.md) 记录。文档节点与依赖由反例测试检查，不因包或类型存在而宣称运行验收通过。

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

桌面持久化使用 `persistence.connection` 与 `persistence.repository`；Web 使用 `persistence.mapper`、`persistence.repository` 和 Spring 受管事务，连接池在 Web 启动配置中建立。平台无关运行时文档编解码位于 `shared.config.persistence.serialization`。0.6 期未实现职责只保留包说明。

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
| E-05 | 1.1 受控失败以公开实例方法 `Problem problem()` 暴露安全原因；未迁移代码保留 `FailureCarrier`。本地化消息与安全诊断分离。 | `ApiException`、`BrowserRecoveryException` | 原始 `exception.getMessage()` 用户回退 |
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
| `ui`      | 实现 Swing/FlatLaf 界面、输入校验、进度展示和用户决定交互。                                                                                                            | 不直接访问 SQLite、SSH、AI、加密算法或 APP 数据库中的统一凭据库。                                                                                  |
| `windows` | 选择和访问 Windows 本地项目、备份及工作目录，采集文件与环境事实，通过 `shared/source` 准备待上传项目包；持有桌面包签名/版本/架构验证、独立更新事务和受管卸载文件边界。 | 不自行定义源码归档格式，不执行远程上传，不解释备份格式，不处理加密、私钥、明文秘密或 Credential Manager 实现；卸载凭据只能经 `secret` 窄端口编排。 |
| `db`      | 管理 SQLite 连接、表结构、版本化迁移和事务，保存非敏感数据、配置实例与历史版本、受管应用标识、最后观测状态及由 `secret` 生成的加密数据。                               | 不定义共用配置规则，不执行加解密，不派生或保存明文密钥，不直接访问 APP 数据库中的统一凭据库。                                                      |
| `secret`  | 统一处理主密码、Argon2id 密钥派生、AES-256-GCM 加解密、DEK 包装、敏感信息存取和 APP 数据库中的统一凭据库。                                                             | 不负责普通业务数据、界面、项目打包、部署或远程连接。                                                                                               |
| `service` | 实现桌面端部署和受管应用生命周期等用例、后台任务、同服务器修改互斥、事件、取消和模块协作，整合 `app` 与 `shared` 能力。                                                | 不自行实现界面、SQLite、加密算法、凭据平台接口、SSH/SFTP 或 Linux 生命周期动作。                                                                   |
| `main`    | 提供应用启动入口，识别运行模式、解析应用与数据目录，装配模块和 `linux-sshd` 实现并管理生命周期。                                                                       | 不承载具体业务规则、界面逻辑、持久化、SSH/SFTP 或加密实现。                                                                                        |

桌面端所有加密、解密、密钥派生、密钥包装、敏感信息存取和 APP 数据库中的统一凭据库。`windows` 只处理非敏感的 Windows 本地能力；不得为了平台调用方便把任何安全实现放入 `windows`。

桌面调用方向固定为 `UI → app/service/contract → 窄用例 → shared 领域/契约`。`AiApplicationFacade`、`BackupApplicationFacade`、`DeploymentApplicationFacade`、`MultiComponentApplicationFacade`、`ServerApplicationFacade` 和 `ManagedApplicationFacade` 隔离页面所需能力；`DesktopApplicationFacade` 作为稳定门面和组合根实现这些门面，只转发到窄用例，不承载业务算法。

### 3.3 `web` Web 应用模块

本节描述 0.5 期内部服务台的当前代码与 0.6 期历史结构预留。其中未实现的生产认证、账号、工作区和执行服务规划已由 1.1 工程取代，不再要求继续补齐；新官网不要求下载者登录，网站细节暂缓。下表用于解释尚存的旧模块，不作为新官网任务清单。

| 模块       | 职责                                                                                                                                                            | 明确不负责                                                                                           |
| ---------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------- |
| `frontend` | 实现 Vue 3/TypeScript 页面、用户交互、流式上传交互和 SSE 事件展示。                                                                                             | 不保存原始凭据，不直接访问数据库、文件系统、SSH/SFTP 或共用执行模块。                                |
| `api`      | 通过 Spring MVC Controller 实现版本化 REST API、SSE 及输入输出边界，Filter 执行来源和容量检查，统一提供脱敏错误。                                               | 不直接访问 SQLite、受管文件目录、凭据或部署执行器，不承载业务流程或直接执行生命周期动作。            |
| `auth`     | 旧 0.6 期身份、登录、会话与访问安全结构预留，当前无业务实现；该生产方向已由 1.1 取代。                                                                          | 0.5 期内部功能测试不得提前创建伪认证；本模块不自行实现密码哈希、加密算法、主密钥或普通业务编排。     |
| `service`  | 实现 Web 部署、配置访问控制和受管应用生命周期等用例并整合 `db`、`file`、`secret` 与 `shared` 能力。                                                             | 不实现 HTTP/SSE、任务调度、SQLite、文件底层操作、密码学算法、SSHD 或 Linux 生命周期动作。            |
| `task`     | 管理部署和生命周期等持久化任务的状态、调度、同服务器互斥、受控取消、重启恢复和结构化事件。                                                                      | 不传输 SSE，不重复实现分析、部署、生命周期、备份或迁移规则。                                         |
| `db`       | 使用受管数据源、Spring 事务和 MyBatis-Plus Mapper 管理 Web SQLite、版本化迁移及持久化仓储，包括配置实例与历史版本、项目与环境关联、受管应用标识和最后观测状态。 | 不定义共用配置规则，不执行加解密，不管理上传文件、后台调度或接口协议，不把最后观测状态当作远端事实。 |
| `file`     | 管理流式上传下载、配额、临时文件清理和受管工作目录，对上传容器执行配额和路径检查，完成后通过 `shared/source` 生成规范源码归档。                                 | 不定义第二套规范源码归档格式，不负责 SFTP、远程 Linux 文件、备份恢复语义或秘密加解密。               |
| `secret`   | 0.5 期为内部功能测试处理测试主密钥和服务端凭据；旧 0.6 期认证密码学预留只作历史，不代表新官网要保存下载者账号。                                                 | 不负责登录会话流程、普通数据库业务、上传文件、部署或远程连接；不得因 0.5 期无登录而明文保存秘密。    |
| `main`     | 提供 Spring Boot 无参入口及 YAML 绑定，装配后端模块、`linux-sshd`、数据源与前端静态资源，管理启动检查、健康检查和关闭。                                         | 不承载具体业务、接口、任务、持久化、文件、SSH/SFTP 或密码学实现。                                    |

Web 主密钥和本地凭据加解密由 `web/secret` 独占；可移植备份密码格式由 App/Web 共用的 `shared/backup.crypto` 独占。0.5 期不实现登录或管理员密码；旧 0.6 期曾约定 `auth` 通过 `secret` 使用认证密码学，这属于未实施历史方向。其他 Web 模块不得直接接触明文密钥或实现加密算法。

### 3.4 配置规则、存储与秘密边界

1. `shared/config` 提供平台无关的配置类型、文本解析、规则和载荷编码，不读写文件或数据库，也不定义 Repository、数据库表或具体持久化技术；`persistence` 仅表示可复用编码，不承担平台存储。
2. 桌面端配置实例及历史版本由 `app/db` 统一保存；Web 配置实例、项目、环境关联及历史版本由 `web/db` 统一保存，不为每名用户创建独立配置文件。Web 后续支持多用户或租户时，所有权关联同样由 `web/db` 保存。
3. 0.5 期 Web 只能在明确回环内部测试模式访问配置，由 `web/service` 保持业务边界；旧 0.6 期上线权限协作仅属历史规划，新网站不继承用户执行服务。`shared/config` 不感知具体用户、租户、会话或授权策略。
4. 敏感值由 `app/secret` 或 `web/secret` 处理；普通配置只保存秘密 ID、版本等不透明引用，不保存明文秘密。
5. 桌面启动和固定数据根解析分别由 `app/main/startup` 与 `app/main/runtime` 承担；Web 启动配置位于 `web/main/config`，Spring 装配位于 `web/main/startup`，CLASS/JAR 数据根解析位于 `web/db/runtime`。这些应用启动职责不属于 `shared/config`。

平台 UI 通过现有 `service.contract` 门面提交类型化表单，只使用门面显式暴露的纯数据契约。平台服务负责交互、凭据和存储记录构造；标准运行时及默认值解析归 `shared/standard/deploy/input`，普通配置文本解析和平台无关载荷编码归 `shared/config/input`、`shared/config/persistence`，数据库审阅模式归 `shared/model/deployment`。这些共享规则不读写平台数据库，不向 UI 开放解析器、远程端口或 SSHD 实现。

### 3.5 桌面端本地化与诊断边界

1. 英文是桌面端生产代码、内部校验、程序生成诊断和默认消息资源的基准语言；`app/ui/i18n/messages/Messages.properties` 是消息目录的规范来源，简体中文只保存在 `Messages_zh_CN.properties`。
2. 生产模块不得提前拼接英文或中文界面句子。1.1 失败通过公开 `Problem` 传递稳定事件码、安全英文诊断、上下文及原诊断身份；UI 按 `problem.<eventCode>` 查找固定本地化说明，未登记事件使用脱敏后的安全诊断。未迁移代码仍通过 `FailureCarrier.failure()` 暴露 `FailureDescriptor` 及原消息键；UI 是唯一负责解析显示语言的边界。
3. 部署步骤、部署状态、置信度、运行状态、自启状态和生命周期动作必须以稳定代码、`record` 或 `enum` 跨模块传递，不得依赖 `Object.toString()` 或显示字符串表达业务状态。

语言选择、原始诊断处理等运行规则见 [运行约定](Runtime.md)。

### 3.6 结构化错误契约与模块归属

1. 错误识别、低层异常转换和安全恢复属于产生错误的模块，不新增中央 `error` Maven 模块，也不通过复制真实包结构集中保存模块私有异常。1.1 使用 `appapi.task` 的 `Problem`、`ProblemCode` 和 `ApiException`；内部异常可以保留原 cause，但平台公开出口只能携带安全 Problem。`shared.model.failure` 的七个旧契约仅用于尚未迁移的调用链。
2. 旧错误码固定为 `<domain>.<operation>.<reason>`：全部小写，`reason` 使用 kebab-case，例如 `linux.connection.authentication-failed`；旧消息键为 `<domain>.error.<lowerCamelReason>`。迁移凭据错误时保留原八个事件码，采用公开 `ProblemCode` 分类，UI 消息键改为 `problem.<eventCode>`。已发布错误码不得复用、改义或静默删除；同一错误码在仓库内必须唯一。
3. 系统级错误只由 `app/main/diagnostic/DesktopSystemFailureType` 管理，覆盖启动布局、数据目录、数据库初始化、UI 初始化、未知运行时异常、诊断报告写入、资源耗尽和关闭失败。运行时错误继续由 `ai`、`config`、`db`、`deploy`、`git`、`linux`、`secret`、`service`、`source`、`windows` 等产生错误的模块自己的 `FailureType` 管理。
4. 已迁移调用链保留原 `Problem` 和诊断身份，UI 不经旧报告写入器重新生成身份；统一后台日志生产方迁移仍待完成。旧调用链继续使用“模块内识别与恢复 → 共用失败契约 → `deploy`/`service` 编排 → `app/ui/diagnostic` 安全展示 → `app/main/diagnostic` 本地报告”。旧 `DeploymentEvent` 只接受 `DeploymentTraceEvent`，可以携带 `FailureDescriptor`；部署与生命周期结果携带 `OperationIdentity` 和非致命警告列表，所含失败统一绑定到该操作标识。
5. `FailureContractArchitectureTest` 以 JDK AST 遍历仍实现 `FailureDefinition` 的生产定义并与登记表双向核对，新增未登记、漏登记和失效登记均失败；按类型继承关系检查顶级及嵌套异常的 `Exception` 后缀，以及公开实例 `Problem problem()` 或未迁移的 `FailureCarrier`，不得按文件名筛选。继续检查旧错误码及凭据事件码格式和唯一性、模块归属、类型命名、中英文消息键一致、用户边界及静默捕获，并在 `target/failure-catalog.md` 生成不跟踪的旧失败目录。原生 DB 六类原因由 `linux.error` 提供结构化描述，服务边界保留既有 `service.database.*` 及恢复建议。

重试、恢复、日志、备份和更新行为及验收边界见 [运行约定](Runtime.md)。

## 4. 叶子模块约定

### 4.1 Maven 通用目录结构

本节描述当前 0.x 工程约定。其允许的旧 Web 预留仅解释已有文件，不授权继续新增；1.1 结构按文末目标结构章节及主文档门槛实施。

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

`shared`、`app` 的 Java 叶子模块没有实现内容时不提前创建空目录。Web 现有 0.6 期 `package-info.java` 和说明性 `README.md` 是旧规划留下的职责预留，实际只实现 0.5 期功能；保留现有文件，不据此新增旧规划占位。`web/frontend` 使用前端工程自己的 `package.json`、源码和测试结构，不适用 Maven 目录。Java 包前缀固定为 `gold.debug.windowstolinux`。Web 账号、工作区、授权、审计和发布预留的历史原因见[0.6 期文档](development/PHASE-0.6.md#security)，后续方向以 1.1 为准。

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

当前内部依赖方向如下；父包保存共享入口时可依赖职责子包，纯数据子包不得反向依赖协调入口：

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

`ManagedHelperBundle` 按固定资源展开标记，`DnfSetupRenderer` 生成 CRB 事务参数，`CentosStreamSetupRenderer` 持有发行版选择；装配不依赖 runtime/distro 的 Java 实现类，不保留临时包装类。按职责拆片段并遵守默认 300 行门禁，不能用压缩代码规避。Eclipse/shfmt 展开同行语句后，`PackageStructureArchitectureTest.FORMATTED_LINE_CAPS` 对 1 个 Java 文件和 7 个 helper 片段登记格式化后的固定上限；不增加业务容量，不提供后续增长余量，其余文件沿用原门槛。后续超限必须审查职责，不能自动更新该表。

会话持有已认证连接，通过 `databaseOperations()`、`backupArtifacts()`、`restoreActivation()` 返回绑定原会话的窄端口，不继承三组接口或复制转发。运行流程归 `runtime.ManagedRuntimeExecutor`，会话类遵守 30 方法上限；端口保留批准、归属与服务端检查，不暴露给 UI。资源一致性及本地验证见[0.4 期架构验收](development/PHASE-0.4.md#acceptance-architecture)。

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
app/secret ──→ appapi, app/db
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
8. `app/service` 只能通过 `app/secret` 使用敏感信息，不得直接处理加密算法、明文密钥或 APP 数据库中的统一凭据库。
9. `app/ui` 只能通过 `app/service` 发起业务操作，不得直接调用 `app/db`、`app/secret` 或 `shared` 的执行型模块。门面公开的 `AutomaticDeploymentInteraction` 是纯输入/确认/秘密回调契约，和其他已登记数据契约一样可由 UI 实现，不授权 UI 调用部署引擎。
10. `web/api` 不直接访问 `web/db`、`web/file`、`web/secret` 或 `shared` 的执行型模块；异步业务经 `web/task` 调用 `web/service`。
11. `web/auth` 当前仅保留旧 0.6 期包职责说明，不提供认证接口；现有依赖允许表用于检查旧结构，不授权补齐已被 1.1 取代的上线方向。`web/file` 不实现 SFTP 或备份恢复规则，`web/task` 不承担 SSE 传输。
12. `linux-sshd` 的项目依赖仅为 `linux`、`model`，外部依赖为 Apache SSHD 和 JSON 协议编解码；Apache SSHD 类型不得进入 `linux` 公共契约，也不得传递给上层模块。
13. `deploy`、`backup`、`app/service` 和 `web/service` 只通过 `linux` 公共契约使用远程能力，不得直接依赖或构造 `linux-sshd`。
14. 只有 `app/main` 和 `web/main` 作为组合根选择并注入 `linux-sshd`；具体 SSHD 实现不得进入业务服务、数据库、界面或 API 模块。
15. `app/db` 和 `web/db` 可以依赖 `shared/config` 保存配置实例和版本；`shared/config` 不得反向依赖平台数据库、秘密、认证或服务模块。
16. 桌面页面只能依赖对应的 `app/service/contract` 窄门面；门面依赖用例和 shared 契约，具体用例、注册表或 SSHD 实现不得反向依赖 UI。已批准的跨模块纯数据类型例外固定为 `AiCollaborationRoleKind`、`AiRoleInvocationResult`、`ProjectAnalysisRoleContext`、`DeploymentInputRoleContext`、`ComponentAnalysisRequest`、`ApplicationHealthGate`、`ManagedDatabaseBinding`、`ManagedDatabaseConnection`、`MultiComponentDeploymentResult` 和 `MultiComponentLifecycleResult` 及其嵌套类型；这些类型不要求 UI 增加执行模块依赖，也不允许扩大为整包例外。

17. `app/service → shared/source` 是源码冻结和清理的显式复用边界，必须在 POM 声明。所有生产模块引用均须属于第 5 节允许的方向并有直接 POM 依赖，前述 UI 具体纯数据类型例外除外；传递依赖不自动授权新的业务调用方向。

`ModuleDependencyArchitectureTest` 从本节读取允许方向，并核对所有叶子 POM、生产导入与显式全限定类型引用；`DocumentStructureArchitectureTest` 双向检查实际生产包与第 1 节目标树，未实现节点需明确标记 `[PLANNED]`。门禁包含禁止依赖、遗漏 POM、遗漏包节点与未标记规划目录的反例。

## 6. 桌面端数据目录

本标题保留为现有模块依赖测试的解析结束标记。实际运行数据目录与行为规则统一见 [运行约定](Runtime.md#desktop-data)，不在本文维护。

## 7. 源码结构约束

当前 0.x 结构见第 1～6 节，1.1 结构见第 8 节。结构变更、模块评估和门禁更新流程见 [1.1 开发文档](development/PHASE-1.1.md#structure-gates)。

1. 普通 Maven 叶子模块的目录名使用一个英文单词；只有把具体实现从公共契约模块独立隔离时，才使用 `<contract>-<implementation>`，例如 `linux-sshd`。
2. Java 类存在接口与实现不代表必须拆分 Maven 模块；只有具体实现需要独立依赖或装配时才建立连字符实现模块。`source` 当前不拆分为 `source-tar`，源码归档格式属于其内部职责。
3. 只有经过确认的目录、模块名称、职责和依赖方向才能写入正式结构；禁止通过复制模型、反射或静态全局状态绕开依赖约束。
4. `.idea/`、`.ai-workspace/`、`target/`、`node_modules/`、`dist/`、缓存、日志和其他开发辅助或生成目录不纳入正式项目结构。
5. 分期是开发路线与验收文档的组织方式，不是产品运行时架构。`src/` 中的模块、包、类、方法、字段、枚举、消息键、配置键、资源名、脚本名和测试名不得以 `PhaseOne`、`PhaseTwo`、`phase1`、`phase2`、0.1 期、0.2 期等期数命名；必须按稳定职责命名。正式文档保留分期标题，内部按功能组织；日期和版本仅为必要证据属性，不维护时间流水账，不把期数泄漏为代码 API 或持久化契约。
6. `DeploymentAnalysisCoordinator` 不得导入具体项目类型实现；`ProjectLanguageInspector` 只负责组合确定性的语言事实检查器。低层语言与构建 Inspector 不得修改调用方提供的拒绝集合，生产包与测试包必须镜像，包依赖不得成环，且不得恢复按阶段或宽泛类别聚合实现的包。`standard.deploy.plan` 不得构造具体适配器，`linux.connection` 不得保存异常或组合会话，`linux-sshd.connection` 不得保存 command 或总会话；禁止以兼容壳保留旧类型。
7. `PackageStructureArchitectureTest` 使用 JDK 编译器 AST、物理路径和生产导入图自动检查文件与顶级类型同名、仓库级顶级类型唯一性、顶级及嵌套枚举语义后缀、禁限用词及封闭例外、资源文件名、复数后缀、缩写、测试后缀、模块根包以下最多三层、五个功能组及合法职责、远程契约和领域模型例外、语言/构建架构/发行版分类轴、禁用包名、全部包依赖环、测试包镜像、职责映射、反向依赖、旧 FQCN、旧物理包、旧 helper 资源路径、已删除包装类以及仅允许的 `AppMain.main` / `WebMain.main` 产品入口。Java 职责规模门禁仅排除 AST 识别的 Javadoc 行，正文、实现注释及嵌入脚本仍计入现有上限，脚本文件上限保持不变；双语文档另由 `JavadocCoverageTest` 负责。门禁不设置总包数或单包类型数量硬上限，不得通过文本豁免隐藏结构回归；通过结果只证明当前本地静态结构，不构成新的 Linux 运行证据。

<a id="phase-1-1-target"></a>

## 8. 1.1 目标结构（已认可、待实施）

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
│  │     ├─ db/                            # [包] 部署配置、应用拓扑、版本和业务记录的数据访问
│  │     ├─ linux/                         # [包] 部署业务远端端口及 helper 适配，经公开 API 使用主体连接
│  │     ├─ secret/                        # [包] 部署秘密的用途、引用及短期注入策略
│  │     ├─ service/                       # [包] 部署业务编排、公开契约实现及任务执行
│  │     │  ├─ agent/                      # [包] 自主部署循环、任务状态、工具执行与审批，独立于 standard
│  │     │  ├─ core/                       # [包] 标准与自主流程共用的部署业务
│  │     │  │  ├─ config/                  # [包] 部署配置、不可变修订、秘密引用和资源绑定
│  │     │  │  ├─ delivery/                # [包] 受管发布、候选健康验证、版本切换和回滚
│  │     │  │  ├─ lifecycle/               # [包] 受管应用的状态、启停、重启及生命周期校验
│  │     │  │  └─ model/                   # [包] 部署、应用、发布与业务状态的私有模型
│  │     │  └─ standard/                   # [包] 标准部署流程，区分分析和执行
│  │     │     ├─ analyze/                 # [包] 静态识别、证据提取、冲突和缺项分析
│  │     │     └─ deploy/                  # [包] 标准计划、适配器、工具链及有界 assistance
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

| 使用方                                | 可依赖的边界                                                                     | 禁止反向渗透                                                      |
| ------------------------------------- | -------------------------------------------------------------------------------- | ----------------------------------------------------------------- |
| APP 的平台实现                        | API、职责明确的 APP 内部模块；`main`/`service` 负责装配                          | 不引用部署/备份私有类型；`linux` 不直接构造平台 UI 或插件引擎     |
| API                                   | 最小必要的公共类型                                                               | 不依赖 APP、Web、插件实现、SSHD 或数据库实现                      |
| `deployment/api`                      | 平台 API 的必要公共契约                                                          | 不依赖或暴露部署私有实现、数据库实体及备份类型                    |
| `deployment/ai`                       | 平台公开 AI 契约、本包的请求与结果类型                                           | 不直接依赖 APP 的 AI 实现、Provider 客户端、`db` 或业务流程       |
| `deployment/db`                       | 平台公开 JDBC/凭据契约、本包的存储映射                                           | 不反向依赖 UI、AI 适配或业务流程，不访问其他 Mod 私有表           |
| `deployment/linux`                    | 平台 Linux/服务器/任务契约及业务窄端口                                           | 不接管 SSHD、连接、指纹或独立救援；不调用 UI 或两个部署引擎       |
| `deployment/secret`                   | 平台秘密/任务契约及业务用途和引用值                                              | 不持有 APP 主密钥，不绕过凭据归属和任务授权，不调用 UI 或业务流程 |
| `deployment/source`                   | 平台 Source/任务契约及业务取材规则                                               | 不接管平台快照寿命，不调用 UI 或部署引擎；Git 固定取材属于本包    |
| `deployment/windows`                  | 平台公开 Source/进程/交互等契约及本机业务适配                                    | 不依赖 app/windows 私有类，不接管平台更新、卸载和窗口隐私         |
| `deployment/service/core`             | 平台 API、部署公开契约及必要业务能力                                             | 不反向依赖 standard 或 agent；具体存储/远端实现经窄端口接入       |
| `deployment/service/standard/analyze` | 平台 API、必要部署业务契约                                                       | 保持确定性静态分析，不依赖 agent 或 UI                            |
| `deployment/service/standard/deploy`  | 标准分析、共用 core、部署 API、所需 ai/db/linux/secret/source/windows 及平台 API | 不依赖 agent 或 UI，普通标准流程保持无 AI 路径                    |
| `deployment/service/agent`            | 共用 core、部署 API、所需 ai/db/linux/secret/source/windows 及平台 API           | 不依赖 standard 或 UI；多轮业务决策和工具执行留在本包             |
| `deployment/ui`                       | 本 Mod 的 service 及平台 UI 契约                                                 | 不直接访问 db 或调用 ai 执行业务                                  |
| `deployment/Mod.java`                 | 本 Mod 的各层及平台注册契约                                                      | 负责装配与生命周期，不把两个引擎互相耦合或承载业务实现            |
| `backup`                              | 平台 API、部署公开 API                                                           | 不依赖 deployment 的任何未导出实现包或私有表                      |
| Web                                   | 后续官网设计自身需要的内容与服务                                                 | 不接入 APP/插件的用户服务器执行链                                 |

备份与部署均采用一个 Mod 主模块，`Mod.java` 位于各自 Java 根包；部署的 core、standard、agent 是 `service` 下的职责包，不再设置独立 runtime 入口模块。包间依赖仍须由架构检查约束，不能因合并构建而取消两个引擎的边界。`deployment/api` 首先是公开契约包，不自动生成独立 JAR；公开类型共享和依赖声明见 [Mod.md](Mod.md#loader-adaptation)。以后只有确需独立构建或隔离依赖时才提取同作者从模块。第三方开发者无需把源码合入本仓库，官网投稿与 APP 执行能力分开建设。

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

作者工具复用 `app/mod/installation` 的包读取/校验能力，以命令行模式运行，不启动 APP。构建配置和 SDK 父 POM 归 `.mvn/mod/` 维护，不新增业务叶子模块；正式源码任务才创建这些文件，AllFile 不提前登记工具类或示例。完整结构门禁映射见 [工程门禁](development/PHASE-1.1.md#129-工程门禁映射)。

当前 app/db/mod/ModDeletionLedger 已实现普通安装版本删除的持久阶段、停用确认、最后 Schema 引用判断、独立 DDL 清理、引用释放和固定摘要归档。账本自身不删除程序文件，推进前核验登记安装目录实际不存在；CLASS 源码不是安装目录。PlatformModDeletion 现已由安装服务持有，接入单版本程序文件清理、可选原包清理及默认已下载页。整 Mod 删除数据库范围现与单版本共用账本，增加全部版本及孤立 Schema、专属诊断、非共享自有凭据和固定导航的原范围清理；ModNavigationPreferences 是导航入口和删除事务的共同持久化实现。安装服务已复用实际运行时日志实例的按所有者删除作用域，阻止旧回填快照重建文件；ModOwnedCleanup 已接整 Mod 登记程序、专属数据根及原日志类别文件的有界审阅和剩余子集清理，原 DELETE_MOD 保存原文件证据并支持跨启动恢复；默认已下载页已接整 Mod 范围确认与继续原清理，PlatformModIntents 在登记已移除时仍展示原 DELETE_MOD；界面分别刷新原包和导航偏好。

`app/db/mod/ModProgramMaintenance` 复用删除和重装的已停止所有者、任务/资源与依赖准入；`ModReinstallationLedger` 保存同版本修复原范围和有序检查点，只提交原版本程序状态/原包摘要及固定终态，不修改数据和选择。账本不执行物理文件替换；`app/service/mod/PlatformModReinstallation` 已由安装服务持有，复用原包读取、原生目录发布和精确范围清理，实际替换、保留旧副本、明确恢复及独立原包清理均已接通。默认已下载重装/旧程序恢复入口及原包页 REINSTALL 核对路由已接入；PlatformModIntents 提供只读原修订停用确认，UI 不以草稿关闭代替。安装版默认启动已通过 PlatformModPrograms 接入同一生命周期，按登记内容验证实际目录，并支持原关闭状态的重启确认。

当前 `app/mod/installation` 已实现原包读取、嵌套 JAR 索引与新目录暂存，直接复用工程集中管理的 Commons Compress 1.26.1；包内 ZIP 视图借用原文件句柄，不生成临时 JAR 副本。读取结果不代表已安装或可启用。`app/service/mod/PlatformModInstallation` 已串联原 INSTALL 记录、原生目录发布、版本登记、留存设置和明确中断核对，CLASS 装配不使用该安装来源；`app/ui/mod/ModOriginalsPane` 已接 JAR/APP 原包扫描、搜索、确认安装、原包删除、留存设置及原操作核对；`app/mod/loader` 使用 Maven 集中管理的 ASM 9.10.1，在原包、原安装恢复、CLASS 构建/发现和启用前共用入口继承与公开签名静态准入；`app/service/mod/ModInstallationCleanup` 为未登记安装的明确撤销捕获和复核原文件范围；原 INSTALL 保存 CANCELLING 后才删除，UI 接原清理核对，完整行为见 [Mod 包校验](Mod.md#package-installation)。

<a id="mod-source-layout"></a>

### 8.4 已确认的 Mod 源码结构与命名

本节适用于 1.1 官方与第三方 Mod 的主 JAR 源码，**结构规则已确认，代码尚未实施**。它不表示完整接口规格或实现验收已经完成，也不改变第 1～7 节当前代码的结构门禁。分发包目录和清单另见 [Mod 包格式](Mod.md#contents-draft)，本文仅规定源码组织。

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
