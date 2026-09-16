package gold.debug.windowstolinux.app.ui.managed;

import java.util.Objects;

/**
 * Represents an immutable {@code ManagedPageState} value.
 *
 * <p>表示不可变的 {@code ManagedPageState} 值。
 *
 * @param applicationId the {@code applicationId} value / {@code applicationId} 值
 * @param output the {@code output} value / {@code output} 值
 */
public record ManagedPageState(String applicationId, String output, String typeFilter, String serverFilter) {
    /** Preserves earlier page snapshots. / 保留此前页面快照。 */
    public ManagedPageState(String applicationId, String output) { this(applicationId, output, "", ""); }
    /**
     * Creates a {@code ManagedPageState} instance.
     *
     * <p>创建 {@code ManagedPageState} 实例。
     *
     * @param applicationId the {@code applicationId} value / {@code applicationId} 值
     * @param output the {@code output} value / {@code output} 值
     * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
     */
    public ManagedPageState {
        Objects.requireNonNull(applicationId, "applicationId");
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(typeFilter, "typeFilter"); Objects.requireNonNull(serverFilter, "serverFilter");
    }
}
