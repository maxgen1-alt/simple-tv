package com.maxgendler.simpletv;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Tiny download helper. Call from a background thread. */
final class Net {
    private Net() {}

    static String downloadText(String url) throws Exception {
        return new String(download(url), "UTF-8");
    }

    static byte[] download(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", "SimpleTV (Android TV)");
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[16384];
            int n;
            while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
            return buf.toByteArray();
        } finally {
            c.disconnect();
        }
    }
}
