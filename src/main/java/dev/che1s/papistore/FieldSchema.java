package dev.che1s.papistore;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class FieldSchema {
    private final Map<String, String> fields;
    private final Map<String, String> normalizedNames;

    private FieldSchema(Map<String, String> fields) {
        this.fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
        Map<String, String> names = new LinkedHashMap<>();
        for (String field : fields.keySet()) {
            names.put(field.toLowerCase(Locale.ROOT), field);
        }
        this.normalizedNames = Collections.unmodifiableMap(names);
    }

    public static FieldSchema fromColumns(Map<String, String> columns) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("name", "%player_name%");

        for (Map.Entry<String, String> entry : columns.entrySet()) {
            String column = entry.getKey();
            if (column == null || !SqlIdentifiers.isValidColumn(column)) {
                continue;
            }
            if ("name".equalsIgnoreCase(column)) {
                continue;
            }
            String expression = entry.getValue();
            if (expression != null && !expression.trim().isEmpty()) {
                fields.put(column, expression);
            }
        }
        return new FieldSchema(fields);
    }

    public FieldSchema withField(String column, String expression) {
        if (!SqlIdentifiers.isValidColumn(column)) {
            throw new IllegalArgumentException("Invalid column name: " + column);
        }

        Map<String, String> updated = new LinkedHashMap<>(fields);
        String existingName = findColumn(column);
        if (existingName != null) {
            updated.remove(existingName);
        }
        updated.put(column, expression == null ? "" : expression);
        return new FieldSchema(updated);
    }

    public boolean contains(String column) {
        return column != null && normalizedNames.containsKey(column.toLowerCase(Locale.ROOT));
    }

    public String findColumn(String column) {
        return column == null ? null : normalizedNames.get(column.toLowerCase(Locale.ROOT));
    }

    public String getExpression(String column) {
        String actual = findColumn(column);
        return actual == null ? null : fields.get(actual);
    }

    public List<String> columnNames() {
        return Collections.unmodifiableList(new ArrayList<>(fields.keySet()));
    }

    public FieldSchema select(Collection<String> wantedColumns) {
        Map<String, String> selected = new LinkedHashMap<>();
        String name = findColumn("name");
        if (name != null) {
            selected.put(name, fields.get(name));
        }
        for (String wanted : wantedColumns) {
            String actual = findColumn(wanted);
            if (actual != null) {
                selected.put(actual, fields.get(actual));
            }
        }
        return new FieldSchema(selected);
    }

    public Map<String, String> asMap() {
        return fields;
    }
}
