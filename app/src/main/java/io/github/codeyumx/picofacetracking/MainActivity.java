package io.github.codeyumx.picofacetracking;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputFilter;
import android.text.InputType;
import android.text.method.DigitsKeyListener;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Two tabs: Settings (tracking on/off, options, adjustments, connection) and Test (what the headset tracks now). */
public final class MainActivity extends Activity {
    private static final String[] TRACKING_PERMISSIONS = {
            "com.picovr.permission.EYE_TRACKING",
            "com.picovr.permission.FACE_TRACKING",
    };
    private static final long REFRESH_MS = 500;
    private static final int ON_COLOR = 0xFF2E7D32;
    static final int WARNING_COLOR = 0xFFE65100;
    private static final int IMPORT_LICENCE = 1;
    /** Licence files are a few hundred bytes. */
    private static final int MAX_LICENCE_BYTES = 4096;
    private static final String SHOWING_TEST = "showing_test";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            showState();
            handler.postDelayed(this, REFRESH_MS);
        }
    };

    private Prefs prefs;
    private CheckBox tracking;
    private EditText pcAddress;
    private CheckBox startAtBoot;
    private CheckBox pauseForOtherApps;
    private TextView status;
    private TextView pairingInfo;
    private Button removeKey;
    private Button pairWithPc;
    private LinearLayout pairingPanel;
    private TextView pairingStatus;
    private RadioGroup pairingPcs;
    private EditText pairingCode;
    private Button pairingSubmit;
    private Button pairingCancel;
    /** The PCs shown in pairingPcs; a radio button's id is its index + 1. */
    private List<InetSocketAddress> shownPcs = new ArrayList<>();
    private TextView licenceInfo;
    private TextView headsetToggles;
    private boolean showingState;
    private TestTab testTab;
    private View settingsPage;
    private TextView settingsTabButton;
    private TextView testTabButton;
    private boolean showingTest;
    private boolean resumed;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(Language.apply(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(24);
        content.setPadding(padding, dp(8), padding, padding);
        addLanguages(content);

        content.addView(heading(R.string.section_tracking));
        tracking = checkCard(R.string.tracking_switch, 22, 40, 84);
        tracking.setOnCheckedChangeListener((button, on) -> {
            if (showingState)
                return;
            if (on)
                turnOn();
            else
                turnOff();
        });
        LinearLayout.LayoutParams trackingLayout = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        trackingLayout.setMargins(0, dp(8), 0, dp(8));
        content.addView(tracking, trackingLayout);
        addParts(content);

        pauseForOtherApps = new CheckBox(this);
        pauseForOtherApps.setText(R.string.pause_for_other_apps);
        pauseForOtherApps.setChecked(prefs.pauseForOtherApps());
        pauseForOtherApps.setOnCheckedChangeListener((button, checked) -> {
            prefs.setPauseForOtherApps(checked);
            // A running tracker keeps the setting it started with.
            if (prefs.enabled()) {
                TrackingService.stop(this);
                TrackingService.start(this);
            }
        });
        content.addView(pauseForOtherApps);

        status = text("", 15);
        status.setPadding(0, dp(8), 0, 0);
        content.addView(status);

        addAdjustments(content);

        content.addView(heading(R.string.section_connection));
        pairingInfo = text("", 15);
        content.addView(pairingInfo);
        removeKey = new Button(this);
        removeKey.setText(R.string.remove_pairing_key);
        removeKey.setOnClickListener(view -> {
            prefs.setPairingKey("");
            if (prefs.enabled()) {
                TrackingService.stop(this);
                TrackingService.start(this);
            }
            showState();
        });
        content.addView(removeKey);
        addPairing(content);
        content.addView(text(getString(R.string.pc_address_help), 15));
        pcAddress = new EditText(this);
        pcAddress.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        pcAddress.setKeyListener(DigitsKeyListener.getInstance("0123456789."));
        pcAddress.setHint(R.string.pc_address_hint);
        pcAddress.setText(prefs.pcAddress());
        content.addView(pcAddress);

        startAtBoot = new CheckBox(this);
        startAtBoot.setText(R.string.start_at_boot);
        startAtBoot.setChecked(prefs.startAtBoot());
        startAtBoot.setOnCheckedChangeListener((button, checked) -> prefs.setStartAtBoot(checked));
        content.addView(startAtBoot);

        content.addView(heading(R.string.section_pc));
        content.addView(text(getString(R.string.pc_help), 15));
        content.addView(heading(R.string.section_no_connection));
        content.addView(text(getString(R.string.no_connection_help), 15));
        content.addView(heading(R.string.section_after_restart));
        content.addView(text(getString(R.string.after_restart_help), 15));

        // Closing the panel normally leaves tracking running; this button is for the rare full stop. The tracking
        // switch stays on, so tracking comes back the next time the app opens or the headset starts.
        Button close = new Button(this);
        close.setText(R.string.stop_and_close);
        close.setOnClickListener(view -> {
            TrackingService.stop(this);
            finishAndRemoveTask();
        });
        LinearLayout.LayoutParams closeLayout = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        closeLayout.setMargins(0, dp(20), 0, 0);
        content.addView(close, closeLayout);
        content.addView(text(getString(R.string.stop_and_close_help), 15));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        settingsPage = scroll;
        testTab = new TestTab(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(padding, padding, padding, dp(8));
        TextView title = text(getString(R.string.app_name), 24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        settingsTabButton = tabButton(getString(R.string.tab_settings));
        settingsTabButton.setOnClickListener(view -> showTab(false));
        header.addView(settingsTabButton);
        testTabButton = tabButton(getString(R.string.tab_test));
        testTabButton.setOnClickListener(view -> showTab(true));
        LinearLayout.LayoutParams testLayout = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        testLayout.setMargins(dp(8), 0, 0, 0);
        header.addView(testTabButton, testLayout);
        root.addView(header);

        FrameLayout pages = new FrameLayout(this);
        pages.addView(settingsPage);
        pages.addView(testTab.view());
        root.addView(pages, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        showTab(savedInstanceState != null && savedInstanceState.getBoolean(SHOWING_TEST));

        requestMissingPermissions();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // PICO OS may stop sideloaded apps from starting at boot; opening this screen brings tracking back.
        if (prefs.enabled())
            TrackingService.start(this);
        handler.post(refresh);
        resumed = true;
        if (showingTest)
            testTab.start();
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refresh);
        resumed = false;
        testTab.stop();
        if (!prefs.enabled())
            prefs.setPcAddress(pcAddress.getText().toString().trim());
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putBoolean(SHOWING_TEST, showingTest);
    }

    /** The Test tab runs the headset's tracking only while it is shown. */
    private void showTab(boolean test) {
        showingTest = test;
        settingsPage.setVisibility(test ? View.GONE : View.VISIBLE);
        testTab.view().setVisibility(test ? View.VISIBLE : View.GONE);
        settingsTabButton.setSelected(!test);
        testTabButton.setSelected(test);
        if (test && resumed)
            testTab.start();
        else
            testTab.stop();
    }

    private TextView tabButton(String label) {
        TextView tab = text(label, 18);
        tab.setTypeface(Typeface.DEFAULT_BOLD);
        tab.setPadding(dp(20), dp(10), dp(20), dp(10));
        tab.setClickable(true);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_selected}, card(0x332E7D32, ON_COLOR));
        states.addState(new int[0], card(0x14808080, 0x66808080));
        tab.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), states, null));
        return tab;
    }

    /** The paid adjustments when unlocked; otherwise what they are and how to unlock them. */
    private void addAdjustments(LinearLayout content) {
        content.addView(heading(R.string.section_adjustments));
        licenceInfo = text("", 15);
        content.addView(licenceInfo);

        String features = getString(R.string.adjustments_features);
        if (!Paid.inBuild()) {
            licenceInfo.setText(getString(R.string.adjustments_not_in_build, features));
            return;
        }

        Licence licence = Licence.saved(prefs);
        if (licence == null)
            licenceInfo.setText(getString(R.string.adjustments_locked, features));
        else if (licence.expired())
            licenceInfo.setText(getString(R.string.licence_ended, licence.describe(this)));
        else
            licenceInfo.setText(getString(R.string.licence_unlocked, licence.describe(this)));

        Button importLicence = new Button(this);
        importLicence.setText(licence == null ? R.string.import_licence : R.string.import_another_licence);
        importLicence.setOnClickListener(view -> startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), IMPORT_LICENCE));
        content.addView(importLicence);

        PaidFeatures paid = Paid.unlocked(prefs);
        if (paid != null)
            paid.addSettings(this, content);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != IMPORT_LICENCE || resultCode != RESULT_OK || data == null || data.getData() == null)
            return;

        String text;
        try (InputStream in = getContentResolver().openInputStream(data.getData())) {
            if (in == null) {
                licenceInfo.setText(getString(R.string.licence_unreadable, getString(R.string.licence_file_empty)));
                return;
            }
            byte[] buffer = new byte[MAX_LICENCE_BYTES];
            int length = 0;
            int read;
            while (length < buffer.length && (read = in.read(buffer, length, buffer.length - length)) > 0)
                length += read;
            text = new String(buffer, 0, length, StandardCharsets.UTF_8);
        } catch (IOException | SecurityException e) {
            licenceInfo.setText(getString(R.string.licence_unreadable, e.getMessage()));
            return;
        }

        try {
            Licence.parse(text);
        } catch (Licence.Invalid e) {
            licenceInfo.setText(getString(R.string.licence_not_imported, getString(e.reason)));
            return;
        }
        prefs.setLicence(text);
        // A running tracker keeps the adjustments it started with.
        if (prefs.enabled()) {
            TrackingService.stop(this);
            TrackingService.start(this);
        }
        recreate();
    }

    /** Pairing by code: the code from VRCFaceTracking's Output page is typed here. */
    private void addPairing(LinearLayout content) {
        pairWithPc = new Button(this);
        pairWithPc.setOnClickListener(view -> {
            PairingSession.start(this);
            pairingCode.setText("");
            showState();
        });
        content.addView(pairWithPc);

        pairingPanel = new LinearLayout(this);
        pairingPanel.setOrientation(LinearLayout.VERTICAL);
        pairingStatus = text("", 15);
        pairingPanel.addView(pairingStatus);
        pairingPcs = new RadioGroup(this);
        pairingPanel.addView(pairingPcs);
        pairingCode = new EditText(this);
        pairingCode.setInputType(InputType.TYPE_CLASS_NUMBER);
        pairingCode.setKeyListener(DigitsKeyListener.getInstance("0123456789 "));
        pairingCode.setFilters(new InputFilter[]{new InputFilter.LengthFilter(7)});
        pairingCode.setHint(R.string.pairing_code_hint);
        pairingPanel.addView(pairingCode);
        pairingSubmit = new Button(this);
        pairingSubmit.setText(R.string.pair);
        pairingSubmit.setOnClickListener(view -> {
            PairingSession session = PairingSession.current();
            if (session == null || !session.running())
                return;
            int index = pairingPcs.getCheckedRadioButtonId() - 1;
            InetSocketAddress pc = index >= 0 && index < shownPcs.size() ? shownPcs.get(index) : null;
            Text error = session.submit(pc, pairingCode.getText().toString());
            if (error != null)
                pairingCode.setError(error.get(this));
            else
                pairingCode.setText("");
            showState();
        });
        pairingPanel.addView(pairingSubmit);
        pairingCancel = new Button(this);
        pairingCancel.setText(R.string.cancel_pairing);
        pairingCancel.setOnClickListener(view -> {
            PairingSession session = PairingSession.current();
            if (session != null)
                session.cancel();
            showState();
        });
        pairingPanel.addView(pairingCancel);
        content.addView(pairingPanel);
    }

    private void showPairing(boolean keyed) {
        PairingSession session = PairingSession.current();
        boolean pairing = session != null && session.running();
        pairWithPc.setText(keyed ? R.string.pair_again : R.string.pair_by_code);
        pairWithPc.setVisibility(pairing ? View.GONE : View.VISIBLE);
        removeKey.setVisibility(keyed && !pairing ? View.VISIBLE : View.GONE);
        pairingPanel.setVisibility(session != null ? View.VISIBLE : View.GONE);
        if (session == null)
            return;

        pairingStatus.setText(session.status().get(this));
        List<String> labels = new ArrayList<>();
        List<InetSocketAddress> pcs = session.pcs(labels);
        if (!pcs.equals(shownPcs)) {
            InetSocketAddress checked = pairingPcs.getCheckedRadioButtonId() > 0 && pairingPcs.getCheckedRadioButtonId() <= shownPcs.size()
                    ? shownPcs.get(pairingPcs.getCheckedRadioButtonId() - 1) : null;
            pairingPcs.removeAllViews();
            for (int i = 0; i < pcs.size(); i++) {
                RadioButton button = new RadioButton(this);
                button.setId(i + 1);
                button.setText(labels.get(i));
                pairingPcs.addView(button);
            }
            shownPcs = pcs;
            int keep = checked != null ? pcs.indexOf(checked) : -1;
            if (keep >= 0)
                pairingPcs.check(keep + 1);
            else if (pcs.size() == 1)
                pairingPcs.check(1);
        }
        boolean canType = pairing && !pcs.isEmpty();
        pairingPcs.setVisibility(canType ? View.VISIBLE : View.GONE);
        pairingCode.setVisibility(canType ? View.VISIBLE : View.GONE);
        pairingSubmit.setVisibility(canType ? View.VISIBLE : View.GONE);
        pairingCancel.setVisibility(pairing ? View.VISIBLE : View.GONE);
    }

    private void turnOn() {
        // Empty: pair with the first PC on the network that runs VRCFaceTracking.
        String typed = pcAddress.getText().toString().trim();
        Inet4Address pc = Prefs.parseIpv4(typed);
        if (pc == null && !typed.isEmpty()) {
            pcAddress.setError(getString(R.string.pc_address_invalid));
            showState();
            return;
        }
        String address = pc != null ? pc.getHostAddress() : "";
        pcAddress.setText(address);
        prefs.setPcAddress(address);
        prefs.setEnabled(true);
        TrackingService.start(this);
        showState();
    }

    private void turnOff() {
        prefs.setEnabled(false);
        TrackingService.stop(this);
        showState();
    }

    private void showState() {
        showingState = true;
        boolean on = prefs.enabled();
        tracking.setChecked(on);
        pcAddress.setEnabled(!on);
        // The adb control receiver can change the address while this screen is open.
        if (on && !pcAddress.getText().toString().equals(prefs.pcAddress()))
            pcAddress.setText(prefs.pcAddress());
        status.setText(on || TrackingService.failed() ? TrackingService.status().get(this) : getString(R.string.status_off));
        boolean keyed = !prefs.pairingKey().isEmpty();
        pairingInfo.setText(keyed ? R.string.paired_by_key : R.string.paired_by_address);
        showPairing(keyed);
        showHeadsetToggles();
        showingState = false;
    }

    /** PICO's own Settings → LAB switches (Settings.System key_et_enable / key_ft_enable); off stops that part entirely. */
    private void showHeadsetToggles() {
        // A missing key counts as on, so headsets without these switches show no warning.
        boolean eyeOn = Settings.System.getInt(getContentResolver(), "key_et_enable", 1) != 0;
        boolean faceOn = Settings.System.getInt(getContentResolver(), "key_ft_enable", 1) != 0;
        headsetToggles.setVisibility(eyeOn && faceOn ? View.GONE : View.VISIBLE);
        headsetToggles.setText(!eyeOn && !faceOn ? R.string.headset_both_off
                : !eyeOn ? R.string.headset_eye_off : R.string.headset_face_off);
    }

    private void requestMissingPermissions() {
        List<String> missing = new ArrayList<>();
        for (String permission : TRACKING_PERMISSIONS) {
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED)
                missing.add(permission);
        }
        if (!missing.isEmpty())
            requestPermissions(missing.toArray(new String[0]), 0);
    }

    /** A flag button for each Language; the one shown is highlighted, and picking another redraws the screen in it. */
    private void addLanguages(LinearLayout content) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Language shown = Language.shown(this);
        for (Language language : Language.values()) {
            boolean selected = language == shown;
            TextView button = text(getString(language.label), 16);
            button.setTypeface(selected ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            button.setPadding(dp(14), dp(8), dp(14), dp(8));
            button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF),
                    selected ? card(0x332E7D32, ON_COLOR) : card(0x14808080, 0x66808080), null));
            button.setSelected(selected);
            button.setOnClickListener(view -> {
                if (language == Language.shown(this))
                    return;
                prefs.setLanguage(language.code);
                recreate();
            });
            LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            layout.setMargins(0, 0, dp(8), 0);
            row.addView(button, layout);
        }
        LinearLayout.LayoutParams rowLayout = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowLayout.setMargins(0, 0, 0, dp(8));
        content.addView(row, rowLayout);
    }

    TextView heading(int label) {
        TextView heading = text(getString(label), 18);
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        heading.setPadding(0, dp(20), 0, dp(4));
        return heading;
    }

    /** The eyes and mouth switches (see FaceParts); they apply to a running tracker at once. */
    private void addParts(LinearLayout content) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        CheckBox eyes = checkCard(R.string.eyes_switch, 18, 32, 64);
        eyes.setChecked(prefs.eyesEnabled());
        eyes.setOnCheckedChangeListener((button, on) -> {
            prefs.setEyesEnabled(on);
            FaceParts.reload(prefs);
        });
        CheckBox mouth = checkCard(R.string.mouth_switch, 18, 32, 64);
        mouth.setChecked(prefs.mouthEnabled());
        mouth.setOnCheckedChangeListener((button, on) -> {
            prefs.setMouthEnabled(on);
            FaceParts.reload(prefs);
        });
        LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        left.setMargins(0, 0, dp(6), 0);
        LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        right.setMargins(dp(6), 0, 0, 0);
        row.addView(eyes, left);
        row.addView(mouth, right);
        content.addView(row);
        headsetToggles = text("", 15);
        headsetToggles.setTextColor(WARNING_COLOR);
        headsetToggles.setVisibility(View.GONE);
        content.addView(headsetToggles);
        content.addView(text(getString(R.string.parts_help), 15));
    }

    /** A large check box on a rounded card, tinted green while checked. */
    private CheckBox checkCard(int label, int textSp, int markDp, int minHeightDp) {
        CheckBox check = new CheckBox(this);
        check.setText(label);
        check.setTextSize(textSp);
        check.setTypeface(Typeface.DEFAULT_BOLD);
        check.setButtonDrawable(new InsetDrawable(new CheckMarkDrawable(dp(markDp), ON_COLOR), dp(markDp / 2), 0, 0, 0));
        // The theme's check box tint would recolour the custom mark.
        check.setButtonTintList(null);
        check.setBackground(cardBackground());
        check.setMinHeight(dp(minHeightDp));
        check.setPadding(dp(16), dp(12), dp(20), dp(12));
        return check;
    }

    private RippleDrawable cardBackground() {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_checked}, card(0x332E7D32, ON_COLOR));
        states.addState(new int[0], card(0x14808080, 0x66808080));
        return new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), states, null);
    }

    private GradientDrawable card(int fill, int outline) {
        GradientDrawable card = new GradientDrawable();
        card.setColor(fill);
        card.setStroke(dp(2), outline);
        card.setCornerRadius(dp(16));
        return card;
    }

    TextView text(String value, int sizeSp) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        return view;
    }

    int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
