package com.thowilabs.wscanner;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.animation.LinearInterpolator;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatTextView;

/** Texto shimmer sutil para estados activos. Se desactiva cuando no es necesario. */
public class ShimmerTextView extends AppCompatTextView {

    private final Matrix shaderMatrix = new Matrix();
    private ValueAnimator animator;
    private LinearGradient gradient;
    private boolean shimmerEnabled;
    private int baseColor = 0xFFA6B2C2;
    private int highlightColor = 0xFF8DEBFF;

    public ShimmerTextView(Context context) {
        super(context);
    }

    public ShimmerTextView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public ShimmerTextView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void setShimmerEnabled(boolean enabled) {
        if (shimmerEnabled == enabled) return;
        shimmerEnabled = enabled;
        if (enabled) {
            post(this::startShimmer);
        } else {
            stopShimmer();
        }
    }

    public void setShimmerColors(int baseColor, int highlightColor) {
        this.baseColor = baseColor;
        this.highlightColor = highlightColor;
        if (shimmerEnabled) post(this::startShimmer);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (shimmerEnabled && w > 0) startShimmer();
    }

    @Override
    public void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);
        if (isVisible && shimmerEnabled) {
            post(this::startShimmer);
        } else {
            stopAnimatorOnly();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        stopShimmer();
        super.onDetachedFromWindow();
    }

    private void startShimmer() {
        if (!shimmerEnabled || getWidth() <= 0 || !isShown()) return;
        stopAnimatorOnly();

        float band = Math.max(getWidth() * 0.65f, 180f);
        gradient = new LinearGradient(
                -band, 0, 0, 0,
                new int[]{baseColor, highlightColor, baseColor},
                new float[]{0f, 0.5f, 1f},
                Shader.TileMode.CLAMP);
        getPaint().setShader(gradient);

        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(1350L);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setRepeatMode(ValueAnimator.RESTART);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(animation -> {
            if (gradient == null) return;
            float fraction = (float) animation.getAnimatedValue();
            float travel = getWidth() * 2.2f;
            shaderMatrix.setTranslate(-getWidth() + travel * fraction, 0f);
            gradient.setLocalMatrix(shaderMatrix);
            invalidate();
        });
        animator.start();
    }

    private void stopShimmer() {
        stopAnimatorOnly();
        gradient = null;
        getPaint().setShader(null);
        setTextColor(baseColor);
        invalidate();
    }

    private void stopAnimatorOnly() {
        if (animator != null) {
            animator.cancel();
            animator.removeAllUpdateListeners();
            animator = null;
        }
    }
}
