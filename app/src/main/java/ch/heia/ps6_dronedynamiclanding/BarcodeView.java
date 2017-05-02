package ch.heia.ps6_dronedynamiclanding;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.Rect;
import android.nfc.Tag;
import android.os.Handler;
import android.os.Looper;
import android.support.annotation.Nullable;
import android.util.AttributeSet;
import android.util.Log;
import android.util.SparseArray;
import android.view.Display;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;


import com.google.android.gms.vision.Frame;
import com.google.android.gms.vision.barcode.Barcode;
import com.google.android.gms.vision.barcode.BarcodeDetector;

import dji.common.camera.SettingsDefinitions;
import dji.common.error.DJIError;
import dji.common.flightcontroller.FlightControlState;
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

import static android.R.attr.cycles;
import static android.R.attr.screenSize;
import static android.R.attr.value;


public class BarcodeView extends View{

    private Context ctx;
    public CascadingThread mainThread = null;

    private TextureView cameraView = null;
    private BaseProduct currentDrone = null;

    private BarcodeDetector detector;
    private Paint paint;
    private Rect[] facesArray = null;
    private final Object lock = new Object(); //Drawing mutex

    private int viewWidth = -1;
    private int viewHeight = -1;

    private final int skipFrameLimit = 200;

    private Rect targetRect = new Rect(500,275,750,515);

    private boolean landMode = false;

    private Float speed = 0.2f;

    private static final int TIMEBETWEENSCAN = 100; //in ms

    private int framesWithoutQR = 0; //The number of tests without a successful detection


    private static final String TAG = BarcodeView.class.getName();


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

    private void init(Context context){
        detector = new BarcodeDetector.Builder(context).setBarcodeFormats(Barcode.QR_CODE).build();

        if(!detector.isOperational()){
            Toast.makeText(context, "Could not set up the QR detector!", Toast.LENGTH_SHORT).show();
            return;
        }
        ctx=context;

        paint = new Paint();
        paint.setAntiAlias(true);
        paint.setColor(Color.GREEN);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(4f);

        currentDrone = DJISDKManager.getInstance().getProduct();

        currentDrone.getCamera().setFocusMode(SettingsDefinitions.FocusMode.AUTO, null);
        currentDrone.getCamera().setExposureMode(SettingsDefinitions.ExposureMode.PROGRAM, null);


        //Point in the screen for the focus
        PointF point = new PointF(600,600);
        currentDrone.getCamera().setFocusTarget(point, null);

        //Getting the coordinates for the Target rectangle
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        Display display = wm.getDefaultDisplay();
        Point size = new Point();
        display.getSize(size);
        targetRect = new Rect((size.x/2)-125,(size.y/2)-125,(size.x/2)+125,(size.y/2)+125);

    }

    public void resume(final TextureView cameraView, int sWidth, int sHeight){
        if(getVisibility() == View.VISIBLE){


        }
        //this.viewWidth = sWidth;
        //this.viewHeight = sHeight;
        this.cameraView = cameraView;
        mainThread = new CascadingThread(ctx);
        mainThread.start();
    }

    public void pause(){
        if(getVisibility() == View.VISIBLE){
            mainThread.interrupt();

            try {
                mainThread.join();
            }catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    public class CascadingThread extends Thread{
        private final Handler handler;
        boolean interrupted = false;

        private CascadingThread(final Context ctx) {
            handler = new Handler(ctx.getMainLooper());
        }

        public void interrupt() {
            interrupted = true;
        }

        @Override
        public void run() {

            int halfWidth = targetRect.width()/2;
            int halfHeight = targetRect.height()/2;
            Log.d(TAG, "Thread started");
            int counter = 0;

            while (!interrupted) {

                /*if (counter == skipFrameLimit) {
                    counter = 0;*/
                    if (viewWidth > -1 && viewHeight > -1) {
                        Bitmap source = cameraView.getBitmap();

                        if (source != null) {
                            if (getAltitude()<=0.4){
                                land();
                            }
                            //// TODO: 25.04.2017 add if (is drone flying?)
                            Frame convFram = new Frame.Builder().setBitmap(source).build();
                            SparseArray<Barcode> barcodes = detector.detect(convFram);

                            int index = -1;
                            int lastVal = 100;
                            for(int i = 0; i < barcodes.size(); i++){
                                int cVal = 100;
                                try {
                                    cVal = Integer.parseInt(barcodes.valueAt(i).rawValue);
                                }catch (NumberFormatException e){

                                }
                                if(cVal < lastVal){
                                    index = i;
                                    lastVal = cVal;
                                }
                            }


                            if (barcodes.size() > 0) {
                                framesWithoutQR = 0;
                                //Log.d(TAG, barcodes.valueAt(0).displayValue);
                                Log.d(TAG, "QR Code detected");

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
                                //TODO : tester si qr code dans targetRect, ou targetRect dans le QRCode. Sinon, adjustMovements. Si oui, overOrder

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
                //}
                //counter++;
            }
        }
        /**
         * Called when the drone needs to move closer to a QR code, it is not very precise since it
         * is a triangulation from the screen to the 3D world. The main trick is to use small value
         * so the drone will be more precise. It corrects the movements on every axis, still it
         * does not affect the yaw.
         * @param qrPoints the coordinates of the QR rectangle
         */
        private void adjustMovements(Point[] qrPoints){

            int qrLeft = qrPoints[0].x;
            int qrRight = qrPoints[1].x;
            int qrTop = qrPoints[0].y;
            int qrBottom = qrPoints[2].y;

            int count = 0;
            for (Point point:qrPoints) {
                Log.v(TAG, "Point"+count+": ("+point.x+","+point.y+")");
                count++;
            }


            //Log.d(TAG, "adjustMovement");
            //center point of the QR Square

            Rect qrRectangle = new Rect(qrLeft,qrTop,qrRight,qrBottom);

            int cX = qrRectangle.centerX();
            int cY = qrRectangle.centerY();


            //preparations in order to get the Virtual Stick Mode available
            ((Aircraft)currentDrone).getFlightController().setVirtualStickModeEnabled(true,null);
            ((Aircraft)currentDrone).getFlightController().setFlightOrientationMode(FlightOrientationMode.AIRCRAFT_HEADING,null);
            ((Aircraft)currentDrone).getFlightController().setTerrainFollowModeEnabled(false,null);
            ((Aircraft)currentDrone).getFlightController().setTripodModeEnabled(false,null);
            Log.d(TAG,"something running: "+DJISDKManager.getInstance().getMissionControl().getRunningElement());


            ((Aircraft)currentDrone).getFlightController().setRollPitchControlMode(RollPitchControlMode.VELOCITY);
            ((Aircraft)currentDrone).getFlightController().setYawControlMode(YawControlMode.ANGULAR_VELOCITY);
            Log.d(TAG, "Virtual stick enabled:"+((Aircraft)currentDrone).getFlightController().isVirtualStickAdvancedModeEnabled());

            if(((Aircraft)currentDrone).getFlightController().isVirtualStickControlModeAvailable()){
                Log.d(TAG, "virtual stick control mode available");
                if(!((Aircraft)currentDrone).getFlightController().isVirtualStickAdvancedModeEnabled()){
                    ((Aircraft)currentDrone).getFlightController().setVirtualStickAdvancedModeEnabled(true);
                    Log.d(TAG, "Virtual Stick Advanced Mode enabled");
                }
                //Setting the control modes for Roll, Pitch and Yaw
                ((Aircraft)currentDrone).getFlightController().setRollPitchControlMode(RollPitchControlMode.VELOCITY);
                ((Aircraft)currentDrone).getFlightController().setYawControlMode(YawControlMode.ANGULAR_VELOCITY);
                ((Aircraft)currentDrone).getFlightController().setVerticalControlMode(VerticalControlMode.VELOCITY);
                ((Aircraft)currentDrone).getFlightController().setRollPitchCoordinateSystem(FlightCoordinateSystem.BODY);
                ((Aircraft)currentDrone).getFlightController().getFlightAssistant().setLandingProtectionEnabled(false,null);
            }
            FlightControlData move = new FlightControlData(0,0,0,0);
            if (cX < targetRect.left){

                //move drone to right
                Log.i(TAG,"move to left");
                move.setPitch(-speed);

            }else if(cX> targetRect.right){
                Log.i(TAG,"move to right");
                //move drone to left
                move.setPitch(speed);
            }
            if (cY < targetRect.top){
                Log.i(TAG,"move top");
                //move drone top
                move.setRoll(speed);
            }else if (cY > targetRect.bottom){
                //move drone bottom
                Log.i(TAG,"move bottom");

                move.setRoll(-speed);
            }
           // if (targetRect.contains(qrLeft,qrTop,qrRight,qrBottom) || qrRectangle.contains(targetRect) || getAltitude()==0.4){
            if (targetRect.contains(cX,cY)){
                    if(landMode) {
                        //decrease altitude
                        Log.d(TAG, "Decrasing altitude");

                        move.setVerticalThrottle(-speed);
                    }
                }

            Log.d(TAG,"Virtual stick mode available?:"+((Aircraft)currentDrone).getFlightController().isVirtualStickControlModeAvailable());
            ((Aircraft)currentDrone).getFlightController().sendVirtualStickFlightControlData(move, new CommonCallbacks.CompletionCallback() {
                @Override
                public void onResult(DJIError arg0) {
                    if(arg0!=null) {
                        Log.e(TAG, arg0.toString());
                    }
                }
            });
        }



        /**
         * Called when the drone is over inside the target rectangle or when the target rectangle
         * is inside the QR. It basically checks if the drone has to follow the 0 QR code or land.
         * if the drone has to follow the 0 QR code it will try to go down, closer to the QR codes.
         * @param qrValue the value inside the QR code
         * @param cAlt the current altitude
         */
        private void overOrder(int qrValue, double cAlt){

            if(landMode){
                if (qrValue == 0){
                    //currentDrone.land();
                }else {
                    //float down = (float)-(DELTAMETERMOVEMENT*cAlt);
                    //currentDrone.moveDroneInMeters(0f,0f,down,0f); //try do go down
                }
            }
        }

        private void land (){
            FlightControlData move = new FlightControlData(0,0,0,0);
            if(landMode){
                move.setVerticalThrottle(-speed);
                ((Aircraft)currentDrone).getFlightController().sendVirtualStickFlightControlData(move,null);
            }
        }

        /**
         * Called when a frame does not contain any QR code, this increase the number of frame
         * without QR code and delete the previous rectangles drawn on the canvas. When a certain
         * amount of frames without QR code are reached the autopilot is restarted.
         * Since the two functionnalities (autonomous flight & detection of QR code) have not
         * been tested together the startautopilot is commented and instead we set a default
         * movement as follow : currentDrone.moveDroneInMeters(0f,0f,0f,0f);
         */
        private void noQRFound(){
            framesWithoutQR++;
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
            if (framesWithoutQR > 50){
                currentDrone.getCamera().setFocusTarget(new PointF(targetRect.centerX(),targetRect.centerY()), null);
            }
        }

        private void runOnUiThread(Runnable r) {
            handler.post(r);
        }

        /**
         * This is called to set a new altitude. Since the altitude could be used from within
         * the run function, we use a mutex to ensure that no concurency problem will happen.
         * @param alt the new altitude
         */
        public void setAltitude(double alt){

        }
    }

    public void setViewWidthHeight(int sWidth, int sHeight){
        this.viewWidth = sWidth;
        this.viewHeight = sHeight;
    }


    public boolean isLandMode() {
        return landMode;
    }

    public void setLandMode(boolean landMode) {
        this.landMode = landMode;
    }

    public Float getAltitude(){
        Float result =-1F;
        if (((Aircraft) currentDrone).getFlightController().getState().isUltrasonicBeingUsed()){
            result = ((Aircraft) currentDrone).getFlightController().getState().getUltrasonicHeightInMeters();
            if (result<0f){
                result = ((Aircraft) currentDrone).getFlightController().getState().getAircraftLocation().getAltitude();
            }

        }
        return result;
    }

    /**
     * Used to display custom shapes over the texture. We use this to draw the rectangles.
     * @param canvas the canvas that will get the rectangles drawn on
     */
    @Override
    protected void onDraw(Canvas canvas) {
        synchronized(lock) {
            if (facesArray != null && facesArray.length > 0) {
                for (Rect target : facesArray) {
                    if(target == targetRect){
                        paint.setColor(Color.GREEN);
                    }else{
                        paint.setColor(Color.RED);
                    }
                    canvas.drawRect(target, paint);
                }
            }
        }

        super.onDraw(canvas);
    }
}

