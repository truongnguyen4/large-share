package com.datalogic.largeshareapp.manager;

import android.util.Log;
import android.util.Pair;

import java.util.concurrent.ConcurrentHashMap;

public class PerformanceTracker {
    public static ConcurrentHashMap<String, Pair<Long, Long>> performanceMetrics = new ConcurrentHashMap<>();
    public static void startRecord(String name, long startTime) {
        performanceMetrics.put(name, new Pair<>(startTime, null));
    }

    public static void endRecord(String name, long endTime) {
        Pair<Long, Long> startEndPair = performanceMetrics.get(name);
        if (startEndPair != null) {
            performanceMetrics.put(name, new Pair<>(startEndPair.first, endTime));
            // Log.d("PerformanceTracker", "Recorded duration for " + name + ": " + (endTime - startEndPair.first) + " ms");
        }
    }

    public static long getDuration(String name) {
        Pair<Long, Long> startEndPair = performanceMetrics.get(name);
        if (startEndPair != null && startEndPair.first != null && startEndPair.second != null) {
            return startEndPair.second - startEndPair.first;
        }
        return -1;
    }
}
