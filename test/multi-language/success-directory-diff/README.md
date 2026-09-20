# 目录快照与差异工具

C++ 逐条输出目录扫描结果；Python 用有界线程池计算 SHA-256，SQLite 保存命名快照、包含/排除规则、条目与扫描诊断。只有 `complete` 快照可进行正式比较或重复内容查询。

## 独立构建与使用

需要 Python 3.12（仅标准库）、CMake >=3.21、Ninja 和 C++20 编译器。项目携带独立的 nlohmann/json 3.12.0 源码/许可，没有需要安装的 pip 依赖。

在 `native`：

```powershell
cmake --preset w2l-release
cmake --build --preset w2l-release-build
```

在项目根目录，先把 `samples/baseline` 复制到准备修改的临时目录；不要修改正式样本。数据库必须放在被扫描目录之外：

```powershell
python cli/main.py --db "快照 数据.sqlite" --format json snapshot --root "临时 文件树" --name "修改前" --exclude "*.tmp" --workers 4
python cli/main.py --db "快照 数据.sqlite" --format json list
python cli/main.py --db "快照 数据.sqlite" --format json show --snapshot 1
```

修改“说明 文档.txt”、删除 remove.txt、新增 added.txt，再创建“修改后”快照。随后：

```powershell
python cli/main.py --db "快照 数据.sqlite" --format json diff --before 1 --after 2
python cli/main.py --db "快照 数据.sqlite" --format csv --output diff.csv diff --before 1 --after 2 --pattern "*.txt"
python cli/main.py --db "快照 数据.sqlite" --format json duplicates --snapshot 2
python samples/generate.py "一万 文件" --files 10000
```

固定样本变更的预期：新增 added.txt、删除 remove.txt、内容变化“说明 文档.txt”、不变 1 个。内容摘要改变时，即使大小和修改时间保持原值也能检出。相同摘要的新增/删除路径列为 `renameCandidates`，只表示候选，不能证明实际发生重命名；相同内容多路径按组展示，并计算冗余字节。

## 一致性与恢复

每个快照从 building 开始，摘要按批保存；记录文件大小、精确时间、文件身份和变化时间。读取前后检查身份与元数据，再进行第二遍原生扫描核对路径集合，最后再次检查所有已摘要条目。只有全部检查成功后，事务发布 complete 状态。扫描中删除、替换或修改文件会留下 failed 及 issues，拒绝参与比较。

这是文件扫描一致性检查，不是操作系统提供的原子卷快照。对于持续写入目录，应暂停写入后重试；工具不宣称能捕获所有文件在同一瞬间的状态。

终止运行中的 Python 后，再次执行 list 会将已退出进程遗留的 building 标为 interrupted，已有 complete 快照保持有效。新快照需使用新名称；同名 10 个竞争请求只能有一个创建成功。失败记录可以 `show` 查看和导出。数据库不迁移旧版。

根目录、路径组件和文件不跟随符号链接或 Windows 重解析点；扫描问题和跳过链接分别记录。正式比较要求相同根目录及相同包含/排除规则，避免把规则变化误报为业务删除。

## 选项与协议

全局选项写在操作名前：`--db`（默认 `DATA_DIR/snapshots.db`，未设置 DATA_DIR 时为项目 data）、`--helper` / `NATIVE_HELPER`、`--timeout`（默认 120 秒，最多 600）、`--format text|json|csv`、`--output`。snapshot 支持重复 `--include`/`--exclude`（fnmatch 路径模式，排除优先）、`--workers`（默认 HASH_WORKERS 或 4，最多 32）、`--max-files`（100000）、`--max-file-bytes`（256 MiB）。list 支持 offset/limit，diff/duplicates 支持 pattern。

C++ stdout 为 NDJSON 版本 2，component=cpp-scan；start、entry/skip/problem、end 消息的 sequence 必须连续。entry 包含 path/size/modifiedNs；end 给出 messages/files/skipped/problems/complete。Python 队列最多 64 条，待摘要任务最多 workers×2，单协议行最多 64 KiB。子程序超时或协议失败会终止并等待退出。报告最大 64 MiB。stderr 为诊断。

退出码：0 成功、2 输入/业务约束错误、3 文件/SQLite 错误、4 子程序/协议错误。`SAMPLE_FAULT_POINT=scan-started|hashes-computed|snapshot-publishing` 和 `SAMPLE_FAULT_DIR` 仅用于运行器确定性中断验收，默认关闭。

Windows 标准验收覆盖 10001 个文件、真实目录联接、SHA-256、10 个同名竞争请求、扫描变化和提交中断。普通符号链接创建受本机权限限制，Linux 符号链接及实机部署待验证，见上级 `VERIFICATION.md`。扫描目录、数据库和报告不提交。
