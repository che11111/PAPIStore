package dev.che1s.papistore;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class MigrationService implements AutoCloseable {
    private static final long CONFIRMATION_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(60);
    private static final int MIGRATION_BATCH_SIZE = 500;

    private final PapiStorePlugin plugin;
    private final DatabaseManager activeDatabase;
    private final DatabaseSettings activeSettings;
    private final PlayerDataService playerData;
    private final Logger logger;
    private final ExecutorService executor;
    private final Map<String, PendingMigration> pending = new ConcurrentHashMap<>();

    public MigrationService(
            PapiStorePlugin plugin,
            DatabaseManager activeDatabase,
            DatabaseSettings activeSettings,
            Logger logger
    ) {
        this.plugin = plugin;
        this.activeDatabase = activeDatabase;
        this.activeSettings = activeSettings;
        this.playerData = plugin.getPlayerDataService();
        this.logger = logger;
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, "PAPIStore-Migration");
            thread.setDaemon(true);
            return thread;
        };
        this.executor = new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(8),
                threadFactory,
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    public CompletableFuture<MigrationResult> prepare(
            String senderKey,
            DatabaseSettings.Type source,
            DatabaseSettings.Type target
    ) {
        if (source == target) {
            return CompletableFuture.completedFuture(
                    MigrationResult.failed("Source and target are identical")
            );
        }
        if (activeSettings.getType() != source) {
            return CompletableFuture.completedFuture(
                    MigrationResult.failed("Source storage is not active")
            );
        }
        if (playerData.isWriteFrozen()) {
            return CompletableFuture.completedFuture(
                    MigrationResult.failed(
                            "A migration has already completed; restart the server first"
                    )
            );
        }

        return submit(() -> {
            DatabaseManager targetDatabase = null;
            try {
                DatabaseSettings targetSettings = DatabaseSettings.fromConfig(
                        plugin.getConfig(),
                        plugin.getDataFolder(),
                        target
                );
                targetDatabase = new DatabaseManager(targetSettings, logger);
                targetDatabase.connect();
                FieldSchema fields = plugin.getPlayerDataService().currentFields();
                targetDatabase.ensureConfiguredFields(fields.asMap());
                int rows = targetDatabase.countRows();
                PendingMigration request = new PendingMigration(
                        source,
                        target,
                        targetSettings,
                        System.currentTimeMillis() + CONFIRMATION_TIMEOUT_MILLIS
                );
                if (rows > 0) {
                    pending.put(senderKey, request);
                    targetDatabase.close();
                    return MigrationResult.needsConfirmation(source, target, rows);
                }
                return migrate(request, targetDatabase);
            } catch (Exception exception) {
                logger.log(Level.SEVERE, "PAPIStore migration preparation failed", exception);
                return MigrationResult.failed(safeReason(exception));
            } finally {
                if (targetDatabase != null) {
                    targetDatabase.close();
                }
            }
        });
    }

    public CompletableFuture<MigrationResult> confirm(
            String senderKey,
            DatabaseSettings.Type source,
            DatabaseSettings.Type target
    ) {
        if (playerData.isWriteFrozen()) {
            return CompletableFuture.completedFuture(
                    MigrationResult.failed(
                            "A migration has already completed; restart the server first"
                    )
            );
        }
        PendingMigration request = takePending(senderKey, source, target);
        if (request == null) {
            return CompletableFuture.completedFuture(MigrationResult.noPending());
        }
        return submit(() -> {
            DatabaseManager targetDatabase = null;
            try {
                targetDatabase = new DatabaseManager(request.targetSettings, logger);
                targetDatabase.connect();
                targetDatabase.ensureConfiguredFields(
                        plugin.getPlayerDataService().currentFields().asMap()
                );
                return migrate(request, targetDatabase);
            } catch (Exception exception) {
                logger.log(Level.SEVERE, "PAPIStore migration failed", exception);
                return MigrationResult.failed(safeReason(exception));
            } finally {
                if (targetDatabase != null) {
                    targetDatabase.close();
                }
            }
        });
    }

    private MigrationResult migrate(
            PendingMigration request,
            DatabaseManager targetDatabase
    ) {
        boolean writesFrozen = false;
        try {
            OperationResult freezeResult = playerData.freezeWritesAndDrain().join();
            if (!freezeResult.isSuccess()) {
                return MigrationResult.failed(freezeResult.getFailureReason());
            }
            writesFrozen = true;

            FieldSchema migrationFields = playerData.currentFields();
            List<String> columns = migrationFields.columnNames();
            targetDatabase.ensureConfiguredFields(migrationFields.asMap());
            try (DatabaseManager.MigrationWriter writer =
                         targetDatabase.beginReplaceAll(columns)) {
                String afterUuid = null;
                while (true) {
                    List<StoredPlayerData> players = activeDatabase.readPlayersBatch(
                            columns,
                            afterUuid,
                            MIGRATION_BATCH_SIZE
                    );
                    if (players.isEmpty()) {
                        break;
                    }
                    writer.writeBatch(players);
                    afterUuid = players.get(players.size() - 1)
                            .getUniqueId()
                            .toString();
                    if (players.size() < MIGRATION_BATCH_SIZE) {
                        break;
                    }
                }
                writer.commit();
            }
            return MigrationResult.completed(request.source, request.target);
        } catch (Exception exception) {
            logger.log(Level.SEVERE, "PAPIStore data migration failed", exception);
            if (writesFrozen) {
                playerData.resumeWrites();
            }
            return MigrationResult.failed(safeReason(exception));
        }
    }

    private PendingMigration takePending(
            String senderKey,
            DatabaseSettings.Type source,
            DatabaseSettings.Type target
    ) {
        PendingMigration request = pending.get(senderKey);
        if (request == null) {
            return null;
        }
        if (System.currentTimeMillis() > request.expiresAt) {
            pending.remove(senderKey, request);
            return null;
        }
        if (request.source != source || request.target != target) {
            return null;
        }
        if (!pending.remove(senderKey, request)) {
            return null;
        }
        return request;
    }

    private CompletableFuture<MigrationResult> submit(MigrationTask task) {
        try {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return task.run();
                } catch (Exception exception) {
                    logger.log(Level.SEVERE, "PAPIStore migration failed", exception);
                    return MigrationResult.failed(safeReason(exception));
                }
            }, executor);
        } catch (RejectedExecutionException exception) {
            return CompletableFuture.completedFuture(
                    MigrationResult.failed(
                            "PAPIStore migration queue is full or shutting down"
                    )
            );
        }
    }

    private static String safeReason(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.trim().isEmpty()
                ? exception.getClass().getSimpleName()
                : message;
    }

    @Override
    public void close() {
        pending.clear();
        executor.shutdownNow();
    }

    @FunctionalInterface
    private interface MigrationTask {
        MigrationResult run() throws Exception;
    }

    private static final class PendingMigration {
        private final DatabaseSettings.Type source;
        private final DatabaseSettings.Type target;
        private final DatabaseSettings targetSettings;
        private final long expiresAt;

        private PendingMigration(
                DatabaseSettings.Type source,
                DatabaseSettings.Type target,
                DatabaseSettings targetSettings,
                long expiresAt
        ) {
            this.source = source;
            this.target = target;
            this.targetSettings = targetSettings;
            this.expiresAt = expiresAt;
        }
    }
}
