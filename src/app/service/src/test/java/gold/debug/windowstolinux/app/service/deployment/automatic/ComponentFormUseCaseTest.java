package gold.debug.windowstolinux.app.service.deployment.automatic;

import gold.debug.windowstolinux.app.service.contract.definition.ComponentHealthMode;

import gold.debug.windowstolinux.app.service.contract.definition.ComponentFormInput;

import gold.debug.windowstolinux.shared.model.deployment.DatabaseReviewMode;

import gold.debug.windowstolinux.shared.deploy.input.DeploymentRuntimeParser;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Tests the bounded multi-component desktop form mapping. / 测试有界多组件桌面表单映射。 */
class ComponentFormUseCaseTest {
    @Test
    void mapsAComponentToTypedAnalysisAndReviewWithoutAnUnsafeExecutionField() {
        ComponentFormInput draft = draft(DeploymentProjectType.NODE_SERVICE, ComponentHealthMode.TCP);

        var analysis = new ComponentFormUseCase(draft).analysisRequest();
        var review = new ComponentFormUseCase(draft).reviewInput("shop-api", false, false);

        assertEquals("api", analysis.componentId());
        assertEquals(java.util.Set.of("database"), analysis.dependencies());
        assertEquals(java.util.Set.of(18081), analysis.ports());
        assertEquals(java.util.List.of("PORT"), analysis.configurationKeys());
        assertEquals(java.util.List.of("database-password"), analysis.secretIdentifiers());
        assertFalse(analysis.isolation().arbitraryShell());
        assertFalse(analysis.isolation().hostPrivileges());
        assertInstanceOf(DeploymentRuntimeSpecification.NodeService.class, analysis.runtime().orElseThrow());
        assertEquals("shop-api", review.configuration().applicationId());
        assertEquals(1, review.secretReferences().size());
        assertEquals(java.util.List.of(), review.databaseBindings().orElseThrow());
        assertFalse(review.limits().runAsRoot());
    }

    @Test
    void rejectsPreviewComponentsAndStaticSitesWithoutHttpHealth() {
        assertThrows(IllegalArgumentException.class,
                () -> new ComponentFormUseCase(draft(DeploymentProjectType.RECOGNITION_PREVIEW, ComponentHealthMode.TCP)).analysisRequest());
        assertThrows(IllegalArgumentException.class,
                () -> new ComponentFormUseCase(draft(DeploymentProjectType.STATIC_SITE, ComponentHealthMode.TCP)).analysisRequest());
    }

    private static ComponentFormInput draft(DeploymentProjectType type, ComponentHealthMode healthMode) {
        return new ComponentFormInput("api", "api", type, "dist", "", "22", "", "", healthMode,
                healthMode == ComponentHealthMode.TCP ? "18081" : "http://127.0.0.1:18081/health",
                "200", "20", "1", healthMode == ComponentHealthMode.HTTP
                ? "http://example.test:18081/" : "", "api/dist", "18081", "database",
                "PORT=18081", DatabaseReviewMode.NONE, "",
                "database-password:1", true, false, "",
                "version=1\nmode=DAEMON\nhealth.mode=TCP\nhealth.port=18081\nendpoints=service\nendpoint.service.protocol=TCP\nendpoint.service.port=18081\nendpoint.service.exposure=EXTERNAL\n");
    }
}
