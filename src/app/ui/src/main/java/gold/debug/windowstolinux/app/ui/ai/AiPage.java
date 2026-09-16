package gold.debug.windowstolinux.app.ui.ai;

import gold.debug.windowstolinux.app.service.contract.AiApplicationFacade;
import gold.debug.windowstolinux.app.service.ai.AiProviderSummary;
import gold.debug.windowstolinux.app.ui.component.*;
import gold.debug.windowstolinux.app.ui.deployment.ReviewContext;
import gold.debug.windowstolinux.app.ui.i18n.PageMessagePresenter;
import gold.debug.windowstolinux.shared.ai.collaboration.invocation.AiRoleInvocationResult;
import gold.debug.windowstolinux.shared.ai.collaboration.role.ProjectAnalysisRoleContext;
import gold.debug.windowstolinux.shared.ai.collaboration.role.AiCollaborationRoleKind;
import gold.debug.windowstolinux.shared.model.security.CredentialStorageMode;
import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/** Ordered model inventory and cancellable explanations. / 有序模型清单与可取消的解释。 */
public final class AiPage {
    private final AiApplicationFacade service;
    private final ReviewContext reviewContext;
    private final PageMessagePresenter messages;
    private final DesktopComponentFactory c;
    private final JPasswordField masterPassword = new JPasswordField(20);
    private final JTextArea output = DesktopComponentFactory.outputArea();
    private final JPanel cards = new JPanel();
    private final JLabel status = new JLabel();
    private final JButton cancel;
    private final JPanel panel;
    private final AiProviderDragTransfer transfer;
    private List<AiProviderSummary> models = List.of();
    private DesktopTaskHandle task;
    private boolean busy, loading;
    private AiPageState draft = new AiPageState("", "", "", AiCollaborationRoleKind.PROJECT_ANALYSIS,
            new char[0], CredentialStorageMode.WINDOWS_CREDENTIAL_MANAGER, new char[0], "");

    /** Creates cards without making network requests. / 创建卡片，不发送网络请求。 */
    public AiPage(AiApplicationFacade service, ReviewContext reviewContext, DesktopComponentFactory c, PageMessagePresenter messages) {
        this.service = service; this.reviewContext = reviewContext; this.c = c; this.messages = messages;
        cancel = c.secondaryButton(messages.text("button.cancel")); cancel.setEnabled(false);
        cancel.addActionListener(event -> { if (task != null) { task.cancel(); output.setText(messages.text("ai.models.cancelling")); } });
        transfer = new AiProviderDragTransfer(cards, this::order, this::reorder, () -> busy || loading);
        panel = createPanel();
        panel.addHierarchyListener(event -> { if ((event.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0 && panel.isShowing()) refresh(); });
    }
    /** Returns the page. / 返回页面。 */
    public JPanel panel() { return panel; }
    /** Preserves old transient drafts and current output across appearance changes. / 在外观变化时保留旧临时草稿与当前输出。 */
    public AiPageState captureState() {
        return new AiPageState(draft.endpoint(), draft.model(), draft.providerId(), draft.role(), draft.apiKey(), draft.credentialMode(), masterPassword.getPassword(), output.getText());
    }
    /** Restores transient state without mutating model priority. / 恢复临时状态，不改变模型优先级。 */
    public void restoreState(AiPageState state) {
        draft.close(); draft = new AiPageState(state.endpoint(), state.model(), state.providerId(), state.role(), state.apiKey(), state.credentialMode(), new char[0], "");
        masterPassword.setText(new String(state.masterPassword())); output.setText(state.output());
    }
    private JPanel createPanel() {
        JPanel page = c.pagePanel(); AdvancedOptionsPane advanced = new AdvancedOptionsPane(page, c, messages);
        JPanel toolbar = c.transparent(new BorderLayout(12, 0)); toolbar.add(c.sectionHeading(messages.text("ai.models.title"), messages.text("ai.models.description")));
        JButton add = c.primaryButton(messages.text("ai.models.add")); add.addActionListener(event -> edit(null)); toolbar.add(add, BorderLayout.EAST); page.add(toolbar, BorderLayout.NORTH);
        cards.setLayout(new BoxLayout(cards, BoxLayout.Y_AXIS)); cards.setOpaque(false); cards.setTransferHandler(transfer);
        JScrollPane scroll = new JScrollPane(cards); scroll.setBorder(BorderFactory.createEmptyBorder()); scroll.setOpaque(false); scroll.getViewport().setOpaque(false); scroll.getVerticalScrollBar().setUnitIncrement(20);
        page.add(scroll); page.add(status, BorderLayout.SOUTH); advanced.field("field.masterPassword", masterPassword);
        JButton explain = c.primaryButton(messages.text("button.requestAi")); explain.addActionListener(event -> explain()); advanced.addOption(explain); advanced.addOption(cancel);
        output.setRows(16); advanced.addOption(new JScrollPane(output)); return advanced;
    }
    private void refresh() {
        if (service == null || loading || busy) return; loading = true;
        DesktopTaskExecutor.run(service::listAiConfigurations, values -> { loading = false; models = values; render(); }, failure -> { loading = false; status.setText(messages.safe(failure)); });
    }
    private void render() {
        cards.removeAll(); String preferred = models.stream().filter(AiProviderSummary::enabled).map(value -> value.profile().id()).findFirst().orElse("");
        for (int index = 0; index < models.size(); index++) cards.add(card(models.get(index), index, preferred));
        status.setText(messages.text(models.isEmpty() ? "ai.models.empty" : "ai.models.orderHint")); cards.revalidate(); cards.repaint();
    }
    private JPanel card(AiProviderSummary value, int index, String preferred) {
        JPanel card = c.card(new BorderLayout(14, 12)); card.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 168)); card.setPreferredSize(new Dimension(600, 156)); card.setTransferHandler(transfer);
        JLabel grip = new JLabel("⋮⋮"); grip.setFont(grip.getFont().deriveFont(22f)); grip.setToolTipText(messages.text("ai.models.drag")); transfer.install(grip, value.profile().id()); card.add(grip, BorderLayout.WEST);
        JPanel details = c.transparent(new BorderLayout(0, 10)); JPanel title = c.transparent(new FlowLayout(FlowLayout.LEFT, 8, 0));
        JLabel name = new JLabel((index + 1) + "  " + value.name()); name.setFont(name.getFont().deriveFont(Font.BOLD, 16f)); title.add(name);
        if (value.profile().id().equals(preferred)) title.add(c.badge(messages.text("ai.models.preferred")));
        details.add(title, BorderLayout.NORTH); details.add(new JLabel(value.profile().model() + "  ·  " + value.profile().chatCompletionsEndpoint()));
        String verification = value.verifiedAt().map(time -> messages.text("ai.models.verified", Map.of("time", DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(time))))
                .orElseGet(() -> messages.text("ai.models.unverified")); details.add(new JLabel(verification), BorderLayout.SOUTH); card.add(details);
        JPanel actions = c.transparent(new FlowLayout(FlowLayout.RIGHT, 6, 0)); JCheckBox enabled = new JCheckBox(messages.text("ai.models.enabled"), value.enabled()); enabled.setOpaque(false);
        enabled.addActionListener(event -> mutate(() -> service.setAiProviderEnabled(value.profile().id(), enabled.isSelected()))); actions.add(enabled);
        JButton up = c.secondaryButton("↑"), down = c.secondaryButton("↓"), edit = c.secondaryButton(messages.text("ai.models.edit"));
        up.setToolTipText(messages.text("ai.models.up")); down.setToolTipText(messages.text("ai.models.down")); up.getAccessibleContext().setAccessibleName(messages.text("ai.models.up")); down.getAccessibleContext().setAccessibleName(messages.text("ai.models.down"));
        up.setEnabled(index > 0); down.setEnabled(index + 1 < models.size()); up.addActionListener(event -> move(index, -1)); down.addActionListener(event -> move(index, 1)); edit.addActionListener(event -> edit(value));
        actions.add(up); actions.add(down); actions.add(edit); card.add(actions, BorderLayout.SOUTH); keyboard(card, index, -1, "alt UP"); keyboard(card, index, 1, "alt DOWN"); return card;
    }
    private void keyboard(JPanel card, int index, int delta, String key) {
        card.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(key), key);
        card.getActionMap().put(key, new AbstractAction() { @Override public void actionPerformed(ActionEvent event) { move(index, delta); } });
    }
    private List<String> order() { return models.stream().map(value -> value.profile().id()).toList(); }
    private void move(int index, int delta) {
        if (busy || loading || index + delta < 0 || index + delta >= models.size()) return;
        var reordered = new java.util.ArrayList<>(order()); java.util.Collections.swap(reordered, index, index + delta); reorder(reordered);
    }
    private void reorder(List<String> ids) { if (!ids.equals(order())) mutate(() -> service.reorderAiProviders(ids)); }
    private void mutate(Change change) {
        if (busy || loading || service == null) return; setBusy(true);
        DesktopTaskExecutor.run(() -> { change.apply(); return true; }, value -> { setBusy(false); refresh(); }, failure -> { setBusy(false); render(); status.setText(messages.safe(failure)); });
    }
    private void edit(AiProviderSummary existing) {
        if (busy || loading || service == null) return;
        new AiProviderDialog(SwingUtilities.getWindowAncestor(panel), service, c, messages, existing, this::refresh).setVisible(true);
    }
    private void explain() {
        if (busy || service == null) return; var preparation = reviewContext.reviewedPreparation();
        if (preparation.isEmpty() || preparation.orElseThrow().assessment().facts().isEmpty()) { output.setText(messages.text("ai.analyzeFirst")); return; }
        char[] master = masterPassword.getPassword(); output.setText(messages.text("ai.requesting")); setBusy(true); cancel.setEnabled(true);
        task = DesktopTaskExecutor.submit(() -> {
            try { return service.invokeAiRole(ProjectAnalysisRoleContext.from(preparation.orElseThrow().assessment().facts().orElseThrow()), master); }
            finally { java.util.Arrays.fill(master, '\0'); }
        }, result -> { setBusy(false); output.setText(result.map(this::evidenceText).orElseGet(() -> messages.text("ai.status.noEnabledProviders"))); },
                failure -> { boolean cancelled = task != null && task.cancelled(); setBusy(false); output.setText(cancelled ? messages.text("ai.models.cancelled") : messages.safe(failure)); });
    }
    private void setBusy(boolean value) { busy = value; ((AdvancedOptionsPane) panel).setBusy(value); cancel.setEnabled(false); if (!value) task = null; }
    private String evidenceText(AiRoleInvocationResult result) {
        var evidence = result.evidence(); String text = messages.text("ai.roleEvidence", Map.of("provider", evidence.providerId(), "model", evidence.model(),
                "status", messages.text("ai.invocation." + evidence.status().name().toLowerCase(java.util.Locale.ROOT)), "digest", evidence.inputSha256(), "validation", evidence.validationDetail(),
                "decision", evidence.output().map(value -> messages.text("ai.decision." + value.decision().name().toLowerCase(java.util.Locale.ROOT))).orElse("-"), "summary", evidence.output().map(value -> value.summary()).orElse("-")));
        return text + "\n\n" + messages.text("ai.models.attempts") + "\n" + result.attempts().stream().map(value -> value.providerId() + " · " + value.model() + " · "
                + messages.text("ai.invocation." + value.status().name().toLowerCase(java.util.Locale.ROOT)) + " · " + value.detail()).collect(java.util.stream.Collectors.joining("\n"));
    }
    @FunctionalInterface private interface Change { void apply() throws Exception; }
}
