/*
 * Copyright 2022 SBB AG. License: CC0-1.0
 */
package ch.sbb.mobile.ml;

import android.util.Size;

public class MLSettings {
    /**
     * The preview size will be at least this large. Avoid too large preview size, it will just slow down image operations.
     */
    private Size desirePreviewSize;
    /**
     * The model filename in assets folder.
     */
    private String modelFilename;
    /**
     * The model input size.
     */
    private int modelInputSize;
    /**
     * The preview size which will be selected based on #desirePreviewSize.
     */
    private Size previewSize;
    /**
     * Number of CPU threads use to do inference.
     */
    private int numberOfThreds;
    /**
     * On which processor should the inference run.
     */
    private Processor processor;
    /**
     * Minimum confidence level for an object to be detected.
     */
    private float minimumConfidence;
    /**
     * If true, will ensure that scaling the image x and y remains constant,
     * cropping the image if necessary.
     */
    private boolean maintainAspectRatio;
    /**
     * Minimum object size in pixels to be detected.
     */
    private float minObjectSize;
    /**
     * Intersection over union. Used to control how much object bounding box overlapping is allowed.
     * https://medium.com/analytics-vidhya/iou-intersection-over-union-705a39e7acef
     */
    private float iou;
    /**
     * Enable or disable the tracker.
     */
    private boolean useTracker;
    /**
     * Use CPU as backup if running on other processor type fails.
     */
    private boolean useCPUBackup;

    // Default
    private final int NUMBER_OF_THREADS  = 4;
    private final Processor PROCESSOR = Processor.CPU;
    private final float MIN_CONFIDENCE = 0.6f;
    private final boolean MAINTAIN_ASPECT_RATIO = false;
    private final float MIN_OBJECT_SIZE = 30.0f;
    private final float IOU = 0.45f;
    private final boolean USE_TRACKER = true;
    private final boolean USE_CPU_BACKUP = true;

    public enum Processor {CPU, GPU, NNAPI}

    public MLSettings(Size desirePreviewSize, String modelFilename, int modelInputSize) {
        this.desirePreviewSize = desirePreviewSize;
        this.modelFilename = modelFilename;
        this.modelInputSize = modelInputSize;

        this.numberOfThreds = NUMBER_OF_THREADS;
        this.processor = PROCESSOR;
        this.minimumConfidence = MIN_CONFIDENCE;
        this.maintainAspectRatio = MAINTAIN_ASPECT_RATIO;
        this.minObjectSize = MIN_OBJECT_SIZE;
        this.iou = IOU;
        this.useTracker = USE_TRACKER;
        this.useCPUBackup = USE_CPU_BACKUP;
    }

    public Size getDesirePreviewSize() {
        return desirePreviewSize;
    }

    public void setDesirePreviewSize(Size desirePreviewSize) {
        this.desirePreviewSize = desirePreviewSize;
    }

    public Size getPreviewSize() {
        return previewSize;
    }

    public void setPreviewSize(Size previewSize) {
        this.previewSize = previewSize;
    }

    public String getModelFilename() {
        return modelFilename;
    }

    public void setModelFilename(String modelFilename) {
        this.modelFilename = modelFilename;
    }

    public int getModelInputSize() {
        return modelInputSize;
    }

    public void setModelInputSize(int modelInputSize) {
        this.modelInputSize = modelInputSize;
    }

    public int getNumberOfThreds() {
        return numberOfThreds;
    }

    public void setNumberOfThreds(int numberOfThreds) {
        this.numberOfThreds = numberOfThreds;
    }

    public Processor getProcessor() {
        return processor;
    }

    public void setProcessor(Processor processor) {
        this.processor = processor;
    }

    public float getMinimumConfidence() {
        return minimumConfidence;
    }

    public void setMinimumConfidence(float minimumConfidence) {
        this.minimumConfidence = minimumConfidence;
    }

    public boolean isMaintainAspectRatio() {
        return maintainAspectRatio;
    }

    public void setMaintainAspectRatio(boolean maintainAspectRatio) {
        this.maintainAspectRatio = maintainAspectRatio;
    }

    public float getMinObjectSize() {
        return minObjectSize;
    }

    public void setMinObjectSize(float minObjectSize) {
        this.minObjectSize = minObjectSize;
    }

    public float getIou() { return iou; }

    public void setIou(float iou) { this.iou = iou; }

    public boolean isUseTracker() {
        return useTracker;
    }

    public void setUseTracker(boolean useTracker) {
        this.useTracker = useTracker;
    }

    public boolean isUseCPUBackup() {
        return useCPUBackup;
    }

    public void setUseCPUBackup(boolean useCPUBackup) {
        this.useCPUBackup = useCPUBackup;
    }
}
