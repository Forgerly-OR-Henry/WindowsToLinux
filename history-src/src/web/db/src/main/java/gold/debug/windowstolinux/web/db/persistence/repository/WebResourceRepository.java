package gold.debug.windowstolinux.web.db.persistence.repository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import gold.debug.windowstolinux.web.db.entity.*;
import gold.debug.windowstolinux.web.db.persistence.mapper.*;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Provides workspace-scoped CRUD and atomic resource/configuration revisions.
 * <p>提供工作区限定的 CRUD 及原子资源和配置修订。
 */
@Repository
@Transactional
public class WebResourceRepository {
    /**
     * Access mode.
     * <p>访问模式。
     */
    private final ScopeAccess access;

    /**
     * Mappings.
     * <p>映射集合。
     */
    private final Map<ResourceType, ResourceMapping<?>> mappings;

    /**
     * The supplied web ai profile mapper.
     * <p>所提供的WebAI配置资料映射器。
     */
    private final WebAiProfileMapper ai;

    /**
     * Returns immutable preferences for the authorized workspace member.
     * <p>返回已授权工作区成员的不可变偏好设置。
     */
    private final WebPreferenceMapper preferences;

    /**
     * Configurations.
     * <p>配置集合。
     */
    private final WebConfigurationMapper configurations;

    /**
     * Binds the supplied dependencies and state for web resource repository.
     * <p>为Web资源仓库绑定传入的依赖及状态。
     *
     * @param access access mode / 访问模式
     * @param servers server-profile and authenticated-session service / 服务器资料及已认证会话服务
     * @param ai the supplied web ai profile mapper / 所提供的WebAI配置资料映射器
     * @param sources sources / 源码集合
     * @param applications applications / 应用集合
     * @param backups backups / 备份集合
     * @param preferences preferences / 偏好
     * @param configurations configurations / 配置集合
     */
    public WebResourceRepository(ScopeAccess access, WebServerMapper servers, WebAiProfileMapper ai,
            WebSourceMapper sources, WebApplicationMapper applications, WebBackupMapper backups,
            WebPreferenceMapper preferences, WebConfigurationMapper configurations) {
        this.access = access;
        this.ai = ai;
        this.preferences = preferences;
        this.configurations = configurations;
        mappings = Map.of(ResourceType.SERVER, new ResourceMapping<>(servers, WebServerEntity::new),
                ResourceType.AI_PROFILE, new ResourceMapping<>(ai, WebAiProfileEntity::new), ResourceType.SOURCE,
                new ResourceMapping<>(sources, WebSourceEntity::new), ResourceType.APPLICATION,
                new ResourceMapping<>(applications, WebApplicationEntity::new), ResourceType.BACKUP,
                new ResourceMapping<>(backups, WebBackupEntity::new));
    }

    /**
     * Lists list.
     * <p>列出列表。
     *
     * @param scope ownership or configuration scope of the operation / 操作的归属或配置作用域
     * @param type selected member of the supported type set / 受支持类型集合中的所选项
     * @return constructed or resolved list / 构造或解析得到的列表
     */
    public List<StoredResource> list(ResourceScope scope, ResourceType type) {
        access.require(scope);
        return listRows(mappings.get(type), scope);
    }

    /**
     * Finds optional.
     * <p>查找可选。
     *
     * @param scope ownership or configuration scope of the operation / 操作的归属或配置作用域
     * @param type selected member of the supported type set / 受支持类型集合中的所选项
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @return matching result, or empty when no admitted value exists / 匹配结果；不存在已准入内容时为空
     */
    public Optional<StoredResource> find(ResourceScope scope, ResourceType type, String id) {
        access.require(scope);
        ResourceScope.identifier(id);
        return findRow(mappings.get(type), scope, id);
    }

    /**
     * Persists stored resource.
     * <p>持久化已存储资源。
     *
     * @param scope ownership or configuration scope of the operation / 操作的归属或配置作用域
     * @param type selected member of the supported type set / 受支持类型集合中的所选项
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @param name human-readable name or diagnostic field label / 可读名称或诊断字段标签
     * @param attributes attributes / 属性
     * @param document document / 文档
     * @param expectedVersion expected version / 预期版本
     * @return constructed or resolved stored resource / 构造或解析得到的已存储资源
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    public StoredResource save(ResourceScope scope, ResourceType type, String id, String name,
            Map<String, Object> attributes, String document, long expectedVersion) {
        access.require(scope);
        ResourceScope.identifier(id);
        if (name == null || name.isBlank() || name.length() > 200 || document == null || document.length() > 1_048_576
                || expectedVersion < 0)
            throw new IllegalArgumentException("Invalid resource revision");
        if (type == ResourceType.SERVER && expectedVersion > 0
                && configurations.activeEndpointChange(scope, id, attributes) != 0)
            throw new OptimisticLockingFailureException("Server has an active task; retry after completion");
        String now = Instant.now().toString();
        saveRevision(mappings.get(type), scope, id, name, attributes, document, expectedVersion, now);
        if (type == ResourceType.APPLICATION)
            configurationRevision(scope, id, document, expectedVersion + 1, now);
        return find(scope, type, id).orElseThrow();
    }

    /**
     * Applies a scoped optimistic resource update and records its corresponding immutable revision atomically.
     * <p>原子应用限定作用域的乐观资源更新，并记录对应不可变修订。
     *
     * @param <T> type of the contract payload / 契约载荷的类型
     * @param mapping mapping / 映射
     * @param scope ownership or configuration scope of the operation / 操作的归属或配置作用域
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @param name human-readable name or diagnostic field label / 可读名称或诊断字段标签
     * @param attributes attributes / 属性
     * @param document document / 文档
     * @param version version of the relevant protocol, configuration or runtime / 相应协议、配置或运行时的版本
     * @param now current instant supplied by the caller or clock / 调用方或时钟提供的当前时刻
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    private <T extends WebResourceEntity> void saveRevision(ResourceMapping<T> mapping, ResourceScope scope, String id,
            String name, Map<String, Object> attributes, String document, long version, String now) {
        T row = mapping.factory().get();
        if (!attributes.keySet().equals(row.attributes().keySet()))
            throw new IllegalArgumentException("Unexpected resource fields");
        row.attributes(attributes);
        row.setWorkspaceId(scope.workspaceId());
        row.setId(id);
        row.setCreatedBy(scope.userId());
        row.setName(name);
        row.setDocument(document);
        row.setVersion(version + 1);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        int changed = version == 0 ? mapping.mapper().insert(row) : mapping.mapper().replace(row, version);
        if (changed != 1)
            throw new OptimisticLockingFailureException("Resource changed; refresh before saving");
    }

    /**
     * Appends a configuration revision only for a successful deployment graph, recording its SHA-256 identity.
     * <p>仅为成功部署图追加配置修订，并记录其 SHA-256 身份。
     *
     * @param scope ownership or configuration scope of the operation / 操作的归属或配置作用域
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @param document document / 文档
     * @param revision immutable configuration or secret revision number / 不可变配置或秘密修订号
     * @param now current instant supplied by the caller or clock / 调用方或时钟提供的当前时刻
     * @throws IllegalStateException if the required state or runtime facility is unavailable / 所需状态或运行设施不可用时
     */
    private void configurationRevision(ResourceScope scope, String id, String document, long revision, String now) {
        String graph = configurations.successfulGraph(document);
        if (graph == null)
            return;
        try {
            String digest = HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(graph.getBytes(StandardCharsets.UTF_8)));
            configurations.append(scope, id, revision, graph, digest, now);
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    /**
     * Deletes web resource.
     * <p>删除Web资源。
     *
     * @param scope ownership or configuration scope of the operation / 操作的归属或配置作用域
     * @param type selected member of the supported type set / 受支持类型集合中的所选项
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @param version version of the relevant protocol, configuration or runtime / 相应协议、配置或运行时的版本
     */
    public void delete(ResourceScope scope, ResourceType type, String id, long version) {
        access.require(scope);
        ResourceScope.identifier(id);
        int changed = deleteRow(mappings.get(type), scope, id, version);
        if (changed != 1)
            throw new OptimisticLockingFailureException("Resource changed; refresh before deleting");
    }

    /**
     * Reorders AI analysis.
     * <p>重新排序AI 分析。
     *
     * @param scope ownership or configuration scope of the operation / 操作的归属或配置作用域
     * @param ids ids / 标识集合
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    public void reorderAi(ResourceScope scope, List<String> ids) {
        access.require(scope);
        if (ids.size() > 1000 || ids.stream().distinct().count() != ids.size())
            throw new IllegalArgumentException("Invalid model order");
        var existing = ai.selectList(new QueryWrapper<WebAiProfileEntity>().eq("workspace_id", scope.workspaceId()))
                .stream().map(WebAiProfileEntity::getId).collect(java.util.stream.Collectors.toSet());
        if (!existing.equals(new HashSet<>(ids)))
            throw new OptimisticLockingFailureException("Model list changed");
        for (int index = 0; index < ids.size(); index++) {
            int changed = ai.update(new UpdateWrapper<WebAiProfileEntity>().eq("workspace_id", scope.workspaceId())
                    .eq("id", ids.get(index)).set("priority", index).set("updated_at", Instant.now().toString())
                    .setSql("version=version+1"));
            if (changed != 1)
                throw new OptimisticLockingFailureException("Model list changed");
        }
    }

    /**
     * Returns immutable preferences for the authorized workspace member.
     * <p>返回已授权工作区成员的不可变偏好设置。
     *
     * @param scope ownership or configuration scope of the operation / 操作的归属或配置作用域
     * @return immutable preferences for the authorized workspace member / 已授权工作区成员的不可变偏好设置
     */
    public Map<String, String> preferences(ResourceScope scope) {
        access.require(scope);
        var result = new LinkedHashMap<String, String>();
        preferences.selectList(new QueryWrapper<WebPreferenceEntity>().eq("workspace_id", scope.workspaceId())
                .eq("user_id", scope.userId())).forEach(row -> result.put(row.name(), row.value()));
        return Map.copyOf(result);
    }

    /**
     * Persists preferences.
     * <p>持久化偏好。
     *
     * @param scope ownership or configuration scope of the operation / 操作的归属或配置作用域
     * @param values ordered contents supplied to the current conversion or validation / 提供给当前转换或校验的有序内容
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    public void savePreferences(ResourceScope scope, Map<String, String> values) {
        access.require(scope);
        for (var entry : values.entrySet()) {
            if (!Set.of("theme", "language", "navigationCollapsed").contains(entry.getKey()) || entry.getValue() == null
                    || entry.getValue().length() > 30)
                throw new IllegalArgumentException("Invalid preference");
            preferences.save(new WebPreferenceEntity(scope.workspaceId(), scope.userId(), entry.getKey(),
                    entry.getValue(), Instant.now().toString()));
        }
    }

    /**
     * Associates a resource kind with its mapper and persistence entity factory.
     * <p>将资源类型与其映射器及持久化实体工厂关联。
     *
     * @param <T> type of the contract payload / 契约载荷的类型
     * @param mapper mapper / 映射器
     * @param factory factory / 工厂
     */
    private record ResourceMapping<T extends WebResourceEntity>(ResourceRevisionMapper<T> mapper, Supplier<T> factory) {
    }

    /**
     * Lists rows.
     * <p>列出数据行集合。
     *
     * @param <T> type of the contract payload / 契约载荷的类型
     * @param mapping mapping / 映射
     * @param scope ownership or configuration scope of the operation / 操作的归属或配置作用域
     * @return constructed or resolved list / 构造或解析得到的列表
     */
    private static <T extends WebResourceEntity> List<StoredResource> listRows(ResourceMapping<T> mapping,
            ResourceScope scope) {
        return mapping
                .mapper().selectList(new QueryWrapper<T>().eq("workspace_id", scope.workspaceId())
                        .orderByDesc("created_at").orderByAsc("id").last("LIMIT 1000"))
                .stream().map(WebResourceEntity::stored).toList();
    }

    /**
     * Finds row.
     * <p>查找数据行。
     *
     * @param <T> type of the contract payload / 契约载荷的类型
     * @param mapping mapping / 映射
     * @param scope ownership or configuration scope of the operation / 操作的归属或配置作用域
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @return matching result, or empty when no admitted value exists / 匹配结果；不存在已准入内容时为空
     */
    private static <T extends WebResourceEntity> Optional<StoredResource> findRow(ResourceMapping<T> mapping,
            ResourceScope scope, String id) {
        return Optional
                .ofNullable(mapping.mapper()
                        .selectOne(new QueryWrapper<T>().eq("workspace_id", scope.workspaceId()).eq("id", id)))
                .map(WebResourceEntity::stored);
    }

    /**
     * Deletes row.
     * <p>删除数据行。
     *
     * @param <T> type of the contract payload / 契约载荷的类型
     * @param mapping mapping / 映射
     * @param scope ownership or configuration scope of the operation / 操作的归属或配置作用域
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @param version version of the relevant protocol, configuration or runtime / 相应协议、配置或运行时的版本
     * @return delete row as a numeric result / 删除数据行的数值结果
     */
    private static <T extends WebResourceEntity> int deleteRow(ResourceMapping<T> mapping, ResourceScope scope,
            String id, long version) {
        return mapping.mapper().delete(
                new QueryWrapper<T>().eq("workspace_id", scope.workspaceId()).eq("id", id).eq("version", version));
    }
}
