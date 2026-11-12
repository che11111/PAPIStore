package dev.che1s.papistore;

import org.bukkit.entity.Player;
import org.bukkit.OfflinePlayer;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class PlaceholderRenderer {
    private static final Method PLAYER_PING = findMethod("getPing");
    private final Logger logger;
    private final Method setPlaceholders;

    public PlaceholderRenderer(Logger logger) {
        this.logger = logger;
        this.setPlaceholders = resolvePlaceholderMethod();
    }

    public PlayerSnapshot capture(Player player, FieldSchema fields) {
        Map<String, String> values = new LinkedHashMap<>();
        for (Map.Entry<String, String> field : fields.asMap().entrySet()) {
            String column = field.getKey();
            String expression = field.getValue();
            try {
                String value = "name".equalsIgnoreCase(column)
                        ? player.getName()
                        : applyPlaceholders(player, expression);
                values.put(column, value == null ? "" : value);
            } catch (RuntimeException exception) {
                logger.log(
                        Level.SEVERE,
                        "Failed to render PAPI expression for column `" + column + "`",
                        exception
                );
                throw new IllegalStateException(
                        "PAPI expression failed for column " + column, exception
                );
            }
        }
        return new PlayerSnapshot(player.getUniqueId(), player.getName(), values);
    }

    private String applyPlaceholders(Player player, String expression) {
        try {
            Object result = setPlaceholders.invoke(null, player, expression);
            String value = result == null ? "" : result.toString();
            if (expression.equals(value)) {
                String builtIn = builtInValue(player, expression);
                if (builtIn != null) {
                    return builtIn;
                }
            }
            return value;
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Cannot access PlaceholderAPI", exception);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new IllegalStateException("PlaceholderAPI expansion failed", cause);
        }
    }

    private static Method resolvePlaceholderMethod() {
        try {
            Class<?> api = Class.forName("me.clip.placeholderapi.PlaceholderAPI");
            try {
                return api.getMethod("setPlaceholders", Player.class, String.class);
            } catch (NoSuchMethodException ignored) {
                return api.getMethod("setPlaceholders", OfflinePlayer.class, String.class);
            }
        } catch (ClassNotFoundException exception) {
            throw new IllegalStateException("PlaceholderAPI is not installed", exception);
        } catch (NoSuchMethodException exception) {
            throw new IllegalStateException(
                    "Installed PlaceholderAPI version is not compatible",
                    exception
            );
        }
    }

    private static String builtInValue(Player player, String expression) {
        switch (expression.toLowerCase(Locale.ROOT)) {
            case "%player_name%":
                return player.getName();
            case "%player_uuid%":
                return player.getUniqueId().toString();
            case "%player_world%":
                return player.getWorld().getName();
            case "%player_x%":
                return formatCoordinate(player.getLocation().getX());
            case "%player_y%":
                return formatCoordinate(player.getLocation().getY());
            case "%player_z%":
                return formatCoordinate(player.getLocation().getZ());
            case "%player_ping%":
                return playerPing(player);
            default:
                return null;
        }
    }

    private static String playerPing(Player player) {
        if (PLAYER_PING == null) {
            return null;
        }
        try {
            Object ping = PLAYER_PING.invoke(player);
            return ping == null ? null : ping.toString();
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private static String formatCoordinate(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static Method findMethod(String name) {
        try {
            return Player.class.getMethod(name);
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }
}
