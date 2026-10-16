package io.github.codeyumx.picofacetracking;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/**
 * The lower face as the wearer has it (their left on the left), moved by the mouth, jaw, cheek and tongue expressions:
 * the jaw opens and shifts, the lips smile, frown, stretch, pucker and roll, the cheeks puff and the tongue comes out.
 */
final class MouthView extends View {
    private static final int SKIN = 0x33C9967A;
    private static final int OUTLINE = 0xFF9E9E9E;
    private static final int LIPS = 0xFFB5545C;
    private static final int INSIDE = 0xFF3A0F12;
    private static final int TONGUE = 0xFFE57373;
    private static final int MISSING = 0xFF616161;

    private static final int JAW_OPEN = Expressions.index("JawOpen");
    private static final int JAW_LEFT = Expressions.index("JawLeft");
    private static final int JAW_RIGHT = Expressions.index("JawRight");
    private static final int MOUTH_CLOSE = Expressions.index("MouthClose");
    private static final int MOUTH_LEFT = Expressions.index("MouthLeft");
    private static final int MOUTH_RIGHT = Expressions.index("MouthRight");
    private static final int SMILE_L = Expressions.index("MouthSmileL");
    private static final int SMILE_R = Expressions.index("MouthSmileR");
    private static final int FROWN_L = Expressions.index("MouthFrownL");
    private static final int FROWN_R = Expressions.index("MouthFrownR");
    private static final int STRETCH_L = Expressions.index("MouthStretchL");
    private static final int STRETCH_R = Expressions.index("MouthStretchR");
    private static final int DIMPLE_L = Expressions.index("MouthDimpleL");
    private static final int DIMPLE_R = Expressions.index("MouthDimpleR");
    private static final int PRESS_L = Expressions.index("MouthPressL");
    private static final int PRESS_R = Expressions.index("MouthPressR");
    private static final int UPPER_UP_L = Expressions.index("MouthUpperUpL");
    private static final int UPPER_UP_R = Expressions.index("MouthUpperUpR");
    private static final int LOWER_DOWN_L = Expressions.index("MouthLowerDownL");
    private static final int LOWER_DOWN_R = Expressions.index("MouthLowerDownR");
    private static final int ROLL_UPPER = Expressions.index("MouthRollUpper");
    private static final int ROLL_LOWER = Expressions.index("MouthRollLower");
    private static final int SHRUG_UPPER = Expressions.index("MouthShrugUpper");
    private static final int SHRUG_LOWER = Expressions.index("MouthShrugLower");
    private static final int PUCKER = Expressions.index("MouthPucker");
    private static final int FUNNEL = Expressions.index("MouthFunnel");
    private static final int CHEEK_PUFF = Expressions.index("CheekPuff");
    private static final int TONGUE_OUT = Expressions.TONGUE_OUT;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF oval = new RectF();
    private final float density;
    private final float[] weights = new float[Expressions.COUNT];
    private boolean seen;

    MouthView(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(2 * density);
        stroke.setColor(OUTLINE);
    }

    void show(boolean seen, float[] weights) {
        this.seen = seen;
        System.arraycopy(weights, 0, this.weights, 0, this.weights.length);
        invalidate();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), Math.round(240 * density));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float height = getHeight() - 8 * density;
        float faceWidth = Math.min(getWidth() * 0.6f, 260 * density);
        float cx = getWidth() / 2f;
        float top = 4 * density;

        // Jaw: the chin drops as the jaw opens and moves sideways with JawLeft and JawRight.
        float jawOpen = w(JAW_OPEN);
        float jawShift = (w(JAW_RIGHT) - w(JAW_LEFT)) * faceWidth * 0.12f;
        float chinX = cx + jawShift;
        float chinY = top + height * (0.82f + 0.16f * jawOpen);
        float side = faceWidth / 2f;
        path.reset();
        path.moveTo(cx - side, top);
        path.cubicTo(cx - side, chinY - height * 0.25f, chinX - side * 0.6f, chinY, chinX, chinY);
        path.cubicTo(chinX + side * 0.6f, chinY, cx + side, chinY - height * 0.25f, cx + side, top);
        fill.setColor(seen ? SKIN : 0x22616161);
        canvas.drawPath(path, fill);
        canvas.drawPath(path, stroke);

        // Cheeks puff out at the sides.
        float puff = w(CHEEK_PUFF);
        if (puff > 0.02f) {
            fill.setColor(0x66C9967A);
            float radius = faceWidth * 0.1f * puff;
            canvas.drawCircle(cx - side * 0.78f, top + height * 0.4f, radius, fill);
            canvas.drawCircle(cx + side * 0.78f, top + height * 0.4f, radius, fill);
        }

        // Mouth: centre follows MouthLeft/MouthRight and part of the jaw's shift.
        float mouthX = cx + jawShift * 0.6f + (w(MOUTH_RIGHT) - w(MOUTH_LEFT)) * faceWidth * 0.12f;
        float mouthY = top + height * (0.42f + 0.06f * jawOpen);
        float baseHalf = faceWidth * 0.22f;
        float narrow = 1f - 0.45f * w(PUCKER) - 0.25f * w(FUNNEL);
        float leftHalf = baseHalf * (narrow + 0.25f * w(STRETCH_L) + 0.12f * w(SMILE_L) + 0.08f * w(DIMPLE_L));
        float rightHalf = baseHalf * (narrow + 0.25f * w(STRETCH_R) + 0.12f * w(SMILE_R) + 0.08f * w(DIMPLE_R));
        float leftX = mouthX - leftHalf;
        float rightX = mouthX + rightHalf;
        // Corners rise with a smile and drop with a frown.
        float leftY = mouthY - (w(SMILE_L) - w(FROWN_L)) * baseHalf * 0.45f;
        float rightY = mouthY - (w(SMILE_R) - w(FROWN_R)) * baseHalf * 0.45f;

        float open = jawOpen * (1f - w(MOUTH_CLOSE)) * height * 0.28f
                + w(FUNNEL) * height * 0.06f + w(PUCKER) * height * 0.02f;
        float upperUp = (w(UPPER_UP_L) + w(UPPER_UP_R)) / 2f;
        float lowerDown = (w(LOWER_DOWN_L) + w(LOWER_DOWN_R)) / 2f;
        float press = (w(PRESS_L) + w(PRESS_R)) / 2f;
        float upperInner = mouthY - open * 0.3f - upperUp * height * 0.06f - w(SHRUG_UPPER) * height * 0.02f;
        float lowerInner = mouthY + open * 0.7f + lowerDown * height * 0.06f - w(SHRUG_LOWER) * height * 0.03f;
        float lipScale = height * 0.05f * (1f + 0.5f * w(PUCKER)) * (1f - 0.4f * press);
        float upperOuter = upperInner - lipScale * (1f - 0.75f * w(ROLL_UPPER));
        float lowerOuter = lowerInner + lipScale * 1.2f * (1f - 0.75f * w(ROLL_LOWER));
        float cornerY = (leftY + rightY) / 2f;

        if (lowerInner - upperInner > density) {
            fill.setColor(INSIDE);
            path.reset();
            path.moveTo(leftX, leftY);
            path.quadTo(mouthX, through(upperInner, cornerY), rightX, rightY);
            path.quadTo(mouthX, through(lowerInner, cornerY), leftX, leftY);
            canvas.drawPath(path, fill);
        }

        fill.setColor(seen ? LIPS : MISSING);
        lip(canvas, leftX, leftY, rightX, rightY, mouthX, through(upperOuter, cornerY), through(upperInner, cornerY));
        lip(canvas, leftX, leftY, rightX, rightY, mouthX, through(lowerInner, cornerY), through(lowerOuter, cornerY));

        float tongue = w(TONGUE_OUT);
        if (tongue > 0.02f) {
            fill.setColor(TONGUE);
            float length = tongue * height * 0.22f;
            float halfWidth = Math.max(baseHalf * 0.45f * narrow, 6 * density);
            oval.set(mouthX - halfWidth, lowerInner - length * 0.3f, mouthX + halfWidth, lowerInner + length);
            canvas.drawOval(oval, fill);
        }
    }

    /** One lip between two curves from corner to corner, given as control points. */
    private void lip(Canvas canvas, float leftX, float leftY, float rightX, float rightY, float midX,
                     float firstControl, float secondControl) {
        path.reset();
        path.moveTo(leftX, leftY);
        path.quadTo(midX, firstControl, rightX, rightY);
        path.quadTo(midX, secondControl, leftX, leftY);
        canvas.drawPath(path, fill);
    }

    /** The control point that makes a quadratic curve between the corners pass through y in its middle. */
    private static float through(float y, float cornerY) {
        return 2f * y - cornerY;
    }

    private float w(int index) {
        return Math.max(0f, Math.min(1f, weights[index]));
    }
}
