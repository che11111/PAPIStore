package dev.che1s.papistore;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class PlayerSnapshot {
    private final UUID uniqueId;
    private final String playerName;
    private final Map<String, String> values;

    public PlayerSnapshot(UUID uniqueId, String playerName, Map<String, String> values) {
        this.uniqueId = uniqueId;
        this.playerName = playerName == null ? "" : playerName;
        Map<String, String> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (entry.getKey() != null) {
                normalized.put(
                        entry.getKey().toLowerCase(Locale.ROOT),
                        entry.getValue() == null ? "" : entry.getValue()
                );
            }
        }
        this.values = Collections.unmodifiableMap(normalized);
    }

    public UUID getUniqueId() {
        return uniqueId;
    }

    public String getPlayerName() {
        return playerName;
    }

    public String getValue(String column) {
        if (column == null) {
            return "";
        }
        String value = values.get(column.toLowerCase(Locale.ROOT));
        return value == null ? "" : value;
    }
}
