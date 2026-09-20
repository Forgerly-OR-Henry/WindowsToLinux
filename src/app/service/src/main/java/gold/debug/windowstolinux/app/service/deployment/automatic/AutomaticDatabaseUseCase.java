package gold.debug.windowstolinux.app.service.deployment.automatic;

import gold.debug.windowstolinux.app.db.entity.StoredApplicationSecretRevision;
import gold.debug.windowstolinux.app.db.persistence.repository.ApplicationSecretRepository;
import gold.debug.windowstolinux.app.service.config.DeploymentConfigurationUseCase;
import gold.debug.windowstolinux.app.service.contract.AiApplicationFacade;
import gold.debug.windowstolinux.app.service.server.*;
import gold.debug.windowstolinux.shared.analyze.ecosystem.db.DatabaseProjectInspector;
import gold.debug.windowstolinux.shared.config.secretref.SecretReference;
import gold.debug.windowstolinux.shared.deploy.contract.*;
import gold.debug.windowstolinux.shared.deploy.contract.spi.DatabaseCredentialPort;
import gold.debug.windowstolinux.shared.deploy.execution.environment.NativeDatabasePreparationService;
import gold.debug.windowstolinux.shared.linux.connection.DeploymentLinuxGateway;
import gold.debug.windowstolinux.shared.model.deployment.DeploymentInputField;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Desktop credentials and UI adapter for the shared native database preparation flow. */
public final class AutomaticDatabaseUseCase {
    private final ServerUseCaseFacade servers;
    private final DeploymentLinuxGateway gateway;
    private final ApplicationSecretRepository secretMetadata;
    private final DesktopSecretStoreService stores;
    private final DeploymentConfigurationUseCase configurations;
    private final AutomaticInputCompletion completion;
    public AutomaticDatabaseUseCase(ServerUseCaseFacade servers, DeploymentLinuxGateway gateway,
            ApplicationSecretRepository secretMetadata, DesktopSecretStoreService stores,
            DeploymentConfigurationUseCase configurations, AiApplicationFacade ai) {
        this.servers=servers; this.gateway=gateway; this.secretMetadata=secretMetadata; this.stores=stores;
        this.configurations=configurations; this.completion=new AutomaticInputCompletion(ai);
    }
    public DatabaseProjectInspector.Assessment completeInputs(Path root, String applicationId,
            DatabaseProjectInspector.Assessment assessment, char[] master, AutomaticDeploymentInteraction interaction) throws Exception {
        try { return new NativeDatabasePreparationService(null).completeInputs(root,applicationId,assessment,withAi(master,interaction)); }
        finally { Arrays.fill(master,'\0'); }
    }
    public AutomaticDatabasePreparation prepare(Path root, String applicationId, ServerProfile profile,
            DatabaseProjectInspector.Assessment assessment, char[] master, AutomaticDeploymentInteraction interaction,
            Predicate<String> fingerprint, Consumer<LocalizedMessage> progress) throws Exception {
        try (var store = stores.open(profile.credentialMode(), master);
             var session = gateway.connect(profile.endpoint(),servers.loadPassword(profile,store),servers.hostKeyVerifier(profile,fingerprint))) {
            return new NativeDatabasePreparationService(credentials(profile,master)).prepare(root,applicationId,profile.id(),session.nativeDatabases(),assessment,interaction,progress);
        } finally { Arrays.fill(master,'\0'); }
    }
    private AutomaticDeploymentInteraction withAi(char[] master, AutomaticDeploymentInteraction delegate) {
        return new AutomaticDeploymentInteraction() {
            @Override public Optional<Map<String,String>> requestInputs(List<DeploymentInputField> fields) { return Optional.of(completion.resolve(fields,master,delegate)); }
            @Override public boolean confirm(String key, Map<String,?> details) { return delegate.confirm(key,details); }
            @Override public boolean confirmDatabaseReplacement(Map<String,?> details) { return delegate.confirmDatabaseReplacement(details); }
            @Override public char[] requestSecret(String key) { return delegate.requestSecret(key); }
        };
    }
    private DatabaseCredentialPort credentials(ServerProfile profile,char[] master) {
        return new DatabaseCredentialPort() {
            @Override public Optional<SecretReference> latest(String identifier) throws Exception {
                var reference = new SecretReference(identifier,1);
                if (secretMetadata.findRevision(reference).isEmpty()) return Optional.empty();
                while (secretMetadata.findRevision(new SecretReference(identifier,reference.revision()+1)).isPresent()) reference = new SecretReference(identifier,reference.revision()+1);
                return Optional.of(reference);
            }
            @Override public char[] load(SecretReference reference) throws Exception {
                var stored = secretMetadata.findRevision(reference).orElseThrow();
                try (var store = stores.open(stored.credentialMode(),master)) { return store.read(stored.credentialKey()).orElseThrow(() -> new IllegalStateException("Saved DB credential is missing")); }
            }
            @Override public void save(SecretReference reference,char[] value) throws Exception {
                String digest = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(reference.identifier().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                configurations.saveSecretRevision(new StoredApplicationSecretRevision(reference,"application-secret/"+digest+"/"+reference.revision(),profile.credentialMode(),Instant.now()),profile.credentialMode(),master.clone(),value);
            }
        };
    }
}
