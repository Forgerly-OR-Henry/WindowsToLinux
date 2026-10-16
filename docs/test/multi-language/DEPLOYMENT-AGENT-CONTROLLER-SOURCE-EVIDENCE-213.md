# 213：Agent 已安装控制器的完整源码证据入口

2026-10-16，针对[211 File Transfer](DEPLOYMENT-AGENT-FILE-TRANSFER-POST-FIX-211.md)实际遇到的审批证据缺口，补齐 prepare/bind 的完整控制器源码入口。**3个具体方法、5个不同用例已有通过证据，跳过测试打包成功；没有新增付费模型、SSH或真实部署。** 本地结果不证明审批占比、缓存命中率或业务验收改善。

## 实际问题与修改

211末次审批的原日志只保留669字符理由中的512字符节选，不能将其称为完整理由。节选指出：当前请求的 loader 与参数完整，但审批无法读取它将执行的控制器源码。随后对实际代码的数据流审查确认，已安装控制器只提供源SHA、字节数和原安装回执；`source_*`只读取应用源码，旧安装审批也不能替代本次 prepare 的独立审批。

仅修改 Agent 的 `DeploymentAgentToolchainInputEvidence.selectInstalled`：在原 task/server、captured layout、版本、源码SHA、原持久化动作与安装观测、canonical launcher、typed argv、目标、stdin 和时限全部匹配后，对 prepare/bind 派生 `DeploymentAgentToolchainPrograms.controller(layout)` 的完整文本，再核对其UTF-8 SHA与当前描述、原动作索引一致，字节数与当前描述一致。

新增 `controllerSource` 提供完整文本、SHA、UTF-8字节数和专用指针 `/agentToolchainInstalledInputBinding/controllerSource/text`，复用既有 `compact` 描述符及 `evidence` 分页。偏移仍为UTF-16 code units；原 `sourcePointer=/exactAction` 保持动作入口含义。应用 `source_list/source_search` 的范围不变。源码来自完整的当前布局渲染结果，不是裸资源、任意文件或调用者提供的替代文本。

这是证据可达性修复，不表示安全结论或执行许可，不要求每次机械重读全文。原独立审批、DENY、动作绑定及新grant条件保持。没有修改 loader、ProgramStore、manifest、审批contract/policy、配置、数据库、STATIC/ASSISTED或远端状态；无需改变控制器版本pin。默认及合法非默认ASCII布局均有定点正例，原Unicode路径拒绝规则保持。

## 实际验证

生产只改 `DeploymentAgentToolchainInputEvidence.java`，测试只改对应 `DeploymentAgentToolchainInputEvidenceTest.java` 与 `DeploymentAgentApprovalTest.java`。Sol XHigh实现三owner，Sol High准备复用工具，父检查数据流并执行格式、定点及产物核对；本阶段未使用Astra。

| 范围 | 实际结果 |
|---|---|
| IDEA 2026.2.3，三个Java文件 | write/check均exit0；修正断言后只检查该测试文件，exit0 |
| 首次3方法/5case | 3PASS、2FAIL、0ERROR、0SKIP；两失败均为新增分页方法读错版本字段位置 |
| 失败方法单独重跑 | 仅2case，2PASS、0FAIL/ERROR/SKIP；原通过3case未重跑 |
| 跳测打包 | `-B -pl src/mod/deployment -am -DskipTests package`，成功/16.394秒，14处skip日志、0测试执行 |
| 源码及产物 | 3309项源冻结；20生产组/40成员逐source-target-JAR核对，19组含210 Approval及policy保持 |
| 收尾 | 正常用户CIM独核已观测53组PID/创建时间均退出；自有TMP/UDS及补丁校验scratch已清除 |

首次失败发生在本地测试断言：它假定描述根含 `version`，实际已有版本位于 `originalAction/index/version`。只修正该路径，没有为适配测试增加生产字段或放宽条件。两轮原XML、日志与当轮源码清单分别保留。最终5个不同用例通过，不称作一次运行5PASS或全量通过。

两布局的原入口方法覆盖四phase及8种原输入/观测变异；新增 prepare/bind 流程通过 NativeAiFixture 从真实描述符分页还原完整源码，核对UTF-8字节/SHA和UTF-16游标，同时验证应用源码范围、DENY零grant、原CLOSED拒绝不重开、修改argv不能借旧观测，以及无关动作不获得该入口。直接受影响的原bind方法另验证首次完整请求边界与独立APPROVE新grant。均为本地模型夹具，不能代替真实模型回执。

## 基线、增量与保留证据

运行HEAD为 `c46bd686a8a8864cc79e550b172ba26589174f07`。输入JAR为 `60b3d55cd8ff6f756c3d69655cdd22fd3c1e5275aa3b7a2d42e610ad10e4961b`；新JAR为 **`a5830441da7f2532d8174adf2bd0953cbc814d4f80c99ec3311215c62643fe7a`**。最终3309项源码manifest SHA为 **`41cf3b2ef892125545124f2424280735d9f9c4914fd473de482fa28b7d20137a`**。policy仍为 `f116b4ed48492f3800d95cc12419010e852b302793091a4ac613bf327cedb250`；产物字节核对不冒充实际JVM CodeSource证明。

工作树仍含原迁移及其它任务改动。本地交付使用[三份原dirty源码上的精确增量](patches/AGENT-CONTROLLER-SOURCE-EVIDENCE-213.patch)，另加本记录和该补丁单路径的 `-text` 属性，不整体暂存混合源码。真实 `git apply` 正反向重建逐字节一致，实际工作源码reverse-check通过，原源码未被校验副本替换。不能据此声称干净HEAD独立包含当前完整产品。

辅助根为 `.ai-workspace/agent-live-evidence/agent-autonomy-20261012/controller-source-evidence-213/`，保留修改前、格式前、首轮失败及修正后原件。首次before复制曾遇Windows长路径限制，在0源码变更、仅空输出目录的条件下保存原helper并改extended-prefix后成功；不当作产品失败。复用工具的来源、差分、PS/Python AST及9个选择器纯解析正反例均保留；没有运行整类、旧210的25case或212的17case。

| 关键证据 | SHA256 |
|---|---|
| 首轮InputEvidence原XML，2PASS | 71d9672ba5b6b85f560b705640d0ba5bccdcb961704ff00e2e6d506201913efe |
| 首轮Approval原XML，1PASS/2FAIL | 377711732cdd1b5d3bb5c3b985ceebc729350f2315b5ee62a07a5eb1f86d7980 |
| 修正后Approval原XML，仅2PASS | 20680ced1316e1e6f261d34478236f8791a4d23c0dfd6743e22e30d2de3ee904 |
| package/after-metadata.json | f135723cbfffaff1e57bbdad5ce84b4c021779f9c17a22a0dd8a75cba717aef3 |
| 精确增量patch | dd6ec33b51ac5e9fc4bb48f71c1e9e57d1214b6856c9a7295d06443eda25ebc7 |

211原Task仍为UNKNOWN、目标BLOCKED，原Runtime、资源与全部失败/拒绝证据保留，没有改写历史或自动重放。当前仍只采样CSV Inspector和File Transfer两个不同项目；本轮没有追加FULL或第三项目。后续真实模型、完整业务/浏览器/产品STOP-DISABLE、八项目冻结复验、非默认目录真实部署与整机重启恢复仍待。默认仅本地提交，不推送。
