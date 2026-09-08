package com.juancavr6.regibot.services;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Binder;
import android.os.IBinder;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.cardview.widget.CardView;
import androidx.core.content.ContextCompat;

import com.juancavr6.regibot.R;
import com.juancavr6.regibot.ui.fragment.HomeFragment;

public class FloatingMenuService extends Service implements View.OnClickListener{

    public static FloatingMenuService instance = null;

    private final IBinder mBinder = new LocalBinder();
    Callbacks fragment;

    private boolean isRunning = false;

    private WindowManager mWindowManager;
    private View myFloatingView;

    private ProgressBar loader;
    private ImageView mainIcon;
    private CardView mainButton;
    private CardView destroyButton;
    private CardView trackerButton;
    private android.widget.TextView statusText;
    
    private View coordinateOverlay;
    private boolean isTrackerActive = false;

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                fragment.updateClient(false);
            }
        }
    };

    public FloatingMenuService() {


    }



    @Override
    public IBinder onBind(Intent intent) {return mBinder;}
    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_EXPORTED);
    }
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        if( loader != null && intent != null && intent.getBooleanExtra("loadedNotification", false)) {
            loader.setVisibility(View.GONE);
            mainButton.setVisibility(View.VISIBLE);
            destroyButton.setVisibility(View.VISIBLE);
            trackerButton.setVisibility(View.VISIBLE);

            fragment.updateClient(true);
            Toast.makeText(this, getString(R.string.displayText_ready), Toast.LENGTH_SHORT).show();

        }
        else{
            //getting the widget layout from xml using layout inflater
            myFloatingView = LayoutInflater.from(this).inflate(R.layout.floating_menu, null);


            int layout_parms = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;


            //setting the layout parameters
            final WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    layout_parms,
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.TOP;

            //getting windows services and adding the floating view to it
            mWindowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
            mWindowManager.addView(myFloatingView, params);

            //adding an touchlistener to make drag movement of the floating widget
            myFloatingView.findViewById(R.id.thisIsAnID).setOnTouchListener(new View.OnTouchListener() {
                private int initialX;
                private int initialY;
                private float initialTouchX;
                private float initialTouchY;
                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    switch (event.getAction()) {
                        case MotionEvent.ACTION_DOWN:
                            initialX = params.x;
                            initialY = params.y;
                            initialTouchX = event.getRawX();
                            initialTouchY = event.getRawY();
                            return true;

                        case MotionEvent.ACTION_UP:

                            return true;

                        case MotionEvent.ACTION_MOVE:
                            //this code is helping the widget to move around the screen with fingers
                            params.x = initialX + (int) (event.getRawX() - initialTouchX);
                            params.y = initialY + (int) (event.getRawY() - initialTouchY);
                            mWindowManager.updateViewLayout(myFloatingView, params);
                            return true;
                    }
                    return false;
                }
            });

            //getting the widgets reference from xml layout
            loader = myFloatingView.findViewById(R.id.loader);
            mainIcon = myFloatingView.findViewById(R.id.mainIcon);
            mainButton =  myFloatingView.findViewById(R.id.main);
            mainButton.setOnClickListener(this);
            destroyButton = myFloatingView.findViewById(R.id.destroy);
            destroyButton.setOnClickListener(this);
            trackerButton = myFloatingView.findViewById(R.id.trackerToggle);
            trackerButton.setOnClickListener(this);
            statusText = myFloatingView.findViewById(R.id.statusText);
        }

           return START_NOT_STICKY;
    }

    public class LocalBinder extends Binder {
        public FloatingMenuService getServiceInstance(){
            return FloatingMenuService.this;
        }
    }

    public void registerClient(HomeFragment fragment){
        this.fragment = fragment;
    }

    @Override
    public void onClick(View v) {
        int id = v.getId();
        if (id == R.id.main) {
            String action = isRunning ? "pause" : "resume";
            isRunning = !isRunning;
            updateMainButton();
            if (ActionService.instance != null) {
                ActionService.instance.handleAction(action);
            }
        } else if (id == R.id.destroy) {
            isRunning = false;
            fragment.updateClient(false);
        } else if (id == R.id.trackerToggle) {
            toggleCoordinateTracker();
        }
    }
    
    private void toggleCoordinateTracker() {
        if (!isTrackerActive) {
            isTrackerActive = true;
            trackerButton.setCardBackgroundColor(Color.parseColor("#4CAF50")); // Green
            updateStatus("Tap screen to get coordinates");
            
            if (coordinateOverlay == null) {
                coordinateOverlay = new View(this);
                coordinateOverlay.setBackgroundColor(Color.parseColor("#44000000")); // semi-transparent
                coordinateOverlay.setOnTouchListener(new View.OnTouchListener() {
                    @Override
                    public boolean onTouch(View view, MotionEvent event) {
                        if (event.getAction() == MotionEvent.ACTION_DOWN) {
                            float x = event.getRawX();
                            float y = event.getRawY();
                            String coordStr = "X=" + x + ", Y=" + y;
                            
                            // Copy to clipboard
                            android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                            android.content.ClipData clip = android.content.ClipData.newPlainText("PokemonCoords", coordStr);
                            if (clipboard != null) {
                                clipboard.setPrimaryClip(clip);
                            }
                            
                            Toast.makeText(FloatingMenuService.this, "Copied: " + coordStr, Toast.LENGTH_LONG).show();
                            updateStatus("Copied: " + coordStr);
                        }
                        return true; // intercept touches
                    }
                });
            }
            
            WindowManager.LayoutParams overlayParams = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT);
            overlayParams.gravity = Gravity.TOP | Gravity.START;
            mWindowManager.addView(coordinateOverlay, overlayParams);
            
        } else {
            isTrackerActive = false;
            trackerButton.setCardBackgroundColor(Color.parseColor("#979797")); // Gray
            updateStatus("Tracker Disabled");
            if (coordinateOverlay != null) {
                mWindowManager.removeView(coordinateOverlay);
            }
        }
    }


    @Override
    public void onDestroy() {
        instance = null;
        if (ActionService.instance != null) {
            ActionService.instance.handleAction("destroy");
        }

        super.onDestroy();
        if (isTrackerActive && coordinateOverlay != null) {
            try {
                mWindowManager.removeView(coordinateOverlay);
            } catch (Exception ignored) {}
            isTrackerActive = false;
        }
        mWindowManager.removeView(myFloatingView);
        unregisterReceiver(screenReceiver);
    }

    public void onModelsLoaded() {
        if (loader != null) {
            loader.setVisibility(View.GONE);
            mainButton.setVisibility(View.VISIBLE);
            destroyButton.setVisibility(View.VISIBLE);
            if (trackerButton != null) {
                trackerButton.setVisibility(View.VISIBLE);
            }
            if (statusText != null) {
                statusText.setVisibility(View.VISIBLE);
                statusText.setText("Ready");
            }

            if (fragment != null) {
                fragment.updateClient(true);
            }
            Toast.makeText(this, getString(R.string.displayText_ready), Toast.LENGTH_SHORT).show();
        }
    }

    public void updateStatus(final String message) {
        if (statusText != null) {
            statusText.post(new Runnable() {
                @Override
                public void run() {
                    if (statusText != null) {
                        statusText.setVisibility(View.VISIBLE);
                        statusText.setText(message);
                    }
                }
            });
        }
    }

    private void updateMainButton() {
        if(!isRunning){
            mainButton.setCardBackgroundColor(Color.parseColor("#979797"));
            mainIcon.setImageResource(android.R.drawable.ic_media_play);
            updateStatus("Paused");
        }else{
            mainButton.setCardBackgroundColor(Color.parseColor("#C4C4C4"));
            mainIcon.setImageResource(android.R.drawable.ic_media_pause);
            updateStatus("Running...");
        }
    }

    public interface Callbacks{
        void updateClient(boolean data);
    }


}