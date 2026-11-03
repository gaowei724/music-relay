package app.musicrelay;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URL;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.Enumeration;
import java.util.Locale;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509KeyManager;
import javax.net.ssl.X509TrustManager;

// Transport between phone and player, kept simple for the user:
// - The player has one switch. Off = plain HTTP (default). On = HTTPS with a key the app
//   generates itself in the Android keystore; advanced users can import their own .p12.
// - The phone has no setting. Pairing finds out whether the player is encrypted and, if
//   so, remembers its certificate (like Bluetooth pairing). Afterwards only that certificate
//   is accepted, or a new one that the system already trusts for that address (e.g. a
//   renewed certificate from the user's own CA). Anything else means "pair again".
class Tls {
    static final String OFF = "off", AUTO = "auto", P12 = "p12";
    private static final String P12_FILE = "server.p12";
    private static final String ALIAS = "musicrelay-server";

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("am", Context.MODE_PRIVATE);
    }

    // ---- player ----

    static String serverMode(Context c) {
        String m = prefs(c).getString("serverTls", null);
        if (m == null) m = new File(c.getFilesDir(), P12_FILE).exists() ? P12 : OFF;
        return P12.equals(m) && !new File(c.getFilesDir(), P12_FILE).exists() ? OFF : m;
    }

    static boolean serverSecure(Context c) {
        return !OFF.equals(serverMode(c));
    }

    static void setServerMode(Context c, String m) {
        prefs(c).edit().putString("serverTls", m).apply();
    }

    // Null for plain HTTP.
    static SSLServerSocketFactory serverFactory(Context c) throws Exception {
        String m = serverMode(c);
        KeyManager[] km;
        if (AUTO.equals(m)) {
            km = new KeyManager[] {new KeystoreKeyManager(ownKey())};
        } else if (P12.equals(m)) {
            char[] pass = prefs(c).getString("p12pass", "").toCharArray();
            KeyStore ks = KeyStore.getInstance("PKCS12");
            try (InputStream in = new FileInputStream(new File(c.getFilesDir(), P12_FILE))) {
                ks.load(in, pass);
            }
            KeyManagerFactory kmf =
                    KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, pass);
            km = kmf.getKeyManagers();
        } else {
            return null;
        }
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(km, null, null);
        return ctx.getServerSocketFactory();
    }

    // The app's own key pair, created once in the Android keystore; the private key never
    // leaves it. The keystore issues a self-signed certificate for it.
    private static KeyStore ownKey() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (!ks.containsAlias(ALIAS)) {
            KeyPairGenerator g = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore");
            long now = System.currentTimeMillis();
            g.initialize(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256,
                            KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
                    .setCertificateSubject(new javax.security.auth.x500.X500Principal("CN=Music Relay"))
                    .setCertificateSerialNumber(BigInteger.valueOf(now))
                    .setCertificateNotBefore(new Date(now - 86400000L))
                    .setCertificateNotAfter(new Date(now + 30L * 365 * 86400000L))
                    .build());
            g.generateKeyPair();
        }
        return ks;
    }

    private static class KeystoreKeyManager implements X509KeyManager {
        private final KeyStore ks;

        KeystoreKeyManager(KeyStore ks) {
            this.ks = ks;
        }

        @Override public String chooseServerAlias(String type, Principal[] issuers, Socket s) {
            return ALIAS;
        }

        @Override public String[] getServerAliases(String type, Principal[] issuers) {
            return new String[] {ALIAS};
        }

        @Override public X509Certificate[] getCertificateChain(String alias) {
            try {
                return new X509Certificate[] {(X509Certificate) ks.getCertificate(ALIAS)};
            } catch (Exception e) {
                return null;
            }
        }

        @Override public PrivateKey getPrivateKey(String alias) {
            try {
                return (PrivateKey) ks.getKey(ALIAS, null);
            } catch (Exception e) {
                return null;
            }
        }

        @Override public String chooseClientAlias(String[] t, Principal[] i, Socket s) {
            return null;
        }

        @Override public String[] getClientAliases(String t, Principal[] i) {
            return null;
        }
    }

    // Checks that the file opens with the password and holds a private key, then keeps a copy.
    static void importServer(Context c, Uri uri, String pass) throws Exception {
        byte[] data = readAll(c, uri);
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(new ByteArrayInputStream(data), pass.toCharArray());
        boolean hasKey = false;
        for (Enumeration<String> e = ks.aliases(); e.hasMoreElements(); ) {
            if (ks.isKeyEntry(e.nextElement())) hasKey = true;
        }
        if (!hasKey) throw new Exception("证书文件里没有私钥");
        try (FileOutputStream out = new FileOutputStream(new File(c.getFilesDir(), P12_FILE))) {
            out.write(data);
        }
        prefs(c).edit().putString("p12pass", pass).putString("serverTls", P12).apply();
    }

    // ---- phone ----

    static boolean clientSecure(Context c) {
        return !prefs(c).getString("pin", "").isEmpty();
    }

    // A request to the paired player: HTTPS to the remembered certificate, else plain HTTP.
    static HttpURLConnection open(Context c, String path) throws Exception {
        return open(c, path, clientSecure(c), null);
    }

    // seen: while pairing, collects the certificate the player presents and accepts it.
    private static HttpURLConnection open(Context c, String path, boolean secure, String[] seen)
            throws Exception {
        SharedPreferences p = prefs(c);
        String host = p.getString("host", "");
        URL url = new URL((secure ? "https://" : "http://") + host + ":" + AgentService.PORT + path);
        android.net.Network lan = lanNetwork(c, host);
        HttpURLConnection h = (HttpURLConnection) (lan != null ? lan.openConnection(url) : url.openConnection());
        if (secure) {
            HttpsURLConnection s = (HttpsURLConnection) h;
            s.setSSLSocketFactory(lenient());
            s.setHostnameVerifier(seen != null ? (hn, ses) -> {
                seen[0] = fingerprint(ses);
                return !seen[0].isEmpty();
            } : new PinVerifier(c));
        }
        h.setRequestProperty("X-Token", p.getString("code", ""));
        return h;
    }

    // Pairing: try plain HTTP, and if the player answers in TLS, connect again over HTTPS and
    // remember its certificate. Returns the HTTP status; throws on network errors.
    static int pair(Context c) throws Exception {
        prefs(c).edit().remove("pin").apply();
        try {
            HttpURLConnection h = open(c, "/state", false, null);
            h.setConnectTimeout(4000);
            h.setReadTimeout(8000);
            try {
                return h.getResponseCode();
            } finally {
                h.disconnect();
            }
        } catch (java.net.SocketTimeoutException | java.net.ConnectException e) {
            throw e;
        } catch (java.io.IOException tlsProbably) {
            String[] seen = new String[1];
            HttpURLConnection h = open(c, "/state", true, seen);
            h.setConnectTimeout(4000);
            h.setReadTimeout(8000);
            try {
                int rc = h.getResponseCode();
                if (rc == 200 && seen[0] != null) prefs(c).edit().putString("pin", seen[0]).apply();
                return rc;
            } finally {
                h.disconnect();
            }
        }
    }

    // Accepts the remembered certificate, or one the system trusts for this address (then it
    // becomes the remembered one). The trust manager below accepts any certificate, so this
    // verifier is the real check; HttpsURLConnection runs it before sending the request.
    private static class PinVerifier implements HostnameVerifier {
        private final Context c;

        PinVerifier(Context c) {
            this.c = c;
        }

        @Override public boolean verify(String host, SSLSession s) {
            String fp = fingerprint(s);
            if (fp.isEmpty()) return false;
            if (fp.equals(prefs(c).getString("pin", ""))) return true;
            try {
                X509Certificate[] chain = (X509Certificate[]) s.getPeerCertificates();
                systemTrust().checkServerTrusted(chain, "GENERIC");
                if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, s)) return false;
                prefs(c).edit().putString("pin", fp).apply();
                return true;
            } catch (Exception e) {
                return false;
            }
        }
    }

    private static X509TrustManager system;

    // System CAs plus the ones the user installed in the phone's settings.
    private static synchronized X509TrustManager systemTrust() throws Exception {
        if (system != null) return system;
        KeyStore ks = KeyStore.getInstance("AndroidCAStore");
        ks.load(null, null);
        TrustManagerFactory tmf =
                TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        for (TrustManager t : tmf.getTrustManagers()) {
            if (t instanceof X509TrustManager) system = (X509TrustManager) t;
        }
        return system;
    }

    private static SSLSocketFactory lenientFactory;

    private static synchronized SSLSocketFactory lenient() throws Exception {
        if (lenientFactory != null) return lenientFactory;
        TrustManager all = new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] c, String a) {
            }

            @Override public void checkServerTrusted(X509Certificate[] c, String a) {
            }

            @Override public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, new TrustManager[] {all}, null);
        lenientFactory = ctx.getSocketFactory();
        return lenientFactory;
    }

    // ---- helpers ----

    // The network whose subnet holds the player's address. Phones may make mobile data the
    // default network while Wi-Fi stays connected (e.g. when Wi-Fi has no internet), and then
    // LAN traffic would go out over mobile data and never arrive.
    private static android.net.Network lanNetwork(Context c, String host) {
        try {
            if (!host.matches("[0-9.]+")) return null;
            byte[] want = java.net.InetAddress.getByName(host).getAddress();
            android.net.ConnectivityManager cm = c.getSystemService(android.net.ConnectivityManager.class);
            for (android.net.Network n : cm.getAllNetworks()) {
                android.net.LinkProperties lp = cm.getLinkProperties(n);
                if (lp == null) continue;
                for (android.net.LinkAddress la : lp.getLinkAddresses()) {
                    byte[] have = la.getAddress().getAddress();
                    if (have.length == want.length && samePrefix(have, want, la.getPrefixLength())) return n;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static boolean samePrefix(byte[] a, byte[] b, int bits) {
        for (int i = 0; i < bits; i++) {
            int mask = 0x80 >> (i % 8);
            if ((a[i / 8] & mask) != (b[i / 8] & mask)) return false;
        }
        return true;
    }

    private static String fingerprint(SSLSession s) {
        try {
            return fingerprint(s.getPeerCertificates()[0]).replace("\n", ":");
        } catch (Exception e) {
            return "";
        }
    }

    static String fingerprint(Certificate cert) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(cert.getEncoded());
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < d.length; i++) {
                if (i > 0) b.append(i % 8 == 0 ? "\n" : ":");
                b.append(String.format(Locale.US, "%02X", d[i]));
            }
            return b.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static byte[] readAll(Context c, Uri uri) throws Exception {
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
            return o.toByteArray();
        }
    }
}
