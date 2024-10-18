package org.example;

import java.sql.*;
import java.util.*;

import org.example.RelationalDependencyRules.Cell;
import org.example.RelationalDependencyRules.Attribute;
import org.example.RelationalDependencyRules.Rule;
import org.example.RelationalDependencyRules.Cell.HyperEdge;
import org.postgresql.util.PGobject;


public class Instatiator {
    final public HashMap<Attribute, ArrayList<Rule>> attributeInHead;
    final public HashMap<Attribute, ArrayList<Rule>> attributeInTail;
    final HashMap<String, String> tableName2keyCol;
    final ArrayList<Rule> EMPTY_LIST = new ArrayList<>(0);
    public final Statement statement;
    final String IT_SUFFIX = "_insertiontime";
    final String BACKUP_SUFFIX = "_backup";
    final Connection c;

    public Instatiator(HashMap<Attribute, ArrayList<Rule>> attributeInHead, HashMap<Attribute, ArrayList<Rule>> attributeInTail, HashMap<String, String> tableName2keyCol) throws SQLException {
        this.attributeInHead = attributeInHead;
        this.attributeInTail = attributeInTail;
        this.tableName2keyCol = tableName2keyCol;
        c = DriverManager.getConnection(ConfigParameter.connectionUrl + ConfigParameter.database, ConfigParameter.username, ConfigParameter.password);
        c.setAutoCommit(false);
        statement = c.createStatement();
    }

    public ArrayList<HyperEdge> instantiateAttachedCells(Cell start, long sourceInsertionTime) throws SQLException {
        var result = new ArrayList<HyperEdge>();
        iterateRules(start, sourceInsertionTime, result, attributeInHead);
        iterateRules(start, sourceInsertionTime, result, attributeInTail);
        return result;
    }

    public void iterateRules(Cell start, long sourceInsertionTime, ArrayList<HyperEdge> result, HashMap<Attribute, ArrayList<Rule>> connectedRules) throws SQLException {
        for (var rule : connectedRules.getOrDefault(start.attribute, EMPTY_LIST)) {
            try (var rs = queryRule(rule, start, sourceInsertionTime)) {
                result.addAll(resultSetToCellList(rule, start, rs, sourceInsertionTime));
            }
        }
    }

    public ArrayList<HyperEdge> resultSetToCellList(Rule rule, Cell start, ResultSet resultSet, long sourceInsertionTime) throws SQLException {
        var result = new ArrayList<HyperEdge>();
        while (resultSet.next()) {
            HashMap<String, String> table2Key = new HashMap<>(rule.tables.size(), 1f);
            int columnIdx = 1;
            for (int tableIdx = 0; tableIdx < rule.tables.size(); tableIdx++) {
                table2Key.put(rule.tables.get(tableIdx), resultSet.getString(columnIdx++));
            }

            if (rule.head.equals(start.attribute)) {
                // if start == head, then all other cells need to be connected
                columnIdx += 2;
                var list = new HyperEdge(rule.tail.size());
                boolean anyNull = false;
                for (int tailIdx = 0; tailIdx < rule.tail.size(); tailIdx++) {
                    var currAttr = rule.tail.get(tailIdx);
                    var val = resultSet.getString(columnIdx++);
                    var it = resultSet.getLong(columnIdx++);
                    if (val == null) {
                        anyNull = true;
                        break;
                    }
                    if (it >= sourceInsertionTime) {
                        list.add(new Cell(currAttr, table2Key.get(currAttr.table), val));
                    }
                }
                if (!anyNull && !list.isEmpty()) {
                    result.add(list);
                }
            } else {
                // if start is in tail, only the head is interesting to us
                var val = resultSet.getString(columnIdx++);
                var it = resultSet.getLong(columnIdx);
                if (val != null && it >= sourceInsertionTime) {
                    var list = new HyperEdge(1);
                    list.add(new Cell(rule.head, table2Key.get(rule.head.table), val));
                    result.add(list);
                }
            }
        }
        return result;
    }

    public ResultSet queryRule(Rule rule, Cell identifier, long sourceInsertionTime) throws SQLException {
        ArrayList<String> tableStrings = new ArrayList<>(rule.tables.size());
        ArrayList<String> itJoinStrings = new ArrayList<>(rule.tables.size());
        for (var table : rule.tables) {
            var alias = rule.table2Alias.get(table);
            tableStrings.add(table + " " + alias);
            tableStrings.add(table + IT_SUFFIX + " " + alias + IT_SUFFIX);
            itJoinStrings.add(alias + "." + tableName2keyCol.get(table) + " = " + alias + IT_SUFFIX + ".insertionKey");
        }
        var idQuery = rule.table2Alias.get(identifier.attribute.table) + "." + tableName2keyCol.get(identifier.attribute.table) + " = '" + identifier.key + "'";
        var finalQuery = "SELECT " + String.join(", ", ruleToColumnNames(rule)) + " FROM " + String.join(", ", tableStrings) + " WHERE " + rule.condition + " AND " + idQuery + " AND " + String.join(" AND ", itJoinStrings) + " AND (" + String.join(" OR ", ruleToItQuery(rule, sourceInsertionTime)) + ")";
        // OR insertionTime > identifier. insertionTime => ONLY create cells from later insertionTime
        // ONLY check rules where all cells are not null
        return statement.executeQuery(finalQuery);
    }

    private ArrayList<String> ruleToItQuery(Rule rule, long insertionTime) {
        var queries = new ArrayList<String>(rule.tail.size() + 1);
        queries.add(attributeToColumnName(rule.head, rule.table2Alias, true) + " >= " + insertionTime);
        for (Attribute attribute : rule.tail) {
            queries.add(attributeToColumnName(attribute, rule.table2Alias, true) + " >= " + insertionTime);
        }
        return queries;
    }

    private ArrayList<String> ruleToColumnNames(Rule rule) {
        var names = new ArrayList<String>(rule.tail.size() + rule.tables.size() + 1);
        for (String table : rule.tables) {
            names.add(rule.table2Alias.get(table) + "." + tableName2keyCol.get(table));
        }
        names.add(attributeToColumnName(rule.head, rule.table2Alias, false));
        names.add(attributeToColumnName(rule.head, rule.table2Alias, true));
        for (Attribute attribute : rule.tail) {
            names.add(attributeToColumnName(attribute, rule.table2Alias, false));
            names.add(attributeToColumnName(attribute, rule.table2Alias, true));
        }
        return names;
    }

    private String attributeToColumnName(Attribute attribute, HashMap<String, String> table2Alias, boolean isInsertionTime) {
        return table2Alias.get(attribute.table) + (isInsertionTime ? IT_SUFFIX : "") + "." + attribute.attribute + "";
    }

    public Cell completeCell(Cell cell) throws SQLException {
        var keyCol = tableName2keyCol.get(cell.attribute.table);
        var attr = cell.attribute.attribute;
        var a = "SELECT a." + attr + ", b." + attr + " FROM " + cell.attribute.table + " a, " + cell.attribute.table + IT_SUFFIX + " b WHERE a." + keyCol + " = '" + cell.key + "' AND a." + keyCol + " = b.insertionKey";
        var rs = statement.executeQuery(a);
        if (rs.next()) {
            cell.value = rs.getString(1);
            cell.insertionTime = rs.getLong(2);
        }
        if (rs.next()) {
            throw new SQLException("Non-unique key!");
        }
        return cell;
    }

    public long deleteCells(HashSet<Cell> toDelete) throws SQLException {
        var delStart = System.nanoTime();
        for (var cell : toDelete) {
            setToNull(cell);
        }
        c.commit();
        return System.nanoTime() - delStart;
    }

    private void setToNull(Cell cell) throws SQLException {
        var q = "UPDATE " + cell.attribute.table + " SET " + cell.attribute.attribute + " = NULL WHERE " + tableName2keyCol.get(cell.attribute.table) + " = '" + cell.key + "'";
        var i = statement.executeUpdate(q);

        if (i != 1) {
            throw new SQLException("More cells deleted than expected");
        }
    }

    public void resetSchema() throws SQLException {
        var tableName = tableName2keyCol.keySet().iterator().next();
        var schemaName = tableName.split("\\.")[0];
        var dropQuery = "DROP SCHEMA IF EXISTS " + schemaName + " CASCADE";
        statement.execute(dropQuery);
        var q = "SELECT clone_schema('" + schemaName + BACKUP_SUFFIX + "', '" + schemaName + "', 'DATA');";
        statement.execute(q);
        c.commit();
    }

    public void resetValues(Collection<Cell> cells) throws SQLException {
        for (var cell : cells) {
            var stmt = c.prepareStatement("UPDATE " + cell.attribute.table + " SET " + cell.attribute.attribute + " = ? WHERE " + tableName2keyCol.get(cell.attribute.table) + " = '" + cell.key + "'");
            if (cell.attribute.attribute.equals("payload")) {
                PGobject jsonObject = new PGobject();
                jsonObject.setType("json");
                jsonObject.setValue(cell.value);
                stmt.setObject(1, jsonObject);
            } else {
                try {
                    var val = Long.parseLong(cell.value);
                    stmt.setLong(1, val);
                } catch (Exception e) {
                    try {
                        var val = Float.parseFloat(cell.value);
                        stmt.setFloat(1, val);
                    } catch (Exception e2) {
                        stmt.setString(1, cell.value);
                    }
                }

            }
            var i = stmt.executeUpdate();
            stmt.close();
            if (i != 1) {
                throw new SQLException("More cells deleted than expected");
            }
        }
        c.commit();
    }

    public long earliestTimestamp() throws SQLException {
        var attribute = attributeInHead.keySet().iterator().next();
        var q = "SELECT MIN(" + attribute.attribute + ") FROM " + attribute.table + IT_SUFFIX + ";";
        var rs = statement.executeQuery(q);
        rs.next();
        return rs.getLong(1);
    }

    public long latestTimestamp() throws SQLException {
        var attribute = attributeInHead.keySet().iterator().next();
        var q = "SELECT MAX(" + attribute.attribute + ") FROM " + attribute.table + IT_SUFFIX + ";";
        var rs = statement.executeQuery(q);
        rs.next();
        return rs.getLong(1);
    }

    public ArrayList<String> getKeys(Attribute attr) throws SQLException {
        ArrayList<String> keys = new ArrayList<>(ConfigParameter.numKeys);
        var resultSet = statement.executeQuery("SELECT " + tableName2keyCol.get(attr.table) + " FROM " + attr.table + " ORDER BY RANDOM() LIMIT " + ConfigParameter.numKeys);
        while (resultSet.next()) {
            keys.add(resultSet.getString(1));
        }
        return keys;
    }
}
