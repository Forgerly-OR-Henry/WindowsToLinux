# 205：向独立审批准确说明 agent-open 的阶段与效果

**本地最终有效24个Java用例通过，唯一显式跳测打包通过。尚未执行新版真实产品复验，不能声称审批占比或缓存已改善。** 这是用户限定的第3轮、最后一轮优化；前两轮均未达到完整验收标准。

[204](DEPLOYMENT-AGENT-CACHED-PROBE-LIVE-204.md)最后拒绝的是agent-open。源码核实：该动作内部重新检查任务归属、隔离卷和归档摘要，只按受限规则解包数据；build-entry用于后续项目执行，CURRENT_SOURCE在open/prepare成功后才由客户端记录。原契约对此阶段的说明较粗，出现要求预先提供后置证据的时点混淆。原拒因1277字符，日志仅保留1229字符且truncated=true；缺失部分、原完整wire及reviewBinding仍未知，不回造旧证明，也不改判旧DENY/UNKNOWN。

本次只改AgentApproval、所属approval-agent.md及两个直接测试。审批挂点只有在完整guard、已审默认helper源SHA `90bacdd769a17245401ce8e426117bbcb87ba4362a46d9cee8129bcd9892859d`、原intent/exactAction、task/server/layout/typed targets、规范四argv、原压缩归档摘要、派生candidate、空stdin及120秒/4096字节frame全部匹配时，才添加agentOpenPhaseEvidence。自报保留字段被拒绝；其它不匹配请求仍走原独立审批，不自动授权。

说明明确列出stop/remount/extract/freeze等可达效果，并保持remoteObserved=false、远端状态UNKNOWN。它是代码语义，不证明远端守门或清理成功。第四argv为压缩归档SHA，不能混同解包tree revision；opaque target保留原值，不解码为路径。一般解释固定在Agent SYSTEM一次，当前小事实进入本次USER及原绑定复查。完整输入/coverage、独立APP自主读证据、DENY、配置变化和每个修改动作重新取得grant均保持。共享HelperReviewContract、执行器、主history、配额、数据库版本1及其它模式未改。

## 本地验证与隔离交付

- 6个具体方法最终有效24PASS。java-01实际24=21PASS/3FAIL，失败均为同一PrepareReview方法：旧夹具漏允许三个OPEN场景的前置verify grant，未到open。仅将其旧枚举断言改为已有mode.verified()，其余严格断言不变；生产2文件和已通过ApprovalTest字节不变。java-02仅重该方法3PASS，复用原21PASS。实际共执行27case，保留原3FAIL；0ERROR/0SKIP，不是全量测试。
- 只对相应3/2/1个Java文件分阶段格式检查，均通过。复用198/203无变更场景，不重跑Python或完整模块。
- 唯一 `-pl src/mod/deployment -am -DskipTests package` 成功，Maven15.802秒，源码前后3307项完全相同。20个生产组（18不变+当前2）完整类族/资源逐项核source、target和JAR；host AI、HTTP及真实业务fixture字节不变。
- 两次Java各15组、package12组normal PID+creation ticks全部退出；格式三阶段9/11/11组退出。自有marker TMP/短UDS已清，旧Runtime/UNKNOWN/资源保留。本阶段无SSH/provider/DPAPI或浏览器操作。

混合工作树原改动保留。4份原dirty bytes为补丁基线，父实际Git apply正反检查与逐字还原通过，自有scratch清理；不以干净HEAD构建，也不整文件暂存混合源码。正式提交包含本记录、精确增量patch及其单路径-text属性；默认不推送。

| 冻结对象 | SHA256 |
|---|---|
| 3307项 after-manifest | 0c2fac8e64ffd210bc08dd03210f85c480a1d1ef25587512ecf979a9cf0493cf |
| 精确dirty-before→after补丁 | 790c2fb7b09036dbf905667f164c20ba51f8374b8930fb32d3c0532952cfd4c8 |
| deployment.jar | f040260c14433052440b9fc92903f216e652966f5a99cc2df0e2c4367b50430e |
| package/after-metadata.json | ab7820404b6a55f14eff835d5fde58d40d4f36de1a02f1ba9d70fc21992a323a |

精确补丁见[AGENT-OPEN-PHASE-EVIDENCE-205.patch](patches/AGENT-OPEN-PHASE-EVIDENCE-205.patch)。本地原始报告/失败版本/进程与清理证据位于 `.ai-workspace/agent-live-evidence/agent-autonomy-20261012/denial-cache-optimization-205/`，不含凭据正文。

## 效率判断与最后一轮边界

204失败窗口已知653481 Token，APP56.0601%、输入加权cache74.8228%；不是完整验收或八项目指标。最后连续8次APP共122502 Token，即使假设全部去掉，同窗口APP仍45.9227%，不能承诺此修复足够达到20%。四个必要能力探测及完整账单仍未知，reasoning不重复计入output，不补费追证，也不通过增加主模型分母或删必要审批凑指标。

下一阶段至多一次新的task-board FULL：模型自主部署与独立审批，真实前后端业务、浏览器操作、产品STOP/DISABLE、冻结与物理闭合及同覆盖usage均须成立。最低cache≥60%、APP≤20%，理想≥80%/<10%保持；第3轮仍未达到最低线即按用户指令暂停，不追加第四轮。203小probe尚无本轮真实专属回执，冷安装/保留容量条件不降，旧201/204及其它UNKNOWN与资源不追补结清。
