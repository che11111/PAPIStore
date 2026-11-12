package dev.che1s.papistore;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class SqliteStorageTest {
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    private DatabaseManager database;
    private DatabaseSettings settings;
    private FieldConfigStore fieldConfig;

    @Before
    public void setUp() throws Exception {
        MemoryConfiguration root = new MemoryConfiguration();
        root.set("storage.type", "sqlite");
        ConfigurationSection sqlite = root.createSection("storage.sqlite");
        sqlite.set("file", "player-store.db");

        settings = DatabaseSettings.fromConfig(root, temporaryFolder.getRoot());
        database = new DatabaseManager(settings, Logger.getLogger("PAPIStoreTest"));
        database.connect();
        fieldConfig = new FieldConfigStore(
                new File(temporaryFolder.getRoot(), "fields.yml"),
                Logger.getLogger("PAPIStoreTest")
        );
        database.ensureConfiguredFields(fieldConfig.loadOrCreate());
    }

    @After
    public void tearDown() {
        if (database != null) {
            database.close();
        }
    }

    @Test
    public void persistsFieldsInConfigAcrossReconnects() throws Exception {
        assertEquals("%player_name%", fieldConfig.loadOrCreate().get("name"));
        database.addField("money", "%vault_eco_balance%");
        fieldConfig.put("money", "%vault_eco_balance%", "zh_cn", "金币");
        Map<String, String> fields = fieldConfig.loadOrCreate();
        database.ensureConfiguredFields(fields);
        YamlConfiguration savedConfig = YamlConfiguration.loadConfiguration(
                new File(temporaryFolder.getRoot(), "fields.yml")
        );
        assertEquals(
                "%vault_eco_balance%",
                savedConfig.getString("fields.money.placeholder")
        );
        assertEquals(
                "金币",
                savedConfig.getString("fields.money.display-name.zh_cn")
        );
        assertNull(savedConfig.getConfigurationSection("display-names"));

        UUID playerId = UUID.randomUUID();
        Map<String, String> firstValues = new LinkedHashMap<>();
        firstValues.put("name", "Steve");
        firstValues.put("money", "100");
        database.saveSnapshot(
                new PlayerSnapshot(playerId, "Steve", firstValues),
                new ArrayList<>(fields.keySet())
        );

        Map<String, String> updatedValues = new LinkedHashMap<>();
        updatedValues.put("name", "Steve");
        updatedValues.put("money", "250");
        database.saveSnapshot(
                new PlayerSnapshot(playerId, "Steve", updatedValues),
                new ArrayList<>(fields.keySet())
        );

        StoredPlayerData queried = database.findPlayer(
                "Steve",
                new ArrayList<>(fields.keySet())
        );
        assertNotNull(queried);
        assertEquals(playerId, queried.getUniqueId());
        assertEquals("250", queried.getValues().get("money"));
        assertNotNull(database.findPlayer(
                playerId.toString(),
                new ArrayList<>(fields.keySet())
        ));

        database.close();
        database = new DatabaseManager(settings, Logger.getLogger("PAPIStoreTest"));
        database.connect();
        fieldConfig = new FieldConfigStore(
                new File(temporaryFolder.getRoot(), "fields.yml"),
                Logger.getLogger("PAPIStoreTest")
        );
        assertEquals(
                "%vault_eco_balance%",
                fieldConfig.loadOrCreate().get("money")
        );
        assertEquals("金币", fieldConfig.getDisplayName("money", "zh_cn"));

        try (Connection connection = DriverManager.getConnection(settings.getJdbcUrl());
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT name, money, updated_at FROM papi_store"
             )) {
            assertTrue(result.next());
            assertEquals("Steve", result.getString("name"));
            assertEquals("250", result.getString("money"));
            assertNotNull(result.getString("updated_at"));
            assertEquals(1, countRows(statement, "papi_store"));
        }
    }

    @Test
    public void keepsOnlyNameWhenNoFieldsAreConfigured() throws Exception {
        database.saveSnapshot(
                new PlayerSnapshot(UUID.randomUUID(), "Alex", Collections.emptyMap()),
                Collections.singletonList("name")
        );

        try (Connection connection = DriverManager.getConnection(settings.getJdbcUrl());
             Statement statement = connection.createStatement()) {
            assertEquals(1, countRows(statement, "papi_store"));
        }
    }

    @Test
    public void replaceAllClearsExistingTargetRows() throws Exception {
        database.addField("money", "%vault_eco_balance%");
        fieldConfig.put("money", "%vault_eco_balance%", "zh_cn", "金币");
        Map<String, String> fields = fieldConfig.loadOrCreate();
        List<String> columns = new ArrayList<>(fields.keySet());

        Map<String, String> firstValues = new LinkedHashMap<>();
        firstValues.put("name", "Steve");
        firstValues.put("money", "100");
        Map<String, String> secondValues = new LinkedHashMap<>();
        secondValues.put("name", "Alex");
        secondValues.put("money", "200");
        List<PlayerSnapshot> existing = new ArrayList<>();
        existing.add(new PlayerSnapshot(UUID.randomUUID(), "Steve", firstValues));
        existing.add(new PlayerSnapshot(UUID.randomUUID(), "Alex", secondValues));
        database.saveSnapshots(existing, columns);

        List<StoredPlayerData> replacement = new ArrayList<>();
        replacement.add(new StoredPlayerData(
                UUID.randomUUID(),
                "Steve",
                "",
                firstValues
        ));
        database.replaceAllPlayers(replacement, columns);

        try (Connection connection = DriverManager.getConnection(settings.getJdbcUrl());
             Statement statement = connection.createStatement()) {
            assertEquals(1, countRows(statement, "papi_store"));
            assertNotNull(database.findPlayer("Steve", columns));
            assertNull(database.findPlayer("Alex", columns));
        }
    }

    private int countRows(Statement statement, String table) throws Exception {
        String safeTable = table.replace("`", "``");
        try (ResultSet result = statement.executeQuery(
                "SELECT COUNT(*) FROM `" + safeTable + "`"
        )) {
            result.next();
            return result.getInt(1);
        }
    }

}
