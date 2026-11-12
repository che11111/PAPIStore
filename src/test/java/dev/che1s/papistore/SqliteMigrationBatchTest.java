package dev.che1s.papistore;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class SqliteMigrationBatchTest {
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    private DatabaseManager source;
    private DatabaseManager target;
    private List<String> columns;

    @Before
    public void setUp() throws Exception {
        source = open("source.db");
        target = open("target.db");
        columns = new ArrayList<>();
        columns.add("name");
        columns.add("money");
        source.ensureConfiguredFields(singletonFields());
        target.ensureConfiguredFields(singletonFields());
    }

    @After
    public void tearDown() {
        if (source != null) {
            source.close();
        }
        if (target != null) {
            target.close();
        }
    }

    @Test
    public void copiesAllPagesInOneTargetTransaction() throws Exception {
        for (int i = 1; i <= 5; i++) {
            source.saveSnapshot(snapshot(i), columns);
        }

        try (DatabaseManager.MigrationWriter writer =
                     target.beginReplaceAll(columns)) {
            String afterUuid = null;
            while (true) {
                List<StoredPlayerData> page = source.readPlayersBatch(
                        columns,
                        afterUuid,
                        2
                );
                if (page.isEmpty()) {
                    break;
                }
                writer.writeBatch(page);
                afterUuid = page.get(page.size() - 1).getUniqueId().toString();
                if (page.size() < 2) {
                    break;
                }
            }
            writer.commit();
        }

        assertEquals(5, target.countRows());
        for (int i = 1; i <= 5; i++) {
            StoredPlayerData player = target.findPlayer("Player" + i, columns);
            assertNotNull(player);
            assertEquals(Integer.toString(i * 100), player.getValues().get("money"));
        }
    }

    @Test
    public void closingWriterWithoutCommitRollsBackTargetReplacement()
            throws Exception {
        target.saveSnapshot(snapshot(9), columns);

        try (DatabaseManager.MigrationWriter ignored =
                     target.beginReplaceAll(columns)) {
            ignored.writeBatch(source.readPlayersBatch(columns, null, 1));
        }

        assertNotNull(target.findPlayer("Player9", columns));
        assertNull(target.findPlayer("Player1", columns));
    }

    private DatabaseManager open(String fileName) throws Exception {
        MemoryConfiguration root = new MemoryConfiguration();
        root.set("storage.type", "sqlite");
        ConfigurationSection sqlite = root.createSection("storage.sqlite");
        sqlite.set("file", fileName);
        DatabaseSettings settings = DatabaseSettings.fromConfig(
                root,
                temporaryFolder.getRoot()
        );
        DatabaseManager manager = new DatabaseManager(
                settings,
                Logger.getLogger("PAPIStoreTest")
        );
        manager.connect();
        return manager;
    }

    private static Map<String, String> singletonFields() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("name", "%player_name%");
        fields.put("money", "%vault_eco_balance%");
        return fields;
    }

    private static PlayerSnapshot snapshot(int index) {
        UUID playerId = UUID.fromString(
                String.format("00000000-0000-0000-0000-%012d", index)
        );
        Map<String, String> values = new LinkedHashMap<>();
        values.put("name", "Player" + index);
        values.put("money", Integer.toString(index * 100));
        return new PlayerSnapshot(playerId, "Player" + index, values);
    }
}
