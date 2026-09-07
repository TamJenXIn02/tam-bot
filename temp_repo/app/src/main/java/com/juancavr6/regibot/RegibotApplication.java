package com.juancavr6.regibot;

import android.app.Application;

import com.juancavr6.regibot.utils.CrashLogger;

public class RegibotApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();

        CrashLogger.init(this);
        logExitReasons();

        Thread.UncaughtExceptionHandler defaultHandler =
                Thread.getDefaultUncaughtExceptionHandler();

        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {

            CrashLogger.logCrash(throwable);

            if (defaultHandler != null) {
                defaultHandler.uncaughtException(thread, throwable);
            }
        });
    }

    private void logExitReasons() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            try {
                android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(android.content.Context.ACTIVITY_SERVICE);
                java.util.List<android.app.ApplicationExitInfo> reasons = am.getHistoricalProcessExitReasons(getPackageName(), 0, 10);
                
                java.io.File dir = new java.io.File(getFilesDir(), "crashes");
                if (!dir.exists()) dir.mkdirs();
                java.io.File file = new java.io.File(dir, "exit_reasons.txt");
                java.io.PrintWriter writer = new java.io.PrintWriter(new java.io.FileWriter(file));
                
                writer.println("--- PROCESS EXIT REASONS ---");
                for (android.app.ApplicationExitInfo reason : reasons) {
                    writer.println("Timestamp: " + reason.getTimestamp());
                    writer.println("Reason code: " + reason.getReason());
                    writer.println("Status: " + reason.getStatus());
                    writer.println("Description: " + reason.getDescription());
                    writer.println("Importance: " + reason.getImportance());
                    writer.println("---");
                }
                writer.close();
            } catch (Exception ignored) {
            }
        }
    }
}
