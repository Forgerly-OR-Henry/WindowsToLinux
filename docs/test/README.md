# 测试文档

本目录统一保存测试集说明、执行入口、验收记录和失败分析。实际测试集位于 [根目录 test](../../test/)，不加入根 Maven reactor。样例项目自身的 README、业务规范和接口文件属于部署输入，随测试集保留；本目录不存放凭据、原始日志或运行数据。

当前 Agent 验收见 [暂停记录](multi-language/AGENT-CURRENT-STATUS.md)，完整成功仍为 0/8，等待服务器重装后继续。
- [单语言样例](single-language/README.md)：覆盖 12 种语言，构建组合与场景清单由单语言矩阵独立维护。
- [多语言样例](multi-language/README.md)：8 个中型业务验收项目，包含 5 个网页和 3 个控制台工具；提供规模、并发和故障恢复检查，本地验证与 Linux 部署证据分开记录。

样例不加入根 Maven reactor。自动化测试及运行器保存在模块的 `src/test` 中。
