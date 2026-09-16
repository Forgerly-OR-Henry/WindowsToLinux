package gold.debug.windowstolinux.app.service.contract;

import gold.debug.windowstolinux.app.secret.SecretStoreException;
import gold.debug.windowstolinux.app.service.deployment.multi.ManagedMultiComponentApplication;
import gold.debug.windowstolinux.app.service.contract.definition.MultiComponentReviewInput;
import gold.debug.windowstolinux.app.service.deployment.multi.ReviewedMultiComponentApplication;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.app.service.source.PreparedMultiComponentSource;
import gold.debug.windowstolinux.shared.analyze.component.ComponentAnalysisRequest;
import gold.debug.windowstolinux.shared.config.revision.ConfigurationSnapshot;
import gold.debug.windowstolinux.shared.deploy.contract.ApplicationHealthGate;
import gold.debug.windowstolinux.shared.deploy.contract.result.deployment.MultiComponentDeploymentResult;
import gold.debug.windowstolinux.shared.deploy.contract.result.lifecycle.MultiComponentLifecycleResult;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleAction;
import gold.debug.windowstolinux.shared.model.security.CredentialStorageMode;
import gold.debug.windowstolinux.shared.model.server.ServerIdentity;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/** Narrow application operations required by whole-application deployment and lifecycle. / 整应用部署与生命周期所需的窄应用操作。 */
public interface MultiComponentApplicationFacade {
    /** Parses component analysis within the service boundary. / 在服务边界内解析组件分析输入。 */
    ComponentAnalysisRequest parseComponentAnalysis(gold.debug.windowstolinux.app.service.contract.definition.ComponentFormInput input);

    /** Parses review inputs against the analyzed managed identity. / 根据已分析受管身份解析审阅输入。 */
    MultiComponentReviewInput parseComponentReview(gold.debug.windowstolinux.app.service.contract.definition.ComponentFormInput input,
            String managedApplicationId, boolean containerRisk, boolean experimentalRisk);

    PreparedMultiComponentSource prepareReviewedMultiComponentSource(
            Path applicationRoot, String applicationId, List<ComponentAnalysisRequest> components) throws IOException;

    Optional<ServerIdentity> findTrustedServer(String serverId) throws SQLException;

    ReviewedMultiComponentApplication createReviewedMultiComponentApplication(
            PreparedMultiComponentSource prepared, ServerIdentity server, List<MultiComponentReviewInput> inputs,
            ApplicationHealthGate applicationHealth) throws SQLException;

    void saveDeploymentConfigurationSnapshot(ConfigurationSnapshot snapshot) throws SQLException;

    MultiComponentDeploymentResult deployReviewedMultiComponentWithStoredPassword(
            ReviewedMultiComponentApplication review, ServerProfile profile, CredentialStorageMode mode,
            char[] masterPassword, Predicate<String> confirmation) throws SecretStoreException, SQLException;

    Optional<ManagedMultiComponentApplication> findManagedMultiComponentApplication(String applicationId)
            throws SQLException;

    MultiComponentLifecycleResult executeManagedMultiComponentLifecycleWithStoredPassword(
            String applicationId, Set<String> targetComponentIds, LifecycleAction action,
            ServerProfile profile, CredentialStorageMode mode, char[] masterPassword)
            throws SecretStoreException, SQLException;
}
