package dev.che1s.papistore;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.Test;

import java.io.File;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class LanguageManagerTest {
    private static final List<String> LANGUAGES = Arrays.asList(
            "en_us",
            "zh_cn",
            "zh_tw",
            "ja_jp",
            "ko_kr",
            "de_de",
            "fr_fr",
            "es_es",
            "ru_ru"
    );

    private static final List<String> REQUIRED_KEYS = Arrays.asList(
            "no-permission",
            "only-player",
            "refresh-own-usage",
            "refresh-own-begin",
            "refresh-own-done",
            "refresh-failed",
            "refresh-cooldown",
            "admin-command-usage",
            "refresh-other-error",
            "refresh-other-begin",
            "refresh-other-done",
            "refresh-other-failed",
            "create-begin",
            "create-done",
            "create-failed",
            "create-invalid-option",
            "create-invalid-name",
            "generate-option-error",
            "generate-option-begin",
            "generate-option-done",
            "generate-option-failed",
            "generate-all-begin",
            "generate-all-done",
            "generate-all-failed",
            "query-not-found",
            "query-failed",
            "query-header",
            "query-uuid",
            "query-updated",
            "query-entry",
            "query-empty",
            "help",
            "migration-usage",
            "migration-target-data",
            "migration-confirm-usage",
            "migration-complete",
            "migration-failed",
            "migration-no-pending",
            "reload-usage",
            "reload-begin",
            "reload-done",
            "reload-failed",
            "query-self-usage",
            "mydata-usage",
            "self-query-disabled",
            "failure-reason"
    );

    @Test
    public void resolvesClientLocales() {
        assertEquals("zh_cn", LanguageManager.resolveLanguage("auto", "zh_CN"));
        assertEquals("zh_tw", LanguageManager.resolveLanguage("auto", "zh_HK"));
        assertEquals("zh_tw", LanguageManager.resolveLanguage("auto", "zh-Hant"));
        assertEquals("ja_jp", LanguageManager.resolveLanguage("auto", "ja_JP"));
        assertEquals("ko_kr", LanguageManager.resolveLanguage("auto", "ko_KR"));
        assertEquals("de_de", LanguageManager.resolveLanguage("auto", "de_AT"));
        assertEquals("fr_fr", LanguageManager.resolveLanguage("auto", "fr_CA"));
        assertEquals("es_es", LanguageManager.resolveLanguage("auto", "es_MX"));
        assertEquals("ru_ru", LanguageManager.resolveLanguage("auto", "ru_RU"));
    }

    @Test
    public void fallsBackToEnglishForUnknownLocales() {
        assertEquals("en_us", LanguageManager.resolveLanguage("auto", null));
        assertEquals("en_us", LanguageManager.resolveLanguage("auto", "unknown"));
        assertEquals("en_us", LanguageManager.resolveLanguage("auto", "uk_UA"));
    }

    @Test
    public void honorsExplicitLanguageAndNormalizesDashes() {
        assertEquals("zh_cn", LanguageManager.resolveLanguage("zh-CN", "en_US"));
        assertEquals("ja_jp", LanguageManager.resolveLanguage("ja_jp", "en_US"));
        assertEquals("en_us", LanguageManager.resolveLanguage("unsupported", "zh_CN"));
    }

    @Test
    public void allLanguageFilesContainEveryMessage() {
        for (String language : LANGUAGES) {
            File file = new File("src/main/resources/lang/" + language + ".yml");
            YamlConfiguration configuration = YamlConfiguration.loadConfiguration(file);
            for (String key : REQUIRED_KEYS) {
                assertNotNull(language + " is missing " + key, configuration.getString(key));
            }
        }
    }
}
