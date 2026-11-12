package dev.che1s.papistore;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class FieldConfigStoreTest {
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void normalizesLegacyFlatFormat() throws Exception {
        File file = temporaryFolder.newFile("fields.yml");
        YamlConfiguration legacy = new YamlConfiguration();
        legacy.set("fields.name", "%player_name%");
        legacy.set("fields.server_world", "%player_world%");
        legacy.set("display-names.name", "玩家名称");
        legacy.set("display-names.server_world", "世界名称");
        legacy.save(file);

        FieldConfigStore store = new FieldConfigStore(file, Logger.getLogger("PAPIStoreTest"));
        Map<String, String> fields = store.loadOrCreate();

        assertEquals("%player_world%", fields.get("server_world"));
        assertEquals("世界名称", store.getDisplayName("server_world", "zh_cn"));
        assertEquals("世界名称", store.getDisplayName("server_world", "ja_jp"));

        YamlConfiguration saved = YamlConfiguration.loadConfiguration(file);
        assertEquals(
                "%player_world%",
                saved.getString("fields.server_world.placeholder")
        );
        assertEquals(
                "世界名称",
                saved.getString("fields.server_world.display-name.zh_cn")
        );
        assertEquals(
                "世界名称",
                saved.getString("fields.server_world.display-name.en_us")
        );
        assertNull(saved.getConfigurationSection("display-names"));

        assertTrue(store.put("server_world", "%player_world%", "ja_jp", "世界"));
        assertEquals("世界", store.getDisplayName("server_world", "ja_jp"));
        assertEquals("世界名称", store.getDisplayName("server_world", "fr_fr"));
    }

    @Test
    public void addsDefaultPlayerPresets() throws Exception {
        File file = new File(temporaryFolder.getRoot(), "fields.yml");
        FieldConfigStore store = new FieldConfigStore(file, Logger.getLogger("PAPIStoreTest"));

        Map<String, String> fields = store.loadOrCreate();

        assertEquals("%player_name%", fields.get("name"));
        assertEquals("%player_uuid%", fields.get("player_uuid"));
        assertEquals("%player_world%", fields.get("world"));
        assertEquals("%player_x%", fields.get("x"));
        assertEquals("%player_y%", fields.get("y"));
        assertEquals("%player_z%", fields.get("z"));
        assertEquals("%player_ping%", fields.get("ping"));
        assertEquals(
                "玩家UUID",
                store.getDisplayName("player_uuid", "zh_cn")
        );
        assertEquals(
                "Player UUID",
                store.getDisplayName("player_uuid", "en_us")
        );
    }

    @Test
    public void restoresReservedNamePlaceholder() throws Exception {
        File file = temporaryFolder.newFile("fields.yml");
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("fields.name.placeholder", "%some_custom_name%");
        configuration.save(file);

        FieldConfigStore store = new FieldConfigStore(
                file,
                Logger.getLogger("PAPIStoreTest")
        );

        assertEquals("%player_name%", store.loadOrCreate().get("name"));
        YamlConfiguration saved = YamlConfiguration.loadConfiguration(file);
        assertEquals(
                "%player_name%",
                saved.getString("fields.name.placeholder")
        );
    }
}
