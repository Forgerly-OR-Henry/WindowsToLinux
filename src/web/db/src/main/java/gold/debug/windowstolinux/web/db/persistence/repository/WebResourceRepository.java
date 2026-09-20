package gold.debug.windowstolinux.web.db.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import gold.debug.windowstolinux.web.db.entity.*;
import gold.debug.windowstolinux.web.db.persistence.mapper.*;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;

/** Workspace-scoped CRUD and atomic resource/configuration revisions. */
@Repository
@Transactional
public class WebResourceRepository {
    private final ScopeAccess access;
    private final Map<ResourceType, ResourceMapping<?>> mappings;
    private final WebAiProfileMapper ai;
    private final WebPreferenceMapper preferences;
    private final WebConfigurationMapper configurations;

    public WebResourceRepository(ScopeAccess access, WebServerMapper servers, WebAiProfileMapper ai,
                                 WebSourceMapper sources, WebApplicationMapper applications, WebBackupMapper backups,
                                 WebPreferenceMapper preferences, WebConfigurationMapper configurations) {
        this.access = access; this.ai = ai; this.preferences = preferences; this.configurations = configurations;
        mappings = Map.of(ResourceType.SERVER, new ResourceMapping<>(servers, WebServerEntity::new),
                ResourceType.AI_PROFILE, new ResourceMapping<>(ai, WebAiProfileEntity::new),
                ResourceType.SOURCE, new ResourceMapping<>(sources, WebSourceEntity::new),
                ResourceType.APPLICATION, new ResourceMapping<>(applications, WebApplicationEntity::new),
                ResourceType.BACKUP, new ResourceMapping<>(backups, WebBackupEntity::new));
    }

    public List<StoredResource> list(ResourceScope scope, ResourceType type) {
        access.require(scope);
        return listRows(mappings.get(type), scope);
    }

    public Optional<StoredResource> find(ResourceScope scope, ResourceType type, String id) {
        access.require(scope); ResourceScope.identifier(id);
        return findRow(mappings.get(type), scope, id);
    }

    public StoredResource save(ResourceScope scope, ResourceType type, String id, String name,
                               Map<String,Object> attributes, String document, long expectedVersion) {
        access.require(scope); ResourceScope.identifier(id);
        if (name == null || name.isBlank() || name.length() > 200 || document == null
                || document.length() > 1_048_576 || expectedVersion < 0) throw new IllegalArgumentException("Invalid resource revision");
        if (type == ResourceType.SERVER && expectedVersion > 0 && configurations.activeEndpointChange(scope, id, attributes) != 0)
            throw new OptimisticLockingFailureException("Server has an active task; retry after completion");
        String now = Instant.now().toString();
        saveRevision(mappings.get(type), scope, id, name, attributes, document, expectedVersion, now);
        if (type == ResourceType.APPLICATION) configurationRevision(scope, id, document, expectedVersion + 1, now);
        return find(scope, type, id).orElseThrow();
    }

    private <T extends WebResourceEntity> void saveRevision(ResourceMapping<T> mapping, ResourceScope scope,
            String id, String name, Map<String,Object> attributes, String document, long version, String now) {
        T row = mapping.factory().get();
        if (!attributes.keySet().equals(row.attributes().keySet())) throw new IllegalArgumentException("Unexpected resource fields");
        row.attributes(attributes); row.setWorkspaceId(scope.workspaceId()); row.setId(id); row.setCreatedBy(scope.userId());
        row.setName(name); row.setDocument(document); row.setVersion(version + 1); row.setCreatedAt(now); row.setUpdatedAt(now);
        int changed = version == 0 ? mapping.mapper().insert(row) : mapping.mapper().replace(row, version);
        if (changed != 1) throw new OptimisticLockingFailureException("Resource changed; refresh before saving");
    }

    private void configurationRevision(ResourceScope scope, String id, String document, long revision, String now) {
        String graph = configurations.successfulGraph(document);
        if (graph == null) return;
        try {
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(graph.getBytes(StandardCharsets.UTF_8)));
            configurations.append(scope, id, revision, graph, digest, now);
        } catch (java.security.NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }

    public void delete(ResourceScope scope, ResourceType type, String id, long version) {
        access.require(scope); ResourceScope.identifier(id);
        int changed = deleteRow(mappings.get(type), scope, id, version);
        if (changed != 1) throw new OptimisticLockingFailureException("Resource changed; refresh before deleting");
    }

    public void reorderAi(ResourceScope scope, List<String> ids) {
        access.require(scope);
        if (ids.size() > 1000 || ids.stream().distinct().count() != ids.size()) throw new IllegalArgumentException("Invalid model order");
        var existing = ai.selectList(new QueryWrapper<WebAiProfileEntity>().eq("workspace_id", scope.workspaceId()))
                .stream().map(WebAiProfileEntity::getId).collect(java.util.stream.Collectors.toSet());
        if (!existing.equals(new HashSet<>(ids))) throw new OptimisticLockingFailureException("Model list changed");
        for (int index = 0; index < ids.size(); index++) {
            int changed = ai.update(new UpdateWrapper<WebAiProfileEntity>().eq("workspace_id", scope.workspaceId()).eq("id", ids.get(index))
                    .set("priority", index).set("updated_at", Instant.now().toString()).setSql("version=version+1"));
            if (changed != 1) throw new OptimisticLockingFailureException("Model list changed");
        }
    }

    public Map<String,String> preferences(ResourceScope scope) {
        access.require(scope);
        var result = new LinkedHashMap<String,String>();
        preferences.selectList(new QueryWrapper<WebPreferenceEntity>().eq("workspace_id", scope.workspaceId()).eq("user_id", scope.userId()))
                .forEach(row -> result.put(row.name(), row.value()));
        return Map.copyOf(result);
    }

    public void savePreferences(ResourceScope scope, Map<String,String> values) {
        access.require(scope);
        for (var entry : values.entrySet()) {
            if (!Set.of("theme", "language", "navigationCollapsed").contains(entry.getKey())
                    || entry.getValue() == null || entry.getValue().length() > 30) throw new IllegalArgumentException("Invalid preference");
            preferences.save(new WebPreferenceEntity(scope.workspaceId(), scope.userId(), entry.getKey(), entry.getValue(), Instant.now().toString()));
        }
    }

    private record ResourceMapping<T extends WebResourceEntity>(ResourceRevisionMapper<T> mapper, Supplier<T> factory) { }

    private static <T extends WebResourceEntity> List<StoredResource> listRows(ResourceMapping<T> mapping, ResourceScope scope) {
        return mapping.mapper().selectList(new QueryWrapper<T>().eq("workspace_id", scope.workspaceId())
                .orderByDesc("created_at").orderByAsc("id").last("LIMIT 1000")).stream().map(WebResourceEntity::stored).toList();
    }

    private static <T extends WebResourceEntity> Optional<StoredResource> findRow(ResourceMapping<T> mapping, ResourceScope scope, String id) {
        return Optional.ofNullable(mapping.mapper().selectOne(new QueryWrapper<T>()
                .eq("workspace_id", scope.workspaceId()).eq("id", id))).map(WebResourceEntity::stored);
    }

    private static <T extends WebResourceEntity> int deleteRow(ResourceMapping<T> mapping, ResourceScope scope, String id, long version) {
        return mapping.mapper().delete(new QueryWrapper<T>().eq("workspace_id", scope.workspaceId()).eq("id", id).eq("version", version));
    }
}
