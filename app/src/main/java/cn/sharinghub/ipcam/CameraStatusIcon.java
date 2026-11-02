package cn.sharinghub.ipcam;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * Small camera glyph drawn with Canvas (no VectorDrawable, which API 17 cannot inflate).
 * Colour encodes state: grey = camera released, green = capturing, red = open failed.
 */
public class CameraStatusIcon extends View {
    public static final int OFF = 0, ON = 1, ERROR = 2;
    private static final int COLOR_OFF = 0xFF9E9E9E;
    private static final int COLOR_ON = 0xFF43A047;
    private static final int COLOR_ERROR = 0xFFE53935;
    /** The lens/dot punch-out must match whatever surface the icon sits on, in either theme. */
    private final int cardColor = ThemeUtil.color(getContext(), R.attr.colorCard);

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF body = new RectF();
    private int state;

    public CameraStatusIcon(Context context, AttributeSet attrs) {
        super(context, attrs);
        paint.setStyle(Paint.Style.FILL);
    }

    public void setState(int state) {
        if (this.state == state) return;
        this.state = state;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        int color = state == ON ? COLOR_ON : state == ERROR ? COLOR_ERROR : COLOR_OFF;

        float top = h * 0.30f, bottom = h * 0.88f, pad = w * 0.03f;
        float radius = (bottom - top) * 0.20f;
        body.set(pad, top, w - pad, bottom);
        paint.setColor(color);
        path.reset();
        path.addRoundRect(body, radius, radius, Path.Direction.CW);
        // Viewfinder hump, overlapping the body so both read as one silhouette.
        path.addRect(w * 0.30f, h * 0.15f, w * 0.62f, top + (bottom - top) * 0.25f, Path.Direction.CW);
        canvas.drawPath(path, paint);

        // Lens ring punched out in the card colour, then the darker pupil.
        float cx = w * 0.5f, cy = (top + bottom) / 2f;
        float outer = (bottom - top) * 0.28f;
        paint.setColor(cardColor);
        canvas.drawCircle(cx, cy, outer, paint);
        paint.setColor(color);
        canvas.drawCircle(cx, cy, outer * 0.58f, paint);

        // Indicator dot on the body's right edge.
        paint.setColor(cardColor);
        canvas.drawCircle(w * 0.855f, top + (bottom - top) * 0.22f, (bottom - top) * 0.075f, paint);
    }
}
