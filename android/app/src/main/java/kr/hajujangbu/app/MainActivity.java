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
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class MainActivity extends Activity {
    static final String HOST = "wltn1679.github.io";
    static final String URL = "https://" + HOST + "/hajujangbu/";
    private static final int REQ_FILE = 1;

    /** 지금 화면이 내 사이트일 때만 true. 브리지는 이때만 동작해요. */
    static volatile boolean trusted = false;
    private static volatile WebView WEB;

    /** 화면(웹)에 자바스크립트 한 줄을 알려요 (로그인 끝남 등) */
    static void notifyJs(final String js) {
        final WebView w = WEB;
        if (w != null) w.post(() -> w.evaluateJavascript(js, null));
    }

    private WebView web;
    private ValueCallback<Uri[]> fileCb;
    private boolean askedThisRun = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE, android.view.WindowManager.LayoutParams.FLAG_SECURE); // 화면 캡처·최근 앱 미리보기에서 금액이 보이지 않게
        web = new WebView(this);
        WEB = web;
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setSafeBrowsingEnabled(true);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setSupportMultipleWindows(false);
        s.setGeolocationEnabled(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setUserAgentString(s.getUserAgentString() + " HajujangbuApp/1");

        web.addJavascriptInterface(new Bridge(this), "HJAndroid");
        web.setWebViewClient(new WebViewClient() {
/** 구글 드라이브 API(이 앱 전용 폴더 저장용)만 예외로 허용 */            private boolean drive(Uri u) {                return u != null && "https".equals(u.getScheme()) && "www.googleapis.com".equals(u.getHost()) && u.getPath() != null                        && (u.getPath().startsWith("/drive/v3/") || u.getPath().startsWith("/upload/drive/v3/"));            }
            private boolean mine(Uri u) {
                // 주소는 https + 내 사이트 + 내 폴더(/hajujangbu/)만 허용 (같은 github.io 아래 다른 페이지는 거부)
                return u != null && "https".equals(u.getScheme()) && HOST.equals(u.getHost())
                        && u.getPath() != null && u.getPath().startsWith("/hajujangbu/") && u.getPort() == -1;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                // 바깥으로 나가는 이동은 전부 막아요. (페이지가 알림 내용을 주소에 실어 밖으로 보내는 것도 막기 위해
                // 외부 브라우저·메일 앱 열기도 하지 않아요)
                return !mine(r.getUrl());
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                // 내 사이트 말고는 어디에도 연결하지 못하게 막아요 (알림 내용이 밖으로 새지 않게)
                String sc = String.valueOf(r.getUrl().getScheme());
                if (mine(r.getUrl()) || drive(r.getUrl()) || "blob".equals(sc) || "data".equals(sc)) return null;
                return new WebResourceResponse("text/plain", "utf-8", 403, "Blocked", new java.util.HashMap<String, String>(),
                        new java.io.ByteArrayInputStream(new byte[0]));
            }

            @Override
            public void onPageStarted(WebView v, String url, android.graphics.Bitmap icon) {
                trusted = mine(Uri.parse(url));
                if (!trusted) {
                    v.stopLoading();
                    v.loadUrl(URL);
                }
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                trusted = mine(Uri.parse(String.valueOf(url)));
            }
            @Override
            public void onReceivedSslError(WebView v, android.webkit.SslErrorHandler h, android.net.http.SslError e) {
                h.cancel(); // 인증서가 이상하면 절대 진행하지 않아요
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

        GoogleAuth.handleRedirect(this, getIntent() == null ? null : getIntent().getData());
        if (b != null) web.restoreState(b);
        else web.loadUrl(URL);
    }


    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        GoogleAuth.handleRedirect(this, i.getData());
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
