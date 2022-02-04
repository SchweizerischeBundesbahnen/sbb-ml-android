/*
 * Copyright 2022 SBB AG. License: CC0-1.0
 */
package ch.sbb.mobile.ml;

import android.graphics.RectF;

import org.jetbrains.annotations.NotNull;

import java.util.Locale;

public class MLRecognition {
    private final String title;
    private final Float confidence;
    private RectF location;

    public MLRecognition(final String title, final Float confidence, final RectF location) {
        this.title = title;
        this.confidence = confidence;
        this.location = location;
    }

    public String getTitle() {
        return title;
    }

    public Float getConfidence() {
        return confidence;
    }

    public RectF getLocation() {
        return new RectF(location);
    }

    public void setLocation(RectF location) {
        this.location = location;
    }

    @Override
    public @NotNull String toString() {
        String resultString = "";
        if (title != null) {
            resultString += title + " ";
        }

        if (confidence != null) {
            resultString += String.format(Locale.ENGLISH, "(%.1f%%) ", confidence * 100.0f);
        }

        if (location != null) {
            resultString += location + " ";
        }

        return resultString.trim();
    }
}
