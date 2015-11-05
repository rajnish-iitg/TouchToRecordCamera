package demo.camera.com.cameraapplication.encoder;

import android.support.v4.util.Pair;

import java.util.ArrayList;

/**
 * @hide
 */
public class VideoEncoderConfig {
    protected final int mWidth;
    protected final int mHeight;
    protected final int mBitRate;
    protected ArrayList<Pair<Integer, Integer>> mSupportedResolution = new ArrayList<>();

    public VideoEncoderConfig(int width, int height, int bitRate) {
        mWidth = width;
        mHeight = height;
        mBitRate = bitRate;
    }

    public int getWidth() {
        return mWidth;
    }

    public int getHeight() {
        return mHeight;
    }

    public int getResolutionWidth() {
        return mWidth > mHeight ? mWidth : mHeight;
    }

    public int getResolutionHeight() {
        return mWidth > mHeight ?  mHeight : mWidth;
    }

    public int getBitRate() {
        return mBitRate;
    }

    @Override
    public String toString() {
        return "VideoEncoderConfig: " + mWidth + "x" + mHeight + " @" + mBitRate + " bps";
    }

    public void addSupportedResolution(Pair<Integer, Integer> pair) {
        mSupportedResolution.add(pair);
    }
}