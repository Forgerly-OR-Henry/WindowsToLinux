package gold.debug.windowstolinux.app.service.execution.lifecycle;

import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.shared.model.lifecycle.*;
import java.util.List;
import java.util.Map;

/** Scan candidates and existing registrations; absent managed ownership stays unavailable. / 扫描候选及既有登记，缺少受管归属时保持不可用。 */
public record ApplicationScan(ServerProfile server, List<DiscoveredApplication> candidates,
                              List<ExternalScanIssueType> issues, Map<String, String> registrations) {
    /** Freezes the non-secret scan result. / 固化非秘密扫描结果。 */
    public ApplicationScan { candidates = List.copyOf(candidates); issues = List.copyOf(issues); registrations = Map.copyOf(registrations); }
    /** Reports whether this candidate can be attached through a supported contract. / 报告候选是否能经受支持的契约接入。 */
    public boolean adoptable(DiscoveredApplication value) {
        return !value.managed() || registrations.getOrDefault(value.target().key(), "").startsWith("managed:");
    }
}
