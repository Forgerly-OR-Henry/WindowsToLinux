package gold.debug.windowstolinux.app.windows.uninstall;

import java.util.List;
import java.util.Objects;

/** Narrow uninstall seam with no source-project, backup or remote-server operation. / 不含源码项目、备份或远端服务器操作的卸载窄接缝。 */
public interface DesktopUninstallPort {
    /** Stops only application-owned desktop tasks. / 仅停止本应用持有的桌面任务。 */
    StepEvidence stopOwnedTasks(DesktopUninstallRequest request) throws DesktopUninstallException;

    /** Verifies jpackage, install/data markers and credential namespace ownership. / 验证 jpackage、安装/数据标记及凭据命名空间归属。 */
    BoundaryEvidence verifyManagedBoundaries(DesktopUninstallRequest request) throws DesktopUninstallException;

    /** Removes program content while preserving the fixed data child. / 在保留固定 data 子目录时移除程序内容。 */
    RemovalEvidence removeProgram(DesktopUninstallRequest request) throws DesktopUninstallException;

    /** Removes only the verified application data root. / 仅移除已验证的应用数据根。 */
    RemovalEvidence removeData(DesktopUninstallRequest request) throws DesktopUninstallException;

    /** Removes only the verified WindowsToLinux credential namespace. / 仅移除已验证的 WindowsToLinux 凭据命名空间。 */
    RemovalEvidence removeCredentials(DesktopUninstallRequest request) throws DesktopUninstallException;

    /** Generic verified uninstall step. / 通用已验证卸载步骤。 */
    record StepEvidence(boolean completed, boolean verified, List<String> evidence) {
        /** Validates evidence. / 校验证据。 */
        public StepEvidence { evidence = validatedEvidence(evidence); }
    }

    /** Managed root and namespace evidence. / 受管根目录及命名空间证据。 */
    record BoundaryEvidence(
            boolean jpackageLayoutVerified,
            boolean installMarkerVerified,
            boolean dataMarkerVerified,
            boolean credentialNamespaceVerified,
            List<String> evidence
    ) {
        /** Validates evidence. / 校验证据。 */
        public BoundaryEvidence { evidence = validatedEvidence(evidence); }
    }

    /** Exact removal evidence and residual items. / 精确删除证据及残留项目。 */
    record RemovalEvidence(
            boolean completed,
            boolean verified,
            List<String> residualItems,
            List<String> evidence
    ) {
        /** Validates bounded residuals and evidence. / 校验有界残留及证据。 */
        public RemovalEvidence {
            residualItems = checkedTexts(residualItems, "residualItems", true);
            evidence = checkedTexts(evidence, "evidence", false);
            if ((!completed || !verified) && residualItems.isEmpty()) {
                throw new IllegalArgumentException("incomplete removal requires exact residual items");
            }
        }
    }

    private static List<String> validatedEvidence(List<String> values) {
        return checkedTexts(values, "evidence", false);
    }

    private static List<String> checkedTexts(List<String> values, String field, boolean emptyAllowed) {
        Objects.requireNonNull(values, field);
        if ((!emptyAllowed && values.isEmpty()) || values.size() > 64) {
            throw new IllegalArgumentException(field + " has an invalid item count");
        }
        List<String> result = values.stream().map(value -> {
            String item = Objects.requireNonNull(value, field + " item").trim();
            if (item.isEmpty() || item.length() > 512 || item.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException(field + " item is invalid");
            }
            return item;
        }).distinct().toList();
        if (result.size() != values.size()) throw new IllegalArgumentException(field + " has duplicates");
        return result;
    }
}
