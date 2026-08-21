package gold.debug.windowstolinux.shared.linux.sshd.execution.protocol.helper;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagedHelperBundleTest {
    @Test
    void assemblesTheAllowlistedProtocolByteForByte() throws Exception {
        byte[] bytes = ManagedHelperBundle.renderScript().getBytes(StandardCharsets.UTF_8);
        String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));

        assertEquals(ManagedHelperBundle.EXPECTED_SHA256, sha256);
        assertTrue(ManagedHelperBundle.renderScript().startsWith("#!/usr/bin/env bash\n"));
        assertTrue(ManagedHelperBundle.renderScript().endsWith("esac\n"));
        assertEquals("/usr/local/lib/windowstolinux/managed-helper", ManagedHelperBundle.PATH);
        assertTrue(ManagedHelperBundle.renderScript().contains("/usr/local/lib/windowstolinux/java-21"));
        assertFalse(ManagedHelperBundle.renderScript().contains("/usr/bin/java"));
    }

    @Test
    void exposesOnlyVersionedTypedOperationsAndKeepsLegacyGradleReadCompatibility() {
        String helper = ManagedHelperBundle.renderScript();

        assertEquals(4, ManagedHelperBundle.PROTOCOL_VERSION);
        assertTrue(helper.contains("printf 'HELPER=1\\nPROTOCOL=%s\\n' \"$helper_protocol\""));
        assertTrue(helper.contains("gradle)"));
        assertTrue(helper.contains("[ \"$kind\" != gradle ] || reject legacy-gradle-write"));
        assertTrue(helper.contains("go|rust)"));
        assertTrue(helper.contains("java|javasource)"));
        assertTrue(helper.contains("PIP_LOCKED|PIPENV_LOCKED|POETRY_LOCKED|UV_LOCKED"));
        assertTrue(helper.contains("GRADLE_KOTLIN_WRAPPER|KOTLINC"));
        assertTrue(helper.contains("render_ecosystem_runtime_command \"$kind\" \"$root\" \"$@\""));
        assertTrue(helper.contains("phpcli)"));
        assertTrue(helper.contains("rubycli)"));
        assertTrue(helper.contains("cmake)"));
        assertTrue(helper.contains("database-inspect) database_inspect \"$@\""));
        assertTrue(helper.contains("database-export) database_export \"$@\""));
        assertTrue(helper.contains("database-stage-artifact) database_stage_artifact \"$@\""));
        assertTrue(helper.contains("database-restore-candidate) database_restore_candidate \"$@\""));
        assertTrue(helper.contains("database-discard-candidate) database_discard_candidate \"$@\""));
        assertTrue(helper.contains("rm -f -- \"$pgpass\"; reject database-restore-failed"));
        assertTrue(helper.contains("DROP DATABASE IF EXISTS"));
        assertTrue(helper.contains("dropdb --if-exists --force --no-password"));
        assertTrue(helper.contains("rm -f -- \"$credentials\""));
        assertTrue(helper.contains("NPM) command=\"/usr/bin/env PATH=/usr/local/bin:/usr/bin:/bin npm"));
        assertTrue(helper.contains("PNPM) command=\"/usr/bin/env PATH=/usr/local/bin:/usr/bin:/bin pnpm"));
        assertTrue(helper.contains("YARN) command=\"/usr/bin/env PATH=/usr/local/bin:/usr/bin:/bin yarn"));
        assertTrue(helper.contains("ecosystem_runtime_command_result=\"/usr/bin/env PATH=/usr/local/bin:/usr/bin:/bin php -S"));
        assertTrue(helper.contains("ecosystem_runtime_command_result=\"/usr/bin/env PATH=/usr/local/bin:/usr/bin:/bin php -n -S"));
        assertTrue(helper.contains("ecosystem_runtime_command_result=\"/usr/bin/env PATH=/usr/local/bin:/usr/bin:/bin bundle exec rackup"));
        assertTrue(helper.contains("ecosystem_runtime_command_result=\"/usr/bin/env PATH=/usr/local/bin:/usr/bin:/bin PORT=$4 ruby"));
        assertTrue(helper.contains("previous_kind=ordinary"));
        assertTrue(helper.contains("[ \"$previous_kind\" = deployment ] || [ \"$previous_kind\" = ordinary ]"));
        assertTrue(helper.contains("printf '%s\\n' \"$previous_kind\" > \"$snapshot/kind\""));
        assertTrue(helper.contains("tr -d '\\r' < \"$manifest/META-INF/MANIFEST.MF\" | grep -Eq"));
        assertTrue(helper.contains("if [ ! -e \"$candidate/.windowstolinux-owner\" ] && [ ! -L \"$candidate/.windowstolinux-owner\" ]; then"));
        assertTrue(helper.contains("[ ! -e \"$release/.windowstolinux-owner\" ] && [ ! -L \"$release/.windowstolinux-owner\" ] || reject release-exists"));
        assertTrue(helper.contains("[ \"$previous_path\" = \"$release\" ] && [ \"$previous_running\" -eq 1 ]"));
        assertTrue(helper.contains("ln -sfnT -- \"$previous\" \"$root/current\""));
        assertTrue(helper.contains("install -o root -g root -m 644 -- \"$snapshot/unit\" \"$unit\""));
        assertTrue(helper.contains("if [ \"$previous_runtime\" = active ]; then systemctl start"));
        assertFalse(helper.contains("  snapshot)"));
        assertFalse(helper.contains("  publish)"));
        assertFalse(helper.contains("  rollback)"));
        assertFalse(helper.contains("  install-unit)"));
        assertFalse(helper.contains("  daemon-reload)"));
    }
}
