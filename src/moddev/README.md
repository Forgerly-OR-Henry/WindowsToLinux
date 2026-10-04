# Mod 独立开发区

本区存放开发中的 Mod 和同作者从模块。根 POM 不聚合本区，CLASS 运行时也不扫描本区。
每个项目只保留一份源码；需要真实 APP 集成调试时，将整个项目（主模块与从模块）移入 `src/mod`。

先在仓库根目录安装公开 API 和作者工具及其必要依赖：

```powershell
mvn -pl src/app/mod -am install -DskipTests
```

项目建议使用 `src/moddev/<project>/pom.xml` 作为独立聚合 POM，只声明本项目的主模块和从模块。
主模块继承仓库中的 `.mvn/wtlmod-author-parent/pom.xml`，正确设置 `relativePath`，声明 API 为
`provided`；从模块用普通 Maven POM，在主模块的 `wtl.authorArtifacts` 中声明精确已解析坐标。
JUnit 单元测试只需声明 `junit-jupiter` 为 `test`，作者父 POM 提供版本及 Surefire。
将本项目作为独立 Maven 工程导入 IDEA；不要为开发验证导入全部 APP 或所有开发项目。

从仓库根目录执行本项目的真实独立构建、单元测试和静态代码准入：

```powershell
mvn -f src/moddev/<project>/pom.xml verify
```

`process-classes` 会通过作者工具捕获 Maven 实际解析的依赖和构建输入，执行生产字节码安全准入，
再生成 `target/wtlmod-development.json` 与编译标记。该检查不构造作者入口、不启动 APP；
单元测试执行的是作者自己的测试。静态准入失败或编译失败不会保留可用的旧开发描述。
额外构建配置须列入 `wtl.buildInputs`；主模块与从模块必须归属同一源码区域。
格式检查用 `python .mvn/formatting/format.py --check --path src/moddev/<project>`，
平台全生产源码门禁与文档索引覆盖两个区域。

移入 `src/mod/<project>` 后核对相对父 POM、从模块及资源路径，并在 `src/mod/pom.xml` 中登记
项目聚合模块（仅参与集成调试的项目），然后按新位置重新执行 `verify`。复制旧 `target` 或开发
描述不能替代重建；旧路径及旧区域的摘要将被拒绝，禁止在两个区域保留同身份副本。

在 CLASS 配置中明确开启 `mod.auto-load` 后启动 APP，本区仍不被发现，移入调试区的有效唯一构建
才可首次建立启用及版本选择。已保存的禁用、版本和待重启决定保留。需数据库初始化、迁移或授权
的项目仍通过原平台界面明确确认；未完成时显示待处理原因，准备完成后下次启动接入。关闭自动
加载时不扫描、不启用；没有目录监听或热加载。发行安装仍按原安装和启用流程处理。
