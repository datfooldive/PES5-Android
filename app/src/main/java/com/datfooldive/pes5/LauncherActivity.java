package com.datfooldive.pes5;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntConsumer;

// The app's entry: a normal portrait screen that gets the player into the game without the old force close. It checks
// storage access, validates the game's files with the same verdict the game would reach (native/launcher_bridge.cpp),
// says plainly what is wrong and how to fix it, can copy a disc image into place, and sets a few options before
// launch. PLAY starts PES5Activity, which runs the game in landscape.
public class LauncherActivity extends Activity {
    static final String SHARED = "PES5";

    // app::Probe in native/game_files.h, in ordinal order
    private static final int NOT_FOUND = 0, EXTRACTED = 1, ISO = 2, WRONG_RELEASE = 3;

    // the curated values' order, the same indices native/launcher_bridge.cpp has
    private static final int RENDER = 0, FRAME_RATE = 1, ASPECT = 2, VSYNC = 3, ANTI_ALIAS = 4, COUNT = 5;

    private static final String[] kFrameRate = {"Auto", "30 FPS"};
    private static final String[] kAspect = {"Original", "16:9", "Stretch", "Fill"};
    private static final String[] kAntiAlias = {"Off", "SMAA", "MSAA 2x", "MSAA 4x"};

    // the Material dark palette; accent comes from the theme (resolved in onCreate)
    private static final int BG = 0xFF121212;        // Material dark background
    private static final int SURFACE = 0xFF1E1E1E;   // Material dark surface
    private static final int SURFACE2 = 0xFF2C2C2C;  // elevated surface
    private static final int ON_ACCENT = 0xFFFFFFFF; // text/icon on the accent colour
    private static final int TEXT = 0xFFFFFFFF;
    private static final int SUB = 0xFFB0B3B8;
    private static final int GOOD = 0xFF4CAF50;       // Material green
    private static final int WARN = 0xFFFFB300;       // Material amber
    private static final int BAD = 0xFFE53935;        // Material red
    private int accent = 0xFF2196F3;                  // the theme's colorAccent, resolved in onCreate
    private static final int RAD = 8;                 // a restrained corner radius, in dp

    private static final boolean ALL_FILES_ACCESS = Build.VERSION.SDK_INT >= 30;
    private static final int STORAGE_REQUEST = 0x4457;
    private static final int PICK_ISO = 0x4953;

    private static boolean nativeReady;
    static {
        try {
            System.loadLibrary("SDL3");
            System.loadLibrary("main");
            nativeReady = true;
        } catch (Throwable t) {
            nativeReady = false;
        }
    }

    // native/launcher_bridge.cpp: none of these start the game, they only read the files and the settings
    static native int nativeProbeGame(String filesDir);
    static native String nativeProbePath();
    static native int[] nativeLauncherGetSettings(String filesDir);
    static native void nativeLauncherSetSettings(String filesDir, int[] values);

    private final int[] cur = new int[COUNT];
    private boolean loading;
    private boolean storageAsked;

    private TextView badge, statusTitle, statusDetail;
    private LinearLayout statusCard, setupCard, optionsCard;
    private LinearLayout progressRow;
    private ProgressBar progressBar;
    private TextView progressText;

    private SeekBar resSeek;
    private TextView resValue;
    private Segmented frameRate, aspect, antiAlias;
    private Switch vsync;
    private TextView play;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final AtomicBoolean copying = new AtomicBoolean(false);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TypedValue tv = new TypedValue();
        if (getTheme().resolveAttribute(android.R.attr.colorAccent, tv, true) && tv.data != 0) accent = tv.data;
        setContentView(buildUi());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!copying.get()) refresh();
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    private String filesDir() {
        return getFilesDir().getAbsolutePath();
    }

    // ---------------------------------------------------------------- the screen

    private View buildUi() {
        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setBackgroundColor(BG);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        screen.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(24), dp(20), dp(16));
        scroll.addView(content);

        content.addView(hero());
        content.addView(gap(dp(20)));

        statusCard = card();
        buildStatus(statusCard);
        content.addView(statusCard);
        content.addView(gap(dp(14)));

        setupCard = card();
        content.addView(setupCard);  // filled per state, hidden when not needed
        content.addView(gap(dp(14)));

        optionsCard = card();
        buildOptions(optionsCard);
        content.addView(optionsCard);

        // the PLAY bar, pinned under the scroll
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setBackgroundColor(0xFF0B0E12);
        bar.setPadding(dp(20), dp(12), dp(20), dp(16));
        play = new TextView(this);
        play.setText("PLAY");
        play.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        play.setTypeface(Typeface.DEFAULT_BOLD);
        play.setTextColor(ON_ACCENT);
        play.setGravity(Gravity.CENTER);
        play.setAllCaps(false);
        play.setPadding(0, dp(15), 0, dp(15));
        play.setBackground(ripple(round(accent, dp(RAD)), 0x33FFFFFF));
        play.setOnClickListener(x -> startGame());
        bar.addView(play, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        screen.addView(bar);

        return screen;
    }

    private View hero() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView name = label("PES5 Android", 26, TEXT);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        TextView sub = label("Pro Evolution Soccer 5", 14, SUB);
        sub.setPadding(0, dp(2), 0, 0);
        col.addView(name);
        col.addView(sub);
        return col;
    }

    // ---------------------------------------------------------------- status

    private void buildStatus(LinearLayout card) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        badge = new TextView(this);
        badge.setGravity(Gravity.CENTER);
        badge.setTextColor(ON_ACCENT);
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(dp(34), dp(34));
        bl.setMarginEnd(dp(14));
        row.addView(badge, bl);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        statusTitle = label("", 16, TEXT);
        statusTitle.setTypeface(Typeface.DEFAULT_BOLD);
        statusDetail = label("", 13, SUB);
        statusDetail.setLineSpacing(dp(2), 1f);
        texts.addView(statusTitle);
        texts.addView(statusDetail);
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(row);
    }

    private void setStatus(int color, String glyph, String title, String detail) {
        badge.setText(glyph);
        badge.setBackground(round(color, dp(17)));
        statusTitle.setText(title);
        statusDetail.setText(detail);
        statusDetail.setVisibility(detail == null || detail.isEmpty() ? View.GONE : View.VISIBLE);
    }

    // ---------------------------------------------------------------- state

    private void refresh() {
        setupCard.removeAllViews();
        setupCard.setVisibility(View.GONE);
        if (!nativeReady) {
            setStatus(BAD, "✕", "Something went wrong", "The game's code could not be loaded. Reinstall the app.");
            enablePlay(false);
            optionsCard.setVisibility(View.GONE);
            return;
        }
        optionsCard.setVisibility(View.VISIBLE);
        if (!hasStorageAccess()) {
            setStatus(WARN, "!", "Storage access needed", ALL_FILES_ACCESS
                    ? "PES5 reads the game from your storage. Turn on all-files access, then come back."
                    : "PES5 reads the game from your storage.");
            setupAccess();
            enablePlay(false);
            loadOptions();
            return;
        }
        new File(Environment.getExternalStorageDirectory(), SHARED).mkdirs();
        int probe = nativeProbeGame(filesDir());
        String path = nativeProbePath();
        switch (probe) {
            case EXTRACTED:
            case ISO:
                setStatus(GOOD, "✓", "Ready to play",
                        probe == ISO ? "Found your disc image." : "Found your game files.");
                enablePlay(true);
                break;
            case WRONG_RELEASE:
                setStatus(BAD, "✕", "Different game version",
                        "The game in " + shortPath(path) + " is a different release than this build. "
                                + "Use the .iso that matches this build.");
                setupFindGame();
                enablePlay(false);
                break;
            default:
                setStatus(WARN, "!", "No game found",
                        "Add your game to continue. Put the disc image (.iso), or default.xbe with its files, in:");
                setupFindGame();
                enablePlay(false);
                break;
        }
        loadOptions();
    }

    private void enablePlay(boolean on) {
        play.setEnabled(on);
        play.setAlpha(on ? 1f : 0.35f);
    }

    private void setupAccess() {
        setupCard.setVisibility(View.VISIBLE);
        setupCard.addView(sectionTitle("SET UP"));
        setupCard.addView(filled("Grant storage access", this::openAccessSettings));
    }

    private void setupFindGame() {
        setupCard.setVisibility(View.VISIBLE);
        setupCard.addView(sectionTitle("SET UP"));
        TextView p = label("/storage/emulated/0/" + SHARED, 13, accent);
        p.setBackground(round(SURFACE2, dp(RAD)));
        p.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        pl.bottomMargin = dp(12);
        setupCard.addView(p, pl);

        progressRow = new LinearLayout(this);
        progressRow.setOrientation(LinearLayout.VERTICAL);
        progressRow.setVisibility(View.GONE);
        progressText = label("", 12, SUB);
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(1000);
        progressRow.addView(progressText);
        LinearLayout.LayoutParams pbl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8));
        pbl.topMargin = dp(6);
        progressRow.addView(progressBar, pbl);
        setupCard.addView(progressRow);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams a = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        a.setMarginEnd(dp(8));
        LinearLayout.LayoutParams b = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(filledIn("Locate game file", this::pickIso), a);
        row.addView(outlined("Rescan", this::refresh), b);
        setupCard.addView(row);
    }

    // ---------------------------------------------------------------- options

    private void buildOptions(LinearLayout card) {
        card.addView(sectionTitle("OPTIONS"));

        card.addView(optionLabel("Render resolution"));
        LinearLayout res = new LinearLayout(this);
        res.setOrientation(LinearLayout.HORIZONTAL);
        res.setGravity(Gravity.CENTER_VERTICAL);
        resSeek = new SeekBar(this);
        resSeek.setMax((200 - 20) / 5);
        resSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                cur[RENDER] = 20 + progress * 5;
                resValue.setText(cur[RENDER] + "%");
                if (!fromUser || loading) return;
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {}

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                if (!loading) save();
            }
        });
        resValue = label("100%", 14, TEXT);
        resValue.setGravity(Gravity.END);
        resValue.setMinWidth(dp(56));
        res.addView(resSeek, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        res.addView(resValue);
        card.addView(res);

        card.addView(optionLabel("Frame rate"));
        frameRate = new Segmented(kFrameRate, i -> setOption(FRAME_RATE, i));
        card.addView(frameRate.view);

        card.addView(optionLabel("Aspect ratio"));
        aspect = new Segmented(kAspect, i -> setOption(ASPECT, i));
        card.addView(aspect.view);

        card.addView(optionLabel("Anti-aliasing"));
        antiAlias = new Segmented(kAntiAlias, i -> setOption(ANTI_ALIAS, i));
        card.addView(antiAlias.view);

        LinearLayout vs = new LinearLayout(this);
        vs.setOrientation(LinearLayout.HORIZONTAL);
        vs.setGravity(Gravity.CENTER_VERTICAL);
        vs.setPadding(0, dp(14), 0, 0);
        vs.addView(optionLabel("Vertical sync"), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        vsync = new Switch(this);
        vsync.setOnCheckedChangeListener((b, on) -> {
            if (loading) return;
            cur[VSYNC] = on ? 1 : 0;
            save();
        });
        vs.addView(vsync);
        card.addView(vs);
    }

    private TextView optionLabel(String s) {
        TextView t = label(s, 13, SUB);
        t.setPadding(0, dp(14), 0, dp(6));
        return t;
    }

    private void setOption(int key, int index) {
        if (loading) return;
        cur[key] = index;
        save();
    }

    private void loadOptions() {
        if (!nativeReady) return;
        int[] s = nativeLauncherGetSettings(filesDir());
        System.arraycopy(s, 0, cur, 0, Math.min(s.length, COUNT));
        loading = true;
        resSeek.setProgress((clamp(cur[RENDER], 20, 200) - 20) / 5);
        resValue.setText(clamp(cur[RENDER], 20, 200) + "%");
        frameRate.set(clamp(cur[FRAME_RATE], 0, kFrameRate.length - 1));
        aspect.set(clamp(cur[ASPECT], 0, kAspect.length - 1));
        antiAlias.set(clamp(cur[ANTI_ALIAS], 0, kAntiAlias.length - 1));
        vsync.setChecked(cur[VSYNC] != 0);
        loading = false;
    }

    private void save() {
        if (nativeReady) nativeLauncherSetSettings(filesDir(), cur);
    }

    // a row of pill buttons, one selected: a clear, tappable choice
    private final class Segmented {
        final LinearLayout view;
        final TextView[] cells;
        int index;
        final IntConsumer onPick;

        Segmented(String[] names, IntConsumer onPick) {
            this.onPick = onPick;
            view = new LinearLayout(LauncherActivity.this);
            view.setOrientation(LinearLayout.HORIZONTAL);
            cells = new TextView[names.length];
            for (int i = 0; i < names.length; i++) {
                final int at = i;
                TextView c = label(names[i], 13, SUB);
                c.setGravity(Gravity.CENTER);
                c.setPadding(dp(6), dp(11), dp(6), dp(11));
                c.setOnClickListener(x -> {
                    set(at);
                    onPick.accept(at);
                });
                cells[i] = c;
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                if (i > 0) lp.setMarginStart(dp(8));
                view.addView(c, lp);
            }
        }

        void set(int i) {
            index = i;
            for (int j = 0; j < cells.length; j++) {
                boolean on = j == index;
                cells[j].setBackground(ripple(round(on ? accent : SURFACE2, dp(RAD)), 0x33FFFFFF));
                cells[j].setTextColor(on ? ON_ACCENT : SUB);
                cells[j].setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            }
        }
    }

    // ---------------------------------------------------------------- launch

    private void startGame() {
        if (!play.isEnabled()) return;
        startActivity(new Intent(this, PES5Activity.class));
    }

    // ---------------------------------------------------------------- the disc image picker

    private void pickIso() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try {
            startActivityForResult(i, PICK_ISO);
        } catch (ActivityNotFoundException e) {
            toast("No file picker on this device.");
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_ISO || result != RESULT_OK || data == null || data.getData() == null) return;
        copyIso(data.getData());
    }

    private void copyIso(Uri uri) {
        if (!copying.compareAndSet(false, true)) return;
        String picked = displayName(uri);
        final String name = picked.toLowerCase(Locale.US).endsWith(".iso") ? picked
                : (picked.isEmpty() ? "game" : picked) + ".iso";
        final long total = size(uri);
        showCopying(true);
        runOnUiThread(() -> showProgress(0, total));
        io.execute(() -> {
            File dir = new File(Environment.getExternalStorageDirectory(), SHARED);
            dir.mkdirs();
            File dest = new File(dir, name);
            boolean ok = false;
            String error = null;
            try (InputStream in = getContentResolver().openInputStream(uri);
                 OutputStream out = new FileOutputStream(dest)) {
                if (in == null) throw new Exception("could not open the file");
                byte[] buf = new byte[1 << 20];
                long copied = 0;
                long lastShown = -1;
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    copied += n;
                    if (total > 0) {
                        long permille = copied * 1000 / total;
                        if (permille != lastShown) {
                            lastShown = permille;
                            final int p = (int) permille;
                            runOnUiThread(() -> showProgress(p, total));
                        }
                    }
                }
                out.flush();
                ok = true;
            } catch (Exception e) {
                error = e.getMessage();
                dest.delete();
            }
            final boolean done = ok;
            final String why = error;
            runOnUiThread(() -> {
                copying.set(false);
                showCopying(false);
                if (!done) toast("Could not copy the game" + (why != null ? ": " + why : "."));
                refresh();
            });
        });
    }

    private void showCopying(boolean on) {
        if (progressRow != null) progressRow.setVisibility(on ? View.VISIBLE : View.GONE);
        if (on) enablePlay(false);
    }

    private void showProgress(int permille, long total) {
        if (progressBar == null) return;
        progressBar.setProgress(permille);
        progressText.setText(total > 0
                ? String.format(Locale.US, "Copying… %d%%  (%d MB)", permille / 10, total / (1024 * 1024))
                : "Copying…");
    }

    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getString(0);
        } catch (Exception ignored) {
        }
        return "";
    }

    private long size(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[] {OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getLong(0);
        } catch (Exception ignored) {
        }
        return -1;
    }

    // ---------------------------------------------------------------- storage access

    private boolean hasStorageAccess() {
        if (ALL_FILES_ACCESS) return Environment.isExternalStorageManager();
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void openAccessSettings() {
        if (!ALL_FILES_ACCESS) {
            boolean ask = !storageAsked
                    || shouldShowRequestPermissionRationale(Manifest.permission.READ_EXTERNAL_STORAGE);
            if (ask) {
                storageAsked = true;
                requestPermissions(new String[] {Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE}, STORAGE_REQUEST);
            } else {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName())));
            }
            return;
        }
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (ActivityNotFoundException e) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            } catch (ActivityNotFoundException e2) {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName())));
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int request, String[] perms, int[] results) {
        super.onRequestPermissionsResult(request, perms, results);
        if (request == STORAGE_REQUEST) refresh();
    }

    // ---------------------------------------------------------------- pieces

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(round(SURFACE, dp(RAD)));
        c.setPadding(dp(16), dp(16), dp(16), dp(16));
        return c;
    }

    private TextView sectionTitle(String s) {
        TextView t = label(s, 11, SUB);
        t.setLetterSpacing(0.12f);
        t.setPadding(0, 0, 0, dp(10));
        return t;
    }

    private TextView label(String s, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    private TextView filled(String s, Runnable onClick) {
        TextView b = button(s, ON_ACCENT, round(accent, dp(RAD)));
        b.setOnClickListener(x -> onClick.run());
        return b;
    }

    private TextView filledIn(String s, Runnable onClick) {
        return filled(s, onClick);
    }

    private TextView outlined(String s, Runnable onClick) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.TRANSPARENT);
        g.setCornerRadius(dp(RAD));
        g.setStroke(dp(1), SURFACE2);
        TextView b = button(s, TEXT, g);
        b.setOnClickListener(x -> onClick.run());
        return b;
    }

    private TextView button(String s, int textColor, Drawable bg) {
        TextView b = new TextView(this);
        b.setText(s);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(textColor);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(14), dp(13), dp(14), dp(13));
        b.setBackground(ripple(bg, 0x33FFFFFF));
        return b;
    }

    private GradientDrawable round(int color, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    private Drawable ripple(Drawable content, int rippleColor) {
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), content, null);
    }

    private View gap(int h) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h));
        return v;
    }

    private String shortPath(String p) {
        if (p == null || p.isEmpty()) return "the game folder";
        int i = p.indexOf("/emulated/0");
        return i >= 0 ? p.substring(i + "/emulated/0".length()) : p;
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(v, max));
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show();
    }
}
