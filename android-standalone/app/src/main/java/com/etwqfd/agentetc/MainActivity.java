package com.etwqfd.agentetc;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {
    private WebView web;
    private NativeBridge bridge;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        installCrashLogger();
        Window w = getWindow();
        w.setStatusBarColor(Color.parseColor("#0b0b0d"));
        w.setNavigationBarColor(Color.parseColor("#0b0b0d"));
        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#0b0b0d"));
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setTextZoom(100);
        // 手机端性能优化：优先本地缓存、硬件层渲染，减少滚动白屏和重绘。
        s.setCacheMode(WebSettings.LOAD_CACHE_ELSE_NETWORK);
        try { s.setOffscreenPreRaster(true); } catch (Throwable ignored) {}
        try { web.setLayerType(View.LAYER_TYPE_HARDWARE, null); } catch (Throwable ignored) {}
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setWebViewClient(new WebViewClient());
        bridge = new NativeBridge(this, web);
        web.addJavascriptInterface(bridge, "Android");
        new Thread(() -> {
            TerminalRuntime.ensure(this);
            runOnUiThread(() -> {
                if (TerminalRuntime.isReady()) {
                    android.widget.Toast.makeText(this, "内置 32 位终端核心已就绪", android.widget.Toast.LENGTH_SHORT).show();
                } else if (!TerminalRuntime.error().isEmpty()) {
                    android.widget.Toast.makeText(this, "终端核心解压失败：" + TerminalRuntime.error(), android.widget.Toast.LENGTH_LONG).show();
                }
            });
        }, "termux-bootstrap").start();
        web.loadUrl("file:///android_asset/app.html");
        setContentView(web);
        startTaskService();
        if (android.os.Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 9001);
        }
        if (checkSelfPermission("android.permission.WRITE_EXTERNAL_STORAGE") != PackageManager.PERMISSION_GRANTED
                || checkSelfPermission("android.permission.ACCESS_FINE_LOCATION") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{
                "android.permission.READ_EXTERNAL_STORAGE",
                "android.permission.WRITE_EXTERNAL_STORAGE",
                "android.permission.ACCESS_FINE_LOCATION",
                "android.permission.ACCESS_COARSE_LOCATION"
            }, 9002);
        }
    }

    public void startTaskService() {
        try {
            Intent i = new Intent(this, AgentTaskService.class);
            if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
        } catch (Throwable ignored) {}
    }

    @Override
    protected void onResume() {
        super.onResume();
        AgentTaskService.refresh();
        if (web != null) {
            web.post(() -> {
                try { web.evaluateJavascript("window.__onResume&&window.__onResume()", null); } catch (Throwable ignored) {}
            });
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (bridge != null) bridge.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onBackPressed() {
        // 任务运行中不允许直接退出：返回键只回主页/后台，任务继续在通知栏运行。
        if (AgentTaskService.isTaskRunning()) {
            Toast.makeText(this, "任务仍在运行，已退到后台；通知栏可查看进度", Toast.LENGTH_SHORT).show();
            moveTaskToBack(true);
            return;
        }
        if (web != null && web.canGoBack()) web.goBack();
        else moveTaskToBack(true);
    }

    private void installCrashLogger() {
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                File dir = new File(getFilesDir(), "state");
                if (!dir.exists()) dir.mkdirs();
                StringWriter sw = new StringWriter();
                error.printStackTrace(new PrintWriter(sw));
                String text = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())
                        + "\n" + thread.getName() + "\n" + sw;
                try (FileOutputStream fos = new FileOutputStream(new File(dir, "last-crash.txt"))) {
                    fos.write(text.getBytes("UTF-8"));
                }
            } catch (Throwable ignored) {}
            if (previous != null) previous.uncaughtException(thread, error);
        });
    }

    public void toast(final String msg) {
        runOnUiThread(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show());
    }
}
