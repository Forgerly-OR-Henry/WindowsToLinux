package gold.debug.windowstolinux.app.ui.deployment;

import gold.debug.windowstolinux.app.service.source.ReviewedSourcePreparation;

import java.util.Optional;

/**
 * Narrow current-review view consumed by the optional AI page. / 可选 AI 页面使用的窄当前审阅视图。
 */
public interface ReviewContext {
    /**
     * Returns the latest review when available. / 返回最新审阅（如有）。
     *
     * @return matching result, or empty when no admitted value exists / 匹配结果；不存在已准入内容时为空
     */
    Optional<ReviewedSourcePreparation> reviewedPreparation();
}
