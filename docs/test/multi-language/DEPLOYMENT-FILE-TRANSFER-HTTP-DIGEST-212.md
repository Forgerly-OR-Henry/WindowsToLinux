# File Transfer 普通 HTTP 客户端摘要修复（212）

本阶段修复样例浏览器上传对 WebCrypto 安全上下文的依赖，完成本地定点及实际 HTTP 页面验证。没有新增付费部署、SSH、DPAPI 或产品 Runtime 调用；211 真机复验仍未启动。

## 原因与改动

File Transfer 的公开验收 URL 由真实部署 fixture 构造为 `http://<host>:<port>`，原客户端上传直接调用 `crypto.subtle.digest`。[WebCrypto 的 `subtle` 接口要求安全上下文](https://w3c.github.io/webcrypto/#crypto-interface)。本地浏览器在 `http://192.168.201.1:60321` 实际观察到 `secureContext=false`、`subtleAvailable=false`，原摘要调用抛出 TypeError。这是原调用的能力负例，不是原样例完整上传的负例。

该地址来自 Windows 的 VMware Host-only 网卡，前后端仍运行在本机，用于覆盖非 localhost 的普通 HTTP。原 Java 业务检查自行计算摘要，绕过客户端调用；仅在 loopback 浏览器运行也不足以覆盖这一问题。

只改三个样例文件：

- `web/src/client/client.ts`：上传摘要改为调用 `sha256Blob`。
- `web/src/client/sha256.ts`：纯 TypeScript 增量 SHA-256，按至多 1 MiB 读取原始 Blob，计算完整文件摘要。
- `web/test/sha256.mjs`：使用 Node 原生 SHA-256 作独立 oracle。

无新增依赖或锁文件变化，完整文件摘要仍参与续传身份比较，Go 发布时仍独立核验原字节。上传、续传协议、下载逻辑、Go 源码和平台审批规则均未改。分块读取验证只证明读取请求上限，不声称浏览器 RSS 被严格限制在 1 MiB。

## 实际验证

只在私有副本安装既有锁定依赖，未在正式样例树产生 node_modules、dist、二进制或数据库。唯一 build exec20220 已 exit0。

| 范围 | 实际结果 |
| --- | --- |
| 既有前端 build | TypeScript 服务端及客户端编译通过 |
| `node --test --test-reporter=tap test/sha256.mjs` | 17 PASS、0 FAIL、0 cancelled、0 skipped，约 991 ms |
| 摘要边界 | 空、abc、Unicode/NUL、全部 byte 值、55/56/63/64/65/127/128/129 字节、七种增量分片、非零 offset、终结后输入拒绝、跨 Blob 块及 64 MiB 完整摘要 |
| Go build | 本机 Go 1.26.8、`-mod=readonly` 通过；不是目标 Go 1.24 验收 |
| 实际普通 HTTP 页面 | 批量上传、同名两版、历史第一版下载逐字节一致 |
| 实际 64 MiB 续传 | 确认分块后暂停；刷新后保留同一会话及第 0 块；原文件续传完成、64 块与完整摘要一致 |
| 错误续传 | 同名、同大小、末字节不同的 64 MiB 文件被拒绝，原会话保留 |
| 历史终态 | 四个实际上传会话均 completed，错误续传未创建额外会话 |

浏览器大文件下载的两种受支持工具分别在 15 s、45 s 未取得回执，**64 MiB 浏览器下载未通过验证，原因未知**。仅对该失败环节追加本地 HTTP 诊断：同一 Node 网关、同一已发布版本，200/67,108,864 字节，约 1.234 s，完整 SHA-256 一致。这不能替代浏览器下载通过；没有因此改下载代码或放宽浏览器保护。

文件选择工具曾出现异常长等待，之后重新核对控件并完成实际上传。刷新后的文件夹选择和历史终态断言各遇一次页面加载时序问题，读取新页面后仅继续对应步骤，未重跑已通过业务或摘要测试。

## 基线、证据与收尾

修改前 HEAD 为 `a2393e2f286ec0acc084371d6a8b7d4872ca3d8f`，原 client 与 HEAD 原字节一致；本任务三个源码可直接隔离提交，其它迁移与 dirty 改动保留。

| 对象 | 核对值 |
| --- | --- |
| 当前完整源码 | 3309 项，仅一个既存 client 改动加两个新文件 |
| after-manifest SHA-256 | `b78a5293be17589faa20addb2259716adab21d65d5848be3e318a20dc26503a0` |
| 样例当前清单 | 28 文件、67,297 字节，原为 26 文件、58,906 字节 |
| deployment.jar（未变） | `60b3d55cd8ff6f756c3d69655cdd22fd3c1e5275aa3b7a2d42e610ad10e4961b` |

辅助原证位于 `.ai-workspace/agent-live-evidence/agent-autonomy-20261012/file-transfer-http-digest-212/`：`sample-fix-validation212.json` 绑定定点 TAP、前后源码、实际页面结果/截图、单独 HTTP 诊断及物理闭合。`browser-proof/two-versions.jpg` 为真实两版本页面，`wrong-resume.jpg` 为真实冲突提示。

负例服务器、实际 Go/Node 和工具 launcher 已退出；父正常用户 CIM 独核 115 个自有 PID/创建时间均退出，60321/60322 无监听。实际 Go/Node 通过精确 PID/创建时间终止，未将其写成自然退出或产品 STOP/DISABLE。私有副本、依赖缓存、临时输入及可丢弃 SQLite 已删除；浏览器页已关闭，默认下载目录中本轮唯一 35 字节文件在精确路径/大小/SHA/创建时间核对后清除，原下载字节仍保留。无其它进程、正式数据、旧 FAILED/UNKNOWN 或远端资源删除。

## 下一轮边界

211 工具只作必要绑定调整：保留原 207 人类出站授权和 210 包原证，以精确三文件差分连接旧 3307 项包来源与当前 3309 项样例来源。授权原文包含必要修正版本，不伪造新用户答复；JAR、20 个生产组和 classpath 守门保留。新增差分判定 16 个纯解析正反例及三个 Python AST 通过，尚未执行新 capture、preflight、凭据复制或部署。

本地通过不证明远端部署、独立审批闭环、缓存收益或审批成本改善。后续仍需当前 Git/源码/JAR/classpath 重新准入、模型自主完整部署、真实业务及浏览器、产品 STOP/DISABLE。两个已选项目的原 FULL 共两次；若启动唯一 211 修后 File Transfer，则累计为第三次 FULL，不增加第三个不同项目，也不重开旧任务。
