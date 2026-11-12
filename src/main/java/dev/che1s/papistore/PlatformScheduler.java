package dev.che1s.papistore;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.function.Consumer;

public final class PlatformScheduler {
    private static final boolean FOLIA =
            classExists("io.papermc.paper.threadedregions.RegionizedServer");

    private final JavaPlugin plugin;
    private final Method entityGetScheduler;
    private final Method entityRun;
    private final Method entityRunDelayed;
    private final Method getGlobalRegionScheduler;
    private final Method globalRun;
    private final Method globalRunDelayed;

    public PlatformScheduler(JavaPlugin plugin) {
        this.plugin = plugin;

        if (!FOLIA) {
            entityGetScheduler = null;
            entityRun = null;
            entityRunDelayed = null;
            getGlobalRegionScheduler = null;
            globalRun = null;
            globalRunDelayed = null;
            return;
        }

        try {
            entityGetScheduler = Player.class.getMethod("getScheduler");
            Class<?> entityScheduler = entityGetScheduler.getReturnType();
            entityRun = entityScheduler.getMethod(
                    "run", Plugin.class, Consumer.class, Runnable.class
            );
            entityRunDelayed = entityScheduler.getMethod(
                    "runDelayed", Plugin.class, Consumer.class, Runnable.class, long.class
            );

            getGlobalRegionScheduler = Bukkit.class.getMethod("getGlobalRegionScheduler");
            Class<?> globalScheduler = getGlobalRegionScheduler.getReturnType();
            globalRun = globalScheduler.getMethod("run", Plugin.class, Consumer.class);
            globalRunDelayed = globalScheduler.getMethod(
                    "runDelayed", Plugin.class, Consumer.class, long.class
            );
        } catch (NoSuchMethodException exception) {
            throw new IllegalStateException("This Folia version is not supported", exception);
        }
    }

    public boolean isFolia() {
        return FOLIA;
    }

    public void runOnPlayer(Player player, Runnable task, Runnable retired) {
        if (!FOLIA) {
            Bukkit.getScheduler().runTask(plugin, task);
            return;
        }
        invokeEntity(entityRun, player, new Object[]{plugin, consumer(task), retired});
    }

    public void runOnPlayerLater(
            Player player,
            long delayTicks,
            Runnable task,
            Runnable retired
    ) {
        if (!FOLIA) {
            Bukkit.getScheduler().runTaskLater(plugin, task, Math.max(1L, delayTicks));
            return;
        }
        invokeEntity(
                entityRunDelayed,
                player,
                new Object[]{plugin, consumer(task), retired, Math.max(1L, delayTicks)}
        );
    }

    public void runForSender(CommandSender sender, Runnable task) {
        if (sender instanceof Player) {
            runOnPlayer((Player) sender, task, () -> {
            });
        } else {
            runGlobal(task);
        }
    }

    public void runGlobal(Runnable task) {
        if (!FOLIA) {
            Bukkit.getScheduler().runTask(plugin, task);
            return;
        }
        invokeGlobal(globalRun, new Object[]{plugin, consumer(task)});
    }

    public void runDelayedGlobal(long delayTicks, Runnable task) {
        if (!FOLIA) {
            Bukkit.getScheduler().runTaskLater(plugin, task, Math.max(1L, delayTicks));
            return;
        }
        invokeGlobal(
                globalRunDelayed,
                new Object[]{plugin, consumer(task), Math.max(1L, delayTicks)}
        );
    }

    private void invokeEntity(Method method, Player player, Object[] arguments) {
        try {
            Object scheduler = entityGetScheduler.invoke(player);
            method.invoke(scheduler, arguments);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Cannot access the Folia entity scheduler", exception);
        } catch (InvocationTargetException exception) {
            throw schedulerFailure(exception);
        }
    }

    private void invokeGlobal(Method method, Object[] arguments) {
        try {
            Object scheduler = getGlobalRegionScheduler.invoke(null);
            method.invoke(scheduler, arguments);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Cannot access the Folia global scheduler", exception);
        } catch (InvocationTargetException exception) {
            throw schedulerFailure(exception);
        }
    }

    private static Consumer<Object> consumer(Runnable task) {
        return ignored -> task.run();
    }

    private static IllegalStateException schedulerFailure(InvocationTargetException exception) {
        Throwable cause = exception.getCause();
        return new IllegalStateException("Folia scheduler rejected the task", cause);
    }

    private static boolean classExists(String className) {
        try {
            Class.forName(className);
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        }
    }
}
