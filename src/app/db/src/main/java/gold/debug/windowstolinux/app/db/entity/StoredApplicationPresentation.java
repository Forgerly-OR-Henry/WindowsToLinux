package gold.debug.windowstolinux.app.db.entity;

import gold.debug.windowstolinux.shared.model.health.UserAccessUrl;
import java.util.Objects;
import java.util.Optional;

/** User-adjustable application name, classification and access entry. / 用户可调整的应用名称、分类及访问入口。 */
public record StoredApplicationPresentation(String key, String name, String category, Optional<UserAccessUrl> accessUrl) {
    /** Keeps classification closed and access URLs under the existing URL policy. / 封闭分类，访问链接沿用既有 URL 策略。 */
    public StoredApplicationPresentation {
        if (!Objects.requireNonNull(key).matches("(managed:[a-z0-9][a-z0-9-]{0,62}|external:[a-f0-9-]{36})")) throw new IllegalArgumentException("invalid application key");
        name = Objects.requireNonNull(name).trim();
        if (name.isEmpty() || name.length() > 240 || name.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("invalid display name");
        if (!category.equals("WEBSITE") && !category.equals("APP")) throw new IllegalArgumentException("invalid application category");
        Objects.requireNonNull(accessUrl);
    }
}
