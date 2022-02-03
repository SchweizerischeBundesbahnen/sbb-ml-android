/*
 * Copyright 2022 SBB AG. License: CC0-1.0
 */
package ch.sbb.mobile.ml;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.os.Trace;
import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.gpu.CompatibilityList;
import org.tensorflow.lite.gpu.GpuDelegate;
import org.tensorflow.lite.nnapi.NnApiDelegate;
import org.tensorflow.lite.support.image.TensorImage;
import org.tensorflow.lite.support.metadata.MetadataExtractor;
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import timber.log.Timber;
import static java.lang.Math.min;

class TFLiteObjectDetector {

  private final List<String> labels = new ArrayList<>();
  private final List<String> output_order = new ArrayList<>();
  private final List<String> input_order = new ArrayList<>();
  private float[][][] outputLocations;
  private float[][] outputClasses;
  private float[][] outputScores;
  private float[] numDetections;
  private int maxNumberOfOutput;

  private Interpreter tfLite;
  private GpuDelegate gpuDelegate;
  private NnApiDelegate nnApiDelegate;
  private final MLSettings mlSettings;


  public TFLiteObjectDetector(final Context context, MLSettings mlSettings) throws IOException {
    this.mlSettings = mlSettings;
    loadModel(context, mlSettings);
  }

  private MappedByteBuffer loadModelFile(AssetManager assets) throws IOException {
    AssetFileDescriptor fileDescriptor = assets.openFd(mlSettings.getModelFilename());
    FileInputStream inputStream = new FileInputStream(fileDescriptor.getFileDescriptor());
    FileChannel fileChannel = inputStream.getChannel();
    long startOffset = fileDescriptor.getStartOffset();
    long declaredLength = fileDescriptor.getDeclaredLength();
    return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
  }

  private void loadModel(final Context context, final MLSettings mlSettings) throws IOException {
    if (gpuDelegate != null) {
      gpuDelegate.close();
      gpuDelegate = null;
    }

    if(nnApiDelegate != null) {
      nnApiDelegate.close();
      nnApiDelegate = null;
    }

    if (tfLite != null) {
      tfLite.close();
    }

    MappedByteBuffer modelFile = loadModelFile(context.getAssets());
    MetadataExtractor metadataExtractor = new MetadataExtractor(modelFile);

    InputStream labelsInputStream = metadataExtractor.getAssociatedFile("labels.txt");
    InputStreamReader labelsInputStreamReader = new InputStreamReader(labelsInputStream);
    try (BufferedReader br = new BufferedReader(labelsInputStreamReader)) {
      String line;
      while ((line = br.readLine()) != null) {
        Timber.i(line);
        labels.add(line);
      }
    }

    int numberInput = metadataExtractor.getInputTensorCount();
    for(int i = 0; i < numberInput; i++) {
      String inputName = metadataExtractor.getInputTensorMetadata(i).name();
      input_order.add(inputName);
    }

    int numberOutput = metadataExtractor.getOutputTensorCount();
    for (int i = 0; i < numberOutput; i++) {
      String outputName = metadataExtractor.getOutputTensorMetadata(i).name();
      output_order.add(outputName);
    }

    maxNumberOfOutput = Arrays.stream(metadataExtractor.getOutputTensorShape(0)).max().getAsInt();


    try {
      Interpreter.Options options = new Interpreter.Options();
      options.setNumThreads(mlSettings.getNumberOfThreds());

      if(mlSettings.getProcessor() == MLSettings.Processor.CPU) {
        options = getCPUModeOptions();
      } else if(mlSettings.getProcessor() == MLSettings.Processor.GPU) {
        CompatibilityList compatList = new CompatibilityList();
        if(compatList.isDelegateSupportedOnThisDevice()){
          GpuDelegate.Options delegateOptions = compatList.getBestOptionsForThisDevice();
          gpuDelegate = new GpuDelegate(delegateOptions);
          options.addDelegate(gpuDelegate);
          Timber.i("GPU Delegate %s", delegateOptions.toString());
        } else {
          throw new IOException("Cannot init delegate");
        }
      } else if(mlSettings.getProcessor() == MLSettings.Processor.NNAPI) {
        NnApiDelegate.Options nnapi_options = new NnApiDelegate.Options();
        nnapi_options.setExecutionPreference(1);
        nnapi_options.setAllowFp16(true);
        nnApiDelegate = new NnApiDelegate(nnapi_options);
        options.addDelegate(nnApiDelegate);
      }
      tfLite = new Interpreter(modelFile, options);
    } catch (Exception e) {
      if(mlSettings.isUseCPUBackup()) {
        tfLite = new Interpreter(modelFile, getCPUModeOptions());
      } else {
        Timber.e("Cannot init delegate: %s ", e.toString());
        throw new IOException("Cannot init delegate");
      }
    }

    Timber.i("Interepreter created for model %s", mlSettings.getModelFilename());
    outputLocations = new float[1][maxNumberOfOutput][4];
    outputClasses = new float[1][maxNumberOfOutput];
    outputScores = new float[1][maxNumberOfOutput];
    numDetections = new float[1];
  }

  private Interpreter.Options getCPUModeOptions() {
    Interpreter.Options options = new Interpreter.Options();
    options.setNumThreads(mlSettings.getNumberOfThreds());
    options.setUseXNNPACK(true);
    return options;
  }
  
  public List<Recognition> recognizeImage(final Bitmap bitmap) throws IOException {
    Trace.beginSection("recognizeImage");
    TensorImage tensorImage = new TensorImage(DataType.FLOAT32);
    tensorImage.load(bitmap);

    Object[] inputImageAsArray = new Object[input_order.size()];
    for(int i = 0; i < input_order.size(); i++) {
      switch (input_order.get(i)) {
        case "image": inputImageAsArray[i] = tensorImage.getBuffer(); break;
        case "iou threshold": inputImageAsArray[i] = mlSettings.getIou(); break;
        case "conf threshold": inputImageAsArray[i] = mlSettings.getMinimumConfidence(); break;
      }
    }

    Map<Integer, Object> outputMap = new HashMap<>();
    outputMap.put(output_order.indexOf("location"), outputLocations);
    outputMap.put(output_order.indexOf("category"), outputClasses);
    outputMap.put(output_order.indexOf("score"), outputScores);
    outputMap.put(output_order.indexOf("number of detections"), numDetections);
    Trace.endSection();

    Trace.beginSection("run");
    try {
      tfLite.runForMultipleInputsOutputs(inputImageAsArray, outputMap);
    } catch (Exception e) {
      Timber.e("Failed to run the model " + e.toString());
      Trace.endSection();
      throw new IOException("failed to run: " + e.toString());
    }
    Trace.endSection();

    int numDetectionsOutput = min(maxNumberOfOutput, (int) numDetections[0]);
    final ArrayList<Recognition> recognitions = new ArrayList<>(numDetectionsOutput);
    for (int i = 0; i < numDetectionsOutput; ++i) {
      final RectF detection = new RectF(
              outputLocations[0][i][1] * mlSettings.getModelInputSize(),
              outputLocations[0][i][0] * mlSettings.getModelInputSize(),
              outputLocations[0][i][3] * mlSettings.getModelInputSize(),
              outputLocations[0][i][2] * mlSettings.getModelInputSize());

      int labelIndex = (int) outputClasses[0][i];
      if(labelIndex < labels.size()) {
        recognitions.add( new Recognition(labels.get(labelIndex), outputScores[0][i], detection));
      }
    }
    Trace.endSection(); // "recognizeImage"
    return recognitions;
  }
}
