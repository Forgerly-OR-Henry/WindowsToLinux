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

/** Exact locally validated activation material, owning all decrypted secrets until close. */
public record WebRestoreMaterial(BackupArchiveValidation validation,BackupRestoreCandidate candidate,
        Map<String,BackupConfigurationDocument> configurations,Map<String,byte[]> runtimeDocuments,
        Optional<BackupSecretDocument> secretDocument,Optional<DatabaseRestoreRequest> database,Optional<Path> databaseFile) implements AutoCloseable {
    public static WebRestoreMaterial read(Path archive,Path parent,char[] password,long maximumBytes,long minimumFreeBytes) throws Exception {
        try { return readMaterial(archive,parent,password,maximumBytes,minimumFreeBytes); }
        finally { Arrays.fill(password,'\0'); }
    }
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
    public List<ResolvedSecretRevision> secrets() { return secretDocument.map(BackupSecretDocument::revisions).orElse(List.of()); }
    public String candidateId() { return candidate.root().getFileName().toString(); }
    @Override public void close() { secretDocument.ifPresent(BackupSecretDocument::close); }
    private static BackupMember exact(BackupRestoreCandidate candidate,String name,BackupMemberKind kind) throws IOException {
        return candidate.manifest().members().stream().filter(member -> member.path().equals(name) && member.kind()==kind).findFirst().orElseThrow(() -> new IOException("Required backup member is absent"));
    }
    private static byte[] read(BackupRestoreCandidate candidate,String name,BackupMemberKind kind,long limit) throws Exception {
        var member=exact(candidate,name,kind);Path path=candidate.root().resolve(name).normalize();
        if(!path.startsWith(candidate.root()) || member.size()>limit || Files.size(path)!=member.size() || !Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)) throw new IOException("Backup member boundary changed");
        byte[] bytes=Files.readAllBytes(path);
        if(bytes.length!=member.size() || !HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(member.sha256())) throw new IOException("Backup member changed");
        return bytes;
    }
}
