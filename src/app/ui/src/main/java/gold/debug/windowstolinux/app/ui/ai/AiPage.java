package gold.debug.windowstolinux.app.ui.ai;

import gold.debug.windowstolinux.app.service.port.AiApplicationPort;
import gold.debug.windowstolinux.app.service.ai.AiProviderProfile;
import gold.debug.windowstolinux.app.service.ai.AiRoleAssignment;
import gold.debug.windowstolinux.app.ui.component.DesktopAsyncTask;
import gold.debug.windowstolinux.app.ui.component.DesktopComponents;
import gold.debug.windowstolinux.app.ui.deployment.ReviewContext;
import gold.debug.windowstolinux.app.ui.i18n.PageMessages;
import gold.debug.windowstolinux.shared.model.security.CredentialStorageMode;
import gold.debug.windowstolinux.shared.ai.collaboration.AiCollaborationRole;
import gold.debug.windowstolinux.shared.ai.collaboration.AiRoleInvocationResult;
import gold.debug.windowstolinux.shared.ai.collaboration.ProjectAnalysisRoleContext;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridBagLayout;
import java.net.URI;
import java.util.Map;
import java.util.Optional;

/** Owns the optional AI form, temporary secrets, state, and explanation workflow. / 持有可选 AI 表单、临时秘密、状态与解释流程。 */
public final class AiPage {
    private final AiApplicationPort service;
    private final ReviewContext reviewContext;
    private final PageMessages messages;
    private final JTextField endpoint = new JTextField("https://api.openai.com/v1/chat/completions", 34);
    private final JTextField model = new JTextField("gpt-5", 20);
    private final JTextField providerId = new JTextField("project-analysis", 20);
    private final JComboBox<AiCollaborationRole> role = new JComboBox<>(AiCollaborationRole.values());
    private final JPasswordField apiKey = new JPasswordField(24);
    private final JComboBox<CredentialStorageMode> credentialMode = new JComboBox<>(CredentialStorageMode.values());
    private final JPasswordField masterPassword = new JPasswordField(20);
    private final JTextArea output = DesktopComponents.outputArea();
    private final JPanel panel;

    /** Creates the stateful page controller. / 创建有状态页面控制器。 */
    public AiPage(AiApplicationPort service, ReviewContext reviewContext,
                  DesktopComponents components, PageMessages messages) {
        this.service = service;
        this.reviewContext = reviewContext;
        this.messages = messages;
        messages.localize(credentialMode, "credential.mode.");
        messages.localize(role, "ai.role.");
        credentialMode.addActionListener(event -> masterPassword.setEnabled(
                credentialMode.getSelectedItem() == CredentialStorageMode.MASTER_PASSWORD));
        panel = createPanel(components);
    }

    /** Returns the page panel. / 返回页面面板。 */
    public JPanel panel() { return panel; }

    /** Captures unsaved state and temporary password copies. / 捕获未保存状态与临时密码副本。 */
    public AiPageState captureState() {
        return new AiPageState(endpoint.getText(), model.getText(), providerId.getText(), selectedRole(),
                apiKey.getPassword(), selectedMode(),
                masterPassword.getPassword(), output.getText());
    }

    /** Restores unsaved state. / 恢复未保存状态。 */
    public void restoreState(AiPageState state) {
        endpoint.setText(state.endpoint());
        model.setText(state.model());
        providerId.setText(state.providerId());
        role.setSelectedItem(state.role());
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
        c.addField(form, 1, 0, messages.text("field.aiProviderId"), providerId);
        c.addField(form, 1, 1, messages.text("field.aiRole"), role);
        c.addField(form, 2, 0, messages.text("field.apiKey"), apiKey);
        c.addField(form, 2, 1, messages.text("field.credentialStorage"), credentialMode);
        c.addField(form, 3, 0, messages.text("field.masterPassword"), masterPassword);
        card.add(form, BorderLayout.CENTER);
        JPanel actions = c.transparent(new FlowLayout(FlowLayout.LEFT, 8, 0));
        JButton save = c.secondaryButton(messages.text("button.saveAiProvider"));
        save.addActionListener(event -> saveNamed());
        JButton assign = c.secondaryButton(messages.text("button.assignAiRole"));
        assign.addActionListener(event -> assignRole());
        JButton explain = c.primaryButton(messages.text("button.requestAi"));
        explain.addActionListener(event -> explain());
        actions.add(save); actions.add(assign); actions.add(explain);
        card.add(actions, BorderLayout.SOUTH);
        page.add(card, BorderLayout.NORTH);
        page.add(c.outputCard(messages.text("section.aiOutput.title"), messages.text("section.aiOutput.description"), output),
                BorderLayout.CENTER);
        return page;
    }

    private void saveNamed() {
        try {
            CredentialStorageMode mode = selectedMode();
            String id = providerId.getText().trim();
            AiProviderProfile profile = new AiProviderProfile(id, URI.create(endpoint.getText().trim()),
                    model.getText().trim(), "ai/provider/" + id + "/api-key", mode);
            service.saveAiProviderProfile(profile, masterPassword.getPassword(), apiKey.getPassword());
            apiKey.setText("");
            masterPassword.setText("");
            output.setText(messages.text("ai.providerSaved", Map.of("provider", id)));
        } catch (Exception exception) {
            output.setText(messages.text("ai.saveFailed", Map.of("detail", messages.safe(exception))));
        }
    }

    private void assignRole() {
        try {
            service.assignAiRole(new AiRoleAssignment(selectedRole(), providerId.getText().trim()));
            output.setText(messages.text("ai.roleAssigned", Map.of(
                    "role", messages.text("ai.role." + selectedRole().name()),
                    "provider", providerId.getText().trim())));
        } catch (Exception exception) {
            output.setText(messages.text("ai.roleAssignFailed", Map.of("detail", messages.safe(exception))));
        }
    }

    private void explain() {
        var preparation = reviewContext.reviewedPreparation();
        if (preparation.isEmpty()) {
            output.setText(messages.text("ai.analyzeFirst"));
            return;
        }
        char[] master = masterPassword.getPassword();
        output.setText(messages.text("ai.requesting"));
        DesktopAsyncTask.run(
                () -> service.invokeAiRole(ProjectAnalysisRoleContext.from(
                        preparation.orElseThrow().assessment().facts().orElseThrow()), master),
                result -> output.setText(result.map(AiPage.this::evidenceText)
                        .orElseGet(() -> messages.text("ai.roleUnassigned"))),
                exception -> output.setText(messages.text("ai.failed",
                        Map.of("detail", messages.safe(exception)))));
    }

    private String evidenceText(AiRoleInvocationResult result) {
        var evidence = result.evidence();
        String decision = evidence.output().map(value -> value.decision().name()).orElse("-");
        String summary = evidence.output().map(value -> value.summary()).orElse("-");
        return messages.text("ai.roleEvidence", Map.of("provider", evidence.providerId(), "model", evidence.model(),
                "status", evidence.status().name(), "digest", evidence.inputSha256(),
                "validation", evidence.validationDetail(), "decision", decision, "summary", summary));
    }

    private CredentialStorageMode selectedMode() {
        return (CredentialStorageMode) credentialMode.getSelectedItem();
    }

    private AiCollaborationRole selectedRole() {
        return (AiCollaborationRole) role.getSelectedItem();
    }
}
