package dev.che1s.papistore.metrics;

import dev.che1s.papistore.PapiStorePlugin;
import dev.che1s.papistore.PlatformScheduler;
import org.bstats.MetricsBase;
import org.bstats.json.JsonObjectBuilder;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.UUID;
import java.util.logging.Level;


final class RegionMetrics {
    private final MetricsBase base;

    RegionMetrics(
            PapiStorePlugin plugin,
            PlatformScheduler scheduler,
            int serviceId
    ) throws IOException {
        File file = new File(plugin.getDataFolder().getParentFile(), "bStats/config.yml");
        File parent = file.getParentFile();
        if (parent != null) {
            Files.createDirectories(parent.toPath());
        }

        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        boolean newlyCreated = !file.isFile() || !config.isSet("serverUuid");
        if (!config.isSet("serverUuid")) {
            config.set("serverUuid", UUID.randomUUID().toString());
        }
        config.addDefault("enabled", true);
        config.addDefault("logFailedRequests", false);
        config.addDefault("logSentData", false);
        config.addDefault("logResponseStatusText", false);
        config.options().copyDefaults(true);
        if (newlyCreated) {
            config.options().header(
                    "bStats anonymous metrics / bStats 匿名统计\n"
                            + "Set enabled to false to disable globally. / enabled: false 全局关闭统计。\n"
                            + "https://bstats.org/"
            );
            config.save(file);
        }

        this.base = new MetricsBase(
                "bukkit",
                config.getString("serverUuid"),
                serviceId,
                config.getBoolean("enabled", true),
                RegionMetrics::platformData,
                data -> serviceData(data, plugin, scheduler),
                action -> {
                    try {
                        if (plugin.isEnabled()) {
                            scheduler.runGlobal(action);
                        }
                    } catch (RuntimeException exception) {
                        plugin.getLogger().log(
                                Level.WARNING,
                                "bStats could not schedule its metrics task",
                                exception
                        );
                    }
                },
                plugin::isEnabled,
                (message, error) -> plugin.getLogger().log(Level.WARNING, message, error),
                plugin.getLogger()::info,
                config.getBoolean("logFailedRequests", false),
                config.getBoolean("logSentData", false),
                config.getBoolean("logResponseStatusText", false),
                false
        );
    }

    private static void platformData(JsonObjectBuilder data) {
        data.appendField("playerAmount", Bukkit.getOnlinePlayers().size())
                .appendField("onlineMode", Bukkit.getOnlineMode() ? 1 : 0)
                .appendField("bukkitVersion", Bukkit.getVersion())
                .appendField("bukkitName", Bukkit.getName())
                .appendField("javaVersion", System.getProperty("java.version"))
                .appendField("osName", System.getProperty("os.name"))
                .appendField("osArch", System.getProperty("os.arch"))
                .appendField("osVersion", System.getProperty("os.version"))
                .appendField("coreCount", Runtime.getRuntime().availableProcessors());
    }

    private static void serviceData(
            JsonObjectBuilder data,
            PapiStorePlugin plugin,
            PlatformScheduler scheduler
    ) {
        int fieldCount = plugin.getPlayerDataService() == null
                ? 0
                : Math.max(
                        0,
                        plugin.getPlayerDataService()
                                .currentFields()
                                .columnNames()
                                .size() - 1
                );
        data.appendField("pluginVersion", plugin.getDescription().getVersion())
                .appendField(
                        "storageType",
                        plugin.getConfig().getString("storage.type", "sqlite")
                )
                .appendField("fieldCount", fieldCount)
                .appendField("scheduler", scheduler.isFolia() ? "Folia" : "Bukkit");
    }

    void shutdown() {
        base.shutdown();
    }
}
