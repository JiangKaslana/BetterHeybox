package com.better.heybox;

import android.text.Spannable;
import android.text.method.LinkMovementMethod;
import android.view.MotionEvent;
import android.widget.TextView;

/**
 * 透明 LinkMovementMethod：不干预任何事件与 Selection。
 *
 * 用途：正文 TextView 保留 ClickableSpan（@提及）点击跳转的同时，
 * 长按文本选择 Android 原生处理。
 */
public final class SelectionSafeLinkMovementMethod extends LinkMovementMethod {

    private static SelectionSafeLinkMovementMethod sInstance;

    public static SelectionSafeLinkMovementMethod getInstance() {
        if (sInstance == null) {
            sInstance = new SelectionSafeLinkMovementMethod();
        }
        return sInstance;
    }

    @Override
    public boolean onTouchEvent(TextView widget, Spannable buffer, MotionEvent event) {
        return super.onTouchEvent(widget, buffer, event);
    }
}
