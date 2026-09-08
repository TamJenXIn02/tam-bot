package com.juancavr6.regibot.executor;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.Log;
import android.view.Display;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.juancavr6.regibot.controller.SettingsController;
import com.juancavr6.regibot.ml.ModelHandler;
import com.juancavr6.regibot.services.ActionService;
import com.juancavr6.regibot.services.FloatingMenuService;
import com.juancavr6.regibot.utils.CrashLogger;
import com.juancavr6.regibot.utils.CustomUtils;

import java.io.IOException;

public class ActionLooper implements Runnable {

    private final String TAG = "ActionLooper";
    //AccessibilityService reference
    private final  ActionService service;

    //Running flags
    private boolean isRunning;
    private boolean isPaused;

    //Models instances
    private ModelHandler.Detector
            model_map,
            model_encounter,
            model_clickable;
    private ModelHandler.Classifier model_classifier;
    private ModelHandler.Predictor model_predictor;

    //Settings Controller instance
    private final SettingsController controller ;

    //Last screenshot made
    public Bitmap lastScreenShot;

    //Thread locker
    private final Object lock = new Object();

    private void setStatus(final String message) {
        Log.d(TAG, "STATUS: " + message);
        if (FloatingMenuService.instance != null) {
            FloatingMenuService.instance.updateStatus(message);
        }
    }

    public void performRawTap(float x, float y) {
        int targetX = Math.round(x);
        int targetY = Math.round(y);
        Path swipePath = new Path();
        swipePath.moveTo(targetX, targetY);
        swipePath.lineTo(targetX, targetY);

        Log.v(TAG, "performRawTap: " + targetX + " , " + targetY);

        GestureDescription.Builder gestureBuilder = new GestureDescription.Builder();
        gestureBuilder.addStroke(new GestureDescription.StrokeDescription(swipePath, 0, 50));
        service.dispatchGesture(gestureBuilder.build(), new AccessibilityService.GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                super.onCompleted(gestureDescription);
                synchronized(lock){lock.notify();}
            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                super.onCancelled(gestureDescription);
                synchronized(lock){lock.notify();}
            }
        }, null);
    }

    // Taps at the exact (X, Y) pixel coordinates provided by user
    public void performExactPixelTap(float userX, float userY) {
        float finalX = userX;
        float finalY = userY;
        // If system reports higher display resolution (e.g. 1080x2400 instead of 640x1400 pointer location), scale proportionally
        if (service.displayWidth > 700) {
            finalX = (userX / 640.0f) * service.displayWidth;
            finalY = (userY / 1400.0f) * service.displayHeight;
        }
        Log.d(TAG, "performExactPixelTap: User (" + userX + ", " + userY + ") -> Final (" + finalX + ", " + finalY + ")");
        performRawTap(finalX, finalY);
    }



    public ActionLooper(ActionService service){
        this.service = service;
        this.controller = SettingsController.getInstance(service);

    }
    @Override
    public void run() {
        try {
            if(model_map==null) loadModels();
            controller.reloadAllValues();

            if (FloatingMenuService.instance != null) {
                service.mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (FloatingMenuService.instance != null) {
                            FloatingMenuService.instance.onModelsLoaded();
                        }
                    }
                });
            }

            isRunning = true;
            isPaused = true;
            while (isRunning){
                if(!isPaused){
                    try {
                        Thread.sleep(controller.getCycleInterval());

                        captureScreen();
                        synchronized(lock){lock.wait(controller.getWaitTimeout());} // Wait for screenshot

                        if(lastScreenShot != null){

                            model_classifier.classify(lastScreenShot);
                            Log.d(TAG,"run(): Class  " + model_classifier.getClassName(0) + " " + model_classifier.getScore(0) );

                            if(controller.isValidClassification(model_classifier)){
                                switch (model_classifier.getClassName(0)){
                                    case "mapScreen":
                                        taskMapScreen();
                                        break;
                                    case "pokestopScreen":
                                        taskPokestopScreen();
                                        break;
                                    case "encounterScreen":
                                        taskEncounterScreen();
                                        break;
                                    case "rewardScreen":
                                        taskRewardScreen();
                                        break;
                                    case "eggScreen":
                                        taskEggScreen();
                                        break;
                                    case "menusScreen":
                                        taskMenusScreen();
                                        break;
                                    default:
                                        Log.d(TAG, "Unrecognized screen class: " + model_classifier.getClassName(0));
                                        break;
                                }

                            }
                        }
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }
            }
        } catch (Throwable t) {
            CrashLogger.logCrash(t);
            Log.e(TAG, "ActionLooper CRASHED", t);
        }
    }


    public void loadModels(){

        CrashLogger.log("Called loadModels");
        model_map = ModelHandler.buildDetector
           (service, "model_detector_map_v2.tflite",controller.getMaxResults());
        CrashLogger.log("Loaded model_map");
        model_encounter = ModelHandler.buildDetector
           (service, "model_detector_encounter.tflite",controller.getMaxResults());
        CrashLogger.log("Loaded model_encounter");
        model_clickable = ModelHandler.buildDetector
           (service, "model_detector_clickable_v2.tflite",controller.getMaxResults());
        CrashLogger.log("Loaded model_clickable");
        model_classifier = ModelHandler.buildClassifier
           (service, "model_classifier_screen_v5.tflite",controller.getMaxResults());
        CrashLogger.log("Loaded model_classifier");
        model_predictor = ModelHandler.buildPredictor
                (service,"predictor.tflite");
        CrashLogger.log("Loaded model_predictor");
    }
    public void pause(){
        this.isPaused=true;
        setStatus("Paused");
    }
    public void resume(){
        this.isPaused=false;
        setStatus("Running...");
    }
    public void stop(){
        this.isRunning=false;
        setStatus("Stopped");
    }

    private void taskMapScreen() throws InterruptedException{
        setStatus("Scanning map...");
        model_map.detect(lastScreenShot);
        Log.d(TAG , "run(): Scanning the map : " + model_map.getDetectionList().toString());
        int objectMatchIndex = controller.lookForMatchAtMap(model_map);
        if(objectMatchIndex > -1){
            String matchClass = model_map.getClassName(objectMatchIndex);
            Log.d(TAG,"run(): Proceeding with: " + matchClass);
            if ("pokestop".equalsIgnoreCase(matchClass) || "actionElement_pokestop".equalsIgnoreCase(matchClass)) {
                setStatus("Found PokéStop! Spinning...");
            } else {
                setStatus("Found Pokémon! Entering...");
            }
            performActionTap(model_map.getBoundingBox(objectMatchIndex));
            synchronized(lock){lock.wait(controller.getWaitTimeout());}
        }
    }
    private void taskPokestopScreen() throws InterruptedException{
        setStatus("Spinning PokéStop...");
        performActionSpinDisc();
        synchronized(lock){lock.wait(controller.getWaitTimeout());}
    }
    private void taskEncounterScreen() throws InterruptedException{
        setStatus("Aiming Pokéball...");
        final float[] pokeballCoords = resolvePokeballCoords();

        if(pokeballCoords[1] != 0.0f){
            // Hold the Pokeball and look for the bounding box
            service.mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    performActionHold(pokeballCoords);
                }
            });
            service.mainHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    captureScreen();
                }},50);
            synchronized(lock){lock.wait(controller.getWaitTimeout());}

            model_encounter.detect(lastScreenShot);
            Log.d(TAG,"run(): Finding BoundingBox " + model_encounter.getDetectionList());

            int boundingBoxIndex = controller.lookForMatchAtEncounter(model_encounter,"boundingBox");
            int dynamicBoxIndex = controller.lookForMatchAtEncounter(model_encounter,"dynamicBox");

            // If bounding box is found, proceed with throw immediately for speed
            if(boundingBoxIndex > -1){

                manageThrow(boundingBoxIndex,pokeballCoords);

            }
        }
    }
    private void taskRewardScreen() throws InterruptedException{
        setStatus("Catch Success! Clicking OK...");
        Log.d(TAG, "taskRewardScreen(): Clicking green OK button");
        model_clickable.detect(lastScreenShot);
        int clickableIndex = controller.lookForMatchAtClickable(model_clickable,"clickable");
        Log.d(TAG,clickableIndex + " Finding Clickable:" + model_clickable.getDetectionList());
        if (clickableIndex > -1){
            Log.d(TAG,"run(): Clickable Found! ");
            performActionTap(model_clickable.getBoundingBox(clickableIndex));
            synchronized(lock){lock.wait(controller.getWaitTimeout());}
        } else {
            // Fallback directly to the center of the green OK button on reward screen
            Log.d(TAG, "taskRewardScreen(): Clicking default OK button coordinates (0.50, 0.67)");
            performRawTap(service.displayWidth * 0.50f, service.displayHeight * 0.67f);
            Thread.sleep(300);
        }

        if (controller.shouldAutoTransfer()) {
            setStatus("Waiting for Summary Screen...");
            // Summary screen takes ~3.5s to fully load and become interactive after OK is clicked
            Thread.sleep(3500);
            taskAutoTransfer();
        } else {
            Thread.sleep(500);
            // Dismiss summary screen to return to map (checkmark at bottom center)
            performRawTap(service.displayWidth * 0.50f, service.displayHeight * 0.94f);
        }
    }
    private void taskEggScreen() {
        setStatus("Hatching Egg...");
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
        RectF centerBoxDisplay = new RectF(0,0,service.displayWidth,service.displayHeight);
        performActionTap(centerBoxDisplay);
    }
    private void taskMenusScreen() throws InterruptedException {
        model_clickable.detect(lastScreenShot);
        int passengerIndex = controller.lookForMatchAtClickable(model_clickable,"passenger");
        Log.d(TAG,passengerIndex + " Discarding Passenger Screen:" + model_clickable.getDetectionList());
        if (passengerIndex > -1){
            setStatus("Passenger alert: Dismissing...");
            Log.d(TAG,"run(): */* You're going too fast! ");
            int clickableIndex = controller.lookForMatchAtClickable(model_clickable,"clickable");
            Log.d(TAG,clickableIndex + " Finding Clickable:" + model_clickable.getDetectionList());
            if (clickableIndex > -1){
                Log.d(TAG,"run(): Clickable Found! ");
                performActionTap(model_clickable.getBoundingBox(clickableIndex));
                synchronized(lock){lock.wait(controller.getWaitTimeout());}
            } else {
                performRawTap(service.displayWidth * 0.50f, service.displayHeight * 0.58f);
            }
        } else {
            // Pokémon Summary Screen / Menu
            if (controller.shouldAutoTransfer()) {
                taskAutoTransfer();
            } else {
                // Dismiss summary screen to return to map (checkmark at bottom center)
                performRawTap(service.displayWidth * 0.50f, service.displayHeight * 0.94f);
            }
        }
    }

    private void taskAutoTransfer() throws InterruptedException {
        Log.d(TAG, "taskAutoTransfer(): Auto Transfer sequence executing.");
        
        // Step 1: Tap Hamburger Menu Icon (Exact user position: X=590, Y=1333)
        setStatus("Auto-Transfer: Tapping menu (590, 1333)...");
        performExactPixelTap(590f, 1333f);
        // Wait for menu to fully slide up
        Thread.sleep(1200);
        
        // Step 2: Tap Transfer Menu Item (Exact user position: X=570, Y=1212)
        setStatus("Auto-Transfer: Tapping Transfer (570, 1212)...");
        performExactPixelTap(570f, 1212f);
        // Wait for YES/NO confirmation dialog to appear
        Thread.sleep(1200);
        
        // Step 3: Tap YES Confirmation Button (Exact user position: X=315, Y=702)
        setStatus("Auto-Transfer: Confirming YES (315, 702)...");
        performExactPixelTap(315f, 702f);
        // Wait for transfer to complete and toast to disappear
        Thread.sleep(1500);
        
        setStatus("Transfer Complete!");
        Log.d(TAG, "taskAutoTransfer(): Transfer completed, returning to map.");
    }

    private void captureScreen (){
        service.takeScreenshot(Display.DEFAULT_DISPLAY, service.mainExecutor, new AccessibilityService.TakeScreenshotCallback() {
            @Override
            public void onSuccess(@NonNull AccessibilityService.ScreenshotResult screenshotResult) {

                try {
                    Log.w(TAG, "Screen Capture Completed");
                    Bitmap screenShot = Bitmap.wrapHardwareBuffer
                            (screenshotResult.getHardwareBuffer(), screenshotResult.getColorSpace());
                    screenShot = screenShot.copy(Bitmap.Config.ARGB_8888, true);

                    lastScreenShot = screenShot;
                    screenshotResult.getHardwareBuffer().close();
                    synchronized (lock) {
                        lock.notify();
                    }
                } catch (IllegalArgumentException e) {
                    Log.e(TAG, "Capture conversion Failed");                }

            }

            @Override
            public void onFailure(int i) {
                Log.e(TAG, "Capture Failed");
                synchronized (lock) {
                    lock.notify();
                }
            }
        });

    }
    private void performActionTap(RectF boundingBox) {

        int x = Math.round(boundingBox.centerX());
        int y = Math.round(boundingBox.centerY());

        if(!CustomUtils.isValidSectionForTap(x,y, service.displayWidth, service.displayHeight))
            return;

        Path swipePath = new Path();
        swipePath.moveTo(x, y);
        swipePath.lineTo(x, y);

        Log.v(TAG,"Tapping -->" + x + " , " + y);

        GestureDescription.Builder gestureBuilder = new GestureDescription.Builder();
        gestureBuilder.addStroke(new GestureDescription.StrokeDescription(swipePath, 0, 10));
        service.dispatchGesture(gestureBuilder.build(), new AccessibilityService.GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                super.onCompleted(gestureDescription);
                synchronized(lock){lock.notify();}

            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                super.onCancelled(gestureDescription);
                synchronized(lock){lock.notify();}

            }
        }, null);

    }
    private void performActionSpinDisc() {

        int x = Math.round((float)service.displayWidth/2);
        int y = Math.round((float)service.displayHeight/2);

        Path swipePath = new Path();
        swipePath.moveTo(x, y);
        swipePath.lineTo(x+200, y);

        GestureDescription.Builder gestureBuilder = new GestureDescription.Builder();
        gestureBuilder.addStroke(new GestureDescription.StrokeDescription(swipePath, 0, 300));

        service.dispatchGesture(gestureBuilder.build(), new AccessibilityService.GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                super.onCompleted(gestureDescription);
                try {
                    Thread.sleep(1000);
                }catch (InterruptedException e){Thread.currentThread().interrupt();}

                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);

                synchronized(lock){lock.notify();}


            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                super.onCancelled(gestureDescription);
                synchronized(lock){lock.notify();}

            }
        }, null);
    }
    private void performActionHold(float[] coords) {

        int x = Math.round(coords[0]);
        int y = Math.round(coords[1]);
        Path swipePath = new Path();
        swipePath.moveTo(x, y);
        swipePath.lineTo(x, y);
        GestureDescription.Builder gestureBuilder = new GestureDescription.Builder();
        gestureBuilder.addStroke(new GestureDescription.StrokeDescription(swipePath, 0, 4000));

        service.dispatchGesture(gestureBuilder.build(), new AccessibilityService.GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                super.onCompleted(gestureDescription);
                //synchronized(lock){lock.notify();}

            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                super.onCancelled(gestureDescription);
                Log.e(TAG,"Gesture Hold Cancelled " + gestureDescription.toString());
                //synchronized(lock){lock.notify();}

            }
        }, null);


    }
    private void performActionThrow(float[] pokeballCoords,RectF boundingBox ,float deltaY, long duration){
        Path swipePath = new Path();
        swipePath.moveTo(pokeballCoords[0], pokeballCoords[1]);
        float finalX = boundingBox.centerX();
        float finalY = pokeballCoords[1]+deltaY;
        swipePath.lineTo(finalX, finalY);

        GestureDescription.Builder gestureBuilder = new GestureDescription.Builder();
        gestureBuilder.addStroke(new GestureDescription.StrokeDescription(swipePath, 50, Math.round(duration*controller.getThrowBoostDurationFactor())));

        service.dispatchGesture(gestureBuilder.build(), new AccessibilityService.GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                super.onCompleted(gestureDescription);

                synchronized(lock){lock.notify();}

            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                super.onCancelled(gestureDescription);

                synchronized(lock){lock.notify();}

            }
        }, null);
    }
    private void performActionFastThrow(float[] pokeballCoords,RectF boundingBox ,float deltaY, long duration){

        Path swipePath = new Path();
        swipePath.moveTo(pokeballCoords[0], pokeballCoords[1]);
        float finalX = boundingBox.centerX();
        float finalY = pokeballCoords[1]+deltaY;
        swipePath.lineTo(finalX, finalY);

        Path berryPath = new Path();
        berryPath.moveTo(service.displayWidth/8f, service.displayHeight-(service.displayHeight/16f));
        berryPath.lineTo(service.displayWidth/8f, service.displayHeight/2f);

        GestureDescription.Builder gestureBuilder = new GestureDescription.Builder();
        gestureBuilder.addStroke(new GestureDescription.StrokeDescription(berryPath, 20, 400));
        gestureBuilder.addStroke(new GestureDescription.StrokeDescription(swipePath, 40, Math.round(duration*controller.getThrowBoostDurationFactor())));


        service.dispatchGesture(gestureBuilder.build(), new AccessibilityService.GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                super.onCompleted(gestureDescription);

                // Los timings son puro freestyle
                service.mainHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
                    }},450);
                service.mainHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
                    }},750);


            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                super.onCancelled(gestureDescription);
                Log.e(TAG,"Gesture FastThrow Cancelled " + gestureDescription.toString());

                synchronized(lock){lock.notify();}

            }
        }, null);
    }

    private float[] resolvePokeballCoords() {
        final float[] coords = new float[2];

        if (controller.shouldFixedPokeball()) {
            Log.d(TAG, "run(): Using fixed Pokeball coords");
            return getFixedCoords();
        }
        if (!controller.shouldSaveCoords() || !controller.isPokeballCoordsSet()) {
            model_encounter.detect(lastScreenShot);
            Log.d(TAG, "run(): Finding Pokeball: " + model_encounter.getDetectionList());

            int pokeballIndex = controller.lookForMatchAtEncounter(model_encounter, "pokeball");

            if (pokeballIndex > -1) {
                float centerX = model_encounter.getBoundingBox(pokeballIndex).centerX();
                float centerY = model_encounter.getBoundingBox(pokeballIndex).centerY();

                if (CustomUtils.isValidSectionForPokeball(centerX, centerY, service.displayWidth, service.displayHeight)) {
                    coords[0] = centerX;
                    coords[1] = centerY;

                    if (controller.shouldSaveCoords()) {
                        controller.setPokeballCoords(coords);
                    }
                    Log.d(TAG, "run(): Using detected Pokeball coords");
                    return coords;
                }
            }

            // fallback to fixed coords if detection failed
            Log.d(TAG, "run(): Using (fallback) fixed Pokeball coords");
            return getFixedCoords();
        }
        Log.d(TAG, "run(): Using saved Pokeball coords");
        return controller.getPokeballCoords();
    }
    private float[] getFixedCoords() {
        return new float[] {
                service.displayWidth / 2f,
                service.displayHeight - (service.displayHeight / 16f)
        };
    }
    private void manageThrow(int boundingBoxIndex,float[] pokeballCoords) throws InterruptedException {
        RectF boundingBox = model_encounter.getBoundingBox(boundingBoxIndex);
        float[] input = {boundingBox.centerX(),boundingBox.centerY(),
                boundingBox.width(),boundingBox.height()};
        float[] normalizedInput = model_predictor.getNormalizedInput(
                service.displayWidth,service.displayHeight,input);

        model_predictor.predict(normalizedInput);

        if(controller.shouldFastCatch()){
            setStatus("Fast Catch: Throwing...");
            performActionTap(new RectF(0,0,service.displayWidth,service.displayHeight));
            synchronized(lock){lock.wait(controller.getWaitTimeout());}

            service.mainHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    performActionFastThrow(pokeballCoords, boundingBox,
                            model_predictor.getDenormalizedDeltaY(service.displayHeight),
                            (long)model_predictor.getDenormalizedDuration());
                }},250);
            Thread.sleep(1100);
            setStatus("Fast Catch: Exiting...");
        }
        else{
            setStatus("Throwing Pokéball...");
            performActionThrow(pokeballCoords, boundingBox,
                    model_predictor.getDenormalizedDeltaY(service.displayHeight),
                    (long)model_predictor.getDenormalizedDuration());
            synchronized(lock){lock.wait(controller.getWaitTimeout());}

            setStatus("Ball thrown!");
            Thread.sleep(800);
        }
    }
}
