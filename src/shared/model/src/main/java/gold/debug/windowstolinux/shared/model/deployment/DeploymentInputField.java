package gold.debug.windowstolinux.shared.model.deployment;

import java.util.List;
import java.util.Objects;

/** A non-secret missing deployment parameter with bounded choices and a stable message key. / 非秘密的缺失部署参数，带有界候选和稳定消息键。 */
public record DeploymentInputField(String id, String labelKey, String helpKey, String value, List<String> choices) {
    /** Validates input descriptors before they reach either AI or a desktop form. / 在输入描述到达 AI 或桌面表单前进行校验。 */
    public DeploymentInputField {
        if (id == null || !id.matches("[a-zA-Z0-9._/-]{1,160}")) throw new IllegalArgumentException("invalid input identifier");
        Objects.requireNonNull(labelKey); Objects.requireNonNull(helpKey); Objects.requireNonNull(value);
        choices = List.copyOf(choices);
        int limit = id.equals("applicationDeclaration") || id.endsWith("/applicationDeclaration") ? 65536 : 4096;
        if (value.length() > limit || choices.size() > 64) throw new IllegalArgumentException("input descriptor exceeds limits");
    }
}
