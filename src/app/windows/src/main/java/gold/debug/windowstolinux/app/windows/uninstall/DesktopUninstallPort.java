package gold.debug.windowstolinux.app.windows.uninstall;

import java.util.List;
import java.util.Objects;

/**
 * Split main-process and external-worker uninstall seam with no source, backup or remote operation. / 不含源码、备份或远端操作的主进程与外部执行器分阶段卸载接缝。
 */
public interface DesktopUninstallPort {
    /**
     * Stops only application-owned desktop tasks. / 仅停止本应用持有的桌面任务。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @return constructed or resolved step evidence / 构造或解析得到的步骤证据
     * @throws DesktopUninstallException if the desktop uninstall boundary rejects the operation / Desktop卸载边界拒绝当前操作时
     */
    StepEvidence stopOwnedTasks(DesktopUninstallRequest request) throws DesktopUninstallException;

    /**
     * Verifies external-worker identity, main-process exit and authenticated handoff. / 验证外部执行器身份、主进程退出及已认证交接。
     *
     * @param handoff handoff / 交接
     * @return constructed or resolved handoff evidence / 构造或解析得到的交接证据
     * @throws DesktopUninstallException if the desktop uninstall boundary rejects the operation / Desktop卸载边界拒绝当前操作时
     */
    HandoffEvidence verifyIndependentWorker(DesktopUninstallHandoff handoff) throws DesktopUninstallException;

    /**
     * Re-verifies jpackage, install/data markers and credential ownership in the external worker. / 在外部执行器中重新验证 jpackage、安装/数据标记及凭据归属。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @return constructed or resolved boundary evidence / 构造或解析得到的边界证据
     * @throws DesktopUninstallException if the desktop uninstall boundary rejects the operation / Desktop卸载边界拒绝当前操作时
     */
    BoundaryEvidence verifyManagedBoundaries(DesktopUninstallRequest request) throws DesktopUninstallException;

    /**
     * Removes program content while preserving the fixed data child. / 在保留固定 data 子目录时移除程序内容。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @return constructed or resolved removal evidence / 构造或解析得到的移除证据
     * @throws DesktopUninstallException if the desktop uninstall boundary rejects the operation / Desktop卸载边界拒绝当前操作时
     */
    RemovalEvidence removeProgram(DesktopUninstallRequest request) throws DesktopUninstallException;

    /**
     * Removes only the verified application data root. / 仅移除已验证的应用数据根。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @return constructed or resolved removal evidence / 构造或解析得到的移除证据
     * @throws DesktopUninstallException if the desktop uninstall boundary rejects the operation / Desktop卸载边界拒绝当前操作时
     */
    RemovalEvidence removeData(DesktopUninstallRequest request) throws DesktopUninstallException;

    /**
     * Removes only the verified WindowsToLinux credential namespace. / 仅移除已验证的 WindowsToLinux 凭据命名空间。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @return constructed or resolved removal evidence / 构造或解析得到的移除证据
     * @throws DesktopUninstallException if the desktop uninstall boundary rejects the operation / Desktop卸载边界拒绝当前操作时
     */
    RemovalEvidence removeCredentials(DesktopUninstallRequest request) throws DesktopUninstallException;

    /**
     * Generic verified uninstall step. / 通用已验证卸载步骤。
     *
     * @param completed completed / 已完成
     * @param verified verified / 已验证
     * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
     */
    record StepEvidence(boolean completed, boolean verified, List<String> evidence) {
        /**
         * Validates evidence. / 校验证据。
         *
         * @param completed completed / 已完成
         * @param verified verified / 已验证
         * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
         */
        public StepEvidence {
            evidence = validatedEvidence(evidence);
        }
    }

    /**
     * External worker and handoff evidence. / 外部执行器及交接证据。
     *
     * @param independentWorkerVerified independent worker verified / 独立工作线程已验证
     * @param mainProcessExited main process exited / 主进程Exited
     * @param handoffAuthenticated handoff authenticated / 交接已认证
     * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
     */
    record HandoffEvidence(boolean independentWorkerVerified, boolean mainProcessExited, boolean handoffAuthenticated,
            List<String> evidence) {
        /**
         * Validates evidence. / 校验证据。
         *
         * @param independentWorkerVerified independent worker verified / 独立工作线程已验证
         * @param mainProcessExited main process exited / 主进程Exited
         * @param handoffAuthenticated handoff authenticated / 交接已认证
         * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
         */
        public HandoffEvidence {
            evidence = validatedEvidence(evidence);
        }
    }

    /**
     * Managed root and namespace evidence. / 受管根目录及命名空间证据。
     *
     * @param jpackageLayoutVerified jpackage layout verified / jpackage布局已验证
     * @param installMarkerVerified install marker verified / 安装标记已验证
     * @param dataMarkerVerified data marker verified / 数据标记已验证
     * @param credentialNamespaceVerified credential namespace verified / 凭据命名空间已验证
     * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
     */
    record BoundaryEvidence(boolean jpackageLayoutVerified, boolean installMarkerVerified, boolean dataMarkerVerified,
            boolean credentialNamespaceVerified, List<String> evidence) {
        /**
         * Validates evidence. / 校验证据。
         *
         * @param jpackageLayoutVerified jpackage layout verified / jpackage布局已验证
         * @param installMarkerVerified install marker verified / 安装标记已验证
         * @param dataMarkerVerified data marker verified / 数据标记已验证
         * @param credentialNamespaceVerified credential namespace verified / 凭据命名空间已验证
         * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
         */
        public BoundaryEvidence {
            evidence = validatedEvidence(evidence);
        }
    }

    /**
     * Exact removal evidence and residual items. / 精确删除证据及残留项目。
     *
     * @param completed completed / 已完成
     * @param verified verified / 已验证
     * @param residualItems residual items / 残留项目集合
     * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
     */
    record RemovalEvidence(boolean completed, boolean verified, List<String> residualItems, List<String> evidence) {
        /**
         * Validates bounded residuals and evidence. / 校验有界残留及证据。
         *
         * @param completed completed / 已完成
         * @param verified verified / 已验证
         * @param residualItems residual items / 残留项目集合
         * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
         * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
         */
        public RemovalEvidence {
            residualItems = checkedTexts(residualItems, "residualItems", true);
            evidence = checkedTexts(evidence, "evidence", false);
            if ((!completed || !verified) && residualItems.isEmpty()) {
                throw new IllegalArgumentException("incomplete removal requires exact residual items");
            }
        }
    }

    /**
     * Checks returned evidence against the exact requested operation.
     * <p>按精确请求操作检查返回证据。
     *
     * @param values ordered contents supplied to the current conversion or validation / 提供给当前转换或校验的有序内容
     * @return constructed or resolved list / 构造或解析得到的列表
     */
    private static List<String> validatedEvidence(List<String> values) {
        return checkedTexts(values, "evidence", false);
    }

    /**
     * Validates and produces checked texts for the next contract boundary.
     * <p>校验并生成供下一契约边界使用的已检查文本集合。
     *
     * @param values ordered contents supplied to the current conversion or validation / 提供给当前转换或校验的有序内容
     * @param field field name or input definition being validated / 正在校验的字段名或输入定义
     * @param emptyAllowed empty allowed / 空Allowed
     * @return constructed or resolved list / 构造或解析得到的列表
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
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
        if (result.size() != values.size())
            throw new IllegalArgumentException(field + " has duplicates");
        return result;
    }
}
