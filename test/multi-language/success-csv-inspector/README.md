# CSV 检查工作台

JavaScript 提供数据集、规则编辑、任务详情与比较页面；PHP 独占 SQLite 队列和报告记录；独立 PHP 工作进程通过 HTTP 调用 Python 流式检查器。检查过程中页面可继续查询和取消任务。数据集及模板保存后固定，新规则另存模板；任务保留规则快照。

## 独立构建与运行

需要 Python 3.12、PHP 8.3（pdo_sqlite、curl、mbstring）、Composer。依赖分别由带哈希的 `analyzer/requirements.lock` 和 `web/composer.lock` 锁定。复制整个项目后，不依赖仓库其他样例。

在 `analyzer` 运行：

```powershell
python -m venv .venv
.venv\Scripts\python.exe -m pip install --require-hashes -r requirements.lock
$env:HOST="127.0.0.1"
$env:PORT="18131"
.venv\Scripts\python.exe server.py
```

在 `web` 安装：`composer install --no-dev --no-interaction`。然后打开两个终端，均切换到 `web`，设置相同的绝对数据目录和分析器地址：

```powershell
$env:DATA_DIR="D:\CSV 演示数据"
$env:ANALYZER_URL="http://127.0.0.1:18131"
```

第一个终端运行 `php -S 127.0.0.1:18130 -t public router.php`；第二个运行 `php worker.php`。浏览器打开 `http://127.0.0.1:18130`。结束体验时在三个终端分别 Ctrl+C。Linux 对应 `.venv/bin/python` 与 `export` 语法；Ubuntu 产品部署、队列/分析服务和重启证据见上级验证记录。

PHP 页面与 Python 分别提供 `/healthz`，`/api/worker` 显示工作进程心跳。每个数据目录只允许一个 PHP 工作进程，使用进程文件锁防止重复消费；不同独立数据目录可各自运行。此样例直接使用 v2 数据目录，不迁移旧数据库。

WindowsToLinux 部署声明将 `web/worker.php` 纳入 PHP 主服务的受管工作进程，随同发布和生命周期操作。`analyzer/pyproject.toml` 声明 Python 版本和依赖，安装仍使用原有哈希锁文件。PHP 的文件树与 SQLite 分别使用受管默认存储；首次文件初始化使用 `web/seed/datasets/demo.csv`，内容与 `samples/demo.csv` 相同，使单独打包的 web 组件无需读取其源码根之外的样本。现有数据库和文件不会被该种子覆盖。Linux 实机结果待本轮验证完成后记录。

## 完整体验

1. 打开“检查任务”，选择初始化的“演示人员.csv”和“基础检查”。详情应显示 3 行、6 个问题、1 行有效；查看规则、问题说明、任务历史并下载 JSON。
2. 打开“规则模板”，参考“仅必填”另存自己的模板。对同一数据集再检查，应为 1 个问题、2 行有效。“报告比较”选择两次结果，应消除 5 个问题，并列出两份规则与分类变化。
3. `python samples/generate.py "大 数据.csv" --rows 100000`，上传后开始检查。可以切换列表查询，回到详情取消，再点击“重试此任务”；任务标识不变，尝试次数增加，问题明细不会累加。
4. 检查过程中终止 PHP 工作进程，再启动。原运行中任务显示“已中断，可重试”；用户明确点击重试后才执行。关闭 Python 后提交任务应失败，不能导出成功报告；恢复 Python 后重试。

数据集列表、任务列表、问题明细使用服务端分页；报告下载逐条写入并读取文件。页面从不请求全部问题集合。固定演示数据不包含身份认证。

## 配置

| 变量 | 默认值 / 用途 |
|---|---|
| `DATA_DIR` | PHP 默认 `web/data`，页面与 worker 必须一致 |
| `ANALYZER_URL` | `http://127.0.0.1:18131` |
| `ANALYZER_TIMEOUT_MS` | 120000，整次调用超时 |
| `WORKER_POLL_MS` | 200，队列轮询间隔 |
| `MAX_FILE_BYTES` | 134217728，PHP/Python 均设置相同值 |
| `MAX_ROWS` | 500000，PHP/Python 均设置相同值 |
| `MAX_UNIQUE_KEYS` / `MAX_FIELD_CHARS` | Python 每组去重键 500000 / 字段 65536 字符 |
| `MAX_CONCURRENT_JOBS` | Python 4；PHP 单工作进程顺序发布 |
| `MAX_PROTOCOL_RECORDS` | PHP 2000000；超过时明确失败 |
| `INPUT_TIMEOUT_SECONDS` | Python 请求读取超时 30 秒 |

只有测试时使用 `SAMPLE_BATCH_DELAY_MS`（默认 0）及 `SAMPLE_FAULT_POINT=job-running|before-report` / `SAMPLE_FAULT_DIR`，故障等待最多 60 秒。正常体验无需设置。

## 数据与协议

见 [接口和不变量](docs/INTERFACES.md) 与 [业务验收规范](BUSINESS.md)。取消、失败和中断保留状态、历史与部分诊断；只有收到完整协议、核对问题数量并成功发布的任务可导出。重试复用任务身份，增加尝试次数并清除上一尝试的问题明细。模板和数据集边界由 PHP 校验；实际问题规则由 Python 执行。

仓库运行器支持 `verify --project csv-inspector --profile quick|standard --work <隔离目录>`。标准配置生成 100000 行，独立核对 22937 个问题，覆盖 10 请求竞争、恢复、协议注入及浏览器操作。实际 Windows 记录见上级 `VERIFICATION.md`；本地结果不代表 Linux 或 WindowsToLinux 产品部署通过。数据库、数据集、报告、大样本和安装目录不提交。
