/*
 * Copyright 2022 SBB AG. License: CC0-1.0
 */
package ch.sbb.mobile.ml.demo;

import android.util.Log;

import org.jetbrains.annotations.NotNull;

import timber.log.Timber;

class LogsReleaseTree extends Timber.Tree {
    @Override
    protected void log(int priority, String tag, @NotNull String message, Throwable t) {
        if (priority == Log.VERBOSE || priority == Log.DEBUG || priority == Log.INFO || priority == Log.WARN) {
            return;
        }
        Log.println(priority, tag, message);
    }
}