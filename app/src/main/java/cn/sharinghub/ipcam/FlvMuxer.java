package cn.sharinghub.ipcam;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Muxes H.264 Annex-B access units and raw AAC frames into FLV tags, so /live.flv
 * behaves like the reference server for FLV clients (e.g. flv.js).
 */
public class FlvMuxer {

    /** FLV file header, with the audio flag set when a sound track follows. */
    public static byte[] header(boolean hasAudio) {
        return new byte[]{'F', 'L', 'V', 0x01, hasAudio ? (byte) 0x05 : 0x01, 0, 0, 0, 0x09, 0, 0, 0, 0};
    }

    /** onMetaData script tag; mirrors the reference server's property list. */
    public static byte[] metadata(int width, int height, boolean hasAudio) {
        ByteArrayOutputStream amf = new ByteArrayOutputStream();
        amfString(amf, "onMetaData");
        amf.write(0x08); // ECMA array
        writeU32(amf, hasAudio ? 18 : 13);
        amfPropString(amf, "MetaDataCreator", "created by PocketCam");
        amfPropBool(amf, "hasKeyFrames", true);
        amfPropBool(amf, "hasVideo", true);
        amfPropBool(amf, "hasAudio", hasAudio);
        amfPropBool(amf, "hasMatadata", true);
        amfPropBool(amf, "canSeekToEnd", false);
        amfPropNumber(amf, "duration", 0);
        amfPropNumber(amf, "width", width);
        amfPropNumber(amf, "height", height);
        amfPropNumber(amf, "videodatarate", 0);
        amfPropNumber(amf, "framerate", 0);
        amfPropNumber(amf, "videocodecid", 7); // AVC
        if (hasAudio) {
            amfPropNumber(amf, "audiodatarate", 0);
            amfPropNumber(amf, "audiocodecid", 10); // AAC
            amfPropNumber(amf, "audiosamplerate", AacEncoder.SAMPLE_RATE);
            amfPropNumber(amf, "audiosamplesize", 16);
            amfPropBool(amf, "stereo", false);
        }
        amfPropNumber(amf, "filesize", 0);
        amf.write(0); amf.write(0); amf.write(0x09); // end marker
        return tag(0x12, 0, amf.toByteArray());
    }

    /** AVC sequence header tag (avcC) built from the encoder's SPS/PPS, timestamp 0. */
    public static byte[] sequenceHeader(byte[] sps, byte[] pps) {
        byte[] s = stripStartCode(sps);
        byte[] p = stripStartCode(pps);
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        data.write(0x17); // keyframe + AVC
        data.write(0x00); // AVCPacketType: sequence header
        data.write(0); data.write(0); data.write(0); // composition time
        data.write(0x01); // avcC version
        data.write(s.length > 3 ? s[1] : 0x42); // profile
        data.write(s.length > 3 ? s[2] : 0x00); // compatibility
        data.write(s.length > 3 ? s[3] : 0x20); // level
        data.write(0xFF); // 4-byte NALU lengths
        data.write(0xE1); // one SPS
        writeU16(data, s.length);
        data.write(s, 0, s.length);
        data.write(0x01); // one PPS
        writeU16(data, p.length);
        data.write(p, 0, p.length);
        return tag(0x09, 0, data.toByteArray());
    }

    /** One video tag holding the access unit as length-prefixed NALUs. */
    public static byte[] videoTag(byte[] annexB, long timestampMs, boolean keyFrame) {
        List<int[]> nals = splitNals(annexB);
        if (nals.isEmpty()) return null;
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        data.write((keyFrame ? 0x10 : 0x20) | 0x07); // frametype + AVC
        data.write(0x01); // AVCPacketType: NALU
        data.write(0); data.write(0); data.write(0); // composition time
        for (int[] span : nals) {
            writeU32(data, span[1]);
            data.write(annexB, span[0], span[1]);
        }
        return tag(0x09, timestampMs, data.toByteArray());
    }

    /** AAC sequence header tag carrying the encoder's AudioSpecificConfig, timestamp 0. */
    public static byte[] audioSequenceHeader(byte[] audioSpecificConfig) {
        ByteArrayOutputStream data = new ByteArrayOutputStream(2 + audioSpecificConfig.length);
        data.write(0xAE); // AAC, 44kHz, 16-bit, mono
        data.write(0x00); // AACPacketType: sequence header
        data.write(audioSpecificConfig, 0, audioSpecificConfig.length);
        return tag(0x08, 0, data.toByteArray());
    }

    /** One audio tag holding a raw AAC frame. */
    public static byte[] audioTag(byte[] frame, long timestampMs) {
        ByteArrayOutputStream data = new ByteArrayOutputStream(2 + frame.length);
        data.write(0xAE);
        data.write(0x01); // AACPacketType: raw frame
        data.write(frame, 0, frame.length);
        return tag(0x08, timestampMs, data.toByteArray());
    }

    /** True when the access unit carries an IDR slice. */
    public static boolean isKeyFrame(byte[] annexB) {
        for (int[] span : splitNals(annexB))
            if ((annexB[span[0]] & 0x1F) == 5) return true;
        return false;
    }

    // ------------------------------------------------------------------ internals

    private static byte[] tag(int type, long timestampMs, byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(11 + data.length + 4);
        out.write(type);
        out.write((data.length >> 16) & 0xFF);
        out.write((data.length >> 8) & 0xFF);
        out.write(data.length & 0xFF);
        out.write((int) (timestampMs >> 16) & 0xFF);
        out.write((int) (timestampMs >> 8) & 0xFF);
        out.write((int) timestampMs & 0xFF);
        out.write((int) (timestampMs >> 24) & 0xFF); // extended timestamp
        out.write(0); out.write(0); out.write(0); // stream id
        out.write(data, 0, data.length);
        int size = 11 + data.length;
        out.write((size >> 24) & 0xFF);
        out.write((size >> 16) & 0xFF);
        out.write((size >> 8) & 0xFF);
        out.write(size & 0xFF);
        return out.toByteArray();
    }

    /** Returns [offset, length] pairs for each NAL unit, skipping start codes. */
    private static List<int[]> splitNals(byte[] au) {
        List<int[]> out = new ArrayList<>();
        int i = 0, nalStart = -1;
        while (i + 2 < au.length) {
            int prefix = 0;
            if (au[i] == 0 && au[i + 1] == 0 && au[i + 2] == 1) prefix = 3;
            else if (i + 3 < au.length && au[i] == 0 && au[i + 1] == 0 && au[i + 2] == 0 && au[i + 3] == 1) prefix = 4;
            if (prefix > 0) {
                if (nalStart >= 0) out.add(new int[]{nalStart, i - nalStart});
                nalStart = i + prefix;
                i += prefix;
            } else i++;
        }
        if (nalStart >= 0 && nalStart < au.length) out.add(new int[]{nalStart, au.length - nalStart});
        return out;
    }

    private static byte[] stripStartCode(byte[] nal) {
        if (nal.length >= 4 && nal[0] == 0 && nal[1] == 0 && nal[2] == 0 && nal[3] == 1) {
            byte[] out = new byte[nal.length - 4];
            System.arraycopy(nal, 4, out, 0, out.length);
            return out;
        }
        if (nal.length >= 3 && nal[0] == 0 && nal[1] == 0 && nal[2] == 1) {
            byte[] out = new byte[nal.length - 3];
            System.arraycopy(nal, 3, out, 0, out.length);
            return out;
        }
        return nal;
    }

    // ------------------------------------------------------------ AMF0 encoding

    private static void amfString(ByteArrayOutputStream out, String value) {
        out.write(0x02);
        writeU16(out, value.length());
        writeAscii(out, value);
    }

    private static void amfPropName(ByteArrayOutputStream out, String name) {
        writeU16(out, name.length());
        writeAscii(out, name);
    }

    private static void amfPropString(ByteArrayOutputStream out, String name, String value) {
        amfPropName(out, name);
        amfString(out, value);
    }

    private static void amfPropBool(ByteArrayOutputStream out, String name, boolean value) {
        amfPropName(out, name);
        out.write(0x01);
        out.write(value ? 1 : 0);
    }

    private static void amfPropNumber(ByteArrayOutputStream out, String name, double value) {
        amfPropName(out, name);
        out.write(0x00);
        long bits = Double.doubleToLongBits(value);
        for (int shift = 56; shift >= 0; shift -= 8) out.write((int) (bits >> shift) & 0xFF);
    }

    private static void writeU16(ByteArrayOutputStream out, int value) {
        out.write((value >> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    private static void writeU32(ByteArrayOutputStream out, long value) {
        out.write((int) (value >> 24) & 0xFF);
        out.write((int) (value >> 16) & 0xFF);
        out.write((int) (value >> 8) & 0xFF);
        out.write((int) value & 0xFF);
    }

    private static void writeAscii(ByteArrayOutputStream out, String value) {
        for (int i = 0; i < value.length(); i++) out.write(value.charAt(i) & 0xFF);
    }
}
