package gold.debug.windowstolinux.app.service.mod.store;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import gold.debug.windowstolinux.app.service.mod.install.PlatformModInstallation.Receipt;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.app.service.mod.store.ModStoreSelection.ReleaseKey;

/** Actual per-package facts retained even after cancellation or uncertainty. / 取消或不确定后仍保留的实际逐包事实。
 * @param batchId original batch identity / 原批次身份
 * @param planRevision confirmed plan revision / 已确认计划修订
 * @param selection unchanged user choices / 未擅改的用户选择
 * @param outcome truthful aggregate result / 真实汇总结果
 * @param packages every planned package outcome / 每个计划包结果
 * @param requiredReady physically verified root-required closure / 物理核验的主项必需闭包
 * @param allSelectedReady whether every selected package is physically ready / 每个选中包是否物理就绪
 */
public record ModStoreBatchResult(UUID batchId, long planRevision, ModStoreSelection selection, TaskResult outcome,
                                  List<PackageResult> packages, boolean requiredReady, boolean allSelectedReady) {
    /** Copies actual completed and unattempted facts. / 复制实际完成及未尝试事实。
     * @param batchId original batch identity / 原批次身份
     * @param planRevision confirmed revision / 已确认修订
     * @param selection user choices / 用户选择
     * @param outcome aggregate result / 汇总结果
     * @param packages all actual outcomes / 全部实际结果
     * @param requiredReady required readiness / 必需就绪状态
     * @param allSelectedReady selected readiness / 选中就绪状态
     */
    public ModStoreBatchResult {
        packages = List.copyOf(packages);
    }

    /** Actual execution state, distinct from whether a program is enabled. / 实际执行状态，与程序是否启用分开。 */
    public enum State {
        /** Reused an existing verified program; final readiness is checked separately. / 复用既有已核验程序，最终就绪状态另行检查。 */
        REUSED,
        /** The original installer returned a successful receipt. / 原安装器返回成功凭据。 */
        INSTALLED,
        /** Package acquisition encountered a known failure. / 包获取发生已知失败。 */
        FAILED,
        /** Original cancellation prevented this package from completing. / 原取消请求阻止此包完成。 */
        CANCELLED,
        /** This planned package was neither reused nor submitted to the installer. / 此计划包既未复用也未提交安装器。 */
        UNATTEMPTED,
        /** The original installation outcome requires reconciliation using its retained UUID. / 原安装结果需使用保留 UUID 核对。 */
        UNKNOWN
    }

    /** One original installation fact without replay authority. / 不携带重放权限的原安装事实。
     * @param key exact planned release / 精确计划发布
     * @param state actual execution state / 实际执行状态
     * @param receipt original successful installation receipt / 原成功安装凭据
     * @param operationId original installer identity when allocated / 已分配时的原安装身份
     * @param problem actual rejection or uncertainty / 实际拒绝或不确定
     */
    public record PackageResult(ReleaseKey key, State state, Optional<Receipt> receipt, Optional<UUID> operationId,
                                Optional<Problem> problem) {
    }
}
