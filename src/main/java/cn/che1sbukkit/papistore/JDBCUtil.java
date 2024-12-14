package cn.che1sbukkit.papistore;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;

import java.sql.*;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class JDBCUtil {
    private static Connection conn = null;
    private static String address;
    private static int port;
    private static String database;
    private static String table;
    private static String user;
    private static String password;
    private static boolean useSSL;
    private static boolean allowPublicKeyRetrieval;
    private JDBCUtil() {}


//    注释使用 MarsCodeAI 生成


    /**
     * 初始化数据库连接配置
     * 该方法从插件配置文件中读取 MySQL 数据库的连接信息，并将其存储在类的静态变量中
     */

    public static void init() {
        // 从 papistore 插件的配置文件中获取名为 "mysql" 的配置节
        ConfigurationSection mysql = papistore.instance.getConfig().getConfigurationSection("mysql");
        // 从配置节中获取数据库地址，并赋值给静态变量 address
        address = mysql.getString("address");
        // 从配置节中获取数据库端口号，并赋值给静态变量 port
        port = mysql.getInt("port");
        // 从配置节中获取数据库名，并赋值给静态变量 database
        database = mysql.getString("database");
        // 从配置节中获取数据表名，并赋值给静态变量 table
        table = mysql.getString("table");
        // 从配置节中获取是否使用 SSL 的布尔值，并赋值给静态变量 useSSL
        useSSL = mysql.getBoolean("useSSL");
        // 从配置节中获取数据库用户名，并赋值给静态变量 user
        user = mysql.getString("user");
        // 从配置节中获取数据库密码，并赋值给静态变量 password
        password = mysql.getString("password");
        // 从配置节中获取是否允许公钥检索的布尔值，并赋值给静态变量 allowPublicKeyRetrieval
        allowPublicKeyRetrieval = mysql.getBoolean("allowPublicKeyRetrieval");
    }

    /**
     * 尝试连接到数据库并创建一个新的数据表（如果该表不存在）
     *
     * @return 如果连接成功并创建了数据表，返回 true；否则返回 false
     */
    public static boolean connect() {
        // 如果已经连接，则直接返回 true
        if (isConnected()) return true;
        try {
            // 使用配置的连接信息尝试连接到数据库
            conn = DriverManager.getConnection(
                    "jdbc:mysql://" + address + ":" + port + "/" + database + "?"
                    + "allowPublicKeyRetrieval=" + allowPublicKeyRetrieval + "&useSSL=" + useSSL + "&serverTimezone=UTC",
                    user,
                    password
            );
            // 创建一个预编译的 SQL 语句，用于创建数据表
            PreparedStatement statement = conn.prepareStatement(
                    "CREATE TABLE IF NOT EXISTS `" + table + "` ("
                    + "`id` int(4) NOT NULL AUTO_INCREMENT,"
                    + "`uuid` varchar(64) NOT NULL,"
                    + "`name` varchar(64) NOT NULL DEFAULT '' COMMENT '%player_name%',"
                    + "PRIMARY KEY(`id`)"
                    + ") ENGINE=InnoDB DEFAULT charset=utf8mb4;"
            );
            // 执行 SQL 语句，创建数据表
            statement.execute();
            // 关闭预编译语句
            statement.close();
            // 返回 true，表示连接成功并创建了数据表
            return true;
        } catch (SQLException e) {
            // 捕获异常，记录错误信息
            papistore.instance.getLogger().severe(e.getLocalizedMessage());
            // 断开连接
            disconnect();
            // 返回 false，表示连接失败
            return false;
        }
    }


    /**
     * 更新数据库中指定玩家的特定字段值
     *
     * @param uuid 玩家的唯一标识符
     * @param filed 要更新的字段名
     * @param value 要更新的字段值
     */
    public static void updateField(UUID uuid, String filed, String value) {
        // 如果未连接到数据库，则尝试连接
        if (!isConnected()) connect();
        // 如果连接失败，则返回
        if (!isConnected()) return;
        // 如果值为 "%player_name%"，则替换为玩家的实际名称
        if (value.equalsIgnoreCase("%player_name%")) value = Bukkit.getOfflinePlayer(uuid).getName();
        try {
            // 准备更新语句
            PreparedStatement statement = conn.prepareStatement(
                    "UPDATE `" + table + "` SET `" + filed + "`=? WHERE `uuid`=?;"
            );
            // 设置参数
            statement.setString(1, value);
            statement.setString(2, uuid.toString());
            // 执行更新
            statement.executeUpdate();
            // 关闭语句
            statement.close();
        } catch (SQLException e) {
            // 捕获异常并记录错误信息
            papistore.instance.getLogger().severe(e.getLocalizedMessage());
        }
    }


    /**
     * 如果数据库中不存在指定 UUID 的玩家记录，则插入一条基础记录
     *
     * @param uuid 要检查并插入记录的玩家 UUID
     */
    public static void insertBaseIfNotExist(UUID uuid) {
        // 如果未连接到数据库，则尝试连接
        if (!isConnected()) connect();
        // 如果连接失败，则返回
        if (!isConnected()) return;
        try {
            // 准备一个查询语句，检查是否存在指定 UUID 的玩家记录
            PreparedStatement check = conn.prepareStatement("SELECT COUNT(*) FROM `" + table + "` WHERE `uuid`=?;");
            // 设置查询参数
            check.setString(1, uuid.toString());
            // 执行查询并获取结果
            ResultSet checkResult = check.executeQuery();
            // 移动结果集指针到下一行
            checkResult.next();
            // 获取结果集中的计数
            if (checkResult.getInt("COUNT(*)") == 0) {
                // 如果不存在，则准备插入语句
                PreparedStatement statement = conn.prepareStatement("INSERT INTO `" + table + "` (`uuid`) VALUES (?)");
                // 设置插入参数
                statement.setString(1, uuid.toString());
                // 执行插入操作
                statement.executeUpdate();
                // 关闭插入语句
                statement.close();
            }
            // 关闭检查结果集
            checkResult.close();
            // 关闭检查语句
            check.close();
        } catch (SQLException e) {
            // 捕获异常并记录错误信息
            papistore.instance.getLogger().severe(e.getLocalizedMessage());
        }
    }


    /**
     * 从数据库中获取指定表的字段信息，并将这些信息以键值对的形式存储在一个 Map 中返回
     *
     * @return 一个包含字段名和字段注释的 Map，如果连接失败或查询失败，则返回一个只包含默认字段名和注释的 Map
     */
    public static Map<String, String> getFields() {
        // 如果未连接到数据库，则尝试连接
        if (!isConnected()) connect();
        // 如果连接失败，则返回一个只包含默认字段名和注释的 Map
        if (!isConnected()) return Collections.singletonMap("name", "%player_name%");
        try {
            // 准备一个查询语句，从 information_schema 中获取指定表的字段信息
            PreparedStatement statement = conn.prepareStatement(
                    "SELECT COLUMN_NAME,COLUMN_COMMENT FROM information_schema.COLUMNS WHERE table_name=? and table_schema=?;"
            );
            // 设置查询参数，表名和数据库名
            statement.setString(1, table);
            statement.setString(2, database);
            // 执行查询并获取结果集
            ResultSet resultSet = statement.executeQuery();
            // 创建一个 Map 用于存储字段名和字段注释
            Map<String, String> map = new HashMap<>();
            // 遍历结果集，将字段名和字段注释添加到 Map 中
            while (resultSet.next()) {
                map.put(resultSet.getString("COLUMN_NAME"), resultSet.getString("COLUMN_COMMENT"));
            }
            // 从 Map 中移除 id 和 uuid 字段，因为这些字段是自动生成的，不需要用户关注
            map.remove("id");
            map.remove("uuid");
            // 关闭结果集和语句
            resultSet.close();
            statement.close();
            // 返回包含字段名和字段注释的 Map
            return map;
        } catch (SQLException e) {
            // 捕获异常并记录错误信息
            papistore.instance.getLogger().severe(e.getLocalizedMessage());
        }
        // 如果发生异常，返回一个只包含默认字段名和注释的 Map
        return Collections.singletonMap("name", "%player_name%");
    }


    /**
     * 在数据库中添加一个新的字段
     *
     * @param name  新字段的名称
     * @param comment 新字段的注释
     */
    public static void addField(String name, String comment) {
        // 如果未连接到数据库，则尝试连接
        if (!isConnected()) connect();
        // 如果连接失败，则返回
        if (!isConnected()) return;
        try {
            // 准备一个 ALTER TABLE 语句，用于添加新字段
            PreparedStatement statement = conn.prepareStatement(
                    "ALTER TABLE `" + table + "` ADD COLUMN `" + name + "` varchar(255) NOT NULL DEFAULT '' COMMENT '" + comment + "';"
            );
            // 执行 ALTER TABLE 语句，添加新字段
            statement.executeUpdate();
            // 关闭 PreparedStatement 对象
            statement.close();
            // 将新字段的名称和注释添加到 papistore.options 中
            papistore.options.put(name, comment);
        } catch (SQLException e) {
            // 捕获异常并记录错误信息
            papistore.instance.getLogger().severe(e.getLocalizedMessage());
        }
    }


    /**
     * 关闭数据库连接
     * 如果数据库连接已存在，则关闭连接并将 conn 变量设置为 null
     */
    public static void disconnect() {
        try {
            // 检查是否已连接到数据库
            if (isConnected()) {
                // 关闭数据库连接
                conn.close();
                // 将 conn 变量设置为 null，指示连接已关闭
                conn = null;
            }
        } catch (SQLException e) {
            // 捕获异常并重新抛出一个运行时异常
            throw new RuntimeException(e);
        }
    }


    /**
     * 检查数据库连接是否已经建立并且处于活动状态
     *
     * @return 如果数据库连接已经建立并且处于活动状态，返回 true；否则返回 false
     */
    public static boolean isConnected() {
        try {
            // 检查 conn 是否不为 null，并且没有被关闭
            return conn != null && !conn.isClosed();
        } catch (SQLException e) {
            // 捕获异常并重新抛出一个运行时异常
            throw new RuntimeException(e);
        }
    }

}
