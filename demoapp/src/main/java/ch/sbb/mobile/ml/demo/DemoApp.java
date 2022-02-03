package ch.sbb.mobile.ml.demo;

import android.app.Application;
import timber.log.Timber;

public class DemoApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        if (BuildConfig.DEBUG) {
            Timber.plant(new Timber.DebugTree());
        } else {
            Timber.plant(new LogsReleaseTree());
        }
    }
}

