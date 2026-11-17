package com.etwqfd.agentetc;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.Build;
import android.system.Os;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 内置 32 位 Termux bootstrap（来自 Termux v0.119.0-beta.3）的解压与终端执行。
 * bootstrap 的前缀已重写为 /data/data/<本应用包名>，因此不需要 root，也不依赖 Termux App。
 */
public final class TerminalRuntime {
    private static final String ASSET = "termux-arm.zip";
    private static volatile boolean ready = false;
    private static volatile String lastError = "";
    private static volatile boolean extracting = false;

    private TerminalRuntime() {}

    public static boolean isReady() { return ready; }
    public static String error() { return lastError; }

    public static synchronized void ensure(Context ctx) {
        if (ready) return;
        if (extracting) return;
        extracting = true;
        File prefix = ctx.getFilesDir().getParentFile();
        try {
            File bash = new File(prefix, "bin/bash");
            File mark = new File(prefix, ".termux-ready");
            if (!mark.exists() || !bash.exists() || !librariesReady(prefix)) {
                try { extract(ctx, prefix); } catch (Throwable e) {
                    lastError = "解压主流程：" + e.getClass().getSimpleName() + ": " + e.getMessage();
                }
                repairCritical(ctx, prefix);
                createLinks(prefix);
                if (bash.exists() && librariesReady(prefix)) {
                    try (FileOutputStream fos = new FileOutputStream(mark)) {
                        fos.write("termux-arm-v0.119.0-beta.3\n".getBytes(StandardCharsets.UTF_8));
                    }
                }
            }
            repairCritical(ctx, prefix);
            createLinks(prefix);
            new File(prefix, "home").mkdirs();
            new File(prefix, "tmp").mkdirs();
            ready = bash.exists() && librariesReady(prefix);
            if (!ready) {
                lastError = "bootstrap 已解压，但缺少：" + missingList(prefix);
            }
        } catch (Throwable e) {
            lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            ready = false;
        } finally {
            extracting = false;
        }
    }

    private static final String[] CRITICAL = {
            "bin/bash", "lib/libandroid-support.so", "lib/libreadline.so.8.2", "lib/libiconv.so"
    };

    private static boolean criticalReady(File prefix) {
        return librariesReady(prefix) && new File(prefix, "bin/bash").isFile();
    }

    private static String missingList(File prefix) {
        StringBuilder b = new StringBuilder();
        for (String n : CRITICAL) {
            File f = new File(prefix, n);
            if (!f.isFile() || f.length() <= 0) {
                if (b.length() > 0) b.append("、");
                b.append(n);
            }
        }
        return b.length() == 0 ? "无" : b.toString();
    }

    /**
     * 解压容错：单独补齐 bash 和三个核心库；文件系统禁止创建 lib 时，
     * 再从 APK 的 nativeLibraryDir 复制一份，避免一句 not found 直接停摆。
     */
    private static void repairCritical(Context ctx, File prefix) {
        File bin = new File(prefix, "bin");
        File lib = new File(prefix, "lib");
        try {
            if (bin.exists() && !bin.isDirectory()) bin.delete();
            if (lib.exists() && !lib.isDirectory()) lib.delete();
            bin.mkdirs(); lib.mkdirs();
        } catch (Throwable ignored) {}
        if (criticalReady(prefix)) return;

        try (InputStream in = ctx.getAssets().open(ASSET, AssetManager.ACCESS_STREAMING);
             ZipInputStream zis = new ZipInputStream(in)) {
            ZipEntry e; byte[] buf = new byte[8192];
            while ((e = zis.getNextEntry()) != null) {
                String name = e.getName();
                if (name == null) continue;
                if (name.startsWith("./")) name = name.substring(2);
                boolean wanted = false;
                for (String n : CRITICAL) if (n.equals(name)) { wanted = true; break; }
                if (!wanted) continue;
                File out = new File(prefix, name);
                File parent = out.getParentFile();
                if (parent != null) parent.mkdirs();
                try (OutputStream fos = new FileOutputStream(out)) {
                    int n;
                    while ((n = zis.read(buf)) > 0) fos.write(buf, 0, n);
                }
                out.setExecutable(true, false);
            }
        } catch (Throwable ignored) {}

        // 私有 lib 目录仍缺库时，从 nativeLibraryDir 兜底。
        copyFromNative(ctx, prefix, "lib/libandroid-support.so", "libandroid-support.so");
        copyFromNative(ctx, prefix, "lib/libiconv.so", "libiconv.so");
        File rl = new File(prefix, "lib/libreadline.so.8.2");
        if (!rl.isFile() || rl.length() <= 0) {
            copyFromNative(ctx, prefix, "lib/libreadline.so.8.2", "libreadline.so");
            if (!rl.isFile() || rl.length() <= 0) {
                copyFromNative(ctx, prefix, "lib/libreadline.so.8.2", "libreadline.so.8");
            }
        }
    }

    private static void copyFromNative(Context ctx, File prefix, String targetName, String nativeName) {
        File target = new File(prefix, targetName);
        if (target.isFile() && target.length() > 0) return;
        try {
            File src = new File(ctx.getApplicationInfo().nativeLibraryDir, nativeName);
            if (!src.isFile() || src.length() <= 0) return;
            File parent = target.getParentFile();
            if (parent != null) parent.mkdirs();
            try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(target)) {
                byte[] buf = new byte[8192]; int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            target.setExecutable(true, false);
        } catch (Throwable ignored) {}
    }

    private static boolean librariesReady(File prefix) {
        String[] need = {"lib/libandroid-support.so", "lib/libreadline.so.8.2", "lib/libiconv.so"};
        for (String n : need) {
            File f = new File(prefix, n);
            if (!f.isFile() || f.length() <= 0) return false;
        }
        return true;
    }

    private static void extract(Context ctx, File prefix) throws Exception {
        AssetManager am = ctx.getAssets();
        String prefixPath = prefix.getCanonicalPath();
        try (InputStream in = am.open(ASSET, AssetManager.ACCESS_STREAMING);
             ZipInputStream zis = new ZipInputStream(in)) {
            ZipEntry e;
            byte[] buf = new byte[8192];
            while ((e = zis.getNextEntry()) != null) {
                String name = e.getName();
                if (name == null || name.isEmpty()) continue;
                if (name.startsWith("./")) name = name.substring(2);
                if (name.contains("..")) continue;
                try {
                    File out = new File(prefix, name);
                    String outPath = out.getCanonicalPath();
                    if (!outPath.equals(prefixPath) && !outPath.startsWith(prefixPath + File.separator)) continue;
                    if (e.isDirectory()) { out.mkdirs(); continue; }
                    File parent = out.getParentFile();
                    if (parent != null) parent.mkdirs();
                    try (OutputStream fos = new FileOutputStream(out)) {
                        int n;
                        while ((n = zis.read(buf)) > 0) fos.write(buf, 0, n);
                    }
                    if (name.startsWith("bin/") || name.startsWith("libexec/") || name.startsWith("lib/")) {
                        out.setExecutable(true, false);
                    }
                } catch (Throwable ignored) {
                    // 单个条目失败不终止整包；repairCritical 会单独重取 bash/核心库。
                } finally {
                    try { zis.closeEntry(); } catch (Throwable ignored) {}
                }
            }
        }
    }

    private static void createLinks(File prefix) {
        File linksFile = new File(prefix, "SYMLINKS.txt");
        if (!linksFile.isFile()) return;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(linksFile), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                int sep = line.indexOf('←');
                if (sep <= 0) continue;
                String target = line.substring(0, sep).trim();
                String linkName = line.substring(sep + 1).trim();
                if (linkName.startsWith("./")) linkName = linkName.substring(2);
                if (target.isEmpty() || linkName.isEmpty() || linkName.contains("..")) continue;
                File link = new File(prefix, linkName);
                File linkParent = link.getParentFile();
                if (linkParent != null) linkParent.mkdirs();
                File targetFile = target.startsWith("/") ? new File(target) : new File(linkParent, target);
                if (!targetFile.exists()) continue;
                if (link.exists()) continue;
                try {
                    Os.symlink(target, link.getAbsolutePath());
                } catch (Throwable ignored) {
                    // 某些文件系统不允许符号链接：对 readline 直接复制一份。
                    try {
                        if (linkName.endsWith("libreadline.so.8")) {
                            try (InputStream in = new FileInputStream(targetFile); OutputStream out = new FileOutputStream(link)) {
                                byte[] buf = new byte[8192]; int n;
                                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                            }
                            link.setExecutable(true, false);
                        }
                    } catch (Throwable ignored2) {}
                }
            }
        } catch (Throwable ignored) {}
    }

    public static String exec(Context ctx, String command, String cwd) {
        String cmd = command == null ? "" : command.trim();
        if (cmd.isEmpty()) return "命令为空";
        ensure(ctx);
        File prefix = ctx.getFilesDir().getParentFile();
        File dir;
        try {
            if (cwd != null && !cwd.trim().isEmpty()) {
                File c = new File(cwd.trim());
                dir = c.isAbsolute() && c.isDirectory() ? c : new File(ctx.getFilesDir(), cwd.trim());
            } else dir = ctx.getFilesDir();
            if (!dir.isDirectory()) dir = ctx.getFilesDir();
        } catch (Throwable e) { dir = ctx.getFilesDir(); }

        File bash = new File(prefix, "bin/bash");
        boolean arm32 = false;
        for (String abi : Build.SUPPORTED_ABIS) if (abi != null && abi.startsWith("armeabi-v7a")) arm32 = true;
        String[] argv;
        boolean useTermux = ready && bash.isFile() && arm32;
        if (useTermux) argv = new String[]{bash.getAbsolutePath(), "-lc", cmd};
        else argv = new String[]{"/system/bin/sh", "-c", cmd};

        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(argv);
            pb.directory(dir);
            pb.redirectErrorStream(true);
            if (useTermux) {
                pb.environment().put("PREFIX", prefix.getAbsolutePath());
                pb.environment().put("HOME", new File(prefix, "home").getAbsolutePath());
                pb.environment().put("TMPDIR", new File(prefix, "tmp").getAbsolutePath());
                String lib = new File(prefix, "lib").getAbsolutePath();
                StringBuilder lp = new StringBuilder(lib);
                try { lp.append(':').append(ctx.getApplicationInfo().nativeLibraryDir); } catch (Throwable ignored) {}
                pb.environment().put("LD_LIBRARY_PATH", lp.toString());
                // Termux 的 bash 依赖 libandroid-support/readline/iconv；显式预加载，
                // 即使某些 ROM 忽略 LD_LIBRARY_PATH 也能链接成功。
                String preload = "";
                File support = new File(prefix, "lib/libandroid-support.so");
                if (support.isFile() && support.length() > 0) preload = support.getAbsolutePath();
                File rl = new File(prefix, "lib/libreadline.so.8");
                if (rl.isFile()) preload += (preload.isEmpty() ? "" : ":") + rl.getAbsolutePath();
                File iconv = new File(prefix, "lib/libiconv.so");
                if (iconv.isFile()) preload += (preload.isEmpty() ? "" : ":") + iconv.getAbsolutePath();
                if (!preload.isEmpty()) pb.environment().put("LD_PRELOAD", preload);
                pb.environment().put("PATH", new File(prefix, "bin").getAbsolutePath() + ":/system/bin:/system/xbin:/vendor/bin");
                pb.environment().put("TERM", "xterm-256color");
                pb.environment().put("LANG", "C.UTF-8");
            } else {
                pb.environment().put("HOME", dir.getAbsolutePath());
                pb.environment().put("TMPDIR", ctx.getCacheDir().getAbsolutePath());
                pb.environment().put("PATH", "/system/bin:/system/xbin:/vendor/bin:" + prefix.getAbsolutePath());
            }
            p = pb.start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (out.length() < 24000) out.append(line).append('\n');
                }
            }
            boolean done = p.waitFor(45, TimeUnit.SECONDS);
            int code = -1;
            if (!done) { p.destroyForcibly(); out.append("\n[超时，已结束进程]"); }
            else code = p.exitValue();
            String tag = useTermux ? "[Termux 32-bit]" : "[系统 shell]";
            if (ready && bash.isFile() && !arm32) tag += " 设备不支持 32 位 ARM，已回退；内置核心仍可用在 32 位设备上。";
            if (useTermux && out.indexOf("CANNOT LINK EXECUTABLE") >= 0) {
                return tag + "\n退出码 " + code + "\n[终端核心] 动态库链接失败，已保留原始输出：\n" + out
                    + "\n请把上面的完整报错发我；内置库路径已自动指向 " + new File(prefix, "lib").getAbsolutePath();
            }
            return tag + "\n退出码 " + code + "\n" + out + (useTermux ? "" : (lastError.isEmpty() ? "" : "\n[终端核心] " + lastError));
        } catch (Throwable e) {
            return "终端执行失败：" + e.getMessage() + (lastError.isEmpty() ? "" : "\n[终端核心] " + lastError);
        } finally {
            if (p != null) try { p.destroy(); } catch (Throwable ignored) {}
        }
    }
}
