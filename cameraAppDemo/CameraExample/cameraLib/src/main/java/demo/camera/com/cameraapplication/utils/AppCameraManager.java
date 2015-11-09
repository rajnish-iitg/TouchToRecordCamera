package demo.camera.com.cameraapplication.utils;

import android.content.Context;
import android.media.MediaActionSound;
import android.util.Log;

import java.io.IOException;

/**
 * Created by rajnish on 9/11/15.
 */
public class AppCameraManager {

    private long mRecordingStartTime;
    private long mRecordingStopTime;
    private long mElapsedTime;
    private MediaActionSound mSound;
    private boolean mIsRecording;

    public AppCameraManager(Context context) {

        loadMediaActionSoundPlayer();
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

}
