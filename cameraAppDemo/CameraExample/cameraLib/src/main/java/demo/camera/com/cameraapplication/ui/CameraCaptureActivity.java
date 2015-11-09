/*
 * Copyright 2013 Google Inc. All rights reserved.
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

package demo.camera.com.cameraapplication.ui;

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.opengl.GLSurfaceView;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.support.v4.view.MotionEventCompat;
import android.support.v7.app.AlertDialog;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.ScaleAnimation;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemSelectedListener;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.Spinner;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

import demo.camera.com.cameraapplication.R;
import demo.camera.com.cameraapplication.encoder.MicrophoneEncoder;
import demo.camera.com.cameraapplication.encoder.SessionConfig;
import demo.camera.com.cameraapplication.encoder.TextureMovieEncoder;
import demo.camera.com.cameraapplication.utils.AppCameraManager;
import demo.camera.com.cameraapplication.utils.CameraUtils;

/**
 * Shows the camera preview on screen while simultaneously recording it to a .mp4 file.
 * <p>
 * Every time we receive a frame from the camera, we need to:
 * <ul>
 * <li>Render the frame to the SurfaceView, on GLSurfaceView's renderer thread.
 * <li>Render the frame to the mediacodec's input surface, on the encoder thread, if
 *     recording is enabled.
 * </ul>
 * <p>
 * At any given time there are four things in motion:
 * <ol>
 * <li>The UI thread, embodied by this Activity.  We must respect -- or work around -- the
 *     app lifecycle changes.  In particular, we need to release and reacquire the Camera
 *     so that, if the user switches away from us, we're not preventing another app from
 *     using the camera.
 * <li>The Camera, which will busily generate preview frames once we hand it a
 *     SurfaceTexture.  We'll get notifications on the main UI thread unless we define a
 *     Looper on the thread where the SurfaceTexture is created (the GLSurfaceView renderer
 *     thread).
 * <li>The video encoder thread, embodied by TextureMovieEncoder.  This needs to share
 *     the Camera preview external texture with the GLSurfaceView renderer, which means the
 *     EGLContext in this thread must be created with a reference to the renderer thread's
 *     context in hand.
 * <li>The GLSurfaceView renderer thread, embodied by CameraSurfaceRenderer.  The thread
 *     is created for us by GLSurfaceView.  We don't get callbacks for pause/resume or
 *     thread startup/shutdown, though we could generate messages from the Activity for most
 *     of these things.  The EGLContext created on this thread must be shared with the
 *     video encoder, and must be used to create a SurfaceTexture that is used by the
 *     Camera.  As the creator of the SurfaceTexture, it must also be the one to call
 *     updateTexImage().  The renderer thread is thus at the center of a multi-thread nexus,
 *     which is a bit awkward since it's the thread we have the least control over.
 * </ol>
 * <p>
 * GLSurfaceView is fairly painful here.  Ideally we'd create the video encoder, create
 * an EGLContext for it, and pass that into GLSurfaceView to share.  The API doesn't allow
 * this, so we have to do it the other way around.  When GLSurfaceView gets torn down
 * (say, because we rotated the device), the EGLContext gets tossed, which means that when
 * it comes back we have to re-create the EGLContext used by the video encoder.  (And, no,
 * the "preserve EGLContext on pause" feature doesn't help.)
 * <p>
 * We could simplify this quite a bit by using TextureView instead of GLSurfaceView, but that
 * comes with a performance hit.  We could also have the renderer thread drive the video
 * encoder directly, allowing them to work from a single EGLContext, but it's useful to
 * decouple the operations, and it's generally unwise to perform disk I/O on the thread that
 * renders your UI.
 * <p>
 * We want to access Camera from the UI thread (setup, teardown) and the renderer thread
 * (configure SurfaceTexture, start preview), but the API says you can only access the object
 * from a single thread.  So we need to pick one thread to own it, and the other thread has to
 * access it remotely.  Some things are simpler if we let the renderer thread manage it,
 * but we'd really like to be sure that Camera is released before we leave onPause(), which
 * means we need to make a synchronous call from the UI thread into the renderer thread, which
 * we don't really have full control over.  It's less scary to have the UI thread own Camera
 * and have the renderer call back into the UI thread through the standard Handler mechanism.
 * <p>
 * (The <a href="http://developer.android.com/training/camera/cameradirect.html#TaskOpenCamera">
 * camera docs</a> recommend accessing the camera from a non-UI thread to avoid bogging the
 * UI thread down.  Since the GLSurfaceView-managed renderer thread isn't a great choice,
 * we might want to create a dedicated camera thread.  Not doing that here.)
 * <p>
 * With three threads working simultaneously (plus Camera causing periodic events as frames
 * arrive) we have to be very careful when communicating state changes.  In general we want
 * to send a message to the thread, rather than directly accessing state in the object.
 * <p>
 * &nbsp;
 * <p>
 * To exercise the API a bit, the video encoder is required to survive Activity restarts.  In the
 * current implementation it stops recording but doesn't stop time from advancing, so you'll
 * see a pause in the video.  (We could adjust the timer to make it seamless, or output a
 * "paused" message and hold on that in the recording, or leave the Camera running so it
 * continues to generate preview frames while the Activity is paused.)  The video encoder object
 * is managed as a static property of the Activity.
 */
public class CameraCaptureActivity extends ImmersiveActivity
        implements SurfaceTexture.OnFrameAvailableListener, OnItemSelectedListener {
    private static final String TAG = CameraCaptureActivity.class.getSimpleName();
    private static final boolean VERBOSE = false;

    // Camera filters; must match up with cameraFilterNames in strings.xml
    static final int FILTER_NONE = 0;
    static final int FILTER_BLACK_WHITE = 1;
    static final int FILTER_BLUR = 2;
    static final int FILTER_SHARPEN = 3;
    static final int FILTER_EDGE_DETECT = 4;
    static final int FILTER_EMBOSS = 5;

    private GLSurfaceView mGLView;
    private CameraSurfaceRenderer mRenderer;
    private Camera mCamera;
    private CameraHandler mCameraHandler;
    private boolean mRecordingEnabled;      // controls button state

    private int mCameraPreviewWidth, mCameraPreviewHeight;
    protected MicrophoneEncoder mMicEncoder;

    // this is static so it survives activity restarts
    private TextureMovieEncoder mVideoEncoder;
    SessionConfig mSessionConfig;
    private double mCurrentAspectRatio;

    private Button mDoneButton;
    private ImageView mCancleButton;
    private Button mRecordButton;
    private ImageButton mFlashButton;
    private DonutProgress mDonutProgress;
    private Timer mTimer;
    private RelativeLayout mTouchInterceptor;
    private RelativeLayout mBlockerSpinner;
    private ImageView mTouchIndicator;
    private ImageView mMoreOptions;
    private LinearLayout mExtrasContainer;

    private static final int mCancelMsgDelay = 400; // in MS
    private static final int mProgressLoopWindow = 15000; // in MS
    private static AppCameraManager mCameraManager;

    private String mCurrentFlash;
    private String mDesiredFlash;

    private int mCurrentCamera;
    private int mDesiredCamera;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        setContentView(R.layout.activity_camera_capture);

        CameraUtils.clearSessionConfig();
        CameraUtils.clearSessionFolders(this, true, true);

        Spinner spinner = (Spinner) findViewById(R.id.cameraFilter_spinner);
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(this,
                R.array.cameraFilterNames, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        // Apply the adapter to the spinner.
        spinner.setAdapter(adapter);
        spinner.setOnItemSelectedListener(this);

        // Define a handler that receives camera-control messages from other threads.  All calls
        // to Camera must be made on the same thread.  Note we create this before the renderer
        // thread, so we know the fully-constructed object will be visible.
        mSessionConfig = CameraUtils.getSessionConfig(this);
        CameraUtils.clearSessionConfig();

        mCameraHandler = new CameraHandler(this);
        mVideoEncoder = new TextureMovieEncoder();
        mRecordingEnabled = mVideoEncoder.isRecording();



        try {
            mMicEncoder = new MicrophoneEncoder(mSessionConfig);
        } catch (IOException e) {
            e.printStackTrace();
        }

        // Configure the GLSurfaceView.  This will start the Renderer thread, with an
        // appropriate EGL context.
        mGLView = (GLSurfaceView) findViewById(R.id.cameraPreview_surfaceView);
        mGLView.setEGLContextClientVersion(2);     // select GLES 2.0
        mRenderer = new CameraSurfaceRenderer(mCameraHandler, mSessionConfig, mVideoEncoder);
        mGLView.setRenderer(mRenderer);
        mGLView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
        mCameraManager = new AppCameraManager(this,mSessionConfig);
        setUpUi();
        Log.d(TAG, "onCreate complete: " + this);

        mCurrentFlash = Camera.Parameters.FLASH_MODE_OFF;
        mDesiredFlash = null;

        mCurrentCamera = -1;
        mDesiredCamera = Camera.CameraInfo.CAMERA_FACING_BACK;

    }

    public void setUpUi() {
        mBlockerSpinner = (RelativeLayout) findViewById(R.id.blocker);
        mBlockerSpinner.setVisibility(View.GONE);

        mTouchIndicator = (ImageView) findViewById(R.id.touchIndicator);
        mTouchInterceptor = (RelativeLayout) findViewById(R.id.touch_interceptor);
        mTouchInterceptor.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                mTouchIndicator.setImageResource(R.drawable.white_circle);
                mTouchIndicator.setVisibility(View.VISIBLE);
                final int X = (int) event.getRawX();
                final int Y = (int) event.getRawY();

                RelativeLayout.LayoutParams lParams = (RelativeLayout.LayoutParams)
                        mTouchIndicator.getLayoutParams();
                lParams.leftMargin = X - mTouchIndicator.getWidth() / 2;
                lParams.topMargin = Y - mTouchIndicator.getHeight() / 2 ;
                mTouchIndicator.setLayoutParams(lParams);
                mTouchIndicator.invalidate();

                ScaleAnimation scaleUpAnimation = new ScaleAnimation(
                        0, 1, 0, 1, Animation.RELATIVE_TO_SELF, (float)0.5,
                        Animation.RELATIVE_TO_SELF, (float)0.5);

                scaleUpAnimation.setDuration(350);
                scaleUpAnimation.setAnimationListener(new Animation.AnimationListener() {
                    @Override
                    public void onAnimationStart(Animation animation) {

                    }

                    @Override
                    public void onAnimationEnd(Animation animation) {
                        mTouchIndicator.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                mTouchIndicator.setVisibility(View.GONE);
                            }
                        }, 100);

                    }

                    @Override
                    public void onAnimationRepeat(Animation animation) {

                    }
                });
                mTouchIndicator.startAnimation(scaleUpAnimation);
                return false;
            }
        });

//            mTouchInterceptor.setVisibility(View.GONE);

        mRecordButton = (Button) findViewById(R.id.recordButton);

        mExtrasContainer = (LinearLayout) findViewById(R.id.settings_container);
        mMoreOptions = (ImageView) findViewById(R.id.icon_more);
        mMoreOptions.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mMoreOptions.setSelected(!mMoreOptions.isSelected());
                if (mMoreOptions.isSelected()) {
                    mExtrasContainer.setVisibility(View.VISIBLE);
                } else {
                    mExtrasContainer.setVisibility(View.GONE);
                }

            }
        });

//            mRecordButton .setOnClickListener(mRecordButtonClickListener);
        setUpTouchInterceptor(mRecordButton);

        setUpHeaders();
        setUpFlashButton();
        setUpProgressIndicator();

//        setupFilterSpinner();
        setupCameraFlipper();
    }

    private void setupCameraFlipper() {
        View flipper = findViewById(R.id.cameraFlipper);
        if (Camera.getNumberOfCameras() == 1) {
            flipper.setVisibility(View.GONE);
        } else {
            flipper.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    requestOtherCamera();
                }
            });
        }
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
        if (mCamera != null && mDesiredCamera != mCurrentCamera) {
            // Hot swap camera
            onPauseCameraSetup();
            onResumeCameraSetup();
        }
    }


    private void setUpTouchInterceptor(View interceptorView) {
        interceptorView.setOnTouchListener(new View.OnTouchListener() {

            private long lastRecordingRequestedTime = 0;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                int action = MotionEventCompat.getActionMasked(event);
                boolean retVal = false;
                switch (action) {
                    case (MotionEvent.ACTION_DOWN):
                        Log.d(TAG, "Action was DOWN");
                        lastRecordingRequestedTime = System.currentTimeMillis();
                        startRecording();
                        retVal = true;
                        break;
                    case (MotionEvent.ACTION_UP):
                        Log.d(TAG, "Action was UP");
                        if (System.currentTimeMillis() - lastRecordingRequestedTime > mCancelMsgDelay) {
                            stopRecording();
                        }
                        retVal = true;
                        break;
                    default:
                        retVal = false;
                }
                return retVal;
            }
        });
    }

    public void startRecording() {
        Log.d(TAG, "Action was DOWN");
        mMicEncoder.startRecording();
        mRecordButton.setBackgroundResource(R.drawable.red_dot_stop);
        mRecordingEnabled = true;
        mCameraManager.changeRecordingState(mRecordingEnabled);
        mGLView.queueEvent(new Runnable() {
            @Override
            public void run() {
                // notify the renderer that we want to change the encoder's state
                mRenderer.changeRecordingState(mRecordingEnabled);
            }
        });
        mRecordButton.setBackgroundResource(R.drawable.red_dot_stop);
    }

    public void stopRecording() {
        mRecordingEnabled = false;
        mMicEncoder.stopRecording();
        handleStopRecording();
        resetConfig();
        try {
            mMicEncoder.reset(mSessionConfig);
            mRenderer.resetSessionConfig(mSessionConfig);
        } catch (IOException e) {
            e.printStackTrace();
        }

        mCameraManager.changeRecordingState(mRecordingEnabled);
        mGLView.queueEvent(new Runnable() {
            @Override
            public void run() {
                // notify the renderer that we want to change the encoder's state
                mRenderer.changeRecordingState(mRecordingEnabled);
            }
        });
    }
    public void handleStopRecording() {
        mRecordButton.setBackgroundResource(R.drawable.red_dot);
        mDoneButton.setVisibility(View.VISIBLE);
        mCancleButton.setVisibility(View.VISIBLE);
        mCancleButton.setImageResource(R.drawable.ic_delete);
        mCancleButton.setColorFilter(getResources().getColor(R.color.color_white));
    }

    private void setUpProgressIndicator() {
        mDonutProgress = (DonutProgress) findViewById(R.id.donut_progress);
        mDonutProgress.setText(CameraUtils.millisecondToTimeString(0));
        mTimer = new Timer();
        mTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (mCameraManager != null && mCameraManager.isRecording()) {
                            mDonutProgress.setVisibility(View.VISIBLE);
                            mDonutProgress.setText(CameraUtils.millisecondToTimeString(
                                    mCameraManager.getRecordingTime()));
                            float timeInMlSec = mCameraManager.getRecordingTime();
                            float progress = ((timeInMlSec % mProgressLoopWindow) * 1.0f /
                                    mProgressLoopWindow) * 100;
                            mDonutProgress.setProgress(progress);
                        }
                    }
                });
            }
        }, 100, 200);
    }

    private void setUpFlashButton() {
        mFlashButton = (ImageButton) findViewById(R.id.flashButton);
        mFlashButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mCameraManager != null) {
                    v.setSelected(!v.isSelected());
                    if (v.isSelected()) {
                        mFlashButton.setImageResource(R.drawable.flash_on);
                    } else {
                        mFlashButton.setImageResource(R.drawable.flash_off);
                    }

                    toggleFlashMode();
                }
            }
        });
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

    private void setUpHeaders() {
        mCancleButton = (ImageView) findViewById(R.id.cancle_button);
        mCancleButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mDoneButton.getVisibility() == View.VISIBLE) {
                    showCancleAlert();
                } else {
                    finish();
                }
            }
        });

        mDoneButton = (Button) findViewById(R.id.doneButton);
        mDoneButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                AsyncStitcherTask stitcherTask = new AsyncStitcherTask(CameraCaptureActivity.this);
                stitcherTask.execute("AsyncStitcherTask Task");
                mDoneButton.setVisibility(View.GONE);

                if (mCameraManager != null && mCameraManager.isRecording()) {
                    handleStopRecording();
                }

                mBlockerSpinner.setVisibility(View.VISIBLE);
                mDonutProgress.setProgress(0);
                mDonutProgress.setText(CameraUtils.millisecondToTimeString(0));
                if (mCameraManager != null) {
                    mCameraManager.resetRecordingTime();
                }

                mCancleButton.setVisibility(View.INVISIBLE);

            }
        });
    }


    private class AsyncStitcherTask extends AsyncTask<String, Integer, Boolean> {

        WeakReference<CameraCaptureActivity> weakActivity;
        Context mContext;

        AsyncStitcherTask(CameraCaptureActivity activity) {
            weakActivity = new WeakReference<>(activity);
            mContext = activity.getApplicationContext();

        }
        @Override
        protected Boolean doInBackground(String... params) {
            final File inputDir = new File(mContext.getExternalFilesDir(null),
                    SessionConfig.sSessionFolder);
            final File outDir = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DCIM);

            CameraUtils.stichVideos(
                    mContext, inputDir.getPath(), outDir.getPath());
            return true;
        }

        @Override
        protected void onPostExecute(Boolean aBoolean) {
            super.onPostExecute(aBoolean);
            CameraCaptureActivity activity = weakActivity.get();
            if (activity != null) {
                mBlockerSpinner.setVisibility(View.GONE);
            }
        }
    }

    private void showCancleAlert() {
        new AlertDialog.Builder(this)
                .setTitle("Delete video ...")
                .setMessage("Are you sure you want to delete video ?")
                .setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        // continue with delete
                        mDonutProgress.setProgress(0);
                        mDonutProgress.setText(CameraUtils.millisecondToTimeString(0));
                        if (mCameraManager != null) {
                            if (mCameraManager.isRecording()) {
                                mCameraManager.stopRecording();
                            }
                            mCameraManager.resetRecordingTime();
                        }
                        mCancleButton.setVisibility(View.INVISIBLE);
                        mDoneButton.setVisibility(View.GONE);
                        CameraUtils.clearSessionFolders(mDonutProgress.getContext(), true, false);
                        dialog.dismiss();
                    }
                })
                .setNegativeButton(android.R.string.no, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                    }
                })
                .setIcon(android.R.drawable.ic_dialog_alert)
                .show();
    }
    @Override
    protected void onResume() {
        Log.d(TAG, "onResume -- acquiring camera");
        super.onResume();
        onResumeCameraSetup();
    }


    void resetCameraSetup() {
//        onPauseCameraSetup();
//        onResumeCameraSetup();
    }

    private void onResumeCameraSetup() {
        openCamera(mSessionConfig.getVideoResolutionWidth(),
                mSessionConfig.getVideoResolutionHeight(), mDesiredCamera);      // updates mCameraPreviewWidth/Height

        // Set the preview aspect ratio.
        AspectFrameLayout layout = (AspectFrameLayout) findViewById(R.id.cameraPreview_afl);
        int orientation = getResources().getConfiguration().orientation;
        if (orientation == Configuration.ORIENTATION_PORTRAIT) {
            mCamera.setDisplayOrientation(90);
            mCurrentAspectRatio = (double) mCameraPreviewHeight / mCameraPreviewWidth;
            layout.setAspectRatio(mCurrentAspectRatio);
        } else if (orientation == Configuration.ORIENTATION_LANDSCAPE){
            mCamera.setDisplayOrientation(0);
            mCurrentAspectRatio = (double) mCameraPreviewWidth / mCameraPreviewHeight;
            layout.setAspectRatio(mCurrentAspectRatio);
        }

        mGLView.onResume();
        mGLView.queueEvent(new Runnable() {
            @Override
            public void run() {
                mRenderer.setCameraPreviewSize(mCameraPreviewWidth, mCameraPreviewHeight);
            }
        });
        Log.d(TAG, "onResume complete: " + this);
    }

    private void onPauseCameraSetup() {
        releaseCamera();
        mGLView.queueEvent(new Runnable() {
            @Override
            public void run() {
                // Tell the renderer that it's about to be paused so it can clean up.
                mRenderer.notifyPausing();
            }
        });
        mGLView.onPause();
        Log.d(TAG, "onPause complete");
    }

    @Override
    protected void onPause() {
        Log.d(TAG, "onPause -- releasing camera");
        super.onPause();
        onPauseCameraSetup();
    }

    @Override
    protected void onDestroy() {
        Log.d(TAG, "onDestroy");
        super.onDestroy();
        mCameraHandler.invalidateHandler();     // paranoia
        CameraUtils.clearSessionFolders(this, true, true);
    }

    // spinner selected
    @Override
    public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
        Spinner spinner = (Spinner) parent;
        final int filterNum = spinner.getSelectedItemPosition();

        Log.d(TAG, "onItemSelected: " + filterNum);
        mGLView.queueEvent(new Runnable() {
            @Override
            public void run() {
                // notify the renderer that we want to change the encoder's state
                mRenderer.changeFilterMode(filterNum);
            }
        });
    }

    @Override
    public void onNothingSelected(AdapterView<?> parent) {}

    /**
     * Opens a camera, and attempts to establish preview mode at the specified width and height.
     * <p>
     * Sets mCameraPreviewWidth and mCameraPreviewHeight to the actual width/height of the preview.
     */
    private void openCamera(int desiredWidth, int desiredHeight, int requestedCameraType) {
        if (mCamera != null) {
            throw new RuntimeException("camera already initialized");
        }

        Camera.CameraInfo info = new Camera.CameraInfo();

        // Try to find a front-facing camera (e.g. for videoconferencing).
        int numCameras = Camera.getNumberOfCameras();
        int targetCameraType = requestedCameraType;
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
                if (targetCameraType == requestedCameraType)
                    targetCameraType = (requestedCameraType == Camera.CameraInfo.CAMERA_FACING_BACK
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
        TextView text = (TextView) findViewById(R.id.cameraParams_text);
        text.setText(previewFacts);

        mCameraPreviewWidth = mCameraPreviewSize.width;
        mCameraPreviewHeight = mCameraPreviewSize.height;
    }

    /**
     * Stops camera preview, and releases the camera to the system.
     */
    private void releaseCamera() {
        if (mCamera != null) {
            mCamera.stopPreview();
            mCamera.release();
            mCamera = null;
            Log.d(TAG, "releaseCamera -- done");
        }
    }

    private void resetConfig() {
        mSessionConfig = CameraUtils.getSessionConfig(this);
        CameraUtils.clearSessionConfig();
        try {
            mCameraManager.reset(mSessionConfig);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

//    /**
//     * onClick handler for "rebind" checkbox.
//     */
//    public void clickRebindCheckbox(View unused) {
//        CheckBox cb = (CheckBox) findViewById(R.id.rebindHack_checkbox);
//        TextureRender.sWorkAroundContextProblem = cb.isChecked();
//    }


    /**
     * Connects the SurfaceTexture to the Camera preview output, and starts the preview.
     */
    private void handleSetSurfaceTexture(SurfaceTexture st) {
        st.setOnFrameAvailableListener(this);
        try {
            mCamera.setPreviewTexture(st);
        } catch (IOException ioe) {
            throw new RuntimeException(ioe);
        }
        mCamera.startPreview();
    }

    private void handleSurfaceChanged(double aspectRatio) {
        if (mCurrentAspectRatio != aspectRatio) {
            resetCameraSetup();
        }
    }

    @Override
    public void onFrameAvailable(SurfaceTexture st) {
        // The SurfaceTexture uses this to signal the availability of a new frame.  The
        // thread that "owns" the external texture associated with the SurfaceTexture (which,
        // by virtue of the context being shared, *should* be either one) needs to call
        // updateTexImage() to latch the buffer.
        //
        // Once the buffer is latched, the GLSurfaceView thread can signal the encoder thread.
        // This feels backward -- we want recording to be prioritized over rendering -- but
        // since recording is only enabled some of the time it's easier to do it this way.
        //
        // Since GLSurfaceView doesn't establish a Looper, this will *probably* execute on
        // the main UI thread.  Fortunately, requestRender() can be called from any thread,
        // so it doesn't really matter.
        if (VERBOSE) Log.d(TAG, "ST onFrameAvailable");
        mGLView.requestRender();
    }

    /**
     * Handles camera operation requests from other threads.  Necessary because the Camera
     * must only be accessed from one thread.
     * <p>
     * The object is created on the UI thread, and all handlers run there.  Messages are
     * sent from other threads, using sendMessage().
     */
    static class CameraHandler extends Handler {
        public static final int MSG_SET_SURFACE_TEXTURE = 0;
        public static final int MSG_SURFACE_CHANGED = 1;

        // Weak reference to the Activity; only access this from the UI thread.
        private WeakReference<CameraCaptureActivity> mWeakActivity;

        public CameraHandler(CameraCaptureActivity activity) {
            mWeakActivity = new WeakReference<CameraCaptureActivity>(activity);
        }

        /**
         * Drop the reference to the activity.  Useful as a paranoid measure to ensure that
         * attempts to access a stale Activity through a handler are caught.
         */
        public void invalidateHandler() {
            mWeakActivity.clear();
        }

        @Override  // runs on UI thread
        public void handleMessage(Message inputMessage) {
            int what = inputMessage.what;
            Log.d(TAG, "CameraHandler [" + this + "]: what=" + what);

            CameraCaptureActivity activity = mWeakActivity.get();
            if (activity == null) {
                Log.w(TAG, "CameraHandler.handleMessage: activity is null");
                return;
            }

            switch (what) {
                case MSG_SET_SURFACE_TEXTURE:
                    activity.handleSetSurfaceTexture((SurfaceTexture) inputMessage.obj);
                    break;
                case MSG_SURFACE_CHANGED:
                    activity.handleSurfaceChanged((double)(inputMessage.obj));
                    break;
                default:
                    throw new RuntimeException("unknown msg " + what);
            }
        }
    }
}
