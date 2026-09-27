package gold.debug.windowstolinux.mod.deployment.db;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentProjectSource;

/** Saved project identity and complete observed source revision. / 已保存项目身份及完整已观察来源修订。
 * @param id stable project identity / 稳定项目身份
 * @param displayName user-facing name / 面向用户名称
 * @param source complete observed facts / 完整已观察事实
 * @param createdAt first save time / 首次保存时间
 * @param updatedAt last actual update time / 最近实际更新时间 */
public record DeploymentProjectRecord(UUID id, String displayName, DeploymentProjectSource source, Instant createdAt,
        Instant updatedAt) {
    /** Validates the bounded complete row at exact millisecond precision. / 按精确毫秒精度校验有界完整记录。
     * @param id stable project identity / 稳定项目身份
     * @param displayName user-facing name / 面向用户名称
     * @param source complete observed facts / 完整已观察事实
     * @param createdAt first save time / 首次保存时间
     * @param updatedAt last actual update time / 最近实际更新时间 */
    public DeploymentProjectRecord {
        Objects.requireNonNull(id);
        Objects.requireNonNull(source);
        if (displayName == null || displayName.isBlank() || displayName.length() > 256
                || displayName.chars().anyMatch(c -> c < 32 || c == 127))
            throw new IllegalArgumentException("Invalid project display name");
        createdAt = millisecond(createdAt);
        updatedAt = millisecond(updatedAt);
        if (updatedAt.isBefore(createdAt))
            throw new IllegalArgumentException("Project update precedes creation");
    }

    /** Requires exact database time precision without inventing another observation. / 要求精确数据库时间精度，不虚构另一观测时点。
     * @param value supplied observation / 传入观测
     * @return exact observation / 精确观测 */
    private static Instant millisecond(Instant value) {
        Objects.requireNonNull(value);
        if (value.getNano() % 1_000_000 != 0)
            throw new IllegalArgumentException("Project observations require millisecond precision");
        return value;
    }
}
