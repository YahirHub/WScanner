package com.thowilabs.wscanner;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * Velocímetro circular moderno y minimalista.
 * Arco de progreso grueso con gradiente cian + efecto glow,
 * sin aguja ni marcas. Solo el número central y la etiqueta.
 */
public class SpeedometerGauge extends View {

    // ── Geometría del arco ──
    private static final float START_ANGLE = 135f;    // 3/4 de círculo
    private static final float SWEEP_ANGLE = 270f;
    private static final int   DEFAULT_MAX_MBPS = 100;

    // ── Paleta oscura ──
    private static final int COLOR_BG_ARC     = 0xFF1C2128;
    private static final int COLOR_INNER_RING = 0xFF161B22;
    private static final int COLOR_VALUE      = 0xFF00E5FF;
    private static final int COLOR_LABEL      = 0xFF6E7681;
    private static final int COLOR_GLOW       = 0x1800E5FF;  // ~9% opaco

    // ── Degradado del arco activo: azul profundo → cian → cian claro ──
    private static final int[] GRADIENT_COLORS = {
        0xFF0077B6,   // azul marino
        0xFF00A8D4,   // teal
        0xFF00E5FF,   // cian brillante
        0xFF80F0FF    // cian pastel
    };
    private static final float[] GRADIENT_POSITIONS = {0f, 0.35f, 0.70f, 1f};

    // ── Paints ──
    private final Paint arcBgPaint;
    private final Paint arcActivePaint;
    private final Paint glowPaint;
    private final Paint textValuePaint;
    private final Paint textLabelPaint;
    private final Paint innerRingPaint;

    private final RectF  arcRect;
    private final Matrix gradientMatrix;

    private SweepGradient sweepGradient;
    private int lastCx = -1;
    private int lastCy = -1;

    // ── Estado ──
    private double maxSpeed     = DEFAULT_MAX_MBPS;
    private double currentSpeed = 0;
    private double targetSpeed  = 0;
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

        glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        glowPaint.setStyle(Paint.Style.STROKE);
        glowPaint.setStrokeCap(Paint.Cap.ROUND);
        glowPaint.setColor(COLOR_GLOW);

        textValuePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textValuePaint.setColor(COLOR_VALUE);
        textValuePaint.setTextAlign(Paint.Align.CENTER);
        textValuePaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));

        textLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textLabelPaint.setColor(COLOR_LABEL);
        textLabelPaint.setTextAlign(Paint.Align.CENTER);

        innerRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        innerRingPaint.setStyle(Paint.Style.STROKE);
        innerRingPaint.setColor(COLOR_INNER_RING);

        arcRect = new RectF();
        gradientMatrix = new Matrix();
    }

    /**
     * Actualiza la velocidad mostrada con animación suave.
     * Escala automáticamente el máximo si la velocidad lo supera.
     */
    public void setSpeed(double mbps) {
        targetSpeed = Math.max(0, mbps);

        // Reiniciar escala al comenzar un test nuevo y auto-escalar hasta 10 Gbps.
        if (targetSpeed == 0) {
            maxSpeed = DEFAULT_MAX_MBPS;
        }
        while (targetSpeed > maxSpeed * 0.9 && maxSpeed < 10_000) {
            maxSpeed *= 2;
        }

        if (animator != null && animator.isRunning()) {
            animator.cancel();
        }

        animator = ValueAnimator.ofFloat((float) currentSpeed, (float) targetSpeed);
        animator.setDuration(400);
        animator.setInterpolator(new DecelerateInterpolator(1.2f));
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
        int size    = MeasureSpec.getSize(widthMeasureSpec);
        int desired = (int) (280 * getResources().getDisplayMetrics().density);
        int min     = (int) (200 * getResources().getDisplayMetrics().density);
        int w       = Math.max(Math.min(size, desired), min);
        setMeasuredDimension(w, w);
    }

    // ─────────────────────────────────────────────
    //  Dibujo
    // ─────────────────────────────────────────────

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int w  = getWidth();
        int h  = getHeight();
        int cx = w / 2;
        int cy = h / 2;

        float strokeWidth = w * 0.10f;               // arco grueso
        float margin      = strokeWidth / 2f + 14f;
        float radius      = Math.min(cx, cy) - margin;

        arcBgPaint.setStrokeWidth(strokeWidth);
        arcActivePaint.setStrokeWidth(strokeWidth);

        arcRect.set(cx - radius, cy - radius, cx + radius, cy + radius);

        // Progreso actual como fracción del arco total
        float sweep = (float) (currentSpeed / maxSpeed * SWEEP_ANGLE);
        sweep = Math.min(sweep, SWEEP_ANGLE);

        // ── Degradado SweepGradient alineado con START_ANGLE ──
        // Se crea una sola vez y se reusa (solo se recrea si cambia el centro)
        if (sweepGradient == null || cx != lastCx || cy != lastCy) {
            sweepGradient = new SweepGradient(cx, cy, GRADIENT_COLORS, GRADIENT_POSITIONS);
            lastCx = cx;
            lastCy = cy;
        }
        gradientMatrix.reset();
        gradientMatrix.setRotate(START_ANGLE, cx, cy);
        sweepGradient.setLocalMatrix(gradientMatrix);
        arcActivePaint.setShader(sweepGradient);

        // ── 1. Glow exterior (anillo ancho semi-transparente) ──
        if (sweep > 0) {
            glowPaint.setStrokeWidth(strokeWidth + 12f);
            canvas.drawArc(arcRect, START_ANGLE, sweep, false, glowPaint);
        }

        // ── 2. Arco de fondo ──
        canvas.drawArc(arcRect, START_ANGLE, SWEEP_ANGLE, false, arcBgPaint);

        // ── 3. Arco activo con degradado ──
        if (sweep > 0) {
            canvas.drawArc(arcRect, START_ANGLE, sweep, false, arcActivePaint);
        }

        arcActivePaint.setShader(null);   // liberar shader

        // ── 4. Aro interior sutil (profundidad) ──
        float innerRadius = radius - strokeWidth - 8f;
        innerRingPaint.setStrokeWidth(1f);
        canvas.drawCircle(cx, cy, innerRadius, innerRingPaint);

        // ── 5. Texto central: valor ──
        textValuePaint.setTextSize(w * 0.17f);
        float valueY = cy - w * 0.03f;
        canvas.drawText(speedText, cx, valueY, textValuePaint);

        // ── 6. Etiqueta "Mbps" ──
        textLabelPaint.setTextSize(w * 0.05f);
        canvas.drawText("Mbps", cx, valueY + w * 0.10f, textLabelPaint);
    }
}
