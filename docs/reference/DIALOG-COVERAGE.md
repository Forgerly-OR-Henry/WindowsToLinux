# 弹窗统一覆盖清单

本清单覆盖当前 APP 与官方部署、文件 Mod 的业务弹窗。系统文件选择器保留原交互，第三方 Mod 内部界面不在改造范围；Git Mod 当前没有自建 Swing 对话框。视觉和交互规则见 [UI 规范](UI.md#8-弹窗布局与生命周期)。

## 生产入口

| 所属 | 弹窗内容与入口 | 共用布局入口 |
| --- | --- | --- |
| APP 任务交互 | TaskInteractionPresenter：完整操作审阅／展开详情、删除效果选择、普通／秘密输入、本地目录、文件冲突、传输冲突与停滞 | DesktopDialogs.create；保留原任务取消计时器及返回值 |
| APP 任务管理 | TaskRecoveryPane：取消、归档、救援及未知结果核对 | DesktopDialogs.create；保留所属确认及隐私代次 |
| APP 设置 | ServerProfileDialog、PlatformModelDialog、DesktopUnlockDialog | DesktopDialogs.install；保留编辑器及密码清理合同 |
| APP 服务器 | PlatformServersPane：删除／核对；SshRecoveryDialog：人工登录交接及精确命令批准 | DesktopDialogs 的确认、安装及尺寸管理 |
| APP Mod 管理 | PlatformModsPane：来源选择、维护确认、待生效修改及修复文件选择；ModOriginalsPane：原包确认；ModStoreConfirmation：完整安装计划 | DesktopDialogs；保留原审阅窗口、清理及任务归属 |
| APP 外壳 | PlatformFrame：启动／管理错误、问题详情、工作区关闭、重启错误和进度；DesktopLockController：设密引导及退出提示；CardOrderSession：排序冲突与保存错误 | DesktopDialogs 消息／选项及固定操作壳 |
| APP 调试 | UiDebugDialog 的画廊、服务器和模型预览；UiDebugPreviewHost 的作者预览外壳 | DesktopDialogs；预览不持有真实保存、SSH或模型执行能力 |
| APP 启动错误 | ApplicationLauncher、DesktopUncaughtFailureBoundary 的无主窗口错误 | DesktopDialogs 有界消息；保留原失败报告及退出合同 |
| 部署标准流程 | DeploymentStandardPageReviewer：组件、运行参数、构建纠正、数据库来源／软件／替换／恢复／结构／密码、最终确认；独立保存来源确认 | DeploymentStandardPage 审阅窗口及 DeploymentDialogs |
| 部署其他窗口 | 高级设置、服务器选择、应用编辑、发布历史、发现／接管、重新关联及日志 | 部署自身 UI 样式层；保留原窗口模态性和业务能力 |
| 文件 Mod | FilesDialogs：新建／重命名、位置替换、删除范围、服务器与绝对路径、移动链接 | 文件 Mod 私有窗口支持；保留草稿、显式目标及撤销 |

## 真实窗口矩阵

| 布局状态组 | 每组状态数 | 生产窗口验证入口 |
| --- | ---: | --- |
| 服务器／模型编辑、解锁、长消息确认、文件选择外壳 | 5 | DesktopDialogsTest |
| 任务输入、折叠／展开审批、删除选择、文件与传输冲突、停滞、目录选择；SSH交接／命令／高影响确认；调试画廊、商店确认 | 16 | DesktopDialogCoverageTest |
| 待生效修改、重启进度 | 2 | DesktopOwnedDialogMatrixTest |
| 部署组件、运行参数、最终确认、高级、服务器、应用编辑、历史、发现、日志、重新关联 | 10 | DeploymentDialogVisualTest；固定数据由 DeploymentDialogPreview 复用生产构造 |
| 新文件夹、新文件、重命名、替换位置、删除范围、远端路径、链接选择 | 7 | FilesDialogLayoutTest |

每种状态覆盖24组合，即两种语言、两种主题、三档缩放及正常／受限工作区，共960组截图与几何检查。数据库审阅步骤复用部署自然表单壳，另由实际数据库纠正、密码及同任务流程的定点用例验证内容、清理和返回值。参数组合、最终结果及保留失败以本轮证据报告为准，重复复验不重复计数。

## 验证口径

- 视觉验证使用真实创建并显示的 Swing JDialog，读取实际布局并通过 Swing 绘制输出；不使用生成图片代替程序界面。输出不包含操作系统桌面合成特效。
- 矩阵为中英文、浅深主题、100／125／150缩放、正常及受限工作区。受限工作区由生产使用的完整窗口约束算法接收较小矩形，不能称为实际切换系统分辨率或多显示器验收。
- 不同确认文案和数据库步骤复用同一生产审阅壳；布局矩阵与真实业务返回值／撤销测试分别核对。
- 链接全文及SSH同意说明直接换行显示；原复选模型、默认未选和标签切换行为保留。视口按当前可用宽度测量正文高度，不能依赖上一帧的字段宽度而少算最后一项。
- 实际定点结果、截图、首次失败与修正记录集中保存在 `.ai-workspace/evidence/dialog-unification-20261024/`。未进行真实服务器部署、外部模型调用或全项目测试。
