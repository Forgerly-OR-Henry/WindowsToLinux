package gold.debug.windowstolinux.shared.deploy.contract.result.compatibility;

import java.util.List;
import java.util.Objects;

/** A conservative host-support status with ordered review evidence. / 带有有序审阅证据的保守主机支持状态。 */
public record HostSupportDecision(HostSupportStatus support, List<String> evidence) {
    /** Creates an immutable support decision. / 创建不可变支持决定。 */
    public HostSupportDecision {
        support = Objects.requireNonNull(support, "support");
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
    }
}
