package kr.hajujangbu.app;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import org.json.JSONObject;

import java.util.regex.Pattern;

/** 카드 승인·입금 알림을 읽어 대기열에 쌓아요. 해석은 웹 앱(index.html)이 해요. */
public class NotifService extends NotificationListenerService {
    // 금액이 있고 금융 관련 단어가 있는 알림만 보관 (나머지는 버려요)
    private static final Pattern MONEY = Pattern.compile("\\d[\\d,]*\\s*원|(?:입금|출금)\\s*[:：]?\\s*\\d");
    private static final Pattern WORDS = Pattern.compile("승인|결제|출금|입금|취소|이체|송금|환불|사용");

    private static String lastKey = "";
    private static long lastTime = 0;

    static boolean isEnabled(Context c) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            return nm.isNotificationListenerAccessGranted(new ComponentName(c, NotifService.class));
        } catch (Throwable t) {
            return false;
        }
    }

    private static String cs(CharSequence s) {
        return s == null ? "" : s.toString().trim();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        try {
            if (sbn == null || getPackageName().equals(sbn.getPackageName())) return;
            Notification n = sbn.getNotification();
            if (n == null || (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;
            Bundle e = n.extras;
            if (e == null) return;

            String title = cs(e.getCharSequence(Notification.EXTRA_TITLE));
            String text = cs(e.getCharSequence(Notification.EXTRA_BIG_TEXT));
            if (text.isEmpty()) text = cs(e.getCharSequence(Notification.EXTRA_TEXT));
            if (text.isEmpty()) {
                CharSequence[] lines = e.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
                if (lines != null) {
                    StringBuilder sb = new StringBuilder();
                    for (CharSequence l : lines) sb.append(cs(l)).append(' ');
                    text = sb.toString().trim();
                }
            }
            if (text.isEmpty()) return;

            String all = title + " " + text;
            if (!MONEY.matcher(all).find() || !WORDS.matcher(all).find()) return;

            long now = System.currentTimeMillis();
            String key = sbn.getPackageName() + "|" + text;
            synchronized (NotifService.class) {
                if (key.equals(lastKey) && now - lastTime < 15000) return; // 같은 알림이 갱신돼 다시 오는 경우
                lastKey = key;
                lastTime = now;
            }

            JSONObject o = new JSONObject();
            o.put("pkg", sbn.getPackageName());
            o.put("title", title);
            o.put("text", text);
            o.put("t", sbn.getPostTime());
            Store.add(this, o);
        } catch (Throwable ignored) {
            // 알림 하나 때문에 서비스가 멈추지 않게 해요
        }
    }

    @Override
    public void onListenerDisconnected() {
        try {
            NotificationListenerService.requestRebind(new ComponentName(this, NotifService.class));
        } catch (Throwable ignored) {
        }
    }
}
