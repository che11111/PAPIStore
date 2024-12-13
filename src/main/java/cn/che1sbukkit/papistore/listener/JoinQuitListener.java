package cn.che1sbukkit.papistore.listener;

import cn.che1sbukkit.papistore.papistore;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * 监听器类，用于处理玩家加入和退出服务器的事件
 */
public class JoinQuitListener implements Listener {
    /**
     * 处理玩家加入服务器的事件
     *
     * @param e 包含加入服务器的玩家信息的事件对象
     */
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        // 创建一个新的 BukkitRunnable，用于异步更新玩家数据
        new BukkitRunnable() {
            @Override
            public void run() {
                // 调用 papistore.updatePlayer 方法更新玩家数据
                papistore.updatePlayer(e.getPlayer());
            }
        // 异步执行 BukkitRunnable
        }.runTaskAsynchronously(papistore.instance);
    }

    /**
     * 处理玩家退出服务器的事件
     *
     * @param e 包含退出服务器的玩家信息的事件对象
     */
    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        // 创建一个新的 BukkitRunnable，用于异步更新玩家数据
        new BukkitRunnable() {
            @Override
            public void run() {
                // 调用 papistore.updatePlayer 方法更新玩家数据
                papistore.updatePlayer(e.getPlayer());
            }
        // 异步执行 BukkitRunnable
        }.runTaskAsynchronously(papistore.instance);
    }
}
