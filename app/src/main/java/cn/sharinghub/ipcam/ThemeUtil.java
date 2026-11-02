package cn.sharinghub.ipcam;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.preference.PreferenceManager;
import android.util.TypedValue;

/**
 * Theme mode: follow the system (default), or force light/dark. The window theme is chosen
 * before setContentView, so every activity must call {@link #apply} first.
 */
public final class ThemeUtil {
    public static final String PREF_MODE = "theme_mode";
    public static final String MODE_AUTO = "auto";
    public static final String MODE_LIGHT = "light";
    public static final String MODE_DARK = "dark";

    private ThemeUtil() { }

    public static String mode(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getString(PREF_MODE, MODE_AUTO);
    }

    public static void setMode(Context context, String mode) {
        SharedPreferences.Editor editor = PreferenceManager.getDefaultSharedPreferences(context).edit();
        editor.putString(PREF_MODE, mode).apply();
    }

    /** True when the activity should use the dark palette, including the system-follow mode. */
    public static boolean isDark(Context context) {
        String mode = mode(context);
        if (MODE_DARK.equals(mode)) return true;
        if (MODE_LIGHT.equals(mode)) return false;
        Configuration config = context.getResources().getConfiguration();
        return (config.uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    public static void apply(Activity activity) {
        boolean dark = isDark(activity);
        activity.setTheme(dark ? R.style.Theme_PocketCam_Dark : R.style.Theme_PocketCam_Light);
        applyStatusBarIcons(activity, dark);
    }

    /** Light status bars need dark system icons; API 22 relies on the ROM (Flyme) doing it. */
    public static void applyStatusBarIcons(Activity activity, boolean dark) {
        if (android.os.Build.VERSION.SDK_INT < 23) return;
        int flags = activity.getWindow().getDecorView().getSystemUiVisibility();
        if (dark) flags &= ~android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        else flags |= android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        activity.getWindow().getDecorView().setSystemUiVisibility(flags);
    }

    /** Resolves a theme colour attribute (e.g. R.attr.colorStatusOk) to an ARGB int. */
    public static int color(Context context, int attrRes) {
        TypedValue value = new TypedValue();
        if (!context.getTheme().resolveAttribute(attrRes, value, true)) return 0xFF808080;
        if (value.resourceId != 0) return context.getResources().getColor(value.resourceId);
        return value.data;
    }
}
