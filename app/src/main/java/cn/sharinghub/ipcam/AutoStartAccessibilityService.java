package cn.sharinghub.ipcam;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * Optional helper for non-rooted devices: while the user is on an OEM auto-start settings
 * page, enables switches whose label mentions auto-start so the boot broadcast can arrive.
 * The user must enable this service manually in system accessibility settings.
 */
public class AutoStartAccessibilityService extends AccessibilityService {
    private static final String[] KEYWORDS = {"自启动", "自动启动", "开机启动", "后台启动", "auto-start", "autostart"};
    private long lastClick;

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (System.currentTimeMillis() - lastClick < 1500) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        try {
            if (screenMentionsUs(root)) walk(root, 0);
        } finally {
            root.recycle();
        }
    }

    /** Only act on screens that show this app's entry, to avoid touching unrelated settings. */
    private boolean screenMentionsUs(AccessibilityNodeInfo root) {
        return findText(root, "PocketCam", 0) || findText(root, getPackageName(), 0);
    }

    private boolean findText(AccessibilityNodeInfo node, String needle, int depth) {
        if (node == null || depth > 12) return false;
        CharSequence text = node.getText();
        if (text != null && text.toString().toLowerCase().contains(needle.toLowerCase())) return true;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (findText(child, needle, depth + 1)) return true;
        }
        return false;
    }

    private void walk(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 12) return;
        CharSequence cls = node.getClassName();
        if (cls != null && (cls.toString().contains("Switch") || cls.toString().contains("CheckBox"))
                && !node.isChecked() && labelMentionsKeyword(node)) {
            AccessibilityNodeInfo target = node.isClickable() ? node : node.getParent();
            if (target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                lastClick = System.currentTimeMillis();
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            walk(node.getChild(i), depth + 1);
        }
    }

    private boolean labelMentionsKeyword(AccessibilityNodeInfo node) {
        String label = textOf(node) + textOf(node.getParent());
        AccessibilityNodeInfo parent = node.getParent();
        if (parent != null) {
            for (int i = 0; i < parent.getChildCount(); i++) label += textOf(parent.getChild(i));
        }
        for (String keyword : KEYWORDS) if (label.contains(keyword)) return true;
        return false;
    }

    private static String textOf(AccessibilityNodeInfo node) {
        if (node == null) return "";
        CharSequence text = node.getText();
        CharSequence desc = node.getContentDescription();
        return (text == null ? "" : text.toString()) + (desc == null ? "" : desc.toString());
    }

    @Override public void onInterrupt() { }
}
