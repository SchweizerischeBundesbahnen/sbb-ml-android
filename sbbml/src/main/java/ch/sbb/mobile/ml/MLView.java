/*
 * Copyright 2022 SBB AG. License: CC0-1.0
 */
package ch.sbb.mobile.ml;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.media.Image;
import android.media.ImageReader;
import android.media.ImageReader.OnImageAvailableListener;
import android.util.AttributeSet;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Semaphore;

import timber.log.Timber;

/**
 * Use @see {@link MLView} if you don't have a @see {@link android.app.FragmentManager} to manage the lifecycle.
 * For starting use @see {@link MLView#onAttach(DetectionListener, MLSettings)} and @see {@link MLView#startDetection()}.
 * For stopping use @see {@link MLView#onStop()} and @see {@link MLView#onDetach()}.
 */
public class MLView extends FrameLayout implements OnImageAvailableListener, FrameProcessor.FrameProcessorListener {
  public MLView(@NonNull Context context) {
    super(context);
    initView();
  }

  public MLView(@NonNull Context context, @Nullable AttributeSet attrs) {
    super(context, attrs);
    initView();
  }

  public MLView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
    super(context, attrs, defStyleAttr);
    initView();
  }

  public MLView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr, int defStyleRes) {
    super(context, attrs, defStyleAttr, defStyleRes);
    initView();
  }

  private void initView() {
    final View view = inflate(getContext(), R.layout.camera_fragment, this);
    textureView = view.findViewById(R.id.texture);
    trackingOverlay = view.findViewById(R.id.tracking_overlay);
  }

  public interface DetectionListener {

    /**
     * Used preview size.
     *
     * @param inputSize
     */
    void previewSize(Size inputSize);

    /**
     * Used input size.
     *
     * @param inputSize
     */
    void inputSize(Size inputSize);

    /**
     * Latest object detection inference time. This does not include tracker.
     *
     * @param inferenceTime
     */
    void inferenceTime(int inferenceTime);

    /**
     * Detected objects are ready to be drawn. The fragment does not draw the objects but instead
     * this is delegated to main the app so one can custom drawing.
     *
     * @param canvas  on which the object are drawn.
     * @param view    can used to set a touch listener on objects.
     * @param objects the detected objects and their locations on the canvas.
     */
    void drawObjects(Canvas canvas, View view, List<MLRecognition> objects);

    /**
     * User denied to give camera access asked by the library.
     * You can also ask the permission on app side to have more control of this.
     */
    void permissonDenied();

    /**
     * Any error which prevents the object detection to run.
     *
     * @param errorMsg
     */
    void runError(String errorMsg);
  }

  private enum STATE {STOPPED, RUNNING, INITIALIZING}

  private STATE state = STATE.STOPPED;
  public static final String TAG = "MLView";
  private DetectionListener detectionListener;
  private MLSettings mlSettings;
  private AutoFitTextureView textureView;
  private Integer sensorOrientation;
  private final List<MLRecognition> canvasObjects = new ArrayList<>();
  private OverlayView trackingOverlay;
  private CameraPreview cameraPreview;
  private FrameProcessor frameProcessor;
  private List<MultiBoxTracker.TrackedRecognition> frameRecognitions = new ArrayList<>();
  private final Semaphore updateObjectsSemaphore = new Semaphore(1);

  private final TextureView.SurfaceTextureListener surfaceTextureListener =
      new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(final SurfaceTexture texture, final int width, final int height) {
          Timber.i("onSurfaceTextureAvailable");
          initialize();
        }

        @Override
        public void onSurfaceTextureSizeChanged(final SurfaceTexture texture, final int width, final int height) {
          Timber.i("onSurfaceTextureSizeChanged");
          initialize();
        }

        @Override
        public boolean onSurfaceTextureDestroyed(final SurfaceTexture texture) {
          return true;
        }

        @Override
        public void onSurfaceTextureUpdated(final SurfaceTexture texture) {
        }
      };

  /**
   * This will initialize callbacks and sets the initial settings.
   *
   * @param listener   This includes all the callbacks for the detector.
   * @param mlSettings Configuration of the detector.
   */
  public void onAttach(DetectionListener listener, MLSettings mlSettings) {
    detectionListener = listener;
    this.mlSettings = mlSettings;
  }

  /**
   * Cleanup the view when the hosting Component gets destroyed.
   */
  public void onDetach() {
    detectionListener = null;
  }

  /**
   * Start detection.
   * Make sure that @see {@link MLView#onAttach(DetectionListener, MLSettings)} was called before.
   */
  public void startDetection() {
    Timber.i("startDetection");
    // When the screen is turned off and turned back on, the SurfaceTexture is already
    // available, and "onSurfaceTextureAvailable" will not be called. In this case, we can open
    // a camera and start preview from here (otherwise, we wait until the surface is ready in
    // the SurfaceTextureListener).

    if (textureView.isAvailable()) {
      initialize();
    } else {
      textureView.setSurfaceTextureListener(surfaceTextureListener);
    }
  }

  /**
   * Suspend the detector when the hosting Component transits to the Stopped state.
   */
  public void onStop() {
    Timber.i("onStop");
    state = STATE.STOPPED;

    if (cameraPreview != null) {
      cameraPreview.stopCamera();
    }
    if (frameProcessor != null) {
      frameProcessor.onStop();
    }
  }

  /**
   * Update the ML settings.
   * <p>
   * Update may fail and the object detection does not start. In this case @see {@link DetectionListener#runError(String)}
   *
   * @param mlSettings new settings.
   */
  public void updateSettings(MLSettings mlSettings) {
    if (state.equals(STATE.RUNNING) || state.equals(STATE.STOPPED)) {
      this.mlSettings = mlSettings;
      onStop();
      initialize();
    } else {
      detectionListener.runError("Cannot update settings, already initializing");
    }
  }

  private void setAspectRatio() {
    final int orientation = getResources().getConfiguration().orientation;
    if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
      textureView.setAspectRatio(mlSettings.getPreviewSize().getWidth(), mlSettings.getPreviewSize().getHeight());
    } else {
      textureView.setAspectRatio(mlSettings.getPreviewSize().getHeight(), mlSettings.getPreviewSize().getWidth());
    }
  }

  private void initialize() {
    Timber.i("initialize");
    state = STATE.INITIALIZING;
    cameraPreview = new CameraPreview();

    mlSettings.setPreviewSize(ImageUtils.chooseOptimalSize(
        cameraPreview.getCameraOutputSizes(getContext()),
        mlSettings.getDesirePreviewSize().getWidth(),
        mlSettings.getDesirePreviewSize().getHeight()));

    setAspectRatio();

    sensorOrientation = cameraPreview.getCameraOrientation(getContext()) - getScreenOrientation();

    try {
      frameProcessor = new FrameProcessor(getContext(), mlSettings, sensorOrientation, this);
    } catch (final IOException e) {
      String errorMsg = "model init failed: " + e.toString();
      Timber.e(errorMsg);
      detectionListener.runError(errorMsg);
      state = STATE.STOPPED;
      return;
    }

    configureTextureViewTransform(textureView.getWidth(), textureView.getHeight());

    initRenderer();

    cameraPreview.openCamera(getContext(), this, textureView, mlSettings);
    state = STATE.RUNNING;
  }

  /**
   * Configures the necessary {@link Matrix} transformation to `mTextureView`. This method should be
   * called after the camera preview size is determined in setUpCameraOutputs and also the size of
   * `mTextureView` is fixed.
   *
   * @param viewWidth  The width of `mTextureView`
   * @param viewHeight The height of `mTextureView`
   */
  private void configureTextureViewTransform(final int viewWidth, final int viewHeight) {
    Timber.i("configureTransform " + viewWidth + " " + viewHeight);
    if (null == textureView || null == mlSettings.getPreviewSize()) {
      return;
    }
    final int rotation = ((Activity) getContext()).getWindowManager().getDefaultDisplay().getRotation();
    final Matrix matrix = new Matrix();
    final RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
    final RectF bufferRect = new RectF(0, 0, mlSettings.getPreviewSize().getHeight(), mlSettings.getPreviewSize().getWidth());
    final float centerX = viewRect.centerX();
    final float centerY = viewRect.centerY();
    if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
      bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
      matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
      final float scale = Math.max(
          (float) viewHeight / mlSettings.getPreviewSize().getHeight(),
          (float) viewWidth / mlSettings.getPreviewSize().getWidth());
      matrix.postScale(scale, scale, centerX, centerY);
      matrix.postRotate(90 * (rotation - 2), centerX, centerY);
    } else if (Surface.ROTATION_180 == rotation) {
      matrix.postRotate(180, centerX, centerY);
    }
    textureView.setTransform(matrix);
  }

  private synchronized void mapTrackedObjectPositions2Canvas(Canvas canvas) {
    Timber.i("mapRecognitions2Canvas");
    final boolean rotated = sensorOrientation % 180 == 90;
    final float multiplier = Math.min(
        canvas.getHeight() / (float) (rotated ? mlSettings.getPreviewSize().getWidth() : mlSettings.getPreviewSize().getHeight()),
        canvas.getWidth() / (float) (rotated ? mlSettings.getPreviewSize().getHeight() : mlSettings.getPreviewSize().getWidth()));

    Matrix frameToCanvasMatrix = ImageUtils.getTransformationMatrix(
        mlSettings.getPreviewSize().getWidth(),
        mlSettings.getPreviewSize().getHeight(),
        (int) (multiplier * (rotated ? mlSettings.getPreviewSize().getHeight() : mlSettings.getPreviewSize().getWidth())),
        (int) (multiplier * (rotated ? mlSettings.getPreviewSize().getWidth() : mlSettings.getPreviewSize().getHeight())),
        sensorOrientation,
        false);

    canvasObjects.clear();
    CopyOnWriteArrayList<MultiBoxTracker.TrackedRecognition> unmutableCopyOfTrackedRecognitions = new CopyOnWriteArrayList(frameRecognitions);
    for (final MultiBoxTracker.TrackedRecognition recognition : unmutableCopyOfTrackedRecognitions) {
      MLRecognition detectedObject;
      if (recognition.getTrackedObject() != null) {
        detectedObject = new MLRecognition(recognition.getTitle(), recognition.getDetectionConfidence(), recognition.getTrackedObject().getTrackedPositionInPreviewFrame());
      } else {
        detectedObject = new MLRecognition(recognition.getTitle(), recognition.getDetectionConfidence(), recognition.getLocation());
      }

      final RectF trackedPos = new RectF(detectedObject.getLocation());
      frameToCanvasMatrix.mapRect(trackedPos);
      detectedObject.setLocation(trackedPos);
      canvasObjects.add(detectedObject);
    }
  }

  @Override
  public void onImageAvailable(final ImageReader reader) {
    if (state.equals(STATE.RUNNING)) {
      // check if there is a free buffer available before getting the image.
      int bufferIndex = frameProcessor.reserveBuffer();
      if (bufferIndex >= 0) {
        final Image image = reader.acquireLatestImage();
        if (image != null) {
          frameProcessor.processImage(image, bufferIndex);
        } else {
          frameProcessor.freeBuffer(bufferIndex);
        }
      }
    }
  }

  private void requestDrawing() {
    Timber.i("requestDrawing");
    trackingOverlay.postInvalidate();
  }

  private void initRenderer() {
    Timber.i("initRenderer");
    trackingOverlay.clearCallbacks();
    trackingOverlay.addCallback(
        canvas -> {
          updateObjectsSemaphore.acquireUninterruptibly();
          mapTrackedObjectPositions2Canvas(canvas);
          if (detectionListener != null) {
            detectionListener.drawObjects(canvas, textureView, canvasObjects);
          }
          updateObjectsSemaphore.release();
        });
  }

  private int getScreenOrientation() {
    Timber.i("getScreenOrientation");
    switch (((Activity) getContext()).getWindowManager().getDefaultDisplay().getRotation()) {
      case Surface.ROTATION_270:
        return 270;
      case Surface.ROTATION_180:
        return 180;
      case Surface.ROTATION_90:
        return 90;
      case Surface.ROTATION_0:
      default:
        return 0;
    }
  }

  @Override
  public void foundObjects(List<MultiBoxTracker.TrackedRecognition> objectList) {
    updateObjectsSemaphore.acquireUninterruptibly();
    frameRecognitions = objectList;
    updateObjectsSemaphore.release();
    requestDrawing();
  }

  @Override
  public void error(String errorMsg) {
    if (detectionListener != null) {
      detectionListener.runError(errorMsg);
    }
  }

  @Override
  public void info(Size previewSize, Size inputSize, int inferenceTime) {
    if (detectionListener != null) {
      detectionListener.previewSize(previewSize);
      detectionListener.inputSize(inputSize);
      detectionListener.inferenceTime(inferenceTime);
    }
  }
}
