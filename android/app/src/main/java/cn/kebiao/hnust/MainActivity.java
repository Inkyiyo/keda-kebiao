package cn.kebiao.hnust;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Window;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 单页 App：界面仍是那套 HTML，登录与课表抓取换成了本机 Java 实现。
 *
 * 网页里的 fetch("/api/xxx") 由 assets/native-shim.js 拦下，转成
 * NativeApi.request(path, body, id)。这里在后台线程执行，完成后
 * 再 evaluateJavascript 回推结果，避免网络请求卡住页面渲染。
 */
public class MainActivity extends Activity {

    private WebView web;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private long lastBackPressed = 0;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Diag.init(getFilesDir(), deviceInfo());
        Diag.step("App 启动");

        // 上课闹钟/提醒靠通知送达，Android 13+ 需要运行时授权
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 700);
        }

        Window window = getWindow();
        window.setStatusBarColor(Color.parseColor("#0F5C4C"));
        window.setNavigationBarColor(Color.WHITE);

        web = new WebView(this);
        setContentView(web);

        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setTextZoom(100);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settings.setSafeBrowsingEnabled(true);
        }

        web.addJavascriptInterface(new Bridge(), "NativeApi");
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String scheme = uri.getScheme() == null ? "" : uri.getScheme();
                if (scheme.startsWith("http") || scheme.equals("mailto") || scheme.equals("tel")) {
                    openOutside(uri);
                    return true;
                }
                return false;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url.startsWith("http")) {
                    openOutside(Uri.parse(url));
                    return true;
                }
                return false;
            }
        });

        web.loadUrl("file:///android_asset/index.html");
    }

    @Override
    protected void onResume() {
        super.onResume();
        ClassAlarms.appForeground = true;
        // 回到前台时把闹钟/系统时钟叫醒/小组件刷新点都对齐一次
        ClassAlarms.reschedule(this);
    }

    @Override
    protected void onPause() {
        ClassAlarms.appForeground = false;
        super.onPause();
    }

    private void openOutside(Uri uri) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (Exception e) {
            toast("没有可以打开该链接的应用");
        }
    }

    /** 头部信息：出问题时第一眼看的就是这些。 */
    private String deviceInfo() {
        String version = "";
        int code = 0;
        try {
            android.content.pm.PackageInfo info =
                    getPackageManager().getPackageInfo(getPackageName(), 0);
            version = info.versionName;
            code = info.versionCode;
        } catch (Exception ignored) {
        }
        String campus = "main";
        try {
            campus = new Store(this).campus();
        } catch (Exception ignored) {
        }
        return "应用 " + version + "（versionCode " + code + "）\n"
                + "系统 Android " + Build.VERSION.RELEASE + "（API " + Build.VERSION.SDK_INT + "）\n"
                + "机型 " + Build.MANUFACTURER + " " + Build.MODEL + "\n"
                + "校区 " + campus;
    }

    /**
     * 导出诊断日志：写文件 → 复制到剪贴板 → 调起系统分享。
     * 三道都留着，任何一道失败用户都还能把内容粘出来。
     */
    private void exportLog() {
        String text = Diag.dump();
        Diag.step("导出日志（" + text.length() + " 字符）");

        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("科大课表诊断日志", text));
        } catch (Exception ignored) {
        }

        File out = new File(getCacheDir(), DiagProvider.FILE_NAME);
        boolean saved = false;
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(out);
            fos.write(text.getBytes(StandardCharsets.UTF_8));
            saved = true;
        } catch (Exception ignored) {
        } finally {
            if (fos != null) try {
                fos.close();
            } catch (Exception ignored) {
            }
        }

        if (!saved) {
            toast("日志已复制到剪贴板，可直接粘贴发送");
            return;
        }

        try {
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_SUBJECT, "科大课表诊断日志");
            send.putExtra(Intent.EXTRA_TEXT, text);
            send.putExtra(Intent.EXTRA_STREAM,
                    Uri.parse("content://" + DiagProvider.AUTHORITY + "/" + DiagProvider.FILE_NAME));
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(send, "导出诊断日志"));
        } catch (Exception e) {
            toast("日志已复制到剪贴板，可直接粘贴发送");
        }
    }

    private void toast(final String text) {
        runOnUiThread(() -> Toast.makeText(MainActivity.this, text, Toast.LENGTH_SHORT).show());
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastBackPressed < 2000) {
            super.onBackPressed();
            return;
        }
        lastBackPressed = now;
        toast("再按一次返回键退出");
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        if (web != null) {
            web.removeJavascriptInterface("NativeApi");
            web.destroy();
        }
        super.onDestroy();
    }

    /** 暴露给网页的接口，方法都在 JS 线程之外执行。 */
    private class Bridge {

        @JavascriptInterface
        public void request(final String path, final String body, final String callbackId) {
            worker.execute(() -> {
                String result;
                try {
                    result = Api.get(MainActivity.this).handle(path, body);
                } catch (Throwable t) {
                    String message = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
                    result = "{\"ok\":false,\"error\":" + JSONObject.quote("请求出错：" + message) + ",\"kind\":\"error\"}";
                }
                final String script = "window.__nativeResolve(" + JSONObject.quote(callbackId)
                        + "," + JSONObject.quote(result) + ");";
                runOnUiThread(() -> {
                    if (web != null) web.evaluateJavascript(script, null);
                });
            });
        }

        @JavascriptInterface
        public void exportLog() {
            runOnUiThread(MainActivity.this::exportLog);
        }

        @JavascriptInterface
        public String platform() {
            return "android";
        }

        @JavascriptInterface
        public String version() {
            try {
                return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            } catch (Exception e) {
                return "";
            }
        }

        @JavascriptInterface
        public void toast(String text) {
            MainActivity.this.toast(text);
        }

        @JavascriptInterface
        public void setStatusBar(final String color) {
            runOnUiThread(() -> {
                try {
                    getWindow().setStatusBarColor(Color.parseColor(color));
                } catch (Exception ignored) {
                }
            });
        }
    }
}
