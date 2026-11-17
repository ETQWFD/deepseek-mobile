package com.etwqfd.agentetc;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONObject;

/** 用户主动授权的屏幕读取/点击服务；只读当前可见界面，不绕过验证码或支付确认。 */
public class AgentAccessibilityService extends AccessibilityService {
    private static volatile AgentAccessibilityService instance;
    private static volatile boolean connected = false;

    public static boolean isReady() { return connected && instance != null; }

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        connected = true;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override
    public void onInterrupt() {}

    @Override
    public void onDestroy() {
        connected = false;
        instance = null;
        super.onDestroy();
    }

    private static void walk(AccessibilityNodeInfo node, JSONArray out, int depth) {
        if (node == null || depth > 16 || out.length() >= 260) return;
        try {
            String text = node.getText() == null ? "" : node.getText().toString();
            String desc = node.getContentDescription() == null ? "" : node.getContentDescription().toString();
            boolean useful = !text.isEmpty() || !desc.isEmpty() || node.isClickable() || node.isEditable() || node.isScrollable();
            if (useful && node.isVisibleToUser()) {
                JSONObject o = new JSONObject();
                o.put("text", text);
                o.put("desc", desc);
                o.put("cls", node.getClassName() == null ? "" : node.getClassName().toString());
                try { o.put("id", node.getViewIdResourceName() == null ? "" : node.getViewIdResourceName()); } catch (Throwable ignored) {}
                o.put("clickable", node.isClickable());
                o.put("editable", node.isEditable());
                o.put("scrollable", node.isScrollable());
                o.put("enabled", node.isEnabled());
                Rect r = new Rect();
                node.getBoundsInScreen(r);
                o.put("bounds", new JSONArray(new int[]{r.left, r.top, r.right, r.bottom}));
                out.put(o);
            }
            for (int i = 0; i < node.getChildCount(); i++) walk(node.getChild(i), out, depth + 1);
        } catch (Exception ignored) {}
    }

    public static String dumpScreen() {
        if (!isReady()) return "{\"enabled\":false,\"error\":\"无障碍服务未开启\"}";
        try {
            AccessibilityNodeInfo root = instance.getRootInActiveWindow();
            if (root == null) return "{\"enabled\":true,\"error\":\"当前没有可读取的窗口\"}";
            JSONObject out = new JSONObject();
            out.put("enabled", true);
            out.put("package", root.getPackageName() == null ? "" : root.getPackageName().toString());
            try {
                android.util.DisplayMetrics dm = instance.getResources().getDisplayMetrics();
                out.put("width", dm.widthPixels);
                out.put("height", dm.heightPixels);
                out.put("density", dm.densityDpi);
            } catch (Throwable ignored) {}
            JSONArray nodes = new JSONArray();
            walk(root, nodes, 0);
            out.put("nodes", nodes);
            return out.toString();
        } catch (Exception e) {
            return "{\"enabled\":true,\"error\":" + JSONObject.quote(String.valueOf(e.getMessage())) + "}";
        }
    }

    public static String tap(int x, int y) {
        if (!isReady()) return "无障碍服务未开启，请先在系统设置里授权";
        if (Build.VERSION.SDK_INT < 24) return "当前 Android 版本不支持手势点击";
        try {
            Path path = new Path();
            path.moveTo(x, y);
            GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(path, 0, 80);
            boolean ok = instance.dispatchGesture(new GestureDescription.Builder().addStroke(stroke).build(), null, null);
            return ok ? ("已点击 " + x + "," + y) : "点击未执行（可能被系统拦截）";
        } catch (Exception e) {
            return "点击失败：" + e.getMessage();
        }
    }

    private static boolean matches(AccessibilityNodeInfo n, String q) {
        if (n == null) return false;
        String text = n.getText() == null ? "" : n.getText().toString();
        String desc = n.getContentDescription() == null ? "" : n.getContentDescription().toString();
        return text.toLowerCase().contains(q) || desc.toLowerCase().contains(q);
    }

    private static AccessibilityNodeInfo findExactNode(AccessibilityNodeInfo root, String q) {
        if (root == null) return null;
        try {
            String text = root.getText() == null ? "" : root.getText().toString();
            String desc = root.getContentDescription() == null ? "" : root.getContentDescription().toString();
            if (text.toLowerCase().equals(q) || desc.toLowerCase().equals(q)) return root;
            for (int i = 0; i < root.getChildCount(); i++) {
                AccessibilityNodeInfo hit = findExactNode(root.getChild(i), q);
                if (hit != null) return hit;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static AccessibilityNodeInfo findNode(AccessibilityNodeInfo root, String q, boolean editableOnly) {
        if (root == null) return null;
        try {
            if (matches(root, q)) {
                if (!editableOnly || root.isEditable()) return root;
            }
            for (int i = 0; i < root.getChildCount(); i++) {
                AccessibilityNodeInfo hit = findNode(root.getChild(i), q, editableOnly);
                if (hit != null) return hit;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static AccessibilityNodeInfo findEditable(AccessibilityNodeInfo root) {
        if (root == null) return null;
        try {
            if (root.isEditable()) return root;
            for (int i = 0; i < root.getChildCount(); i++) {
                AccessibilityNodeInfo n = findEditable(root.getChild(i));
                if (n != null) return n;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    public static String findText(String query) {
        if (!isReady()) return "{\"enabled\":false,\"error\":\"无障碍服务未开启\"}";
        try {
            AccessibilityNodeInfo root = instance.getRootInActiveWindow();
            if (root == null) return "{\"enabled\":true,\"nodes\":[]}";
            JSONArray arr = new JSONArray();
            collect(root, query == null ? "" : query.toLowerCase(), arr, 0);
            return new JSONObject().put("enabled", true).put("nodes", arr).toString();
        } catch (Throwable e) { return "{\"enabled\":true,\"error\":\"查询失败\"}"; }
    }

    private static void collect(AccessibilityNodeInfo n, String q, JSONArray arr, int depth) {
        if (n == null || depth > 16 || arr.length() > 30) return;
        try {
            String text = n.getText() == null ? "" : n.getText().toString();
            String desc = n.getContentDescription() == null ? "" : n.getContentDescription().toString();
            if ((!q.isEmpty() && (text.toLowerCase().contains(q) || desc.toLowerCase().contains(q))) || (q.isEmpty() && (!text.isEmpty() || !desc.isEmpty()))) {
                Rect r = new Rect(); n.getBoundsInScreen(r);
                arr.put(new JSONObject().put("text", text).put("desc", desc).put("clickable", n.isClickable())
                        .put("editable", n.isEditable()).put("bounds", new JSONArray(new int[]{r.left, r.top, r.right, r.bottom})));
            }
            for (int i = 0; i < n.getChildCount(); i++) collect(n.getChild(i), q, arr, depth + 1);
        } catch (Throwable ignored) {}
    }

    public static String tapText(String text) {
        if (!isReady()) return "无障碍服务未开启";
        try {
            AccessibilityNodeInfo root = instance.getRootInActiveWindow();
            String q = text == null ? "" : text.toLowerCase();
            AccessibilityNodeInfo n = findExactNode(root, q);
            if (n == null) n = findNode(root, q, false);
            if (n == null) return "没有找到：" + text;
            AccessibilityNodeInfo click = n;
            for (int i = 0; i < 10 && click != null && !click.isClickable(); i++) click = click.getParent();
            if (click != null && click.isClickable() && click.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return "已点击：" + text;
            Rect r = new Rect(); n.getBoundsInScreen(r);
            return tap((r.left + r.right) / 2, (r.top + r.bottom) / 2);
        } catch (Throwable e) { return "点击失败：" + e.getMessage(); }
    }

    public static String setText(String text) {
        if (!isReady()) return "无障碍服务未开启";
        try {
            AccessibilityNodeInfo root = instance.getRootInActiveWindow();
            AccessibilityNodeInfo n = findEditable(root);
            if (n == null) return "当前界面没有可输入框；请先点开搜索框";
            n.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            Bundle b = new Bundle();
            b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text == null ? "" : text);
            boolean ok = n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b);
            return ok ? ("已输入：" + text) : "输入失败（输入框可能不可编辑）";
        } catch (Throwable e) { return "输入失败：" + e.getMessage(); }
    }

    public static String enter() {
        if (!isReady()) return "无障碍服务未开启";
        try {
            AccessibilityNodeInfo root = instance.getRootInActiveWindow();
            AccessibilityNodeInfo n = findEditable(root);
            if (n == null) return "没有输入框";
            if (Build.VERSION.SDK_INT >= 30) {
                boolean ok = n.performAction(AccessibilityAction.ACTION_IME_ENTER.getId());
                if (ok) return "已按下回车";
            }
            return tapText("搜索");
        } catch (Throwable e) { return "回车失败：" + e.getMessage(); }
    }

    public static String swipe(int x1, int y1, int x2, int y2, int durationMs) {
        if (!isReady()) return "无障碍服务未开启";
        if (Build.VERSION.SDK_INT < 24) return "当前 Android 版本不支持滑动";
        try {
            Path path = new Path();
            path.moveTo(x1, y1);
            path.lineTo(x2, y2);
            int d = Math.max(80, Math.min(1200, durationMs <= 0 ? 300 : durationMs));
            GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(path, 0, d);
            boolean ok = instance.dispatchGesture(new GestureDescription.Builder().addStroke(stroke).build(), null, null);
            return ok ? ("已滑动 " + x1 + "," + y1 + " → " + x2 + "," + y2) : "滑动未执行（可能被系统拦截）";
        } catch (Exception e) { return "滑动失败：" + e.getMessage(); }
    }

    public static String back() {
        if (!isReady()) return "无障碍服务未开启";
        return instance.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) ? "已返回" : "返回失败";
    }
}
