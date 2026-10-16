package io.github.codeyumx.picofacetracking;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * A table of read-only sliders, one row per value with its name and number, under group headings. Drawn as one view so
 * sixty rows can follow every frame without layout passes or allocation.
 */
final class ParameterTable extends View {
    private static final int TRACK = 0x55FFFFFF;
    private static final int FILL = 0xFF66BB6A;
    private static final int THUMB = 0xFFFFFFFF;
    private static final int TEXT = 0xFFE0E0E0;
    private static final int MISSING = 0xFF757575;

    private static final class Row {
        final String label;
        /** Index into the values, or -1 for a heading. */
        final int value;
        final float min;
        final float max;

        Row(String label, int value, float min, float max) {
            this.label = label;
            this.value = value;
            this.min = min;
            this.max = max;
        }
    }

    private final List<Row> rows = new ArrayList<>();
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint headingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint numberPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumb = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final char[] number = new char[16];
    private final float density;
    private final float rowHeight;
    private float[] values = new float[0];
    private boolean[] seen = new boolean[0];

    ParameterTable(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        float sp = context.getResources().getDisplayMetrics().scaledDensity;
        rowHeight = 30 * density;
        labelPaint.setColor(TEXT);
        labelPaint.setTextSize(14 * sp);
        headingPaint.setColor(TEXT);
        headingPaint.setTextSize(16 * sp);
        headingPaint.setTypeface(Typeface.DEFAULT_BOLD);
        numberPaint.setColor(TEXT);
        numberPaint.setTextSize(14 * sp);
        numberPaint.setTypeface(Typeface.MONOSPACE);
        numberPaint.setTextAlign(Paint.Align.RIGHT);
        track.setStrokeCap(Paint.Cap.ROUND);
        track.setStrokeWidth(4 * density);
        thumb.setColor(THUMB);
    }

    void addHeading(String label) {
        rows.add(new Row(label, -1, 0, 0));
    }

    /** Adds a row for the next value; a range below zero centres the slider. */
    void addRow(String label, float min, float max) {
        int index = values.length;
        rows.add(new Row(label, index, min, max));
        values = java.util.Arrays.copyOf(values, index + 1);
        seen = java.util.Arrays.copyOf(seen, index + 1);
    }

    /** Sets one value; call invalidate once all are set. */
    void set(int index, float value, boolean valueSeen) {
        values[index] = value;
        seen[index] = valueSeen;
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), Math.round(rows.size() * rowHeight + 4 * density));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float width = getWidth();
        float sliderStart = width * 0.42f;
        float sliderEnd = width * 0.82f;
        float numberEnd = width - 4 * density;
        float thumbRadius = 7 * density;
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            float middle = i * rowHeight + rowHeight / 2f;
            if (row.value < 0) {
                canvas.drawText(row.label, 0, middle + headingPaint.getTextSize() / 2.5f, headingPaint);
                continue;
            }
            canvas.drawText(row.label, 0, middle + labelPaint.getTextSize() / 2.5f, labelPaint);

            float value = values[row.value];
            boolean valueSeen = seen[row.value];
            float zero = position(row, Math.max(row.min, Math.min(row.max, 0f)), sliderStart, sliderEnd);
            float at = position(row, value, sliderStart, sliderEnd);
            track.setColor(TRACK);
            canvas.drawLine(sliderStart, middle, sliderEnd, middle, track);
            track.setColor(valueSeen ? FILL : MISSING);
            canvas.drawLine(zero, middle, at, middle, track);
            thumb.setColor(valueSeen ? THUMB : MISSING);
            canvas.drawCircle(at, middle, thumbRadius, thumb);

            int length = format(value);
            numberPaint.setColor(valueSeen ? TEXT : MISSING);
            canvas.drawText(number, 0, length, numberEnd, middle + numberPaint.getTextSize() / 2.5f, numberPaint);
        }
    }

    private static float position(Row row, float value, float start, float end) {
        float fraction = (value - row.min) / (row.max - row.min);
        return start + Math.max(0f, Math.min(1f, fraction)) * (end - start);
    }

    /** The value with two decimals into number, without allocating; returns the length. */
    private int format(float value) {
        if (Float.isNaN(value)) {
            "NaN".getChars(0, 3, number, 0);
            return 3;
        }
        long hundredths = Math.round(Math.abs((double) value) * 100);
        int length = 0;
        if (value < 0 && hundredths != 0)
            number[length++] = '-';
        long whole = Math.min(hundredths / 100, 9_999_999L);
        int start = length;
        do {
            number[length++] = (char) ('0' + whole % 10);
            whole /= 10;
        } while (whole > 0);
        for (int a = start, b = length - 1; a < b; a++, b--) {
            char swap = number[a];
            number[a] = number[b];
            number[b] = swap;
        }
        number[length++] = '.';
        number[length++] = (char) ('0' + hundredths / 10 % 10);
        number[length++] = (char) ('0' + hundredths % 10);
        return length;
    }
}
