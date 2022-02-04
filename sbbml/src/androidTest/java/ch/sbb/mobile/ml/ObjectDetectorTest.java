package ch.sbb.mobile.ml;

import static androidx.test.core.app.ApplicationProvider.getApplicationContext;
import static com.google.common.truth.Truth.assertThat;
import static ch.sbb.mobile.ml.TestHelper.loadImage;
import android.graphics.Bitmap;
import android.util.Size;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public class ObjectDetectorTest {

    private TFLiteObjectDetector tfLiteObjectDetector;
    private MLSettings mlSettings;

    @Before
    public void setUp() throws Exception {
        mlSettings = new MLSettings(new Size(640, 480), "wagen_yolov5n_448_float32.tflite", 448);
        tfLiteObjectDetector = new TFLiteObjectDetector(getApplicationContext(), mlSettings);
    }

    @Test
    public void detectObject() throws Exception {
        Bitmap bitmap = loadImage("wagen_448.jpg");
        List<MLRecognition> detectedObjects = tfLiteObjectDetector.recognizeImage(bitmap);
        assertThat(detectedObjects.size()).isEqualTo(13);
    }

    @Test
    public void detectObjectMultipleTimes() throws Exception {
        Bitmap bitmap = loadImage("wagen_448.jpg");
        tfLiteObjectDetector.recognizeImage(bitmap);
        tfLiteObjectDetector.recognizeImage(bitmap);
        tfLiteObjectDetector.recognizeImage(bitmap);
        tfLiteObjectDetector.recognizeImage(bitmap);
        List<MLRecognition> detectedObjects = tfLiteObjectDetector.recognizeImage(bitmap);
        assertThat(detectedObjects.size()).isEqualTo(13);
    }
}
