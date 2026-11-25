package cn.kebiao.hnust;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Deque;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 诊断日志。
 *
 * 目的只有一个：出问题时用户点一下就能把「到底哪一步失败」发出来。
 * 因此记的是流程与状态码，不记内容——密码、token、ticket、execution 一律打码。
 * 纯 Java 实现，不依赖 Android，方便在 JVM 上直接验证脱敏是否彻底。
 */
final class Diag {

    /** 内存里最多留这么多行，防止长时间运行把内存吃掉。 */
    private static final int MAX_LINES = 600;
    /** 落盘文件超过这个大小就截掉前面一半。 */
    private static final long MAX_BYTES = 300 * 1024;

    private static final Deque<String> BUF = new ArrayDeque<>();
    private static final SimpleDateFormat FMT =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.CHINA);
    private static final Object LOCK = new Object();

    private static File file;
    private static String header = "";

    private static final Pattern SECRET = Pattern.compile(
            "(?i)(password|passwd|pwd|token|ticket|execution|idtoken|jsessionid|sessionid|"
                    + "captcha)=([^&\\s\"',;]+)");
    private static final Pattern BEARER = Pattern.compile("(?i)bearer\\s+[A-Za-z0-9._\\-]{8,}");
    private static final Pattern JSON_SECRET = Pattern.compile(
            "(?i)\"(password|token|idToken|execution)\"\\s*:\\s*\"[^\"]*\"");
    /** 学号：10 位以上纯数字，只留前 4 位。 */
    private static final Pattern STU_NO = Pattern.compile("\\b(\\d{4})\\d{4,}\\b");

    private Diag() {
    }

    static void init(File dir, String info) {
        synchronized (LOCK) {
            header = info == null ? "" : info;
            if (dir != null) {
                if (!dir.exists()) dir.mkdirs();
                file = new File(dir, "diag.log");
            }
        }
    }

    static void step(String msg) {
        write("S", msg);
    }

    static void info(String msg) {
        write("I", msg);
    }

    static void warn(String msg) {
        write("W", msg);
    }

    static void error(String msg) {
        write("E", msg);
    }

    /** 一次 HTTP 往返：方法、打码后的 URL、状态码、响应字节数、耗时。 */
    static void http(String method, String url, int code, int bytes, long ms) {
        write("H", method + " " + mask(url) + " -> " + code + " (" + bytes + "B, " + ms + "ms)");
    }

    /** 网络异常单独记，带异常类型，比只有 message 好定位。 */
    static void fail(String method, String url, Throwable t) {
        write("E", method + " " + mask(url) + " !! "
                + (t == null ? "unknown" : t.getClass().getSimpleName() + ": " + t.getMessage()));
    }

    static void write(String tag, String msg) {
        String line;
        synchronized (LOCK) {
            line = "[" + FMT.format(new Date()) + "] " + tag + " " + mask(msg);
            if (BUF.size() >= MAX_LINES) BUF.removeFirst();
            BUF.addLast(line);
            append(line);
        }
    }

    /** 导出用：头部信息 + 全部日志行 + 结尾统计。 */
    static String dump() {
        StringBuilder sb = new StringBuilder();
        synchronized (LOCK) {
            sb.append("科大课表 · 诊断日志\n");
            if (!header.isEmpty()) sb.append(header).append('\n');
            sb.append("生成时间：").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
                    .format(new Date())).append('\n');
            sb.append("说明：password / token / ticket / execution / 学号后 6 位均已打码\n");
            sb.append("------------------------------------------------------------\n");
            // 优先用磁盘上的完整日志：它跨进程启动仍在，比内存缓冲更全
            String disk = readFile();
            if (disk != null && !disk.isEmpty()) {
                sb.append(disk.endsWith("\n") ? disk : disk + "\n");
            } else if (BUF.isEmpty()) {
                sb.append("(暂无日志)\n");
            } else {
                for (String line : BUF) sb.append(line).append('\n');
            }
            sb.append("------------------------------------------------------------\n");
            sb.append("共 ").append(BUF.size()).append(" 行\n");
            sb.append("\n图例：S=流程  H=HTTP  I=信息  W=警告  E=错误\n");
        }
        return sb.toString();
    }

    /** 打码：URL 参数、JSON 字段、Bearer、学号。 */
    static String mask(String text) {
        if (text == null || text.isEmpty()) return "";
        // 顺序很重要：先吃掉 Bearer 后面那一整段，否则 Authorization= 先被打码，
        // 剩下的 JWT 就没人管了。
        String out = BEARER.matcher(text).replaceAll("Bearer ***");
        out = SECRET.matcher(out).replaceAll("$1=***");
        out = JSON_SECRET.matcher(out).replaceAll("\"$1\":\"***\"");
        out = STU_NO.matcher(out).replaceAll("$1******");
        return out;
    }

    // ------------------------------------------------------------------ //

    private static void append(String line) {
        if (file == null) return;
        FileOutputStream out = null;
        try {
            rotateIfNeeded();
            out = new FileOutputStream(file, true);
            out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // 日志写失败不能影响主流程
        } finally {
            if (out != null) try {
                out.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** 文件太大时只保留后半段，避免占满存储。 */
    private static void rotateIfNeeded() {
        if (file == null || !file.exists() || file.length() < MAX_BYTES) return;
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(file, "rw");
            long keep = MAX_BYTES / 2;
            long from = Math.max(0, raf.length() - keep);
            byte[] tail = new byte[(int) (raf.length() - from)];
            raf.seek(from);
            raf.readFully(tail);
            raf.setLength(0);
            raf.seek(0);
            raf.write("...(较早的日志已截断)...\n".getBytes(StandardCharsets.UTF_8));
            raf.write(tail);
        } catch (IOException ignored) {
        } finally {
            if (raf != null) try {
                raf.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** 读回磁盘上的完整日志（比内存缓冲更全）。 */
    static String readFile() {
        if (file == null || !file.exists()) return null;
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(file, "r");
            byte[] buf = new byte[(int) Math.min(raf.length(), MAX_BYTES)];
            raf.seek(Math.max(0, raf.length() - buf.length));
            raf.readFully(buf);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            bos.write(buf);
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        } finally {
            if (raf != null) try {
                raf.close();
            } catch (IOException ignored) {
            }
        }
    }
}
