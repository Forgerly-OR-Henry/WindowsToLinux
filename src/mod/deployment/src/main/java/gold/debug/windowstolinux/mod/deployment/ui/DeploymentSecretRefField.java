package gold.debug.windowstolinux.mod.deployment.ui;

import java.awt.BorderLayout;
import java.util.List;

import javax.swing.*;

import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentSecretSelection;

/** Adds an existing exact business revision to the advanced non-secret reference field. / 将既有精确业务修订添加到高级无秘密引用字段。 */
final class DeploymentSecretRefField extends JPanel {
    /** Advanced exact input retained for existing workflows. / 保留供既有流程使用的高级精确输入。 */
    private final JTextField value;

    /** Builds one component-scoped selector from the pre-publication snapshot. / 从发布前快照构建一个组件作用域选择器。
     * @param texts pure localized labels / 纯本地化标签
     * @param initial source-backed default / 来源依据默认值
     * @param choices existing revisions with original purposes / 带原用途的既有修订 */
    DeploymentSecretRefField(java.util.function.Function<String, String> texts, String initial,
                             List<DeploymentSecretSelection> choices) {
        super(new BorderLayout(4, 4));
        value = new JTextField(initial);
        add(value, BorderLayout.CENTER);
        var selector = new JComboBox<DeploymentSecretSelection>();
        choices.forEach(selector::addItem);
        selector.setRenderer((list, selected, index, highlighted, focus) -> {
            var label = new JLabel(selected == null
                ? ""
                : DeploymentStaticSecretControls.format(selected) + " (" + selected.purpose() + ")");
            label.setOpaque(true);
            label.setBackground(highlighted ? list.getSelectionBackground() : list.getBackground());
            label.setForeground(highlighted ? list.getSelectionForeground() : list.getForeground());
            return label;
        });
        var add = new JButton(texts.apply("secretUse"));
        add.setEnabled(!choices.isEmpty());
        add.addActionListener(ignored -> {
            var selected = (DeploymentSecretSelection) selector.getSelectedItem();
            if (selected == null)
                return;
            String reference = DeploymentStaticSecretControls.format(selected);
            var current = value.getText().trim();
            value.setText(current.isEmpty() ? reference : current + ";" + reference);
        });
        var right = new JPanel(new BorderLayout(4, 0));
        right.add(selector, BorderLayout.CENTER);
        right.add(add, BorderLayout.EAST);
        add(right, BorderLayout.EAST);
    }

    /** Reads only exact non-secret references. / 仅读取精确无秘密引用。
     * @return semicolon-delimited references / 分号分隔的引用 */
    String value() {
        return value.getText().trim();
    }
}
