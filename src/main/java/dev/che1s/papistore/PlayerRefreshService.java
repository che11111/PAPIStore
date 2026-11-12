package dev.che1s.papistore;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class PlayerRefreshService {
    private final PapiStorePlugin plugin;
    private final PlayerDataService playerData;
    private final PlaceholderRenderer renderer;
    private final PlatformScheduler scheduler;
    private final long cooldownMillis;
    private final int playersPerBatch;
    private final long renderTimeBudgetNanos;
    private final ConcurrentHashMap<UUID, Long> lastRefresh = new ConcurrentHashMap<>();

    public PlayerRefreshService(
            PapiStorePlugin plugin,
            PlayerDataService playerData,
            PlaceholderRenderer renderer,
            PlatformScheduler scheduler
    ) {
        this.plugin = plugin;
        this.playerData = playerData;
        this.renderer = renderer;
        this.scheduler = scheduler;
        this.cooldownMillis = TimeUnit.SECONDS.toMillis(
                Math.max(0, plugin.getConfig().getLong("settings.refresh-cooldown-seconds", 10))
        );
        this.playersPerBatch = Math.max(
                1,
                Math.min(100, plugin.getConfig().getInt("settings.players-per-tick", 20))
        );
        long renderBudgetMillis = Math.max(
                1L,
                Math.min(
                        1000L,
                        plugin.getConfig().getLong(
                                "settings.render-time-budget-millis",
                                10L
                        )
                )
        );
        this.renderTimeBudgetNanos = TimeUnit.MILLISECONDS.toNanos(renderBudgetMillis);
    }

    public RefreshRequest request(Player player, boolean enforceCooldown) {
        if (playerData.isWriteFrozen()) {
            return RefreshRequest.failed(OperationResult.failure(
                    "Storage writes are disabled until the server restarts after migration"
            ));
        }
        UUID playerId = player.getUniqueId();
        long now = System.currentTimeMillis();
        if (enforceCooldown) {
            long remaining = reserveCooldown(playerId, now);
            if (remaining > 0) {
                return RefreshRequest.cooldown(remaining);
            }
        }

        CompletableFuture<OperationResult> completion = new CompletableFuture<>();
        try {
            scheduler.runOnPlayerLater(player, 1L, () -> captureAndSave(
                    player,
                    playerId,
                    now,
                    enforceCooldown,
                    completion
            ), () -> {
                if (enforceCooldown) {
                    releaseCooldown(playerId, now);
                }
                completion.complete(OperationResult.failure("Player is no longer online"));
            });
        } catch (RuntimeException exception) {
            if (enforceCooldown) {
                releaseCooldown(playerId, now);
            }
            return RefreshRequest.failed(OperationResult.failure(exception.getMessage()));
        }
        return RefreshRequest.accepted(completion);
    }

    public CompletableFuture<OperationResult> refreshAllInBatches(
            Collection<? extends Player> players
    ) {
        if (playerData.isWriteFrozen()) {
            return CompletableFuture.completedFuture(OperationResult.failure(
                    "Storage writes are disabled until the server restarts after migration"
            ));
        }
        FieldSchema fields = playerData.currentFields();
        List<Player> snapshot = new ArrayList<>(players);
        if (scheduler.isFolia()) {
            return refreshAcrossRegions(snapshot, fields, fields.columnNames());
        }
        return refreshInBatches(snapshot, fields, fields.columnNames());
    }

    public CompletableFuture<OperationResult> refreshColumnInBatches(
            Collection<? extends Player> players,
            String column
    ) {
        if (playerData.isWriteFrozen()) {
            return CompletableFuture.completedFuture(OperationResult.failure(
                    "Storage writes are disabled until the server restarts after migration"
            ));
        }
        FieldSchema fields = playerData.currentFields();
        FieldSchema selected = fields.select(Collections.singletonList(column));
        if (!selected.contains(column)) {
            return CompletableFuture.completedFuture(
                    OperationResult.failure("Column does not exist: " + column)
            );
        }

        List<Player> snapshot = new ArrayList<>(players);
        if (scheduler.isFolia()) {
            return refreshAcrossRegions(snapshot, selected, selected.columnNames());
        }
        return refreshInBatches(snapshot, selected, selected.columnNames());
    }

    public void runForSender(CommandSender sender, Runnable action) {
        if (plugin.isEnabled()) {
            scheduler.runForSender(sender, action);
        }
    }

    private void captureAndSave(
            Player player,
            UUID playerId,
            long refreshStartedAt,
            boolean enforceCooldown,
            CompletableFuture<OperationResult> completion
    ) {
        if (!player.isOnline()) {
            if (enforceCooldown) {
                releaseCooldown(playerId, refreshStartedAt);
            }
            completion.complete(OperationResult.failure("Player is no longer online"));
            return;
        }

        try {
            FieldSchema fields = playerData.currentFields();
            PlayerSnapshot snapshot = renderer.capture(player, fields);
            playerData.save(snapshot, fields.columnNames()).whenComplete((result, error) -> {
                OperationResult finalResult = error == null
                        ? result
                        : OperationResult.failure(failureReason(error));
                if (!finalResult.isSuccess()) {
                    if (enforceCooldown) {
                        releaseCooldown(playerId, refreshStartedAt);
                    }
                }
                completion.complete(finalResult);
                if (finalResult.isSuccess() && enforceCooldown && cooldownMillis > 0) {
                    scheduleCooldownRelease(playerId, refreshStartedAt);
                }
            });
        } catch (RuntimeException exception) {
            if (enforceCooldown) {
                releaseCooldown(playerId, refreshStartedAt);
            }
            completion.complete(OperationResult.failure(exception.getMessage()));
        }
    }

    private void scheduleCooldownRelease(UUID playerId, long refreshStartedAt) {
        try {
            scheduler.runDelayedGlobal(
                    (cooldownMillis / 50L) + 1L,
                    () -> releaseCooldown(playerId, refreshStartedAt)
            );
        } catch (RuntimeException exception) {
            releaseCooldown(playerId, refreshStartedAt);
            plugin.getLogger().warning(
                    "Could not schedule refresh cooldown release: "
                            + exception.getMessage()
            );
        }
    }

    private CompletableFuture<OperationResult> refreshAcrossRegions(
            List<Player> players,
            FieldSchema fields,
            List<String> columns
    ) {
        if (players.isEmpty()) {
            return CompletableFuture.completedFuture(OperationResult.success());
        }

        CompletableFuture<OperationResult> completion = new CompletableFuture<>();
        List<PlayerSnapshot> snapshots = Collections.synchronizedList(new ArrayList<>());
        captureRegionWave(players, 0, fields, columns, snapshots, completion);
        return completion;
    }

    private void captureRegionWave(
            List<Player> players,
            int start,
            FieldSchema fields,
            List<String> columns,
            List<PlayerSnapshot> snapshots,
            CompletableFuture<OperationResult> completion
    ) {
        int end = Math.min(players.size(), start + playersPerBatch);
        AtomicInteger pending = new AtomicInteger(end - start);
        AtomicReference<String> failure = new AtomicReference<>();

        Runnable finishedOne = () -> {
            if (pending.decrementAndGet() == 0) {
                String reason = failure.get();
                if (reason != null) {
                    completion.complete(OperationResult.failure(reason));
                } else if (end < players.size()) {
                    captureRegionWave(
                            players,
                            end,
                            fields,
                            columns,
                            snapshots,
                            completion
                    );
                } else if (snapshots.isEmpty()) {
                    completion.complete(OperationResult.success());
                } else {
                    List<PlayerSnapshot> copy;
                    synchronized (snapshots) {
                        copy = new ArrayList<>(snapshots);
                    }
                    playerData.saveAll(copy, columns).whenComplete((result, error) ->
                            completion.complete(
                                    error == null
                                            ? result
                                            : OperationResult.failure(failureReason(error))
                            )
                    );
                }
            }
        };

        for (int i = start; i < end; i++) {
            Player player = players.get(i);
            try {
                scheduler.runOnPlayerLater(player, 1L, () -> {
                    try {
                        if (player.isOnline()) {
                            snapshots.add(renderer.capture(player, fields));
                        }
                    } catch (RuntimeException exception) {
                        failure.compareAndSet(
                                null,
                                exception.getMessage() == null
                                        ? "Placeholder rendering failed"
                                        : exception.getMessage()
                        );
                    } finally {
                        finishedOne.run();
                    }
                }, finishedOne);
            } catch (RuntimeException exception) {
                failure.compareAndSet(
                        null,
                        exception.getMessage() == null
                                ? "Folia scheduler rejected the task"
                                : exception.getMessage()
                );
                finishedOne.run();
            }
        }
    }

    private CompletableFuture<OperationResult> refreshInBatches(
            List<Player> players,
            FieldSchema fields,
            List<String> columns
    ) {
        CompletableFuture<OperationResult> completion = new CompletableFuture<>();
        processBatch(players, 0, fields, columns, completion);
        return completion;
    }

    private void processBatch(
            List<Player> players,
            int start,
            FieldSchema fields,
            List<String> columns,
            CompletableFuture<OperationResult> completion
    ) {
        if (start >= players.size()) {
            completion.complete(OperationResult.success());
            return;
        }

        int maximumEnd = Math.min(players.size(), start + playersPerBatch);
        long deadline = System.nanoTime() + renderTimeBudgetNanos;
        int end = start;
        List<PlayerSnapshot> snapshots = new ArrayList<>(maximumEnd - start);
        try {
            while (end < maximumEnd) {
                if (end > start && System.nanoTime() >= deadline) {
                    break;
                }
                Player player = players.get(end);
                if (player.isOnline()) {
                    snapshots.add(renderer.capture(player, fields));
                }
                end++;
            }
        } catch (RuntimeException exception) {
            completion.complete(OperationResult.failure(failureReason(exception)));
            return;
        }

        CompletableFuture<OperationResult> save = snapshots.isEmpty()
                ? CompletableFuture.completedFuture(OperationResult.success())
                : playerData.saveAll(snapshots, columns);
        int nextStart = end;
        save.whenComplete((result, error) -> {
            if (error != null) {
                completion.complete(OperationResult.failure(failureReason(error)));
                return;
            }
            if (!result.isSuccess()) {
                completion.complete(result);
                return;
            }
            try {
                scheduler.runGlobal(() ->
                        processBatch(players, nextStart, fields, columns, completion)
                );
            } catch (RuntimeException exception) {
                completion.complete(OperationResult.failure(failureReason(exception)));
            }
        });
    }

    private static String failureReason(Throwable throwable) {
        Throwable cause = throwable;
        if (cause instanceof java.util.concurrent.CompletionException
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null || message.trim().isEmpty()
                ? cause.getClass().getSimpleName()
                : message;
    }

    private synchronized long reserveCooldown(UUID playerId, long now) {
        long remaining = remainingCooldownUnsafe(playerId, now);
        if (remaining <= 0 && cooldownMillis > 0) {
            lastRefresh.put(playerId, now);
        }
        return remaining;
    }

    private void releaseCooldown(UUID playerId, long startedAt) {
        lastRefresh.remove(playerId, startedAt);
    }

    private long remainingCooldownUnsafe(UUID playerId, long now) {
        if (cooldownMillis <= 0) {
            return 0;
        }
        Long previous = lastRefresh.get(playerId);
        if (previous == null) {
            return 0;
        }
        long elapsed = now - previous;
        return elapsed >= cooldownMillis ? 0 : cooldownMillis - elapsed;
    }

    public static final class RefreshRequest {
        private final long remainingCooldownMillis;
        private final CompletableFuture<OperationResult> future;

        private RefreshRequest(
                long remainingCooldownMillis,
                CompletableFuture<OperationResult> future
        ) {
            this.remainingCooldownMillis = remainingCooldownMillis;
            this.future = future;
        }

        static RefreshRequest cooldown(long millis) {
            return new RefreshRequest(millis, null);
        }

        static RefreshRequest accepted(CompletableFuture<OperationResult> future) {
            return new RefreshRequest(0, future);
        }

        static RefreshRequest failed(OperationResult result) {
            return new RefreshRequest(0, CompletableFuture.completedFuture(result));
        }

        public boolean isCoolingDown() {
            return future == null;
        }

        public long getRemainingCooldownMillis() {
            return remainingCooldownMillis;
        }

        public CompletableFuture<OperationResult> getFuture() {
            return future;
        }
    }
}
