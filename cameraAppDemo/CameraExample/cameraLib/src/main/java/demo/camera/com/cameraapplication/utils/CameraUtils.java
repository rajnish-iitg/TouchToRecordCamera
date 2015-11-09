/*
 * Copyright 2014 Google Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package demo.camera.com.cameraapplication.utils;

import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.hardware.Camera;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import com.google.common.io.Files;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import demo.camera.com.cameraapplication.encoder.SessionConfig;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Camera-related utility functions.
 */
public class CameraUtils {
    private static final String TAG = CameraUtils.class.getSimpleName();
    private static SessionConfig sSessionConfig;

    public static boolean isKitKat() {
        return Build.VERSION.SDK_INT >= 19;
    }

    private static void setupDefaultSessionConfig(Context context, int width, int height) {
        Log.i(TAG, "Setting default SessonConfig");
        checkNotNull(context);
        String outputLocation = new File(context.getExternalFilesDir(null),
                System.currentTimeMillis() + ".mp4").getAbsolutePath();

        CameraUtils.setSessionConfig(new SessionConfig.Builder(outputLocation)
                .withVideoBitrate((int)(SessionConfig.BPP * SessionConfig.FRAME_RATE * width * height))
                .withPrivateVisibility(false)
                .withLocation(true)
                .withVideoResolution(width, height)
                .build());
    }

    public static SessionConfig getSessionConfig(Context context) {
        if (sSessionConfig == null) {
            setupDefaultSessionConfig(context, SessionConfig.sDefaultWidth,
                    SessionConfig.sDefaultHeight);
        }
        return sSessionConfig;
    }


    public static void clearSessionConfig() {
        Log.i(TAG, "Clearing SessionConfig");
        sSessionConfig = null;
    }


    public static void setSessionConfig(SessionConfig config) {
        sSessionConfig = config;
    }
    /**
     * Attempts to find a preview size that matches the provided width and height (which
     * specify the dimensions of the encoded video).  If it fails to find a match it just
     * uses the default preview size for video.
     * <p>
     * TODO: should do a best-fit match, e.g.
     * https://github.com/commonsguy/cwac-camera/blob/master/camera/src/com/commonsware/cwac/camera/CameraUtils.java
     */
    public static void choosePreviewSize(Camera.Parameters parms, int width, int height) {
        // We should make sure that the requested MPEG size is less than the preferred
        // size, and has the same aspect ratio.
        Camera.Size ppsfv = parms.getPreferredPreviewSizeForVideo();
        if (ppsfv != null) {
            Log.d(TAG, "Camera preferred preview size for video is " +
                    ppsfv.width + "x" + ppsfv.height);
        }

        //for (Camera.Size size : parms.getSupportedPreviewSizes()) {
        //    Log.d(TAG, "supported: " + size.width + "x" + size.height);
        //}

        for (Camera.Size size : parms.getSupportedPreviewSizes()) {
            if (size.width == width && size.height == height) {
                Log.w(TAG, "setting preview size to " + width + "x" + height);
                parms.setPreviewSize(width, height);
                return;
            }
        }

        Log.w(TAG, "Unable to set desired preview size to " + width + "x" + height);
        if (ppsfv != null) {
            parms.setPreviewSize(ppsfv.width, ppsfv.height);
            Log.w(TAG, "setting preview size to " + ppsfv.width + "x" + ppsfv.height);
        }
        // else use whatever the default size is
    }

    /**
     * Attempts to find a fixed preview frame rate that matches the desired frame rate.
     * <p>
     * It doesn't seem like there's a great deal of flexibility here.
     * <p>
     * TODO: follow the recipe from http://stackoverflow.com/questions/22639336/#22645327
     *
     * @return The expected frame rate, in thousands of frames per second.
     */
    public static int chooseFixedPreviewFps(Camera.Parameters parms, int desiredThousandFps) {
        List<int[]> supported = parms.getSupportedPreviewFpsRange();

        for (int[] entry : supported) {
            //Log.d(TAG, "entry: " + entry[0] + " - " + entry[1]);
            if ((entry[0] == entry[1]) && (entry[0] == desiredThousandFps)) {
                parms.setPreviewFpsRange(entry[0], entry[1]);
                return entry[0];
            }
        }

        int[] tmp = new int[2];
        parms.getPreviewFpsRange(tmp);
        int guess;
        if (tmp[0] == tmp[1]) {
            guess = tmp[0];
        } else {
            guess = tmp[1] / 2;     // shrug
        }

        Log.d(TAG, "Couldn't find match for " + desiredThousandFps + ", using " + guess);
        return guess;
    }

    public static void moveVideoChunk(Context context, SessionConfig config) {
        SessionConfig oldConfig = config;
        String path = oldConfig.getOutputPath();
        File outputDirectory = new File(context.getExternalFilesDir(null), SessionConfig.sSessionFolder);
        try {
            outputDirectory.mkdirs();
            File outPutFileName = new File(outputDirectory, System.currentTimeMillis() + ".mp4");
            Files.move(new File(path), outPutFileName);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static String stichVideos(Context context, String inputDirPath, String outputDirPath) {
        final File inputDir = new File(inputDirPath);
        final File outDir = new File(outputDirPath);

        Log.d(TAG, "path=" + outDir.toString());
        File outputFile = new File(outDir, "testOut" + System.currentTimeMillis() + ".mp4");

        try {

            // Clean Up
            for (File inputFile : inputDir.listFiles()) {
                if (!inputFile.exists()) {
                    continue ;
                }

                if (!inputFile.getAbsolutePath().endsWith(".mp4")) {
                    Log.d(TAG, "Deleting: " + inputFile.getPath());
                    inputFile.delete();
                    continue;
                }

                if (inputFile.length() == 0) {
                    Log.d(TAG, "Deleting: " + inputFile.getPath());
                    inputFile.delete();
                    continue;
                }
            }


            ArrayList<String> inputVideolist = new ArrayList<>();
            for (File inputFile : inputDir.listFiles()) {
                inputVideolist.add(inputFile.getPath());
            }

            if (inputVideolist.size() == 0) {
                Log.e(TAG, "no video's found for stiching");
                return null;
            }

            Mp4ParserUtility.stitchVideos(outputFile.getPath(), inputVideolist);

            // Removes input files
            for (File inputFile : inputDir.listFiles()) {
                if (inputFile.exists()) {
                    inputFile.delete();
                }
            }

            Intent broadcastIntent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
            broadcastIntent.setData(Uri.fromFile(outputFile));
            context.sendBroadcast(broadcastIntent);

        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }

        return outputFile.getPath();
    }

    public static float dp2px(Resources resources, float dp) {
        final float scale = resources.getDisplayMetrics().density;
        return  dp * scale + 0.5f;
    }

    public static float sp2px(Resources resources, float sp){
        final float scale = resources.getDisplayMetrics().scaledDensity;
        return sp * scale;
    }

    public static String millisecondToTimeString(long milliSeconds) {
        long seconds = milliSeconds / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        long remainderMinutes = minutes - (hours * 60);
        long remainderSeconds = seconds - (minutes * 60);

        StringBuilder timeStringBuilder = new StringBuilder();

        // Hours
        if (hours > 0) {
            if (hours < 10) {
                timeStringBuilder.append('0');
            }
            timeStringBuilder.append(hours);

            timeStringBuilder.append(':');
        }

        // Minutes
        if (remainderMinutes < 10) {
            timeStringBuilder.append('0');
        }
        timeStringBuilder.append(remainderMinutes);
        timeStringBuilder.append(':');

        // Seconds
        if (remainderSeconds < 10) {
            timeStringBuilder.append('0');
        }
        timeStringBuilder.append(remainderSeconds);

        return timeStringBuilder.toString();
    }

    public static void clearSessionFolders(Context context) {
        final File sessionFolder = new File(context.getExternalFilesDir(null),
                SessionConfig.sSessionFolder);

        if (sessionFolder != null && sessionFolder.exists()) {
            for (File file : sessionFolder.listFiles()) {
                if (file.exists()) {
                    file.delete();
                }
            }
        }


        final File sessionFolderTemp = new File(context.getExternalFilesDir(null),
                SessionConfig.sSessionFolderTemp);
        if (sessionFolderTemp != null && sessionFolderTemp.exists()) {
            for (File file : sessionFolderTemp.listFiles()) {
                if (file.exists()) {
                    file.delete();
                }
            }
        }

    }
}
