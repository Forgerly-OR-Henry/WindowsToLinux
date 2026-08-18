# `shared.analyze` 生态化分包修订

## 文档信息

- 文档版本：`1.2.0`
- 文档状态：**已实施（简化命名迁移与本地结构验证完成）**
- 更新日期：2026-08-18
- 正式目标结构：[File.md](../File.md)
- 当前实现状态：生产和测试源码已迁入本文的责任包；SPI、注册表、元数据、策略与组件职责已拆分。Linux 部署链随后的原子迁移已将旧 `Advanced*` 公共模型替换为六种明确的生态服务运行时类型。本次不产生新的 Linux 或产品入口运行证据。

> 实施更新（2026-08-15）：本文件的 1.0.0 设计记录保留原始分阶段边界；实际实施已与 Linux 部署链修订同步完成公共模型的原子替换，因此不再保留 `AdvancedRuntimeKind`、`AdvancedService` 或 `ADVANCED_*` 兼容路径。

> 命名更新（2026-08-18）：分析预览职责包由 `recognition` 简化为 `preview`，`RecognitionPreviewInspector` 同步简化为 `PreviewInspector`；分析行为、支持等级和部署边界不变。

## 1. 目标与边界

`gold.debug.windowstolinux.shared.analyze` 改为“公共流程按稳定职责分包，语言专属能力按技术生态聚合，生态内部再按 `language`、`build`、`framework`、`project` 细分”。该结构用于消除当前类型检查器位置不一致、包级循环依赖、三期语言集中实现和公共辅助类职责过宽的问题。

本次后续代码迁移必须保留以下对外入口的行为与签名：

- `DeploymentAnalysisCoordinator`
- `MixedProjectAnalyzer`
- `ComponentAnalysisRequest`

分析层只读取有界源码并生成静态事实；实际 Maven、Gradle、Cargo、Composer、Bundler、Go、.NET 等构建执行继续归 `shared/linux-sshd.build`。本次不改变数据库结构、支持等级或真实环境验收结论。

## 2. 当前待清理问题

1. `core` 直接装配分布在 `build`、`framework`、`language`、`workload` 中的具体类型检查器，而这些实现反向依赖 `core` 中的检查器契约，形成包级循环依赖。
2. 完整项目类型检查器分散在不同维度包：Java JAR 位于 `language`，Node/Python 位于 `build`，Spring Boot 位于 `framework`，静态站点/容器位于 `workload`，预览与六种三期语言再次位于 `language`。
3. `language.advanced.AdvancedLanguageDeploymentInspector` 通过一个运行时枚举和多个 `switch` 同时处理 Go、Rust、.NET、Kotlin、PHP、Ruby；`language.additional.AdditionalLanguageInspector` 用一个宽泛分支表混合已支持语言和仅预览语言。
4. Maven、Gradle、Node、Python 的低层构建检查器在异常传播、`Optional`、可变拒绝集合和结果命名方面没有统一约定。
5. `DeploymentAnalysisCoordinator` 保存具体数据库迁移策略，Spring Boot 检查器又包含部分重复规则；协调器没有保持纯编排职责。
6. `BoundedProjectMetadata` 同时负责文件读取、ZIP 检查、项目身份、证据和消息创建；`SourceInspection` 同时携带通用源码事实、Maven Wrapper 和数据库脚本事实。
7. `MixedProjectAnalyzer` 同时承担组件编排、根路径校验、资源冲突、数据安全、依赖环和受管身份生成。
8. `build`、`source`、`preview` 等能力缺少与生产包一一对应的直接测试，现有结构边界测试只校验部分文件集合和行数，没有约束包依赖方向。

## 3. 正式目标结构

```text
gold.debug.windowstolinux.shared.analyze
├─ component/
│  ├─ ComponentAnalysisRequest
│  ├─ ComponentConflictValidator
│  ├─ ComponentDependencyValidator
│  └─ MixedProjectAnalyzer
├─ core/
│  └─ DeploymentAnalysisCoordinator
├─ ecosystem/
│  ├─ LanguageInspector
│  ├─ ProjectLanguageInspector
│  ├─ dotnet/
│  │  ├─ build/
│  │  ├─ language/csharp/
│  │  └─ project/service/
│  ├─ go/
│  │  ├─ build/
│  │  ├─ language/
│  │  └─ project/service/
│  ├─ jvm/
│  │  ├─ build/gradle/
│  │  ├─ build/maven/
│  │  ├─ framework/springboot/
│  │  ├─ language/java/
│  │  ├─ language/kotlin/
│  │  ├─ project/jar/
│  │  └─ project/kotlin/
│  ├─ node/
│  │  ├─ build/
│  │  ├─ language/
│  │  └─ project/service/
│  ├─ php/
│  │  ├─ build/
│  │  ├─ language/
│  │  └─ project/service/
│  ├─ python/
│  │  ├─ build/
│  │  ├─ language/
│  │  └─ project/service/
│  ├─ ruby/
│  │  ├─ build/
│  │  ├─ language/
│  │  └─ project/service/
│  └─ rust/
│     ├─ build/
│     ├─ language/
│     └─ project/service/
├─ policy/
│  └─ SourceMutationPolicy
├─ preview/
│  ├─ PreviewLanguageMarkerCatalog
│  └─ PreviewInspector
├─ registry/
│  └─ DeploymentTypeInspectorRegistry
├─ source/
│  ├─ BoundedSourceInspector
│  ├─ SourceInspection
│  └─ metadata/
│     ├─ BoundedMetadataReader
│     └─ ProjectIdentityResolver
├─ spi/
│  ├─ DeploymentTypeInspection
│  └─ DeploymentTypeInspector
└─ workload/
   ├─ container/
   │  └─ ContainerDeploymentInspector
   └─ staticweb/
      └─ StaticWebDeploymentInspector
```

只在存在实际实现时创建目标目录，不预建空包。预览级长尾语言继续由声明式 `PreviewLanguageMarkerCatalog` 管理；某种语言出现独立解析、构建或项目类型规则时，才迁入对应生态。

## 4. 目标依赖方向

```text
component ──→ core
core ───────→ ecosystem, policy, registry, source, spi
registry ───→ ecosystem, preview, spi, workload
preview ─→ source, spi
workload ───→ ecosystem.node.build, source, spi
ecosystem.*.project ─→ 同生态 language/build/framework, source, spi
ecosystem.*.language/build/framework ─→ source
policy ─────→ source
```

- `core` 不得导入任何具体项目类型检查器，由 `registry` 提供经过完整性和重复性校验的固定集合。
- `registry` 只负责装配，不执行源码分析、策略判断或结果汇总。
- `spi` 不依赖 `core`、`registry` 或任何具体生态。
- 生态内允许共享构建和框架事实，但不得复制跨生态机械逻辑，也不得引入发行版、CPU、SSH、SFTP、systemd、容器执行或任意 Shell 能力。
- `workload.staticweb` 可以复用 `ecosystem.node.build` 的 Node 静态构建事实，但 Node 生态不得反向依赖工作负载包。

## 5. 生产代码迁移映射

下表路径均相对于 `gold.debug.windowstolinux.shared.analyze`。没有列为新类的目标不得创建兼容壳；原类型完成迁移后直接删除旧路径。

| 当前类型 | 目标类型或处理方式 |
| --- | --- |
| `build.gradle.GradleProjectInspection` | `ecosystem.jvm.build.gradle.GradleBuildFacts` |
| `build.gradle.GradleProjectInspector` | `ecosystem.jvm.build.gradle.GradleBuildInspector` |
| `build.maven.MavenProjectInspection` | `ecosystem.jvm.build.maven.MavenBuildFacts`；移除 Spring Boot 专属布尔值，由框架检查器解释通用插件事实 |
| `build.maven.MavenProjectInspector` | `ecosystem.jvm.build.maven.MavenBuildInspector` |
| `build.node.NodeProjectInspection` | `ecosystem.node.build.NodeBuildFacts` |
| `build.node.NodeProjectInspector` | `ecosystem.node.build.NodeBuildInspector` |
| `build.node.NodeServiceDeploymentInspector` | `ecosystem.node.project.service.NodeServiceDeploymentInspector` |
| `build.python.PythonProjectInspection` | `ecosystem.python.build.PythonBuildFacts` |
| `build.python.PythonProjectInspector` | `ecosystem.python.build.PythonBuildInspector` |
| `build.python.PythonServiceDeploymentInspector` | `ecosystem.python.project.service.PythonServiceDeploymentInspector` |
| `component.ComponentAnalysisRequest` | 保持原包、名称、字段和校验契约 |
| `component.MixedProjectAnalyzer` | 保持为编排入口；资源/数据冲突提取到 `ComponentConflictValidator`，依赖与环检查提取到 `ComponentDependencyValidator` |
| `core.DeploymentAnalysisCoordinator` | 保持对外包名、类名和 `analyze` 行为；移除具体检查器构造和具体策略正则，改为使用 `registry` 与 `policy` |
| `core.DeploymentTypeInspection` | `spi.DeploymentTypeInspection` |
| `core.DeploymentTypeInspector` | `spi.DeploymentTypeInspector` |
| `framework.springboot.SpringBootDeploymentInspector` | `ecosystem.jvm.framework.springboot.SpringBootDeploymentInspector` |
| `language.ProjectLanguageInspector` | `ecosystem.ProjectLanguageInspector`，通过新的 `ecosystem.LanguageInspector` 固定组合全部语言事实检查器 |
| `language.additional.AdditionalLanguageInspector` | 删除；已支持语言进入独立生态，仅预览标记进入 `preview.PreviewLanguageMarkerCatalog` |
| `language.advanced.AdvancedLanguageDeploymentInspector` | 删除并拆为 `GoServiceDeploymentInspector`、`RustServiceDeploymentInspector`、`DotNetServiceDeploymentInspector`、`KotlinServiceDeploymentInspector`、`PhpServiceDeploymentInspector`、`RubyServiceDeploymentInspector`，分别进入对应生态的 `project` 包 |
| `language.java.JavaJarDeploymentInspector` | `ecosystem.jvm.project.jar.JavaJarDeploymentInspector` |
| `language.java.JavaLanguageInspector` | `ecosystem.jvm.language.java.JavaLanguageInspector` |
| `language.node.NodeLanguageInspector` | `ecosystem.node.language.NodeLanguageInspector` |
| `language.preview.RecognitionPreviewInspector` | `preview.PreviewInspector` |
| `language.python.PythonLanguageInspector` | `ecosystem.python.language.PythonLanguageInspector` |
| `source.BoundedProjectMetadata` | 删除；文件/ZIP 操作进入 `source.metadata.BoundedMetadataReader`，项目 ID 进入 `source.metadata.ProjectIdentityResolver`，`required` 改为直接构造 `LocalizedMessage`，证据由使用方按其领域直接构造 |
| `source.BoundedSourceInspector` | 保持包名和类名；只负责有界、安全、只读的源码遍历和文本采集 |
| `source.SourceInspection` | 保持包名和类名；改为通用路径与带来源文本事实，移除 Maven Wrapper 和数据库策略专属字段 |
| `workload.container.ContainerDeploymentInspector` | 保持原包和类名，继续处理语言无关的单容器工作负载 |
| `workload.staticweb.StaticWebDeploymentInspector` | 保持原包和类名；复用 `ecosystem.node.build.NodeBuildFacts`，不复制 Node 构建解析 |

三期六种生态拆分时新增并独立测试以下低层事实检查器：

- `ecosystem.go.language.GoLanguageInspector` 与 `ecosystem.go.build.GoBuildInspector`
- `ecosystem.rust.language.RustLanguageInspector` 与 `ecosystem.rust.build.CargoBuildInspector`
- `ecosystem.dotnet.language.csharp.CSharpLanguageInspector` 与 `ecosystem.dotnet.build.DotNetBuildInspector`
- `ecosystem.jvm.language.kotlin.KotlinLanguageInspector`，构建事实复用 JVM Gradle 检查器
- `ecosystem.php.language.PhpLanguageInspector` 与 `ecosystem.php.build.ComposerBuildInspector`
- `ecosystem.ruby.language.RubyLanguageInspector` 与 `ecosystem.ruby.build.BundlerBuildInspector`

低层 `LanguageInspector` 和 `*BuildInspector` 只返回不可变事实或抛出有界读取异常，不接收、保存或修改调用方的 `List<RejectionReason>`。缺失、冲突和安全停止原因由项目类型检查器或 `policy` 转换。

## 6. 测试迁移映射

测试包必须镜像生产包，不保留旧包测试或只为兼容旧类型而存在的测试壳。

| 当前测试 | 目标测试或处理方式 |
| --- | --- |
| `component.MixedProjectAnalyzerTest` | 保持原测试；把资源/数据冲突和依赖/环用例分别下沉到两个新 Validator 测试 |
| `core.DeploymentAnalysisCoordinatorTest` | 保持核心流程测试，新增“注册表完整、无重复、未知类型失败”边界 |
| `core.SpringBootDeploymentAnalysisTest` | 移为 `ecosystem.jvm.framework.springboot.SpringBootDeploymentInspectorTest` |
| `language.advanced.AdvancedLanguageDeploymentInspectorTest` | 删除并拆为 Go、Rust、.NET、Kotlin、PHP、Ruby 六个项目类型测试 |
| `language.java.JavaLanguageInspectorTest` | 移为 `ecosystem.jvm.language.java.JavaLanguageInspectorTest` |
| `language.node.NodeLanguageInspectorTest` | 移为 `ecosystem.node.language.NodeLanguageInspectorTest` |
| `language.python.PythonLanguageInspectorTest` | 移为 `ecosystem.python.language.PythonLanguageInspectorTest` |
| `workload.container.ContainerDeploymentInspectorTest` | 保持原包和测试 |
| `workload.staticweb.StaticWebDeploymentInspectorTest` | 保持原包和测试；验证只复用 Node 构建事实 |

必须新增构建事实、六种新语言事实、`SourceMutationPolicy`、`PreviewLanguageMarkerCatalog`、`DeploymentTypeInspectorRegistry`、`BoundedMetadataReader`、`ProjectIdentityResolver`、`ComponentConflictValidator` 和 `ComponentDependencyValidator` 的直接测试。结构边界测试必须从固定文件名检查扩展到目标包位置、禁用包名和单向导入规则。

## 7. 实施阶段

### 7.1 SPI 与装配解耦

1. 将类型检查契约原子移动到 `spi`。
2. 新增唯一 `DeploymentTypeInspectorRegistry`，接管完整性、重复项目类型和类型匹配校验。
3. `DeploymentAnalysisCoordinator` 只依赖注册表返回的窄契约，不再导入具体实现。

### 7.2 现有生态归位

1. 迁移 JVM、Node、Python 的语言与构建事实，统一 `*BuildFacts` 命名。
2. 将 Spring Boot、普通 JAR、Node 服务和 Python 服务归入各自生态。
3. 静态站点和容器继续留在 `workload`，现有运行建议与安全行为保持不变。

### 7.3 六种三期语言拆分

1. 建立 Go、Rust、.NET、Kotlin、PHP、Ruby 的独立语言、构建和项目类型实现。
2. 删除 `AdvancedLanguageDeploymentInspector` 与 `AdditionalLanguageInspector`，不保留委托壳。
3. 首轮继续使用现有 `AdvancedRuntimeKind` 和 `ADVANCED_*` 公共模型，避免把本包整理扩大到 UI、部署和远程协议。

### 7.4 源码、策略与组件职责收敛

1. 拆除 `BoundedProjectMetadata`，使 `SourceInspection` 恢复通用源码事实。
2. 将通用数据库迁移/schema mutation 停止规则移入 `SourceMutationPolicy`，删除 Spring Boot 中已经被全局策略覆盖的重复检查。
3. 从 `MixedProjectAnalyzer` 提取冲突和依赖验证器，保留其组件级编排入口。

### 7.5 测试、架构门禁与文档收尾

1. 原子迁移测试包并补齐直接测试。
2. 增加包依赖、禁用旧包、测试镜像和 `core` 纯协调职责门禁。
3. 更新 `File.md` 当前落地状态和本文件清单；只有全部门禁通过后才标记迁移完成。

## 8. 验收清单

- [ ] `core` 只保留 `DeploymentAnalysisCoordinator`，且不导入具体项目类型实现。
- [ ] `spi` 与 `registry` 已分离，注册表拒绝缺失、重复和类型不匹配实现。
- [ ] 所有已支持语言进入对应技术生态；生产源码中不存在 `language.advanced` 或 `language.additional`。
- [ ] Spring Boot、普通 JAR、Kotlin、Maven、Gradle 统一位于 JVM 生态，且 Maven/Gradle 分析逻辑没有按语言复制。
- [ ] 低层语言和构建 Inspector 不修改外部拒绝集合，所有结果为不可变事实。
- [ ] `BoundedProjectMetadata` 已删除，源码读取、项目身份、策略和证据职责不再集中。
- [ ] `MixedProjectAnalyzer` 只保留编排，资源/数据冲突与依赖图验证具有独立测试。
- [ ] 生产包和测试包镜像，旧类型和兼容壳静态搜索结果为零，包级依赖无环。
- [ ] `DeploymentAnalysisCoordinator`、`MixedProjectAnalyzer`、`ComponentAnalysisRequest` 对外行为与签名保持不变。
- [ ] JDK 21 下 `mvn.cmd -B -ntp -o verify` 通过全部 28 个模块。
- [ ] `git diff --check` 通过；正式文档与当前落地状态一致。

本迁移是本地结构重构，不产生新的 Linux、systemd、SSH、构建发布或运行支持证据，不需要以真实目标机执行替代本地门禁；任何后续运行行为变化必须独立评审和验收。

## 9. 状态维护

- 开始代码迁移时将状态改为“实施中”，只勾选已有源码和测试证据支持的项目。
- 迁移中断或遇到跨模块模型阻塞时记录具体未完成项，不以兼容壳或重复路径暂时宣称完成。
- 全部验收完成后将状态改为“已实施”，同步把 `File.md` 的当前落地说明改为目标结构已经落地，并在两个文档中追加同日版本记录。

## 10. 文档版本记录

| 版本 | 日期 | 状态 | 说明 |
| --- | --- | --- | --- |
| 1.2.0 | 2026-08-18 | 已实施（本地结构验证完成） | 将分析预览包和检查器简化为 `preview.PreviewInspector`；不改变分析行为、公共方法、支持等级或运行证据。 |
| 1.1.0 | 2026-08-15 | 已实施（本地结构验证完成） | 完成生产与测试包迁移、SPI/注册表/元数据/策略职责拆分，并与 Linux 部署链一起原子移除旧 `Advanced*` 公共模型；JDK 21 离线 28 模块验证通过，真实 Linux 结论不变。 |
| 1.0.0 | 2026-08-15 | 已批准，待实施 | 确认 `shared.analyze` 的生态化目标结构、单向依赖、完整生产/测试迁移映射、实施阶段和验收条件；未修改源码、测试、POM、公共模型或运行行为。 |
