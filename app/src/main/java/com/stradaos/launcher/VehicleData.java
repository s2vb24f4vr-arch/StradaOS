package com.stradaos.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Datos del vehículo leídos de la caja CAN (velocidad real, RPM, refrigerante, aceite de la caja, batería,
 * temperatura exterior). Cada pantalla los publica distinto, así que se prueban varias fuentes:
 *  1. Propiedades del sistema con nombres reconocibles (se buscan en «getprop» cada pocos segundos).
 *  2. Broadcasts de las apps del fabricante (se aceptan extras con nombres reconocibles).
 * Lo que no se encuentra queda en null y la interfaz muestra «—». El informe (report) va en el diagnóstico para
 * poder agregar la fuente exacta de cada pantalla.
 */
public class VehicleData {

    public Float speed, rpm, coolant, transOil, battery, outside;
    /** De dónde salió cada dato (para el diagnóstico). */
    final Map<String, String> src = new LinkedHashMap<String, String>();
    /** Propiedades del sistema que parecen del vehículo (para el diagnóstico). */
    final Map<String, String> candidates = new LinkedHashMap<String, String>();
    /** Últimos broadcasts recibidos de apps del fabricante. */
    final List<String> seen = new ArrayList<String>();
    long updatedAt;

    private static VehicleData inst;
    private Context ctx;
    private long lastScan;

    public static synchronized VehicleData get(Context c) {
        if (inst == null) { inst = new VehicleData(); inst.ctx = c.getApplicationContext(); inst.listen(); }
        return inst;
    }

    public boolean any() { return rpm != null || coolant != null || transOil != null || battery != null || outside != null || speed != null; }

    /** Llamar cada ~2 s (desde el hilo de la interfaz). La lectura de getprop va en otro hilo, cada 5 s. */
    public void poll() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastScan < 5000) return;
        lastScan = now;
        new Thread(new Runnable() { public void run() { scanProps(); } }).start();
    }

    // ───────── fuente 1: propiedades del sistema ─────────

    private static final String[] VEH_WORDS = {"can", "car", "mcu", "vehicle", "veh", "obd", "engine", "canbus", "raise", "rz"};

    private void scanProps() {
        Map<String, String> found = new LinkedHashMap<String, String>();
        BufferedReader r = null;
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"getprop"});
            r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = r.readLine()) != null) {
                // formato: [clave]: [valor]
                int a = line.indexOf("]: [");
                if (!line.startsWith("[") || a < 0 || !line.endsWith("]")) continue;
                String k = line.substring(1, a), v = line.substring(a + 4, line.length() - 1);
                String kl = k.toLowerCase(Locale.US);
                boolean veh = false;
                for (String w : VEH_WORDS) if (kl.contains(w)) { veh = true; break; }
                if (veh || kl.contains("volt") || kl.contains("temp") || kl.contains("rpm") || kl.contains("speed") || kl.contains("coolant"))
                    found.put(k, v);
            }
        } catch (Throwable ignored) {
        } finally {
            try { if (r != null) r.close(); } catch (Throwable ignored) { }
        }
        synchronized (this) {
            candidates.clear();
            candidates.putAll(found);
            for (Map.Entry<String, String> e : found.entrySet()) offer(e.getKey(), e.getValue(), "prop:" + e.getKey());
        }
    }

    // ───────── fuente 2: broadcasts de apps del fabricante ─────────

    private void listen() {
        // Acciones habituales de cajas CAN chinas (Raise/Hiworld/Simple Soft) y de apps «Car Settings». Se amplía con
        // lo que aparezca en el diagnóstico de cada pantalla.
        String[] actions = {
            "com.canbus.action.DATA", "com.canbus.data", "android.intent.action.CANBUS", "com.android.canbus",
            "com.raise.canbus", "com.raise.can.data", "com.hiworld.canbus", "com.car.canbus", "com.car.info",
            "com.ts.can.data", "com.ts.canbus", "com.android.car.vehicle", "com.syu.canbus", "com.syu.ms.can",
            "com.nwd.can", "com.xy.canbus", "com.carsetting.car_info", "com.mcu.car_info", "action.car.info",
            "android.intent.action.CAR_INFO", "com.android.mcu.CAR_INFO", "com.vehicle.info",
        };
        IntentFilter f = new IntentFilter();
        for (String a : actions) f.addAction(a);
        try {
            ctx.registerReceiver(new BroadcastReceiver() {
                public void onReceive(Context c, Intent i) { onBroadcast(i); }
            }, f);
        } catch (Throwable ignored) { }
    }

    void onBroadcast(Intent i) {
        Bundle b = i.getExtras();
        StringBuilder sb = new StringBuilder(i.getAction());
        if (b != null) for (String k : b.keySet()) {
            Object v = b.get(k);
            sb.append(" ").append(k).append("=").append(v);
            if (v != null) offer(k, String.valueOf(v), "bc:" + i.getAction() + "/" + k);
        }
        synchronized (this) {
            seen.add(sb.toString());
            while (seen.size() > 30) seen.remove(0);
        }
    }

    // ───────── reconocer el dato por el nombre y un rango razonable ─────────

    private void offer(String key, String val, String from) {
        Float n = num(val);
        if (n == null) return;
        String k = key.toLowerCase(Locale.US);
        float x = n;
        if ((k.contains("rpm") || k.contains("engine_speed") || k.contains("enginespeed")) && x >= 0 && x < 9000) set("rpm", x, from);
        else if ((k.contains("coolant") || k.contains("water") || k.contains("engine_temp") || k.contains("enginetemp")) && x > -40 && x < 150) set("coolant", x, from);
        else if ((k.contains("trans") || k.contains("gearbox") || k.contains("atf") || k.contains("gear_oil")) && k.contains("temp") && x > -40 && x < 170) set("transOil", x, from);
        else if ((k.contains("volt") || k.contains("vbat") || k.contains("battery_v") || k.contains("batteryv") || k.contains("bat_v")) && x > 6 && x < 17) set("battery", x, from);
        else if ((k.contains("volt") || k.contains("vbat") || k.contains("battery")) && x > 6000 && x < 17000) set("battery", x / 1000f, from);   // en mV
        else if ((k.contains("outside") || k.contains("ambient") || k.contains("out_temp") || k.contains("outtemp") || k.contains("exterior")) && x > -50 && x < 70) set("outside", x, from);
        else if ((k.contains("vehicle_speed") || k.contains("vehiclespeed") || k.contains("car_speed") || k.contains("carspeed") || k.equals("speed")) && x >= 0 && x < 300) set("speed", x, from);
    }

    private synchronized void set(String field, float v, String from) {
        if ("rpm".equals(field)) rpm = v;
        else if ("coolant".equals(field)) coolant = v;
        else if ("transOil".equals(field)) transOil = v;
        else if ("battery".equals(field)) battery = v;
        else if ("outside".equals(field)) outside = v;
        else if ("speed".equals(field)) speed = v;
        src.put(field, from);
        updatedAt = SystemClock.elapsedRealtime();
    }

    static Float num(String s) {
        if (s == null) return null;
        s = s.trim().replace(',', '.');
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= '0' && c <= '9') || c == '.' || (c == '-' && b.length() == 0)) b.append(c);
            else if (b.length() > 0) break;
        }
        if (b.length() == 0 || "-".equals(b.toString()) || ".".equals(b.toString())) return null;
        try { return Float.parseFloat(b.toString()); } catch (Throwable e) { return null; }
    }

    /** Solo pruebas (emulador): valores de ejemplo. coolant < 0 = sin refrigerante. */
    public void demo(float coolant) {
        set("rpm", 850, "demo");
        if (coolant >= 0) set("coolant", coolant, "demo");
        set("transOil", 44, "demo");
        set("battery", 12.4f, "demo");
        set("outside", 24, "demo");
        set("speed", 0, "demo");
    }

    /** Informe para el diagnóstico. */
    public synchronized String report() {
        StringBuilder sb = new StringBuilder();
        sb.append("Velocidad=").append(speed).append(" RPM=").append(rpm).append(" Refrigerante=").append(coolant)
          .append(" AceiteCaja=").append(transOil).append(" Batería=").append(battery).append(" Exterior=").append(outside).append('\n');
        sb.append("Fuentes: ").append(src).append("\n\nPropiedades candidatas:\n");
        for (Map.Entry<String, String> e : candidates.entrySet()) sb.append("  ").append(e.getKey()).append(" = ").append(e.getValue()).append('\n');
        sb.append("\nBroadcasts recibidos:\n");
        for (String s : seen) sb.append("  ").append(s).append('\n');
        return sb.toString();
    }
}
