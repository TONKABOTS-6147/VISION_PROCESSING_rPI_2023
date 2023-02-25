// Copyright (c) FIRST and other WPILib contributors.
// Open Source Software; you can modify and/or share it under the terms of
// the WPILib BSD license file in the root directory of this project 

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
import edu.wpi.first.cscore.CvSource;
import edu.wpi.first.cscore.MjpegServer;
import edu.wpi.first.cscore.UsbCamera;
import edu.wpi.first.cscore.VideoSource;
import edu.wpi.first.networktables.NetworkTableEvent;
import edu.wpi.first.networktables.NetworkTableInstance;
import edu.wpi.first.vision.VisionPipeline;
import edu.wpi.first.vision.VisionThread;
import edu.wpi.first.wpilibj.smartdashboard.SmartDashboard;
import org.opencv.core.*;
import org.opencv.imgproc.*;

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
  // Original:
  private static Mat original;
  private static CvSource outputStream = CameraServer.putVideo("Processed Camera", 680, 480);
  private static CvSource outputStreamCube = CameraServer.putVideo("Processed Camera", 680, 480);

  public static CubePipeline cubePipeline = new CubePipeline();


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
  public static class CubePipeline implements VisionPipeline {

    //Outputs
    private Mat blurOutput = new Mat();
    private Mat hsvThresholdOutput = new Mat();
    private Mat cvErodeOutput = new Mat();
    private Mat cvDilateOutput = new Mat();
    private ArrayList<MatOfPoint> findContoursOutput = new ArrayList<MatOfPoint>();
    private ArrayList<MatOfPoint> filterContoursOutput = new ArrayList<MatOfPoint>();
  
    static {
      System.loadLibrary(Core.NATIVE_LIBRARY_NAME);
    }
  
    /**
     * This is the primary method that runs the entire pipeline and updates the outputs.
     */
    @Override	public void process(Mat source0) {
      // Step Blur0:
      Mat blurInput = source0;
      BlurType blurType = BlurType.get("Median Filter");
      double blurRadius = 5.662805540067656;
      blur(blurInput, blurType, blurRadius, blurOutput);
  
      // Step HSV_Threshold0:
      Mat hsvThresholdInput = blurOutput;
      double[] hsvThresholdHue = {110.07194784905415, 151.0385228179827};
      double[] hsvThresholdSaturation = {85.17471721275247, 204.02490267574584};
      double[] hsvThresholdValue = {150.69374088331952, 255.0};
      hsvThreshold(hsvThresholdInput, hsvThresholdHue, hsvThresholdSaturation, hsvThresholdValue, hsvThresholdOutput);
  
      // Step CV_erode0:
      Mat cvErodeSrc = hsvThresholdOutput;
      Mat cvErodeKernel = new Mat();
      Point cvErodeAnchor = new Point(-1, -1);
      double cvErodeIterations = 3.0;
      int cvErodeBordertype = Core.BORDER_CONSTANT;
      Scalar cvErodeBordervalue = new Scalar(-1);
      cvErode(cvErodeSrc, cvErodeKernel, cvErodeAnchor, cvErodeIterations, cvErodeBordertype, cvErodeBordervalue, cvErodeOutput);
  
      // Step CV_dilate0:
      Mat cvDilateSrc = cvErodeOutput;
      Mat cvDilateKernel = new Mat();
      Point cvDilateAnchor = new Point(-1, -1);
      double cvDilateIterations = 3.0;
      int cvDilateBordertype = Core.BORDER_CONSTANT;
      Scalar cvDilateBordervalue = new Scalar(-1);
      cvDilate(cvDilateSrc, cvDilateKernel, cvDilateAnchor, cvDilateIterations, cvDilateBordertype, cvDilateBordervalue, cvDilateOutput);
  
      // Step Find_Contours0:
      Mat findContoursInput = cvDilateOutput;
      boolean findContoursExternalOnly = false;
      findContours(findContoursInput, findContoursExternalOnly, findContoursOutput);
  
      // Step Filter_Contours0:
      ArrayList<MatOfPoint> filterContoursContours = findContoursOutput;
      double filterContoursMinArea = 565.0;
      double filterContoursMinPerimeter = 90.0;
      double filterContoursMinWidth = 24.0;
      double filterContoursMaxWidth = 1000.0;
      double filterContoursMinHeight = 24.0;
      double filterContoursMaxHeight = 1000;
      double[] filterContoursSolidity = {0.5380680781075285, 100};
      double filterContoursMaxVertices = 1000000;
      double filterContoursMinVertices = 0;
      double filterContoursMinRatio = 0.0;
      double filterContoursMaxRatio = 1000.0;
      filterContours(filterContoursContours, filterContoursMinArea, filterContoursMinPerimeter, filterContoursMinWidth, filterContoursMaxWidth, filterContoursMinHeight, filterContoursMaxHeight, filterContoursSolidity, filterContoursMaxVertices, filterContoursMinVertices, filterContoursMinRatio, filterContoursMaxRatio, filterContoursOutput);
  
    }
  
    /**
     * This method is a generated getter for the output of a Blur.
     * @return Mat output from Blur.
     */
    public Mat blurOutput() {
      return blurOutput;
    }
  
    /**
     * This method is a generated getter for the output of a HSV_Threshold.
     * @return Mat output from HSV_Threshold.
     */
    public Mat hsvThresholdOutput() {
      return hsvThresholdOutput;
    }
  
    /**
     * This method is a generated getter for the output of a CV_erode.
     * @return Mat output from CV_erode.
     */
    public Mat cvErodeOutput() {
      return cvErodeOutput;
    }
  
    /**
     * This method is a generated getter for the output of a CV_dilate.
     * @return Mat output from CV_dilate.
     */
    public Mat cvDilateOutput() {
      return cvDilateOutput;
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
     * An indication of which type of filter to use for a blur.
     * Choices are BOX, GAUSSIAN, MEDIAN, and BILATERAL
     */
    enum BlurType{
      BOX("Box Blur"), GAUSSIAN("Gaussian Blur"), MEDIAN("Median Filter"),
        BILATERAL("Bilateral Filter");
  
      private final String label;
  
      BlurType(String label) {
        this.label = label;
      }
  
      public static BlurType get(String type) {
        if (BILATERAL.label.equals(type)) {
          return BILATERAL;
        }
        else if (GAUSSIAN.label.equals(type)) {
        return GAUSSIAN;
        }
        else if (MEDIAN.label.equals(type)) {
          return MEDIAN;
        }
        else {
          return BOX;
        }
      }
  
      @Override
      public String toString() {
        return this.label;
      }
    }
  
    /**
     * Softens an image using one of several filters.
     * @param input The image on which to perform the blur.
     * @param type The blurType to perform.
     * @param doubleRadius The radius for the blur.
     * @param output The image in which to store the output.
     */
    private void blur(Mat input, BlurType type, double doubleRadius,
      Mat output) {
      int radius = (int)(doubleRadius + 0.5);
      int kernelSize;
      switch(type){
        case BOX:
          kernelSize = 2 * radius + 1;
          Imgproc.blur(input, output, new Size(kernelSize, kernelSize));
          break;
        case GAUSSIAN:
          kernelSize = 6 * radius + 1;
          Imgproc.GaussianBlur(input,output, new Size(kernelSize, kernelSize), radius);
          break;
        case MEDIAN:
          kernelSize = 2 * radius + 1;
          Imgproc.medianBlur(input, output, kernelSize);
          break;
        case BILATERAL:
          Imgproc.bilateralFilter(input, output, -1, radius, radius);
          break;
      }
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
     * Expands area of lower value in an image.
     * @param src the Image to erode.
     * @param kernel the kernel for erosion.
     * @param anchor the center of the kernel.
     * @param iterations the number of times to perform the erosion.
     * @param borderType pixel extrapolation method.
     * @param borderValue value to be used for a constant border.
     * @param dst Output Image.
     */
    private void cvErode(Mat src, Mat kernel, Point anchor, double iterations,
      int borderType, Scalar borderValue, Mat dst) {
      if (kernel == null) {
        kernel = new Mat();
      }
      if (anchor == null) {
        anchor = new Point(-1,-1);
      }
      if (borderValue == null) {
        borderValue = new Scalar(-1);
      }
      Imgproc.erode(src, dst, kernel, anchor, (int)iterations, borderType, borderValue);
    }
  
    /**
     * Expands area of higher value in an image.
     * @param src the Image to dilate.
     * @param kernel the kernel for dilation.
     * @param anchor the center of the kernel.
     * @param iterations the number of times to perform the dilation.
     * @param borderType pixel extrapolation method.
     * @param borderValue value to be used for a constant border.
     * @param dst Output Image.
     */
    private void cvDilate(Mat src, Mat kernel, Point anchor, double iterations,
    int borderType, Scalar borderValue, Mat dst) {
      if (kernel == null) {
        kernel = new Mat();
      }
      if (anchor == null) {
        anchor = new Point(-1,-1);
      }
      if (borderValue == null){
        borderValue = new Scalar(-1);
      }
      Imgproc.dilate(src, dst, kernel, anchor, (int)iterations, borderType, borderValue);
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


  public static class ConePipeline implements VisionPipeline {

    //Outputs
    private Mat blurOutput = new Mat();
    private Mat hsvThresholdOutput = new Mat();
    private Mat cvErodeOutput = new Mat();
    private Mat cvDilateOutput = new Mat();
    private ArrayList<MatOfPoint> findContoursOutput = new ArrayList<MatOfPoint>();
    private ArrayList<MatOfPoint> filterContoursOutput = new ArrayList<MatOfPoint>();
  
    static {
      System.loadLibrary(Core.NATIVE_LIBRARY_NAME);
    }
  
    /**
     * This is the primary method that runs the entire pipeline and updates the outputs.
     */
    @Override	public void process(Mat source0) {
      Main.cubePipeline.process(source0);
  
      // Step Blur0:
      Main.original = source0;
      Mat blurInput = source0;
      BlurType blurType = BlurType.get("Gaussian Blur");
      double blurRadius = 5.621523057119637;
      blur(blurInput, blurType, blurRadius, blurOutput);
  
      // Step HSV_Threshold0:
      Mat hsvThresholdInput = blurOutput;
      double[] hsvThresholdHue = {0.0, 28.802518867987356};
      double[] hsvThresholdSaturation = {94.1302177823957, 252.51340097941636};
      double[] hsvThresholdValue = {161.11506841619806, 255.0};
      hsvThreshold(hsvThresholdInput, hsvThresholdHue, hsvThresholdSaturation, hsvThresholdValue, hsvThresholdOutput);
  
      // Step CV_erode0:
      Mat cvErodeSrc = hsvThresholdOutput;
      Mat cvErodeKernel = new Mat();
      Point cvErodeAnchor = new Point(-1, -1);
      double cvErodeIterations = 4.0;
      int cvErodeBordertype = Core.BORDER_CONSTANT;
      Scalar cvErodeBordervalue = new Scalar(-1);
      cvErode(cvErodeSrc, cvErodeKernel, cvErodeAnchor, cvErodeIterations, cvErodeBordertype, cvErodeBordervalue, cvErodeOutput);
  
      // Step CV_dilate0:
      Mat cvDilateSrc = cvErodeOutput;
      Mat cvDilateKernel = new Mat();
      Point cvDilateAnchor = new Point(-1, -1);
      double cvDilateIterations = 4.0;
      int cvDilateBordertype = Core.BORDER_CONSTANT;
      Scalar cvDilateBordervalue = new Scalar(-1);
      cvDilate(cvDilateSrc, cvDilateKernel, cvDilateAnchor, cvDilateIterations, cvDilateBordertype, cvDilateBordervalue, cvDilateOutput);
  
      // Step Find_Contours0:
      Mat findContoursInput = cvDilateOutput;
      boolean findContoursExternalOnly = false;
      findContours(findContoursInput, findContoursExternalOnly, findContoursOutput);
  
      // Step Filter_Contours0:
      ArrayList<MatOfPoint> filterContoursContours = findContoursOutput;
      double filterContoursMinArea = 745.0;
      double filterContoursMinPerimeter = 80.0;
      double filterContoursMinWidth = 24.0;
      double filterContoursMaxWidth = 800.0;
      double filterContoursMinHeight = 24.0;
      double filterContoursMaxHeight = 2000.0;
      double[] filterContoursSolidity = {0.0, 100.0};
      double filterContoursMaxVertices = 1000000.0;
      double filterContoursMinVertices = 0.0;
      double filterContoursMinRatio = 0.0;
      double filterContoursMaxRatio = 1000.0;
      filterContours(filterContoursContours, filterContoursMinArea, filterContoursMinPerimeter, filterContoursMinWidth, filterContoursMaxWidth, filterContoursMinHeight, filterContoursMaxHeight, filterContoursSolidity, filterContoursMaxVertices, filterContoursMinVertices, filterContoursMinRatio, filterContoursMaxRatio, filterContoursOutput);
  
    }
  
    /**
     * This method is a generated getter for the output of a Blur.
     * @return Mat output from Blur.
     */
    public Mat blurOutput() {
      return blurOutput;
    }
  
    /**
     * This method is a generated getter for the output of a HSV_Threshold.
     * @return Mat output from HSV_Threshold.
     */
    public Mat hsvThresholdOutput() {
      return hsvThresholdOutput;
    }
  
    /**
     * This method is a generated getter for the output of a CV_erode.
     * @return Mat output from CV_erode.
     */
    public Mat cvErodeOutput() {
      return cvErodeOutput;
    }
  
    /**
     * This method is a generated getter for the output of a CV_dilate.
     * @return Mat output from CV_dilate.
     */
    public Mat cvDilateOutput() {
      return cvDilateOutput;
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
     * An indication of which type of filter to use for a blur.
     * Choices are BOX, GAUSSIAN, MEDIAN, and BILATERAL
     */
    enum BlurType{
      BOX("Box Blur"), GAUSSIAN("Gaussian Blur"), MEDIAN("Median Filter"),
        BILATERAL("Bilateral Filter");
  
      private final String label;
  
      BlurType(String label) {
        this.label = label;
      }
  
      public static BlurType get(String type) {
        if (BILATERAL.label.equals(type)) {
          return BILATERAL;
        }
        else if (GAUSSIAN.label.equals(type)) {
        return GAUSSIAN;
        }
        else if (MEDIAN.label.equals(type)) {
          return MEDIAN;
        }
        else {
          return BOX;
        }
      }
  
      @Override
      public String toString() {
        return this.label;
      }
    }
  
    /**
     * Softens an image using one of several filters.
     * @param input The image on which to perform the blur.
     * @param type The blurType to perform.
     * @param doubleRadius The radius for the blur.
     * @param output The image in which to store the output.
     */
    private void blur(Mat input, BlurType type, double doubleRadius,
      Mat output) {
      int radius = (int)(doubleRadius + 0.5);
      int kernelSize;
      switch(type){
        case BOX:
          kernelSize = 2 * radius + 1;
          Imgproc.blur(input, output, new Size(kernelSize, kernelSize));
          break;
        case GAUSSIAN:
          kernelSize = 6 * radius + 1;
          Imgproc.GaussianBlur(input,output, new Size(kernelSize, kernelSize), radius);
          break;
        case MEDIAN:
          kernelSize = 2 * radius + 1;
          Imgproc.medianBlur(input, output, kernelSize);
          break;
        case BILATERAL:
          Imgproc.bilateralFilter(input, output, -1, radius, radius);
          break;
      }
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
     * Expands area of lower value in an image.
     * @param src the Image to erode.
     * @param kernel the kernel for erosion.
     * @param anchor the center of the kernel.
     * @param iterations the number of times to perform the erosion.
     * @param borderType pixel extrapolation method.
     * @param borderValue value to be used for a constant border.
     * @param dst Output Image.
     */
    private void cvErode(Mat src, Mat kernel, Point anchor, double iterations,
      int borderType, Scalar borderValue, Mat dst) {
      if (kernel == null) {
        kernel = new Mat();
      }
      if (anchor == null) {
        anchor = new Point(-1,-1);
      }
      if (borderValue == null) {
        borderValue = new Scalar(-1);
      }
      Imgproc.erode(src, dst, kernel, anchor, (int)iterations, borderType, borderValue);
    }
  
    /**
     * Expands area of higher value in an image.
     * @param src the Image to dilate.
     * @param kernel the kernel for dilation.
     * @param anchor the center of the kernel.
     * @param iterations the number of times to perform the dilation.
     * @param borderType pixel extrapolation method.
     * @param borderValue value to be used for a constant border.
     * @param dst Output Image.
     */
    private void cvDilate(Mat src, Mat kernel, Point anchor, double iterations,
    int borderType, Scalar borderValue, Mat dst) {
      if (kernel == null) {
        kernel = new Mat();
      }
      if (anchor == null) {
        anchor = new Point(-1,-1);
      }
      if (borderValue == null){
        borderValue = new Scalar(-1);
      }
      Imgproc.dilate(src, dst, kernel, anchor, (int)iterations, borderType, borderValue);
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

  public static void main(String... args) {
    System.out.println("Main method running!");
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
      VisionThread visionThread = new VisionThread(cameras.get(0), new ConePipeline(), pipeline -> {
        if (!(pipeline.filterContoursOutput().size() == 0)) {
          MatOfPoint largestMatrix = pipeline.filterContoursOutput().get(0);
          for (MatOfPoint contour : pipeline.filterContoursOutput()) {
            if (Imgproc.contourArea(contour) > Imgproc.contourArea(largestMatrix)) {
              largestMatrix = contour;
            }
          }

          // Splits up the largest matrix into an array to be processed:
          MatOfPoint2f matrix = new MatOfPoint2f(largestMatrix.toArray());
          RotatedRect rect;
          rect = Imgproc.minAreaRect(matrix);
          matrix.release();
          Point[] boxPts = new Point[4];
          rect.points(boxPts);
          List<MatOfPoint> listMidContour = new ArrayList<MatOfPoint>();
          listMidContour.add(new MatOfPoint(boxPts[0], boxPts[1], boxPts[2], boxPts[3]));
          double angle = rect.angle;

          Rect rectB = Imgproc.boundingRect(largestMatrix);
          Point[] bBoxPts = { new Point(rectB.x, rectB.y), new Point(rectB.x + rectB.width, rectB.y), new Point(rectB.x + rectB.width, rectB.y + rectB.height), new Point(rectB.x, rectB.y + rectB.height) };
          List<MatOfPoint> bListMidContour = new ArrayList<MatOfPoint>();
          bListMidContour.add(new MatOfPoint(bBoxPts[0], bBoxPts[1], bBoxPts[2], bBoxPts[3]));
      
          Imgproc.polylines(Main.original /* the original image */,
                          listMidContour /* The points */,
                          true /* Is a Closed Polygon? */,
                          new Scalar(255, 0, 0), /* For RGB Values of Box */
                          1,
                          Imgproc.LINE_4 /* Line type */);
          Imgproc.polylines(Main.original, 
                          bListMidContour, 
                          true, 
                          new Scalar(0, 0, 255), 
                          1, 
                          Imgproc.LINE_4);

          Imgproc.circle(Main.original, new Point(rectB.x, rectB.y), 1, new Scalar(57, 255, 20));

          Main.outputStream.putFrame(Main.original);
          SmartDashboard.putNumber("Angle of Cone", angle);
          SmartDashboard.putNumber("Cone X", rect.center.x);
          SmartDashboard.putNumber("Cone Y", rect.center.y);
          SmartDashboard.putNumber("Cone Width", rect.size.width);
          SmartDashboard.putNumber("Cone Height", rect.size.height);
          SmartDashboard.putNumber("Cone Area", Imgproc.contourArea(largestMatrix));
          SmartDashboard.putBoolean("Cone in Vision", true);
        } else {
          SmartDashboard.putNumber("Angle of Cone", 0.0d);
          SmartDashboard.putNumber("Cone X", 0.0d);
          SmartDashboard.putNumber("Cone Y", 0.0d);
          SmartDashboard.putNumber("Cone Width", 0.0d);
          SmartDashboard.putNumber("Cone Height", 0.0d);
          SmartDashboard.putNumber("Cone Area", 0.0d);
          SmartDashboard.putBoolean("Cone in Vision", false);
          Main.outputStream.putFrame(Main.original);
        }
        
        if (!(Main.cubePipeline.filterContoursOutput().size() == 0)) {
          MatOfPoint largestMatrixCube = Main.cubePipeline.filterContoursOutput().get(0);
          for (MatOfPoint contour : Main.cubePipeline.filterContoursOutput()) {
            if (Imgproc.contourArea(contour) > Imgproc.contourArea(largestMatrixCube)) {
              largestMatrixCube = contour;
            }
          }

          MatOfPoint2f matrixCube = new MatOfPoint2f(largestMatrixCube.toArray());
          RotatedRect rectCube;
          rectCube = Imgproc.minAreaRect(matrixCube);
          matrixCube.release();
          Point[] boxPtsCube = new Point[4];
          rectCube.points(boxPtsCube);
          List<MatOfPoint> listMidContourCube = new ArrayList<MatOfPoint>();
          listMidContourCube.add(new MatOfPoint(boxPtsCube[0], boxPtsCube[1], boxPtsCube[2], boxPtsCube[3]));
          double angleCube = rectCube.angle;

          Rect rectBCube = Imgproc.boundingRect(largestMatrixCube);
          Point[] bBoxPtsCube = { new Point(rectBCube.x, rectBCube.y), new Point(rectBCube.x + rectBCube.width, rectBCube.y), new Point(rectBCube.x + rectBCube.width, rectBCube.y + rectBCube.height), new Point(rectBCube.x, rectBCube.y + rectBCube.height) };
          List<MatOfPoint> bListMidContourCube = new ArrayList<MatOfPoint>();
          bListMidContourCube.add(new MatOfPoint(bBoxPtsCube[0], bBoxPtsCube[1], bBoxPtsCube[2], bBoxPtsCube[3]));
      
          Imgproc.polylines(Main.original /* the original image */,
                          listMidContourCube /* The points */,
                          true /* Is a Closed Polygon? */,
                          new Scalar(255, 0, 0), /* For RGB Values of Box */
                          1,
                          Imgproc.LINE_4 /* Line type */);
          Imgproc.polylines(Main.original, 
                          bListMidContourCube, 
                          true, 
                          new Scalar(57, 255, 20), 
                          1, 
                          Imgproc.LINE_4);

          SmartDashboard.putNumber("Angle of Cube", angleCube);
          SmartDashboard.putNumber("Cube X", rectCube.center.x);
          SmartDashboard.putNumber("Cube Y", rectCube.center.y);                
          SmartDashboard.putBoolean("Cube in Vision", true);
          Main.outputStreamCube.putFrame(Main.original);
        } else {
          SmartDashboard.putNumber("Angle of Cube", 0.0d);
          SmartDashboard.putNumber("Cube X", 0.0d);
          SmartDashboard.putNumber("Cube Y", 0.0d);   
          SmartDashboard.putBoolean("Cube in Vision", false);
          Main.outputStreamCube.putFrame(Main.original);
        }
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