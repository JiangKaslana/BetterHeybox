package com.better.heybox.hooks;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;

import com.better.heybox.App;
import com.better.heybox.MainModule;
import com.better.heybox.LogRecorder;

/**
 * 图片系统分享：在小黑盒图片长按菜单末尾追加「系统分享」，下载图片后唤起系统分享界面。
 */
public final class ImageShareHook {

    private final MainModule module;

    public ImageShareHook(MainModule module) {
        this.module = module;
    }
    public void install(ClassLoader cl) {
        hookImageLongPressMenu(cl);
    }

    private static final int TARGET_FORWARD_ICON = 0x7f0800de;
    private volatile Object pendingImageShareMediaData;
    private void hookImageLongPressMenu(ClassLoader cl) {
        try {
            Class<?> customizer = Class.forName(
                    "com.max.xiaoheihe.utils.imageviewer.ui.BaseResUICustomizer", false, cl);
            Class<?> mediaData = Class.forName(
                    "com.max.xiaoheihe.utils.imageviewer.MediaData", false, cl);
            Method getLocalHandlers = customizer.getDeclaredMethod("r", customizer, mediaData);
            module.hook(getLocalHandlers).intercept(chain -> {
                Object result = chain.proceed();
                if (!(result instanceof List)) {
                    module.logd(Log.WARN, module.TAG, "图片长按处理器返回值不是 List: "
                            + (result == null ? "null" : result.getClass().getName()));
                    return result;
                }
                Object currentMediaData = chain.getArg(1);
                appendSystemShareHandler((List<?>) result, currentMediaData, cl);
                return result;
            });

            Method openShare = customizer.getDeclaredMethod("h0", mediaData);
            module.hook(openShare).intercept(chain -> {
                pendingImageShareMediaData = chain.getArg(0);
                module.logd(Log.INFO, module.TAG, "图片长按分享入口命中: mediaData="
                        + (pendingImageShareMediaData == null ? "null"
                        : pendingImageShareMediaData.getClass().getName()));
                return chain.proceed();
            });

            Class<?> dialogBuilder = Class.forName(
                    "com.max.xiaoheihe.accelworld.HBShareDialog$a", false, cl);
            Method addHandlers = dialogBuilder.getDeclaredMethod("c", List.class);
            module.hook(addHandlers).intercept(chain -> {
                Object handlers = chain.getArg(0);
                if (handlers instanceof List) {
                    module.logd(Log.INFO, module.TAG, "HBShareDialog 处理器列表命中: count="
                            + ((List<?>) handlers).size());
                    appendSystemShareHandler((List<?>) handlers,
                            pendingImageShareMediaData, cl);
                }
                return chain.proceed();
            });

            Class<?> shareDialog = Class.forName(
                    "com.max.xiaoheihe.accelworld.HBShareDialog", false, cl);
            Method showDialog = shareDialog.getDeclaredMethod("g");
            module.hook(showDialog).intercept(chain -> {
                Object dialog = chain.getThisObject();
                Object actions = readField(dialog, "f83135h");
                if (actions instanceof List) {
                    appendSystemShareAction((List<?>) actions, cl);
                }
                return chain.proceed();
            });

            Class<?> shareViewManager = Class.forName(
                    "com.max.common.common.share.ShareViewManager", false, cl);
            Class<?> forwardModel = Class.forName(
                    "com.max.data.model.share.IForwardModel", false, cl);
            Method buildForwardActions = shareViewManager.getDeclaredMethod(
                    "m", Context.class, forwardModel, List.class);
            module.hook(buildForwardActions).intercept(chain -> {
                Object result = chain.proceed();
                Object actions = chain.getArg(2);
                if (actions instanceof List) {
                    appendSystemShareAction((List<?>) actions, cl);
                }
                return result;
            });
            module.logd(Log.INFO, module.TAG, "✔ 图片长按真实分享面板 Hook 已安装: BaseResUICustomizer.r");
        } catch (Throwable t) {
            module.logd(Log.ERROR, module.TAG, "✘ 图片长按真实分享面板 Hook 失败", t);
        }
    }

    private void appendSystemShareAction(List<?> actions, ClassLoader cl) {
        try {
            if (!module.isEnabled(App.KEY_SYSTEM_SHARE, true)) {
                return;
            }
            for (Object actionObject : actions) {
                if (actionObject == null) {
                    continue;
                }
                Method getAction = actionObject.getClass().getMethod("getAction");
                Object action = getAction.invoke(actionObject);
                Method getActionTag = action.getClass().getMethod("getActionTag");
                if ("SystemShare".equals(String.valueOf(getActionTag.invoke(action)))) {
                    return;
                }
            }

            Class<?> actionObjClass = Class.forName(
                    "com.max.data.bean.share.ActionObj", false, cl);
            Class<?> actionClass = Class.forName(
                    "com.max.data.model.share.IAction", false, cl);
            Class<?> customActionClass = Class.forName(
                    "com.max.data.model.share.IAction$CustomAction", false, cl);
            Object customAction = customActionClass.getConstructor(String.class)
                    .newInstance("SystemShare");

            Object actionObject = actionObjClass.getConstructor(
                            String.class, Integer.class, String.class, String.class,
                            String.class, String.class, actionClass)
                    .newInstance("系统分享", TARGET_FORWARD_ICON, null, null, null, null, customAction);
            @SuppressWarnings("unchecked")
            List<Object> mutableActions = (List<Object>) actions;
            mutableActions.add(actionObject);
            module.logd(Log.INFO, module.TAG, "图片长按菜单动作已追加系统分享: count="
                    + mutableActions.size());
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "追加图片系统分享动作失败", t);
        }
    }

    private void appendSystemShareHandler(List<?> handlers, Object mediaData, ClassLoader cl) {
        try {
            if (!module.isEnabled(App.KEY_SYSTEM_SHARE, true)) {
                return;
            }
            for (Object handler : handlers) {
                if (handler == null) {
                    continue;
                }
                Method getTarget = handler.getClass().getMethod("getTarget");
                if ("SystemShare".equals(String.valueOf(getTarget.invoke(handler)))) {
                    return;
                }
            }

            Class<?> localHandler = Class.forName(
                    "com.max.common.common.share.local.c", false, cl);
            Class<?> callbackType = Class.forName("un.a", false, cl);
            InvocationHandler callback = (proxy, method, args) -> {
                if ("invoke".equals(method.getName())) {
                    module.logd(Log.INFO, module.TAG, "图片系统分享处理器已命中");
                    shareImageWithSystemChooser(mediaData);
                }
                if (method.getReturnType() == Void.TYPE) {
                    return null;
                }
                return readKotlinUnit(cl);
            };
            Object callbackProxy = Proxy.newProxyInstance(
                    cl, new Class<?>[]{callbackType}, callback);
            Object action = localHandler.getConstructor(String.class, callbackType)
                    .newInstance("SystemShare", callbackProxy);
            @SuppressWarnings("unchecked")
            List<Object> mutableHandlers = (List<Object>) handlers;
            mutableHandlers.add(action);
            module.logd(Log.INFO, module.TAG, "图片长按处理器已追加系统分享: count=" + mutableHandlers.size());
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "追加图片系统分享处理器失败", t);
        }
    }

    private Object readKotlinUnit(ClassLoader cl) {
        try {
            Class<?> unit = Class.forName("kotlin.b2", false, cl);
            Field instance = unit.getDeclaredField("f140421a");
            instance.setAccessible(true);
            return instance.get(null);
        } catch (Throwable t) {
            return null;
        }
    }

    private Object readField(Object target, String name) {
        if (target == null) {
            return null;
        }
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (Throwable ignored) {
            return null;
        }
    }
    private boolean shareImageWithSystemChooser(Object mediaData) {
        if (mediaData == null) {
            return false;
        }
        try {
            Method urlMethod = mediaData.getClass().getMethod("U");
            Method contextMethod = mediaData.getClass().getMethod("n");
            String imageUrl = String.valueOf(urlMethod.invoke(mediaData));
            Object contextObject = contextMethod.invoke(mediaData);
            if (!(contextObject instanceof Context) || imageUrl.length() == 0
                    || "null".equals(imageUrl)) {
                module.logd(Log.WARN, module.TAG, "图片分享跳过: MediaData 缺少 URL 或 Context");
                return false;
            }
            Context context = (Context) contextObject;
            LogRecorder.setContext(context);
            module.logd(Log.INFO, module.TAG, "图片分享开始下载: url=" + imageUrl);
            Thread worker = new Thread(() -> {
                File output = null;
                try {
                    output = downloadImage(context, imageUrl);
                    Uri uri = getTargetFileUri(context, output);
                    if (uri == null) {
                        throw new IllegalStateException("目标 FileProvider 返回 null");
                    }
                    File finalOutput = output;
                    context.getMainExecutor().execute(() -> {
                        try {
                            Intent share = new Intent(Intent.ACTION_SEND)
                                    .setType("image/*")
                                    .putExtra(Intent.EXTRA_STREAM, uri)
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            Intent chooser = Intent.createChooser(share, "分享图片");
                            if (!(context instanceof Activity)) {
                                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            }
                            context.startActivity(chooser);
                            module.logd(Log.INFO, module.TAG, "图片分享 chooser 已唤起: uri=" + uri);
                        } catch (Throwable t) {
                            module.logd(Log.ERROR, module.TAG, "图片分享 chooser 启动失败", t);
                            if (finalOutput != null) {
                                finalOutput.delete();
                            }
                        }
                    });
                } catch (Throwable t) {
                    module.logd(Log.WARN, module.TAG, "图片分享准备失败，回退原分享", t);
                    if (output != null) {
                        output.delete();
                    }
                    context.getMainExecutor().execute(() -> {
                        Toast.makeText(context, "图片暂时无法分享", Toast.LENGTH_SHORT).show();
                    });
                }
            }, "BetterHeybox-image-share");
            worker.start();
            return true;
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "图片分享接管失败，回退原分享", t);
            return false;
        }
    }

    private File downloadImage(Context context, String imageUrl) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(imageUrl).openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(20000);
        connection.setInstanceFollowRedirects(true);
        connection.connect();
        if (connection.getResponseCode() < 200 || connection.getResponseCode() >= 300) {
            throw new IllegalStateException("HTTP " + connection.getResponseCode());
        }
        File dir = new File(context.getCacheDir(), "betterheybox-share");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("无法创建分享缓存目录");
        }
        File output = new File(dir, "image-" + System.currentTimeMillis() + ".jpg");
        try (InputStream input = connection.getInputStream();
             FileOutputStream file = new FileOutputStream(output)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                file.write(buffer, 0, count);
            }
        } finally {
            connection.disconnect();
        }
        return output;
    }
    private Uri getTargetFileUri(Context context, File file) throws Exception {
        Class<?> provider = Class.forName("androidx.core.content.FileProvider",
                true, context.getClassLoader());
        String authority = MainModule.TARGET_PKG + ".fileprovider";
        String[] methodNames = {"getUriForFile", "h", "i"};
        for (String methodName : methodNames) {
            try {
                Method method = provider.getDeclaredMethod(
                        methodName, Context.class, String.class, File.class);
                method.setAccessible(true);
                return (Uri) method.invoke(null, context, authority, file);
            } catch (NoSuchMethodException ignored) {
            }
        }
        throw new NoSuchMethodException("FileProvider URI method not found");
    }
}
