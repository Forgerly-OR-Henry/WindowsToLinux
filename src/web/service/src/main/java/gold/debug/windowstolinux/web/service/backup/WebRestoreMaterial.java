package gold.debug.windowstolinux.web.service.backup;

import gold.debug.windowstolinux.shared.backup.contract.spi.*;
import gold.debug.windowstolinux.shared.backup.contract.validation.*;
import gold.debug.windowstolinux.shared.backup.crypto.*;
import gold.debug.windowstolinux.shared.backup.format.*;
import gold.debug.windowstolinux.shared.backup.manifest.*;
import gold.debug.windowstolinux.shared.backup.restore.*;
import gold.debug.windowstolinux.shared.config.persistence.serialization.DeploymentRuntimePersistenceCodec;
import gold.debug.windowstolinux.shared.config.resource.*;
import gold.debug.windowstolinux.shared.config.secretref.*;
import gold.debug.windowstolinux.shared.model.project.*;
import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/**
 * Owns exact locally validated activation material and all decrypted secrets until close.
 * <p>持有精确且已本地验证的激活素材及全部解密秘密，直至关闭。
 *
 * @param validation validation / 校验
 * @param candidate candidate / 候选
 * @param configurations configurations / 配置集合
 * @param runtimeDocuments runtime documents / 运行时文档集合
 * @param secretDocument secret document / 秘密文档
 * @param database reviewed database identity or database operation boundary / 已审阅数据库身份或数据库操作边界
 * @param databaseFile database file / 数据库文件
 */
public record WebRestoreMaterial(BackupArchiveValidation validation,BackupRestoreCandidate candidate,
        Map<String,BackupConfigurationDocument> configurations,Map<String,byte[]> runtimeDocuments,
        Optional<BackupSecretDocument> secretDocument,Optional<DatabaseRestoreRequest> database,Optional<Path> databaseFile) implements AutoCloseable {
    /**
     * Reads web restore material.
     * <p>读取Web恢复素材。
     *
     * @param archive source or backup archive descriptor or filesystem path / 源码或备份归档描述或文件系统路径
     * @param parent parent / 父级
     * @param password temporary plaintext authentication buffer / 临时明文认证缓冲区
     * @param maximumBytes maximum bytes / 最大字节
     * @param minimumFreeBytes minimum free disk space in bytes / 最小磁盘剩余空间，单位为字节
     * @return web restore material / Web恢复素材
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    public static WebRestoreMaterial read(Path archive,Path parent,char[] password,long maximumBytes,long minimumFreeBytes) throws Exception {
        try { return readMaterial(archive,parent,password,maximumBytes,minimumFreeBytes); }
        finally { Arrays.fill(password,'\0'); }
    }
    /**
     * Validates and extracts bounded backup material, authenticates secrets and owns all decrypted buffers until close.
     * <p>校验并提取有界备份素材、认证秘密，并持有全部解密缓冲区直至关闭。
     *
     * @param archive source or backup archive descriptor or filesystem path / 源码或备份归档描述或文件系统路径
     * @param parent parent / 父级
     * @param password temporary plaintext authentication buffer / 临时明文认证缓冲区
     * @param maximumBytes maximum bytes / 最大字节
     * @param minimumFreeBytes minimum free disk space in bytes / 最小磁盘剩余空间，单位为字节
     * @return constructed or resolved web restore material / 构造或解析得到的Web恢复素材
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    private static WebRestoreMaterial readMaterial(Path archive,Path parent,char[] password,long maximumBytes,long minimumFreeBytes) throws Exception {
        var validation=new BackupArchiveValidator(BackupArchivePolicy.defaults()).validate(archive);
        long bytes=validation.manifest().members().stream().mapToLong(member -> member.size()).sum();
        if(bytes>maximumBytes || Files.getFileStore(parent).getUsableSpace()<bytes+minimumFreeBytes) throw new IOException("Restore workspace quota exceeded");
        if(!validation.manifest().supportsAutomaticActivation()) throw new IllegalArgumentException("Backup lacks exact activation inputs");
        String candidateId=validation.manifest().applicationId()+"-"+validation.archiveSha256().substring(0,16);
        var candidate=new BackupArchiveExtractor().extract(archive,parent.resolve(candidateId),validation);
        BackupSecretDocument secretDocument=null;
        try {
            if(!validation.manifest().inventory().secretReferences().isEmpty()) {
                secretDocument=new BackupSecretCryptoService().decryptRevisions(password,read(candidate,"secrets.enc",BackupMemberKind.ENCRYPTED_SECRETS,BackupSecretCryptoService.MAXIMUM_ENVELOPE_BYTES));
                if(!new HashSet<>(secretDocument.revisions().stream().map(ResolvedSecretRevision::reference).toList()).equals(new HashSet<>(validation.manifest().inventory().secretReferences()))) throw new IOException("Backup secret set differs");
            }
            var configurations=new LinkedHashMap<String,BackupConfigurationDocument>();var runtimeDocuments=new LinkedHashMap<String,byte[]>();
            DatabaseRestoreRequest database=null;Path databaseFile=null;
            for(var component:validation.manifest().inventory().components()) {
                var configuration=new BackupConfigurationCodec().readActivation(read(candidate,component.configurationSnapshotPath(),BackupMemberKind.CONFIGURATION,4*1024*1024));
                byte[] runtimeDocument=read(candidate,component.serviceDefinitionPath(),BackupMemberKind.RUNTIME,1024*1024);
                var runtime=new DeploymentRuntimePersistenceCodec().read(runtimeDocument,configuration.runtimeConfiguration().healthCheck());
                if(!BackupComponentRuntime.from(runtime).equals(component.runtime()) || !configuration.configuration().applicationId().equals(component.managedApplicationId())
                        || runtime.identityPolicy()==RuntimeIdentityMode.LEGACY_UNSPECIFIED || runtime.identityPolicy()!=configuration.runtimeConfiguration().identityPolicy()) throw new IOException("Backup runtime/configuration identity differs");
                if(runtime instanceof DeploymentRuntimeSpecification.Container container) {
                    exact(candidate,"runtime/"+component.componentId()+".oci",BackupMemberKind.RUNTIME);
                }
                for(var binding:configuration.resourceBindings().fileBindings()) exact(candidate,"data/"+component.componentId()+"/files/"+binding.bindingId()+".pax",BackupMemberKind.PERSISTENT_CONTENT);
                for(var binding:configuration.resourceBindings().databaseBindings().orElseThrow()) {
                    if(database!=null)throw new IOException("Multiple database restore is unsupported");
                    var profile=WebBackupCollection.profile(binding);
                    if(profile.type()!=validation.manifest().inventory().database().type() || binding.connection() instanceof ManagedDatabaseConnection.Server server
                            && !component.secretReferences().orElseThrow().contains(server.passwordReference())) throw new IOException("Database identity differs");
                    String path="database/"+binding.databaseId()+".dump";var member=exact(candidate,path,BackupMemberKind.DATABASE);
                    var artifact=new DatabaseBackupArtifact("db-"+member.sha256().substring(0,32),member.size(),member.sha256(),validation.manifest().inventory().database(),List.of("digest-bound archive database material"));
                    database=new DatabaseRestoreRequest(validation.manifest().applicationId(),component.managedApplicationId(),candidateId,profile,artifact);databaseFile=candidate.root().resolve(path);
                }
                configurations.put(component.componentId(),configuration);runtimeDocuments.put(component.componentId(),runtimeDocument);
            }
            if((database==null)!=(validation.manifest().inventory().database().type()==BackupDatabaseType.NONE)
                    || validation.manifest().members().stream().filter(member -> member.kind()==BackupMemberKind.DATABASE).count()!=(database==null?0:1)) throw new IOException("Database material set differs");
            return new WebRestoreMaterial(validation,candidate,Map.copyOf(configurations),Map.copyOf(runtimeDocuments),Optional.ofNullable(secretDocument),Optional.ofNullable(database),Optional.ofNullable(databaseFile));
        } catch(Exception failure) { if(secretDocument!=null) secretDocument.close();throw failure; }
        finally { Arrays.fill(password,'\0'); }
    }
    /**
     * Returns credential references or scoped secret-access service.
     * <p>返回凭据引用或限定作用域的秘密访问服务。
     *
     * @return credential references or scoped secret-access service / 凭据引用或限定作用域的秘密访问服务
     */
    public List<ResolvedSecretRevision> secrets() { return secretDocument.map(BackupSecretDocument::revisions).orElse(List.of()); }
    /**
     * Returns identity of the isolated deployment or restore candidate.
     * <p>返回隔离部署或恢复候选的身份。
     *
     * @return identity of the isolated deployment or restore candidate / 隔离部署或恢复候选的身份
     */
    public String candidateId() { return candidate.root().getFileName().toString(); }
    /**
     * Closes the resources owned by this instance and completes its cleanup boundary.
     * <p>关闭当前实例持有的资源并完成其清理边界。
     */
    @Override public void close() { secretDocument.ifPresent(BackupSecretDocument::close); }
    /**
     * Finds the required archive member by path and kind, failing if it is absent.
     * <p>按路径及类型查找必需归档成员，缺失时失败。
     *
     * @param candidate candidate / 候选
     * @param name human-readable name or diagnostic field label / 可读名称或诊断字段标签
     * @param kind selected member of the supported kind set / 受支持种类集合中的所选项
     * @return the required archive member by path and kind, failing if it is absent / 按路径及类型查找必需归档成员，缺失时失败
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    private static BackupMember exact(BackupRestoreCandidate candidate,String name,BackupMemberKind kind) throws IOException {
        return candidate.manifest().members().stream().filter(member -> member.path().equals(name) && member.kind()==kind).findFirst().orElseThrow(() -> new IOException("Required backup member is absent"));
    }
    /**
     * Reads web restore material.
     * <p>读取Web恢复素材。
     *
     * @param candidate candidate / 候选
     * @param name human-readable name or diagnostic field label / 可读名称或诊断字段标签
     * @param kind selected member of the supported kind set / 受支持种类集合中的所选项
     * @param limit limit / 限制
     * @return web restore material / Web恢复素材
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    private static byte[] read(BackupRestoreCandidate candidate,String name,BackupMemberKind kind,long limit) throws Exception {
        var member=exact(candidate,name,kind);Path path=candidate.root().resolve(name).normalize();
        if(!path.startsWith(candidate.root()) || member.size()>limit || Files.size(path)!=member.size() || !Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)) throw new IOException("Backup member boundary changed");
        byte[] bytes=Files.readAllBytes(path);
        if(bytes.length!=member.size() || !HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(member.sha256())) throw new IOException("Backup member changed");
        return bytes;
    }
}
