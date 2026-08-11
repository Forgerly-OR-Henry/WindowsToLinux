package gold.debug.windowstolinux.app.ui.ai;

import gold.debug.windowstolinux.app.ui.component.DesktopComponents;
import gold.debug.windowstolinux.app.ui.i18n.MessageCatalog;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridBagLayout;

/**
 * Provides the {@code AiPage} implementation.
 *
 * <p>提供 {@code AiPage} 实现。
 */
public final class AiPage {
    private AiPage() {
    }

    /**
     * Creates a value through {@code create}.
     *
     * <p>通过 {@code create} 创建值。
     *
     * @param c the {@code c} value / {@code c} 值
     * @param m the {@code m} value / {@code m} 值
     * @param endpoint the {@code endpoint} value / {@code endpoint} 值
     * @param model the {@code model} value / {@code model} 值
     * @param apiKey the {@code apiKey} value / {@code apiKey} 值
     * @param mode the {@code mode} value / {@code mode} 值
     * @param masterPassword the {@code masterPassword} value / {@code masterPassword} 值
     * @param output the {@code output} value / {@code output} 值
     * @param save the {@code save} value / {@code save} 值
     * @param explain the {@code explain} value / {@code explain} 值
     * @return the operation result / 操作结果
     */
    public static JPanel create(DesktopComponents c, MessageCatalog m, JTextField endpoint, JTextField model,
                                JPasswordField apiKey, JComboBox<?> mode, JPasswordField masterPassword,
                                JTextArea output, Runnable save, Runnable explain) {
        JPanel panel = c.pagePanel();
        JPanel configuration = c.card(new BorderLayout(0, 12));
        configuration.add(c.sectionHeading(m.text("section.ai.title"), m.text("section.ai.description")),
                BorderLayout.NORTH);
        JPanel form = c.transparent(new GridBagLayout());
        c.addField(form, 0, 0, m.text("field.aiEndpoint"), endpoint);
        c.addField(form, 0, 1, m.text("field.model"), model);
        c.addField(form, 1, 0, m.text("field.apiKey"), apiKey);
        c.addField(form, 1, 1, m.text("field.credentialStorage"), mode);
        c.addField(form, 2, 0, m.text("field.masterPassword"), masterPassword);
        configuration.add(form, BorderLayout.CENTER);
        JPanel actions = c.transparent(new FlowLayout(FlowLayout.LEFT, 8, 0));
        JButton saveButton = c.secondaryButton(m.text("button.saveAi"));
        saveButton.addActionListener(event -> save.run());
        JButton explainButton = c.primaryButton(m.text("button.requestAi"));
        explainButton.addActionListener(event -> explain.run());
        actions.add(saveButton);
        actions.add(explainButton);
        configuration.add(actions, BorderLayout.SOUTH);
        panel.add(configuration, BorderLayout.NORTH);
        panel.add(c.outputCard(m.text("section.aiOutput.title"), m.text("section.aiOutput.description"), output),
                BorderLayout.CENTER);
        return panel;
    }
}
