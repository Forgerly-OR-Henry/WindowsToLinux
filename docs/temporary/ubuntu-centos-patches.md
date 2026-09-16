# Ubuntu / CentOS 实测补丁临时归属记录

状态：界面精修的前置抽离进行中。这里记录实测来源与暂存职责，不代表最终架构决定；界面完成后保留，由用户决定最终归属。

| 来源 | 处理 | 原位置 | 本次暂存职责 | 适用条件与保持项 |
| --- | --- | --- | --- | --- |
| Ubuntu 实测 | 容器用户命名空间使用引擎现有 AppArmor profile | workspace/21-workspace-volume.sh | execution.protocol.helper.AppArmorNamespaceCompatibility 及配套资源 | 依据实际 AppArmor profile；不是仅 Ubuntu 生效 |
| CentOS 实测 | systemd 管理接口隔离、动态身份查询、标准启动入口 | runtime/systemd/helper/61-dynamic-identity.sh；00-protocol-foundation.sh | runtime.systemd.SystemdIsolationCompatibility 及配套资源 | 保持 SELinux 能力判定、非 SELinux 分支、权限和 reviewed 参数 |
| CentOS 实测 | DNF 事务临时启用 CRB | DnfSetupRenderer；toolchain/20-installation-boundaries.py | distro.dnf.CentosStreamRepositoryCompatibility 及配套资源 | 仅 CentOS Stream 9/10；不新增仓库，不永久启用 |
| 已独立 | 发行版准备、SELinux 准备 | UbuntuSetupRenderer、CentosStreamSetupRenderer、SelinuxPreparationExecutor | 保留既有类 | 保留专门系统变更确认和重启恢复契约 |
| 两次实测 | systemd 状态、StateDirectory、工具链权限、SSH/NIO 等通用修复 | 既有生命周期、工具链及连接职责 | 保留原职责 | 不改为 Ubuntu 或 CentOS 独占逻辑 |

## 调用与验证

- 固定远端脚本仍由 ManagedHelperBundle 装配；补丁资源通过对应类读取，保持当前行为。
- 抽离验证待执行：覆盖 AppArmor 有无、SELinux/非 SELinux、CentOS/其他 DNF，以及 helper 摘要和安装快照。
- 历史真实服务器验证不代替本次抽离验证；本次真实目标和本地测试分别记录。

## 待用户决定

- 界面调整完成后，决定暂存类与脚本资源的最终位置。
- 此文档随 Git 保存，不在交付清理中删除。
