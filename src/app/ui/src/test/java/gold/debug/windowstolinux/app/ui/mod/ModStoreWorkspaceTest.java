package gold.debug.windowstolinux.app.ui.mod;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Component;
import java.awt.Container;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.*;

import gold.debug.windowstolinux.app.mod.config.ManifestReader;
import gold.debug.windowstolinux.app.service.mod.store.*;
import gold.debug.windowstolinux.app.service.mod.store.ModStoreSelection.*;
import gold.debug.windowstolinux.app.ui.control.DesktopPartFactory;
import gold.debug.windowstolinux.app.ui.display.ThemePalette;
import gold.debug.windowstolinux.app.ui.error.PageMessagePresenter;
import gold.debug.windowstolinux.app.ui.language.MessageCatalog;
import org.junit.jupiter.api.Test;

class ModStoreWorkspaceTest {
    @Test
    void ownerCardsChooseSemanticMaximumWithoutRemovingExactCatalogCandidates() throws Exception {
        var old = release("sample.root", "1.9.0");
        var newest = release("sample.root", "1.10.0");
        var prerelease = release("sample.root", "1.10.0-rc.1");
        var other = release("sample.other", "2.0.0");
        var all = List.of(old, other, prerelease, newest);
        assertEquals(List.of(newest, other), ModStoreCatalogRows.owners(all, ""));
        assertEquals(List.of(newest), ModStoreCatalogRows.owners(all, "1.9.0"));
        assertEquals(List.of(other), ModStoreCatalogRows.owners(all, "SAMPLE.OTHER"));
        assertTrue(ModStoreCatalogRows.owners(all, "missing").isEmpty());
        assertEquals(4, all.size());
    }

    @Test
    void mainVersionDropdownChangesOnlyExactRootAndPreservesDependencyIntent() throws Exception {
        var fixture = fixture();
        var changed = new AtomicReference<ModStoreSelection>();
        SwingUtilities.invokeAndWait(() -> {
            var pane = pane(changed);
            pane.display(fixture.catalog(), fixture.plan());
            pane.interactive(true);
            var root = find(pane, "store.rootVersions", JComboBox.class);
            assertEquals("2.0.0", root.getItemAt(0));
            assertEquals("1.0.0", root.getSelectedItem());
            root.setSelectedItem("2.0.0");
            assertEquals(new ReleaseKey("sample.root", "2.0.0"), changed.get().root());
            assertEquals(fixture.plan().selection().pins(), changed.get().pins());
            assertEquals(fixture.plan().selection().optionals(), changed.get().optionals());
        });
    }

    @Test
    void dependencyPinsOptionalEdgesAndRequiredGatesRetainOriginalMeaning() throws Exception {
        var fixture = fixture();
        var changed = new AtomicReference<ModStoreSelection>();
        SwingUtilities.invokeAndWait(() -> {
            var pane = pane(changed);
            pane.display(fixture.catalog(), fixture.plan());
            pane.interactive(true);
            var required = find(pane, "store.required.sample.root.sample.core", JCheckBox.class);
            assertFalse(required.isEnabled());
            assertTrue(required.isSelected());
            var optional = find(pane, "store.optional.sample.root.sample.extra", JCheckBox.class);
            assertTrue(optional.isEnabled());
            optional.doClick(0);
            assertEquals(Set.of(new OptionalKey("sample.root", "sample.extra")), changed.get().optionals());
            pane.display(fixture.catalog(), fixture.plan());
            var table = find(pane, "store.plan", JTable.class);
            for (int row = 0; row < table.getRowCount(); row++)
                if ("sample.core".equals(table.getValueAt(row, 0))) table.setRowSelectionInterval(row, row);
            var versions = find(pane, "store.versions", JComboBox.class);
            versions.setSelectedItem("2.0.0");
            assertEquals(new ReleaseKey("sample.root", "1.0.0"), changed.get().root());
            assertEquals("2.0.0", changed.get().pins().get("sample.core"));
            find(pane, "store.unpin", JButton.class).doClick(0);
            assertFalse(changed.get().pins().containsKey("sample.core"));
        });
    }

    @Test
    void clearingPlanRemovesAllPrivateFactsAndCannotEmitStaleDraft() throws Exception {
        var fixture = fixture();
        var changed = new AtomicReference<ModStoreSelection>();
        SwingUtilities.invokeAndWait(() -> {
            var pane = pane(changed);
            pane.display(fixture.catalog(), fixture.plan());
            pane.interactive(true);
            var sourceToggle = find(pane, "store.sourceToggle", JToggleButton.class);
            sourceToggle.doClick(0);
            assertTrue(find(pane, "store.sourceDetails", JTextArea.class).isVisible());
            pane.display(null, null);
            assertEquals(0, find(pane, "store.plan", JTable.class).getRowCount());
            assertEquals(0, find(pane, "store.rootVersions", JComboBox.class).getItemCount());
            assertEquals("", find(pane, "store.details", JTextArea.class).getText());
            assertEquals("", find(pane, "store.sourceDetails", JTextArea.class).getText());
            assertNull(changed.get());
        });
    }

    private static ModStoreSelectionPane pane(AtomicReference<ModStoreSelection> changed) {
        return new ModStoreSelectionPane(new DesktopPartFactory(ThemePalette.light()),
            new PageMessagePresenter(MessageCatalog.forLanguageTag("en")), true, changed::set);
    }

    private static Fixture fixture() throws Exception {
        var root = release("sample.root", "1.0.0");
        var core = release("sample.core", "1.0.0");
        var catalog = new ModStoreCatalog(1, URI.create("https://store.example/catalog/"), List.of(root,
            release("sample.root", "2.0.0"), core, release("sample.core", "2.0.0"), release("sample.extra", "1.0.0")), List.of(), List.of());
        var selection = new ModStoreSelection(new ReleaseKey(root.owner(), root.version()), Set.of(), Map.of(core.owner(), core.version()));
        var plan = new ModStorePlan(2, 1, selection, List.of(
            new ModStorePlan.Item(new ReleaseKey(core.owner(), core.version()), core.manifest(), ModStorePlan.Source.REMOTE, true),
            new ModStorePlan.Item(selection.root(), root.manifest(), ModStorePlan.Source.REMOTE, true)), List.of(
            new ModStorePlan.Relation(new OptionalKey(root.owner(), core.owner()), "1.0.0", "[1.0.0,3.0.0)", false, true,
                "Required function", Optional.of(new ReleaseKey(core.owner(), core.version()))),
            new ModStorePlan.Relation(new OptionalKey(root.owner(), "sample.extra"), "1.0.0", "1.0.0", true, false,
                "Optional function", Optional.empty())), List.of());
        return new Fixture(catalog, plan);
    }

    private static ModStoreGateway.Release release(String owner, String version) throws Exception {
        var manifest = ManifestReader.read(("""
            {"formatVersion":1,"id":"%s","name":"%s","version":"%s","platformApiRange":"[1.0.0,2.0.0)",
             "minJavaVersion":21,"mainJar":"main/mod.jar","entryClass":"%s.Mod","authors":["fixture"],
             "dependencies":[],"exports":[]}
            """).formatted(owner, owner, version, owner).getBytes(StandardCharsets.UTF_8));
        return new ModStoreGateway.Release(manifest, URI.create(owner + "-" + version + ".wtlmod"), 4, "0".repeat(64), Map.of());
    }

    private static <T> T find(Component component, String name, Class<T> type) {
        if (name.equals(component.getName()) && type.isInstance(component)) return type.cast(component);
        if (component instanceof Container container)
            for (var child : container.getComponents()) {
                T found = find(child, name, type);
                if (found != null) return found;
            }
        return null;
    }

    private record Fixture(ModStoreCatalog catalog, ModStorePlan plan) {
    }
}
