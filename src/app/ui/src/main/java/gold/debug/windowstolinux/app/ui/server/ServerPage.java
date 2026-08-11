package gold.debug.windowstolinux.app.ui.server;

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
import java.util.function.Consumer;

/**
 * Provides the {@code ServerPage} implementation.
 *
 * <p>提供 {@code ServerPage} 实现。
 */
public final class ServerPage {
    private ServerPage() {
    }

    /**
     * Creates a value through {@code create}.
     *
     * <p>通过 {@code create} 创建值。
     *
     * @param c the {@code c} value / {@code c} 值
     * @param m the {@code m} value / {@code m} 值
     * @param id the {@code id} value / {@code id} 值
     * @param host the {@code host} value / {@code host} 值
     * @param port the {@code port} value / {@code port} 值
     * @param user the {@code user} value / {@code user} 值
     * @param password the {@code password} value / {@code password} 值
     * @param mode the {@code mode} value / {@code mode} 值
     * @param masterPassword the {@code masterPassword} value / {@code masterPassword} 值
     * @param output the {@code output} value / {@code output} 值
     * @param save the {@code save} value / {@code save} 值
     * @param verify the {@code verify} value / {@code verify} 值
     * @param prepare the {@code prepare} value / {@code prepare} 值
     * @return the operation result / 操作结果
     */
    public static JPanel create(DesktopComponents c, MessageCatalog m, JTextField id, JTextField host,
                                JTextField port, JTextField user, JPasswordField password,
                                JComboBox<?> mode, JPasswordField masterPassword, JTextArea output,
                                Runnable save, Runnable verify, Consumer<JButton> prepare) {
        JPanel panel = c.pagePanel();
        JPanel connection = c.card(new BorderLayout(0, 12));
        connection.add(c.sectionHeading(m.text("section.connection.title"),
                m.text("section.connection.description")), BorderLayout.NORTH);
        JPanel form = c.transparent(new GridBagLayout());
        c.addField(form, 0, 0, m.text("field.serverId"), id);
        c.addField(form, 0, 1, m.text("field.host"), host);
        c.addField(form, 1, 0, m.text("field.sshPort"), port);
        c.addField(form, 1, 1, m.text("field.sshUser"), user);
        c.addField(form, 2, 0, m.text("field.sshPassword"), password);
        c.addField(form, 2, 1, m.text("field.credentialStorage"), mode);
        c.addField(form, 3, 0, m.text("field.masterPassword"), masterPassword);
        connection.add(form, BorderLayout.CENTER);
        JPanel actions = c.transparent(new FlowLayout(FlowLayout.LEFT, 8, 0));
        JButton saveButton = c.secondaryButton(m.text("button.saveServer"));
        saveButton.addActionListener(event -> save.run());
        JButton verifyButton = c.primaryButton(m.text("button.verifyServer"));
        verifyButton.addActionListener(event -> verify.run());
        JButton prepareButton = c.secondaryButton(m.text("button.prepareEnvironment"));
        prepareButton.addActionListener(event -> prepare.accept(prepareButton));
        actions.add(saveButton);
        actions.add(verifyButton);
        actions.add(prepareButton);
        connection.add(actions, BorderLayout.SOUTH);
        panel.add(connection, BorderLayout.NORTH);
        panel.add(c.outputCard(m.text("section.verification.title"), m.text("section.verification.description"), output),
                BorderLayout.CENTER);
        return panel;
    }
}
