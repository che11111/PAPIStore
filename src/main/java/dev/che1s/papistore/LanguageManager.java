package dev.che1s.papistore;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class LanguageManager {
    public static final String ENGLISH = "en_us";
    private static final Method PLAYER_LOCALE = findPlayerLocaleMethod();
    private static final Method PLAYER_SPIGOT = findPlayerSpigotMethod();
    private static final Method SPIGOT_LOCALE = findSpigotLocaleMethod();
    private static final List<String> SUPPORTED = Collections.unmodifiableList(Arrays.asList(
            ENGLISH,
            "zh_cn",
            "zh_tw",
            "ja_jp",
            "ko_kr",
            "de_de",
            "fr_fr",
            "es_es",
            "ru_ru"
    ));

    private final JavaPlugin plugin;
    private final String configuredLanguage;
    private final Map<String, YamlConfiguration> translations = new LinkedHashMap<>();

    public LanguageManager(JavaPlugin plugin) {
        this.plugin = plugin;
        String configured = plugin.getConfig().getString("language", "auto");
        this.configuredLanguage = configured == null
                ? "auto"
                : configured.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        loadTranslations();
    }

    public String message(CommandSender sender, String key, String... replacements) {
        String language = language(sender);
        String message = translations.get(language).getString(key);
        if (message == null) {
            message = translations.get(ENGLISH).getString(key);
        }
        if (message == null) {
            plugin.getLogger().warning("Missing translation key: " + key);
            message = key;
        }
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            message = message.replace(replacements[i], replacements[i + 1]);
        }
        return ChatColor.translateAlternateColorCodes('&', message);
    }

    public String language(CommandSender sender) {
        return resolveLanguage(configuredLanguage, senderLocale(sender));
    }

    static String resolveLanguage(String configured, String senderLocale) {
        String normalized = configured == null
                ? "auto"
                : configured.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        if (!"auto".equals(normalized)) {
            return SUPPORTED.contains(normalized) ? normalized : ENGLISH;
        }
        return normalizeLocale(senderLocale);
    }

    static String normalizeLocale(String locale) {
        if (locale == null || locale.trim().isEmpty()) {
            return ENGLISH;
        }
        String normalized = locale.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        if (SUPPORTED.contains(normalized)) {
            return normalized;
        }
        if (normalized.startsWith("zh_")) {
            return normalized.contains("tw")
                    || normalized.contains("hk")
                    || normalized.contains("mo")
                    || normalized.contains("hant")
                    ? "zh_tw"
                    : "zh_cn";
        }
        int separator = normalized.indexOf('_');
        String base = separator < 0 ? normalized : normalized.substring(0, separator);
        switch (base) {
            case "en":
                return ENGLISH;
            case "zh":
                return "zh_cn";
            case "ja":
                return "ja_jp";
            case "ko":
                return "ko_kr";
            case "de":
                return "de_de";
            case "fr":
                return "fr_fr";
            case "es":
                return "es_es";
            case "ru":
                return "ru_ru";
            default:
                return ENGLISH;
        }
    }

    private String senderLocale(CommandSender sender) {
        if (!(sender instanceof Player)) {
            return null;
        }
        try {
            Object locale = PLAYER_LOCALE == null ? null : PLAYER_LOCALE.invoke(sender);
            if (locale == null && PLAYER_SPIGOT != null && SPIGOT_LOCALE != null) {
                Object spigot = PLAYER_SPIGOT.invoke(sender);
                locale = spigot == null ? null : SPIGOT_LOCALE.invoke(spigot);
            }
            return locale == null ? null : locale.toString();
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private void loadTranslations() {
        for (String language : SUPPORTED) {
            String resourcePath = "lang/" + language + ".yml";
            File file = new File(new File(plugin.getDataFolder(), "lang"), language + ".yml");
            if (!file.isFile()) {
                plugin.saveResource(resourcePath, false);
            }

            YamlConfiguration bundled = loadBundled(resourcePath);
            YamlConfiguration current = YamlConfiguration.loadConfiguration(file);
            current.setDefaults(bundled);
            translations.put(language, current);
        }

        if (!"auto".equals(configuredLanguage) && !SUPPORTED.contains(configuredLanguage)) {
            plugin.getLogger().warning(
                    "Unsupported language: " + configuredLanguage + ", using English."
            );
        }
    }

    private YamlConfiguration loadBundled(String resourcePath) {
        InputStream stream = plugin.getResource(resourcePath);
        if (stream == null) {
            throw new IllegalStateException("Missing bundled language file: " + resourcePath);
        }
        try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read " + resourcePath, exception);
        }
    }

    private static Method findPlayerLocaleMethod() {
        try {
            return Player.class.getMethod("getLocale");
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    private static Method findPlayerSpigotMethod() {
        try {
            return Player.class.getMethod("spigot");
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    private static Method findSpigotLocaleMethod() {
        if (PLAYER_SPIGOT == null) {
            return null;
        }
        try {
            return PLAYER_SPIGOT.getReturnType().getMethod("getLocale");
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }
}
