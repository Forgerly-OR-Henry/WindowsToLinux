package gold.debug.windowstolinux.shared.deploy.input;

import gold.debug.windowstolinux.shared.config.contract.definition.ConfigurationScope;
import gold.debug.windowstolinux.shared.config.contract.definition.ConfigurationValue;
import gold.debug.windowstolinux.shared.config.resource.*;
import gold.debug.windowstolinux.shared.config.revision.ConfigurationEntry;
import gold.debug.windowstolinux.shared.config.revision.ConfigurationSnapshot;
import gold.debug.windowstolinux.shared.model.managed.ManagedStorageLocation;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;
import gold.debug.windowstolinux.shared.model.project.component.ComponentDataPath;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/**
 * Resolves explicit source declarations once for desktop and Web. / 桌面与 Web 共用的显式源码存储声明解析。
 */
public final class ManagedStoragePreparation {
    /**
     * Prevents instantiation of this static contract helper.
     * <p>防止实例化当前静态契约辅助类。
     */
    private ManagedStoragePreparation() { }
    /**
     * Carries prepared resource bindings and the credentials needed for activation.
     * <p>携带已准备的资源绑定及激活所需凭据。
     *
     * @param configuration reviewed configuration snapshot or settings / 已审阅配置快照或设置
     * @param files controlled filesystem access or reviewed file inventory / 受控文件系统访问或已审阅文件清单
     */
    public record Prepared(ConfigurationSnapshot configuration, List<ManagedFileBinding> files) { }

    /**
     * Validates explicit storage declarations and source seeds, derives immutable configuration revisions and merges them with reviewed bindings.
     * <p>校验显式存储声明及源码种子，派生不可变配置修订，并将其与已审阅绑定合并。
     *
     * @param root root directory defining the filesystem boundary / 定义文件系统边界的根目录
     * @param applicationId managed application identifier / 受管应用标识
     * @param configuration reviewed configuration snapshot or settings / 已审阅配置快照或设置
     * @param runtime reviewed language, process and health specification / 已审阅的语言、进程及健康规格
     * @param existing existing / 既有
     * @return constructed or resolved prepared / 构造或解析得到的已准备
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    public static Prepared prepare(Path root, String applicationId, ConfigurationSnapshot configuration,
                                   DeploymentRuntimeSpecification runtime, List<ManagedFileBinding> existing) {
        var files = new LinkedHashMap<String,ManagedFileBinding>();
        existing.forEach(file -> files.put(file.bindingId(),file));
        var entries = new LinkedHashMap<String,ConfigurationEntry>();
        configuration.entries().forEach(entry -> entries.put(entry.key(),entry));
        Path manifest = root.resolve("windowstolinux-storage.properties");
        try {
            if (Files.exists(manifest,LinkOption.NOFOLLOW_LINKS)) {
                verifiedSource(root,manifest,65536);
                Properties properties = new Properties(); properties.load(new StringReader(Files.readString(manifest)));
                String[] identifiers = properties.getProperty("storage", "").split(",");
                if (identifiers.length > 32) throw new IllegalArgumentException("too many storage declarations");
                var declared = new HashSet<String>();
                for (String raw : identifiers) {
                    String id = raw.trim();
                    if (id.isEmpty() || !declared.add(id)) throw new IllegalArgumentException("storage identifiers must be nonempty and unique");
                    String prefix = "storage."+id+".";
                    var kind = ManagedStorageLocation.StorageResourceType.valueOf(properties.getProperty(prefix+"kind","FILE"));
                    if (kind == ManagedStorageLocation.StorageResourceType.DATABASE) throw new IllegalArgumentException("SQLite belongs in windowstolinux-db.properties");
                    String path = properties.getProperty(prefix+"path");
                    String selection = properties.getProperty(prefix+"location",path == null ? "DEFAULT" : "CUSTOM");
                    var location = new ManagedStorageLocation(ManagedStorageLocation.StorageLocationType.valueOf(selection),path == null ? "" : path);
                    String physical = location.resolve(applicationId,kind,id);
                    String environment = properties.getProperty(prefix+"environment","");
                    if (!environment.isEmpty() && !environment.matches("[A-Z][A-Z0-9_]{0,63}")) throw new IllegalArgumentException("invalid storage environment key");
                    String access = properties.getProperty(prefix+"access",path == null ? "" : path);
                    String seed = properties.getProperty(prefix+"seed","");
                    String digest = "";
                    boolean configurationFile = kind == ManagedStorageLocation.StorageResourceType.CONFIGURATION;
                    if (configurationFile) {
                        if (seed.isEmpty()) throw new IllegalArgumentException("configuration files require an explicit reviewed source file");
                        Path source = root.resolve(seed).normalize(); verifiedSource(root,source,16 * 1024 * 1024);
                        digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source)));
                        physical += "/revisions/"+digest+"/value";
                    } else if (!seed.isEmpty()) {
                        Path source = root.resolve(seed).normalize();
                        if (!source.startsWith(root) || !Files.isDirectory(source,LinkOption.NOFOLLOW_LINKS) || !source.toRealPath().startsWith(root.toRealPath()))
                            throw new IllegalArgumentException("persistent file seeds require a reviewed source directory");
                    }
                    if (access.isEmpty()) {
                        if (environment.isEmpty()) throw new IllegalArgumentException("unresolved application storage input: declare access or environment");
                        if (runtime instanceof DeploymentRuntimeSpecification.Container) throw new IllegalArgumentException("container storage requires its application access path");
                        access = physical;
                    }
                    String mode = properties.getProperty(prefix+"mode",configurationFile ? "ro" : "rw");
                    if (!Set.of("ro","rw").contains(mode)) throw new IllegalArgumentException("storage mode must be ro or rw");
                    var binding = new ManagedFileBinding(id,new ComponentDataPath(access,mode.equals("ro") ? ComponentDataPath.AccessMode.READ_ONLY : ComponentDataPath.AccessMode.READ_WRITE,"files",true),location,kind,seed,digest);
                    ManagedFileBinding previous = files.putIfAbsent(id,binding);
                    if (previous != null && !previous.equals(binding)) throw new IllegalArgumentException("storage manifest conflicts with reviewed component input");
                    if (!environment.isEmpty()) {
                        var entry = new ConfigurationEntry(environment,ConfigurationScope.RUNTIME,new ConfigurationValue.Text(access));
                        ConfigurationEntry previousEntry = entries.putIfAbsent(environment,entry);
                        if (previousEntry != null && !previousEntry.equals(entry)) throw new IllegalArgumentException("application configuration conflicts with storage declaration");
                    }
                }
            }
            if (runtime instanceof DeploymentRuntimeSpecification.Container container) {
                for (var volume : container.volumes()) {
                    var existingFile = files.get(volume.bindingId());
                    if (existingFile != null) {
                        if (!existingFile.dataPath().path().equals(volume.containerPath()) || (existingFile.dataPath().access() == ComponentDataPath.AccessMode.READ_ONLY) != volume.readOnly())
                            throw new IllegalArgumentException("container volume conflicts with storage declaration");
                    } else files.put(volume.bindingId(),new ManagedFileBinding(volume.bindingId(),new ComponentDataPath(volume.containerPath(),
                            volume.readOnly() ? ComponentDataPath.AccessMode.READ_ONLY : ComponentDataPath.AccessMode.READ_WRITE,"files",true),ManagedStorageLocation.defaults()));
                }
            }
            ManagedStoragePlan.resolve(applicationId,new ManagedComponentResourceBindings(List.copyOf(files.values()),Optional.of(List.of())),runtime);
            return new Prepared(ConfigurationSnapshot.create(applicationId,configuration.revision(),configuration.schemaVersion(),configuration.createdAt(),List.copyOf(entries.values())),List.copyOf(files.values()));
        } catch (IOException | java.security.NoSuchAlgorithmException failure) {
            throw new IllegalArgumentException("cannot read bounded storage declarations",failure);
        }
    }

    /**
     * Describes the reviewed managed storage locations and handoff actions without modifying them.
     * <p>描述已审阅受管存储位置及交接动作，不修改存储。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @return constructed or resolved list / 构造或解析得到的列表
     */
    public static List<gold.debug.windowstolinux.shared.model.message.LocalizedMessage> preview(
            gold.debug.windowstolinux.shared.deploy.contract.ReviewedDeploymentRequest request) {
        var messages = new ArrayList<gold.debug.windowstolinux.shared.model.message.LocalizedMessage>();
        String app = request.facts().applicationId();
        var workload = request.runtime().workload();
        messages.add(gold.debug.windowstolinux.shared.model.message.LocalizedMessage.of("application.preflight", Map.of(
                "application",app,"category",workload.category(),"mode",workload.mode(),
                "directory",workload.workingDirectory().isEmpty()?".":workload.workingDirectory(),
                "command",(workload.command().entrypoint().isBlank()?"(primary)":workload.command().entrypoint())+" "+workload.command().arguments(),
                "endpoints",workload.endpoints().stream().map(endpoint -> endpoint.protocol()+" "+endpoint.bindAddress()+":"+endpoint.hostPort()+" → "+endpoint.targetPort()+" / "+endpoint.exposure()).toList())));
        for (var input : workload.inputs()) messages.add(gold.debug.windowstolinux.shared.model.message.LocalizedMessage.of(
                "application.input.preflight", Map.of("binding",input.id(),"host",input.hostPath(),"access",input.accessPath())));
        for (var worker : workload.workers()) messages.add(gold.debug.windowstolinux.shared.model.message.LocalizedMessage.of(
                "application.worker.preflight", Map.of("application", app, "worker", worker.id(),
                        "command", worker.command().entrypoint() + " " + worker.command().arguments())));
        String format = request.runtime() instanceof DeploymentRuntimeSpecification.Container ? "container" : "systemd";
        String configurationPath = ManagedStorageLocation.configurationRoot(app)+"/"+request.configuration().sha256()+"/"+format+".env";
        messages.add(gold.debug.windowstolinux.shared.model.message.LocalizedMessage.of("storage.preflight",Map.of("application",app,"binding","deployment-configuration",
                "kind","CONFIGURATION","source","DEFAULT","access",configurationPath,"host",configurationPath,"mode","ro")));
        messages.addAll(ManagedStoragePlan.resolve(request.facts().applicationId(),
                new ManagedComponentResourceBindings(request.fileBindings(),request.databaseBindings()),request.runtime()).stream()
                .map(resource -> gold.debug.windowstolinux.shared.model.message.LocalizedMessage.of("storage.preflight",Map.of(
                        "application",request.facts().applicationId(),"binding",resource.id(),"kind",resource.kind(),
                        "source",resource.location().type(),"access",resource.accessPath(),"host",resource.physicalPath(),
                        "mode",resource.readOnly() ? "ro" : "rw"))).toList());
        return List.copyOf(messages);
    }

    /**
     * Requires a bounded regular source file whose normalized and real paths remain beneath the reviewed source root.
     * <p>要求源码文件为有界常规文件，且规范路径及真实路径均位于已审阅源码根目录内。
     *
     * @param root root directory defining the filesystem boundary / 定义文件系统边界的根目录
     * @param source source identity or content read by the operation / 操作读取的源身份或内容
     * @param limit limit / 限制
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    private static void verifiedSource(Path root, Path source, long limit) throws IOException {
        if (!source.normalize().startsWith(root.normalize()) || !Files.isRegularFile(source,LinkOption.NOFOLLOW_LINKS)
                || !source.toRealPath().startsWith(root.toRealPath()) || Files.size(source)>limit)
            throw new IllegalArgumentException("storage source must be a bounded regular file in the reviewed project");
    }
}
