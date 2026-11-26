package cn.kebiao.hnust;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * 本机状态：登录态、作息、设置。
 * 「记住密码」用 Android Keystore 里的 AES 密钥加密后存 SharedPreferences，
 * 密钥不出安全硬件，卸载应用即失效。
 */
class Store {

    private static final String FILE = "kebiao_state";
    private static final String KEY_ALIAS = "kebiao-pwd-key";
    private static final String KEY_PWD = "password_sealed";

    private final SharedPreferences sp;

    Store(Context ctx) {
        sp = ctx.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    // ---------------- 基本字段 ---------------- //
    String campus() {
        return sp.getString("campus", "main");
    }

    /**
     * 登录页里 FingerprintJS 生成的 visitorId。固定一个值，避免每次登录都被当成新设备，
     * 也避免风控把「设备频繁变化」当成异常。
     */
    String visitorId() {
        String value = sp.getString("visitorId", "");
        if (value == null || value.length() != 32) {
            java.security.SecureRandom rnd = new java.security.SecureRandom();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 16; i++) sb.append(String.format("%02x", rnd.nextInt(256)));
            value = sb.toString();
            sp.edit().putString("visitorId", value).apply();
        }
        return value;
    }

    void setCampus(String value) {
        sp.edit().putString("campus", value == null ? "main" : value).apply();
    }

    String username() {
        return sp.getString("username", "");
    }

    String token() {
        return sp.getString("token", "");
    }

    String kbjcmsid() {
        return sp.getString("kbjcmsid", "");
    }

    String termStart() {
        return sp.getString("term_start", "");
    }

    int totalWeeks() {
        return sp.getInt("total_weeks", 20);
    }

    void setTotalWeeks(int weeks) {
        sp.edit().putInt("total_weeks", Math.max(1, Math.min(30, weeks))).apply();
    }

    boolean demo() {
        return sp.getBoolean("demo", false);
    }

    boolean wakeAlarm() {
        return sp.getBoolean("wake_alarm", true);
    }

    boolean remindAlarm() {
        return sp.getBoolean("remind_alarm", true);
    }

    void setWakeAlarm(boolean on) {
        sp.edit().putBoolean("wake_alarm", on).apply();
    }

    void setRemindAlarm(boolean on) {
        sp.edit().putBoolean("remind_alarm", on).apply();
    }

    /** 已设进系统时钟的叫醒闹钟的触发点（"yyyy-MM-dd HH:mm"），空 = 没设过。 */
    String sysAlarmStamp() {
        return sp.getString("sys_alarm_stamp", "");
    }

    void setSysAlarmStamp(String stamp) {
        sp.edit().putString("sys_alarm_stamp", stamp == null ? "" : stamp).apply();
    }

    boolean remember() {
        return sp.getBoolean("remember", false);
    }

    boolean loggedIn() {
        return !token().isEmpty() && !demo();
    }

    JSONObject cookies() {
        try {
            return new JSONObject(sp.getString("cookies", "{}"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    List<String[]> sections() {
        List<String[]> rows = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(sp.getString("sections", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONArray pair = arr.optJSONArray(i);
                if (pair != null && pair.length() >= 2) {
                    rows.add(new String[]{pair.optString(0), pair.optString(1)});
                }
            }
        } catch (Exception ignored) {
        }
        if (rows.isEmpty()) {
            for (String[] row : HnustClient.DEFAULT_SECTIONS) rows.add(new String[]{row[0], row[1]});
        }
        return rows;
    }

    void saveSession(String username, String token, JSONObject cookies, String kbjcmsid,
                     List<String[]> sections) {
        SharedPreferences.Editor editor = sp.edit()
                .putString("username", username)
                .putString("token", token)
                .putString("cookies", cookies == null ? "{}" : cookies.toString())
                .putString("kbjcmsid", kbjcmsid == null ? "" : kbjcmsid)
                .putBoolean("demo", false);
        if (sections != null) {
            JSONArray arr = new JSONArray();
            for (String[] row : sections) {
                JSONArray pair = new JSONArray();
                pair.put(row[0]);
                pair.put(row[1]);
                arr.put(pair);
            }
            editor.putString("sections", arr.toString());
        }
        editor.apply();
    }

    void saveToken(String token, JSONObject cookies) {
        sp.edit().putString("token", token)
                .putString("cookies", cookies == null ? "{}" : cookies.toString()).apply();
    }

    void setToken(String token) {
        sp.edit().putString("token", token == null ? "" : token).apply();
    }

    void setKbjcmsid(String value) {
        sp.edit().putString("kbjcmsid", value == null ? "" : value).apply();
    }

    void setSections(List<String[]> rows) {
        JSONArray arr = new JSONArray();
        for (String[] row : rows) {
            JSONArray pair = new JSONArray();
            pair.put(row[0]);
            pair.put(row[1]);
            arr.put(pair);
        }
        sp.edit().putString("sections", arr.toString()).apply();
    }

    void setTermStart(String value) {
        sp.edit().putString("term_start", value == null ? "" : value).apply();
    }

    void setDemo(boolean on) {
        sp.edit().putBoolean("demo", on).apply();
    }

    void clearSession(boolean keepCredentials) {
        SharedPreferences.Editor editor = sp.edit()
                .putString("token", "")
                .putString("cookies", "{}")
                .putString("kbjcmsid", "")
                .putString("username", "")
                .putString("term_start", "")
                .putBoolean("demo", false);
        if (!keepCredentials) {
            editor.putBoolean("remember", false).remove(KEY_PWD);
        }
        editor.apply();
        clearWeekCaches();
    }

    // ---------------- 课表本地缓存（先显示缓存，再静默校对） ---------------- //
    String weekCache(int week) {
        return sp.getString("weekcache_" + week, "");
    }

    void putWeekCache(int week, String json) {
        sp.edit().putString("weekcache_" + week, json).apply();
    }

    int lastCachedWeek() {
        return sp.getInt("last_cached_week", 0);
    }

    void setLastCachedWeek(int week) {
        sp.edit().putInt("last_cached_week", week).apply();
    }

    void clearWeekCaches() {
        SharedPreferences.Editor editor = sp.edit();
        for (String key : sp.getAll().keySet()) {
            if (key.startsWith("weekcache_")) editor.remove(key);
        }
        editor.remove("last_cached_week").apply();
    }

    // ---------------- 密码 ---------------- //
    void setPassword(String password, boolean remember) {
        SharedPreferences.Editor editor = sp.edit().putBoolean("remember", remember);
        if (remember && password != null && !password.isEmpty()) {
            try {
                editor.putString(KEY_PWD, seal(password));
            } catch (Exception e) {
                editor.putBoolean("remember", false).remove(KEY_PWD);
            }
        } else {
            editor.remove(KEY_PWD);
        }
        editor.apply();
    }

    String password() {
        if (!remember()) return "";
        String sealed = sp.getString(KEY_PWD, "");
        if (sealed.isEmpty()) return "";
        try {
            return unseal(sealed);
        } catch (Exception e) {
            return "";
        }
    }

    boolean hasPassword() {
        return remember() && !sp.getString(KEY_PWD, "").isEmpty();
    }

    private SecretKey secretKey() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(KEY_ALIAS)) {
            return (SecretKey) ks.getKey(KEY_ALIAS, null);
        }
        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        return generator.generateKey();
    }

    private String seal(String text) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, secretKey());
        byte[] iv = cipher.getIV();
        byte[] body = cipher.doFinal(text.getBytes("UTF-8"));
        byte[] out = new byte[iv.length + body.length];
        System.arraycopy(iv, 0, out, 0, iv.length);
        System.arraycopy(body, 0, out, iv.length, body.length);
        return Base64.encodeToString(out, Base64.NO_WRAP);
    }

    private String unseal(String sealed) throws Exception {
        byte[] raw = Base64.decode(sealed, Base64.NO_WRAP);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), new GCMParameterSpec(128, raw, 0, 12));
        return new String(cipher.doFinal(raw, 12, raw.length - 12), "UTF-8");
    }
}
