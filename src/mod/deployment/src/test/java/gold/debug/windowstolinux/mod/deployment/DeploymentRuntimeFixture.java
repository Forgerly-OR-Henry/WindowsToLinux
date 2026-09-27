package gold.debug.windowstolinux.mod.deployment;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;

import javax.swing.JComponent;

import gold.debug.windowstolinux.app.db.bootstrap.DatabaseProtection;
import gold.debug.windowstolinux.app.db.schema.SchemaAccessScope;
import gold.debug.windowstolinux.app.main.startup.PlatformRuntime;
import gold.debug.windowstolinux.app.service.mod.PlatformModIntents;
import gold.debug.windowstolinux.app.service.task.PlatformInteractionService;
import gold.debug.windowstolinux.app.ui.mod.ModPageRegistry;
import gold.debug.windowstolinux.appapi.linux.*;
import gold.debug.windowstolinux.appapi.server.ServerRef;
import gold.debug.windowstolinux.appapi.task.*;
import gold.debug.windowstolinux.appapi.ui.*;
import gold.debug.windowstolinux.mod.deployment.db.DeploymentSchema;
import org.junit.jupiter.api.io.TempDir;

public class DeploymentRuntimeFixture {
    protected static final String OWNER = DeploymentSchema.OWNER;

    protected static final String VERSION = "1.1.0-dev.1";

    protected static final String PASSWORD = "deployment-isolated-password";

    protected static final DatabaseProtection PROTECTION = new DatabaseProtection() {
        public byte[] protect(byte[] input) {
            byte[] output = input.clone();
            for (int i = 0; i < output.length; i++)
                output[i] ^= 0x67;
            return output;
        }

        public byte[] unprotect(byte[] input) {
            return protect(input);
        }
    };

    @TempDir
    protected Path root;

    protected Path workspace() throws Exception {
        Path path = Path.of("").toRealPath();
        while (path != null && !Files.isRegularFile(path.resolve("src/mod/deployment/mod.json")))
            path = path.getParent();
        return Objects.requireNonNull(path, "Actual reactor workspace is required");
    }

    protected PlatformRuntime open() throws Exception {
        var runtime = PlatformRuntime.open(root.resolve("data"), PROTECTION);
        if (runtime.security().securityState().configured())
            runtime.security().unlock(PASSWORD.toCharArray());
        else
            runtime.security().setupAndUnlock(PASSWORD.toCharArray(), PASSWORD.toCharArray());
        runtime.setInteractionPresenter(new PlatformInteractionService.Prompts() {
            public TransferConflictChoice transferConflict(TransferConflict conflict, ServerRef server,
                    TaskSnapshot task, CancellationToken cancellation) {
                throw new AssertionError("No remote interaction expected");
            }

            public TransferStallChoice transferStalled(TransferStall stall, ServerRef server, TaskSnapshot task,
                    CancellationToken cancellation) {
                throw new AssertionError("No remote interaction expected");
            }

            public String transferProgress(TransferProgress progress) {
                throw new AssertionError("No remote interaction expected");
            }

            public boolean approve(ActionIntent intent, TaskSnapshot task, CancellationToken cancellation) {
                throw new AssertionError("No external action expected");
            }

            public char[] text(String title, String prompt, boolean secret, TaskSnapshot task,
                    CancellationToken cancellation) {
                throw new AssertionError("No input expected");
            }
        });
        runtime.setSourcePicker((title, task, cancel) -> selectedSourceDirectory());
        runtime.started();
        return runtime;
    }

    protected Path selectedSourceDirectory() {
        throw new AssertionError("No source selection expected");
    }

    protected void start(PlatformRuntime runtime, boolean enabled) throws Exception {
        var pages = new ModPageRegistry(new ModPageRegistry.PageHost() {
            public void show(ModPageRegistry.PageKey key, PageSpec spec, JComponent component) {
                throw new AssertionError("No deployment page yet");
            }

            public void focus(ModPageRegistry.PageKey key) {
            }

            public void remove(ModPageRegistry.PageKey key) {
            }

            public void environment(UiEnvironment environment) {
            }
        }, new UiEnvironment(Locale.SIMPLIFIED_CHINESE, "LIGHT", false));
        var report = runtime.startDevelopmentMods(workspace(), pages);
        assertTrue(report.problems().isEmpty(), report.problems().toString());
        assertEquals(enabled ? Set.of(OWNER) : Set.of(), report.running());
    }

    protected void enable() throws Exception {
        try (var runtime = open()) {
            start(runtime, false);
            assertEquals(0, scalar(runtime, "SELECT COUNT(*) FROM mod_schema"));
            assertEquals(1, scalar(runtime,
                    "SELECT COUNT(*) FROM mod_version WHERE mod_id='" + OWNER + "' AND origin_kind='CLASS'"));
            var intents = runtime.modIntents();
            intents.apply(
                    intents.review(OWNER, new PlatformModIntents.Choice(true, Optional.of(VERSION)), Optional.empty()),
                    false);
        }
    }

    protected SchemaAccessScope scope(PlatformRuntime runtime, boolean readOnly) throws Exception {
        return new SchemaAccessScope(runtime.persistence().database(),
                runtime.modContexts().get(OWNER).context().db().identity(), UUID.randomUUID(), readOnly);
    }

    protected long scalar(PlatformRuntime runtime, String query) throws Exception {
        try (var connection = runtime.persistence().database().openConnection();
                var statement = connection.createStatement();
                var rows = statement.executeQuery(query)) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }

}
