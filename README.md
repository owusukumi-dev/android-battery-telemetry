# Android Battery & Hardware Telemetry Monitor

A systems-level Android telemetry application built in Java that samples, visualizes, and persists real-time battery, charging, and network interface metrics.

## Architecture & Engineering Highlights
- **Hardware & OS Telemetry Sampling:** Hooks directly into Android `BatteryManager` broadcasts, `System.currentTimeMillis()` intervals, and `NetworkStatsManager` APIs to track instantaneous current draw (mA), voltage, temperature, charging state (Fast/QC detection), and per-interface data throughput.
- **Custom 2D Canvas Engine (`GraphView`):** Engineered a custom `android.view.View` real-time plotting engine with density-independent (`dp`) scaling, dynamic axis bounds, and touch-coordinate inspection—zero third-party charting dependencies.
- **Local Persistence (`Room` / SQLite):** Implemented structured asynchronous sample logging with automated rolling retention (`DB_TRIM_INTERVAL_MS`) using Android `Room` entities and DAOs.
- **UI & Live Diagnostics:** Multi-tab ViewBinding interface paired with live status-bar notification readouts showing real-time current draw and hourly drain projections.

## Tech Stack
- **Language:** Java (Android SDK)
- **Storage:** Room Persistence Library (SQLite)
- **UI & Graphics:** Custom `View` Canvas rendering, XML layouts, ViewBinding
- **System APIs:** `BatteryManager`, `NetworkStatsManager`, `NotificationManager`, `Intent.ACTION_BATTERY_CHANGED`
