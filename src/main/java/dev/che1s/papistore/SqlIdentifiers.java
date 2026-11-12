package dev.che1s.papistore;

import java.util.regex.Pattern;

/**
 * 校验并转义 PAPIStore SQL 语句中的动态部分。
 *
 * 数据值始终通过 PreparedStatement 参数绑定；只有标识符需要拼接，
 * 因此限制为 MySQL 和 MariaDB 安全的小范围 ASCII 字符。
 */
public final class SqlIdentifiers {
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,63}");
    private static final Pattern DATABASE =
            Pattern.compile("[A-Za-z0-9_$-]{1,64}");

    private SqlIdentifiers() {
    }

    public static boolean isValidColumn(String name) {
        return name != null && IDENTIFIER.matcher(name).matches()
                && !"id".equalsIgnoreCase(name)
                && !"uuid".equalsIgnoreCase(name)
                && !"updated_at".equalsIgnoreCase(name);
    }

    public static boolean isValidTable(String name) {
        return name != null && IDENTIFIER.matcher(name).matches();
    }

    public static boolean isValidDatabase(String name) {
        return name != null && DATABASE.matcher(name).matches();
    }

    public static String quoteColumn(String column) {
        if (!isValidColumn(column)) {
            throw new IllegalArgumentException("Unsafe SQL column: " + column);
        }
        return "`" + column + "`";
    }

    public static String quoteTable(String table) {
        if (!isValidTable(table)) {
            throw new IllegalArgumentException("Unsafe SQL table: " + table);
        }
        return "`" + table + "`";
    }

    /**
     * 转义写入 MySQL COMMENT 字面量的文本。
     */
    public static String escapeStringLiteral(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\")
                .replace("'", "''")
                .replace("\u0000", "");
    }
}
