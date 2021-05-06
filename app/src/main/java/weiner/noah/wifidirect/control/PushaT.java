package weiner.noah.wifidirect.control;

/**
 * Convenience class to represent a block that gradually pushes the drone left or right
 * until torso tilt ratio is in appropriate range again
 */
public class PushaT {
    private boolean isOn;

    public PushaT() {

    }


    public void switchOn(int dir, HumanFollower.FollowRunnable callingRunnable) {
        //make sure the drone takes angle psi into account while pusher is on to get circular motion (see paper)
        callingRunnable.setLateralHandlingMethod(LateralHandlingMethod.YAW_TO_PSI);
    }

    public void switchOff(HumanFollower.FollowRunnable callingRunnable) {
        //switch back to normal lateral handling method
        callingRunnable.setLateralHandlingMethod(LateralHandlingMethod.ROLL_TO_CENTER);
    }

    public boolean isOn() {
        return this.isOn;
    }

    //TODO
    public float getPush() {
        return 0;
    }
}
