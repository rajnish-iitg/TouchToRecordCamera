package demo.camera.library.utils;

import android.content.Context;
import android.hardware.Camera;
import android.media.MediaActionSound;
import android.util.Log;

import com.google.common.eventbus.DeadEvent;
import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;

import java.io.IOException;
import java.util.List;

import demo.camera.library.encoder.SessionConfig;
import demo.camera.library.event.MuxerFinishedEvent;

/**
 * Created by rajnish on 9/11/15.
 */
public class AppCameraManager {
    public static final String TAG = AppCameraManager.class.getSimpleName();
    private long mRecordingStartTime;
    private long mRecordingStopTime;
    private long mElapsedTime;
    private MediaActionSound mSound;
    private boolean mIsRecording;
    private EventBus mEventBus;
    SessionConfig mLastSessionConfig;
    SessionConfig mSessionConfig;
    Context mContext;
    private Camera mCamera;

    private String mCurrentFlash;
    private String mDesiredFlash;

    private int mCurrentCamera;
    private int mDesiredCamera;
    private int mCameraPreviewWidth, mCameraPreviewHeight;

    public AppCameraManager(Context context, SessionConfig config) {
        mEventBus = new EventBus("CameraManager");
        mEventBus.register(this);
        config.getMuxer().setEventBus(mEventBus);
        mSessionConfig = mLastSessionConfig = config;
        mContext = context;
        loadMediaActionSoundPlayer();


        mCurrentFlash = Camera.Parameters.FLASH_MODE_OFF;
        mDesiredFlash = null;

        mCurrentCamera = -1;
        mDesiredCamera = Camera.CameraInfo.CAMERA_FACING_BACK;

    }

    public void loadMediaActionSoundPlayer() {
        mSound = new MediaActionSound();
        mSound.load(MediaActionSound.START_VIDEO_RECORDING);
        mSound.load(MediaActionSound.STOP_VIDEO_RECORDING);
        mSound.load(MediaActionSound.FOCUS_COMPLETE);
    }


    public void startRecording() {
        mIsRecording = true;
        mRecordingStartTime = System.currentTimeMillis();
        mSound.play(MediaActionSound.START_VIDEO_RECORDING);
    }

    public void stopRecording() {
        mIsRecording = false;
        mRecordingStopTime = System.currentTimeMillis();
        mElapsedTime += (mRecordingStopTime - mRecordingStartTime);
        mSound.play(MediaActionSound.STOP_VIDEO_RECORDING);
    }

    public long getRecordingTime() {
        long currentTime = System.currentTimeMillis();
        return mElapsedTime + (currentTime - mRecordingStartTime);
    }

    public void resetRecordingTime() {
        mElapsedTime = 0;
        mRecordingStartTime = 0;
        mRecordingStopTime = 0;
    }

    public boolean isRecording(){
        return mIsRecording;
    }

    public void toggleFlash(){
//        mCamEncoder.toggleFlashMode();
    }

    public void changeRecordingState(boolean isRecording) {
        mIsRecording = isRecording;
        if (mIsRecording) {
            startRecording();
        } else {
            stopRecording();
        }
    }

    public EventBus getEventBus() {
        return mEventBus;
    }

    public void reset(SessionConfig config) throws IOException {
        Log.d(TAG, "reset");
        mLastSessionConfig = mSessionConfig;
        mSessionConfig = config;
        if (mEventBus != null) {
            config.getMuxer().setEventBus(mEventBus);
        }
    }

    @Subscribe
    public void onDeadEvent(DeadEvent e) {
        Log.i(TAG, "DeadEvent ");
    }


    @Subscribe
    public void onMuxerFinished(MuxerFinishedEvent e) {
        Log.d(TAG, "onMuxerFinished");
        CameraUtils.moveVideoChunk(mContext, mLastSessionConfig);
    }

    public int getCameraPreviewWidth() {
        return mCameraPreviewWidth;
    }

    public int getCameraPreviewHeight() {
        return mCameraPreviewHeight;
    }

    /**
     * Opens a camera, and attempts to establish preview mode at the specified width and height.
     * <p>
     * Sets mCameraPreviewWidth and mCameraPreviewHeight to the actual width/height of the preview.
     */
    public String openCamera(int desiredWidth, int desiredHeight) {
        if (mCamera != null) {
            throw new RuntimeException("camera already initialized");
        }

        Camera.CameraInfo info = new Camera.CameraInfo();

        // Try to find a front-facing camera (e.g. for videoconferencing).
        int numCameras = Camera.getNumberOfCameras();
        int targetCameraType = mDesiredCamera;
        boolean triedAllCameras = false;
        cameraLoop:
        while (!triedAllCameras) {
            for (int i = 0; i < numCameras; i++) {
                Camera.getCameraInfo(i, info);
                if (info.facing == targetCameraType) {
                    mCamera = Camera.open(i);
                    mCurrentCamera = targetCameraType;
                    break cameraLoop;
                }
            }
            if (mCamera == null) {
                if (targetCameraType == mDesiredCamera)
                    targetCameraType = (mDesiredCamera == Camera.CameraInfo.CAMERA_FACING_BACK
                            ? Camera.CameraInfo.CAMERA_FACING_FRONT : Camera.CameraInfo.CAMERA_FACING_BACK);
                else
                    triedAllCameras = true;
            }

        }

        if (mCamera == null) {
            Log.d(TAG, "No front-facing camera found; opening default");
            mCurrentCamera = -1;
            mCamera = Camera.open();    // opens first back-facing camera
        }
        if (mCamera == null) {
            throw new RuntimeException("Unable to open camera");
        }

        Camera.Parameters parms = mCamera.getParameters();

        CameraUtils.choosePreviewSize(parms, desiredWidth, desiredHeight);

        // Give the camera a hint that we're recording video.  This can have a big
        // impact on frame rate.
        parms.setRecordingHint(true);
//
        // leave the frame rate set to default
        mCamera.setParameters(parms);

        int[] fpsRange = new int[2];
        Camera.Size mCameraPreviewSize = parms.getPreviewSize();
        parms.getPreviewFpsRange(fpsRange);
        String previewFacts = mCameraPreviewSize.width + "x" + mCameraPreviewSize.height;
        if (fpsRange[0] == fpsRange[1]) {
            previewFacts += " @" + (fpsRange[0] / 1000.0) + "fps";
        } else {
            previewFacts += " @[" + (fpsRange[0] / 1000.0) +
                    " - " + (fpsRange[1] / 1000.0) + "] fps";
        }

        mCameraPreviewWidth = mCameraPreviewSize.width;
        mCameraPreviewHeight = mCameraPreviewSize.height;
        return previewFacts;
    }


    /**
     * Request the device camera not currently selected
     * be made active. This will take effect immediately
     * or as soon as the camera preview becomes active.
     * <p/>
     * Called from UI thread
     */
    public void requestOtherCamera() {
        int otherCamera = 0;
        if (mCurrentCamera == 0)
            otherCamera = 1;
        requestCamera(otherCamera);
    }

    /**
     * Request a Camera by cameraId. This will take effect immediately
     * or as soon as the camera preview becomes active.
     * <p/>
     * Called from UI thread
     *
     * @param camera
     */
    public void requestCamera(int camera) {
        if (Camera.getNumberOfCameras() == 1) {
            Log.w(TAG, "Ignoring requestCamera: only one device camera available.");
            return;
        }
        mDesiredCamera = camera;
    }

    private void onPauseCameraSetup() {
        releaseCamera();
    }

    /**
     * Stops camera preview, and releases the camera to the system.
     */
    public void releaseCamera() {
        if (mCamera != null) {
            mCamera.stopPreview();
            mCamera.release();
            mCamera = null;
            Log.d(TAG, "releaseCamera -- done");
        }
    }

    public void toggleFlashMode() {
        String otherFlashMode = "";
        if (mCurrentFlash.equals(Camera.Parameters.FLASH_MODE_TORCH)) {
            otherFlashMode = Camera.Parameters.FLASH_MODE_OFF;
        } else {
            otherFlashMode = Camera.Parameters.FLASH_MODE_TORCH;
        }
        requestFlash(otherFlashMode);
    }

    /**
     * Sets the requested flash mode and restarts the
     * camera preview. This will take effect immediately
     * or as soon as the camera preview becomes active.
     * <p/>
     * <p/>
     * Called from UI thread
     */
    public void requestFlash(String desiredFlash) {
        mDesiredFlash = desiredFlash;
        /* If mCamera for some reason is null now flash mode will be applied
         * next time the camera opens through mDesiredFlash. */
        if (mCamera == null) {
            Log.w(TAG, "Ignoring requestFlash: Camera isn't available now.");
            return;
        }
        Camera.Parameters params = mCamera.getParameters();
        List<String> flashModes = params.getSupportedFlashModes();
        /* If the device doesn't have a camera flash or
         * doesn't support our desired flash modes return */

        Log.i(TAG, "Trying to set flash to: " + mDesiredFlash + " modes available: " + flashModes);


        if (isValidFlashMode(flashModes, mDesiredFlash) && mDesiredFlash != mCurrentFlash) {
            mCurrentFlash = mDesiredFlash;
            mDesiredFlash = null;
            try {
                params.setFlashMode(mCurrentFlash);
                mCamera.setParameters(params);
                Log.i(TAG, "Changed flash successfully!");
            } catch (RuntimeException e) {
                Log.d(TAG, "Unable to set flash" + e);
            }
        }
    }

    /**
     * @param flashModes
     * @param flashMode
     * @return returns true if flashModes aren't null AND they contain the flashMode,
     * else returns false
     */
    private boolean isValidFlashMode(List<String> flashModes, String flashMode) {
        if (flashModes != null && flashModes.contains(flashMode)) {
            return true;
        }
        return false;
    }

    public Camera getCamera() {
        return mCamera;
    }


}
