package cn.sharinghub.ipcam;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Synchronous H.264 encoder fed with legacy-camera NV21 frames.
 * Uses byte-buffer input (no Surface) so it works on API 17.
 * Output is delivered per access unit in Annex-B form.
 */
public class H264Encoder {
    public interface Listener {
        void onFormat(byte[] sps, byte[] pps);
        void onAccessUnit(byte[] annexB, long ptsUs);
    }

    private final MediaCodec codec;
    private final int colorFormat;
    private final int width, height;
    private final Listener listener;
    private final ByteBuffer[] inputs;
    private ByteBuffer[] outputs;
    private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
    private boolean closed;

    public H264Encoder(int width, int height, Listener listener) throws IOException {
        this.width = width;
        this.height = height;
        this.listener = listener;
        MediaCodecInfo chosen = null;
        int fmt = 0;
        for (int i = 0; i < MediaCodecList.getCodecCount(); i++) {
            MediaCodecInfo candidate = MediaCodecList.getCodecInfoAt(i);
            if (!candidate.isEncoder()) continue;
            for (String type : candidate.getSupportedTypes()) {
                if (!type.equalsIgnoreCase("video/avc")) continue;
                int f = pickColorFormat(candidate);
                if (f != 0) { chosen = candidate; fmt = f; break; }
            }
            if (chosen != null) break;
        }
        if (chosen == null) throw new IOException("no H.264 encoder with YUV420 input");
        colorFormat = fmt;
        MediaFormat format = MediaFormat.createVideoFormat("video/avc", width, height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, fmt);
        format.setInteger(MediaFormat.KEY_BIT_RATE, Math.max(256000, width * height * 2));
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 15);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        codec = MediaCodec.createByCodecName(chosen.getName());
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        codec.start();
        inputs = codec.getInputBuffers();
        outputs = codec.getOutputBuffers();
    }

    private static int pickColorFormat(MediaCodecInfo info) {
        MediaCodecInfo.CodecCapabilities caps;
        try { caps = info.getCapabilitiesForType("video/avc"); } catch (Exception e) { return 0; }
        for (int c : caps.colorFormats) if (c == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) return c;
        for (int c : caps.colorFormats) if (c == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) return c;
        return 0;
    }

    /** Accepts one NV21 frame; drops it when the codec has no free input buffer. */
    public synchronized void submit(byte[] nv21, long ptsUs) {
        if (closed) return;
        try {
            int idx = codec.dequeueInputBuffer(0);
            if (idx >= 0) {
                byte[] frame = convert(nv21);
                ByteBuffer buf = inputs[idx];
                buf.clear();
                buf.put(frame);
                codec.queueInputBuffer(idx, 0, frame.length, ptsUs, 0);
            }
            drain();
        } catch (Exception ignored) { }
    }

    private void drain() {
        for (; ; ) {
            int out = codec.dequeueOutputBuffer(info, 0);
            if (out == MediaCodec.INFO_TRY_AGAIN_LATER) return;
            if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat f = codec.getOutputFormat();
                listener.onFormat(toArray(f.getByteBuffer("csd-0")), toArray(f.getByteBuffer("csd-1")));
                continue;
            }
            if (out == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) { outputs = codec.getOutputBuffers(); continue; }
            if (out < 0) continue;
            ByteBuffer buf = outputs[out];
            byte[] au = new byte[info.size];
            buf.position(info.offset);
            buf.get(au);
            codec.releaseOutputBuffer(out, false);
            if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) continue; // SPS/PPS travel in the SDP
            if (info.size > 0) listener.onAccessUnit(au, info.presentationTimeUs);
        }
    }

    private static byte[] toArray(ByteBuffer buf) {
        if (buf == null) return new byte[0];
        ByteBuffer dup = buf.duplicate();
        byte[] out = new byte[dup.remaining()];
        dup.get(out);
        return out;
    }

    /** NV21 -> the codec's YUV420 flavour. */
    private byte[] convert(byte[] nv21) {
        int ySize = width * height;
        if (colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) {
            // NV21 (VU) -> NV12 (UV): swap the chroma byte pairs.
            byte[] out = nv21.clone();
            for (int i = ySize; i + 1 < out.length; i += 2) { byte t = out[i]; out[i] = out[i + 1]; out[i + 1] = t; }
            return out;
        }
        // NV21 -> I420 planar.
        int qSize = ySize / 4;
        byte[] out = new byte[ySize + 2 * qSize];
        System.arraycopy(nv21, 0, out, 0, ySize);
        for (int i = 0; i < qSize; i++) {
            out[ySize + i] = nv21[ySize + 2 * i + 1];
            out[ySize + qSize + i] = nv21[ySize + 2 * i];
        }
        return out;
    }

    public synchronized void close() {
        closed = true;
        try { codec.stop(); } catch (Exception ignored) { }
        try { codec.release(); } catch (Exception ignored) { }
    }
}
