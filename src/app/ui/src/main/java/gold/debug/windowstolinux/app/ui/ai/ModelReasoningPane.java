package gold.debug.windowstolinux.app.ui.ai;

import java.awt.*;
import java.util.*;
import java.util.List;
import javax.swing.*;

import gold.debug.windowstolinux.app.service.ai.*;
import gold.debug.windowstolinux.app.ui.control.DesktopPartFactory;
import gold.debug.windowstolinux.app.ui.error.PageMessagePresenter;
import gold.debug.windowstolinux.app.ui.task.DesktopTaskRunner;
import gold.debug.windowstolinux.appapi.ai.model.*;

/** Edits one model's purpose preferences without changing caller overrides. / 编辑单模型用途偏好，不改变调用方覆写。
 */
final class ModelReasoningPane extends JPanel implements gold.debug.windowstolinux.app.ui.control.PendingCardEdits, AutoCloseable {
    /** Captured service for this model capability boundary. / 此模型能力边界捕获的 service。
     */
    private final PlatformModelService service;

    /** Captured model for this model capability boundary. / 此模型能力边界捕获的 model。
     */
    private AiModelInfo model;

    /** Captured messages for this model capability boundary. / 此模型能力边界捕获的 messages。
     */
    private final PageMessagePresenter messages;

    /** Captured refresh for this model capability boundary. / 此模型能力边界捕获的 refresh。
     */
    private final Runnable refresh;

    /** Independent purpose controls; programmatic restoration never saves. / 独立用途控件，程序恢复不触发保存。
     */
    private final Map<AiPurpose, JComboBox<AiReasoningMode>> choices = new EnumMap<>(AiPurpose.class);

    /** Persists the captured purpose choices through their existing owner. / 通过现有所有者持久化捕获的用途选择。
     */
    private final JButton save;

    /** Captured status for this model capability boundary. / 此模型能力边界捕获的 status。
     */
    private final JLabel status = new JLabel();

    /** Prevents overlapping preference writes from this view. / 防止此视图的偏好写入重叠。
     */
    private boolean saving;

    /** Original view permission retained across asynchronous callbacks. / 跨异步回调保留的原视图许可。
     */
    private boolean allowed;

    /** Seals callbacks after the owning detail view has closed. / 所属详情视图关闭后封闭回调。
     */
    private boolean closed;

    /** Completion of the current admitted database save. / 当前已准入数据库保存的完成状态。
     */
    private java.util.concurrent.CompletableFuture<Boolean> pending;

    /** Captures inputs at the ModelReasoningPane ownership boundary. / 在 ModelReasoningPane 所有权边界捕获输入。
     * @param service host-owned model configuration service / 宿主持有的模型配置服务
     * @param model configured model identity or captured model metadata / 已配置型号或捕获的模型信息
     * @param c existing styled component factory / 现有样式化组件工厂
     * @param messages current localized message presenter / 当前本地化消息展示器
     * @param refresh reloads authoritative model metadata after saving / 保存后重新加载权威模型信息
     */
    ModelReasoningPane(PlatformModelService service, AiModelInfo model, DesktopPartFactory c,
                       PageMessagePresenter messages, Runnable refresh) {
        super(new BorderLayout(0, 12));
        this.service = service;
        this.model = model;
        this.messages = messages;
        this.refresh = refresh;
        setOpaque(false);
        setName("models.reasoning");
        status.setName("models.reasoning.status");
        var card = c.outlinedCard(new BorderLayout(0, 10));
        add(card);
        card.add(messages.bind(new JLabel(), "ai.reasoning.title"), BorderLayout.NORTH);
        var fields = c.transparent(new GridBagLayout());
        var capability = service.reasoningCapabilities(model.profile());
        var summary = new JLabel();
        summary.setName("models.reasoning.support");

        summary.setText(messages.text(capability.known() ? "ai.reasoning.known" : "ai.reasoning.unknown")
            + (capability.known() ? " (" + (capability.options().size() - 1) + "): " + capability.options().stream().filter(value -> value != AiReasoningMode.DEFAULT)
            .map(value -> messages.text("ai.reasoning.option." + value.name().toLowerCase(Locale.ROOT)))
            .collect(java.util.stream.Collectors.joining(", ")) : ""));
        summary.setToolTipText(capability.source());
        c.addField(fields, 0, 0, messages, "ai.reasoning.support", summary);
        boolean voice = Set.of("ws", "wss").contains(model.profile().endpoint().getScheme());
        int row = 1;
        if (!voice) for (var purpose : List.of(AiPurpose.LANGUAGE, AiPurpose.APPROVAL, AiPurpose.VISION)) {
            var configured = model.profile().reasoningDefaults().getOrDefault(purpose, AiReasoningMode.DEFAULT);
            var options = new ArrayList<>(capability.options());
            if (!options.contains(configured)) options.add(configured);
            var choice = new JComboBox<>(options.toArray(AiReasoningMode[]::new));
            choice.setName("models.reasoning." + purpose.name());
            messages.localize(choice, "ai.reasoning.option.");
            choice.setSelectedItem(configured);
            choices.put(purpose, choice);
            c.addField(fields, row++, 0, messages, "ai.reasoning.purpose." + purpose.name().toLowerCase(Locale.ROOT), choice);
        }
        card.add(fields);
        save = messages.bind(c.primaryButton(""), "ai.reasoning.save");
        save.setName("models.reasoning.save");
        save.addActionListener(event -> save());
        var footer = c.transparent(new BorderLayout(8, 0));
        footer.add(status);
        if (!voice) footer.add(save, BorderLayout.EAST);
        card.add(footer, BorderLayout.SOUTH);
    }

    /** Applies original view admission while an asynchronous save is pending. / 在异步保存期间应用原视图准入。
     * @param enabled whether the original unlocked view may edit / 原未锁定视图是否可编辑
     */
    void availability(boolean enabled) {
        allowed = enabled;
        choices.values().forEach(choice -> choice.setEnabled(enabled && !saving && !closed));
        save.setEnabled(enabled && !saving && !closed);
    }

    /** Persists the captured purpose choices through their existing owner. / 通过现有所有者持久化捕获的用途选择。
     */
    private void save() {
        saveBeforeLeave();
    }

    /** Captures unsaved controls for a localized detail rebuild. / 捕获本地化详情重建前未保存的选择。
     * @return independent purpose draft / 独立用途草稿
     */
    Map<AiPurpose, AiReasoningMode> draft() {
        var selected = new EnumMap<AiPurpose, AiReasoningMode>(AiPurpose.class);
        choices.forEach((purpose, choice) -> selected.put(purpose, (AiReasoningMode) choice.getSelectedItem()));
        return selected;
    }

    /** Restores controls without writing preferences. / 恢复控件，不写入偏好。
     * @param values captured purpose draft / 捕获的用途草稿
     */
    void restore(Map<AiPurpose, AiReasoningMode> values) {
        values.forEach((purpose, value) -> {
            var choice = choices.get(purpose);
            if (choice != null) choice.setSelectedItem(value);
        });
    }

    /** Keeps a live save attached to its original view. / 让进行中的保存保持绑定原视图。
     * @return whether a database write is pending / 是否有待完成数据库写入
     */
    boolean saving() {
        return saving;
    }

    /** Detects changes against the last confirmed purpose preferences. / 比较最近确认的用途偏好以检测修改。
     * @return validated immutable result or admitted completion / 已校验不可变结果或已准入完成状态
     */
    @Override
    public boolean hasPendingEdits() {
        return choices.entrySet().stream().anyMatch(entry -> entry.getValue().getSelectedItem()
            != model.profile().reasoningDefaults().getOrDefault(entry.getKey(), AiReasoningMode.DEFAULT));
    }

    /** Completes admitted preference writes before the containing view leaves. / 在所属视图离开前完成已准入偏好写入。
     * @return validated immutable result or admitted completion / 已校验不可变结果或已准入完成状态
     */
    @Override
    public java.util.concurrent.CompletionStage<Boolean> saveBeforeLeave() {
        if (saving) return pending;
        if (!hasPendingEdits()) return java.util.concurrent.CompletableFuture.completedFuture(true);
        if (!allowed || closed) return java.util.concurrent.CompletableFuture.completedFuture(false);
        pending = new java.util.concurrent.CompletableFuture<>();
        var selected = draft();
        saving = true;
        availability(allowed);
        DesktopTaskRunner.run(() -> {
            service.saveReasoning(model.profile().id(), model.revision(), selected);
            return service.listAiConfigurations().stream().filter(value -> value.profile().id().equals(model.profile().id())).findFirst().orElseThrow();
        }, confirmed -> {
            model = confirmed;
            saving = false;
            availability(allowed);
            pending.complete(true);
            refresh.run();
        }, failure -> {
            saving = false;
            choices.forEach((purpose, choice) -> choice.setSelectedItem(model.profile().reasoningDefaults().get(purpose)));
            status.setText(messages.safe(failure));
            availability(allowed);
            pending.complete(false);
        });
        return pending;
    }

    /** Seals view callbacks without cancelling already admitted database writes. / 封闭视图回调，不取消已准入数据库写入。
     */
    @Override
    public void close() {
        closed = true;
        availability(false);
    }
}
