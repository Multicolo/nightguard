package com.nightguard.app;

import android.content.Context;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** 极简 HTTP 客户端（只用 framework，不引第三方库）。 */
public final class Api {
    private Api() {}

    public static JSONObject post(Context c, String path, JSONObject body) throws Exception {
        HttpURLConnection conn = open(c, path, "POST");
        byte[] data = body.toString().getBytes("UTF-8");
        conn.setFixedLengthStreamingMode(data.length);
        OutputStream os = conn.getOutputStream();
        os.write(data);
        os.close();
        return readJson(conn);
    }

    public static JSONObject getJson(Context c, String path) throws Exception {
        return readJson(open(c, path, "GET"));
    }

    public static byte[] getBytes(Context c, String path) throws Exception {
        HttpURLConnection conn = open(c, path, "GET");
        int code = conn.getResponseCode();
        InputStream is = code < 400 ? conn.getInputStream() : conn.getErrorStream();
        if (is == null) return null;
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        is.close();
        conn.disconnect();
        return code < 400 ? bos.toByteArray() : null;
    }

    private static HttpURLConnection open(Context c, String path, String method) throws Exception {
        if (!path.startsWith("http")) path = Cfg.server(c) + path;
        URL u = new URL(path);
        HttpURLConnection conn = (HttpURLConnection) u.openConnection();
        if (Cfg.insecureTls(c) && conn instanceof javax.net.ssl.HttpsURLConnection) {
            javax.net.ssl.HttpsURLConnection https = (javax.net.ssl.HttpsURLConnection) conn;
            javax.net.ssl.SSLContext ctx = App.trustAllContext();
            if (ctx != null) {
                https.setSSLSocketFactory(ctx.getSocketFactory());
                https.setHostnameVerifier(App.ANY_HOST);
            }
        }
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(90000);
        conn.setRequestMethod(method);
        conn.setRequestProperty("X-Night-Token", Cfg.token(c));
        conn.setRequestProperty("Accept", "application/json");
        if ("POST".equals(method)) {
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        }
        return conn;
    }

    private static JSONObject readJson(HttpURLConnection conn) throws Exception {
        int code = conn.getResponseCode();
        InputStream is = code < 400 ? conn.getInputStream() : conn.getErrorStream();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        if (is != null) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            is.close();
        }
        conn.disconnect();
        String s = new String(bos.toByteArray(), "UTF-8");
        if (code >= 400) throw new Exception("HTTP " + code + " " + s);
        if (s.trim().isEmpty()) return new JSONObject();
        return new JSONObject(s);
    }
}
