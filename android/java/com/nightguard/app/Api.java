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

    /** 把常见网络异常翻译成可执行的排查提示，直接显示在设置页。 */
    public static String describe(Throwable t) {
        if (t == null) return "未知错误";
        if (t instanceof java.net.UnknownHostException) {
            return "域名/IP 解析失败：检查服务器地址和手机网络";
        }
        if (t instanceof java.net.ConnectException) {
            return "连不上服务器：端口没映射、防火墙拦截，或手机不在同一局域网";
        }
        if (t instanceof java.net.SocketTimeoutException) {
            return "连接超时：服务器没响应，检查端口映射 / nginx 反代";
        }
        if (t instanceof javax.net.ssl.SSLException) {
            return "TLS 证书被拒绝：自签证书请勾「信任自签证书」，或改用 https";
        }
        String m = t.getMessage();
        if (m == null || m.isEmpty()) m = t.getClass().getSimpleName();
        if (m.contains("HTTP 401")) return "口令不对（HTTP 401）：检查 api_token";
        if (m.contains("HTTP 404")) {
            return "路径不对（HTTP 404）：服务器地址不要带 /health，nginx 子路径要匹配";
        }
        return m;
    }
}
