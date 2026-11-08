# 下一轮候选：保护链、镜像与只读观测整合

本记录只证明隔离候选代码的本地整合与定点验证。R12已中断收尾，计划分母8、原启动5、完整首过0；原FAILED、三项UNKNOWN、Survey缺失终态及三项未启动均保留。本候选尚未发行新轮SOURCE/P/R、建立新正常凭据、执行新产品清场或八项目真实部署。

候选工作树为`H:/Program/WindowsToLinux/.ai-workspace/wt/r13f`，分支`codex/assisted-integrated-observation-20261104`。从MAIN `42181ccd83fce23fc2071679cf97681e04750bc5`整合隔离保护链`caa4f99a22c1756ed574e49331988ee4881cec4d`；共同祖先为`c17e937b73869507ac4e88f5a323174bfa56a121`。MAIN镜像提交`8d37bf5a4`、`560294aad`与保护链此前没有合并，不能把分支间缺失解释成这两次提交回退旧修复。

整合保留以下边界：

- 0701的prior helper/build-entry字节准入、锁内再次校验、payload staging与安装后摘要，以及对应body/receipt字节范围。
- 27f的独立模型审查与后续真实host grant区分、全部前后校验；通用helper入口的`env -i`、固定PATH及`bash -p`。
- cff67共享依赖`--no-remove`、aa804的原server/task、单一期限、READY／ACK／cgroup完成证明和UNKNOWN占用。镜像policy/runtime与剩余预算通过同一manifest进入原systemd worker；旧、新已知manifest均可被清场器严格识别，畸形字段不接受。
- MAIN镜像选择、签名／强摘要约束、实际host body及request摘要、Agent冻结指导与snapshot漂移检查。没有整文件选择任意一侧覆盖另一侧。

默认bootstrap的两处APT install另补`--no-remove`，保留原本安装Python的能力。这只限制移除，不代表所有来源、hook和生命周期边界已修复，也不保证独立模型批准。未将原native明确非零统一改成UNKNOWN。

测试侧新增有界、透明的first-observed观测：APPROVAL原响应只记录严格JSON形态和已知decision literal；实际host grant调用、白名单日志write之前的event-attempt及其真实存储结果；原TaskWork的进入／退出／throw，以及原等待循环已读到的UNKNOWN snapshot。返回对象、原throw、request/context/handle及调用次数保留，不增加模型、SSH、inspect或重试请求。reason、完整arguments、脚本、自由文本异常和任意context不进入新投影。

`authorDelegateExitObserved`只表示测试装饰边界被观察到，不等于平台`returned=true`。内部纯校验、beforeSend和ledger精确分支仍可能未知；达到观测上限或sink失败时保留coverage gap，不将缺记录当成功。R12 UNKNOWN首因尚未查明，诊断增强不算底层修复。

## 实际验证

| 范围 | 最终证据 | 原失败及限制 |
| --- | --- | --- |
| Java定点 | 33个唯一方法、48个唯一cases最终各有通过证据；51次执行中48通过／3失败／0跳过 | 一次诊断断言误要求字段紧邻；两次Mirror既有fixture因Files未编译而初始化失败。各原log/XML保留，修复后只重跑失败方法 |
| Python定点 | 60个唯一方法最终通过；104次执行中102通过／2失败 | 生命周期源变化后有相关组重跑；两项旧fixture包数未计新增openssl，修正后仅复测失败2项。早期原stdout未落盘，台账根据可见工具记录重建，缺口明确保留 |
| 必要编译 | 正常test阶段生成app-main test-support classifier，并完成部署生产／测试编译；Files另行准备exit0、执行0测试 | 最初compile-only因缺classifier提前失败，原log保留。Files准备只算构建依赖，不算功能测试通过 |
| 受控本地Runtime | 最终Mirror单方法使用其新临时数据库通过 | 前两次初始化超出worker最初noRuntime内部范围；授权时序已单列。无远端、真实AI、旧DB或真机验收 |
| 实际renderer | 七份完整渲染字节经SHA复核；CodeSource和classpath指向r13f，无旧r12f输出 | helper保留MAIN的`535861d0ef1c0621d3eca7565895e3fe35267f422f834f174a604423d336f359`；build-entry保留`4fdd1d5d6661c31382647512e4efd96d6e5eb5ef1296512e2a3df25f49706e42`。依据实际输出更新四个旧测试pin |

实际selector、native exit及原XML见[Java台账](H:/Program/WindowsToLinux/.ai-workspace/wt/r13f/.ai-workspace/evidence/r13-protection-mirror-merge/java-verification-summary.json)，Python重跑与原日志缺口见[Python台账](H:/Program/WindowsToLinux/.ai-workspace/wt/r13f/.ai-workspace/evidence/r13-protection-mirror-merge/python-test-executions-reconstructed.json)。[renderer原件](H:/Program/WindowsToLinux/.ai-workspace/wt/r13f/.ai-workspace/evidence/r13-protection-mirror-merge/actual-renderer/renderer-identities.json)已由父复核七个文件的实际长度与SHA；[父独立核验](H:/Program/WindowsToLinux/.ai-workspace/evidence/assisted-resume-20261104-01/r13-independent-local-receipt-audit.json)从各次XML重算48个最终通过cases与51次实际执行。

上述检查不等于全项目、真实Linux包管理生命周期、当前模型连通性或完整业务验收。所有自有验证进程已退出，task tmp已清；需要保留的原件在evidence，不提交辅助文件、凭据或AGENTS，不推送。

后续须从提交后的候选核对实际编译产物、三Mod发现、provider与类路径，重新建立安全凭据和当前接口证据，再发行新冻结并通过产品清场及独立after，才开始新八原项目。继续使用既定6luna HIGH/HIGH、1500000／65536／90分钟及授权8MiB FTP负载；旧轮不恢复或拼接。
