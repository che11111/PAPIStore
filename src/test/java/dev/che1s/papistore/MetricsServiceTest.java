package dev.che1s.papistore;

import dev.che1s.papistore.metrics.MetricsService;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class MetricsServiceTest {
    @Test
    public void usesTheRegisteredBStatsServiceId() {
        assertEquals(35296, MetricsService.SERVICE_ID);
    }

    @Test
    public void enablesBStatsByDefault() {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(
                new File("src/main/resources/config.yml")
        );

        assertTrue(config.getBoolean("bstats.enabled", false));
    }
}
