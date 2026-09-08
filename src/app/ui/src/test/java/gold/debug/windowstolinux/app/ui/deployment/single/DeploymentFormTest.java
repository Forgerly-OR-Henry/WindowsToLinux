package gold.debug.windowstolinux.app.ui.deployment.single;

import gold.debug.windowstolinux.app.service.deployment.automatic.DeploymentRuntimeParser;
import gold.debug.windowstolinux.app.ui.i18n.MessageCatalog;
import gold.debug.windowstolinux.app.ui.i18n.PageMessagePresenter;
import gold.debug.windowstolinux.shared.config.resource.ManagedDatabaseConnection;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests explicit database review and non-secret single-deployment form state. / 测试显式数据库审阅及单组件部署表单的非秘密状态。 */
class DeploymentFormTest {
    @Test void automaticHealthModePreservesAnExplicitChoiceWithoutAnEndpoint() {
        DeploymentForm form = form();
        var inputs = new java.util.LinkedHashMap<String, String>();
        form.automaticHealthInputs(inputs);
        assertTrue(inputs.isEmpty());
        form.healthMode.setSelectedIndex(2);
        form.automaticHealthInputs(inputs);
        assertEquals("TCP", inputs.get("healthMode"));
        assertFalse(inputs.containsKey("port"));
        form.healthMode.setSelectedIndex(0);
        form.healthEndpoint.setText("http://127.0.0.1:18080/health");
        form.automaticHealthInputs(inputs);
        assertEquals("HTTP", inputs.get("healthMode")); assertEquals("18080", inputs.get("port"));
        DeploymentForm restored = form(); restored.restore(form.capture("", null));
        var restoredInputs = new java.util.LinkedHashMap<String, String>(); restored.automaticHealthInputs(restoredInputs);
        assertEquals(inputs, restoredInputs);
    }

    @Test
    void requiresDatabaseReviewAndPreservesAReviewedServerBinding() {
        DeploymentForm form = form();
        assertThrows(IllegalArgumentException.class, form::databaseBindings);
        assertFalse(form.databaseDetails.isEnabled());

        form.databaseMode.setSelectedItem(DeploymentRuntimeParser.DatabaseReviewMode.POSTGRESQL);
        form.databaseDetails.setText(
                "primary|db.example.test|5432|shop|shop|database-password:4|required");
        assertTrue(form.databaseDetails.isEnabled());
        var connection = (ManagedDatabaseConnection.Server) form.databaseBindings().orElseThrow()
                .getFirst().connection();
        assertEquals("db.example.test", connection.host());
        assertEquals(4, connection.passwordReference().revision());

        DeploymentPageState state = form.capture("review output", null);
        DeploymentForm restored = form();
        restored.restore(state);
        assertEquals(DeploymentRuntimeParser.DatabaseReviewMode.POSTGRESQL,
                restored.databaseMode.getSelectedItem());
        assertEquals(form.databaseDetails.getText(), restored.databaseDetails.getText());
        assertEquals("review output", state.output());
    }

    @Test
    void recordsAnExplicitEmptyDatabaseScopeWithoutParsingRetainedExpertInput() {
        DeploymentForm form = form();
        form.databaseDetails.setText("retained expert input");
        form.databaseMode.setSelectedItem(DeploymentRuntimeParser.DatabaseReviewMode.NONE);

        assertEquals(java.util.List.of(), form.databaseBindings().orElseThrow());
        assertFalse(form.databaseDetails.isEnabled());
    }

    private static DeploymentForm form() {
        return new DeploymentForm(new PageMessagePresenter(
                MessageCatalog.forLanguageTag(MessageCatalog.ENGLISH_TAG)), () -> { });
    }
}
