package dev.che1s.papistore.metrics;

import dev.che1s.papistore.PapiStorePlugin;
import dev.che1s.papistore.PlatformScheduler;

import java.io.IOException;

public final class MetricsService {
    public static final int SERVICE_ID = 35296;

    private final PapiStorePlugin plugin;
    private final PlatformScheduler scheduler;
    private RegionMetrics reporter;

    public MetricsService(PapiStorePlugin plugin, PlatformScheduler scheduler) {
        this.plugin = plugin;
        this.scheduler = scheduler;
    }

    public synchronized void reload() {
        if (!plugin.getConfig().getBoolean("bstats.enabled", true)) {
            shutdown();
            return;
        }
        if (reporter != null) {
            return;
        }
        try {
            reporter = new RegionMetrics(plugin, scheduler, SERVICE_ID);
        } catch (IOException | RuntimeException exception) {
            plugin.getLogger().warning(
                    "Could not initialize bStats metrics: " + exception.getMessage()
            );
        }
    }

    public synchronized void shutdown() {
        if (reporter != null) {
            reporter.shutdown();
            reporter = null;
        }
    }
}
