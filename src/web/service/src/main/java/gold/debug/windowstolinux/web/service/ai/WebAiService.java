package gold.debug.windowstolinux.web.service.ai;

import tools.jackson.databind.JsonNode;
import gold.debug.windowstolinux.shared.ai.client.OpenAiCompatibleRoleClient;
import gold.debug.windowstolinux.shared.ai.collaboration.role.*;
import gold.debug.windowstolinux.shared.ai.collaboration.invocation.*;
import gold.debug.windowstolinux.shared.ai.collaboration.advice.AiAdviceDecision;
import gold.debug.windowstolinux.shared.ai.provider.ProviderEndpointPolicy;
import gold.debug.windowstolinux.shared.model.deployment.DeploymentInputField;
import gold.debug.windowstolinux.web.db.entity.*;
import gold.debug.windowstolinux.web.db.persistence.repository.WebResourceRepository;
import gold.debug.windowstolinux.web.secret.credential.WebCredentialStore;
import gold.debug.windowstolinux.web.service.contract.*;
import java.net.URI;
import java.time.Instant;
import java.util.*;

/** Selected-model verification and ordered validated AI advice; AI never authorizes remote actions. */
public final class WebAiService {
    private final WebResourceRepository repository;
    private final WebCredentialStore secrets;
    private final OpenAiCompatibleRoleClient client;
    public WebAiService(WebResourceRepository repository,WebCredentialStore secrets) { this(repository,secrets,new OpenAiCompatibleRoleClient()); }
    public WebAiService(WebResourceRepository repository,WebCredentialStore secrets,OpenAiCompatibleRoleClient client) { this.repository=repository;this.secrets=secrets;this.client=client; }
    public JsonNode list(WebRequestContext context) throws Exception { return WebJson.tree(ordered(context).stream().map(WebAiService::view).toList()); }
    public PreparedWebOperation save(WebRequestContext context,String id,JsonNode input) throws Exception {
        WebJson.fields(input,"name","endpoint","model","apiKey","version");
        String name=WebJson.text(input,"name",120),model=new ProviderEndpointPolicy().requireModel(WebJson.text(input,"model",128));
        URI endpoint=new ProviderEndpointPolicy().validateEndpoint(URI.create(WebJson.text(input,"endpoint",2048)));
        var old=id==null?Optional.<StoredResource>empty():Optional.of(require(context,id));
        if(old.map(StoredResource::version).orElse(0L)!=input.path("version").asLong(0)) throw new IllegalStateException("Model changed");
        String supplied=input.path("apiKey").asText(""); String secret;
        if(supplied.isEmpty()) {
            var saved=old.orElseThrow(() -> new IllegalArgumentException("API key required"));
            if(!WebJson.read(saved.document()).path("endpoint").asText().equals(endpoint.toString())) throw new IllegalArgumentException("Endpoint changes require a new key");
            secret=saved.attributes().get("secret_id").toString();
        } else {
            if(supplied.length()>65536) throw new IllegalArgumentException("API key too long");
            secret=secrets.save(scope(context),"ai",supplied.toCharArray());
        }
        String profileId=id==null?"ai-"+UUID.randomUUID():id;
        var safe=WebJson.object().put("profileId",profileId).put("name",name).put("endpoint",endpoint.toString()).put("model",model);
        return new PreparedWebOperation("AI_SAVE",safe,List.of(),List.of("ai:"+context.workspaceId()),true,null,null,null,interaction -> {
            interaction.progress("AI_TESTING",WebJson.object().put("profileId",profileId));
            var test=new ProjectAnalysisRoleContext("connection-test","JAVA_MAVEN_SPRING_BOOT","MAVEN_WRAPPER","FORMALLY_SUPPORTED",List.of());
            var result=secrets.use(scope(context),secret,"ai",key -> client.invoke(new AiRoleBinding(test.role(),profileId,endpoint,model),key,test));
            interaction.checkCancelled();
            if(result.evidence().status()!=AiInvocationStatus.VALIDATED) {
                interaction.completion(OperationCompletionState.FAILED); return WebJson.object().put("status","AI_TEST_FAILED");
            }
            var document=WebJson.object().put("endpoint",endpoint.toString()).put("model",model).put("verifiedAt",Instant.now().toString());
            int priority=old.map(row -> ((Number)row.attributes().get("priority")).intValue()).orElse(ordered(context).size());
            int enabled=old.map(row -> ((Number)row.attributes().get("enabled")).intValue()).orElse(1);
            return view(repository.save(scope(context),ResourceType.AI_PROFILE,profileId,name,Map.of("priority",priority,"enabled",enabled,"secret_id",secret,"secret_version",1),WebJson.write(document),old.map(StoredResource::version).orElse(0L)));
        });
    }
    public JsonNode enabled(WebRequestContext context,String id,JsonNode input) throws Exception {
        WebJson.fields(input,"enabled","version"); if(!input.path("enabled").isBoolean()) throw new IllegalArgumentException("Invalid enabled flag");
        var old=require(context,id); var values=new LinkedHashMap<>(old.attributes());values.put("enabled",input.path("enabled").asBoolean()?1:0);
        return view(repository.save(scope(context),ResourceType.AI_PROFILE,id,old.name(),values,old.document(),input.path("version").asLong(-1)));
    }
    public JsonNode reorder(WebRequestContext context,JsonNode input) throws Exception {
        WebJson.fields(input,"ids"); if(!input.path("ids").isArray()) throw new IllegalArgumentException("Invalid model order");
        var ids=new ArrayList<String>();for(var id:input.path("ids")) { if(!id.isTextual()) throw new IllegalArgumentException("Invalid model id");ids.add(id.asText()); }
        repository.reorderAi(scope(context),ids); return list(context);
    }
    public void delete(WebRequestContext context,String id,long version) throws Exception { repository.delete(scope(context),ResourceType.AI_PROFILE,id,version); }
    public Optional<AiRoleInvocationResult> invoke(WebRequestContext context,AiRoleContext role,TaskInteraction interaction) throws Exception {
        var snapshot=ordered(context).stream().filter(row -> ((Number)row.attributes().get("enabled")).intValue()==1).toList();
        for(var row:snapshot) {
            interaction.checkCancelled(); var document=WebJson.read(row.document()); AiRoleInvocationResult result=null;
            try { result=secrets.use(scope(context),row.attributes().get("secret_id").toString(),"ai",key -> client.invoke(new AiRoleBinding(role.role(),row.id(),URI.create(document.path("endpoint").asText()),document.path("model").asText()),key,role)); }
            catch(InterruptedException cancelled) { throw cancelled; }
            catch(Exception unavailable) { interaction.progress("AI_PROVIDER_UNAVAILABLE",WebJson.object().put("profileId",row.id())); }
            interaction.checkCancelled();
            if(result!=null) {
                interaction.progress("AI_ATTEMPT",WebJson.object().put("profileId",row.id()).put("status",result.evidence().status().name()));
                if(result.evidence().status()==AiInvocationStatus.VALIDATED) return Optional.of(result);
            }
        }
        return Optional.empty();
    }
    public Map<String,String> inputs(WebRequestContext context,List<DeploymentInputField> fields,TaskInteraction interaction) throws Exception {
        if(fields.isEmpty()) return Map.of();
        var supplied=new LinkedHashMap<String,String>();
        var result=invoke(context,new DeploymentInputRoleContext(fields.stream().limit(64).toList(),"Select only a uniquely evidenced supplied candidate; leave unknown free text unresolved.",List.of()),interaction);
        result.flatMap(value -> value.evidence().output()).filter(value -> value.decision()==AiAdviceDecision.CLEAR).ifPresent(advice -> {
            for(String finding:advice.findings()) { String[] pair=finding.split("=",2); if(pair.length==2) fields.stream().filter(field -> field.id().equals(pair[0]) && field.choices().size()==1 && field.choices().contains(pair[1])).findFirst().ifPresent(field -> supplied.put(field.id(),pair[1])); }
        });
        supplied.putAll(WebTaskPrompts.inputs(interaction,fields.stream().filter(field -> !supplied.containsKey(field.id())).toList()));
        return Map.copyOf(supplied);
    }
    private List<StoredResource> ordered(WebRequestContext context) throws Exception { return repository.list(scope(context),ResourceType.AI_PROFILE).stream().sorted(Comparator.comparingInt((StoredResource row) -> ((Number)row.attributes().get("priority")).intValue()).thenComparing(StoredResource::id)).toList(); }
    private StoredResource require(WebRequestContext context,String id) throws Exception { return repository.find(scope(context),ResourceType.AI_PROFILE,id).orElseThrow(NoSuchElementException::new); }
    private static JsonNode view(StoredResource row) {
        var document=WebJson.read(row.document());return WebJson.object().put("id",row.id()).put("name",row.name()).put("version",row.version())
                .put("endpoint",document.path("endpoint").asText()).put("model",document.path("model").asText()).put("verifiedAt",document.path("verifiedAt").asText())
                .put("enabled",((Number)row.attributes().get("enabled")).intValue()==1).put("priority",((Number)row.attributes().get("priority")).intValue()).put("credentialConfigured",true);
    }
    private static ResourceScope scope(WebRequestContext context) { return new ResourceScope(context.workspaceId(),context.userId()); }
}
