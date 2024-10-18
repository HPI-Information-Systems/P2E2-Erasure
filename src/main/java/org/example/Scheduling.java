package org.example;

import org.example.RelationalDependencyRules.Attribute;
import org.example.RelationalDependencyRules.Cell;
import org.example.RelationalDependencyRules.Rule;
import org.apache.commons.csv.CSVFormat;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.util.*;

public class Scheduling {
    final static long baseFrequence = 24 * 3600000;
    private static ArrayList<Rule> parseRules(String path) throws Exception {
        ArrayList<Rule> rules = new ArrayList<>();
        var parser = CSVFormat.DEFAULT.parse(Files.newBufferedReader(Paths.get(path)));
        for (var record : parser) {
            var rule = Main.parseRule(record);
            rules.add(rule);
        }
        return rules;
    }

    static class Pair {
        long first;
        long second;
        boolean incoming;
        Cell value;

        public Pair(long l, boolean b) {
            this.first = l;
            this.incoming = b;
        }

        public Pair(long l, boolean b, long second, Cell value) {
            this.first = l;
            this.incoming = b;
            this.second = second;
            this.value = value;
        }
    }

    private static long maxOverlap(HashSet<Pair> deletionIntervals, HashSet<Pair> removedElems, long gracePeriod, long startTime, long maxTime) {
        ArrayList<Pair> applicablePairs = new ArrayList<>();
        for (var pair : deletionIntervals) {
            if (pair.incoming && pair.first >= startTime && pair.first <= maxTime) {
                applicablePairs.add(pair);
                applicablePairs.add(new Pair(pair.first + gracePeriod, false));
            }
        }

        if (applicablePairs.isEmpty()) {
            return maxTime;
        }

        applicablePairs.sort(Comparator.comparingLong(a -> a.first));

        int max = 0, count = 0;
        long bestTs = 0;
        for (var pair : applicablePairs) {
            if (pair.incoming) {
                count++;
                if (count > max) {
                    max = count;
                    bestTs = pair.first;
                }
            } else {
                count--;
            }
        }

        for (var pair : applicablePairs) {
            if (pair.incoming && pair.first <= bestTs && pair.second >= bestTs) {
                deletionIntervals.remove(pair);
                removedElems.add(pair);
            }
        }

        return bestTs;
    }

    private static long constructScheduleWithGraceMaxOverlap(ArrayList<Cell> dependentValues, long gracePeriod, long start, long end, HashSet<Pair> pairs, HashMap<Long, HashSet<Pair>> deletionSchedule) {
        var curr = start;
        for (var cell : dependentValues) {
            pairs.add(new Pair(cell.insertionTime, true, cell.insertionTime + gracePeriod, cell));
        }

        while (curr < end - baseFrequence) {
            HashSet<Pair> depSet = new HashSet<>();
            var maxOverlapTs = maxOverlap(pairs, depSet, gracePeriod, curr, curr + baseFrequence);
            deletionSchedule.put(maxOverlapTs, depSet);
            curr = maxOverlapTs;
        }

        return deletionSchedule.size() + pairs.size();
    }

    private static long constructScheduleWithGraceMaxOverlap(ArrayList<Cell> dependentValues, long gracePeriod, long start, long end) {
        HashMap<Long, HashSet<Pair>> deletionSchedule = new HashMap<>();
        HashSet<Pair> pairs = new HashSet<>();
        return constructScheduleWithGraceMaxOverlap(dependentValues, gracePeriod, start, end, pairs, deletionSchedule);
    }

    public static long baseReconstructions(ArrayList<Cell> dependentValues, long start, long end) {
        int reconstructions = 0;
        long lastUpdate = start;

        for (var dep : dependentValues) {
            while (lastUpdate + baseFrequence < dep.insertionTime) {
                lastUpdate += baseFrequence;
                reconstructions++;
            }
            reconstructions++;
        }
        while (lastUpdate < end) {
            lastUpdate += baseFrequence;
            reconstructions++;
        }
        return reconstructions;
    }

    public static void scheduleExperiment(Instatiator instatiator) throws Exception {
        var rules = parseRules("/home/y/neu/erasure/derived_exSmall.csv");
        var start = 1640991600000L;
        var end = 1672527600000L;

        for (var rule : rules) {
            long[] reconstructions = new long[25];
            var keys = instatiator.getKeys(rule.head);

            for (var key : keys) {
                var dependentValues = new ArrayList<Cell>();
                var cell = new Cell(rule.head, key);
                instatiator.completeCell(cell);

                try (var rs = instatiator.queryRule(rule, cell, cell.insertionTime)) {
                    for (var edge : instatiator.resultSetToCellList(rule, cell, rs, cell.insertionTime)) {
                        for (var child : edge) {
                            dependentValues.add(instatiator.completeCell(child));
                        }
                    }
                }
                Collections.sort(dependentValues);

                var base = baseReconstructions(dependentValues, start, end);
                reconstructions[0] += base;
                System.out.println("base," + base);
                for (int i = 0; i < 24; i++) {
                    var schedule = constructScheduleWithGraceMaxOverlap(dependentValues, i * 3600000L, start, end);
                    reconstructions[i + 1] += schedule;
                    System.out.println(i + "," + schedule);
                }
            }
            System.out.println();
            System.out.println("base," + reconstructions[0]);
            for (int i = 1; i < reconstructions.length; i++) {
                System.out.println((i - 1) + "," + reconstructions[i]);
            }
        }
    }

    public static void mixScheduleDemandExperiment(Instatiator instatiator) throws Exception {
        var random = new Random();
        var rules = parseRules(ConfigParameter.configPath + ConfigParameter.derivedFile);
        var start = 1640991600000L;
        var end = 1672527600000L;
        var gracePeriod = 60 * 60 * 1000;
        var reconstructions = 0L;
        var baseReonstructionCount = 0L;
        var deletedCells = 0L;

        HashMap<Cell, ArrayList<Cell>> derivedData2BaseData = new HashMap<>();
        HashMap<Cell, Long> randomDeletionTime = new HashMap<>();
        var retentionAwareInstantiator = new RetentionAwareInstantiator(instatiator.attributeInHead, instatiator.attributeInTail, instatiator.tableName2keyCol);

        var keys = instatiator.getKeys(rules.get(0).head);
        HashMap<String, ArrayList<Cell>> key2Cell = new HashMap<>();
        for (var rule : rules) {
            for (var key : keys) {
                var dependentValues = new ArrayList<Cell>();
                var cell = new Cell(rule.head, key);
                instatiator.completeCell(cell);
                key2Cell.computeIfAbsent(key, a -> new ArrayList<>()).add(cell);

                try (var rs = instatiator.queryRule(rule, cell, cell.insertionTime)) {
                    for (var edge : instatiator.resultSetToCellList(rule, cell, rs, cell.insertionTime)) {
                        for (var child : edge) {
                            instatiator.completeCell(child);
                            // delete before it "expires"
                            var delTime = (random.nextInt((int) ((child.insertionTime - start) / 1000))) * 1000L;
                            randomDeletionTime.put(child, start + delTime);
                            dependentValues.add(child);
                        }
                    }
                }
                Collections.sort(dependentValues);
                derivedData2BaseData.put(cell, dependentValues);
            }
        }
        Collections.shuffle(keys);

        for (int retentionDrivenShare = 0; retentionDrivenShare <= ConfigParameter.numKeys; retentionDrivenShare += (ConfigParameter.numKeys / 10)) {
            var retentionKeys = new ArrayList<String>(retentionDrivenShare);
            var demandKeys = new ArrayList<String>(ConfigParameter.numKeys - retentionDrivenShare);

            for (int i = 0; i < retentionDrivenShare; i++) {
                retentionKeys.add(keys.get(i));
            }
            for (int i = retentionDrivenShare; i < keys.size(); i++) {
                demandKeys.add(keys.get(i));
            }

            HashSet<Pair> pairs = new HashSet<>();
            HashMap<Long, HashSet<Pair>> deletionSchedule = new HashMap<>();
            HashSet<Cell> retentionKeyCellSet = new HashSet<>();
            var retentionCells = new ArrayList<Cell>();

            for (var key : retentionKeys) {
                var retentionKeyCells = key2Cell.get(key);
                retentionKeyCellSet.addAll(retentionKeyCells);
                for (var cell : retentionKeyCells) {
                    baseReonstructionCount += baseReconstructions(derivedData2BaseData.get(cell), start, end);
                    reconstructions += constructScheduleWithGraceMaxOverlap(derivedData2BaseData.get(cell), gracePeriod, start, end);
                    retentionCells.addAll(derivedData2BaseData.get(cell));
                }
            }
            constructScheduleWithGraceMaxOverlap(retentionCells, gracePeriod, start, end, pairs, deletionSchedule);
            var finalSchedule = new TreeMap<Long, List<Cell>>();
            for (var entry : deletionSchedule.entrySet()) {
                ArrayList<Cell> cells = new ArrayList<>();
                for (var pair : entry.getValue()) {
                    cells.add(pair.value);
                }
                finalSchedule.put(entry.getKey(), cells);
            }
            for (var pair : pairs) {
                finalSchedule.put(pair.first, List.of(pair.value));
            }

            var delT2DemandCell = new TreeMap<Long, List<Cell>>();
            for (var key : demandKeys) {
                var demandKeyCells = key2Cell.get(key);
                for (var keyCell : demandKeyCells) {
                    var baseCount = baseReconstructions(derivedData2BaseData.get(keyCell), start, end);
                    reconstructions += baseCount;
                    baseReonstructionCount += baseCount;
                    for (var baseCell : derivedData2BaseData.get(keyCell)) {
                        delT2DemandCell.computeIfAbsent(randomDeletionTime.get(baseCell), a -> new ArrayList<>()).add(baseCell);
                    }
                }
            }

            var retentionIter = finalSchedule.keySet().iterator();
            var demandIter = delT2DemandCell.keySet().iterator();
            long currRetentionTime = -1, currDemandTime = -1;
            if (retentionIter.hasNext()) {
                currRetentionTime = retentionIter.next();
            }
            if (demandIter.hasNext()) {
                currDemandTime = demandIter.next();
            }

            var curr = start;

            var batchStart = start;
            ArrayList<Cell> batch = new ArrayList<>();
            retentionAwareInstantiator.retentionCells = retentionKeyCellSet;
            while (currRetentionTime != -1 || currDemandTime != -1) {
                if (currRetentionTime != -1 && (currRetentionTime <= currDemandTime || currDemandTime == -1)) {
                    // process retention driven erasures
                    curr = currRetentionTime;
                    if (curr - batchStart >= gracePeriod) {
                        var model = new InstantiatedModel(batch, instatiator);
                        deletedCells += Main.batchedApproximateDelete(model, batch).size();
                        batch.clear();
                        batchStart = curr;
                    }
                    batch.addAll(finalSchedule.get(curr));
                    if (retentionIter.hasNext()) {
                        currRetentionTime = retentionIter.next();
                    } else {
                        currRetentionTime = -1;
                    }
                }

                if (currDemandTime != -1 && (currDemandTime <= currRetentionTime || currRetentionTime == -1)) {
                    // process demand driven erasures
                    curr = currDemandTime;
                    if (curr - batchStart >= gracePeriod) {
                        var model = new InstantiatedModel(batch, instatiator);
                        deletedCells += Main.batchedApproximateDelete(model, batch).size();
                        batch.clear();
                        batchStart = curr;
                    }
                    batch.addAll(delT2DemandCell.get(curr));
                    if (demandIter.hasNext()) {
                        currDemandTime = demandIter.next();
                    } else {
                        currDemandTime = -1;
                    }
                }
            }
            System.out.println(retentionDrivenShare + "," + baseReonstructionCount + "," + reconstructions + "," + deletedCells);
            baseReonstructionCount = 0;
            reconstructions = 0;
            deletedCells = 0;
        }
    }


    static class RetentionAwareInstantiator extends Instatiator {
        HashSet<Cell> retentionCells;

        public RetentionAwareInstantiator(HashMap<Attribute, ArrayList<Rule>> attributeInHead, HashMap<Attribute, ArrayList<Rule>> attributeInTail, HashMap<String, String> tableName2keyCol) throws SQLException {
            super(attributeInHead, attributeInTail, tableName2keyCol);
        }

        @Override
        public void iterateRules(Cell start, long sourceInsertionTime, ArrayList<Cell.HyperEdge> result, HashMap<Attribute, ArrayList<Rule>> connectedRules) throws SQLException {
            for (var rule : connectedRules.getOrDefault(start.attribute, EMPTY_LIST)) {
                try (var rs = queryRule(rule, start, sourceInsertionTime)) {
                    var edges = resultSetToCellList(rule, start, rs, sourceInsertionTime);
                    var edgeIter = edges.iterator();
                    while (edgeIter.hasNext()) {
                        for (var cell : edgeIter.next()) {
                            if (retentionCells.contains(cell)) {
                                edgeIter.remove();
                                break;
                            }
                        }
                    }
                    result.addAll(edges);
                }
            }
        }
    }
}
