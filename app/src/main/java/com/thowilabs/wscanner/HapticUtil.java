package com.thowilabs.wscanner;

import android.content.Context;
import android.os.Build;
import android.os.Vibrator;
import android.view.HapticFeedbackConstants;
import android.view.View;

/**
 * Utilidad de feedback háptico compatible con API 24+.
 * Usa performHapticFeedback en API 26+ y Vibrator legacy en 24-25.
 */
public final class HapticUtil {

    private HapticUtil() {}

    /** Feedback sutil para taps y clicks. */
    public static void performClick(View view) {
        if (view == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
        } else {
            vibrateLegacy(view.getContext(), 15);
        }
    }

    /** Feedback de confirmación para acciones importantes (iniciar escaneo). */
    public static void performConfirm(View view) {
        if (view == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
        } else {
            vibrateLegacy(view.getContext(), 25);
        }
    }

    /** Feedback más intenso para finalización. */
    public static void performHeavy(View view) {
        if (view == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        } else {
            vibrateLegacy(view.getContext(), 40);
        }
    }

    private static void vibrateLegacy(Context context, long ms) {
        Vibrator v = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        if (v != null && v.hasVibrator()) {
            v.vibrate(ms);
        }
    }
}
