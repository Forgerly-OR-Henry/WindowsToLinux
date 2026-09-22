package gold.debug.samples.tasks;

import java.util.List;

public final class Models {
    private Models() {
    }
    public record TaskInput(long projectId, String title, String description, long ownerId, int priority,
            List<String> labels, List<Long> dependsOn, String dueDate, long version, long actorId) {
    }

    public record Transition(String status, long version, long actorId) {
    }

    public record Comment(String text, long actorId, String requestId) {
    }

    public record Project(String name) {
    }

    public static final class BusinessError extends RuntimeException {
        public final int status;
        public BusinessError(int status, String message) {
            super(message);
            this.status = status;
        }
    }
}
