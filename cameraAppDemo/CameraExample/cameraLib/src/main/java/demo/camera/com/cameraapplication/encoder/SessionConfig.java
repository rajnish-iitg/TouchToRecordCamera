package demo.camera.com.cameraapplication.encoder;

import java.io.File;
import java.util.Map;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static demo.camera.com.cameraapplication.utils.CameraUtils.isKitKat;

/**
 * Configuration information for a Broadcasting or Recording session.
 * Includes meta data, video + audio encoding
 * and muxing parameters
 */

public class SessionConfig {
    public static final String TAG = SessionConfig.class.getSimpleName();
    private final VideoEncoderConfig mVideoConfig;
    private final AudioEncoderConfig mAudioConfig;
    private File mOutputDirectory;
    private Muxer mMuxer;
    private boolean mAttachLocation;

    public static final int FRAME_RATE = 30;               // 30fps
    public static final float BPP = 0.10f;
    public static int sDefaultWidth = 480;
    public static int sDefaultHeight = 640;

    public static String sSessionFolderTemp = "session_temp";
    public static String sSessionFolder = "session";


    public SessionConfig(Muxer muxer, VideoEncoderConfig videoConfig, AudioEncoderConfig audioConfig) {
        mVideoConfig = checkNotNull(videoConfig);
        mAudioConfig = checkNotNull(audioConfig);

        mMuxer = checkNotNull(muxer);
    }

    public Muxer getMuxer() {
        return mMuxer;
    }

    public void setOutputDirectory(File outputDirectory) {
        mOutputDirectory = outputDirectory;
    }

    public File getOutputDirectory() {
        return mOutputDirectory;
    }

    public String getOutputPath() {
        return mMuxer.getOutputPath();
    }

    public int getTotalBitrate() {
        return mVideoConfig.getBitRate() + mAudioConfig.getBitrate();
    }

    public int getVideoResolutionWidth() {
        return  mVideoConfig.getResolutionWidth();
    }

    public int getVideoResolutionHeight() {
        return  mVideoConfig.getResolutionHeight();
    }

    public int getVideoWidth() {
        return mVideoConfig.getWidth();
    }

    public int getVideoHeight() {
        return mVideoConfig.getHeight();
    }

    public int getVideoBitrate() {
        return mVideoConfig.getBitRate();
    }

    public VideoEncoderConfig getVideoConfig() {
        return mVideoConfig;
    }

    public int getNumAudioChannels() {
        return mAudioConfig.getNumChannels();
    }

    public int getAudioBitrate() {
        return mAudioConfig.getBitrate();
    }

    public int getAudioSamplerate() {
        return mAudioConfig.getSampleRate();
    }

    public boolean shouldAttachLocation() {
        return mAttachLocation;
    }

    public void setAttachLocation(boolean mAttachLocation) {
        this.mAttachLocation = mAttachLocation;
    }

    public static class Builder {
        private int mWidth;
        private int mHeight;
        private int mVideoBitrate;

        private int mAudioSamplerate;
        private int mAudioBitrate;
        private int mNumAudioChannels;

        private Muxer mMuxer;

        private File mOutputDirectory;
        private String mTitle;
        private String mDescription;
        private boolean mPrivate;
        private boolean mAttachLocation;

        /**
         * Configure a SessionConfig quickly with intelligent path interpretation.
         * Valid inputs are "/path/to/name.m3u8", "/path/to/name.mp4"
         * <p/>
         * For file-based outputs (.m3u8, .mp4) the file structure is managed
         * by a recording UUID.
         * <p/>
         * Given an absolute file-based outputLocation like:
         * <p/>
         * /sdcard/test.m3u8
         * <p/>
         * the output will be available in:
         * <p/>
         * /sdcard/<UUID>/test.m3u8
         * /sdcard/<UUID>/test0.ts
         * /sdcard/<UUID>/test1.ts
         * ...
         * <p/>
         * You can query the final outputLocation after building with
         * SessionConfig.getOutputPath()
         *
         * @param outputLocation desired output location. For file based recording,
         *                       recordings will be stored at <outputLocationParent>/<UUID>/<outputLocationFileName>
         */
        public Builder(String outputLocation) {
            setAVDefaults();
            setMetaDefaults();

            if (outputLocation.contains(".mp4")) {
                mMuxer = AndroidMuxer.create(createRecordingPath(outputLocation), Muxer.FORMAT.MPEG4);
            } else
                throw new RuntimeException("Unexpected muxer output. Expected a .mp4, Got: " + outputLocation);

        }


        /**
         * @param outputPath a desired storage location like /path/filename.ext
         * @return a File pointing to /path/filename.ext
         */
        private String createRecordingPath(String outputPath) {
            File desiredFile = new File(outputPath);
            String desiredFilename = desiredFile.getName();
            File outputDir = new File(desiredFile.getParent(), sSessionFolderTemp);
            mOutputDirectory = outputDir;
            outputDir.mkdirs();
            return new File(outputDir, desiredFilename).getAbsolutePath();
        }

        private void setAVDefaults() {
            mWidth = SessionConfig.sDefaultWidth;
            mHeight = SessionConfig.sDefaultHeight;
            mVideoBitrate =  (int)(BPP * FRAME_RATE * mWidth * mHeight) ;

            mAudioSamplerate = 44100;
            mAudioBitrate = 96 * 1000;
            mNumAudioChannels = 1;
        }

        private void setMetaDefaults() {
            mPrivate = false;
            mAttachLocation = true;
        }

        public Builder withMuxer(Muxer muxer) {
            mMuxer = checkNotNull(muxer);
            return this;
        }

        public Builder withTitle(String title) {
            mTitle = title;
            return this;
        }

        public Builder withDescription(String description) {
            mDescription = description;
            return this;
        }

        public Builder withPrivateVisibility(boolean isPrivate) {
            mPrivate = isPrivate;
            return this;
        }

        public Builder withLocation(boolean attachLocation) {
            mAttachLocation = attachLocation;
            return this;
        }

        public Builder withVideoResolution(int width, int height) {
            mWidth = width;
            mHeight = height;
            return this;
        }

        public Builder withVideoBitrate(int bitrate) {
            mVideoBitrate = bitrate;
            return this;
        }

        public Builder withAudioSamplerate(int samplerate) {
            mAudioSamplerate = samplerate;
            return this;
        }

        public Builder withAudioBitrate(int bitrate) {
            mAudioBitrate = bitrate;
            return this;
        }

        public Builder withAudioChannels(int numChannels) {
            checkArgument(numChannels == 0 || numChannels == 1);
            mNumAudioChannels = numChannels;
            return this;
        }



        public SessionConfig build() {
            SessionConfig session = new SessionConfig(mMuxer,
                    new VideoEncoderConfig(mWidth, mHeight, mVideoBitrate),
                    new AudioEncoderConfig(mNumAudioChannels, mAudioSamplerate, mAudioBitrate));

            session.setAttachLocation(mAttachLocation);
            session.setOutputDirectory(mOutputDirectory);

            return session;
        }


    }
}
