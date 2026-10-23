package gold.debug.windowstolinux.mod.deployment.service.agent.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gold.debug.windowstolinux.mod.deployment.ai.agent.DeploymentToolSchemas;

/** Native descriptions of the existing strict graph and recipe contracts. / 既有严格图与配方合同的原生描述。 */
final class DeploymentAgentSchemas {
    private static final ObjectMapper JSON = new ObjectMapper();

    private DeploymentAgentSchemas() {
    }

    static ObjectNode assessment() {
        var schema = DeploymentToolSchemas.assessment();
        for (String field : java.util.List.of("suitability", "differences", "decision"))
            ((ObjectNode) schema.at("/properties/" + field)).put("maxLength", 1500);
        return schema;
    }

    static void actualArgumentBounds(java.util.Map<String, ObjectNode> schemas) {
        for (var schema : schemas.values()) {
            var properties = schema.path("properties");
            for (String field : java.util.List.of("component", "path", "query", "question", "reason", "identifier", "purpose",
                "beforeDigest", "sourceRevision")) {
                if (!properties.has(field)) continue;
                int maximum = switch (field) {
                    case "component" -> 63;
                    case "path" -> 500;
                    case "query" -> 256;
                    case "question", "reason" -> 2000;
                    case "identifier", "purpose" -> 128;
                    default -> 64;
                };
                ((ObjectNode) properties.get(field)).put("maxLength", maximum);
            }
        }
        ((ObjectNode) schemas.get("evidence_index").at("/properties/offset")).put("maximum", gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigLoader.current().ai().execution().maxObservations());
        ((ObjectNode) schemas.get("observation").at("/properties/offset")).put("maximum", 12000);
        ((ObjectNode) schemas.get("observation").at("/properties/id")).put("maxLength", 8);
        ((ObjectNode) schemas.get("source").at("/properties/kind")).put("maxLength", 8)
            .putArray("enum").add("list").add("read").add("search").add("index");
        for (String field : java.util.List.of("beforeDigest", "sourceRevision"))
            ((ObjectNode) schemas.get("patch").at("/properties/" + field)).put("minLength", 64);
    }

    static ObjectNode plan() {
        var plan = DeploymentToolSchemas.object("applicationId", "components:array", "applicationHealth:object", "explanation");
        property(plan, "applicationId", identifier());
        property(plan, "explanation", text(1, 2000));
        var component = DeploymentToolSchemas.object("id", "sourceRoot", "dependencies:array", "evidence:array",
            "backend", "mode", "entrypoint", "arguments:array", "workingDirectory", "health:object", "ports:array",
            "resources:array", "configuration:array", "databases:array", "secretReferences:array", "workspace:object");
        property(component, "id", identifier());
        property(component, "sourceRoot", text(1, 500).put("description", "Current source-relative component root; dot means the source root."));
        property(component, "dependencies", array(identifier(), 0, 15).put("uniqueItems", true)
            .put("description", "Declare component prerequisites from actual runtime consumption. Individual health does not establish independence."));
        property(component, "evidence", array(text(1, 500).put("pattern", "o[1-9][0-9]{0,5}"), 1, 32)
            .put("description", "Original current READ IDs inside this sourceRoot, as used in identification. IDENTIFIED/LIST/SEARCH/tool-result IDs do not prove source reads."));
        var workspace = DeploymentToolSchemas.object("mode", "minimumBuildBytes:integer", "preferredBuildBytes:integer", "explicitBytes:integer");
        property(workspace, "mode", enumeration("AUTO", "EXPLICIT"));
        for (String field : java.util.List.of("minimumBuildBytes", "preferredBuildBytes", "explicitBytes"))
            property(workspace, field, JSON.createObjectNode().put("type", "integer").put("minimum", 0).put("maximum", 137438953472L));
        workspace.put("description", "Source-backed build budget demand. AUTO: explicitBytes=0 and minimum<=preferred; product adds entire archive source copies and metadata margin. EXPLICIT: exact candidate bytes (64MiB..128GiB); minimum/preferred are build headroom estimates, not dependency-size proof. inspect supplies strict host/source/policy facts. Accepted selection is immutable once a candidate exists.");
        property(component, "workspace", workspace);
        property(component, "backend", enumeration("PROCESS", "DOCKER", "PODMAN"));
        property(component, "mode", enumeration("DAEMON", "ON_DEMAND").put("description", "ON_DEMAND requires COMMAND health."));
        property(component, "entrypoint", entrypoint());
        property(component, "arguments", array(text(0, 4000), 0, 64));
        property(component, "workingDirectory", text(0, 512).put("description", "Literal artifact-relative directory. Empty means artifact root."));
        property(component, "health", health());
        var port = DeploymentToolSchemas.object("id", "protocol", "host:integer", "target:integer", "exposure", "accessUrl");
        property(port, "id", identifier());
        property(port, "protocol", enumeration("HTTP", "HTTPS", "TCP", "UDP"));
        property(port, "host", integer(1, 65535));
        property(port, "target", integer(1, 65535));
        property(port, "exposure", enumeration("INTERNAL", "EXTERNAL"));
        property(port, "accessUrl", text(0, 4096).put("description", "Explicit credential-free HTTP/HTTPS public URL matching protocol and host port, or empty for non-browser/internal usage. requirements.publicUrl requires a matching EXTERNAL HTTP/HTTPS endpoint with that exact URL; TCP transport alone is insufficient."));
        property(component, "ports", array(port, 0, 32).put("description", "Endpoint declarations do not configure listeners or clients; match the actual runtime consumers."));
        var resource = DeploymentToolSchemas.object("id", "path", "access", "schema", "reversible:boolean", "type", "seedFile", "digest");
        property(resource, "id", identifier());
        property(resource, "path", text(1, 500).put("description", "Logical runtime access path. Publication replaces this artifact path with the managed binding: FILE exposes a directory tree; CONFIGURATION exposes a regular file. An ordinary sealed artifact file needs no persistent binding unless its runtime requirements need managed storage."));
        property(resource, "access", enumeration("READ_ONLY", "READ_WRITE"));
        property(resource, "schema", text(1, 64).put("pattern", "[a-z0-9][a-z0-9._-]{0,63}"));
        property(resource, "type", enumeration("FILE", "CONFIGURATION").put("description", "FILE is a persistent directory tree, not a single file. CONFIGURATION is an immutable read-only regular file. Choose from the actual storage consumer; names and extensions do not determine the type."));
        property(resource, "seedFile", text(0, 500).put("description", "Seed path relative to the sealed artifact source root. FILE: optional directory copied only on first initialization; empty creates an empty directory and existing data survives later publication. CONFIGURATION: required regular file matching the verified READ digest. The logical path's candidate content is not an implicit seed."));
        property(resource, "digest", text(0, 64).put("description", "CONFIGURATION: verified sourceSha256 from READ, with READ_ONLY and reversible=true. FILE: empty digest."));
        property(component, "resources", array(resource, 0, 32));
        var config = DeploymentToolSchemas.object("key", "value");
        property(config, "key", text(1, 64).put("description", "Nonsecret runtime environment key; it does not rewrite artifact consumers. Verify actual consumed keys, literal argv and override priority. Credential keys require secure capabilities."));
        var value = JSON.createObjectNode();
        value.putArray("type").add("string").add("integer").add("boolean");
        value.put("minLength", 1).put("maxLength", 1024).put("minimum", Long.MIN_VALUE).put("maximum", Long.MAX_VALUE);
        property(config, "value", value);
        property(component, "configuration", array(config, 0, 64));
        var databases = array(text(0, 63), 0, 32).put("uniqueItems", true);
        databases.remove("type");
        databases.putArray("type").add("array").add("null");
        databases.put("description", "Initial plan: null when preparation is unknown. [] only when none is proven. Nonempty IDs require completed databases preparation before replanning.");
        property(component, "databases", databases);
        var ref = DeploymentToolSchemas.object("identifier", "revision:integer");
        property(ref, "identifier", text(1, 128));
        property(ref, "revision", integer(1, Integer.MAX_VALUE));
        property(component, "secretReferences", array(ref, 0, 64).put("uniqueItems", true));
        property(plan, "components", array(component, 1, 16));
        var gate = DeploymentToolSchemas.object("component", "health:object");
        property(gate, "component", identifier());
        property(gate, "health", health());
        property(plan, "applicationHealth", gate);
        return plan;
    }

    static ObjectNode recipe() {
        var recipe = DeploymentToolSchemas.object("version", "primary", "secondary", "jvmTarget", "servicePort:integer");
        for (String field : java.util.List.of("version", "primary", "secondary", "jvmTarget"))
            property(recipe, field, text(0, 512));
        ((ObjectNode) recipe.at("/properties/version")).put("description", "Runtime/compiler version for the actual analyzed project type; STATIC_SITE uses optional decimal Node major, CMAKE_SERVICE uses a preset, and PYTHON_SERVICE instead reads its Python version from primary.");
        ((ObjectNode) recipe.at("/properties/primary")).put("description", "Type-specific artifact name, source/output directory or mode. RUBY_SERVICE uses bundle or source; PHP_SERVICE uses public or source. Read the type mapping below.");
        ((ObjectNode) recipe.at("/properties/secondary")).put("description", "Type-specific main class, Python module, source entry file or CMake target. Paths are relative to the component source root, not the original multi-component root; never a shell command or interpreter invocation.");
        ((ObjectNode) recipe.at("/properties/jvmTarget")).put("description", "Explicit Java version for KOTLIN_SERVICE; unused for other recipe types.");
        property(recipe, "servicePort", integer(0, 65535).put("description", "PHP/Ruby recipe runtime port; zero is available only under the existing runtime's CLI/zero-port shapes. This does not set the independently planned publication ports. Unused for other types."));
        recipe.put("description", "Use the actual analyze facts.projectType and submit all five fields yourself. Admission alone does not validate an arbitrary recipe. Unused fields remain explicit bounded inputs; software never supplies values. Relative paths prohibit absolute paths, '..', repeated separators and shell syntax. "
            + java.util.Arrays.stream(gold.debug.windowstolinux.mod.deployment.service.core.model.project.kind.DeploymentProjectKind.values())
            .filter(type -> type.deployable() || type == gold.debug.windowstolinux.mod.deployment.service.core.model.project.kind.DeploymentProjectKind.RECOGNITION_PREVIEW)
            .map(DeploymentAgentProtocol::recipeContract).collect(java.util.stream.Collectors.joining(" ")));
        return recipe;
    }

    static ObjectNode databaseSource() {
        var schema = DeploymentToolSchemas.object("component", "sourceSelection:object");
        property(schema, "component", identifier());
        var selection = DeploymentToolSchemas.object("declarationRoot", "databaseIds:array", "declarationEvidence:array", "consumptionEvidence:array");
        property(selection, "declarationRoot", text(1, 500).put("description", "Explicit declaration directory in the current full frozen snapshot; dot means its root. This does not change the component's build sourceRoot."));
        property(selection, "databaseIds", array(text(1, 32).put("pattern", "[a-z][a-z0-9-]{0,31}"), 1, 16).put("uniqueItems", true)
            .put("description", "Choose the actual nonempty subset of this manifest's DB IDs consumed by this component. A project-wide declaration alone does not assign every DB to every component."));
        var evidence = array(text(2, 7).put("pattern", "o[1-9][0-9]{0,5}"), 1, 32).put("uniqueItems", true);
        property(selection, "declarationEvidence", evidence.deepCopy().put("description", "Current original READ IDs for the exact windowstolinux-db.properties at declarationRoot and any selected declaration SQL members. Other out-of-component READs are not permitted."));
        property(selection, "consumptionEvidence", evidence.deepCopy().put("description", "Current original READ IDs inside this component's sourceRoot showing its database consumption. Select ownership yourself from these source facts."));
        property(schema, "sourceSelection", selection);
        return schema;
    }

    static ObjectNode databases() {
        var schema = DeploymentToolSchemas.object("component", "selections:array");
        property(schema, "component", identifier());
        var selection = DeploymentToolSchemas.object("databaseId", "instanceId");
        property(selection, "databaseId", text(1, 32).put("pattern", "[a-z][a-z0-9-]{0,31}")
            .put("description", "Actual non-SQLite database ID from this component's current READY inventory; databaseId must be unique across selections."));
        property(selection, "instanceId", text(0, 256).put("description", "Required explicit string: an observed instanceId selects that existing native instance; an empty string proposes a new native installation and still requires independent business/install approval."));
        property(schema, "selections", array(selection, 0, 32).put("uniqueItems", true)
            .put("description", "Exactly one selection per native (non-SQLite) database in the current READY inventory, with unique databaseId values. Exclude SQLite rows. SQLite-only inventories require selections:[]; this does not mean plan.databases is empty. Replan with all actual prepared IDs, including SQLite."));
        return schema;
    }

    private static ObjectNode health() {
        var schema = JSON.createObjectNode().put("type", "object");
        var variants = schema.putArray("oneOf");
        var process = DeploymentToolSchemas.object("type", "timeout:integer", "stability:integer");
        property(process, "type", enumeration("PROCESS"));
        property(process, "timeout", integer(1, 300));
        property(process, "stability", integer(1, 300).put("description", "Required continuous interval in seconds; must not exceed this PROCESS timeout."));
        variants.add(process);
        var tcp = DeploymentToolSchemas.object("type", "port:integer", "timeout:integer", "stability:integer");
        property(tcp, "type", enumeration("TCP"));
        property(tcp, "port", integer(1, 65535));
        property(tcp, "timeout", integer(1, 300));
        property(tcp, "stability", integer(1, 300));
        variants.add(tcp);
        var http = DeploymentToolSchemas.object("type", "url", "status:integer", "timeout:integer");
        property(http, "type", enumeration("HTTP"));
        property(http, "url", text(1, 2048).put("description", "Plain http/https URL on the candidate host's loopback address: use 127.0.0.1 or localhost with the actual service port and path. External/server/public addresses are rejected. No user-info or fragment. Submit the intended URL yourself; software never rewrites it."));
        property(http, "status", integer(200, 399));
        property(http, "timeout", integer(1, 300));
        variants.add(http);
        var command = DeploymentToolSchemas.object("type", "entrypoint", "arguments:array", "expected", "timeout:integer");
        property(command, "type", enumeration("COMMAND"));
        property(command, "entrypoint", entrypoint());
        property(command, "arguments", array(text(0, 4000), 0, 64));
        property(command, "expected", text(1, 4096));
        property(command, "timeout", integer(1, 300));
        variants.add(command);
        return schema;
    }

    private static ObjectNode entrypoint() {
        return text(1, 512).put("description", "Literal executable path relative to the intended artifact. The initial plan may declare an output or launcher to build next; its actual executable layout must be verified before seal. Host interpreters require an artifact launcher. No absolute path or shell expansion.");
    }

    private static ObjectNode identifier() {
        return text(1, 63).put("pattern", "[a-z0-9][a-z0-9-]{0,62}");
    }

    private static ObjectNode text(int min, int max) {
        return JSON.createObjectNode().put("type", "string").put("minLength", min).put("maxLength", max);
    }

    private static ObjectNode integer(int min, int max) {
        return JSON.createObjectNode().put("type", "integer").put("minimum", min).put("maximum", max);
    }

    private static ObjectNode enumeration(String... values) {
        var schema = JSON.createObjectNode().put("type", "string");
        var choices = schema.putArray("enum");
        for (String value : values) choices.add(value);
        return schema;
    }

    private static ObjectNode array(ObjectNode item, int min, int max) {
        var schema = JSON.createObjectNode().put("type", "array").put("minItems", min).put("maxItems", max);
        schema.set("items", item);
        return schema;
    }

    private static void property(ObjectNode object, String key, ObjectNode value) {
        ((ObjectNode) object.get("properties")).set(key, value);
    }
}
