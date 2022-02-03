package ch.sbb.mobile.ml;

import static androidx.test.core.app.ApplicationProvider.getApplicationContext;
import static com.google.common.truth.Truth.assertThat;
import android.util.Size;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public class FrameProcessorTest  {

    private FrameProcessor frameProcessor;
    private MLSettings mlSettings;

    @Before
    public void setUp() {
        mlSettings = new MLSettings(new Size(640, 480), "wagen_yolov5n_448_float32.tflite", 448);
        mlSettings.setPreviewSize(new Size(640, 480));
    }

    @Test
    public void canReserver2Buffers() throws Exception {

        frameProcessor = new FrameProcessor(getApplicationContext(), mlSettings, 0, new FrameProcessor.FrameProcessorListener() {
            @Override
            public void foundObjects(List<MultiBoxTracker.TrackedRecognition> objectList) {

            }

            @Override
            public void error(String errorMsg) {
                Assert.fail(errorMsg);
            }

            @Override
            public void info(String frameInfo, String scaleInfo, int inferenceTime) {

            }
        });

        int index0 = frameProcessor.reserveBuffer();
        int index1 = frameProcessor.reserveBuffer();
        int index2 = frameProcessor.reserveBuffer();

        assertThat(index0).isEqualTo(0);
        assertThat(index1).isEqualTo(1);
        assertThat(index2).isEqualTo(-1);

        frameProcessor.freeBuffer(1);
        int index3 = frameProcessor.reserveBuffer();
        assertThat(index3).isEqualTo(1);
    }
}
