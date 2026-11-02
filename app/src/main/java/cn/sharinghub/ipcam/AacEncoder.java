package cn.sharinghub.ipcam;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Microphone capture encoded to AAC, mirroring the reference server's live.flv audio
 * track. A dedicated thread feeds MediaCodec; output is raw AAC frames (no ADTS).
 * 44100 Hz mono is the one configuration AudioRecord guarantees on every device.
 */
public class AacEncoder {
    public interface Listener {
        void onFormat(byte[] audioSpecificConfig);
        void onAccessUnit(byte[] frame, long ptsUs);
        /** Raw PCM16 little-endian mono frames, delivered on the encoder thread. */
        void onPcm(byte[] pcm, int length);
    }

    static final int SAMPLE_RATE = 44100;

    private final Listener listener;
    private final AudioRecord record;
    private final MediaCodec codec;
    private final ByteBuffer[] inputs;
    private ByteBuffer[] outputs;
    private final Thread thread;
    private volatile boolean running = true;

    public AacEncoder(Listener listener) throws IOException {
        this.listener = listener;
        int minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuffer <= 0) throw new IOException("microphone buffer size unavailable");
        AudioRecord created;
        try {
            created = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuffer * 2);
        } catch (Exception e) {
            throw new IOException("microphone unavailable: " + e.getMessage());
        }
        if (created.getState() != AudioRecord.STATE_INITIALIZED) {
            created.release();
            throw new IOException("microphone unavailable");
        }
        record = created;
        try {
            MediaFormat format = MediaFormat.createAudioFormat("audio/mp4a-latm", SAMPLE_RATE, 1);
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            format.setInteger(MediaFormat.KEY_BIT_RATE, 64000);
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 8192);
            codec = MediaCodec.createEncoderByType("audio/mp4a-latm");
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start();
            record.startRecording();
        } catch (Exception e) {
            try { record.release(); } catch (Exception ignored) { }
            throw new IOException("AAC encoder unavailable: " + e.getMessage());
        }
        inputs = codec.getInputBuffers();
        outputs = codec.getOutputBuffers();
        thread = new Thread(this::loop, "aac-encoder");
        thread.start();
    }

    private void loop() {
        byte[] pcm = new byte[4096];
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (running) {
            int read;
            try {
                read = record.read(pcm, 0, pcm.length);
            } catch (Exception e) { android.util.Log.w("PocketCam", "aac read failed", e); return; }
            if (read < 0) { android.util.Log.w("PocketCam", "aac read error " + read); return; } // e.g. mic blocked by the system
            if (read == 0) continue;
            listener.onPcm(pcm, read);
            long ptsUs = System.nanoTime() / 1000; // same clock as the video encoder submits with
            try {
                // The codec's input buffer may be smaller than one AudioRecord read, so feed
                // the captured PCM across as many input buffers as needed.
                int offset = 0;
                while (offset < read && running) {
                    int in = codec.dequeueInputBuffer(10000);
                    if (in < 0) { drain(info); continue; }
                    ByteBuffer buf = inputs[in];
                    buf.clear();
                    int chunk = Math.min(read - offset, buf.remaining());
                    buf.put(pcm, offset, chunk);
                    long framePts = ptsUs + (offset * 1000000L / (SAMPLE_RATE * 2));
                    codec.queueInputBuffer(in, 0, chunk, framePts, 0);
                    offset += chunk;
                }
                drain(info);
            } catch (Exception e) { android.util.Log.w("PocketCam", "aac feed failed", e); return; }
        }
    }

    private void drain(MediaCodec.BufferInfo info) {
        for (; ; ) {
            int out = codec.dequeueOutputBuffer(info, 0);
            if (out == MediaCodec.INFO_TRY_AGAIN_LATER) return;
            if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                listener.onFormat(toArray(codec.getOutputFormat().getByteBuffer("csd-0")));
                continue;
            }
            if (out == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) { outputs = codec.getOutputBuffers(); continue; }
            if (out < 0) continue;
            ByteBuffer buf = outputs[out];
            byte[] frame = new byte[info.size];
            buf.position(info.offset);
            buf.get(frame);
            codec.releaseOutputBuffer(out, false);
            if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) continue; // config travels in the FLV sequence header
            if (info.size > 0) listener.onAccessUnit(frame, info.presentationTimeUs);
        }
    }

    private static byte[] toArray(ByteBuffer buf) {
        if (buf == null) return new byte[0];
        ByteBuffer dup = buf.duplicate();
        byte[] out = new byte[dup.remaining()];
        dup.get(out);
        return out;
    }

    public void close() {
        running = false;
        try { record.stop(); } catch (Exception ignored) { } // unblocks a pending read()
        try { thread.join(1000); } catch (Exception ignored) { }
        try { record.release(); } catch (Exception ignored) { }
        try { codec.stop(); } catch (Exception ignored) { }
        try { codec.release(); } catch (Exception ignored) { }
    }

    /** True while the capture/encode thread is still running. */
    public boolean isAlive() { return thread != null && thread.isAlive(); }
}
