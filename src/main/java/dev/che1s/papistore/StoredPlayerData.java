package dev.che1s.papistore;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class StoredPlayerData {
    private final UUID uniqueId;
    private final String playerName;
    private final String updatedAt;
    private final Map<String, String> values;

    public StoredPlayerData(
            UUID uniqueId,
            String playerName,
            String updatedAt,
            Map<String, String> values
    ) {
        this.uniqueId = uniqueId;
        this.playerName = playerName == null ? "" : playerName;
        this.updatedAt = updatedAt == null ? "" : updatedAt;
        this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    public UUID getUniqueId() {
        return uniqueId;
    }

    public String getPlayerName() {
        return playerName;
    }

    public String getUpdatedAt() {
        return updatedAt;
    }

    public Map<String, String> getValues() {
        return values;
    }
}
