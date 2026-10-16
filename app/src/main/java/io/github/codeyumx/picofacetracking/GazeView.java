package io.github.codeyumx.picofacetracking;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/**
 * A square with a dot where the eyes look, as seen by the wearer: looking right moves the dot right, looking up moves
 * it up. The big dot is what both eyes send to VRCFaceTracking on average, the small dots each eye.
 */
final class GazeView extends View {
    /** Gaze vector x or y at the edge of the square; a unit vector's 0.5 is 30 degrees from straight ahead. */
    static final float RANGE = 0.5f;
    static final int LEFT_COLOR = 0xFF42A5F5;
    static final int RIGHT_COLOR = 0xFFFFA726;
    private static final int DOT_COLOR = 0xFF66BB6A;
    private static final int MISSING_COLOR = 0xFF757575;

    private final Paint frame = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private boolean seen;
    private float leftX;
    private float leftY;
    private float rightX;
    private float rightY;

    GazeView(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        frame.setStyle(Paint.Style.STROKE);
        frame.setStrokeWidth(2 * density);
        frame.setColor(0xFF9E9E9E);
        grid.setStyle(Paint.Style.STROKE);
        grid.setStrokeWidth(density);
        grid.setColor(0x55FFFFFF);
    }

    void show(boolean seen, float leftX, float leftY, float rightX, float rightY) {
        this.seen = seen;
        this.leftX = leftX;
        this.leftY = leftY;
        this.rightX = rightX;
        this.rightY = rightY;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        int side = Math.min(width, Math.round(260 * density));
        setMeasuredDimension(width, side);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float side = getHeight() - 2 * density;
        float left = (getWidth() - side) / 2f;
        float top = density;
        float cx = left + side / 2f;
        float cy = top + side / 2f;
        float half = side / 2f;

        canvas.drawLine(cx, top, cx, top + side, grid);
        canvas.drawLine(left, cy, left + side, cy, grid);
        canvas.drawCircle(cx, cy, half / 2f, grid);
        canvas.drawRect(left, top, left + side, top + side, frame);

        float dotRadius = 6 * density;
        dot.setColor(seen ? LEFT_COLOR : MISSING_COLOR);
        canvas.drawCircle(x(cx, half, leftX), y(cy, half, leftY), dotRadius, dot);
        dot.setColor(seen ? RIGHT_COLOR : MISSING_COLOR);
        canvas.drawCircle(x(cx, half, rightX), y(cy, half, rightY), dotRadius, dot);
        dot.setColor(seen ? DOT_COLOR : MISSING_COLOR);
        canvas.drawCircle(x(cx, half, (leftX + rightX) / 2f), y(cy, half, (leftY + rightY) / 2f), 2 * dotRadius, dot);
    }

    private static float x(float cx, float half, float gaze) {
        return cx + clamp(gaze / RANGE) * (half - 1);
    }

    /** Up is positive in the gaze vector and down on screen. */
    private static float y(float cy, float half, float gaze) {
        return cy - clamp(gaze / RANGE) * (half - 1);
    }

    private static float clamp(float value) {
        return Math.max(-1f, Math.min(1f, value));
    }
}
