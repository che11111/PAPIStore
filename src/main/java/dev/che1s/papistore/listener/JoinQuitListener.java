package dev.che1s.papistore.listener;

import dev.che1s.papistore.PapiStorePlugin;
import dev.che1s.papistore.OperationResult;
import dev.che1s.papistore.PlayerRefreshService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public final class JoinQuitListener implements Listener {
    private final PapiStorePlugin plugin;

    public JoinQuitListener(PapiStorePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        PlayerRefreshService refresh = plugin.getRefreshService();
        refresh.request(player, false).getFuture().whenComplete((result, error) -> {
            OperationResult finalResult = error == null
                    ? result
                    : OperationResult.failure(
                            error.getMessage() == null
                                    ? error.getClass().getSimpleName()
                                    : error.getMessage()
                    );
            refresh.runForSender(player, () -> {
                if (!finalResult.isSuccess() && plugin.isEnabled()) {
                        plugin.getLogger().warning(
                                "Join refresh failed for " + player.getName()
                                        + ": " + finalResult.getFailureReason()
                        );
                }
            });
        });
    }
}
