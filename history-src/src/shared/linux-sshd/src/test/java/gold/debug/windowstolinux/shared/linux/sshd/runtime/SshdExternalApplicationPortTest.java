package gold.debug.windowstolinux.shared.linux.sshd.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.Test;

class SshdExternalApplicationPortTest {
    private static String encoded(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }
    private final String row = "APP\tSYSTEMD\t" + encoded("demo.service") + "\t" + "a".repeat(64) + "\t"
            + encoded("Demo") + "\tSTOPPED\t1\t1\t0\n";

    @Test
    void readsPartialScanButRejectsTruncationDuplicatesAndCommandsInIdentity() {
        var scan = SshdExternalApplicationPort.parse(row + "ISSUE\tDOCKER_PERMISSION\nEND\t1\n");
        assertEquals(1, scan.applications().size());
        assertEquals(1, scan.issues().size());
        for (String invalid : java.util.List.of(row, row + "END\t0\n", row + row + "END\t2\n", row + "END\t1\nextra\n",
                row.replace(encoded("demo.service"), encoded("demo.service;shutdown")) + "END\t1\n"))
            assertThrows(IllegalArgumentException.class, () -> SshdExternalApplicationPort.parse(invalid));
    }
}
