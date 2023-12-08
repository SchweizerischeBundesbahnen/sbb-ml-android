# SBB ML Android Lib

[![GPLv3 license](https://img.shields.io/badge/License-MIT-blue.svg)](https://spdx.org/licenses/MIT.html)

This library simplifies the integration of object detection models into Android App. It reads an object detection model from a file inside assets folder and initialises object detection algorithm with given settings. A camera preview fragment is shown to user and camera images are taken as an input to the algorithm. As an output it publishes detected objects, which you can then draw over the camera preview or use for further app logic. All this is inside a single Android fragment. The fragment is shown according to layout definitions which you define in your app.

In addition to object detection there is also a object tracker algorithm. The tracker is a fast C++ optimised algorithm which is able to follow an object if the camera moves only slightly. The tracker smooths object detection results because object position is tracked several times while the object detection is done only once at the same time. It is possible to enable/disable the tracker in settings.

This project also contains a sample app which demos how the library works. It has also several sample object detection models. A model is selected based on user chosen settings on App UI. The sample model detect typical objects inside a train wagon.

## Gradle

implementation 'ch.sbb.mobile.ml:sbbml:1.0.5'

Check the latest version at https://repo1.maven.org/maven2/ch/sbb/mobile/ml/sbbml/
 
## Minimum supported Android Version

* Android API Level 25

## Getting started

Copy your object detection ML model to assets folder. The main components are called MLSettings, MLFragment and MLRecognition. Initialise MLSettings with your parameters, the default settings are a good starting point. Create MLFragment with the settings and make it visible in your app. When the MLFragment is visible then the object detection starts already running. MLFragment has DetectionListener interface which gives all the information about detected objects as MLRecognition. You can then mark the objects on camera preview screen. See the DemoApp how or create your own rendering style.

If you create an Android app and publish it then you may want to test with many different devices. Low-end and high-end Android devices have a huge difference in performance and you have to solve it in your app. The DetectionListener interface gives you the inference time of each image. Use it to check how fast the app is. If the time is more than 500ms then it would be better to switch to a smaller ML model or use different input size. So it would be good to prepare your app with couple of different ML models and switch them on the fly based on inference time. 

It is best to test the demo app object detection performance in a real environment where there are objects of SBB, like in SBB trains or at train stations. If you test e.g. in your living room you will just get false detected objects. The object detection works in the target environment where it was designed to work. You may also get bad results if you open images of SBB objects on your computer screen and try to detected them due to flicker effect on the screen.    

## ML Models 

The inference lib was developed to run Yolo ML models on Android in optimal way. The demo app contains several different size models so one can test performance. 

See more about the models in SBB ML Models project: https://github.com/SchweizerischeBundesbahnen/sbb-ml-models
You can create your own Yolo model with your data and there is a tool to convert it to the format suitable for this infrence library.

## Known issues

* When running in CPU then the device gets warm. A warm Android device starts limiting CPU power and thus the inference time increase.
* On NNAPI we see often very similar performance as with CPU. 
* GPU may accelerate inference time a lot but it depends on your device. On some device GPU inference is fast but it blocks the UI thread, so it does not look nice on UI. This is a known issue for Tensorflow team: https://github.com/tensorflow/tensorflow/issues/43073 
* Some Pixel devices don't let GPU to be used for inferencing.

## Getting help

If you need help, you can reach out to us by e-mail: [mobile@sbb.ch](mailto:mobile@sbb.ch?subject=[GitHub]%20Android%20ML%20Lib)

## Getting involved

We are welcoming contributions improving the inference library. 

General instructions on _how_ to contribute can be found under [Contributing](Contributing.md).

## Authors

* **Henrik Karppinen**

## License

Code released under the [MIT](LICENSE).
