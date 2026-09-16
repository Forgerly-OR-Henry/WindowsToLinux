package gold.debug.windowstolinux.app.service.contract;

import gold.debug.windowstolinux.app.secret.SecretStoreException;
import gold.debug.windowstolinux.app.service.deployment.single.DeploymentOutcome;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.app.service.source.ReviewedSourcePreparation;
import gold.debug.windowstolinux.shared.config.revision.ConfigurationSnapshot;
import gold.debug.windowstolinux.shared.config.resource.ManagedDatabaseBinding;
import gold.debug.windowstolinux.shared.config.secretref.SecretReference;
import gold.debug.windowstolinux.shared.deploy.contract.ReviewedDeploymentPlan;
import gold.debug.windowstolinux.shared.deploy.contract.ReviewedDeploymentRequest;
import gold.debug.windowstolinux.shared.git.GitSnapshotException;
import gold.debug.windowstolinux.shared.git.GitSourceRequest;
import gold.debug.windowstolinux.shared.model.deployment.BuildLimitConfiguration;
import gold.debug.windowstolinux.shared.model.health.UserAccessUrl;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;
import gold.debug.windowstolinux.shared.model.security.CredentialStorageMode;
import gold.debug.windowstolinux.shared.model.server.ServerIdentity;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/** Narrow application operations required by single-component deployment. / 单组件部署所需的窄应用操作。 */
public interface DeploymentApplicationFacade {
    ReviewedSourcePreparation prepareReviewedSource(Path sourceDirectory, DeploymentProjectType projectType)
            throws IOException;

    ReviewedSourcePreparation prepareReviewedGitSource(GitSourceRequest request, DeploymentProjectType projectType)
            throws GitSnapshotException;

    Optional<ServerIdentity> findTrustedServer(String serverId) throws SQLException;

    ReviewedDeploymentRequest createReviewedDeploymentRequest(
            ReviewedSourcePreparation preparation, ServerIdentity server, ConfigurationSnapshot configuration,
            List<SecretReference> secretReferences, Optional<List<ManagedDatabaseBinding>> databaseBindings,
            DeploymentRuntimeSpecification runtime,
            Optional<UserAccessUrl> userAccessUrl, BuildLimitConfiguration limits, boolean rootBuildConfirmed,
            boolean containerDaemonRiskAccepted, boolean experimentalAdapterRiskAccepted) throws SQLException;

    /** Creates a request whose database scope has not yet been reviewed. / 创建数据库范围尚未审阅的请求。 */
    default ReviewedDeploymentRequest createReviewedDeploymentRequest(
            ReviewedSourcePreparation preparation, ServerIdentity server, ConfigurationSnapshot configuration,
            List<SecretReference> secretReferences, DeploymentRuntimeSpecification runtime,
            Optional<UserAccessUrl> userAccessUrl, BuildLimitConfiguration limits, boolean rootBuildConfirmed,
            boolean containerDaemonRiskAccepted, boolean experimentalAdapterRiskAccepted) throws SQLException {
        return createReviewedDeploymentRequest(preparation, server, configuration, secretReferences, Optional.empty(),
                runtime, userAccessUrl, limits, rootBuildConfirmed, containerDaemonRiskAccepted,
                experimentalAdapterRiskAccepted);
    }

    ReviewedDeploymentPlan planDeployment(ReviewedDeploymentRequest request);

    void saveDeploymentConfigurationSnapshot(ConfigurationSnapshot snapshot) throws SQLException;

    DeploymentOutcome deployReviewedWithStoredPassword(
            ReviewedDeploymentRequest request, ServerProfile profile, CredentialStorageMode mode,
            char[] masterPassword, Predicate<String> confirmation) throws SecretStoreException, SQLException;

    SecretReference saveDeploymentSecretRevision(String referenceInput, CredentialStorageMode mode,
                                      char[] masterPassword, char[] value)
            throws SQLException, SecretStoreException;
}
