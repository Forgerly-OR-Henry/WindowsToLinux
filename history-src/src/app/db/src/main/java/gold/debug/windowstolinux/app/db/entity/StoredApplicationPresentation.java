package gold.debug.windowstolinux.app.db.entity;

import java.util.Objects;
import java.util.Optional;

import gold.debug.windowstolinux.shared.model.health.UserAccessUrl;

/**
 * User-adjustable application name, classification and access entry. / 用户可调整的应用名称、分类及访问入口。
 *
 * @param key lookup key within the current contract / 当前契约内的查找键
 * @param name human-readable name or diagnostic field label / 可读名称或诊断字段标签
 * @param category category / 类别
 * @param accessUrl access url / 访问URL
 */
public record StoredApplicationPresentation(String key, String name, String category,
        Optional<UserAccessUrl> accessUrl) {
    /**
     * Keeps classification closed and access URLs under the existing URL policy. / 封闭分类，访问链接沿用既有 URL 策略。
     *
     * @param key lookup key within the current contract / 当前契约内的查找键
     * @param name human-readable name or diagnostic field label / 可读名称或诊断字段标签
     * @param category category / 类别
     * @param accessUrl access url / 访问URL
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public StoredApplicationPresentation {
        if (!Objects.requireNonNull(key).matches("(managed:[a-z0-9][a-z0-9-]{0,62}|external:[a-f0-9-]{36})"))
            throw new IllegalArgumentException("invalid application key");
        name = Objects.requireNonNull(name).trim();
        if (name.isEmpty() || name.length() > 240 || name.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("invalid display name");
        if (!category.equals("WEBSITE") && !category.equals("APP"))
            throw new IllegalArgumentException("invalid application category");
        Objects.requireNonNull(accessUrl);
    }
}
