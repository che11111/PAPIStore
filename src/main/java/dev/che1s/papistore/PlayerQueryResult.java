package dev.che1s.papistore;

public final class PlayerQueryResult {
    private final StoredPlayerData playerData;
    private final String failureReason;

    private PlayerQueryResult(StoredPlayerData playerData, String failureReason) {
        this.playerData = playerData;
        this.failureReason = failureReason;
    }

    public static PlayerQueryResult found(StoredPlayerData playerData) {
        return new PlayerQueryResult(playerData, null);
    }

    public static PlayerQueryResult notFound() {
        return new PlayerQueryResult(null, null);
    }

    public static PlayerQueryResult failure(String reason) {
        return new PlayerQueryResult(null, reason == null ? "Database query failed" : reason);
    }

    public boolean isFound() {
        return playerData != null;
    }

    public boolean isFailure() {
        return failureReason != null;
    }

    public StoredPlayerData getPlayerData() {
        return playerData;
    }

    public String getFailureReason() {
        return failureReason;
    }
}
