package gold.debug.windowstolinux.app.ui.mod;

import java.util.*;

import gold.debug.windowstolinux.app.service.mod.store.ModStoreGateway;

/** Groups catalog cards without changing exact resolver releases. / 聚合目录卡片，不改变解析器精确发布。 */
final class ModStoreCatalogRows {
    private ModStoreCatalogRows() {
    }

    static List<ModStoreGateway.Release> owners(List<ModStoreGateway.Release> releases, String keyword) {
        var groups = new LinkedHashMap<String, List<ModStoreGateway.Release>>();
        releases.forEach(release -> groups.computeIfAbsent(release.owner(), ignored -> new ArrayList<>()).add(release));
        String query = keyword.toLowerCase(Locale.ROOT);
        return groups.values().stream().filter(versions -> versions.stream().anyMatch(release ->
                (release.title() + " " + release.owner() + " " + release.version()).toLowerCase(Locale.ROOT).contains(query)))
            .map(versions -> versions.stream().max(Comparator.comparing((ModStoreGateway.Release release) -> release.manifest().version())
                .thenComparing(ModStoreGateway.Release::version)).orElseThrow()).toList();
    }
}
