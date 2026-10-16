package io.github.codeyumx.picofacetracking;

import android.view.Choreographer;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * The Test tab: shows what the headset tracks right now, so the wearer can check that it sees their eyes and face.
 * Runs the headset's tracking only while the tab is shown (start/stop), with or without a PC.
 */
final class TestTab {
    private static final int EYE_ROWS = 10;

    private final ScrollView view;
    private final TextView status;
    private final GazeView gaze;
    private final EyesView eyes;
    private final MouthView mouth;
    private final ParameterTable table;
    /** The expression shown in each table row after the eye rows. */
    private final int[] expressionRows = new int[Expressions.COUNT];
    private final int laughingRow;
    private final TrackingFrame frame = new TrackingFrame();
    private final Choreographer.FrameCallback tick = this::tick;

    private final MainActivity activity;
    private TrackingPreview preview;
    private long seenFrame;
    private Text shownStatus;
    private int shownSeen = -1;

    TestTab(MainActivity activity) {
        this.activity = activity;
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = activity.dp(24);
        content.setPadding(padding, activity.dp(8), padding, padding);

        status = activity.text("", 16);
        content.addView(status);
        content.addView(activity.text(activity.getString(R.string.test_note), 15));

        content.addView(activity.heading(R.string.test_section_eyes));
        content.addView(activity.text(activity.getString(R.string.test_eyes_help), 15));
        gaze = new GazeView(activity);
        content.addView(gaze);
        eyes = new EyesView(activity);
        LinearLayout.LayoutParams eyesLayout = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        eyesLayout.setMargins(0, activity.dp(16), 0, 0);
        content.addView(eyes, eyesLayout);

        content.addView(activity.heading(R.string.test_section_mouth));
        TextView accuracy = activity.text(activity.getString(R.string.test_accuracy), 15);
        accuracy.setTextColor(MainActivity.WARNING_COLOR);
        content.addView(accuracy);
        content.addView(activity.text(activity.getString(R.string.test_mouth_help), 15));
        mouth = new MouthView(activity);
        content.addView(mouth);

        content.addView(activity.heading(R.string.test_section_parameters));
        content.addView(activity.text(activity.getString(R.string.test_parameters_help), 15));
        table = new ParameterTable(activity);
        table.addHeading(activity.getString(R.string.test_group_eye));
        table.addRow(activity.getString(R.string.test_left_gaze_x), -1f, 1f);
        table.addRow(activity.getString(R.string.test_left_gaze_y), -1f, 1f);
        table.addRow(activity.getString(R.string.test_right_gaze_x), -1f, 1f);
        table.addRow(activity.getString(R.string.test_right_gaze_y), -1f, 1f);
        table.addRow(activity.getString(R.string.test_combined_gaze_x), -1f, 1f);
        table.addRow(activity.getString(R.string.test_combined_gaze_y), -1f, 1f);
        table.addRow(activity.getString(R.string.test_left_openness), 0f, 1f);
        table.addRow(activity.getString(R.string.test_right_openness), 0f, 1f);
        table.addRow(activity.getString(R.string.test_left_pupil), 0f, 8f);
        table.addRow(activity.getString(R.string.test_right_pupil), 0f, 8f);
        // Expression names stay as VRCFaceTracking's parameter names; only the group headings are translated.
        String[] groupHeadings = activity.getResources().getStringArray(R.array.test_expression_groups);
        int row = 0;
        for (int group = 0; group < Expressions.GROUPS.length; group++) {
            table.addHeading(groupHeadings[group]);
            for (int i = 0; i < Expressions.COUNT; i++) {
                if (Expressions.group(i).equals(Expressions.GROUPS[group])) {
                    table.addRow(Expressions.label(i), 0f, 1f);
                    expressionRows[row++] = i;
                }
            }
        }
        table.addHeading(activity.getString(R.string.test_group_other));
        table.addRow(activity.getString(R.string.test_laughing), 0f, 1f);
        laughingRow = EYE_ROWS + Expressions.COUNT;
        LinearLayout.LayoutParams tableLayout = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tableLayout.setMargins(0, activity.dp(8), 0, 0);
        content.addView(table, tableLayout);

        view = new ScrollView(activity);
        view.addView(content);
    }

    View view() {
        return view;
    }

    void start() {
        if (preview != null)
            return;
        preview = TrackingPreview.start();
        seenFrame = 0;
        shownStatus = null;
        shownSeen = -1;
        status.setText(R.string.test_starting);
        Choreographer.getInstance().postFrameCallback(tick);
    }

    void stop() {
        if (preview == null)
            return;
        preview.stop();
        preview = null;
        Choreographer.getInstance().removeFrameCallback(tick);
    }

    private void tick(long frameTimeNanos) {
        if (preview == null)
            return;
        Choreographer.getInstance().postFrameCallback(tick);

        Text waiting = preview.status();
        int seen = (frame.eyesSeen ? 1 : 0) | (frame.faceSeen ? 2 : 0);
        // setText lays the screen out again, so only on a change.
        if (waiting != null ? !waiting.equals(shownStatus) : shownStatus != null || seen != shownSeen) {
            shownStatus = waiting;
            shownSeen = seen;
            status.setText(waiting != null ? waiting.get(activity)
                    : activity.getString(R.string.test_live,
                    activity.getString(frame.eyesSeen ? R.string.test_seen : R.string.test_not_seen),
                    activity.getString(frame.faceSeen ? R.string.test_seen : R.string.test_not_seen)));
        }

        long newest = preview.copyLatest(frame, seenFrame);
        if (newest == seenFrame)
            return;
        seenFrame = newest;
        show();
    }

    private void show() {
        boolean eyesSeen = frame.eyesSeen;
        gaze.show(eyesSeen, frame.shownLeftGazeX(), frame.shownLeftGazeY(), frame.shownRightGazeX(), frame.shownRightGazeY());
        eyes.show(eyesSeen,
                frame.shownLeftGazeX(), frame.shownLeftGazeY(), frame.shownLeftOpenness(), frame.leftPupilMm,
                frame.shownRightGazeX(), frame.shownRightGazeY(), frame.shownRightOpenness(), frame.rightPupilMm);
        mouth.show(frame.faceSeen, frame.weights);

        table.set(0, frame.leftGazeX, eyesSeen);
        table.set(1, frame.leftGazeY, eyesSeen);
        table.set(2, frame.rightGazeX, eyesSeen);
        table.set(3, frame.rightGazeY, eyesSeen);
        table.set(4, frame.combinedGazeX, eyesSeen);
        table.set(5, frame.combinedGazeY, eyesSeen);
        table.set(6, frame.leftOpenness, eyesSeen);
        table.set(7, frame.rightOpenness, eyesSeen);
        table.set(8, frame.leftPupilMm, eyesSeen);
        table.set(9, frame.rightPupilMm, eyesSeen);
        for (int row = 0; row < Expressions.COUNT; row++) {
            int expression = expressionRows[row];
            table.set(EYE_ROWS + row, frame.weights[expression],
                    Expressions.isEyePart(expression) ? eyesSeen : frame.faceSeen);
        }
        table.set(laughingRow, frame.laughing, frame.faceSeen);
        table.invalidate();
    }
}
