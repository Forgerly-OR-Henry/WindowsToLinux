# 原238单次产品协调241：仍为UNKNOWN

2026-10-17。只对原Task `072bc074-4bce-4c14-b425-f2ff965c7d40`、原Runtime `agent-live-file-transfer-8pqtfa76o`执行一次产品原生协调，没有新增FULL、其它应用管理或旧194/234/221恢复。原任务仍为UNKNOWN，未结清或归档。

## 实际执行及来源

240修复已通过本地验证，正式补丁与记录提交为`0af9bcc37d2d0591683874f85c1938f392674df2`，未推送。241执行前核3311源码、六JAR、33部署组、3AI族、4平台族、5完整宿主模块及54CP/3213文件；238/239/240封存分别72/37/77文件核同。原238保护数据对及既有空锁创建时间与239一致。

保存的原238模型角色、目标和指纹来源已核对；这不代表预检重新探测了provider或SSH。未配置新恢复模型。首次本地字节捕获因把四行host-trust日志误按单行匹配而失败，发生在输出创建、数据库打开或网络操作之前；保留原工具和失败记录，修正为核原连续四行后捕获成功。baseline SHA256为`b43e967ea80898510f2e262ac25994f07c88dd07c44a528f4674c8f5e7aac4f7`；唯一offline核验退出0，SHA256为`61a666039e82979e1384fe1a992d5f8faf33d83b0c0df294c1b7bc4678b4fb8f`。

唯一执行为`DeploymentStaticLiveAcceptanceTest#reconcilesOriginalTaskThroughProduct`，session `9236`，`originalTaskOnly=true`、`originalPreparationOnly=false`、`configureAgentRecoveryModel=false`。原30分钟等待上限未改变；实际窗口为`2026-10-16T18:25:00.3511815Z`至`18:25:33.5118213Z`。

| 证据 | 实际结果 |
| --- | --- |
| Maven / 前后来源冻结 | `1 / 0 / 0` |
| 原始JUnit | 1F、0E、0Skip；单一指定方法，未跑全量 |
| 原任务 | UNKNOWN、无终态outcome或endedAt；最后进度`AI_RECOVERY_ORIGINAL_HOST_VERIFIED` |
| 关闭Runtime | 明确报告原Mod仍拥有资源；不据此释放原资源 |
| 后续242只读账本 | 原问题为`DEPLOYMENT_AGENT_FINAL_ASSESSMENT_MISSING` |

实际JUnit的`java.class.path`及`surefire.test.class.path`均与54个绑定CP入口的顺序和规范路径一致，原fixture class字节不变；末尾空入口单独排除。这是CP属性与来源冻结证据，不是逐类JVM CodeSource证明。XML SHA256为`a6f3582d07d0fdf49ae2e7acb803d227ceca1c4923c8233a1de3bf3e8ea27e62`。

## 纯恢复用量

只按原Task、generation身份、调用开始和记录结束均落在本次窗口的记录计入。100条诊断窗口中有96条AI usage：2条本次、94条原238历史；历史行逐项与238封存统计核对后排除。首次父核对误把100条诊断全部当usage，断言失败记录已保留；原汇总器结果正确，没有重跑产品或补付费探测。

| 指标 | 本次已知子集 |
| --- | ---: |
| generation | 2，均APPROVAL；这是模型调用结果，不等于动作获批 |
| input / output / IO | 16826 / 583 / 17409 |
| APP IO / 已知DEP+APP IO | 17409 / 17409 = 100% |
| cached input / input | 13440 / 16826 = 79.8763817901% |
| cache miss input | 3386 |
| reasoning | 两条均未知，属于output子集，不另加 |

完整generation、物理HTTP、callback覆盖及账单仍UNKNOWN；未知不补零。本表是纯恢复窗口，不能混入238失败FULL或两项目完成后的最终指标。原238失败FULL的已知APP占比60.9908882154%、cache/input 71.8215986794%保持原记录不变。

## 收尾及保留边界

本轮正常CIM先核15组协调进程，再独立核19组含两次预检、offline及closer的精确PID/创建时间全部退出。仅按原marker及绝对无重解析路径清理本轮两个TMP/UDS，没有终止其它进程；原Runtime保留。执行binding和capture开关均关闭，消耗前字节留存。原结果SHA256为`a5627447629c9b7608c6dfd91221ca242718ab9362ed018188849238cc803830`，日志SHA256为`f53c7a53fcc33b7bdd002fa8c8cc8f6962aa75ed62aff0b39e66815431ce2600`。

全部辅助证据位于`.ai-workspace/agent-live-evidence/agent-autonomy-20261012/file-transfer-original238-reconciliation-241/`。242确认了最终评估缺失；原事件还包含backend的`PUBLISH_RESULT/HEALTH_RESULT`和web的`PUBLISH_EXECUTING`，需要继续核对部分发布的实际效果。不能把本地正式App缺行解释为远端无效果，也不能补造FINISHED或盲目重放协调。

当前仍9次实际FULL、0完整业务通过，CSV排队。File Transfer与CSV Inspector严格逐个，两个项目真实验证和必要收尾完成后暂停，分别汇报审批占比和缓存命中率，等用户决定后再开始。
