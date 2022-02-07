package ch.sbb.mobile.ml;

import static androidx.test.core.app.ApplicationProvider.getApplicationContext;
import static com.google.common.truth.Truth.assertThat;
import android.util.Size;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class CameraPreviewTest  {

    private CameraPreview cameraPreview;
    private MLSettings mlSettings;

    @Before
    public void setUp() {
        cameraPreview = new CameraPreview();
        mlSettings = new MLSettings(new Size(640, 480), "wagen_yolov5n_448_float32.tflite", 448);
        mlSettings.setPreviewSize(new Size(640, 480));
    }

    @Test
    public void cameraOutputSizes() {
        Size[] sizes = cameraPreview.getCameraOutputSizes(getApplicationContext());
        if(sizes.length == 0) {
            Assert.fail("No camera size found");
        }
    }

    @Test
    public void cameraOrientation() {
        int orientation = cameraPreview.getCameraOrientation(getApplicationContext());
        assertThat(orientation).isAnyOf(0,90,180,270);
    }
}

