package gold.debug.windowstolinux.mod.deployment.linux;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** Checks actual helper result shape and byte frame ownership. / 核对真实 helper 结果形状和字节帧所有权。 */
class DeploymentNativeDatabaseResultsTest {
    /** Rejects ambiguous inventories and malformed target ownership. / 拒绝歧义库存及畸形目标归属。 */
    @Test
    void exactInventoryAndTargetFacts() {
        var fields = new LinkedHashMap<String, String>();
        fields.put("engine", "POSTGRESQL");
        fields.put("instanceCount", "1");
        fields.put("conflictCount", "0");
        instance(fields, "instance.0.");
        fields.put("package", "postgresql-16");
        fields.put("packageVersion", "16.3-1");
        fields.put("engineVersion", "16.3");
        var inventory = DeploymentNativeDatabaseResults.inventory(fields, "");
        assertEquals("16.3", inventory.candidate().orElseThrow().engineVersion());
        assertEquals("main", inventory.instances().getFirst().id());
        fields.put("instanceCount", "2");
        instance(fields, "instance.1.");
        assertThrows(IllegalArgumentException.class, () -> DeploymentNativeDatabaseResults.inventory(fields, ""));
        fields.put("instanceCount", "1");
        instance(fields, "instance.");
        fields.put("applicationId", "app");
        fields.put("database", "db");
        fields.put("username", "user");
        fields.put("exists", "false");
        fields.put("empty", "true");
        fields.put("ownershipToken", "");
        fields.put("initialization", "UNOWNED");
        fields.put("initializedSourceSha256", "");
        assertFalse(DeploymentNativeDatabaseResults.target(fields, "").exists());
        fields.put("empty", "false");
        assertThrows(IllegalArgumentException.class, () -> DeploymentNativeDatabaseResults.target(fields, ""));
        fields.put("empty", "true");
        fields.put("ownershipToken", "not-a-digest");
        assertThrows(IllegalArgumentException.class, () -> DeploymentNativeDatabaseResults.target(fields, ""));
    }

    /** A sensitive UTF-8 value reaches the exact helper frame while the borrowed input stays caller-owned. / 敏感 UTF-8 值进入精确 helper 帧且借用输入仍由调用方持有。 */
    @Test
    void fixedSensitiveFrameMatchesJdkBase64WithoutChangingBorrowedBytes() {
        byte[] borrowed = "密碼✓".getBytes(StandardCharsets.UTF_8);
        byte[] original = borrowed.clone();
        byte[] frame = DeploymentNativeDatabaseWire.sensitiveFrame(Map.of("engine", "POSTGRESQL"),
                Map.of("adminPassword", borrowed));
        try {
            String expected = "engine="
                    + Base64.getEncoder().encodeToString("POSTGRESQL".getBytes(StandardCharsets.UTF_8))
                    + "\nadminPassword=" + Base64.getEncoder().encodeToString(original) + "\n";
            assertArrayEquals(expected.getBytes(StandardCharsets.US_ASCII), frame);
            assertArrayEquals(original, borrowed);
        } finally {
            Arrays.fill(frame, (byte) 0);
            Arrays.fill(borrowed, (byte) 0);
            Arrays.fill(original, (byte) 0);
        }
        byte[] tooLarge = new byte[3 * 1024 * 1024];
        assertThrows(IllegalArgumentException.class,
                () -> DeploymentNativeDatabaseWire.sensitiveFrame(Map.of(), Map.of("sql", tooLarge)));
    }

    /** Fills the helper's exact observed instance fields. / 填写 helper 精确观测实例字段。
     * @param fields destination fields / 目标字段
     * @param prefix instance prefix / 实例前缀 */
    private static void instance(Map<String, String> fields, String prefix) {
        fields.put(prefix + "engine", "POSTGRESQL");
        fields.put(prefix + "id", "main");
        fields.put(prefix + "version", "16.3");
        fields.put(prefix + "port", "5432");
        fields.put(prefix + "service", "postgresql.service");
        fields.put(prefix + "dataDirectory", "/var/lib/postgresql/main");
        fields.put(prefix + "fingerprint", "a".repeat(64));
        fields.put(prefix + "running", "true");
    }
}
