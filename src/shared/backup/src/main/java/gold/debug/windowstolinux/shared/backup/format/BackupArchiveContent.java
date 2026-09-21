package gold.debug.windowstolinux.shared.backup.format;

import gold.debug.windowstolinux.shared.backup.manifest.BackupMember;

import java.util.Objects;

/**
 * One manifest-bound stream used to construct an archive. / 用于构建归档的一个清单绑定流。
 *
 * @param member member / 成员
 * @param stream stream / 流
 */
public record BackupArchiveContent(BackupMember member, BackupArchiveStream stream) {
    /**
     * Requires a member and a fresh-stream factory. / 要求成员及全新流工厂。
     *
     * @param member member / 成员
     * @param stream stream / 流
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public BackupArchiveContent {
        member = Objects.requireNonNull(member, "member");
        stream = Objects.requireNonNull(stream, "stream");
    }
}
