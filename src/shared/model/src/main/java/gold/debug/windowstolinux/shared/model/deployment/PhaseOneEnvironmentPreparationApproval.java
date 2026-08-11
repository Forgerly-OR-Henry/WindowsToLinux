package gold.debug.windowstolinux.shared.model.deployment;

import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;
import gold.debug.windowstolinux.shared.model.message.LocalizedOperationException;

import java.time.Instant;
import java.util.Objects;

/**
 * A single, explicit confirmation to install the fixed phase-one Ubuntu toolset on one trusted target. It deliberately carries no package, command or path input.
 *
 * <p>在一个可信目标上安装固定一期 Ubuntu 工具集的单次明确确认。它刻意不携带包、命令或路径输入。
 *
 * @param serverId the {@code serverId} value / {@code serverId} 值
 * @param installationAccepted the {@code installationAccepted} value / {@code installationAccepted} 值
 * @param confirmedAt the {@code confirmedAt} value / {@code confirmedAt} 值
 */
public record PhaseOneEnvironmentPreparationApproval(
        String serverId,
        boolean installationAccepted,
        Instant confirmedAt
) {
    /**
     * Creates a {@code PhaseOneEnvironmentPreparationApproval} instance.
     *
     * <p>创建 {@code PhaseOneEnvironmentPreparationApproval} 实例。
     *
     * @param serverId the {@code serverId} value / {@code serverId} 值
     * @param installationAccepted the {@code installationAccepted} value / {@code installationAccepted} 值
     * @param confirmedAt the {@code confirmedAt} value / {@code confirmedAt} 值
     * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
     */
    public PhaseOneEnvironmentPreparationApproval {
        serverId = identifier(serverId, "serverId");
        confirmedAt = Objects.requireNonNull(confirmedAt, "confirmedAt");
    }

    /**
     * Rejects a declined or differently targeted confirmation before SSH mutation begins.
     *
     * <p>在 SSH 修改开始前拒绝被否决或目标不匹配的确认。
     *
     * @param expectedServerId the {@code expectedServerId} value / {@code expectedServerId} 值
     */
    public void requireAcceptedFor(String expectedServerId) {
        if (!installationAccepted) {
            throw new LocalizedOperationException(LocalizedMessage.of("environment.confirmationRequired"),
                    "Explicit confirmation is required before installing the phase-one target environment");
        }
        if (!serverId.equals(identifier(expectedServerId, "expectedServerId"))) {
            throw new LocalizedOperationException(LocalizedMessage.of("environment.serverMismatch"),
                    "Environment preparation approval does not belong to the current target server");
        }
    }

    private static String identifier(String value, String name) {
        value = Objects.requireNonNull(value, name).toLowerCase(java.util.Locale.ROOT);
        if (!value.matches("[a-z0-9][a-z0-9-]{0,62}")) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return value;
    }
}
