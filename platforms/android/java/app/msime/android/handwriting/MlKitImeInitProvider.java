package app.msime.android;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import androidx.work.Configuration;
import androidx.work.WorkManager;
import com.google.mlkit.common.MlKit;

/** Initializes packaged handwriting dependencies inside the isolated IME process. */
public final class MlKitImeInitProvider extends ContentProvider {
    @Override public boolean onCreate() {
        Context context = getContext();
        if (context == null) return false;
        // 这个 provider 在 `:ime` 进程启动时第一个跑：这里抛出的任何异常都会让输入法进程起不来，
        // 表现就是「输入法调不出来、换别的输入法再切回来才好」。初始化失败只影响手写识别，
        // 所以一律吞掉并记一行，绝不让它连坐键盘本身。
        try {
            WorkManager.getInstance(context);
        } catch (IllegalStateException uninitialized) {
            try {
                WorkManager.initialize(context, new Configuration.Builder().build());
            } catch (Throwable ignored) {
                // 后台任务不可用不影响键盘。
            }
        } catch (Throwable ignored) {
            // 同上。
        }
        try {
            MlKit.initialize(context);
        } catch (Throwable unavailable) {
            // 没有 Google Play 服务或 ML Kit 初始化失败时，手写会自己回到「需要下载」状态。
            android.util.Log.w("msime", "MlKit initialize failed in :ime", unavailable);
        }
        return true;
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) {
        throw new UnsupportedOperationException();
    }

    @Override public String getType(Uri uri) { return null; }

    @Override public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

    @Override public int update(Uri uri, ContentValues values, String selection,
                                String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }
}
