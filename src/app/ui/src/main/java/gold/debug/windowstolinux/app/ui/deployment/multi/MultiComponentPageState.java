package gold.debug.windowstolinux.app.ui.deployment.multi;

import gold.debug.windowstolinux.app.service.contract.definition.ComponentFormInput;

import gold.debug.windowstolinux.app.service.deployment.multi.ReviewedMultiComponentApplication;
import gold.debug.windowstolinux.app.service.source.PreparedMultiComponentSource;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleAction;

import java.util.List;
import java.util.Objects;

/**
 * Complete non-secret multi-component page state. / 完整且不含秘密的多组件页面状态。
 *
 * @param applicationRoot application root / 应用根目录
 * @param applicationId managed application identifier / 受管应用标识
 * @param healthComponentId health component id / 健康组件标识
 * @param lifecycleTargets lifecycle targets / 生命周期目标集合
 * @param lifecycleAction lifecycle action / 生命周期动作
 * @param form form / 表单
 * @param drafts drafts / 草稿集合
 * @param output destination receiving the produced content / 接收所生成内容的目标
 * @param preparation preparation / 准备
 * @param review review / 审阅
 */
public record MultiComponentPageState(
        String applicationRoot,
        String applicationId,
        String healthComponentId,
        String lifecycleTargets,
        LifecycleAction lifecycleAction,
        MultiComponentFormState form,
        List<ComponentFormInput> drafts,
        String output,
        PreparedMultiComponentSource preparation,
        ReviewedMultiComponentApplication review
) {
    /**
     * Preserves immutable collections and required values. / 保留不可变集合与必要值。
     *
     * @param applicationRoot application root / 应用根目录
     * @param applicationId managed application identifier / 受管应用标识
     * @param healthComponentId health component id / 健康组件标识
     * @param lifecycleTargets lifecycle targets / 生命周期目标集合
     * @param lifecycleAction lifecycle action / 生命周期动作
     * @param form form / 表单
     * @param drafts drafts / 草稿集合
     * @param output destination receiving the produced content / 接收所生成内容的目标
     * @param preparation preparation / 准备
     * @param review review / 审阅
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
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
