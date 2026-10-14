# 149/153：受保护备份的本地内存校验

[验证器](../../../test/tools/backup/verify_backup_memory.py)在正常非管理员Windows用户下核密文原SHA、原生当前用户DPAPI解密、明文SHA/长度；不落明文文件。SQLite包模式核原SDK成功回执、严格固定USTAR成员和manifest、12份逐件SHA/长度，并在内存只读检查SQLite完整性及外键。配置/回执模式还须冻结metadata文件及SHA，核全部源路径、dev/inode/UID/GID/mode/大小映射，拒绝LINK、硬链、稀疏、PAX、重复/额外成员、DB路径和SQLite文件头。数据正文不输出，不将这些包自动恢复为活动任务锁或正式App。

145实际正常用户6个公开定点验证包含Java DPAPI密文与Python兼容、自造SQLite及摘要/截断/非SQLite反例。149新增17个公开场景通过，含真实已commit WAL数据仍在WAL时的原生在线备份；首次目标保留WAL header2/2导致内存SQLITE_CANTOPEN，原失败保留。仅对私有新目标在backup后执行原生`PRAGMA journal_mode=DELETE`，header1/1、WAL提交行保留且无sidecar后通过；未修改源或手工修header。

153最终仅运行11个新增context场景及3个直接受影响snapshot场景，14/14通过，含WAL正例、UNKNOWN和恶意tar边界；没有重跑全套或将旧源码结果当最终验证。两份最终正式源码等于实测：验证器SHA `823a64b613aeabb4a54ecdaa37e20469543b9118758d6b31c8b115fc43c345ec`，[测试](../../../test/tools/backup/test_verify_backup_memory.py)SHA `e9e91b154de29049fc526ce41a1542c24636a556707f44ca8b3795a0cb247b9e`。正常CIM核153两组进程全退，公开tmp精确清；149四组亦已闭合。原证分别在`.ai-workspace/agent-live-evidence/agent-autonomy-20261012/backup-bundle-verifier-149/`与`backup-context-verifier-153/`。

此处均为公开本地验证；真实Linux限额、实际12库及310文件的导回/恢复检查须另记。DPAPI仍依赖原Windows用户，Python/SQLite内部副本不保证全部安全擦除；不因此声称业务数据已恢复或备份已完成。
