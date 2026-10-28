# R08 纯本地集成准备（2026-10-29）

R08 已完成固定基线集成、必要构建、32 例指定定点测试和本地运行输入核验。R08 正式项目执行数为 **0**；本文不表示真实部署、模型 provider 或完整业务验收通过。

## 固定来源与保留边界

- 保留根：`H:/Program/WindowsToLinux/.ai-workspace/wt/r08`；分支：`codex/assisted-r08-local-preparation-20261029`。实际编译后必须保留原路径，不得移动或作为 tmp 删除。
- 基线：`4b27d6e726ce9b00d98618c6e20436168952f06d`，未带入 main 未提交论文；不跟随后续 main docs。
- 正常 `merge --no-ff ff83bda9820b45794b45f47d8e1f4ddb37add546` 成功，无冲突；merge commit：`f3acc0e9dc3bd086d7ff44e3fcdf71a528d2f0f1`，双亲分别为固定基线和 ff83。
- ff83 祖先包含 `9068fe92db66c11ee5cc047922b19ef04c7f22b5`（后续 FTP 8 MiB）及 `da3e709f2920a8c3aa2a7a61eaf1fd008ae5b453`（UI 调整）。本轮没有另改生产逻辑、负载、超时、模型 route、依赖版本或系统设置。
- main、R07、nr29 的源码、target、原失败、UNKNOWN、论文、私有配置及原 policy/driver 全保留；R08 只读参考原纯本地 scanner 源码，未查询、启动或停止 R07/远端/DB/driver。
- 未复制或打开 API key、凭据、UNKNOWN 库；未构造 PlatformRuntime、未开启产品 DB、未触发作者 Mod、真实模型、SSH、HTTP 业务、服务器清场或新一轮。Maven 仅使用指定公开 Central settings。

## 实际执行及结果

所有 Maven 均在当前进程设置 `JAVA_HOME=E:/Program/Java/JDK21`，同时使用以下完整路径作为 `-s` 和 `-gs`：
`H:/Program/WindowsToLinux/.ai-workspace/evidence/assisted-continuous-20261028/resume-preflight-01/maven-central-settings.xml`。
命令与原生退出记录见 R08 证据的 `InvokeLocalMaven.ps1`、两个 launch/exit JSON 与完整 log；外层 shell 返回值没有代替 Maven 原生退出值。

```text
-pl src/mod/files,src/mod/deployment -am -DskipTests -Dmanaged.runtime.staticLive=false -Dmanaged.runtime.agentLive=false -Djava.awt.headless=true package
```

实际 Maven exit=0、BUILD SUCCESS，39.097s。全部测试跳过（日志 15 处 Tests are skipped）；16 个实际打包 JAR 已逐一记录路径、字节数、SHA-256。Files 同时构建，三个动态 Mod 的 target 均来自本树。

```text
-pl src/mod/deployment -am -Dtest=DeploymentRequiredReviewSubmissionTest,DeploymentLiveMonitoringRequestTest,DeploymentApprovalConversationTest#automaticSubmissionRejectsTextWrongToolAndMultipleCallsWithinTwoSubmissions,DeploymentAiJsonTest#approvalPutsCompleteUnchangedExecutableBeforeChangingTargetMetadata -Dsurefire.failIfNoSpecifiedTests=false -Dmanaged.runtime.staticLive=false -Dmanaged.runtime.agentLive=false -Djava.awt.headless=true test
```

实际 Maven exit=0、BUILD SUCCESS，7.186s。四份本树 XML：RequiredReviewSubmission 23、LiveMonitoringRequest 7、ApprovalConversation 指定方法 1、AiJson 指定方法 1；合计 **32**，失败 0、错误 0、跳过 0。只执行这一次限定选择。未执行全套测试，也未把 nr29 先前的三个 CurrentSource 前置拒绝夹具失败改绿或纳入本次选择；真实 provider 仍未验证。

## 本地运行输入核验

- 本树四 XML 的 `java.class.path` 完全一致：原始 55 段，含末尾空项；其 JUnit 有效 cwd 是 `H:\Program\WindowsToLinux\.ai-workspace\wt\r08\src\mod\deployment`。独立 java 输入已去除空项，不隐式引入 cwd，保持原顺序，得到 54 个非空绝对项。
- 逐项校验存在、规范路径、全部祖先非 reparse 与内容 SHA-256；54 项中 38 个公开 Maven JAR、16 个 R08 JUnit 输出，另加本树 Files 动态输出共 17。无 main/R07/nr29 target。目录摘要编码为按 UTF-8 相对路径排序，每文件 uint32BE 路径长度、路径、uint64BE 文件长度、SHA-256 内容。
- 17,061 个普通 CLASS 资源无重复 provider；只排除非普通资源 `module-info.class` 与 `META-INF/` 版本元数据。实际 `DeploymentStaticLiveAcceptanceTest.class` 唯一来源是 R08 deployment/target/test-classes。
- 真实产品 DevelopmentModFinder.scan 经 DevelopmentInfo.read、ModCodeChecker.validate 扫描三 manifest：Deployment/Files/Git，候选 3、拒绝 0，无 Runtime/DB/网络/作者激活。
- 三 descriptor 的绝对路径、inputDigest、compiled marker 已匹配。package 与 test 间 Deployment 的原始 descriptor hash 因 Map.of 对象键顺序变化而改变；严格 duplicate-key 拒绝解析后只规范化对象键，数组顺序和值逐项不变；保留前后原始 SHA 和规范化 SHA，不修改生产解析器。

| Mod | inputDigest | 当前 descriptor SHA-256 | compiled marker SHA-256 |
| --- | --- | --- | --- |
| deployment | `20b4baddd86bfc162aec376b2750cd577b4a9446bd2f2b69e6d50e54a3740645` | `3d6c07469e8b0d6c18fea101103d1dea355b82f92d36f7b402a99a05e234db18` | `b8de7c6d7864e2d2f9398042acbf7a26381088f0a16f006cd66b6df6d90a36eb` |
| files | `abf2eda85dc49f3a5b24bc2698c3e8bf3af7a12145c6fc76a97111bc641aa47d` | `dd1f4678fff118d82c369585a4d14ba4338480c8747f7decff66c51157f7a31a` | `14b45548fe4762f6fbf2c47ab370f49416a65c46ec8ed725449b95045ba7effb` |
| git | `68cc061865f856452cd95da5b1d9919955590bd6b8914e9cce4fe40f3fcf9501` | `b29beb534ec2a98c065291ddb4b9c815bd6ab2834b881357d4ad99315a1e2439` | `9d74e0342af2a6f5ba6f915804409f1e8ec1a548fa346a2238187bc6630b49c7` |

独立 java classpath 文件 SHA-256：`6e7c07e020d051a67753eba88f05a40827a924c7a3c3f27d908ba8f6b9d3dfdf`。
实际 StaticLiveAcceptanceTest CLASS SHA-256：`f1029d46b74207492249f095e8f2f4ac74388c5b214454e82a69882b96af17ff`。

ASM 读取实际 CLASS 的 fileTransferV2 指令，确认 1048576 字节 chunk、8 倍 payload=8388608 字节、8 块、四分之一=2 块重启前以及剩余 6 块。只反射纯 `responseReadTimeoutMillis`，12 个边界输入通过：合法 chunk PUT 为 300000ms；GET/POST、非法 chunk 路径和普通请求为 20000ms。没有调用 fileTransferV2、HTTP helper 或任何业务入口。

## 工具、来源和证据

所有辅助证据位于：
`H:/Program/WindowsToLinux/.ai-workspace/wt/r08/.ai-workspace/evidence/assisted-r08-local-preparation-20261029/`。
54 cp 各项、17 输出、16 JAR、三 manifest/descriptor/marker 和相关源码的完整路径与 SHA 分别见对应 JSON；辅助 Java/Python/PowerShell/argfile hash 见 auxiliary-source-hashes.json。

| 实际工具或公开 settings | SHA-256 |
| --- | --- |
| `E:\Program\Java\JDK21\bin\java.exe` | `8c6d49b508d222cda1e3765e66c74905dffa3312db4044a43547e5196a2e94c4` |
| `E:\Program\Java\JDK21\bin\javac.exe` | `61e685c154127fffa5e2b2b7ef96dd8f889cbd77b0eed2273e85937ad03b59c1` |
| `E:\Program\Java\maven\apache-maven-3.9.12\bin\mvn.cmd` | `5f9b8eac523030cd9818daa0eb337635e06137d0da95a66a0a5425d668050e12` |
| `E:\Program\Python\Python3.12\python.exe` | `4d6f5f81a4bca11191c4c7c6b43632694d0a4ce74e068619d8fdc161d469859a` |
| `H:\Program\WindowsToLinux\.ai-workspace\evidence\assisted-continuous-20261028\resume-preflight-01\maven-central-settings.xml` | `a99ef5165993ce1f9765c6458cabff604ec17bfd2ca79f1c8d2f3af02f814aef` |

只读参考原 scanner：`H:\Program\WindowsToLinux\.ai-workspace\wt\r07\.ai-workspace\evidence\assisted-continuous-20261028\round-07\VerifyAllDevelopmentMods02.java`，SHA-256 `c705ac7bc74b92ba55b50aa0ef92d78a7b53a71b176327ecb9d91e7c488a124f`。R08 自有改编源码只替换类名、固定根和精确输出路径，在本树自有 tmp 编译执行；原文件未改。

| 保留证据 | SHA-256 |
| --- | --- |
| `package-launch.json` | `37c09242b8e42603e1709bc3144b2ea2517c1d61ef7eb1955e2de89ea44b9993` |
| `package-exit.json` | `9f899978bec243ef3e34d062588eebdc96c7bb61aa5d250f557be22982e447ef` |
| `package.log` | `2de197e855a9fc9e2184f59741c76573242a3490a24ccde285764c69facd6363` |
| `test-launch.json` | `6edd69e3212889afc02a964219da84358ee3b5bcffa32529536309e620e5325d` |
| `test-exit.json` | `85578678f58095b18179527039af3947ab30db44ec8d5a1457a956dac671c251` |
| `test.log` | `28e333ed76441101e611bb063ec9e2b0f8e42d7cdea0130164de5cdb8ff55589` |
| `tests-summary.json` | `b6edaadfde6f5dc217c41f4378bd5c926364a72c3f6a0e4ae0fc7d21b0dc3f17` |
| `junit-classpath.txt` | `6e7c07e020d051a67753eba88f05a40827a924c7a3c3f27d908ba8f6b9d3dfdf` |
| `classpath-input-manifest.json` | `a590f11708aa1ac497cf307d0aa12fc645ff2324c4594d291f0ba2d0247a5890` |
| `classpath-providers.json` | `a9b584a99132992e6bee4035dfc427bdcedbb13d71793a158f98cc94ebeae300` |
| `workspace-outputs.json` | `6b437bd32358756c2221a41b662fe08b54233bb1f36e357750a5decbbad71ab8` |
| `packaged-jars.json` | `59d5f246690d6eb5c26aad2ea057b724d032b2998c2b04adc1a8823ac56ab4e8` |
| `descriptor-proof.json` | `8b7e61591d026cae12db75c012b94bb1b79ee4d6b14a9965c18edd143df0f358` |
| `product-mod-scan.json` | `0dcf8d34f4e22b2a8477c615ed7189f4b7ebf880c1e010295dc257cb3d8ec335` |
| `bytecode-reflection-proof.json` | `1280e1e34c0a07c696df45f055405b1f671bc2c3a849fd23077126b27c498f9c` |
| `source-inputs.json` | `00985160ea7361eb6bdd12ca0231a818c34c7f5a24aecd821de2c9c2b747c21e` |
| `tools-and-settings.json` | `680a91ba16308cee2037dc7f6a26df99091de29039969acaa610adca20bb1b52` |
| `original-scanner-hash.json` | `5d9e3aad31521f134e13db531762ab38a30f7a77532243a43feb9660825e3bf0` |
| `auxiliary-source-hashes.json` | `c1b2ebb061f9b6429451dd6fedb08f7ff36bfcaf146d950bc92bab1e000df034` |
| `process-exit-check.json` | `7800136345cdfeff052b006f34b602ec80aa77c979fca31a8328296821aa1bce` |
| `temporary-cleanup.json` | `daefd6bd4e6877d09a8858953558a37f0a2767088760bd8047dd5e9de396a95b` |

## 收尾与尚未执行

package/test/javac/scan/reflection 的原生进程都实际 wait 完成；Maven 和三 proof 原生 exit 均为 0。宿主 CIM 只按精确 R08 路径及记录的自有 launcher PID 检查：自有 launcher 与其它 R08 进程均为 0。核实精确归属路径和所有条目非 reparse 后，仅删除 `.ai-workspace/tmp/r08-local-proof-20261029` 及空 tmp 父目录；临时 CLASS hash 已先保留，target、源码与证据保留。没有停止其他任务进程。
此提交只提交本正式记录；辅助证据按仓库既有排除规则留在本地，未修改 .gitignore，未暂存 AGENTS.md。最终候选 HEAD 及末次 compiled marker 核查由本地证据 `final-state.json` 记录，不把 docs 提交后的 HEAD 与构建时 merge HEAD 混为一谈。
R08 正式项目为 **0**。父任务必须等 R07 原队列结束后另行完成新凭据准备、产品连接、最终 policy 冻结、服务器清场及独立 after 观察，然后才可授权/执行正式八项。当前没有执行这些事项，也没有推送、merge main 或 merge 活动 R07。
