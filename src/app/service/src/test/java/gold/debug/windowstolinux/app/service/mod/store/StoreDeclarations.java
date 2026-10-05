package gold.debug.windowstolinux.app.service.mod.store;

import java.net.URI;
import java.util.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.app.mod.config.ManifestReader;
import gold.debug.windowstolinux.app.mod.version.SemanticVersion;
import gold.debug.windowstolinux.app.service.mod.store.ModStoreSelection.*;

/** Strict author declarations for finite pure-data counterexamples. / 有限纯数据反例的严格作者声明。 */
final class StoreDeclarations {
    static final SemanticVersion API = SemanticVersion.parse("1.0.0-dev.4");

    static final URI BASE = URI.create("http://127.0.0.1:12345/store/");

    private StoreDeclarations() {
    }

    static ModStoreGateway.Release release(String owner, String version, Map<String, String> required,
                                           Map<String, String> optional, int javaVersion) throws Exception {
        var dependencies = new ArrayList<Map<String, Object>>();
        required.forEach((id, range) -> dependencies.add(Map.of("id", id, "versionRange", range, "optional", false)));
        optional.forEach((id, range) -> dependencies.add(Map.of("id", id, "versionRange", range, "optional", true)));
        var json = new LinkedHashMap<String, Object>();
        json.put("formatVersion", 1);
        json.put("id", owner);
        json.put("name", owner);
        json.put("version", version);
        json.put("platformApiRange", "[1.0.0-dev.1,2.0.0)");
        json.put("minJavaVersion", javaVersion);
        json.put("mainJar", "main/fixture.jar");
        json.put("entryClass", owner + ".Mod");
        json.put("authors", List.of("fixture"));
        json.put("dependencies", dependencies);
        json.put("exports", List.of());
        var manifest = ManifestReader.read(new ObjectMapper().writeValueAsBytes(json));
        return new ModStoreGateway.Release(manifest, URI.create(owner + "-" + version + ".wtlmod"), 4,
            ModStoreProtocol.hash("body".getBytes(java.nio.charset.StandardCharsets.UTF_8)), Map.of());
    }

    static ModStoreGateway.Release release(String owner, String version, Map<String, String> required) throws Exception {
        return release(owner, version, required, Map.of(), 21);
    }

    static ModStoreCatalog catalog(ModStoreGateway.Release... releases) {
        return new ModStoreCatalog(1, BASE, List.of(releases), List.of(), List.of());
    }

    static ModStorePlan solve(ModStoreCatalog catalog, String root, Set<OptionalKey> optionals, Map<String, String> pins) throws Exception {
        return new ModStoreResolver(catalog, new ModStoreSelection(new ReleaseKey(root, "1.0.0"), optionals, pins),
            API, 21, () -> false, 100000).resolve(2);
    }

    static String selected(ModStorePlan plan, String owner) {
        return plan.items().stream().filter(item -> item.key().owner().equals(owner)).findFirst().orElseThrow().key().version();
    }

    static byte[] wire(ModStoreGateway.Release release) throws Exception {
        var json = new ObjectMapper();
        var node = new LinkedHashMap<String, Object>();
        node.put("manifest", json.readTree(release.manifest().json()));
        node.put("packageUri", release.packageUri().toString());
        node.put("bytes", release.bytes());
        node.put("sha256", release.sha256());
        node.put("purposes", release.purposes());
        return json.writeValueAsBytes(Map.of("formatVersion", 1, "releases", List.of(node)));
    }
}
