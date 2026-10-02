package gold.debug.windowstolinux.app.ui.language;

import java.awt.Component;
import java.awt.Container;
import java.util.Objects;

import javax.swing.AbstractButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;

/** Explicit locale bindings for display text without interpreting user input. / 显式绑定展示文本的语言，不解释用户输入。 */
public final class LocaleBinding {
    /** Explicit text or tab-title identity. / 明确的文本或标签标题身份。 */
    private static final String MESSAGE = LocaleBinding.class.getName() + ".message";

    /** Explicit tooltip message identity. / 明确的提示消息身份。 */
    private static final String TOOLTIP = LocaleBinding.class.getName() + ".tooltip";

    /** Explicit existing titled-border identity. / 明确的已有标题边框身份。 */
    private static final String BORDER = LocaleBinding.class.getName() + ".border";

    /** Explicit owned dialog title identity. / 明确的从属对话框标题身份。 */
    private static final String TITLE = LocaleBinding.class.getName() + ".title";

    /** Prevents construction of this stateless binding helper. / 禁止构造无状态绑定工具。 */
    private LocaleBinding() {
    }

    /** Binds one display component or tab body to its declared message. / 将展示组件或标签内容绑定到声明的消息。
     * @param <T> actual component type / 实际组件类型
     * @param component display component, never an editable text input / 展示组件，不得为可编辑文本输入
     * @param message declared key and safe display arguments / 声明的键和安全展示参数
     * @param catalog current locale catalog / 当前区域目录
     * @return the original component / 原组件
     * @throws IllegalArgumentException if binding would change editable input / 绑定将改变可编辑输入时 */
    public static <T extends JComponent> T bind(T component, LocalizedMessage message, MessageCatalog catalog) {
        requireEdt();
        Objects.requireNonNull(component);
        if (component instanceof JTextComponent text && text.isEditable())
            throw new IllegalArgumentException("Editable input cannot be a locale binding");
        component.putClientProperty(MESSAGE, Objects.requireNonNull(message));
        render(component, catalog.text(message));
        return component;
    }

    /** Binds an actual table column to its declared heading key. / 将实际表格列绑定到声明的标题键。
     * @param table original table / 原表格
     * @param column view column index / 视图列索引
     * @param message explicit heading message / 明确标题消息
     * @param catalog current locale catalog / 当前区域目录 */
    public static void column(JTable table, int column, LocalizedMessage message, MessageCatalog catalog) {
        requireEdt();
        var actual = table.getColumnModel().getColumn(column);
        actual.setIdentifier(Objects.requireNonNull(message));
        actual.setHeaderValue(catalog.text(message));
    }

    /** Removes a former display binding before writing independent diagnostic feedback. / 写入独立诊断反馈前移除原展示绑定。
     * @param component original display component / 原展示组件 */
    public static void unbind(JComponent component) {
        requireEdt();
        component.putClientProperty(MESSAGE, null);
    }

    /** Binds only the component tooltip, leaving input contents untouched. / 仅绑定组件提示，保持输入内容不变。
     * @param component original component / 原组件
     * @param message declared tooltip / 声明提示
     * @param catalog current locale catalog / 当前区域目录 */
    public static void tooltip(JComponent component, LocalizedMessage message, MessageCatalog catalog) {
        requireEdt();
        component.putClientProperty(TOOLTIP, Objects.requireNonNull(message));
        component.setToolTipText(catalog.text(message));
    }

    /** Binds the title of an existing titled border without replacing its layout. / 绑定已有标题边框的标题，不替换布局。
     * @param component original bordered component / 原边框组件
     * @param message declared border title / 声明边框标题
     * @param catalog current locale catalog / 当前区域目录
     * @throws IllegalArgumentException if the existing border is not titled / 已有边框没有标题时 */
    public static void border(JComponent component, LocalizedMessage message, MessageCatalog catalog) {
        requireEdt();
        if (!(component.getBorder() instanceof javax.swing.border.TitledBorder border))
            throw new IllegalArgumentException("Locale border binding requires an existing titled border");
        component.putClientProperty(BORDER, Objects.requireNonNull(message));
        border.setTitle(catalog.text(message));
    }

    /** Binds an owned dialog title without creating or closing the dialog. / 绑定从属对话框标题，不创建或关闭窗口。
     * @param dialog original dialog / 原对话框
     * @param message declared title / 声明标题
     * @param catalog current locale catalog / 当前区域目录 */
    public static void title(javax.swing.JDialog dialog, LocalizedMessage message, MessageCatalog catalog) {
        requireEdt();
        dialog.getRootPane().putClientProperty(TITLE, Objects.requireNonNull(message));
        dialog.setTitle(catalog.text(message));
    }

    /** Refreshes only explicit bindings and their table headings on the EDT. / 仅在事件线程刷新显式绑定及表格标题。
     * @param root original component tree / 原组件树
     * @param catalog current locale catalog / 当前区域目录 */
    public static void refresh(Component root, MessageCatalog catalog) {
        requireEdt();
        if (root instanceof JComponent component
            && component.getClientProperty(MESSAGE) instanceof LocalizedMessage message)
            render(component, catalog.text(message));
        if (root instanceof JComponent component) {
            if (component.getClientProperty(TOOLTIP) instanceof LocalizedMessage message)
                component.setToolTipText(catalog.text(message));
            if (component.getClientProperty(BORDER) instanceof LocalizedMessage message
                && component.getBorder() instanceof javax.swing.border.TitledBorder border)
                border.setTitle(catalog.text(message));
            if (component.getClientProperty(TITLE) instanceof LocalizedMessage message
                && SwingUtilities.getWindowAncestor(component) instanceof java.awt.Dialog dialog)
                dialog.setTitle(catalog.text(message));
        }
        if (root instanceof JTabbedPane tabs)
            for (int index = 0; index < tabs.getTabCount(); index++)
                if (tabs.getComponentAt(index) instanceof JComponent body
                    && body.getClientProperty(MESSAGE) instanceof LocalizedMessage message)
                    tabs.setTitleAt(index, catalog.text(message));
        if (root instanceof JTable table) {
            for (int index = 0; index < table.getColumnCount(); index++) {
                var column = table.getColumnModel().getColumn(index);
                if (column.getIdentifier() instanceof LocalizedMessage message)
                    column.setHeaderValue(catalog.text(message));
                else
                    column.setHeaderValue(table.getModel().getColumnName(column.getModelIndex()));
            }
            if (table.getTableHeader() != null)
                table.getTableHeader().repaint();
            table.repaint();
        }
        if (root instanceof Container container)
            for (Component child : container.getComponents())
                refresh(child, catalog);
        root.repaint();
    }

    /** Writes only supported display surfaces; tab bodies retain their explicit identity. / 仅写入受支持的展示界面，标签内容保留明确身份。
     * @param component original display component / 原展示组件
     * @param text resolved display text / 已解析展示文本 */
    private static void render(JComponent component, String text) {
        if (component instanceof AbstractButton button)
            button.setText(text);
        else if (component instanceof JLabel label)
            label.setText(text);
        else if (component instanceof JTextComponent field && !field.isEditable())
            field.setText(text);
    }

    /** Rejects mutation outside the Swing event thread. / 拒绝在 Swing 事件线程之外修改。
     * @throws IllegalStateException if called outside the EDT / 在事件线程外调用时 */
    private static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread())
            throw new IllegalStateException("Locale bindings require the EDT");
    }
}
