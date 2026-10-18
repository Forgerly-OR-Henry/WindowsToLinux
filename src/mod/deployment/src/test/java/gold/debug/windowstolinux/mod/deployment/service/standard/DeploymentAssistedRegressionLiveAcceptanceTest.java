package gold.debug.windowstolinux.mod.deployment.service.standard;

import java.nio.file.Path;

import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** Runs the real ASSISTED workflow and exports product-owned usage for manual acceptance. / 真实辅助部署并导出产品用法供人工验收。 */
@EnabledIfSystemProperty(named = "managed.runtime.staticLive", matches = "true")
class DeploymentAssistedRegressionLiveAcceptanceTest extends DeploymentStaticRegressionLiveAcceptanceTest {
    @ParameterizedTest
    @MethodSource("websiteProjects")
    void deploysAssistedWebsitesForManualAcceptance(String project) throws Exception {
        Path delivery = deliveryFile(project);
        try {
            deploysWebsitesThroughAssistedEntry(project);
            deliver(project, false, delivery, "ASSISTED");
        } catch (Exception | AssertionError failure) {
            captureDenials(failure);
            throw failure;
        }
    }

    @ParameterizedTest
    @MethodSource("cliProjects")
    void deploysAssistedCliForManualAcceptance(String project) throws Exception {
        Path delivery = deliveryFile(project);
        try {
            deploysCliThroughAssistedEntry(project);
            deliver(project, true, delivery, "ASSISTED");
        } catch (Exception | AssertionError failure) {
            captureDenials(failure);
            throw failure;
        }
    }

    @ParameterizedTest
    @MethodSource("cliProjects")
    void deliversRecoveredAssistedCliForManualAcceptance(String project) throws Exception {
        Path delivery = deliveryFile(project);
        verifyRecoveredAssistedCli(project);
        deliver(project, true, delivery, "ASSISTED");
    }

    @org.junit.jupiter.api.Test
    void deliversRecoveredAssistedTaskBoardForManualAcceptance() throws Exception {
        Path delivery = deliveryFile("task-board");
        verifiesRecoveredTaskBoardThroughProduct();
        deliver("task-board", false, delivery, "ASSISTED");
    }

    private void captureDenials(Throwable original) {
        try (var runtime = open()) {
            for (var category : runtime.logs().inventory(false).categories()) {
                if (!category.target().owner().equals(OWNER)) continue;
                for (var record : runtime.logs().inspect(category.target().key()).records())
                    if (record.event().eventCode().equals("DEPLOYMENT_COMMAND_REVIEW_DENIED")
                        || record.event().eventCode().equals("DEPLOYMENT_ASSISTED_REVIEW_DENIED")
                        || record.event().eventCode().equals("DEPLOYMENT_OPERATION_FAILURE"))
                        System.out.println("ASSISTED_REVIEW_DIAGNOSTIC " + record.event().eventCode() + " " + record.event().message());
            }
        } catch (Exception unavailable) {
            original.addSuppressed(unavailable);
        }
    }
}
