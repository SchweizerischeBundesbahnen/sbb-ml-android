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
import java.nio.ByteBuffer;
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

    private byte[][][] yuvBytesBuffer;
    private int[] rgbBytes;
    private ExecutorService executorService;
    private MLSettings mlSettings;
    private Bitmap rgbFrameBitmap;
    private TFLiteObjectDetector detector;
    private MultiBoxTracker multiBoxTracker;
    private long timestamp = 0;
    private long lastProcessingTimeMs;
    private final AtomicBoolean isBuffer0Free = new AtomicBoolean(true);
    private final AtomicBoolean isBuffer1Free = new AtomicBoolean(true);
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
    }

    public void processImage(Image image, int bufferIndex) {
        final Image.Plane[] planes = image.getPlanes();
        final int yRowStride = planes[0].getRowStride();
        final int uvRowStride = planes[1].getRowStride();
        final int uvPixelStride = planes[1].getPixelStride();

        // allocate on first run
        // it is not possible to allocate these buffers earlier. in some image formats the yuv image is actually
        // bigger than chosen preview size
        if(yuvBytesBuffer == null) {
            final int luminanceLength = planes[0].getBuffer().capacity();
            final int chrominanceLength = planes[1].getBuffer().capacity();

            yuvBytesBuffer = new byte[2][3][];
            yuvBytesBuffer[0][0] = new byte[luminanceLength];
            yuvBytesBuffer[0][1] = new byte[chrominanceLength];
            yuvBytesBuffer[0][2] = new byte[chrominanceLength];
            yuvBytesBuffer[1][0] = new byte[luminanceLength];
            yuvBytesBuffer[1][1] = new byte[chrominanceLength];
            yuvBytesBuffer[1][2] = new byte[chrominanceLength];
        }

        for (int i = 0; i < planes.length; ++i) {
            final ByteBuffer buffer = planes[i].getBuffer();
            buffer.get(yuvBytesBuffer[bufferIndex][i]);
        }

        Timber.i("onImageAvailable - launch new thread");
        Runnable task = () -> {
            // first do tracking
            timestamp++;
            if(mlSettings.isUseTracker()) {
                Timber.i("Executing tracker inside : %s", Thread.currentThread().getName());
                trackFrame(yuvBytesBuffer[bufferIndex][0], timestamp);
            }

            if(isDetectingFrame.get()) {
                // the detector is already busy, free the buffer and return.
                image.close();
                freeBuffer(bufferIndex);
                return;
            }

            // run object detector on this frame
            isDetectingFrame.set(true);
            try {
                Timber.i("Executing detection inside : %s", Thread.currentThread().getName());
                yuv2RGB(yRowStride, uvRowStride, uvPixelStride, yuvBytesBuffer[bufferIndex]);
                scale();
                detectObjects(yuvBytesBuffer[bufferIndex][0], timestamp);
            } catch(Exception e) {
                Timber.e("Buffer overflow. This may happen if input image size is changed on the fly. Just skip this frame.");
            }
            isDetectingFrame.set(false);

            // closing the image will enable us the get next image in onImageAvailable() and we also free a buffer to read it.
            image.close();
            freeBuffer(bufferIndex);
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

    private void yuv2RGB(int yRowStride, int uvRowStride, int uvPixelStride, byte[][] yuvBytes) {
        Timber.i("yuv2RGB");
        ImageUtils.convertYUV420ToARGB8888(
                yuvBytes[0],
                yuvBytes[1],
                yuvBytes[2],
                rgbBytes,
                mlSettings.getPreviewSize().getWidth(),
                mlSettings.getPreviewSize().getHeight(),
                yRowStride,
                uvRowStride,
                uvPixelStride,
                false);
  }

    int reserveBuffer() {
        if(isBuffer0Free.get()) {
            Timber.i("reserveBuffer 0");
            isBuffer0Free.set(false);
            return 0;
        } else if(isBuffer1Free.get()) {
            Timber.i("reserveBuffer 1");
            isBuffer1Free.set(false);
            return 1;
        } else {
            return -1;
        }
    }

    void freeBuffer(int index) {
        // this will just mark buffer as free
        Timber.i("Free buffer %d", index);
        if(index == 0) {
            isBuffer0Free.set(true);
        } else if(index == 1) {
            isBuffer1Free.set(true);
        }
    }
}
