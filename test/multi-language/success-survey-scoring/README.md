# 问卷与评分管理

TypeScript 网页编辑问卷和填写答案，Kotlin 独占 SQLite 中的问卷、不可变发布版本、规则、答卷和结果，Ruby 根据该版本的声明式规则实际评分。没有动态执行用户代码，固定演示身份不属于认证。业务规范见 [BUSINESS.md](BUSINESS.md)。

## 独立构建与运行

需要 JDK 21、Gradle Wrapper 8.10.2、Kotlin 2.0.21、Ruby 3.3、Bundler、Node 24 和 npm。Wrapper、分发校验、gradle.lockfile、Gemfile.lock 和 package-lock.json 随项目提供。

1. backend：`./gradlew.bat --no-daemon installDist`；Linux 使用 `sh gradlew --no-daemon installDist`。
2. scorer：`bundle config set --local path vendor/bundle`，`bundle config set --local frozen true`，`bundle install`。
3. frontend：`npm ci`、`npm run build`。
4. 三个终端依次启动 scorer 的 `bundle exec ruby server.rb`、backend 的 `./build/install/survey-scoring/bin/survey-scoring.bat`、frontend 的 `npm run start -- --port 18140`。Linux 去掉 Kotlin 启动器的 `.bat`。浏览器打开 `http://127.0.0.1:18140`；结束后各终端 Ctrl+C。

| 组件 | 配置 | 默认值 |
|---|---|---|
| Kotlin | HOST / PORT / DATA_DIR | 127.0.0.1 / 18141 / 当前目录 data |
| Kotlin | SCORER_URL / SCORER_TIMEOUT_MS / MAX_SCORE_BYTES | http://127.0.0.1:18142 / 5000 / 262144 |
| Kotlin | WEB_ORIGIN / WORKERS | http://127.0.0.1:18140 / 16（1..64） |
| Ruby | HOST / PORT / MAX_CLIENTS / REQUEST_TIMEOUT_SECONDS | 127.0.0.1 / 18142 / 32 / 10 |
| 网页 | runtime-config.json | apiBase 指向 Kotlin；requestTimeoutMs 默认 15000 |

更换页面端口须同步 WEB_ORIGIN。构建后的 dist/runtime-config.json 可直接修改。指定新 DATA_DIR 初始化本版数据库，不迁移旧数据；重启新版目录保留问卷与成绩。两个服务提供 /healthz，不能代替提交评分验收。

## 人工完整操作

1. 初始“校园协作体验”已发布版本 1，包含单选、多选、条件量表与反向量表。进入填写，选择“未参加”，质量题隐藏且不会误判必填；选“参加过”则显示该必填题。
2. 提交后查看总分、维度、逐题公式解释、隐藏题及原始答案，导出 JSON；在该版本历史中查询原结果。
3. 从详情“基于最新版本创建草稿”，编辑标题、题目、选项分值、维度、权重、反向计分或显示条件，保存草稿，再发布已保存草稿。发布版本不能原地编辑。
4. 使用相同答案填写新版本，核对改变规则后分数变化；旧版答案、题目和分数保持原样。关闭旧版后不能新增提交，但历史仍可查看。
5. 新建另一份问卷，其版本和历史独立。停止 Ruby 后提交应显示失败且不产生答卷；恢复 Ruby 后可重新提交。

## 题目和评分格式

内容为 `{title,questions}`；每题包含 `id,label,type,required,rule`。id 为小写字母开头的字母／数字／下划线，1..30 题。type 为 single、multi、scale。

- 单选／多选的 options 为 `{value,label,score}` 数组，选项分 0..100，最高分必须大于零。
- 量表含 min/max，0≤min<max≤100，只接受范围内整数。
- rule 为 `{dimension,weight,reverse}`，权重 1..10。量表正常分为 answer-min，反向为 max-answer；单选取选项分，多选求和，反向时用最高可能分减当前分。再乘权重。
- visibleWhen 为 `{question,equals}`，只能引用前面的非条件单选题。隐藏题不进入必填校验或得分分母，隐藏答案不保存。可选题未答时为 0 分，但保留该可见题的满分。
- 维度和总分均为加权分／加权满分×100，保留两位小数，附逐题解释。

完整初始化内容位于 backend/src/main/resources/demo-survey.json。固定答案 `yes, quality=4, support=[docs,peer], friction=2` 得 16/19×100=84.21；选择 no 并只选 docs、friction=2，隐藏 quality，得 5/11×100=45.45。将新版本 quality 权重从 2 改为 3，第一组答案变为 19/23×100=82.61，旧版保持 84.21。

## HTTP 接口

Kotlin 的 UTF-8 JSON 响应带 X-Sample-Protocol:2。错误体 `{error}`，输入错误 400、范围不存在 404、状态／幂等冲突 409、Ruby 故障 502。

- GET/POST `/api/surveys`：创建体 `{name,actor}`；GET `/api/surveys/{id}` 返回版本和事件；GET `/api/surveys/{id}/stats` 返回版本统计。
- POST `/api/surveys/{id}/revisions`：`{actor}`，从最新版本复制一个草稿；每问卷最多一个未发布草稿。
- GET/PUT `/api/revisions/{id}`：编辑体 `{actor,editVersion,content}`，仅草稿可修改；编辑版本冲突 409。
- POST `/api/revisions/{id}/publish|close`：`{actor}`。
- POST `/api/submissions`：`{revisionId,requestId,respondent,answers}`。10 个相同请求只保存一份结果；同键不同身份、版本或答案 409。
- GET `/api/submissions?revisionId=1&offset=0&limit=25`，limit 1..100；GET `/api/submissions/{id}`、`/{id}/export` 返回包含原始版本的答卷。
- Kotlin→Ruby POST `/score`：`{protocolVersion:2,revisionId,content,answers}`；返回 component、revisionId、score、weightedTotal、maximum、parts、dimensions、hidden。Kotlin 验证版本、题号、数量、数值范围和合计，再提交结果。超时取消 HTTP 请求，响应大小有上限。

## 验收

`python samples/generate.py --url http://127.0.0.1:18141 --count 10000 --seed 20260919` 为初始化版本产生真实答卷；可用 --revision 指定保留演示题号的发布版本。固定种子重跑复用幂等键。

仓库运行器 `python src/app/main/src/test/python/polyglot_runner.py verify --project survey-scoring --profile standard --work <隔离目录>`，快速档为 quick。标准档实际调用 Ruby 10,000 次并使用独立算式核对；覆盖条件题、并发、版本历史、关闭、导出、浏览器、下游故障及修改 Ruby 实现后的差异检测。

隔离故障闸门 SAMPLE_FAULT_POINT 可取 score-returned 或 submission-written，配合 SAMPLE_FAULT_DIR 在评分后或事务内写入 ready 文件，再等 release 文件，默认关闭、60 秒超时。运行器核验启动器的真实 JVM 归属并终止 JVM，检查不留下重复或半成品答卷。

构建产物、数据库、生成数据和测试结果不提交。Windows 实际状态见上级验证记录；Linux 实机及产品部署待验证。
