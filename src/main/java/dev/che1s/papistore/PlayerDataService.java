package dev.che1s.papistore;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class PlayerDataService {
    private static final String WRITES_FROZEN_REASON =
            "Storage writes are disabled until the server restarts after migration";

    private final DatabaseManager database;
    private final AtomicReference<FieldConfigStore> fieldConfig;
    private final ExecutorService executor;
    private final Logger logger;
    private final AtomicReference<FieldSchema> fields;
    private final Object writeGate = new Object();
    private boolean writesFrozen;

    public PlayerDataService(
            DatabaseManager database,
            FieldConfigStore fieldConfig,
            ExecutorService executor,
            Logger logger,
            FieldSchema initialFields
    ) {
        this.database = database;
        this.fieldConfig = new AtomicReference<>(fieldConfig);
        this.executor = executor;
        this.logger = logger;
        this.fields = new AtomicReference<>(initialFields);
    }

    public FieldSchema currentFields() {
        return fields.get();
    }

    public CompletableFuture<OperationResult> save(PlayerSnapshot snapshot) {
        return save(snapshot, currentFields().columnNames());
    }

    public CompletableFuture<OperationResult> save(
            PlayerSnapshot snapshot,
            List<String> columns
    ) {
        return submitWrite(() -> {
            database.saveSnapshot(snapshot, columns);
            return OperationResult.success();
        });
    }

    public CompletableFuture<OperationResult> saveAll(List<PlayerSnapshot> snapshots) {
        return saveAll(snapshots, currentFields().columnNames());
    }

    public CompletableFuture<OperationResult> saveAll(
            List<PlayerSnapshot> snapshots,
            List<String> columns
    ) {
        return submitWrite(() -> {
            database.saveSnapshots(snapshots, columns);
            return OperationResult.success();
        });
    }

    public CompletableFuture<OperationResult> addField(
            String column,
            String expression,
            String language,
            String displayName
    ) {
        if (!SqlIdentifiers.isValidColumn(column)) {
            return CompletableFuture.completedFuture(
                    OperationResult.failure("Invalid column name")
            );
        }
        if (currentFields().contains(column)) {
            return CompletableFuture.completedFuture(
                    OperationResult.failure("Column already exists: " + column)
            );
        }

        return submitWrite(() -> {
            if (currentFields().contains(column)) {
                return OperationResult.failure(
                        "Column already exists: " + column
                );
            }
            database.addField(column, expression);
            if (!fieldConfig.get().put(column, expression, language, displayName)) {
                throw new IllegalStateException("Could not save fields.yml");
            }
            publishField(column, expression);
            return OperationResult.success();
        });
    }

    /**
     * Stops accepting new writes and appends a barrier to the database queue.
     * Writes accepted before this call are guaranteed to finish before the
     * returned future completes.
     */
    public CompletableFuture<OperationResult> freezeWritesAndDrain() {
        synchronized (writeGate) {
            boolean wasFrozen = writesFrozen;
            writesFrozen = true;
            CompletableFuture<OperationResult> completion = new CompletableFuture<>();
            try {
                executor.execute(() -> completion.complete(OperationResult.success()));
            } catch (RejectedExecutionException exception) {
                writesFrozen = wasFrozen;
                completion.complete(OperationResult.failure(
                        "Could not freeze storage writes because PAPIStore is shutting down"
                ));
            } catch (RuntimeException exception) {
                writesFrozen = wasFrozen;
                completion.complete(OperationResult.failure(safeReason(exception)));
            }
            return completion;
        }
    }

    public void resumeWrites() {
        synchronized (writeGate) {
            writesFrozen = false;
        }
    }

    public boolean isWriteFrozen() {
        synchronized (writeGate) {
            return writesFrozen;
        }
    }

    public String getDisplayName(String column, String language) {
        return fieldConfig.get().getDisplayName(column, language);
    }

    public CompletableFuture<OperationResult> reloadFields(File file) {
        if (isWriteFrozen()) {
            return CompletableFuture.completedFuture(
                    OperationResult.failure(
                            "Restart the server before reloading fields after a migration"
                    )
            );
        }
        return submit(() -> {
            FieldConfigStore reloaded = new FieldConfigStore(file, logger);
            Map<String, String> configured = reloaded.loadOrCreate();
            database.ensureConfiguredFields(configured);
            FieldSchema schema = FieldSchema.fromColumns(configured);
            fields.set(schema);
            fieldConfig.set(reloaded);
            return OperationResult.success();
        });
    }

    public CompletableFuture<PlayerQueryResult> query(String identifier) {
        try {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    StoredPlayerData data = database.findPlayer(
                            identifier,
                            currentFields().columnNames()
                    );
                    return data == null
                            ? PlayerQueryResult.notFound()
                            : PlayerQueryResult.found(data);
                } catch (Exception exception) {
                    logger.log(Level.SEVERE, "PAPIStore player query failed", exception);
                    return PlayerQueryResult.failure(safeReason(exception));
                }
            }, executor);
        } catch (RejectedExecutionException exception) {
            return CompletableFuture.completedFuture(
                    PlayerQueryResult.failure(
                            "PAPIStore database queue is full or shutting down"
                    )
            );
        }
    }

    private void publishField(String column, String expression) {
        while (true) {
            FieldSchema existing = fields.get();
            FieldSchema updated = existing.withField(column, expression);
            if (fields.compareAndSet(existing, updated)) {
                return;
            }
        }
    }

    private CompletableFuture<OperationResult> submit(DatabaseTask task) {
        try {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return task.run();
                } catch (Exception exception) {
                    logger.log(Level.SEVERE, "PAPIStore database operation failed", exception);
                    return OperationResult.failure(safeReason(exception));
                }
            }, executor);
        } catch (RejectedExecutionException exception) {
            return CompletableFuture.completedFuture(
                    OperationResult.failure(
                            "PAPIStore database queue is full or shutting down"
                    )
            );
        }
    }

    private CompletableFuture<OperationResult> submitWrite(DatabaseTask task) {
        synchronized (writeGate) {
            if (writesFrozen) {
                return CompletableFuture.completedFuture(
                        OperationResult.failure(WRITES_FROZEN_REASON)
                );
            }
            return submit(task);
        }
    }

    private static String safeReason(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.trim().isEmpty()
                ? exception.getClass().getSimpleName()
                : message;
    }

    @FunctionalInterface
    private interface DatabaseTask {
        OperationResult run() throws Exception;
    }
}
