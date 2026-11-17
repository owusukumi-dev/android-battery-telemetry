package com.syntax.batteryproject;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import java.util.List;

@Dao
public interface BatteryDao {

    class AppDrainTuple {
        public String packageName;
        public long totalDrain;
    }

    @Insert
    void insertSample(BatterySample sample);

    @Query("SELECT * FROM battery_logs ORDER BY timestampMs DESC LIMIT 50")
    List<BatterySample> getRecentSamples();

    @Query("SELECT * FROM battery_logs WHERE timestampMs >= :sinceMs ORDER BY timestampMs ASC")
    List<BatterySample> getSamplesSince(long sinceMs);

    @Query("SELECT AVG(currentMa) FROM battery_logs")
    double getAverageCurrent();

    @Query("SELECT packageName, SUM(ABS(currentMa)) as totalDrain FROM battery_logs WHERE packageName IS NOT NULL AND currentMa < 0 GROUP BY packageName ORDER BY SUM(ABS(currentMa)) DESC LIMIT 3")
    List<AppDrainTuple> getDischargingAppsByDrain();

    @Query("SELECT SUM(ABS(currentMa)) FROM battery_logs WHERE currentMa < 0")
    long getTotalRecordedDrain();

    @Query("DELETE FROM battery_logs")
    void clearLogs();

    // Fast indexed deletion by timestamp instead of subquery scan
    @Query("DELETE FROM battery_logs WHERE timestampMs < :cutoffMs")
    void deleteOlderThan(long cutoffMs);

    @Query("SELECT AVG(ABS(currentMa)) FROM battery_logs WHERE timestampMs >= :since AND currentMa < -30")
    double getAvgDischargeMaSince(long since);

    @Query("SELECT AVG(currentMa) FROM battery_logs WHERE timestampMs >= :since AND currentMa > 30")
    double getAvgChargeMaSince(long since);

    @Query("SELECT COUNT(*) FROM battery_logs WHERE timestampMs >= :since")
    int getSampleCountSince(long since);
}