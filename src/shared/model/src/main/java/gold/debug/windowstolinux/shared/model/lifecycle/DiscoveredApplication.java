package gold.debug.windowstolinux.shared.model.lifecycle;

import java.util.Objects;

/** Bounded scan evidence; a managed marker never grants ownership. / 有界扫描证据，受管标记本身不授予归属。 */
public record DiscoveredApplication(ExternalApplicationTarget target, String name, RuntimeState state,
                                    boolean canStart, boolean canStop, boolean managed) {
    /** Bounds display metadata without retaining process environments. / 限制显示元数据，不保留进程环境。 */
    public DiscoveredApplication {
        Objects.requireNonNull(target, "target"); Objects.requireNonNull(state, "state");
        if (Objects.requireNonNull(name, "name").isBlank() || name.length() > 240 || name.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("invalid application display name");
    }
}
