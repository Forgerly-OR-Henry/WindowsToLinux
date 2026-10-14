# 176：重装后凭据登记与 SSH 主机身份观察

2026-10-15（UTC+08）。用户已重装 Linux 验收服务器并要求继续。产品已将新凭据保存到独立的受保护配置，并通过正式 SSH 连接入口读到新的主机指纹；首次信任回调按预期拒绝，尚未认证、执行远端命令或启动部署。**这不是新服务器业务验收通过。**

## 恢复基线

177 本地核查的 HEAD 为 `47fb3d22f5f7ee1183d85eb786a113886a72b177`，暂存区为空。保留原有 860 个 tracked dirty 路径，不将干净 HEAD 当作当前产品源码，不切换缺少原迁移改动的新工作树。3299 项源码与 175 的 `after-manifest.json` 一致，清单 SHA 为 `6bba98ed41eeaf56b4ec8ce1e30548a0ad373b7a6255187bf0ee14eea598c831`。

`deployment.jar` SHA 为 `1e3f04a95201d5fdd131229093107130143636a48f67bfc8441125be8367af47`，实际验收 fixture class SHA 为 `b300a41c48dda597a562b82d6c6bb047f4cffa7d59d6861b3eecfe489affd421`。本阶段没有修改产品源码、JAR 或 fixture，没有重新执行已有效的 117/119、172/175 定点证据。

实际工具链为 JDK 21.0.10、Maven 3.9.12、Python 3.12.10、bundled PowerShell 7.6.5；Windows OCR 使用 Desktop PowerShell 5.1。既有 provider 配置仅核对非秘密地址、模型及凭据存在性，没有发起新的模型 API 请求。

174 选择性备份仍 READY，总清单 SHA `4217d227e7112a63f3a322195175b871c2ec7b7e677655087912de5c049b3868` 未变。两个原归档合计约 1.10 MiB，复用已有实际恢复验真，没有重新下载或解密；不恢复旧执行锁、主机 pin、任务身份或 UNKNOWN。

## 凭据通道与定点修复

本地工具从用户新截图执行 en-US 与 zh-Hans-CN 双 OCR，并核对公开 endpoint 与两个识别结果；密码仅经匿名 stdin 送入正式 `saveServerProfile`，由产品 CurrentUser DPAPI bootstrap 和加密 vault 保存。不将密码写入提示词、参数、环境变量、证据正文或日志。

前置准备曾发现 Desktop 5.1 不支持 `ProcessStartInfo.StandardInputEncoding`；仅移除不支持的 setter。首个 `-File` 启动被默认执行策略挡在脚本入口之前，所有 scope 均 Undefined，没有组织策略；后续只为已审自有脚本使用单进程 `-ExecutionPolicy Bypass`，没有持久修改策略。

原 actual01 在创建数据库前以 `IllegalStateException` 退出。其独立 root 仅有 marker，原失败证据保留；不能将此记录解释为服务器故障、已完成凭据导入或已发生 SSH 认证。

Python 子进程公开输入无 BOM 的结果，不能推广为 Java 的输入证据。随后在相同 Desktop 启动上下文，将公开 alnum 串送入原编译 Java `readSecret`：实际 writer 含 UTF-8 前导，原严格输入守门返回 `SECRET_INPUT_REJECTED`。工具只在 `Process.Start` 局部临时设置严格无 BOM 的 `Console.InputEncoding`，并在 finally 恢复；同一路径公开定点随后 accepted 与 publicInputMatched 均为 true。没有放宽秘密输入守门，没有修改产品解析器或添加布局回退。原 actual01 的输入未被记录，以上公开复现不追补其逐字节证明。

actual02 Java 仅将证据 leaf 从 actual01 改为 actual02，行为保持不变。父审绑定 preparation SHA `792e8e172ba7ea02163556ac361f067126cf6dfa8dac0dd5897a7c09034fb8c2`、runner SHA `e6a555af26ee1926e9e7e0c17d994f87839fdf71807d2f83c849a9e54d8bde7a` 和 compile03 SHA `77f8e0738b53e22536d7410a9013feb4eaad2b38a5eb1f1a759587f06e46e379`，仅释放一次实际登记与拒绝未知 key 观察，不自动重试。

## 实际产品结果与信任边界

actual02 正式产品登记成功，`encryptedVault` 与 `currentUserDpapiBootstrap` 均为 true。新独立 source 为 `src/app/db/data/agent-live-fresh-server176-4862bdd0583640b2bd569d955762ab1c`；未覆盖或复用旧配置。

产品连接目标为 `45.192.105.213:22/root`，实际 SSH wire-key 指纹为：

```text
SHA256:sCRc3ICCT9OMtsaGxjciDIQd0NMVO5/+pDe/fNMgRYc
```

Task `1ae0e10c-0ac4-434b-bf49-ff98d068c95e` 为 ENDED/CANCELLED，事件 `SSH_TRUST_CANCELLED`。首次信任回调出现一次，工具返回拒绝，产品 pin 仍为空；这次 CANCELLED 是有意保留人工主机身份确认边界，不是认证失败或部署失败。没有执行 SSH 认证、远端命令、SFTP、provider 请求或部署。

新 key 与重装前用户授权值不同，已向用户展示实际新值请求确认。未收到确认前，不接受新 key、不登录、不启动 178。确认和未来业务结果由后续记录承接，不改写此次拒绝观察。

## 证据与后续

证据根为 `.ai-workspace/agent-live-evidence/agent-autonomy-20261012/`，本阶段目录为 `fresh-server-enrollment-176/` 与 `fresh-server-baseline-177/`。保留原失败、公开输入诊断、修复前后 bytes、编译与独立父审；最终 `sealed-observed-enrollment176.json` SHA 为 `7a3bc1cc3ec6907ffbae6bdb284ec83e1a07910166d41931c0a9e3655a76e10f`。父独立核对其中 15 份原始证据的长度及 SHA，通过。

Runtime 已关闭，正常用户 CIM 核实际 launcher 与 Java 两个 PID/CreationTicks 均退出，marker 绑定的本轮 TMP 与短 UDS 已清理。新 source 保留；关闭后独占原零字节 lock 时得到 `windowstolinux.mv.db` 143360 字节、SHA `4488944018f530b7d720fca36655f41477754e9cf42bd45b51cec6d7774cf1cd`，`db-access.bin` 358 字节、SHA `009733460631e80b9d0f1fe0b23fd0828f80540cee89c2983cac3559c340b285`。这是受保护文件的完整性绑定，不是密码哈希。没有删除原配置、失败 root、调查数据库或其他临时目录。

178 仅准备单个 task-board 的 FULL AGENT/AUTOMATIC 验收草稿；新 source 的关闭后 pair SHA、实际新 key 的用户授权及最终 HEAD 绑定完成前，admission 和运行授权保持 false。部署与审批模型继续自主选择工具和动作，监督者不代部署、不注入服务器命令或旧计划。

本阶段仅执行必要辅助工具公开定点、语法与基线检查，没有全量测试或新业务验收。task-board 完整独立审批、其余七项目、冻结版本后的全新服务器八项目、非默认目录和整机重启恢复仍待验。历史 DENY/UNKNOWN、资源占用与调查证据保留，服务器重装不自动结清这些记录。
