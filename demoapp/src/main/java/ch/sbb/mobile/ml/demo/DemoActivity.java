/*
 * Copyright 2022 SBB AG. License: CC0-1.0
 */
package ch.sbb.mobile.ml.demo;

import android.graphics.Canvas;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Size;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;

import androidx.annotation.NonNull;
import androidx.fragment.app.FragmentActivity;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.slider.Slider;

import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.List;

import ch.sbb.mobile.ml.MLFragment;
import ch.sbb.mobile.ml.MLView;
import ch.sbb.mobile.ml.MLRecognition;
import ch.sbb.mobile.ml.MLSettings;
import ch.sbb.mobile.ml.demo.databinding.CameraViewBinding;
import timber.log.Timber;

/*
  This is a simple demo app to demonstrate how to integrate the inference library to an app.
  It will let you to play with the inference settings. It shows also how to use output objects
  and draw them on the screen including a touch interface. The app includes several ML models
  in assets.
 */
public class DemoActivity extends FragmentActivity implements MLView.DetectionListener {
    private static final String MODEL_PREFIX = "wagen";
  private final List<String> processorStrings = Arrays.asList("CPU", "GPU", "NNAPI");
  private final List<String> modelStrings = Arrays.asList("yolov5n", "yolov5s6");
  private final List<String> typeStrings = Arrays.asList("float32", "int8");
  private final List<String> imgSizeStrings = Arrays.asList("576", "512", "448", "384");

  protected int defaultDeviceIndex = 0;
  protected int defaultTypeIndex = 0;
  protected int defaultImgSizeIndex = 0;
  private long lastUpdateTimestamp = 0;
  private int numUpdatesPerSec = 0;
  private int inferenceTimeTotalMillis = 0;

  private static final Size DESIRED_PREVIEW_SIZE_BIG = new Size(1280, 720);
  private static final Size DESIRED_PREVIEW_SIZE_MEDIUM = new Size(640, 480);

  private CameraViewBinding binding;
  private BottomSheetBehavior<LinearLayout> sheetBehavior;
  private MLFragment mlFragment;
  private MLSettings mlSettings;
  private MultiBoxRenderer multiBoxRenderer;
  
  AdapterView.OnItemClickListener updateClickListener = (parent, view, position, id) -> updateSettings();

  @Override
  protected void onCreate(final Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    if(savedInstanceState == null) {
      binding = CameraViewBinding.inflate(getLayoutInflater());
      setContentView(binding.getRoot());

      getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

      binding.bottomSheet.deviceList.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
      ArrayAdapter<String> deviceAdapter = new ArrayAdapter<>(this , R.layout.deviceview_row, R.id.deviceview_row_text, processorStrings);
      binding.bottomSheet.deviceList.setAdapter(deviceAdapter);
      binding.bottomSheet.deviceList.setItemChecked(defaultDeviceIndex, true);
      binding.bottomSheet.deviceList.setOnItemClickListener(updateClickListener);

      binding.bottomSheet.modelList.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
      ArrayAdapter<String> modelAdapter = new ArrayAdapter<>(this , R.layout.deviceview_row, R.id.deviceview_row_text, modelStrings);
      binding.bottomSheet.modelList.setAdapter(modelAdapter);
      binding.bottomSheet.modelList.setItemChecked(defaultDeviceIndex, true);
      binding.bottomSheet.modelList.setOnItemClickListener(updateClickListener);

      binding.bottomSheet.typeList.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
      ArrayAdapter<String> typeAdapter =
              new ArrayAdapter<>(this , R.layout.typeview_row, R.id.typeview_row_text, typeStrings);
      binding.bottomSheet.typeList.setAdapter(typeAdapter);
      binding.bottomSheet.typeList.setItemChecked(defaultTypeIndex, true);
      binding.bottomSheet.typeList.setOnItemClickListener(updateClickListener);

      binding.bottomSheet.imageSizeList.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
      ArrayAdapter<String> imgSizeAdapter =
              new ArrayAdapter<>(this , R.layout.imgsizeview_row, R.id.imgsizeview_row_text, imgSizeStrings);
      binding.bottomSheet.imageSizeList.setAdapter(imgSizeAdapter);
      binding.bottomSheet.imageSizeList.setItemChecked(defaultImgSizeIndex, true);
      binding.bottomSheet.imageSizeList.setOnItemClickListener(updateClickListener);

      ViewTreeObserver vto = binding.mlfragmentContainer.getViewTreeObserver();
      vto.addOnGlobalLayoutListener(
              new ViewTreeObserver.OnGlobalLayoutListener() {
                @Override
                public void onGlobalLayout() {
                  binding.bottomSheet.gestureLayout.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                  int height = binding.bottomSheet.gestureLayout.getMeasuredHeight();
                  sheetBehavior.setPeekHeight(height);
                }
              });

      sheetBehavior = BottomSheetBehavior.from(binding.bottomSheet.bottomSheetLayout);
      sheetBehavior.setHideable(true);
      sheetBehavior.addBottomSheetCallback(
              new BottomSheetBehavior.BottomSheetCallback() {
                @Override
                public void onStateChanged(@NonNull View bottomSheet, int newState) {
                  switch (newState) {
                      case BottomSheetBehavior.STATE_EXPANDED:
                      {
                          binding.bottomSheet.bottomSheetArrow.setImageResource(R.drawable.icn_chevron_down);
                      }
                      break;
                      case BottomSheetBehavior.STATE_COLLAPSED:
                      case BottomSheetBehavior.STATE_SETTLING:
                      {
                      binding.bottomSheet.bottomSheetArrow.setImageResource(R.drawable.icn_chevron_up);
                      }
                      break;
                      case BottomSheetBehavior.STATE_HIDDEN:
                      case BottomSheetBehavior.STATE_DRAGGING:
                      break;
                  }
                }

                @Override
                public void onSlide(@NonNull View bottomSheet, float slideOffset) {}
              });

      binding.bottomSheet.threadsSlider.addOnSliderTouchListener(new Slider.OnSliderTouchListener() {
        @Override
        public void onStartTrackingTouch(@NonNull @NotNull Slider slider) {
        }

        @Override
        public void onStopTrackingTouch(@NonNull @NotNull Slider slider) {
          mlSettings.setIou(slider.getValue());
          binding.bottomSheet.threadsText.setText("Threads " + (int)slider.getValue());
          updateSettings();
        }
      });

      binding.bottomSheet.iouSlider.addOnSliderTouchListener(new Slider.OnSliderTouchListener() {
        @Override
        public void onStartTrackingTouch(@NonNull @NotNull Slider slider) {
        }

        @Override
        public void onStopTrackingTouch(@NonNull @NotNull Slider slider) {
          mlSettings.setIou(slider.getValue());
          binding.bottomSheet.iouText.setText("Iou " + slider.getValue());
          updateSettings();
        }
      });

      binding.bottomSheet.confSlider.addOnSliderTouchListener(new Slider.OnSliderTouchListener() {
        @Override
        public void onStartTrackingTouch(@NonNull @NotNull Slider slider) {
        }

        @Override
        public void onStopTrackingTouch(@NonNull @NotNull Slider slider) {
          mlSettings.setMinimumConfidence(slider.getValue());
          binding.bottomSheet.confText.setText("Confidence " + slider.getValue());
          updateSettings();
        }
      });

      binding.bottomSheet.trackerSwitch.setOnCheckedChangeListener((compoundButton, b) -> updateSettings());

      binding.bottomSheet.confText.setText("Confidence " + binding.bottomSheet.confSlider.getValue());
      binding.bottomSheet.iouText.setText("Iou " + binding.bottomSheet.iouSlider.getValue());
      binding.bottomSheet.threadsText.setText("Threads " + (int)binding.bottomSheet.threadsSlider.getValue());

      multiBoxRenderer = new MultiBoxRenderer(this);

      String filename = getModelFilename(modelStrings.getFirst(), typeStrings.getFirst(), imgSizeStrings.getFirst());
      mlSettings = new MLSettings(DESIRED_PREVIEW_SIZE_BIG, filename, Integer.parseInt(imgSizeStrings.getFirst()));
      binding.bottomSheet.modelInfo.setText(filename);

      setFragment();
    }
  }

  public String getModelFilename(final String model, final String type, final String inputSize) {
    String modelFilename = MODEL_PREFIX;
    modelFilename += "_" + model;
    modelFilename += "_" + inputSize;
    modelFilename += "_" + type;
    modelFilename += ".tflite";

    Timber.i("Model filename %s",  modelFilename);
    return modelFilename;
  }

  // open the ML fragment
  protected void setFragment() {
      mlFragment = MLFragment.newInstance(mlSettings);
      getSupportFragmentManager().beginTransaction().replace(R.id.mlfragment_container, mlFragment, MLFragment.TAG).commitNow();
  }

  void updateFrameTime() {
    final long updateTime = SystemClock.uptimeMillis() - lastUpdateTimestamp;
    inferenceTimeTotalMillis += (int) updateTime;
    numUpdatesPerSec++;

    if(inferenceTimeTotalMillis > 1000) {
      binding.bottomSheet.frameupdateInfo.setText(String.valueOf(numUpdatesPerSec));
      lastUpdateTimestamp = 0;
      inferenceTimeTotalMillis = 0;
      numUpdatesPerSec = 0;
    }
    lastUpdateTimestamp = SystemClock.uptimeMillis();
  }

  protected void updateSettings() {
    int id = binding.bottomSheet.imageSizeList.getCheckedItemPosition();
    int inputSize = Integer.parseInt((String)binding.bottomSheet.imageSizeList.getItemAtPosition(id));
    mlSettings.setModelInputSize(inputSize);
    if(inputSize <= DESIRED_PREVIEW_SIZE_MEDIUM.getHeight()) {
      mlSettings.setDesirePreviewSize(DESIRED_PREVIEW_SIZE_MEDIUM);
    } else {
      mlSettings.setDesirePreviewSize(DESIRED_PREVIEW_SIZE_BIG);
    }

    mlSettings.setNumberOfThreds((int)(binding.bottomSheet.threadsSlider.getValue()));
    id = binding.bottomSheet.deviceList.getCheckedItemPosition();
    String deviceString = binding.bottomSheet.deviceList.getItemAtPosition(id).toString();
      switch (deviceString) {
          case "GPU" -> mlSettings.setProcessor(MLSettings.Processor.GPU);
          case "NNAPI" -> mlSettings.setProcessor(MLSettings.Processor.NNAPI);
          case "CPU" -> mlSettings.setProcessor(MLSettings.Processor.CPU);
      }
    id = binding.bottomSheet.typeList.getCheckedItemPosition();
    String typeString = binding.bottomSheet.typeList.getItemAtPosition(id).toString();

    id = binding.bottomSheet.modelList.getCheckedItemPosition();
    String modelString = binding.bottomSheet.modelList.getItemAtPosition(id).toString();

    String filename = getModelFilename(modelString, typeString, String.valueOf(inputSize));
    mlSettings.setModelFilename(filename);
    binding.bottomSheet.modelInfo.setText(filename);

    mlSettings.setMinimumConfidence(binding.bottomSheet.confSlider.getValue());
    mlSettings.setIou(binding.bottomSheet.iouSlider.getValue());
    mlSettings.setUseTracker(binding.bottomSheet.trackerSwitch.isChecked());

    runError(""); // clear last error msg on ui
    mlFragment.updateSettings(mlSettings);
  }

  @Override
  public void previewSize(Size previewSize) {
    runOnUiThread(() -> binding.bottomSheet.frameInfo.setText(previewSize.toString()));
  }

  @Override
  public void inputSize(Size inputSize) {
    runOnUiThread(() -> binding.bottomSheet.scaleInfo.setText(inputSize.toString()));
  }

  @Override
  public void inferenceTime(int inferenceTime) {
    runOnUiThread(() -> binding.bottomSheet.inferenceInfo.setText(String.valueOf(inferenceTime)));
  }

  @Override
  public void drawObjects(Canvas canvas, View view, List<MLRecognition> objects) {
    updateFrameTime();
    multiBoxRenderer.draw(canvas, view, objects);
  }

  @Override
  public void permissionDenied() {
    // the developer can request camera permission in the app code.
    // this method is used to inform user about missing camera permission, the inference module cannot run.
  }

  @Override
  public void runError(String errorMsg) {
    runOnUiThread(() -> binding.bottomSheet.errorInfo.setText(errorMsg));
  }
}
