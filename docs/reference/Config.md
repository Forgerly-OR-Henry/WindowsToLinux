# 固定配置填写说明

本文件集中说明 APP 和官方部署 Mod 的固定配置字段。两者各自读取所属模块的随包 YAML，不共享加载器或配置资源。`app/config` 不依赖其他项目模块；main 在存储、服务和窗口创建前读取 Config 模块资源，合并为一次不可变 `AppConfig` 快照并注入消费者。部署 Mod 的字段见文末“部署 Mod 固定参数”。配置不承载密码、私钥、API Key 或运行状态。

## 文件位置与模式覆盖

配置源文件位于 [Config 模块资源目录](../../src/app/config/src/main/resources/gold/debug/windowstolinux/app/config/config.yaml)：`src/app/config/src/main/resources/gold/debug/windowstolinux/app/config/config.yaml`。Maven 自动将它复制到模块构建输出并打入 Config JAR；三种模式均通过 classpath 读取 `/gold/debug/windowstolinux/app/config/config.yaml`，不读取程序根或工作目录中的同名文件。

YAML 的资源位置不改变数据目录的相对路径基准：

| 模式 | 相对 database.data-directory 的基准 | 发布默认数据目录 |
|---|---|---|
| CLASS | 已验证工作区根目录 | src/app/db/data |
| JAR | 最外层主入口 JAR 所在目录 | data |
| APP | EXE 所在目录 | data |

顶层仅允许 `common`、`class`、`jar`、`app` 四个区。通用字段按叶字段合并 `common` 与当前模式区；模式区明确填写的值优先，未填写的字段继承 common。唯一例外是 `mod.auto-load`：只允许填写 `class.mod.auto-load`，不能写入 common、jar 或 app，填写到这些区会报错。JAR/APP 固定加载 Mod，不存在配置开关。其他模式的合法字段不影响当前模式。区及中间映射允许省略或填写 `{}`，但合并后本说明列出的通用叶字段必须齐全；CLASS 还必须填写专属 Mod 开关。没有隐藏的加载器默认回退。表中默认值是随程序发布的 YAML 值。`null` 不代表继承，任何区的 null、未知字段或重复键均报错。有效区的错误类型、非法值、语法错误、缺失资源和缺失生效字段都停止启动并提示配置位置及原因。单文件上限 256 KiB。

修改资源 YAML 后需重新构建并重启；CLASS 使用构建输出中的资源，JAR/APP 需重新打包 Config JAR。升级时如需保留定制配置，应先合并资源 YAML 再构建。配置不提供 ENV 覆盖、命令行字段覆盖、外置文件覆盖、运行时修改或热加载。路径可以明确填写绝对路径；Windows 示例建议使用 `/` 或 YAML 单引号。切换路径不自动搬迁、复制、覆盖或回退到已有数据库，迁移时应自行完整处理配对文件。用户在 DB 中保存的语言、主题、连接阈值及删除说明设置优先于 YAML 默认值；新数据库通过初始化接口接收删除说明初始偏好，已有数据库值不被改写。

## 字段填写表

以下键省略区名前缀；通用字段填写位置为 `common.<键>` 或对应模式 `<模式>.<键>`，Mod 自动加载开关仅填写 `class.mod.auto-load`。除商店地址允许空字符串外，字符串不得留空；布尔值必须使用不带引号的 true/false，整数不能使用小数或带引号文本。通用叶字段允许从模式区省略并继承 common，合并后的有效配置不能省略。

### 数据目录

| 配置键 | 用途 | 类型／单位 | 默认值 | 允许值／范围 | 填写示例 | 生效模式 |
|---|---|---|---|---|---|---|
| `database.data-directory` | H2、配对材料及所属运行数据的根目录 | 字符串 / 路径 | common: `data`；class: `src/app/db/data` | 非空合法路径；禁止 `;`、NUL；相对或绝对均可 | `"D:/WTL/data"` | CLASS / JAR / APP |

### 界面初态

| 配置键 | 用途 | 类型／单位 | 默认值 | 允许值／范围 | 填写示例 | 生效模式 |
|---|---|---|---|---|---|---|
| `ui.navigation-collapsed` | 本次启动导航栏初始折叠 | 布尔 | false | true / false | `true` | CLASS / JAR / APP |
| `ui.advanced-expanded` | CLASS 高级区域初始展开 | 布尔 | false | true / false | `true` | CLASS；JAR/APP 可填写但不展示高级区 |

### 用户设置默认值

| 配置键 | 用途 | 类型／单位 | 默认值 | 允许值／范围 | 填写示例 | 生效模式 |
|---|---|---|---|---|---|---|
| `defaults.locale` | DB 未存语言时的默认语言 | 字符串 | system | system / zh-CN / en-US | `zh-CN` | CLASS / JAR / APP |
| `defaults.theme` | DB 未存主题时的默认主题 | 字符串 | SYSTEM | SYSTEM / LIGHT / DARK | `DARK` | CLASS / JAR / APP |
| `defaults.connect-seconds` | DB 未存连接超时的默认值 | 整数 / 秒 | 30 | 1～86400 | `45` | CLASS / JAR / APP |
| `defaults.idle-seconds` | DB 未存无进展超时的默认值 | 整数 / 秒 | 300 | 1～86400 | `600` | CLASS / JAR / APP |
| `defaults.delete-ai-explanation` | 新数据库删除 AI 说明的初始偏好 | 布尔 | true | true / false | `false` | CLASS / JAR / APP |

### SSH 参数

| 配置键 | 用途 | 类型／单位 | 默认值 | 允许值／范围 | 填写示例 | 生效模式 |
|---|---|---|---|---|---|---|
| `ssh.heartbeat-seconds` | SSH 心跳间隔 | 整数 / 秒 | 30 | 1～86400 | `30` | CLASS / JAR / APP |
| `ssh.recovery.observation-seconds` | 救援观察间隔 | 整数 / 秒 | 2 | 1～86400 | `2` | CLASS / JAR / APP |
| `ssh.recovery.probe-interval-seconds` | 救援恢复验证间隔 | 整数 / 秒 | 10 | 1～86400 | `10` | CLASS / JAR / APP |
| `ssh.recovery.probe-timeout-seconds` | 单次救援验证超时 | 整数 / 秒 | 15 | 1～86400 | `15` | CLASS / JAR / APP |

### 模型调用参数

| 配置键 | 用途 | 类型／单位 | 默认值 | 允许值／范围 | 填写示例 | 生效模式 |
|---|---|---|---|---|---|---|
| `ai.connect-seconds` | 文字、视觉、文件语音 HTTP 及实时语音 WebSocket 连接时限上限；同时受请求总超时限制 | 整数 / 秒 | 30 | 1～86400 | `30` | CLASS / JAR / APP |
| `ai.recommended-max-output-tokens` | 普通模型调用未指定额度时的推荐最大输出 Token；关闭覆写时强制使用 | 整数 / Token | 32768 | 1～2147483647 | `32768` | CLASS / JAR / APP |
| `ai.allow-output-token-override` | 允许调用方为本次普通模型调用覆写推荐额度 | 布尔 | true | true / false | `false` | CLASS / JAR / APP |
| `ai.verification-max-output-tokens` | 文字、视觉及原生工具能力验证的独立输出额度 | 整数 / Token | 8192 | 1～2147483647 | `8192` | CLASS / JAR / APP |
| `ai.reasoning.default-level` | 仅初始化缺失的模型用途强度 | 枚举 | MEDIUM | DEFAULT / MINIMAL / LOW / MEDIUM / HIGH / XHIGH / MAX；使用已收录官方映射，无映射则提供方默认 | `MEDIUM` | CLASS / JAR / APP |
| `ai.reasoning.default-mode` | 只有标准/思考模式的模型的初始化选择 | 枚举 | STANDARD | STANDARD / THINKING | `STANDARD` | CLASS / JAR / APP |
| `ai.native-state.max-states` | 每个原任务所有用途共享的 native 状态数量上限 | 整数 / 条 | 4096 | 1～2147483647 | `8192` | CLASS / JAR / APP |
| `ai.native-state.max-total-bytes` | 每个原任务全部 native 状态实际 UTF-8 载荷上限 | 整数 / 字节 | 67108864 | 1～9223372036854775807，且不小于 max-value-bytes | `134217728` | CLASS / JAR / APP |
| `ai.native-state.max-value-bytes` | 单个 native 状态实际 UTF-8 载荷上限 | 整数 / 字节 | 1048576 | 1～1048576，受公共 continuation 契约约束 | `1048576` | CLASS / JAR / APP |
| `ai.recovery.timeout-seconds` | 救援模型请求总超时 | 整数 / 秒 | 60 | 1～86400 | `60` | CLASS / JAR / APP |
| `ai.deletion.timeout-seconds` | 删除审核请求总超时 | 整数 / 秒 | 30 | 1～86400 | `30` | CLASS / JAR / APP |
| `ai.file.timeout-seconds` | 文件判断请求总超时 | 整数 / 秒 | 20 | 1～86400 | `20` | CLASS / JAR / APP |

普通模型调用统一由 APP 服务解析额度。允许覆写时，Mod 省略请求额度使用推荐值，传入正整数则原样使用；关闭覆写时始终使用推荐值，但非法请求值仍拒绝。APP 内部的 SSH 救援、删除审核和文件判断均省略请求额度，统一使用推荐值，不受覆写开关影响；三者仅分别配置请求超时，不再提供 `max-tokens` 字段。能力验证始终使用独立验证额度，不受开关影响；语音转写不发送输出 Token 参数。推荐额度不表示模型能力上限，平台不按旧模型字段裁剪，也不推测提供方上限；提供方拒绝沿用原错误处理。

模型编辑器中的旧输出额度字段及其 DB 存取暂留，页面本次不调整。该字段不再参与调用、能力验证或冻结路由；仅修改它不使验证失效。推荐值和覆写开关纳入冻结调用修订，启动快照改变后原任务仍须通过续接检查。

native 状态预算为平台内存资源策略，按原任务汇总用途，保留独立会话的合法旧句柄；当前请求未引用不构成释放依据。达到任一容量边界时明确失败；provider 已成功但本地保存失败的响应仅交付实际计量，清空文本、工具及 continuation，并封闭该 purpose 会话。此失败不证明原部署副作用已结清。详细作用域、历史窗口与结构上界见[Agent运行预算配置](../test/multi-language/DEPLOYMENT-AGENT-RUNTIME-LIMITS.md)。

### 日志参数

| 配置键 | 用途 | 类型／单位 | 默认值 | 允许值／范围 | 填写示例 | 生效模式 |
|---|---|---|---|---|---|---|
| `logs.emergency-records` | 每类应急日志新增记录时的保留数量 | 整数 / 条 | 100 | 1～100；旧日志读取及回填安全上限仍为 100 | `100` | CLASS / JAR / APP |

### Mod 参数

| 配置键 | 用途 | 类型／单位 | 默认值 | 允许值／范围 | 填写示例 | 生效模式 |
|---|---|---|---|---|---|---|
| `mod.auto-load` | 源码开发启动时是否先编译，再发现并加载 Mod | 布尔 | class: false | true / false；只允许 class 区 | `false` | 仅控制 CLASS 自动加载；Mod 管理界面始终保留，JAR/APP 固定加载 |
| `mod.store.base-url` | 商店目录及下载服务基地址 | 字符串 / URL | 空字符串 | 允许 ""；否则绝对 HTTPS，或仅 `127.0.0.1` / `[::1]` 的 HTTP；禁止用户信息、查询、片段和非法端口 | `"https://mods.example.com/api"` | CLASS / JAR / APP |

## Mod 自动加载与商店边界

CLASS 默认关闭自动加载，JAR/APP 固定装配并发现 Mod。`class.mod.auto-load` 仅控制启动时是否主动读取、发现并加载 Mod：`false` 时不扫描 Mod 源码、清单、配置、发行包或开发构建输出，不自动登记新构建、建立类加载器或调用 Mod 生命周期。工作区、Mod 管理、Mod 设置、固定导航容器和商店始终保留；管理页可读取数据库中已有的登记、用户选择和待处理操作，平台照常初始化管理服务及恢复账本，不会因此读取或启动 Mod 代码。尚未加载的 Mod 不会发布业务页面或生成自身根入口。CLASS 仍不能安装发行包，原始包页保留源码模式说明。APP 的数据库结构检查、安全、任务、SSH、模型配置及日志仍正常初始化。CLASS 开启时，无需 `-Dwtl.workspace` VM 参数；平台根据 AppMain 与 DB 的实际代码来源验证工作区，只发现其中与 `src/app` 并列的 `src/mod`，不从启动目录搜索，也不扫描独立开发区 `src/moddev`。启动专用刷新为首次新身份选择本次唯一有效构建版本并启用，必要数据准备仍经明确审阅和下一适用启动。已有禁用、选中版本及待重启意图保留。JAR/APP 继续原安装和用户启用规则，不继承 CLASS 首次自动启用。

CLASS 开启自动加载时，开始发现前，使用当前 JDK 及本机 Maven 执行根工程 `-B -ntp -DskipTests process-test-classes`，刷新 Mod 主类、资源和开发描述；该阶段会编译相关测试源码和测试支持 JAR，但不执行测试，不运行 `clean`、打包或安装。Maven 从 `maven.home` 系统属性、`MAVEN_HOME`、`M2_HOME`、`PATH` 依次定位；构建输出保存在工作区 `target/mod-startup-build.log`。编译失败、工具不可用、中断或超时均停止本次 Mod 加载，不回退到旧产物；主 App 继续提供构建失败反馈。关闭自动加载和 JAR/APP 启动均不触发源码构建。

商店沿此地址获取完整目录及包；真实客户端、选择清单和安装批次已通过 1.3 S5 本机验收，验收状态以 [该期记录](../development/PHASE-1.3.md#dependency-download)为准。正式服务地址由用户明确填写，不预设已经上线；空地址不发起网络请求，连接失败可明确重试，不后台轮询。HTTP 仅用于精确数字回环地址的本机受控服务，`localhost`、其他 HTTP 主机及近似域名均拒绝；正式远端使用 HTTPS。包地址保持同源，不跟随重定向。CLASS 可以查询商店，不受自动加载开关影响，仍不能安装发行包；安装不会自动启用或切换版本。

入口排序、窗口尺寸、居中、布局、安全与协议常量不属于 YAML。外观中的平台入口顺序和固定 Mod 入口顺序进入 DB，分别使用 `platform_navigation_order` 和 `mod_navigation_pins`；隐藏项保留位置，新项追加，设置按钮保持底部。平台恢复默认按代码顺序，Mod 恢复默认按 ownerId/pageId 排序，不取消固定。损坏偏好报错并保留原值。

外观按语言、主题、缩放、界面动画、启动页面这一组顺序维护。`app.desktop_preference.ui_animation_enabled` 保存 `true`／`false`，缺失时开启，非法值报错并保留原记录。启动先读取此值再创建窗口。界面动画开关立即生效、单独自动保存，不提交主题、缩放等未保存草稿；连续切换按最后一次选择串行写入。保存失败保留本次会话选择，并显示未保存及具体原因。开启时仍遵守 Windows 客户区动画偏好和 `-Dflatlaf.animation=false`。

启动页面同样由 DB 偏好管理，不新增 YAML 配置：`ui_restore_last_page` 保存恢复开关（缺失时为 false），`ui_last_page` 保存正常关闭时的页面身份 JSON；未启用恢复或找不到原页面时进入首页。记录不包含输入草稿、弹窗或凭据。

模型编辑窗口分别选择厂商适配与线协议，推理偏好在模型详情中按语言、审批、视觉用途独立保存。偏好键为 `ai_model_reasoning_<language|approval|vision>_<模型标识>`，厂商键为 `ai_model_provider_<模型标识>`；标识连字符映射为下划线。旧 `ai_model_reasoning_effort_<模型标识>` 单值只初始化尚不存在的用途偏好，`NONE` 迁移为 `STANDARD`；旧值及已存在偏好不覆盖。新模型缺失偏好由 Config 初始化：优先中等及官方映射，只有标准/思考模式时默认标准，无映射或未知型号使用提供方默认。Config 改动不覆盖数据库。

公共 API 可以查询全部模型、用途推荐、能力和当前用途强度，也可在请求中指定模型配置 ID 或仅覆写当前请求的强度。未指定强度时继承所选模型对应用途的偏好；显式 `DEFAULT` 省略提供方控制，区别于关闭思考。指定模型不要求加入推荐用途，但仍需能力验证，不提供跨模型后备；推荐路由保留已知 HTTP 拒绝时的顺序后备。部署 Mod 暂不设置覆写。强度纳入冻结修订与原生续接身份，非法值或不支持的明确选择报错，不静默降档。

## 完整可加载示例

此示例的字段和值与 Config 模块资源目录中的 `config.yaml` 一致，可直接保存到上述资源文件后重新构建。资源文件中全部字段另有用途和单位注释。修改时保持全部有效叶字段齐全。

```yaml
common:
  database:
    data-directory: data
  ui:
    navigation-collapsed: false
    advanced-expanded: false
  defaults:
    locale: system
    theme: SYSTEM
    connect-seconds: 30
    idle-seconds: 300
    delete-ai-explanation: true
  ssh:
    heartbeat-seconds: 30
    recovery:
      observation-seconds: 2
      probe-interval-seconds: 10
      probe-timeout-seconds: 15
  ai:
    connect-seconds: 30
    recommended-max-output-tokens: 32768
    allow-output-token-override: true
    verification-max-output-tokens: 8192
    reasoning:
      default-level: MEDIUM
      default-mode: STANDARD
    native-state:
      max-states: 4096
      max-total-bytes: 67108864
      max-value-bytes: 1048576
    recovery:
      timeout-seconds: 60
    deletion:
      timeout-seconds: 30
    file:
      timeout-seconds: 20
  logs:
    emergency-records: 100
  mod:
    store:
      base-url: ""
class:
  database:
    data-directory: src/app/db/data
  mod:
    auto-load: true
jar: {}
app: {}
```

结构与依赖见 [File.md](File.md#module-dependencies)，运行路径见 [Runtime.md](Runtime.md#desktop-data)，发行携带规则见 [APP.md](APP.md)，Mod 作者的独立配置职责见 [Mod.md](Mod.md)。

## 部署 Mod 固定参数

源文件位于 [部署 Mod 配置资源](../../src/mod/deployment/src/main/resources/gold/debug/windowstolinux/mod/deployment/config/config.yaml)，通过本 Mod 的 classpath 读取 `/gold/debug/windowstolinux/mod/deployment/config/config.yaml`。`DeploymentConfigLoader` 在 `Mod.start` 登记服务前加载配置；同一选中 Mod 类加载器只加载一次，服务和页面共享不可变 `DeploymentConfig`。部署 Mod 不依赖 APP Config 实现，也不读取外置同名文件、环境变量或命令行字段覆盖。

全部六组及 `ai.requests`、`ai.execution`、`ai.history` 的叶字段必须齐全，配置键见下表。缺失、未知、重复、null、类型错误、小数、溢出、越界和多个 YAML 文档均报错，不提供加载失败后的默认回退。目录必须为规范 Linux 绝对路径，五根不得相同或互相包含，禁止系统保留目录。单文件上限 64 KiB。修改后重新构建并重启；CLASS 使用部署 Mod 构建输出，JAR/APP 需重新打包并按原 Mod 安装和版本选择流程使用新构建。

| 配置键 | 用途 | 单位 | 默认值 | 允许范围 |
|---|---|---|---|---|
| `ai.deployment-max-output-tokens` | 普通部署/Agent 调用申请的最大输出额度 | Token | 32768 | 1～2147483647 |
| `ai.extended-deployment-max-output-tokens` | 扩展部署分析申请的最大输出额度 | Token | 65536 | deployment-max-output-tokens～2147483647 |
| `ai.approval-max-output-tokens` | 每次独立审批申请的最大输出额度 | Token | 65536 | 1～extended-deployment-max-output-tokens |
| `ai.requests.deployment-timeout-seconds` | 单次普通部署模型请求时限 | 秒 | 120 | 1～86400 |
| `ai.requests.approval-timeout-seconds` | 单次审批模型请求时限 | 秒 | 600 | 1～86400 |
| `ai.requests.extended-timeout-seconds` | 单次扩展部署模型请求时限 | 秒 | 600 | 普通与审批时限最大值～86400 |
| `ai.execution.shared-tool-attempts` | 一次协调作用域内共享工具尝试上限，嵌套继续扣减 | 次 | 4096 | 1～2147483647 |
| `ai.execution.standalone-tool-attempts` | 没有外层协调作用域时的独立工具尝试上限 | 次 | 256 | 1～shared-tool-attempts |
| `ai.execution.local-tool-turns` | 单个局部循环的响应与工具尝试预算 | 次 | 128 | 1～standalone-tool-attempts |
| `ai.execution.agent-model-responses` | 一次主Agent协调循环的响应预算 | 次 | 512 | 1～536870911，避免每响应4个工具的计数乘法溢出 |
| `ai.execution.retained-review-sessions` | 留存的不同原审批请求会话身份上限 | 个 | 4096 | 1～shared-tool-attempts |
| `ai.execution.max-observations` | 原任务可留存的观察证据数量上限 | 条 | 4096 | 1～999999，保留既有证据ID格式 |
| `ai.history.max-batches` | 滚动保留的完整工具响应批次上限 | 批 | 24 | 1～2147483647 |
| `ai.history.characters` | 单份历史文本窗口 | UTF-16码元 | 131072 | 1～1048576 |
| `ai.history.payload-bytes` | 单份历史实际 UTF-8 与 opaque 载荷窗口 | 字节 | 262144 | 1～1048576 |
| `ai.history.initial-batches` | 保留的初始批次数量 | 批 | 2 | 1～max-batches |
| `ai.history.initial-payload-bytes` | 初始批次载荷窗口 | 字节 | 8192 | 1～payload-bytes |
| `source.bytes` | 新任务来源准入字节上限 | 字节 | 2147483648 | 1～2147483648 |
| `source.entries` | 新任务来源准入条目上限 | 条 | 100000 | 1～100000 |
| `source.archive-bytes` | 新任务压缩归档上限 | 字节 | 2147483648 | 1～2147483648 |
| `build.timeout-seconds` | 构建表单初始命令时限 | 秒 | 1800 | 60～7200 |
| `build.processes` | 构建表单初始进程上限 | 个 | 1024 | 8～4096 |
| `build.memory-mib` | 构建表单初始内存上限 | MiB | 4096 | 256～262144 |
| `build.output-bytes` | 构建表单初始捕获输出上限 | 字节 | 4194304 | 4096～134217728 |
| `build.workspace-bytes` | EXPLICIT 明确输入时的初值；AUTO 不直接采用该值 | 字节 | 4294967296 | 67108864～137438953472 |
| `build.workspace-max-bytes` | 自动或明确工作区额度的政策上限 | 字节 | 137438953472 | 67108864～137438953472 |
| `build.workspace-host-reserve-bytes` | 候选整图共享的主机空间余量，仅计一次 | 字节 | 1073741824 | 1073741824～137438953472 |
| `environment.install-seconds` | 环境准备软件包命令时限 | 秒 | 900 | 1～7200 |
| `environment.check-seconds` | 环境准备其他固定命令时限 | 秒 | 30 | 1～7200 |
| `environment.reboot-seconds` | 环境准备重启观察预算 | 秒 | 900 | 1～7200 |
| `environment.poll-seconds` | 环境准备重启观察间隔 | 秒 | 5 | 1～reboot-seconds |
| `view.refresh-ms` | 所属任务反馈刷新间隔 | 毫秒 | 750 | 100～60000 |
| `view.page-size` | 标准、自主和应用查询的任务分页容量 | 条 | 200 | 1～200，受平台分页合同约束 |

构建表单默认选择 AUTO 工作区额度。STATIC 依据实际归档和已识别固定构建配方估算，ASSISTED 在本模式分析中提供有界需求，AGENT 在组件计划中声明有源码依据的 minimum/preferred 构建预算或 EXPLICIT 明确额度。三者都结合当前主机空间、来源开销、政策上限和保留余量选定实际字节数；估算不等于已测得依赖体积，也不保证未来构建成功。未知下载或容器体量需要明确预算，不能默认为足够。

STATIC/ASSISTED 在最终确认前选额并显示依据；AGENT 在计划接受阶段绑定选额。创建前重新核实主机容量，空间变化只能拒绝，不能悄改已确认额度。创建后的工作区不能在线扩缩，构建与恢复沿用原任务的额度和配置身份。EXPLICIT 输入保持精确值，低于来源最低需要或超出政策、主机约束时拒绝。独立 Agent 工具链卷仍采用自己的 8 GiB 预算，工作区选额不代表工具链空间已满足。固定配置不授予 root 构建权限。来源请求也保存本次准入值，后续恢复不会重新套用当前 YAML。分页容量不改变历史结果窗口、日志上限或数据库分页规则。

新环境任务在初始范围确认后、远端操作前，在同一原任务保存 `deployment.config.environment.v1` 非秘密元数据检查点并立即释放其占用。原任务恢复读取已捕获时限，不受新 YAML 影响；缺少该元数据的旧环境任务仍使用原有 900/30/900/5 秒时限。原环境操作日志的版本、任务类型、远端操作身份和数据库结构保持不变；损坏元数据拒绝恢复，不改用新值。

三项 AI 输出额度通过 `AiRequest.maxOutputTokens` 可选字段明确申请，所有部署模式和原任务恢复共用该配置。APP 开启 `allow-output-token-override` 时采用申请值；关闭时采用 APP 推荐值，因此这些字段不是能覆盖 APP 策略的硬限制。实际采用额度以 `AiResponse.effectiveMaxOutputTokens` 或错误元数据为准；诊断分别记录申请值和实际值，失败不裁剪重试。普通及审批请求时限默认120秒，扩展默认600秒，均从 `ai.requests` 读取；完整证据、独立审批和输入体积监督保持。修改随包 YAML 后重新构建、加载 Mod 才生效，不在运行中的任务内热更新。

工具、响应、审查身份留存和历史窗口使用 `ai.execution` 与 `ai.history`，均为有限资源预算；任一先达到即可拒绝，不能理解为所有最坏嵌套都可同时跑满。审批模型通过实时Skill自行判断重复讨论是否必要，`retained-review-sessions` 只控制身份留存容量，不规定讨论轮数。公共非视觉模型请求最终仍受1 MiB编码载荷约束，窗口扩大不会截断完整审查输入来伪造投递完成。

`service/core/config` 继续管理应用部署的业务配置、作用范围和不可变修订。审批偏好、模型选择、累计 Token 监督预算、提示词、helper 协议和远端固定脚本继续沿各自边界管理；用户偏好和已提交业务配置使用原有持久化流程。完整运行预算说明及未验证范围见[Agent运行预算配置](../test/multi-language/DEPLOYMENT-AGENT-RUNTIME-LIMITS.md)。

### 部署服务器目录

`paths` 的五根统一用于 STATIC、ASSISTED 和 AGENT。根目录以下的固定层级由部署协议派生，不再从当前配置重算旧任务的路径。

| 配置键 | 默认目录 | 用途 |
|---|---|---|
| `helper-directory` | `/usr/local/lib/windowstolinux` | managed-helper、build-entry、受管工具链 |
| `applications-root` | `/opt/windowstolinux/apps` | 各受管应用的发布目录 |
| `state-root` | `/var/lib/windowstolinux` | work 候选、锁、快照、备份及操作回执 |
| `configuration-root` | `/etc/opt/windowstolinux/apps` | 各应用的配置文件 |
| `data-root` | `/var/opt/windowstolinux/apps` | 各应用的持久数据 |

新部署在读取来源和产生远端副作用前捕获五根。独立审批、实际 helper、工具链、上传与发布使用同一捕获；恢复使用原任务证据，应用管理和归档恢复使用原完整发布清单。历史默认记录继续解释为原固定目录，修改 YAML 不迁移既有应用，也不重写待处理命令或上传目标。

安装前通过固定的 `/etc/systemd/system/windowstolinux-workspace-recovery.service` 核对宿主布局，拒绝不同布局、符号链接、外部片段和 drop-in。仅以目录绑定创建该文件不启用服务；完整安装在校验已安装 helper 和 build-entry 摘要后登记服务。固定 systemd、系统包管理、可信系统程序及历史外部 SELinux 状态检查不随目录配置改写。

### 模型设置与 YAML 的边界

模型协议、完整接口 URL、用途顺序及验证状态由 APP 模型页面保存到数据库；实际输出额度策略由 APP 随包 YAML 提供，部署 Mod 只传入本次申请额度。旧模型编辑器额度暂留 DB，但不参与调用和验证。文字与工具能力须分别验证；协议修改后的失效规则见 [Runtime](Runtime.md#model-protocols)，填写与操作见 [产品手册](PRODUCT-MANUAL.md#16-app-部署模式与模型用途)。原生请求与续接公开合同见 [Mod.md](Mod.md#native-ai-contract)。
