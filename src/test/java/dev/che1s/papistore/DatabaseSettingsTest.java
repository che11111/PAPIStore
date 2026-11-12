package dev.che1s.papistore;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DatabaseSettingsTest {
    @Test
    public void parsesDefaultsAndIpv4Url() {
        ConfigurationSection root = mysqlConfiguration();

        DatabaseSettings settings = DatabaseSettings.fromConfig(root, dataFolder());

        assertEquals("jdbc:mysql://127.0.0.1:3306/minecraft", settings.getJdbcUrl());
        assertEquals(5000, settings.getConnectTimeoutMillis());
        assertFalse(settings.isUseSsl());
        assertFalse(settings.isSqlite());
    }

    @Test
    public void bracketsIpv6Address() {
        ConfigurationSection root = mysqlConfiguration();
        ConfigurationSection mysql = root.getConfigurationSection("mysql");
        mysql.set("address", "::1");

        DatabaseSettings settings = DatabaseSettings.fromConfig(root, dataFolder());

        assertEquals("jdbc:mysql://[::1]:3306/minecraft", settings.getJdbcUrl());
    }

    @Test
    public void resolvesSqliteFileAgainstPluginDataFolder() {
        MemoryConfiguration root = new MemoryConfiguration();
        root.set("storage.type", "sqlite");
        ConfigurationSection sqlite = root.createSection("storage.sqlite");
        sqlite.set("file", "data/player-store.db");

        DatabaseSettings settings = DatabaseSettings.fromConfig(root, dataFolder());

        assertTrue(settings.isSqlite());
        assertEquals("org.sqlite.JDBC", settings.getDriverClass());
        assertEquals(
                new File(dataFolder(), "data/player-store.db").getAbsolutePath(),
                settings.getSqliteFile().getAbsolutePath()
        );
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUnsafeTableName() {
        ConfigurationSection root = mysqlConfiguration();
        ConfigurationSection mysql = root.getConfigurationSection("mysql");
        mysql.set("table", "users`; DROP TABLE users; --");

        DatabaseSettings.fromConfig(root, dataFolder());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsInvalidPort() {
        ConfigurationSection root = mysqlConfiguration();
        ConfigurationSection mysql = root.getConfigurationSection("mysql");
        mysql.set("port", 70000);

        DatabaseSettings.fromConfig(root, dataFolder());
    }

    private ConfigurationSection mysqlConfiguration() {
        MemoryConfiguration configuration = new MemoryConfiguration();
        configuration.set("storage.type", "mysql");
        ConfigurationSection mysql = configuration.createSection("mysql");
        mysql.set("address", "127.0.0.1");
        mysql.set("port", 3306);
        mysql.set("database", "minecraft");
        mysql.set("table", "papi_mysql");
        mysql.set("user", "root");
        mysql.set("password", "secret");
        return configuration;
    }

    private File dataFolder() {
        return new File("target/test-server/plugins/PAPIStore");
    }
}
