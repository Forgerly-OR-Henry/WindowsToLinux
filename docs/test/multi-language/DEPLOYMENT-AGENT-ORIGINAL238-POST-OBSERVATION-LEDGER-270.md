# 原238新观测实账270：整图完整，回滚目标仍未达成

只读公开codec核验原File Transfer 238的完整267条事件。269新增417–426整图观测完整、两组件都有匹配的正式状态回执；backend的CURRENT、unit、release和维护标记仍存在，原回滚目标未达成。没有新增OBSERVED_ONLY、候选清理或正式FAILED结清。平台和Mod仍UNKNOWN，effectsSettled=false。

## 新观测与不变事实

最新BEGIN417、backend REQUEST418/RESULT420、web REQUEST421/RESULT423、END426；public完整framing/prefix/receipt绑定校验通过，2/2组件有回执，不回退旧289–298。backend仍为原release `39f2ab6846947132163fa93814283b91df81817416110b90ffedcba6c901a597`、deployment/DAEMON、maintenance=true、unitPresent=true、retainedCount=1、candidate=true、buildActive=false、snapshot=false。web为current=none、unitPresent=false、maintenance=false、retainedCount=0、candidate=true。

接续385–391仍只有一次，固定receipt UNKNOWN/INTERNAL/mutationStarted=true、五项结果事实null，完整public Continuation codec再次通过。旧271 intent/272 FAILED/UNKNOWN及原READY安装保持；compensation completed=0、pending=true、verified=false、lastActionEvent=391。整图较391更新，但goal未成，不能消除pending。正式operation receipt为0，原工作journal、execution和资源仍保留。

INTERNAL回执的mutationStarted=true是保守fallback，不证明实际抵达修改；本次新观测证明的是当前目标未成立，不能倒推267的精确历史执行点。静态已证原生渲染stdout重定向可能吞掉拒绝帧，但具体触发检查仍未知。

## 实际只读验证及收尾

父核交回pins及完整diff，独立逐字核Java正文仅改阶段路径和实际269诊断窗口；5helper沿sealed268最终字节，保既有两字段PID/time兼容、module globals、源/JAR/CP和原空锁约束。没有新增SQL、诊断类别或权限。

fresh capture实际0，baseline SHA256 `2f7fb9029a6752a0af7bb64571041ff2b3b822eb4b028e5375991b3ee15f31be`；3332源、6JAR、58deployment/3AI/4platform/5host、54CP/3282文件同266。隔离javac0、2class，-proc:none/-implicit:none/--release21及本轮空sourcepath。唯一reader session16136实际0/stderr0，stdout151103B/SHA256 `5abc6bab0a1c7dd67cad2203d5b4ae211508ac6f8dc9dda0026db1013b2a3235`。

原既有空FileChannel锁、existing-only DPAPI bootstrap、bare H2 IFEXISTS/ACCESS_MODE_DATA=r/isReadOnly；没有TaskContext、Runtime、recovery、credential行读取、provider或SSH。原DB223637504B/`079d9b0acf50d142804042f3c1fe330a119e13bcaa443bc16ae8f99541fcea35`、bootstrap358B/`81c546baf9dc3e7632155d86c8ae6d657109f82f1608e01006ca97ec07f7bd8b`和原空锁创建身份在前、锁内、关闭后不变。

正常finalizer0核编译/reader共4个精确进程身份全退；独立普通非管理员CIM含finalizer共5个身份全退，执行位全false，本轮空sourcepath已删，无无关进程终止。源码、target、JAR、Runtime及旧sealed根未改。该只读窗口没有模型用量，新增FULL/BUSINESS通过为0。

## 下一步

不再重复协调或发送任一回滚，不重装READY。下一步只定位当前三项原生纯合同：完整参数校验、managed-data links、预期unit渲染。诊断需绑定旧272、新385–391与417–426，独立APP和fresh grant，使用不会被stdout重定向吞掉的控制通道，仅回传白名单阶段、退出和拒绝类别；不得修改、补造ACK或授权结清。

证据根：`.ai-workspace/agent-live-evidence/agent-autonomy-20261012/file-transfer-post-observation-ledger-270`。File Transfer后才处理CSV Inspector，两项完整BUSINESS v2、真实浏览器和必要收尾完成后暂停，报告各项目FULL审批IO占比与加权缓存命中率；失败FULL/恢复窗口独列，无第三项目。194/234不恢复、221不自动恢复，当前仍9次历史FULL、0完整BUSINESS。
