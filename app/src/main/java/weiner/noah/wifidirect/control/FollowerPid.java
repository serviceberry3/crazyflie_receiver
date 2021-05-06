package weiner.noah.wifidirect.control;

import android.util.Log;

public class FollowerPid {
    private final float Kp;
    private final float Ki;
    private final float Kd;

    private float compP;
    private float compI;
    private float compD;

    private float lastError = 0f;
    private float integError = 0f;

    private final String TAG = "FollowerPid";

    //whether we should reset integral to 0 when error approaches 0
    boolean SHOULD_RESET_INTEGRAL = true;

    //+- range of error at which integral should be reset to 0
    int INTEGRAL_RESET_THRESHOLD = 1;

    public FollowerPid(float p, float i, float d) {
        this.Kp = p;
        this.Ki = i;
        this.Kd = d;
        this.compP = 0;
        this.compI = 0;
        this.compD = 0;
        resetCtrl();
    }

    public void resetCtrl()
    {
        this.lastError = 0f;
        this.integError = 0f;
    }

    public float update(float error, float dt)
    {
        Log.i(TAG, "Abs error is " + Math.abs(error));

        //reset the integral component when error reaches low point, to avoid overshooting
        if (SHOULD_RESET_INTEGRAL && (Math.abs(error) < INTEGRAL_RESET_THRESHOLD)) {
            Log.i(TAG, "RESET INTEGRAL");
            this.integError = 0;
        }

        this.integError += error * dt;
        this.compP = error * this.Kp;
        this.compI = this.integError * this.Ki;
        this.compD = ((error - this.lastError) / dt) * this.Kd;
        this.lastError = error;
        return this.compP + this.compI + this.compD;
    }

    //convenience API
    public float componentP() {
        return this.compP;
    }
    public float componentI() {
        return this.compI;
    }
    public float componentD() {
        return this.compD;
    }
    public float getKp() {
        return this.Kp;
    }
    public float getKi() {
        return this.Ki;
    }
    public float getKd() {
        return this.Kd;
    }
}
