package ch.sbb.mobile.ml;

import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.InputStream;

public class TestHelper {

    public static Bitmap loadImage(String fileName) throws Exception {
        AssetManager assetManager = InstrumentationRegistry.getInstrumentation().getContext().getAssets();
        InputStream inputStream = assetManager.open(fileName);
        return BitmapFactory.decodeStream(inputStream);
    }

    public static void getLuminanceFromRGB(int[] rgb, byte[] luminanceOutArray, int width, int height) {
        for (int i = 0; i < width * height; i++) {
            float red = (rgb[i] >> 16) & 0xff;
            float green = (rgb[i] >> 8) & 0xff;
            float blue = (rgb[i]) & 0xff;
            int luminance = (int) ((0.257f * red) + (0.504f * green) + (0.098f * blue) + 16);
            luminanceOutArray[i] = (byte) (0xff & luminance);
        }
    }
}
