package gold.debug.windowstolinux.app.ui.deployment;

import gold.debug.windowstolinux.app.service.deployment.ReviewedMultiComponentApplication;
import gold.debug.windowstolinux.app.service.source.PreparedMultiComponentSource;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleAction;

import java.util.List;
import java.util.Objects;

/** Complete non-secret multi-component page state. / 完整且不含秘密的多组件页面状态。 */
public record MultiComponentPageState(
        String applicationRoot,
        String applicationId,
        String healthComponentId,
        String lifecycleTargets,
        LifecycleAction lifecycleAction,
        MultiComponentFormState form,
        List<MultiComponentDraft> drafts,
        String output,
        PreparedMultiComponentSource preparation,
        ReviewedMultiComponentApplication review
) {
    /** Preserves immutable collections and required values. / 保留不可变集合与必要值。 */
    public MultiComponentPageState {
        Objects.requireNonNull(applicationRoot, "applicationRoot");
        Objects.requireNonNull(applicationId, "applicationId");
        Objects.requireNonNull(healthComponentId, "healthComponentId");
        Objects.requireNonNull(lifecycleTargets, "lifecycleTargets");
        Objects.requireNonNull(lifecycleAction, "lifecycleAction");
        Objects.requireNonNull(form, "form");
        drafts = List.copyOf(Objects.requireNonNull(drafts, "drafts"));
        Objects.requireNonNull(output, "output");
    }
}
