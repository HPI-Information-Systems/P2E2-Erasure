package org.example.RelationalDependencyRules;

public class Attribute {
    public String table;
    public String attribute;

    public Attribute(String table, String attribute) {
        this.table = table;
        this.attribute = attribute;
    }

    @Override
    public String toString() {
        return table + " " + attribute;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        Attribute attribute1 = (Attribute) o;

        if (!table.equals(attribute1.table)) return false;
        return attribute.equals(attribute1.attribute);
    }

    @Override
    public int hashCode() {
        return 31 * table.hashCode() + attribute.hashCode();
    }
}
