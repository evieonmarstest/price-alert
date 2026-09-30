package com.felix.pricealert;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity {

    private static final int BG = 0xFF0E1116;
    private static final int CARD = 0xFF171C24;
    private static final int TEXT = 0xFFE8EAED;
    private static final int MUTED = 0xFF8B93A1;
    private static final int UP = 0xFF2ECC71;
    private static final int DOWN = 0xFFFF5C5C;
    private static final int ACCENT = 0xFFF5B942;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, TextView[]> priceViews = new HashMap<>();

    private LinearLayout priceBox, alertBox;
    private TextView statusView;
    private Button toggleBtn, batteryBtn, ringModeBtn, stopRingBtn;
    private Spinner symSpin, condSpin;
    private EditText priceInput, symbolInput;

    private List<String> shownSymbols = new ArrayList<>();
    private int shownVersion = -1;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Store.init(this);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        setContentView(buildUi());

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        }
        if (Store.isEnabled()) PriceService.start(this);
        handleSilence(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleSilence(intent);
    }

    private void handleSilence(Intent intent) {
        if (intent != null && intent.getBooleanExtra(PriceService.EXTRA_SILENCE, false)) {
            PriceService.silence(this);
            intent.removeExtra(PriceService.EXTRA_SILENCE);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        shownVersion = -1;
        handler.post(ticker);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(ticker);
    }

    // ---------------------------------------------------------------- UI build

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(20), dp(16), dp(32));
        scroll.addView(root);

        TextView title = text("Price Alert", 26, TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);
        statusView = text("", 13, MUTED);
        statusView.setPadding(0, dp(2), 0, dp(12));
        root.addView(statusView);

        stopRingBtn = button("Stop ringing", true);
        stopRingBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        stopRingBtn.setOnClickListener(v -> {
            PriceService.silence(this);
            PriceHub.ringing = false;
            refresh();
        });
        stopRingBtn.setVisibility(View.GONE);
        root.addView(stopRingBtn, new LinearLayout.LayoutParams(-1, dp(72)));

        // Live prices
        root.addView(section("LIVE PRICES"));
        priceBox = card();
        root.addView(priceBox);

        LinearLayout addSym = row();
        symbolInput = input("Add pair, e.g. SOL/USD or BTC/CAD", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        addSym.addView(symbolInput, new LinearLayout.LayoutParams(0, -2, 1));
        Button addSymBtn = button("Add", false);
        addSymBtn.setOnClickListener(v -> onAddSymbol());
        addSym.addView(addSymBtn);
        root.addView(addSym);
        root.addView(hint("Long-press a price to remove it."));

        // New alert
        root.addView(section("NEW ALERT"));
        LinearLayout form = card();
        symSpin = new Spinner(this);
        form.addView(symSpin);
        condSpin = new Spinner(this);
        ArrayAdapter<String> ca = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item,
                new String[]{"when price reaches (auto up/down)", "when price rises above", "when price falls below"});
        ca.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        condSpin.setAdapter(ca);
        form.addView(condSpin);
        priceInput = input("Target price", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        form.addView(priceInput);
        Button addAlert = button("Add alert", true);
        addAlert.setOnClickListener(v -> onAddAlert());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(8);
        form.addView(addAlert, lp);
        root.addView(form);

        // Alerts
        root.addView(section("YOUR ALERTS"));
        alertBox = card();
        root.addView(alertBox);
        root.addView(hint("Tap an alert to switch it on/off. Long-press to delete."));

        // Controls
        root.addView(section("ALARM"));
        ringModeBtn = button("", false);
        ringModeBtn.setOnClickListener(v -> {
            Store.setRing(!Store.ringEnabled());
            refresh();
        });
        root.addView(ringModeBtn, new LinearLayout.LayoutParams(-1, -2));
        root.addView(hint("Continuous ring uses your alarm sound and plays even in silent mode, "
                + "until you tap Stop ringing (auto-stops after 5 minutes)."));

        root.addView(section("BACKGROUND"));
        toggleBtn = button("", false);
        toggleBtn.setOnClickListener(v -> {
            if (Store.isEnabled()) PriceService.stop(this); else PriceService.start(this);
            shownVersion = -1;
            refresh();
        });
        root.addView(toggleBtn, new LinearLayout.LayoutParams(-1, -2));
        batteryBtn = button("Allow running in background (recommended)", false);
        batteryBtn.setOnClickListener(v -> askBatteryExemption());
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, -2);
        bl.topMargin = dp(8);
        root.addView(batteryBtn, bl);

        root.addView(hint("Prices: Kraken live feed (updates on every trade). Gold = PAXG, a token backed by "
                + "physical gold that tracks spot gold closely and trades 24/7. Keep the \"Watching prices\" "
                + "notification running for alerts to work while the app is closed."));
        return scroll;
    }

    // ---------------------------------------------------------------- refresh

    private void refresh() {
        boolean enabled = Store.isEnabled();
        String status = enabled ? PriceHub.status : "Monitoring is off";
        statusView.setText((PriceHub.live && enabled ? "● " : "○ ") + status);
        statusView.setTextColor(PriceHub.live && enabled ? UP : MUTED);

        List<String> syms = Store.symbols();
        if (!syms.equals(shownSymbols)) {
            shownSymbols = syms;
            rebuildPrices();
            String keep = symSpin.getSelectedItem() != null ? symSpin.getSelectedItem().toString() : null;
            List<String> labels = new ArrayList<>();
            for (String s : syms) labels.add(s);
            ArrayAdapter<String> sa = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels);
            sa.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            symSpin.setAdapter(sa);
            if (keep != null && labels.contains(keep)) symSpin.setSelection(labels.indexOf(keep));
        }

        for (String s : syms) {
            TextView[] v = priceViews.get(s);
            if (v == null) continue;
            PriceHub.Tick t = PriceHub.get(s);
            if (t == null) continue;
            v[0].setText(PriceHub.fmt(t.last));
            if (!Double.isNaN(t.changePct)) {
                v[1].setText(String.format(Locale.US, "%+.2f%% 24h", t.changePct));
                v[1].setTextColor(t.changePct >= 0 ? UP : DOWN);
            }
        }

        int ver = Store.version();
        if (ver != shownVersion) {
            shownVersion = ver;
            rebuildAlerts();
        }
        toggleBtn.setText(enabled ? "Stop monitoring" : "Start monitoring");
        ringModeBtn.setText(Store.ringEnabled()
                ? "Alert style: Ring continuously  (tap to change)"
                : "Alert style: Vibrate once  (tap to change)");
        stopRingBtn.setVisibility(PriceHub.ringing ? View.VISIBLE : View.GONE);

        PowerManager pm = getSystemService(PowerManager.class);
        batteryBtn.setVisibility(pm.isIgnoringBatteryOptimizations(getPackageName()) ? View.GONE : View.VISIBLE);
    }

    private void rebuildPrices() {
        priceBox.removeAllViews();
        priceViews.clear();
        if (shownSymbols.isEmpty()) {
            priceBox.addView(text("No pairs yet. Add one below.", 14, MUTED));
            return;
        }
        for (int i = 0; i < shownSymbols.size(); i++) {
            final String s = shownSymbols.get(i);
            LinearLayout r = row();
            r.setPadding(0, dp(10), 0, dp(10));

            LinearLayout left = new LinearLayout(this);
            left.setOrientation(LinearLayout.VERTICAL);
            TextView name = text(PriceHub.name(s), 17, TEXT);
            name.setTypeface(Typeface.DEFAULT_BOLD);
            left.addView(name);
            left.addView(text(s, 12, MUTED));
            r.addView(left, new LinearLayout.LayoutParams(0, -2, 1));

            LinearLayout right = new LinearLayout(this);
            right.setOrientation(LinearLayout.VERTICAL);
            right.setGravity(Gravity.END);
            TextView price = text("—", 19, TEXT);
            price.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            price.setGravity(Gravity.END);
            TextView chg = text("", 12, MUTED);
            chg.setGravity(Gravity.END);
            right.addView(price);
            right.addView(chg);
            r.addView(right);

            r.setOnLongClickListener(v -> {
                new AlertDialog.Builder(this)
                        .setMessage("Remove " + s + " and its alerts?")
                        .setPositiveButton("Remove", (d, w) -> {
                            Store.removeSymbol(s);
                            PriceService.restart(this);
                            refresh();
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
                return true;
            });

            priceViews.put(s, new TextView[]{price, chg});
            if (i > 0) priceBox.addView(divider());
            priceBox.addView(r);
        }
    }

    private void rebuildAlerts() {
        alertBox.removeAllViews();
        List<Store.Alert> list = Store.alerts();
        if (list.isEmpty()) {
            alertBox.addView(text("No alerts yet.", 14, MUTED));
            return;
        }
        DateFormat df = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT);
        for (int i = 0; i < list.size(); i++) {
            final Store.Alert a = list.get(i);
            LinearLayout r = new LinearLayout(this);
            r.setOrientation(LinearLayout.VERTICAL);
            r.setPadding(0, dp(10), 0, dp(10));

            TextView line = text(PriceHub.name(a.symbol) + (a.above ? "  ≥  " : "  ≤  ") + PriceHub.fmt(a.price),
                    16, a.active ? TEXT : MUTED);
            line.setTypeface(Typeface.DEFAULT_BOLD);
            r.addView(line);

            String sub;
            int subColor;
            if (a.active) {
                sub = "Active · " + (a.above ? "rises above" : "falls below");
                subColor = ACCENT;
            } else if (a.triggeredAt > 0) {
                sub = "Triggered at " + PriceHub.fmt(a.triggeredPrice) + " · " + df.format(new Date(a.triggeredAt));
                subColor = UP;
            } else {
                sub = "Off";
                subColor = MUTED;
            }
            r.addView(text(sub, 12, subColor));

            r.setOnClickListener(v -> {
                boolean on = !a.active;
                Store.setActive(a.id, on);
                if (on) {
                    PriceHub.Tick t = PriceHub.get(a.symbol);
                    if (t != null && (a.above ? t.last >= a.price : t.last <= a.price)) {
                        toast("Price is already past this level – it will trigger right away.");
                    }
                }
                refresh();
            });
            r.setOnLongClickListener(v -> {
                Store.removeAlert(a.id);
                toast("Alert deleted");
                refresh();
                return true;
            });

            if (i > 0) alertBox.addView(divider());
            alertBox.addView(r);
        }
    }

    // ---------------------------------------------------------------- actions

    private void onAddSymbol() {
        String s = Store.addSymbol(symbolInput.getText().toString());
        if (s == null) {
            toast("Use the format BASE/QUOTE, e.g. SOL/USD (and not already in the list)");
            return;
        }
        symbolInput.setText("");
        hideKeyboard();
        PriceService.restart(this);
        toast("Added " + s + ". If no price shows, Kraken doesn't list that pair.");
        refresh();
    }

    private void onAddAlert() {
        Object sel = symSpin.getSelectedItem();
        if (sel == null) { toast("Add a pair first"); return; }
        String symbol = sel.toString();

        double target;
        try {
            target = Double.parseDouble(priceInput.getText().toString().replace(",", "").trim());
        } catch (NumberFormatException e) {
            toast("Enter a target price");
            return;
        }
        if (target <= 0) { toast("Enter a target price"); return; }

        boolean above;
        int cond = condSpin.getSelectedItemPosition();
        if (cond == 0) {
            PriceHub.Tick t = PriceHub.get(symbol);
            if (t == null) { toast("Waiting for the live price… try again in a moment, or pick above/below."); return; }
            above = target > t.last;
        } else {
            above = cond == 1;
        }

        Store.addAlert(symbol, above, target);
        priceInput.setText("");
        hideKeyboard();
        toast("Alert set: " + PriceHub.name(symbol) + (above ? " ≥ " : " ≤ ") + PriceHub.fmt(target));
        if (!Store.isEnabled()) toast("Monitoring is off – tap Start monitoring.");
        refresh();
    }

    private void askBatteryExemption() {
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
    }

    // ---------------------------------------------------------------- view helpers

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    private TextView section(String s) {
        TextView t = text(s, 12, MUTED);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setLetterSpacing(0.08f);
        t.setPadding(dp(4), dp(18), 0, dp(8));
        return t;
    }

    private TextView hint(String s) {
        TextView t = text(s, 12, MUTED);
        t.setPadding(dp(4), dp(6), dp(4), 0);
        return t;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(8), dp(16), dp(8));
        GradientDrawable g = new GradientDrawable();
        g.setColor(CARD);
        g.setCornerRadius(dp(14));
        c.setBackground(g);
        return c;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }

    private View divider() {
        View d = new View(this);
        d.setBackgroundColor(0x22FFFFFF);
        d.setLayoutParams(new LinearLayout.LayoutParams(-1, 1));
        return d;
    }

    private EditText input(String hintText, int type) {
        EditText e = new EditText(this);
        e.setHint(hintText);
        e.setInputType(type);
        e.setSingleLine(true);
        e.setTextColor(TEXT);
        e.setHintTextColor(MUTED);
        return e;
    }

    private Button button(String label, boolean primary) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(primary ? 0xFF1A1300 : TEXT);
        GradientDrawable g = new GradientDrawable();
        g.setColor(primary ? ACCENT : 0xFF252C37);
        g.setCornerRadius(dp(10));
        b.setBackground(g);
        b.setPadding(dp(16), 0, dp(16), 0);
        return b;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private void hideKeyboard() {
        View v = getCurrentFocus();
        if (v != null) {
            InputMethodManager imm = getSystemService(InputMethodManager.class);
            imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
        }
    }
}
