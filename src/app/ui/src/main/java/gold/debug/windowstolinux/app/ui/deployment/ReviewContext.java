package gold.debug.windowstolinux.app.ui.deployment;

import gold.debug.windowstolinux.app.service.source.ReviewedSourcePreparation;

import java.util.Optional;

/** Narrow current-review view consumed by the optional AI page. / 可选 AI 页面使用的窄当前审阅视图。 */
public interface ReviewContext {
    /** Returns the latest review when available. / 返回最新审阅（如有）。 */
    Optional<ReviewedSourcePreparation> reviewedPreparation();
}
