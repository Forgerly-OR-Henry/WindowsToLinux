# Agent 第七组 UNKNOWN 的有界隔离回执 289

原287的prepare_tools整体范围证据不足，保持UNKNOWN，不补CAPABILITY_RESULT、不运行旧恢复或为腾空间新增部分候选清理合同。原285已由产品正式结清归档，不纳入UNKNOWN；194不恢复。后续只准备新File Transfer，CSV仍后置。

本次增加原287 Task7abadc73-0817-4159-80e8-945341a0bbb1/Appwtl-agent-file-transfer-61fy7ik8g的双unit、已创建backend候选、49292/49293、真实末PLAN/source绑定；原238/280/281/282/283/284集合完整保留，flag链要求第七组不能跳过早先保护。

## 最小改动与容量边界

只改Agent IsolationProbe、直接相关IsolationTest、Live验收fixture。固定Java请求/decoder/严格parse与Python行数/最终输出预算同步由128行/24576B改为有限160行/32768B；不截断、不抽样、不省略保护对象。20s内部协作预算、30s外层timeout、四batch、unit96、work扫描4096/候选16、所有identity/mandatory/foreign/排序/漂移、freshAPP与精确新grant保持。

七组完整135行=130 mandatory+原238真实发现web候选3路径/backend release2路径。以287首READY原116行23558B保守追加287十九行（kind取DIRECTORY），投影135行27312B，32KiB余5456B；只是静态投影，不是新的实机证明。161行短合法保护路径fixture投影27724B，低于字节上限以独立验证行数拒绝；135行+64合法filesystem投影raw34417B，用于独立字节拒绝。两定点实际验证135行合法、161行拒绝、超字节及缺项/foreign/排序/truncated反例，结果见下。

上次同FS free1897394176B，扣1GiB reserve和64MiB额外需求后，双份候选配额总和理论上限378271744B（64MiB粒度320MiB）。这不等于实际构建需求或容量保证；模型仍须依据fresh真实容量和source floor规划，不能压低需求、工具版本、reserve或完整验收来放行。若拒绝，保留真实拒因，不重复同一失败FULL或无界积累保护frame。

## 验证与交付状态

仅运行 `DeploymentAgentIsolationTest#completeSevenRetainedNamespacesStayWithinBoundedReceipt+fixedPythonProtectedProbeUsesSameFiniteMetadataBound`；Maven显式单类双方法筛选，必要package，无全量、provider或SSH。本次runner首次语法错误在Maven启动前已修并静态parse通过，不计JUnit。固定Python方法使用受控本地AST执行原protected枚举函数；不访问真实远端。唯一test01/session80140实际2P/0F/0E/0Skip、0.442s，package0、Maven17.668s，UTC16:06:35.4588975Z—16:06:54.0039013Z。直接十项source metadata在package开始后0改变，JAR4253948B位于该窗口。288已通过的Session/诊断组未重复。CIM本轮Java0/terminated0，owner/边界/no-reparse后仅清c289a/u289t，原始输出与UNKNOWN库均保。

辅助材料在 `.ai-workspace/agent-live-evidence/agent-autonomy-20261012/file-transfer-seven-retained-289/`，含三源before、窄diff、字节投影及定点原始输出。保留迁移，只交付三路径窄patch（41增/23删，reverse check通过），仅Git提交阶段六项限定摘要（三源、JAR、patch、本说明），只本地提交，不推送。

下一新scope使用49294/49295，3361B需求包含全部七组与完整BUSINESS v2/真实浏览器/STOP/DISABLE边界；脚本默认disabled，目前无新credentialcopy或新实机调用。每个实际动作仍独立APP/新grant。File Transfer完成后才开始CSV，两个完整后暂停汇报FULL审批IO占比、输入加权缓存与已知/未知覆盖，失败FULL和恢复单列。
