package gold.debug.windowstolinux.app.service.contract;

import gold.debug.windowstolinux.shared.deploy.contract.AutomaticDatabasePreparation;

import gold.debug.windowstolinux.shared.deploy.contract.AutomaticDeploymentInteraction;

import gold.debug.windowstolinux.app.service.contract.definition.*;


import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Desktop automatic deployment boundary; domain execution remains in existing reviewed use cases. / 桌面自动部署边界，领域执行仍由现有审阅用例负责。 */
public interface AutomaticDeploymentApplicationFacade extends DeploymentApplicationFacade, MultiComponentApplicationFacade,
        ServerApplicationFacade, AiApplicationFacade {
    /** Identifies a directory or a Git URI within the existing source policy. / 在既有源码策略内识别目录或 Git URI。 */
    DeploymentSourceInput identifyDeploymentSource(String value);
    /** Parses typed source and deployment controls inside the service boundary. / 在服务边界内解析源码与部署控件。 */
    AutomaticDeploymentRequest createAutomaticDeploymentRequest(DeploymentSourceInput source,
            gold.debug.windowstolinux.app.service.server.ServerProfile server, DeploymentFormInput input);

    gold.debug.windowstolinux.shared.analyze.ecosystem.db.DatabaseProjectInspector.Assessment completeAutomaticDatabaseInputs(
            java.nio.file.Path root, String applicationId,
            gold.debug.windowstolinux.shared.analyze.ecosystem.db.DatabaseProjectInspector.Assessment assessment,
            char[] master, AutomaticDeploymentInteraction interaction) throws Exception;

    AutomaticDatabasePreparation prepareAutomaticDatabases(java.nio.file.Path root, String applicationId,
            gold.debug.windowstolinux.app.service.server.ServerProfile server,
            gold.debug.windowstolinux.shared.analyze.ecosystem.db.DatabaseProjectInspector.Assessment assessment, char[] master,
            AutomaticDeploymentInteraction interaction, Predicate<String> fingerprint, Consumer<LocalizedMessage> progress) throws Exception;

    AutomaticDeploymentOutcome deployAutomatically(AutomaticDeploymentRequest request, char[] masterPassword,
            AutomaticDeploymentInteraction interaction, Predicate<String> firstUseConfirmation,
            Consumer<LocalizedMessage> progress) throws Exception;

    gold.debug.windowstolinux.app.service.deployment.single.DeploymentOutcome deployAutomaticallyReviewed(
            gold.debug.windowstolinux.shared.deploy.contract.ReviewedDeploymentRequest request,
            gold.debug.windowstolinux.app.service.server.ServerProfile profile, char[] master,
            Predicate<String> fingerprint, Consumer<LocalizedMessage> progress) throws Exception;
    gold.debug.windowstolinux.shared.deploy.contract.result.deployment.MultiComponentDeploymentResult deployAutomaticallyReviewed(
            gold.debug.windowstolinux.app.service.deployment.multi.ReviewedMultiComponentApplication request,
            gold.debug.windowstolinux.app.service.server.ServerProfile profile, char[] master,
            Predicate<String> fingerprint, Consumer<LocalizedMessage> progress) throws Exception;
}
