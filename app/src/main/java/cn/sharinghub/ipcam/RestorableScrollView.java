package cn.sharinghub.ipcam;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.widget.ScrollView;

/**
 * ScrollView that can hold a scroll offset for a moment. After an activity recreation the
 * list briefly sits at the top (framework restore clamps against a not-yet-final content
 * height, focus changes scroll children into view), so the offset is pinned until the
 * layout settles, and released on the first user touch.
 */
public class RestorableScrollView extends ScrollView {
    private boolean pinned;
    private int pinnedY;

    public RestorableScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void pin(int y) {
        pinned = true;
        pinnedY = y;
        applyPinned();
    }

    public void unpin() {
        pinned = false;
    }

    @Override public void scrollTo(int x, int y) {
        super.scrollTo(x, pinned ? pinnedY : y);
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        applyPinned();
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) pinned = false;
        return super.onInterceptTouchEvent(event);
    }

    private void applyPinned() {
        if (pinned) super.scrollTo(0, pinnedY);
    }
}
