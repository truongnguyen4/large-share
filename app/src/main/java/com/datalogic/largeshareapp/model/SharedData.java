package com.datalogic.largeshareapp.model;

import java.util.BitSet;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

public class SharedData {
    private static SharedData mInstance;
    public BitSetMetadata instanceBitSetMetadata;
    public InformationMetadata instanceInformationMetadata;
    private final Random random = new Random();
    private final Object mLockBitSetMetadata = new Object();

    private SharedData() {}

    public static SharedData getInstance() {
        if (mInstance == null) {
            mInstance = new SharedData();
        }
        return mInstance;
    }
}
