# Agent 构建容量与缓存能力说明（220）

仅补齐两份 Agent 资源中的已证能力事实，静态一致性核对和显式跳测打包通过。未改变隔离执行器、权限、配额、工具接口、配置或数据库；未新增真实部署、provider、SSH、DPAPI 或产品 Runtime 调用。服务器当前容量、模型恢复效果及业务成功仍未验证。

## 已确认的说明缺口

[218 原验收](DEPLOYMENT-AGENT-NATIVE-VALIDATION-LIVE-218.md) 的候选创建回执给出 `134217728` 字节，即 128 MiB 镜像。原日志 3161–3163 的 `104M` 是格式化后的文件系统显示，`/tmp`、`/dev/shm` 和候选 mutable 均为 `/dev/loop20`。3176–3177 的真实失败是在 `/dev/shm/gomodcache` 解包 SQLite 模块时 ENOSPC；切换这些临时路径不会增加容量。

源码 `22-restricted-build.sh:87–117` 对 ordinary 构建设置 `ProtectSystem=strict`、候选 mutable 可写例外，并将 `/tmp`、`/var/tmp`、`/dev/shm` 绑定至同一个 mutable/tmp。`24-build-entry.sh:14–19` 在候选内设置 HOME、TMPDIR、XDG_CACHE_HOME，保持输入源码只读。宿主缓存目录的 chown/chmod 不会扩大这个 namespace 的可写边界。原运行没有独立 mountinfo 观测，不将源码约束冒充远端 mountinfo 实测。

平台没有统一替所有工具设置专用缓存变量，不能推定 Go 的 GOCACHE/GOMODCACHE/GOTMPDIR 都遵循 XDG。每次命令建立新环境，前一次 export 不继承。缓存与 scratch 必须按实际配置观察；这些说明不提供项目命令、预先选定缓存位置或替模型决定下一动作。

此外，`agent-toolchain-controller.py` 的冷安装镜像固定 8 GiB，加 1 GiB 宿主预留；有效匹配注册的 SYSTEM/MANAGED 安装才使用 64 MiB PROBE 镜像及相同预留。原 9 GiB 拒绝是工具链 VOLUME 准入，不是模型 workspace 请求或 Go 实测峰值。PATH 上发现可执行文件不等于已满足注册匹配；promotion 另查实际复制需求。当前匹配状态、工具链文件系统容量和真实峰值均需实际证据。

原 Agent 主策略和诊断 Skill 已说明工作区估算与不可原地改额，但未充分呈现上述同卷临时路径、缓存写权限及独立工具链预算。没有证据要求放宽执行器，本次只修这个说明缺口。

## 改动与验证边界

仅在 `protocol/autonomous-deployment.md` 新增两段、`skill/deployment-diagnosis/SKILL.md` 新增一段。原有全部行保留，包括完整独立审批、新 grant、UNKNOWN 不重放、现有候选配额不可原地更改和整棵输出 seal。说明不承诺跨候选/任务共享缓存、固定 DynamicUser UID、所有 `/dev` 路径只读或空间一定足够。

这两份资源属于 Agent，STATIC/ASSISTED 编排与共享 helper 不变；控制器整数常量、ReadWritePaths、原始输出过滤及审批 policy 未改。219 的拒因逐叶脱敏修复继续保留，不重跑其已通过的 38 个不同用例。

| 检查 | 实际结果 |
| --- | --- |
| 说明与实现 | 与当前 controller 整数政策、helper 的 writable/tmp 绑定、入口环境及原配额约束一致 |
| 原规则与加载边界 | 原有全部行保留；主策略 31,996 B，小于 65,536 B；Skill 5,600 B，小于 8,000 B；UTF-8、frontmatter 有效 |
| 包工具交付前验证 | 新增 13 个纯检查、Python AST 1 项、PowerShell AST 2 项通过；不算 JUnit 或包主体通过 |
| 唯一打包 | `-B -pl src/mod/deployment -am -DskipTests package`，exit0；14 处 skip、0 测试执行，包前后来源检查均 exit0 |
| 来源与产物 | 3309 项清单仅两资源变化；原 22 组完整冻结，新增两资源证明组后为 24 部署组/46 成员/8 资源；宿主 AI 3 族/5 成员不变 |
| 补丁 | 自有副本 git apply 正反向逐字节一致，实际源码 reverse-check 通过 |
| 收尾 | 正常用户 CIM 核对 12 组自有 PID/创建时间均退出，专属 TMP/短 UDS 和补丁副本已清，无强杀或原数据删除 |

本次是低风险说明资源修改，没有新增或运行 JUnit、整类、整模块、全项目测试，也未运行 Java 格式化。静态资源与 source/target/JAR 核对不证明 JVM CodeSource、模型已经读取新说明或真实构建通过。

父 bootstrap 最初误以为两资源已在 219 的 22 个证明组内，在创建 before 快照或修改源码前拒绝。原 helper 和原因保留；实际核实两资源原 JAR 字节后，保持原 22 组不动，再增加这两个证明组。没有新增生产资源文件，证明组增加不等于代码新增两个组件。

## 交付与后续

输入 HEAD `8fdb1c3a15414527a91134292efe229918706f8c`、index 空；输入 deployment.jar 为 `c026c0d0085a0e0667c3adca68be05085b896b7127bb4d3c9959f88b9babf127`。

| 最终对象 | SHA-256 |
| --- | --- |
| 3309 项 after-manifest | `a42f0668f810d70511bd96e7029ca4c432309d94f3a3ee748eabba6f853f5235` |
| deployment.jar | `a66babeaa7a960409a156807dfe455fb20829765c9705a423bb2707e38dacfa9` |
| host AI JAR（未变） | `74ff9dfb37104c23e10baa409a18c2723fc1ab33848e5550af6a6e3ac5429863` |
| package/after-metadata.json | `9a2d8ec03d0c13e37e25957d5bf6ee73c7746f309169d05063fcd5c9d61db817` |
| 两资源精确补丁 | `544f76b9b997ac846f19938a0ee09eb4f1f71a1c3f1d7aedb143fb86c9cb2ece` |

仅本记录、[原 dirty 字节上的两资源精确补丁](patches/AGENT-BUILD-STORAGE-CONTRACT-220.patch) 及单路径 `-text` 属性入本地提交，不整体暂存混合源码、不推送。原迁移、FAILED/UNKNOWN、Runtime、远端资源与调查数据保留，不宣称干净 HEAD 已独立包含当前产品。

证据根为 `.ai-workspace/agent-live-evidence/agent-autonomy-20261012/build-storage-contract-220/`；`implementation/resource-facts-verification220.json` 记录静态来源，`implementation/parent-final-verification.json` 独核包及收尾。219 的 272 份封存原件逐 SHA 未变；Sol XHigh 完成只读调查，Sol High 准备包工具，未调用 Astra。

仍为 CSV Inspector、File Transfer 两个不同项目，累计六次 FULL，本阶段新增 0 次。没有新的 provider usage、缓存或审批占比结论；当前 cache 大于 60% 可推进、审批仅观察的阶段规则不替代原八项目最终标准。

下一阶段应按当前新源/JAR/classpath、实际配置和产品连接重新准入，再由模型自主判断当前容量与可行方案；不沿用旧冻结计划、端口或 UNKNOWN 动作。必要的新 File Transfer 复验仍须独立完整审批、真实前后端及浏览器业务、产品 STOP/DISABLE。完整八项目冻结新服、非默认目录及整机重启验收尚未完成。
