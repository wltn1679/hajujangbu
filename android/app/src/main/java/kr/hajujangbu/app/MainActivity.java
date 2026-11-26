package kr.hajujangbu.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class MainActivity extends Activity {
    static final String HOST = "wltn1679.github.io";
    static final String URL = "https://" + HOST + "/hajujangbu/";
    private static final int REQ_FILE = 1;

    private WebView web;
    private ValueCallback<Uri[]> fileCb;
    private boolean askedThisRun = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setUserAgentString(s.getUserAgentString() + " HajujangbuApp/1");

        web.addJavascriptInterface(new Bridge(this), "HJAndroid");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if ("https".equals(u.getScheme()) && HOST.equals(u.getHost())) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (Exception ignored) {
                }
                return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView w, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCb != null) fileCb.onReceiveValue(null);
                fileCb = cb;
                try {
                    startActivityForResult(p.createIntent(), REQ_FILE);
                } catch (Exception e) {
                    fileCb = null;
                    return false;
                }
                return true;
            }
        });

        if (b != null) web.restoreState(b);
        else web.loadUrl(URL);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
        if (!askedThisRun && !NotifService.isEnabled(this)) {
            askedThisRun = true;
            new AlertDialog.Builder(this)
                    .setTitle("결제 알림 자동 기록")
                    .setMessage("카드 승인·입금 알림을 자동으로 장부에 적으려면 '알림 접근 허용'이 필요해요.\n\n"
                            + "1) [설정 열기] → 하루장부를 켜 주세요.\n"
                            + "2) 켜지지 않고 막혀 있으면: 설정 앱 → 앱 → 하루장부 → 오른쪽 위 ⋮ → '제한된 설정 허용' 후 다시 1)을 해 주세요.")
                    .setPositiveButton("설정 열기", (d, w) -> openListenerSettings(this))
                    .setNegativeButton("나중에", null)
                    .show();
        } else if (NotifService.isEnabled(this)) {
            SharedPreferences p = getSharedPreferences("hj_ui", Context.MODE_PRIVATE);
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (!p.getBoolean("batAsked", false) && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                p.edit().putBoolean("batAsked", true).apply();
                new AlertDialog.Builder(this)
                        .setTitle("알림이 끊기지 않게 해 주세요")
                        .setMessage("폰이 절전을 위해 앱을 멈추면 알림을 놓칠 수 있어요. '허용'을 눌러 배터리 제한을 풀어 주세요.")
                        .setPositiveButton("설정 열기", (d, w) -> openBatterySettings(this))
                        .setNegativeButton("나중에", null)
                        .show();
            }
        }
    }

    @Override
    protected void onPause() {
        web.onPause();
        super.onPause();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == REQ_FILE && fileCb != null) {
            fileCb.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, data));
            fileCb = null;
            return;
        }
        super.onActivityResult(req, res, data);
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    static void openListenerSettings(Activity a) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                Intent i = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS);
                i.putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                        new ComponentName(a, NotifService.class).flattenToString());
                a.startActivity(i);
                return;
            }
        } catch (Exception ignored) {
        }
        try {
            a.startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        } catch (Exception ignored) {
        }
    }

    static void openBatterySettings(Activity a) {
        try {
            a.startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + a.getPackageName())));
        } catch (Exception e) {
            try {
                a.startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception ignored) {
            }
        }
    }
}
