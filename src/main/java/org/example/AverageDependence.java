package org.example;

import org.example.RelationalDependencyRules.Attribute;
import org.example.RelationalDependencyRules.Cell;
import org.example.RelationalDependencyRules.Rule;

import java.sql.SQLException;
import java.util.*;

public class AverageDependence {

    private static long getDeletionCells(Instatiator instantiator, Attribute attr, ArrayList<Cell> deletionCells, ArrayList<String> keys) throws SQLException {
        keys.addAll(instantiator.getKeys(attr));
        var count = 0L;
        for (var key : keys) {
            var deletionCell = new Cell(attr, key);
            instantiator.completeCell(deletionCell);
            deletionCells.add(deletionCell);
            var currModel = new InstantiatedModel(deletionCell, instantiator);
            count += currModel.instantiationTime.size();
        }
        return count;
    }

    static void averageDependence(HashSet<Attribute> allAttributes, ArrayList<Rule> allRules, HashMap<Attribute, ArrayList<Rule>> attributeInHead, HashMap<Attribute, ArrayList<Rule>> attributeInTail, HashMap<String, String> tableName2keyCol) throws SQLException {
        var instantiator = new Instatiator(attributeInHead, attributeInTail, tableName2keyCol);
        ArrayList<Cell> deletionCells = new ArrayList<>(ConfigParameter.numKeys * allAttributes.size());
        for (var attr : allAttributes) {
            System.out.print(attr + ",");
            var currCells = new ArrayList<Cell>();
            var keys = new ArrayList<String>();
            var count = getDeletionCells(instantiator, attr, currCells, keys);
            if (attr.attribute.equals("timestamp")) {
                while (count < 200) {
                    currCells.clear();
                    keys.clear();
                    count = getDeletionCells(instantiator, attr, currCells, keys);
                }
            }
            System.out.println(String.join(",", keys));
            deletionCells.addAll(currCells);
        }
        System.out.println(deletionCells.size());
        HashMap<Cell, HashMap<Long, HashMap<Rule, ArrayList<Cell.HyperEdge>>>> cell2InsertionTime2Rule2InstantiationCache = new HashMap<>();

        System.out.println("Size,InstantiatedCells,DeletionCount");

        int n = allRules.size();
        for (int i = 0; i < (1 << n); i++) {
            HashSet<Rule> currentRuleSet = new HashSet<>(allRules.size(), 1f);
            int instantiatedCells = 0, deletedCells = 0;
            for (int j = 0; j < n; j++) {
                if ((i & (1 << j)) > 0) {
                    currentRuleSet.add(allRules.get(j));
                }
            }

            var cachingInstantiator = new CachingInstantiator(currentRuleSet, cell2InsertionTime2Rule2InstantiationCache, attributeInHead, attributeInTail, tableName2keyCol);
            for (var deleted : deletionCells) {
                var currModel = new InstantiatedModel(deleted, cachingInstantiator);
                var toDelete = Main.optimalDelete(currModel, deleted);
                instantiatedCells += currModel.instantiationTime.size() - 1;
                deletedCells += toDelete.size() - 1;
            }
            cachingInstantiator.closeConnection();
            System.out.println(i + "," + instantiatedCells + "," + deletedCells);
        }
    }

    static class CachingInstantiator extends Instatiator {
        HashSet<Rule> currentRules;
        HashMap<Cell, HashMap<Long, HashMap<Rule, ArrayList<Cell.HyperEdge>>>> cell2InsertionTime2Rule2InstantiationCache;

        public CachingInstantiator(HashSet<Rule> currentRules, HashMap<Cell, HashMap<Long, HashMap<Rule, ArrayList<Cell.HyperEdge>>>> cell2InsertionTime2Rule2InstantiationCache, HashMap<Attribute, ArrayList<Rule>> attributeInHead, HashMap<Attribute, ArrayList<Rule>> attributeInTail, HashMap<String, String> tableName2keyCol) throws SQLException {
            super(attributeInHead, attributeInTail, tableName2keyCol);
            this.currentRules = currentRules;
            this.cell2InsertionTime2Rule2InstantiationCache = cell2InsertionTime2Rule2InstantiationCache;
        }

        @Override
        public void iterateRules(Cell start, long sourceInsertionTime, ArrayList<Cell.HyperEdge> result, HashMap<Attribute, ArrayList<Rule>> connectedRules) throws SQLException {
            for (var rule : connectedRules.getOrDefault(start.attribute, EMPTY_LIST)) {
                if (currentRules.contains(rule)) {
                    var cellCache = cell2InsertionTime2Rule2InstantiationCache.computeIfAbsent(start, k -> new HashMap<>());
                    var itCache = cellCache.computeIfAbsent(sourceInsertionTime, k -> new HashMap<>());
                    var ruleResult = itCache.get(rule);
                    if (ruleResult == null) {
                        try (var rs = queryRule(rule, start, sourceInsertionTime)) {
                            ruleResult = resultSetToCellList(rule, start, rs, sourceInsertionTime);
                            itCache.put(rule, ruleResult);
                        }
                    }
                    result.addAll(ruleResult);
                }
            }
        }

        public void closeConnection() throws SQLException {
            c.commit();
            c.close();
        }
    }
}
