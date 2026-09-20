package gold.debug.windowstolinux.web.task.model;

/** Public durable task states; a browser cannot assign them. */
public enum TaskState {
    QUEUED, ANALYZING, WAITING_DECISION, RUNNING, CANCELLING, CANCELLED, SUCCEEDED, FAILED, INTERRUPTED, REVALIDATION_REQUIRED;
    public boolean terminal() { return switch (this) { case CANCELLED, SUCCEEDED, FAILED, INTERRUPTED, REVALIDATION_REQUIRED -> true; default -> false; }; }
}
