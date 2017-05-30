/**
 * Authors: Adriano Silva and Luc Francey
 * T3-F
 * HEIA-FR 2017
 */
package ch.heia.ps6_dronedynamiclanding;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.Rect;
import android.os.Handler;
import android.support.annotation.Nullable;
import android.util.AttributeSet;
import android.util.Log;
import android.util.SparseArray;
import android.view.Display;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;


import com.google.android.gms.vision.Frame;
import com.google.android.gms.vision.barcode.Barcode;
import com.google.android.gms.vision.barcode.BarcodeDetector;

import dji.common.camera.SettingsDefinitions;
import dji.common.error.DJIError;
import dji.common.flightcontroller.FlightOrientationMode;
import dji.common.flightcontroller.virtualstick.FlightControlData;
import dji.common.flightcontroller.virtualstick.FlightCoordinateSystem;
import dji.common.flightcontroller.virtualstick.RollPitchControlMode;
import dji.common.flightcontroller.virtualstick.VerticalControlMode;
import dji.common.flightcontroller.virtualstick.YawControlMode;
import dji.common.util.CommonCallbacks;
import dji.sdk.base.BaseProduct;
import dji.sdk.products.Aircraft;
import dji.sdk.sdkmanager.DJISDKManager;

import static java.lang.Math.abs;
import static java.lang.Math.pow;
import static java.lang.Math.round;
import static java.lang.Math.sqrt;
import static java.lang.Math.tan;
import static java.lang.Math.toRadians;


public class BarcodeView extends View {

    private static final String TAG = BarcodeView.class.getName();
    private Context ctx;
    public CascadingThread mainThread = null;

    private TextureView cameraView = null;
    private BaseProduct currentDrone = null;  //Instance of the Base Product from sdk (Drone)

    private BarcodeDetector detector;   //Google Mobile Vision detector

    private int viewWidth = -1; //Width of the view (Drone Camera)
    private int viewHeight = -1; //Height of the view (Drone Camera)

    private Rect targetRect = new Rect(0, 0, 0, 0);  //Target Rectangle in middle of screen

    private boolean landMode = false; //land mode, to know if the drone should land or just follow

    private Float speed = 1f; //speed in meters/seconds for the drone movement
    private Float lowSpeed = 0.25f;

    private double minDistSlow = 1; //minimum distance in meters at which the drone should slow down the speed

    private static final int TIMEBETWEENSCAN = 150; //Sleep time between each QR Code scan
    private int framesWithoutQR = 0; //The number of tests without a successful detection

    private Paint paint;
    private Rect[] facesArray = null;
    private final Object lock = new Object(); //Drawing mutex

    private final double halfFOV = 39.4; // angle of half Field Of View of the camera



    public BarcodeView(Context context) {
        super(context);
        Log.d(TAG, "constructor 1");
        init(context);
    }

    public BarcodeView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        Log.d(TAG, "constructor 2");
        init(context);
    }

    public BarcodeView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        Log.d(TAG, "constructor 3");
        init(context);
    }

    private void init(Context context) {
        detector = new BarcodeDetector.Builder(context).setBarcodeFormats(Barcode.QR_CODE).build();

        if (!detector.isOperational()) {
            Toast.makeText(context, "Could not set up the QR detector!", Toast.LENGTH_SHORT).show();
            return;
        }
        ctx = context;

        //Painting of the target square
        paint = new Paint();
        paint.setAntiAlias(true);
        paint.setColor(Color.GREEN);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(4f);

        currentDrone = DJISDKManager.getInstance().getProduct();

        //Setting the Focus and Exposure modes of the camera in auto mode
        currentDrone.getCamera().setFocusMode(SettingsDefinitions.FocusMode.AUTO, null);
        currentDrone.getCamera().setExposureMode(SettingsDefinitions.ExposureMode.PROGRAM, null);


        //Point in the screen for the focus
        PointF point = new PointF(600, 600);

        //Getting the coordinates for the Target rectangle
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        Display display = wm.getDefaultDisplay();
        Point size = new Point();
        display.getSize(size);
        targetRect = new Rect((size.x / 2) - 125, (size.y / 2) - 125, (size.x / 2) + 125, (size.y / 2) + 125);

    }

    public void resume(final TextureView cameraView, int sWidth, int sHeight) {
        this.cameraView = cameraView;
        mainThread = new CascadingThread(ctx);
        mainThread.start();
    }

    public void pause() {
        if (getVisibility() == View.VISIBLE) {
            mainThread.interrupt();

            try {
                mainThread.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    public class CascadingThread extends Thread {
        private final Handler handler;
        boolean interrupted = false;

        private CascadingThread(final Context ctx) {
            handler = new Handler(ctx.getMainLooper());
        }

        public void interrupt() {
            interrupted = true;
        }

        //Thread for scanning the QR Codes and moving the drone
        @Override
        public void run() {

            Log.d(TAG, "Thread started");
            while (!interrupted) {
                if (viewWidth > -1 && viewHeight > -1) {
                    Bitmap source = cameraView.getBitmap();
                    if (source != null) {
                        if (getAltitude() <= 0.4) {
                            land();
                        }
                        //Converting the Bitmap of the camera to a frame
                        Frame convFram = new Frame.Builder().setBitmap(source).build();
                        //Detection of QR Codes in the frame
                        SparseArray<Barcode> barcodes = detector.detect(convFram);

                        //Checking and storing of the QR Code with the lowest value
                        int index = -1;
                        int lastVal = 100;
                        for (int i = 0; i < barcodes.size(); i++) {
                            int cVal = 100;
                            try {
                                cVal = Integer.parseInt(barcodes.valueAt(i).rawValue);
                            } catch (NumberFormatException e) {

                            }
                            if (cVal < lastVal) {
                                index = i;
                                lastVal = cVal;
                            }
                        }

                        if (barcodes.size() > 0) {
                            framesWithoutQR = 0;

                            Log.d(TAG, "QR Code detected");

                            //get boundingBox and cornerPoint of the QR Code
                            Rect qrRect = barcodes.valueAt(index).getBoundingBox();
                            Point[] qrPoints = barcodes.valueAt(index).cornerPoints;

                            synchronized (lock) {
                                facesArray = new Rect[2];
                                facesArray[0] = targetRect;
                                facesArray[1] = qrRect;
                                runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        invalidate();
                                    }
                                });
                            }
                            //Moving the drone in order to place it above the platform
                            adjustMovements(qrPoints);
                        } else {
                            noQRFound();
                        }
                    }
                    try {
                        Thread.sleep(TIMEBETWEENSCAN);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
        }

        /**
         * Called when the drone needs to move closer to a QR code, or decrease the height.
         *
         * @param qrPoints the coordinates of the QR rectangle
         */
        private void adjustMovements(Point[] qrPoints) {

            int qrLeft = qrPoints[0].x;
            int qrRight = qrPoints[1].x;
            int qrTop = qrPoints[0].y;
            int qrBottom = qrPoints[2].y;

            int count = 0;
            for (Point point : qrPoints) {
                Log.v(TAG, "Point" + count + ": (" + point.x + "," + point.y + ")");
                count++;
            }

            Rect qrRectangle = new Rect(qrLeft, qrTop, qrRight, qrBottom);
            int cX = qrRectangle.centerX();
            int cY = qrRectangle.centerY();

            PointF qrRect = new PointF(cX, cY);
            PointF tarRect = new PointF(targetRect.centerX(), targetRect.centerY());


            double[] distance = distanceInMeters(tarRect, qrRect);
            double diagonalDistance = sqrt(pow(distance[0], 2) + pow(distance[1], 2));



            //preparations in order to get the Virtual Stick Mode available
            ((Aircraft) currentDrone).getFlightController().setVirtualStickModeEnabled(true, null);
            ((Aircraft) currentDrone).getFlightController().setFlightOrientationMode(FlightOrientationMode.AIRCRAFT_HEADING, null);
            ((Aircraft) currentDrone).getFlightController().setTerrainFollowModeEnabled(false, null);
            ((Aircraft) currentDrone).getFlightController().setTripodModeEnabled(false, null);
            Log.d(TAG, "something running: " + DJISDKManager.getInstance().getMissionControl().getRunningElement());


            if (((Aircraft) currentDrone).getFlightController().isVirtualStickControlModeAvailable()) {
                Log.d(TAG, "virtual stick control mode available");
                if (!((Aircraft) currentDrone).getFlightController().isVirtualStickAdvancedModeEnabled()) {
                    ((Aircraft) currentDrone).getFlightController().setVirtualStickAdvancedModeEnabled(true);
                    Log.d(TAG, "Virtual Stick Advanced Mode enabled");
                }
                //Setting the control modes for Roll, Pitch and Yaw
                ((Aircraft) currentDrone).getFlightController().setRollPitchControlMode(RollPitchControlMode.VELOCITY);
                ((Aircraft) currentDrone).getFlightController().setYawControlMode(YawControlMode.ANGULAR_VELOCITY);
                ((Aircraft) currentDrone).getFlightController().setVerticalControlMode(VerticalControlMode.VELOCITY);
                ((Aircraft) currentDrone).getFlightController().setRollPitchCoordinateSystem(FlightCoordinateSystem.BODY);

                //Disabling the Landing Protection
                ((Aircraft) currentDrone).getFlightController().getFlightAssistant().setLandingProtectionEnabled(false, null);
            }


            //Creation of a FlightControlData to store the movement information
            FlightControlData move = new FlightControlData(0, 0, 0, 0);
            if (cX < targetRect.left) {
                //move drone to left
                Log.i(TAG, "move to left");
                //Reduce the speed if the distance between the QR Code and the target is less or equal than minDistSlow
                if (diagonalDistance <= minDistSlow) {
                    move.setPitch(-lowSpeed);
                } else {
                    move.setPitch(-speed * (float) distance[0]);
                }
            } else if (cX > targetRect.right) {
                //move drone to right
                Log.i(TAG, "move to right");
                //Reduce the speed if the distance between the QR Code and the target is less or equal than minDistSlow
                if (diagonalDistance <= minDistSlow) {
                    move.setPitch(lowSpeed);
                } else {
                    move.setPitch(speed * (float) distance[0]);
                }
            }
            if (cY < targetRect.top) {
                //move drone forward
                Log.i(TAG, "move forward");
                //Reduce the speed if the distance between the QR Code and the target is less or equal than minDistSlow
                if (diagonalDistance <= minDistSlow) {
                    move.setRoll(lowSpeed);
                } else {
                    move.setRoll(speed * (float) distance[1]);
                }
            } else if (cY > targetRect.bottom) {
                //move drone backward
                Log.i(TAG, "move backward");
                //Reduce the speed if the distance between the QR Code and the target is less or equal than minDistSlow
                if (diagonalDistance <= minDistSlow) {
                    move.setRoll(-lowSpeed);
                } else {
                    move.setRoll(-speed * (float) distance[1]);
                }
            }

            //Decreasing altitude if the Target square contains the middle point of the QR Code
            if (targetRect.contains(cX, cY)) {
                if (landMode) {
                    //decrease altitude
                    Log.d(TAG, "Decreasing altitude");
                    move.setVerticalThrottle(-lowSpeed);
                }
            }

            //Sending the movement information's to the drone
            Log.d(TAG, "Virtual stick mode available?:" + ((Aircraft) currentDrone).getFlightController()
                    .isVirtualStickControlModeAvailable());

            ((Aircraft) currentDrone).getFlightController().sendVirtualStickFlightControlData(move, new CommonCallbacks.CompletionCallback() {
                @Override
                public void onResult(DJIError arg0) {
                    if (arg0 != null) {
                        Log.e(TAG, arg0.toString());
                    }
                }
            });

        }

        /**
         * Function to move down the drone, it will stop the motors once the drone has landed
         */
        private void land() {
            FlightControlData move = new FlightControlData(0, 0, 0, 0);
            if (landMode) {
                move.setVerticalThrottle(-speed);
                ((Aircraft) currentDrone).getFlightController().sendVirtualStickFlightControlData(move, null);
            }
        }

        /**
         * Called when a frame does not contain any QR code, this increase the number of frame
         * without QR code and delete the previous rectangles drawn on the canvas. It resets the
         * speed at 1 m/s.
         */
        private void noQRFound() {
            framesWithoutQR++;
            speed = 1f;
            synchronized (lock) {
                facesArray = new Rect[1];
                facesArray[0] = targetRect;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        invalidate();
                    }
                });
            }
        }

        private void runOnUiThread(Runnable r) {
            handler.post(r);
        }
    }

    /**
     * Setter for the width and height of the view
     * @param sWidth
     * @param sHeight
     */
    public void setViewWidthHeight(int sWidth, int sHeight) {
        this.viewWidth = sWidth;
        this.viewHeight = sHeight;
    }

    /**
     * Getter for the landMode
     * @return boolean landMode
     */
    public boolean isLandMode() {
        return landMode;
    }

    //Setter for the landMode boolean
    public void setLandMode(boolean landMode) {
        this.landMode = landMode;
    }

    /**
     * Function returning the altitude calculated by the sonar or with the gps coordination if the can't return a good value
     * @return Float value of the height
     */
    public Float getAltitude() {
        Float result = -1F;
        if (((Aircraft) currentDrone).getFlightController().getState().isUltrasonicBeingUsed()) {
            result = ((Aircraft) currentDrone).getFlightController().getState().getUltrasonicHeightInMeters();
        } else {
            result = ((Aircraft) currentDrone).getFlightController().getState().getAircraftLocation().getAltitude();
        }
        return result;
    }

    /**
     * Function to calculate the distance in meters between the target square and the QR Code
     *
     * @param target the center point of the target square
     * @param qrCode the center point of the QR Code
     */
    public double[] distanceInMeters(PointF target, PointF qrCode) {
        double[] result = new double[2];
        //Calculation in meters of half de view in the screen
        double halfViewMeter = tan(toRadians(halfFOV)) * getAltitude();

        //Ratio pixels for 1m
        double ratioPixelMeter = (viewWidth / 2) / halfViewMeter;
        Log.d(TAG, "RATIO:" + ratioPixelMeter);

        //Distance in pixels from the QR Code and the target
        double deltaX = abs(qrCode.x - target.x);
        double deltaY = abs(qrCode.y - target.y);

        //Distance in meters from the QR Code and the target
        double distTargetQRX = round(deltaX / ratioPixelMeter);
        double distTargetQRY = round(deltaY / ratioPixelMeter);

        result[0] = distTargetQRX;
        result[1] = distTargetQRY;

        return result;
    }

    /**
     * Used to display custom shapes over the texture. We use this to draw the rectangles.
     *
     * @param canvas the canvas that will get the rectangles drawn on
     */
    @Override
    protected void onDraw(Canvas canvas) {
        synchronized (lock) {
            if (facesArray != null && facesArray.length > 0) {
                for (Rect target : facesArray) {
                    if (target == targetRect) {
                        paint.setColor(Color.GREEN);
                    } else {
                        paint.setColor(Color.RED);
                    }
                    canvas.drawRect(target, paint);
                }
            }
        }

        super.onDraw(canvas);
    }
}

