package kr.hajujangbu.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

/** 알림 대기열: 앱이 꺼져 있어도 받은 알림을 보관했다가 화면이 열리면 웹 앱에 넘겨요. */
final class Store {
    private static final String PREF = "hj_queue";
    private static final int MAX = 300;

    private Store() {}

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    private static JSONArray arr(SharedPreferences p, String k) {
        try {
            return new JSONArray(p.getString(k, "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    static synchronized void add(Context c, JSONObject o) {
        SharedPreferences p = sp(c);
        JSONArray a = arr(p, "pending");
        a.put(o);
        JSONArray cut = new JSONArray();
        long old = System.currentTimeMillis() - 7L * 24 * 3600 * 1000; // 일주일 넘게 안 가져간 알림은 버려요
        for (int i = Math.max(0, a.length() - MAX); i < a.length(); i++) {
            JSONObject x = a.optJSONObject(i);
            if (x != null && x.optLong("t", 0) >= old) cut.put(x);
        }
        p.edit()
                .putString("pending", cut.toString())
                .putInt("received", p.getInt("received", 0) + 1)
                .putLong("last", System.currentTimeMillis())
                .apply();
    }

    /** 대기 중인 알림을 꺼내요. 웹 앱이 처리를 마치면 ack()로 확정하고, 아니면 다음에 다시 받아요. */
    static synchronized String drain(Context c) {
        SharedPreferences p = sp(c);
        JSONArray inflight = arr(p, "inflight");
        JSONArray pending = arr(p, "pending");
        for (int i = 0; i < pending.length(); i++) inflight.put(pending.opt(i));
        p.edit().putString("inflight", inflight.toString()).putString("pending", "[]").apply();
        return inflight.toString();
    }

    static synchronized void ack(Context c) {
        sp(c).edit().putString("inflight", "[]").apply();
    }

    static synchronized int received(Context c) {
        return sp(c).getInt("received", 0);
    }

    static synchronized long last(Context c) {
        return sp(c).getLong("last", 0L);
    }
}
