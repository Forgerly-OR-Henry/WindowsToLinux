package gold.debug.windowstolinux.web.service.contract;

/** Business result mapped to the durable terminal state by the task executor. */
public enum OperationCompletionState { SUCCEEDED, FAILED, REVALIDATION_REQUIRED }
