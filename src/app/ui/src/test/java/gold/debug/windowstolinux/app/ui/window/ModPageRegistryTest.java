package gold.debug.windowstolinux.app.ui.window;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Component;
import java.awt.Container;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import javax.swing.*;

import gold.debug.windowstolinux.app.ui.mod.ExtensionPagePane;
import gold.debug.windowstolinux.app.ui.mod.ModPageRegistry.*;
import gold.debug.windowstolinux.app.ui.mod.ModPageRegistry;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.ui.*;
import gold.debug.windowstolinux.appapi.ui.page.ModPage;
import gold.debug.windowstolinux.appapi.ui.page.PageRegistration;
import gold.debug.windowstolinux.appapi.ui.page.PageSpec;
import gold.debug.windowstolinux.appapi.ui.page.UiEnvironment;
import org.junit.jupiter.api.Test;

class ModPageRegistryTest {
    static final UiEnvironment INITIAL = new UiEnvironment(Locale.ENGLISH, "light", false);

    PageSpec spec(String id, boolean settings) {
        return new PageSpec(id, "fixture." + id, "Fixture " + id, settings);
    }

    static class Host implements PageHost {
        final Map<PageKey, JComponent> views = new LinkedHashMap<>();

        int focuses;

        int removeFailures;

        boolean locked;

        public void show(PageKey key, PageSpec spec, JComponent view) {
            assertTrue(SwingUtilities.isEventDispatchThread());
            views.put(key, view);
        }

        public void focus(PageKey key) {
            assertTrue(SwingUtilities.isEventDispatchThread());
            assertTrue(views.containsKey(key));
            focuses++;
        }

        public void remove(PageKey key) {
            assertTrue(SwingUtilities.isEventDispatchThread());
            if (removeFailures-- > 0)
                throw new IllegalStateException("fixture removal failure");
            views.remove(key);
        }

        public void environment(UiEnvironment environment) {
            assertTrue(SwingUtilities.isEventDispatchThread());
            locked = environment.locked();
        }
    }

    static class Page implements ModPage {
        int creates;

        int closes;

        int closeFailures;

        boolean createFailure;

        boolean notifyFailure;

        Error createError, closeError, notifyError;

        final List<UiEnvironment> environments = new ArrayList<>();

        public JComponent create(UiEnvironment environment) {
            assertTrue(SwingUtilities.isEventDispatchThread());
            creates++;
            if (createError != null)
                throw createError;
            if (createFailure)
                throw new IllegalStateException("fixture create failure");
            return new JPanel();
        }

        public void environmentChanged(UiEnvironment environment) {
            assertTrue(SwingUtilities.isEventDispatchThread());
            environments.add(environment);
            if (notifyError != null)
                throw notifyError;
            if (notifyFailure)
                throw new IllegalStateException("fixture notification failure");
        }

        public void close() {
            assertTrue(SwingUtilities.isEventDispatchThread());
            closes++;
            if (closeError != null)
                throw closeError;
            if (closeFailures-- > 0)
                throw new IllegalStateException("fixture close failure");
        }
    }

    @Test
    void hostNavigationCreatesLockedPagesWhileSdkOpenStillRequiresUnlock() throws Exception {
        var host = new Host();
        var registry = new ModPageRegistry(host, new UiEnvironment(Locale.ENGLISH, "dark", true));
        var creations = new AtomicInteger();
        try (var owner = registry.begin("example.owner", false)) {
            owner.register(spec("main", false), () -> new Page() {
                @Override
                public JComponent create(UiEnvironment environment) {
                    assertTrue(environment.locked());
                    creations.incrementAndGet();
                    return super.create(environment);
                }
            });
            owner.commit();
            assertThrows(ApiException.class, () -> owner.open("main"));
            assertThrows(ApiException.class, () -> registry.openView(new PageKey("example.owner", "main")));
            registry.navigateView(new PageKey("example.owner", "main"));
            assertEquals(1, creations.get());
            registry.navigateView(new PageKey("example.owner", "main"));
            assertEquals(1, creations.get());
            assertEquals(1, host.focuses);
        }
        assertTrue(host.views.isEmpty());
    }

    @Test
    void authorErrorsRetainExactFailedPageUntilCleanupRetry() throws Exception {
        var registry = new ModPageRegistry(new Host(), INITIAL);
        var page = new Page();
        page.createError = new ServiceConfigurationError("page provider unavailable");
        page.closeError = new Error("page cleanup unavailable");
        try (var owner = registry.begin("example.owner", false)) {
            owner.register(spec("main", false), () -> page);
            owner.commit();
            var failure = assertThrows(ApiException.class, () -> owner.open("main"));
            assertEquals("PAGE_CREATION_FAILED", failure.problem().eventCode());
            assertEquals(1, failure.getSuppressed().length);
            assertEquals(1, page.creates);
            assertEquals(1, page.closes);
            assertThrows(ApiException.class, () -> owner.open("main"));
            assertEquals(1, page.creates);
            page.closeError = null;
            registry.closeView(new PageKey("example.owner", "main"));
            assertEquals(2, page.closes);
        }
        assertTrue(registry.contributions().isEmpty());
    }

    @Test
    void environmentErrorsDoNotSkipOtherPagesOrUndoHostLock() throws Exception {
        var host = new Host();
        var registry = new ModPageRegistry(host, INITIAL);
        var bad = new Page();
        var good = new Page();
        bad.notifyError = new ServiceConfigurationError("page notification unavailable");
        try (var owner = registry.begin("example.owner", false);
             var other = registry.begin("example.other", false)) {
            owner.register(spec("bad", false), () -> bad);
            other.register(spec("good", false), () -> good);
            owner.commit();
            other.commit();
            owner.open("bad");
            other.open("good");
            var locked = new UiEnvironment(Locale.ENGLISH, "light", true);
            var failure = assertThrows(ApiException.class, () -> registry.environment(locked));
            assertEquals("PAGE_ENVIRONMENT_FAILED", failure.problem().eventCode());
            assertTrue(host.locked);
            assertEquals(List.of(locked), good.environments);
        }
        assertEquals(1, bad.closes);
        assertEquals(1, good.closes);
    }

    @Test
    void fatalPageErrorsLeaveEdtBoundaryUnconvertedAndAllowExactCleanup() throws Exception {
        for (var fatal : List.of(new InternalError("synthetic page VM error"), new ThreadDeath())) {
            var registry = new ModPageRegistry(new Host(), INITIAL);
            var page = new Page();
            page.createError = fatal;
            try (var owner = registry.begin("example.owner", false)) {
                owner.register(spec("main", false), () -> page);
                owner.commit();
                assertSame(fatal, assertThrows(Error.class, () -> owner.open("main")));
                assertEquals(0, page.closes);
                page.createError = null;
                registry.closeView(new PageKey("example.owner", "main"));
                owner.open("main");
                page.notifyError = fatal;
                assertSame(fatal, assertThrows(Error.class,
                    () -> registry.environment(new UiEnvironment(Locale.ENGLISH, "dark", false))));
                page.notifyError = null;
                page.closeError = fatal;
                assertSame(fatal, assertThrows(Error.class,
                    () -> registry.closeView(new PageKey("example.owner", "main"))));
                page.closeError = null;
                registry.closeView(new PageKey("example.owner", "main"));
                assertEquals(3, page.closes);
            }
        }
    }

    @Test
    void startupContributionsStayPrivateAndPageInstancesAreLazyAndFreshOnReopen() throws Exception {
        var host = new Host();
        var registry = new ModPageRegistry(host, INITIAL);
        var created = new ArrayList<Page>();
        try (var owner = registry.begin("example.owner", false); var other = registry.begin("example.other", false)) {
            owner.register(spec("main", false), () -> {
                assertTrue(SwingUtilities.isEventDispatchThread());
                var page = new Page();
                created.add(page);
                return page;
            });
            other.register(spec("main", false), Page::new);
            assertTrue(registry.contributions().isEmpty());
            assertTrue(created.isEmpty());
            assertThrows(ApiException.class, () -> owner.open("main"));
            assertThrows(ApiException.class, () -> owner.register(spec("main", false), Page::new));
            owner.commit();
            other.commit();
            assertEquals(2, registry.contributions().size());
            assertTrue(host.views.isEmpty());
            owner.open("main");
            owner.open("main");
            assertEquals(1, created.size());
            assertEquals(1, host.focuses);
            registry.closeView(new PageKey("example.owner", "main"));
            assertEquals(1, created.getFirst().closes);
            owner.open("main");
            assertEquals(2, created.size());
            assertEquals(1, host.views.size());
        }
        assertTrue(registry.contributions().isEmpty());
        assertTrue(host.views.isEmpty());
        assertEquals(1, created.getLast().closes);
    }

    @Test
    void failedStartupAndRecoveryModeDoNotPublishOrCreateBusinessViews() throws Exception {
        var registry = new ModPageRegistry(new Host(), INITIAL);
        var creates = new AtomicInteger();
        try (var failed = registry.begin("example.owner", false)) {
            failed.register(spec("main", false), () -> {
                creates.incrementAndGet();
                return new Page();
            });
        }
        try (var recovery = registry.begin("example.owner", true)) {
            recovery.register(spec("recovery", false), () -> {
                creates.incrementAndGet();
                return new Page();
            });
            recovery.commit();
            assertTrue(registry.contributions().isEmpty());
            assertThrows(ApiException.class, () -> recovery.open("recovery"));
            assertThrows(ApiException.class, () -> registry.begin("example.owner", false));
        }
        assertEquals(0, creates.get());
        try (var normal = registry.begin("example.owner", false)) {
            normal.commit();
        }
    }

    @Test
    void failedCreationCleanupRetainsTheInstanceUntilAnExplicitCloseRetry() throws Exception {
        var registry = new ModPageRegistry(new Host(), INITIAL);
        var page = new Page();
        page.createFailure = true;
        page.closeFailures = 1;
        var factories = new AtomicInteger();
        var owner = registry.begin("example.owner", false);
        owner.register(spec("main", false), () -> {
            factories.incrementAndGet();
            return page;
        });
        owner.commit();
        assertThrows(ApiException.class, () -> owner.open("main"));
        assertEquals(1, page.closes);
        assertThrows(ApiException.class, () -> owner.open("main"));
        assertEquals(1, factories.get());
        owner.close();
        owner.close();
        assertEquals(2, page.closes);
        assertTrue(registry.contributions().isEmpty());
    }

    @Test
    void hostRemovalRetryDoesNotRepeatSuccessfulAuthorClose() throws Exception {
        var host = new Host();
        var registry = new ModPageRegistry(host, INITIAL);
        var page = new Page();
        var owner = registry.begin("example.owner", false);
        var registration = owner.register(spec("main", false), () -> page);
        owner.commit();
        owner.open("main");
        host.removeFailures = 1;
        assertThrows(ApiException.class, registration::close);
        assertEquals(1, page.closes);
        assertEquals(1, host.views.size());
        assertThrows(ApiException.class, () -> owner.open("main"));
        registration.close();
        registration.close();
        assertEquals(1, page.closes);
        assertTrue(host.views.isEmpty());
        owner.close();
    }

    @Test
    void hostLocksBeforeNotificationsAndAFailingPageDoesNotSkipTheOthers() throws Exception {
        var host = new Host();
        var registry = new ModPageRegistry(host, INITIAL);
        var first = new Page();
        first.notifyFailure = true;
        var second = new Page() {
            @Override
            public void environmentChanged(UiEnvironment environment) {
                assertTrue(host.locked);
                super.environmentChanged(environment);
            }
        };
        try (var owner = registry.begin("example.owner", false)) {
            owner.register(spec("first", false), () -> first);
            owner.register(spec("second", true), () -> second);
            owner.commit();
            owner.open("first");
            owner.open("second");
            var changed = new UiEnvironment(Locale.SIMPLIFIED_CHINESE, "dark", true);
            assertThrows(ApiException.class, () -> registry.environment(changed));
            assertEquals(List.of(changed), second.environments);
            assertEquals(changed, owner.environment());
            assertThrows(ApiException.class, () -> owner.open("second"));
        }
    }

    @Test
    void interruptedBackgroundRegistrationReturnsTheActualCommittedIdentity() throws Exception {
        var registry = new ModPageRegistry(new Host(), INITIAL);
        try (var owner = registry.begin("example.owner", false)) {
            var done = new CompletableFuture<Boolean>();
            var thread = new Thread(() -> {
                try {
                    Thread.currentThread().interrupt();
                    var registration = owner.register(spec("main", false), Page::new);
                    done.complete(registration.id().equals("main") && Thread.currentThread().isInterrupted());
                } catch (Throwable failure) {
                    done.completeExceptionally(failure);
                }
            }, "page-registration-test");
            thread.start();
            assertTrue(done.get(5, TimeUnit.SECONDS));
            thread.join(5000);
            assertFalse(thread.isAlive());
            owner.commit();
            assertEquals(1, registry.contributions().size());
        }
    }

    @Test
    void actualSwingHostSeparatesBusinessAndOwnerSettingsAndCloseButtonReleasesView() throws Exception {
        var pane = new AtomicReference<ExtensionPagePane>();
        var registry = new AtomicReference<ModPageRegistry>();
        var closeFailure = new AtomicReference<Exception>();
        SwingUtilities.invokeAndWait(() -> pane.set(new ExtensionPagePane(key -> {
            try {
                registry.get().closeView(key);
            } catch (ApiException failure) {
                closeFailure.set(failure);
            }
        }, owner -> "Owner " + owner, () -> "Locked", settings -> {
        })));
        registry.set(new ModPageRegistry(pane.get(), INITIAL));
        var business = new Page();
        var settings = new Page();
        try (var owner = registry.get().begin("example.owner", false)) {
            owner.register(spec("business", false), () -> business);
            owner.register(spec("settings", true), () -> settings);
            owner.commit();
            owner.open("business");
            owner.open("settings");
            SwingUtilities.invokeAndWait(() -> {
                var businessTabs = components(pane.get(), JTabbedPane.class);
                assertTrue(businessTabs.isEmpty());
                var settingTabs = components(pane.get().settingsComponent(), JTabbedPane.class);
                assertEquals(2, settingTabs.size());
                assertEquals("Owner example.owner", settingTabs.getFirst().getTitleAt(0));
                components(pane.get(), JButton.class).getFirst().doClick();
                assertTrue(pane.get().selectedPage(false).isEmpty());
            });
            assertNull(closeFailure.get());
            assertEquals(1, business.closes);
            registry.get().environment(new UiEnvironment(Locale.ENGLISH, "dark", true));
            SwingUtilities.invokeAndWait(() -> {
                assertTrue(components(pane.get(), JTabbedPane.class).isEmpty());
                assertTrue(components(pane.get().settingsComponent(), JTabbedPane.class).getFirst().isVisible());
            });
        }
        assertEquals(1, settings.closes);
    }

    @Test
    void oneRootPerOwnerDoesNotCountSettingsOrIsolatedPreviews() throws Exception {
        var registry = new ModPageRegistry(new Host(), INITIAL);
        var created = new AtomicInteger();
        try (var owner = registry.begin("example.owner", false)) {
            var root = owner.register(spec("workspace", false), () -> {
                created.incrementAndGet();
                return new Page();
            });
            owner.register(spec("preferences", true), Page::new);
            owner.registerPreview(spec("preview", false), Page::new);
            var error = assertThrows(ApiException.class, () -> owner.register(spec("another", false), Page::new));
            assertEquals("WORKSPACE_ALREADY_REGISTERED", error.problem().eventCode());
            assertEquals("PAGE_ID_INVALID", assertThrows(ApiException.class,
                () -> owner.register(spec("x".repeat(1025), false), Page::new)).problem().eventCode());
            owner.commit();
            assertEquals(1, registry.contributions().stream().filter(page -> !page.spec().settingsPage()).count());
            assertEquals(0, created.get());
            root.close();
            owner.register(spec("replacement", false), Page::new);
            assertEquals("replacement", registry.contributions().stream().filter(page -> !page.spec().settingsPage())
                .findFirst().orElseThrow().key().pageId());
        }
    }

    @Test
    void aPageCannotMountAfterRevokingItsOwnRegistrationDuringCreation() throws Exception {
        var host = new Host();
        var registry = new ModPageRegistry(host, INITIAL);
        var registration = new AtomicReference<PageRegistration>();
        var page = new Page() {
            @Override
            public JComponent create(UiEnvironment environment) {
                var component = super.create(environment);
                try {
                    registration.get().close();
                } catch (ApiException failure) {
                    throw new IllegalStateException(failure);
                }
                return component;
            }
        };
        try (var owner = registry.begin("example.owner", false)) {
            registration.set(owner.register(spec("main", false), () -> page));
            owner.commit();
            assertThrows(ApiException.class, () -> owner.open("main"));
            assertEquals(1, page.closes);
            assertTrue(host.views.isEmpty());
            assertTrue(registry.contributions().isEmpty());
        }
    }

    @Test
    void recursiveAuthorCloseIsRejectedWithoutLosingRetryableOwnership() throws Exception {
        var host = new Host();
        var registry = new ModPageRegistry(host, INITIAL);
        var registration = new AtomicReference<PageRegistration>();
        var recurse = new AtomicBoolean(true);
        var page = new Page() {
            @Override
            public void close() {
                super.close();
                if (recurse.get())
                    try {
                        registration.get().close();
                    } catch (ApiException failure) {
                        throw new IllegalStateException(failure);
                    }
            }
        };
        try (var owner = registry.begin("example.owner", false)) {
            registration.set(owner.register(spec("main", false), () -> page));
            owner.commit();
            owner.open("main");
            assertThrows(ApiException.class, registration.get()::close);
            assertEquals(1, page.closes);
            assertEquals(1, host.views.size());
            recurse.set(false);
            registration.get().close();
            assertEquals(2, page.closes);
            assertTrue(host.views.isEmpty());
        }
    }

    @Test
    void previewsRequireClassHostCommittedOwnerAndUnlockAndStayOutOfNormalNavigation() throws Exception {
        var workspace = new Host();
        var previews = new Host();
        var registry = new ModPageRegistry(workspace, INITIAL);
        var preview = new Page();
        var business = new Page();
        var key = new PageKey("example.owner", "preview");
        try (var owner = registry.begin("example.owner", false)) {
            owner.register(spec("business", false), () -> business);
            owner.registerPreview(spec("preview", true), () -> preview);
            assertTrue(registry.previews().isEmpty());
            var noClass = assertThrows(ApiException.class, () -> registry.openPreview(key));
            assertEquals("PREVIEW_CLASS_REQUIRED", noClass.problem().eventCode());
            registry.installPreviews(previews);
            assertTrue(registry.previews().isEmpty());
            assertThrows(ApiException.class, () -> registry.openPreview(key));
            owner.commit();
            assertEquals(List.of(new PageKey("example.owner", "business")),
                registry.contributions().stream().map(PageContribution::key).toList());
            assertEquals(List.of(key), registry.previews().stream().map(PageContribution::key).toList());
            assertEquals(0, preview.creates);
            assertEquals(0, business.creates);
            assertThrows(ApiException.class, () -> owner.open("preview"));
            assertThrows(ApiException.class, () -> registry.openPreview(new PageKey("example.owner", "business")));
            owner.open("business");
            registry.openPreview(key);
            registry.openPreview(key);
            assertEquals(1, preview.creates);
            assertEquals(1, previews.focuses);
            assertEquals(Set.of(key), previews.views.keySet());
            assertEquals(Set.of(new PageKey("example.owner", "business")), workspace.views.keySet());
            var changed = new UiEnvironment(Locale.SIMPLIFIED_CHINESE, "dark", false);
            registry.environment(changed);
            assertEquals(List.of(changed), preview.environments);
            registry.environment(new UiEnvironment(Locale.SIMPLIFIED_CHINESE, "dark", true));
            assertEquals(1, preview.closes);
            assertTrue(previews.views.isEmpty());
            assertEquals(0, business.closes);
            assertEquals("PAGE_LOCKED",
                assertThrows(ApiException.class, () -> registry.openPreview(key)).problem().eventCode());
            registry.environment(INITIAL);
        }
        assertEquals(1, business.closes);
        assertTrue(registry.contributions().isEmpty());
        assertTrue(registry.previews().isEmpty());
        assertTrue(workspace.views.isEmpty());
    }

    @Test
    void failedPreviewCleanupRetainsExactOwnerUntilExplicitRetryAndNeverRecreatesIt() throws Exception {
        var workspace = new Host();
        var previews = new Host();
        var registry = new ModPageRegistry(workspace, INITIAL);
        registry.installPreviews(previews);
        var page = new Page();
        page.closeFailures = 1;
        var factoryCalls = new AtomicInteger();
        var key = new PageKey("example.owner", "preview");
        var owner = registry.begin("example.owner", false);
        try {
            owner.registerPreview(spec("preview", true), () -> {
                factoryCalls.incrementAndGet();
                return page;
            });
            owner.commit();
            registry.openPreview(key);
            assertThrows(ApiException.class, owner::close);
            assertEquals(1, page.closes);
            assertEquals(1, factoryCalls.get());
            assertEquals(Set.of(key), previews.views.keySet());
            assertTrue(workspace.views.isEmpty());
            assertThrows(ApiException.class, () -> registry.begin("example.owner", false));
            assertThrows(ApiException.class, () -> registry.openPreview(key));
            assertEquals(1, factoryCalls.get());
            owner.close();
            owner.close();
            assertEquals(2, page.closes);
            assertTrue(previews.views.isEmpty());
            assertTrue(registry.previews().isEmpty());
            try (var replacement = registry.begin("example.owner", false)) {
                replacement.commit();
            }
        } finally {
            page.closeFailures = 0;
            owner.close();
        }
    }

    static <T extends Component> List<T> components(Component root, Class<T> type) {
        var found = new ArrayList<T>();
        if (type.isInstance(root))
            found.add(type.cast(root));
        if (root instanceof Container container)
            for (var child : container.getComponents())
                found.addAll(components(child, type));
        return found;
    }
}
