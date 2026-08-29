package com.better.heybox;
import android.util.Log;
import org.json.JSONException;
import org.json.JSONObject;

public final class ConfigBackup {

    public static final String FORMAT = "betterheybox-config";
    public static final int VERSION = 1;

    private static final String KEY_FORMAT = "format";
    private static final String KEY_VERSION = "version";
    private static final String KEY_EXPORTED_AT = "exportedAt";
    private static final String KEY_BOOLEANS = "booleans";
    private static final String KEY_STRINGS = "strings";
    private static final String[] BOOLEAN_KEYS = {
            App.KEY_OPEN_SCREEN,
            App.KEY_FEED_AD,
            App.KEY_BUBBLE_AD,
            App.KEY_CORNER_AD,
            App.KEY_PROMOTE_AD,
            App.KEY_HIDE_TAB_HOME,
            App.KEY_HIDE_TAB_HOT,
            App.KEY_HIDE_TAB_GAME,
            App.KEY_HIDE_ADD,
            App.KEY_COPY_POST,
            App.KEY_CUSTOM_TEXT_SELECT,
            App.KEY_SYSTEM_SHARE,
            App.KEY_VIDEO_DOWNLOAD,
            App.KEY_VIDEO_TO_MP4,
            App.KEY_PURIFY_SHARE_LINK,
            App.KEY_BLOCK_UPDATE,
            App.KEY_DAILY_TASK_ENABLED,
            App.KEY_FAKE_NOTIFICATION,
            App.KEY_LOG,
    };
    private static final String[] STRING_KEYS = {
            App.KEY_DAILY_TASK_PICTURE,
            App.KEY_DAILY_TASK_NORMAL,
            App.KEY_DAILY_TASK_CHANNEL,
            App.KEY_SHARE_CHANNEL,
    };
    private static final String[] RESTART_KEYS = {
            App.KEY_HIDE_TAB_HOME,
            App.KEY_HIDE_TAB_HOT,
            App.KEY_HIDE_TAB_GAME,
            App.KEY_HIDE_ADD,
    };

    private ConfigBackup() {
    }
    public interface BooleanReader {
        boolean get(String key, boolean def);
    }
    public interface StringReader {
        String get(String key, String def);
    }
    public interface BooleanWriter {
        void write(String key, boolean value);
    }
    public interface StringWriter {
        void write(String key, String value);
    }
    public static String buildJson(BooleanReader booleanReader, StringReader stringReader) {
        try {
            JSONObject booleans = new JSONObject();
            for (String key : BOOLEAN_KEYS) {
                booleans.put(key, booleanReader.get(key, defaultFor(key)));
            }
            JSONObject strings = new JSONObject();
            for (String key : STRING_KEYS) {
                String value = stringReader.get(key, "");
                strings.put(key, value == null ? "" : value);
            }
            JSONObject root = new JSONObject();
            root.put(KEY_FORMAT, FORMAT);
            root.put(KEY_VERSION, VERSION);
            root.put(KEY_EXPORTED_AT, System.currentTimeMillis());
            root.put(KEY_BOOLEANS, booleans);
            root.put(KEY_STRINGS, strings);
            return root.toString(2);
        } catch (JSONException e) {
            Logs.e(TAG, "导出配置失败: " + e);
            return null;
        }
    }
    public static ApplyResult applyJson(String json, BooleanWriter booleanWriter, StringWriter stringWriter) {
        try {
            JSONObject root = new JSONObject(json);
            String format = root.optString(KEY_FORMAT);
            if (!FORMAT.equals(format)) {
                Logs.w(TAG, "导入拒绝: format 不匹配, actual=" + format);
                return null;
            }
            int count = 0;
            boolean restartRequired = false;
            JSONObject booleans = root.optJSONObject(KEY_BOOLEANS);
            if (booleans != null) {
                for (String key : BOOLEAN_KEYS) {
                    if (booleans.has(key)) {
                        booleanWriter.write(key, booleans.optBoolean(key));
                        count++;
                        if (isRestartKey(key)) {
                            restartRequired = true;
                        }
                    }
                }
            }
            JSONObject strings = root.optJSONObject(KEY_STRINGS);
            if (strings != null) {
                for (String key : STRING_KEYS) {
                    if (strings.has(key)) {
                        stringWriter.write(key, strings.optString(key));
                        count++;
                    }
                }
            }
            Logs.i(TAG, "导入完成: applied=" + count + ", restartRequired=" + restartRequired);
            return new ApplyResult(count, restartRequired);
        } catch (JSONException e) {
            Logs.e(TAG, "导入配置解析失败: " + e);
            return null;
        }
    }

    private static boolean isRestartKey(String key) {
        for (String k : RESTART_KEYS) {
            if (k.equals(key)) {
                return true;
            }
        }
        return false;
    }
    private static boolean defaultFor(String key) {
        if (App.KEY_OPEN_SCREEN.equals(key)
                || App.KEY_FEED_AD.equals(key)
                || App.KEY_BUBBLE_AD.equals(key)
                || App.KEY_CORNER_AD.equals(key)
                || App.KEY_PROMOTE_AD.equals(key)
                || App.KEY_COPY_POST.equals(key)
                || App.KEY_SYSTEM_SHARE.equals(key)) {
            return true;
        }
        return false;
    }
    public static final class ApplyResult {
        public final int applied;
        public final boolean restartRequired;

        ApplyResult(int applied, boolean restartRequired) {
            this.applied = applied;
            this.restartRequired = restartRequired;
        }
    }

    private static final String TAG = "BetterHeybox";
}
