# 1.1 隔离技术探针

此工程不属于活动 Maven reactor，不启动产品或打开用户数据库。它保存 S2 技术验证的可复跑输入；平台实现迁入活动模块后，对应反例继续随正式测试迁移，探针不能代替 S3～S7 产品验收。

使用 JDK 21 和现有系统 Maven 仓库：

```powershell
mvn.cmd -B -ntp -f .mvn/probes/phase11/pom.xml test
python .mvn/archive/verify.py --git HEAD
```

依赖固定 H2 2.5.250、PF4J 3.13.0；均由 Maven 解析，不使用手动下载 JAR。当前 28 项 Java 探针通过，零失败、错误或跳过。DPAPI 的 CurrentUser 保护/解保护与篡改拒绝另在普通 Windows 用户上下文验证通过；沙箱模拟身份无法加载相同用户配置文件，该失败不作为产品缺陷或成功证据。

| 检查 | 通过项 | 范围 |
| --- | ---: | --- |
| DatabaseProbeTest | 8 | 44 表、重开、非管理员隔离、JDBC 事务/LOB、源只读、旧 SQLite 保全、双进程锁、实际异常退出后核对 |
| JdbcResourceProbeTest | 5 | 包装器句柄不泄漏、关闭失败保留占用、跨线程拒绝、关闭回滚、提交结果未知 |
| LoaderProbeTest | 5 | CLASS/JAR、多来源与资源、公开类型共享、私有冲突隔离、错误入口/依赖/循环/重叠导出、关闭后可删除 JAR |
| PageProbeTest | 3 | EDT、自定义 Swing、懒创建与重开、部分失败释放、页面关闭后的任务结果 |
| ProcessProbeTest | 5 | 真实子进程握手/结果、持续 stderr、EOF/错帧/重复、请求上限、取消单发、STOPPED 不等于进程退出、JSON/帧长/身份检查 |
| VersionProbeTest | 2 | 任意精度 SemVer、预发布范围与错误语法 |

探针 SQL 与 API 声明取自本期规格快照，仅供技术验证；对正式规格的后续修改需要同步迁入的生产代码和正式测试，不将此快照当作另一份产品规范。测试只在本工程 target 中创建隔离临时数据，实际子进程均须退出；不启动 H2 网络服务。普通文件资源连接关闭缓存，避免 Windows JAR URL 缓存绕过加载器关闭而继续锁文件。

冻结归档的工作区字节及 Git blob 均由校验脚本检查。仓库本地 `.git/info/attributes` 使用 `history-src/** -text -eol` 防止旧归档内的行尾规则改写归档。需要恢复归档时，在独立目录使用 `git archive --format=zip --output=<输出ZIP> <归档提交> history-src` 提取 Git 原始 blob，再核对摘要；不要在冻结目录构建，或依赖新克隆工作区的行尾转换来复现原字节。
