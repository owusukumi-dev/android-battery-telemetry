package com.syntax.batteryproject;

import android.annotation.SuppressLint;
import android.app.AppOpsManager;
import android.app.usage.NetworkStats;
import android.app.usage.NetworkStatsManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.net.ConnectivityManager;
import android.net.TrafficStats;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RawBatteryExplorer {

    public enum ChargeState { CHARGING, DISCHARGING, REVERSE_CHARGING, IDLE }

    public static class AppDrainItem {
        public final String packageName;
        public final String appName;
        public final int percentage;
        public final Drawable icon;
        public final String reason;

        public AppDrainItem(String pkg, String appName, int pct, Drawable icon, String reason) {
            this.packageName = pkg;
            this.appName = appName;
            this.percentage = pct;
            this.icon = icon;
            this.reason = reason;
        }
    }

    public static class Snapshot {
        public int currentMaSigned;
        public ChargeState state;
        public String signedCurrentText;
        public double powerW;
        public int level;
        public double voltageV;
        public double tempC;
        public String chargingTech;
        public String health;
        public String cycleCount;
        public String capacityMah;
        public long estimatedMinutes;
        public String trendSummary;
        public List<AppDrainItem> topApps = new ArrayList<>();
        public List<BatterySample> chartHistory = new ArrayList<>();
    }

    public interface Callback { void onTelemetryReady(Snapshot snapshot); }

    private static final ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    private static final long ESTIMATE_WINDOW_MS = 15 * 60 * 1000L;
    private static final long CHART_WINDOW_MS = 30 * 60 * 1000L;
    private static final long APP_SCORE_INTERVAL_MS = 30 * 1000L;
    private static final long DB_TRIM_INTERVAL_MS = 10 * 60 * 1000L;

    private static double emaSignedMa = Double.NaN;
    private static ChargeState lastChargeState = null;

    private static long lastAppScoreTime = 0;
    private static long lastDbTrimTime = 0;
    private static List<AppDrainItem> cachedTopApps = new ArrayList<>();

    private static final String[] CYCLE_COUNT_PATHS = {
            "/sys/class/power_supply/battery/cycle_count",
            "/sys/class/power_supply/bms/cycle_count",
            "/sys/class/power_supply/battery/battery_cycle_count",
            "/sys/class/power_supply/maxfg/cycle_count",
            "/sys/class/power_supply/max170xx_battery/cycle_count",
            "/sys/devices/system/battery/cycle_count"
    };

    public static void queryHardwareSensor(Context context, Callback callback) {
        final Context appCtx = context.getApplicationContext();
        backgroundExecutor.execute(() -> {
            Snapshot snap = new Snapshot();
            BatteryManager bm = (BatteryManager) appCtx.getSystemService(Context.BATTERY_SERVICE);

            int currentRaw = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
            int rawMa;
            if (currentRaw == Integer.MIN_VALUE) rawMa = 0;
            else if (Math.abs(currentRaw) > 10_000) rawMa = currentRaw / 1000;
            else rawMa = currentRaw;

            IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            Intent bStatus = appCtx.registerReceiver(null, filter);

            int rawVoltage = bStatus != null ? bStatus.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 3850) : 3850;
            int rawTemp = bStatus != null ? bStatus.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 350) : 350;
            int level = bStatus != null ? bStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, 50) : 50;
            int statusInt = bStatus != null ? bStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1) : -1;
            int healthInt = bStatus != null ? bStatus.getIntExtra(BatteryManager.EXTRA_HEALTH, -1) : -1;
            int plugged = bStatus != null ? bStatus.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) : 0;
            boolean isPlugged = plugged != 0;

            if (Math.abs(rawMa) < 15 && statusInt == BatteryManager.BATTERY_STATUS_FULL) {
                snap.state = ChargeState.IDLE;
            } else if (isPlugged) {
                if (statusInt == BatteryManager.BATTERY_STATUS_DISCHARGING && Math.abs(rawMa) > 40) {
                    snap.state = ChargeState.REVERSE_CHARGING;
                } else {
                    snap.state = ChargeState.CHARGING;
                }
            } else {
                snap.state = ChargeState.DISCHARGING;
            }

            int rawSignedMa;
            switch (snap.state) {
                case CHARGING:
                    rawSignedMa = Math.abs(rawMa);
                    break;
                case DISCHARGING:
                case REVERSE_CHARGING:
                    rawSignedMa = -Math.abs(rawMa);
                    break;
                default:
                    rawSignedMa = 0;
                    break;
            }

            if (Double.isNaN(emaSignedMa) || lastChargeState != snap.state) {
                emaSignedMa = rawSignedMa;
                lastChargeState = snap.state;
            } else {
                emaSignedMa = (0.25 * rawSignedMa) + (0.75 * emaSignedMa);
            }
            int smoothedSignedMa = (int) Math.round(emaSignedMa);

            snap.currentMaSigned = smoothedSignedMa;
            snap.voltageV = rawVoltage / 1000.0;
            snap.tempC = rawTemp / 10.0;
            snap.level = level;
            snap.powerW = Math.abs((smoothedSignedMa / 1000.0) * snap.voltageV);

            switch (snap.state) {
                case CHARGING:
                    snap.signedCurrentText = String.format(Locale.US, "+%d mA", Math.abs(smoothedSignedMa));
                    break;
                case REVERSE_CHARGING:
                    snap.signedCurrentText = String.format(Locale.US, "-%d mA · reverse", Math.abs(smoothedSignedMa));
                    break;
                case DISCHARGING:
                    snap.signedCurrentText = String.format(Locale.US, "-%d mA", Math.abs(smoothedSignedMa));
                    break;
                default:
                    snap.signedCurrentText = "0 mA";
                    break;
            }

            if (!isPlugged) snap.chargingTech = "Discharging";
            else if (snap.powerW > 15.0 && snap.state == ChargeState.CHARGING) snap.chargingTech = "Fast / QC";
            else if (plugged == BatteryManager.BATTERY_PLUGGED_USB) snap.chargingTech = "USB Port";
            else if (plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS) snap.chargingTech = "Wireless";
            else if (snap.state == ChargeState.REVERSE_CHARGING) snap.chargingTech = "Reverse (power share)";
            else snap.chargingTech = "Standard AC";

            snap.health = parseHealth(healthInt);
            snap.cycleCount = readCycleCount(bStatus);
            snap.capacityMah = readCapacityMah(appCtx, bm, level);

            long now = System.currentTimeMillis();
            BatteryDao dao = AppDatabase.getDatabase(appCtx).batteryDao();

            dao.insertSample(new BatterySample(
                    now, smoothedSignedMa, snap.voltageV,
                    snap.powerW, snap.level, snap.tempC, appCtx.getPackageName()));

            if (now - lastDbTrimTime > DB_TRIM_INTERVAL_MS) {
                dao.deleteOlderThan(now - (2L * 60 * 60 * 1000));
                lastDbTrimTime = now;
            }

            applyHistoricalEstimate(appCtx, dao, bm, snap, level, smoothedSignedMa, now);
            snap.chartHistory = dao.getSamplesSince(now - CHART_WINDOW_MS);

            if (now - lastAppScoreTime > APP_SCORE_INTERVAL_MS || cachedTopApps.isEmpty()) {
                List<UsageStats> usageList = getRecentUsageStats(appCtx, now);
                cachedTopApps = scoreTop3Apps(appCtx, usageList, dao, now);
                lastAppScoreTime = now;
            }
            snap.topApps = cachedTopApps;

            mainHandler.post(() -> callback.onTelemetryReady(snap));
        });
    }

    private static void applyHistoricalEstimate(Context ctx, BatteryDao dao, BatteryManager bm, Snapshot snap, int level, int smoothedSignedMa, long now) {
        long since = now - ESTIMATE_WINDOW_MS;
        int sampleCount = dao.getSampleCountSince(since);
        double capacityMah = extractCapacity(ctx, bm, level);
        if (capacityMah <= 0) {
            snap.estimatedMinutes = -1;
            snap.trendSummary = "Capacity unknown";
            return;
        }

        double effectiveMa;
        String source;
        if (snap.state == ChargeState.CHARGING) {
            double dbAvg = dao.getAvgChargeMaSince(since);
            double liveMa = Math.abs(smoothedSignedMa);
            if (sampleCount >= 6 && dbAvg > 50) {
                effectiveMa = (0.80 * dbAvg) + (0.20 * liveMa);
                source = String.format(Locale.US, "15m avg charge: %.0f mA", effectiveMa);
            } else {
                effectiveMa = Math.max(liveMa, 250.0);
                source = String.format(Locale.US, "Charge rate: %.0f mA", effectiveMa);
            }
            double mahToFill = capacityMah * (100 - level) / 100.0;
            snap.estimatedMinutes = Math.max(1, Math.round((mahToFill / effectiveMa) * 60.0 * 1.12));
            snap.trendSummary = source + " · ~" + formatMinutes(snap.estimatedMinutes) + " to full";
        } else {
            double dbAvg = dao.getAvgDischargeMaSince(since);
            double liveMa = Math.abs(smoothedSignedMa);
            if (sampleCount >= 6 && dbAvg > 40) {
                effectiveMa = (0.80 * dbAvg) + (0.20 * liveMa);
                source = String.format(Locale.US, "15m avg draw: %.0f mA", effectiveMa);
            } else {
                effectiveMa = (0.50 * Math.max(liveMa, 120.0)) + (0.50 * 350.0);
                source = String.format(Locale.US, "Calibrating draw (~%.0f mA)", effectiveMa);
            }
            effectiveMa = Math.max(80.0, Math.min(effectiveMa, 3500.0));
            double mahLeft = capacityMah * level / 100.0;
            snap.estimatedMinutes = Math.max(1, Math.round((mahLeft / effectiveMa) * 60.0));
            snap.trendSummary = source + " · ~" + formatMinutes(snap.estimatedMinutes) + " to empty";
        }
    }

    private static String formatMinutes(long minutes) {
        if (minutes < 60) return minutes + " m";
        return (minutes / 60) + " h " + (minutes % 60) + " m";
    }

    private static String parseHealth(int health) {
        switch (health) {
            case BatteryManager.BATTERY_HEALTH_OVERHEAT: return "Overheat";
            case BatteryManager.BATTERY_HEALTH_DEAD: return "Dead";
            case BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE: return "Over Volt";
            case BatteryManager.BATTERY_HEALTH_COLD: return "Cold";
            case BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE: return "Failing";
            default: return "Good";
        }
    }

    private static String readCycleCount(Intent bStatus) {
        if (Build.VERSION.SDK_INT >= 34 && bStatus != null) {
            int c = bStatus.getIntExtra("android.os.extra.CYCLE_COUNT", -1);
            if (c > 0) return String.valueOf(c);
        }
        for (String path : CYCLE_COUNT_PATHS) {
            try (BufferedReader br = new BufferedReader(new FileReader(path))) {
                String line = br.readLine();
                if (line != null && !line.trim().isEmpty()) {
                    int v = Integer.parseInt(line.trim());
                    if (v > 0 && v < 100_000) return String.valueOf(v);
                }
            } catch (Exception ignored) {}
        }
        long full = readLongPath("/sys/class/power_supply/battery/charge_full");
        long design = readLongPath("/sys/class/power_supply/battery/charge_full_design");
        if (full > 0 && design > 0 && full < design) {
            double health = (double) full / design;
            int est = (int) Math.round((1.0 - health) * 5000);
            if (est > 0 && est < 5000) return "~" + est + " (est.)";
        }
        return "N/A";
    }

    private static long readLongPath(String path) {
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line = br.readLine();
            return line != null ? Long.parseLong(line.trim()) : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    private static String readCapacityMah(Context ctx, BatteryManager bm, int level) {
        double cap = extractCapacity(ctx, bm, level);
        return cap > 0 ? Math.round(cap) + " mAh" : "5000 mAh";
    }

    @SuppressLint("PrivateApi")
    private static double extractCapacity(Context ctx, BatteryManager bm, int level) {
        try {
            Class<?> powerProfile = Class.forName("com.android.internal.os.PowerProfile");
            Object profileInstance = powerProfile.getConstructor(Context.class).newInstance(ctx);
            Object result = powerProfile.getMethod("getBatteryCapacity").invoke(profileInstance);
            if (result instanceof Double) {
                double cap = (Double) result;
                if (cap > 1000) return cap;
            }
        } catch (Exception ignored) {}
        int chargeCounterUah = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);
        if (chargeCounterUah > 0 && level > 15) {
            double est = (chargeCounterUah / 1000.0) / (level / 100.0);
            if (est >= 2000 && est <= 10000) return est;
        }
        return 5000.0;
    }

    private static List<UsageStats> getRecentUsageStats(Context ctx, long now) {
        AppOpsManager appOps = (AppOpsManager) ctx.getSystemService(Context.APP_OPS_SERVICE);
        if (appOps == null) return null;
        int mode;
        if (Build.VERSION.SDK_INT >= 29) {
            mode = appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.getPackageName());
        } else {
            mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.getPackageName());
        }
        if (mode != AppOpsManager.MODE_ALLOWED) return null;

        UsageStatsManager usm = (UsageStatsManager) ctx.getSystemService(Context.USAGE_STATS_SERVICE);
        if (usm == null) return null;
        return usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - (24L * 60 * 60 * 1000), now);
    }

    private static List<AppDrainItem> scoreTop3Apps(Context ctx, List<UsageStats> stats, BatteryDao dao, long now) {
        List<AppDrainItem> result = new ArrayList<>();
        PackageManager pm = ctx.getPackageManager();
        Set<String> ignoredPackages = getIgnoredPackages(ctx, pm);

        if (stats != null && !stats.isEmpty()) {
            Map<String, Long> fgMap = new HashMap<>();
            long totalFg = 0;

            for (UsageStats s : stats) {
                String pkg = s.getPackageName();
                if (pkg == null || ignoredPackages.contains(pkg)) continue;
                long fg = s.getTotalTimeInForeground();
                if (fg < 15_000) continue;

                Long prevFg = fgMap.get(pkg);
                long updatedFg = (prevFg != null ? prevFg : 0L) + fg;
                fgMap.put(pkg, updatedFg);
                totalFg += fg;
            }

            if (totalFg > 0 && !fgMap.isEmpty()) {
                List<Map.Entry<String, Long>> candidates = new ArrayList<>(fgMap.entrySet());
                candidates.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
                int checkLimit = Math.min(6, candidates.size());

                long since24h = now - (24L * 60 * 60 * 1000);

                class Scored {
                    String pkg;
                    double score;
                    String reason;
                }
                List<Scored> scored = new ArrayList<>();

                for (int i = 0; i < checkLimit; i++) {
                    String pkg = candidates.get(i).getKey();
                    long fg = candidates.get(i).getValue();

                    int uid = -1;
                    int cat = ApplicationInfo.CATEGORY_UNDEFINED;
                    try {
                        ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                        uid = ai.uid;
                        cat = ai.category;
                    } catch (Exception ignored) {}

                    long bytes = (uid > 0) ? queryUidBytes(ctx, uid, since24h, now) : 0;
                    double score = (double) fg / totalFg;

                    if (cat == ApplicationInfo.CATEGORY_GAME || cat == ApplicationInfo.CATEGORY_VIDEO) {
                        score *= 1.45;
                    } else if (cat == ApplicationInfo.CATEGORY_SOCIAL || cat == ApplicationInfo.CATEGORY_AUDIO) {
                        score *= 1.20;
                    }

                    String reason = formatMinutes(Math.max(1, fg / 60_000L));
                    if (bytes > 200 * 1024) reason += " · " + humanBytes(bytes);

                    Scored sc = new Scored();
                    sc.pkg = pkg;
                    sc.score = score;
                    sc.reason = reason;
                    scored.add(sc);
                }

                scored.sort((a, b) -> Double.compare(b.score, a.score));
                double sum = 0;
                for (Scored s : scored) sum += s.score;

                if (sum > 0) {
                    for (int i = 0; i < Math.min(3, scored.size()); i++) {
                        Scored s = scored.get(i);
                        int pct = Math.max(1, Math.min(99, (int) Math.round((s.score * 100.0) / sum)));
                        result.add(new AppDrainItem(
                                s.pkg,
                                resolveAppLabel(pm, s.pkg),
                                pct,
                                loadAppIcon(pm, s.pkg),
                                s.reason
                        ));
                    }
                    return result;
                }
            }
        }

        List<BatteryDao.AppDrainTuple> dbTop = dao.getDischargingAppsByDrain();
        long totalDb = dao.getTotalRecordedDrain();
        if (totalDb > 0) {
            for (int i = 0; i < Math.min(3, dbTop.size()); i++) {
                BatteryDao.AppDrainTuple t = dbTop.get(i);
                int pct = Math.max(1, (int) Math.round((t.totalDrain * 100.0) / totalDb));
                result.add(new AppDrainItem(
                        t.packageName,
                        resolveAppLabel(pm, t.packageName),
                        pct,
                        loadAppIcon(pm, t.packageName),
                        "Tap to enable Usage Access"
                ));
            }
        }
        return result;
    }

    private static Set<String> getIgnoredPackages(Context ctx, PackageManager pm) {
        Set<String> ignored = new HashSet<>();
        ignored.add(ctx.getPackageName());
        ignored.add("com.android.systemui");
        ignored.add("android");
        try {
            Intent homeIntent = new Intent(Intent.ACTION_MAIN);
            homeIntent.addCategory(Intent.CATEGORY_HOME);
            ResolveInfo ri = pm.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY);
            if (ri != null && ri.activityInfo != null) ignored.add(ri.activityInfo.packageName);
        } catch (Exception ignoredEx) {}
        return ignored;
    }

    private static String resolveAppLabel(PackageManager pm, String pkg) {
        if (pkg == null) return "—";
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            CharSequence label = pm.getApplicationLabel(ai);
            if (label.length() > 0) return label.toString();
        } catch (Exception ignored) {}
        int dot = pkg.lastIndexOf('.');
        String s = (dot >= 0 && dot < pkg.length() - 1) ? pkg.substring(dot + 1) : pkg;
        return s.isEmpty() ? pkg : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static Drawable loadAppIcon(PackageManager pm, String pkg) {
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            return ai.loadIcon(pm);
        } catch (Exception e) {
            try { return pm.getApplicationIcon(pkg); }
            catch (Exception ex) { return pm.getDefaultActivityIcon(); }
        }
    }

    private static long queryUidBytes(Context ctx, int uid, long from, long to) {
        try {
            NetworkStatsManager nsm = (NetworkStatsManager) ctx.getSystemService(Context.NETWORK_STATS_SERVICE);
            if (nsm != null) {
                long total = 0;
                for (int netType : new int[]{ConnectivityManager.TYPE_WIFI, ConnectivityManager.TYPE_MOBILE}) {
                    try (NetworkStats stats = nsm.queryDetailsForUid(netType, null, from, to, uid)) {
                        NetworkStats.Bucket bucket = new NetworkStats.Bucket();
                        while (stats.hasNextBucket()) {
                            stats.getNextBucket(bucket);
                            total += bucket.getRxBytes() + bucket.getTxBytes();
                        }
                    }
                }
                if (total > 0) return total;
            }
        } catch (Exception ignored) {}
        long rx = TrafficStats.getUidRxBytes(uid);
        long tx = TrafficStats.getUidTxBytes(uid);
        if (rx > 0 || tx > 0) {
            return (rx > 0 ? rx : 0) + (tx > 0 ? tx : 0);
        }
        return 0;
    }

    private static String humanBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        if (bytes < 1024L * 1024 * 1024) return (bytes / (1024 * 1024)) + " MB";
        return String.format(Locale.US, "%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }
}