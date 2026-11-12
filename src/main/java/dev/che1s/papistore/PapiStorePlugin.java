package dev.che1s.papistore;

import dev.che1s.papistore.command.MainCommand;
import dev.che1s.papistore.listener.JoinQuitListener;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

public final class PapiStorePlugin extends JavaPlugin {
    private ExecutorService databaseExecutor;
    private DatabaseManager database;
    private DatabaseSettings databaseSettings;
    private FieldConfigStore fieldConfig;
    private LanguageManager languageManager;
    private MigrationService migrationService;
    private PlatformScheduler scheduler;
    private PlayerDataService playerData;
    private PlayerRefreshService refreshService;
    private final AtomicBoolean databaseCleanupStarted = new AtomicBoolean();
    private volatile boolean startupFailed;

    @Override
    public void onEnable() {
        databaseCleanupStarted.set(false);
        startupFailed = false;
        saveDefaultConfig();
        try {
            languageManager = new LanguageManager(this);
        } catch (IllegalStateException exception) {
            failStartup("Could not load language files", exception);
            return;
        }

        DatabaseSettings settings;
        try {
            settings = DatabaseSettings.fromConfig(
                    getConfig(),
                    getDataFolder()
            );
            databaseSettings = settings;
        } catch (IllegalArgumentException exception) {
            failStartup("Invalid database configuration: " + exception.getMessage(), exception);
            return;
        }

        int queueCapacity = Math.max(
                32,
                Math.min(
                        10000,
                        getConfig().getInt("settings.database-queue-capacity", 512)
                )
        );
        databaseExecutor = newDatabaseExecutor(queueCapacity);
        database = new DatabaseManager(settings, getLogger());
        fieldConfig = new FieldConfigStore(
                new File(getDataFolder(), "fields.yml"),
                getLogger()
        );

        Map<String, String> configuredFields;
        long startupTimeout = TimeUnit.SECONDS.toMillis(Math.max(
                5L,
                Math.min(
                        60L,
                        getConfig().getLong("settings.startup-timeout-seconds", 20L)
                )
        ));
        Future<Map<String, String>> startup = null;
        try {
            startup = databaseExecutor.submit(() -> {
                database.connect();
                Map<String, String> fields = fieldConfig.loadOrCreate();
                database.ensureConfiguredFields(fields);
                return fields;
            });
            configuredFields = startup.get(startupTimeout, TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            if (startup != null) {
                startup.cancel(true);
            }
            failStartup("Database startup was interrupted", exception);
            return;
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            failStartup("Could not initialize PAPIStore storage", cause);
            return;
        } catch (TimeoutException exception) {
            startup.cancel(true);
            failStartup("Timed out while initializing PAPIStore storage", exception);
            return;
        }

        FieldSchema fields = FieldSchema.fromColumns(configuredFields);
        playerData = new PlayerDataService(
                database,
                fieldConfig,
                databaseExecutor,
                getLogger(),
                fields
        );
        try {
            scheduler = new PlatformScheduler(this);
            refreshService = new PlayerRefreshService(
                    this,
                    playerData,
                    new PlaceholderRenderer(getLogger()),
                    scheduler
            );
            migrationService = new MigrationService(
                    this,
                    database,
                    databaseSettings,
                    getLogger()
            );
        } catch (IllegalStateException exception) {
            failStartup(exception.getMessage(), exception);
            return;
        }

        getServer().getPluginManager().registerEvents(new JoinQuitListener(this), this);
        PluginCommand command = getCommand("papistore");
        if (command == null) {
            failStartup("The papistore command is missing from plugin.yml", null);
            return;
        }
        MainCommand executor = new MainCommand(this);
        command.setExecutor(executor);
        command.setTabCompleter(executor);
        PluginCommand myDataCommand = getCommand("mydata");
        if (myDataCommand == null) {
            failStartup("The mydata command is missing from plugin.yml", null);
            return;
        }
        myDataCommand.setExecutor(executor);
        myDataCommand.setTabCompleter(executor);

        getLogger().info("Database connected successfully.");
        getLogger().info("Loaded " + Math.max(0, fields.columnNames().size() - 1) + " field(s).");
        getLogger().info("Scheduler: " + (scheduler.isFolia() ? "Folia" : "Bukkit"));
        getLogger().info("Plugin has been enabled.");
    }

    @Override
    public void onDisable() {
        if (migrationService != null) {
            migrationService.close();
        }
        if (databaseExecutor != null) {
            if (startupFailed) {
                databaseExecutor.shutdownNow();
            } else {
                databaseExecutor.shutdown();
                try {
                    if (!databaseExecutor.awaitTermination(3, TimeUnit.SECONDS)) {
                        databaseExecutor.shutdownNow();
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    databaseExecutor.shutdownNow();
                }
            }
        }
        closeDatabaseAsynchronously();
        getLogger().info("Done.");
    }

    public PlayerDataService getPlayerDataService() {
        return playerData;
    }

    public PlayerRefreshService getRefreshService() {
        return refreshService;
    }

    public PlatformScheduler getPlatformScheduler() {
        return scheduler;
    }

    public LanguageManager getLanguageManager() {
        return languageManager;
    }

    public MigrationService getMigrationService() {
        return migrationService;
    }

    public CompletableFuture<OperationResult> reloadPluginState() {
        if (playerData == null) {
            return CompletableFuture.completedFuture(
                    OperationResult.failure("PAPIStore is not fully initialized")
            );
        }
        if (playerData.isWriteFrozen()) {
            return CompletableFuture.completedFuture(
                    OperationResult.failure(
                            "Restart the server before reloading after a migration"
                    )
            );
        }

        File configFile = new File(getDataFolder(), "config.yml");
        DatabaseSettings candidateSettings;
        try {
            candidateSettings = DatabaseSettings.fromConfig(
                    YamlConfiguration.loadConfiguration(configFile),
                    getDataFolder()
            );
        } catch (RuntimeException exception) {
            return CompletableFuture.completedFuture(
                    OperationResult.failure(
                            "Invalid config.yml: " + failureReason(exception)
                    )
            );
        }

        try {
            reloadConfig();
            languageManager = new LanguageManager(this);
        } catch (RuntimeException exception) {
            return CompletableFuture.completedFuture(
                    OperationResult.failure(
                            "Could not reload config or language files: "
                                    + failureReason(exception)
                    )
            );
        }

        if (!sameDatabaseRuntime(databaseSettings, candidateSettings)) {
            getLogger().warning(
                    "Database settings changed in config.yml; restart the server "
                            + "to apply them"
            );
        }
        return playerData.reloadFields(new File(getDataFolder(), "fields.yml"));
    }

    private void failStartup(String message, Throwable cause) {
        startupFailed = true;
        if (cause == null) {
            getLogger().severe(message);
        } else {
            getLogger().log(Level.SEVERE, message, cause);
        }
        if (databaseExecutor != null) {
            databaseExecutor.shutdownNow();
        }
        closeDatabaseAsynchronously();
        getServer().getPluginManager().disablePlugin(this);
    }

    private ExecutorService newDatabaseExecutor(int queueCapacity) {
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, "PAPIStore-Database");
            thread.setDaemon(true);
            return thread;
        };
        return new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                threadFactory,
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    private void closeDatabaseAsynchronously() {
        if (database == null || !databaseCleanupStarted.compareAndSet(false, true)) {
            return;
        }
        DatabaseManager databaseToClose = database;
        Thread cleanup = new Thread(() -> {
            try {
                databaseToClose.close();
            } catch (RuntimeException exception) {
                getLogger().log(
                        Level.WARNING,
                        "Could not close the PAPIStore database cleanly",
                        exception
                );
            }
        }, "PAPIStore-Database-Cleanup");
        cleanup.setDaemon(true);
        cleanup.start();
    }

    private static boolean sameDatabaseRuntime(
            DatabaseSettings current,
            DatabaseSettings candidate
    ) {
        return current != null
                && current.getType() == candidate.getType()
                && Objects.equals(current.getJdbcUrl(), candidate.getJdbcUrl())
                && Objects.equals(current.getTable(), candidate.getTable())
                && Objects.equals(current.getUser(), candidate.getUser())
                && Objects.equals(current.getPassword(), candidate.getPassword())
                && current.isUseSsl() == candidate.isUseSsl()
                && current.isAllowPublicKeyRetrieval()
                == candidate.isAllowPublicKeyRetrieval()
                && current.getConnectTimeoutMillis()
                == candidate.getConnectTimeoutMillis()
                && current.getSocketTimeoutMillis()
                == candidate.getSocketTimeoutMillis();
    }

    private static String failureReason(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.trim().isEmpty()
                ? throwable.getClass().getSimpleName()
                : message;
    }
}
