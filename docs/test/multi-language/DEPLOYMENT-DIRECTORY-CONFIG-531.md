# 部署目录配置与阶段交接

2026-10-11：本阶段完成目录提取、实际接线及直接本地验证。按照用户要求，到此停止；八项目部署验收仍未完成。

## 配置

[部署 Mod 的随包配置](../../../src/mod/deployment/src/main/resources/gold/debug/windowstolinux/mod/deployment/config/config.yaml) 使用直接的 `paths` 字段，删除 YAML 中的 `remote` 包装层：

```yaml
paths:
  helper-directory: /usr/local/lib/windowstolinux
  applications-root: /opt/windowstolinux/apps
  state-root: /var/lib/windowstolinux
  configuration-root: /etc/opt/windowstolinux/apps
  data-root: /var/opt/windowstolinux/apps
```

| 字段 | 实际用途 |
|---|---|
| helper-directory | managed-helper、build-entry、受管工具链 |
| applications-root | 各应用的发布目录 |
| state-root | work 候选、锁、快照、备份、安装与命令回执 |
| configuration-root | 各应用配置文件与原配置协议派生目录 |
| data-root | 各应用持久数据 |

全部五个字符串必须齐全。目录必须是规范 Linux 绝对路径，不允许命令、变量展开、遍历、保留控制目录或五根相互包含。修改随包配置后需按原流程构建、加载新 Mod；运行中的原任务不热更新目录。

STATIC、ASSISTED、AGENT 的部署入口捕获相同的完整布局。审批、helper、工具链隔离、工作区、上传、构建、发布、数据库和环境准备使用原捕获；应用管理和采集使用原完整发布身份；归档恢复保留原五根。持久证据的规范布局格式独立于 YAML，未为删去配置包装层重写历史摘要。

默认 helper 仍为协议 16，完整源码 SHA-256 为 `90bacdd769a17245401ce8e426117bbcb87ba4362a46d9cee8129bcd9892859d`。默认发布清单 v1 字节和摘要保留；非默认清单 v2 包含完整布局。读取损坏或缺失的新证据拒绝，不借用当前 YAML 填充。旧默认记录明确使用原固定目录。

## 宿主与恢复边界

固定 `/etc/systemd/system/windowstolinux-workspace-recovery.service` 用作宿主布局锚点。安装前核对所有实际根、单元正文、所有权、符号链接、FragmentPath 和 drop-in；缺失时只原子创建匹配锚点，冲突不覆盖。仅创建布局锚点不启用服务；完整安装校验实际 helper/build-entry 摘要后启用。完整独立审批和单次宿主许可保持。

新恢复候选使用 helper 的实际 `/work` 根，持久保存候选路径版本与完整上传端点、容量。历史 `/candidates` 仅解释原默认旧记录，不同时接受两种路径，不把旧 pending transfer 改投新目标。固定系统程序、systemd 目录、包管理器及历史外部 SELinux 状态检查保持系统语义。

## 实际验证

| 运行 | 范围与结果 |
|---|---|
| 513 | 初始目录配置定点 9 项通过；当时仍有 remote 包装层，不能单独证明最终配置 |
| 518 | 纯布局、原任务捕获、默认/非默认 helper 定点 15 项通过 |
| 524–525 | 生产及测试编译各发现局部静态引用/夹具错误，已修正；未执行业务验收 |
| 526 | 明确选定 144 项：131 通过，13 失败/错误，无跳过 |
| 527 | 仅失败组及直接影响场景 35 项：33 通过，2 失败 |
| 528 | 直接影响场景 12 项：11 通过，1 失败 |
| 529 | 失败与目标合同场景 7 项：6 通过，1 失败 |
| 530 | 修正真实 ownsInstallReview 完整目标匹配后，最后 2 项通过；Maven 退出 0，22:10:39+08 完成 |

最终选定的 144 个场景均有通过结果，失败记录没有删除；没有重复已通过且未受影响的整组测试。各运行冻结 3,747 个源码/测试样例条目，零变化，Java 进程退出。范围包括配置扁平化、五根校验、原任务/发布持久身份、默认字节兼容、实际请求与上传目标、源码完整重建、独立审批拒绝和恢复原目标保护。

安装锚点测试在临时目录运行真实 Bash 文件比较、原子链接和符号链接；root 身份、所有权及 systemctl 为明确的本地替身。它不证明真实 Linux 主机的所有权、systemd 或远端隔离行为。没有调用真实部署/审批 API、SSH 或生产数据库，也没有运行全项目测试、浏览器业务验收和同版八项目批次。

修正的局部问题包括：默认 Bash 空分支；helper 身份携带实际布局；完整安装目标在共享 helper 与 Agent 审批入口一致；测试夹具按 checkpoint key 区分原记录；源码导航按渲染后内容核对。没有模型特化、跳过审批或弱化恢复身份检查。

## 留存与下一目标

源码增量见 [完整精确补丁](patches/DEPLOYMENT-DIRECTORY-WIRING-531.patch)，原始 SHA、明确 selector 和 Maven 日志保留在 `.ai-workspace/agent-live-evidence/agent-root-cause-20261010/deployment-directory-wiring-521/`。补丁已在规范 LF 镜像反向检查通过。工作区已有其他迁移修改：本地提交仅包含可独立暂存的自有源码、索引增量及混合文件的精确自有补丁，不整体提交其他基线，不推送；未证明独立干净 checkout 可构建。

最后真实结果是 509 原 task-board 恢复结清并产品停止/禁用；这不等于 task-board 业务成功。484 可用空间记录足够，未再次探测。原 file-transfer 核心业务覆盖 1/8，425 整条测试仍保留 FAIL。跨版本用量和未知请求保留，不冒充新的完整八项目成本。

下一目标先读取 [CURRENT](../../../.ai-workspace/progress/CURRENT.md)，仅将 task-board 完成部署、真实前后端业务验证和产品收尾，再串行推广其他项目。重新识别新任务的来源、API/URL、CORS、监听与端口；不要改写或重放原冻结错误计划。用户另行启动目标前，不继续部署、付费取证、缓存优化、Agent 重构或恢复记录清理。
