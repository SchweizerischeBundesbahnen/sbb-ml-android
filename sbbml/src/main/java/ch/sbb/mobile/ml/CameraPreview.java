/*
 * Copyright 2022 SBB AG. License: CC0-1.0
 */
package ch.sbb.mobile.ml;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import androidx.core.app.ActivityCompat;
import java.util.Arrays;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import timber.log.Timber;

class CameraPreview {

    private CameraCaptureSession captureSession;
    private CameraDevice cameraDevice;
    private final Semaphore cameraOpenCloseLock = new Semaphore(1);
    private Handler cameraHandler;
    private ImageReader previewReader;
    private CaptureRequest.Builder previewRequestBuilder;
    private CaptureRequest previewRequest;
    private HandlerThread cameraThread;
    private ImageReader.OnImageAvailableListener imageAvailableListener;
    private TextureView textureView;
    private MLSettings mlSettings;

    private final CameraCaptureSession.CaptureCallback captureCallback =
            new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureProgressed(
                        final CameraCaptureSession session,
                        final CaptureRequest request,
                        final CaptureResult partialResult) {
                }

                @Override
                public void onCaptureCompleted(
                        final CameraCaptureSession session,
                        final CaptureRequest request,
                        final TotalCaptureResult result) {
                }
            };

    private final CameraDevice.StateCallback stateCallback =
            new CameraDevice.StateCallback() {
                @Override
                public void onOpened(final CameraDevice cd) {
                    cameraOpenCloseLock.release();
                    cameraDevice = cd;
                    createCameraPreviewSession(textureView, mlSettings, imageAvailableListener);
                }

                @Override
                public void onDisconnected(final CameraDevice cd) {
                    cameraOpenCloseLock.release();
                    cd.close();
                    cameraDevice = null;
                }

                @Override
                public void onError(final CameraDevice cd, final int error) {
                    cameraOpenCloseLock.release();
                    cd.close();
                    cameraDevice = null;
                }

                @Override
                public void onClosed(final CameraDevice cd) {
                    super.onClosed(cd);
                    cameraOpenCloseLock.release();
                    cd.close();
                    cameraDevice = null;
                }
            };

    private void createCameraPreviewSession(TextureView textureView, MLSettings mlSettings, ImageReader.OnImageAvailableListener imageListener) {
        Timber.i("createCameraPreviewSession");
        try {
            final SurfaceTexture texture = textureView.getSurfaceTexture();
            texture.setDefaultBufferSize(mlSettings.getPreviewSize().getWidth(), mlSettings.getPreviewSize().getHeight());
            final Surface surface = new Surface(texture);
            previewRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewRequestBuilder.addTarget(surface);

            previewReader = ImageReader.newInstance(mlSettings.getPreviewSize().getWidth(), mlSettings.getPreviewSize().getHeight(), ImageFormat.YUV_420_888, 2);
            previewReader.setOnImageAvailableListener(imageListener, cameraHandler);
            previewRequestBuilder.addTarget(previewReader.getSurface());

            cameraDevice.createCaptureSession(
                    Arrays.asList(surface, previewReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {

                        @Override
                        public void onConfigured(final CameraCaptureSession cameraCaptureSession) {
                            if (null == cameraDevice) {
                                return;
                            }
                            captureSession = cameraCaptureSession;
                            try {
                                previewRequestBuilder.set(
                                        CaptureRequest.CONTROL_AF_MODE,
                                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                                previewRequestBuilder.set(
                                        CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);

                                previewRequest = previewRequestBuilder.build();
                                captureSession.setRepeatingRequest(previewRequest, captureCallback, cameraHandler);
                                Timber.i("Camera is ready");
                            } catch (final CameraAccessException e) {
                                Timber.e(e);
                            }
                        }

                        @Override
                        public void onConfigureFailed(final CameraCaptureSession cameraCaptureSession) {
                            Timber.e("Camera config failed");
                        }
                    },
                    null);
        } catch (final CameraAccessException | IllegalArgumentException e) {
            Timber.e(e);
        }
    }

    private String chooseCamera(Context context) {
        Timber.i("chooseCamera");
        final CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        try {
            for (final String cameraId : manager.getCameraIdList()) {
                final CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
                final Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    continue;
                }

                final StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (map == null) {
                    continue;
                }
                return cameraId;
            }
        } catch (CameraAccessException e) {
            Timber.e(e, "Not allowed to access camera");
        }
        return null;
    }

    int getCameraOrientation(Context context) {
        String cameraId = chooseCamera(context);
        final CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        try {
            final CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            return characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
        } catch (final Exception e) {
            Timber.e(e);
            return 0;
        }
    }

    Size[] getCameraOutputSizes(Context context) {
        String cameraId = chooseCamera(context);
        final CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        try {
            final CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            final StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            return map.getOutputSizes(SurfaceTexture.class);
        } catch (final Exception e) {
            Timber.e(e);
            return new Size[0];
        }
    }

    void openCamera(Context context, ImageReader.OnImageAvailableListener imageAvailableListener, TextureView textureView, MLSettings mlSettings) {
        Timber.i("openCamera");
        this.imageAvailableListener = imageAvailableListener;
        this.textureView = textureView;
        this.mlSettings = mlSettings;

        String cameraId = chooseCamera(context);
        startCameraThread();
        final CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        try {
            if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw new RuntimeException("Time out waiting to lock camera opening.");
            }
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                return;
            }
            manager.openCamera(cameraId, stateCallback, cameraHandler);
        } catch (final CameraAccessException e) {
            Timber.e(e);
        } catch (final InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera opening.", e);
        }
    }

    private void closeCamera() {
        Timber.i("closeCamera");
        try {
            cameraOpenCloseLock.acquire();
            if (null != captureSession) {
                captureSession.close();
                captureSession = null;
            }
            if (null != cameraDevice) {
                cameraDevice.close();
                cameraDevice = null;
            }
            if (null != previewReader) {
                previewReader.close();
                previewReader = null;
            }
        } catch (final InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera closing.", e);
        } finally {
            cameraOpenCloseLock.release();
        }
    }

    void stopCamera() {
        Timber.i("stopCamera");
        closeCamera();
        stopCameraThread();
    }

    private void startCameraThread() {
        Timber.i("startCameraThread");
        cameraThread = new HandlerThread("CameraThread");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
    }

    private void stopCameraThread() {
        Timber.i("stopCameraThread");
        if(cameraThread != null) {
            cameraThread.quitSafely();
            try {
                cameraThread.join();
                cameraThread = null;
                cameraHandler = null;
            } catch (final InterruptedException e) {
                Timber.e(e);
            }
        }
    }
}
