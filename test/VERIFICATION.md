# 多文件与依赖样例验证记录

验证日期：2026-09-09。范围为 12 种语言、25 个构建组合、125 个场景。所有项目结构、构建身份和准入结果均由仓库矩阵测试检查；每组至少包含三个有实际职责的非入口源码模块。

## 逐组合结果

“HTTP 通过”指三个成功场景、配置默认值的额外启动，以及持续返回 503 的健康失败场景均已检查；正常场景验证默认/自定义计算、中文配置、20 个元素上界、非法参数和失败后的后续请求。配置失败场景由真实分析器验证阻断，不尝试把它构建为正常服务。

| 组合 | 依赖安装与构建 | 本地运行 | 已执行的破坏检查 |
| --- | --- | --- | --- |
| C / CMake | MinGW 编译配置、路由、业务单元；完整 CMake 待 Linux | 组件通过；HTTP 待 Linux | 缺头文件、漏链接业务单元 |
| C++ / CMake | 同上，C++20 | 组件通过；HTTP 待 Linux | 缺头文件、漏链接业务单元 |
| Java / JDK | 全部源码递归编译并打包可执行 JAR | HTTP 通过 | 缺失模型编译失败 |
| Java / Maven | 真实 `mvn package`，包含报价单元测试 | HTTP 通过 | 缺失模型编译失败 |
| Java / Maven Wrapper | 真实 `mvnw.cmd package`，包含报价单元测试 | HTTP 通过 | 缺失模型编译失败 |
| Java / Gradle | 真实 Wrapper 构建、依赖锁和 Boot JAR | HTTP 通过 | 缺失模型、严格锁冲突 |
| Kotlin / kotlinc | 2.0.21 递归编译，包含运行库的可执行 JAR | HTTP 通过 | 缺失模型编译失败 |
| Kotlin / Gradle | 真实锁定构建与 `installDist` | HTTP 通过 | 缺失模型、移除制品中 Gson JAR、严格锁冲突 |
| JavaScript / npm | `npm ci` 与实际构建脚本 | HTTP 通过 | 缺模型、缺 Zod、清单/锁冲突 |
| TypeScript / npm | `npm ci` 与实际 TypeScript 编译 | HTTP 通过 | 缺模型、缺 Zod、清单/锁冲突 |
| JavaScript / pnpm | `install --frozen-lockfile` 与实际构建脚本 | HTTP 通过 | 缺模型、缺 Zod、清单/锁冲突 |
| TypeScript / pnpm | 冻结安装与实际 TypeScript 编译 | HTTP 通过 | 缺模型、缺 Zod、清单/锁冲突 |
| JavaScript / Yarn | `install --immutable`，使用 node-modules | HTTP 通过 | 缺模型、缺 Zod、清单/锁冲突 |
| TypeScript / Yarn | 不可变安装与实际 TypeScript 编译 | HTTP 通过 | 缺模型、缺 Zod、清单/锁冲突 |
| Python / pip | 真实 venv、`--require-hashes` 安装 | 3.12 与 3.11 HTTP 均通过 | 缺模型、缺 jsonschema、错误完整性摘要 |
| Python / uv | 真实 `sync --locked --no-dev` | 3.12 与 3.11 HTTP 均通过 | 缺模型、缺 jsonschema、清单/锁冲突 |
| Python / Poetry | 真实 `sync --only main --no-root` | 3.12 与 3.11 HTTP 均通过 | 缺模型、缺 jsonschema、`check --lock` 拒绝冲突 |
| Python / Pipenv | 真实 `sync` | 3.12 与 3.11 HTTP 均通过 | 缺模型、缺 jsonschema、`verify` 拒绝冲突 |
| Go / Go Modules | `go mod tidy` 后 `build -mod=readonly` | HTTP 通过 | 缺内部包、变更 chi 版本导致缺少对应 go.sum |
| Rust / Cargo | 真实 `build --locked --release` | HTTP 通过 | 缺模型、移除 serde_json 声明、锁冲突 |
| C# / .NET SDK | 正式 NuGet 源、锁定 restore、发布 net8.0 | HTTP 通过，使用 .NET 10 向上滚动运行 | 缺 DTO、NuGet 锁冲突 NU1004 |
| PHP / CLI | 独立命名空间文件，由 PHP CLI 内建服务器加载 | HTTP 通过 | 缺模型导致明确加载失败 |
| PHP / Composer | 真实锁定安装与 PSR-4/classmap 自动加载 | HTTP 通过 | 缺模型、缺 Opis、清单/锁冲突 |
| Ruby / CLI | 独立模块、标准库与 require_relative | HTTP 通过 | 缺业务模型导致加载失败 |
| Ruby / Bundler | Bundler 冻结安装 Rack 与 WEBrick | HTTP 通过 | 缺业务模型、冻结模式拒绝清单/锁冲突 |

C/C++ 的组件检查使用 `-Wall -Wextra -Werror`，包括四个可运行场景的配置、业务、路由、缺头文件及漏链接检查。它不包含 POSIX socket 编译，也不证明 Linux CMake 构建通过。

## 依赖与制品

- Spring Boot 保留 3.4.0 的 Web/Jackson/Validation 及报价项目依赖；Boot JAR 中核对业务类、配置类、模型、Jackson 和 Hibernate Validator。Maven 通过固定父 POM/BOM 管理传递版本，未伪造不存在的 Maven 锁格式。
- Kotlin Gradle 使用 Gson 2.14.0；最终发行目录包含 Kotlin 运行库、Gson 和应用 JAR。移除 Gson 后直接调用制品中的模型序列化方法出现 `NoClassDefFoundError`。
- 三种 Node 包管理器统一 Zod 3.25.76，TypeScript 5.7.3 与 `@types/node` 18.19.130；锁文件均由相应工具生成，TypeScript 从编译后的 `dist` 启动。
- Python 统一 jsonschema 4.26.0，四种锁同步实际传递依赖；3.11 覆盖资源也由各自工具生成。额外使用 pip 的平台参数下载并校验了 Python 3.11、3.12 的 manylinux2014 x86_64 wheel，包含 rpds-py；这只证明分发文件与摘要可用，未在 Linux 安装或运行。
- Go 使用 chi 5.2.1；Rust 固定 serde 1.0.229、serde_json 1.0.151；C# 使用 Newtonsoft.Json 13.0.4；Composer 使用 Opis JSON Schema 2.6.0，含其传递依赖；Bundler 沿用 Rack 2.2.9、WEBrick 1.8.1。
- 每个场景的运行依赖均位于该项目清单中；配置失败组只保留矩阵指定的故障。生产 Maven 模块未加入这些依赖。

## 环境与证据边界

本地为 Windows x64：JDK 21.0.10、Maven/Wrapper 3.9.12、Gradle 8.10.2、kotlinc 2.0.21、MinGW GCC/G++ 16.1.0、Node 24.16.0、pnpm 10.15.1、Yarn 4.9.2、Python 3.12.10 与独立 3.11 解释器、uv 0.12.11、Poetry 2.4.3、Pipenv 2026.8.0、Go 1.24.13、rustc 1.95.0、.NET SDK 10.0.300、PHP 8.3.33、Ruby 3.3.12、Bundler 2.4.22。

Node 样例仍声明 Node 18，本轮执行使用本机 Node 24；Node 18 的实际运行待验证。C# 样例仍声明 SDK 8.0.408、目标 net8.0；临时副本使用 SDK 10 构建，并以 `dotnet --roll-forward Major` 在 .NET 10 上运行，SDK/.NET 8 的实际执行待验证。

本机 JDK 在默认临时目录创建内部 AF_UNIX 管道时失败，验证命令使用任务目录的 `-Djdk.net.unixdomain.tmpdir=...`。本机 `127.0.0.1` 短连接重置可由独立标准库服务和 curl 复现；改用 `127.0.0.2` 的对照 100 次通过，跨语言探针据此使用该回环地址。没有修改全局网络配置，也没有为业务请求增加失败重试。

相关 Maven 回归共 168 项通过，包括 125 组准入检查、结构检查、分析器、构建渲染器、验收辅助类、JDK HTTP 和包结构门禁。JDK 之外的 22 个组合及额外四种 Python 3.11 配置共执行 130 次服务启动、1742 项 HTTP 检查；临时副本的 52 个破坏场景通过，修改探针后另复查一次，另有 C/C++ 缺头文件/漏链接和 JDK 缺模型检查。探针的超时父子进程清理也单独验证。

实际命令及可复用脚本见 [夹具说明](README.md)。本记录仅覆盖本地检查：完整 Linux 工具链安装、SSH 发布、systemd、健康回滚及恢复继续为 **RUNTIME-PENDING**，由原有受控实机入口验收。
