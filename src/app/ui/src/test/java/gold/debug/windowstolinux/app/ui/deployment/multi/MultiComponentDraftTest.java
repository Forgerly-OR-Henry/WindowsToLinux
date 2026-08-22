package gold.debug.windowstolinux.app.ui.deployment.multi;

import gold.debug.windowstolinux.app.ui.deployment.DeploymentRuntimeParser;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Tests the bounded multi-component desktop form mapping. / 测试有界多组件桌面表单映射。 */
class MultiComponentDraftTest {
    @Test
    void mapsAComponentToTypedAnalysisAndReviewWithoutAnUnsafeExecutionField() {
        MultiComponentDraft draft = draft(DeploymentProjectType.NODE_SERVICE, MultiComponentHealthMode.TCP);

        var analysis = draft.analysisRequest();
        var review = draft.reviewInput("shop-api", false, false);

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
                () -> draft(DeploymentProjectType.RECOGNITION_PREVIEW, MultiComponentHealthMode.TCP).analysisRequest());
        assertThrows(IllegalArgumentException.class,
                () -> draft(DeploymentProjectType.STATIC_SITE, MultiComponentHealthMode.TCP).analysisRequest());
    }

    private static MultiComponentDraft draft(DeploymentProjectType type, MultiComponentHealthMode healthMode) {
        return new MultiComponentDraft("api", "api", type, "dist", "", "22", "", "", healthMode,
                healthMode == MultiComponentHealthMode.TCP ? "18081" : "http://127.0.0.1:18081/health",
                "200", "20", "1", healthMode == MultiComponentHealthMode.HTTP
                ? "http://example.test:18081/" : "", "api/dist", "18081", "database",
                "PORT=18081", DeploymentRuntimeParser.DatabaseReviewMode.NONE, "",
                "database-password:1", true, false);
    }
}
