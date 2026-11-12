package dev.che1s.papistore;

import org.bukkit.configuration.ConfigurationSection;

import java.io.File;
import java.util.Locale;

public final class DatabaseSettings {
    public enum Type {
        MYSQL,
        SQLITE
    }

    private final Type type;
    private final String address;
    private final int port;
    private final String database;
    private final String table;
    private final String user;
    private final String password;
    private final boolean useSsl;
    private final boolean allowPublicKeyRetrieval;
    private final int connectTimeoutMillis;
    private final int socketTimeoutMillis;
    private final File sqliteFile;

    private DatabaseSettings(
            Type type,
            String address,
            int port,
            String database,
            String table,
            String user,
            String password,
            boolean useSsl,
            boolean allowPublicKeyRetrieval,
            int connectTimeoutMillis,
            int socketTimeoutMillis,
            File sqliteFile
    ) {
        this.type = type;
        this.address = address;
        this.port = port;
        this.database = database;
        this.table = table;
        this.user = user;
        this.password = password;
        this.useSsl = useSsl;
        this.allowPublicKeyRetrieval = allowPublicKeyRetrieval;
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.socketTimeoutMillis = socketTimeoutMillis;
        this.sqliteFile = sqliteFile;
    }

    public static DatabaseSettings fromConfig(ConfigurationSection root, File dataFolder) {
        return fromConfig(root, dataFolder, null);
    }

    public static DatabaseSettings fromConfig(
            ConfigurationSection root,
            File dataFolder,
            Type forcedType
    ) {
        if (root == null) {
            throw new IllegalArgumentException("Missing configuration");
        }

        Type type;
        if (forcedType != null) {
            type = forcedType;
        } else {
            String typeName = root.getString("storage.type", "mysql");
            try {
                type = Type.valueOf(
                        typeName == null ? "MYSQL" : typeName.trim().toUpperCase(Locale.ROOT)
                );
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("storage.type must be mysql or sqlite");
            }
        }

        if (type == Type.SQLITE) {
            ConfigurationSection sqlite = root.getConfigurationSection("storage.sqlite");
            String configured = sqlite == null
                    ? "papi_store.db"
                    : sqlite.getString("file", "papi_store.db");
            String table = sqlite == null
                    ? "papi_store"
                    : sqlite.getString("table", "papi_store");
            if (configured == null || configured.trim().isEmpty()) {
                throw new IllegalArgumentException("storage.sqlite.file cannot be empty");
            }
            if (!SqlIdentifiers.isValidTable(table)) {
                throw new IllegalArgumentException(
                        "storage.sqlite.table must be a simple SQL identifier"
                );
            }
            File file = new File(configured.trim());
            if (!file.isAbsolute()) {
                file = new File(dataFolder, configured.trim());
            }
            return new DatabaseSettings(
                    Type.SQLITE,
                    null,
                    0,
                    null,
                    table,
                    null,
                    null,
                    false,
                    false,
                    5000,
                    10000,
                    file.getAbsoluteFile()
            );
        }

        ConfigurationSection mysql = root.getConfigurationSection("mysql");
        if (mysql == null) {
            throw new IllegalArgumentException("Missing mysql configuration section");
        }

        String address = required(mysql, "address");
        int port = mysql.getInt("port", 3306);
        String database = required(mysql, "database");
        String table = required(mysql, "table");
        String user = required(mysql, "user");
        String password = mysql.getString("password", "");

        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("mysql.port must be between 1 and 65535");
        }
        if (!SqlIdentifiers.isValidDatabase(database)) {
            throw new IllegalArgumentException("mysql.database contains unsupported characters");
        }
        if (!SqlIdentifiers.isValidTable(table)) {
            throw new IllegalArgumentException("mysql.table must be a simple SQL identifier");
        }

        int connectTimeout = mysql.getInt("connectTimeout", 5000);
        int socketTimeout = mysql.getInt("socketTimeout", 10000);
        if (connectTimeout < 1000 || connectTimeout > 120000) {
            throw new IllegalArgumentException("mysql.connectTimeout must be 1000-120000 ms");
        }
        if (socketTimeout < 1000 || socketTimeout > 300000) {
            throw new IllegalArgumentException("mysql.socketTimeout must be 1000-300000 ms");
        }

        return new DatabaseSettings(
                Type.MYSQL,
                address,
                port,
                database,
                table,
                user,
                password,
                mysql.getBoolean("useSSL", false),
                mysql.getBoolean("allowPublicKeyRetrieval", false),
                connectTimeout,
                socketTimeout,
                null
        );
    }

    private static String required(ConfigurationSection section, String key) {
        String value = section.getString(key);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Missing mysql." + key);
        }
        return value.trim();
    }

    public String getJdbcUrl() {
        if (type == Type.SQLITE) {
            return "jdbc:sqlite:" + sqliteFile.getAbsolutePath();
        }
        String host = address;
        if (host.indexOf(':') >= 0 && !host.startsWith("[")) {
            host = "[" + host + "]";
        }
        return "jdbc:mysql://" + host + ":" + port + "/" + database;
    }

    public String getDriverClass() {
        return type == Type.SQLITE ? "org.sqlite.JDBC" : "com.mysql.cj.jdbc.Driver";
    }

    public Type getType() {
        return type;
    }

    public boolean isSqlite() {
        return type == Type.SQLITE;
    }

    public File getSqliteFile() {
        return sqliteFile;
    }

    public String getDatabase() {
        return database;
    }

    public String getTable() {
        return table;
    }

    public String getUser() {
        return user;
    }

    public String getPassword() {
        return password;
    }

    public boolean isUseSsl() {
        return useSsl;
    }

    public boolean isAllowPublicKeyRetrieval() {
        return allowPublicKeyRetrieval;
    }

    public int getConnectTimeoutMillis() {
        return connectTimeoutMillis;
    }

    public int getSocketTimeoutMillis() {
        return socketTimeoutMillis;
    }
}
