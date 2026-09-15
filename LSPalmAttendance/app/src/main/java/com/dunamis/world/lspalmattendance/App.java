package com.dunamis.world.lspalmattendance;

import android.app.Application;

import com.api.stream.PalmSdk;

/**
 * @author ljj
 * @date 2021/09/11  11:04
 * @description:
 **/
public class App extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        com.dunamis.world.lspalmattendance.util.AppUtils.init(this);
        PalmSdk.initialize();
    }
}