package gold.debug.windowstolinux.mod.deployment.ui.standard;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Component;
import java.awt.Container;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.Set;
import javax.swing.*;

import gold.debug.windowstolinux.mod.deployment.service.core.model.project.kind.DeploymentProjectKind;
import gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.part.DiscoveredProjectPart;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Edits the real component controls and checks invalidation before another source review. / 编辑真实组件控件并核对再次来源审阅前失效。 */
class DeploymentComponentEditorTest {
    @ParameterizedTest
    @CsvSource({"en, 1.0", "zh-CN, 1.0", "en, 1.5", "zh-CN, 1.5"})
    void missingHealthOwnerShowsVisibleGuidanceAndExplicitSelectionAllowsConfirmation(String language, float scale) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var messages = ResourceBundle.getBundle("gold.debug.windowstolinux.mod.deployment.ui.deployment-page", Locale.forLanguageTag(language));
            var editor = new DeploymentComponentEditor(messages::getString, List.of(
                new DiscoveredProjectPart("backend", Path.of("backend"), List.of(DeploymentProjectKind.DOTNET_SERVICE)),
                new DiscoveredProjectPart("web", Path.of("web"), List.of(DeploymentProjectKind.NODE_SERVICE))));
            var panel = editor.panel();
            var health = (JComboBox<?>) named(panel, "deployment.components.health");
            assertNull(health.getSelectedItem(), "The health owner must remain an explicit choice");
            assertThrows(IllegalArgumentException.class, editor::selection);
            editor.showInvalid();
            var error = (JLabel) named(panel, "deployment.components.error");
            assertNotNull(error, "Validation guidance must be available on the component editor");
            assertEquals(messages.getString("component.healthRequired"), error.getText());
            assertSame(health, panel.getClientProperty("deployment.review.focus"));
            scale(panel, scale);
            panel.setBorder(BorderFactory.createEmptyBorder(16, 18, 16, 18));
            var scroll = new JScrollPane(panel);
            scroll.setSize(760, 480);
            layout(scroll);
            health.scrollRectToVisible(new java.awt.Rectangle(0, 0, health.getWidth(), health.getHeight()));
            assertTrue(scroll.getViewport().getViewRect().contains(
                    SwingUtilities.convertRectangle(error.getParent(), error.getBounds(), panel)),
                "The initial viewport must show the error without scrolling to the action row");
            health.setSelectedItem("web");
            assertEquals("", error.getText());
            assertNull(panel.getClientProperty("deployment.review.focus"));
            var selection = editor.selection();
            assertEquals(Optional.of("web"), selection.healthComponent());
            assertEquals(Set.of("backend", "web"), selection.choices().keySet());
        });
    }

    @Test
    void actualAddRenameDeletePreservesAllManualValuesAndInvalidatesReviewedRevision() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var messages = ResourceBundle.getBundle("gold.debug.windowstolinux.mod.deployment.ui.deployment-page", Locale.ENGLISH);
            var editor = new DeploymentComponentEditor(messages::getString,
                List.of(new DiscoveredProjectPart("detected", Path.of("old"), List.of(DeploymentProjectKind.GO_SERVICE))));
            var panel = editor.panel();
            text(panel, "id").setText("frontend");
            text(panel, "root").setText("services/web");
            ((JCheckBox) named(panel, "deployment.component.required")).setSelected(false);
            ((JCheckBox) named(panel, "deployment.component.autoArtifacts")).doClick();
            text(panel, "artifacts").setText("services/web/web-bin,shared/docs");
            ((JCheckBox) named(panel, "deployment.component.autoPorts")).doClick();
            text(panel, "ports").setText("8080,8443");
            button(panel, "deployment.components.addUpdate").doClick();
            var initial = editor.selection();
            assertEquals(Set.of("frontend"), initial.choices().keySet());
            var original = initial.choices().get("frontend").inputs().orElseThrow();
            assertEquals("services/web", original.relativeRoot());
            assertFalse(original.required());
            assertEquals(Optional.of(List.of("services/web/web-bin", "shared/docs")), original.artifactPaths());
            assertEquals(Optional.of(Set.of(8080, 8443)), original.ports());
            assertNotNull(panel.getClientProperty("deployment.components.reviewedRevision"));
            text(panel, "ports").setText("8081");
            assertNull(panel.getClientProperty("deployment.components.reviewedRevision"), "A pending input edit must invalidate the captured review immediately");
            button(panel, "deployment.components.addUpdate").doClick();
            button(panel, "deployment.components.new").doClick();
            text(panel, "id").setText("backend");
            text(panel, "root").setText("services/api");
            ((JComboBox<?>) named(panel, "deployment.component.type")).setSelectedItem(DeploymentProjectKind.GO_SERVICE);
            button(panel, "deployment.components.addUpdate").doClick();
            var list = (JList<?>) named(panel, "deployment.components.list");
            list.setSelectedValue("frontend", true);
            text(panel, "depends").setText("backend");
            button(panel, "deployment.components.addUpdate").doClick();
            ((JComboBox<?>) named(panel, "deployment.components.health")).setSelectedItem("frontend");
            assertEquals(Set.of("backend"), editor.selection().choices().get("frontend").dependencies());
            list.setSelectedValue("backend", true);
            button(panel, "deployment.components.remove").doClick();
            assertNull(panel.getClientProperty("deployment.components.reviewedRevision"));
            assertThrows(IllegalArgumentException.class, editor::selection, "Deleting a predecessor must not silently discard its dependent edge");
            list.setSelectedValue("frontend", true);
            text(panel, "depends").setText("");
            var revised = editor.selection();
            assertEquals(Set.of("frontend"), revised.choices().keySet());
            assertEquals(Optional.of(Set.of(8081)), revised.choices().get("frontend").inputs().orElseThrow().ports());
            assertFalse(revised.choices().get("frontend").inputs().orElseThrow().required());
            assertEquals(original.ports(), initial.choices().get("frontend").inputs().orElseThrow().ports(), "The old immutable review remains a value, never the new draft");
        });
    }

    @Test
    void deselectionRetainsPendingRenameAndReselectDoesNotOverwriteTheDraft() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var messages = ResourceBundle.getBundle("gold.debug.windowstolinux.mod.deployment.ui.deployment-page", Locale.ENGLISH);
            var editor = new DeploymentComponentEditor(messages::getString,
                List.of(new DiscoveredProjectPart("service", Path.of(""), List.of(DeploymentProjectKind.GO_SERVICE))));
            var panel = editor.panel();
            var list = (JList<?>) named(panel, "deployment.components.list");
            assertEquals(ListSelectionModel.SINGLE_SELECTION, list.getSelectionMode());
            text(panel, "id").setText("renamed");
            text(panel, "root").setText("services/app");
            Object revision = panel.getClientProperty("deployment.components.draftRevision");
            list.clearSelection();
            assertEquals(revision, panel.getClientProperty("deployment.components.draftRevision"));
            assertEquals("service", list.getModel().getElementAt(0));
            assertFalse(button(panel, "deployment.components.remove").isEnabled());
            button(panel, "deployment.components.remove").doClick();
            list.setSelectedValue("service", true);
            assertEquals("renamed", text(panel, "id").getText());
            assertEquals("services/app", text(panel, "root").getText());
            list.clearSelection();
            button(panel, "deployment.components.addUpdate").doClick();
            var selection = editor.selection();
            assertEquals(Set.of("renamed"), selection.choices().keySet());
            assertEquals("services/app", selection.choices().get("renamed").inputs().orElseThrow().relativeRoot());
            text(panel, "id").setText("reviewed");
            list.clearSelection();
            assertEquals(Set.of("reviewed"), editor.selection().choices().keySet());
        });
    }

    @Test
    void traversalAndInvalidPortsRemainEditableAndExplicitEmptyIsNotAutomatic() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var messages = ResourceBundle.getBundle("gold.debug.windowstolinux.mod.deployment.ui.deployment-page", Locale.ENGLISH);
            var editor = new DeploymentComponentEditor(messages::getString,
                List.of(new DiscoveredProjectPart("service", Path.of(""), List.of(DeploymentProjectKind.GO_SERVICE))));
            var panel = editor.panel();
            text(panel, "root").setText("../outside");
            assertThrows(IllegalArgumentException.class, editor::selection);
            assertEquals("../outside", text(panel, "root").getText());
            text(panel, "root").setText(".");
            ((JCheckBox) named(panel, "deployment.component.autoArtifacts")).doClick();
            ((JCheckBox) named(panel, "deployment.component.autoPorts")).doClick();
            text(panel, "ports").setText("0");
            assertThrows(IllegalArgumentException.class, editor::selection);
            text(panel, "ports").setText("");
            var inputs = editor.selection().choices().get("service").inputs().orElseThrow();
            assertEquals(Optional.of(List.of()), inputs.artifactPaths());
            assertEquals(Optional.of(Set.of()), inputs.ports());
            assertEquals(".", inputs.relativeRoot());
        });
    }

    private static JTextField text(Container root, String key) {
        return (JTextField) named(root, "deployment.component." + key);
    }

    private static JButton button(Container root, String name) {
        return (JButton) named(root, name);
    }

    private static void scale(Container root, float factor) {
        root.setFont(root.getFont().deriveFont(root.getFont().getSize2D() * factor));
        for (var child : root.getComponents()) if (child instanceof Container nested) scale(nested, factor);
    }

    private static void layout(Container root) {
        root.doLayout();
        for (var child : root.getComponents()) if (child instanceof Container nested) layout(nested);
    }

    private static Component named(Container root, String name) {
        for (var child : root.getComponents()) {
            if (name.equals(child.getName())) return child;
            if (child instanceof Container nested) {
                var found = named(nested, name);
                if (found != null) return found;
            }
        }
        return null;
    }
}
