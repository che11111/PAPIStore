package dev.che1s.papistore;

import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class FieldSchemaTest {
    @Test
    public void buildsImmutableSchemaFromColumns() {
        Map<String, String> columns = new LinkedHashMap<>();
        columns.put("id", "");
        columns.put("uuid", "");
        columns.put("name", "%player_name%");
        columns.put("Money", "%vault_eco_balance%");
        columns.put("missing_comment", "");

        FieldSchema schema = FieldSchema.fromColumns(columns);

        assertEquals("%vault_eco_balance%", schema.getExpression("money"));
        assertEquals("Money", schema.findColumn("MONEY"));
        assertFalse(schema.contains("missing_comment"));
        assertFalse(schema.contains("uuid"));
    }

    @Test
    public void withFieldDoesNotMutateOriginalSchema() {
        FieldSchema original = FieldSchema.fromColumns(new LinkedHashMap<>());
        FieldSchema updated = original.withField("points", "%points%");

        assertEquals("%points%", updated.getExpression("POINTS"));
        assertNull(original.getExpression("points"));
    }

    @Test
    public void selectAlwaysKeepsNameAndOnlyRequestedColumns() {
        Map<String, String> columns = new LinkedHashMap<>();
        columns.put("money", "%money%");
        columns.put("points", "%points%");
        FieldSchema selected = FieldSchema.fromColumns(columns)
                .select(Arrays.asList("POINTS"));

        assertEquals(Arrays.asList("name", "points"), selected.columnNames());
        assertTrue(selected.contains("name"));
        assertFalse(selected.contains("money"));
    }

    @Test
    public void namePlaceholderIsReserved() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("name", "%some_custom_name%");

        FieldSchema schema = FieldSchema.fromColumns(fields);

        assertEquals("%player_name%", schema.getExpression("name"));
    }
}
