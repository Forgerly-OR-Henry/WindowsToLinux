package gold.debug.windowstolinux.shared.linux.sshd.protocol;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    }
}
