package gold.debug.windowstolinux.mod.deployment.ui.mirror;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.Component;
import java.awt.Container;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.*;
import org.junit.jupiter.api.Test;
import gold.debug.windowstolinux.mod.deployment.config.mirror.MirrorCatalog;
import gold.debug.windowstolinux.mod.deployment.service.mirror.*;

class DeploymentMirrorSettingsEditorTest {
    private final MirrorCatalog catalog = MirrorCatalog.load();
    private final ResourceBundle messages = ResourceBundle.getBundle(DeploymentMirrorSettingsPage.MESSAGES, Locale.ROOT);

    private MirrorSettings fixed() {
        var points = new EnumMap<DownloadPoint, java.util.List<MirrorSettings.Row>>(DownloadPoint.class);
        for (var point : DownloadPoint.values()) {
            var provider = catalog.providersFor(point).stream().filter(p -> p.official()).findFirst().orElseThrow();
            points.put(point, java.util.List.of(new MirrorSettings.Row(provider.id(), "", false, false, "")));
        }
        return new MirrorSettings(1, points, Map.of());
    }

    @Test void retainsThreeRowsWhenAutomaticTemporarilyHidesThem() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var questions = new AtomicInteger();
            var editor = new DeploymentMirrorSettingsEditor(catalog, messages, () -> { questions.incrementAndGet(); return true; });
            editor.load(fixed());
            var point = DownloadPoint.APT_DEBIAN;
            var add = (JButton)find(editor, "deployment.mirror.add." + point.name());
            add.doClick();
            sources(editor, point).getLast().setSelectedIndex(1);
            add.doClick();
            sources(editor, point).getLast().setSelectedIndex(1);
            assertEquals(3, editor.values().rows(point).size());
            assertFalse(add.isVisible());
            var automatic = (JCheckBox)find(editor, "deployment.mirror.auto." + point.name());
            automatic.doClick();
            assertEquals(1, questions.get());
            assertEquals(1, sources(editor, point).size());
            assertFalse(sources(editor, point).getFirst().isEnabled());
            assertEquals(3, editor.values().rows(point).size());
            automatic.doClick();
            assertEquals(3, sources(editor, point).size());
            automatic.doClick();
            assertEquals(2, questions.get(), "Every manual off-to-on transition must ask again");
        });
    }

    @Test void cancelledPrivacyLeavesTheFixedSourceIntact() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var saved = fixed();
            var questions = new AtomicInteger();
            var editor = new DeploymentMirrorSettingsEditor(catalog, messages, () -> { questions.incrementAndGet(); return false; });
            editor.load(saved);
            var automatic = (JCheckBox)find(editor, "deployment.mirror.auto.APT_DEBIAN");
            automatic.doClick();
            assertFalse(automatic.isSelected());
            assertEquals(saved, editor.values());
            assertEquals(1, questions.get());
        });
    }

    @Test void defaultAutomaticStaysPendingUntilUsedAndConfirmedReloadDoesNotAskAgain() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var questions = new AtomicInteger();
            var editor = new DeploymentMirrorSettingsEditor(catalog, messages, () -> { questions.incrementAndGet(); return true; });
            editor.load(MirrorSettings.defaults());
            var saved = editor.values();
            assertEquals(0, questions.get(), "Saving unused defaults must not require an IP-detection decision");
            assertFalse(saved.rows(DownloadPoint.APT_DEBIAN).getFirst().privacyConsent());
            var automatic = (JCheckBox)find(editor, "deployment.mirror.auto.APT_DEBIAN");
            automatic.doClick(); automatic.doClick();
            assertEquals(1, questions.get());
            saved = editor.values();
            assertTrue(saved.rows(DownloadPoint.APT_DEBIAN).getFirst().privacyConsent());
            editor.load(saved);
            assertEquals(saved, editor.values());
            assertEquals(1, questions.get());
            assertFalse(find(editor, "deployment.mirror.add.APT_DEBIAN").isVisible());
        });
    }

    private static Component find(Container parent, String name) {
        for (var child : parent.getComponents()) {
            if (name.equals(child.getName())) return child;
            if (child instanceof Container nested) {
                var found = findOrNull(nested, name);
                if (found != null) return found;
            }
        }
        throw new AssertionError("Missing control: " + name);
    }
    private static Component findOrNull(Container parent, String name) {
        for (var child : parent.getComponents()) {
            if (name.equals(child.getName())) return child;
            if (child instanceof Container nested) {
                var result = findOrNull(nested, name);
                if (result != null) return result;
            }
        }
        return null;
    }
    private static java.util.List<JComboBox<?>> sources(Container parent, DownloadPoint point) {
        var result = new ArrayList<JComboBox<?>>();
        collect(parent, "deployment.mirror.source." + point.name(), result);
        return result;
    }
    private static void collect(Container parent, String name, java.util.List<JComboBox<?>> result) {
        for (var child : parent.getComponents()) {
            if (name.equals(child.getName())) result.add((JComboBox<?>)child);
            if (child instanceof Container nested) collect(nested, name, result);
        }
    }
}
