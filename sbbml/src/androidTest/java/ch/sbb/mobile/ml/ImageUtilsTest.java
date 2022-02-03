package ch.sbb.mobile.ml;

import static com.google.common.truth.Truth.assertThat;
import android.graphics.Matrix;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class ImageUtilsTest  {

    @Test
    public void transformation1() {
        Matrix transformationMatrix = ImageUtils.getTransformationMatrix(100,200,200,400, 0, true);
        float f[] = {2.0f, 0.0f, 0.0f, 0.0f, 2.0f, 0.0f, 0.0f, 0.0f, 1.0f};
        Matrix matrix = new Matrix();
        matrix.setValues(f);
        assertThat(transformationMatrix).isEqualTo(matrix);
    }

    @Test
    public void transformation2() {
        Matrix transformationMatrix = ImageUtils.getTransformationMatrix(100,200,200,400, 0, false);
        float f[] = {2.0f, 0.0f, 0.0f, 0.0f, 2.0f, 0.0f, 0.0f, 0.0f, 1.0f};
        Matrix matrix = new Matrix();
        matrix.setValues(f);
        assertThat(transformationMatrix).isEqualTo(matrix);
    }
}
