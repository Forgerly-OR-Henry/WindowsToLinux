# 原238后续诊断账本276：三项合同 PASS，原补偿仍 UNKNOWN

276仅执行一次本地只读账本查询，未启动产品 Runtime、recovery、provider 或 SSH。公开codec验证275实际产生的新整图及一次性 followup 全链：安全回执为 OBSERVED/COMPLETE/MATCH/0，三项合同均 PASS。原 pending271/272仍未ACK，completed0、verifiedfalse，原 Mod Task UNKNOWN；诊断成功不等于回滚、业务或结清成功。

## 字节、编译与读取

275封存165文件逐字节核同，沿用274/275实际源码清单、生产metadata、完整有序CP和六JAR，未构建生产包或重跑历史program proof。fresh276核3341源、54CP/3306文件、原文件对及空锁不变；baseline SHA256 `9a64f3b94fb956b8c5d91087676940edf718d7ea65a6bdbfe33801f6ee4b6013`。

首次capture在长证据路径的普通 `Path.is_file` 检查处失败，实际退出1，未写baseline/binding、未启动Java或打开DB。失败事实及旧脚本保留于 `parent-corrections/long-evidence-path`；仅父capture改用扩展路径stat判断普通文件，consumer未改。CP成员最长219字符，不受此证据路径问题影响。第二次capture实际0，stdout/stderr原样保留，stderr0。

隔离javac实际0，-proc:none/-implicit:none/--release21，未打开DB；产生两个class，source SHA256 `a446e7a87ab1abc0c808f8ef1334b1c38d1b5d2e52e1fd0dfb8fbd40e8eed8ab`，主class `2aac9f418a6b7d7526c36debde2e75a74b791bdafeecfe9459388446ce3b58a1`，匿名class `bafc69808b93010223255c284cd71bdb83c4150687b1b48dde4aa0031f1ef142`。javac result SHA256 `c95415a77017c3b1360874026d1fadcf0010a98eb9ba2fe8bfb3d58c94bc7075`。

父另绑定实际class与读输入 `ea871287f6d3e5d44021ccbbdc9cbff163e360027326eaae819d169f183c7901`，唯一reader session32785实际0，before/after byte各0、stderr0、originalFailurePhase=null。existing-only原FileChannel锁、DPAPI、H2 READONLY；credentialRowsRead0，锁内及关闭后文件对SHA不变。不构造未来action或授权。

| 实际证据（相对本轮根） | SHA256 |
| --- | --- |
| `read-276-01/result.json` | `517d19963e1805f75aa1115989747c82099d52616b70ae9f2e7b08f23b0af7cf` |
| `read-276-01/stdout.json` | `d4c0d96b05a3f850751985529ee26bf94fdca4444d0411387cd7ab167a564a36` |
| `closure276.json` | `ffc106ab2f4cc533f0048d6c64e9c859d812a24270b747014ed45acfa6c9d82f` |
| `parent-closure-audit276.json` | `7178d0d5c94486d71fcac648121c8f5803b57b72c04d898965e0e384f25a9c52` |

## 实际新整图与诊断

完整公开历史读取293条事件，history SHA256 `07cddb0bca2fe370405c06013c5a5a8b1761b7db0ad09d5c20115851c54da8da`；293不是动作数。requireInitial、完整 Compensation.read、旧385及449–456链、REQUEST前prefix eligibility、typed Binding、公开 Program.request/reviewFacts/resourceTargets及validateReceipt均验证。sourceVersion只表示绑定的预期stdin身份，不是reader远端测量。

最新整图invocation `24169021-48e9-419c-bb30-bc6ae76f4043`，481 BEGIN/490 END，2/2组件有已验证保存回执。backend current=`39f2ab6846947132163fa93814283b91df81817416110b90ffedcba6c901a597`、DAEMON、maintenance=true、unitPresent=true、retainedCount1、candidate=true；web current=none、unitPresent=false、retainedCount0、candidate=true。expected owner不冒充observed owner；保存观测不等于reader重新探测或当前健康证明。

followup action=`1393c6a2-45f2-4823-b32a-62a78c84bb75`，sourceVersion=`d6dc683c881c529f0f67727daa3d69763632b630f34b5542143598bfbf575406`，绑定新整图490及旧诊断456。

| 实际事件 | 事实 |
| --- | --- |
| 491 REQUEST | detail SHA256 `78df5e26b7fd9401e83b6efb09ab7e9eb685ac6556ce771bfa97ccc4da7bbbc0`，exactAction `cd9e922293cb820e5f6ee68eefcb8fdd757358f25e01e78476333aca39728d1b` |
| 492 REVIEW | APPROVE，confirmation=false，rememberAuthorization=false |
| 493 GRANT | 新grant的UUID仅输出SHA256 `689d9f665c18be05d038f879247e49229124b5e8101ba08670ccaad3560b65e2` |
| 494 DISPATCH | 独立新动作的正式派发记录 |
| 495 RESULT | SUCCEEDED、fixedReceiptVerified=true、parts2 |
| 496–497 RECEIPT / 498 END | 两块2048B＋1076B，完整receipt SHA256 `dcd1c01d53e92d1de74049f083342a05781860548d0df87c9d78fa22b2187cf0` |

安全receipt：OBSERVED、COMPLETE、MATCH、exitCode0；FULL_PARAMETER_VALIDATION、MANAGED_DATA_LINKS、UNIT_RENDER_MATCH均PASS/MATCH/0。未输出APP正文、原生stderr或秘密。该一次性尝试已消费，不因版本或新action重置，也不重放旧449、385或READY266。

原 pending action `d5ace0b0-25c5-4fa9-a8fa-dc1839cd5b24`，intent271/result272，原结果FAILED/UNKNOWN、fixedReceiptVerified=false；completed0、verifiedfalse、lastAction498。原 Mod Task行UNKNOWN、formalPhase EXECUTING、businessCommitted=false。没有formal settlement或历史删除授权。

## 收尾与下一步

normal close实际0，7个编译/reader/byte子进程精确PID与创建时间全部退出，自有空sourcepath已删，四个执行位false。独立普通非管理员audit实际0，含closer共8个精确身份全退；不终止任何进程，不删除原数据，文件对保持不变。capture为纯Python单进程，实际tool退出0，无Java或子进程派发。

本轮没有provider用量、新FULL或完整BUSINESS通过。下一步根据真实三项PASS和生成程序静态证据，判断原恢复路径的最小修复；不把诊断PASS当修改授权，不再增加诊断代际，不盲重试产品协调。File Transfer后再处理CSV Inspector，两项完整BUSINESS v2、真实浏览器与必要收尾后暂停并报告FULL审批IO占比及输入加权缓存命中率；194/234不恢复，221不自动恢复，无第三项目。

证据根：`.ai-workspace/agent-live-evidence/agent-autonomy-20261012/file-transfer-post-contract-followup-ledger-276`。本轮只有隔离reader编译和本地只读验证，无全量测试或推送；正式本地提交及封存由父后置记录。
