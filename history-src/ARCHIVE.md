# Frozen 0.x workspace archive / 冻结的 0.x 工作区归档

- Baseline UTC: 2026-09-25T17:20:25.998740+00:00
- Git HEAD: `da8a390121e768cc809fc4ffacd0200e0a3ffe43`; includes existing uncommitted changes. / 包含既有未提交改动。
- Source files: 2835; source and destination SHA-256 verified twice. / 已核对源文件集合及逐文件摘要。
- Restore only into a separate working copy; use JDK 21 and the archived root POM. Never build, format, index or import this frozen tree as an active Maven project. / 只在独立工作副本中恢复，使用 JDK 21 和归档根 POM，禁止在此冻结目录构建、格式化或注册活动模块。
- No runtime databases, credentials, dependencies, generated output, Git metadata or IDE personal state are included. Hash equality does not prove runtime or future dependency availability. / 不包含运行数据库、凭据、下载依赖、生成产物、Git 元数据或 IDE 个人状态；摘要不代表实机验收或外部依赖永久可用。

## Exclusions / 排除项

- `.ai-workspace`: generated-or-private
- `.git`: generated-or-private
- `.idea`: generated-or-private
- `.mvn/formatting/node_modules`: generated-or-private
- `.mvn/formatting/target`: generated-or-private
- `.pnpm-store`: generated-or-private
- `.ruff_cache`: generated-or-private
- `src/app/db/data`: runtime-data
- `src/app/db/target`: generated-or-private
- `src/app/main/target`: generated-or-private
- `src/app/secret/target`: generated-or-private
- `src/app/service/target`: generated-or-private
- `src/app/target`: generated-or-private
- `src/app/ui/target`: generated-or-private
- `src/app/windows/target`: generated-or-private
- `src/shared/agent/target`: generated-or-private
- `src/shared/ai/target`: generated-or-private
- `src/shared/backup/target`: generated-or-private
- `src/shared/config/target`: generated-or-private
- `src/shared/deploy/target`: generated-or-private
- `src/shared/git/target`: generated-or-private
- `src/shared/linux-sshd/src/test/python/__pycache__`: generated-or-private
- `src/shared/linux-sshd/target`: generated-or-private
- `src/shared/linux/target`: generated-or-private
- `src/shared/model/target`: generated-or-private
- `src/shared/source/target`: generated-or-private
- `src/shared/standard/analyze/target`: generated-or-private
- `src/shared/standard/deploy/target`: generated-or-private
- `src/shared/standard/target`: generated-or-private
- `src/shared/target`: generated-or-private
- `src/web/api/target`: generated-or-private
- `src/web/auth/target`: generated-or-private
- `src/web/db/target`: generated-or-private
- `src/web/file/target`: generated-or-private
- `src/web/frontend/.playwright-browsers`: generated-or-private
- `src/web/frontend/dist`: generated-or-private
- `src/web/frontend/node_modules`: generated-or-private
- `src/web/frontend/test-results`: generated-or-private
- `src/web/main/target`: generated-or-private
- `src/web/secret/target`: generated-or-private
- `src/web/service/target`: generated-or-private
- `src/web/target`: generated-or-private
- `src/web/task/target`: generated-or-private

## Ignored but retained / 被忽略但仍保留的必要文件

- None / 无
