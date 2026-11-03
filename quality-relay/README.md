# SpeedTester real-world Quality relay (optional)

The Android app does **not** fabricate game/UDP loss or message delivery. These checks require a controlled server.

## Server

Requires Node.js 20+. Run npm install, then:

    QUALITY_LISTEN=0.0.0.0 QUALITY_HTTP_PORT=8787 QUALITY_UDP_PORT=8788 npm start

Use a VPS reachable from the tested connection. Configure a TLS reverse proxy exposing
wss://your-host.example/ws to local TCP port 8787. The relay must use WSS/TLS;
never expose a plain ws:// connection to production devices. Allow UDP port 8788 in
the host firewall (with rate limits). The relay limits packet length and echo rate.

## Phone and receiving client

On the SpeedTester Quality page, opt into Advanced real-world tests. Set UDP relay
hostname and UDP port 8788; set the wss:// URL and a random pairing code (12–64 chars).
On a second device/PC run:

    node receiver.mjs wss://your-host.example/ws RANDOMROOMCODE123456

The receiver processes five synthetic test messages and returns matching acknowledgements.
The relay itself NEVER pretends that the recipient received a message.

This measures two-device synthetic message delivery, NOT WhatsApp delivery. WhatsApp
would require official Business API authorization and delivery webhook verification.

The UDP benchmark sends 12 sequenced, random-nonce packets and validates matching
echoes from the configured server. It calculates packet loss ONLY after at least
one matching response. It is an interactive UDP game-action proxy, not actual
performance inside an unrelated game.

## 4K video and ICMP

The app uses Microsoft's documented, clear 3840x2160 H.264 test clip with Media3
ExoPlayer in a visible preview. It observes video dimensions, rendered frames,
playback progress, dropped frames and stalls; a 24 MiB read cap and 15s time limit
avoid unlimited data consumption. It never reports 4K as verified without actual
rendering and >= 3840x2160 decoded video.

Gateway ICMP and TTL=1..3 route probes run via Android ping when available.
ICMP filtering and OEM restrictions can prevent replies; absence of a response
means unavailable, not confirmed hop loss. No root or ADB required.

Per-scenario UID traffic counters capture app RX/TX deltas. These are approximate
because other app-owned network work can occur concurrently. Manual, disabled by
default, cancellation stops remaining work.

## Privacy and operations

Synthetic messages only. No contacts, phone numbers or personal messages. Pairing
codes are never written into test history. The relay logs no message contents.
Use a long random code; a production instance requires authentication, WSS TLS
and network rate limiting.
