package com.felix.pricealert;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Latest prices shared between the background service and the screen. */
public final class PriceHub {

    public static final class Tick {
        public final double last;
        public final double changePct;
        public final long time;

        Tick(double last, double changePct) {
            this.last = last;
            this.changePct = changePct;
            this.time = System.currentTimeMillis();
        }
    }

    private static final Map<String, Tick> ticks = new ConcurrentHashMap<>();
    private static final Map<String, String> NAMES = new HashMap<>();

    static {
        NAMES.put("BTC", "Bitcoin");
        NAMES.put("ETH", "Ethereum");
        NAMES.put("PAXG", "Gold");
        NAMES.put("SOL", "Solana");
        NAMES.put("XRP", "XRP");
        NAMES.put("DOGE", "Dogecoin");
        NAMES.put("LTC", "Litecoin");
        NAMES.put("ADA", "Cardano");
    }

    public static volatile String status = "Stopped";
    public static volatile boolean live = false;

    private PriceHub() {}

    public static void put(String symbol, double last, double changePct) {
        ticks.put(symbol, new Tick(last, changePct));
    }

    public static Tick get(String symbol) { return ticks.get(symbol); }

    public static String name(String symbol) {
        String[] p = symbol.split("/");
        String base = NAMES.containsKey(p[0]) ? NAMES.get(p[0]) : p[0];
        if (p.length > 1 && !"USD".equals(p[1])) base += " · " + p[1];
        return base;
    }

    public static String fmt(double p) {
        if (p >= 1) return String.format(Locale.US, "%,.2f", p);
        return String.format(Locale.US, "%.5f", p);
    }
}
