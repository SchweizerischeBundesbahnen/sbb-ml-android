package ch.sbb.mobile.ml.demo;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Paint.Cap;
import android.graphics.Paint.Join;
import android.graphics.Paint.Style;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;

import java.util.List;
import java.util.Locale;

import ch.sbb.mobile.ml.Recognition;
import timber.log.Timber;

class MultiBoxRenderer {
  private static final float TEXT_SIZE_DIP = 18;
  private final Paint boxPaint = new Paint();
  private final BorderedText borderedText;

  public MultiBoxRenderer(final Context context) {
    boxPaint.setColor(Color.RED);
    boxPaint.setStyle(Style.STROKE);
    boxPaint.setStrokeWidth(10.0f);
    boxPaint.setStrokeCap(Cap.ROUND);
    boxPaint.setStrokeJoin(Join.ROUND);
    boxPaint.setStrokeMiter(100);
    float textSizePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, TEXT_SIZE_DIP, context.getResources().getDisplayMetrics());
    borderedText = new BorderedText(textSizePx);
  }

  public void draw(final Canvas canvas, View view, List<Recognition> trackedObjects) {
    Timber.i("Draw number of objects: %d", trackedObjects.size());

    // draw objects
    for (final Recognition trackedPos : trackedObjects) {
      float cornerSize = Math.min(trackedPos.getLocation().width(), trackedPos.getLocation().height()) / 8.0f;
      canvas.drawRoundRect(trackedPos.getLocation(), cornerSize, cornerSize, boxPaint);

      final String labelString = String.format(Locale.ENGLISH,"%s %.2f", trackedPos.getTitle(), (100 * trackedPos.getConfidence()));
      borderedText.drawText(canvas, trackedPos.getLocation().left + cornerSize, trackedPos.getLocation().top, labelString + "%", boxPaint);
    }

    // this touch listener is a sample code, it just writes touched object name on logger.
    view.setOnTouchListener((v, event) -> {
      if(event.getAction() == MotionEvent.ACTION_DOWN) {
        for (final Recognition trackedPos : trackedObjects) {
          if(event.getX() > trackedPos.getLocation().left &&
             event.getX() < trackedPos.getLocation().right &&
             event.getY() > trackedPos.getLocation().top &&
             event.getY() < trackedPos.getLocation().bottom) {
            Timber.i("Object selected: %s", trackedPos.getTitle());
          }
        }
      }
      return false;
    });
  }
}
