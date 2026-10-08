package io.github.codeyumx.picofacetracking;

import android.app.Activity;
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
import android.text.InputFilter;
import android.text.InputType;
import android.text.method.DigitsKeyListener;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
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

/** Settings screen: tracking on/off and pause option, the supporter tracking adjustments, then the connection. */
public final class MainActivity extends Activity {
    private static final String[] TRACKING_PERMISSIONS = {
            "com.picovr.permission.EYE_TRACKING",
            "com.picovr.permission.FACE_TRACKING",
    };
    private static final long REFRESH_MS = 500;
    private static final int ON_COLOR = 0xFF2E7D32;
    private static final int IMPORT_LICENCE = 1;
    /** Licence files are a few hundred bytes. */
    private static final int MAX_LICENCE_BYTES = 4096;

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
    private boolean showingState;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(24);
        content.setPadding(padding, padding, padding, padding);

        TextView title = text(getString(R.string.app_name), 24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        content.addView(title);

        content.addView(heading("1. Tracking"));
        tracking = new CheckBox(this);
        tracking.setText("Face and eye tracking");
        tracking.setTextSize(22);
        tracking.setTypeface(Typeface.DEFAULT_BOLD);
        tracking.setButtonDrawable(new InsetDrawable(new CheckMarkDrawable(dp(40), ON_COLOR), dp(20), 0, 0, 0));
        // The theme's check box tint would recolour the custom mark.
        tracking.setButtonTintList(null);
        tracking.setBackground(toggleBackground());
        tracking.setMinHeight(dp(84));
        tracking.setPadding(dp(16), dp(12), dp(20), dp(12));
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

        pauseForOtherApps = new CheckBox(this);
        pauseForOtherApps.setText("Pause while another app uses eye and face tracking, for example PICO Connect "
                + "(it feeds VRCFaceTracking's Pico4SAFT module itself)");
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

        content.addView(heading("Connection"));
        pairingInfo = text("", 15);
        content.addView(pairingInfo);
        removeKey = new Button(this);
        removeKey.setText("Remove pairing key");
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
        content.addView(text("Paired PC (the PC running VRCFaceTracking). Only this PC can receive your tracking data. "
                + "Leave it empty to pair with the first PC on your network that runs VRCFaceTracking; "
                + "clear it to pair again, for example when the PC gets a new address.", 15));
        pcAddress = new EditText(this);
        pcAddress.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        pcAddress.setKeyListener(DigitsKeyListener.getInstance("0123456789."));
        pcAddress.setHint("empty: pair automatically");
        pcAddress.setText(prefs.pcAddress());
        content.addView(pcAddress);

        startAtBoot = new CheckBox(this);
        startAtBoot.setText("Start when the headset turns on");
        startAtBoot.setChecked(prefs.startAtBoot());
        startAtBoot.setOnCheckedChangeListener((button, checked) -> prefs.setStartAtBoot(checked));
        content.addView(startAtBoot);

        content.addView(heading("On the PC"));
        content.addView(text("Install \"Pico Facial Data Module (paired)\" in VRCFaceTracking, which can pair by code, or thoricelli's "
                + "Pico Facial Data Module, which pairs by address. Pico4SAFTExtTrackingModule can stay installed: "
                + "it only starts while PICO Connect or Streaming Assistant runs on the PC. "
                + "Tracking only flows while the headset is worn.", 15));
        content.addView(heading("After a restart"));
        content.addView(text("If tracking does not come back by itself, open this app once. It opens as a panel next to "
                + "Virtual Desktop without closing it.", 15));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        setContentView(scroll);

        requestMissingPermissions();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // PICO OS may stop sideloaded apps from starting at boot; opening this screen brings tracking back.
        if (prefs.enabled())
            TrackingService.start(this);
        handler.post(refresh);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refresh);
        if (!prefs.enabled())
            prefs.setPcAddress(pcAddress.getText().toString().trim());
        super.onPause();
    }

    /** The paid adjustments when unlocked; otherwise what they are and how to unlock them. */
    private void addAdjustments(LinearLayout content) {
        content.addView(heading("Tracking adjustments (supporters)"));
        licenceInfo = text("", 15);
        content.addView(licenceInfo);

        String features = "Eyes and mouth switches, tongue out, eye widen maximum, blink strength, smoothing and an editor "
                + "for all 52 expressions. ";
        if (!Paid.inBuild()) {
            licenceInfo.setText(features + "They are not part of this build: the APK on the GitHub Releases page includes "
                    + "them, unlocked by a supporter licence from Patreon.");
            return;
        }

        Licence licence = Licence.saved(prefs);
        if (licence == null)
            licenceInfo.setText(features + "They unlock with a supporter licence file from Patreon. Put the .licence file "
                    + "next to Install face tracking app.bat and run it, or import it here.");
        else if (licence.expired())
            licenceInfo.setText("Your licence (" + licence.describe() + ") has ended. Import a new licence file from Patreon.");
        else
            licenceInfo.setText("Unlocked, " + licence.describe() + ". Thank you for supporting the app.");

        Button importLicence = new Button(this);
        importLicence.setText(licence == null ? "Import licence file" : "Import another licence file");
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
            if (in == null)
                throw new IOException("the file is empty");
            byte[] buffer = new byte[MAX_LICENCE_BYTES];
            int length = 0;
            int read;
            while (length < buffer.length && (read = in.read(buffer, length, buffer.length - length)) > 0)
                length += read;
            text = new String(buffer, 0, length, StandardCharsets.UTF_8);
        } catch (IOException | SecurityException e) {
            licenceInfo.setText("Could not read the licence file: " + e.getMessage());
            return;
        }

        try {
            Licence.parse(text);
        } catch (IllegalArgumentException e) {
            licenceInfo.setText("That file was not imported: " + e.getMessage() + ".");
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
        pairingCode.setHint("6-digit pairing code");
        pairingPanel.addView(pairingCode);
        pairingSubmit = new Button(this);
        pairingSubmit.setText("Pair");
        pairingSubmit.setOnClickListener(view -> {
            PairingSession session = PairingSession.current();
            if (session == null || !session.running())
                return;
            int index = pairingPcs.getCheckedRadioButtonId() - 1;
            InetSocketAddress pc = index >= 0 && index < shownPcs.size() ? shownPcs.get(index) : null;
            String error = session.submit(pc, pairingCode.getText().toString());
            if (error != null)
                pairingCode.setError(error);
            else
                pairingCode.setText("");
            showState();
        });
        pairingPanel.addView(pairingSubmit);
        pairingCancel = new Button(this);
        pairingCancel.setText("Cancel pairing");
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
        pairWithPc.setText(keyed ? "Pair again by code (new key)" : "Pair by code");
        pairWithPc.setVisibility(pairing ? View.GONE : View.VISIBLE);
        removeKey.setVisibility(keyed && !pairing ? View.VISIBLE : View.GONE);
        pairingPanel.setVisibility(session != null ? View.VISIBLE : View.GONE);
        if (session == null)
            return;

        pairingStatus.setText(session.status());
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
            pcAddress.setError("Enter the PC's address, for example 192.168.1.20, or leave it empty");
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
        status.setText(on || TrackingService.status().startsWith("Stopped:") ? TrackingService.status() : "Off");
        boolean keyed = !prefs.pairingKey().isEmpty();
        pairingInfo.setText(keyed
                ? "Paired by key. The PC's address can change, and the data is encrypted. The address below is not used."
                : "Paired by address (no pairing key). Pairing by code adds encryption and keeps working when the PC's "
                + "address changes; it needs \"Pico Facial Data Module (paired)\" in VRCFaceTracking.");
        showPairing(keyed);
        showingState = false;
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

    private TextView heading(String label) {
        TextView heading = text(label, 18);
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        heading.setPadding(0, dp(20), 0, dp(4));
        return heading;
    }

    /** The tracking toggle: a rounded card, tinted green while tracking is on. */
    private RippleDrawable toggleBackground() {
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

    private TextView text(String value, int sizeSp) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
