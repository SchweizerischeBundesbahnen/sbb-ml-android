/*
 * Copyright 2022 SBB AG. License: CC0-1.0
 */
package ch.sbb.mobile.ml;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.media.Image;
import android.os.SystemClock;
import android.util.Size;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import timber.log.Timber;

/*
 * The architechture is optimized for performance.
 * 
 * There are 2 image buffers for input images and 2 threads in executor.
 * The algorithm gets images faster than it can handle and it discards some input images.
 * A thread may do both tracking and detection or only just tracking for an image depending on
 * if the detector is busy or not. Detector algorithm is slower than tracking. Tracking only thread
 * is executed several times while detection algorithms does the inference.
 *
 * The image buffers are never copied or given as input parameter to a function to avoid
 * large garbage collection.
 *
 * Some functions are C++ optimized.
 */

class FrameProcessor {

    public interface FrameProcessorListener {
        void foundObjects(List<MultiBoxTracker.TrackedRecognition> objectList);
        void error(String errorMsg);
        void info(Size previewSize, Size inputSize, int inferenceTime);
    }

    private enum YuvLayer
    {
        LAYER_Y(0),
        LAYER_U(1),
        LAYER_V(2),
        LAYER_COUNT(3);

        public final int value;
        YuvLayer(int value) { this.value = value; }
    }

    public static final int BUFFER_COUNT = 2;
    private byte[][] yuvBytesBuffer;
    private int[] rgbBytes;
    private ExecutorService executorService;
    private MLSettings mlSettings;
    private Bitmap rgbFrameBitmap;
    private TFLiteObjectDetector detector;
    private MultiBoxTracker multiBoxTracker;
    private long timestamp = 0;
    private long lastProcessingTimeMs;
    private final AtomicBoolean[] isBufferFree = new AtomicBoolean[BUFFER_COUNT];
    private final AtomicBoolean isDetectingFrame = new AtomicBoolean(false);
    private final List<MultiBoxTracker.TrackedRecognition> frameRecognitions = new ArrayList<>();
    private Bitmap scaledBitmap;
    private Matrix frameToScaledTransform;
    private Matrix scaledToFrameTransform;
    private FrameProcessorListener frameProcessorListener;

    public FrameProcessor(Context context, MLSettings mlSettings, int sensorOrientation, FrameProcessorListener frameProcessorListener) throws IOException {
        this.mlSettings = mlSettings;
        this.frameProcessorListener = frameProcessorListener;
        executorService = Executors.newFixedThreadPool(2);
        rgbFrameBitmap = Bitmap.createBitmap(mlSettings.getPreviewSize().getWidth(), mlSettings.getPreviewSize().getHeight(), Bitmap.Config.ARGB_8888);
        rgbBytes = new int[mlSettings.getPreviewSize().getWidth() * mlSettings.getPreviewSize().getHeight()];
        scaledBitmap = Bitmap.createBitmap(mlSettings.getModelInputSize(), mlSettings.getModelInputSize(), Bitmap.Config.ARGB_8888);
        frameToScaledTransform = ImageUtils.getTransformationMatrix(
                mlSettings.getPreviewSize().getWidth(), mlSettings.getPreviewSize().getHeight(),
                mlSettings.getModelInputSize(), mlSettings.getModelInputSize(),
                sensorOrientation, mlSettings.isMaintainAspectRatio());
        scaledToFrameTransform = new Matrix();
        frameToScaledTransform.invert(scaledToFrameTransform);

        detector = new TFLiteObjectDetector(context, mlSettings);
        multiBoxTracker = new MultiBoxTracker();
        timestamp = 0;

        for(int bufferIndex = 0; bufferIndex < BUFFER_COUNT; bufferIndex++)
        {
            isBufferFree[bufferIndex] = new AtomicBoolean(true);
        }
    }

    public void processImage(Image image, int bufferIndex) {
        final Image.Plane[] planes = image.getPlanes();
        final int yRowStride = planes[YuvLayer.LAYER_Y.value].getRowStride();
        final int uvRowStride = planes[YuvLayer.LAYER_U.value].getRowStride();
        final int uvPixelStride = planes[YuvLayer.LAYER_U.value].getPixelStride();

        // allocate on first run
        // it is not possible to allocate these buffers earlier. in some image formats the yuv image is actually
        // bigger than chosen preview size
        if(yuvBytesBuffer == null) {
            final int luminanceLength = planes[YuvLayer.LAYER_Y.value].getBuffer().capacity();
            final int chrominanceLength = planes[YuvLayer.LAYER_U.value].getBuffer().capacity();

            yuvBytesBuffer = new byte[BUFFER_COUNT * YuvLayer.LAYER_COUNT.value][];
            for(int iterationIndex = 0; iterationIndex < BUFFER_COUNT; iterationIndex++)
            {
                final int buffIndex = iterationIndex * YuvLayer.LAYER_COUNT.value;
                yuvBytesBuffer[buffIndex + YuvLayer.LAYER_Y.value] = new byte[luminanceLength];
                yuvBytesBuffer[buffIndex + YuvLayer.LAYER_U.value] = new byte[chrominanceLength];
                yuvBytesBuffer[buffIndex + YuvLayer.LAYER_V.value] = new byte[chrominanceLength];
            }
        }

        final int bufferPointer = bufferIndex * YuvLayer.LAYER_COUNT.value;
        planes[YuvLayer.LAYER_Y.value].getBuffer().get(yuvBytesBuffer[bufferPointer + YuvLayer.LAYER_Y.value]);
        planes[YuvLayer.LAYER_U.value].getBuffer().get(yuvBytesBuffer[bufferPointer + YuvLayer.LAYER_U.value]);
        planes[YuvLayer.LAYER_V.value].getBuffer().get(yuvBytesBuffer[bufferPointer + YuvLayer.LAYER_V.value]);

        Timber.i("onImageAvailable - launch new thread");
        Runnable task = () -> {
            // first do tracking
            timestamp++;
            if(mlSettings.isUseTracker()) {
                Timber.i("Executing tracker inside : %s", Thread.currentThread().getName());
                trackFrame(yuvBytesBuffer[bufferPointer + YuvLayer.LAYER_Y.value], timestamp);
            }

            if(isDetectingFrame.get()) {
                // the detector is already busy, free the buffer and return.
                freeBufferByIndex(bufferIndex);
                return;
            }

            // run object detector on this frame
            isDetectingFrame.set(true);
            //try {
                Timber.i("Executing detection inside : %s", Thread.currentThread().getName());
                yuv2RGB(yRowStride, uvRowStride, uvPixelStride, yuvBytesBuffer, bufferPointer);
                scale();
                detectObjects(yuvBytesBuffer[bufferPointer + YuvLayer.LAYER_Y.value], timestamp);
            //} catch(Exception e) {
            //    Timber.e("Buffer overflow. This may happen if input image size is changed on the fly. Just skip this frame.");
            //}
            isDetectingFrame.set(false);

            // closing the image will enable us the get next image in onImageAvailable() and we also free a buffer to read it.
            freeBufferByIndex(bufferIndex);
        };
        executorService.execute(task);
    }

    private void trackFrame(byte[] luminance, long timestamp) {
        Timber.i("trackFrame");
        multiBoxTracker.onFrame(
                mlSettings.getPreviewSize().getWidth(),
                mlSettings.getPreviewSize().getHeight(),
                mlSettings.getPreviewSize().getWidth(),
                luminance,
                timestamp);

        publishTrackerResults();
    }

    private synchronized void publishTrackerResults() {
        frameRecognitions.clear();
        CopyOnWriteArrayList<MultiBoxTracker.TrackedRecognition> unmutableCopyOfTrackedRecognitions = new CopyOnWriteArrayList(multiBoxTracker.publish());
        for (final MultiBoxTracker.TrackedRecognition result : unmutableCopyOfTrackedRecognitions) {
            if ((result != null) && (result.getTrackedObject() != null) && (result.getTrackedObject().isValid())) {
                frameRecognitions.add(result);
                Timber.i("found tracker match: %s", result.getTrackedObject().getTrackedPositionInPreviewFrame());
            }
        }
        frameProcessorListener.foundObjects(frameRecognitions);
    }

    private void detectObjects(byte[] luminance, long lastTimestamp) {
        Timber.i("detectObjects");
        final long startTime = SystemClock.uptimeMillis();
        List<MLRecognition> results = new ArrayList<>();
        try {
            results = detector.recognizeImage(scaledBitmap);
        } catch (IOException e) {
            frameProcessorListener.error("Object detection failed:" + e.toString());
            return;
        }

        final List<MLRecognition> validResults = new ArrayList<>();
        lastProcessingTimeMs = SystemClock.uptimeMillis() - startTime;

        for (final MLRecognition result : results) {
            final RectF location = result.getLocation();
            if (location != null &&
                    result.getConfidence() >= mlSettings.getMinimumConfidence() &&
                    result.getLocation().width() >= mlSettings.getMinObjectSize() &&
                    result.getLocation().height() >= mlSettings.getMinObjectSize()) {
                scaledToFrameTransform.mapRect(location);
                result.setLocation(location);
                validResults.add(result);
                Timber.i("detectObjects found: %s", location.toString());
            }
        }

        // update tracker with true detections
        if(mlSettings.isUseTracker()) {
            multiBoxTracker.trackResults(validResults, luminance, lastTimestamp);
        }

        publishDetectionResults(validResults);
    }

    private synchronized void publishDetectionResults(List<MLRecognition> validResults) {
        frameRecognitions.clear();
        if(mlSettings.isUseTracker()) {
            List<MultiBoxTracker.TrackedRecognition> trackedRecognitions = multiBoxTracker.publish();
            for (final MultiBoxTracker.TrackedRecognition result : trackedRecognitions) {
                if ((result != null) && (result.getTrackedObject() != null) && result.getTrackedObject().isValid()) {
                    frameRecognitions.add(result);
                }
            }
        } else {
            for(final MLRecognition validResult: validResults) {
                frameRecognitions.add(new MultiBoxTracker.TrackedRecognition(null, validResult.getLocation(), validResult.getConfidence(), validResult.getTitle()));
            }
        }

        Timber.i("Objects detected: %d", frameRecognitions.size());
        frameProcessorListener.foundObjects(frameRecognitions);

        frameProcessorListener.info(
                new Size(mlSettings.getPreviewSize().getWidth(), mlSettings.getPreviewSize().getHeight()),
                new Size(scaledBitmap.getWidth(), scaledBitmap.getHeight()),
                (int)lastProcessingTimeMs);
    }

    public void onStop() {
        if(executorService != null) {
            executorService.shutdown();
        }
    }

    private void scale() {
        Timber.i("scale");
        rgbFrameBitmap.setPixels(rgbBytes, 0, mlSettings.getPreviewSize().getWidth(), 0, 0,
                mlSettings.getPreviewSize().getWidth(), mlSettings.getPreviewSize().getHeight());
        final Canvas canvas = new Canvas(scaledBitmap);
        canvas.drawBitmap(rgbFrameBitmap, frameToScaledTransform, null);
    }

    private void yuv2RGB(int yRowStride, int uvRowStride, int uvPixelStride, byte[][] yuvBytes, final int bufferPointer) {
        Timber.i("yuv2RGB");
        ImageUtils.convertYUV420ToARGB8888(
                yuvBytes[bufferPointer + YuvLayer.LAYER_Y.value],
                yuvBytes[bufferPointer + YuvLayer.LAYER_U.value],
                yuvBytes[bufferPointer + YuvLayer.LAYER_V.value],
                rgbBytes,
                mlSettings.getPreviewSize().getWidth(),
                mlSettings.getPreviewSize().getHeight(),
                yRowStride,
                uvRowStride,
                uvPixelStride,
                false);
  }

    int getFreeBufferIndex()
    {
        for(int bufferIndex = 0; bufferIndex < BUFFER_COUNT; bufferIndex++)
        {
            if(isBufferFree[bufferIndex].get())
            {
                Timber.i("Buffer Index %d reserved", bufferIndex);
                isBufferFree[bufferIndex].set(false);
                return bufferIndex;
            }
        }
        Timber.i("No free buffer could be found");
        return -1;
    }

    void freeBufferByIndex(int index) {
        // this will just mark buffer as free
        Timber.i("Free buffer %d", index);
        isBufferFree[index].set(true);
    }
}
