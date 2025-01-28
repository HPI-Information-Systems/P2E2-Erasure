package org.example;

public class ConfigParameter {
    static String dataset = "twitter";
    static String configPath = "";
    static String ruleFile = "rules_" + dataset + ".csv";
    static String schemaFile = "schema_" + dataset + ".csv";
    static String derivedFile = "derived_" + dataset + ".csv";
    static String resultFile = "result_" + dataset + ".db";

    public static String connectionUrl = "jdbc:postgresql://localhost:5432/"; // Note final slash
    public static String database = "database";
    public static String username = "postgres";
    public static String password = "postgres";
    static int numKeys = 10;
    static boolean batching = false;
    static boolean scheduling = false;
    static boolean averageDependence = false;
    static int[] batchSizes = new int[]{numKeys};
    static boolean isBatchSizeTime = false;
    static boolean measureMemory = true;
    static long startSchedule = 1;
    static long endSchedule = 2;
    static long baseFrequency = 1000;


    public static void setDataset(String dataset) {
        ConfigParameter.dataset = dataset;
        ConfigParameter.ruleFile = "rules_" + dataset + ".csv";
        ConfigParameter.schemaFile = "schema_" + dataset + ".csv";
        ConfigParameter.derivedFile = "derived_" + dataset + ".csv";
        ConfigParameter.resultFile = "result_" + dataset + ".db";
    }
}
