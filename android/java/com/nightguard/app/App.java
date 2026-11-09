package com.nightguard.app;

import android.app.Application;

import java.security.SecureRandom;
import java.security.cert.X509Certificate;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * 只为「自建服务器 + 自签证书」准备的可选项：默认走系统正常 TLS 校验，
 * 只有在设置页勾选「信任自签证书」后，请求才会放行域名不匹配/自签的证书。
 */
public class App extends Application {
    private static SSLContext trustAll;

    @Override public void onCreate() {
        super.onCreate();
    }

    /** 勾了「信任自签证书」才用它；否则返回 null，走系统默认校验。 */
    public static SSLContext trustAllContext() {
        if (trustAll == null) {
            try {
                TrustManager[] tm = new TrustManager[]{new X509TrustManager() {
                    @Override public void checkClientTrusted(X509Certificate[] c, String a) {}
                    @Override public void checkServerTrusted(X509Certificate[] c, String a) {}
                    @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                }};
                SSLContext ctx = SSLContext.getInstance("TLS");
                ctx.init(null, tm, new SecureRandom());
                trustAll = ctx;
            } catch (Throwable ignored) {
                trustAll = null;
            }
        }
        return trustAll;
    }

    public static final HostnameVerifier ANY_HOST = new HostnameVerifier() {
        @Override public boolean verify(String host, SSLSession session) { return true; }
    };
}
