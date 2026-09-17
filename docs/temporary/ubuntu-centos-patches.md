# Ubuntu / CentOS 实测补丁归属记录

状态：最终归属已确认，代码迁移及本地验证完成。本文保留实测来源与正式职责；沿用原文档路径以保留已有引用。

以下脚本路径相对于 `linux-sshd` 的 `src/main/resources/gold/debug/windowstolinux/shared/linux/sshd/`，Java 包省略共同前缀。

| 来源 | 处理 | 抽离前位置 | 最终归属 | 适用条件与保持项 |
| --- | --- | --- | --- | --- |
| Ubuntu 实测 | 容器用户命名空间使用引擎现有 AppArmor profile | workspace/21-workspace-volume.sh | `execution/protocol/helper/fragments/workspace/apparmor-namespace.sh` | 依据实际 AppArmor profile；不是仅 Ubuntu 生效 |
| CentOS 实测 | systemd 管理接口隔离、标准启动入口 | runtime/systemd/helper/61-dynamic-identity.sh；00-protocol-foundation.sh | `runtime/systemd/helper/systemd-manager-isolation.sh`、`runtime/systemd/helper/selinux-command-entry.sh` | 保持 SELinux 能力判定、非 SELinux 分支、动态身份查询、权限和 reviewed 参数 |
| CentOS 实测 | DNF 事务临时启用 CRB | DnfSetupRenderer；toolchain/20-installation-boundaries.py | `distro.dnf.DnfSetupRenderer`、`distro/dnf/centos-source-repositories.py`；发行版选择归 `distro.dnf.CentosStreamSetupRenderer` | 仅 CentOS Stream 9/10；不新增仓库，不永久启用 |
| 已独立 | 发行版准备、SELinux 准备 | UbuntuSetupRenderer、CentosStreamSetupRenderer、SelinuxPreparationExecutor | 保留 `distro.apt.UbuntuSetupRenderer`、`distro.dnf.CentosStreamSetupRenderer` 和 `distro.dnf.SelinuxPreparationExecutor` | 保留专门系统变更确认和重启恢复契约 |
| 两次实测 | systemd 状态、StateDirectory、工具链权限、SSH/NIO 等通用修复 | 既有生命周期、工具链及连接职责 | 保留原职责 | 不改为 Ubuntu 或 CentOS 独占逻辑 |

## 调用与验证

- `ManagedHelperBundle` 统一读取四个固定资源、展开原标记并验证整体摘要。三个临时 Java 包装类已移除，CRB 参数直接由 `DnfSetupRenderer` 生成；helper 不引用 runtime/distro 的 Java 实现类。
- CentOS Python 文件是通用工具链准备程序的嵌入片段，无独立执行入口；在源码依赖安装处按目标系统事实选择本次 DNF 事务的仓库选项。
- 三个移动脚本的文件内容校验一致，helper SHA256 保持 `1f10cece3ee7a3c9d75e9000c2b1c6bbe950afc11da65b65cc71ed26f4a2af87`，发行版安装快照不变，300 行门禁及严格资源清单通过。
- 本次 JDK 21 离线 Maven 定向 `clean test`：helper、发行版、包结构、模块依赖和文档结构共 42 项通过；Python 完整测试目录 60 项通过，均无失败、错误或跳过。使用显式 Python 3.12/Git Bash 路径，详细范围见[四期记录](../development/PHASE-4.md)。未运行全量 Maven 测试，未连接真实服务器，不将历史实测或本地验证外推为新的远端验收。

## 保留说明

- 最初抽离阶段曾将三个 Java 包装类暂存在 helper 包以避免包依赖环；当时 Python 54 项、Java helper/发行版/架构 49 项通过，属于历史抽离验证。
- 此文档随 Git 保存，不在交付清理中删除。
