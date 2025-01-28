package org.example;

import org.example.RelationalDependencyRules.Rule;
import org.example.RelationalDependencyRules.Attribute;
import org.example.RelationalDependencyRules.Cell;
import org.example.RelationalDependencyRules.Cell.HyperEdge;
import com.gurobi.gurobi.*;
import org.apache.commons.csv.*;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

public class Main {
    final static ArrayList<Rule> rules = new ArrayList<>();
    final static ArrayList<Rule> derivedData = new ArrayList<>();
    final static HashSet<Attribute> derivedAttributes = new HashSet<>();
    final static HashMap<Attribute, ArrayList<Rule>> attributeInHead = new HashMap<>();
    final static HashMap<Attribute, ArrayList<Rule>> attributeInTail = new HashMap<>();
    final static HashMap<String, String> tableName2keyCol = new HashMap<>();
    final static ArrayList<HyperEdge> EMPTY_LIST = new ArrayList<>(0);
    static GRBEnv env;

    private static void parseConfigFile(String jsonString) throws Exception {
        JSONObject root = new JSONObject(jsonString);

        if (root.has("dataset")) {
            ConfigParameter.setDataset(root.getString("dataset"));
        }
        if (root.has("configPath")) {
            ConfigParameter.configPath = root.getString("configPath");
        }
        if (root.has("ruleFile")) {
            ConfigParameter.ruleFile = root.getString("ruleFile");
        }
        if (root.has("schemaFile")) {
            ConfigParameter.schemaFile = root.getString("schemaFile");
        }
        if (root.has("derivedFile")) {
            ConfigParameter.derivedFile = root.getString("derivedFile");
        }
        if (root.has("resultFile")) {
            ConfigParameter.resultFile = root.getString("resultFile");
        }
        if (root.has("batching")) {
            ConfigParameter.batching = root.getBoolean("batching");
        }
        if (root.has("scheduling")) {
            ConfigParameter.scheduling = root.getBoolean("scheduling");
        }
        if (root.has("averageDependence")) {
            ConfigParameter.averageDependence = root.getBoolean("averageDependence");
        }
        if (root.has("numKeys")) {
            ConfigParameter.numKeys = root.getInt("numKeys");
        }
        if (root.has("batchSizes")) {
            var jsonArray = root.getJSONArray("batchSizes");
            ConfigParameter.batchSizes = new int[jsonArray.length()];
            for (int i = 0; i < jsonArray.length(); i++) {
                ConfigParameter.batchSizes[i] = jsonArray.getInt(i);
            }
        }
        if (root.has("isBatchSizeTime")) {
            ConfigParameter.isBatchSizeTime = root.getBoolean("isBatchSizeTime");
        }
        if (root.has("connectionUrl")) {
            ConfigParameter.connectionUrl = root.getString("connectionUrl");
        }
        if (root.has("database")) {
            ConfigParameter.database = root.getString("database");
        }
        if (root.has("username")) {
            ConfigParameter.username = root.getString("username");
        }
        if (root.has("password")) {
            ConfigParameter.password = root.getString("password");
        }
        if (root.has("startSchedule")) {
            ConfigParameter.startSchedule = root.getLong("startSchedule");
        }
        if (root.has("endSchedule")) {
            ConfigParameter.endSchedule = root.getLong("endSchedule");
        }
        if (root.has("baseFrequency")) {
            ConfigParameter.baseFrequency = root.getLong("baseFrequency");
        }
    }

    public static void main(String[] args) throws Exception {
        String configFilePath = args.length > 0 ? args[0] : "config.json";
        parseConfigFile(Files.readString(Paths.get(configFilePath)));

        env = new GRBEnv();
        env.set(GRB.IntParam.OutputFlag, 0);
        env.set(GRB.IntParam.LogToConsole, 0);

        parseRules();
        parseSchema();
        parseDerivedData();

        var baseAttributes = new HashSet<Attribute>();
        baseAttributes.addAll(attributeInTail.keySet());
        baseAttributes.addAll(attributeInHead.keySet());
        baseAttributes.removeAll(derivedAttributes);

        checkNonCyclicRules(baseAttributes);

        var instatiator = new Instatiator(attributeInHead, attributeInTail, tableName2keyCol);

        // switch between experiments
        if (ConfigParameter.averageDependence) {
            AverageDependence.averageDependence(baseAttributes, rules, attributeInHead, attributeInTail, tableName2keyCol);
        } else if (ConfigParameter.batching && ConfigParameter.scheduling) {
            Scheduling.mixScheduleDemandExperiment(instatiator);
        } else if (ConfigParameter.batching) {
            compareBatch(instatiator, baseAttributes);
        } else if (ConfigParameter.scheduling) {
            Scheduling.scheduleExperiment(instatiator);
        } else {
            iterateAttributes(instatiator, baseAttributes);
        }
    }

    private static void checkNonCyclicRules(HashSet<Attribute> allAttributes) {
        HashSet<Attribute> checkedAttributes = new HashSet<>();
        for (var startAttribute : allAttributes) {
            if (checkedAttributes.contains(startAttribute)) {
                continue;
            }
            Queue<Attribute> attributesToVisit = new LinkedList<>();
            HashMap<Attribute, Attribute> attr2Parent = new HashMap<>();
            attributesToVisit.add(startAttribute);
            attr2Parent.put(startAttribute, new Attribute("", ""));
            checkedAttributes.add(startAttribute);

            while (!attributesToVisit.isEmpty()) {
                var curr = attributesToVisit.poll();
                for (var rule : attributeInHead.getOrDefault(curr, new ArrayList<>(0))) {
                    for (var attr : rule.tail) {
                        assert (attr2Parent.get(attr) == null || attr2Parent.get(curr).equals(attr)) || attr.equals(curr);
                        if (!attr.equals(curr)) {
                            attr2Parent.put(attr, curr);
                        }
                        if (checkedAttributes.add(attr)) {
                            attributesToVisit.add(attr);
                        }
                    }
                }
                for (var rule : attributeInTail.getOrDefault(curr, new ArrayList<>(0))) {
                    var attr = rule.head;
                    assert (attr2Parent.get(attr) == null || attr2Parent.get(curr).equals(attr)) || attr.equals(curr);
                    if (!attr.equals(curr)) {
                        attr2Parent.put(attr, curr);
                    }
                    if (checkedAttributes.add(attr)) {
                        attributesToVisit.add(attr);
                    }
                }
            }
        }
    }

    private static HashSet<Cell> runDeletionMethod(Cell deleted, InstantiatedModel instantiatedModel, int deletionMethod, long[] countsArray) throws Exception {
        HashSet<Cell> result = null;
        switch (deletionMethod) {
            case 0:
                result = optimalDelete(instantiatedModel, deleted);
                break;
            case 1:
                result = approximateDelete(instantiatedModel, deleted);
                break;
            case 2:
                result = ilpApproach(instantiatedModel, deleted);
                break;
        }
        countsArray[0] += result.size() - 1;
        return result;
    }

    private static void iterateAttributes(Instatiator instatiator, Set<Attribute> attributes) throws Exception {
        writeHeader();
        HashSet<Cell>[] deletionSets = new HashSet[3];

        for (var attr : attributes) {
            if (derivedAttributes.contains(attr)) continue;
            System.out.print(attr.toString() + ",");
            var keys = instatiator.getKeys(attr);
            for (var key : keys) {
                var deletionCell = new Cell(attr, key);
                instatiator.completeCell(deletionCell);
                var instantiatedModel = new InstantiatedModel(deletionCell, instatiator);
                deletionSets[0] = runDeletionMethod(deletionCell, instantiatedModel, 0, Utils.optimalCounts);
                deletionSets[1] = runDeletionMethod(deletionCell, instantiatedModel, 1, Utils.approximateCounts);
                deletionSets[2] = runDeletionMethod(deletionCell, instantiatedModel, 2, Utils.ilpCounts);

                // speed up experiments by only applying deletes once for optimal/ilp
                assert deletionSets[0].size() == deletionSets[2].size();
                var deletionTime = instatiator.deleteCells(deletionSets[2]);
                instatiator.resetValues(deletionSets[2]);
                Utils.optimalTimes[4] += deletionTime;
                Utils.ilpTimes[4] += deletionTime;
                if (deletionSets[0].size() == deletionSets[1].size()) {
                    Utils.approximateTimes[4] += deletionTime;
                } else {
                    Utils.approximateTimes[4] += instatiator.deleteCells(deletionSets[1]);
                    instatiator.resetValues(deletionSets[1]);
                }
            }
            writeOutput();
        }
    }

    private static void compareBatch(Instatiator instatiator, Set<Attribute> attributes) throws Exception {
        HashSet<Cell>[] deletionSets = new HashSet[3];
        var totalBatchSize = (ConfigParameter.numKeys * attributes.size()) - (ConfigParameter.numKeys * attributes.size()) % ConfigParameter.batchSizes[ConfigParameter.batchSizes.length - 1];
        var batch = new ArrayList<Cell>(ConfigParameter.numKeys * attributes.size());

        if (ConfigParameter.isBatchSizeTime) {
            for (var attr : attributes) {
                var keys = instatiator.getKeysInTime(attr, ConfigParameter.startSchedule, ConfigParameter.endSchedule);
                for (var key : keys) {
                    var deletionCell = new Cell(attr, key);
                    instatiator.completeCell(deletionCell);
                    batch.add(deletionCell);
                }
            }

            Collections.sort(batch);

            for (int i = 1; i < ConfigParameter.batchSizes.length; i++) {
                var currTs = ConfigParameter.startSchedule;
                var batchSize = ConfigParameter.batchSizes[i];
                ArrayList<Cell> subBatch = new ArrayList<>();

                for (Cell cell : batch) {
                    if (cell.insertionTime - currTs > batchSize) {
                        processBatch(instatiator, deletionSets, subBatch);
                        currTs += batchSize;
                    }
                    subBatch.add(cell);
                }
                if (!subBatch.isEmpty()) {
                    processBatch(instatiator, deletionSets, subBatch);
                }
                System.out.print(batchSize + ",");
                writeOutput();
            }
        } else {
            for (var attr : attributes) {
                var keys = instatiator.getKeys(attr);
                for (var key : keys) {
                    if (batch.size() == totalBatchSize) {
                        break;
                    }
                    var deletionCell = new Cell(attr, key);
                    instatiator.completeCell(deletionCell);
                    batch.add(deletionCell);
                }
            }

            Collections.sort(batch);

            for (var batchSize : ConfigParameter.batchSizes) {
                ArrayList<Cell> subBatch = new ArrayList<>(batchSize);
                for (int rowIdx = 0; rowIdx < batch.size(); rowIdx++) {
                    subBatch.add(batch.get(rowIdx));
                    if ((rowIdx + 1) % batchSize == 0) {
                        processBatch(instatiator, deletionSets, subBatch);
                    }
                }
                System.out.print(batchSize + ",");
                writeOutput();
            }
        }
    }

    private static void processBatch(Instatiator instatiator, HashSet<Cell>[] deletionSets, ArrayList<Cell> subBatch) throws Exception {
        var model = new InstantiatedModel(subBatch, instatiator);

        deletionSets[0] = batchedOptimalDelete(model, subBatch);
        Utils.optimalCounts[0] += deletionSets[0].size() - subBatch.size();
        deletionSets[1] = batchedApproximateDelete(model, subBatch);
        Utils.approximateCounts[0] += deletionSets[1].size() - subBatch.size();
        deletionSets[2] = batchedIlpApproach(model, subBatch);
        Utils.ilpCounts[0] += deletionSets[2].size() - subBatch.size();

        HashMap<Integer, Long> deletionCount = new HashMap<>(3, 1.0f);
        for (int i = 0; i < 3; i++) {
            var delTime = deletionCount.get(deletionSets[i].size());
            if (delTime == null) {
                delTime = instatiator.deleteCells(deletionSets[i]);
                instatiator.resetValues(deletionSets[i]);
                deletionCount.put(deletionSets[i].size(), delTime);
            }
            switch (i) {
                case 0:
                    Utils.optimalTimes[4] += delTime;
                    break;
                case 1:
                    Utils.approximateTimes[4] += delTime;
                    break;
                case 2:
                    Utils.ilpTimes[4] += delTime;
                    break;
            }
        }
        subBatch.clear();
    }

    private static void writeHeader() {
        System.out.println("Attribute,optimalTime,optimalInstantiationTime,optimalModelTime,optimalOptimizationTime,optimalDeletionTime,approximateTime,approximateInstantiationTime,approximateModelTime,approximateOptimizationTime,approximateDeletionTime,ilpTime,ilpInstantiationTime,ilpModelTime,ilpOptimizationTime,ilpDeletionTime,optimalDeletes,optimalInstantiations,optimalHeight,optimalMemory,approximateDeletes,approximateInstantiations,approximateHeight,approximateMemory,ilpDeletes,ilpInstantiations,ilpHeight,ilpMemory");
    }

    private static String getTimeString(long time) {
        return String.valueOf((long) (time / 1e6));
    }

    private static void writeOutput() {
        ArrayList<String> output = new ArrayList<>();
        // subtract instantiation time from model construction
        Utils.optimalTimes[2] -= Utils.optimalTimes[1];
        // no model construction for approximate version
        Utils.ilpTimes[2] -= Utils.ilpTimes[1];
        for (var time : Utils.optimalTimes) {
            output.add(getTimeString(time));
        }
        for (var time : Utils.approximateTimes) {
            output.add(getTimeString(time));
        }
        for (var time : Utils.ilpTimes) {
            output.add(getTimeString(time));
        }
        for (var count : Utils.optimalCounts) {
            output.add(String.valueOf(count));
        }
        for (var count : Utils.approximateCounts) {
            output.add(String.valueOf(count));
        }
        for (var count : Utils.ilpCounts) {
            output.add(String.valueOf(count));
        }
        System.out.println(String.join(",", output));
        Arrays.fill(Utils.optimalTimes, 0L);
        Arrays.fill(Utils.approximateTimes, 0L);
        Arrays.fill(Utils.ilpTimes, 0L);
        Arrays.fill(Utils.optimalCounts, 0L);
        Arrays.fill(Utils.approximateCounts, 0L);
        Arrays.fill(Utils.ilpCounts, 0L);
    }

    public static HashSet<Cell> optimalDelete(InstantiatedModel model, Cell deleted) {
        Utils.optimalCounts[1] += model.instantiationTime.size() - 1;
        Utils.optimalCounts[2] += model.treeLevels.size();
        Utils.optimalTimes[2] += model.modelConstructionTime;

        var start = System.nanoTime();
        for (var currLevels : model.treeLevels) {
            for (var currCell : currLevels) {
                Utils.optimalTimes[1] += model.instantiationTime.getOrDefault(currCell, 0L);
                var childrenEdges = model.cell2Edge.get(currCell);
                currCell.cost = 1;
                if (childrenEdges != null) {
                    for (var edge : childrenEdges) {
                        long minCost = Integer.MAX_VALUE;
                        Cell minCell = null;

                        for (var cell : edge) {
                            if (minCell == null || cell.cost < minCost) {
                                minCell = cell;
                                minCost = cell.cost;
                            }
                        }
                        edge.minCell = minCell;
                        currCell.cost += minCost;
                    }
                }
            }
        }

        Queue<Cell> cellsToVisit = new LinkedList<>();
        cellsToVisit.add(deleted);
        HashSet<Cell> toDelete = new HashSet<>();
        toDelete.add(deleted);

        while (!cellsToVisit.isEmpty()) {
            var currCell = cellsToVisit.poll();
            var edges = model.cell2Edge.get(currCell);
            if (edges != null) {
                for (var edge : model.cell2Edge.get(currCell)) {
                    if (toDelete.add(edge.minCell)) {
                        cellsToVisit.add(edge.minCell);
                    }
                }
            }
        }
        Utils.optimalTimes[3] += System.nanoTime() - start;
        if (ConfigParameter.measureMemory) {
            Utils.optimalCounts[3] += measureOptimalMemory(model, deleted);
        }

        return toDelete;
    }

    private static long measureOptimalMemory(InstantiatedModel model, Cell deleted) {
        long size = 0;
        LinkedList<Cell> cellsToVisit = new LinkedList<>();
        HashSet<Cell> instantiatedCells = new HashSet<>();
        cellsToVisit.add(deleted);
        while (!cellsToVisit.isEmpty()) {
            var curr = cellsToVisit.poll();
            // per cell: 4 bytes for the table index, 4 bytes for the row index, 4 bytes insertionTime, 1 byte state (deleted) and 4 bytes cost
            size += 4 + 4 + 4 + 1 + 4;
            var edges = model.cell2Edge.get(curr);
            if (edges != null) {
                for (var edge : edges) {
                    // 8 bytes per element in hyperedge + 8 bytes for pointer from head to edge + 4 bytes for the cheapest node
                    size += edge.size() * 8L + 8L + 4L;
                    for (var cell : edge) {
                        if (instantiatedCells.add(cell)) {
                            cellsToVisit.add(cell);
                        }
                    }
                }
            }
        }
        return size;
    }

    private static boolean areAllChildrenInitialized(ArrayList<HyperEdge> edges) {
        for (var edge : edges) {
            for (var cell : edge) {
                if (cell.cost == Integer.MAX_VALUE) return false;
            }
        }
        return true;
    }

    static HashSet<Cell> batchedOptimalDelete(InstantiatedModel model, ArrayList<Cell> deletedCells) {
        Utils.optimalCounts[1] += model.instantiationTime.size() - deletedCells.size();
        Utils.optimalTimes[2] += model.modelConstructionTime;

        var start = System.nanoTime();
        LinkedList<Cell> cellsToVisit = new LinkedList<>();
        HashSet<Cell> instantiatedCells = new HashSet<>();

        for (var deleted : deletedCells) {
            if (instantiatedCells.add(deleted)) {
                cellsToVisit.add(deleted);
                while (!cellsToVisit.isEmpty()) {
                    var curr = cellsToVisit.poll();
                    var edges = model.cell2Edge.get(curr);
                    if (edges == null) {
                        // leaf node
                        Utils.optimalTimes[1] += model.instantiationTime.getOrDefault(curr, 0L);
                        curr.cost = 1;
                    } else {
                        // inner node
                        if (areAllChildrenInitialized(edges)) {
                            Utils.optimalTimes[1] += model.instantiationTime.getOrDefault(curr, 0L);
                            curr.cost = 1;
                            for (var edge : edges) {
                                long minCost = Integer.MAX_VALUE;
                                Cell minCell = null;

                                for (var cell : edge) {
                                    if (minCell == null || cell.cost < minCost) {
                                        minCell = cell;
                                        minCost = cell.cost;
                                    }
                                }
                                edge.minCell = minCell;
                                curr.cost += minCost;
                            }
                        } else {
                            for (var edge : edges) {
                                for (var cell : edge) {
                                    if (instantiatedCells.add(cell)) {
                                        cellsToVisit.addFirst(cell);
                                    }
                                }
                            }
                            cellsToVisit.addLast(curr);
                        }
                    }
                }
            }
        }

        for (var deleted : deletedCells) {
            var parents = model.cell2Parents.get(deleted);
            for (var parent : parents) {
                for (var edge : model.cell2Edge.get(parent)) {
                    if (edge.contains(deleted)) {
                        edge.minCell = deleted;
                    }
                }
            }
        }

        HashSet<Cell> toDelete = new HashSet<>();
        for (var deleted : deletedCells) {
            cellsToVisit.add(deleted);

            while (!cellsToVisit.isEmpty()) {
                var currCell = cellsToVisit.poll();

                if (toDelete.add(currCell)) {
                    var edges = model.cell2Edge.get(currCell);
                    if (edges != null) {
                        for (var edge : edges) {
                            cellsToVisit.add(edge.minCell);
                        }
                    }
                }
            }
        }
        Utils.optimalTimes[3] += System.nanoTime() - start;

        return toDelete;
    }

    private static HashSet<Cell> ilpApproach(InstantiatedModel model, Cell deleted) throws GRBException {
        Utils.ilpTimes[2] += model.modelConstructionTime;
        var start = System.nanoTime();
        int maxId = 0;
        int edgeCounter = -1;
        HashMap<Cell, Integer> cell2Id = new HashMap<>();
        HashMap<Cell, GRBVar> cell2Var = new HashMap<>();
        HashSet<Cell> instantiatedCells = new HashSet<>();
        HashSet<Cell> toDelete = new HashSet<>();
        Queue<Cell> cellsToVisit = new LinkedList<>();
        GRBModel grbModel = new GRBModel(env);
        GRBLinExpr obj = new GRBLinExpr();
        cell2Var.put(deleted, grbModel.addVar(1, 1, 0, GRB.BINARY, "a0"));
        cell2Id.put(deleted, maxId++);
        cellsToVisit.add(deleted);
        instantiatedCells.add(deleted);

        while (!cellsToVisit.isEmpty()) {
            var curr = cellsToVisit.poll();
            var currId = cell2Id.get(curr);
            var aj = cell2Var.get(curr);
            obj.addTerm(1, aj);
            Utils.ilpTimes[1] += model.instantiationTime.getOrDefault(curr, 0L);

            var edges = model.cell2Edge.get(curr);
            if (edges != null) {
                for (var edge : edges) {
                    var bi = grbModel.addVar(0, 1, 0, GRB.BINARY, "b" + ++edgeCounter);
                    var hij = grbModel.addVar(0, 1, 0, GRB.BINARY, "h" + edgeCounter + currId);
                    grbModel.addConstr(aj, GRB.EQUAL, hij, "");
                    grbModel.addConstr(bi, GRB.EQUAL, hij, "");
                    var tjiVars = new ArrayList<GRBVar>(edge.size());
                    for (var cell : edge) {
                        int tId;
                        GRBVar aCell;
                        if (cell2Id.get(cell) == null) {
                            tId = maxId;
                            cell2Id.put(cell, maxId++);
                            aCell = grbModel.addVar(0, 1, 0, GRB.BINARY, "a" + tId);
                            cell2Var.put(cell, aCell);
                        } else {
                            tId = cell2Id.get(cell);
                            aCell = cell2Var.get(cell); // grbModel.getVarByName("a" + tId);
                        }

                        var tji = grbModel.addVar(0, 1, 0, GRB.BINARY, "t" + edgeCounter + tId);
                        tjiVars.add(tji);
                        grbModel.addConstr(tji, GRB.EQUAL, aCell, "");
                        if (instantiatedCells.add(cell)) {
                            cellsToVisit.add(cell);
                        }
                    }
                    GRBLinExpr tjis = new GRBLinExpr();
                    for (var tji : tjiVars) {
                        tjis.addTerm(1, tji);
                    }
                    grbModel.addConstr(tjis, GRB.GREATER_EQUAL, bi, "");
                }
            }
        }

        var stop = System.nanoTime();
        Utils.ilpTimes[2] += stop - start;

        grbModel.setObjective(obj, GRB.MINIMIZE);
        grbModel.optimize();

        if (grbModel.get(GRB.IntAttr.Status) == 3) {
            throw new GRBException("Infeasible grbModel");
        }

        for (var cellEntry : cell2Id.entrySet()) {
            if (grbModel.getVarByName("a" + cellEntry.getValue()).get(GRB.DoubleAttr.X) == 1d) {
                toDelete.add(cellEntry.getKey());
            }
        }
        grbModel.dispose();
        Utils.ilpTimes[3] += System.nanoTime() - stop;
        Utils.ilpCounts[1] += cell2Id.size() - 1;

        if (ConfigParameter.measureMemory) {
            Utils.ilpCounts[3] += measureILPMemory(model, deleted);
        }
        return toDelete;
    }

    private static long measureILPMemory(InstantiatedModel model, Cell deleted) {
        long size = 0;
        LinkedList<Cell> cellsToVisit = new LinkedList<>();
        HashSet<Cell> instantiatedCells = new HashSet<>();
        cellsToVisit.add(deleted);
        while (!cellsToVisit.isEmpty()) {
            var curr = cellsToVisit.poll();
            // per cell: 4 bytes for the table index, 4 bytes for the row index, 4 bytes insertionTime, 1 byte decision variable aj, pointer for objective
            size += 4 + 4 + 4 + 1 + 8;
            var edges = model.cell2Edge.get(curr);
            if (edges != null) {
                for (var edge : edges) {
                    // 1 byte decision variable bi, 1 byte decision variable hij, constr aj = hij, constr bi = hij, 1byte decision variable + constr tji = aj + constr SUM(tji) >= bi per element in hyperedge
                    size += 1L + 1L + 16L + 16L + edge.size() * (1L + 16L + 8L) + 8L;
                    for (var cell : edge) {
                        if (instantiatedCells.add(cell)) {
                            cellsToVisit.add(cell);
                        }
                    }
                }
            }
        }
        return size;
    }

    static HashSet<Cell> batchedIlpApproach(InstantiatedModel model, ArrayList<Cell> deletedCells) throws GRBException {
        Utils.ilpTimes[2] += model.modelConstructionTime;
        var start = System.nanoTime();

        var toDelete = new HashSet<Cell>();
        GRBModel grbModel = new GRBModel(env);
        GRBLinExpr obj = new GRBLinExpr();
        HashMap<Cell, Integer> cell2Id = new HashMap<>();
        HashMap<Cell, GRBVar> cell2Var = new HashMap<>();
        int maxId = 0;
        int edgeCounter = -1;

        HashSet<Cell> instantiatedCells = new HashSet<>();
        Queue<Cell> cellsToVisit = new LinkedList<>();

        for (var deleted : deletedCells) {
            if (instantiatedCells.add(deleted)) {
                int initialTId = maxId;
                cell2Id.put(deleted, maxId++);
                cell2Var.put(deleted, grbModel.addVar(1, 1, 0, GRB.BINARY, "a" + initialTId));

                cellsToVisit.add(deleted);
                while (!cellsToVisit.isEmpty()) {
                    var curr = cellsToVisit.poll();

                    int currId = cell2Id.get(curr);
                    GRBVar aj = cell2Var.get(curr);
                    obj.addTerm(1, aj);

                    Utils.ilpTimes[1] += model.instantiationTime.getOrDefault(curr, 0L);

                    var edges = model.cell2Edge.get(curr);
                    if (edges != null) {
                        for (var edge : edges) {
                            var bi = grbModel.addVar(0, 1, 0, GRB.BINARY, "b" + ++edgeCounter);
                            var hij = grbModel.addVar(0, 1, 0, GRB.BINARY, "h" + edgeCounter + currId);
                            grbModel.addConstr(aj, GRB.EQUAL, hij, "");
                            grbModel.addConstr(bi, GRB.EQUAL, hij, "");
                            var tjiVars = new ArrayList<GRBVar>(edge.size());
                            for (var cell : edge) {
                                int tId;
                                GRBVar aCell;
                                if (cell2Id.get(cell) == null) {
                                    tId = maxId;
                                    cell2Id.put(cell, maxId++);
                                    aCell = grbModel.addVar(0, 1, 0, GRB.BINARY, "a" + tId);
                                    cell2Var.put(cell, aCell);
                                } else {
                                    tId = cell2Id.get(cell);
                                    aCell = cell2Var.get(cell);
                                }

                                var tji = grbModel.addVar(0, 1, 0, GRB.BINARY, "t" + edgeCounter + tId);
                                tjiVars.add(tji);
                                grbModel.addConstr(tji, GRB.EQUAL, aCell, "");
                                if (instantiatedCells.add(cell)) {
                                    cellsToVisit.add(cell);
                                }
                            }
                            GRBLinExpr tjis = new GRBLinExpr();
                            for (var tji : tjiVars) {
                                tjis.addTerm(1, tji);
                            }
                            grbModel.addConstr(tjis, GRB.GREATER_EQUAL, bi, "");
                        }
                    }
                }
            } else {
                int tId = cell2Id.get(deleted);
                grbModel.update();
                var aCell = grbModel.getVarByName("a" + tId);
                aCell.set(GRB.DoubleAttr.LB, 1);
            }
        }

        var stop = System.nanoTime();
        Utils.ilpTimes[2] += stop - start;

        grbModel.setObjective(obj, GRB.MINIMIZE);
        grbModel.optimize();

        if (grbModel.get(GRB.IntAttr.Status) == 3) {
            throw new GRBException("Infeasible model");
        }

        for (var cellEntry : cell2Id.entrySet()) {
            if (grbModel.getVarByName("a" + cellEntry.getValue()).get(GRB.DoubleAttr.X) == 1d) {
                toDelete.add(cellEntry.getKey());
            }
        }

        grbModel.dispose();
        Utils.ilpTimes[3] += System.nanoTime() - stop;
        Utils.ilpCounts[1] += cell2Id.size() - deletedCells.size();

        return toDelete;
    }

    private static HashSet<Cell> approximateDelete(InstantiatedModel model, Cell deleted) {
        Cell lastCell = null;
        var start = System.nanoTime();
        HashSet<Cell> edgesInstantiated = new HashSet<>();
        var toDelete = new HashSet<Cell>();
        toDelete.add(deleted);
        HashSet<Cell> nodesInstantiated = new HashSet<>();

        Queue<Cell> cellsToVisit = new LinkedList<>();
        cellsToVisit.add(deleted);
        edgesInstantiated.add(deleted);
        nodesInstantiated.add(deleted);

        while (!cellsToVisit.isEmpty()) {
            var curr = cellsToVisit.poll();

            var edges = model.cell2Edge.get(curr);
            if (edges != null) {
                for (var edge : edges) {
                    Cell minCell = null;
                    nodesInstantiated.addAll(edge);
                    for (var cell : edge) {
                        edgesInstantiated.add(cell);
                        for (var grandChildren : model.cell2Edge.getOrDefault(cell, EMPTY_LIST)) {
                            nodesInstantiated.addAll(grandChildren);
                        }
                        if (minCell == null || model.cell2Edge.getOrDefault(cell, EMPTY_LIST).size() < model.cell2Edge.getOrDefault(minCell, EMPTY_LIST).size()) {
                            minCell = cell;
                            edge.minCell = cell;
                        }
                    }
                    if (toDelete.add(minCell)) {
                        lastCell = minCell;
                        cellsToVisit.add(minCell);
                    }
                }
            }
        }

        Utils.approximateTimes[2] += System.nanoTime() - start;
        Utils.approximateCounts[1] += nodesInstantiated.size() - 1;
        int count = 0;
        for (var level : model.treeLevels) {
            if (level.contains(lastCell)) break;
            count++;
        }
        Utils.approximateCounts[2] += model.treeLevels.size() - count;
        if (ConfigParameter.measureMemory) {
            Utils.approximateCounts[3] += measureApproximateMemory(model, nodesInstantiated, edgesInstantiated);
        }
        for (var cell : edgesInstantiated) {
            Utils.approximateTimes[1] += model.instantiationTime.getOrDefault(cell, 0L);
        }

        return toDelete;
    }

    private static long measureApproximateMemory(InstantiatedModel model, HashSet<Cell> nodesInstantiated, HashSet<Cell> edgesInstantiated) {
        long size = 0;

        // per cell: 4 bytes for the table index, 4 bytes for the row index, 4 bytes insertionTime, 1 byte state (deleted) and 4 bytes cost
        size += nodesInstantiated.size() * (4 + 4 + 4 + 1L);

        for (var cell : edgesInstantiated) {
            var edges = model.cell2Edge.getOrDefault(cell, EMPTY_LIST);
            for (var edge : edges) {
                size += edge.size() * 8L + 8L + 4L;
            }
        }
        return size;
    }

    static HashSet<Cell> batchedApproximateDelete(InstantiatedModel model, ArrayList<Cell> deletedCells) throws Exception {
        var start = System.nanoTime();
        HashSet<Cell> instantiatedCells = new HashSet<>();

        for (var deleted : deletedCells) {
            if (instantiatedCells.add(deleted)) {
                Queue<Cell> cellsToVisit = new LinkedList<>();
                cellsToVisit.add(deleted);

                while (!cellsToVisit.isEmpty()) {
                    var curr = cellsToVisit.poll();

                    var edges = model.cell2Edge.get(curr);
                    if (edges != null) {
                        for (var edge : edges) {
                            Cell minCell = null;
                            for (var cell : edge) {
                                instantiatedCells.add(cell);
                                if (minCell == null || model.cell2Edge.getOrDefault(cell, EMPTY_LIST).size() < model.cell2Edge.getOrDefault(minCell, EMPTY_LIST).size()) {
                                    minCell = cell;
                                }
                            }
                            edge.minCell = minCell;
                            cellsToVisit.add(minCell);
                        }
                    }
                }
            }
        }

        for (var deleted : deletedCells) {
            var parents = model.cell2Parents.get(deleted);
            for (var parent : parents) {
                for (var edge : model.cell2Edge.get(parent)) {
                    if (edge.contains(deleted)) {
                        edge.minCell = deleted;
                    }
                }
            }
        }

        HashSet<Cell> toDelete = new HashSet<>();
        for (var deleted : deletedCells) {
            Queue<Cell> cellsToVisit = new LinkedList<>();
            cellsToVisit.add(deleted);

            while (!cellsToVisit.isEmpty()) {
                var currCell = cellsToVisit.poll();

                if (toDelete.add(currCell)) {
                    var edges = model.cell2Edge.get(currCell);
                    if (edges != null) {
                        for (var edge : edges) {
                            cellsToVisit.add(edge.minCell);
                        }
                    }
                }
            }
        }
        Utils.approximateTimes[2] += System.nanoTime() - start;
        Utils.approximateCounts[1] += instantiatedCells.size() - deletedCells.size();

        for (var cell : instantiatedCells) {
            Utils.approximateTimes[1] += model.instantiationTime.getOrDefault(cell, 0L);
        }

        return toDelete;
    }

    private static void parseSchema() throws IOException {
        var parser = CSVFormat.DEFAULT.parse(Files.newBufferedReader(Paths.get(ConfigParameter.configPath, ConfigParameter.schemaFile)));
        for (var record : parser) {
            tableName2keyCol.put(record.get(0), record.get(1));
        }
    }

    private static void parseRules() throws Exception {
        var parser = CSVFormat.DEFAULT.parse(Files.newBufferedReader(Paths.get(ConfigParameter.configPath, ConfigParameter.ruleFile)));
        for (var record : parser) {
            var rule = parseRule(record);
            rules.add(rule);
            attributeInHead.computeIfAbsent(rule.head, a -> new ArrayList<>()).add(rule);
            for (var tail : rule.tail) {
                attributeInTail.computeIfAbsent(tail, attribute -> new ArrayList<>()).add(rule);
            }
        }
    }

    private static void parseDerivedData() throws Exception {
        var parser = CSVFormat.DEFAULT.parse(Files.newBufferedReader(Paths.get(ConfigParameter.configPath, ConfigParameter.derivedFile)));
        for (var record : parser) {
            var rule = parseRule(record);
            derivedData.add(rule);
            derivedAttributes.add(rule.head);
        }
    }

    public static Rule parseRule(CSVRecord record) throws Exception {
        int keywordsFound = 0;
        Rule rule = new Rule();
        String headString = "";
        ArrayList<String> tailStrings = new ArrayList<>();
        for (String field : record) {
            switch (keywordsFound) {
                case 0: {
                    headString = field;
                    keywordsFound++;
                    break;
                }
                case 1: {
                    if (field.equalsIgnoreCase("from")) {
                        keywordsFound++;
                    } else {
                        tailStrings.add(field);
                    }
                    break;
                }
                case 2: {
                    if (field.equalsIgnoreCase("where")) {
                        keywordsFound++;
                    } else {
                        var tab2Alias = field.split(" ");
                        if (tab2Alias.length != 2) {
                            throw new Exception("Wrong table definition");
                        }
                        rule.table2Alias.put(tab2Alias[0], tab2Alias[1]);
                        rule.table2Alias.put(tab2Alias[1], tab2Alias[0]);
                        rule.tables.add(tab2Alias[0]);
                    }
                    break;
                }
                case 3: {
                    rule.condition = field;
                }
            }
        }
        rule.head = parseAttribute(rule.table2Alias, headString);
        for (var tails : tailStrings) {
            rule.tail.add(parseAttribute(rule.table2Alias, tails));
        }
        return rule;
    }

    public static Attribute parseAttribute(HashMap<String, String> table2Alias, String attrString) throws Exception {
        var splitted = attrString.split("\\.");
        if (splitted.length != 2) {
            throw new Exception("Wrong table definition");
        }
        return new Attribute(table2Alias.get(splitted[0]), splitted[1]);
    }
}
