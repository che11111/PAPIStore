package dev.che1s.papistore;

public final class MigrationResult {
    public enum Status {
        NEEDS_CONFIRM,
        COMPLETED,
        FAILED,
        NO_PENDING
    }

    private final Status status;
    private final DatabaseSettings.Type source;
    private final DatabaseSettings.Type target;
    private final int targetRows;
    private final String failureReason;

    private MigrationResult(
            Status status,
            DatabaseSettings.Type source,
            DatabaseSettings.Type target,
            int targetRows,
            String failureReason
    ) {
        this.status = status;
        this.source = source;
        this.target = target;
        this.targetRows = targetRows;
        this.failureReason = failureReason;
    }

    public static MigrationResult needsConfirmation(
            DatabaseSettings.Type source,
            DatabaseSettings.Type target,
            int targetRows
    ) {
        return new MigrationResult(
                Status.NEEDS_CONFIRM,
                source,
                target,
                targetRows,
                null
        );
    }

    public static MigrationResult completed(
            DatabaseSettings.Type source,
            DatabaseSettings.Type target
    ) {
        return new MigrationResult(Status.COMPLETED, source, target, 0, null);
    }

    public static MigrationResult failed(String reason) {
        return new MigrationResult(Status.FAILED, null, null, 0, reason);
    }

    public static MigrationResult noPending() {
        return new MigrationResult(Status.NO_PENDING, null, null, 0, null);
    }

    public Status getStatus() {
        return status;
    }

    public DatabaseSettings.Type getSource() {
        return source;
    }

    public DatabaseSettings.Type getTarget() {
        return target;
    }

    public int getTargetRows() {
        return targetRows;
    }

    public String getFailureReason() {
        return failureReason;
    }
}
