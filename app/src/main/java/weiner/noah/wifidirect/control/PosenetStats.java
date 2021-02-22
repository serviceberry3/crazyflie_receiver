package weiner.noah.wifidirect.control;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.core.util.Pair;

import org.opencv.calib3d.Calib3d;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfDouble;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.MatOfPoint3f;
import org.opencv.core.Point;
import org.opencv.core.Point3;
import org.tensorflow.lite.examples.noah.lib.BodyPart;
import org.tensorflow.lite.examples.noah.lib.KeyPoint;
import org.tensorflow.lite.examples.noah.lib.Person;
import org.tensorflow.lite.examples.noah.lib.Posenet;
import org.tensorflow.lite.examples.noah.lib.Position;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Semaphore;

import weiner.noah.wifidirect.Constants;
import weiner.noah.wifidirect.utils.ImageUtils;

public class PosenetStats {
    private Posenet posenet;
    private MainActivity mainActivity;
    private final String TAG = "PosenetStats";

    public PosenetStats(Posenet posenet, MainActivity mainActivity) {
        this.posenet = posenet;
        this.mainActivity = mainActivity;

        //On construction, we'd like to launch a background thread which runs Posenet on incoming images from front-facing camera,
        //and allows polling of the data (distance from human, angle of human, etc)

    }

    private class PosenetLiveStatFeed implements Runnable {
        /** List of body joints that should be connected.    */
        ArrayList<Pair> bodyJoints = new ArrayList<Pair>(
                Arrays.asList(new Pair(BodyPart.LEFT_WRIST, BodyPart.LEFT_ELBOW),
                        new Pair(BodyPart.LEFT_ELBOW, BodyPart.LEFT_SHOULDER),
                        new Pair(BodyPart.LEFT_SHOULDER, BodyPart.RIGHT_SHOULDER),
                        new Pair(BodyPart.RIGHT_SHOULDER, BodyPart.RIGHT_ELBOW),
                        new Pair(BodyPart.RIGHT_ELBOW, BodyPart.RIGHT_WRIST),
                        new Pair(BodyPart.LEFT_SHOULDER, BodyPart.LEFT_HIP),
                        new Pair(BodyPart.LEFT_HIP, BodyPart.RIGHT_HIP),
                        new Pair(BodyPart.RIGHT_HIP, BodyPart.RIGHT_SHOULDER),
                        new Pair(BodyPart.LEFT_HIP, BodyPart.LEFT_KNEE),
                        new Pair(BodyPart.LEFT_KNEE, BodyPart.LEFT_ANKLE),
                        new Pair(BodyPart.RIGHT_HIP, BodyPart.RIGHT_KNEE),
                        new Pair(BodyPart.RIGHT_KNEE, BodyPart.RIGHT_ANKLE)));

        /** Threshold for confidence score. */
        private double minConfidence = 0.5;

        /** Radius of circle used to draw keypoints.  */
        private float circleRadius = 8.0f;

        /** Paint class holds the style and color information to draw geometries,text and bitmaps. */
        private Paint redPaint = new Paint();
        private Paint bluePaint = new Paint();
        private Paint greenPaint = new Paint();
        private Paint whitePaint = new Paint();

        /** A shape for extracting frame data.   */
        private int PREVIEW_WIDTH = 640;
        private int PREVIEW_HEIGHT = 480;
        public static final String ARG_MESSAGE = "message";


        //Macros for 'looking' variable
        private final int LOOKING_LEFT = 0;
        private final int LOOKING_RIGHT = 1;

        /**
         * Tag for the [Log].
         */
        private String TAG = "PosenetActivity";

        private String FRAGMENT_DIALOG = "dialog";

        //Whether to use front- or rear-facing camera
        private final boolean USE_FRONT_CAM = true;

        /** An object for the Posenet library.    */
        private Posenet posenet;

        /** ID of the current [CameraDevice].   */
        private String cameraId = null; //nullable

        /** A [SurfaceView] for camera preview.   */
        private SurfaceView surfaceView = null; //nullable

        /** A [CameraCaptureSession] for camera preview.   */
        private CameraCaptureSession captureSession = null; //nullable

        /** A reference to the opened [CameraDevice].    */
        private CameraDevice cameraDevice = null; //nullable

        /** The [android.util.Size] of camera preview.  */
        private Size previewSize = null;

        /** The [android.util.Size.getWidth] of camera preview. */
        private int previewWidth = 0;

        /** The [android.util.Size.getHeight] of camera preview.  */
        private int previewHeight = 0;

        /** A counter to keep count of total frames.  */
        private int frameCounter = 0;

        /** An IntArray to save image data in ARGB8888 format  */
        private int[] rgbBytes;

        /** A ByteArray to save image data in YUV format  */
        private byte[][] yuvBytes = new byte[3][];  //???

        /** An additional thread for running tasks that shouldn't block the UI.   */
        private HandlerThread backgroundThread = null; //nullable

        /** A [Handler] for running tasks in the background.    */
        private Handler backgroundHandler = null; //nullable

        /** An [ImageReader] that handles preview frame capture.   */
        private ImageReader imageReader = null; //nullable

        /** [CaptureRequest.Builder] for the camera preview   */
        private CaptureRequest.Builder previewRequestBuilder = null; //nullable

        /** [CaptureRequest] generated by [.previewRequestBuilder   */
        private CaptureRequest previewRequest = null; //nullable

        /** A [Semaphore] to prevent the app from exiting before closing the camera.    */
        private Semaphore cameraOpenCloseLock = new Semaphore(1);

        /** Whether the current camera device supports Flash or not.    */
        private boolean flashSupported = false;

        /** Orientation of the camera sensor.   */
        private int sensorOrientation = 0;  //was null. Need Integer?

        /** Abstract interface to someone holding a display surface.    */
        private SurfaceHolder surfaceHolder; //nullable

        //canvas that displays relevant info on the screen
        private Canvas infoCanvas;

        //NAIVE IMPLEMENTATION ACCEL ARRAYS

        //temporary array to store raw linear accelerometer data before low-pass filter applied
        private final float[] NtempAcc = new float[3];

        //acceleration array for data after filtering
        private final float[] Nacc = new float[3];

        //velocity array (calculated from acceleration values)
        private final float[] Nvelocity = new float[3];

        //position (displacement) array (calculated from dVelocity values)
        private final float[] Nposition = new float[3];

        //NOSHAKE SPRING IMPLEMENTATION ACCEL ARRAYS
        private final float[] StempAcc = new float[3];
        private final float[] Sacc = new float[3];
        private final float[] accAfterFrix = new float[3];

        //long to use for keeping track of thyme
        private long timestamp = 0;

        //the view to be stabilized
        private View layoutSensor, waitingText;

        //the text that can be dragged around (compare viewing of this text to how the stabilized text looks)
        private TextView noShakeText;

        //original vs. changed layout parameters of the draggable text
        private RelativeLayout.LayoutParams originalLayoutParams;
        private RelativeLayout.LayoutParams editedLayoutParams;
        private int ogLeftMargin, ogTopMargin;

        //the accelerometer and its manager
        private Sensor accelerometer;
        private SensorManager sensorManager;

        //changes in x and y to be used to move the draggable text based on user's finger
        private int _xDelta, _yDelta;

        //time variables, and the results of H(t) and Y(t) functions
        private double HofT, YofT, startTime, timeElapsed;

        //the raw values that the low-pass filter is applied to
        private float[] gravity = new float[3];

        //working on circular buffer for the data
        private float[] accelBuffer = new float[3];

        private float impulseSum;

        //is the device shaking??
        private volatile int shaking = 0;

        private int index = 0, check = 0, times = 0;

        private Thread outputPlayerThread = null;

        public float toMoveX, toMoveY;

        float noseDeltaX, noseDeltaY;

        //declare global matrix containing my model 3D coordinates of human pose, to be used for camera pose estimation
        private Point3[] humanModelRaw = new Point3[6];
        private List<Point3> humanModelList = new ArrayList<Point3>();
        private MatOfPoint3f humanModelMat;

        //declare global matrix containing the actual 2D coordinates of the human found
        private Point[] humanActualRaw = new Point[6];

        //used for bounding box points
        private Point[] boundingBox = new Point[4];

        private List<Point> humanActualList = new ArrayList<Point>();
        private MatOfPoint2f humanActualMat;

        //matrices to be used for pose estimation calculation
        private Mat cameraMatrix, rotationMat, translationMat;
        private MatOfDouble distortionMat;

        Point3[] testPts = new Point3[3];
        List<Point3> testPtList = new ArrayList<Point3>();

        private int capture = 0;

        //which direction the person is looking (split at exactly perp to camera)
        private int looking;

        //declare floats for computing actual 2D dist found between nose and eyes and shoulders
        //this lets us deduce whether the person is looking left or rt (we need to swap axes)
        private float distToLeftEyeX, distToRightEyeX, distToLeftShouldX, distToRtShouldX;

        //float for finding center of human bust (pt between shoulders) in 2D coordinates, used as "origin" for drawing
        private float torsoCtrX, torsoCtrY;

        private Point torsoCenter;

        /** [CameraDevice.StateCallback] is called when [CameraDevice] changes its state.   */
        private class stateCallback extends CameraDevice.StateCallback {

            @Override
            public void onOpened(@NonNull CameraDevice cameraDevice) {
                cameraOpenCloseLock.release();
                PosenetLiveStatFeed.this.cameraDevice = cameraDevice;
                createCameraPreviewSession();
            }

            @Override
            public void onDisconnected(@NonNull CameraDevice cameraDevice) {
                cameraOpenCloseLock.release();
                cameraDevice.close();
                cameraDevice = null;
            }

            @Override
            public void onError(@NonNull CameraDevice cameraDevice, int i) {
                onDisconnected(cameraDevice);
                mainActivity.finish();
            }
        }

        /**
         * A [CameraCaptureSession.CaptureCallback] that handles events related to JPEG capture.
         */
        private class captureCallback extends CameraCaptureSession.CaptureCallback {
            @Override
            public void onCaptureProgressed(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull CaptureResult partialResult) {
                super.onCaptureProgressed(session, request, partialResult);

            }

            @Override
            public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
                super.onCaptureCompleted(session, request, result);
            }
        }

        //Creates a new [CameraCaptureSession] for camera preview.
        private void createCameraPreviewSession() {
            try {
                // We capture images from preview in YUV format.
                imageReader = ImageReader.newInstance(previewSize.getWidth(), previewSize.getHeight(), ImageFormat.YUV_420_888, 2);

                imageReader.setOnImageAvailableListener(new imageAvailableListener(), backgroundHandler);

                List<Surface> recordingSurfaces = new ArrayList<Surface>();

                // This is the surface we need to record images for processing.
                Surface recordingSurface = imageReader.getSurface();

                recordingSurfaces.add(recordingSurface);

                //We set up a CaptureRequest.Builder with the output Surface.
                previewRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);

                previewRequestBuilder.addTarget(recordingSurface);

                // Here, we create a CameraCaptureSession for camera preview.
                cameraDevice.createCaptureSession(
                        recordingSurfaces,
                        new CameraCaptureSession.StateCallback() {
                            @Override
                            public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                                // The camera is already closed
                                if (cameraDevice == null) return;

                                // When the session is ready, we start displaying the preview.
                                captureSession = cameraCaptureSession;

                                try {
                                // Auto focus should be continuous for camera preview.
                                    previewRequestBuilder.set(
                                            CaptureRequest.CONTROL_AF_MODE,
                                            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                                    );
                                    // Flash is automatically enabled when necessary.
                                    setAutoFlash(previewRequestBuilder);

                                    // Finally, we start displaying the camera preview.
                                    previewRequest = previewRequestBuilder.build();
                                    captureSession.setRepeatingRequest(previewRequest, new captureCallback(), backgroundHandler);
                                }
                                catch (CameraAccessException e) {
                                    Log.e(TAG, e.toString());
                                }
                            }
                            @Override
                            public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
                                showToast("Failed");
                            }
                        }, null);
            }

            catch (CameraAccessException e) {
                Log.e(TAG, e.toString());
            }
        }

        private void setAutoFlash(CaptureRequest.Builder requestBuilder) {
            if (flashSupported) {
                requestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
            }
        }

        /** Fill the yuvBytes with data from image planes.   */
        private void fillBytes(Image.Plane[] planes, byte[][] yuvBytes) {
            // Row stride is the total number of bytes occupied in memory by a row of an image.
            // Because of the variable row stride it's not possible to know in
            // advance the actual necessary dimensions of the yuv planes.
            for (int i = 0; i < planes.length; i++) {
                ByteBuffer buffer = planes[i].getBuffer();

                //create new byte array in yuvBytes the size of this plane
                if (yuvBytes[i] == null) {
                    yuvBytes[i] = new byte[(buffer.capacity())];
                }

                //store the raw ByteBuffer of the plane at this location in yuvBytes 2D array
                buffer.get(yuvBytes[i]);
            }
        }


        /** A [OnImageAvailableListener] to receive frames as they are available.  */
        private class imageAvailableListener implements ImageReader.OnImageAvailableListener {
            @Override
            public void onImageAvailable(ImageReader imageReader) {
                //Log.i("DBUG", "onImageAvailable");

                //We need to wait until we have some size from onPreviewSizeChosen
                if (previewWidth == 0 || previewHeight == 0) {
                    return;
                }

                //acquire the latest image from the the ImageReader queue
                Image image = imageReader.acquireLatestImage();
                if (image == null) {
                    return;
                }

                //get the planes from the image
                Image.Plane[] planes = image.getPlanes();

                //put all planes data into 2D byte array called yuvBytes
                fillBytes(planes, yuvBytes);

                //get first plane
                Image.Plane copy = planes[0];

                //get raw bytes from incoming 2d image
                ByteBuffer byteBuffer = copy.getBuffer();

                //create new array of raw bytes of the appropriate size (remaining)
                byte[] buffer = new byte[byteBuffer.remaining()];

                //store the ByteBuffer in the raw byte array (the pixels from first plane of image)
                byteBuffer.get(buffer);

                //instantiate new Matrix object to hold the image pixels
                Mat imageGrab = new Mat();

                //put all of the bytes into the Mat
                imageGrab.put(0,0, buffer);

                ImageUtils imageUtils = new ImageUtils();

                //convert the three planes into single int array called rgbBytes
                imageUtils.convertYUV420ToARGB8888(yuvBytes[0], yuvBytes[1], yuvBytes[2], previewWidth, previewHeight,
                        /*yRowStride=*/ image.getPlanes()[0].getRowStride(),
                        /*uvRowStride=*/ image.getPlanes()[1].getRowStride(),
                        /*uvPixelStride=*/ image.getPlanes()[1].getPixelStride(),
                        rgbBytes //an int[]
                );

                // Create bitmap from int array
                Bitmap imageBitmap = Bitmap.createBitmap(rgbBytes, previewWidth, previewHeight, Bitmap.Config.ARGB_8888);

                /*
                // Create rotated version (FOR PORTRAIT DISPLAY)
                Matrix rotateMatrix = new Matrix();
                rotateMatrix.postRotate(90.0f);

                Bitmap rotatedBitmap = Bitmap.createBitmap(imageBitmap, 0, 0, previewWidth, previewHeight, rotateMatrix, true);*/
                image.close();

                //testing convert bitmap to OpenCV Mat
                Mat testMat = new Mat();

                org.opencv.android.Utils.bitmapToMat(imageBitmap, testMat);

                /*
                //save the final rotated 480 x 640 bitmap
                if (capture == 0) {
                        Log.i(TAG, "Writing image");
                        Imgcodecs.imwrite("/data/data/weiner.noah.noshake.posenet.test/testCapture.jpg", testMat);
                }*/

                Log.i(TAG, String.format("Focal length found is %d", testMat.cols()));

                //set up the intrinsic camera matrix and initialize the world-to-camera translation and rotation matrices
                makeCameraMat();

                //send the final bitmap to be drawn on and output to the screen
                processImage(imageBitmap);
            }
        }

        private void makeCameraMat() {
            // Camera internals
            double focal_length_x = 526.69; // Approximate focal length, found from OpenCV chessboard calibration
            double focal_length_y = 540.36;

            //center of image plane
            Point center = new Point(313.07,238.39);

            //Log.i(TAG, String.format("Center at %f, %f", center.x, center.y));

            //create a 3x3 camera (intrinsic params) matrix
            cameraMatrix = Mat.eye(3, 3, CvType.CV_64F);

            double[] vals = {focal_length_x, 0, center.x, 0, focal_length_y, center.y, 0, 0, 1};

            //populate the 3x3 camera matrix
            cameraMatrix.put(0, 0, vals);

            /*
            cameraMatrix.put(0, 0, 400);
            cameraMatrix.put(1, 1, 400);
            cameraMatrix.put(0, 2, 640 / 2f);
            cameraMatrix.put(1, 2, 480 / 2f);
             */

                //distortionMat = new MatOfDouble(0,0,0,0);

            /*
            cameraMatrix.put(0, 0, 400);
            cameraMatrix.put(1, 1, 400);
            cameraMatrix.put(0, 2, 640 / 2f);
            cameraMatrix.put(1, 2, 480 / 2f);
             */

            //assume no camera distortion
            distortionMat = new MatOfDouble(new Mat(4, 1, CvType.CV_64FC1));
            distortionMat.put(0,0,0);
            distortionMat.put(1,0,0);
            distortionMat.put(2,0,0);
            distortionMat.put(3,0,0);

            //new mat objects to store rotation and translation matrices from camera coords to world coords when solvePnp runs
            rotationMat = new Mat(1, 3, CvType.CV_64FC1);
            translationMat = new Mat(1, 3, CvType.CV_64FC1);

            //Hack! initialize transition and rotation matrixes to improve estimation
            translationMat.put(0,0,-100);
            translationMat.put(0,0,100);
            translationMat.put(0,0,1000);

            if (distToLeftEyeX < distToRightEyeX) {
                //looking at left
                rotationMat.put(0,0,-1.0);
                rotationMat.put(1,0,-0.75);
                rotationMat.put(2,0,-3.0);
                looking = LOOKING_LEFT;
            }
            else {
                //looking at right
                rotationMat.put(0,0,1.0);
                rotationMat.put(1,0,-0.75);
                rotationMat.put(2,0,-3.0);
                looking = LOOKING_RIGHT;
            }

        }

        /** Crop Bitmap to maintain aspect ratio of model input. */
        private Bitmap cropBitmap(Bitmap bitmap) {
            float bitmapRatio = (float)bitmap.getHeight() / bitmap.getWidth();

            float modelInputRatio = (float) Constants.MODEL_HEIGHT / Constants.MODEL_WIDTH;

            //first set new edited bitmap equal to the passed one
            Bitmap croppedBitmap = bitmap;

            // Acceptable difference between the modelInputRatio and bitmapRatio to skip cropping.
            double maxDifference = 1e-5;

            // Checks if the bitmap has similar aspect ratio as the required model input.
            if (Math.abs(modelInputRatio - bitmapRatio) < maxDifference) {
                return croppedBitmap;
            }
            else if (modelInputRatio < bitmapRatio) {
                // New image is taller so we are height constrained.
                float cropHeight = bitmap.getHeight() - ((float)bitmap.getWidth() / modelInputRatio);

                croppedBitmap = Bitmap.createBitmap(bitmap, 0, (int)(cropHeight / 2), bitmap.getWidth(), (int)(bitmap.getHeight() - cropHeight));
            }
            else {
                float cropWidth = bitmap.getWidth() - ((float)bitmap.getHeight() * modelInputRatio);

                croppedBitmap = Bitmap.createBitmap(bitmap, (int)(cropWidth / 2), 0, (int)(bitmap.getWidth() - cropWidth), bitmap.getHeight());
            }

            Mat croppedImage = new Mat();

            org.opencv.android.Utils.bitmapToMat(croppedBitmap, croppedImage);

            /*
            if (capture == 0) {
                    Log.i(TAG, "Writing cropped image");
                    Imgcodecs.imwrite("/data/data/weiner.noah.noshake.posenet.test/testCaptureCropped.jpg", croppedImage);
            }*/

            return croppedBitmap;
        }


        //Process image using Posenet library. The image needs to be scaled in order to fit Posenet's input dimension requirements of
        //257 x 257 (defined in Constants.java), and probably needs to be cropped in order to preserve the image's aspect ratio
        private void processImage(Bitmap bitmap) {
            // Crop bitmap.
            Bitmap croppedBitmap = cropBitmap(bitmap);

            Mat scaledImage = new Mat();

            Log.i(TAG, String.valueOf(croppedBitmap.getConfig()));

            // Created scaled version of bitmap for model input (scales it to 257 x 257)
            Bitmap scaledBitmap = Bitmap.createScaledBitmap(croppedBitmap, Constants.MODEL_WIDTH, Constants.MODEL_HEIGHT, true);

            //get bitmap from mat
            org.opencv.android.Utils.bitmapToMat(scaledBitmap, scaledImage);

            /*
            //save the scaled down bitmap of the first image taken (as a jpg)
            if (capture == 0) {
                    capture = 1;
                    Log.i(TAG, "Writing scaled image");
                    Imgcodecs.imwrite("/data/data/weiner.noah.noshake.posenet.test/testCaptureScaled0.jpg", scaledImage);
            }*/

            //Perform inference.
            Person person = posenet.estimateSinglePose(scaledBitmap);

            Canvas canvas = surfaceHolder.lockCanvas();

            /*
            if (canvas == null) {
                    Log.e("DEBUG", "processImage: canvas came up NULL");
                    return;
            }
            */

            draw(canvas, person, scaledBitmap);

            //displacementOnly(person, canvas);
        }

        private int noseFound = 0;
        private float noseOriginX, noseOriginY, lastNosePosX, lastNosePosY;

        /** Draw bitmap on Canvas. */
        //the Canvas class holds the draw() calls. To draw something, you need 4 basic components: A Bitmap to hold the pixels,
        // a Canvas to host the draw calls (writing into the bitmap),
        // a drawing primitive (e.g. Rect, Path, text, Bitmap), and a paint (to describe the colors and styles for the drawing).
        private void draw(Canvas canvas, Person person, Bitmap bitmap) { //NOTE: the Bitmap passed here is 257x257 pixels, good for Posenet model
            //save canvas into a global
            infoCanvas = canvas;

            //draw clear nothing color to the screen (needs this to clear out the old text and stuff)
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);

            //Draw `bitmap` and `person` in square canvas.
            int screenWidth, screenHeight, left, right, top, bottom, canvasHeight, canvasWidth;

            int rightEyeFound = 0, leftEyeFound = 0;

            float xValue, yValue, xVel, yVel;
            float dist = 0;

            BodyPart currentPart;
            Position leftEye = null, rightEye = null;

            //get the dimensions of our drawing canvas
            canvasHeight = canvas.getHeight();
            canvasWidth = canvas.getWidth();

            //should be 1080 x 2148 (full screen besides navigation bar)
            Log.i(TAG, String.format("Canvas width and height are %d and %d", canvasWidth, canvasHeight));

            //check screen orientation: if portrait mode, set the camera preview square appropriately
            if (canvasHeight > canvasWidth) {
                screenWidth = canvasWidth;
                screenHeight = canvasWidth; //screenwidth x screenHeight should now be 1080 x 1080
                left = 0;

                //we can find the top of the camera preview square by finding width of the padding on top and bottom of the square
                //the total amt of padding will be the canvasHeight (2148) minus the heigt of camera preview box, then divide by 2 to get
                //amt we need to go down from top of screen to find top of camera preview square
                top = (canvasHeight - canvasWidth) / 2; //should be 534
            }

            //otherwise if landscape mode, set the width and height of the camera preview square appropriately
            else {
                screenWidth = canvasHeight;
                screenHeight = canvasHeight;
                left = (canvasWidth - canvasHeight) / 2;
                top = 0;
            }

            Log.i(TAG, "Left is " + left);

            //right is right edge of screen if portrait mode; otherwise it's in middle of screen
            right = left + screenWidth; //should be 1080

            //find bottom of the camera preview square
            bottom = top + screenHeight; //should be 534 + 1080 = 1614


            int bmWidth = bitmap.getWidth(); //should be 257
            int bmHeight = bitmap.getHeight(); //should be 257

            Log.i(TAG, String.format("Bitmap width and height are %d and %d", bmWidth, bmHeight)); //should be 257x257

            //WHAT IS PT OF THIS??
            int newRectWidth = right - left;
            int newRectHeight = bottom - top;

            double scaleDownRatioVert = newRectHeight / 2280f;
            double scaleDownRatioHoriz = newRectWidth / 1080f;

            Log.i(TAG, String.format("New rect width and height are %d and %d", newRectWidth, newRectHeight));
            Log.i(TAG, String.format("Scaledown ratios are %f and %f", scaleDownRatioHoriz, scaleDownRatioVert));

            //draw the camera preview square bitmap on the screen
            //Android fxn documentation: Draw the specified bitmap, scaling/translating automatically to fill the destination rectangle.
            //*If the source rectangle is not null, it specifies the subset of the bitmap to draw.
            //This function ignores the density associated with the bitmap. This is because the source and destination rectangle
            // coordinate spaces are in their respective densities, so must already have the appropriate scaling factor applied.
            canvas.drawBitmap(bitmap, /*src*/new Rect(0, 0, bmWidth, bmHeight), //in other words draw whole bitmap
                    /*dest*/new Rect(left, top, right, bottom), redPaint);


            //Next need to calculate ratios used to scale image back up from the 257x257 passed to PoseNet to the actual display

            //divide the available screen width pixels by PoseNet's required number of width pixels to get the number of real screen pixels
            //widthwise per posenet input image "pixel"
            float widthRatio = (float) screenWidth / Constants.MODEL_WIDTH; //should be 1080/257

            //divide the available screen height pixels by PoseNet's required number of height pixels to get number of real screen pixels
            //heightwise per posenet input image "pixel"
            float heightRatio = (float) screenHeight / Constants.MODEL_HEIGHT; //should be 1080/257

            Log.i(TAG, "Widthratio is " + widthRatio + ", heightRatio is " + heightRatio);

            //get the keypoints list ONCE at the beginning
            List<KeyPoint> keyPoints = person.getKeyPoints();

            //Log.d(TAG, String.format("Found %d keypoints for the person", keyPoints.size()));

            //Draw key points of the person's body parts over the camera image
            for (KeyPoint keyPoint : keyPoints) {
                //get the body part ONCE at the beginning
                currentPart = keyPoint.getBodyPart();

                //make sure we're confident enough about where this posenet pose is to display it
                if (keyPoint.getScore() > minConfidence) {
                    Position position = keyPoint.getPosition();
                    xValue = (float) position.getX();
                    yValue = (float) position.getY();

                    //the real x value for this body part dot should be the xValue PoseNet found in its 257x257 input bitmap multiplied
                    //by the number of real Android display (or at least Canvas) pixels per Posenet input bitmap pixel
                    float adjustedX = (float) xValue * widthRatio + left; //x value adjusted for actual Android display
                    float adjustedY = (float) yValue * heightRatio + top; //y value adjusted for actual Android display

                    //I'll start by just using the person's nose to try to estimate how fast the phone is moving
                    if (currentPart == BodyPart.NOSE) {
                        //add nose to first slot of Point array for pose estimation
                        humanActualRaw[0] = new Point(xValue, yValue);
                        humanActualRaw[1] = new Point(xValue, yValue);

                                /*
                                if (noseFound == 0) {
                                        noseFound = 1;
                                        setInitialNoseLocation(adjustedX, adjustedY);
                                }

                                else {
                                        //compute the displacement from the starting position that the nose has traveled (helper fxn)
                                        computeDisplacement(adjustedX, adjustedY);
                                }
                                */
                    }
                    else if (currentPart == BodyPart.LEFT_EYE) {
                        //add nose to first slot of Point array for pose estimation
                        humanActualRaw[2] = new Point(xValue, yValue);

                        //add x val of left eye to bbox array
                        boundingBox[1] = new Point(adjustedX, adjustedY);


                        leftEyeFound = 1;
                        leftEye = new Position(adjustedX, adjustedY);

                        //if we've also already found right eye, we have both eyes. Send data to the scale computer
                        if (rightEyeFound == 1) {
                            dist = computeScale(leftEye, rightEye);
                        }
                    }
                    else if (currentPart == BodyPart.RIGHT_EYE) {
                        //add nose to first slot of Point array for pose estimation
                        humanActualRaw[3] = new Point(xValue, yValue);

                        //add x val of rt eye to bbox array
                        boundingBox[0] = new Point(adjustedX, adjustedY);

                        rightEyeFound = 1;
                        rightEye = new Position(adjustedX, adjustedY);

                        //if we've also already found left eye, we have both eyes. Send data to the scale computer
                        if (leftEyeFound == 1) {
                            dist = computeScale(leftEye, rightEye);
                        }

                    }

                    else if (currentPart == BodyPart.RIGHT_SHOULDER) {
                        //add rt shoulder to fifth slot of Point array for pose estimation
                        humanActualRaw[4] = new Point(xValue, yValue);

                        boundingBox[2] = new Point(adjustedX, adjustedY);
                    }
                    else if (currentPart == BodyPart.LEFT_SHOULDER) {
                        //add left shoulder to sixth slot of Point array for pose estimation
                        humanActualRaw[5] = new Point(xValue, yValue);

                        boundingBox[3] = new Point(adjustedX, adjustedY);
                    }


                }

                /*
                //if this point is the nose but we've lost our lock on it (confidence level is low)
                else if (currentPart == BodyPart.NOSE) {
                        //set noseFound back to 0
                        noseFound = 0;

                        //reset velocity array
                        velocity[0] = velocity[1] = 0;
                }
                */
            }

            /*
            //draw the lines of the person's limbs
            for (Pair line : bodyJoints) {
                    assert line.first != null;
                    assert line.second != null;

                    if ((keyPoints.get(BodyPart.getValue((BodyPart) line.first)).getScore() > minConfidence) &&
                            (keyPoints.get(BodyPart.getValue((BodyPart) line.second)).getScore() > minConfidence)) {

                            //draw a line for this "limb" using coordinates of the two BodyPart points and the scaling ratios again
                            canvas.drawLine(
                                    keyPoints.get(BodyPart.getValue((BodyPart) line.first)).getPosition().getX() * widthRatio + left,
                                    keyPoints.get(BodyPart.getValue((BodyPart) line.first)).getPosition().getY() * heightRatio + top,
                                    keyPoints.get(BodyPart.getValue((BodyPart) line.second)).getPosition().getX() * widthRatio + left,
                                    keyPoints.get(BodyPart.getValue((BodyPart) line.second)).getPosition().getY() * heightRatio + top,
                                    //the paint tool we've set up
                                    paint
                            );
                    }
            }
            */

            //ONLY EVERY THIRD FRAME, maybe?
            //check that all of the keypoints for a human body bust area were found
            if (humanActualRaw[0] != null && humanActualRaw[1] != null && humanActualRaw[2] != null && humanActualRaw[3] != null
                    && humanActualRaw[4] != null && humanActualRaw[5] != null
                //&& frameCounter==3
            )
            {
                //DRAW BOUNDING BOX
                //top is aligned with uppermost eye
                double bbox_top = Math.max(humanActualRaw[3].y, humanActualRaw[2].y);

                //left is aligned w right shoulder
                double bbox_left = humanActualRaw[4].x;

                //rt is aligned w left shoulder
                double bbox_rt = humanActualRaw[5].x;

                //bottom is at lowermost shoulder
                double bbox_bot = Math.min(humanActualRaw[4].y, humanActualRaw[5].y);


                canvas.drawRect(new Rect((int)boundingBox[2].x, (int)Math.min(boundingBox[0].y, boundingBox[1].y),
                        (int)boundingBox[3].x, (int)Math.max(boundingBox[2].y, boundingBox[3].y)), whitePaint);


                distToLeftEyeX = (float)Math.abs(humanActualRaw[2].x - humanActualRaw[0].x);
                distToRightEyeX = (float)Math.abs(humanActualRaw[3].x - humanActualRaw[0].x);

                distToLeftEyeX = (float)Math.abs(humanActualRaw[5].x - humanActualRaw[0].x);
                distToRightEyeX = (float)Math.abs(humanActualRaw[4].x - humanActualRaw[0].x);


                //correction for axis flipping
                if (distToLeftEyeX > distToRightEyeX) {
                    //person looking towards left, swap left eye and rt eye for actual
                    Point temp = humanActualRaw[2];
                    humanActualRaw[2] = humanActualRaw[3];
                    humanActualRaw[3] = temp;
                }

                //correction for axis flipping
                if (distToLeftShouldX < distToRtShouldX) {
                    //person looking towards left, swap left eye and rt eye for actual
                    Point temp = humanActualRaw[5];
                    humanActualRaw[5] = humanActualRaw[4];
                    humanActualRaw[4] = temp;
                }


                //HARDCODED, FIXME

                //find chest pt (midpt between shoulders)
                torsoCtrX = (float) (humanActualRaw[4].x + humanActualRaw[5].x) / 2;
                torsoCtrY = (float) (humanActualRaw[4].y + humanActualRaw[5].y) / 2;


                torsoCenter = new Point(torsoCtrX, torsoCtrY);
                //torsoCenter = new Point((rt_should.x + left_should.x)/2, (rt_should.y + left_should.y)/2);
                //humanActualRaw[0] = torsoCenter;

                //draw the point corresponding to the chest
                canvas.drawCircle(torsoCtrX, torsoCtrY, circleRadius, redPaint);

                //clear out the ArrayList
                humanActualList.clear();

                //compute pose estimation and draw line coming out of person's chest

                //add the pts of interest to a list
                humanActualList.add(humanActualRaw[0]);
                humanActualList.add(humanActualRaw[1]);
                humanActualList.add(humanActualRaw[2]);
                humanActualList.add(humanActualRaw[3]);
                humanActualList.add(humanActualRaw[4]);
                humanActualList.add(humanActualRaw[5]);

                Log.i(TAG, String.format("Human actual: [%f,%f], [%f,%f], [%f,%f], [%f,%f], [%f,%f], [%f, %f]",
                        humanActualList.get(0).x,
                        humanActualList.get(0).y,
                        humanActualList.get(1).x,
                        humanActualList.get(1).y,
                        humanActualList.get(2).x,
                        humanActualList.get(2).y,
                        humanActualList.get(3).x,
                        humanActualList.get(3).y,
                        humanActualList.get(4).x,
                        humanActualList.get(4).y,
                        humanActualList.get(5).x,
                        humanActualList.get(5).y));

                humanActualMat.fromList(humanActualList);

                //now should have everything we need to run solvePnP

                //solve for translation and rotation matrices based on a model of 3d pts for human bust area
                Calib3d.solvePnP(humanModelMat, humanActualMat, cameraMatrix, distortionMat, rotationMat, translationMat);

                //Now we'll try projecting our 3D axes onto the image plane
                MatOfPoint3f testPtMat = new MatOfPoint3f();
                testPtMat.fromList(testPtList);

                //the 2d pts that correspond to the 3d pts above. Will be filled upon return of projectPoints()
                MatOfPoint2f imagePts = new MatOfPoint2f();

                //project our basic x-y-z axis from the world coordinate system onto the camera coord system using rot and trans mats we solved
                Calib3d.projectPoints(testPtMat, rotationMat, translationMat, cameraMatrix, distortionMat, imagePts);

                //imagePts now contains 3 2D coordinates which correspond to ends of the 3D axes

                Log.i(TAG, String.format("Resulting imagepts Mat is of size %d x %d", imagePts.rows(), imagePts.cols()));

                //extract the 3 2D coordinates for drawing the 3D axes
                double[] x_ax = imagePts.get(0,0);
                double[] y_ax = imagePts.get(1,0);
                double[] z_ax = imagePts.get(2,0);

                //we need to change the points found so that they map correctly into the square Canvas on the screen (basically we're
                //scaling up the pts from the original 257x257 bitmap to the 1080x1080 image preview box we now have on screen
                x_ax[0] = x_ax[0] * widthRatio + left;
                x_ax[1] = x_ax[1] * heightRatio + top;

                y_ax[0] = y_ax[0] * widthRatio + left;
                y_ax[1] = y_ax[1] * heightRatio + top;

                z_ax[0] = z_ax[0] * widthRatio + left;
                z_ax[1] = z_ax[1] * heightRatio + top;

                torsoCenter.x = torsoCenter.x * widthRatio + left;
                torsoCenter.y = torsoCenter.y * heightRatio + top;

                Log.i(TAG, String.format("Found point %f, %f for x axis", x_ax[0], x_ax[1]));
                Log.i(TAG, String.format("Found point %f, %f for y axis", y_ax[0], y_ax[1]));
                Log.i(TAG, String.format("Found point %f, %f for z axis", z_ax[0], z_ax[1]));


                //filter out the weird bogus data I was getting
                if (!(x_ax[0] > 2500 || x_ax[1] > 1400 || y_ax[0] > 1500 || y_ax[1] < -1000 || z_ax[0] > 1500 || z_ax[1] < -900
                        //check for illogical axes layout
                        || (looking == LOOKING_LEFT && z_ax[0] < y_ax[0]) || (looking == LOOKING_RIGHT && z_ax[0] > y_ax[0]))) {

                    //draw the projected 3D axes onto the canvas
                    canvas.drawLine((float) torsoCenter.x, (float) torsoCenter.y,
                            (float) x_ax[0], (float) x_ax[1], bluePaint);
                    canvas.drawLine((float) torsoCenter.x, (float) torsoCenter.y,
                            (float) y_ax[0], (float) y_ax[1], greenPaint);
                    canvas.drawLine((float) torsoCenter.x, (float) torsoCenter.y,
                            (float) z_ax[0], (float) z_ax[1], redPaint);

                    //estimate angles for yaw and pitch of the human's upper body

                    //Mat eulerAngles = new Mat();

                    //THIS FXN DOESN'T WORK RIGHT NOW
                    //getEulerAngles(eulerAngles);

                    Log.i(TAG, "z ax[0] is " + z_ax[0]);
                    Log.i(TAG, "torsoCenter.x is ");
                    //we know length of z axis to be 81.25. Let's find length of 'opposite' side of the rt triangle so that we can use sine to find angle
                    float lenOpposite = (float) z_ax[0] - (float)torsoCenter.x;
                    Log.i(TAG, "Len opposite is " + lenOpposite);

                    float humAngle = getHumAnglesTrig(lenOpposite, 135f); //81.25?

                    Log.i(TAG, "Human angle is " + humAngle + " degrees");

                    //Log.i(TAG, String.format("Euler angles mat is of size %d x %d", eulerAngles.rows(), eulerAngles.cols()));

                    //pitch, yaw, roll
                    //double[] angles = eulerAngles.get(0,0);

                    double[] pitch = rotationMat.get(0,0);
                    double[] yaw = rotationMat.get(1,0);

                }
                else {
                    Log.i(TAG,"Triggered");
                }
            }

            //reset contents of the arrays

            humanActualRaw[0] = humanActualRaw[1] = humanActualRaw[2] = humanActualRaw[3] = humanActualRaw[4] = null;

            //draw/push the Canvas bits to the screen - FINISHED THE CYCLE
            surfaceHolder.unlockCanvasAndPost(canvas);

            //increment framecounter, if at 4 set to 0
            frameCounter++;
            if (frameCounter == 4) {
                frameCounter = 0;
            }
        }

        private float getHumAnglesTrig(float opp, float hyp) {
            Log.i(TAG, "opp/hyp is " + (opp/hyp));

            float ratio = opp/hyp;

            if (ratio <= -1)
                return -90f;
            else if (ratio >= 1)
                return 90f;

            return (float) Math.toDegrees(Math.asin(ratio));
        }

        //how many real-world meters each pixel in the camera image represents
        private float mPerPixel;

        //compute how much distance each pixel currently represents in real life, using known data about avg human pupillary distance
        private float computeScale(Position leftEye, Position rightEye) {
            //I'll just use the x distance between left eye and right eye points to get distance in pixels between eyes
            //don't forget left eye is on the right and vice versa
            float pixelDistance = leftEye.getX() - rightEye.getX();

            Log.d(TAG, String.format("Pupillary distance in pixels: %f", pixelDistance));

            //now we want to find out how many real meters each pixel on the display corresponds to
            float scale = Constants.PD / pixelDistance;
            mPerPixel = scale;

            Log.d(TAG, String.format("Each pixel on the screen represents %f meters in real life in plane of peron's face", scale));

            //find experimental distance from camera to human and display it on screen

            return calculateDistanceToHuman(pixelDistance);
        }

        private float calculateDistanceToHuman(float pixelDistance) {
            //Triangle simularity
            //D = (W * F) / P

            //find distance to human in meters
            return Constants.PD * Constants.focalLenExp / pixelDistance;
        }


        /**
         * Shows a [Toast] on the UI thread.
         *
         * @param text The message to show
         */
        private void showToast(final String text) {
            if (mainActivity != null)
                mainActivity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(mainActivity, text, Toast.LENGTH_SHORT).show();
                    }
                });
        }

        //ENTRY POINT OF THREAD
        @Override
        public void run() {

        }
    }
}
