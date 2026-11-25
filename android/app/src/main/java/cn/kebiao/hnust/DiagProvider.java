package cn.kebiao.hnust;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * 把导出的日志文件以 content:// 形式暴露给分享面板。
 * 用自带的 Provider 而不是 FileProvider，是因为这个工程不引 androidx，
 * 而 API 24+ 又不允许直接把 file:// 放进 Intent（会抛 FileUriExposedException）。
 */
public class DiagProvider extends ContentProvider {

    public static final String AUTHORITY = "cn.kebiao.hnust.diag";
    public static final String FILE_NAME = "keda-kebiao-diag.txt";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File file = new File(getContext().getCacheDir(), FILE_NAME);
        if (!file.exists()) throw new FileNotFoundException(FILE_NAME);
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        return "text/plain";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
