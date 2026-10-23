package com.rud.freemomo.nativesmoke;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.system.Os;
import android.system.OsConstants;
import android.widget.TextView;

import org.lsposed.lspatch.metaloader.NativeBridge;

public final class MainActivity extends Activity {
    @Override
    @SuppressLint("SetTextI18n")
    public void onCreate(Bundle state) {
        super.onCreate(state);
        TextView text = new TextView(this);
        text.setPadding(32, 64, 32, 32);
        text.setTextIsSelectable(true);
        String device = "Model: " + Build.MODEL + "\nAPI: " + Build.VERSION.SDK_INT
                + "\nABI: " + String.join(", ", Build.SUPPORTED_ABIS);
        try {
            device += "\nKernel page size: " + Os.sysconf(OsConstants._SC_PAGESIZE);
            text.setText(device + "\n\n" + NativeBridge.runSmokeCheck()
                    + "\n\nThis diagnostic does not test Momo, SecNeo timing, or vocabulary study.");
        } catch (Exception | LinkageError error) {
            text.setText(device + "\n\nJNI smoke check failed:\n" + error);
        }
        setContentView(text);
    }
}
