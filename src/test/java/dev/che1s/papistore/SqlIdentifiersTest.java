package dev.che1s.papistore;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SqlIdentifiersTest {
    @Test
    public void acceptsSafeColumnNames() {
        assertTrue(SqlIdentifiers.isValidColumn("money"));
        assertTrue(SqlIdentifiers.isValidColumn("_points"));
        assertTrue(SqlIdentifiers.isValidColumn("Player_Score2"));
    }

    @Test
    public void rejectsReservedAndUnsafeColumnNames() {
        assertFalse(SqlIdentifiers.isValidColumn(null));
        assertFalse(SqlIdentifiers.isValidColumn("id"));
        assertFalse(SqlIdentifiers.isValidColumn("uuid"));
        assertFalse(SqlIdentifiers.isValidColumn("updated_at"));
        assertFalse(SqlIdentifiers.isValidColumn("bad-name"));
        assertFalse(SqlIdentifiers.isValidColumn("money`=1"));
        assertFalse(SqlIdentifiers.isValidColumn("money); DROP TABLE users; --"));
        assertFalse(SqlIdentifiers.isValidColumn(repeat('a', 65)));
    }

    @Test
    public void escapesCommentLiteral() {
        assertEquals(
                "x''); DROP TABLE users; -- \\\\ path",
                SqlIdentifiers.escapeStringLiteral("x'); DROP TABLE users; -- \\ path")
        );
    }

    @Test
    public void quotesValidIdentifiers() {
        assertEquals("`money`", SqlIdentifiers.quoteColumn("money"));
        assertEquals("`papi_mysql`", SqlIdentifiers.quoteTable("papi_mysql"));
    }

    private static String repeat(char value, int count) {
        StringBuilder builder = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            builder.append(value);
        }
        return builder.toString();
    }
}
