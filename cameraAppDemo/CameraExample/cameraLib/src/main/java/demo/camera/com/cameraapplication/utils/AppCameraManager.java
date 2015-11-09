package demo.camera.com.cameraapplication.utils;

import android.content.Context;
import android.media.MediaActionSound;
import android.util.Log;

import com.google.common.eventbus.DeadEvent;
import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;

import java.io.IOException;

import demo.camera.com.cameraapplication.encoder.SessionConfig;
import demo.camera.com.cameraapplication.event.MuxerFinishedEvent;

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

    public AppCameraManager(Context context, SessionConfig config) {
        mEventBus = new EventBus("CameraManager");
        mEventBus.register(this);
        config.getMuxer().setEventBus(mEventBus);
        mSessionConfig = mLastSessionConfig = config;
        mContext = context;
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
}
