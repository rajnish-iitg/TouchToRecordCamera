package demo.camera.com.cameraapplication.encoder;

import android.os.Environment;

import java.io.File;

/**
 * Created by rajnish on 5/11/15.
 */
public class CommonConfig {

    private static CommonConfig sInstance;
    private CommonConfig() {}
    AndroidMuxer mMuxer;

    public static CommonConfig getsInstance() {
        if(sInstance == null) {
            sInstance = new CommonConfig();
        }

        return sInstance;
    }

    public AndroidMuxer getMuxer() {
        if (mMuxer == null) {
            File outputFile = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_MOVIES), System.currentTimeMillis() + "camera-test.mp4");
            mMuxer = AndroidMuxer.create(outputFile.getPath(), Muxer.FORMAT.MPEG4);
        }
        return mMuxer;
    }
}
