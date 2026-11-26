package kr.hajujangbu.app;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.Settings;
import android.webkit.JavascriptInterface;

import org.json.JSONObject;

import java.io.OutputStream;

/** 웹 앱(index.html)이 window.HJAndroid 로 부르는 기능들 */
final class Bridge {
    private final Activity act;

    Bridge(Activity a) {
        act = a;
    }

    @JavascriptInterface
    public String drain() {
        if (!MainActivity.trusted) return "[]";
        return Store.drain(act);
    }

    @JavascriptInterface
    public void ack() {
        if (!MainActivity.trusted) return;
        Store.ack(act);
    }

    @JavascriptInterface
    public String status() {
        if (!MainActivity.trusted) return "{}";
        try {
            JSONObject o = new JSONObject();
            o.put("enabled", NotifService.isEnabled(act));
            o.put("received", Store.received(act));
            o.put("last", Store.last(act));
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    @JavascriptInterface
    public void openListenerSettings() {
        if (!MainActivity.trusted) return;
        MainActivity.openListenerSettings(act);
    }

    @JavascriptInterface
    public void openAppInfo() {
        if (!MainActivity.trusted) return;
        try {
            act.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + act.getPackageName())));
        } catch (Exception ignored) {
        }
    }

    @JavascriptInterface
    public void openBatterySettings() {
        if (!MainActivity.trusted) return;
        MainActivity.openBatterySettings(act);
    }

    @JavascriptInterface
    public boolean saveBackup(String name, String json) {
        if (!MainActivity.trusted || json == null || json.length() > 40_000_000) return false; // 크기 제한
        try {
            String safe = name == null ? "hajujangbu-backup.json" : name.replaceAll("[^\\w가-힣.\\-]", "_");
            ContentValues v = new ContentValues();
            v.put(MediaStore.Downloads.DISPLAY_NAME, safe);
            v.put(MediaStore.Downloads.MIME_TYPE, "application/json");
            v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            Uri u = act.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (u == null) return false;
            try (OutputStream os = act.getContentResolver().openOutputStream(u)) {
                if (os == null) return false;
                os.write(json.getBytes("UTF-8"));
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
