# Main-only live 输入桥（91）

原89在input工具调用后被live harness的Unexpected additional input AssertionError中断，题目未保存，Task UNKNOWN保留。仅Test harness增加当前活跃回调的文件输入桥：mainOnlyDiagnostic && agentMode、非secret、固定Autonomous deployment input标题、显式managed.live.inputCheckpoint及managed.live.inputRun、真实supervisedAgent task匹配。旧STATIC识别与未配置桥的路径保持，生产UI/Agent/shared/Skill无变化。

每次新请求在checkpoint/request-UUID目录生成不可覆盖的ready.json；字段schema=1、run、task、requestId、title、prompt、questionSha256、questionCharacters、secret=false、status=READY、redacted。实际题目中的英文/中文credential-shaped值安全处理，题目SHA绑定原输入，不读凭据。Root在同目录写answer.json；ANSWER固定键schema/run/task/requestId/questionSha256/kind/text，CANCEL固定键同前但无text。严格拒绝重复键、尾部内容、额外键、错误run/task/request/题目SHA与敏感答案；无效answer保持并写仅code/SHA的rejected-answer.json，无默认答案或授权。有效回答只作为DATA返回；result.json保存安全终态/answerSHA或取消来源。原CancelToken/明确CANCEL或中断返回null；中断被消耗为取消，使原平台lease finally能够正常结清，不改变平台UNKNOWN/恢复机制。桥仅本次活动callback，不支持restart pendingInput或旧89恢复。

唯一非参数selector DeploymentStaticLiveAcceptanceTest#mainOnlyInputBridgeRequiresExactAnswerBindingAndSupportsExplicitCancel。仅managed.runtime.inputBridgeLocal字面true且Surefire test精确等该selector时启用本方法及XOR临时H2；类原staticLive字面true语义保留，新方法额外EnabledIf隔离gate不会混入真实live。此验证不设staticLive/deploy flags、provider/SSH/原DB为0。

全部原轮保留：baseline01 1FAIL2.229s（模块未enable）；baseline02 1ERROR2.931s（继承enable虚调用DPAPI open，随后XOR读取同新临时DB不兼容，该轮不能称DPAPI0，未读真实凭据/原DB）；baseline03 1FAIL6.121s（确切旧input反例，suppressed cleanup blocked）；corrected01 1PASS7.576s；corrected02 1PASS10.766s；corrected03 1FAIL18.178s（人为interrupt TaskUNKNOWN及cleanup）；corrected04 1ERROR18.348s（原lease释放ApiException）；corrected05 1PASS7.826s（四个实际Task ENDED）；corrected06最终1PASS8.196s/case8.183s，0FAIL/ERROR/SKIP（最终JUnit隔离gate确认）。每轮仅同一case，sourceFreeze0，未跑其它方法/类/组；失败不是全通过。默认flags与原业务/UNKNOWN门未放宽。

最终同方法核实际原平台requestText：未答等待；wrong run/task/request/题目SHA实际rejection SHA不消费；duplicate/trailing/extra/ANSWER-CANCEL互斥拒绝；中文凭据值不落ready、敏感答案拒绝；合法答案返回；explicitCANCEL、CancelToken、interrupt均原Cancelled，四个Task END后关闭Runtime。受控答案不是真实API模型部署证明。

before/after3296，仅一个Test owner增量，before==88afinal，生产源0。单owner IDEA write/check与skipTests package通过；19相关生产class/15Agentresources均与88字节相同，JAR容器hash可能变化不代表生产收益。原89及其余UNKNOWN/DB/资源/证据保留，Git交付由父隔离本地提交、不推送。

证据目录.ai-workspace/agent-live-evidence/agent-autonomy-20261012/agent-live-input-bridge-91；formal增量patch patches/AGENT-LIVE-INPUT-BRIDGE-91.patch，仅本Test+本文，UTF-8/LF。原九轮XML/TXT/result、actual生产parity及knownPID退出详见REPORT与closure。
