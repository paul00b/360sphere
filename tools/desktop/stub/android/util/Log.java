package android.util;

/** Stub minimal pour exécuter le code de l'app sur le banc de test desktop (android.jar n'a pas de corps de méthode). */
public final class Log {
    private Log() {}
    private static void out(String level, String tag, String msg, Throwable t) {
        System.out.println("[" + level + "/" + tag + "] " + msg);
        if (t != null) t.printStackTrace(System.out);
    }
    public static int v(String tag, String msg) { out("V", tag, msg, null); return 0; }
    public static int v(String tag, String msg, Throwable t) { out("V", tag, msg, t); return 0; }
    public static int d(String tag, String msg) { out("D", tag, msg, null); return 0; }
    public static int d(String tag, String msg, Throwable t) { out("D", tag, msg, t); return 0; }
    public static int i(String tag, String msg) { out("I", tag, msg, null); return 0; }
    public static int i(String tag, String msg, Throwable t) { out("I", tag, msg, t); return 0; }
    public static int w(String tag, String msg) { out("W", tag, msg, null); return 0; }
    public static int w(String tag, String msg, Throwable t) { out("W", tag, msg, t); return 0; }
    public static int e(String tag, String msg) { out("E", tag, msg, null); return 0; }
    public static int e(String tag, String msg, Throwable t) { out("E", tag, msg, t); return 0; }
    public static String getStackTraceString(Throwable t) { return t == null ? "" : t.toString(); }
    public static boolean isLoggable(String tag, int level) { return true; }
}
