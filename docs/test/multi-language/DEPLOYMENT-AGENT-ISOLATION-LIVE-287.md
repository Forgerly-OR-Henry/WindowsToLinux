# File Transfer 287：工具准备被独立 APP 拒绝，任务保留 UNKNOWN

唯一 `file-transfer-isolated-287-01`，UTC 2026-10-17T15:32:18.5088985Z—15:42:07.3111819Z，session85718 实际 exit1；JUnit 1F/0E/0Skip、583.5s，Goal BLOCKED。构建基线 f0350445e51d243f3eb68a934797ced4d0cedaf4，代码基线 9e457582d7e51af882a0f14c545d40f829ab6764。这是第16次历史 FULL，File Transfer 完整 BUSINESS v2、真实浏览器和 STOP/DISABLE 尚未完成，CSV 未开始。

| 对象 | 实际值 |
| --- | --- |
| Task | `7abadc73-0817-4159-80e8-945341a0bbb1` |
| Runtime | `src/app/db/data/agent-live-file-transfer-61fy5tpsw` |
| App | `wtl-agent-file-transfer-61fy7ik8g` |
| 端口 | 49292 / 49293 |
| backend 候选 | `wtl-agent-file-transfer-61fy7ik8g-backend-525f7bab44afa87d` |
| 最新 plan | `025fa88d05de24e1f9600e4b81e658bddb919df44dd407ab8bd6037d42e33d9d` |

## 实际经过与边界

六组旧保护 238/280/281/282/283/284 首次 fresh probe READY，116行/23558B，allocatedCandidates=[]，free2232991744、required1140850688、reserve1073741824B，MemAvailable3526316032B/CPU2。scope9577034de290ff3cba584322e022a0bb87fd7ffba2e41e32c0fd85ab6f8ae5d2。新 backend 实际 create/query/upload24765B/verify/open。

第二次 fresh probe 同样 READY，116行，scopef5bdd3940bb78bf1fd4ef63313ff8949ae599eb9f4b8ccf3f45c8fb1226fc480，allocated 只有本轮 backend，free1897394176、required805306368、reserve1073741824B。固定 reader 确认 PLAN40/配额41、42 与 PLAN69/配额70、71 均 backend335544320B（320MiB）、web201326592B（192MiB）。这实际越过 285 的预算重选阻点，不代表完整业务通过。原285已由产品正式结清和归档，详见 DEPLOYMENT-AGENT-ORIGINAL285-SETTLED-287.md。

Go resolver 实际选择1.24.13；已有固定控制器 READINESS 返回 READY。接着 existing-only prepare 原动作被独立 APP DENY，没有 grant、没有派发；原 ApiException 导致 Agent UNKNOWN。没有 build/seal/publication/business/browser。此前容量拒绝诊断出现 projectionFailure INVALID_DATA，是测试夹具未接受286新增预算字段，不是终止根因；288另行修复该投影。

完整原脱敏拒因1113字符已保 `file-transfer-post-quota-fix-live-287/file-transfer-isolated-287-01/complete-persisted-denial-reason.json`，没有仅保512字符摘要。APP认为现有 MANAGED 记录的实时状态、精确源码/版本/摘要及内部记录、选择、快照、actualBytes/promotion/sandbox证据不足，并提出PGP/tar相关审查问题。拒绝必须保留；不放宽审核、重放原动作或强制 grant。此轮未实际覆盖 existing-only FAILED65 拒绝证明，因为 DENY 发生在执行之前。

## 固定只读账本与收尾

288 reader 实际 javac0/read0，UTC15:45:39.238833Z—15:45:40.840318Z；existing-only bootstrap、H2 READONLY/IFEXISTS、现有空锁，87公开事件完整逐字匹配，pair属性锁内和关闭后不变。平台与Mod均UNKNOWN，2298资源/2293released/5unreleased；正式App/component/release/receipt/execution均0。CAPABILITY_EXECUTING2/RESULT1，controller action2/result2，PREPARE1/1，effectsSettledProductionEvaluator=false。reader没有读取controller完整checkpoint，不能据结果计数补认整个capability的NOT_SENT。

288静态判断：原capability参数、planRevision、审核绑定输入未完整持久化；controller index和摘要不能填补整个调用范围。保留UNKNOWN，不追溯补CAPABILITY_RESULT、不直接运行恢复。resolver含官方HTTPS读取，readiness对既有锁短暂共享flock，不概括为零外部动作。新APP/grant只能授权新动作，不能生成缺失历史事实。

确认UNKNOWN后仅写本轮automatic-close，产品MONITOR_CANCEL把postQuery83720e2b-5e27-4e88-a1bc-78bd7166ee03置CANCELLED；测试自然exit1，没有kill。CIM本轮Java0/terminated0；核owner/nonce/SID、绝对边界、no-reparse后仅清f287a/u287a及私有加密credential287 copy。reader三个自有临时目录也已清。Runtime、候选、旧六组和全部证据保留；source176未由cleanup打开；无浏览器页或输入文件。不恢复194。

## 失败 FULL 的已知用量

封闭stdout离线统计，无新增模型计量调用。

| 角色 | 物理尝试 | 输入 | 输出 | 命中 | 未命中 | 输入加输出 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| DEP | 17 | 704761 | 19947 | 544000 | 160761 | 724708 |
| APP | 53 | 1623986 | 12859 | 1295232 | 328754 | 1636845 |
| 合计 | 70 | 2328747 | 32806 | 1839232 | 489515 | 2361553 |

APP已知IO占比69.3122280127%；输入加权缓存78.9794683579%。70条IO/cache均已知，DEP17 reasoning已含output、APP53 reasoning未知；完整provider、配置探测、HTTP/账单未知。本表仅失败FULL，不混纯恢复或冒充最终两项目指标。原生role totals与统计吻合。

原始证据在 `.ai-workspace/agent-live-evidence/agent-autonomy-20261012/file-transfer-post-quota-fix-live-287/`；288固定reader和拒绝处理修复材料在 `file-transfer-toolchain-denial-288/`。后续保留287，有限扩大保护frame并让模型按真实预算自主规划；未知不能改判。File Transfer完整后才开始CSV，两个完整后暂停汇报指标。
