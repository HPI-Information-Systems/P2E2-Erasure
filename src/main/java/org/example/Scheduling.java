package org.example;

import org.example.RelationalDependencyRules.Attribute;
import org.example.RelationalDependencyRules.Cell;
import org.example.RelationalDependencyRules.Rule;

import java.sql.SQLException;
import java.util.*;

import static org.example.Main.*;

public class Scheduling {
    final static long baseFrequence = 24 * ConfigParameter.baseFrequency;

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
            if (pair.first > maxTime) {
                break;
            }
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
        HashSet<Pair> pairs = new LinkedHashSet<>();
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
        for (var rule : derivedData) {
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

                reconstructions[0] += baseReconstructions(dependentValues, ConfigParameter.startSchedule, ConfigParameter.endSchedule);
                for (int i = 0; i < 24; i++) {
                    reconstructions[i + 1] += constructScheduleWithGraceMaxOverlap(dependentValues, i * ConfigParameter.baseFrequency, ConfigParameter.startSchedule, ConfigParameter.endSchedule);
                }
            }
            System.out.println("base," + reconstructions[0]);
            for (int i = 1; i < reconstructions.length; i++) {
                System.out.println((i - 1) + "," + reconstructions[i]);
            }
        }
    }

    public static void mixScheduleDemandExperiment(Instatiator instatiator) throws Exception {
        var random = new Random();
        var start = ConfigParameter.startSchedule;
        var end = ConfigParameter.endSchedule;
        var gracePeriod = ConfigParameter.baseFrequency;
        var reconstructions = 0L;
        var deletedCells = 0L;

        HashMap<Cell, ArrayList<Cell>> derivedData2BaseData = new HashMap<>();
        HashMap<Cell, Long> randomDeletionTime = new HashMap<>();

        var keys = instatiator.getKeys(derivedData.get(0).head);
        HashMap<String, ArrayList<Cell>> key2Cell = new HashMap<>();
        for (var rule : derivedData) {
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
                            // var delTime = (random.nextInt((int) ((child.insertionTime - start) / 1000))) * 1000L;
                            var delTime = random.nextInt((int) (child.insertionTime - start));
                            randomDeletionTime.put(child, start + delTime);
                            dependentValues.add(child);
                        }
                    }
                }
                Collections.sort(dependentValues);
                derivedData2BaseData.put(cell, dependentValues);
            }
        }

        for (int retentionDrivenShare = 0; retentionDrivenShare <= ConfigParameter.numKeys; retentionDrivenShare += (ConfigParameter.numKeys / 10)) {
            var retentionKeys = new ArrayList<String>(retentionDrivenShare);
            var demandKeys = new ArrayList<String>(ConfigParameter.numKeys - retentionDrivenShare);

            Collections.shuffle(keys);
            for (int i = 0; i < retentionDrivenShare; i++) {
                retentionKeys.add(keys.get(i));
            }
            for (int i = retentionDrivenShare; i < keys.size(); i++) {
                demandKeys.add(keys.get(i));
            }

            HashSet<Pair> pairs = new LinkedHashSet<>();
            HashMap<Long, HashSet<Pair>> deletionSchedule = new HashMap<>();
            HashSet<Cell> retentionKeyCellSet = new HashSet<>();
            var retentionCells = new ArrayList<Cell>();

            for (var key : retentionKeys) {
                var retentionKeyCells = key2Cell.get(key);
                retentionKeyCellSet.addAll(retentionKeyCells);
                for (var cell : retentionKeyCells) {
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
                    reconstructions += baseReconstructions(derivedData2BaseData.get(keyCell), start, end);
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
            while (currRetentionTime != -1 || currDemandTime != -1) {
                if (currRetentionTime != -1 && (currRetentionTime <= currDemandTime || currDemandTime == -1)) {
                    // process retention driven erasures
                    curr = currRetentionTime;
                    if (curr - batchStart >= gracePeriod) {
                        var model = new InstantiatedModel(batch, instatiator);
                        deletedCells += batchedOptimalDelete(model, batch).size();
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
                        deletedCells += batchedOptimalDelete(model, batch).size();
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
            System.out.println(retentionDrivenShare + "," + reconstructions + "," + deletedCells);
            reconstructions = 0;
            deletedCells = 0;
        }
    }
}
