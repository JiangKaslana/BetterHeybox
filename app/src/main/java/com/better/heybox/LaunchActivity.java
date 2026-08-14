package com.better.heybox;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * 桌面图标入口：仅负责跳转到模块设置页。
 * 可被设置页「不显示桌面图标」开关禁用（PackageManager 组件级禁用），
 * 禁用后桌面图标消失，但小黑盒设置页注入的入口仍可直达 SettingsActivity。
 */
public class LaunchActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            startActivity(new Intent(this, SettingsActivity.class));
        } finally {
            finish();
        }
    }
}
