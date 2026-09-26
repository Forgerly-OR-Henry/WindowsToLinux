# WindowsToLinux 发布与安装布局

- 状态：本文描述 **1.1 已确认发布目标**；平台源码正在改造。按 2026-09-26 用户决定，本次完成源码迁移和 CLASS 本机验收，S7 暂缓，JAR/APP/SDK 发行打包待源码稳定后进行。
- 职责：维护 APP/JAR 交付物、发布目录、启动配置、更新边界和官网服务范围；源码模块、包和命名由 [File.md](File.md) 维护，三种模式的 Mod 加载规则见 [Mod 文档](Mod.md#loading-modes)。
- 产品方向：WindowsToLinux 是在 Windows 上操作 Linux 的开放 APP 平台，主体提供界面、连接、AI、任务、安全和 Mod 管理；部署与备份由官方 Mod 提供。
- [开发总纲](development/DEVELOPMENT.md) · [1.1 工程](development/PHASE-1.1.md) · [Mod 包格式](Mod.md)

<a id="release-layout"></a>

## 1. APP 模式发布目录布局

本文描述发布后的程序布局，与 [源码目录](File.md#phase-1-1-tree)、`.wtlmod` 的包内目录分别维护。

```text
WindowsToLinux/                            # 安装根目录
├─ WindowsToLinux.exe                      # jpackage 生成的 Windows 启动器
├─ app/                                   # 平台自身的程序及启动配置
│  ├─ WindowsToLinux.cfg                   # 启动类、classpath 和 JVM 参数等配置
│  ├─ main.jar                            # 软件主程序；文件名为示意
│  └─ appapi.jar                         # APP 公开 API；文件名为示意
├─ lib/                                   # 仅存放平台主体使用的第三方依赖
│  ├─ dependency-a.jar                    # 主体依赖示例
│  └─ dependency-b.jar                    # 主体依赖示例
├─ runtime/                               # 随软件交付的 Java 运行环境
├─ .mod-staging/                          # 平台内部安装暂存
├─ mod-zip/                               # 带扩展名的原始 Mod 包，含按设置保留的已解压包
└─ mod/                                   # 已解压安装的官方或第三方 Mod
```

<a id="mod-directories"></a>

## 2. 目录职责与边界

- 保留 jpackage 的 `app/` 和 `runtime/` 布局；平台主体依赖使用与 `app/` 并列的根目录 `lib/`。实际主体 JAR 数量及名称由后续模块构建结果确定，不因示例写了两个文件就限定为两个 JAR。
- 根目录 `lib/` 只管理平台主体的第三方依赖。每个 Mod 的主 JAR 和同作者配套从包位于该 Mod 的 `main/`，第三方依赖位于该 Mod 的 `lib/`，详见 [Mod 包结构](Mod.md#contents-draft)。Mod 依赖由插件加载器管理，不统一混入主体 classpath。
- APP 模式的 `mod-zip/` 和 `mod/` 均位于 EXE 所在目录，与 `app/`、`lib/` 并列；JAR 模式则与主程序 JAR 并列。`mod-zip/` 存放带扩展名的原始包；`mod/` 只放解压后的 Mod，按 `mod/<稳定 ID>/<发布版本>/` 组织。不要与 jpackage 模块化打包可能使用的 `app/mods/` Java 模块目录混淆。
- 包的两种来源、原始包管理、手动/自动解压和原包留存设置统一见 [Mod 获取与解压流程](Mod.md#package-acquisition)。JAR/APP 不扫描开发工作区；随发行物预装且已展开的官方 Mod 按已安装插件处理。
- 程序根下的 `.mod-staging/` 是平台内部安装暂存目录，APP/JAR 分别按 EXE/主 JAR 定位；其中内容不作为已安装 Mod 展示或加载。完成和异常后的处理见 [Mod 安装恢复](Mod.md#package-installation)。
- 安装位置不代表 Mod 已经启用；启停、版本选择及删除条件统一见 [Mod 生命周期](Mod.md#mod-lifecycle)。
- 本图聚焦程序交付，运行数据定位引用 [Runtime.md](Runtime.md#desktop-data)，新工程的 H2 与 Schema 生命周期引用 [Mod.md](Mod.md#mod-database)。发行物不携带用户运行数据库、日志或真实凭据；整个 APP 的更新与卸载边界见[交付与维护](#app-maintenance)。

<a id="jar-layout"></a>

## 3. JAR 模式发布目录布局

JAR 模式以**主程序 JAR 所在目录**为程序根目录，平台 `lib/`、Mod 原包目录 `mod-zip/` 和解压安装目录 `mod/` 与主 JAR 并列：

```text
WindowsToLinux/                            # 主程序 JAR 所在目录
├─ main.jar                               # 主程序 JAR；文件名为示意
├─ appapi.jar                            # APP 公开 API；文件名为示意
├─ lib/                                   # 平台主体第三方依赖
│  ├─ dependency-a.jar                    # 主体依赖示例
│  └─ dependency-b.jar                    # 主体依赖示例
├─ .mod-staging/                          # 平台内部安装暂存
├─ mod-zip/                               # 带扩展名的原始 Mod 包
└─ mod/                                   # 解压安装或随发行物预装的 Mod
```

主体自身模块的 JAR 数量与名称仍由构建结果确定；不能把所有 JAR 都作为 Mod 扫描。JAR 模式与 APP 模式共用 Mod 安装和加载规则，程序与 Mod 安装根分别锚定主程序 JAR 与 EXE。JAR 模式的运行 `data/` 则独立锚定数据库模块 JAR（如 `db.jar`）的所在目录；程序根和数据根不必相同，均不以启动命令的当前工作目录为基准。

开发用 JAR 发行物采用 `java -jar` 启动，由构建生成主 JAR 的入口和所需依赖的相对 `Class-Path`；仅在旁边放一个 `lib/` 目录不会自动加载其中所有 JAR。Mod 仍经插件加载器加载，不加入主体依赖清单。[JAR Class-Path 规范](https://docs.oracle.com/en/java/javase/21/docs/specs/jar/jar.html#class-path-attribute)

本节说明程序与 Mod 的目录位置；[三种模式的数据根](Mod.md#mod-data-root)也已确认，H2 文件名及内部目录职责见对应规范。

<a id="launch-config"></a>

## 4. APP 模式启动配置要求

启动 classpath 明确包含根目录 `lib/` 中的平台依赖；仅存在这个目录不会使启动器自动发现依赖。以 JDK 21 的普通 classpath 启动方式为例，`app/WindowsToLinux.cfg` 中的相关配置可以表达为：

```ini
[Application]
app.classpath=$APPDIR\main.jar
app.classpath=$APPDIR\appapi.jar
app.classpath=$ROOTDIR\lib\dependency-a.jar
app.classpath=$ROOTDIR\lib\dependency-b.jar
app.mainclass=example.Main
```

此段仅说明路径关系，不是现有项目可直接覆盖使用的完整配置。`example.Main` 和 JAR 文件名均为占位示例；实际打包时还需保留所需的 JVM 参数等内容。`$APPDIR` 由启动器展开为 `app/`，`$ROOTDIR` 展开为安装根目录，不依赖用户启动时的工作目录。上述示例使用 `app.mainclass` 的 classpath 模式；不直接套用到 `app.mainjar`（`-jar`）或 `app.mainmodule` 的启动模式。

<a id="5-后续打包与验证流程"></a>

## 5. 交付物范围

首次交付便携 APP 包和开发用 JAR 包：便携包包含 EXE、Java 运行环境、两个已解压的官方 Mod 和救援资源，普通使用无需官网账号。当前属于受控开发，本期终点为本地验收交付，不要求公开发布、签名或兼容历史公开 API。正式安装器、网站、在线商店和首次公开发布条件后置；本地包明确标记开发版本。作者示例和工具见 [Mod.md](Mod.md#developer-tooling)。

构建步骤与发行物验收见 [1.1 开发文档](development/PHASE-1.1.md#packaging-steps)。

<a id="app-maintenance"></a>

## 6. 整个 APP 的更新与卸载边界（已认可、待实施）

首版手动更新，不开发后台自动更新。更新只替换程序文件，保留 `mod/`、`mod-zip/` 和运行 `data/`；实际数据路径仍按 [Runtime.md](Runtime.md#desktop-data)定位，不能把 JAR 模式的数据库模块目录误当作可整体清除的程序目录。平台数据库升级与程序文件替换分别管理，升级状态由[平台表](Mod.md#platform-database-tables)登记。

未来卸载器默认保留这些用户内容，提供明确的“同时清除本地内容”选项并展示范围；用户导出的独立备份不自动删除。上述边界不代表已经实现更新/卸载执行器或数据库升级程序。

<a id="website-service"></a>

## 7. 官网与 Mod 商店服务范围

- 官网提供软件介绍、教程、熟悉流程的教学演示和公开下载。演示使用示例数据，操作按钮不实际部署、备份或连接用户 Linux。
- 下载者无需账号、邮箱或其他个人资料，下载后即可使用；不围绕下载建立用户画像、广告或营销联系。
- 插件贡献者通过官网上传插件包，必须提供联系邮箱，便于作者与维护者沟通问题；不要求向 Git 仓库提交 PR。
- 官网后台收集投稿，维护者可以查看收到的包及联系信息。联系邮箱用于投稿沟通，不因此要求普通下载者注册。
- 投稿经人工审核后上架，邮箱仅后台可见；拒绝或撤回的投稿在 90 天后清理邮箱，已上架项目维护期间保留。下载无需登录不代表后台可匿名管理。
- 商店使用官方 HTTPS 服务，提供 Mod 列表、版本信息和包下载，目录附带包摘要并进行官方签名，由 APP 验证。下载不要求账号；不自动下载缺失依赖，也不自动升级或切换用户已经安装的版本。下载后的本地行为只维护在 [Mod 获取与解压流程](Mod.md#package-acquisition)。

    0.6 期的多用户执行服务台、生产账号与工作区规划已由本方向取代。网站不是未来第三方插件的执行环境；第三方包在 APP 使用。投稿收集意味着网站可能需要接收服务，因此不把“纯静态网站”或某种后端技术栈提前定为要求。

<a id="build-contract"></a>

## 8. 本地构建与交付契约（规格已定，待验证）

### 8.1 构建身份与依赖

根构建沿用 `gold.debug.windowstolinux` groupId，工程开发版本为 `1.1.0-SNAPSHOT`，不是公开版本承诺。平台 API 开发契约版本为 `1.0.0-dev.1`，独立于 Maven 工程版本；两个官方 Mod 清单版本为 `1.1.0-dev.1`、schemaVersion 为 `1`，platformApiRange 为 `[1.0.0-dev.1,1.0.0)`，备份依赖部署的范围为 `[1.1.0-dev.1,1.1.0)`。后续受控修改同步更新这些声明和测试，不能由字符串相似推断兼容。

| 构建项         | artifactId / 最终文件                                                                          | 装配关系                                                                                |
| -------------- | ---------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------- |
| API            | `windowstolinux-appapi` / `appapi.jar`                                                         | 单独的公开契约叶子模块，仅依赖所需 JDK 模块。                                           |
| APP 各叶子模块 | `windowstolinux-app-<模块名>` / `<模块名>.jar`                                                 | ai、db、linux、main、mod、secret、service、source、ui、windows；依赖方向由 File 维护。  |
| 官方 Mod       | `windowstolinux-mod-deployment` / `deployment.jar`；`windowstolinux-mod-backup` / `backup.jar` | 各自独立主 JAR；平台 API 为 provided，备份对部署的编译依赖为 provided，只引用导出 API。 |
| 聚合模块       | 根、app、mod 的 POM                                                                            | 不生成运行 JAR，不把后置 Web 加入新活动根构建。                                         |

平台入口固定 `gold.debug.windowstolinux.app.main.AppMain`。APP 的平台自有 JAR 全部放 `app/`，JAR 发行物的平台自有 JAR 全部与 main.jar 并列；平台第三方依赖都在根 `lib/`。主程序按普通 classpath 运行，不使用 fat JAR 或 JPMS 启动。独立模块自身带资源，救援脚本等静态资源在 linux.jar；需要可执行浏览器文件的救援运行依赖由构建准备到 `app/recovery/`（JAR 发行物为 `recovery/`），使用构建锁定的 Playwright 配套 Chromium，不依赖用户首次救援时下载。

构建生成固定顺序的主体 classpath 清单、依赖坐标与 SHA-256 清单，不手工改启动配置。JAR 的 Manifest 指定 Main-Class 和相对 Class-Path，路径按 JAR 规范 URL 编码；APP 生成配置逐项引用 `$APPDIR` 自有 JAR 和 `$ROOTDIR/lib` 依赖。运行时 API 与主体依赖只各加载一份，官方 Mod 不进入主体 classpath。两个官方包预先经过校验并展开到 `mod/<id>/<version>`，初始开关仍为关闭；发行包不带运行数据库或替用户建立配置。

前面的 `appapi.jar`、`dependency-a.jar` 图示只说明目录关系，实际文件以本节 `appapi.jar` 和构建依赖清单为准。JAR 的 data 始终以 db.jar 所在目录为锚点，APP 的 data 按 EXE 目录；所有路径在不同工作目录及含空格目录启动时复验。

### 8.2 作者构建和校验工具

当前源码已提供 `.mvn/wtlmod-author-parent/pom.xml` 的 CLASS 构建接线，版本暂随现有工程为 `0.1.0-SNAPSHOT`。Mod 主模块通过相对路径继承该独立 POM，并显式声明平台 API 的 provided 依赖；配套模块保持普通 Maven 配置。`initialize` 的 `development-inputs` 目标使用 Maven 原会话捕获解析结果并撤销旧描述，`process-classes` 的 exec 子进程调用 `ModPackageTool describe`。真实 reactor 已验证可直接使用 app/mod 的编译输出，不要求预先打包或安装工具 JAR；工具不进入业务 Mod 运行依赖。当前仅 describe 已实现，下面 pack/verify、SDK 分发与 package 绑定仍是 S7 目标。

`wtl.workspace` 构建默认采用 Maven 明确的多模块根，也可用同名属性指定；运行 APP 时仍必须明确提供该 VM 参数。CLASS 的父 POM、主/从模块 POM、Maven 配置和资源过滤文件均须位于该工作区，额外维护的构建配置可用 `wtl.buildInputs` 逗号列表声明。`wtl.authorArtifacts` 使用完整 `groupId:artifactId:jar:version` 坐标；CLASS 配套必须是同 groupId、当前 reactor 中无独立 mod.json 的普通模块，不接受 classifier 猜测输出。provided/test 依赖不进入私有库，API 非 provided、H2/宿主运行依赖、其他 Mod 运行依赖及未声明配套均失败。需执行 `process-classes`；仅 compile 或编译失败时不会留下可用的旧开发描述。

作者使用独立 `wtlmod-author-parent` POM（同 groupId、同工程开发版本），包含 JDK 21 编译、资源处理、开发描述生成和 package 绑定；该 POM 不继承整个 APP reactor、不作为业务 Mod 运行依赖。作者通过 Maven provided 依赖平台 API，自行声明第三方依赖；同作者配套 JAR 在构建属性 `wtl.authorArtifacts` 中以完整 Maven 坐标列举，其余有效 runtime 依赖进入 lib。平台 API、H2、宿主依赖和 provided 的其他 Mod 不打入包，冲突在构建时失败。

工具主类固定为 `gold.debug.windowstolinux.app.mod.installation.ModPackageTool`，使用 app/mod 现有安装校验职责，不新增 Maven 业务模块。工具运行依赖随 SDK 的 tool 目录提供，使用与 APP 相同的版本/摘要；它们是构建工具 classpath，不进入作者 Mod 的 main/lib。

```text
java -cp "<SDK>/tool/*" gold.debug.windowstolinux.app.mod.installation.ModPackageTool describe --input <构建生成的输入JSON>
java -cp "<SDK>/tool/*" gold.debug.windowstolinux.app.mod.installation.ModPackageTool pack --input <构建生成的输入JSON> --output <目标.wtlmod>
java -cp "<SDK>/tool/*" gold.debug.windowstolinux.app.mod.installation.ModPackageTool verify --package <目标.wtlmod>
```

输入 JSON 固定字段 `formatVersion`（1）、`workspace`、`buildInputs`、`manifest`、`mainJar`、`authorJars`、`libraries`、`readme`、`license`、`changelog`（可选）、`mainOutput`、`authorOutputs`；文件路径均为绝对路径，数组可以为空，无未知键和重复键，未使用的 changelog 省略。`workspace` 为明确工作区，`buildInputs` 是 Maven 提供的实际配置文件集合，算法见 [CLASS 开发描述](Mod.md#72-class-开发描述)。mainJar 为待打包主产物；describe 阶段允许 mainJar、authorJars、README/LICENSE 尚不存在，只验证这些字段的路径语法，并必须验证编译输出及处理后的资源。describe 结果的 contentSha256 是生成描述文件本身的 SHA-256，不是发行包内容摘要。作者原始清单的 mainJar 相对包路径保持不变，构建复制主产物到该路径，不要求物理 Maven JAR 同名。

`mvn package` 通过 Maven exec 插件的 Java 子进程调用同一工具：process-classes 后 describe，package 后 pack/verify；不要求作者安装 Python。工具只处理输入和输出，不能安装或启用 Mod、打开平台运行库或调用入口。成功返回退出码 0，输入错误 2，I/O/校验失败 3；输出 UTF-8 JSON 结果，包含 command、success、artifact、contentSha256 和 problems 数组，诊断走 stderr。独立 verify 与 APP 安装使用相同规则；源码生成、打包和校验规则不能维护三份实现。

作者示例是独立 Maven 项目，提供最小 Swing/JDBC 闭环及依赖提供方/消费方两包验证夹具。CLASS 调试在受验证的平台开发工作区使用对应模块输出；第三方独立项目可先生成包后在 JAR/APP 中验证，不要求把源码合入官方仓库。

### 8.3 便携包、JAR 和手动更新验收

源码阶段使用根 `mvn verify` 验证新活动模块，`mvn package -Pdistribution` 由构建生成 `target/distribution/jar/`、`target/distribution/app/`、`target/distribution/sdk/` 和对应 ZIP、SHA-256 清单；这只是目标命令，当前旧构建尚无该 profile。使用 JDK 21 jpackage app-image，内置完整 Windows JRE；首轮不做运行模块裁剪，避免 Swing、JDBC、加密及救援依赖遗漏。Windows x64 是首轮交付架构，不据此宣称 ARM64 已验收。

打包必须验证：解压即用、没有本机 Maven 缓存依赖、无业务 Mod 时主体可用、官方 Mod 默认未启用、CLASS/JAR/APP 根目录正确、资源和救援浏览器已随包提供。发行物内容白名单为平台 JAR/lib、运行环境、救援运行依赖、官方 Mod、作者工具/示例和说明及许可证；排除 history-src、源码工作区、.git、IDE 状态、运行 data、暂存操作、日志、凭据和缓存。

首版手动更新说明基于两个目录进行：正常退出 APP；在新目录解压新程序；保留并按路径说明迁入原 mod、mod-zip、data，再从新程序启动核对。同一 ID/版本的官方 Mod 不静默覆盖已有用户内容，附带新版也不自动启用或改选。db-access.bin 随 data 一并保留，受同一 Windows 用户和本机 DPAPI 约束；跨电脑或跨 Windows 账户搬迁本地 H2 不在首轮承诺范围，应用业务跨服务器迁移仍由备份 Mod 提供。程序回退须匹配 H2 状态，不把旧 SQLite 程序指向新 H2。公开下载、签名和正式安装器留在后置清单，不阻塞本地包交付。

## 9. 技术依据

[jpackage 生成应用镜像及发布前调整说明](https://docs.oracle.com/en/java/javase/21/jpackage/packaging-overview.html)、[从已有镜像制作安装包的参数](https://docs.oracle.com/en/java/javase/21/docs/specs/man/jpackage.html)、[OpenJDK 21 启动器路径宏](https://github.com/openjdk/jdk21u/blob/master/src/jdk.jpackage/share/native/applauncher/AppLauncher.cpp)、[启动参数处理](https://github.com/openjdk/jdk21u/blob/master/src/jdk.jpackage/share/native/applauncher/JvmLauncher.cpp)。配置解释以实际使用的 JDK 版本和生成配置为准，参考资料不代表本项目已经通过打包运行验收。
