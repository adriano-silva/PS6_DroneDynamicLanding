package ch.heia.ps6_dronedynamiclanding;

import android.content.Intent;
import android.support.v4.app.FragmentActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageButton;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.MarkerOptions;

import dji.sdk.base.BaseProduct;
import dji.sdk.products.Aircraft;
import dji.sdk.sdkmanager.DJISDKManager;

public class MapActivity extends FragmentActivity implements OnMapReadyCallback, View.OnClickListener {

    private GoogleMap mMap;
    private ImageButton mBackBtn;
    private Aircraft currentDrone = null;
    private static final String TAG = MainActivity.class.getName();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        currentDrone = (Aircraft)DJISDKManager.getInstance().getProduct();
        super.onCreate(savedInstanceState);
        this.requestWindowFeature(Window.FEATURE_NO_TITLE);
        this.getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setContentView(R.layout.activity_map);
        // Obtain the SupportMapFragment and get notified when the map is ready to be used.
        SupportMapFragment mapFragment = (SupportMapFragment) getSupportFragmentManager()
                .findFragmentById(R.id.map);
        mapFragment.getMapAsync(this);
        initUI();

    }


    /**
     * Manipulates the map once available.
     * This callback is triggered when the map is ready to be used.
     * This is where we can add markers or lines, add listeners or move the camera. In this case,
     * we just add a marker near Sydney, Australia.
     * If Google Play services is not installed on the device, the user will be prompted to install
     * it inside the SupportMapFragment. This method will only be triggered once the user has
     * installed Google Play services and returned to the app.
     */
    @Override
    public void onMapReady(GoogleMap googleMap) {
        double lat = currentDrone.getFlightController().getState().getAircraftLocation().getLatitude();
        double longi = currentDrone.getFlightController().getState().getAircraftLocation().getLongitude();
        Log.d(TAG, lat+" "+longi);
        LatLng aicraftLocation = new LatLng(lat, longi);
        setMarker(googleMap, aicraftLocation);
    }

    public void setMarker(GoogleMap googleMap, LatLng coordinate){
        mMap = googleMap;

        // Add a marker at Aicraft location and move the camera
        mMap.addMarker(new MarkerOptions().position(coordinate).title("Aicraft location"));
        mMap.setMaxZoomPreference(18);
        mMap.setMinZoomPreference(1);
        mMap.moveCamera(CameraUpdateFactory.newLatLng(coordinate));
    }

    private void initUI() {
        mBackBtn = (ImageButton) findViewById(R.id.btn_back);
        mBackBtn.setOnClickListener(this);
    }

    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.btn_back: {
                this.finish();
                break;
            }
            default:
                break;
        }
    }
}
