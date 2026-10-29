# 工具链依赖事务边界定点验证（2026-10-29）

当前仅完成禁止APT依赖安装移除软件包这一项隔离修复及相关定点验证。R08仍为6/8失败；Log的超时与进程树收尾缺口另待修复，没有启动R09或重新清场。

## 已证问题与未知边界

R08 Directory原root a61da983-01f9-4084-9581-29c4f471a96e的持久化拒绝理由指出：apt-get install -y未禁止解析器移除现有软件包，可能超出声明的依赖闭包目标。第二阶段只在私有副本读取精确拒绝行，原UNKNOWN不重放；SSH/API/master内存脱敏与整个JSON残留检查通过，safe输出70f60127、父接纳9f68be64。

源码共享dependency_main确实仅传-y和--no-install-recommends，没有禁止移除。APT官方说明[--no-remove会在计划移除任何软件包时中止](https://manpages.debian.org/trixie/apt/apt-get.8.en.html)。因此确认的是命令缺少声明范围所需的禁止移除约束；本次在独立审批处已拒绝，不能说实际发生过卸载、解析器确实会卸载或整轮已修复通过。

本问题与R07 CSV无native提交、ff83原生提醒分支不同；未撤销或改变ff83。共享资源从原Agent位置迁至service/deploy/toolchain时的7a321fa1及后续冻结原件保留，本改动仅收紧APT安装参数，不调整审批决定、预算、模型、任务状态或既有来源、签名、root-owned、配置与hook校验。

## 隔离改动

在独立r09f工作树、源73bbbbbb基础上，为共享APT install增加--no-remove，继续使用原包清单、认证选项和单次事务/回执。该路径供PYTHON/PHP/RUBY/NATIVE_BUILD/HELPER_RUNTIME使用；DNF没有更改。实际八项影响仍须下一统一冻结轮证明。

两个既有Python测试夹具仍引用已迁出的service/agent/toolchain依赖资源，现修正为生产实际service/deploy/toolchain，并在既有PHP、native build和helper-runtime事务断言中检查禁止移除选项。不是增加旧路径兼容副本，也不把夹具路径修正当生产问题修复。

## 实际验证与交付边界

仅指定七个Python unittest方法，0失败，0.058秒：PHP原包/认证/单回执；native build APT与DNF事务/精确回执、拒绝不派发、伪造回执拒绝；APT认证放宽与自定义hook拒绝；helper-runtime受控APT事务及危险配置/来源/key/binary在事务前拒绝。内部subTest不另算方法。原始launch/stdout/stderr/exit/CIM位于主工作区[定点证据](H:/Program/WindowsToLinux/.ai-workspace/evidence/assisted-continuous-20261028/r09-fix-only-01/)。Python10704实际wait/exit0，host CIM确认不存在，未留下验证进程；-B避免字节码缓存。

这是本地纯模拟命令/OS接缝验证，没有运行真实APT、SSH、模型、UI或全套测试。尚未重建新工作树部署产物；必要打包待超时边界修复一起完成，不能沿用R08旧JAR冒充新修复。原R08工作树、targets、失败、UNKNOWN、两阶段取证与论文修改保留。所有正式代码及本记录完成相关验证后提交本地Git，默认不推送。
