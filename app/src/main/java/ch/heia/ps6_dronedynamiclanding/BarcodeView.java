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
import android.view.TextureView;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;


import com.google.android.gms.vision.Frame;
import com.google.android.gms.vision.barcode.Barcode;
import com.google.android.gms.vision.barcode.BarcodeDetector;

import dji.common.camera.SettingsDefinitions;
import dji.common.flightcontroller.FlightControlState;
import dji.common.flightcontroller.virtualstick.FlightControlData;
import dji.common.flightcontroller.virtualstick.RollPitchControlMode;
import dji.common.flightcontroller.virtualstick.YawControlMode;
import dji.sdk.base.BaseProduct;
import dji.sdk.products.Aircraft;
import dji.sdk.sdkmanager.DJISDKManager;

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

    private Rect targetRect = new Rect(500,275,750,515);

    private boolean landMode = false;

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

    //TODO regarder si c'est bien ce consctructeur qui est appelé et non un des deux en dessus.
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

    }

    public void resume(final TextureView cameraView, int sWidth, int sHeight){
        if(getVisibility() == View.VISIBLE){
            this.viewWidth = sWidth;
            this.viewHeight = sHeight;
            this.cameraView = cameraView;
        }
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
            while (!interrupted) {
                if (viewWidth > -1 && viewHeight > -1){
                    Bitmap source = cameraView.getBitmap();
                    if (source != null){
                        //// TODO: 25.04.2017 add if (is drone flying?) 
                        Log.d(TAG, "source ok");
                        Frame convFram = new Frame.Builder().setBitmap(source).build();
                        SparseArray<Barcode> barcodes = detector.detect(convFram);
                        Log.d(TAG, barcodes.toString());
                        if (barcodes.size() > 0 ) {
                            Log.d(TAG, "QR Code detected");

                            Rect qrRect =  barcodes.valueAt(0).getBoundingBox();
                            Point[] qrPoints = barcodes.valueAt(0).cornerPoints;

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

                            //adjustMovements(qrPoints);

                        }else{
                            noQRFound();
                        }
                    }
                }
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

            int qrWitdh = qrPoints[1].x - qrPoints[1].x;
            int qrHeight = qrPoints[0].y - qrPoints[2].y;
            int qrLeft = qrPoints[0].x;
            int qrRight = qrPoints[1].x;
            int qrTop = qrPoints[0].y;
            int qrBottom = qrPoints[2].y;

            //center point of the QR Square
            Point qrMassPoint = new Point(qrPoints[1].x - (qrWitdh/2),qrPoints[0].y - (qrHeight/2));
            if(((Aircraft)currentDrone).getFlightController().isVirtualStickControlModeAvailable()){
                if(!((Aircraft)currentDrone).getFlightController().isVirtualStickAdvancedModeEnabled()){
                    ((Aircraft)currentDrone).getFlightController().setVirtualStickAdvancedModeEnabled(true);
                    Log.d(TAG, "Virtual Stick Advanced Mode enabled");
                    ((Aircraft)currentDrone).getFlightController().setRollPitchControlMode(RollPitchControlMode.VELOCITY);
                    ((Aircraft)currentDrone).getFlightController().setYawControlMode(YawControlMode.ANGULAR_VELOCITY);
                }
            }
            FlightControlData move = new FlightControlData(0,0,0,0);
            if (qrMassPoint.x < targetRect.left){

                //move drone to right
                move.setRoll(1);

            }else if(qrMassPoint.x> targetRect.right){
                //move drone to left
                move.setRoll(-1);
            }
            if (qrMassPoint.y < targetRect.bottom){
                //move drone top
            }else if (qrMassPoint.y > targetRect.top){
                //move drone bottom
            }
            if (targetRect.contains(qrLeft,qrTop,qrRight,qrBottom)){
                //decrease altitude
                move.setVerticalThrottle(-1);
            }

            ((Aircraft)currentDrone).getFlightController().sendVirtualStickFlightControlData(move,null);
            // recevoir l'instance de baseProduct
            //((Aircraft) BaseProduct).getFlightController().;
        }



        /**
         * Called when the drone is over inside the target rectangle or when the target rectangle
         * is inside the QR. It basically checks if the drone has to follow the 0 QR code or land.
         * if the drone has to follow the 0 QR code it will try to go down, closer to the QR codes.
         * @param qrValue the value inside the QR code
         * @param cAlt the current altitude
         */
        private void overOrder(int qrValue, double cAlt){

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

        /**
         * This is called to set a new altitude. Since the altitude could be used from within
         * the run function, we use a mutex to ensure that no concurency problem will happen.
         * @param alt the new altitude
         */
        public void setAltitude(double alt){

        }
    }


    public boolean isLandMode() {
        return landMode;
    }

    public void setLandMode(boolean landMode) {
        this.landMode = landMode;
    }

    public String getAltitude(){
        String result = "UltraSonic disabled";
        if (((Aircraft) currentDrone).getFlightController().getState().isUltrasonicBeingUsed()){
            result = ((Aircraft) currentDrone).getFlightController().getState().getUltrasonicHeightInMeters()+"";
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

