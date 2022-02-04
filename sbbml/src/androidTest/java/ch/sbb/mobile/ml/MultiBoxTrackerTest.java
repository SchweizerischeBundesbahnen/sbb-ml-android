package ch.sbb.mobile.ml;

import static com.google.common.truth.Truth.assertThat;
import static ch.sbb.mobile.ml.TestHelper.getLuminanceFromRGB;
import static ch.sbb.mobile.ml.TestHelper.loadImage;
import android.graphics.Bitmap;
import android.graphics.RectF;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.ArrayList;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public class MultiBoxTrackerTest {

    private MultiBoxTracker tracker;

    @Before
    public void setUp() {
        tracker = new MultiBoxTracker();
    }

    @Test
    public void trackerReturnsTheLastDetectedRect() throws Exception {
        final List<MLRecognition> objectDetectorResults = new ArrayList<>();
        final MLRecognition detectedRect = new MLRecognition("title", 0.8f, new RectF(100, 100, 200, 200));
        objectDetectorResults.add(detectedRect);

        Bitmap bitmap = loadImage("wagen_448.jpg");
        int mWidth = bitmap.getWidth();
        int mHeight = bitmap.getHeight();
        int[] rgb = new int[mWidth * mHeight];
        byte[] luminance = new byte[mWidth * mHeight];
        bitmap.getPixels(rgb, 0, mWidth, 0, 0, mWidth, mHeight);
        getLuminanceFromRGB(rgb, luminance, mWidth, mHeight);
        tracker.trackResults(objectDetectorResults, luminance, 0);

        MultiBoxTracker.TrackedRecognition trackedRecognition = tracker.publish().get(0);

        assertThat(trackedRecognition.getLocation()).isEqualTo(detectedRect.getLocation());
        assertThat(trackedRecognition.getTitle()).isEqualTo(detectedRect.getTitle());
        assertThat(trackedRecognition.getDetectionConfidence()).isEqualTo(detectedRect.getConfidence());
    }

    @Test
    public void trackerReturnsTheLastDetectedRectAgain() throws Exception {
        final List<MLRecognition> objectDetectorResults = new ArrayList<>();
        final MLRecognition detectedRect = new MLRecognition("title", 0.8f, new RectF(100, 100, 200, 200));
        objectDetectorResults.add(detectedRect);

        Bitmap bitmap = loadImage("wagen_448.jpg");
        int mWidth = bitmap.getWidth();
        int mHeight = bitmap.getHeight();
        int[] rgb = new int[mWidth * mHeight];
        byte[] luminance = new byte[mWidth * mHeight];
        bitmap.getPixels(rgb, 0, mWidth, 0, 0, mWidth, mHeight);
        getLuminanceFromRGB(rgb, luminance, mWidth, mHeight);
        tracker.trackResults(objectDetectorResults, luminance, 0);
        tracker.onFrame(mWidth, mHeight, 448, luminance, 1);
        tracker.onFrame(mWidth, mHeight, 448, luminance, 2);
        tracker.onFrame(mWidth, mHeight, 448, luminance, 3);

        MultiBoxTracker.TrackedRecognition trackedRecognition = tracker.publish().get(0);

        assertThat(trackedRecognition.getLocation()).isEqualTo(detectedRect.getLocation());
        assertThat(trackedRecognition.getTitle()).isEqualTo(detectedRect.getTitle());
        assertThat(trackedRecognition.getDetectionConfidence()).isEqualTo(detectedRect.getConfidence());
    }
}
