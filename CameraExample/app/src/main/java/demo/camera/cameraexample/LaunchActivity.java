package demo.camera.cameraexample;

import android.content.Intent;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;

import demo.camera.library.ui.CameraCaptureActivity;
import demo.camera.com.cameraexample.R;

public class LaunchActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_launch);

        Intent intent = new Intent(this, CameraCaptureActivity.class);
        startActivity(intent);
        finish();
    }
}
