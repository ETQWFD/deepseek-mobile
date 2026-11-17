package com.etwqfd.agentetc;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

/** Keeps the agent process alive while the Activity is in the background and mirrors task state
 *  to an ongoing notification + optional floating status window. */
public class AgentTaskService extends Service {
    public static final String CHANNEL = "agent_etc_tasks";
    public static final int NOTIF_ID = 4101;
    private static volatile AgentTaskService instance;
    private static volatile String state = "idle";
    private static volatile String title = "DeepSeek Mobile";
    private static volatile String detail = "待命";
    private PowerManager.WakeLock wake;
    private TextView overlay;
    private WindowManager wm;
    private final Handler ui = new Handler(Looper.getMainLooper());

    public static void update(String s, String t, String d) {
        state = s == null ? "idle" : s;
        title = t == null || t.isEmpty() ? "DeepSeek Mobile" : t;
        detail = d == null ? "" : d;
        AgentTaskService i = instance;
        if (i != null) i.ui.post(i::render);
    }

    public static boolean isRunning() { return instance != null; }
    public static boolean isTaskRunning() { return "running".equals(state) || "queued".equals(state); }

    public static void refresh() {
        AgentTaskService i = instance;
        if (i != null) i.ui.post(() -> { i.addOverlay(); i.render(); });
    }

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        createChannel();
        startForeground(NOTIF_ID, buildNotification());
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "agent_etc:task");
            wake.setReferenceCounted(false);
            wake.acquire();
        } catch (Throwable ignored) {}
        addOverlay();
        render();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            update(intent.getStringExtra("state"), intent.getStringExtra("title"), intent.getStringExtra("detail"));
        }
        return START_STICKY;
    }

    @Override public void onTaskRemoved(Intent rootIntent) {
        // 从最近任务划掉后请求系统保留服务；通知栏仍在，任务状态不主动清空。
        try {
            Intent restart = new Intent(this, AgentTaskService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(restart); else startService(restart);
        } catch (Throwable ignored) {}
        super.onTaskRemoved(rootIntent);
    }

    @Override public void onDestroy() {
        try { if (wake != null && wake.isHeld()) wake.release(); } catch (Throwable ignored) {}
        removeOverlay();
        instance = null;
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel(CHANNEL, "Agent 任务", NotificationManager.IMPORTANCE_LOW);
            c.setDescription("显示 Agent 当前任务与后台运行状态");
            c.setShowBadge(false);
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(c);
        }
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        String text = (state.equals("idle") ? "后台待命" : (state.equals("error") ? "任务失败" : (state.equals("done") ? "任务完成" : "正在工作"))) + " · " + detail;
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        b.setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .setOngoing(true)
                .setContentIntent(pi)
                .setShowWhen(false);
        return b.build();
    }

    private void render() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIF_ID, buildNotification());
        } catch (Throwable ignored) {}
        if (overlay == null) addOverlay();
        if (overlay != null) {
            overlay.setText("● " + (state.equals("running") ? "工作中" : state.equals("error") ? "失败" : state.equals("done") ? "完成" : "待命") + "\n" + title + "\n" + detail);
        }
    }

    private void addOverlay() {
        if (Build.VERSION.SDK_INT < 23 || !Settings.canDrawOverlays(this)) return;
        try {
            wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            overlay = new TextView(this);
            overlay.setTextColor(Color.WHITE);
            overlay.setTextSize(11);
            overlay.setPadding(14, 10, 14, 10);
            overlay.setMaxLines(4);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.parseColor("#E6101712"));
            bg.setCornerRadius(28);
            bg.setStroke(1, Color.parseColor("#3B6A50"));
            overlay.setBackground(bg);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                    Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.x = 16; lp.y = 80;
            overlay.setAlpha(0.92f);
            // 悬浮窗可拖动：按住移动，松手停留在新位置，不再固定挡屏。
            overlay.setOnTouchListener(new View.OnTouchListener() {
                private float downX, downY;
                private int startX, startY;
                @Override public boolean onTouch(View v, android.view.MotionEvent e) {
                    switch (e.getActionMasked()) {
                        case android.view.MotionEvent.ACTION_DOWN:
                            downX = e.getRawX(); downY = e.getRawY();
                            startX = lp.x; startY = lp.y;
                            return true;
                        case android.view.MotionEvent.ACTION_MOVE:
                            lp.x = startX + (int)(e.getRawX() - downX);
                            lp.y = startY + (int)(e.getRawY() - downY);
                            try { wm.updateViewLayout(overlay, lp); } catch (Throwable ignored) {}
                            return true;
                        case android.view.MotionEvent.ACTION_UP:
                        case android.view.MotionEvent.ACTION_CANCEL:
                            return true;
                    }
                    return false;
                }
            });
            wm.addView(overlay, lp);
        } catch (Throwable e) {
            overlay = null;
        }
    }

    private void removeOverlay() {
        try { if (wm != null && overlay != null) wm.removeView(overlay); } catch (Throwable ignored) {}
        overlay = null; wm = null;
    }
}
