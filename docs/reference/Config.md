# 固定配置填写说明

本文件集中说明 APP 固定配置和部署 Mod 的输出额度配置。两者读取各自所属模块的随包 YAML。`app/config` 不依赖其他项目模块；main 在存储、服务和窗口创建前读取 Config 模块的 YAML 资源，合并为一次不可变 `AppConfig` 快照并注入消费者。配置不承载密码、私钥、API Key 或运行状态。

## 文件位置与模式覆盖

配置源文件位于 [Config 模块资源目录](../../src/app/config/src/main/resources/gold/debug/windowstolinux/app/config/config.yaml)：`src/app/config/src/main/resources/gold/debug/windowstolinux/app/config/config.yaml`。Maven 自动将它复制到模块构建输出并打入 Config JAR；三种模式均通过 classpath 读取 `/gold/debug/windowstolinux/app/config/config.yaml`，不读取程序根或工作目录中的同名文件。

YAML 的资源位置不改变数据目录的相对路径基准：

| 模式 | 相对 database.data-directory 的基准 | 发布默认数据目录 |
|---|---|---|
| CLASS | 已验证工作区根目录 | src/app/db/data |
| JAR | 最外层主入口 JAR 所在目录 | data |
| APP | EXE 所在目录 | data |

顶层仅允许 `common`、`class`、`jar`、`app` 四个区。通用字段按叶字段合并 `common` 与当前模式区；模式区明确填写的值优先，未填写的字段继承 common。唯一例外是 `mod.auto-load`：只允许填写 `class.mod.auto-load`，不能写入 common、jar 或 app，填写到这些区会报错。JAR/APP 固定加载 Mod，不存在配置开关。其他模式的合法字段不影响当前模式。区及中间映射允许省略或填写 `{}`，但合并后 21 个通用叶字段必须齐全；CLASS 还必须填写专属 Mod 开关。没有隐藏的加载器默认回退。表中默认值是随程序发布的 YAML 值。`null` 不代表继承，任何区的 null、未知字段或重复键均报错。有效区的错误类型、非法值、语法错误、缺失资源和缺失生效字段都停止启动并提示配置位置及原因。单文件上限 256 KiB。

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
| `ai.connect-seconds` | 文字、视觉、文件语音 HTTP 及实时语音 WebSocket 连接时限上限；同时受请求总超时限制 | 整数 / 秒 | 10 | 1～86400 | `10` | CLASS / JAR / APP |
| `ai.recommended-max-output-tokens` | 普通模型调用未指定额度时的推荐最大输出 Token；关闭覆写时强制使用 | 整数 / Token | 32768 | 1～2147483647 | `32768` | CLASS / JAR / APP |
| `ai.allow-output-token-override` | 允许调用方为本次普通模型调用覆写推荐额度 | 布尔 | true | true / false | `false` | CLASS / JAR / APP |
| `ai.verification-max-output-tokens` | 文字、视觉及原生工具能力验证的独立输出额度 | 整数 / Token | 8192 | 1～2147483647 | `8192` | CLASS / JAR / APP |
| `ai.recovery.timeout-seconds` | 救援模型请求总超时 | 整数 / 秒 | 60 | 1～86400 | `60` | CLASS / JAR / APP |
| `ai.deletion.timeout-seconds` | 删除审核请求总超时 | 整数 / 秒 | 30 | 1～86400 | `30` | CLASS / JAR / APP |
| `ai.file.timeout-seconds` | 文件判断请求总超时 | 整数 / 秒 | 20 | 1～86400 | `20` | CLASS / JAR / APP |

普通模型调用统一由 APP 服务解析额度。允许覆写时，Mod 省略请求额度使用推荐值，传入正整数则原样使用；关闭覆写时始终使用推荐值，但非法请求值仍拒绝。APP 内部的 SSH 救援、删除审核和文件判断均省略请求额度，统一使用推荐值，不受覆写开关影响；三者仅分别配置请求超时，不再提供 `max-tokens` 字段。能力验证始终使用独立验证额度，不受开关影响；语音转写不发送输出 Token 参数。推荐额度不表示模型能力上限，平台不按旧模型字段裁剪，也不推测提供方上限；提供方拒绝沿用原错误处理。

模型编辑器中的旧输出额度字段及其 DB 存取暂留，页面本次不调整。该字段不再参与调用、能力验证或冻结路由；仅修改它不使验证失效。推荐值和覆写开关纳入冻结调用修订，启动快照改变后原任务仍须通过续接检查。

### 日志参数

| 配置键 | 用途 | 类型／单位 | 默认值 | 允许值／范围 | 填写示例 | 生效模式 |
|---|---|---|---|---|---|---|
| `logs.emergency-records` | 每类应急日志新增记录时的保留数量 | 整数 / 条 | 100 | 1～100；旧日志读取及回填安全上限仍为 100 | `100` | CLASS / JAR / APP |

### Mod 参数

| 配置键 | 用途 | 类型／单位 | 默认值 | 允许值／范围 | 填写示例 | 生效模式 |
|---|---|---|---|---|---|---|
| `mod.auto-load` | 源码开发启动时是否先编译，再发现并加载 Mod | 布尔 | class: false | true / false；只允许 class 区 | `false` | 仅 CLASS；JAR/APP 固定加载 |
| `mod.store.base-url` | 商店目录及下载服务基地址 | 字符串 / URL | 空字符串 | 允许 ""；否则绝对 HTTPS，或仅 `127.0.0.1` / `[::1]` 的 HTTP；禁止用户信息、查询、片段和非法端口 | `"https://mods.example.com/api"` | CLASS / JAR / APP |

## Mod 关闭与商店边界

CLASS 默认关闭自动加载，JAR/APP 固定装配并发现 Mod。只有 `class.mod.auto-load: false` 时只启动主 APP：不读取 Mod 源码、清单、配置、发行包或开发构建输出；不扫描、登记、校验、建立类加载器或调用 Mod 生命周期；不装配 Mod 专用服务和初始化查询；不挂载工作区、Mod 管理、Mod 设置、固定入口和商店。APP 的数据库结构检查、安全、任务、SSH、模型配置及日志仍正常初始化。CLASS 开启时，无需 `-Dwtl.workspace` VM 参数；平台根据 AppMain 与 DB 的实际代码来源验证工作区，只发现其中与 `src/app` 并列的 `src/mod`，不从启动目录搜索，也不扫描独立开发区 `src/moddev`。启动专用刷新为首次新身份选择本次唯一有效构建版本并启用，必要数据准备仍经明确审阅和下一适用启动。已有禁用、选中版本及待重启意图保留。JAR/APP 继续原安装和用户启用规则，不继承 CLASS 首次自动启用。

CLASS 开启自动加载时，开始发现前，使用当前 JDK 及本机 Maven 执行根工程 `-B -ntp -DskipTests process-test-classes`，刷新 Mod 主类、资源和开发描述；该阶段会编译相关测试源码和测试支持 JAR，但不执行测试，不运行 `clean`、打包或安装。Maven 从 `maven.home` 系统属性、`MAVEN_HOME`、`M2_HOME`、`PATH` 依次定位；构建输出保存在工作区 `target/mod-startup-build.log`。编译失败、工具不可用、中断或超时均停止本次 Mod 加载，不回退到旧产物；主 App 继续提供构建失败反馈。关闭自动加载和 JAR/APP 启动均不触发源码构建。

商店沿此地址获取完整目录及包；真实客户端、选择清单和安装批次已通过 1.3 S5 本机验收，验收状态以 [该期记录](../development/PHASE-1.3.md#dependency-download)为准。正式服务地址由用户明确填写，不预设已经上线；空地址不发起网络请求，连接失败可明确重试，不后台轮询。HTTP 仅用于精确数字回环地址的本机受控服务，`localhost`、其他 HTTP 主机及近似域名均拒绝；正式远端使用 HTTPS。包地址保持同源，不跟随重定向。CLASS 开启 Mod 时可以查询商店，仍不能安装发行包；安装不会自动启用或切换版本。

入口排序、窗口尺寸、居中、布局、安全与协议常量不属于 YAML。外观中的平台入口顺序和固定 Mod 入口顺序进入 DB，分别使用 `platform_navigation_order` 和 `mod_navigation_pins`；隐藏项保留位置，新项追加，设置按钮保持底部。平台恢复默认按代码顺序，Mod 恢复默认按 ownerId/pageId 排序，不取消固定。损坏偏好报错并保留原值。

启动页面同样由 DB 偏好管理，不新增 YAML 配置：`ui_restore_last_page` 保存恢复开关（缺失时为 false），`ui_last_page` 保存正常关闭时的页面身份 JSON；未启用恢复或找不到原页面时进入首页。记录不包含输入草稿、弹窗或凭据。

## 完整可加载示例

此示例与 Config 模块资源目录中的 `config.yaml` 相同，可直接保存到上述资源文件后重新构建。修改时保持全部有效叶字段齐全。

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
    connect-seconds: 10
    recommended-max-output-tokens: 32768
    allow-output-token-override: true
    verification-max-output-tokens: 8192
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
    auto-load: false
jar: {}
app: {}
```

结构与依赖见 [File.md](File.md#module-dependencies)，运行路径见 [Runtime.md](Runtime.md#desktop-data)，发行携带规则见 [APP.md](APP.md)，Mod 作者的独立配置职责见 [Mod.md](Mod.md)。

## 部署 Mod AI 输出额度

源文件位于 [部署 Mod 配置资源](../../src/mod/deployment/src/main/resources/gold/debug/windowstolinux/mod/deployment/config/config.yaml)。随包 YAML 经 Mod 自己的严格加载器读取，按类加载器保存一份不可变配置；不读取 APP Config 实现或外置同名文件。修改后重新构建、加载 Mod 生效。资源文件中的其他必需字段须保留；字段缺失、未知、重复、null、非整数或越界均拒绝加载。

| 字段 | 默认值 | 约束 |
|---|---|---|
| `ai.deployment-max-output-tokens` | 8192 | 正整数，不超过2147483647 |
| `ai.extended-deployment-max-output-tokens` | 65536 | 不小于普通部署额度，不超过2147483647 |
| `ai.approval-max-output-tokens` | 8192 | 正整数，不高于普通部署额度 |

这些值通过 `AiRequest.maxOutputTokens` 申请本次输出额度。APP 开启 `allow-output-token-override` 时采用申请值；关闭时采用 APP 推荐值。实际额度以 `AiResponse.effectiveMaxOutputTokens` 或错误元数据为准，Mod 配置不能越过 APP 策略。独立审批方式、完整证据和累计用量监督继续由原部署流程负责。
