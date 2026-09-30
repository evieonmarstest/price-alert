package com.felix.pricealert;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/** Saves the watchlist and alerts on the phone. Thread-safe. */
public final class Store {

    public static final class Alert {
        public long id;
        public String symbol;
        public boolean above;
        public double price;
        public boolean active = true;
        public long triggeredAt;
        public double triggeredPrice;

        Alert copy() {
            Alert a = new Alert();
            a.id = id; a.symbol = symbol; a.above = above; a.price = price;
            a.active = active; a.triggeredAt = triggeredAt; a.triggeredPrice = triggeredPrice;
            return a;
        }
    }

    private static final String DEFAULT_SYMBOLS = "[\"BTC/USD\",\"PAXG/USD\",\"ETH/USD\"]";

    private static SharedPreferences prefs;
    private static final List<String> symbols = new ArrayList<>();
    private static final List<Alert> alerts = new ArrayList<>();
    private static int version = 0;

    private Store() {}

    public static synchronized void init(Context c) {
        if (prefs != null) return;
        prefs = c.getApplicationContext().getSharedPreferences("store", Context.MODE_PRIVATE);
        try {
            JSONArray s = new JSONArray(prefs.getString("symbols", DEFAULT_SYMBOLS));
            for (int i = 0; i < s.length(); i++) symbols.add(s.getString(i));
            JSONArray a = new JSONArray(prefs.getString("alerts", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Alert x = new Alert();
                x.id = o.getLong("id");
                x.symbol = o.getString("symbol");
                x.above = o.getBoolean("above");
                x.price = o.getDouble("price");
                x.active = o.optBoolean("active", true);
                x.triggeredAt = o.optLong("triggeredAt", 0);
                x.triggeredPrice = o.optDouble("triggeredPrice", 0);
                alerts.add(x);
            }
        } catch (Exception e) {
            symbols.clear();
            symbols.add("BTC/USD");
            symbols.add("PAXG/USD");
        }
    }

    private static void save() {
        try {
            JSONArray s = new JSONArray();
            for (String sym : symbols) s.put(sym);
            JSONArray a = new JSONArray();
            for (Alert x : alerts) {
                JSONObject o = new JSONObject();
                o.put("id", x.id);
                o.put("symbol", x.symbol);
                o.put("above", x.above);
                o.put("price", x.price);
                o.put("active", x.active);
                o.put("triggeredAt", x.triggeredAt);
                o.put("triggeredPrice", x.triggeredPrice);
                a.put(o);
            }
            prefs.edit().putString("symbols", s.toString()).putString("alerts", a.toString()).apply();
        } catch (Exception ignored) {
        }
        version++;
    }

    public static synchronized int version() { return version; }

    // ---- watchlist ----

    public static synchronized List<String> symbols() { return new ArrayList<>(symbols); }

    /** @return normalized symbol, or null if invalid / already present */
    public static synchronized String addSymbol(String raw) {
        String s = raw.trim().toUpperCase(Locale.US).replace(" ", "");
        if (!s.matches("[A-Z0-9]{2,10}/[A-Z0-9]{2,10}") || symbols.contains(s)) return null;
        symbols.add(s);
        save();
        return s;
    }

    public static synchronized void removeSymbol(String s) {
        symbols.remove(s);
        Iterator<Alert> it = alerts.iterator();
        while (it.hasNext()) if (it.next().symbol.equals(s)) it.remove();
        save();
    }

    // ---- alerts ----

    public static synchronized List<Alert> alerts() {
        List<Alert> out = new ArrayList<>();
        for (Alert a : alerts) out.add(a.copy());
        return out;
    }

    public static synchronized void addAlert(String symbol, boolean above, double price) {
        Alert a = new Alert();
        a.id = System.currentTimeMillis();
        a.symbol = symbol;
        a.above = above;
        a.price = price;
        alerts.add(0, a);
        save();
    }

    public static synchronized void removeAlert(long id) {
        Iterator<Alert> it = alerts.iterator();
        while (it.hasNext()) if (it.next().id == id) it.remove();
        save();
    }

    public static synchronized void setActive(long id, boolean active) {
        for (Alert a : alerts) {
            if (a.id == id) {
                a.active = active;
                if (active) { a.triggeredAt = 0; a.triggeredPrice = 0; }
            }
        }
        save();
    }

    /** Checks a new price. Alerts that hit are switched off and returned. */
    public static synchronized List<Alert> checkPrice(String symbol, double last) {
        List<Alert> fired = new ArrayList<>();
        for (Alert a : alerts) {
            if (!a.active || !a.symbol.equals(symbol)) continue;
            boolean hit = a.above ? last >= a.price : last <= a.price;
            if (hit) {
                a.active = false;
                a.triggeredAt = System.currentTimeMillis();
                a.triggeredPrice = last;
                fired.add(a.copy());
            }
        }
        if (!fired.isEmpty()) save();
        return fired;
    }

    // ---- monitoring on/off ----

    public static synchronized boolean isEnabled() { return prefs.getBoolean("enabled", true); }

    /** Ring continuously (alarm) until stopped, instead of a single buzz. */
    public static synchronized boolean ringEnabled() { return prefs.getBoolean("ring", true); }

    public static synchronized void setRing(boolean on) {
        prefs.edit().putBoolean("ring", on).apply();
        version++;
    }

    public static synchronized void setEnabled(boolean on) {
        prefs.edit().putBoolean("enabled", on).apply();
        version++;
    }
}
