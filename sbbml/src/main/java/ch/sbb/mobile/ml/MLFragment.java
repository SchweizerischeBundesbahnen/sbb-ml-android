/*
 * Copyright 2022 SBB AG. License: CC0-1.0
 */
package ch.sbb.mobile.ml;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.fragment.app.Fragment;

import timber.log.Timber;

public class MLFragment extends Fragment {
  protected MLFragment(final MLSettings mlSettings) {
    this.mlSettings = mlSettings;
  }

  /**
   * Create a new instance.
   *
   * @param mlSettings settings.
   * @return instance.
   */
  public static MLFragment newInstance(final MLSettings mlSettings) {
    return new MLFragment(mlSettings);
  }

  public static final String TAG = "MLFragment";

  private MLSettings mlSettings;
  private MLView mlView;

  @Override
  public void onAttach(@NonNull Context context) {
    super.onAttach(context);
    if (context instanceof MLView.DetectionListener) {
      mlView = new MLView(context);
      mlView.onAttach((MLView.DetectionListener) context, mlSettings);
    } else {
      throw new ClassCastException(context.toString() + " must implemenet DetectionListener");
    }
  }

  @Override
  public void onDetach() {
    super.onDetach();
    mlView.onDetach();
    mlView = null;
  }

  @Override
  public View onCreateView(final LayoutInflater inflater, final ViewGroup container, final Bundle savedInstanceState) {
    return mlView.getRootView();
  }

  @Override
  public void onStart() {
    super.onStart();
    Timber.i("onStart");
    if (hasCameraPermission()) {
      mlView.startDetection();
    } else {
      requestPermission();
    }
  }

  @Override
  public void onStop() {
    super.onStop();
    mlView.onStop();
  }

  private boolean hasCameraPermission() {
    return ActivityCompat.checkSelfPermission(getContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
  }

  private void requestPermission() {
    mPermissionResult.launch(Manifest.permission.CAMERA);
  }

  private ActivityResultLauncher<String> mPermissionResult = registerForActivityResult(
      new ActivityResultContracts.RequestPermission(),
      new ActivityResultCallback<Boolean>() {
        @Override
        public void onActivityResult(Boolean result) {
          if (result) {
            mlView.startDetection();
            Timber.i("onActivityResult: PERMISSION GRANTED");
          } else {
            ((MLView.DetectionListener) getContext()).permissonDenied();
            Timber.i("onActivityResult: PERMISSION DENIED");
          }
        }
      }
  );

  /**
   * Update the ML settings.
   * <p>
   * Update may fail and the object detection does not start. In this case @see {@link ch.sbb.mobile.ml.MLView.DetectionListener#runError(String)}
   *
   * @param mlSettings new settings.
   */
  public void updateSettings(MLSettings mlSettings) {
    mlView.updateSettings(mlSettings);
  }
}
