package gold.debug.windowstolinux.web.service.config;

import tools.jackson.databind.JsonNode;
import gold.debug.windowstolinux.shared.config.secretref.*;
import gold.debug.windowstolinux.shared.deploy.contract.spi.DatabaseCredentialPort;
import gold.debug.windowstolinux.web.db.entity.ResourceScope;
import gold.debug.windowstolinux.web.secret.credential.WebCredentialStore;
import gold.debug.windowstolinux.web.service.contract.*;
import java.util.*;

/** Web-owned encrypted application and transient decision secrets; plaintext is never in task records. */
public final class WebApplicationSecrets {
    private final WebCredentialStore store;
    public WebApplicationSecrets(WebCredentialStore store) { this.store=store; }
    public JsonNode save(WebRequestContext context, JsonNode input) throws Exception {
        WebJson.fields(input,"environment","value");
        String value = WebJson.text(input,"value",65536);
        if (!input.has("environment")) return WebJson.object().put("secretId",store.save(scope(context),"input",value.toCharArray()));
        String environment = WebJson.text(input,"environment",40);
        if (!environment.matches("[A-Z][A-Z0-9_]{0,39}")) throw new IllegalArgumentException("Invalid environment name");
        String id = "s-"+UUID.randomUUID().toString().replace("-","").substring(0,12)+".env."+environment.toLowerCase(Locale.ROOT).replace('_','-');
        store.saveRevision(scope(context),id,1,"application",value.toCharArray());
        return WebJson.object().put("identifier",id).put("revision",1);
    }
    public char[] decisionSecret(WebRequestContext context, TaskInteraction interaction, String code) throws Exception {
        var answer = interaction.decide("SECRET",WebJson.object().put("code",code));
        WebJson.fields(answer,"secretId");
        return store.use(scope(context),WebJson.text(answer,"secretId",63),"input",char[]::clone);
    }
    public DatabaseCredentialPort databaseCredentials(WebRequestContext context) {
        return new DatabaseCredentialPort() {
            @Override public Optional<SecretReference> latest(String identifier) throws Exception {
                int version=store.latestVersion(scope(context),identifier,"application");
                return version==0 ? Optional.empty() : Optional.of(new SecretReference(identifier,version));
            }
            @Override public char[] load(SecretReference reference) throws Exception {
                return store.useRevision(scope(context),reference.identifier(),Math.toIntExact(reference.revision()),"application",char[]::clone);
            }
            @Override public void save(SecretReference reference,char[] value) throws Exception {
                store.saveRevision(scope(context),reference.identifier(),Math.toIntExact(reference.revision()),"application",value);
            }
        };
    }
    public List<ResolvedSecretRevision> resolve(WebRequestContext context,List<SecretReference> references) throws Exception {
        List<ResolvedSecretRevision> result=new ArrayList<>();
        try {
            for (var reference:references) result.add(store.useRevision(scope(context),reference.identifier(),Math.toIntExact(reference.revision()),"application",value -> new ResolvedSecretRevision(reference,value)));
            return List.copyOf(result);
        } catch (Exception failure) { result.forEach(ResolvedSecretRevision::close); throw failure; }
    }
    private static ResourceScope scope(WebRequestContext context) { return new ResourceScope(context.workspaceId(),context.userId()); }
}
