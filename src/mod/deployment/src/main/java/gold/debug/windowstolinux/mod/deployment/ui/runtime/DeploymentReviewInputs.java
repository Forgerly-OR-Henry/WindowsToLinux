package gold.debug.windowstolinux.mod.deployment.ui.runtime;

import java.awt.BorderLayout;
import java.util.Set;
import java.util.function.Function;
import javax.swing.*;
import javax.swing.text.JTextComponent;

import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.input.DeploymentReviewField;

/** Creates real editable inputs with accessible field help and bounded multiline declarations. / 创建带可访问字段帮助及有界多行声明的真实编辑输入。 */
public final class DeploymentReviewInputs {
    /** Long structured values retain their line boundaries. / 长结构化值保留行边界。 */
    private static final Set<String> MULTILINE = Set.of("applicationDeclaration", "configuration", "volumes", "databaseDetails");

    /** No instances. / 不创建实例。 */
    private DeploymentReviewInputs() {
    }

    /** Creates the actual input, preserving choices and current text. / 创建实际输入，保留选项及当前文本。
     * @param field exact field contract / 精确字段合同
     * @return editable control / 可编辑控件 */
    public static JComponent create(DeploymentReviewField field) {
        String key = field.id().substring(field.id().lastIndexOf('/') + 1);
        if (!field.choices().isEmpty()) {
            var choices = new JComboBox<>(field.choices().toArray(String[]::new));
            choices.setSelectedItem(field.value().isBlank() ? null : field.value());
            return choices;
        }
        if (MULTILINE.contains(key)) {
            var text = new JTextArea(field.value(), 5, 28);
            text.setLineWrap(true);
            text.setWrapStyleWord(true);
            return text;
        }
        return new JTextField(field.value(), 24);
    }

    /** Adds actual help text accessible by mouse, keyboard and assistive technology. / 添加可由鼠标、键盘及辅助技术访问的实际帮助文本。
     * @param input original editable control / 原可编辑控件
     * @param field actual help contract / 实际帮助合同
     * @param texts original localized resources / 原本地化资源
     * @return same input with a help surface / 带帮助表面的同一输入 */
    public static JComponent decorated(JComponent input, DeploymentReviewField field, Function<String, String> texts) {
        String help = texts.apply(field.helpKey());
        input.setName("deployment.runtime." + field.id());
        input.setToolTipText(help);
        input.getAccessibleContext().setAccessibleDescription(help);
        var panel = new JPanel(new BorderLayout(4, 4));
        panel.add(input instanceof JTextArea ? new JScrollPane(input) : input, BorderLayout.CENTER);
        var details = new JTextArea(help, 3, 24);
        details.setEditable(false);
        details.setLineWrap(true);
        details.setWrapStyleWord(true);
        details.setVisible(false);
        var toggle = new JToggleButton(texts.apply("runtimeDraft.help"));
        toggle.setName(input.getName() + ".help");
        toggle.getAccessibleContext().setAccessibleDescription(help);
        input.putClientProperty("deployment.help.field", field);
        input.putClientProperty("deployment.help.text", details);
        input.putClientProperty("deployment.help.toggle", toggle);
        toggle.addActionListener(ignored -> {
            details.setVisible(toggle.isSelected());
            panel.revalidate();
        });
        panel.add(toggle, BorderLayout.EAST);
        panel.add(details, BorderLayout.SOUTH);
        input.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke("F1"), "field-help");
        input.getActionMap().put("field-help", new AbstractAction() {
            /** Expands the original field's local text, without opening another owner window. / 展开原字段本地文本，不打开另一个所属窗口。
             * @param event actual key event / 实际键盘事件 */
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                toggle.doClick();
            }
        });
        return panel;
    }

    /** Updates the original help controls without replacing edited values. / 更新原帮助控件，不替换已编辑值。
     * @param input original editable input / 原可编辑输入
     * @param field original help identity / 原帮助身份
     * @param texts current owned resources / 当前所属资源 */
    public static void localize(JComponent input, DeploymentReviewField field, Function<String, String> texts) {
        String help = texts.apply(field.helpKey());
        input.setToolTipText(help);
        input.getAccessibleContext().setAccessibleDescription(help);
        if (input.getClientProperty("deployment.help.text") instanceof JTextArea details) details.setText(help);
        if (input.getClientProperty("deployment.help.toggle") instanceof JToggleButton toggle) {
            toggle.setText(texts.apply("runtimeDraft.help"));
            toggle.getAccessibleContext().setAccessibleDescription(help);
        }
    }

    /** Refreshes only this author's named help and labels in an already owned form. / 仅刷新已所属表单中本作者命名的帮助及标签。
     * @param component actual owner control / 实际所属控件
     * @param texts current resources / 当前资源 */
    public static void refresh(JComponent component, Function<String, String> texts) {
        if (component.getClientProperty("deployment.help.field") instanceof DeploymentReviewField field)
            localize(component, field, texts);
        if (component instanceof JLabel label && label.getClientProperty("deployment.style.title") instanceof String key)
            label.setText(texts.apply(key));
        for (var child : component.getComponents()) if (child instanceof JComponent actual) refresh(actual, texts);
    }

    /** Creates a retained field label with its exact locale identity. / 创建带精确语言身份的保留字段标签。
     * @param key original resource key / 原资源键
     * @param texts current resources / 当前资源
     * @return retained label / 保留标签 */
    public static JLabel label(String key, Function<String, String> texts) {
        var label = new JLabel(texts.apply(key));
        label.putClientProperty("deployment.style.title", key);
        return label;
    }

    /** Reads actual text, preserving internal declaration newlines. / 读取实际文本，保留声明内部换行。
     * @param control original input / 原输入
     * @return current input / 当前输入 */
    public static String value(JComponent control) {
        return control instanceof JTextComponent text ? text.getText().trim()
            : ((JComboBox<?>) control).getSelectedItem() == null ? "" : String.valueOf(((JComboBox<?>) control).getSelectedItem());
    }
}
