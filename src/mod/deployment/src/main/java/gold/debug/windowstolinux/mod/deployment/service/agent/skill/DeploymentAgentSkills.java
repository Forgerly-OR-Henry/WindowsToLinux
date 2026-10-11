package gold.debug.windowstolinux.mod.deployment.service.agent.skill;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import gold.debug.windowstolinux.appapi.task.run.TaskContext;

/** Version-owned product skills. User source paths and tool results cannot register instructions. */
public final class DeploymentAgentSkills {
    public enum Role {DEPLOYMENT, APPROVAL}

    private record Skill(String name, String description, String document, String body, Set<Role> roles) {
    }

    private static final Map<String, Skill> SKILLS = load();

    private final TaskContext task;

    private final Role role;

    private final Set<String> active = new LinkedHashSet<>();

    public DeploymentAgentSkills(TaskContext task, Role role) {
        this.task = java.util.Objects.requireNonNull(task);
        this.role = java.util.Objects.requireNonNull(role);
    }

    public static List<Map<String, String>> catalog(Role role) {
        return SKILLS.values().stream().filter(skill -> skill.roles().contains(role)).map(skill ->
            Map.of("id", skill.name(), "name", skill.name(), "description", skill.description())).toList();
    }

    public Map<String, Object> read(String id, TaskContext caller) {
        if (caller != task || task.cancelToken().requested())
            throw new IllegalArgumentException("Skill requires the original active task");
        var skill = require(id, role);
        active.add(id);
        return Map.of("trust", "PRODUCT_SKILL", "task", task.id().toString(), "role", role.name(), "id", id,
            "content", skill.document(), "authority", "NONE", "activation", "CURRENT_CONTEXT");
    }

    public List<String> active() {
        return List.copyOf(active);
    }

    public String system() {
        return system(role, active());
    }

    public static String system(Role role, Object selected) {
        if (!(selected instanceof Iterable<?> ids))
            throw new IllegalArgumentException("Invalid product skill selection");
        var text = new StringBuilder();
        for (var id : ids) {
            if (!(id instanceof String name)) throw new IllegalArgumentException("Invalid product skill identity");
            var skill = require(name, role);
            text.append("\nTrusted version-owned product skill ").append(name).append(" (no execution authority):\n").append(skill.body());
        }
        return text.toString();
    }

    private static Skill require(String id, Role role) {
        var skill = SKILLS.get(id);
        if (skill == null || !skill.roles().contains(role))
            throw new IllegalArgumentException("Unknown or foreign-role product skill");
        return skill;
    }

    private static Map<String, Skill> load() {
        var skills = new LinkedHashMap<String, Skill>();
        register(skills, "approval-discussion", Set.of(Role.APPROVAL));
        register(skills, "approval-context", Set.of(Role.APPROVAL));
        register(skills, "deployment-diagnosis", Set.of(Role.DEPLOYMENT));
        register(skills, "publication-verification", Set.of(Role.DEPLOYMENT, Role.APPROVAL));
        return java.util.Collections.unmodifiableMap(skills);
    }

    private static void register(Map<String, Skill> skills, String id, Set<Role> roles) {
        try (var input = DeploymentAgentSkills.class.getResourceAsStream(id + "/SKILL.md")) {
            if (input == null) throw new IllegalStateException("Missing product skill " + id);
            byte[] bytes = input.readNBytes(8001);
            if (bytes.length > 8000) throw new IllegalStateException("Product skill exceeds its context bound");
            String document = new String(bytes, StandardCharsets.UTF_8).replace("\r\n", "\n");
            int end = document.indexOf("\n---\n", 4);
            if (!document.startsWith("---\n") || end < 0) throw new IllegalStateException("Missing skill frontmatter");
            var metadata = new LinkedHashMap<String, String>();
            for (String line : document.substring(4, end).split("\n")) {
                int colon = line.indexOf(':');
                if (colon < 1 || metadata.put(line.substring(0, colon), line.substring(colon + 1).strip()) != null)
                    throw new IllegalStateException("Invalid product skill metadata");
            }
            if (!metadata.keySet().equals(Set.of("name", "description")) || !id.equals(metadata.get("name"))
                || metadata.get("description").isBlank() || metadata.get("description").length() > 512)
                throw new IllegalStateException("Invalid product skill discovery identity");
            String body = document.substring(end + 5).strip();
            if (body.isBlank()) throw new IllegalStateException("Empty product skill body");
            skills.put(id, new Skill(id, metadata.get("description"), document, body, roles));
        } catch (IOException failure) {
            throw new IllegalStateException("Product skill unavailable", failure);
        }
    }
}
