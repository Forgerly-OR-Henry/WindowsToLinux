package gold.debug.windowstolinux.web.service.contract;

import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;
import gold.debug.windowstolinux.shared.deploy.input.AutomaticRuntimeResolver;
import gold.debug.windowstolinux.shared.backup.format.BackupConfigurationCodec;
import gold.debug.windowstolinux.shared.backup.format.BackupConfigurationDocument;
import java.io.IOException;
import java.util.*;

/** Durable exact non-secret component facts for lifecycle and later backups. */
public record ManagedWebComponent(String id, ManagedApplication application, Map<String, String> inputs,
                                  String activation, List<String> dependencies, String releaseIdentity,
                                  List<gold.debug.windowstolinux.shared.config.secretref.SecretReference> secrets, String runtimeDefinition) {
    public ManagedWebComponent { inputs = Map.copyOf(inputs); dependencies = List.copyOf(dependencies); secrets = List.copyOf(secrets); }
    public DeploymentRuntimeSpecification runtime() {
        try { return new gold.debug.windowstolinux.shared.config.persistence.serialization.DeploymentRuntimePersistenceCodec().read(Base64.getDecoder().decode(runtimeDefinition),configuration().runtimeConfiguration().healthCheck()); }
        catch(IOException failure) { throw new IllegalStateException("Stored runtime is invalid",failure); }
    }
    public BackupConfigurationDocument configuration() throws IOException { return new BackupConfigurationCodec().readActivation(Base64.getDecoder().decode(activation)); }
    public ManagedWebComponent published(String digest) { return new ManagedWebComponent(id, application, inputs, activation, dependencies, digest, secrets,runtimeDefinition); }
}
