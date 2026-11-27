package kr.hajujangbu.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * 구글 로그인(앱 안의 웹뷰는 구글이 막아서, 기본 브라우저로 로그인하고 앱으로 돌아오는 표준 방식).
 * - PKCE + state 로 가로채기를 막아요.
 * - 새로고침 토큰은 폰의 안전 저장소(Keystore)로 암호화해서 보관해요.
 * - 권한은 drive.appdata (이 앱 전용 숨김 폴더) 하나뿐이에요.
 */
final class GoogleAuth {
    static final String SCOPE = "https://www.googleapis.com/auth/drive.appdata";
    static final String REDIRECT = "kr.hajujangbu.app:/oauth2redirect";
    private static final String ALIAS = "hj_oauth_key";
    private static final Object LOCK = new Object();

    private static String pendingVerifier = null;
    private static String pendingState = null;
    private static String access = "";
    private static long accessExp = 0;

    private GoogleAuth() {}

    /** 구글에서 못 받은 값(권한 오류 등) */
    static final class Rejected extends IOException {
        Rejected(String m) {
            super(m);
        }
    }

    static String clientId(Context c) {
        return c.getString(R.string.google_android_client_id).trim();
    }

    static boolean configured(Context c) {
        return !clientId(c).isEmpty();
    }

    private static String b64u(byte[] b) {
        return Base64.encodeToString(b, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("hj_auth", Context.MODE_PRIVATE);
    }

    /** 기본 브라우저로 구글 로그인 화면을 열어요 */
    static void start(Activity a) {
        try {
            String cid = clientId(a);
            if (cid.isEmpty()) return;
            SecureRandom r = new SecureRandom();
            byte[] v = new byte[48];
            r.nextBytes(v);
            byte[] s = new byte[16];
            r.nextBytes(s);
            String ver = b64u(v), st = b64u(s);
            String ch = b64u(MessageDigest.getInstance("SHA-256").digest(ver.getBytes(StandardCharsets.US_ASCII)));
            synchronized (LOCK) {
                pendingVerifier = ver;
                pendingState = st;
            }
            Uri u = Uri.parse("https://accounts.google.com/o/oauth2/v2/auth").buildUpon()
                    .appendQueryParameter("client_id", cid)
                    .appendQueryParameter("redirect_uri", REDIRECT)
                    .appendQueryParameter("response_type", "code")
                    .appendQueryParameter("scope", SCOPE)
                    .appendQueryParameter("code_challenge", ch)
                    .appendQueryParameter("code_challenge_method", "S256")
                    .appendQueryParameter("state", st)
                    .appendQueryParameter("access_type", "offline")
                    .appendQueryParameter("prompt", "select_account consent")
                    .build();
            a.startActivity(new Intent(Intent.ACTION_VIEW, u));
        } catch (Exception ignored) {
        }
    }

    /** 브라우저에서 앱으로 돌아왔을 때 호출. 내 요청이 맞을 때만 처리해요. */
    static boolean handleRedirect(final Context c, Uri d) {
        if (d == null || !"kr.hajujangbu.app".equals(d.getScheme()) || !"/oauth2redirect".equals(d.getPath()))
            return false;
        final String code = d.getQueryParameter("code"), st = d.getQueryParameter("state");
        final String ver;
        synchronized (LOCK) {
            boolean ok = pendingState != null && pendingState.equals(st) && code != null && !code.isEmpty();
            ver = pendingVerifier;
            pendingState = null;
            pendingVerifier = null;
            if (!ok) {
                MainActivity.notifyJs("window.hjGFail&&hjGFail()");
                return true;
            }
        }
        final String cid = clientId(c);
        new Thread(() -> {
            try {
                JSONObject j = post("https://oauth2.googleapis.com/token", form(
                        "client_id", cid, "code", code, "code_verifier", ver,
                        "redirect_uri", REDIRECT, "grant_type", "authorization_code"));
                keep(c, j);
                MainActivity.notifyJs("window.hjG&&hjG()");
            } catch (Exception e) {
                MainActivity.notifyJs("window.hjGFail&&hjGFail()");
            }
        }).start();
        return true;
    }

    private static void keep(Context c, JSONObject j) throws Exception {
        String at = j.optString("access_token", "");
        if (at.isEmpty()) throw new Rejected("no token");
        synchronized (LOCK) {
            access = at;
            accessExp = System.currentTimeMillis() + Math.max(60, j.optLong("expires_in", 3600)) * 1000L - 60000L;
        }
        String rt = j.optString("refresh_token", "");
        if (!rt.isEmpty()) sp(c).edit().putString("rt", seal(rt)).apply();
    }

    static boolean signedIn(Context c) {
        return sp(c).contains("rt");
    }

    /** 쓸 수 있는 접근 토큰. 만료됐으면 새로고침 토큰으로 조용히 새로 받아요. (네트워크를 쓰므로 화면 스레드에서 부르면 안 돼요) */
    static String token(Context c) {
        synchronized (LOCK) {
            if (!access.isEmpty() && System.currentTimeMillis() < accessExp) return access;
        }
        try {
            String enc = sp(c).getString("rt", "");
            if (enc.isEmpty()) return "";
            String rt = open(enc);
            JSONObject j = post("https://oauth2.googleapis.com/token", form(
                    "client_id", clientId(c), "refresh_token", rt, "grant_type", "refresh_token"));
            keep(c, j);
            synchronized (LOCK) {
                return access;
            }
        } catch (Rejected e) {
            clear(c); // 권한이 취소됐거나 만료된 경우: 다시 로그인하게
            return "";
        } catch (Exception e) {
            return "";
        }
    }

    private static void clear(Context c) {
        sp(c).edit().remove("rt").apply();
        synchronized (LOCK) {
            access = "";
            accessExp = 0;
        }
    }

    static void signOut(final Context c) {
        final String enc = sp(c).getString("rt", "");
        clear(c);
        if (enc.isEmpty()) return;
        new Thread(() -> {
            try {
                post("https://oauth2.googleapis.com/revoke", form("token", open(enc)));
            } catch (Exception ignored) {
            }
        }).start();
    }

    // ---- 네트워크 ----
    private static String form(String... kv) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (sb.length() > 0) sb.append('&');
            sb.append(URLEncoder.encode(kv[i], "UTF-8")).append('=').append(URLEncoder.encode(kv[i + 1], "UTF-8"));
        }
        return sb.toString();
    }

    private static JSONObject post(String url, String body) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        try {
            h.setRequestMethod("POST");
            h.setConnectTimeout(15000);
            h.setReadTimeout(20000);
            h.setDoOutput(true);
            h.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            try (OutputStream os = h.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
            int code = h.getResponseCode();
            InputStream is = code >= 400 ? h.getErrorStream() : h.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            if (is != null) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = is.read(buf)) > 0 && bo.size() < 200000) bo.write(buf, 0, n);
            }
            String txt = bo.toString("UTF-8");
            if (code >= 400) {
                if (code == 400 || code == 401) throw new Rejected(txt);
                throw new IOException("http " + code);
            }
            return txt.isEmpty() ? new JSONObject() : new JSONObject(txt);
        } finally {
            h.disconnect();
        }
    }

    // ---- 새로고침 토큰 암호화 (Android Keystore, 꺼낼 수 없는 키) ----
    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (!ks.containsAlias(ALIAS)) {
            KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            kg.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build());
            kg.generateKey();
        }
        return (SecretKey) ks.getKey(ALIAS, null);
    }

    private static String seal(String s) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, key());
        byte[] ct = c.doFinal(s.getBytes(StandardCharsets.UTF_8));
        return Base64.encodeToString(c.getIV(), Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP);
    }

    private static String open(String enc) throws Exception {
        String[] p = enc.split(":");
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(p[0], Base64.NO_WRAP)));
        return new String(c.doFinal(Base64.decode(p[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
    }
}
