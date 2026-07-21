package com.thowilabs.wscanner;

import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.OvershootInterpolator;
import android.view.animation.PathInterpolator;

/**
 * Microinteracción de presión reutilizable para Views tradicionales.
 * No consume el gesto: conserva click, long-click, scroll y cancelación nativos.
 */
public final class PressStateUtil {

    private static final PathInterpolator PRESS_INTERPOLATOR =
            new PathInterpolator(0.2f, 0f, 0f, 1f);
    private static final OvershootInterpolator RELEASE_INTERPOLATOR =
            new OvershootInterpolator(0.75f);

    private PressStateUtil() {}

    public static void attach(View view) {
        attach(view, 0.97f);
    }

    public static void attach(View view, float pressedScale) {
        if (view == null) return;
        view.setClickable(true);
        int touchSlop = ViewConfiguration.get(view.getContext()).getScaledTouchSlop();

        view.setOnTouchListener(new View.OnTouchListener() {
            private float downX;
            private float downY;
            private boolean pressedVisual;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = event.getX();
                        downY = event.getY();
                        pressedVisual = true;
                        animatePressed(v, pressedScale);
                        break;

                    case MotionEvent.ACTION_MOVE:
                        boolean outside = event.getX() < -touchSlop
                                || event.getY() < -touchSlop
                                || event.getX() > v.getWidth() + touchSlop
                                || event.getY() > v.getHeight() + touchSlop;
                        boolean dragged = Math.abs(event.getX() - downX) > touchSlop
                                || Math.abs(event.getY() - downY) > touchSlop;
                        if (pressedVisual && (outside || dragged)) {
                            pressedVisual = false;
                            animateReleased(v, false);
                        } else if (!pressedVisual && !outside && !dragged) {
                            pressedVisual = true;
                            animatePressed(v, pressedScale);
                        }
                        break;

                    case MotionEvent.ACTION_UP:
                        pressedVisual = false;
                        animateReleased(v, true);
                        break;

                    case MotionEvent.ACTION_CANCEL:
                    case MotionEvent.ACTION_POINTER_UP:
                        pressedVisual = false;
                        animateReleased(v, false);
                        break;

                    default:
                        break;
                }
                return false;
            }
        });
    }

    public static void reset(View view) {
        if (view == null) return;
        view.animate().cancel();
        view.setScaleX(1f);
        view.setScaleY(1f);
        view.setAlpha(1f);
        view.setTranslationY(0f);
    }

    private static void animatePressed(View view, float scale) {
        view.animate().cancel();
        view.animate()
                .scaleX(scale)
                .scaleY(scale)
                .alpha(0.94f)
                .setDuration(75)
                .setInterpolator(PRESS_INTERPOLATOR)
                .start();
    }

    private static void animateReleased(View view, boolean springBack) {
        view.animate().cancel();
        view.animate()
                .scaleX(1f)
                .scaleY(1f)
                .alpha(1f)
                .setDuration(springBack ? 170 : 110)
                .setInterpolator(springBack ? RELEASE_INTERPOLATOR : PRESS_INTERPOLATOR)
                .start();
    }
}
