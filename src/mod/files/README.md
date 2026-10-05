# 文件管理 Mod

独立可选 Mod，生产只依赖公开 appapi 与 JDK Swing，不声明业务 Schema。初始目录为空；通过文件管理工作区选择本机目录或已保存 Linux 的绝对路径。默认单栏，按需展开等宽双栏，列表仅名称与类型。

复制、剪切、粘贴、拖拽、新建、重命名及删除复用平台 File/Link API。拖拽默认移动，剪切准备不修改来源；链接自身与普通文件采用各自入口。移动仅维护本 Mod 登记或本次明确获准纳入的链接，结果展示真实操作步骤，UNKNOWN 通过 APP 原任务核对。

完整细节、验收结果与本地支持范围见 [1.3 文件管理规格](../../../docs/development/PHASE-1.3.md#file-explorer)。

独立作者验证（JDK 21，已准备当前 SDK 与 APP 测试制品）：

```powershell
mvn -B -o -f src/mod/files/pom.xml verify
```

生产代码不访问 APP 私有对象。集成测试依赖 APP 的 test scope 制品及本仓库已构建测试支持；这条命令只构建作者工程。工程已从 `src/moddev/files` 整体移入本目录，旧位置没有副本。真实 CLASS 外壳验证前先在根执行 `mvn -B -o process-classes`，使参与集成的 Mod 描述对应当前根聚合。真实 Linux 与外部服务验证另行安排。
