package gold.debug.windowstolinux.web.service.deployment;

import gold.debug.windowstolinux.shared.analyze.ecosystem.db.DatabaseProjectInspector;
import gold.debug.windowstolinux.shared.deploy.contract.*;
import gold.debug.windowstolinux.shared.deploy.execution.environment.NativeDatabasePreparationService;
import gold.debug.windowstolinux.shared.model.deployment.DeploymentInputField;
import gold.debug.windowstolinux.web.service.config.WebApplicationSecrets;
import gold.debug.windowstolinux.web.service.contract.*;
import gold.debug.windowstolinux.web.service.server.WebServerService;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CancellationException;

/** Browser decisions and scoped credentials around the shared native DB policy. */
public final class WebDatabaseService {
    private final WebApplicationSecrets secrets;
    private final WebServerService servers;
    private final gold.debug.windowstolinux.web.service.ai.WebAiService ai;
    public WebDatabaseService(WebApplicationSecrets secrets, WebServerService servers,gold.debug.windowstolinux.web.service.ai.WebAiService ai) { this.secrets=secrets;this.servers=servers;this.ai=ai; }
    public DatabaseProjectInspector.Assessment inputs(WebRequestContext context, Path root,String applicationId,Map<String,String> values,TaskInteraction interaction) throws Exception {
        var assessment = new DatabaseProjectInspector().inspect(root);
        if (values.get("type").equals("DOCKERFILE_CONTAINER")) assessment = DatabaseProjectInspector.containerStorage(assessment);
        if (assessment.databases().isEmpty() && !assessment.unknownDatabase()) return assessment;
        if (values.containsKey("databaseMode")) {
            String mode=values.get("databaseMode");
            if (Set.of("NONE","UNREVIEWED").contains(mode) || assessment.databases().size()>1
                    || assessment.databases().stream().anyMatch(db -> !db.engine().name().equals(mode)) || assessment.schemaReviewRequired())
                throw new IllegalArgumentException("Manual DB bindings conflict with declared database/schema requirements");
            return assessment;
        }
        if (values.get("type").equals("DOCKERFILE_CONTAINER") && assessment.databases().stream().anyMatch(database -> database.engine()!=gold.debug.windowstolinux.shared.model.ecosystem.db.DatabaseEngineType.SQLITE)) throw new IllegalArgumentException("Container databases require an explicitly reachable binding");
        if (assessment.endpointConfirmationRequired()) WebTaskPrompts.approve(interaction,"db.nativeEndpoint",WebJson.object());
        return new NativeDatabasePreparationService(secrets.databaseCredentials(context)).completeInputs(root,applicationId,assessment,interaction(context,interaction));
    }
    public AutomaticDatabasePreparation prepare(WebRequestContext context,String serverId,Path root,String applicationId,
            DatabaseProjectInspector.Assessment assessment,Map<String,String> values,TaskInteraction interaction) throws Exception {
        if (values.containsKey("databaseMode") || assessment.databases().isEmpty()) return AutomaticDatabasePreparation.empty();
        return servers.withSession(context,serverId,interaction,session -> new NativeDatabasePreparationService(secrets.databaseCredentials(context))
                .prepare(root,applicationId,serverId,session.nativeDatabases(),assessment,interaction(context,interaction),
                        message -> WebTaskPrompts.progress(interaction,message.key(),WebJson.object())));
    }
    private AutomaticDeploymentInteraction interaction(WebRequestContext context,TaskInteraction task) {
        return new AutomaticDeploymentInteraction() {
            @Override public Optional<Map<String,String>> requestInputs(List<DeploymentInputField> fields) {
                try { return Optional.of(ai.inputs(context,fields,task)); }
                catch (InterruptedException cancelled) { Thread.currentThread().interrupt(); throw new CancellationException(); }
                catch (Exception failure) { throw new IllegalStateException("Cannot collect database inputs",failure); }
            }
            @Override public boolean confirm(String key,Map<String,?> details) { return WebTaskPrompts.confirm(task,key,WebJson.tree(details)); }
            @Override public boolean confirmDatabaseReplacement(Map<String,?> details) {
                try {
                    var answer=task.decide("DATABASE_REPLACEMENT",WebJson.tree(details));
                    WebJson.fields(answer,"backupConfirmed","downtimeConfirmed","replacementConfirmed");
                    return answer.path("backupConfirmed").asBoolean(false) && answer.path("downtimeConfirmed").asBoolean(false) && answer.path("replacementConfirmed").asBoolean(false);
                } catch (InterruptedException cancelled) { Thread.currentThread().interrupt(); throw new CancellationException(); }
                catch (Exception failure) { throw new IllegalStateException("Cannot confirm database replacement",failure); }
            }
            @Override public char[] requestSecret(String key) {
                try { return secrets.decisionSecret(context,task,key); }
                catch (InterruptedException cancelled) { Thread.currentThread().interrupt(); throw new CancellationException(); }
                catch (Exception failure) { throw new IllegalStateException("Cannot resolve database credential",failure); }
            }
        };
    }
}
