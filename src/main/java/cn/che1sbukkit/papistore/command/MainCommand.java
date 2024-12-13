package cn.che1sbukkit.papistore.command;

import cn.che1sbukkit.papistore.JDBCUtil;
import cn.che1sbukkit.papistore.papistore;
import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

//    注释使用 MarsCodeAI 生成

public class MainCommand implements TabExecutor {
    /**
     * 处理插件的命令执行
     *
     * @param sender  命令发送者
     * @param command 命令对象
     * @param label   命令标签
     * @param args    命令参数
     * @return 如果命令执行成功，返回 true；否则返回 false
     */
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // 检查发送者是否有 papistore.op 权限
        if (!sender.hasPermission("papistore.op")) {
            // 检查发送者是否有 papistore.refresh 权限
            if (!sender.hasPermission("papistore.refresh")) {
                // 发送无权限消息给发送者
                send(sender, papistore.instance.getConfig().getString("message.no-permission"));
                return true;
            }
            // 检查命令参数是否为 1 且为 refresh
            if (args.length != 1 || !args[0].equalsIgnoreCase("refresh")) {
                // 发送刷新自己数据的使用方法给发送者
                send(sender, papistore.instance.getConfig().getString("message.refresh-own-usage"));
                return true;
            }
            // 刷新发送者自己的数据
            refreshOwnData(sender);
            return true;
        }
        // 检查命令参数是否为空
        if (args.length == 0) {
            // 发送管理员命令使用方法给发送者
            send(sender, papistore.instance.getConfig().getString("message.admin-command-usage"));
        }
        // 检查命令参数是否为 1
        else if (args.length == 1) {
            // 检查命令参数是否为 refresh
            if (args[0].equalsIgnoreCase("refresh")) refreshOwnData(sender);
            // 否则发送管理员命令使用方法给发送者
            else send(sender, papistore.instance.getConfig().getString("message.admin-command-usage"));
        }
        // 检查命令参数是否为 2
        else if (args.length == 2) {
            // 检查命令参数是否为 refresh
            if (args[0].equalsIgnoreCase("refresh")) {
                // 获取指定玩家
                Player target = Bukkit.getPlayerExact(args[1]);
                // 检查玩家是否存在
                if (target == null) {
                    // 发送刷新其他玩家数据错误消息给发送者
                    send(sender, papistore.instance.getConfig().getString("message.refresh-other-error").replace("%player%", args[1]));
                    return true;
                }
                // 发送开始刷新其他玩家数据消息给发送者
                send(sender, papistore.instance.getConfig().getString("message.refresh-other-begin").replace("%player%", target.getName()));
                // 异步刷新其他玩家数据
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        papistore.updatePlayer(target);
                        // 发送刷新其他玩家数据完成消息给发送者
                        send(sender, papistore.instance.getConfig().getString("message.refresh-other-done").replace("%player%", target.getName()));
                    }
                }.runTaskAsynchronously(papistore.instance);
            }
            // 检查命令参数是否为 generate
            else if (args[0].equalsIgnoreCase("generate")) {
                // 检查命令参数是否为 all
                if (args[1].equalsIgnoreCase("all")) {
                    // 发送开始生成所有玩家数据消息给发送者
                    send(sender, papistore.instance.getConfig().getString("message.generate-all-begin"));
                    // 异步生成所有玩家数据
                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            for (Player p : Bukkit.getOnlinePlayers()) papistore.updatePlayer(p);
                            // 发送生成所有玩家数据完成消息给发送者
                            send(sender, papistore.instance.getConfig().getString("message.generate-all-done"));
                        }
                    }.runTaskAsynchronously(papistore.instance);
                }
                // 否则检查命令参数是否为一个有效的选项
                else {
                    if (!papistore.options.containsKey(args[1]) || args[1].equalsIgnoreCase("name")) {
                        // 发送生成选项错误消息给发送者
                        send(sender, papistore.instance.getConfig().getString("message.generate-option-error").replace("%option%", args[1]));
                        return true;
                    }
                    // 发送开始生成指定选项数据消息给发送者
                    send(sender, papistore.instance.getConfig().getString("message.generate-option-begin").replace("%option%", args[1]));
                    // 异步生成指定选项数据
                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            for (Player p : Bukkit.getOnlinePlayers())
                                JDBCUtil.updateField(p.getUniqueId(), args[1], PlaceholderAPI.setPlaceholders(p, papistore.options.get(args[1])));
                            // 发送生成指定选项数据完成消息给发送者
                            send(sender, papistore.instance.getConfig().getString("message.generate-option-done").replace("%option%", args[1]));
                        }
                    }.runTaskAsynchronously(papistore.instance);
                }
            }
            // 否则发送管理员命令使用方法给发送者
            else send(sender, papistore.instance.getConfig().getString("message.admin-command-usage"));
        }
        // 检查命令参数是否为 3
        else if (args.length == 3) {
            // 检查命令参数是否为 create
            if (args[0].equalsIgnoreCase("create")) {
                // 发送开始创建字段消息给发送者
                send(sender, papistore.instance.getConfig().getString("message.create-begin").replace("%option%", args[2]).replace("%papi%", args[1]));
                // 异步创建字段
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        JDBCUtil.addField(args[2], args[1]);
                        // 发送创建字段完成消息给发送者
                        send(sender, papistore.instance.getConfig().getString("message.create-done").replace("%option%", args[2]).replace("%papi%", args[1]));
                    }
                }.runTaskAsynchronously(papistore.instance);
            }
            // 否则发送管理员命令使用方法给发送者
            else send(sender, papistore.instance.getConfig().getString("message.admin-command-usage"));
        }
        // 否则发送管理员命令使用方法给发送者
        else send(sender, papistore.instance.getConfig().getString("message.admin-command-usage"));
        return true;
    }

    /**
     * 刷新发送者自己的数据
     *
     * @param sender 命令发送者
     */
    private void refreshOwnData(CommandSender sender) {
        // 检查发送者是否为玩家
        if (!(sender instanceof Player)) {
            // 发送只有玩家才能使用的消息给发送者
            send(sender, papistore.instance.getConfig().getString("message.only-player"));
            return;
        }
        // 发送开始刷新自己数据消息给发送者
        send(sender, papistore.instance.getConfig().getString("message.refresh-own-begin"));
        // 异步刷新自己数据
        new BukkitRunnable() {
            @Override
            public void run() {
                papistore.updatePlayer((Player) sender);
                // 发送刷新自己数据完成消息给发送者
                send(sender, papistore.instance.getConfig().getString("message.refresh-own-done"));
            }
        }.runTaskAsynchronously(papistore.instance);
    }

    /**
     * 处理命令的Tab补全
     *
     * @param sender  命令发送者
     * @param command 命令对象
     * @param alias   命令别名
     * @param args    命令参数
     * @return 一个包含Tab补全建议的字符串列表
     */
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        // 检查发送者是否有 papistore.op 权限
        if (!sender.hasPermission("papistore.op")) {
            // 检查发送者是否有 papistore.refresh 权限
            if (args.length == 1 && sender.hasPermission("papimyswl.refresh"))
                // 如果有 refresh 权限，返回以输入内容为前缀的 refresh 建议
                return easyTab(Collections.singletonList("refresh"), args[0]);
            // 否则返回空列表
            return Collections.emptyList();
        }
        // 如果没有 op 权限，且命令参数长度为 1
        if (args.length == 1)
            // 返回以输入内容为前缀的 refresh, create, generate 建议
            return easyTab(Arrays.asList("refresh", "create", "generate"), args[0]);
        // 如果命令参数长度为 2
        if (args.length == 2) {
            // 根据第二个参数的值提供不同的补全建议
            switch (args[1].toLowerCase()) {
                case "refresh":
                    // 如果是 refresh，返回在线玩家列表作为补全建议
                    return easyTab(Bukkit.getOnlinePlayers().stream().map(HumanEntity::getName).collect(Collectors.toList()), args[1]);
                case "create":
                    // 如果是 create，返回以输入内容为前缀的 % 建议
                    return easyTab(Collections.singletonList("%"), args[1]);
                case "generate":
                    // 如果是 generate，返回以输入内容为前缀的 all 和 options 键集合（除了 name）作为补全建议
                    return easyTab(Stream.concat(Stream.of("all"), papistore.options.keySet().stream().filter(s -> s.equals("name"))).collect(Collectors.toList()), args[1]);
            }
        }
        // 如果没有匹配的补全建议，返回空列表
        return Collections.emptyList();
    }


    /**
     * 提供命令补全的辅助方法
     *
     * @param src    补全建议的源列表
     * @param input  用户输入的字符串
     * @return 一个过滤后的补全建议列表，其中只包含以输入字符串为前缀的建议
     */
    private List<String> easyTab(List<String> src, String input) {
        // 使用流过滤源列表，只保留以输入字符串为前缀的元素
        return src.stream().filter(s -> s.toLowerCase().startsWith(input.toLowerCase())).collect(Collectors.toList());
    }

    /**
     * 向命令发送者发送格式化消息的辅助方法
     *
     * @param sender 命令发送者
     * @param msg    要发送的消息
     */
    private void send(CommandSender sender, String msg) {
        // 将消息中的颜色代码转换为对应的颜色，并发送给发送者
        sender.sendMessage(ChatColor.translateAlternateColorCodes('&', msg));
    }

}
