package gold.debug.windowstolinux.app.ui.component;

import gold.debug.windowstolinux.app.ui.display.ThemePalette;
import gold.debug.windowstolinux.app.ui.i18n.MessageCatalog;
import gold.debug.windowstolinux.app.ui.i18n.PageMessagePresenter;
import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.awt.*;
import static org.junit.jupiter.api.Assertions.*;

class AdvancedOptionsPaneTest {
    @Test void expandingHelpResizesTheDialogAndKeepsTheFormUsable() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(GraphicsEnvironment.isHeadless());
        SwingUtilities.invokeAndWait(() -> {
            var messages = new PageMessagePresenter(MessageCatalog.forLanguageTag("zh-CN"));
            var form = new JScrollPane(new JTextField("preserved input"));
            form.setPreferredSize(new Dimension(520, 250));
            var pane = new AdvancedOptionsPane(form, new DesktopComponentFactory(ThemePalette.light()), messages);
            pane.addOption(new JTextField(24));
            JDialog dialog = new JDialog();
            try {
                dialog.setContentPane(pane); dialog.pack(); int closedWidth = dialog.getWidth();
                pane.setExpanded(true); dialog.validate();
                assertTrue(dialog.getWidth() > closedWidth);
                assertTrue(form.getWidth() >= 400);
                pane.setExpanded(false); assertEquals(closedWidth, dialog.getWidth());
                assertEquals("preserved input", ((JTextField) form.getViewport().getView()).getText());
            } finally { dialog.dispose(); }
        });
    }

    @Test void nestedInputsShowCustomizationAndBusyRestoresOriginalEnabledStates() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var messages = new PageMessagePresenter(MessageCatalog.forLanguageTag("zh-CN"));
            var pane = new AdvancedOptionsPane(new JPanel(), new DesktopComponentFactory(ThemePalette.light()), messages);
            JTextArea input = new JTextArea();
            pane.field("field.configurationEntries", new JScrollPane(input));
            JCheckBox required = new JCheckBox(); JPanel flags = new JPanel(); flags.add(required);
            pane.field("component.required", flags);
            JTextField disabled = new JTextField(); disabled.setEnabled(false); pane.field("field.databaseDetails", disabled);
            assertFalse(customized(pane, messages)); input.setText("PORT=8080"); assertTrue(customized(pane, messages));
            input.setText(""); assertFalse(customized(pane, messages)); required.setSelected(true); assertTrue(customized(pane, messages));
            pane.setBusy(true); assertFalse(input.isEnabled()); assertFalse(required.isEnabled());
            pane.setBusy(false); assertTrue(input.isEnabled()); assertTrue(required.isEnabled()); assertFalse(disabled.isEnabled());
        });
    }

    private static boolean customized(Container parent, PageMessagePresenter messages) {
        for (Component child : parent.getComponents()) {
            if (child instanceof JLabel label && label.getText().equals(messages.text("advanced.modified"))) return true;
            if (child instanceof Container nested && customized(nested, messages)) return true;
        }
        return false;
    }
}
