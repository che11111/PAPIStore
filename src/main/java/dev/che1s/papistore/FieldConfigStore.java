package dev.che1s.papistore;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

public final class FieldConfigStore {
    private static final String FIELDS = "fields";
    private static final String PLACEHOLDER = "placeholder";
    private static final String DISPLAY_NAME = "display-name";
    private static final String ENGLISH = "en_us";
    private static final String CHINESE = "zh_cn";
    private static final String HEADER =
            "PAPIStore 字段配置 / PAPIStore field configuration.\n"
                    + "placeholder 是 PAPI 表达式 / placeholder is the PAPI expression.\n"
                    + "display-name 按语言保存查询备注名，例如 zh_cn、en_us、ja_jp。\n"
                    + "display-name stores query labels by language, such as zh_cn, en_us, ja_jp.";
    private static final Map<String, PresetField> PRESETS = createPresets();

    private final File file;
    private final File temporaryFile;
    private final Logger logger;
    private YamlConfiguration configuration;

    public FieldConfigStore(File file, Logger logger) {
        this.file = file;
        this.temporaryFile = new File(file.getParentFile(), file.getName() + ".tmp");
        this.logger = logger;
    }

    public synchronized boolean exists() {
        return file.isFile();
    }

    public synchronized Map<String, String> loadOrCreate() throws IOException {
        if (!exists()) {
            configuration = new YamlConfiguration();
            configuration.options().header(HEADER);
            ConfigurationSection fields = configuration.createSection(FIELDS);
            for (PresetField preset : PRESETS.values()) {
                writeField(fields, preset);
            }
            saveAtomically();
            return readFields();
        }

        configuration = YamlConfiguration.loadConfiguration(file);
        configuration.options().header(HEADER);
        normalizeLegacyFormat();
        boolean changed = enforceReservedNamePlaceholder();
        if (ensurePresets()) {
            changed = true;
        }
        if (changed) {
            saveAtomically();
        }
        return readFields();
    }

    public synchronized boolean put(
            String column,
            String expression,
            String language,
            String displayName
    ) {
        try {
            if (configuration == null) {
                configuration = YamlConfiguration.loadConfiguration(file);
            }
            ConfigurationSection field = fieldSection(column);
            if ("name".equalsIgnoreCase(column)) {
                if (expression != null
                        && !"%player_name%".equalsIgnoreCase(expression.trim())) {
                    logger.warning(
                            "fields.name.placeholder is reserved and must stay "
                                    + "%player_name%; restoring the default value"
                    );
                }
                field.set(PLACEHOLDER, "%player_name%");
            } else {
                field.set(PLACEHOLDER, expression);
            }
            Map<String, String> labels = readDisplayNames(field, column);
            labels.put(normalizeLanguage(language), displayName);
            writeDisplayNames(field, labels);
            saveAtomically();
            return true;
        } catch (IOException exception) {
            logger.severe("Could not save fields.yml: " + exception.getMessage());
            return false;
        }
    }

    public synchronized String getDisplayName(String column, String language) {
        if (configuration == null) {
            return column;
        }
        Object raw = configuration.get(FIELDS + "." + column);
        if (!(raw instanceof ConfigurationSection)) {
            String legacy = configuration.getString("display-names." + column);
            return normalize(legacy, column);
        }

        Map<String, String> labels =
                readDisplayNames((ConfigurationSection) raw, column);
        String value = labels.get(normalizeLanguage(language));
        if (value == null) {
            value = labels.get(ENGLISH);
        }
        return normalize(value, column);
    }

    private Map<String, String> readFields() {
        Map<String, String> fields = new LinkedHashMap<>();
        ConfigurationSection section = configuration.getConfigurationSection(FIELDS);
        if (section != null) {
            for (String column : section.getKeys(false)) {
                if (!SqlIdentifiers.isValidColumn(column)) {
                    logger.warning("Ignoring invalid field key in fields.yml: " + column);
                    continue;
                }
                Object raw = section.get(column);
                String expression = raw instanceof ConfigurationSection
                        ? ((ConfigurationSection) raw).getString(PLACEHOLDER)
                        : raw instanceof String ? (String) raw : null;
                if (expression != null && !expression.trim().isEmpty()) {
                    fields.put(column, expression);
                }
            }
        }
        if (!fields.containsKey("name")) {
            fields.put("name", "%player_name%");
        }
        return fields;
    }

    private void normalizeLegacyFormat() throws IOException {
        ConfigurationSection oldFields = configuration.getConfigurationSection(FIELDS);
        if (oldFields == null) {
            return;
        }

        Map<String, String> expressions = readFields();
        Map<String, Map<String, String>> labels = new LinkedHashMap<>();
        boolean legacy =
                configuration.getConfigurationSection("display-names") != null;
        for (String column : expressions.keySet()) {
            Object raw = oldFields.get(column);
            labels.put(column, readDisplayNames(raw, column));
            if (!(raw instanceof ConfigurationSection)) {
                legacy = true;
                continue;
            }
            Object displayName = ((ConfigurationSection) raw).get(DISPLAY_NAME);
            if (displayName != null && !(displayName instanceof ConfigurationSection)) {
                legacy = true;
            }
        }
        if (!legacy) {
            return;
        }

        configuration.set(FIELDS, null);
        configuration.set("display-names", null);
        ConfigurationSection fields = configuration.createSection(FIELDS);
        for (Map.Entry<String, String> entry : expressions.entrySet()) {
            ConfigurationSection field = fields.createSection(entry.getKey());
            field.set(PLACEHOLDER, entry.getValue());
            writeDisplayNames(field, labels.get(entry.getKey()));
        }
        saveAtomically();
    }

    private boolean ensurePresets() {
        ConfigurationSection fields = configuration.getConfigurationSection(FIELDS);
        if (fields == null) {
            fields = configuration.createSection(FIELDS);
        }

        boolean changed = false;
        for (PresetField preset : PRESETS.values()) {
            ConfigurationSection field = fields.getConfigurationSection(preset.column);
            if (field == null) {
                writeField(fields, preset);
                changed = true;
                continue;
            }
            Map<String, String> labels = readDisplayNames(field, preset.column);
            boolean fieldChanged = false;
            for (Map.Entry<String, String> entry : preset.displayNames.entrySet()) {
                if (!labels.containsKey(entry.getKey())) {
                    labels.put(entry.getKey(), entry.getValue());
                    fieldChanged = true;
                }
            }
            if (fieldChanged) {
                writeDisplayNames(field, labels);
                changed = true;
            }
        }
        return changed;
    }

    private boolean enforceReservedNamePlaceholder() {
        ConfigurationSection fields = configuration.getConfigurationSection(FIELDS);
        if (fields == null) {
            return false;
        }
        ConfigurationSection name = fields.getConfigurationSection("name");
        if (name == null) {
            return false;
        }
        String expression = name.getString(PLACEHOLDER);
        if ("%player_name%".equalsIgnoreCase(
                expression == null ? "" : expression.trim()
        )) {
            return false;
        }
        logger.warning(
                "fields.name.placeholder is reserved and must stay %player_name%; "
                        + "restoring the default value"
        );
        name.set(PLACEHOLDER, "%player_name%");
        return true;
    }

    private ConfigurationSection fieldSection(String column) {
        ConfigurationSection fields = configuration.getConfigurationSection(FIELDS);
        if (fields == null) {
            fields = configuration.createSection(FIELDS);
        }
        ConfigurationSection field = fields.getConfigurationSection(column);
        if (field == null) {
            fields.set(column, null);
            field = fields.createSection(column);
        }
        return field;
    }

    private Map<String, String> readDisplayNames(Object raw, String column) {
        Map<String, String> labels = new LinkedHashMap<>();
        Object displayName = raw instanceof ConfigurationSection
                ? ((ConfigurationSection) raw).get(DISPLAY_NAME)
                : configuration.getString("display-names." + column);
        if (displayName instanceof ConfigurationSection) {
            ConfigurationSection section = (ConfigurationSection) displayName;
            for (String language : section.getKeys(false)) {
                String value = section.getString(language);
                if (value != null && !value.trim().isEmpty()) {
                    labels.put(normalizeLanguage(language), value);
                }
            }
        } else if (displayName instanceof String) {
            String value = (String) displayName;
            labels.put(CHINESE, value);
            labels.put(ENGLISH, value);
        }
        return labels;
    }

    private static void writeField(
            ConfigurationSection fields,
            PresetField preset
    ) {
        ConfigurationSection field = fields.createSection(preset.column);
        field.set(PLACEHOLDER, preset.expression);
        writeDisplayNames(field, preset.displayNames);
    }

    private static void writeDisplayNames(
            ConfigurationSection field,
            Map<String, String> displayNames
    ) {
        if (displayNames == null || displayNames.isEmpty()) {
            field.set(DISPLAY_NAME, null);
            return;
        }
        field.set(DISPLAY_NAME, null);
        ConfigurationSection section = field.createSection(DISPLAY_NAME);
        for (Map.Entry<String, String> entry : displayNames.entrySet()) {
            section.set(entry.getKey(), entry.getValue());
        }
    }

    private static String normalize(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value;
    }

    private static String normalizeLanguage(String language) {
        return language == null
                ? ENGLISH
                : language.trim().toLowerCase(Locale.ROOT).replace('-', '_');
    }

    private static Map<String, PresetField> createPresets() {
        Map<String, PresetField> presets = new LinkedHashMap<>();
        addPreset(
                presets, "name", "%player_name%",
                "玩家名称", "Player Name"
        );
        addPreset(
                presets, "player_uuid", "%player_uuid%",
                "玩家UUID", "Player UUID"
        );
        addPreset(
                presets, "world", "%player_world%",
                "当前世界", "Current World"
        );
        addPreset(
                presets, "x", "%player_x%",
                "X坐标", "X Coordinate"
        );
        addPreset(
                presets, "y", "%player_y%",
                "Y坐标", "Y Coordinate"
        );
        addPreset(
                presets, "z", "%player_z%",
                "Z坐标", "Z Coordinate"
        );
        addPreset(
                presets, "ping", "%player_ping%",
                "延迟", "Ping"
        );
        return presets;
    }

    private static void addPreset(
            Map<String, PresetField> presets,
            String column,
            String expression,
            String chinese,
            String english
    ) {
        Map<String, String> displayNames = new LinkedHashMap<>();
        displayNames.put(CHINESE, chinese);
        displayNames.put(ENGLISH, english);
        presets.put(
                column,
                new PresetField(column, expression, displayNames)
        );
    }

    private static final class PresetField {
        private final String column;
        private final String expression;
        private final Map<String, String> displayNames;

        private PresetField(
                String column,
                String expression,
                Map<String, String> displayNames
        ) {
            this.column = column;
            this.expression = expression;
            this.displayNames = displayNames;
        }
    }

    private void saveAtomically() throws IOException {
        configuration.save(temporaryFile);
        try {
            Files.move(
                    temporaryFile.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
            );
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(
                    temporaryFile.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
            );
        }
    }
}
