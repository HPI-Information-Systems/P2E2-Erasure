package org.example;

import org.example.RelationalDependencyRules.Cell;

import java.sql.SQLException;
import java.util.*;

public class InstantiatedModel {
    HashMap<Cell, ArrayList<Cell.HyperEdge>> cell2Edge = new HashMap<>();
    HashMap<Cell, Long> instantiationTime = new HashMap<>();
    LinkedList<HashSet<Cell>> treeLevels = new LinkedList<>();
    HashMap<Cell, HashSet<Cell>> cell2Parents = new HashMap<>();
    long modelConstructionTime = 0L;

    static boolean containsParent(Cell.HyperEdge edge, HashSet<Cell> parents) {
        for (var cell : edge) {
            if (parents.contains(cell)) {
                return true;
            }
        }
        return false;
    }

    public InstantiatedModel(Cell deleted, Instatiator instatiator) throws SQLException {
        this(List.of(deleted), instatiator);
    }

    public InstantiatedModel(List<Cell> deletedCells, Instatiator instatiator) throws SQLException {
        var start = System.nanoTime();
        HashMap<Cell, Cell> cell2Identity = new HashMap<>();
        var instantiatedCells = new HashSet<Cell>();

        if (deletedCells.size() > 1) {
            Collections.sort(deletedCells);
        }

        for (var deleted : deletedCells) {
            cell2Identity.put(deleted, deleted);
        }

        for (var deleted : deletedCells) {
            // Cell already handled
            if (!instantiatedCells.add(deleted)) {
                continue;
            }
            HashSet<Cell> nextLevel = new HashSet<>();
            HashSet<Cell> currLevel = new HashSet<>();
            cell2Parents.put(deleted, new HashSet<>(0));
            currLevel.add(deleted);

            while (!currLevel.isEmpty()) {
                HashMap<Cell, HashSet<Cell>> localCell2Parents = new HashMap<>();
                for (var curr : currLevel) {
                    var instantiationStart = System.nanoTime();
                    var result = instatiator.instantiateAttachedCells(curr, deleted.insertionTime);
                    instantiationTime.put(curr, System.nanoTime() - instantiationStart);
                    for (var edge : result) {
                        if (!containsParent(edge, cell2Parents.get(curr))) {
                            var cellIter = edge.iterator();
                            var newCells = new ArrayList<Cell>(edge.size());
                            while (cellIter.hasNext()) {
                                var cell = cellIter.next();
                                var unifiedCell = cell2Identity.get(cell);
                                if (unifiedCell == null) {
                                    cell2Identity.put(cell, cell);
                                    unifiedCell = cell;
                                } else {
                                    cellIter.remove();
                                    newCells.add(unifiedCell);
                                }
                                localCell2Parents.computeIfAbsent(unifiedCell, a -> new HashSet<>()).add(curr);
                                if (instantiatedCells.add(unifiedCell)) {
                                    nextLevel.add(unifiedCell);
                                }
                            }
                            edge.addAll(newCells);
                            cell2Edge.computeIfAbsent(curr, a -> new ArrayList<>()).add(edge);
                        }
                    }
                }
                for (var entry : localCell2Parents.entrySet()) {
                    cell2Parents.merge(entry.getKey(), entry.getValue(), (a, b) -> {
                        a.addAll(b);
                        return a;
                    });
                }
                treeLevels.addFirst(currLevel);
                currLevel = nextLevel;
                nextLevel = new HashSet<>();
            }
            modelConstructionTime = System.nanoTime() - start;
        }
    }
}
