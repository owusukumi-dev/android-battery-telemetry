package com.syntax.batteryproject;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class GraphView extends View {

    private static final int MAX_RENDER_POINTS = 60;
    private static final float MIN_MA_SCALE = 600f;
    private static final float MIN_W_SCALE = 2.5f;

    private static class Sample {
        long ts;
        float ma;
        float w;
    }

    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint axisTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rightAxisTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint currentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint powerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint crosshairPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tooltipBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tooltipTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Path currentPath = new Path();
    private final Path powerPath = new Path();
    private final List<Sample> samples = new ArrayList<>();
    private final SimpleDateFormat clockFmt = new SimpleDateFormat("HH:mm", Locale.US);

    private int touchedIndex = -1;
    private float density;

    public GraphView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    private void init(Context ctx) {
        density = ctx.getResources().getDisplayMetrics().density;

        gridPaint.setColor(Color.parseColor("#1E293B"));
        gridPaint.setStrokeWidth(1f * density);
        gridPaint.setStyle(Paint.Style.STROKE);

        axisTextPaint.setColor(Color.parseColor("#64748B"));
        axisTextPaint.setTextSize(9f * density);

        rightAxisTextPaint.setColor(Color.parseColor("#EF4444"));
        rightAxisTextPaint.setTextSize(9f * density);
        rightAxisTextPaint.setTextAlign(Paint.Align.RIGHT);

        currentPaint.setColor(Color.parseColor("#38BDF8"));
        currentPaint.setStrokeWidth(2.5f * density);
        currentPaint.setStyle(Paint.Style.STROKE);
        currentPaint.setShadowLayer(8f, 0f, 0f, Color.parseColor("#38BDF8"));

        powerPaint.setColor(Color.parseColor("#EF4444"));
        powerPaint.setStrokeWidth(2f * density);
        powerPaint.setStyle(Paint.Style.STROKE);
        powerPaint.setShadowLayer(6f, 0f, 0f, Color.parseColor("#EF4444"));

        crosshairPaint.setColor(Color.parseColor("#F8FAFC"));
        crosshairPaint.setStrokeWidth(1f * density);
        crosshairPaint.setStyle(Paint.Style.STROKE);

        tooltipBgPaint.setColor(Color.parseColor("#EE0F172A"));
        tooltipBgPaint.setStyle(Paint.Style.FILL);

        tooltipTextPaint.setColor(Color.parseColor("#F8FAFC"));
        tooltipTextPaint.setTextSize(10f * density);
    }

    /** Downsamples up to 30 minutes of Room history into smooth, time-accurate buckets. */
    public void setHistory(List<BatterySample> rawHistory) {
        samples.clear();
        if (rawHistory == null || rawHistory.isEmpty()) {
            invalidate();
            return;
        }

        int total = rawHistory.size();
        if (total <= MAX_RENDER_POINTS) {
            for (BatterySample b : rawHistory) {
                Sample s = new Sample();
                s.ts = b.timestampMs;
                s.ma = safe(Math.abs(b.currentMa));
                s.w = safe((float) b.powerW);
                samples.add(s);
            }
        } else {
            double bucketSize = (double) total / MAX_RENDER_POINTS;
            for (int i = 0; i < MAX_RENDER_POINTS; i++) {
                int start = (int) Math.floor(i * bucketSize);
                int end = Math.min(total, (int) Math.floor((i + 1) * bucketSize));
                if (start >= end) start = Math.max(0, end - 1);

                long sumTs = 0;
                float sumMa = 0f;
                float sumW = 0f;
                int count = end - start;
                for (int j = start; j < end; j++) {
                    BatterySample b = rawHistory.get(j);
                    sumTs += b.timestampMs;
                    sumMa += Math.abs(b.currentMa);
                    sumW += (float) b.powerW;
                }
                Sample s = new Sample();
                s.ts = sumTs / count;
                s.ma = safe(sumMa / count);
                s.w = safe(sumW / count);
                samples.add(s);
            }
        }
        if (touchedIndex >= samples.size()) touchedIndex = -1;
        invalidate();
    }

    private static float safe(float v) {
        return (Float.isNaN(v) || Float.isInfinite(v)) ? 0f : v;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (samples.size() < 2) return true;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE: {
                float left = 42f * density;
                float right = getWidth() - 36f * density;
                float plotW = right - left;
                if (plotW <= 0) return true;

                float rel = Math.max(0f, Math.min(1f, (e.getX() - left) / plotW));
                long startTs = samples.get(0).ts;
                long endTs = samples.get(samples.size() - 1).ts;
                long targetTs = startTs + (long) (rel * (endTs - startTs));

                int bestIdx = 0;
                long bestDiff = Long.MAX_VALUE;
                for (int i = 0; i < samples.size(); i++) {
                    long diff = Math.abs(samples.get(i).ts - targetTs);
                    if (diff < bestDiff) {
                        bestDiff = diff;
                        bestIdx = i;
                    }
                }
                touchedIndex = bestIdx;
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                touchedIndex = -1;
                invalidate();
                return true;
        }
        return super.onTouchEvent(e);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) return;

        float leftPad = 42f * density;
        float rightPad = 36f * density;
        float topPad = 10f * density;
        float bottomPad = 22f * density;
        float plotW = w - leftPad - rightPad;
        float plotH = h - topPad - bottomPad;
        if (plotW <= 0 || plotH <= 0) return;

        float maxMa = MIN_MA_SCALE;
        float maxW = MIN_W_SCALE;
        for (Sample s : samples) {
            maxMa = Math.max(maxMa, s.ma);
            maxW = Math.max(maxW, s.w);
        }
        maxMa *= 1.15f;
        maxW *= 1.15f;

        // Horizontal grid + Left Axis (mA) + Right Axis (W)
        int gridLines = 4;
        for (int i = 0; i <= gridLines; i++) {
            float y = topPad + (plotH / gridLines) * i;
            canvas.drawLine(leftPad, y, w - rightPad, y, gridPaint);

            float ratio = 1f - ((float) i / gridLines);
            int maVal = Math.round(maxMa * ratio);
            float wVal = maxW * ratio;

            canvas.drawText(String.format(Locale.US, "%d", maVal), 4f * density, y + 3f * density, axisTextPaint);
            canvas.drawText(String.format(Locale.US, "%.1fW", wVal), w - 4f * density, y + 3f * density, rightAxisTextPaint);
        }

        // Vertical grid lines
        for (int i = 1; i <= 3; i++) {
            float x = leftPad + (plotW / 4f) * i;
            canvas.drawLine(x, topPad, x, topPad + plotH, gridPaint);
        }

        if (samples.size() >= 2) {
            long startTs = samples.get(0).ts;
            long endTs = samples.get(samples.size() - 1).ts;
            long spanMs = Math.max(1L, endTs - startTs);

            buildTimePath(currentPath, samples, true, maxMa, startTs, spanMs, leftPad, topPad, plotW, plotH);
            buildTimePath(powerPath, samples, false, maxW, startTs, spanMs, leftPad, topPad, plotW, plotH);
            canvas.drawPath(powerPath, powerPaint);
            canvas.drawPath(currentPath, currentPaint);

            // Real clock labels along the bottom X-axis
            for (int i = 0; i <= 3; i++) {
                float frac = i / 3f;
                float x = leftPad + plotW * frac;
                long tsAtPos = startTs + (long) (spanMs * frac);
                String label;
                if (i == 3) {
                    label = "Now";
                } else if (spanMs < 120_000L) {
                    long secAgo = (endTs - tsAtPos) / 1000L;
                    label = "-" + secAgo + "s";
                } else {
                    label = clockFmt.format(new Date(tsAtPos));
                }
                float textW = axisTextPaint.measureText(label);
                float tx = Math.max(leftPad, Math.min(w - rightPad - textW, x - textW / 2f));
                canvas.drawText(label, tx, h - 5f * density, axisTextPaint);
            }

            // Touch crosshair + floating inspection box
            if (touchedIndex >= 0 && touchedIndex < samples.size()) {
                Sample s = samples.get(touchedIndex);
                float x = timeToX(s.ts, startTs, spanMs, leftPad, plotW, touchedIndex, samples.size());
                canvas.drawLine(x, topPad, x, topPad + plotH, crosshairPaint);

                float yMa = valueToY(s.ma, maxMa, topPad, plotH);
                float yW = valueToY(s.w, maxW, topPad, plotH);
                canvas.drawCircle(x, yMa, 4f * density, currentPaint);
                canvas.drawCircle(x, yW, 4f * density, powerPaint);

                String timeLabel = clockFmt.format(new Date(s.ts));
                String line1 = String.format(Locale.US, "%s · %d mA", timeLabel, Math.round(s.ma));
                String line2 = String.format(Locale.US, "Power: %.2f W", s.w);
                float textW = Math.max(tooltipTextPaint.measureText(line1), tooltipTextPaint.measureText(line2));
                float boxW = textW + 16f * density;
                float boxH = 38f * density;
                float bx = Math.min(x + 8f * density, w - rightPad - boxW);
                bx = Math.max(leftPad, bx);
                float by = topPad + 4f * density;

                canvas.drawRoundRect(bx, by, bx + boxW, by + boxH, 6f * density, 6f * density, tooltipBgPaint);
                canvas.drawText(line1, bx + 8f * density, by + 15f * density, tooltipTextPaint);
                canvas.drawText(line2, bx + 8f * density, by + 30f * density, tooltipTextPaint);
            }
        }
    }

    private void buildTimePath(Path path, List<Sample> data, boolean isCurrent, float maxScale,
                               long startTs, long spanMs, float leftPad, float topPad, float plotW, float plotH) {
        path.reset();
        float prevX = timeToX(data.get(0).ts, startTs, spanMs, leftPad, plotW, 0, data.size());
        float prevY = valueToY(isCurrent ? data.get(0).ma : data.get(0).w, maxScale, topPad, plotH);
        path.moveTo(prevX, prevY);

        for (int i = 1; i < data.size(); i++) {
            float currX = timeToX(data.get(i).ts, startTs, spanMs, leftPad, plotW, i, data.size());
            float currY = valueToY(isCurrent ? data.get(i).ma : data.get(i).w, maxScale, topPad, plotH);
            float midX = (prevX + currX) / 2f;
            path.cubicTo(midX, prevY, midX, currY, currX, currY);
            prevX = currX;
            prevY = currY;
        }
    }

    private float timeToX(long ts, long startTs, long spanMs, float leftPad, float plotW, int idx, int size) {
        if (spanMs < 3000L) {
            return leftPad + (plotW * idx) / Math.max(1, size - 1);
        }
        float ratio = Math.max(0f, Math.min(1f, (float) (ts - startTs) / spanMs));
        return leftPad + ratio * plotW;
    }

    private float valueToY(float v, float maxScale, float topPad, float plotH) {
        float ratio = Math.max(0f, Math.min(1f, v / maxScale));
        return topPad + plotH - ratio * plotH;
    }
}