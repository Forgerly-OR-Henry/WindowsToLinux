package gold.debug.windowstolinux.app.ui.mod;

import java.awt.*;
import javax.swing.*;

import com.formdev.flatlaf.util.UIScale;
import gold.debug.windowstolinux.app.ui.error.PageMessagePresenter;
import gold.debug.windowstolinux.app.ui.control.DesktopPartFactory;

/** Store-only content groups reading current theme roles. / 读取当前主题角色的商店内容分组。 */
final class ModStoreSections {
    private ModStoreSections() {
    }

    static JPanel column() {
        var panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setOpaque(false);
        return panel;
    }

    static JPanel block(DesktopPartFactory components, PageMessagePresenter messages, String key, JComponent content,
                        boolean outlined) {
        var layout = new BorderLayout(0, UIScale.scale(12));
        var panel = outlined ? components.outlinedCard(layout) : components.card(layout);
        var title = messages.bind(new ModTextLabel("", 18, true, false), key);
        panel.add(title, BorderLayout.NORTH);
        panel.add(content);
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        return panel;
    }

    static void add(JPanel column, JComponent content) {
        if (column.getComponentCount() > 0) column.add(Box.createVerticalStrut(UIScale.scale(14)));
        content.setAlignmentX(Component.LEFT_ALIGNMENT);
        column.add(content);
    }

    static JTextArea text(String name, int rows) {
        var text = new JTextArea(rows, 24);
        text.setName(name);
        text.setEditable(false);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.setOpaque(false);
        text.putClientProperty("html.disable", true);
        return text;
    }
}
