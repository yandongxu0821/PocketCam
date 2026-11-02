package cn.sharinghub.ipcam;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

/**
 * Full-screen live preview. Frames come from the stream service (same process) as JPEG;
 * rotation is applied by rotating the ImageView itself (GPU) instead of re-rendering each
 * frame, and decoded bitmaps are reused via inBitmap, so the UI thread stays idle.
 */
public class PreviewActivity extends Activity {
    private ImageView preview;
    private TextView hint;
    private volatile boolean visible;
    private Thread worker;
    private byte[] lastFrame;
    private Bitmap reusable;
    private int appliedRotation = -1;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ThemeUtil.apply(this);
        setContentView(R.layout.activity_preview);
        if (getActionBar() != null) getActionBar().setDisplayHomeAsUpEnabled(true);
        preview = (ImageView) findViewById(R.id.preview);
        hint = (TextView) findViewById(R.id.preview_hint);
        preview.post(this::applyRotation);
    }

    /** Rotates the whole view; on 90/270 the bounds swap so a landscape frame fills portrait. */
    private void applyRotation() {
        int rotation = CameraStreamService.previewRotation();
        if (rotation == appliedRotation) return;
        appliedRotation = rotation;
        ViewGroup.LayoutParams lp = preview.getLayoutParams();
        View parent = (View) preview.getParent();
        if (rotation == 90 || rotation == 270) {
            lp.width = parent.getHeight();
            lp.height = parent.getWidth();
        } else {
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
        }
        preview.setLayoutParams(lp);
        preview.setRotation(rotation);
    }

    @Override public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) { finish(); return true; }
        return super.onOptionsItemSelected(item);
    }

    @Override protected void onResume() {
        super.onResume();
        visible = true;
        // Keeping the page open counts as a client, so the idle watcher won't release the camera.
        CameraStreamService.holdForPreview();
        worker = new Thread(this::decodeLoop, "preview-decode");
        worker.start();
    }

    @Override protected void onPause() {
        visible = false;
        CameraStreamService.releasePreview();
        if (worker != null) {
            try { worker.join(500); } catch (InterruptedException ignored) { }
            worker = null;
        }
        super.onPause();
    }

    private void decodeLoop() {
        while (visible) {
            final byte[] jpeg = CameraStreamService.currentJpeg();
            if (jpeg == null) {
                runOnUiThread(() -> hint.setVisibility(View.VISIBLE));
                sleep(500);
                continue;
            }
            // The camera is opened on demand, so its rotation is only known once frames arrive.
            if (CameraStreamService.previewRotation() != appliedRotation) runOnUiThread(this::applyRotation);
            if (jpeg != lastFrame) {
                lastFrame = jpeg;
                final Bitmap bitmap = decode(jpeg);
                if (bitmap != null) {
                    runOnUiThread(() -> {
                        preview.setImageBitmap(bitmap);
                        hint.setVisibility(View.GONE);
                    });
                }
            }
            sleep(50);
        }
    }

    private Bitmap decode(byte[] jpeg) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inMutable = true;
        if (reusable != null && reusable.getWidth() > 0) options.inBitmap = reusable;
        Bitmap result;
        try {
            result = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, options);
        } catch (IllegalArgumentException e) {
            result = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
        }
        if (result != null && result != reusable) {
            // Keep a same-sized mutable bitmap for zero-allocation decodes of later frames.
            if (result.isMutable()) reusable = result;
        }
        return result;
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }
}
