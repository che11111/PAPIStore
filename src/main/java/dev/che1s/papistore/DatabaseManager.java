package dev.che1s.papistore;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 管理插件的 JDBC 连接，所有数据库操作都在同一个专用线程中执行。
 */
public final class DatabaseManager implements AutoCloseable {
    private static final int MAX_EXPRESSION_BYTES = 1000;

    private final DatabaseSettings settings;
    private final Logger logger;
    private Connection connection;

    public DatabaseManager(DatabaseSettings settings, Logger logger) {
        this.settings = settings;
        this.logger = logger;
    }

    public synchronized void connect() throws SQLException {
        ensureConnected();
    }

    public synchronized void ensureConfiguredFields(Map<String, String> configured)
            throws SQLException {
        Connection current = ensureConnected();
        Map<String, String> columns = readColumns(current);
        for (Map.Entry<String, String> entry : configured.entrySet()) {
            String column = entry.getKey();
            if (isInternalColumn(column) || "name".equalsIgnoreCase(column)) {
                continue;
            }
            validateExpression(column, entry.getValue());
            if (findColumn(columns, column) == null) {
                addPhysicalColumn(current, column, entry.getValue());
                columns.put(column, "");
            }
        }
    }

    public synchronized void addField(String column, String expression) throws SQLException {
        validateExpression(column, expression);
        Connection current = ensureConnected();
        Map<String, String> columns = readColumns(current);

        if (findColumn(columns, column) == null) {
            addPhysicalColumn(current, column, expression);
        }
    }

    public synchronized StoredPlayerData findPlayer(
            String identifier,
            List<String> columns
    ) throws SQLException {
        List<String> safeColumns = validateColumns(columns);
        Connection current = ensureConnected();

        StringBuilder sql = new StringBuilder("SELECT `uuid`, `updated_at`");
        for (String column : safeColumns) {
            sql.append(", ").append(SqlIdentifiers.quoteColumn(column));
        }
        sql.append(" FROM ").append(quotedTable())
                .append(" WHERE `uuid`=? OR LOWER(`name`)=LOWER(?) LIMIT 1");

        String uuidValue = "";
        try {
            uuidValue = UUID.fromString(identifier).toString();
        } catch (IllegalArgumentException ignored) {
        }

        try (PreparedStatement statement = current.prepareStatement(sql.toString())) {
            statement.setString(1, uuidValue);
            statement.setString(2, identifier);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                Map<String, String> values = new LinkedHashMap<>();
                for (String column : safeColumns) {
                    String value = result.getString(column);
                    values.put(column, value == null ? "" : value);
                }
                return new StoredPlayerData(
                        UUID.fromString(result.getString("uuid")),
                        result.getString("name"),
                        result.getString("updated_at"),
                        values
                );
            }
        }
    }

    public synchronized List<StoredPlayerData> readAllPlayers(List<String> columns)
            throws SQLException {
        List<StoredPlayerData> players = new ArrayList<>();
        String afterUuid = null;
        while (true) {
            List<StoredPlayerData> page = readPlayersBatchInternal(
                    columns,
                    afterUuid,
                    1000
            );
            if (page.isEmpty()) {
                return players;
            }
            players.addAll(page);
            afterUuid = page.get(page.size() - 1).getUniqueId().toString();
            if (page.size() < 1000) {
                return players;
            }
        }
    }

    public synchronized List<StoredPlayerData> readPlayersBatch(
            List<String> columns,
            String afterUuid,
            int limit
    ) throws SQLException {
        return readPlayersBatchInternal(columns, afterUuid, limit);
    }

    private List<StoredPlayerData> readPlayersBatchInternal(
            List<String> columns,
            String afterUuid,
            int limit
    ) throws SQLException {
        List<String> safeColumns = validateColumns(columns);
        Connection current = ensureConnected();
        StringBuilder sql = new StringBuilder("SELECT `uuid`, `updated_at`");
        for (String column : safeColumns) {
            sql.append(", ").append(SqlIdentifiers.quoteColumn(column));
        }
        sql.append(" FROM ").append(quotedTable());
        if (afterUuid != null) {
            sql.append(" WHERE `uuid` > ?");
        }
        sql.append(" ORDER BY `uuid` LIMIT ?");

        List<StoredPlayerData> players = new ArrayList<>();
        int pageSize = Math.max(1, Math.min(10000, limit));
        try (PreparedStatement statement = current.prepareStatement(sql.toString())) {
            int parameter = 1;
            if (afterUuid != null) {
                statement.setString(parameter++, afterUuid);
            }
            statement.setInt(parameter, pageSize);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    Map<String, String> values = new LinkedHashMap<>();
                    for (String column : safeColumns) {
                        String value = result.getString(column);
                        values.put(column, value == null ? "" : value);
                    }
                    players.add(new StoredPlayerData(
                            UUID.fromString(result.getString("uuid")),
                            result.getString("name"),
                            result.getString("updated_at"),
                            values
                    ));
                }
            }
        }
        return players;
    }

    public synchronized int countRows() throws SQLException {
        Connection current = ensureConnected();
        try (Statement statement = current.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT COUNT(*) FROM " + quotedTable()
             )) {
            return result.next() ? result.getInt(1) : 0;
        }
    }

    public synchronized void replaceAllPlayers(
            List<StoredPlayerData> players,
            List<String> columns
    ) throws SQLException {
        try (MigrationWriter writer = beginReplaceAll(columns)) {
            writer.writeBatch(players);
            writer.commit();
        }
    }

    public synchronized MigrationWriter beginReplaceAll(List<String> columns)
            throws SQLException {
        List<String> safeColumns = validateColumns(columns);
        Connection current = ensureConnected();
        boolean originalAutoCommit = beginTransaction(current);
        try {
            try (Statement statement = current.createStatement()) {
                statement.executeUpdate("DELETE FROM " + quotedTable());
            }
            PreparedStatement insert = current.prepareStatement(
                    buildMigrationInsert(safeColumns)
            );
            return new MigrationWriter(
                    current,
                    insert,
                    safeColumns,
                    originalAutoCommit
            );
        } catch (SQLException exception) {
            rollbackQuietly(current);
            restoreAutoCommit(current, originalAutoCommit);
            throw exception;
        }
    }

    private String buildMigrationInsert(List<String> columns) {
        StringBuilder sql = new StringBuilder("INSERT INTO ")
                .append(quotedTable())
                .append(" (`uuid`");
        for (String column : columns) {
            sql.append(", ").append(SqlIdentifiers.quoteColumn(column));
        }
        sql.append(") VALUES (?");
        for (int i = 0; i < columns.size(); i++) {
            sql.append(", ?");
        }
        sql.append(')');
        return sql.toString();
    }

    private void addPhysicalColumn(
            Connection current,
            String column,
            String expression
    ) throws SQLException {
        String sql;
        if (settings.isSqlite()) {
            sql = "ALTER TABLE " + quotedTable()
                    + " ADD COLUMN " + SqlIdentifiers.quoteColumn(column)
                    + " TEXT NOT NULL DEFAULT ''";
        } else {
            sql = "ALTER TABLE " + quotedTable()
                    + " ADD COLUMN " + SqlIdentifiers.quoteColumn(column)
                    + " TEXT NULL COMMENT '"
                    + SqlIdentifiers.escapeStringLiteral(expression) + "'";
        }
        try (Statement statement = current.createStatement()) {
            statement.executeUpdate(sql);
        } catch (SQLException exception) {
            if (!isConnectionValid(current)) {
                invalidateConnection();
            }
            throw exception;
        }
    }

    public synchronized void saveSnapshot(PlayerSnapshot snapshot, List<String> columns)
            throws SQLException {
        saveSnapshots(Collections.singletonList(snapshot), columns);
    }

    public synchronized void saveSnapshots(List<PlayerSnapshot> snapshots, List<String> columns)
            throws SQLException {
        if (snapshots.isEmpty()) {
            return;
        }

        List<String> safeColumns = validateColumns(columns);
        Connection current = ensureConnected();
        if (settings.isSqlite()) {
            saveSqliteSnapshots(current, snapshots, safeColumns);
        } else {
            saveMysqlSnapshots(current, snapshots, safeColumns);
        }
    }

    private Connection ensureConnected() throws SQLException {
        if (connection != null) {
            try {
                if (connection.isValid(2)) {
                    return connection;
                }
            } catch (SQLException exception) {
                closeQuietly(connection);
                connection = null;
            }
        }

        try {
            Class.forName(settings.getDriverClass());
        } catch (ClassNotFoundException exception) {
            String backend = settings.isSqlite() ? "SQLite" : "MySQL";
            throw new SQLException(backend + " JDBC driver is not available", exception);
        }

        if (settings.isSqlite()) {
            ensureSqliteDirectory();
        }

        Properties properties = new Properties();
        if (!settings.isSqlite()) {
            properties.setProperty("user", settings.getUser());
            properties.setProperty("password", settings.getPassword());
            properties.setProperty("useSSL", Boolean.toString(settings.isUseSsl()));
            properties.setProperty(
                    "allowPublicKeyRetrieval",
                    Boolean.toString(settings.isAllowPublicKeyRetrieval())
            );
            properties.setProperty(
                    "connectTimeout",
                    Integer.toString(settings.getConnectTimeoutMillis())
            );
            properties.setProperty(
                    "socketTimeout",
                    Integer.toString(settings.getSocketTimeoutMillis())
            );
            properties.setProperty("characterEncoding", "UTF-8");
            properties.setProperty("useUnicode", "true");
        }

        Connection opened = null;
        try {
            opened = DriverManager.getConnection(settings.getJdbcUrl(), properties);
            if (settings.isSqlite()) {
                configureSqlite(opened);
            }
            initializeSchema(opened);
            connection = opened;
            return connection;
        } catch (SQLException exception) {
            closeQuietly(opened);
            throw exception;
        }
    }

    private void initializeSchema(Connection current) throws SQLException {
        String sql;
        if (settings.isSqlite()) {
            sql = "CREATE TABLE IF NOT EXISTS " + quotedTable() + " ("
                    + "`id` INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "`uuid` TEXT NOT NULL UNIQUE,"
                    + "`name` TEXT NOT NULL DEFAULT '',"
                    + "`updated_at` TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP"
                    + ")";
        } else {
            sql = "CREATE TABLE IF NOT EXISTS " + quotedTable() + " ("
                    + "`id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,"
                    + "`uuid` CHAR(36) NOT NULL,"
                    + "`name` VARCHAR(64) NOT NULL DEFAULT '',"
                    + "`updated_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP"
                    + " ON UPDATE CURRENT_TIMESTAMP,"
                    + "PRIMARY KEY (`id`),"
                    + "UNIQUE KEY `uk_papistore_uuid` (`uuid`)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci";
        }

        try (Statement statement = current.createStatement()) {
            statement.executeUpdate(sql);
        }

        Map<String, String> columns = readColumns(current);
        if (findColumn(columns, "uuid") == null || findColumn(columns, "name") == null) {
            throw new SQLException(
                    "Table `" + settings.getTable() + "` must contain `uuid` and `name`"
            );
        }

        if (findColumn(columns, "updated_at") == null) {
            String addUpdatedAt = settings.isSqlite()
                    ? "ALTER TABLE " + quotedTable() + " ADD COLUMN `updated_at`"
                    + " TEXT NOT NULL DEFAULT ''"
                    : "ALTER TABLE " + quotedTable() + " ADD COLUMN `updated_at`"
                    + " TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP"
                    + " ON UPDATE CURRENT_TIMESTAMP";
            try (Statement statement = current.createStatement()) {
                statement.executeUpdate(addUpdatedAt);
            }
        }

        if (hasNullUuid(current)) {
            throw new SQLException(
                    "Table `" + settings.getTable()
                            + "` contains a NULL UUID. Back up the table and repair the row "
                            + "before starting PAPIStore."
            );
        }

        if (!hasUniqueUuidIndex(current)) {
            String duplicate = findDuplicateUuid(current);
            if (duplicate != null) {
                throw new SQLException(
                        "Cannot add a unique UUID index because duplicate rows exist for "
                                + duplicate + ". Back up the table and remove duplicates first."
                );
            }
            String addIndex = settings.isSqlite()
                    ? "CREATE UNIQUE INDEX IF NOT EXISTS `uk_papistore_uuid` ON "
                    + quotedTable() + " (`uuid`)"
                    : "ALTER TABLE " + quotedTable()
                    + " ADD UNIQUE KEY `uk_papistore_uuid` (`uuid`)";
            try (Statement statement = current.createStatement()) {
                statement.executeUpdate(addIndex);
            }
        }
    }

    private Map<String, String> readColumns(Connection current) throws SQLException {
        Map<String, String> columns = new LinkedHashMap<>();
        if (settings.isSqlite()) {
            try (Statement statement = current.createStatement();
                 ResultSet resultSet = statement.executeQuery(
                         "PRAGMA table_info(" + quotedTable() + ")"
                 )) {
                while (resultSet.next()) {
                    columns.put(resultSet.getString("name"), "");
                }
            }
            return columns;
        }

        String sql = "SELECT COLUMN_NAME, COLUMN_COMMENT FROM information_schema.COLUMNS"
                + " WHERE TABLE_SCHEMA=? AND TABLE_NAME=? ORDER BY ORDINAL_POSITION";
        try (PreparedStatement statement = current.prepareStatement(sql)) {
            statement.setString(1, settings.getDatabase());
            statement.setString(2, settings.getTable());
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    columns.put(
                            resultSet.getString("COLUMN_NAME"),
                            resultSet.getString("COLUMN_COMMENT")
                    );
                }
            }
        }
        return columns;
    }

    private void saveMysqlSnapshots(
            Connection current,
            List<PlayerSnapshot> snapshots,
            List<String> columns
    ) throws SQLException {
        String sql = buildMysqlUpsert(columns);
        boolean originalAutoCommit = beginTransaction(current);
        try (PreparedStatement statement = current.prepareStatement(sql)) {
            for (PlayerSnapshot snapshot : snapshots) {
                bindMysqlSnapshot(statement, snapshot, columns);
                statement.addBatch();
            }
            statement.executeBatch();
            current.commit();
        } catch (SQLException exception) {
            rollbackQuietly(current);
            throw exception;
        } finally {
            restoreAutoCommit(current, originalAutoCommit);
        }
    }

    private void saveSqliteSnapshots(
            Connection current,
            List<PlayerSnapshot> snapshots,
            List<String> columns
    ) throws SQLException {
        String insert = "INSERT OR IGNORE INTO " + quotedTable()
                + " (`uuid`, `name`) VALUES (?, ?)";
        String update = buildUpdate(columns);
        boolean originalAutoCommit = beginTransaction(current);
        try (PreparedStatement insertStatement = current.prepareStatement(insert);
             PreparedStatement updateStatement = current.prepareStatement(update)) {
            for (PlayerSnapshot snapshot : snapshots) {
                insertStatement.setString(1, snapshot.getUniqueId().toString());
                insertStatement.setString(2, snapshot.getPlayerName());
                insertStatement.addBatch();
            }
            insertStatement.executeBatch();

            for (PlayerSnapshot snapshot : snapshots) {
                bindUpdate(updateStatement, snapshot, columns);
                updateStatement.addBatch();
            }
            updateStatement.executeBatch();
            current.commit();
        } catch (SQLException exception) {
            rollbackQuietly(current);
            throw exception;
        } finally {
            restoreAutoCommit(current, originalAutoCommit);
        }
    }

    private String buildMysqlUpsert(List<String> columns) {
        StringBuilder sql = new StringBuilder("INSERT INTO ")
                .append(quotedTable())
                .append(" (`uuid`");
        for (String column : columns) {
            sql.append(", ").append(SqlIdentifiers.quoteColumn(column));
        }
        sql.append(") VALUES (?");
        for (int i = 0; i < columns.size(); i++) {
            sql.append(", ?");
        }
        sql.append(") ON DUPLICATE KEY UPDATE ");
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            String quoted = SqlIdentifiers.quoteColumn(columns.get(i));
            sql.append(quoted).append("=VALUES(").append(quoted).append(')');
        }
        sql.append(", `updated_at`=CURRENT_TIMESTAMP");
        return sql.toString();
    }

    private String buildUpdate(List<String> columns) {
        StringBuilder sql = new StringBuilder("UPDATE ")
                .append(quotedTable())
                .append(" SET ");
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append(SqlIdentifiers.quoteColumn(columns.get(i))).append("=?");
        }
        sql.append(", `updated_at`=CURRENT_TIMESTAMP WHERE `uuid`=?");
        return sql.toString();
    }

    private void bindMysqlSnapshot(
            PreparedStatement statement,
            PlayerSnapshot snapshot,
            List<String> columns
    ) throws SQLException {
        statement.setString(1, snapshot.getUniqueId().toString());
        bindColumnValues(statement, snapshot, columns, 2);
    }

    private void bindUpdate(
            PreparedStatement statement,
            PlayerSnapshot snapshot,
            List<String> columns
    ) throws SQLException {
        bindColumnValues(statement, snapshot, columns, 1);
        statement.setString(columns.size() + 1, snapshot.getUniqueId().toString());
    }

    private void bindColumnValues(
            PreparedStatement statement,
            PlayerSnapshot snapshot,
            List<String> columns,
            int firstIndex
    ) throws SQLException {
        for (int i = 0; i < columns.size(); i++) {
            String column = columns.get(i);
            String value = "name".equalsIgnoreCase(column)
                    ? snapshot.getPlayerName()
                    : snapshot.getValue(column);
            statement.setString(firstIndex + i, value);
        }
    }

    private boolean hasUniqueUuidIndex(Connection current) throws SQLException {
        if (settings.isSqlite()) {
            return hasUniqueUuidIndexSqlite(current);
        }
        Map<String, List<String>> uniqueIndexes = new HashMap<>();
        try (Statement statement = current.createStatement();
             ResultSet resultSet = statement.executeQuery("SHOW INDEX FROM " + quotedTable())) {
            while (resultSet.next()) {
                if (resultSet.getInt("Non_unique") == 0) {
                    String indexName = resultSet.getString("Key_name");
                    uniqueIndexes.computeIfAbsent(indexName, ignored -> new ArrayList<>())
                            .add(resultSet.getString("Column_name"));
                }
            }
        }
        return hasSingleUuidIndex(uniqueIndexes);
    }

    private boolean hasUniqueUuidIndexSqlite(Connection current) throws SQLException {
        List<String> indexNames = new ArrayList<>();
        try (Statement statement = current.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "PRAGMA index_list(" + quotedTable() + ")"
             )) {
            while (resultSet.next()) {
                if (resultSet.getInt("unique") == 1) {
                    indexNames.add(resultSet.getString("name"));
                }
            }
        }

        for (String indexName : indexNames) {
            List<String> columns = new ArrayList<>();
            String safeIndex = indexName.replace("`", "``");
            try (Statement statement = current.createStatement();
                 ResultSet resultSet = statement.executeQuery(
                         "PRAGMA index_info(`" + safeIndex + "`)"
                 )) {
                while (resultSet.next()) {
                    columns.add(resultSet.getString("name"));
                }
            }
            if (columns.size() == 1 && "uuid".equalsIgnoreCase(columns.get(0))) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasSingleUuidIndex(Map<String, List<String>> indexes) {
        for (List<String> columns : indexes.values()) {
            if (columns.size() == 1 && "uuid".equalsIgnoreCase(columns.get(0))) {
                return true;
            }
        }
        return false;
    }

    private String findDuplicateUuid(Connection current) throws SQLException {
        String sql = "SELECT `uuid` FROM " + quotedTable()
                + " GROUP BY `uuid` HAVING COUNT(*) > 1 LIMIT 1";
        try (Statement statement = current.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            if (!resultSet.next()) {
                return null;
            }
            String value = resultSet.getString(1);
            return value == null ? "<NULL>" : value;
        }
    }

    private boolean hasNullUuid(Connection current) throws SQLException {
        String sql = "SELECT 1 FROM " + quotedTable()
                + " WHERE `uuid` IS NULL LIMIT 1";
        try (Statement statement = current.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            return resultSet.next();
        }
    }

    private List<String> validateColumns(List<String> columns) {
        if (columns == null || columns.isEmpty()) {
            throw new IllegalArgumentException("No database columns were configured");
        }
        List<String> validated = new ArrayList<>();
        boolean hasName = false;
        for (String column : columns) {
            if (!SqlIdentifiers.isValidColumn(column)) {
                throw new IllegalArgumentException("Invalid column name: " + column);
            }
            if ("name".equalsIgnoreCase(column)) {
                hasName = true;
            }
            validated.add(column);
        }
        if (!hasName) {
            throw new IllegalArgumentException("The name column is required");
        }
        return validated;
    }

    private void validateExpression(String column, String expression) throws SQLException {
        if (!SqlIdentifiers.isValidColumn(column)) {
            throw new SQLException("Invalid column name: " + column);
        }
        if (expression == null || expression.trim().isEmpty()) {
            throw new SQLException("The PAPI expression cannot be empty");
        }
        if (expression.getBytes(StandardCharsets.UTF_8).length > MAX_EXPRESSION_BYTES) {
            throw new SQLException(
                    "The PAPI expression is longer than " + MAX_EXPRESSION_BYTES + " bytes"
            );
        }
    }

    private void configureSqlite(Connection current) throws SQLException {
        try (Statement statement = current.createStatement()) {
            statement.execute("PRAGMA busy_timeout=5000");
            statement.execute("PRAGMA journal_mode=WAL");
        }
    }

    private void ensureSqliteDirectory() throws SQLException {
        File parent = settings.getSqliteFile().getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new SQLException("Could not create SQLite directory: " + parent);
        }
    }

    private static boolean isInternalColumn(String column) {
        return "id".equalsIgnoreCase(column)
                || "uuid".equalsIgnoreCase(column)
                || "updated_at".equalsIgnoreCase(column);
    }

    private static String findColumn(Map<String, String> columns, String wanted) {
        for (String column : columns.keySet()) {
            if (column.equalsIgnoreCase(wanted)) {
                return column;
            }
        }
        return null;
    }

    private String quotedTable() {
        return SqlIdentifiers.quoteTable(settings.getTable());
    }

    private boolean beginTransaction(Connection current) throws SQLException {
        boolean original = current.getAutoCommit();
        current.setAutoCommit(false);
        return original;
    }

    private static void rollbackQuietly(Connection current) {
        try {
            current.rollback();
        } catch (SQLException ignored) {
        }
    }

    private void restoreAutoCommit(Connection current, boolean originalAutoCommit) {
        try {
            current.setAutoCommit(originalAutoCommit);
        } catch (SQLException exception) {
            logger.log(Level.WARNING, "Could not restore JDBC auto-commit state", exception);
            invalidateConnection();
        }
    }

    private void invalidateConnection() {
        closeQuietly(connection);
        connection = null;
    }

    private static boolean isConnectionValid(Connection current) {
        try {
            return current.isValid(2);
        } catch (SQLException exception) {
            return false;
        }
    }

    @Override
    public synchronized void close() {
        invalidateConnection();
    }

    /**
     * Holds one target-side replacement transaction across several batches.
     * It must be used from a single thread and closed after commit or failure.
     */
    public final class MigrationWriter implements AutoCloseable {
        private final Connection current;
        private final PreparedStatement statement;
        private final List<String> columns;
        private final boolean originalAutoCommit;
        private boolean transactionActive = true;
        private boolean committed;

        private MigrationWriter(
                Connection current,
                PreparedStatement statement,
                List<String> columns,
                boolean originalAutoCommit
        ) {
            this.current = current;
            this.statement = statement;
            this.columns = columns;
            this.originalAutoCommit = originalAutoCommit;
        }

        public void writeBatch(List<StoredPlayerData> players) throws SQLException {
            if (!transactionActive) {
                throw new SQLException("The migration transaction is no longer active");
            }
            if (players.isEmpty()) {
                return;
            }
            try {
                for (StoredPlayerData player : players) {
                    statement.setString(1, player.getUniqueId().toString());
                    for (int i = 0; i < columns.size(); i++) {
                        String value = player.getValues().get(columns.get(i));
                        statement.setString(i + 2, value == null ? "" : value);
                    }
                    statement.addBatch();
                }
                statement.executeBatch();
            } finally {
                statement.clearBatch();
            }
        }

        public void commit() throws SQLException {
            if (!transactionActive) {
                throw new SQLException("The migration transaction is no longer active");
            }
            current.commit();
            committed = true;
            transactionActive = false;
            restoreAutoCommit(current, originalAutoCommit);
        }

        @Override
        public void close() {
            if (transactionActive) {
                rollbackQuietly(current);
                transactionActive = false;
            }
            closeQuietly(statement);
            if (!committed || !isAutoCommitRestored()) {
                restoreAutoCommit(current, originalAutoCommit);
            }
        }

        private boolean isAutoCommitRestored() {
            try {
                return current.getAutoCommit() == originalAutoCommit;
            } catch (SQLException exception) {
                return false;
            }
        }
    }

    private static void closeQuietly(Connection value) {
        if (value == null) {
            return;
        }
        try {
            value.close();
        } catch (SQLException ignored) {
        }
    }

    private static void closeQuietly(PreparedStatement value) {
        if (value == null) {
            return;
        }
        try {
            value.close();
        } catch (SQLException ignored) {
        }
    }
}
