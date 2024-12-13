package cn.che1sbukkit.papistore;

import cn.che1sbukkit.papistore.command.MainCommand;
import cn.che1sbukkit.papistore.listener.JoinQuitListener;
import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;


//    注释使用 MarsCodeAI 生成


public final class papistore
 extends JavaPlugin {
    public static papistore
 instance;
    public static Map<String, String> options;

    @Override
    public void onEnable() {
        // 将当前插件实例赋值给静态变量 instance，方便其他地方调用
        instance = this;
        // 保存默认配置文件
        saveDefaultConfig();
        // 重新加载配置文件
        reloadConfig();
        // 初始化 JDBCUtil
        JDBCUtil.init();
        // 尝试连接到数据库，如果连接失败则记录错误并禁用插件
        if (!JDBCUtil.connect()) {
            getLogger().severe("MySQL error... Please check settings in config.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        // 记录数据库连接成功的信息
        getLogger().info("Database connected successfully.");
        // 从 JDBCUtil 中获取所有配置的字段
        options = JDBCUtil.getFields();
        // 记录加载的字段数量
        getLogger().info("Loaded " + (options.size() - 1) + " field(s).");
        // 注册 JoinQuitListener 监听器
        getServer().getPluginManager().registerEvents(new JoinQuitListener(), this);
        // 创建 MainCommand 命令处理器实例
        MainCommand cmd = new MainCommand();
        // 设置 papistore 命令的执行器为 cmd
        getCommand("papistore").setExecutor(cmd);
        // 设置 papistore 命令的标签自动补全器为 cmd
        getCommand("papistore").setTabCompleter(cmd);
        // 记录插件启用成功的信息
        getLogger().info("Plugin has been enabled.");
    }

    /**
     * 当插件禁用时执行的方法
     */
    @Override
    public void onDisable() {
        // 断开与数据库的连接
        JDBCUtil.disconnect();
        // 记录插件禁用完成的信息
        getLogger().info("Done.");
    }



    /*
     * Async
     * 更新玩家数据到数据库
     *
     * @param p 玩家对象
     */
    public static void updatePlayer(Player p) {
        // 异步插入玩家基础数据，如果不存在
        JDBCUtil.insertBaseIfNotExist(p.getUniqueId());
        // 遍历配置中的所有字段
        for (String field : options.keySet()) {
            // 更新玩家的字段数据
            JDBCUtil.updateField(p.getUniqueId(), field, PlaceholderAPI.setPlaceholders(p, options.get(field)));
        }
    }

}
