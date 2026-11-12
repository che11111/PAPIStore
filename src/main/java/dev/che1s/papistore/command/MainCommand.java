package dev.che1s.papistore.command;

import dev.che1s.papistore.DatabaseSettings;
import dev.che1s.papistore.FieldSchema;
import dev.che1s.papistore.MigrationResult;
import dev.che1s.papistore.OperationResult;
import dev.che1s.papistore.PapiStorePlugin;
import dev.che1s.papistore.PlayerDataService;
import dev.che1s.papistore.PlayerQueryResult;
import dev.che1s.papistore.PlayerRefreshService;
import dev.che1s.papistore.PlayerRefreshService.RefreshRequest;
import dev.che1s.papistore.SqlIdentifiers;
import dev.che1s.papistore.StoredPlayerData;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public final class MainCommand implements TabExecutor {
    private final PapiStorePlugin plugin;
    private final PlayerRefreshService refreshService;
    private final PlayerDataService playerData;

    public MainCommand(PapiStorePlugin plugin) {
        this.plugin = plugin;
        this.refreshService = plugin.getRefreshService();
        this.playerData = plugin.getPlayerDataService();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if ("mydata".equalsIgnoreCase(command.getName())) {
            querySelf(sender, args, false);
            return true;
        }
        boolean operator = sender.hasPermission("papistore.op");
        if (args.length > 0 && "help".equalsIgnoreCase(args[0])) {
            if (operator) {
                message(sender, "help");
            } else {
                message(sender, "no-permission");
            }
            return true;
        }
        if (args.length > 0 && "me".equalsIgnoreCase(args[0])) {
            querySelf(sender, args, true);
            return true;
        }
        if (args.length > 0 && "migrate".equalsIgnoreCase(args[0])) {
            if (operator) {
                migrate(sender, args);
            } else {
                message(sender, "no-permission");
            }
            return true;
        }
        if (args.length > 0 && "reload".equalsIgnoreCase(args[0])) {
            if (operator) {
                reload(sender, args);
            } else {
                message(sender, "no-permission");
            }
            return true;
        }
        if (args.length > 0 && "query".equalsIgnoreCase(args[0])) {
            if (operator || sender.hasPermission("papistore.query")) {
                query(sender, args);
            } else {
                message(sender, "no-permission");
            }
            return true;
        }
        if (!operator) {
            if (!sender.hasPermission("papistore.refresh")) {
                message(sender, "no-permission");
                return true;
            }
            if (args.length != 1 || !"refresh".equalsIgnoreCase(args[0])) {
                message(sender, "refresh-own-usage");
                return true;
            }
            refreshOwn(sender);
            return true;
        }

        if (args.length == 0) {
            message(sender, "admin-command-usage");
            return true;
        }

        String subcommand = args[0].toLowerCase(Locale.ROOT);
        if ("refresh".equals(subcommand)) {
            refresh(sender, args);
        } else if ("create".equals(subcommand)) {
            create(sender, args);
        } else if ("generate".equals(subcommand)) {
            generate(sender, args);
        } else {
            message(sender, "admin-command-usage");
        }
        return true;
    }

    private void refresh(CommandSender sender, String[] args) {
        if (args.length == 1) {
            refreshOwn(sender);
            return;
        }
        if (args.length != 2) {
            message(sender, "admin-command-usage");
            return;
        }

        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            message(sender, "refresh-other-error", "%player%", args[1]);
            return;
        }

        RefreshRequest request = refreshService.request(target, true);
        if (request.isCoolingDown()) {
            sendCooldown(sender, request);
            return;
        }
        message(sender, "refresh-other-begin", "%player%", target.getName());
        completeRefresh(
                sender,
                request.getFuture(),
                "refresh-other-done",
                "refresh-other-failed",
                "%player%",
                target.getName()
        );
    }

    private void create(CommandSender sender, String[] args) {
        if (args.length < 4) {
            message(sender, "admin-command-usage");
            return;
        }

        String column = args[2].toLowerCase(Locale.ROOT);
        if (!SqlIdentifiers.isValidColumn(column) || "all".equals(column)) {
            message(sender, "create-invalid-option");
            return;
        }
        String displayName = joinArguments(args, 3);
        if (displayName.length() > 64) {
            message(sender, "create-invalid-name");
            return;
        }

        message(
                sender,
                "create-begin",
                "%option%", column,
                "%papi%", args[1]
        );
        complete(
                sender,
                playerData.addField(
                        column,
                        args[1],
                        plugin.getLanguageManager().language(sender),
                        displayName
                ),
                "create-done",
                "create-failed",
                "%option%", column,
                "%papi%", args[1]
        );
    }

    private void generate(CommandSender sender, String[] args) {
        if (args.length != 2) {
            message(sender, "admin-command-usage");
            return;
        }
        if ("all".equalsIgnoreCase(args[1])) {
            message(sender, "generate-all-begin");
            List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
            complete(
                    sender,
                    refreshService.refreshAllInBatches(players),
                    "generate-all-done",
                    "generate-all-failed"
            );
            return;
        }

        FieldSchema fields = playerData.currentFields();
        String column = fields.findColumn(args[1]);
        if (column == null || "name".equalsIgnoreCase(column)) {
            message(sender, "generate-option-error", "%option%", args[1]);
            return;
        }

        message(sender, "generate-option-begin", "%option%", column);
        List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
        complete(
                sender,
                refreshService.refreshColumnInBatches(players, column),
                "generate-option-done",
                "generate-option-failed",
                "%option%", column
        );
    }

    private void query(CommandSender sender, String[] args) {
        if (args.length != 2) {
            message(sender, "admin-command-usage");
            return;
        }

        String identifier = args[1].trim();
        playerData.query(identifier).whenComplete((result, error) -> {
            PlayerQueryResult finalResult = error == null
                    ? result
                    : PlayerQueryResult.failure(failureReason(error));
            refreshService.runForSender(
                    sender,
                    () -> displayQuery(sender, identifier, finalResult)
            );
        });
    }

    private void querySelf(CommandSender sender, String[] args, boolean papistoreMe) {
        String enabledPath = papistoreMe
                ? "player-query.enable-papistore-me"
                : "player-query.enable-mydata";
        if (!plugin.getConfig().getBoolean(enabledPath, true)) {
            message(sender, "self-query-disabled");
            return;
        }
        if (!(sender instanceof Player)) {
            message(sender, "only-player");
            return;
        }
        if (!sender.hasPermission("papistore.query.self")) {
            message(sender, "no-permission");
            return;
        }
        int expectedLength = papistoreMe ? 1 : 0;
        if (args.length != expectedLength) {
            message(sender, papistoreMe ? "query-self-usage" : "mydata-usage");
            return;
        }

        Player player = (Player) sender;
        String identifier = player.getUniqueId().toString();
        playerData.query(identifier).whenComplete((result, error) -> {
            PlayerQueryResult finalResult = error == null
                    ? result
                    : PlayerQueryResult.failure(failureReason(error));
            refreshService.runForSender(
                    sender,
                    () -> displayQuery(sender, identifier, finalResult)
            );
        });
    }

    private void migrate(CommandSender sender, String[] args) {
        if (args.length < 3 || args.length > 4
                || (args.length == 4 && !"confirm".equalsIgnoreCase(args[3]))) {
            message(sender, "migration-usage");
            return;
        }

        DatabaseSettings.Type source;
        DatabaseSettings.Type target;
        try {
            source = DatabaseSettings.Type.valueOf(args[1].toUpperCase(Locale.ROOT));
            target = DatabaseSettings.Type.valueOf(args[2].toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            message(sender, "migration-usage");
            return;
        }
        if (source == target) {
            message(sender, "migration-usage");
            return;
        }

        String senderKey = sender instanceof Player
                ? "player:" + ((Player) sender).getUniqueId()
                : "console";
        if (args.length == 4) {
            plugin.getMigrationService().confirm(senderKey, source, target)
                    .whenComplete((result, error) -> {
                        MigrationResult finalResult = error == null
                                ? result
                                : MigrationResult.failed(failureReason(error));
                        refreshService.runForSender(
                                sender,
                                () -> handleMigrationResult(sender, target, finalResult)
                        );
                    });
            return;
        }

        plugin.getMigrationService().prepare(senderKey, source, target)
                .whenComplete((result, error) -> {
                    MigrationResult finalResult = error == null
                            ? result
                            : MigrationResult.failed(failureReason(error));
                    refreshService.runForSender(
                            sender,
                            () -> handleMigrationResult(sender, target, finalResult)
                    );
                });
    }

    private void reload(CommandSender sender, String[] args) {
        if (args.length != 1) {
            message(sender, "reload-usage");
            return;
        }
        message(sender, "reload-begin");
        complete(
                sender,
                plugin.reloadPluginState(),
                "reload-done",
                "reload-failed"
        );
    }

    private void handleMigrationResult(
            CommandSender sender,
            DatabaseSettings.Type target,
            MigrationResult result
    ) {
        switch (result.getStatus()) {
            case NEEDS_CONFIRM:
                message(
                        sender,
                        "migration-target-data",
                        "%target%", storageName(result.getTarget()),
                        "%count%", Integer.toString(result.getTargetRows())
                );
                message(
                        sender,
                        "migration-confirm-usage",
                        "%source%", storageName(result.getSource()),
                        "%target%", storageName(result.getTarget())
                );
                return;
            case COMPLETED:
                plugin.getConfig().set(
                        "storage.type",
                        result.getTarget().name().toLowerCase(Locale.ROOT)
                );
                plugin.saveConfig();
                message(sender, "migration-complete", "%target%", storageName(target));
                return;
            case NO_PENDING:
                message(sender, "migration-no-pending");
                return;
            case FAILED:
            default:
                message(sender, "migration-failed");
                sendFailureReason(sender, result.getFailureReason());
        }
    }

    private static String storageName(DatabaseSettings.Type type) {
        return type == DatabaseSettings.Type.SQLITE ? "SQLite" : "MySQL";
    }

    private void displayQuery(
            CommandSender sender,
            String identifier,
            PlayerQueryResult result
    ) {
        if (result.isFailure()) {
            message(sender, "query-failed");
            sendFailureReason(sender, result.getFailureReason());
            return;
        }
        if (!result.isFound()) {
            message(sender, "query-not-found", "%player%", identifier);
            return;
        }

        StoredPlayerData data = result.getPlayerData();
        String language = plugin.getLanguageManager().language(sender);
        message(sender, "query-header", "%player%", data.getPlayerName());
        message(sender, "query-uuid", "%uuid%", data.getUniqueId().toString());
        message(sender, "query-updated", "%updated%", data.getUpdatedAt());

        boolean displayed = false;
        for (java.util.Map.Entry<String, String> entry : data.getValues().entrySet()) {
            if ("name".equalsIgnoreCase(entry.getKey())) {
                continue;
            }
            displayed = true;
            message(
                    sender,
                    "query-entry",
                    "%field%", playerData.getDisplayName(entry.getKey(), language),
                    "%value%", entry.getValue()
            );
        }
        if (!displayed) {
            message(sender, "query-empty");
        }
    }

    private static String joinArguments(String[] args, int start) {
        StringBuilder value = new StringBuilder();
        for (int i = start; i < args.length; i++) {
            if (value.length() > 0) {
                value.append(' ');
            }
            value.append(args[i]);
        }
        return value.toString().trim();
    }

    private void refreshOwn(CommandSender sender) {
        if (!(sender instanceof Player)) {
            message(sender, "only-player");
            return;
        }

        Player player = (Player) sender;
        RefreshRequest request = refreshService.request(player, true);
        if (request.isCoolingDown()) {
            sendCooldown(sender, request);
            return;
        }
        message(sender, "refresh-own-begin");
        completeRefresh(
                sender,
                request.getFuture(),
                "refresh-own-done",
                "refresh-failed"
        );
    }

    private void sendCooldown(CommandSender sender, RefreshRequest request) {
        long seconds = (request.getRemainingCooldownMillis() + 999L) / 1000L;
        message(sender, "refresh-cooldown", "%seconds%", Long.toString(seconds));
    }

    private void completeRefresh(
            CommandSender sender,
            CompletableFuture<OperationResult> future,
            String successKey,
            String failureKey,
            String... replacements
    ) {
        complete(sender, future, successKey, failureKey, replacements);
    }

    private void complete(
            CommandSender sender,
            CompletableFuture<OperationResult> future,
            String successKey,
            String failureKey,
            String... replacements
    ) {
        future.whenComplete((result, error) -> {
            OperationResult finalResult = error == null
                    ? result
                    : OperationResult.failure(failureReason(error));
            try {
                refreshService.runForSender(sender, () -> {
                    if (finalResult.isSuccess()) {
                        message(sender, successKey, replacements);
                    } else {
                        message(sender, failureKey, replacements);
                        sendFailureReason(sender, finalResult.getFailureReason());
                    }
                });
            } catch (RuntimeException exception) {
                plugin.getLogger().log(
                        java.util.logging.Level.WARNING,
                        "Could not deliver a PAPIStore command result",
                        exception
                );
            }
        });
    }

    private void sendFailureReason(CommandSender sender, String reason) {
        message(sender, "failure-reason", "%reason%", safeFailureReason(reason));
    }

    private static String safeFailureReason(String reason) {
        if (reason == null || reason.trim().isEmpty()) {
            return "Unknown error";
        }
        String normalized = reason
                .replace('\r', ' ')
                .replace('\n', ' ')
                .replace("\u00a7", "")
                .trim();
        return normalized.length() > 240
                ? normalized.substring(0, 240) + "..."
                : normalized;
    }

    private static String failureReason(Throwable throwable) {
        Throwable cause = throwable;
        if (cause instanceof java.util.concurrent.CompletionException
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return safeFailureReason(cause.getMessage());
    }

    @Override
    public List<String> onTabComplete(
            CommandSender sender,
            Command command,
            String alias,
            String[] args
    ) {
        if ("mydata".equalsIgnoreCase(command.getName())) {
            return Collections.emptyList();
        }
        boolean operator = sender.hasPermission("papistore.op");
        if (!operator) {
            if (args.length == 1) {
                List<String> options = new ArrayList<>();
                if (sender.hasPermission("papistore.refresh")) {
                    options.add("refresh");
                }
                if (sender.hasPermission("papistore.query")) {
                    options.add("query");
                }
                if (sender.hasPermission("papistore.query.self")
                        && plugin.getConfig().getBoolean(
                        "player-query.enable-papistore-me",
                        true
                )) {
                    options.add("me");
                }
                return easyTab(options, args[0]);
            }
            if (args.length == 2
                    && "query".equalsIgnoreCase(args[0])
                    && sender.hasPermission("papistore.query")) {
                return easyTab(
                        Bukkit.getOnlinePlayers().stream()
                                .map(HumanEntity::getName)
                                .collect(Collectors.toList()),
                        args[1]
                );
            }
            return Collections.emptyList();
        }

        if (args.length == 1) {
            List<String> options = new ArrayList<>(Arrays.asList(
                    "help", "refresh", "create", "generate", "query", "migrate", "reload"
            ));
            if (plugin.getConfig().getBoolean("player-query.enable-papistore-me", true)) {
                options.add("me");
            }
            return easyTab(options, args[0]);
        }
        if (args.length != 2) {
            if (args.length == 3 && "migrate".equalsIgnoreCase(args[0])) {
                List<String> options = new ArrayList<>(Arrays.asList("sqlite", "mysql"));
                options.remove(args[1].toLowerCase(Locale.ROOT));
                return easyTab(options, args[2]);
            }
            if (args.length == 4 && "migrate".equalsIgnoreCase(args[0])) {
                return easyTab(Collections.singletonList("confirm"), args[3]);
            }
            return Collections.emptyList();
        }

        String subcommand = args[0].toLowerCase(Locale.ROOT);
        if ("refresh".equals(subcommand)) {
            return easyTab(
                    Bukkit.getOnlinePlayers().stream()
                            .map(HumanEntity::getName)
                            .collect(Collectors.toList()),
                    args[1]
            );
        }
        if ("create".equals(subcommand)) {
            return easyTab(Collections.singletonList("%"), args[1]);
        }
        if ("generate".equals(subcommand)) {
            List<String> options = new ArrayList<>();
            options.add("all");
            for (String column : playerData.currentFields().columnNames()) {
                if (!"name".equalsIgnoreCase(column)) {
                    options.add(column);
                }
            }
            return easyTab(options, args[1]);
        }
        if ("query".equals(subcommand)) {
            return easyTab(
                    Bukkit.getOnlinePlayers().stream()
                            .map(HumanEntity::getName)
                            .collect(Collectors.toList()),
                    args[1]
            );
        }
        if ("migrate".equals(subcommand)) {
            return easyTab(Arrays.asList("sqlite", "mysql"), args[1]);
        }
        return Collections.emptyList();
    }

    private List<String> easyTab(List<String> source, String input) {
        String prefix = input.toLowerCase(Locale.ROOT);
        return source.stream()
                .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix))
                .collect(Collectors.toList());
    }

    private void message(
            CommandSender sender,
            String key,
            String... replacements
    ) {
        sender.sendMessage(plugin.getLanguageManager().message(sender, key, replacements));
    }
}
