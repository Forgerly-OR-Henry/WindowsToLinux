package gold.debug.windowstolinux.web.service.server;

import java.time.Instant;
import java.util.*;

import gold.debug.windowstolinux.shared.linux.connection.*;
import gold.debug.windowstolinux.shared.linux.session.DeploymentRemoteSession;
import gold.debug.windowstolinux.shared.model.server.ServerIdentity;
import gold.debug.windowstolinux.web.db.entity.*;
import gold.debug.windowstolinux.web.db.persistence.repository.WebResourceRepository;
import gold.debug.windowstolinux.web.secret.credential.WebCredentialStore;
import gold.debug.windowstolinux.web.service.contract.*;
import gold.debug.windowstolinux.web.service.contract.validation.WebRequestValidator;
import gold.debug.windowstolinux.web.service.persistence.serialization.WebJsonCodec;
import tools.jackson.databind.JsonNode;

/**
 * Manages scoped server profiles, exact credential retention, first-use trust and fresh remote observations.
 * <p>管理限定作用域服务器配置、精确凭据保留、首次使用信任及新鲜远端观测。
 */
public final class WebServerService {
    /**
     * Bound web resource repository collaborator for persistence boundary for the owned records.
     * <p>处理所属记录的持久化边界的Web资源仓库协作对象。
     */
    private final WebResourceRepository repository;

    /**
     * Bound web credential store collaborator for credential references or scoped secret-access service.
     * <p>处理凭据引用或限定作用域的秘密访问服务的Web凭据存储协作对象。
     */
    private final WebCredentialStore secrets;

    /**
     * Factory for authenticated Linux sessions.
     * <p>已认证 Linux 会话的工厂。
     */
    private final DeploymentLinuxGateway gateway;
    /**
     * Binds the supplied dependencies and state for web server service.
     * <p>为Web服务器服务绑定传入的依赖及状态。
     *
     * @param repository persistence boundary for the owned records / 所属记录的持久化边界
     * @param secrets credential references or scoped secret-access service / 凭据引用或限定作用域的秘密访问服务
     * @param gateway factory for authenticated Linux sessions / 已认证 Linux 会话的工厂
     */
    public WebServerService(WebResourceRepository repository, WebCredentialStore secrets,
            DeploymentLinuxGateway gateway) {
        this.repository = repository;
        this.secrets = secrets;
        this.gateway = gateway;
    }

    /**
     * Lists json node.
     * <p>列出JSON节点。
     *
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @return constructed or resolved json node / 构造或解析得到的JSON节点
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    public JsonNode list(WebRequestContext context) throws Exception {
        return WebJsonCodec.tree(
                repository.list(scope(context), ResourceType.SERVER).stream().map(WebServerService::view).toList());
    }

    /**
     * Validates scoped server metadata and credential-retention rules before saving the optimistic resource revision.
     * <p>保存乐观资源修订前校验限定作用域服务器元数据及凭据保留规则。
     *
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @param input source content consumed by this operation / 当前操作消费的源内容
     * @return constructed or resolved json node / 构造或解析得到的JSON节点
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws IllegalStateException if the required state or runtime facility is unavailable / 所需状态或运行设施不可用时
     */
    public JsonNode save(WebRequestContext context, String id, JsonNode input) throws Exception {
        WebRequestValidator.fields(input, "name", "host", "port", "username", "password", "version");
        if (id == null)
            id = UUID.randomUUID().toString();
        String name = WebRequestValidator.text(input, "name", 200);
        var endpoint = new SshEndpoint(id, WebRequestValidator.text(input, "host", 253), input.path("port").asInt(22),
                WebRequestValidator.text(input, "username", 128));
        if (!endpoint.host().matches("[A-Za-z0-9.:%_-]+")
                || endpoint.username().chars().anyMatch(Character::isWhitespace))
            throw new IllegalArgumentException("Invalid server endpoint");
        var previous = repository.find(scope(context), ResourceType.SERVER, id);
        long version = input.path("version").asLong(0);
        if (previous.isPresent() && previous.orElseThrow().version() != version)
            throw new IllegalStateException("Server changed; refresh before saving");
        boolean same = previous.isPresent() && endpoint(previous.orElseThrow()).equals(endpoint);
        String password = input.path("password").asText("");
        String secret;
        if (password.isEmpty()) {
            if (!same)
                throw new IllegalArgumentException("A password is required for a new server endpoint");
            secret = (String) previous.orElseThrow().attributes().get("secret_id");
        } else
            secret = secrets.save(scope(context), "ssh", password.toCharArray());
        var fields = new LinkedHashMap<String, Object>();
        fields.put("host", endpoint.host());
        fields.put("port", endpoint.port());
        fields.put("username", endpoint.username());
        fields.put("fingerprint", same ? previous.orElseThrow().attributes().get("fingerprint") : null);
        fields.put("secret_id", secret);
        fields.put("secret_version", 1);
        String document = same ? previous.orElseThrow().document() : "{}";
        return view(repository.save(scope(context), ResourceType.SERVER, id, name, fields, document, version));
    }

    /**
     * Deletes web server.
     * <p>删除Web服务器。
     *
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @param version version of the relevant protocol, configuration or runtime / 相应协议、配置或运行时的版本
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    public void delete(WebRequestContext context, String id, long version) throws Exception {
        repository.delete(scope(context), ResourceType.SERVER, id, version);
    }

    /**
     * Builds prepared web operation from the supplied probe inputs.
     * <p>根据所提供探测输入构建已准备Web操作。
     *
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @return prepared web operation from the supplied probe inputs / 根据所提供探测输入构建已准备Web操作
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    public PreparedWebOperation probe(WebRequestContext context, String id) throws Exception {
        StoredResource server = require(context, id);
        return new PreparedWebOperation("SERVER_PROBE", WebJsonCodec.object().put("serverId", id), List.of(id),
                List.of(lockKey(server)), false, null, null, null, interaction -> {
                    try {
                        var facts = withSession(context, id, interaction,
                                DeploymentRemoteSession::collectDeploymentCapabilities);
                        var result = WebJsonCodec.object().put("connected", true)
                                .put("observedAt", Instant.now().toString())
                                .put("operatingSystem", facts.distro().name() + " " + facts.version())
                                .put("architecture", facts.architecture());
                        observation(context, id, result);
                        return result;
                    } catch (Exception failure) {
                        observation(context, id, WebJsonCodec.object().put("connected", false)
                                .put("observedAt", Instant.now().toString()).put("operatingSystem", "UNKNOWN"));
                        throw failure;
                    }
                });
    }

    /**
     * Runs scoped work while owning the authenticated remote session's cleanup.
     * <p>执行限定作用域工作，并负责已认证远端会话的清理。
     *
     * @param <T> type of the contract payload / 契约载荷的类型
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @param interaction caller-owned progress, confirmation and input callbacks / 调用方持有的进度、确认及输入回调
     * @param action explicit action selected for the current target / 为当前目标显式选择的动作
     * @return constructed or resolved T / 构造或解析得到的T
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    public <T> T withSession(WebRequestContext context, String id, TaskInteraction interaction, SessionAction<T> action)
            throws Exception {
        return withCredential(context, id, interaction, (endpoint, credential, verifier) -> {
            try (var session = gateway.connect(endpoint, credential, verifier)) {
                return action.execute(session);
            }
        });
    }

    /**
     * Returns the contract with the supplied credential applied.
     * <p>返回应用所提供凭据后的契约。
     *
     * @param <T> type of the contract payload / 契约载荷的类型
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @param interaction caller-owned progress, confirmation and input callbacks / 调用方持有的进度、确认及输入回调
     * @param action explicit action selected for the current target / 为当前目标显式选择的动作
     * @return the contract with the supplied credential applied / 应用所提供凭据后的契约
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    public <T> T withCredential(WebRequestContext context, String id, TaskInteraction interaction,
            ConnectionAction<T> action) throws Exception {
        StoredResource server = require(context, id);
        SshEndpoint endpoint = endpoint(server);
        String secretId = (String) server.attributes().get("secret_id");
        var verifier = verifier(context, server, interaction);
        return secrets.use(scope(context), secretId, "ssh", value -> {
            interaction.checkCancelled();
            var credential = new SshCredential.Password(value);
            try {
                return action.execute(endpoint, credential, verifier);
            } finally {
                credential.clear();
            }
        });
    }

    /**
     * Validates and returns stored resource.
     * <p>校验并返回已存储资源。
     *
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @return constructed or resolved stored resource / 构造或解析得到的已存储资源
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    public StoredResource require(WebRequestContext context, String id) throws Exception {
        return repository.find(scope(context), ResourceType.SERVER, id)
                .orElseThrow(() -> new NoSuchElementException("Server not found"));
    }

    /**
     * Builds server identity from the supplied identity inputs.
     * <p>根据所提供身份输入构建服务器身份。
     *
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @return server identity from the supplied identity inputs / 根据所提供身份输入构建服务器身份
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    public ServerIdentity identity(WebRequestContext context, String id) throws Exception {
        var server = require(context, id);
        var endpoint = endpoint(server);
        return new ServerIdentity(id, endpoint.host(), endpoint.port(),
                (String) server.attributes().get("fingerprint"));
    }

    /**
     * Returns factory for authenticated Linux sessions.
     * <p>返回已认证 Linux 会话的工厂。
     *
     * @return factory for authenticated Linux sessions / 已认证 Linux 会话的工厂
     */
    public DeploymentLinuxGateway gateway() {
        return gateway;
    }

    /**
     * Normalizes the server host and port into the shared operation-lock key.
     * <p>将服务器主机及端口规范化为共享操作锁键。
     *
     * @param server server identity or selected server configuration / 服务器身份或所选服务器配置
     * @return lock key text / 锁键文本
     */
    public static String lockKey(StoredResource server) {
        return server.attributes().get("host").toString().toLowerCase(Locale.ROOT) + ":"
                + server.attributes().get("port");
    }

    /**
     * Builds ssh endpoint from the supplied endpoint inputs.
     * <p>根据所提供端点输入构建SSH端点。
     *
     * @param server server identity or selected server configuration / 服务器身份或所选服务器配置
     * @return ssh endpoint from the supplied endpoint inputs / 根据所提供端点输入构建SSH端点
     */
    public static SshEndpoint endpoint(StoredResource server) {
        return new SshEndpoint(server.id(), (String) server.attributes().get("host"),
                ((Number) server.attributes().get("port")).intValue(), (String) server.attributes().get("username"));
    }

    /**
     * Persists new server observation data against the current optimistic resource version.
     * <p>根据当前乐观资源版本持久化新的服务器观测数据。
     *
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @param observation observation / 观测
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    private void observation(WebRequestContext context, String id, JsonNode observation) throws Exception {
        var current = require(context, id);
        repository.save(scope(context), ResourceType.SERVER, id, current.name(), current.attributes(),
                WebJsonCodec.write(observation), current.version());
    }

    /**
     * Builds a host-key verifier that rechecks the saved endpoint, requests first-use trust and persists only the authenticated accepted fingerprint.
     * <p>构建主机密钥验证器，复核保存的端点、请求首次使用信任，并仅持久化已认证且已接受的指纹。
     *
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @param captured captured / 已捕获
     * @param interaction caller-owned progress, confirmation and input callbacks / 调用方持有的进度、确认及输入回调
     * @return stateful verifier bound to the scoped server revision and task decisions / 绑定限定作用域服务器修订及任务决策的有状态验证器
     */
    private HostKeyEvaluator verifier(WebRequestContext context, StoredResource captured, TaskInteraction interaction) {
        return new HostKeyEvaluator() {
            /**
             * Accepted.
             * <p>已接受。
             */
            private String accepted;
            /**
             * Rejects endpoint or credential drift, accepts matching pinned keys and requests explicit trust for a first-use fingerprint.
             * <p>拒绝端点或凭据漂移、接受匹配的固定密钥，并为首次使用指纹请求显式信任。
             *
             * @param endpoint reviewed network endpoint / 已审阅网络端点
             * @param fingerprint pinned or freshly observed host-key fingerprint / 固定或新近观测的主机密钥指纹
             * @return constructed or resolved host key decision / 构造或解析得到的主机键决定
             */
            @Override
            public HostKeyDecision verify(SshEndpoint endpoint, String fingerprint) {
                try {
                    var current = require(context, captured.id());
                    if (!endpoint(current).equals(endpoint) || !Objects.equals(current.attributes().get("secret_id"),
                            captured.attributes().get("secret_id")))
                        return HostKeyDecision.REJECT;
                    String known = (String) current.attributes().get("fingerprint");
                    if (known != null)
                        return known.equals(fingerprint) ? HostKeyDecision.ACCEPT_EXISTING : HostKeyDecision.REJECT;
                    if (!fingerprint.matches("SHA256:[A-Za-z0-9+/=]{10,100}"))
                        return HostKeyDecision.REJECT;
                    JsonNode answer = interaction.decide("HOST_KEY",
                            WebJsonCodec.object().put("serverId", captured.id()).put("host", endpoint.host())
                                    .put("fingerprint", fingerprint));
                    WebRequestValidator.fields(answer, "accepted");
                    if (!answer.path("accepted").asBoolean(false))
                        return HostKeyDecision.REJECT;
                    accepted = fingerprint;
                    return HostKeyDecision.ACCEPT_FIRST_USE;
                } catch (Exception failure) {
                    if (failure instanceof InterruptedException)
                        Thread.currentThread().interrupt();
                    return HostKeyDecision.REJECT;
                }
            }

            /**
             * Tests the authenticated predicate against the supplied evidence.
             * <p>根据所提供证据检查已认证条件。
             *
             * @param endpoint reviewed network endpoint / 已审阅网络端点
             * @param observation observation / 观测
             * @return true when authenticated predicate against the supplied evidence, false otherwise / 根据所提供证据检查已认证条件时为 true，否则为 false
             */
            @Override
            public boolean authenticated(SshEndpoint endpoint, HostKeyObservation observation) {
                try {
                    var current = require(context, captured.id());
                    if (!endpoint(current).equals(endpoint) || !Objects.equals(current.attributes().get("secret_id"),
                            captured.attributes().get("secret_id")))
                        return false;
                    String known = (String) current.attributes().get("fingerprint");
                    if (known != null)
                        return known.equals(observation.sshSha256());
                    if (!Objects.equals(accepted, observation.sshSha256()))
                        return false;
                    var fields = new LinkedHashMap<>(current.attributes());
                    fields.put("fingerprint", accepted);
                    repository.save(scope(context), ResourceType.SERVER, current.id(), current.name(), fields,
                            current.document(), current.version());
                    return true;
                } catch (Exception failure) {
                    return false;
                }
            }
        };
    }

    /**
     * Projects stored non-secret resource metadata into its Web response fields.
     * <p>将持久化的非秘密资源元数据投影为 Web 响应字段。
     *
     * @param server server identity or selected server configuration / 服务器身份或所选服务器配置
     * @return constructed or resolved json node / 构造或解析得到的JSON节点
     */
    private static JsonNode view(StoredResource server) {
        var fields = server.attributes();
        var result = WebJsonCodec.object().put("id", server.id()).put("name", server.name())
                .put("version", server.version()).put("host", (String) fields.get("host"))
                .put("port", ((Number) fields.get("port")).intValue()).put("username", (String) fields.get("username"))
                .put("fingerprint", (String) fields.get("fingerprint"))
                .put("credentialConfigured", fields.get("secret_id") != null);
        result.set("observation", WebJsonCodec.read(server.document()));
        return result;
    }

    /**
     * Resolves the ownership scope supplied by the trusted caller.
     * <p>解析可信调用方提供的归属作用域。
     *
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @return the ownership scope supplied by the trusted caller / 可信调用方提供的归属作用域
     */
    private static ResourceScope scope(WebRequestContext context) {
        return new ResourceScope(context.workspaceId(), context.userId());
    }
    /**
     * Uses an authenticated deployment session within the server service's resource scope.
     * <p>在服务器服务的资源作用域内使用已认证部署会话。
     *
     * @param <T> type of the contract payload / 契约载荷的类型
     */
    @FunctionalInterface
    public interface SessionAction<T> {
        /**
         * Executes T.
         * <p>执行T。
         *
         * @param session session used for the current scoped operation / 当前限定作用域操作使用的会话
         * @return constructed or resolved T / 构造或解析得到的T
         * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
         */
        T execute(DeploymentRemoteSession session) throws Exception;
    }

    /**
     * Uses an authenticated Linux connection within the server service's resource scope.
     * <p>在服务器服务的资源作用域内使用已认证 Linux 连接。
     *
     * @param <T> type of the contract payload / 契约载荷的类型
     */
    @FunctionalInterface
    public interface ConnectionAction<T> {
        /**
         * Executes T.
         * <p>执行T。
         *
         * @param endpoint reviewed network endpoint / 已审阅网络端点
         * @param credential authentication material scoped to the current connection / 限定于当前连接的认证素材
         * @param verifier verifier / 验证器
         * @return constructed or resolved T / 构造或解析得到的T
         * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
         */
        T execute(SshEndpoint endpoint, SshCredential credential, HostKeyEvaluator verifier) throws Exception;
    }
}
