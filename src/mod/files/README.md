# 文件管理 Mod

独立可选 Mod，生产只依赖公开 appapi 与 JDK Swing，不声明业务 Schema。初始目录为空；通过文件管理工作区选择本机目录或已保存 Linux 的绝对路径。默认单栏，按需展开等宽双栏，列表仅名称与类型。

复制、剪切、粘贴、拖拽、新建、重命名及删除复用平台 File/Link API。拖拽默认移动，剪切准备不修改来源；链接自身与普通文件采用各自入口。移动仅维护本 Mod 登记或本次明确获准纳入的链接，结果展示真实操作步骤，UNKNOWN 通过 APP 原任务核对。

当前公开使用合同见 [Mod 文件能力](../../../docs/reference/Mod.md#public-api-spec)，对应实现与本地验收范围见 [1.3 文件管理规格](../../../docs/development/PHASE-1.3.md#file-explorer)。

启用后向服务器页的“更多”菜单登记“文件管理”动作，保留原主导航入口和手动选目录流程。快捷动作复用已打开的根工作区，直接浏览所选 Linux 的 `/`；新工作区使用左栏，已有工作区使用活动栏。活动栏有未提交路径时确认切换，取消不读取；工作区忙碌时提示拒绝，本次请求不排队。只在读取成功后替换目标栏，失败保留原目录；另一栏、剪贴板和已接纳任务保留，异步请求代次防止旧读取覆盖后续导航。读取仍经原平台任务、授权和主机信任流程。

入口使用公开 `ui.extension.PlatformUiExtensions.SERVER_MORE`，发送 `ui.page.UiNavigationRequest("remote-root", ServerId.class, ...)`；临时服务器上下文不写入启动偏好。普通模式启动后才发布动作，停止随所属登记撤销，RECOVERY 不添加普通入口。

独立作者构建（JDK 21，已准备当前 SDK 与 APP 测试制品，不附带业务测试）：

```powershell
mvn -B -o -f src/mod/files/pom.xml verify -DskipTests
```

生产代码不访问 APP 私有对象。集成测试依赖 APP 的 test scope 制品及本仓库已构建测试支持；这条命令只构建作者工程。源码只在本目录维护，独立开发区不保留副本。真实 CLASS 外壳验证前先在根执行 `mvn -B -o process-classes`，使参与集成的 Mod 描述对应当前根聚合。真实 Linux 与外部服务验证另行安排。

按受影响场景选择定点验证，例如只验证本机文件服务：

```powershell
mvn -B -o -f src/mod/files/pom.xml test '-Dtest=FilesLocalIntegrationTest'
```

本仓库真实快捷入口和受控 SSH/SFTP 夹具的定点命令（JDK 21，按本机实际 Bash 路径调整）：

```powershell
mvn -B -o -pl src/app/main,src/mod/files -am package '-Dtest=FilesServerShortcutTest,FilesRemoteInteractionTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dmanaged.test.bash=E:/Program/Git/bin/bash.exe'
```

`package` 在相同 reactor 中准备 APP 的测试支持 JAR，显式筛选测试；这些本机结果不代表真实服务器验收。

需要完整作者工程测试时须明确要求全量，再执行不带 `-DskipTests` 和测试筛选的 `verify`。该结果仍不代表真实 Linux 验收。
