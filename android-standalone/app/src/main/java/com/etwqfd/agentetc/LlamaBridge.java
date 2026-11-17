package com.etwqfd.agentetc;

public final class LlamaBridge {
    private static Throwable loadError = null;
    static {
        try { System.loadLibrary("agentllama"); }
        catch (Throwable e) { loadError = e; }
    }
    public static boolean available() { return loadError == null; }
    public static String loadErrorText() { return loadError == null ? "" : String.valueOf(loadError); }
    private static long handle = 0;
    private static final Object LOCK = new Object();

    public static native void nativeInit();
    public static native String nativeLastError();
    public static native long nativeLoad(String path, int nCtx, int threads, int gpu);
    public static native void nativeFree(long h);
    public static native String nativeBuildPrompt(long h, String[] roles, String[] contents);
    public static native String nativeGenerate(long h, String prompt, int maxTokens, float temp, float topP, int topK);
    public static native String nativeInfo(long h);
    public static native void nativeRequestStop();

    public static synchronized boolean load(String path, int nCtx, int threads, int gpu) {
        if (loadError != null) return false;
        nativeInit();
        if (handle != 0) { nativeFree(handle); handle = 0; }
        handle = nativeLoad(path, nCtx, threads, gpu);
        return handle != 0;
    }

    public static synchronized void requestStop() { try { if (loadError == null) nativeRequestStop(); } catch (Throwable ignored) {} }

    public static synchronized void unload() {
        if (handle != 0) { nativeFree(handle); handle = 0; }
    }

    public static synchronized boolean ready() { return handle != 0; }
    public static synchronized long getHandle() { return handle; }
    public static synchronized String lastError() { return nativeLastError(); }

    /** Build + generate under one lock so unload/reload can never free the handle mid-inference. */
    public static synchronized String chat(String[] roles, String[] contents, int maxTokens,
                                           float temp, float topP, int topK) {
        if (loadError != null || handle == 0) return "";
        String prompt = nativeBuildPrompt(handle, roles, contents);
        if (prompt == null || prompt.isEmpty()) return "";
        String text = nativeGenerate(handle, prompt, maxTokens, temp, topP, topK);
        return text == null ? "" : text;
    }
}
