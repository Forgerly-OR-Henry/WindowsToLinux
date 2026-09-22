package gold.debug.windowstolinux.app.service.execution.lifecycle;

import java.util.List;
import java.util.Map;

import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.shared.model.lifecycle.*;

/**
 * Scan candidates and existing registrations; absent managed ownership stays unavailable. / 扫描候选及既有登记，缺少受管归属时保持不可用。
 *
 * @param server server identity or selected server configuration / 服务器身份或所选服务器配置
 * @param candidates candidates / 候选集合
 * @param issues issues / 问题集合
 * @param registrations registrations / 登记集合
 */
public record ApplicationScan(ServerProfile server, List<DiscoveredApplication> candidates,
        List<ExternalScanIssueType> issues, Map<String, String> registrations) {
    /**
     * Freezes the non-secret scan result. / 固化非秘密扫描结果。
     *
     * @param server server identity or selected server configuration / 服务器身份或所选服务器配置
     * @param candidates candidates / 候选集合
     * @param issues issues / 问题集合
     * @param registrations registrations / 登记集合
     */
    public ApplicationScan {
        candidates = List.copyOf(candidates);
        issues = List.copyOf(issues);
        registrations = Map.copyOf(registrations);
    }

    /**
     * Reports whether this candidate can be attached through a supported contract. / 报告候选是否能经受支持的契约接入。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @return true when this candidate can be attached through a supported contract, false otherwise / 报告候选是否能经受支持的契约接入时为 true，否则为 false
     */
    public boolean adoptable(DiscoveredApplication value) {
        return !value.managed() || registrations.getOrDefault(value.target().key(), "").startsWith("managed:");
    }
}
