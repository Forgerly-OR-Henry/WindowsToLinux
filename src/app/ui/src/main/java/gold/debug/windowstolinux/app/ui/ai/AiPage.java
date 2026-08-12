package gold.debug.windowstolinux.app.ui.ai;

import gold.debug.windowstolinux.app.service.DesktopApplicationService;
import gold.debug.windowstolinux.app.service.ai.AiAnalysisOutcome;
import gold.debug.windowstolinux.app.service.ai.AiProfile;
import gold.debug.windowstolinux.app.ui.component.DesktopComponents;
import gold.debug.windowstolinux.app.ui.deployment.ReviewContext;
import gold.debug.windowstolinux.app.ui.shell.PageMessages;
import gold.debug.windowstolinux.shared.model.security.CredentialStorageMode;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingWorker;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridBagLayout;
import java.net.URI;
import java.util.Map;

/** Owns the optional AI form, temporary secrets, state, and explanation workflow. / 持有可选 AI 表单、临时秘密、状态与解释流程。 */
public final class AiPage {
    private final DesktopApplicationService service;
    private final ReviewContext reviewContext;
    private final PageMessages messages;
    private final JTextField endpoint = new JTextField("https://api.openai.com/v1/chat/completions", 34);
    private final JTextField model = new JTextField("gpt-5", 20);
    private final JPasswordField apiKey = new JPasswordField(24);
    private final JComboBox<CredentialStorageMode> credentialMode = new JComboBox<>(CredentialStorageMode.values());
    private final JPasswordField masterPassword = new JPasswordField(20);
    private final JTextArea output = DesktopComponents.outputArea();
    private final JPanel panel;

    /** Creates the stateful page controller. / 创建有状态页面控制器。 */
    public AiPage(DesktopApplicationService service, ReviewContext reviewContext,
                  DesktopComponents components, PageMessages messages) {
        this.service = service;
        this.reviewContext = reviewContext;
        this.messages = messages;
        messages.localize(credentialMode, "credential.mode.");
        credentialMode.addActionListener(event -> masterPassword.setEnabled(
                credentialMode.getSelectedItem() == CredentialStorageMode.MASTER_PASSWORD));
        panel = createPanel(components);
    }

    /** Returns the page panel. / 返回页面面板。 */
    public JPanel panel() { return panel; }

    /** Captures unsaved state and temporary password copies. / 捕获未保存状态与临时密码副本。 */
    public AiPageState captureState() {
        return new AiPageState(endpoint.getText(), model.getText(), apiKey.getPassword(), selectedMode(),
                masterPassword.getPassword(), output.getText());
    }

    /** Restores unsaved state. / 恢复未保存状态。 */
    public void restoreState(AiPageState state) {
        endpoint.setText(state.endpoint());
        model.setText(state.model());
        apiKey.setText(new String(state.apiKey()));
        credentialMode.setSelectedItem(state.credentialMode());
        masterPassword.setText(new String(state.masterPassword()));
        output.setText(state.output());
    }

    private JPanel createPanel(DesktopComponents c) {
        JPanel page = c.pagePanel();
        JPanel card = c.card(new BorderLayout(0, 12));
        card.add(c.sectionHeading(messages.text("section.ai.title"), messages.text("section.ai.description")), BorderLayout.NORTH);
        JPanel form = c.transparent(new GridBagLayout());
        c.addField(form, 0, 0, messages.text("field.aiEndpoint"), endpoint);
        c.addField(form, 0, 1, messages.text("field.model"), model);
        c.addField(form, 1, 0, messages.text("field.apiKey"), apiKey);
        c.addField(form, 1, 1, messages.text("field.credentialStorage"), credentialMode);
        c.addField(form, 2, 0, messages.text("field.masterPassword"), masterPassword);
        card.add(form, BorderLayout.CENTER);
        JPanel actions = c.transparent(new FlowLayout(FlowLayout.LEFT, 8, 0));
        JButton save = c.secondaryButton(messages.text("button.saveAi"));
        save.addActionListener(event -> save());
        JButton explain = c.primaryButton(messages.text("button.requestAi"));
        explain.addActionListener(event -> explain());
        actions.add(save); actions.add(explain);
        card.add(actions, BorderLayout.SOUTH);
        page.add(card, BorderLayout.NORTH);
        page.add(c.outputCard(messages.text("section.aiOutput.title"), messages.text("section.aiOutput.description"), output),
                BorderLayout.CENTER);
        return page;
    }

    private void save() {
        try {
            CredentialStorageMode mode = selectedMode();
            AiProfile profile = new AiProfile(URI.create(endpoint.getText().trim()), model.getText().trim(),
                    "ai/default/api-key", mode);
            service.saveAiProfile(profile, mode, masterPassword.getPassword(), apiKey.getPassword());
            apiKey.setText("");
            masterPassword.setText("");
            output.setText(messages.text("ai.saved"));
        } catch (Exception exception) {
            output.setText(messages.text("ai.saveFailed", Map.of("detail", messages.safe(exception))));
        }
    }

    private void explain() {
        var preparation = reviewContext.reviewedPreparation();
        if (preparation.isEmpty()) {
            output.setText(messages.text("ai.analyzeFirst"));
            return;
        }
        try {
            AiProfile profile = service.findAiProfile().orElseThrow(() -> new IllegalStateException(messages.text("ai.saveFirst")));
            char[] master = masterPassword.getPassword();
            output.setText(messages.text("ai.requesting"));
            new SwingWorker<AiAnalysisOutcome, Void>() {
                @Override protected AiAnalysisOutcome doInBackground() {
                    return service.requestAiExplanation(preparation.orElseThrow(), profile, profile.credentialMode(), master,
                            messages.catalog().locale().toLanguageTag());
                }
                @Override protected void done() {
                    try {
                        AiAnalysisOutcome result = get();
                        output.setText(result.available() ? messages.text("ai.available", Map.of("detail", result.content()))
                                : messages.localized(result.status(), result.diagnostic()));
                    } catch (Exception exception) {
                        output.setText(messages.text("ai.failed", Map.of("detail", messages.safe(exception))));
                    }
                }
            }.execute();
        } catch (Exception exception) {
            output.setText(messages.text("ai.requestFailed", Map.of("detail", messages.safe(exception))));
        }
    }

    private CredentialStorageMode selectedMode() {
        return (CredentialStorageMode) credentialMode.getSelectedItem();
    }
}
