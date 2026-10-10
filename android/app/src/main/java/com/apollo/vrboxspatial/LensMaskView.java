package com.apollo.vrboxspatial;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.View;

/**
 * Optical compositor mask for generic VR Box style lenses.
 *
 * The previous builds rendered each eye as a full rectangular half-screen. Through real lenses
 * that makes the two camera images feel disconnected. This view blacks out the regions a lens
 * cannot comfortably see and leaves one shaped viewport for each eye, closer to a real HMD.
 */
final class LensMaskView extends View {
    private final Paint black = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final long bornMs = SystemClock.elapsedRealtime();

    LensMaskView(Context context) {
        super(context);
        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        black.setColor(Color.BLACK);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(dp(1.2f));
        edge.setColor(0x2affffff);
        hint.setColor(0xc9ffffff);
        hint.setTextAlign(Paint.Align.CENTER);
        hint.setTextSize(dp(11f));
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w < 20 || h < 20) return;

        float eyeW = w * .5f;
        Path mask = new Path();
        mask.setFillType(Path.FillType.EVEN_ODD);
        mask.addRect(0, 0, w, h, Path.Direction.CW);

        Path left = lensPath(0, eyeW, h);
        Path right = lensPath(eyeW, eyeW, h);
        mask.addPath(left);
        mask.addPath(right);
        canvas.drawPath(mask, black);

        // A very subtle outline makes it easier to centre the phone in the headset without
        // becoming visible during normal use.
        canvas.drawPath(left, edge);
        canvas.drawPath(right, edge);

        long age = SystemClock.elapsedRealtime() - bornMs;
        if (age < 9000) {
            float alpha = age < 6500 ? 1f : Math.max(0f, 1f - (age - 6500f) / 2500f);
            hint.setAlpha((int)(205 * alpha));
            String t = "aponte • pinça = selecionar • segure no topo = mover • mão aberta = travar";
            float y = h * .905f;
            canvas.drawText(t, eyeW * .5f, y, hint);
            canvas.drawText(t, eyeW + eyeW * .5f, y, hint);
            postInvalidateOnAnimation();
        }
    }

    private Path lensPath(float eyeStart, float eyeW, float h) {
        // Based on the reference supplied by the user: generous black border, curved top/bottom
        // and a slightly narrower centre-facing edge so the two eyes do not visually collide.
        float l = eyeStart + eyeW * .060f;
        float r = eyeStart + eyeW * .940f;
        float t = h * .105f;
        float b = h * .965f;
        float ww = r - l;
        float hh = b - t;

        Path p = new Path();
        p.moveTo(l + ww*.10f, t + hh*.018f);
        p.cubicTo(l + ww*.30f, t - hh*.010f,
                l + ww*.70f, t - hh*.010f,
                r - ww*.10f, t + hh*.018f);
        p.cubicTo(r - ww*.035f, t + hh*.12f,
                r - ww*.010f, t + hh*.34f,
                r - ww*.012f, t + hh*.53f);
        p.cubicTo(r - ww*.010f, t + hh*.72f,
                r - ww*.035f, b - hh*.12f,
                r - ww*.085f, b - hh*.025f);
        p.cubicTo(l + ww*.70f, b + hh*.010f,
                l + ww*.30f, b + hh*.010f,
                l + ww*.085f, b - hh*.025f);
        p.cubicTo(l + ww*.035f, b - hh*.12f,
                l + ww*.010f, t + hh*.72f,
                l + ww*.012f, t + hh*.53f);
        p.cubicTo(l + ww*.010f, t + hh*.34f,
                l + ww*.035f, t + hh*.12f,
                l + ww*.10f, t + hh*.018f);
        p.close();
        return p;
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
