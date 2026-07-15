package com.thowilabs.wscanner;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * Velocímetro circular estilo Ookla Speedtest.
 * Dibuja un arco de fondo, arco activo proporcional a la velocidad,
 * aguja animada y texto central con el valor en Mbps.
 */
public class SpeedometerGauge extends View {

    private static final float START_ANGLE = 150f;
    private static final float SWEEP_ANGLE = 240f;
    private static final int DEFAULT_MAX_MBPS = 100;

    // Colores del tema oscuro
    private static final int COLOR_BG_ARC       = 0xFF21262D;
    private static final int COLOR_ACTIVE_ARC   = 0xFF00E5FF;
    private static final int COLOR_NEEDLE       = 0xFFE6EDF3;
    private static final int COLOR_TEXT_PRIMARY = 0xFFE6EDF3;
    private static final int COLOR_TEXT_SECONDARY = 0xFF8B949E;
    private static final int COLOR_TICK         = 0xFF6E7681;
    private static final int COLOR_CENTER_DOT   = 0xFF0D1117;

    private final Paint arcBgPaint;
    private final Paint arcActivePaint;
    private final Paint needlePaint;
    private final Paint textPaint;
    private final Paint textSmallPaint;
    private final Paint tickPaint;
    private final Paint centerDotPaint;

    private final RectF arcRect;

    private double maxSpeed = DEFAULT_MAX_MBPS;
    private double currentSpeed = 0;
    private double targetSpeed = 0;
    private ValueAnimator animator;
    private String speedText = "0";

    public SpeedometerGauge(Context context) {
        this(context, null);
    }

    public SpeedometerGauge(Context context, AttributeSet attrs) {
        super(context, attrs);

        arcBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        arcBgPaint.setStyle(Paint.Style.STROKE);
        arcBgPaint.setStrokeCap(Paint.Cap.ROUND);
        arcBgPaint.setColor(COLOR_BG_ARC);

        arcActivePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        arcActivePaint.setStyle(Paint.Style.STROKE);
        arcActivePaint.setStrokeCap(Paint.Cap.ROUND);
        arcActivePaint.setColor(COLOR_ACTIVE_ARC);

        needlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        needlePaint.setStyle(Paint.Style.STROKE);
        needlePaint.setStrokeCap(Paint.Cap.ROUND);
        needlePaint.setColor(COLOR_NEEDLE);

        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(COLOR_ACTIVE_ARC);
        textPaint.setTextAlign(Paint.Align.CENTER);

        textSmallPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textSmallPaint.setColor(COLOR_TEXT_SECONDARY);
        textSmallPaint.setTextAlign(Paint.Align.CENTER);

        tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        tickPaint.setColor(COLOR_TICK);
        tickPaint.setTextAlign(Paint.Align.CENTER);

        centerDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        centerDotPaint.setStyle(Paint.Style.FILL);
        centerDotPaint.setColor(COLOR_CENTER_DOT);

        arcRect = new RectF();
    }

    /**
     * Actualiza la velocidad mostrada con animación suave.
     * Si la velocidad supera el máximo actual, se auto-escala.
     */
    public void setSpeed(double mbps) {
        targetSpeed = mbps;

        // Auto-escalar si la velocidad supera el máximo
        while (targetSpeed > maxSpeed * 0.9 && maxSpeed < 1000) {
            maxSpeed *= 2;
        }

        if (animator != null && animator.isRunning()) {
            animator.cancel();
        }

        animator = ValueAnimator.ofFloat((float) currentSpeed, (float) targetSpeed);
        animator.setDuration(350);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(a -> {
            currentSpeed = (float) a.getAnimatedValue();
            speedText = currentSpeed >= 10
                    ? String.valueOf(Math.round(currentSpeed))
                    : String.format("%.1f", currentSpeed);
            invalidate();
        });
        animator.start();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int size = MeasureSpec.getSize(widthMeasureSpec);
        int desired = (int) (280 * getResources().getDisplayMetrics().density);
        // Ser cuadrado basado en el ancho, mínimo 200dp
        int w = Math.max(size, (int) (200 * getResources().getDisplayMetrics().density));
        w = Math.min(w, desired);
        setMeasuredDimension(w, w);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int w = getWidth();
        int h = getHeight();
        int cx = w / 2;
        int cy = h / 2;
        float strokeWidth = w * 0.07f;
        float margin = strokeWidth / 2f + 8f;
        float radius = Math.min(cx, cy) - margin;

        arcBgPaint.setStrokeWidth(strokeWidth);
        arcActivePaint.setStrokeWidth(strokeWidth);

        arcRect.set(cx - radius, cy - radius, cx + radius, cy + radius);

        // ── Arco de fondo ──
        canvas.drawArc(arcRect, START_ANGLE, SWEEP_ANGLE, false, arcBgPaint);

        // ── Arco activo ──
        float sweep = (float) (currentSpeed / maxSpeed * SWEEP_ANGLE);
        sweep = Math.min(sweep, SWEEP_ANGLE);
        if (sweep > 0) {
            canvas.drawArc(arcRect, START_ANGLE, sweep, false, arcActivePaint);
        }

        // ── Marcas de escala ──
        int[] ticks = {0, 10, 25, 50, 100, 250, 500, 1000};
        tickPaint.setTextSize(w * 0.04f);
        float tickRadius = radius - strokeWidth - 8f;
        for (int tick : ticks) {
            if (tick > maxSpeed) break;
            float angle = START_ANGLE + (float) tick / (float) maxSpeed * SWEEP_ANGLE;
            float rad = (float) Math.toRadians(angle);
            float x1 = cx + (tickRadius + 4f) * (float) Math.cos(rad);
            float y1 = cy + (tickRadius + 4f) * (float) Math.sin(rad);
            float x2 = cx + tickRadius * (float) Math.cos(rad);
            float y2 = cy + tickRadius * (float) Math.sin(rad);
            canvas.drawLine(x1, y1, x2, y2, tickPaint);

            // Label solo para algunos ticks
            if (tick <= (int) maxSpeed) {
                float labelRadius = tickRadius - 20f;
                float lx = cx + labelRadius * (float) Math.cos(rad);
                float ly = cy + labelRadius * (float) Math.sin(rad);
                canvas.drawText(String.valueOf(tick), lx, ly + 5f, tickPaint);
            }
        }

        // ── Aguja ──
        float needleAngle = START_ANGLE + sweep;
        float needleRad = (float) Math.toRadians(needleAngle);
        float needleLength = radius * 0.75f;
        float nx = cx + needleLength * (float) Math.cos(needleRad);
        float ny = cy + needleLength * (float) Math.sin(needleRad);
        needlePaint.setStrokeWidth(w * 0.008f);
        canvas.drawLine(cx, cy, nx, ny, needlePaint);

        // ── Círculo central ──
        canvas.drawCircle(cx, cy, w * 0.025f, needlePaint);
        centerDotPaint.setColor(COLOR_CENTER_DOT);
        canvas.drawCircle(cx, cy, w * 0.018f, centerDotPaint);

        // ── Texto central: valor Mbps ──
        textPaint.setTextSize(w * 0.14f);
        canvas.drawText(speedText, cx, cy - w * 0.02f, textPaint);

        // ── Label "Mbps" ──
        textSmallPaint.setTextSize(w * 0.045f);
        canvas.drawText("Mbps", cx, cy + w * 0.08f, textSmallPaint);

        // ── Rango máximo ──
        textSmallPaint.setTextSize(w * 0.035f);
        canvas.drawText("0–" + (int) maxSpeed, cx, cy + w * 0.16f, textSmallPaint);
    }
}
