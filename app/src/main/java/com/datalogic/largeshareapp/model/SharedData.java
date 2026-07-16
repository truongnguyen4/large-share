package com.datalogic.largeshareapp.model;

public class SharedData {
    private static SharedData mInstance;
    public BitSetMetadata instanceBitSetMetadata;
    public InformationMetadata instanceInformationMetadata;

    private SharedData() {}

    public static SharedData getInstance() {
        if (mInstance == null) {
            mInstance = new SharedData();
        }
        return mInstance;
    }
}
