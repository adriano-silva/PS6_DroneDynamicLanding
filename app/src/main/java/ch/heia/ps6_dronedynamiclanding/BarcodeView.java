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
import dji.sdk.base.BaseProduct;
import dji.sdk.products.Aircraft;
import dji.sdk.sdkmanager.DJISDKManager;


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
        detector = new BarcodeDetector.Builder(context).setBarcodeFormats(Barcode.DATA_MATRIX | Barcode.QR_CODE).build();

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

        //Point in the screen for the focus
        PointF point = new PointF(1,1);
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
                Log.d(TAG, "in Thread");
                if (viewWidth > -1 && viewHeight > -1){
                    Bitmap source = cameraView.getBitmap();
                    if (source != null){

                        Log.d(TAG, "source ok");
                        Frame convFram = new Frame.Builder().setBitmap(source).build();
                        SparseArray<Barcode> barcodes = detector.detect(convFram);
                        Log.d(TAG, barcodes.toString());
                        if (barcodes.size() > 0 ) {
                            Log.d(TAG, "QR Code detected");

                            //when qr code detected, drone takes off
                            //((Aircraft) mProduct).getFlightController().turnOnMotors(null);
                            //((Aircraft) mProduct).getFlightController().startTakeoff(null);



                            Rect qrRect =  barcodes.valueAt(0).getBoundingBox();

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
         * @param halfWidth half of the view width
         * @param halfHeight hal of the view height
         * @param qrPoints the coordinates of the QR rectangle
         * @param cAlt the current altitude
         */
        private void adjustMovements(int halfWidth, int halfHeight, Point[] qrPoints, double cAlt){

            int qrWidth = qrPoints[0].x - qrPoints[1].x;
            int qrHeight = qrPoints[0].y - qrPoints[2].y ;

            int centerXQR = qrPoints[1].x - (qrWidth/2);
            int centerYQR = qrPoints[0].y - (qrHeight/2);


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

