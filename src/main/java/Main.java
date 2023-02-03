// Copyright (c) FIRST and other WPILib contributors.
// Open Source Software; you can modify and/or share it under the terms of
// the WPILib BSD license file in the root directory of this project.

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import edu.wpi.first.cameraserver.CameraServer;
import edu.wpi.first.cscore.MjpegServer;
import edu.wpi.first.cscore.UsbCamera;
import edu.wpi.first.cscore.VideoSource;
import edu.wpi.first.networktables.NetworkTableEvent;
import edu.wpi.first.networktables.NetworkTableInstance;
import edu.wpi.first.vision.VisionPipeline;
import edu.wpi.first.vision.VisionThread;

//import org.opencv.core.Mat;

//import edu.wpi.first.wpilibj.vision.VisionPipeline;

import org.opencv.core.*;
import org.opencv.core.Core.*;
//import org.opencv.features2d.FeatureDetector;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.*;
import org.opencv.objdetect.*;


/*
   JSON format:
   {
       "team": <team number>,
       "ntmode": <"client" or "server", "client" if unspecified>
       "cameras": [
           {
               "name": <camera name>
               "path": <path, e.g. "/dev/video0">
               "pixel format": <"MJPEG", "YUYV", etc>   // optional
               "width": <video mode width>              // optional
               "height": <video mode height>            // optional
               "fps": <video mode fps>                  // optional
               "brightness": <percentage brightness>    // optional
               "white balance": <"auto", "hold", value> // optional
               "exposure": <"auto", "hold", value>      // optional
               "properties": [                          // optional
                   {
                       "name": <property name>
                       "value": <property value>
                   }
               ],
               "stream": {                              // optional
                   "properties": [
                       {
                           "name": <stream property name>
                           "value": <stream property value>
                       }
                   ]
               }
           }
       ]
       "switched cameras": [
           {
               "name": <virtual camera name>
               "key": <network table key used for selection>
               // if NT value is a string, it's treated as a name
               // if NT value is a double, it's treated as an integer index
           }
       ]
   }
 */

public final class Main {
  private static String configFile = "/boot/frc.json";

  @SuppressWarnings("MemberName")
  public static class CameraConfig {
    public String name;
    public String path;
    public JsonObject config;
    public JsonElement streamConfig;
  }

  @SuppressWarnings("MemberName")
  public static class SwitchedCameraConfig {
    public String name;
    public String key;
  };

  public static int team;
  public static boolean server;
  public static List<CameraConfig> cameraConfigs = new ArrayList<>();
  public static List<SwitchedCameraConfig> switchedCameraConfigs = new ArrayList<>();
  public static List<VideoSource> cameras = new ArrayList<>();

  private Main() {
  }

  /**
   * Report parse error.
   */
  public static void parseError(String str) {
    System.err.println("config error in '" + configFile + "': " + str);
  }

  /**
   * Read single camera configuration.
   */
  public static boolean readCameraConfig(JsonObject config) {
    CameraConfig cam = new CameraConfig();

    // name
    JsonElement nameElement = config.get("name");
    if (nameElement == null) {
      parseError("could not read camera name");
      return false;
    }
    cam.name = nameElement.getAsString();

    // path
    JsonElement pathElement = config.get("path");
    if (pathElement == null) {
      parseError("camera '" + cam.name + "': could not read path");
      return false;
    }
    cam.path = pathElement.getAsString();

    // stream properties
    cam.streamConfig = config.get("stream");

    cam.config = config;

    cameraConfigs.add(cam);
    return true;
  }

  /**
   * Read single switched camera configuration.
   */
  public static boolean readSwitchedCameraConfig(JsonObject config) {
    SwitchedCameraConfig cam = new SwitchedCameraConfig();

    // name
    JsonElement nameElement = config.get("name");
    if (nameElement == null) {
      parseError("could not read switched camera name");
      return false;
    }
    cam.name = nameElement.getAsString();

    // path
    JsonElement keyElement = config.get("key");
    if (keyElement == null) {
      parseError("switched camera '" + cam.name + "': could not read key");
      return false;
    }
    cam.key = keyElement.getAsString();

    switchedCameraConfigs.add(cam);
    return true;
  }

  /**
   * Read configuration file.
   */
  @SuppressWarnings("PMD.CyclomaticComplexity")
  public static boolean readConfig() {
    // parse file
    JsonElement top;
    try {
      top = new JsonParser().parse(Files.newBufferedReader(Paths.get(configFile)));
    } catch (IOException ex) {
      System.err.println("could not open '" + configFile + "': " + ex);
      return false;
    }

    // top level must be an object
    if (!top.isJsonObject()) {
      parseError("must be JSON object");
      return false;
    }
    JsonObject obj = top.getAsJsonObject();

    // team number
    JsonElement teamElement = obj.get("team");
    if (teamElement == null) {
      parseError("could not read team number");
      return false;
    }
    team = teamElement.getAsInt();

    // ntmode (optional)
    if (obj.has("ntmode")) {
      String str = obj.get("ntmode").getAsString();
      if ("client".equalsIgnoreCase(str)) {
        server = false;
      } else if ("server".equalsIgnoreCase(str)) {
        server = true;
      } else {
        parseError("could not understand ntmode value '" + str + "'");
      }
    }

    // cameras
    JsonElement camerasElement = obj.get("cameras");
    if (camerasElement == null) {
      parseError("could not read cameras");
      return false;
    }
    JsonArray cameras = camerasElement.getAsJsonArray();
    for (JsonElement camera : cameras) {
      if (!readCameraConfig(camera.getAsJsonObject())) {
        return false;
      }
    }

    if (obj.has("switched cameras")) {
      JsonArray switchedCameras = obj.get("switched cameras").getAsJsonArray();
      for (JsonElement camera : switchedCameras) {
        if (!readSwitchedCameraConfig(camera.getAsJsonObject())) {
          return false;
        }
      }
    }

    return true;
  }

  /**
   * Start running the camera.
   */
  public static VideoSource startCamera(CameraConfig config) {
    System.out.println("Starting camera '" + config.name + "' on " + config.path);
    UsbCamera camera = new UsbCamera(config.name, config.path);
    MjpegServer server = CameraServer.startAutomaticCapture(camera);

    Gson gson = new GsonBuilder().create();

    camera.setConfigJson(gson.toJson(config.config));
    camera.setConnectionStrategy(VideoSource.ConnectionStrategy.kKeepOpen);

    if (config.streamConfig != null) {
      server.setConfigJson(gson.toJson(config.streamConfig));
    }

    return camera;
  }

  /**
   * Start running the switched camera.
   */
  public static MjpegServer startSwitchedCamera(SwitchedCameraConfig config) {
    System.out.println("Starting switched camera '" + config.name + "' on " + config.key);
    MjpegServer server = CameraServer.addSwitchedCamera(config.name);

    NetworkTableInstance inst = NetworkTableInstance.getDefault();
    inst.addListener(
        inst.getTopic(config.key),
        EnumSet.of(NetworkTableEvent.Kind.kImmediate, NetworkTableEvent.Kind.kValueAll),
        event -> {
          if (event.valueData != null) {
            if (event.valueData.value.isInteger()) {
              int i = (int) event.valueData.value.getInteger();
              if (i >= 0 && i < cameras.size()) {
                server.setSource(cameras.get(i));
              }
            } else if (event.valueData.value.isDouble()) {
              int i = (int) event.valueData.value.getDouble();
              if (i >= 0 && i < cameras.size()) {
                server.setSource(cameras.get(i));
              }
            } else if (event.valueData.value.isString()) {
              String str = event.valueData.value.getString();
              for (int i = 0; i < cameraConfigs.size(); i++) {
                if (str.equals(cameraConfigs.get(i).name)) {
                  server.setSource(cameras.get(i));
                  break;
                }
              }
            }
          }
        });

    return server;
  }

  /**
  * GripPipeline class.
  *
  * <p>An OpenCV pipeline generated by GRIP.
  *
  * @author GRIP
  */
  public static class GripPipeline implements VisionPipeline {

    //Outputs
    private Mat cvResizeOutput = new Mat();
    private Mat hsvThresholdOutput = new Mat();
    private ArrayList<MatOfPoint> findContoursOutput = new ArrayList<MatOfPoint>();
    private ArrayList<MatOfPoint> filterContoursOutput = new ArrayList<MatOfPoint>();
  
    static {
      System.loadLibrary(Core.NATIVE_LIBRARY_NAME);
    }
  
    /**
     * This is the primary method that runs the entire pipeline and updates the outputs.
     */
    @Override	public void process(Mat source0) {
      // Step CV_resize0:
      Mat cvResizeSrc = source0;
      Size cvResizeDsize = new Size(0, 0);
      double cvResizeFx = 0.25;
      double cvResizeFy = 0.25;
      int cvResizeInterpolation = Imgproc.INTER_LINEAR;
      cvResize(cvResizeSrc, cvResizeDsize, cvResizeFx, cvResizeFy, cvResizeInterpolation, cvResizeOutput);
  
      // Step HSV_Threshold0:
      Mat hsvThresholdInput = cvResizeOutput;
      double[] hsvThresholdHue = {0.0, 69.59815033562474};
      double[] hsvThresholdSaturation = {61.01534725064087, 244.88742097504866};
      double[] hsvThresholdValue = {203.1879162439407, 255.0};
      hsvThreshold(hsvThresholdInput, hsvThresholdHue, hsvThresholdSaturation, hsvThresholdValue, hsvThresholdOutput);
  
      // Step Find_Contours0:
      Mat findContoursInput = hsvThresholdOutput;
      boolean findContoursExternalOnly = true;
      findContours(findContoursInput, findContoursExternalOnly, findContoursOutput);
  
      // Step Filter_Contours0:
      ArrayList<MatOfPoint> filterContoursContours = findContoursOutput;
      double filterContoursMinArea = 100.0;
      double filterContoursMinPerimeter = 10.0;
      double filterContoursMinWidth = 10.0;
      double filterContoursMaxWidth = 1000.0;
      double filterContoursMinHeight = 0.0;
      double filterContoursMaxHeight = 1000;
      double[] filterContoursSolidity = {24.751143266925705, 100};
      double filterContoursMaxVertices = 1000000;
      double filterContoursMinVertices = 0;
      double filterContoursMinRatio = 0;
      double filterContoursMaxRatio = 1000;
      filterContours(filterContoursContours, filterContoursMinArea, filterContoursMinPerimeter, filterContoursMinWidth, filterContoursMaxWidth, filterContoursMinHeight, filterContoursMaxHeight, filterContoursSolidity, filterContoursMaxVertices, filterContoursMinVertices, filterContoursMinRatio, filterContoursMaxRatio, filterContoursOutput);
  
    }
  
    /**
     * This method is a generated getter for the output of a CV_resize.
     * @return Mat output from CV_resize.
     */
    public Mat cvResizeOutput() {
      return cvResizeOutput;
    }
  
    /**
     * This method is a generated getter for the output of a HSV_Threshold.
     * @return Mat output from HSV_Threshold.
     */
    public Mat hsvThresholdOutput() {
      return hsvThresholdOutput;
    }
  
    /**
     * This method is a generated getter for the output of a Find_Contours.
     * @return ArrayList<MatOfPoint> output from Find_Contours.
     */
    public ArrayList<MatOfPoint> findContoursOutput() {
      return findContoursOutput;
    }
  
    /**
     * This method is a generated getter for the output of a Filter_Contours.
     * @return ArrayList<MatOfPoint> output from Filter_Contours.
     */
    public ArrayList<MatOfPoint> filterContoursOutput() {
      return filterContoursOutput;
    }
  
  
    /**
     * Resizes an image.
     * @param src The image to resize.
     * @param dSize size to set the image.
     * @param fx scale factor along X axis.
     * @param fy scale factor along Y axis.
     * @param interpolation type of interpolation to use.
     * @param dst output image.
     */
    private void cvResize(Mat src, Size dSize, double fx, double fy, int interpolation,
      Mat dst) {
      if (dSize==null) {
        dSize = new Size(0,0);
      }
      Imgproc.resize(src, dst, dSize, fx, fy, interpolation);
    }
  
    /**
     * Segment an image based on hue, saturation, and value ranges.
     *
     * @param input The image on which to perform the HSL threshold.
     * @param hue The min and max hue
     * @param sat The min and max saturation
     * @param val The min and max value
     * @param output The image in which to store the output.
     */
    private void hsvThreshold(Mat input, double[] hue, double[] sat, double[] val,
        Mat out) {
      Imgproc.cvtColor(input, out, Imgproc.COLOR_BGR2HSV);
      Core.inRange(out, new Scalar(hue[0], sat[0], val[0]),
        new Scalar(hue[1], sat[1], val[1]), out);
    }
  
    /**
     * Sets the values of pixels in a binary image to their distance to the nearest black pixel.
     * @param input The image on which to perform the Distance Transform.
     * @param type The Transform.
     * @param maskSize the size of the mask.
     * @param output The image in which to store the output.
     */
    private void findContours(Mat input, boolean externalOnly,
      List<MatOfPoint> contours) {
      Mat hierarchy = new Mat();
      contours.clear();
      int mode;
      if (externalOnly) {
        mode = Imgproc.RETR_EXTERNAL;
      }
      else {
        mode = Imgproc.RETR_LIST;
      }
      int method = Imgproc.CHAIN_APPROX_SIMPLE;
      Imgproc.findContours(input, contours, hierarchy, mode, method);
    }
  
  
    /**
     * Filters out contours that do not meet certain criteria.
     * @param inputContours is the input list of contours
     * @param output is the the output list of contours
     * @param minArea is the minimum area of a contour that will be kept
     * @param minPerimeter is the minimum perimeter of a contour that will be kept
     * @param minWidth minimum width of a contour
     * @param maxWidth maximum width
     * @param minHeight minimum height
     * @param maxHeight maximimum height
     * @param Solidity the minimum and maximum solidity of a contour
     * @param minVertexCount minimum vertex Count of the contours
     * @param maxVertexCount maximum vertex Count
     * @param minRatio minimum ratio of width to height
     * @param maxRatio maximum ratio of width to height
     */
    private void filterContours(List<MatOfPoint> inputContours, double minArea,
      double minPerimeter, double minWidth, double maxWidth, double minHeight, double
      maxHeight, double[] solidity, double maxVertexCount, double minVertexCount, double
      minRatio, double maxRatio, List<MatOfPoint> output) {
      final MatOfInt hull = new MatOfInt();
      output.clear();
      //operation
      for (int i = 0; i < inputContours.size(); i++) {
        final MatOfPoint contour = inputContours.get(i);
        final Rect bb = Imgproc.boundingRect(contour);
        if (bb.width < minWidth || bb.width > maxWidth) continue;
        if (bb.height < minHeight || bb.height > maxHeight) continue;
        final double area = Imgproc.contourArea(contour);
        if (area < minArea) continue;
        if (Imgproc.arcLength(new MatOfPoint2f(contour.toArray()), true) < minPerimeter) continue;
        Imgproc.convexHull(contour, hull);
        MatOfPoint mopHull = new MatOfPoint();
        mopHull.create((int) hull.size().height, 1, CvType.CV_32SC2);
        for (int j = 0; j < hull.size().height; j++) {
          int index = (int)hull.get(j, 0)[0];
          double[] point = new double[] { contour.get(index, 0)[0], contour.get(index, 0)[1]};
          mopHull.put(j, 0, point);
        }
        final double solid = 100 * area / Imgproc.contourArea(mopHull);
        if (solid < solidity[0] || solid > solidity[1]) continue;
        if (contour.rows() < minVertexCount || contour.rows() > maxVertexCount)	continue;
        final double ratio = bb.width / (double)bb.height;
        if (ratio < minRatio || ratio > maxRatio) continue;
        output.add(contour);
      }
    }
  
  
  
  
  }
  

  /**
   * Example pipeline.
   */
  // public static class MyPipeline implements VisionPipeline {
  //   public int val;

  //   @Override
  //   public void process(Mat mat) {
  //     val += 1;
  //   }
  // }

  /**
   * Main.
   */
  public static void main(String... args) {
    if (args.length > 0) {
      configFile = args[0];
    }

    // read configuration
    if (!readConfig()) {
      return;
    }

    // start NetworkTables
    NetworkTableInstance ntinst = NetworkTableInstance.getDefault();
    if (server) {
      System.out.println("Setting up NetworkTables server");
      ntinst.startServer();
    } else {
      System.out.println("Setting up NetworkTables client for team " + team);
      ntinst.startClient4("wpilibpi");
      ntinst.setServerTeam(team);
      ntinst.startDSClient();
    }

    // start cameras
    for (CameraConfig config : cameraConfigs) {
      cameras.add(startCamera(config));
    }

    // start switched cameras
    for (SwitchedCameraConfig config : switchedCameraConfigs) {
      startSwitchedCamera(config);
    }

    // start image processing on camera 0 if present
    if (cameras.size() >= 1) {
      /*
      VisionThread visionThread = new VisionThread(cameras.get(0),
              new MyPipeline(), pipeline -> {
        // do something with pipeline results
      });
      */
      VisionThread visionThread = new VisionThread(cameras.get(0),
              new GripPipeline(), pipeline ->  {
        // do something with pipeline results 
      });
      visionThread.start();
    }

    // loop forever
    for (;;) {
      try {
        Thread.sleep(10000);
      } catch (InterruptedException ex) {
        return;
      }
    }
  }
}
