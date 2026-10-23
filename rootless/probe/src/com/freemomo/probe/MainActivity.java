package com.freemomo.probe;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

/** Probe driver: run functional engine tests on create, report via file + toast. */
public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ProbeBoot.arm();
        super.onCreate(savedInstanceState);
        ProbeBoot.runFunctionalTests();
        Log.i("FreeMOMO-Probe", "MainActivity.onCreate done");
    }

    @Override
    protected void onResume() {
        super.onResume();
        // re-write the result file: the boot-class hooks (E attach, F onResume)
        // can only be judged once this lifecycle call has gone through them
        ProbeBoot.writeResults(ProbeBoot.writeResultsPass, ProbeBoot.writeResultsTotal);
        Toast.makeText(this, "FreeMOMO probe: see result.txt", Toast.LENGTH_LONG).show();
    }
}
