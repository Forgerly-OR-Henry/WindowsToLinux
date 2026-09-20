package gold.debug.windowstolinux.app.ui.deployment.single;

import gold.debug.windowstolinux.shared.model.deployment.DatabaseReviewMode;

import gold.debug.windowstolinux.shared.deploy.input.DeploymentRuntimeParser;
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
    @Test void preservesRawHealthControlsAcrossAppearanceRebuilds() {
        DeploymentForm form = form();
        assertEquals("AUTOMATIC", form.input(true).healthMode());
        form.healthMode.setSelectedIndex(2);
        assertEquals("TCP", form.input(true).healthMode());
        assertTrue(form.input(true).healthEndpoint().isEmpty());
        form.healthEndpoint.setText("18080");
        DeploymentForm restored = form(); restored.restore(form.capture("", null));
        assertEquals(form.input(true), restored.input(true));
    }

    @Test
    void requiresDatabaseReviewAndPreservesAReviewedServerBinding() {
        DeploymentForm form = form();
        assertEquals(DatabaseReviewMode.UNREVIEWED, form.input(true).databaseMode());
        assertFalse(form.databaseDetails.isEnabled());

        form.databaseMode.setSelectedItem(DatabaseReviewMode.POSTGRESQL);
        form.databaseDetails.setText(
                "primary|db.example.test|5432|shop|shop|database-password:4|required");
        assertTrue(form.databaseDetails.isEnabled());
        assertEquals(form.databaseDetails.getText(), form.input(true).databaseDetails());

        DeploymentPageState state = form.capture("review output", null);
        DeploymentForm restored = form();
        restored.restore(state);
        assertEquals(DatabaseReviewMode.POSTGRESQL,
                restored.databaseMode.getSelectedItem());
        assertEquals(form.databaseDetails.getText(), restored.databaseDetails.getText());
        assertEquals("review output", state.output());
    }

    @Test
    void recordsAnExplicitEmptyDatabaseScopeWithoutParsingRetainedExpertInput() {
        DeploymentForm form = form();
        form.databaseDetails.setText("retained expert input");
        form.databaseMode.setSelectedItem(DatabaseReviewMode.NONE);

        assertEquals(DatabaseReviewMode.NONE, form.input(true).databaseMode());
        assertEquals("retained expert input", form.input(true).databaseDetails());
        assertFalse(form.databaseDetails.isEnabled());
    }

    private static DeploymentForm form() {
        return new DeploymentForm(new PageMessagePresenter(
                MessageCatalog.forLanguageTag(MessageCatalog.ENGLISH_TAG)), () -> { });
    }
}
