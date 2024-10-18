package org.example;

public class Utils {
    // unused, instantiation time, model construction, optimization time, set to null
    final static long[] optimalTimes = new long[5];
    final static long[] approximateTimes = new long[5];
    final static long[] ilpTimes = new long[5];

    // deletions, instantiations, tree height
    final static long[] approximateCounts = new long[4];
    final static long[] optimalCounts = new long[4];
    final static long[] ilpCounts = new long[4];
}
