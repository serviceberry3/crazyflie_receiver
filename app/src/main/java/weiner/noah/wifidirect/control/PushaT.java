package weiner.noah.wifidirect.control;

import android.util.Log;

/**
 * Convenience class to represent a block that gradually pushes the drone left or right
 * until torso tilt ratio is in appropriate range again
 */
public class PushaT {
    private boolean isOn;
    private PushaDirection mDirection;
    private final String TAG = "PushaT";

    //meters that the pusha will push drone left/right on each control loop cycle
    private final float PUSH_MAGNITUDE = 0.03f; //should be in cm, but it's not executed very accurately

    public PushaT() {
        this.isOn = false;
    }


    public void switchOn(PushaDirection dir, HumanFollower.FollowRunnable callingRunnable) {
        Log.i(TAG, "Pusha switched on rqst");

        //if the pusha is currently off, switch it on
        if (!isOn) isOn = true;

        //set direction to the requested
        this.mDirection = dir;

        //make sure the drone takes angle psi into account while pusher is on to get circular motion (see paper)
        callingRunnable.setLateralHandlingMethod(LateralHandlingMethod.YAW_TO_PSI);
    }

    public void switchOff(HumanFollower.FollowRunnable callingRunnable) {
        Log.i(TAG, "Pusha switched off rqst");

        //if the pusha is currently on, switch it off
        if (isOn) isOn = false;

        //switch back to normal lateral handling method
        callingRunnable.setLateralHandlingMethod(LateralHandlingMethod.ROLL_TO_CENTER);
    }

    public boolean isOn() {
        return this.isOn;
    }

    //a push is being requested
    public float getPush() {
        if (mDirection == PushaDirection.RIGHT)
            return -PUSH_MAGNITUDE;
        else
            return PUSH_MAGNITUDE;
    }
}
