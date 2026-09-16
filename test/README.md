# 部署测试夹具

源码服务按 **12 种语言、25 个语言/构建工具组合**组织，每个组合固定 **3 组正常部署 + 2 组失败部署**，共 125 组。这里是独立的示例项目，不属于根 Maven reactor；自动化验收代码位于各模块的 `src/test`。

## 多文件与库协作约定

全部样例通过入口、配置、请求处理、业务计算和数据模型形成真实调用链，至少三个非入口模块包含实际逻辑。C/C++ 使用自定义头文件及多个编译单元，构建一个可执行目标；其他语言采用包、模块、类型和自动加载。每组须独立复制、安装、构建和运行，不引用相邻场景。

包管理器组合使用运行依赖：Spring Boot Web/Jackson/Validation、Kotlin Gradle Gson、Node Zod、Python jsonschema、Go chi、Rust Serde/serde_json、C# Newtonsoft.Json、PHP Composer Opis JSON Schema、Ruby Bundler Rack/WEBrick。原生 JDK、kotlinc、PHP CLI、Ruby CLI 和 CMake 保持内部模块及标准库边界。

正常组提供 `GET /api/summary?values=2,3,5`，返回 `status`、`items`、`total`，示例总和为 10。未指定参数使用 `[1,2,3]`；显式空值、非整数、超出 1—20 个元素或 0—10000 数值范围返回 HTTP 400。根路径响应、`PORT`、`FIXTURE_LABEL` 和健康失败 503 保留。

依赖清单与锁文件成套维护，传递依赖及摘要由真实工具生成。Python 3.11 元数据同步依赖，pip 哈希覆盖目标 Linux 分发文件。分别记录静态检查、安装/构建、本地 HTTP 和实机发布结果，未执行项保持待验证。

## 五组场景

场景目录统一使用 `<expected-result>-<function>`：`success-` 表示预期部署成功，`failure-` 表示预期部署失败或被阻断。前缀描述部署结果；自动化测试在正确验证这些预期时都应通过。

| 目录 | 预期结果 | 验证内容 |
| --- | --- | --- |
| `success-deployment-smoke` | 正常部署，HTTP 200 | 多模块服务的识别、构建、启动与访问；响应 `deployment-smoke-ok` |
| `success-json-api` | 正常部署，HTTP 200 | `application/json` 响应、结构化数据和 UTF-8/长度处理；Spring Boot 同时保留报价 API 与其单元测试 |
| `success-runtime-config` | 正常部署，HTTP 200 | 读取运行时 `FIXTURE_LABEL`；未提供时返回 `runtime-config-default`，提供时返回指定值 |
| `failure-health-rollback` | 构建与启动正常，候选健康失败 | HTTP 返回 503；先发布同组合的正常组，再以相同应用身份和健康规则发布此候选，验证恢复旧版本 |
| `failure-configuration-rejected` | 部署前被配置准入阻断 | 按实际构建工具缺失锁文件/Wrapper，或提供不支持的版本、打包方式；具体分配见下表 |

失败按真实问题分配，不能把缺失输入或负向候选计为成功部署。`failure-configuration-rejected` 指不能进入部署计划：实际结果可能是 `REQUIRES_INPUT` 或 `REJECTED`；`failure-health-rollback` 则应通过源码分析，在运行阶段被 HTTP 健康检查拒绝。新夹具不以编译错误冒充健康回滚。

## 语言与构建工具

| 语言 | 构建工具/方式 | 正常组 | 失败组 | 合计 |
| --- | --- | ---: | ---: | ---: |
| Java | gradle, jdk, maven-wrapper, maven | 12 | 8 | 20 |
| JavaScript | npm, pnpm, yarn | 9 | 6 | 15 |
| TypeScript | npm, pnpm, yarn | 9 | 6 | 15 |
| Python | pip, pipenv, poetry, uv | 12 | 8 | 20 |
| Go | gomodule | 3 | 2 | 5 |
| Rust | cargo | 3 | 2 | 5 |
| C# | dotnetsdk | 3 | 2 | 5 |
| Kotlin | gradle, kotlinc | 6 | 4 | 10 |
| PHP | composer, phpcli | 6 | 4 | 10 |
| Ruby | bundler, rubycli | 6 | 4 | 10 |
| C | cmake | 3 | 2 | 5 |
| C++ | cmake | 3 | 2 | 5 |

Java 的 Maven/Spring Boot 从原来的 24 个顶层夹具收敛为 5 组；Maven Wrapper 与 Gradle 独立列为构建方式，各自也是 5 组。重复的 Hello、生命周期、断连及快照令牌专用应用不再各占目录；相关产品验收类仍保留。C++ 继续位于 `c/cmake/cpp-service`，TypeScript 位于 `node/<tool>/typescript-service`，通过服务类型区分源码语言。

本矩阵覆盖全部源码服务构建身份。预构建 JAR、静态站点与容器是另外的工作负载验收，继续由 `TypedAcceptanceFixture` 等测试辅助类提供；仅识别的预览语言不列入可部署源码矩阵。

## 每个组合的配置失败点

每条路径下都包含上述五组，路径中的语言、工具和服务类型共同确定一个组合。[matrix.json](matrix.json) 是自动化覆盖清单。

| 组合路径 | 配置错误组的失败点 |
| --- | --- |
| [c/cmake/cpp-service](c/cmake/cpp-service) | 缺少 `CMakePresets.json` |
| [c/cmake/http-service](c/cmake/http-service) | 缺少 `CMakePresets.json` |
| [csharp/dotnetsdk/http-service](csharp/dotnetsdk/http-service) | 缺少 `packages.lock.json` |
| [go/gomodule/http-service](go/gomodule/http-service) | 缺少 `go.sum` |
| [java/gradle/spring-boot](java/gradle/spring-boot) | 缺少 `gradle/wrapper/gradle-wrapper.jar` |
| [java/jdk/http-service](java/jdk/http-service) | Java 版本声明无效 |
| [java/maven-wrapper/spring-boot](java/maven-wrapper/spring-boot) | 使用不支持的 WAR 打包 |
| [java/maven/spring-boot](java/maven/spring-boot) | 使用不支持的 WAR 打包 |
| [kotlin/gradle/http-service](kotlin/gradle/http-service) | 缺少 `gradle.lockfile` |
| [kotlin/kotlinc/http-service](kotlin/kotlinc/http-service) | Kotlin 编译器版本声明无效 |
| [node/npm/http-service](node/npm/http-service) | 缺少 `package-lock.json` |
| [node/npm/typescript-service](node/npm/typescript-service) | 缺少 `package-lock.json` |
| [node/pnpm/http-service](node/pnpm/http-service) | 缺少 `pnpm-lock.yaml` |
| [node/pnpm/typescript-service](node/pnpm/typescript-service) | 缺少 `pnpm-lock.yaml` |
| [node/yarn/http-service](node/yarn/http-service) | 缺少 `yarn.lock` |
| [node/yarn/typescript-service](node/yarn/typescript-service) | 缺少 `yarn.lock` |
| [php/composer/http-service](php/composer/http-service) | 缺少 `composer.lock` |
| [php/phpcli/http-service](php/phpcli/http-service) | PHP 版本声明无效 |
| [python/pip/http-service](python/pip/http-service) | 缺少 `requirements.lock` |
| [python/pipenv/http-service](python/pipenv/http-service) | 缺少 `Pipfile.lock` |
| [python/poetry/http-service](python/poetry/http-service) | 缺少 `poetry.lock` |
| [python/uv/http-service](python/uv/http-service) | 缺少 `uv.lock` |
| [ruby/bundler/http-service](ruby/bundler/http-service) | 缺少 `Gemfile.lock` |
| [ruby/rubycli/http-service](ruby/rubycli/http-service) | Ruby 版本声明无效 |
| [rust/cargo/http-service](rust/cargo/http-service) | 缺少 `Cargo.lock` |

## 使用与验证

服务通常从 `PORT` 读取监听端口，Spring Boot 默认使用 18080；PHP CLI/Bundler 等由产品运行规格设置监听端口。正常组的健康检查应预期 HTTP 200。回滚时，复制正常组和候选组到两个父目录下的同名应用目录，或使用验收辅助类按同一应用身份实例化，避免按不同目录名创建两个应用。不能直接把 503 候选当成另一个独立应用来声称完成回滚。

Python 模块统一为 `http_service_fixture`；C/C++ 可执行目标为 `http_service`。Python/Pipenv、Poetry、uv 的 3.11 元数据保存在 `src/app/main/src/test/resources/fixtures/python311`，由验收辅助类覆盖相应版本声明和锁文件，不增加公开场景数量。普通 Python 夹具约束为 3.12；其余版本约束以各项目的构建文件为准，部署前仍需目标工具链匹配。

Gradle Wrapper 使用 8.10.2，JAR 和分发包摘要均对照 [Gradle 官方校验表](https://gradle.org/release-checksums/) 固定；TypeScript 5.7.3 的 npm、pnpm、Yarn 锁文件按各自格式保留。锁文件与构建工具声明必须成套复制。

`EcosystemExtensionFixtureMatrixTest` 对 125 组逐一执行真实分析器，并核对支持目录、源码语言、构建工具、多文件结构与五组数量。`RepositoryHttpFixtureTest` 递归编译全部 JDK 源码、打包完整可执行 JAR，检查计算、非法参数、默认/中文配置、连续请求、503 和缺失模型编译失败。`RepositoryServiceFixture` 为源码验收复制完整项目；文本定制保留二进制 Wrapper，原有秘密文件、构建环境隔离和专门启动失败守卫仍保留。

跨语言检查脚本位于 `src/app/main/src/test/python`：

- `verify_repository_fixture.py`：先在独立副本中执行对应安装和构建命令，再传入最终制品的直接启动命令，检查 16 项正常响应或 3 项持续 503 响应，最后停止进程。配置场景另加 `--default-label` 检查默认值。`--host` 仅允许回环地址，默认 `127.0.0.1`。
- `verify_fixture_failure.py`：复制已经安装/构建的样例，在临时副本中 `--remove` 必需文件或以 `--replace FILE OLD NEW` 修改清单；要求命令非零退出且包含 `--expect` 指定的诊断，避免把缺工具或网络失败误记为通过。二进制按原字节复制，临时副本自动清理。
- `verify_native_fixture.py`：使用指定 C/C++ 编译器构建配置、路由和业务编译单元，检查边界与中文配置，并验证缺头文件和漏链接失败。这是组件检查，完整 CMake/POSIX 网络运行需 Linux 环境。

例如，在已复制并执行 `npm ci`、`npm run build` 的 JS 样例目录上运行：

```text
python src/app/main/src/test/python/verify_repository_fixture.py --cwd <样例副本> --scenario success-json-api -- node server.js
python src/app/main/src/test/python/verify_fixture_failure.py --source <样例副本> --work-parent <现有临时父目录> --remove node_modules/zod --expect zod -- node -e "require('./src/service')"
```

uv 使用 `uv sync --locked --no-dev`，以检查清单与锁一致；单独使用 `--frozen` 会跳过该检查。Gradle 样例启用严格依赖锁；npm、pnpm、Yarn、Poetry、Pipenv、Cargo、Go、NuGet、Composer、Bundler 保持对应锁定安装流程。pip 的哈希 requirements 文件本身就是安装清单，使用 `--require-hashes`。Maven 使用固定父 POM 和依赖管理，没有伪造通用锁文件。

各组合的工具版本、实际检查和待验证项见 [验证记录](VERIFICATION.md)。已有实机入口继续默认关闭；本地检查不代表完成服务器发布或回滚。

JDK 21 下的聚焦验证命令：

```text
mvn -B -ntp -o -pl :windowstolinux-app-main -am -Dtest=NativeArchitectureInspectionTest,PythonBuildInspectorTest,DeploymentBuildRendererTest,EcosystemExtensionFixtureMatrixTest,EcosystemExtensionAcceptanceFixtureTest,EcosystemServiceAcceptanceFixtureTest,RepositoryHttpFixtureTest,PackageStructureArchitectureTest -Dsurefire.failIfNoSpecifiedTests=false test
```

PowerShell 中将完整的每个 `-D...` 参数放进单引号，避免参数被拆分。实机验收范围和连接参数见 [四期验收](../docs/development/PHASE-4.md#acceptance-methods)。25 种源码代表组合已有各自的真实成功记录，具体管理和回滚范围见[实际覆盖](../docs/development/PHASE-4.md#acceptance-matrix)；125 个场景没有全部执行，不能以代表组合或历史 helper v5 的 17 条路径推定完成。
