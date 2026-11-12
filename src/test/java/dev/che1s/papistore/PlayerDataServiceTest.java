package dev.che1s.papistore;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PlayerDataServiceTest {
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    private DatabaseManager database;
    private ExecutorService executor;
    private PlayerDataService service;
    private FieldSchema fields;
    private List<String> columns;

    @Before
    public void setUp() throws Exception {
        MemoryConfiguration root = new MemoryConfiguration();
        root.set("storage.type", "sqlite");
        ConfigurationSection sqlite = root.createSection("storage.sqlite");
        sqlite.set("file", "write-gate.db");

        DatabaseSettings settings = DatabaseSettings.fromConfig(
                root,
                temporaryFolder.getRoot()
        );
        database = new DatabaseManager(settings, Logger.getLogger("PAPIStoreTest"));
        database.connect();
        fields = FieldSchema.fromColumns(
                Collections.singletonMap("name", "%player_name%")
        );
        columns = fields.columnNames();
        executor = Executors.newSingleThreadExecutor();
        service = new PlayerDataService(
                database,
                new FieldConfigStore(
                        new File(temporaryFolder.getRoot(), "fields.yml"),
                        Logger.getLogger("PAPIStoreTest")
                ),
                executor,
                Logger.getLogger("PAPIStoreTest"),
                fields
        );
    }

    @After
    public void tearDown() throws Exception {
        if (executor != null) {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
        if (database != null) {
            database.close();
        }
    }

    @Test
    public void freezingWritesDrainsAcceptedTasksAndKeepsReadsAvailable()
            throws Exception {
        UUID lastPlayerId = null;
        for (int i = 1; i <= 25; i++) {
            lastPlayerId = UUID.fromString(
                    String.format("00000000-0000-0000-0000-%012d", i)
            );
            String name = "BeforeMigration" + i;
            service.save(
                    new PlayerSnapshot(
                            lastPlayerId,
                            name,
                            Collections.singletonMap("name", name)
                    ),
                    columns
            );
        }

        OperationResult frozen = service.freezeWritesAndDrain()
                .get(5, TimeUnit.SECONDS);
        assertTrue(frozen.isSuccess());
        assertTrue(service.isWriteFrozen());

        OperationResult rejected = service.save(
                new PlayerSnapshot(
                        lastPlayerId,
                        "AfterMigration",
                        Collections.singletonMap("name", "AfterMigration")
                ),
                columns
        ).get(5, TimeUnit.SECONDS);
        assertFalse(rejected.isSuccess());
        assertTrue(rejected.getFailureReason().contains("disabled"));

        PlayerQueryResult query = service.query(lastPlayerId.toString())
                .get(5, TimeUnit.SECONDS);
        assertTrue(query.isFound());
        assertTrue(query.getPlayerData().getPlayerName().startsWith("BeforeMigration"));

        service.resumeWrites();
        assertFalse(service.isWriteFrozen());
        assertTrue(service.save(
                new PlayerSnapshot(
                        lastPlayerId,
                        "AfterMigration",
                        Collections.singletonMap("name", "AfterMigration")
                ),
                columns
        ).get(5, TimeUnit.SECONDS).isSuccess());
    }

    @Test
    public void reloadsFieldsAndAppliesThemToLaterSaves() throws Exception {
        File fieldsFile = new File(temporaryFolder.getRoot(), "reload-fields.yml");
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("fields.name.placeholder", "%player_name%");
        configuration.set("fields.reload_world.placeholder", "%player_world%");
        configuration.set("fields.reload_world.display-name.en_us", "Reloaded World");
        configuration.save(fieldsFile);

        OperationResult reloaded = service.reloadFields(fieldsFile)
                .get(5, TimeUnit.SECONDS);
        assertTrue(reloaded.isSuccess());
        assertTrue(service.currentFields().contains("reload_world"));

        UUID playerId = UUID.fromString("00000000-0000-0000-0000-000000000099");
        Map<String, String> values = new LinkedHashMap<>();
        values.put("name", "ReloadedPlayer");
        values.put("reload_world", "world_nether");
        assertTrue(service.save(
                new PlayerSnapshot(playerId, "ReloadedPlayer", values),
                service.currentFields().columnNames()
        ).get(5, TimeUnit.SECONDS).isSuccess());

        PlayerQueryResult result = service.query(playerId.toString())
                .get(5, TimeUnit.SECONDS);
        assertTrue(result.isFound());
        assertTrue(result.getPlayerData().getValues().containsKey("reload_world"));
    }
}
