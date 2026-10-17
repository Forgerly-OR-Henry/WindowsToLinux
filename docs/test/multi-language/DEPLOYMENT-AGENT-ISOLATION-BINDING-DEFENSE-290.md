# Agent 隔离绑定的空值防御 290

289末次APP质疑隔离载荷绑定并DENY，原任务随后经产品正式结清为FAILED，详见 [289记录](DEPLOYMENT-AGENT-ISOLATION-LIVE-289.md)。当前源码并不支持“空值变成{}”的说法，原分页正文未保存也不能逐字确认本次原请求；本改动是防御增强，不称修复已证实历史漏洞、不保证APP批准。

仅IsolationProbe固定shell suffix中的 `"$wtl_isolation"` 改为 `"${wtl_isolation:?}"`。原set-u已经拒未设置变量，新展开额外在已设置但为空时于解释器启动前失败。正常非空literal JSON的赋值、envelope/policy/scope/action绑定、完整stdin、精确decoder、APP/新grant合同保持。Python SOURCE、sourceVersion、回执format、160行/32768B预算与权限不变，不影响STATIC/ASSISTED原生流程。

两个新增定点直接消费生产生成的suffix/完整script：缺失和空值均非零且受控解释器入口0次；正常非空精确捕获argv JSON及原stdin，核完整policy/scope/mode/queryTask/action/sourceVersion/bindingSha256，并让生产decoder接受原请求、拒digest或suffix篡改。Bash内建exec捕获替身不启动Python或远端探测；10s/64KiB界和进程/reader/临时脚本finally清理。

test01原fixture使用WindowsJDK ProcessBuilder的-c全文启动，实际捕获JSON引号丢失、缺失/空值变为:?，两例2F/0E/0Skip1.588s。该观察指向本地Windows/MSYS参数转义，不是远端脚本漏洞证据。仅fixture改为UTF-8短名.sh文件启动，stdin与所有断言不变；原失败保留，只重跑这两方法。test02的空值方法通过，完整方法已通过argv/stdin逐字断言，但内存LongNode与JSON解析IntNode的期望对象比较失败；仅把两处期望按JSON解析规范化，所有结构和值仍严比。test03只重跑该失败方法，实际1P/0F/0E/0Skip、1.531s/package0，UTC16:39:23.0458404Z—16:39:42.6419209Z。最终两个不同方法全通过，物理5例2P3F，失败原始结果全保。正常case通过也印证先前-c启动传输是本地夹具问题。十项直接source在最后package开始后0改动，JAR4253951B位于该窗口。CIM本轮Java0/terminated0，owner/边界/no-reparse后仅清c290a/u290t，受控.sh文件已finally清理。

辅助before、窄diff、原始验证与受控fixture修正证据在 `.ai-workspace/agent-live-evidence/agent-autonomy-20261012/file-transfer-after-probe-denial-290/`。不重复288/289已过组，不跑全量；两路径窄patch79增1删、reverse check通过；只Git提交阶段五项限定摘要（两源、JAR、patch、本说明），仅本地提交，不推送。

下一步先用产品原生审批和新grant做定点SDK元数据事实观察，明确SDK树只读、既有产品admission/receipt正常写，不执行SDK或prepare/PROBE/install；尚无新实机调用。旧七UNKNOWN不动，194/285/289已结清不恢复，File Transfer→CSV和两完整后暂停指标目标不变。
