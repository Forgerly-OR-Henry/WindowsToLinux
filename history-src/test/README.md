# 独立测试样例

- [单语言样例](single-language/README.md)：覆盖 12 种语言，构建组合与场景清单由单语言矩阵独立维护。
- [多语言样例](multi-language/README.md)：8 个中型业务验收项目，包含 5 个网页和 3 个控制台工具；提供规模、并发和故障恢复检查，本地验证与 Linux 部署证据分开记录。

样例不加入根 Maven reactor。自动化测试及运行器保存在模块的 `src/test` 中。
