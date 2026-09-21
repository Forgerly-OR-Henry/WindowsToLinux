package gold.debug.windowstolinux.shared.model.recovery;

import java.util.Objects;

/**
 * One proposed terminal command; approval is owned by the service. / 单条建议命令，由服务负责批准。
 *
 * @param command fixed or explicitly reviewed command text / 固定或显式审阅的命令文本
 * @param reason reason / 原因
 * @param expected identity, value or state required for verification / 验证要求的身份、内容或状态
 * @param highImpact high impact / 高影响
 */
public record RecoveryAction(String command, String reason, String expected, boolean highImpact) {
    /**
     * Validates and binds the inputs required by recovery action.
     * <p>校验并绑定恢复动作所需输入。
     *
     * @param command fixed or explicitly reviewed command text / 固定或显式审阅的命令文本
     * @param reason reason / 原因
     * @param expected identity, value or state required for verification / 验证要求的身份、内容或状态
     * @param highImpact high impact / 高影响
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public RecoveryAction {
        command = Objects.requireNonNull(command).trim();
        reason = Objects.requireNonNull(reason).trim(); expected = Objects.requireNonNull(expected).trim();
        if (command.length() > 2048 || command.chars().anyMatch(Character::isISOControl)
                || reason.isBlank() || reason.length() > 2000 || expected.length() > 2000)
            throw new IllegalArgumentException("invalid recovery proposal");
        String lower = command.toLowerCase(java.util.Locale.ROOT);
        highImpact |= java.util.regex.Pattern.compile("reboot|shutdown|poweroff|firewall|iptables|nft|selinux|setenforce|passwd|authorized_keys|sshd_config|useradd|usermod|systemctl|service ")
                .matcher(lower).find();
    }
    /**
     * Returns the diagnostic text representation of this object.
     * <p>返回当前对象的诊断文本表示。
     *
     * @return the diagnostic text representation of this object / 当前对象的诊断文本表示
     */
    @Override public String toString() { return "RecoveryAction[redacted]"; }
}
