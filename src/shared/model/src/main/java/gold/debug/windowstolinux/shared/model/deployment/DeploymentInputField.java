package gold.debug.windowstolinux.shared.model.deployment;

import java.util.List;
import java.util.Objects;

/** A non-secret missing deployment parameter with bounded choices and a stable message key. */
public record DeploymentInputField(String id, String labelKey, String helpKey, String value, List<String> choices) {
    /** Validates input descriptors before they reach either AI or a desktop form. */
    public DeploymentInputField {
        if (id == null || !id.matches("[a-zA-Z0-9._/-]{1,160}")) throw new IllegalArgumentException("invalid input identifier");
        Objects.requireNonNull(labelKey); Objects.requireNonNull(helpKey); Objects.requireNonNull(value);
        choices = List.copyOf(choices);
        if (value.length() > 4096 || choices.size() > 64) throw new IllegalArgumentException("input descriptor exceeds limits");
    }
}
