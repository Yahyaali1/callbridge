package com.callbridge.common;

import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Authenticated, encrypted channel between phone B and the host bridge app.
 *
 * Handshake (plaintext JSON frames, each at most {@link #HANDSHAKE_MAX} bytes):
 * <pre>
 *   B -> H  hello     {v, mode: "key"|"pair", pub, n}         ephemeral P-256 key + nonce
 *   H -> B  challenge {pub, n, mac?}                         mac only in key mode (host proves first)
 *   B -> H  auth      {mac}
 *   H -> B  ok        {mac}  or  auth_fail {reason, retry?}
 * </pre>
 * The secret is the long-term pairing key (mode "key") or a hash of the 6-digit code (mode "pair").
 * Session keys come from ECDH bound to the transcript and the secret, so a passive listener learns
 * nothing and nobody without the secret can complete the handshake. In key mode the host proves
 * itself before phone B reveals anything, so a stranger's Wi-Fi gateway learns nothing. A successful
 * pairing derives a fresh 256-bit pairing key on both sides; the code is not used again.
 *
 * Residual risk: an active man-in-the-middle present at the moment of pairing could brute-force the
 * 6-digit code offline from phone B's pairing proof. Pairing is a one-time, user-initiated step.
 *
 * After the handshake every frame payload is AES-256-GCM with a per-direction key, a counter nonce
 * (so frames cannot be replayed, dropped or reordered undetected) and the frame type as AAD.
 */
public final class Secure {
    public static final int VERSION = 2;
    public static final int HANDSHAKE_MAX = 4096;

    private static final SecureRandom RNG = new SecureRandom();
    private static final Base64.Encoder B64 = Base64.getEncoder();
    private static final Base64.Decoder UNB64 = Base64.getDecoder();

    private final SecretKeySpec sendKey, recvKey;
    private final Cipher enc, dec;
    private long sendCtr, recvCtr;
    /** Set on both sides after a successful pairing: the new long-term key to store. */
    public final byte[] newKey;

    private Secure(byte[] send, byte[] recv, byte[] newKey) throws GeneralSecurityException {
        sendKey = new SecretKeySpec(send, "AES");
        recvKey = new SecretKeySpec(recv, "AES");
        enc = Cipher.getInstance("AES/GCM/NoPadding");
        dec = Cipher.getInstance("AES/GCM/NoPadding");
        this.newKey = newKey;
    }

    /** Handshake failure. {@code reason} is a short machine-readable code. */
    public static final class AuthException extends IOException {
        public final String reason;
        public final long retryMs;
        public AuthException(String reason) { this(reason, 0); }
        public AuthException(String reason, long retryMs) { super("auth failed: " + reason); this.reason = reason; this.retryMs = retryMs; }
    }

    /** Host-side pairing state (key, code, brute-force throttle). */
    public interface Policy {
        /** Current long-term pairing key, or null if nothing is paired. */
        byte[] key();
        /** Current pairing code. */
        String code();
        /** Reserves one pairing attempt; returns 0 if allowed, else ms until the next is allowed. */
        long beginPairAttempt();
        /** Pairing succeeded: store the new key, rotate the code, reset the throttle. */
        void paired(byte[] newKey);
    }

    // ------------------------------------------------------------------ frames

    synchronized byte[] seal(int type, byte[] data, int off, int len) throws IOException {
        try {
            enc.init(Cipher.ENCRYPT_MODE, sendKey, new GCMParameterSpec(128, iv(sendCtr++)));
            enc.updateAAD(new byte[]{(byte) type});
            return enc.doFinal(data, off, len);
        } catch (GeneralSecurityException e) {
            throw new IOException("encrypt failed", e);
        }
    }

    synchronized byte[] open(int type, byte[] ct) throws IOException {
        try {
            dec.init(Cipher.DECRYPT_MODE, recvKey, new GCMParameterSpec(128, iv(recvCtr++)));
            dec.updateAAD(new byte[]{(byte) type});
            return dec.doFinal(ct);
        } catch (GeneralSecurityException e) {
            throw new IOException("frame authentication failed");
        }
    }

    private static byte[] iv(long ctr) {
        byte[] iv = new byte[12];
        for (int i = 0; i < 8; i++) iv[11 - i] = (byte) (ctr >>> (8 * i));
        return iv;
    }

    // ------------------------------------------------------------------ handshake: phone B

    /** Phone B side. Uses {@code key} if paired, else pairs with {@code code}. */
    public static Secure client(DataInputStream in, DataOutputStream out, byte[] key, String code) throws IOException {
        boolean pair = key == null;
        if (pair && (code == null || code.isEmpty())) throw new AuthException("need_code");
        try {
            KeyPair kp = ephemeral();
            String pubC = b64(kp.getPublic().getEncoded()), nC = b64(random(16));
            String mode = pair ? "pair" : "key";
            Proto.json(out, Proto.m("hello", "v", VERSION, "mode", mode, "pub", pubC, "n", nC));

            JSONObject ch = Proto.read(in, HANDSHAKE_MAX).json();
            if ("auth_fail".equals(ch.optString("t"))) throw fail(ch);
            if (!"challenge".equals(ch.optString("t"))) throw new AuthException("protocol");
            String pubS = ch.getString("pub"), nS = ch.getString("n");
            byte[] th = transcript(mode, pubC, nC, pubS, nS);
            byte[] secret = pair ? codeSecret(code) : key;
            if (!pair && !macOk(secret, "server", th, ch.optString("mac"))) throw new AuthException("host_unverified");
            byte[] shared = ecdh(kp, pubS);

            Proto.json(out, Proto.m("auth", "mac", b64(mac(secret, "client", th))));
            JSONObject ok = Proto.read(in, HANDSHAKE_MAX).json();
            if ("auth_fail".equals(ok.optString("t"))) throw fail(ok);
            if (!"ok".equals(ok.optString("t")) || !macOk(secret, "server-final", th, ok.optString("mac")))
                throw new AuthException("host_unverified");

            return new Secure(derive(shared, secret, th, "c2s"), derive(shared, secret, th, "s2c"),
                    pair ? derive(shared, secret, th, "pairkey") : null);
        } catch (GeneralSecurityException | org.json.JSONException | IllegalArgumentException e) {
            throw new AuthException("protocol");
        }
    }

    private static AuthException fail(JSONObject m) {
        return new AuthException(m.optString("reason", "rejected"), m.optLong("retry", 0) * 1000);
    }

    // ------------------------------------------------------------------ handshake: host

    /** Host side. Throws AuthException after telling the peer why. */
    public static Secure server(DataInputStream in, DataOutputStream out, Policy policy) throws IOException {
        try {
            JSONObject hello = Proto.read(in, HANDSHAKE_MAX).json();
            if (!"hello".equals(hello.optString("t")) || hello.optInt("v") != VERSION)
                throw reject(out, "upgrade", 0);
            String mode = hello.optString("mode");
            boolean pair = "pair".equals(mode);
            if (!pair && !"key".equals(mode)) throw reject(out, "protocol", 0);
            byte[] secret;
            if (pair) {
                long wait = policy.beginPairAttempt();
                if (wait > 0) throw reject(out, "locked", wait);
                String code = policy.code();
                if (code == null || code.isEmpty()) throw reject(out, "bad_code", 0);
                secret = codeSecret(code);
            } else {
                secret = policy.key();
                if (secret == null) throw reject(out, "not_paired", 0);
            }

            String pubC = hello.getString("pub"), nC = hello.getString("n");
            KeyPair kp = ephemeral();
            String pubS = b64(kp.getPublic().getEncoded()), nS = b64(random(16));
            byte[] th = transcript(mode, pubC, nC, pubS, nS);
            byte[] shared = ecdh(kp, pubC);
            JSONObject ch = Proto.m("challenge", "pub", pubS, "n", nS);
            if (!pair) ch.put("mac", b64(mac(secret, "server", th)));
            Proto.json(out, ch);

            JSONObject auth = Proto.read(in, HANDSHAKE_MAX).json();
            if (!"auth".equals(auth.optString("t")) || !macOk(secret, "client", th, auth.optString("mac")))
                throw reject(out, pair ? "bad_code" : "bad_key", 0);

            byte[] newKey = pair ? derive(shared, secret, th, "pairkey") : null;
            if (pair) policy.paired(newKey);
            Proto.json(out, Proto.m("ok", "mac", b64(mac(secret, "server-final", th))));
            return new Secure(derive(shared, secret, th, "s2c"), derive(shared, secret, th, "c2s"), newKey);
        } catch (GeneralSecurityException | org.json.JSONException | IllegalArgumentException e) {
            throw reject(out, "protocol", 0);
        }
    }

    private static AuthException reject(DataOutputStream out, String reason, long retryMs) {
        try {
            JSONObject m = Proto.m("auth_fail", "reason", reason);
            if (retryMs > 0) m.put("retry", (retryMs + 999) / 1000);
            Proto.json(out, m);
        } catch (Exception ignored) {}
        return new AuthException(reason, retryMs);
    }

    // ------------------------------------------------------------------ primitives

    public static byte[] random(int n) { byte[] b = new byte[n]; RNG.nextBytes(b); return b; }
    public static String b64(byte[] b) { return B64.encodeToString(b); }
    public static byte[] unb64(String s) { return UNB64.decode(s); }

    /** Constant-time string equality (for tokens). */
    public static boolean equal(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    static byte[] codeSecret(String code) throws GeneralSecurityException {
        return MessageDigest.getInstance("SHA-256").digest(("callbridge-pair-v2\n" + code.trim()).getBytes(StandardCharsets.UTF_8));
    }

    static KeyPair ephemeral() throws GeneralSecurityException {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"), RNG);
        return g.generateKeyPair();
    }

    static byte[] ecdh(KeyPair mine, String theirPub) throws GeneralSecurityException {
        PublicKey pk = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(unb64(theirPub)));
        KeyAgreement ka = KeyAgreement.getInstance("ECDH");
        ka.init(mine.getPrivate());
        ka.doPhase(pk, true);
        return ka.generateSecret();
    }

    static byte[] transcript(String mode, String pubC, String nC, String pubS, String nS) throws GeneralSecurityException {
        String t = "callbridge-v2\n" + mode + "\n" + pubC + "\n" + nC + "\n" + pubS + "\n" + nS;
        return MessageDigest.getInstance("SHA-256").digest(t.getBytes(StandardCharsets.UTF_8));
    }

    static byte[] mac(byte[] key, String label, byte[] th) throws GeneralSecurityException {
        Mac m = Mac.getInstance("HmacSHA256");
        m.init(new SecretKeySpec(key, "HmacSHA256"));
        m.update(label.getBytes(StandardCharsets.UTF_8));
        m.update((byte) 0);
        return m.doFinal(th);
    }

    static boolean macOk(byte[] key, String label, byte[] th, String macB64) throws GeneralSecurityException {
        if (macB64 == null || macB64.isEmpty()) return false;
        byte[] got;
        try { got = unb64(macB64); } catch (IllegalArgumentException e) { return false; }
        return MessageDigest.isEqual(mac(key, label, th), got);
    }

    /** Session / pairing key: HMAC(ecdh, label | th | secret). */
    static byte[] derive(byte[] shared, byte[] secret, byte[] th, String label) throws GeneralSecurityException {
        Mac m = Mac.getInstance("HmacSHA256");
        m.init(new SecretKeySpec(shared, "HmacSHA256"));
        m.update(label.getBytes(StandardCharsets.UTF_8));
        m.update((byte) 0);
        m.update(th);
        return m.doFinal(secret);
    }
}
