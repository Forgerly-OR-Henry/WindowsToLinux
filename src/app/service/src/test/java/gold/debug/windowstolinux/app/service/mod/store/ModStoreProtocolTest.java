package gold.debug.windowstolinux.app.service.mod.store;

import static org.junit.jupiter.api.Assertions.*;
import static gold.debug.windowstolinux.app.service.mod.store.StoreDeclarations.*;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ModStoreProtocolTest {
    @Test
    void completeStrictCatalogPreservesAllManifestFields() throws Exception {
        var release = release("root", "1.0.0", Map.of("provider", "[1.0.0,2.0.0)"));
        var actual = ModStoreProtocol.read(wire(release));
        assertEquals(1, actual.releases().size());
        assertTrue(ModStoreFacts.same(release.manifest(), actual.releases().getFirst().manifest()));
        assertEquals(64, actual.sha256().length());
    }

    @Test
    void overflowDuplicateBomInvalidUtf8TrailingAndUnknownFieldsAreRejected() throws Exception {
        String valid = new String(wire(release("root", "1.0.0", Map.of())), StandardCharsets.UTF_8);
        for (var invalid : new String[]{valid.replace("\"formatVersion\":1", "\"formatVersion\":4294967297"),
            valid.replace("\"formatVersion\":1", "\"formatVersion\":1,\"formatVersion\":1"),
            "\ufeff" + valid, valid + " {}", valid.replace("\"formatVersion\":1", "\"unknown\":1,\"formatVersion\":1")})
            assertThrows(IOException.class, () -> ModStoreProtocol.read(invalid.getBytes(StandardCharsets.UTF_8)), invalid);
        assertThrows(IOException.class, () -> ModStoreProtocol.read(new byte[]{(byte) 0xc3, (byte) 0x28}));
        assertThrows(IOException.class, () -> ModStoreProtocol.read(new byte[ModStoreProtocol.MAX_CATALOG_BYTES + 1]));
    }

    @Test
    void duplicateExactReleasesAndTooManyReleasesAreRejected() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var catalog = mapper.readTree(wire(release("root", "1.0.0", Map.of())));
        var releases = (com.fasterxml.jackson.databind.node.ArrayNode) catalog.path("releases");
        var first = releases.get(0);
        releases.add(first);
        assertThrows(IOException.class, () -> ModStoreProtocol.read(mapper.writeValueAsBytes(catalog)));
        while (releases.size() <= 1000) releases.add(first);
        assertThrows(IOException.class, () -> ModStoreProtocol.read(mapper.writeValueAsBytes(catalog)));
    }

    @Test
    void traversalAuthorityEncodedPathsAndNonPackagesAreRejectedBeforeRequests() {
        for (String uri : new String[]{"../x.wtlmod", "/x.wtlmod", "//other/x.wtlmod", "https://other/x.wtlmod",
            "a/%2e%2e/x.wtlmod", "a%252fb.wtlmod", "a.wtlmod?query", "a.wtlmod#fragment", "a/./b.wtlmod",
            "a//b.wtlmod", "a%00.wtlmod", "a.zip"})
            assertThrows(IllegalArgumentException.class, () -> ModStoreProtocol.relative(URI.create(uri)), uri);
        ModStoreProtocol.relative(URI.create("packages/root-1.2.3.wtlmod"));
    }
}
