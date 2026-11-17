package com.syntax.batteryproject;

import android.animation.ValueAnimator;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final long POLL_INTERVAL_MS = 2000L;

    private View overviewPage, diagnosticsPage;
    private TextView tabOverview, tabDiagnostics;

    // Overview
    private TextView ovBattery, ovStatus, ovTimeLeft, ovTemp, ovVoltage, ovCurrent,
            ovHealth, ovCycles, ovCapacity;
    private ProgressBar ovBar;
    private ImageView[] ovIcons;
    private TextView[] ovNames, ovPercents, ovWhy;
    private ProgressBar[] ovProgresses;

    private TextView currentDrawTextView, powerTextView, capacityTextView, voltageTextView, tempTextView;
    private TextView chargingTechTextView, healthTextView, cycleCountTextView, designCapacityTextView;
    private TextView trendSummaryTextView;
    private GraphView graphView;

    private int lastSignedMa = 0;
    private double lastPowerW = 0.0;
    private long stableDisplayedMinutes = -1;
    private RawBatteryExplorer.ChargeState lastDisplayedState = null;

    private ValueAnimator currentAnimator;
    private ValueAnimator powerAnimator;

    private Handler pollHandler;
    private Runnable pollRunnable;
    private boolean pollActive = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        overviewPage = findViewById(R.id.overviewPage);
        diagnosticsPage = findViewById(R.id.diagnosticsPage);
        tabOverview = findViewById(R.id.tabOverview);
        tabDiagnostics = findViewById(R.id.tabDiagnostics);

        tabOverview.setOnClickListener(v -> showTab(true));
        tabDiagnostics.setOnClickListener(v -> showTab(false));

        // Overview bindings
        ovBattery = findViewById(R.id.overviewBatteryPercent);
        ovStatus = findViewById(R.id.overviewStatusText);
        ovTimeLeft = findViewById(R.id.overviewTimeLeft);
        ovBar = findViewById(R.id.overviewBatteryBar);
        ovTemp = findViewById(R.id.overviewTempChip);
        ovVoltage = findViewById(R.id.overviewVoltageChip);
        ovCurrent = findViewById(R.id.overviewCurrentChip);
        ovHealth = findViewById(R.id.overviewHealthText);
        ovCycles = findViewById(R.id.overviewCyclesText);
        ovCapacity = findViewById(R.id.overviewCapacityText);
        ovIcons = new ImageView[]{
                findViewById(R.id.overviewApp1Icon),
                findViewById(R.id.overviewApp2Icon),
                findViewById(R.id.overviewApp3Icon)};
        ovNames = new TextView[]{
                findViewById(R.id.overviewApp1Name),
                findViewById(R.id.overviewApp2Name),
                findViewById(R.id.overviewApp3Name)};
        ovPercents = new TextView[]{
                findViewById(R.id.overviewApp1Percent),
                findViewById(R.id.overviewApp2Percent),
                findViewById(R.id.overviewApp3Percent)};
        ovWhy = new TextView[]{
                findViewById(R.id.overviewApp1Why),
                findViewById(R.id.overviewApp2Why),
                findViewById(R.id.overviewApp3Why)};
        ovProgresses = new ProgressBar[]{
                findViewById(R.id.overviewApp1Progress),
                findViewById(R.id.overviewApp2Progress),
                findViewById(R.id.overviewApp3Progress)};

        // Diagnostics bindings
        currentDrawTextView = findViewById(R.id.currentDrawTextView);
        powerTextView = findViewById(R.id.powerTextView);
        capacityTextView = findViewById(R.id.capacityTextView);
        voltageTextView = findViewById(R.id.voltageTextView);
        tempTextView = findViewById(R.id.tempTextView);
        chargingTechTextView = findViewById(R.id.chargingTechTextView);
        healthTextView = findViewById(R.id.healthTextView);
        cycleCountTextView = findViewById(R.id.cycleCountTextView);
        designCapacityTextView = findViewById(R.id.designCapacityTextView);
        trendSummaryTextView = findViewById(R.id.trendSummaryTextView);
        graphView = findViewById(R.id.graphView);

        View.OnClickListener openUsageSettings = v ->
                startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
        for (TextView nameView : ovNames) {
            nameView.setOnClickListener(openUsageSettings);
        }

        pollHandler = new Handler(Looper.getMainLooper());
        pollRunnable = new Runnable() {
            @Override public void run() {
                if (!pollActive || isFinishing() || isDestroyed()) return;
                RawBatteryExplorer.queryHardwareSensor(MainActivity.this, snap -> {
                    if (!pollActive || isFinishing() || isDestroyed()) return;
                    renderSnapshot(snap);
                    pollHandler.postDelayed(pollRunnable, POLL_INTERVAL_MS);
                });
            }
        };
    }

    private void showTab(boolean overview) {
        overviewPage.setVisibility(overview ? View.VISIBLE : View.GONE);
        diagnosticsPage.setVisibility(overview ? View.GONE : View.VISIBLE);
        tabOverview.setBackgroundResource(overview ? R.drawable.seg_selected : 0);
        tabDiagnostics.setBackgroundResource(overview ? 0 : R.drawable.seg_selected);
        tabOverview.setTextColor(ContextCompat.getColor(this, overview ? android.R.color.white : R.color._neutral_500));
        tabDiagnostics.setTextColor(ContextCompat.getColor(this, overview ? R.color._neutral_500 : android.R.color.white));
    }

    private void renderSnapshot(RawBatteryExplorer.Snapshot snap) {
        // Overview
        ovBattery.setText(String.format(Locale.US, "%d%%", snap.level));
        int levelColor = snap.level > 50 ? 0xFF10B981 :
                snap.level > 20 ? 0xFFF59E0B : 0xFFEF4444;
        ovBattery.setTextColor(levelColor);
        ovBar.setProgress(snap.level);
        ovBar.setProgressTintList(ColorStateList.valueOf(levelColor));

        ovStatus.setText(statusLabel(snap));
        ovStatus.setTextColor(statusColor(snap));

        // Hysteresis filter: only change the displayed time estimate if it shifts by >4 minutes
        if (snap.estimatedMinutes > 0) {
            boolean stateChanged = (lastDisplayedState != snap.state);
            long diff = Math.abs(snap.estimatedMinutes - stableDisplayedMinutes);
            if (stableDisplayedMinutes < 0 || stateChanged || diff >= 5) {
                stableDisplayedMinutes = snap.estimatedMinutes;
                lastDisplayedState = snap.state;
            }
            String t = formatDuration(stableDisplayedMinutes);
            String suffix;
            switch (snap.state) {
                case CHARGING: suffix = "to full"; break;
                case REVERSE_CHARGING: suffix = "of power share"; break;
                case DISCHARGING: suffix = "remaining"; break;
                default: suffix = "";
            }
            ovTimeLeft.setText(suffix.isEmpty() ? "—" : ("~" + t + " " + suffix));
        } else {
            ovTimeLeft.setText("—");
            stableDisplayedMinutes = -1;
        }

        ovTemp.setText(String.format(Locale.US, "%.1f °C", snap.tempC));
        ovTemp.setTextColor(snap.tempC > 45 ? 0xFFEF4444 : snap.tempC > 40 ? 0xFFF59E0B : 0xFFF8FAFC);
        ovVoltage.setText(String.format(Locale.US, "%.2f V", snap.voltageV));

        ovCurrent.setText(snap.signedCurrentText);
        ovCurrent.setTextColor(statusColor(snap));

        ovHealth.setText(snap.health);
        ovCycles.setText(snap.cycleCount);
        ovCapacity.setText(snap.capacityMah);

        renderAppRows(snap.topApps);

        // Diagnostics
        currentDrawTextView.setTextColor(statusColor(snap));
        animateSignedCurrent(currentDrawTextView, lastSignedMa, snap.currentMaSigned);
        animateDouble(powerTextView, lastPowerW, snap.powerW, "%.2f W");
        capacityTextView.setText(String.format(Locale.US, "%d %%", snap.level));
        voltageTextView.setText(String.format(Locale.US, "%.2f V", snap.voltageV));
        tempTextView.setText(String.format(Locale.US, "%.1f °C", snap.tempC));
        chargingTechTextView.setText(snap.chargingTech);
        healthTextView.setText(snap.health);
        cycleCountTextView.setText(snap.cycleCount);
        designCapacityTextView.setText(snap.capacityMah);
        trendSummaryTextView.setText(snap.trendSummary == null ? "Building history…" : snap.trendSummary);
        graphView.setHistory(snap.chartHistory);

        lastSignedMa = snap.currentMaSigned;
        lastPowerW = snap.powerW;
    }

    private static String statusLabel(RawBatteryExplorer.Snapshot snap) {
        switch (snap.state) {
            case CHARGING:          return "Charging · " + snap.chargingTech;
            case REVERSE_CHARGING:  return "Power sharing (output)";
            case IDLE:              return "Full / Idle";
            default:                return "Discharging";
        }
    }

    private static int statusColor(RawBatteryExplorer.Snapshot snap) {
        switch (snap.state) {
            case CHARGING:          return 0xFF10B981;
            case REVERSE_CHARGING:  return 0xFFF59E0B;
            case IDLE:              return 0xFF94A3B8;
            default:                return 0xFFEF4444;
        }
    }

    private void renderAppRows(List<RawBatteryExplorer.AppDrainItem> apps) {
        int shown = Math.min(3, apps.size());
        for (int i = 0; i < shown; i++) {
            RawBatteryExplorer.AppDrainItem item = apps.get(i);
            ovIcons[i].setImageDrawable(item.icon);
            ovNames[i].setText(item.appName);
            ovPercents[i].setText(String.format(Locale.US, "%d%%", item.percentage));
            ovWhy[i].setText(item.reason);
            if (Build.VERSION.SDK_INT >= 24) ovProgresses[i].setProgress(item.percentage, true);
            else ovProgresses[i].setProgress(item.percentage);
        }
        for (int i = shown; i < 3; i++) {
            ovIcons[i].setImageResource(android.R.drawable.sym_def_app_icon);
            ovNames[i].setText("—");
            ovPercents[i].setText("—");
            ovWhy[i].setText("");
            if (Build.VERSION.SDK_INT >= 24) ovProgresses[i].setProgress(0, true);
            else ovProgresses[i].setProgress(0);
        }
    }

    private static String formatDuration(long minutes) {
        if (minutes < 60) return minutes + " m";
        return (minutes / 60) + " h " + (minutes % 60) + " m";
    }

    private void animateSignedCurrent(TextView target, int start, int end) {
        if (currentAnimator != null) currentAnimator.cancel();
        currentAnimator = ValueAnimator.ofInt(start, end);
        currentAnimator.setDuration(600);
        currentAnimator.setInterpolator(new DecelerateInterpolator());
        currentAnimator.addUpdateListener(a -> {
            int v = (int) a.getAnimatedValue();
            String text;
            if (v > 0) text = String.format(Locale.US, "+%d mA", v);
            else if (v < 0) text = String.format(Locale.US, "-%d mA", Math.abs(v));
            else text = "0 mA";
            target.setText(text);
        });
        currentAnimator.start();
    }

    private void animateDouble(TextView target, double start, double end, String format) {
        if (powerAnimator != null) powerAnimator.cancel();
        powerAnimator = ValueAnimator.ofFloat((float) start, (float) end);
        powerAnimator.setDuration(600);
        powerAnimator.setInterpolator(new DecelerateInterpolator());
        powerAnimator.addUpdateListener(a -> target.setText(String.format(Locale.US, format, (float) a.getAnimatedValue())));
        powerAnimator.start();
    }

    @Override protected void onResume() {
        super.onResume();
        pollActive = true;
        pollHandler.removeCallbacks(pollRunnable);
        pollHandler.post(pollRunnable);
    }

    @Override protected void onPause() {
        super.onPause();
        pollActive = false;
        pollHandler.removeCallbacks(pollRunnable);
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        pollHandler.removeCallbacksAndMessages(null);
        if (currentAnimator != null) currentAnimator.cancel();
        if (powerAnimator != null) powerAnimator.cancel();
    }
}