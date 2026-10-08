package io.github.codeyumx.picofacetracking;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/** A large check box for a CompoundButton: a filled box with a check mark when checked, an empty outline otherwise. */
final class CheckMarkDrawable extends Drawable {
    private static final int OUTLINE = 0xFF9E9E9E;
    private static final int MARK = 0xFFFFFFFF;

    private final int size;
    private final int color;
    private final Paint box = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mark = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private boolean checked;

    CheckMarkDrawable(int sizePx, int color) {
        this.size = sizePx;
        this.color = color;
        mark.setColor(MARK);
        mark.setStyle(Paint.Style.STROKE);
        mark.setStrokeCap(Paint.Cap.ROUND);
        mark.setStrokeJoin(Paint.Join.ROUND);
    }

    @Override
    public int getIntrinsicWidth() {
        return size;
    }

    @Override
    public int getIntrinsicHeight() {
        return size;
    }

    @Override
    public boolean isStateful() {
        return true;
    }

    @Override
    protected boolean onStateChange(int[] state) {
        boolean now = false;
        for (int s : state) {
            if (s == android.R.attr.state_checked)
                now = true;
        }
        if (now == checked)
            return false;
        checked = now;
        invalidateSelf();
        return true;
    }

    @Override
    public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        float width = bounds.width();
        float height = bounds.height();
        float stroke = width / 12f;
        float radius = width / 6f;
        rect.set(bounds);
        rect.inset(stroke / 2, stroke / 2);

        if (!checked) {
            box.setStyle(Paint.Style.STROKE);
            box.setStrokeWidth(stroke);
            box.setColor(OUTLINE);
            canvas.drawRoundRect(rect, radius, radius, box);
            return;
        }

        box.setStyle(Paint.Style.FILL);
        box.setColor(color);
        canvas.drawRoundRect(rect, radius, radius, box);
        mark.setStrokeWidth(stroke * 1.4f);
        path.reset();
        path.moveTo(bounds.left + width * 0.25f, bounds.top + height * 0.52f);
        path.lineTo(bounds.left + width * 0.43f, bounds.top + height * 0.70f);
        path.lineTo(bounds.left + width * 0.76f, bounds.top + height * 0.33f);
        canvas.drawPath(path, mark);
    }

    @Override
    public void setAlpha(int alpha) {
        box.setAlpha(alpha);
        mark.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter filter) {
        box.setColorFilter(filter);
        mark.setColorFilter(filter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
