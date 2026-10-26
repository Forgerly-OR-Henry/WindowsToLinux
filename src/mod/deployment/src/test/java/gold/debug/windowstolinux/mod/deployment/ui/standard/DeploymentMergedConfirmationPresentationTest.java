package gold.debug.windowstolinux.mod.deployment.ui.standard;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Container;
import java.util.Locale;
import java.util.List;
import java.util.ResourceBundle;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;
import gold.debug.windowstolinux.mod.deployment.service.core.model.database.DeploymentDatabaseEngineType;
import gold.debug.windowstolinux.mod.deployment.service.core.model.database.DeploymentNativeDatabaseFacts.Instance;

class DeploymentMergedConfirmationPresentationTest {
    @Test
    void alreadyExpandedTechnicalDetailsFollowCurrentLaunchEdits() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var messages = ResourceBundle.getBundle("gold.debug.windowstolinux.mod.deployment.ui.deployment-page", Locale.ENGLISH,
                ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES));
            var panel = DeploymentStandardPageReviewer.confirmationPreview(messages::getString);
            var technical = named(panel, "deployment.confirmation.technical");
            assertFalse(technical.isVisible());
            ((JToggleButton) named(panel, "deployment.confirmation.details")).doClick();
            assertTrue(text(technical).contains("http://preview.example.invalid:8080/"));
            ((JTextField) named(panel, "deployment.launch.preview.port")).setText("9001");
            assertTrue(text(technical).contains("http://preview.example.invalid:9001/"));
            assertFalse(text(technical).contains("http://preview.example.invalid:8080/"));
            ((JTextField) named(panel, "deployment.launch.preview.port")).setText("0");
            assertTrue(text(technical).contains(messages.getString("automatic.launchPreviewInvalid")));
            assertFalse(text(technical).contains("http://preview.example.invalid:9001/"));
        });
    }

    @Test
    void databaseReplacementKeepsRiskDecisionsVisibleAndTechnicalFactsCollapsed() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var messages = ResourceBundle.getBundle("gold.debug.windowstolinux.mod.deployment.ui.deployment-page", Locale.ENGLISH,
                ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES));
            var panel = DeploymentStandardPageReviewer.databaseReplacementPreview(messages::getString);
            var technical = named(panel, "deployment.review.technical");
            assertFalse(technical.isVisible());
            String visible = visibleText(panel);
            assertTrue(visible.contains(messages.getString("database.nativeReplacementWarning")));
            for (String decision : List.of("backup", "downtime", "software")) {
                var check = (JCheckBox) named(panel, "database.replacement." + decision);
                assertTrue(check.isVisible());
                assertFalse(check.isSelected());
            }
            for (String detail : List.of("private-server-id", "private-instance-id", "16.3", "17.1", "postgresql-package"))
                assertFalse(visible.contains(detail), detail);
            ((JToggleButton) named(panel, "deployment.review.details")).doClick();
            for (String detail : List.of("private-server-id", "private-instance-id", "16.3", "17.1", "postgresql-package"))
                assertTrue(visibleText(panel).contains(detail), detail);

            var first = new Instance(DeploymentDatabaseEngineType.POSTGRESQL, "raw-first-id", "16.3", 5432,
                "postgresql.service", "/var/lib/data", "a".repeat(64), true);
            JComboBox<String> choices = DeploymentStandardPageReviewer.instanceChoices(messages::getString, List.of(first));
            assertTrue(choices.getSelectedItem().equals("raw-first-id"), "The actual selected identity is retained");
            var rendered = (JLabel) choices.getRenderer().getListCellRendererComponent(new JList<String>(), "raw-first-id", -1, false, false);
            assertTrue(rendered.getText().contains("Database 1"));
            assertFalse(rendered.getText().contains("raw-first-id"));
            assertFalse(rendered.getText().contains("16.3"));
        });
    }

    private static Component named(Component component, String name) {
        if (name.equals(component.getName())) return component;
        if (component instanceof Container container) for (var child : container.getComponents()) {
            var result = named(child, name);
            if (result != null) return result;
        }
        return null;
    }

    private static String text(Component component) {
        String result = component instanceof JTextArea area ? area.getText() : "";
        if (component instanceof Container container)
            for (var child : container.getComponents()) result += "\n" + text(child);
        return result;
    }

    private static String visibleText(Component component) {
        if (!component.isVisible()) return "";
        String result = component instanceof JTextArea area ? area.getText() : "";
        if (component instanceof Container container)
            for (var child : container.getComponents()) result += "\n" + visibleText(child);
        return result;
    }
}
