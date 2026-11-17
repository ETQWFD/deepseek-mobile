package com.etwqfd.agentetc;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class NativeBridge {
    private final Activity activity;
    private final WebView web;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private String pendingPickId = null;
    private String pendingWorkspaceId = null;
    private String pendingTextId = null;
    private static final int REQ_MODEL = 7001;
    private static final int REQ_WORKSPACE = 7002;
    private static final int REQ_TEXT = 7003;
    private Uri workspaceUri = null;

    public NativeBridge(Activity a, WebView w) {
        activity = a;
        web = w;
        String saved = a.getSharedPreferences("agent_etc", Context.MODE_PRIVATE).getString("workspaceUri", "");
        if (!saved.isEmpty()) {
            try { workspaceUri = Uri.parse(saved); } catch (Exception ignored) {}
        }
    }

    private void js(final String code) {
        ui.post(() -> web.evaluateJavascript(code, null));
    }

    private static String q(String s) { return JSONObject.quote(s == null ? "" : s); }

    @JavascriptInterface
    public void toast(String msg) { activity.runOnUiThread(() -> android.widget.Toast.makeText(activity, msg, android.widget.Toast.LENGTH_SHORT).show()); }

    @JavascriptInterface
    public String deviceInfo() {
        try {
            JSONObject o = new JSONObject();
            o.put("model", Build.MODEL);
            o.put("brand", Build.BRAND);
            o.put("manufacturer", Build.MANUFACTURER);
            o.put("device", Build.DEVICE);
            o.put("product", Build.PRODUCT);
            o.put("android", Build.VERSION.RELEASE);
            o.put("sdk", Build.VERSION.SDK_INT);
            o.put("abi", Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "");
            try {
                android.util.DisplayMetrics dm = activity.getResources().getDisplayMetrics();
                o.put("width", dm.widthPixels);
                o.put("height", dm.heightPixels);
                o.put("density", dm.densityDpi);
            } catch (Throwable ignored) {}
            o.put("locale", java.util.Locale.getDefault().toString());
            return o.toString();
        } catch (Exception e) { return "{}"; }
    }

    @JavascriptInterface
    public String deviceStats() {
        try {
            JSONObject o = new JSONObject();
            o.put("cores", Runtime.getRuntime().availableProcessors());
            String load = "0 0 0";
            try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream("/proc/loadavg"), StandardCharsets.UTF_8))) {
                load = r.readLine();
            } catch (Throwable ignored) {}
            o.put("load", load == null ? "0 0 0" : load);
            ActivityManager am = (ActivityManager) activity.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
                am.getMemoryInfo(mi);
                o.put("memAvail", mi.availMem);
                o.put("memTotal", mi.totalMem);
                o.put("lowMemory", mi.lowMemory);
            }
            StatFs stat = new StatFs(activity.getFilesDir().getAbsolutePath());
            o.put("diskFree", stat.getAvailableBytes());
            o.put("diskTotal", stat.getTotalBytes());
            Intent battery = activity.registerReceiver(null, new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (battery != null) {
                int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                o.put("battery", scale > 0 ? (level * 100 / scale) : level);
                int st = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                o.put("charging", st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL);
            }
            o.put("appVersion", "1.6.16");
            return o.toString();
        } catch (Throwable e) { return "{}"; }
    }

    // ---------- HTTP ----------
    @JavascriptInterface
    public void httpStart(final String id, final String method, final String url, final String headersJson, final String body) {
        new Thread(() -> {
            JSONObject out = new JSONObject();
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestMethod(method == null || method.isEmpty() ? "GET" : method.toUpperCase());
                conn.setConnectTimeout(20000);
                conn.setReadTimeout(120000);
                conn.setRequestProperty("User-Agent", "agent_etc-mobile/1.6.0");
                if (headersJson != null && !headersJson.isEmpty()) {
                    JSONObject h = new JSONObject(headersJson);
                    Iterator<String> it = h.keys();
                    while (it.hasNext()) {
                        String k = it.next();
                        conn.setRequestProperty(k, h.optString(k));
                    }
                }
                if (body != null && !body.isEmpty()) {
                    conn.setDoOutput(true);
                    try (OutputStream os = conn.getOutputStream()) {
                        os.write(body.getBytes(StandardCharsets.UTF_8));
                    }
                }
                int status = conn.getResponseCode();
                InputStream in = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
                StringBuilder sb = new StringBuilder();
                if (in != null) {
                    try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                        String line;
                        int total = 0;
                        while ((line = br.readLine()) != null) {
                            sb.append(line).append('\n');
                            total += line.length();
                            if (total > 12_000_000) break;
                        }
                    }
                }
                out.put("status", status);
                out.put("body", sb.toString());
                out.put("error", "");
            } catch (Exception e) {
                try { out.put("status", 0); out.put("body", ""); out.put("error", e.getMessage() == null ? e.toString() : e.getMessage()); } catch (Exception ignored) {}
            } finally { if (conn != null) conn.disconnect(); }
            js("window.__httpResult(" + q(id) + "," + q(out.toString()) + ")");
        }).start();
    }

    // ---------- local model ----------
    // ---------- accessibility screen (user-granted) ----------
    @JavascriptInterface
    public String accessibilityEnabled() {
        return AgentAccessibilityService.isReady() ? "true" : "false";
    }

    @JavascriptInterface
    public String screenRead() {
        if (!AgentAccessibilityService.isReady()) return "{\"enabled\":false,\"error\":\"无障碍服务未开启，请在系统设置里授权 agent_etc\"}";
        return AgentAccessibilityService.dumpScreen();
    }

    @JavascriptInterface
    public String screenTap(int x, int y) { return AgentAccessibilityService.tap(x, y); }

    @JavascriptInterface
    public String screenBack() { return AgentAccessibilityService.back(); }

    @JavascriptInterface
    public String openAccessibility() {
        try {
            Intent i = new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(i);
            return "已打开系统无障碍设置；请找到 agent_etc 并开启，然后回到这里点“读一次屏幕”。";
        } catch (Exception e) { return "打开设置失败：" + e.getMessage(); }
    }

    @JavascriptInterface
    public void pickModel(final String id) {
        pendingPickId = id;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        ui.post(() -> activity.startActivityForResult(i, REQ_MODEL));
    }

    @JavascriptInterface
    public void pickTextFile(final String id) {
        pendingTextId = id;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        ui.post(() -> activity.startActivityForResult(i, REQ_TEXT));
    }

    private String displayName(Uri uri, String fallback) {
        try (Cursor c = activity.getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    String n = c.getString(idx);
                    if (n != null && !n.isEmpty()) return n;
                }
            }
        } catch (Exception ignored) {}
        String seg = uri.getLastPathSegment();
        return seg == null || seg.isEmpty() ? fallback : seg;
    }

    private String readText(Uri uri, String name) throws Exception {
        String type = activity.getContentResolver().getType(uri);
        String lower = name == null ? "" : name.toLowerCase();
        boolean byExt = lower.endsWith(".md") || lower.endsWith(".txt") || lower.endsWith(".json")
                || lower.endsWith(".yaml") || lower.endsWith(".yml") || lower.endsWith(".csv")
                || lower.endsWith(".log") || lower.endsWith(".xml") || lower.endsWith(".html")
                || lower.endsWith(".js") || lower.endsWith(".ts") || lower.endsWith(".java")
                || lower.endsWith(".py") || lower.endsWith(".sh") || lower.endsWith(".toml");
        if (type != null && !type.startsWith("text/") && !type.equals("application/json")
                && !type.equals("application/xml") && !type.equals("application/x-yaml") && !byExt) {
            throw new Exception("不是文本文件（" + type + "）");
        }
        StringBuilder sb = new StringBuilder();
        try (InputStream in = activity.getContentResolver().openInputStream(uri);
             BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (sb.length() + line.length() > 262144) break;
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_TEXT) {
            final String id = pendingTextId == null ? "text" : pendingTextId;
            pendingTextId = null;
            if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
                js("window.__textPicked(" + q(id) + "," + q("") + "," + q("") + "," + q("已取消") + ")");
                return;
            }
            final Uri uri = data.getData();
            new Thread(() -> {
                try {
                    String name = displayName(uri, "attachment.txt");
                    String text = readText(uri, name);
                    js("window.__textPicked(" + q(id) + "," + q(name) + "," + q(text) + "," + q("") + ")");
                } catch (Exception e) {
                    js("window.__textPicked(" + q(id) + "," + q("") + "," + q("") + "," + q(String.valueOf(e.getMessage())) + ")");
                }
            }).start();
            return;
        }
        if (requestCode == REQ_WORKSPACE) {
            final String id = pendingWorkspaceId == null ? "workspace" : pendingWorkspaceId;
            pendingWorkspaceId = null;
            if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
                Uri uri = data.getData();
                persistWorkspace(uri);
                js("window.__workspacePicked(" + q(id) + "," + q(workspaceName()) + "," + q(uri.toString()) + "," + q("") + ")");
            } else {
                js("window.__workspacePicked(" + q(id) + "," + q("") + "," + q("") + "," + q("已取消") + ")");
            }
            return;
        }
        if (requestCode != REQ_MODEL) return;
        final String id = pendingPickId == null ? "model" : pendingPickId;
        pendingPickId = null;
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            js("window.__modelPicked(" + q(id) + "," + q("") + "," + q("") + ",0," + q("已取消") + ")");
            return;
        }
        final Uri uri = data.getData();
        new Thread(() -> {
            try {
                String name = "model.gguf";
                try (InputStream is = activity.getContentResolver().openInputStream(uri)) {
                    ((MainActivity) activity).toast("正在导入模型，大文件需要一点时间…");
                }
                String display = uri.getLastPathSegment();
                if (display != null) {
                    int slash = display.lastIndexOf('/');
                    if (slash >= 0) display = display.substring(slash + 1);
                    if (display.toLowerCase().endsWith(".gguf")) name = display;
                }
                File dir = new File(activity.getFilesDir(), "models");
                if (!dir.exists()) dir.mkdirs();
                File out = new File(dir, name.replaceAll("[^A-Za-z0-9._\\-\\u4e00-\\u9fa5]", "_"));
                try (InputStream in = activity.getContentResolver().openInputStream(uri);
                     FileOutputStream fos = new FileOutputStream(out)) {
                    byte[] buf = new byte[8192];
                    int n; long total = 0;
                    while ((n = in.read(buf)) > 0) { fos.write(buf, 0, n); total += n; }
                }
                final long size = out.length();
                js("window.__modelPicked(" + q(id) + "," + q(out.getName()) + "," + q(out.getAbsolutePath()) + "," + size + "," + q("") + ")");
            } catch (Exception e) {
                js("window.__modelPicked(" + q(id) + "," + q("") + "," + q("") + ",0," + q(String.valueOf(e.getMessage())) + ")");
            }
        }).start();
    }

    @JavascriptInterface
    public void rememberModel(String path, String name) {
        try {
            activity.getSharedPreferences("agent_etc", Context.MODE_PRIVATE).edit()
                    .putString("lastModelPath", path == null ? "" : path)
                    .putString("lastModelName", name == null ? "" : name).apply();
        } catch (Throwable ignored) {}
    }

    @JavascriptInterface
    public String lastModel() {
        try {
            SharedPreferences p = activity.getSharedPreferences("agent_etc", Context.MODE_PRIVATE);
            JSONObject o = new JSONObject();
            o.put("path", p.getString("lastModelPath", ""));
            o.put("name", p.getString("lastModelName", ""));
            return o.toString();
        } catch (Throwable e) { return "{}"; }
    }

    @JavascriptInterface
    public boolean localReady() {
        try { return LlamaBridge.ready(); } catch (Throwable e) { return false; }
    }

    @JavascriptInterface
    public void loadLocal(final String id, final String path, final int nCtx, final int threads) {
        new Thread(() -> {
            try {
                if (!LlamaBridge.available()) {
                    js("window.__localStatus(" + q(id) + ",false," + q("本地推理库加载失败：" + LlamaBridge.loadErrorText()) + ",{})");
                    return;
                }
                boolean ok = LlamaBridge.load(path, nCtx, threads, 0);
                String err = ok ? "" : LlamaBridge.lastError();
                String info = ok ? LlamaBridge.nativeInfo(LlamaBridge.getHandle()) : "{}";
                js("window.__localStatus(" + q(id) + "," + (ok ? "true" : "false") + "," + q(err) + "," + q(info) + ")");
            } catch (Throwable e) {
                js("window.__localStatus(" + q(id) + ",false," + q(String.valueOf(e.getMessage())) + ",{})");
            }
        }).start();
    }

    @JavascriptInterface
    public void localInfo(final String id) {
        try {
            String info = LlamaBridge.ready() ? LlamaBridge.nativeInfo(LlamaBridge.getHandle()) : "{}";
            js("window.__localInfo(" + q(id) + "," + q(info) + ")");
        } catch (Throwable e) { js("window.__localInfo(" + q(id) + ",{})"); }
    }

    @JavascriptInterface
    public void unloadLocal() { LlamaBridge.unload(); }

    @JavascriptInterface
    public void cancelLocal() { try { LlamaBridge.requestStop(); } catch (Throwable ignored) {} }

    @JavascriptInterface
    public String shellExec(String command, String cwd) {
        return TerminalRuntime.exec(activity, command, cwd);
    }

    @JavascriptInterface
    public void localChat(final String id, final String rolesJson, final String contentsJson,
                          final int maxTokens, final float temp, final float topP, final int topK) {
        new Thread(() -> {
            try {
                if (!LlamaBridge.ready()) {
                    js("window.__localReply(" + q(id) + "," + q("") + "," + q("本地模型还没有加载") + ")");
                    return;
                }
                JSONArray roles = new JSONArray(rolesJson);
                JSONArray contents = new JSONArray(contentsJson);
                int n = Math.min(roles.length(), contents.length());
                String[] r = new String[n], c = new String[n];
                for (int i = 0; i < n; i++) { r[i] = roles.optString(i, "user"); c[i] = contents.optString(i, ""); }
                String text = LlamaBridge.chat(r, c, maxTokens, temp, topP, topK);
                if (text == null) text = "";
                String err = text.isEmpty() ? LlamaBridge.lastError() : "";
                js("window.__localReply(" + q(id) + "," + q(text) + "," + q(err) + ")");
            } catch (Throwable e) {
                js("window.__localReply(" + q(id) + "," + q("") + "," + q(String.valueOf(e.getMessage())) + ")");
            }
        }).start();
    }

    // ---------- workspace (SAF directory) ----------
    @JavascriptInterface
    public void pickWorkspace(final String id) {
        pendingWorkspaceId = id;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        ui.post(() -> activity.startActivityForResult(i, REQ_WORKSPACE));
    }

    private boolean persistWorkspace(Uri uri) {
        try {
            activity.getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (Exception ignored) {}
        workspaceUri = uri;
        SharedPreferences.Editor e = activity.getSharedPreferences("agent_etc", Context.MODE_PRIVATE).edit();
        e.putString("workspaceUri", uri.toString());
        e.apply();
        return true;
    }

    private Uri rootDocUri() {
        if (workspaceUri == null) return null;
        String id = DocumentsContract.getTreeDocumentId(workspaceUri);
        return DocumentsContract.buildDocumentUriUsingTree(workspaceUri, id);
    }

    private String workspaceName() {
        Uri root = rootDocUri();
        if (root == null) return "";
        try (Cursor c = activity.getContentResolver().query(root,
                new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) {}
        return "已选择目录";
    }

    private Uri findInWorkspace(String name) {
        Uri root = rootDocUri();
        if (root == null) return null;
        String rootId = DocumentsContract.getTreeDocumentId(workspaceUri);
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(workspaceUri, rootId);
        try (Cursor c = activity.getContentResolver().query(children,
                new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME},
                null, null, null)) {
            if (c != null) while (c.moveToNext()) {
                if (name.equals(c.getString(1))) {
                    return DocumentsContract.buildDocumentUriUsingTree(workspaceUri, c.getString(0));
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    @JavascriptInterface
    public String workspaceInfo() {
        try {
            JSONObject o = new JSONObject();
            o.put("ready", workspaceUri != null);
            o.put("name", workspaceUri == null ? "" : workspaceName());
            o.put("uri", workspaceUri == null ? "" : workspaceUri.toString());
            return o.toString();
        } catch (Exception e) { return "{\"ready\":false}"; }
    }

    @JavascriptInterface
    public String wsList() {
        try {
            JSONArray arr = new JSONArray();
            Uri root = rootDocUri();
            if (root == null) return arr.toString();
            String rootId = DocumentsContract.getTreeDocumentId(workspaceUri);
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(workspaceUri, rootId);
            try (Cursor c = activity.getContentResolver().query(children,
                    new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE},
                    null, null, null)) {
                if (c != null) while (c.moveToNext()) {
                    JSONObject o = new JSONObject();
                    o.put("name", c.getString(0));
                    o.put("dir", DocumentsContract.Document.MIME_TYPE_DIR.equals(c.getString(1)));
                    o.put("size", c.isNull(2) ? 0 : c.getLong(2));
                    arr.put(o);
                }
            }
            return arr.toString();
        } catch (Throwable e) { return "[]"; }
    }

    @JavascriptInterface
    public String wsRead(String name) {
        try {
            Uri uri = findInWorkspace(safeName(name));
            if (uri == null) return "工作目录中不存在：" + name;
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(
                    activity.getContentResolver().openInputStream(uri), StandardCharsets.UTF_8))) {
                String line; int total = 0;
                while ((line = br.readLine()) != null) {
                    sb.append(line).append('\n'); total += line.length();
                    if (total > 2_000_000) return "文件过大，暂只支持读取 2MB 以内文本";
                }
            }
            return sb.toString();
        } catch (Throwable e) { return "读取失败：" + (e.getMessage() == null ? e.toString() : e.getMessage()); }
    }

    @JavascriptInterface
    public String wsWrite(String name, String content) {
        OutputStream os = null;
        try {
            if (workspaceUri == null) return "还没有选择工作目录";
            String safe = safeName(name);
            String text = content == null ? "" : content;
            Uri root = rootDocUri();
            if (root == null) return "工作目录权限已失效，请重新选择目录";
            Uri existing = findInWorkspace(safe);
            if (existing != null) {
                try { os = activity.getContentResolver().openOutputStream(existing, "wt"); }
                catch (Exception ignored) { os = activity.getContentResolver().openOutputStream(existing, "w"); }
            } else {
                String mime = mimeFor(safe);
                Uri created = DocumentsContract.createDocument(activity.getContentResolver(), root, mime, safe);
                if (created == null) return "创建文件失败：" + safe;
                try { os = activity.getContentResolver().openOutputStream(created, "wt"); }
                catch (Exception ignored) { os = activity.getContentResolver().openOutputStream(created, "w"); }
            }
            if (os == null) return "无法写入：" + safe;
            try (OutputStream out = os) { out.write(text.getBytes(StandardCharsets.UTF_8)); }
            os = null;
            return "已写入工作目录：" + safe + "（" + text.length() + " 字）";
        } catch (Throwable e) {
            return "写入失败：" + (e.getMessage() == null ? e.toString() : e.getMessage());
        } finally {
            if (os != null) try { os.close(); } catch (Exception ignored) {}
        }
    }

    private String mimeFor(String name) {
        String n = name == null ? "" : name.toLowerCase();
        if (n.endsWith(".html") || n.endsWith(".htm")) return "text/html";
        if (n.endsWith(".json")) return "application/json";
        if (n.endsWith(".xml")) return "application/xml";
        if (n.endsWith(".js") || n.endsWith(".ts")) return "text/plain";
        if (n.endsWith(".css")) return "text/css";
        if (n.endsWith(".md")) return "text/markdown";
        return "text/plain";
    }

    @JavascriptInterface
    public String wsDelete(String name) {
        try {
            Uri uri = findInWorkspace(safeName(name));
            if (uri == null) return "工作目录中不存在：" + name;
            return DocumentsContract.deleteDocument(activity.getContentResolver(), uri) ? "已删除：" + name : "删除失败：" + name;
        } catch (Exception e) { return "删除失败：" + e.getMessage(); }
    }

    // ---------- persistent state (files, avoids WebView localStorage quota) ----------
    private File stateDir() {
        File d = new File(activity.getFilesDir(), "state");
        if (!d.exists()) d.mkdirs();
        return d;
    }
    private String stateKey(String key) {
        String k = key == null ? "state" : key.replaceAll("[^A-Za-z0-9._-]", "_");
        return k.isEmpty() ? "state" : k;
    }

    @JavascriptInterface
    public String stateRead(String key) {
        try {
            File f = new File(stateDir(), stateKey(key) + ".json");
            if (!f.exists() || f.length() == 0) return "";
            if (f.length() > 24_000_000L) return "";
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line).append('\n');
            }
            return sb.toString();
        } catch (Throwable e) { return ""; }
    }

    @JavascriptInterface
    public void stateWrite(String key, String json) {
        try {
            File dir = stateDir();
            File f = new File(dir, stateKey(key) + ".json");
            File tmp = new File(dir, stateKey(key) + ".tmp");
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                fos.write((json == null ? "" : json).getBytes(StandardCharsets.UTF_8));
            }
            if (!tmp.renameTo(f)) {
                try (FileOutputStream fos = new FileOutputStream(f)) {
                    fos.write((json == null ? "" : json).getBytes(StandardCharsets.UTF_8));
                }
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        } catch (Throwable ignored) {}
    }

    // ---------- app files ----------
    private File filesDir() {
        File d = new File(activity.getFilesDir(), "agent_files");
        if (!d.exists()) d.mkdirs();
        return d;
    }
    private String safeName(String name) {
        String n = name == null ? "file.txt" : name.replace("\\", "/");
        if (n.contains("/")) n = n.substring(n.lastIndexOf('/') + 1);
        n = n.replace("..", "_");
        return n.isEmpty() ? "file.txt" : n;
    }

    @JavascriptInterface
    public String writeFile(String name, String content) {
        try {
            File f = new File(filesDir(), safeName(name));
            try (FileOutputStream fos = new FileOutputStream(f)) { fos.write(content.getBytes(StandardCharsets.UTF_8)); }
            return "已写入：" + f.getName() + "（" + f.length() + " 字节）";
        } catch (Exception e) { return "写入失败：" + e.getMessage(); }
    }

    @JavascriptInterface
    public String readFile(String name) {
        try {
            File f = new File(filesDir(), safeName(name));
            if (!f.exists()) return "文件不存在：" + f.getName();
            if (f.length() > 2_000_000) return "文件过大，暂只支持读取 2MB 以内文本";
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
                String line; while ((line = br.readLine()) != null) sb.append(line).append('\n');
            }
            return sb.toString();
        } catch (Exception e) { return "读取失败：" + e.getMessage(); }
    }

    @JavascriptInterface
    public String listFiles() {
        try {
            JSONArray arr = new JSONArray();
            File[] fs = filesDir().listFiles();
            if (fs != null) for (File f : fs) {
                JSONObject o = new JSONObject(); o.put("name", f.getName()); o.put("size", f.length()); o.put("dir", f.isDirectory()); arr.put(o);
            }
            return arr.toString();
        } catch (Exception e) { return "[]"; }
    }

    @JavascriptInterface
    public String deleteFile(String name) {
        try {
            File f = new File(filesDir(), safeName(name));
            return f.delete() ? "已删除：" + f.getName() : "删除失败：" + f.getName();
        } catch (Exception e) { return "删除失败：" + e.getMessage(); }
    }

    @JavascriptInterface
    public String models() {
        try {
            JSONArray arr = new JSONArray();
            File dir = new File(activity.getFilesDir(), "models");
            File[] fs = dir.listFiles();
            if (fs != null) for (File f : fs) if (f.isFile()) {
                JSONObject o = new JSONObject(); o.put("name", f.getName()); o.put("path", f.getAbsolutePath()); o.put("size", f.length()); arr.put(o);
            }
            return arr.toString();
        } catch (Exception e) { return "[]"; }
    }

    // ---------- phone actions ----------
    private static String xml(String s) {
        return (s == null ? "" : s).replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;");
    }

    private static void zipText(ZipOutputStream z, String path, String text) throws Exception {
        z.putNextEntry(new ZipEntry(path));
        z.write(text.getBytes(StandardCharsets.UTF_8));
        z.closeEntry();
    }

    private static String shapeText(int id, String name, long x, long y, long cx, long cy, int size, boolean bold, String[] lines) {
        StringBuilder sb = new StringBuilder();
        sb.append("<p:sp><p:nvSpPr><p:cNvPr id=\"").append(id).append("\" name=\"").append(name).append("\"/>")
          .append("<p:cNvSpPr/><p:nvPr/></p:nvSpPr><p:spPr><a:xfrm><a:off x=\"").append(x).append("\" y=\"").append(y)
          .append("\"/><a:ext cx=\"").append(cx).append("\" cy=\"").append(cy).append("\"/></a:xfrm>")
          .append("<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom><a:noFill/></p:spPr>")
          .append("<p:txBody><a:bodyPr wrap=\"square\"/><a:lstStyle/>");
        if (lines == null || lines.length == 0) lines = new String[]{""};
        for (String line : lines) {
            sb.append("<a:p><a:pPr><a:buNone/></a:pPr><a:r><a:rPr lang=\"zh-CN\" sz=\"").append(size)
              .append("\" b=\"").append(bold ? "1" : "0").append("\"/><a:t>").append(xml(line)).append("</a:t></a:r></a:p>");
        }
        sb.append("</p:txBody></p:sp>");
        return sb.toString();
    }

    private static String slideXml(String title, String[] lines) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
          .append("<p:sld xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\">")
          .append("<p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>")
          .append("<p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/><a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr>");
        sb.append(shapeText(2, "Title", 685800, 457200, 10820400, 1371600, 3200, true, new String[]{title}));
        String[] body = lines == null || lines.length == 0 ? new String[]{" "} : lines;
        sb.append(shapeText(3, "Body", 914400, 2286000, 10363200, 3657600, 1800, false, body));
        sb.append("</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>");
        return sb.toString();
    }

    @JavascriptInterface
    public String createPpt(String name, String json) {
        try {
            String safe = safeName(name == null || name.isEmpty() ? "presentation.pptx" : name);
            if (!safe.toLowerCase().endsWith(".pptx")) safe += ".pptx";
            JSONObject in = new JSONObject(json == null ? "{}" : json);
            String deckTitle = in.optString("title", "演示文稿");
            JSONArray slides = in.optJSONArray("slides");
            if (slides == null) slides = new JSONArray();
            int count = slides.length();
            if (count == 0) {
                JSONArray one = new JSONArray().put(new JSONObject().put("title", deckTitle).put("bullets", new JSONArray()));
                slides = one; count = 1;
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            ZipOutputStream z = new ZipOutputStream(bytes);
            StringBuilder ct = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
                    .append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
                    .append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
                    .append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
                    .append("<Override PartName=\"/ppt/presentation.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml\"/>")
                    .append("<Override PartName=\"/ppt/slideMasters/slideMaster1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml\"/>")
                    .append("<Override PartName=\"/ppt/slideLayouts/slideLayout1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml\"/>")
                    .append("<Override PartName=\"/ppt/theme/theme1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.theme+xml\"/>")
                    .append("<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/>")
                    .append("<Override PartName=\"/docProps/app.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.extended-properties+xml\"/>");
            for (int i = 1; i <= count; i++) ct.append("<Override PartName=\"/ppt/slides/slide").append(i).append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slide+xml\"/>");
            ct.append("</Types>");
            zipText(z, "[Content_Types].xml", ct.toString());
            zipText(z, "_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"ppt/presentation.xml\"/><Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" Target=\"docProps/core.xml\"/><Relationship Id=\"rId3\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties\" Target=\"docProps/app.xml\"/></Relationships>");
            StringBuilder pres = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><p:presentation xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\"><p:sldMasterIdLst><p:sldMasterId id=\"2147483648\" r:id=\"rId1\"/></p:sldMasterIdLst><p:sldIdLst>");
            for (int i = 1; i <= count; i++) pres.append("<p:sldId id=\"").append(255 + i).append("\" r:id=\"rId").append(i + 1).append("\"/>");
            pres.append("</p:sldIdLst><p:sldSz cx=\"12192000\" cy=\"6858000\"/><p:notesSz cx=\"6858000\" cy=\"9144000\"/></p:presentation>");
            zipText(z, "ppt/presentation.xml", pres.toString());
            StringBuilder presRels = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster\" Target=\"slideMasters/slideMaster1.xml\"/>");
            for (int i = 1; i <= count; i++) presRels.append("<Relationship Id=\"rId").append(i + 1).append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide\" Target=\"slides/slide").append(i).append(".xml\"/>");
            presRels.append("</Relationships>");
            zipText(z, "ppt/_rels/presentation.xml.rels", presRels.toString());
            zipText(z, "ppt/slideMasters/slideMaster1.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><p:sldMaster xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\"><p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/><a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr></p:spTree></p:cSld><p:clrMap bg1=\"lt1\" tx1=\"dk1\" bg2=\"lt2\" tx2=\"dk2\" accent1=\"accent1\" accent2=\"accent2\" accent3=\"accent3\" accent4=\"accent4\" accent5=\"accent5\" accent6=\"accent6\" hlink=\"hlink\" folHlink=\"folHlink\"/><p:sldLayoutIdLst><p:sldLayoutId id=\"2147483649\" r:id=\"rId1\"/></p:sldLayoutIdLst><p:txStyles><p:titleStyle><a:lvl1pPr><a:defRPr sz=\"3200\" b=\"1\"/></a:lvl1pPr></p:titleStyle><p:bodyStyle><a:lvl1pPr><a:defRPr sz=\"1800\"/></a:lvl1pPr></p:bodyStyle><p:otherStyle><a:lvl1pPr><a:defRPr sz=\"1800\"/></a:lvl1pPr></p:otherStyle></p:txStyles></p:sldMaster>");
            zipText(z, "ppt/slideMasters/_rels/slideMaster1.xml.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout\" Target=\"../slideLayouts/slideLayout1.xml\"/><Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme\" Target=\"../theme/theme1.xml\"/></Relationships>");
            zipText(z, "ppt/slideLayouts/slideLayout1.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><p:sldLayout xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\" type=\"blank\" preserve=\"1\"><p:cSld name=\"Blank\"><p:spTree><p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/><a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr></p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sldLayout>");
            zipText(z, "ppt/slideLayouts/_rels/slideLayout1.xml.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster\" Target=\"../slideMasters/slideMaster1.xml\"/></Relationships>");
            zipText(z, "ppt/theme/theme1.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><a:theme xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" name=\"Office\"><a:themeElements><a:clrScheme name=\"Office\"><a:dk1><a:sysClr val=\"windowText\" lastClr=\"000000\"/></a:dk1><a:lt1><a:sysClr val=\"window\" lastClr=\"FFFFFF\"/></a:lt1><a:dk2><a:srgbClr val=\"44546A\"/></a:dk2><a:lt2><a:srgbClr val=\"E7E6E6\"/></a:lt2><a:accent1><a:srgbClr val=\"4472C4\"/></a:accent1><a:accent2><a:srgbClr val=\"ED7D31\"/></a:accent2><a:accent3><a:srgbClr val=\"A5A5A5\"/></a:accent3><a:accent4><a:srgbClr val=\"FFC000\"/></a:accent4><a:accent5><a:srgbClr val=\"5B9BD5\"/></a:accent5><a:accent6><a:srgbClr val=\"70AD47\"/></a:accent6><a:hlink><a:srgbClr val=\"0563C1\"/></a:hlink><a:folHlink><a:srgbClr val=\"954F72\"/></a:folHlink></a:clrScheme><a:fontScheme name=\"Office\"><a:majorFont><a:latin typeface=\"Calibri\"/><a:ea typeface=\"\"/><a:cs typeface=\"\"/></a:majorFont><a:minorFont><a:latin typeface=\"Calibri\"/><a:ea typeface=\"\"/><a:cs typeface=\"\"/></a:minorFont></a:fontScheme><a:fmtScheme name=\"Office\"><a:fillStyleLst><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:fillStyleLst><a:lnStyleLst><a:ln w=\"9525\" cap=\"flat\" cmpd=\"sng\" algn=\"ctr\"><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill><a:prstDash val=\"solid\"/></a:ln></a:lnStyleLst><a:effectStyleLst><a:effectStyle><a:effectLst/></a:effectStyle></a:effectStyleLst><a:bgFillStyleLst><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:bgFillStyleLst></a:fmtScheme></a:themeElements><a:objectDefaults/><a:extraClrSchemeLst/></a:theme>");
            for (int i = 1; i <= count; i++) {
                JSONObject sl = slides.optJSONObject(i - 1);
                String st = sl == null ? deckTitle : sl.optString("title", deckTitle);
                JSONArray bullets = sl == null ? null : sl.optJSONArray("bullets");
                String[] lines = new String[bullets == null ? 0 : bullets.length()];
                for (int j = 0; j < lines.length; j++) lines[j] = "• " + bullets.optString(j, "");
                zipText(z, "ppt/slides/slide" + i + ".xml", slideXml(st, lines));
                zipText(z, "ppt/slides/_rels/slide" + i + ".xml.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout\" Target=\"../slideLayouts/slideLayout1.xml\"/></Relationships>");
            }
            zipText(z, "docProps/core.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:title>" + xml(deckTitle) + "</dc:title><dc:creator>ETC</dc:creator></cp:coreProperties>");
            zipText(z, "docProps/app.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Properties xmlns=\"http://schemas.openxmlformats.org/officeDocument/2006/extended-properties\"><Application>agent_etc</Application></Properties>");
            z.finish(); z.close();
            byte[] data = bytes.toByteArray();
            if (workspaceUri != null) {
                Uri root = rootDocUri();
                if (root != null) {
                    Uri existing = findInWorkspace(safe);
                    Uri target = existing;
                    if (target == null) target = DocumentsContract.createDocument(activity.getContentResolver(), root,
                            "application/vnd.openxmlformats-officedocument.presentationml.presentation", safe);
                    if (target != null) {
                        try (OutputStream os = activity.getContentResolver().openOutputStream(target, "w")) {
                            if (os != null) { os.write(data); return "已生成 PPT：" + safe + "（" + count + " 页，工作目录）"; }
                        }
                    }
                }
            }
            File f = new File(filesDir(), safe);
            try (FileOutputStream fos = new FileOutputStream(f)) { fos.write(data); }
            return "已生成 PPT：" + safe + "（" + count + " 页，应用私有目录）";
        } catch (Throwable e) {
            return "生成 PPT 失败：" + (e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    @JavascriptInterface
    public String openApp(String nameOrPackage) {
        try {
            String want = nameOrPackage == null ? "" : nameOrPackage.trim().toLowerCase();
            String[] candidates;
            if (want.contains("bilibili") || want.contains("哔哩") || want.contains("b站")) {
                candidates = new String[]{"tv.danmaku.bili", "tv.danmaku.bilibilihd", "com.bilibili.app"};
            } else if (want.contains("qq") || want.contains("qq")) {
                candidates = new String[]{"com.tencent.mobileqq"};
            } else if (want.contains("微信") || want.contains("wechat")) {
                candidates = new String[]{"com.tencent.mm"};
            } else if (want.contains("设置") || want.contains("settings")) {
                candidates = new String[]{"com.android.settings"};
            } else {
                candidates = new String[]{nameOrPackage};
            }
            android.content.pm.PackageManager pm = activity.getPackageManager();
            for (String pkg : candidates) {
                if (launchPackage(pkg)) return "已打开应用：" + pkg;
            }
            String byLabel = findLaunchableByLabel(want);
            if (!byLabel.isEmpty() && launchPackage(byLabel)) return "已打开应用：" + byLabel;
            return "没有找到已安装的应用：" + nameOrPackage + "；请先用 list_apps 查看手机里已安装应用的准确名称";
        } catch (Throwable e) { return "打开应用失败：" + e.getMessage(); }
    }

    @JavascriptInterface
    public String listApps() {
        try {
            android.content.pm.PackageManager pm = activity.getPackageManager();
            Intent launcher = new Intent(Intent.ACTION_MAIN); launcher.addCategory(Intent.CATEGORY_LAUNCHER);
            java.util.List<android.content.pm.ResolveInfo> list = pm.queryIntentActivities(launcher, 0);
            JSONArray arr = new JSONArray();
            java.util.HashSet<String> seen = new java.util.HashSet<>();
            for (android.content.pm.ResolveInfo ri : list) {
                try {
                    if (ri.activityInfo == null) continue;
                    String pkg = ri.activityInfo.packageName;
                    if (pkg == null || pkg.equals(activity.getPackageName()) || !seen.add(pkg)) continue;
                    String label = ri.loadLabel(pm).toString();
                    arr.put(new JSONObject().put("name", label == null ? pkg : label).put("package", pkg));
                    if (arr.length() >= 300) break;
                } catch (Throwable ignored) {}
            }
            return new JSONObject().put("apps", arr).toString();
        } catch (Throwable e) { return "{\"apps\":[],\"error\":" + q(String.valueOf(e.getMessage())) + "}"; }
    }

    private boolean launchPackage(String pkg) {
        try {
            Intent i = activity.getPackageManager().getLaunchIntentForPackage(pkg);
            if (i == null) return false;
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(i);
            return true;
        } catch (Throwable ignored) { return false; }
    }

    private String findLaunchableByLabel(String want) {
        try {
            android.content.pm.PackageManager pm = activity.getPackageManager();
            Intent launcher = new Intent(Intent.ACTION_MAIN); launcher.addCategory(Intent.CATEGORY_LAUNCHER);
            java.util.List<android.content.pm.ResolveInfo> list = pm.queryIntentActivities(launcher, 0);
            String bestPkg = "", bestLabel = "";
            for (android.content.pm.ResolveInfo ri : list) {
                try {
                    if (ri.activityInfo == null) continue;
                    String label = ri.loadLabel(pm).toString().toLowerCase(java.util.Locale.ROOT);
                    String pkg = ri.activityInfo.packageName;
                    if (pkg == null || pkg.equals(activity.getPackageName())) continue;
                    if (label.equals(want)) return pkg;
                    if (label.contains(want) && (bestPkg.isEmpty() || label.length() < bestLabel.length())) { bestPkg = pkg; bestLabel = label; }
                } catch (Throwable ignored) {}
            }
            return bestPkg;
        } catch (Throwable ignored) { return ""; }
    }

    @JavascriptInterface
    public String screenSwipe(int x1, int y1, int x2, int y2, int duration) {
        return AgentAccessibilityService.swipe(x1, y1, x2, y2, duration);
    }

    @JavascriptInterface
    public String screenFind(String text) { return AgentAccessibilityService.findText(text); }

    @JavascriptInterface
    public String screenTapText(String text) { return AgentAccessibilityService.tapText(text); }

    @JavascriptInterface
    public String screenInput(String text) { return AgentAccessibilityService.setText(text); }

    @JavascriptInterface
    public String screenEnter() { return AgentAccessibilityService.enter(); }

    @JavascriptInterface
    public String openUrl(String url) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(i);
            return "已打开：" + url;
        } catch (Exception e) { return "打开失败：" + e.getMessage(); }
    }

    @JavascriptInterface
    public String shareText(String text) {
        try {
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_TEXT, text);
            activity.startActivity(Intent.createChooser(i, "分享"));
            return "已调起分享";
        } catch (Exception e) { return "分享失败：" + e.getMessage(); }
    }

    @JavascriptInterface
    public String copyText(String text) {
        try {
            ClipboardManager cm = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("agent_etc", text));
            return "已复制到剪贴板";
        } catch (Exception e) { return "复制失败：" + e.getMessage(); }
    }

    @JavascriptInterface
    public String pasteText() {
        try {
            ClipboardManager cm = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm.getPrimaryClip() != null && cm.getPrimaryClip().getItemCount() > 0) {
                CharSequence s = cm.getPrimaryClip().getItemAt(0).getText();
                return s == null ? "" : s.toString();
            }
        } catch (Exception ignored) {}
        return "";
    }

    @JavascriptInterface
    public String taskStatus(String state, String title, String detail) {
        try {
            AgentTaskService.update(state, title, detail);
            return "已更新任务状态";
        } catch (Throwable e) { return "状态更新失败：" + e.getMessage(); }
    }

    @JavascriptInterface
    public String startBackground() {
        try {
            if (activity instanceof MainActivity) ((MainActivity) activity).startTaskService();
            return "后台任务服务已启动";
        } catch (Throwable e) { return "启动失败：" + e.getMessage(); }
    }

    @JavascriptInterface
    public String overlayEnabled() {
        try {
            return Build.VERSION.SDK_INT >= 23 && android.provider.Settings.canDrawOverlays(activity) ? "true" : "false";
        } catch (Throwable e) { return "false"; }
    }

    @JavascriptInterface
    public String openOverlaySettings() {
        try {
            Intent i = new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + activity.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(i);
            return "已打开悬浮窗权限设置；授权后回到 App 即可显示";
        } catch (Throwable e) { return "打开失败：" + e.getMessage(); }
    }

    @JavascriptInterface
    public String appVersion() { return "2.0.0"; }

    @JavascriptInterface
    public void requestPermissions() {
        try {
            activity.runOnUiThread(() -> activity.requestPermissions(new String[]{
                "android.permission.READ_EXTERNAL_STORAGE",
                "android.permission.WRITE_EXTERNAL_STORAGE",
                "android.permission.ACCESS_FINE_LOCATION",
                "android.permission.ACCESS_COARSE_LOCATION"
            }, 9002));
        } catch (Throwable ignored) {}
    }
}
