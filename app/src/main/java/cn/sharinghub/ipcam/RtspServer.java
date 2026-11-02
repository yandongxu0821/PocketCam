package cn.sharinghub.ipcam;

import android.util.Base64;
import android.util.Log;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal RTSP server for one H.264 track. Supports OPTIONS, DESCRIBE, SETUP, PLAY
 * and TEARDOWN. RTP can go over the interleaved RTSP TCP connection or over UDP,
 * depending on the allowUdp flag and the client's SETUP request.
 */
public class RtspServer {
    private static final String TAG = "PocketCamRtsp";
    private static final int MTU = 1400;

    /** Told when a client connects, so the service can open the camera on demand. */
    public interface Listener { void onClientConnected(); }

    private final int port;
    private final boolean allowUdp;
    private final Listener listener;
    private volatile byte[] sps, pps;
    private final List<Client> clients = new ArrayList<>();
    private final List<Client> connected = new ArrayList<>();
    private final List<ServerSocket> servers = new ArrayList<>();
    private volatile boolean serving;

    public RtspServer(int port, boolean allowUdp, Listener listener) {
        this.port = port;
        this.allowUdp = allowUdp;
        this.listener = listener;
    }

    public void setSpsPps(byte[] sps, byte[] pps) { this.sps = sps; this.pps = pps; }

    /** Clients with an open RTSP connection; keeps the camera alive while present. */
    public int connectedCount() {
        synchronized (connected) { return connected.size(); }
    }

    public void start() throws IOException {
        servers.addAll(bindAllAddresses(port));
        serving = true;
        for (final ServerSocket socket : servers) {
            Thread accept = new Thread(() -> {
                while (serving) {
                    try {
                        Client client = new Client(socket.accept());
                        synchronized (connected) { connected.add(client); }
                        if (listener != null) listener.onClientConnected();
                        client.start();
                    } catch (Exception e) { if (serving) Log.w(TAG, "accept failed", e); }
                }
            }, "rtsp-accept");
            accept.start();
        }
    }

    /**
     * Listens on the IPv4 and the IPv6 wildcard address. A plain ServerSocket(port) only covers
     * IPv4, so a client reaching the phone over an IPv6 VPN/tun interface could not connect.
     * A colliding sibling (platforms sometimes make them overlap) is tolerated.
     */
    private static List<ServerSocket> bindAllAddresses(int port) throws IOException {
        List<ServerSocket> sockets = new ArrayList<>(2);
        IOException failure = null;
        for (String host : new String[]{"0.0.0.0", "::"}) {
            try {
                ServerSocket socket = new ServerSocket();
                socket.setReuseAddress(true);
                socket.bind(new InetSocketAddress(InetAddress.getByName(host), port), 50);
                sockets.add(socket);
            } catch (IOException e) {
                if (failure == null) failure = e;
            }
        }
        if (sockets.isEmpty()) throw failure;
        return sockets;
    }

    public void stop() {
        serving = false;
        for (ServerSocket socket : servers) {
            try { socket.close(); } catch (Exception ignored) { }
        }
        servers.clear();
        synchronized (clients) {
            for (Client c : new ArrayList<>(clients)) c.close();
            clients.clear();
        }
        synchronized (connected) {
            for (Client c : new ArrayList<>(connected)) c.close();
            connected.clear();
        }
    }

    /** Fans one Annex-B access unit out to every playing client. */
    public void broadcast(byte[] accessUnit, long ptsUs) {
        if (sps == null || pps == null) return;
        List<int[]> nals = splitNals(accessUnit);
        if (nals.isEmpty()) return;
        long ts90k = ptsUs * 90 / 1000;
        synchronized (clients) {
            if (clients.isEmpty()) return;
            for (Client c : new ArrayList<>(clients)) c.send(accessUnit, nals, ts90k);
        }
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
            byte[] out = new byte[nal.length - 4]; System.arraycopy(nal, 4, out, 0, out.length); return out;
        }
        if (nal.length >= 3 && nal[0] == 0 && nal[1] == 0 && nal[2] == 1) {
            byte[] out = new byte[nal.length - 3]; System.arraycopy(nal, 3, out, 0, out.length); return out;
        }
        return nal;
    }

    private static int parseClientPort(String transport) {
        if (transport == null) return -1;
        for (String part : transport.split(";")) {
            part = part.trim();
            if (part.startsWith("client_port=")) {
                try { return Integer.parseInt(part.substring(12).split("-")[0]); }
                catch (Exception e) { return -1; }
            }
        }
        return -1;
    }

    private class Client {
        private final Socket socket;
        private OutputStream out;
        private int seq;
        private boolean playing;
        private DatagramSocket udp;
        private java.net.InetAddress udpAddress;
        private int udpPort;

        Client(Socket socket) { this.socket = socket; }

        void start() { new Thread(this::loop, "rtsp-client").start(); }

        private void loop() {
            try {
                socket.setSoTimeout(0);
                out = socket.getOutputStream();
                BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                String requestLine;
                while ((requestLine = in.readLine()) != null) {
                    Map<String, String> headers = new HashMap<>();
                    String line;
                    while ((line = in.readLine()) != null && line.length() > 0) {
                        int colon = line.indexOf(':');
                        if (colon > 0) headers.put(line.substring(0, colon).trim().toLowerCase(), line.substring(colon + 1).trim());
                    }
                    if (!handle(requestLine, headers)) return;
                }
            } catch (Exception ignored) { }
            close();
        }

        private boolean handle(String requestLine, Map<String, String> headers) throws IOException {
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return true;
            String method = parts[0].toUpperCase();
            String url = parts[1];
            String cseq = headers.containsKey("cseq") ? "CSeq: " + headers.get("cseq") + "\r\n" : "";
            switch (method) {
                case "OPTIONS":
                    respond("RTSP/1.0 200 OK\r\n" + cseq + "Public: OPTIONS, DESCRIBE, SETUP, PLAY, TEARDOWN\r\n\r\n");
                    return true;
                case "DESCRIBE": {
                    // The camera may still be opening for this client; SDP needs real SPS/PPS.
                    waitForParameterSets();
                    if (sps == null || pps == null) {
                        respond("RTSP/1.0 503 Service Unavailable\r\n" + cseq + "\r\n");
                        return true;
                    }
                    byte[] sdp = sdp().getBytes("UTF-8");
                    respond("RTSP/1.0 200 OK\r\n" + cseq + "Content-Base: " + url + "\r\nContent-Type: application/sdp\r\nContent-Length: " + sdp.length + "\r\n\r\n");
                    out.write(sdp);
                    out.flush();
                    return true;
                }
                case "SETUP": {
                    String transportHeader = headers.get("transport");
                    String transport = transportHeader == null ? "" : transportHeader.toLowerCase();
                    if (transport.contains("rtp/avp/tcp")) {
                        respond("RTSP/1.0 200 OK\r\n" + cseq + "Transport: RTP/AVP/TCP;unicast;interleaved=0-1\r\nSession: 1\r\n\r\n");
                        return true;
                    }
                    if (!allowUdp) {
                        respond("RTSP/1.0 461 Unsupported Transport\r\n" + cseq + "\r\n");
                        return true;
                    }
                    int clientPort = parseClientPort(headers.get("transport"));
                    if (clientPort <= 0) {
                        respond("RTSP/1.0 400 Bad Request\r\n" + cseq + "\r\n");
                        return true;
                    }
                    udp = new DatagramSocket();
                    udpAddress = socket.getInetAddress();
                    this.udpPort = clientPort;
                    respond("RTSP/1.0 200 OK\r\n" + cseq + "Transport: RTP/AVP;unicast;client_port=" + clientPort + "-" + (clientPort + 1)
                            + ";server_port=" + udp.getLocalPort() + "\r\nSession: 1\r\n\r\n");
                    return true;
                }
                case "PLAY":
                    respond("RTSP/1.0 200 OK\r\n" + cseq + "Session: 1\r\n\r\n");
                    playing = true;
                    synchronized (clients) { if (!clients.contains(this)) clients.add(this); }
                    return true;
                case "TEARDOWN":
                    respond("RTSP/1.0 200 OK\r\n" + cseq + "\r\n");
                    return false;
                default:
                    respond("RTSP/1.0 501 Not Implemented\r\n" + cseq + "\r\n");
                    return true;
            }
        }

        private void waitForParameterSets() {
            long deadline = System.currentTimeMillis() + 6000;
            while ((sps == null || pps == null) && System.currentTimeMillis() < deadline) {
                try { Thread.sleep(50); } catch (InterruptedException ignored) { return; }
            }
        }

        private void respond(String response) throws IOException {
            synchronized (this) {
                out.write(response.getBytes("US-ASCII"));
                out.flush();
            }
        }

        private String sdp() {
            String spsB64 = Base64.encodeToString(stripStartCode(sps), Base64.NO_WRAP);
            String ppsB64 = Base64.encodeToString(stripStartCode(pps), Base64.NO_WRAP);
            // Interleaved transport is negotiated in SETUP, never in the SDP m-line.
            return "v=0\r\no=- 1 1 IN IP4 0.0.0.0\r\ns=PocketCam\r\nc=IN IP4 0.0.0.0\r\nt=0 0\r\na=control:*\r\n"
                    + "m=video 0 RTP/AVP 96\r\n"
                    + "a=rtpmap:96 H264/90000\r\n"
                    + "a=fmtp:96 packetization-mode=1;sprop-parameter-sets=" + spsB64 + "," + ppsB64 + "\r\n"
                    + "a=control:track1\r\n";
        }

        void send(byte[] au, List<int[]> nals, long ts90k) {
            if (!playing) return;
            try {
                for (int n = 0; n < nals.size(); n++) {
                    int off = nals.get(n)[0], len = nals.get(n)[1];
                    int type = au[off] & 0x1F;
                    if (type == 6 || type == 7 || type == 8 || type == 9) continue; // SEI/SPS/PPS/AUD carried in SDP
                    boolean last = n == nals.size() - 1;
                    if (len <= MTU) {
                        writePacket(au, off, len, last, ts90k);
                    } else {
                        int nri = au[off] & 0xE0;
                        int fuHeader = au[off] & 0x1F;
                        int pos = off + 1, remaining = len - 1;
                        boolean first = true;
                        while (remaining > 0) {
                            int chunk = Math.min(remaining, MTU - 2);
                            boolean fuEnd = remaining - chunk == 0;
                            byte[] payload = new byte[chunk + 2];
                            payload[0] = (byte) (nri | 28);
                            payload[1] = (byte) ((first ? 0x80 : 0) | (fuEnd ? 0x40 : 0) | fuHeader);
                            System.arraycopy(au, pos, payload, 2, chunk);
                            writePacket(payload, 0, payload.length, last && fuEnd, ts90k);
                            first = false; pos += chunk; remaining -= chunk;
                        }
                    }
                }
            } catch (Exception e) {
                close();
            }
        }

        private void writePacket(byte[] payload, int off, int len, boolean marker, long ts) throws IOException {
            boolean overUdp = udp != null;
            byte[] packet = new byte[(overUdp ? 12 : 16) + len];
            int base = overUdp ? 0 : 4;
            if (!overUdp) {
                packet[0] = '$'; packet[1] = 0;
                packet[2] = (byte) ((12 + len) >> 8); packet[3] = (byte) (12 + len);
            }
            packet[base] = (byte) 0x80; // V=2
            packet[base + 1] = (byte) (96 | (marker ? 0x80 : 0));
            int sequence;
            long t = ts & 0xFFFFFFFFL;
            synchronized (this) { sequence = seq; seq = (seq + 1) & 0xFFFF; }
            packet[base + 2] = (byte) (sequence >> 8); packet[base + 3] = (byte) sequence;
            packet[base + 4] = (byte) (t >> 24); packet[base + 5] = (byte) (t >> 16); packet[base + 6] = (byte) (t >> 8); packet[base + 7] = (byte) t;
            packet[base + 8] = 0x50; packet[base + 9] = 0x4B; packet[base + 10] = 0x43; packet[base + 11] = 0x41; // SSRC "PKCA"
            System.arraycopy(payload, off, packet, base + 12, len);
            if (overUdp) {
                udp.send(new DatagramPacket(packet, packet.length, udpAddress, udpPort));
            } else {
                synchronized (this) {
                    out.write(packet);
                    out.flush();
                }
            }
        }

        void close() {
            playing = false;
            synchronized (clients) { clients.remove(this); }
            synchronized (connected) { connected.remove(this); }
            if (udp != null) { try { udp.close(); } catch (Exception ignored) { } udp = null; }
            try { socket.close(); } catch (Exception ignored) { }
        }
    }
}
