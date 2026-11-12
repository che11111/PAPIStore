package dev.che1s.papistore;

public final class OperationResult {
    private final boolean success;
    private final String failureReason;

    private OperationResult(boolean success, String failureReason) {
        this.success = success;
        this.failureReason = failureReason;
    }

    public static OperationResult success() {
        return new OperationResult(true, "");
    }

    public static OperationResult failure(String reason) {
        String normalized = reason == null || reason.trim().isEmpty()
                ? "Unknown database error"
                : reason.trim();
        return new OperationResult(false, normalized);
    }

    public boolean isSuccess() {
        return success;
    }

    public String getFailureReason() {
        return failureReason;
    }
}
