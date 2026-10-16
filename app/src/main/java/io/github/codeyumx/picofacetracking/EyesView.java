package io.github.codeyumx.picofacetracking;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/**
 * Two eyes as the wearer has them (left on the left): the irises follow the gaze, and the eyelids close as the
 * openness that VRCFaceTracking receives drops, so a blink shows as the lids meeting.
 */
final class EyesView extends View {
    private static final int SCLERA = 0xFFEEEEEE;
    private static final int IRIS = 0xFF4F7FA3;
    private static final int PUPIL = 0xFF101010;
    private static final int LID = 0xFFC9967A;
    private static final int LASHES = 0xFF2B1D16;
    private static final int OUTLINE = 0xFF9E9E9E;
    private static final int MISSING = 0xFF616161;
    /** Iris diameter in millimetres, to size the pupil. */
    private static final float IRIS_MM = 12f;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path almond = new Path();
    private final Path lid = new Path();
    private final char[] text = new char[64];
    /** Label parts, read once so drawing does not allocate: "Left ", "Right ", "not seen", "% open". */
    private final String[] sides;
    private final String notSeen;
    private final String openSuffix;
    private final float density;

    private boolean seen;
    private final float[] gazeX = new float[2];
    private final float[] gazeY = new float[2];
    private final float[] openness = {1f, 1f};
    private final float[] pupilMm = new float[2];

    EyesView(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        stroke.setStyle(Paint.Style.STROKE);
        label.setColor(0xFFE0E0E0);
        label.setTextAlign(Paint.Align.CENTER);
        label.setTextSize(14 * context.getResources().getDisplayMetrics().scaledDensity);
        sides = new String[]{context.getString(R.string.eye_left), context.getString(R.string.eye_right)};
        notSeen = context.getString(R.string.eye_not_seen);
        openSuffix = context.getString(R.string.eye_open_suffix);
    }

    /** Index 0 is the left eye, 1 the right eye. */
    void show(boolean seen, float leftX, float leftY, float leftOpen, float leftPupil,
              float rightX, float rightY, float rightOpen, float rightPupil) {
        this.seen = seen;
        gazeX[0] = leftX;
        gazeY[0] = leftY;
        openness[0] = leftOpen;
        pupilMm[0] = leftPupil;
        gazeX[1] = rightX;
        gazeY[1] = rightY;
        openness[1] = rightOpen;
        pupilMm[1] = rightPupil;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), Math.round(150 * density));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float width = getWidth();
        float labelSpace = 28 * density;
        float rx = Math.min(width * 0.2f, 110 * density);
        float ry = Math.min(rx * 0.55f, (getHeight() - labelSpace) / 2f - 4 * density);
        float cy = 4 * density + ry;
        for (int eye = 0; eye < 2; eye++) {
            float cx = width * (eye == 0 ? 0.28f : 0.72f);
            drawEye(canvas, eye, cx, cy, rx, ry);
            int length = describe(eye);
            canvas.drawText(text, 0, length, cx, cy + ry + labelSpace - 6 * density, label);
        }
    }

    private void drawEye(Canvas canvas, int eye, float cx, float cy, float rx, float ry) {
        // A quadratic curve peaks halfway to its control point, so these make an almond ry above and below centre.
        almond.reset();
        almond.moveTo(cx - rx, cy);
        almond.quadTo(cx, cy - 2 * ry, cx + rx, cy);
        almond.quadTo(cx, cy + 2 * ry, cx - rx, cy);
        almond.close();

        canvas.save();
        canvas.clipPath(almond);
        fill.setColor(seen ? SCLERA : MISSING);
        canvas.drawRect(cx - rx, cy - ry, cx + rx, cy + ry, fill);

        float iris = ry * 0.8f;
        float ix = cx + clamp(gazeX[eye] / GazeView.RANGE, -1f, 1f) * (rx - iris) * 0.9f;
        float iy = cy - clamp(gazeY[eye] / GazeView.RANGE, -1f, 1f) * ry * 0.45f;
        if (seen) {
            fill.setColor(IRIS);
            canvas.drawCircle(ix, iy, iris, fill);
            float pupil = pupilMm[eye] > 0 ? clamp(pupilMm[eye] / IRIS_MM, 0.15f, 0.85f) : 0.4f;
            fill.setColor(PUPIL);
            canvas.drawCircle(ix, iy, iris * pupil, fill);
        }

        // The lids close over 70 % (upper) and 30 % (lower) of the opening and meet below the centre.
        float closed = 1f - clamp(openness[eye], 0f, 1f);
        float sag = ry * 0.3f;
        float upper = cy - ry + closed * 1.4f * ry;
        float lower = cy + ry - closed * 0.6f * ry;
        float left = cx - rx - density;
        float right = cx + rx + density;
        fill.setColor(LID);
        lid.reset();
        lid.moveTo(left, cy - ry - density);
        lid.lineTo(right, cy - ry - density);
        lid.lineTo(right, upper);
        lid.quadTo(cx, upper + sag, left, upper);
        lid.close();
        canvas.drawPath(lid, fill);
        lid.reset();
        lid.moveTo(left, lower);
        lid.quadTo(cx, lower + sag, right, lower);
        lid.lineTo(right, cy + ry + density);
        lid.lineTo(left, cy + ry + density);
        lid.close();
        canvas.drawPath(lid, fill);

        // Lashes along the upper lid's edge.
        stroke.setColor(LASHES);
        stroke.setStrokeWidth(3 * density);
        lid.reset();
        lid.moveTo(left, upper);
        lid.quadTo(cx, upper + sag, right, upper);
        canvas.drawPath(lid, stroke);
        canvas.restore();

        stroke.setColor(OUTLINE);
        stroke.setStrokeWidth(2 * density);
        canvas.drawPath(almond, stroke);
    }

    /** "Left 87% open" into text, without allocating; returns the length. */
    private int describe(int eye) {
        int length = append(sides[eye], 0);
        if (!seen)
            return append(notSeen, length);
        int percent = Math.round(clamp(openness[eye], 0f, 1f) * 100f);
        if (percent >= 100)
            text[length++] = '1';
        if (percent >= 10)
            text[length++] = (char) ('0' + percent / 10 % 10);
        text[length++] = (char) ('0' + percent % 10);
        return append(openSuffix, length);
    }

    private int append(String value, int at) {
        value.getChars(0, value.length(), text, at);
        return at + value.length();
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
