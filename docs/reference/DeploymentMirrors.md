# 部署镜像目录与覆盖边界

目录版本：`2026-11-04.1`；资料与公开HTTP核验日期：2026-11-04。来源、提供方、地区、邻区、URL、重写及DNF仓库模板均保存在Mod资源 `config/mirrors.yaml`。国家判断使用随包DB-IP Country Lite 2026-11，在本地完成；出口IP探测服务可看见服务器的出口公网地址。

## 使用设置

在 APP“设置”中打开部署 Mod 的“镜像源设置”，按下载用途保存来源。部署时直接读取保存值，不增加部署向导步骤。

1. 第一行默认“地区自动匹配”，可以先保存为待确认状态；任务首次实际需要自动匹配时会显示隐私说明，确认前不检测 IP。取消后不会探测 IP，也不会改用官方来源。每次手动从关闭切换为开启都会再提示，已保存的确认状态在重启后保留。不相关下载项的默认自动设置不会触发检测。
2. 关闭自动后可指定内置提供方或自定义根地址，用“＋”添加第二、第三优先级。开启任意一行自动匹配会隐藏其后配置，关闭后恢复。固定单行只使用该行，缺少资源或校验信息会明确失败。
3. 有认证或私有 CA 时，先选择一台已配置服务器，点击“添加镜像凭据”，填写 Basic 用户名/密码或 PEM CA，再在所需来源行选择保存的秘密修订。凭据仅对该来源的 HTTPS 地址范围生效；修改配置不复用明文，也不覆盖系统信任库。
4. “服务器地区修正”可覆盖自动判断。点击保存后只影响新任务；已有任务和恢复继续使用原目录、地区、端点、校验策略与秘密修订。

Python 安装包、PyPI 依赖和版本元数据是不同下载用途，不能用仅提供 PyPI 的地址替代安装包或元数据。各项只显示已适配的来源，单源缺口在页面提示；来源标注的国家是目录维护信息，CDN 实际落点可能变化。

## 选择与冻结合同

每个实际下载点独立保存1–3行。固定提供方、用户自定义地址与自动选择共用同一有序链；固定单源不追加任何来源。自动行以首次默认未同意隐私提示保存；未同意时不产生自动端点，后续行停用但仍保存，关闭自动后可以恢复原固定值。自动行本身也保留原固定提供方或自定义地址。

自动选择按国家、地区、配置中的邻区次序、其他地区、官方排序；同层优先覆盖点较丰富的综合提供方，再按稳定ID排序，按实际baseUrl去重并展开全部兼容候选，最多256个端点，保留末尾官方。3项上限只约束用户编辑的优先级行。`Source.complete=false`仅供目录审计，`providersFor`与选择器不会把它作为完整点选项。地区修正以serverId保存ISO国家代码，然后映射到配置地区。

`MirrorSettings` JSON为formatVersion、points(point到Row数组)、serverRegions(serverId到ISO国家码)。Row包含providerId、customUrl、automatic、privacyConsent、secretRef。凭据由现有SecretProvisionFlow业务修订管理，普通配置仅保存 `identifier:revision` 引用；任务私有凭据材料与公开策略分开，凭据只用于对应来源的显式地址范围。偏好仅写Mod H2现有preference表的 `deployment.mirrors.settings.v1`，沿用原TaskContext与ModDatabase，无schema迁移，不更改旧部署偏好。

隐私确认使用比较后更新：仅当数据库当前设置仍等于确认前快照时写入同意结果；若期间用户从界面保存了新选择，旧确认返回false并保留新设置，不覆盖它。缺失偏好视为defaults，首次插入仍遵守不存在才插入。

新任务先冻结用户选择，再按已选项目及源码清单判定实际下载用途；支持的系统包用途通过独立的只读 `/etc/os-release` 检查限定为目标发行版。这属于部署兼容性检查，与出口 IP 地区识别分开审批。未知项目按全部用途处理，不靠猜测省略所需来源。

STATIC/ASSISTED在首次完整运行参数审阅后、数据库准备与helper安装前提交策略，包括纯静态站点显式选择的Node。AGENT允许后续选择工具及容器后端，因此以全部受支持的下载能力准入，仍将系统包项限定为实际发行版；不能只凭初始源码语言省略其后续工具来源。使用AGENT时，所有这些用途中的自动项均须已确认隐私说明。

`ResolvedMirrorPolicy`冻结formatVersion/task/server/catalogVersion/country/region/timeouts/points；每点为有序endpoint数组，未准入用途为空，实际访问时拒绝，不隐式回退。endpoint含providerId、protocol、baseUrl、allowedHosts、official、custom、sourceOrigins、routes、repoTemplates、secretRef及explicit。route的origin/prefix为精确前缀映射；可选pattern/replacement使用锚定正则和Python `match.expand` 数字捕获，仅用于明确的文件名布局转换。APT/DNF不接受正则路由。任务快照用base64分片及SHA256 manifest跨越平台12KiB参数限制；恢复使用原快照，不读取新设置。快照恢复校验任务/服务器绑定、版本、大小、端点数量、URL主机和模板主机边界。新任务若尚未提交完整策略，下载入口拒绝套用旧任务路径；旧任务原本没有镜像快照时保持原恢复语义。

## 元数据与文件分开

九种语言的版本发现与原始校验使用单独的 `_METADATA` 点；安装包使用原工具链点。元数据与文件可以选择不同来源，两个选择均在界面显式可见。安装包镜像不会隐式取得官方版本信息。Node镜像可同时支持index.json及SHASUMS，其他生态的文件目录不自动计为版本API或官方公告的兼容镜像。

## 按实际下载点计数

下表是语义和路径核验得到的兼容目录数，不是实时可用率、完整历史版本覆盖率或真机部署验收。404/403/429和地区限制可能导致运行时端点失败；只有冻结链中明确列出的后续项可以接续。HTTP根目录无法列出内容不等于包不存在，Maven等需按具体对象判断。

| 实际点 | 完整兼容独立目录 | 部分兼容审计项 | 自动展开项 |
| --- | ---: | --- | ---: |
| `APT_DEBIAN` | 7 | 无 | 7 |
| `APT_UBUNTU` | 5 | 无 | 5 |
| `DNF_FEDORA` | 5 | 无 | 5 |
| `DNF_ROCKY` | 4 | 无 | 4 |
| `DNF_ALMA` | 3 | 无 | 3 |
| `DNF_CENTOS_STREAM` | 4 | 无 | 4 |
| `DNF_ORACLE` | 1 | 无 | 1 |
| `JAVA_JDK` | 3（EU27为2） | 无 | 3（EU27为2） |
| `MAVEN_DISTRIBUTION` | 3 | 无 | 3 |
| `MAVEN_REPOSITORY` | 3 | 无 | 3 |
| `GRADLE_DISTRIBUTION` | 2 | 无 | 2 |
| `GRADLE_PLUGIN` | 2 | 无 | 2 |
| `COMPOSER_INSTALLER` | 1 | 无 | 1 |
| `COMPOSER_REPOSITORY` | 2 | aliyun | 2 |
| `PYTHON_DISTRIBUTION` | 2 | 无 | 2 |
| `PYTHON_PIP_BOOTSTRAP` | 1 | 无 | 1 |
| `PYTHON_PACKAGE` | 3 | 无 | 3 |
| `NODE_DISTRIBUTION` | 4 | 无 | 4 |
| `NODE_PACKAGE` | 3 | 无 | 3 |
| `GO_DISTRIBUTION` | 2 | 无 | 2 |
| `GO_MODULE` | 3 | 无 | 3 |
| `GO_CHECKSUM` | 2 | 无 | 2 |
| `RUST_TOOLCHAIN` | 3 | 无 | 3 |
| `RUST_CRATE` | 3 | 无 | 3 |
| `RUST_INDEX` | 3 | 无 | 3 |
| `DOTNET_SDK` | 1 | 无 | 1 |
| `NUGET` | 2 | 无 | 2 |
| `RUBY_DISTRIBUTION` | 6 | 无 | 6 |
| `RUBYGEMS` | 4 | 无 | 4 |
| `PHP_DISTRIBUTION` | 2 | 无 | 2 |
| `OCI_REGISTRY` | 2 | 无 | 2 |
| `KOTLIN_DISTRIBUTION` | 1 | 无 | 1 |
| `JAVA_METADATA` | 1 | 无 | 1 |
| `KOTLIN_METADATA` | 1 | 无 | 1 |
| `GO_METADATA` | 1 | 无 | 1 |
| `RUST_METADATA` | 1 | 无 | 1 |
| `PYTHON_METADATA` | 1 | 无 | 1 |
| `PHP_METADATA` | 1 | 无 | 1 |
| `RUBY_METADATA` | 1 | 无 | 1 |
| `DOTNET_METADATA` | 1 | 无 | 1 |
| `NODE_METADATA` | 4 | 无 | 4 |

完整17地区×41实际点的全部自动候选、同地区数量、完整兼容总量、部分兼容项及距3项的缺口记录在随包 `config/mirrors-coverage.yaml`。该矩阵按地区而非某个具体国家计算；同一地区内某个国家的本国提供方会排在地区提供方之前。跨地区补齐是明确策略，矩阵中的本地缺项没有被改写为“本地区有镜像”。

| 地区 | 目录中本地区有完整兼容源的点数 |
| --- | ---: |
| 中国大陆 | 26 / 41 |
| 俄罗斯 | 6 / 41 |
| 伊朗 | 0 / 41 |
| 东亚其余 | 5 / 41 |
| 东南亚 | 0 / 41 |
| 南亚 | 0 / 41 |
| 中亚 | 0 / 41 |
| 西亚及中东其余 | 0 / 41 |
| 欧洲 | 2 / 41 |
| 北美 | 2 / 41 |
| 中美及加勒比 | 0 / 41 |
| 南美 | 0 / 41 |
| 北非 | 0 / 41 |
| 西中非 | 0 / 41 |
| 东非 | 0 / 41 |
| 南非 | 0 / 41 |
| 大洋洲 | 1 / 41 |

## 当前单源与未达3项的具体依据

“当前仅列官方”表示本次没有核实独立且完整兼容的第二项，不等于已经证明世界上只有一个来源。下列点未达到通常至少2项的目标，因此属于明确保留的目录缺口；不得把它们报告成全覆盖完成。

- JAVA_METADATA：版本与摘要仍使用Adoptium API；JDK文件已补TUNA与中国科技云北京镜像，按major、arch和完整原文件名显式模板映射，维持相同官方SHA-256。不能把其他JDK品牌计作同一Temurin发行身份。依据：[Adoptium API](https://api.adoptium.net/)、[TUNA Adoptium目录](https://mirrors.tuna.tsinghua.edu.cn/Adoptium/)、[中国科技云目录](https://mirrors.cstcloud.cn/Adoptium/21/jdk/x64/linux/)。
- Kotlin安装包与KOTLIN_METADATA：现有实现查询JetBrains Kotlin的GitHub tags/releases，并核对发行asset的SHA-256；未核实镜像对这些元数据和文件布局的完整映射。依据：[Kotlin CLI官方说明](https://kotlinlang.org/docs/command-line.html)。
- GO_METADATA：现有实现依赖go.dev/dl/?mode=json&include=all提供稳定版本、架构与SHA-256。华为文件目录不实现该JSON API，已独立列入安装包点，不冒充metadata镜像。依据：[Go下载](https://go.dev/dl/)。
- RUST_METADATA：非exact安装先查Rust官方GitHub release tags，后从static.rust-lang.org取得sidecar SHA-256；只提供rust-static的镜像不计为完整版本信息点。依据：[Rustup分发说明](https://rust-lang.github.io/rustup/concepts/distribution.html)、工具链实际调用。
- PYTHON_METADATA：需要python.org目录、版本发布页及SHA-256/Sigstore messageDigest；仅归档目录不提供发布页。依据：[Python下载](https://www.python.org/downloads/)。
- PHP_METADATA：需要php.net/releases/index.php版本JSON中的文件及SHA-256；搜狐文件目录不等同发布元数据API。依据：[PHP releases](https://www.php.net/releases/)。
- RUBY_METADATA：需要ruby-lang.org发布列表、公告以及公告中的SHA256；Ruby官方镜像名单只保证源包镜像，不代表复制公告。依据：[Ruby发布](https://www.ruby-lang.org/en/downloads/releases/)、[Ruby镜像表](https://www.ruby-lang.org/en/downloads/mirrors/)。
- .NET SDK与DOTNET_METADATA：现有安装依赖微软release-metadata JSON与其中精确linux-x64发行文件；未核实第二方完整兼容，不能以任意dotnet目录补数。依据：[.NET release metadata](https://github.com/dotnet/core/tree/main/release-notes)。
- COMPOSER_INSTALLER：安装脚本、composer.phar、installer.sig组成完整引导/校验合同。阿里提供composer.phar不代表其完整支持installer和独立签名。依据：[Composer程序化安装](https://getcomposer.org/doc/faqs/how-to-install-composer-programmatically.md)。
- PYTHON_PIP_BOOTSTRAP：PyPA明确要求使用bootstrap.pypa.io的get-pip版本，不能以GitHub仓库文件直接替代。依据：[PyPA get-pip README](https://github.com/pypa/get-pip/blob/main/README.md)。

两项但未达到3项目标的点仍如实列2项，未借用别名域或未核实代理补数。典型有Gradle发行/插件、Python源包、Go源包/校验库、NuGet、PHP源包与OCI。

Maven wrapper常见Central分发URL使用版本目录，不等同Apache归档布局。目录对Central及repo1的分发URL配置显式版本模板，映射到maven-major/version/binaries并保留文件名与sha512/sha256/asc边车；没有用通用主机替换。自定义Maven分发根继承同一官方相对模板，应提供Apache兼容目录布局。Temurin的版本镜像可能只保存各分支当前文件：本次TUNA 21目录列21.0.13_9，而中国科技云仍列21.0.12.1_1；这是真实同步差异，不能以文件目录存在推断每个确切版本均可用。文件缺失按既有冻结候选链继续，预期摘要保持不变。

## 已核实的综合与专门来源

优先综合提供方：阿里、华为、USTC、Yandex；发行版补JAIST、AARNet、Dotsrc。语言特有缺项补GoProxy、npmmirror、RsProxy及Ruby官方列出的地区镜像。地址别名不另计提供方。TUNA仅作为已核实的Temurin JDK文件源收录，配置excludedCountries列明EU27，自动选择时排除这些国家；国家列表及排除依据均是配置数据，不在Java硬编码。Europe地区不等于EU成员集合，矩阵另含27个countryOverrides；排除TUNA后仍有中国科技云与官方两项。

主资料：[Debian官方镜像目录](https://www.debian.org/mirror/list)、[Ubuntu镜像原则](https://ubuntu.com/project/docs/release-team/mirrors/)、[阿里镜像目录](https://developer.aliyun.com/mirror/)、[华为镜像产品说明](https://www.huaweicloud.com/product/mirrors.html)、[Yandex目录](https://mirror.yandex.ru/)、[USTC帮助](https://mirrors.ustc.edu.cn/help/)、[Ruby镜像表](https://www.ruby-lang.org/en/downloads/mirrors/)、[RsProxy](https://rsproxy.cn/)、[Google OCI缓存说明](https://docs.cloud.google.com/artifact-registry/docs/pull-cached-dockerhub-images)。

专项元数据核验发现：华为NuGet index返回同域artifactory服务路径；华为/阿里PyPI six元数据有同镜像相对packages链接；华为/npm及npmmirror包元数据指向各自包地址；华为Composer元数据包含本源dist，而阿里Composer样本仍指向GitHub，因此阿里Composer标为metadata-only并从完整点选项排除。原始公开响应保存在任务证据目录。RsProxy已核实跳转lf3-static与lf9-static CDN；USTC crate API当前验证地点返回403，未掩饰为成功。

Composer官方项明确配置Packagist以及GitHub API、codeload、GitHub release与release-assets路径；这些是锁文件可能引用的包入口，不是允许任意git站点的通行规则。Packagist官方psr/log元数据样本也保留了GitHub zipball分发URL。GitLab、私有git或未知dist域未获核实，不加入允许主机。锁文件固定地址通过任务私有 TLS CONNECT 传输保持原地址及锁文件；跨源重新取得的 Composer dist 只有满足原有受信摘要才可使用。锁记录缺少可校验身份时，不把元数据中的另一个下载地址当作同一制品。

APT把系统中常见HTTP及无尾斜杠的源URI作为显式输入别名，输出仍为选定HTTPS端点。别名只参与URL匹配，不增加独立提供方数量。

Google mirror.gcr.io只缓存部分热门Docker Hub镜像且可能回收，不能称所有镜像的永久镜像；运行时仍必须校验同一digest并按用户冻结链处理缺失，不启用Docker隐式公网回退。

## DNF自定义根与仓库模板

DNF每个point在配置保存repoId到相对路径模板，每个provider展开成完整URL。自定义地址应是对应发行版的上游兼容仓库根，例如Debian风格根不适用于Rocky；选择器继承配置模板，不从域名猜路径。Fedora需根下releases及updates；Rocky/Alma需根下$releasever/BaseOS、AppStream、extras等；CentOS Stream需$releasever-stream以及SIGs层级。非标准布局还需要将明确的每repo模板加入目录，当前单URL输入不表达任意布局。未配置的启用repoId应拒绝；disabled repo保持禁用；现有GPG校验与本地密钥路径保持。参考：[Fedora](https://mirrors.ustc.edu.cn/help/fedora.html)、[Rocky](https://mirrors.ustc.edu.cn/help/rocky.html)、[CentOS Stream](https://mirrors.ustc.edu.cn/help/centos-stream.html)。

Oracle Linux使用独立DNF_ORACLE点，根为Oracle公共仓库的OracleLinux目录。OL9和OL10分别配置自己的BaseOS、AppStream、Addons、CodeReady及UEKR8，OL9另有UEKR7；不把CentOS模板用于Oracle。当前仅核实Oracle这一独立提供方，地区域名别名不增加数量；未列入目录的OCI、付费或其他启用repoId明确拒绝。依据：[Oracle Linux 9目录](https://yum.oracle.com/oracle-linux-9.html)、[Oracle Linux 10目录](https://yum.oracle.com/oracle-linux-10.html)、[Oracle软件仓库说明](https://docs.oracle.com/en/operating-systems/oracle-linux/software-management/sfw-mgmt-AvailableYumRepositories.html)。

DNF远程请求全部通过任务私有loopback网关。第一份结构有效repomd固定当前仓库身份；后续metadata使用其原始checksum，primary中每个包的原始checksum用于安装包传输。校验失败只取同一预期摘要的后续来源，不能接受另一版本或另一个摘要的包。成功对象缓存，耗尽对象的失败也缓存，原生客户端重试不会重新消耗每源请求预算。第一份repomd依赖所选TLS来源及原生repo_gpgcheck策略，客户端仍保留RPM签名及本地GPG key校验，不把网关本地计算摘要宣称为额外官方签名。

只生成任务私有config、repos、cache、logs及变量目录，远程密钥、未知启用仓库和绕过信任的命令参数拒绝。DNF5会读取系统repo override，适配器先读取其有效启禁状态，再用命令行最高优先级锁定loopback仓库及清空外部mirrorlist/metalink、全局代理和凭据；root的system_cachedir也明确指向私有缓存。每对象由统一传输层控制每源最多2次及原任务截止时间，原生DNF的retries设1，避免0代表无限重试。先downloadonly完成全部下载，再只运行一次cacheonly安装；安装阶段网关不再发起上游请求，安装失败不会重跑整个安装。

primary支持普通XML、gzip、xz、bzip2；zstd通过已有系统libzstd流式解压，不下载新依赖，缺少该能力会明确拒绝。zchunk关闭，要求仓库提供普通primary。仅接受SHA256/SHA512对象校验。当前验证为Windows本地模拟仓库、真实HTTP传输和独立假dnf进程；确认配置、请求级切源、摘要身份、缓存安装次数与清理，未宣称Fedora/Oracle真机安装或真实RPM签名验收。DNF5配置与选项依据：[DNF5配置](https://dnf5.readthedocs.io/en/stable/dnf5.conf.5.html)、[DNF5命令](https://dnf5.readthedocs.io/en/latest/dnf5.8.html)；重试含义依据：[DNF配置](https://dnf.readthedocs.io/en/stable/conf_ref.html)。

## 国别库许可与隐私

随包DB-IP Country Lite 2026-11原始CSV.gz含732796范围记录，支持IPv4及IPv6。保留LICENSE、来源、版本与SHA256SUMS；数据为CC BY 4.0，署名“IP Geolocation by DB-IP”，来源[DB-IP](https://db-ip.com/db/download/ip-to-country-lite)。Lite准确度和覆盖有限，未知/保留地址不承诺国别；用户可按serverId修正。已知服务器IP只本地查询，域名不会在国别库查询时触发DNS。

生产查询按随包资源路径与版本共享一份不可变已解析数据库，不按部署任务重复解析73万条。测试流读取入口不缓存；ZZ返回未知，未知国家按全局候选继续排序。

公网IP探测与给已有IP查国家是不同步骤：前者在服务器实际执行并仅访问配置中的IP探测服务，需服从任务隐私同意；后者不上传已知IP。探测使用服务器已有curl，不额外安装工具；失败后只查询已知公网服务器地址，仍未知则冻结全球候选。策略只保存国家/地区，不保存探测得到的公网IP。恢复不重复探测或更新国别库。

## 执行路径与验证范围

来源在首次helper准备前生效；系统包、编译依赖和原生数据库包使用任务私有仓库、缓存及认证文件。JDK、Node、Python、Go、.NET、Rust、Kotlin、PHP、Ruby的元数据与安装包经过相应协议适配，不以通用URL前缀替换冒充协议兼容。

APT首次准备使用Bash路径，不要求先安装Python。DNF严格重定向与对象校验网关要求目标已有Python 3.9及以上（也检查发行版的platform-python及dnf解释器）；缺少时，固定或自定义来源会在下载前明确拒绝，不偷偷安装运行时或借官方回退。只有目录明确允许的自动单官方项保留受限原生DNF分支。精简服务器应先具备相应解释器再使用严格DNF镜像策略。

APT通过局部`APT_CONFIG`先将main及配置片段目录指向任务私有位置，再设置私有列表和二进制缓存，不继承全局代理、钩子或TLS覆盖；保留原`Signed-By`及系统发行版信任密钥。无认证HTTP内网源可以使用，认证仍须HTTPS，重定向关闭，元数据签名及包摘要仍由原生APT校验。配置读取顺序依据：[APT配置手册](https://manpages.debian.org/trixie/apt/apt.conf.5.en.html)。有效元数据缺所需包/版本时，可在制品身份确定前继续下一来源；冻结URI/版本/摘要后只按同一身份下载，不重新解析版本或重跑安装。

| 下载路径 | 任务范围的控制方式 |
| --- | --- |
| Maven / Gradle | 发行包及边车校验、Wrapper固定版本与摘要、Central与插件仓库配置及受控请求 |
| npm / pnpm / Yarn | registry与包元数据、完整性摘要；锁定HTTPS地址使用任务传输，不改锁文件 |
| pip / uv / Poetry / Pipenv | 引导来源、simple/JSON及包文件；固定地址与原摘要保持 |
| Go / Cargo | module与校验服务分别配置；crate/index/toolchain分别适配 |
| NuGet / Composer / RubyGems | 保留原仓库映射及信任约束；NuGet原配置层级、签名、审计与锁记录不被清空；无法证明原制品身份时拒绝换地址 |
| Docker / Podman | 任务私有引擎；基础镜像manifest/config/layer与diff_id核验，冻结digest并预拉本地构建，禁止隐式上游回退 |

公开目录、DNS/连接、重定向、无进展、持续低速、摘要失败及取消均受原请求剩余预算约束。默认连接10秒、无进展30秒、持续60秒低于64KiB/s、每来源最多2次，参数随目录冻结。请求账本在同一运行时进程内共享给原生客户端重试及子策略：同URI并发合并、耗尽不重置、成功缓存仍核对当前来源范围与制品身份；不同独立CLI进程不共享磁盘累计计数。安装、编译和发布错误不会触发整套部署重跑。

任务本机网关只绑定IPv4回环，并在Linux通过实际连接的socket四元组及`/proc/net/tcp`核对UID；有认证或私有CA时，不能确认访问账户即拒绝。无凭据传输在内核无法提供账户证明时保留兼容路径，这不构成网络隔离。IPv6上游地址仍可通过该回环网关访问。凭据使用现有秘密修订及opaque stdin暂存，按任务、服务器、原策略摘要绑定；普通配置、审批事实及日志仅含引用。已知终态的材料清理仍需当前有效的独立授权；标准流程在自己的审阅范围关闭前处理，范围外操作仍走宿主的精确动作审批，不复用已关闭的许可。清理未获确认则保留UNKNOWN以供原任务核查。构建项目与钩子共享该构建账户的文件权限，不能把这些措施宣称为隔离恶意项目代码。

Git源码地址、Dockerfile的RUN及任意项目联网脚本不是自动重写对象。固定单源不能证明可约束的任意host/project shell在执行前拒绝，受控产品下载工具仍可使用。容器目录当前对已配置的Docker Hub及缓存路径适配，未知registry不冒充兼容；调整后的Dockerfile仅生成于任务副本并留作审批依据，不覆盖用户源码。不承诺任意项目完全离线。

本次验证使用显式`-Dtest=...`定点：目录与覆盖矩阵、H2偏好、逐行设置、隐私取消及重载、任务快照、IPv4/IPv6、受影响的helper与审批源合同，以及本地HTTP/HTTPS模拟仓库中的实际请求、固定顺序、单源拒绝、超时、低速、重定向、错误摘要及取消。另有真实pip进程通过原固定URL和原SHA256安装的本机证明。Windows模拟中的Linux账户边界采用显式测试替身，不把它当作真实Linux隔离验收。

未连接真实部署服务器，未运行既有真机验收、付费AI或全量测试。除所述pip本机证明外，各生态真实客户端、APT/DNF发行版安装、真实RPM签名及Docker/Podman仍需在对应环境另行验收；公开HTTP样本也只代表核验机器当时可达，不能代表当地服务器速度或所有历史版本的完整性。
