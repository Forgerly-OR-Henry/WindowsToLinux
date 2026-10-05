package gold.debug.windowstolinux.mod.deployment.ui.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Component;
import java.awt.Container;
import java.awt.event.ActionEvent;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.*;

import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.input.DeploymentStaticRuntimeResolver;
import org.junit.jupiter.api.Test;

/** Verifies retained editable multiline values, exact help and locale updates on the actual forms. / 在实际表单验证保留多行值、精确帮助和语言更新。 */
class DeploymentReviewInputsTest {
    @Test
    void multilineHelpWorksByKeyboardAndLocaleChangeKeepsOriginalControlAndValue() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var english = ResourceBundle.getBundle("gold.debug.windowstolinux.mod.deployment.ui.deployment-page", Locale.ENGLISH);
            var chinese = ResourceBundle.getBundle("gold.debug.windowstolinux.mod.deployment.ui.deployment-page", Locale.SIMPLIFIED_CHINESE);
            var field = DeploymentStaticRuntimeResolver.field("api", "configuration", "RUNTIME:MODE=first\nRUNTIME:PORT=8080", java.util.List.of());
            var input = (JTextArea) DeploymentReviewInputs.create(field);
            var decorated = DeploymentReviewInputs.decorated(input, field, english::getString);
            input.setText("RUNTIME:MODE=changed\nRUNTIME:PORT=9090");
            assertEquals(english.getString(field.helpKey()), input.getToolTipText());
            assertEquals(input.getToolTipText(), input.getAccessibleContext().getAccessibleDescription());
            var details = (JTextArea) input.getClientProperty("deployment.help.text");
            assertFalse(details.isVisible());
            input.getActionMap().get("field-help").actionPerformed(new ActionEvent(input, ActionEvent.ACTION_PERFORMED, "F1"));
            assertTrue(details.isVisible());
            DeploymentReviewInputs.refresh(decorated, chinese::getString);
            assertSame(input, named(decorated, "deployment.runtime.api/configuration"));
            assertEquals("RUNTIME:MODE=changed\nRUNTIME:PORT=9090", DeploymentReviewInputs.value(input));
            assertEquals(chinese.getString(field.helpKey()), input.getToolTipText());
            assertEquals(input.getToolTipText(), details.getText());
            assertTrue(details.isVisible());
        });
    }

    @Test
    void advancedRuntimeDraftUsesRealEmptyChoicesAndRetainsValuesAcrossLocaleAndLock() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var locale = new AtomicReference<>(Locale.ENGLISH);
            var form = new DeploymentRuntimeDraftForm(key -> ResourceBundle.getBundle(
                "gold.debug.windowstolinux.mod.deployment.ui.deployment-page", locale.get()).getString(key));
            var declaration = (JTextArea) named(form.panel(), "deployment.runtime.draft/applicationDeclaration");
            var exposure = (JComboBox<?>) named(form.panel(), "deployment.runtime.draft/exposure");
            assertNull(exposure.getSelectedItem(), "An untouched choice must not become consent or an explicit value");
            assertTrue(form.values().isEmpty());
            declaration.setText("version=1\nhealth.mode=TCP");
            exposure.setSelectedItem("INTERNAL");
            form.enabled(false);
            assertFalse(declaration.isEnabled());
            assertFalse(exposure.isEnabled());
            locale.set(Locale.SIMPLIFIED_CHINESE);
            form.localize();
            form.enabled(true);
            assertSame(declaration, named(form.panel(), "deployment.runtime.draft/applicationDeclaration"));
            assertEquals("version=1\nhealth.mode=TCP", form.values().get("applicationDeclaration"));
            assertEquals("INTERNAL", form.values().get("exposure"));
            assertTrue(declaration.isEnabled());
        });
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
