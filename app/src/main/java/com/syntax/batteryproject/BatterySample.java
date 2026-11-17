package com.syntax.batteryproject;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "battery_logs")
public class BatterySample {

    @PrimaryKey(autoGenerate = true)
    public int id;

    public long timestampMs;
    public int currentMa;
    public double voltageV;
    public double powerW;
    public int capacityPct;
    public double tempC;
    public String packageName;

    public BatterySample(long timestampMs, int currentMa, double voltageV, double powerW, int capacityPct, double tempC, String packageName) {
        this.timestampMs = timestampMs;
        this.currentMa = currentMa;
        this.voltageV = voltageV;
        this.powerW = powerW;
        this.capacityPct = capacityPct;
        this.tempC = tempC;
        this.packageName = packageName;
    }
}