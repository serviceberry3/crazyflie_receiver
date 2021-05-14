package weiner.noah.wifidirect.control;

import android.graphics.ImageDecoder;
import android.os.SystemClock;
import android.util.Log;

import org.tensorflow.lite.examples.noah.lib.Device;
import org.tensorflow.lite.examples.noah.lib.Posenet;

import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

import weiner.noah.wifidirect.crtp.CommanderPacket;
import weiner.noah.wifidirect.crtp.CrtpPacket;
import weiner.noah.wifidirect.crtp.HeightHoldPacket;
import weiner.noah.wifidirect.crtp.PositionPacket;
import weiner.noah.wifidirect.usb.UsbController;

/** Convenience class to run a human following script.
 *
 * */
public class HumanFollower {
    private final UsbController usbController;
    private final float TARG_HEIGHT = 0.3f;
    private final String LOG_TAG = "HumanFollower";
    private final String CTRL = "Control";

    private Thread mFollowThread;
    private Thread mLandingThread;

    private final MainActivity mainActivity;

    private final AtomicBoolean following = new AtomicBoolean(false);
    private final AtomicBoolean landing = new AtomicBoolean(false);
    private final AtomicBoolean kill = new AtomicBoolean(false);

    //do we have new distance data from Posenet?
    private final AtomicBoolean freshPosenetDistData = new AtomicBoolean(false);

    //do we have new angle data from Posenet
    private final AtomicBoolean freshPosenetAngleData = new AtomicBoolean(false);

    //do we have new torso tilt data from Posenet?
    private final AtomicBoolean freshPosenetTorsoTiltRatio = new AtomicBoolean(false);

    //do we have new bounding box center offset data from Posenet?
    private final AtomicBoolean freshPosenetBbCenterOffset = new AtomicBoolean(false);

    private final PosenetStats posenetStats;

    private final int CORRECTION_RELAX = 5;

    //max and min velocities for forward/backward motion
    private final float CORRECTION_VEL_PITCH_UPPER = 0.8f;
    private final float CORRECTION_VEL_PITCH_LOWER = 0.1f;

    private final float CORRECTION_VEL_PITCH = 0.2f;

    private final float CORRECTION_VEL_ROLL = 0.1f;
    private final float CORRECTION_VEL_ROLL_SMALL = 0.05f;
    private final float CORRECTION_VEL_YAW = 15f;
    private final float FOLLOWING_FAR_BOUND = 0.47f;
    private final float FOLLOWING_NEAR_BOUND = 0.32f;

    //let the desired distance always be point in between the far and near bounds
    private final float DIST_DESIRED = (FOLLOWING_FAR_BOUND + FOLLOWING_NEAR_BOUND) / 2;

    //we want always want angle psi, angle from drone to human, to appear to be 0
    private final float PSI_DESIRED = 0f;

    //this parameter can be used to tune the pivoting
    private final float PSI_SCALEUP = 1.45f; //i.e., always scale up psi by 1/5 when pivoting

    //since the camera is located at the far right-hand side of the screen, we can perform a basic correction, considering
    //the human's bounding box center point is usually already about -60 pixels from the frame center even when human is centered wrt the screen
    private final float CTR_OFFSET_DESIRED = -15f; //NOTE: changes based on how the camera is positioned, etc. You'll probably need to adjust this frequently

    //how far we'll let the person turn before making a pivot correction maneuver
    private final float FOLLOWING_ANGLE_THRESHOLD = 25f; //TODO: this seemed like a parameter that could be tuned better, but it also depends on preference
    private final float FOLLOWING_TILT_RATIO_UPPER_BOUND = 1.70f;
    private final float FOLLOWING_TILT_RATIO_LOWER_BOUND = 0.45f;
    private final float FOLLOWING_BB_CENTER_THRESHOLD = 30f; //maintain +- x pixels from CTR_OFFSET_DESIRED

    private final Device PNET_DEV_TO_USE = Device.GPU;

    private final FollowerPid distPid;
    private final FollowerPid yawPid;
    private final FollowerPid xAxisPid;

    private final float distPidP = 0.25f;
    private final float distPidI = 0.0f;
    private final float distPidD = 0.0f;

    private final float yawPidP = 0.12f;
    private final float yawPidI = 0f;
    private final float yawPidD = 0f;

    private final float xAxisPidP = 0.1f;
    private final float xAxisPidI = 0f;
    private final float xAxisPidD = 0f;

    //time elapsed since last PID update()
    private long timeElapsed = 0;

    //timestamp recorded immediately after last PID update()
    private long prevTime = 0;

    //left/right pusher for staying face-to-face with user
    private PushaT mPushaT;

    /*control guide:
    * HEIGHTHOLD PKTS
    *
    * YAW
    *   POSITIVE: left wrt phone's POV
    *   NEGATIVE: right wrt phone's POV
    *
    * ROLL (left/rt wrt phone's POV)
    *   POSITIVE: right
    *   NEGATIVE: left
    *
    * PITCH (forward/back wrt phone's POV)
    *   POSITIVE: forward
    *   NEGATIVE: backward
    *
    * POSHOLD PKTS (wrt phone's POV)
    * YAW:
    *   POSITIVE left wrt phone's POV
    *   NEGATIVE: right wrt phone's POV
    *
    *   NEGATIVE DX: backward
    *   POSITIVE DX: forward
    *   NEGATIVE DY: right
    *   POSITIVE DY: left
    *
    * */
    private static final Object[] xAxisUpdateLock = new Object[]{};

    public HumanFollower(UsbController usbController, MainActivity mainActivity) {
        this.usbController = usbController;
        this.mainActivity = mainActivity;
        posenetStats = new PosenetStats(new Posenet(mainActivity.getApplicationContext(), "posenet_model.tflite", PNET_DEV_TO_USE), mainActivity, this);

        //instantiate the PID controllers
        distPid = new FollowerPid(distPidP, distPidI, distPidD);
        yawPid = new FollowerPid(yawPidP, yawPidI, yawPidD);
        xAxisPid = new FollowerPid(xAxisPidP, xAxisPidI, xAxisPidD);

        //instantiate a new left/right pusher
        mPushaT = new PushaT();
    }

    /* Pseudocode
     *  - In a new Thread, run a USB flight script that does the following:
     *  - Launch the drone up to TARG_HEIGHT, and hover there indefinitely
     *  - Once up to height, start monitoring Posenet. Adjust the drone's position based on that.
     *  - When stop is called, stop monitoring Posenet, and safely land the drone, wherever it currently is.
     * */
    public void start() {
        //reject joystick stream
        mainActivity.setRelay(false);

        Log.i(LOG_TAG, "HumanFollower start() called!");

        //set 'following' AtomicBool to true
        following.set(true);

        mFollowThread = new Thread(new FollowRunnable());
        mFollowThread.start();
    }


    //stop following
    public void stop() {
        following.set(false);
        land();
    }


    public void land() {
        //joystick sending thread should already be paused. Let's set the landing AtomicBoolean to true, which will be detected when landCheck() is run inside
        //the hover thread
        landing.set(true);
    }

    public boolean landCheck() {
        //check to see if land button has been pressed.
        if (landing.get()) {
            //if has been pressed, start the landing thread and return true to signal to the hover thread to return
            mLandingThread = new Thread(new LandRunnable());
            mLandingThread.start();

            //reset the landing and hovering AtomicBools to false ('landing' shouldn't need to be used again)
            landing.set(false); following.set(false);

            return true;
        }

        return false;
    }

    /*Want killing to happen as fast as possible. That means If the launch thread is sleeping, interrupt it. It is undefined whether one of the below *Thread.interrupt()s
    will execute the kill, or whether one of the killChecks() in the background thread will execute it.

    One of two things happens when kill() is called:
    1. The background thread that's running a flight sequence is currently sending a packet or waiting for the ack
            - In this case, a killCheck() will be performed by the thread within a couple milliseconds, at which point the thread will queue some stop packets, resume the
              joystick thread, and exit
    2. The background thread that's running a flight sequence is currently sleeping
            - In this case, kill() interrupts the thread, which causes sleep() to throw an InterruptedException. The thread resumes the joystickRunnable thread and exits immediately.
     */

    //request a kill. Called on pressing "Kill (USB)" button
    public void kill() {
        Log.i(LOG_TAG, "Kill requested");

        //atomically set kill to true
        kill.set(true);


        //if following, interrupt the follow thread immediately (if the Thread is sleeping, this will throw exception to end it)
        if (following.get())
            mFollowThread.interrupt();

        //if landing, interrupt the hover thread immediately (if the Thread is sleeping, this will throw exception to end it)
        if (landing.get())
            mLandingThread.interrupt();
    }

    //check if user has requested kill. If so, kill the drone
    public boolean killCheck() {
        if (kill.get()) {

            //send 10 STOP packets to ensure a kill
            for (int i = 0; i < 10; i++) {
                sendPacket(new CommanderPacket(0, 0, 0, (char) 0));
            }

            //reset kill to false, atomically
            kill.set(false);

            //if we're following, the posenet backgnd thread is running, so stop it now
            posenetStats.stop();

            //make sure following and landing are all reset to false
            landing.set(false); following.set(false);

            //let background thread know to return if necessary
            return true;
        }

        return false;
    }

    //Runnable that safely lands the drone, starting from TARG_HEIGHT
    class LandRunnable implements Runnable {
        private final Object mPauseLock;
        private boolean mPaused;
        private boolean mFinished;

        public LandRunnable() {
            mPauseLock = new Object();
            mPaused = false;
            mFinished = false;
        }

        /*Land the drone.
         * Since we stop the joystick streaming thread while we run the launch sequence, mOutQueue in the driver gets cleaned out (we sleep between
         * sending packets during this sequence). That means the WifiDriverThread will continue to send NULL packets to the onboard phone during sleep time,
         * because it tries to poll the out queue and comes up empty. We need to make sure the LinkedBlockingQueue poll() waitTime is long enough.
         * Otherwise WifiDriverThread will flood the queue.
         * */
        public void run() {
            try {
                //joystick runnable should already be paused

                Log.i(LOG_TAG, "Landing...");

                final int[] cnt = {0};
                int thrust_mult = 1;
                int thrust_step = 100;
                int thrust_dstep = 10;
                int thrust = 3000;
                int pitch = 0;
                int roll = 0;
                int yawrate = 0;
                final float start_height = 0.05f;

                //NOTE: the distance of the ZRanger is not accurate, 1.2 => 1.5m

                //DOWN SEQUENCE
                while (cnt[0] < 50) {
                    sendPacket(new HeightHoldPacket(0, 0, 0, (-TARG_HEIGHT + start_height) * (cnt[0] / 50.0f) + TARG_HEIGHT));

                    if (killCheck()) {
                        //end this thread
                        return;
                    }

                    Thread.sleep(50);

                    cnt[0]++;
                }

                //STOP
                sendPacket(new CommanderPacket(0, 0, 0, (char) 0));

                //end the Posenet thread
                posenetStats.stop();

                //re-enable joystick stream
                mainActivity.setRelay(true);

                //'landing' should already have been reset to false at this time
                //FIXME: it makes more sense for resetting 'landing' to false to go here
            }

            //If a kill was requested, stop now
            catch (InterruptedException e) {
                //make sure Posenet backgnd thread was stopped
                posenetStats.stop();

                kill.set(false);
                e.printStackTrace();

                //thread now stops and goes home
            }
        }


        //CONVENIENCE FXNS
        //pause the thread
        public void onPause() {
            synchronized (mPauseLock) {
                mPaused = true;
            }
        }

        //resume the thread
        public void onResume() {
            //get lock on the pauser object, set paused to false, and notify mPauseLock object
            synchronized (mPauseLock) {
                mPaused = false;
                //wake up all threads that are waiting on this object's monitor
                mPauseLock.notifyAll();
            }
        }
    }

    //Runnable that indefinitely streams HeightHold packets to the drone to make it hover, until the 'Land' button is pressed.
    //This thread is used in addition to the FollowRunnable thread
    class HoverRunnable implements Runnable {
        private final Object mPauseLock;
        private boolean mPaused;
        private boolean mFinished;

        public HoverRunnable() {
            mPauseLock = new Object();
            mPaused = false;
            mFinished = false;
        }

        /* Stream HeightHold packets with no pitch, roll, yaw, and a height of TARG_HEIGHT, indefinitely, until the 'Land' button is pressed
         * NOTE: JoystickRunnable should already be paused at this point
         */
        @Override
        public void run() {
            try {
                while (true) {
                    //HOVER SEQUENCE
                    sendPacket(new HeightHoldPacket(0, 0, 0, TARG_HEIGHT));

                    //Check if a kill has been requested. If so, end this thread.
                    //NOTE: DRONE WILL FALL
                    if (killCheck()) {
                        return;
                    }

                    //Check of a land has been requested. If so, return.
                    if (landCheck()) {
                        //The landing thread has begun at this point
                        return;
                    }

                    Thread.sleep(100);
                }
            }

            //This thread can be interrupted by hitting 'Kill'. In that case, stop and resume the joystick streaming
            catch (InterruptedException e) {
                kill.set(false);
                e.printStackTrace();
                //thread now stops and goes home
            }
        }
    }


    //send a CRTP packet to the drone, waiting for ack from drone but ignoring it
    private void sendPacket(CrtpPacket packet) {
        byte[] ack = new byte[1];
        byte[] dataOut = packet.toByteArray();

        Log.i(LOG_TAG, "Sending next packet via sendBulkTransfer...");


        if (dataOut.length == 15) {
            Log.i(LOG_TAG, String.format("Phone sending USB packet 0x%02X 0x%02X 0x%02X 0x%02X 0x%02X 0x%02X 0x%02X 0x%02X 0x%02X " +
                            "0x%02X 0x%02X 0x%02X 0x%02X 0x%02X 0x%02X",
                    //"0x%02X 0x%02X 0x%02X 0x%02X " +
                    //"0x%02X 0x%02X 0x%02X 0x%02X 0x%02X 0x%02X 0x%02X 0x%02X 0x%02X 0x%02X " +
                    //"0x%02X 0x%02X 0x%02X 0x%02X to drone",
                    dataOut[0], dataOut[1], dataOut[2], dataOut[3], dataOut[4],
                    dataOut[5], dataOut[6], dataOut[7], dataOut[8], dataOut[9], dataOut[10], dataOut[11], dataOut[12],
                    dataOut[13], dataOut[14]));
        }
        else if (dataOut.length == 18) {
            Log.i(LOG_TAG, String.format("Phone sending USB packet 0x%02X 0x%02X 0x%02X 0x%02X 0x%02X 0x%02X 0x%02X 0x%02X 0x%02X " +
                            "0x%02X 0x%02X 0x%02X 0x%02X 0x%02X 0x%02X 0x%02X 0x%02X 0x%02X",
                    //"0x%02X 0x%02X 0x%02X 0x%02X " +
                    //"0x%02X 0x%02X 0x%02X 0x%02X 0x%02X 0x%02X 0x%02X 0x%02X 0x%02X 0x%02X " +
                    //"0x%02X 0x%02X 0x%02X 0x%02X to drone",
                    dataOut[0], dataOut[1], dataOut[2], dataOut[3], dataOut[4],
                    dataOut[5], dataOut[6], dataOut[7], dataOut[8], dataOut[9], dataOut[10], dataOut[11], dataOut[12],
                    dataOut[13], dataOut[14], dataOut[15], dataOut[16], dataOut[17]));
        }

        usbController.sendBulkTransfer(dataOut, ack);

        if (ack[0] == 0x09)
            Log.i(LOG_TAG, "sendBulkTransfer got back correct ack from drone 0x09");
    }


    public void onNewDistanceData() {
        synchronized (xAxisUpdateLock) {
            Log.i(LOG_TAG, "Notify xAxisUpdate");
            xAxisUpdateLock.notify();
        }
    }

    public void setFreshDist(boolean requested) {
        freshPosenetDistData.set(requested);
    }

    public void setFreshAngle(boolean requested) {
        freshPosenetAngleData.set(requested);
    }

    public void setFreshTorsoTiltRatio(boolean requested) {
        freshPosenetTorsoTiltRatio.set(requested);
    }

    public void setFreshBbCenterOffset(boolean requested) {
        freshPosenetBbCenterOffset.set(requested);
    }


    //Runnable that safely lands the drone, starting from TARG_HEIGHT
    class FollowRunnable implements Runnable {
        private final Object mPauseLock;
        private boolean mPaused;
        private boolean mFinished;

        //initial state is idling
        private FollowState currState = FollowState.IDLING;

        //one option would be to do each type of correction in a separate Thread...
        //create a fair semaphore with three permits, which means semphr will use a first-in first-out method (always give up to Thread that's been waiting longest)
        private Semaphore correctionLock = new Semaphore(3, true);

        //whether to take angle psi into account for yawing
        //if it's false, lateral movement of the human in the frame will result in the drone rolling
        //if true, lateral movement of human in frame will result in yaw to angle psi
        private LateralHandlingMethod mLateralMethod = LateralHandlingMethod.ROLL_TO_CENTER;

        final int[] cnt = {0};
        int thrust_mult = 1;
        int thrust_step = 100;
        int thrust_dstep = 10;
        int thrust = 3000;
        int pitch = 0;
        int roll = 0;
        int yawrate = 0;
        final float start_height = 0.05f;


        public FollowRunnable() {
            mPauseLock = new Object();
            mPaused = false;
            mFinished = false;

            //init lateral method to roll to center
            this.mLateralMethod = LateralHandlingMethod.ROLL_TO_CENTER;
        }

        private int launchSequence() {
            //Unlock startup thrust protection
            sendPacket(new CommanderPacket(0, 0, 0, (char) 0));

            //UP SEQUENCE
            while (cnt[0] < 50) { //SHOULD BE 50
                sendPacket(new HeightHoldPacket(0, 0, 0, (float) start_height + (TARG_HEIGHT - start_height) * (cnt[0] / 50.0f)));

                //always check if 'Kill' button has been pressed
                if (killCheck()) {
                    return -1;
                }

                try {
                    Thread.sleep(50);
                }

                //if interrupted by kill()
                catch (InterruptedException e) {
                    e.printStackTrace();
                    following.set(false);
                    kill.set(false);
                    //thread now stops and goes home

                    //notify FollowRunnable thread to return
                    return -1;
                }

                cnt[0]++;
            }

            cnt[0] = 0;

            return 0;
        }


        /**Fly a small arc of circle around human
         * @param dir - direction in which to fly the arc. 1 is right, -1 is left
         * */
        private int arcSequence(float dir) {
            //yaw. If left (-1) was specified, need to yaw right (negative yaw)
            //If right (1) was specified, need to yaw left (positive yaw)

            //Send 10 yaw packets
            while (cnt[0] < 10) {
                sendPacket(new HeightHoldPacket(0, 0, dir * CORRECTION_VEL_YAW, TARG_HEIGHT));

                //always check if 'Kill' button has been pressed
                if (killCheck()) {
                    return -1;
                }

                //sleep 90 ms between packet sends
                try {
                    Thread.sleep(90);
                }

                //if interrupted by kill()
                catch (InterruptedException e) {
                    e.printStackTrace();
                    following.set(false);
                    kill.set(false);
                    //thread now stops and goes home

                    //notify FollowRunnable thread to return
                    return -1;
                }

                cnt[0]++;
            }

            cnt[0] = 0;

            //roll. If left (-1) was specified, need to roll left (negative roll)
            //If right (1) was specified, need to roll right (positive roll)

            //send 5 roll packets
            while (cnt[0] < 5) {
                sendPacket(new HeightHoldPacket(0, dir * CORRECTION_VEL_ROLL, 0, TARG_HEIGHT));

                //always check if 'Kill' button has been pressed
                if (killCheck()) {
                    return -1;
                }

                //sleep 90 ms between packet sends
                try {
                    Thread.sleep(90);
                }

                //if interrupted by kill()
                catch (InterruptedException e) {
                    e.printStackTrace();
                    following.set(false);
                    kill.set(false);
                    //thread now stops and goes home

                    //notify FollowRunnable thread to return
                    return -1;
                }

                cnt[0]++;
            }

            cnt[0] = 0;

            return 0;
        }



        /**
         *
         * Main adjustment loop for human tracking - it works using a state machine. It works okay, but it's a pretty hacky technique. Better to use the PID version below.
         * Pivoting when mannequin turns, back/fwd when mannequin dist changes, centering if mannequin isn't in center of frame.
         * This function should also be rewritten in the future. There's a lot of code repeated, and it's too long.

         PSEUDOCODE
        ON EACH LOOP:
        (We essentially have a semaphore that can be used by one of three different correction "threads." Distance is the initial recipient of said semaphore.)
        1. Let's assume (**FOR NOW**) that mannequin and drone start face-to-face.
        2. We start in the idling state.
        3. Check state first.
            3a. IF state is IDLING:
                I. Check distance first.
                II. IF distance is not in appropriate range:
                    i. Push an appropriate distance adjustment to the drone.
                    ii. Set state to CORRECTING_DIST.
                III. ELSE:
                    i. We know distance is currently correct, so check centering.
                    ii. IF bounding box is not in appropriate range:
                        A. Push an appropriate centering adjustment to the drone.
                        B. Set state to CENTERING.
                    iii. ELSE:
                        A. Now we know distance and centering are correct, so can finally check torso tilt ratio.
                        B. IF tilt ratio is NOT in appropriate range:
                            a. Push an appropriate torso tilt correction packet (combines roll and yaw).
                            b. Keep state in IDLING. After each torso tilt push (**FOR NOW**), we want distance and centering to be checked again just in case.
                        C. ELSE:
                            a. Everything's good. Send blank hover packet and continue in IDLING state.
            3b. IF state is CORRECTING_DIST:
                I. Check distance.
                II. IF distance is not in appropriate range:
                    i. Push an appropriate distance PLUS CENTERING (just in case dist correction unintentionally affects centering) adjustment to the drone.
                III: ELSE:
                    i. For now, just be sure to send blank hover packet.
                    ii.Return to IDLING state to check all following parameters again.
            3c. IF state is CENTERING:
                I. Check centering.
                II. IF bounding box is not in appropriate range:
                    i. Push an appropriate centering PLUS DISTANCE (just in case centering unintentionally affects dist) adjustment to the drone.
                III: ELSE:
                    i. For now, just be sure to send blank hover packet.
                    ii.Return to IDLING state to check all following parameters again.
         */
        private int follow_control_statemach() {
            float dist_to_hum, torso_tilt_ratio, torso_tilt_ratio_abs, hum_angle, bb_center_off;

            //default to no adjustments
            float vx = 0, vy = 0, yaw = 0;

            //print out velocities
            Log.i(LOG_TAG, "X vel is " + posenetStats.getXVel() + ", y vel is " + posenetStats.getYVel() + ", ang vel is " + posenetStats.getAngVel());

            //state mach to determine what Posenet data to check and what corrections to make
            switch (currState) {
                //the drone is idling, just hovering in place
                case IDLING:
                    //check distance first
                    if (freshPosenetDistData.get()) {
                        //ready to update distance from human
                        dist_to_hum = posenetStats.getDistToHum();
                        Log.i(CTRL, "IDLING: From HumFollower: dist to hum is " + dist_to_hum);


                        //we'd like to stay in the distance range ~0.4-0.6m
                        if (dist_to_hum == -1.0f || dist_to_hum == 0f) {
                            Log.i(CTRL, "Human not found, sending hover pkt");

                            //if human not in frame or it's too early, just hover in place
                        }
                        else if (dist_to_hum < FOLLOWING_NEAR_BOUND) {
                            Log.i(CTRL, "Human too close, pitch backward one packet...");

                            //if human too close, pitch backward one packet
                            vx = -CORRECTION_VEL_PITCH;

                            //set state to CORRECTING_DIST
                            currState = FollowState.CORRECTING_DIST;
                        }
                        else if (dist_to_hum > FOLLOWING_FAR_BOUND) {
                            Log.i(CTRL, "Human too far, pitch forward one packet...");

                            //if human too far, pitch forward one packet
                            vx = CORRECTION_VEL_PITCH;

                            //set state to CORRECTING_DIST
                            currState = FollowState.CORRECTING_DIST;
                        }
                        //otherwise human is in frame, and we're at an appropriate distance, so now check centering
                        else {
                            Log.i(CTRL, "IDLING: Human in frame, at appropriate dist, so checking centering");

                            if (freshPosenetBbCenterOffset.get()) {
                                //ready to update bounding box center's offset wrt to frame offset
                                bb_center_off = posenetStats.getBbOffCenter();
                                Log.i(CTRL, "IDLING: From HumFollower: bbox center offset is " + bb_center_off);

                                //check for inexistent/invalid bb center offset data
                                if (bb_center_off == -1.0f || bb_center_off == 0f) {
                                    Log.i(CTRL, "IDLING: Bb ctr data came back -1, skipping adjustment");

                                    //if human not in frame or it's too early, make no adjustment, and don't move out of IDLING state
                                }
                                else if (bb_center_off < -FOLLOWING_BB_CENTER_THRESHOLD) {
                                    Log.i(CTRL, "IDLING: Human too far left, rolling left...");

                                    //if human too far left (wrt to drone's perspective), move drone left
                                    vy = -CORRECTION_VEL_ROLL_SMALL;

                                    //set state to CORRECTING_DIST
                                    currState = FollowState.CENTERING;
                                }
                                else if (bb_center_off > FOLLOWING_BB_CENTER_THRESHOLD) {
                                    Log.i(CTRL, "IDLING: Human too far right, rolling right...");

                                    //if human too far right (wrt to drone's perspective), move drone right
                                    vy = CORRECTION_VEL_ROLL_SMALL;

                                    //set state to CORRECTING_DIST
                                    currState = FollowState.CENTERING;
                                }
                                else {
                                    Log.i(CTRL, "IDLING: Human in frame, at appropriate dist and centered, so checking torso tilt ratio");

                                    //now we know drone is at an appropriate distance and is centered in front of human, so can check torso tilt ratio
                                    if (freshPosenetAngleData.get()) {
                                        torso_tilt_ratio = posenetStats.getHumAngle();
                                        //torso_tilt_ratio_abs = Math.abs(torso_tilt_ratio);

                                        Log.i(CTRL, "IDLING: From HumanFollower: human torso angle is " + torso_tilt_ratio);

                                        if (torso_tilt_ratio == -1.0f || torso_tilt_ratio == 0f) { //FIXME: DEAL WITH EYES PASSING BEYOND SHOULDERS
                                            Log.i(CTRL, "IDLING: Torso tilt ratio value problem, sending hover pkt");

                                            //make no adjustment, don't move out of IDLING state
                                        }
                                        //if person has rotated too far right
                                        else if (torso_tilt_ratio < -FOLLOWING_ANGLE_THRESHOLD) {
                                            //yaw right, roll left
                                            Log.i(CTRL, "IDLING: Torso tilt ratio too small, yawing right, rolling left");

                                            //arc left
                                            if (arcSequence(-1) != 0) {
                                                //kill or land requested, notify HumanFollower Thread to return
                                                return -1;
                                            }

                                            //remain in IDLING state, distance and ctring will be checked after arcSequence completes
                                        }
                                        //if person has rotated too far left
                                        else if (torso_tilt_ratio > FOLLOWING_ANGLE_THRESHOLD) {
                                            //yaw left, roll right
                                            Log.i(CTRL, "IDLING: Torso tilt ratio too big, yawing left, rolling right");

                                            //arc right
                                            if (arcSequence(1) != 0) {
                                                //kill or land requested, notify HumanFollower Thread to return
                                                return -1;
                                            }

                                            //remain in IDLING state, distance and ctring will be checked after arcSequence completes
                                        }

                                        //otherwise we're good on tilt, centering, and distance. Remain in IDLING state, just hovering in place
                                        else {
                                            Log.i(CTRL, "ALL THREE PARAMS (incl torso tilt) CLEAR, HOVER IN PLACE");
                                        }

                                        //set Posenet torso tilt ratio data NOT fresh anymore
                                        freshPosenetTorsoTiltRatio.set(false);

                                        //set Posenet angle data to NOT fresh anymore
                                        freshPosenetAngleData.set(false);
                                    }
                                    else {
                                        Log.i(CTRL, "IDLING: Torso tilt data not fresh, making no adjustments");
                                    }
                                }


                                //set Posenet bb center offset data NOT fresh anymore
                                freshPosenetBbCenterOffset.set(false);
                            }
                            else {
                                Log.i(CTRL, "IDLING: Centering data not fresh, making no adjustments, not even checking torso tilt data");
                            }
                        }

                        //set Posenet distance data NOT fresh anymore
                        freshPosenetDistData.set(false);
                    }
                    //otherwise data isn't fresh, just hover in place
                    else {
                        Log.i(CTRL, "IDLING: Dist data not fresh, making no adjustments, not checking centering nor torso tilt");
                    }

                    break;
                case CORRECTING_DIST:
                    if (freshPosenetDistData.get()) {
                        dist_to_hum = posenetStats.getDistToHum();
                        Log.i(CTRL, "CORRECTING_DIST: From HumFollower: dist to hum is " + dist_to_hum);

                        //we'd like to stay in the distance range ~0.4-0.6m
                        if (dist_to_hum == -1.0f || dist_to_hum == 0f) {
                            Log.i(CTRL, "CORRECTING_DIST: human not found or invalid dist data, skipping adjustment");

                            //if human not in frame or it's too early, make no adjustments, but remain in CORRECTING_DIST state.
                        }
                        else if (dist_to_hum < FOLLOWING_NEAR_BOUND) {
                            Log.i(CTRL, "Human too close, pitch backward one packet...");

                            //if human too close, pitch backward one packet
                            vx = -CORRECTION_VEL_PITCH;

                            //let's also get centering data, if possible, and make vy adjustment (in case person is moving diagonally)
                            if (freshPosenetBbCenterOffset.get()) {
                                bb_center_off = posenetStats.getBbOffCenter();
                                Log.i(CTRL, "CORRECTING_DIST: From HumFollower: bbox center offset is " + bb_center_off);

                                //check for inexistent/invalid bb center offset data
                                if (bb_center_off == -1.0f || bb_center_off == 0f) {
                                    Log.i(CTRL, "CORRECTING_DIST: Bb ctr data came back -1, skipping adjustment");

                                    //if human not in frame or it's too early, make no adjustment, and don't move out of IDLING state
                                }
                                else if (bb_center_off < -FOLLOWING_BB_CENTER_THRESHOLD) {
                                    Log.i(CTRL, "CORRECTING_DIST: Human too far left, rolling left...");

                                    //if human too far left (wrt to drone's perspective), move drone left
                                    vy = -CORRECTION_VEL_ROLL_SMALL;

                                }
                                else if (bb_center_off > FOLLOWING_BB_CENTER_THRESHOLD) {
                                    Log.i(CTRL, "CORRECTING_DIST: Human too far right, rolling right...");

                                    //if human too far right (wrt to drone's perspective), move drone right
                                    vy = CORRECTION_VEL_ROLL_SMALL;
                                }
                                else {
                                    Log.i(CTRL, "CORRECTING_DIST: Bb ctr is OKAY");
                                }

                                freshPosenetBbCenterOffset.set(false);
                            }
                            //otherwise data isn't fresh, just hover in place
                            else {
                                Log.i(CTRL, "CORRECTING_DIST: bb center offset data not fresh, making no adjustments, not checking centering nor torso tilt");
                            }

                            //stay in CORRECTING_DIST state
                        }
                        else if (dist_to_hum > FOLLOWING_FAR_BOUND) {
                            Log.i(CTRL, "Human too far, pitch forward one packet...");

                            //if human too far, pitch forward one packet
                            vx = CORRECTION_VEL_PITCH;

                            //FIXME: REPEATED CODE
                            //let's also get centering data, if possible, and make vy adjustment (in case person is moving diagonally)
                            if (freshPosenetBbCenterOffset.get()) {
                                bb_center_off = posenetStats.getBbOffCenter();
                                Log.i(CTRL, "CORRECTING_DIST: From HumFollower: bbox center offset is " + bb_center_off);

                                //check for inexistent/invalid bb center offset data
                                if (bb_center_off == -1.0f || bb_center_off == 0f) {
                                    Log.i(CTRL, "CORRECTING_DIST: Bb ctr data came back -1, skipping adjustment");

                                    //if human not in frame or it's too early, make no adjustment, and don't move out of IDLING state
                                }
                                else if (bb_center_off < -FOLLOWING_BB_CENTER_THRESHOLD) {
                                    Log.i(CTRL, "CORRECTING_DIST: Human too far left, rolling left...");

                                    //if human too far left (wrt to drone's perspective), move drone left
                                    vy = -CORRECTION_VEL_ROLL_SMALL;

                                }
                                else if (bb_center_off > FOLLOWING_BB_CENTER_THRESHOLD) {
                                    Log.i(CTRL, "CORRECTING_DIST: Human too far right, rolling right...");

                                    //if human too far right (wrt to drone's perspective), move drone right
                                    vy = CORRECTION_VEL_ROLL_SMALL;
                                }
                                else {
                                    Log.i(CTRL, "CORRECTING_DIST: Bb ctr is OKAY");
                                }

                                freshPosenetBbCenterOffset.set(false);
                            }
                            //otherwise data isn't fresh, just hover in place
                            else {
                                Log.i(CTRL, "CORRECTING_DIST: bb center offset data not fresh, making no adjustments, not checking centering nor torso tilt");
                            }

                            //stay in CORRECTING_DIST state
                        }
                        else {
                            //distance is looking good now, return to IDLING state, no adjustments
                            currState = FollowState.IDLING;
                        }

                        //set Posenet dist data NOT fresh anymore
                        freshPosenetDistData.set(false);
                    }
                    //otherwise data isn't fresh, just hover in place
                    else {
                        Log.i(CTRL, "CORRECTING_DIST: Dist data not fresh, making no adjustments, not checking centering nor torso tilt");
                    }


                    break;
                case CENTERING:
                    if (freshPosenetBbCenterOffset.get()) {
                        bb_center_off = posenetStats.getBbOffCenter();
                        Log.i(CTRL, "CENTERING: From HumFollower: bb center offset is " + bb_center_off);

                        //check for nonexistent or invalid data
                        if (bb_center_off == -1.0f || bb_center_off == 0f) {
                            Log.i(CTRL, "CENTERING: Human not found or center offset data invalid, skipping adjustment");

                            //if human not in frame or it's too early, make no adjustments, but remain in CORRECTING_DIST state.
                        }
                        else if (bb_center_off < -FOLLOWING_BB_CENTER_THRESHOLD) {
                            Log.i(CTRL, "CENTERING: Human too far left, rolling left");

                            //if human too far left (wrt to drone's perspective), roll left
                            vy = -CORRECTION_VEL_ROLL_SMALL;

                            //let's also get dist data, if possible, and make vx adjustment (in case person is moving diagonally)
                            if (freshPosenetDistData.get()) {
                                dist_to_hum = posenetStats.getDistToHum();
                                Log.i(CTRL, "CENTERING: From HumFollower: dist to hum is " + dist_to_hum);

                                //check for inexistent/invalid dist data
                                if (dist_to_hum == -1.0f || dist_to_hum == 0f) {
                                    Log.i(CTRL, "CENTERING: dist data came back -1, skipping adjustment");

                                    //if human not in frame or it's too early, make no adjustment, and don't move out of IDLING state
                                }
                                else if (dist_to_hum < FOLLOWING_NEAR_BOUND) {
                                    Log.i(CTRL, "CENTERING: human too close, pitching back");

                                    //if human too close, move drone back
                                    vx = -CORRECTION_VEL_PITCH;
                                }
                                else if (dist_to_hum > FOLLOWING_FAR_BOUND) {
                                    Log.i(CTRL, "CENTERING: Human too far, pitching forward");

                                    //if human too far, move drone forward
                                    vx = CORRECTION_VEL_PITCH;
                                }
                                else {
                                    Log.i(CTRL, "CENTERING: dist is OKAY");
                                }

                                freshPosenetDistData.set(false);
                            }
                            //otherwise data isn't fresh, just hover in place
                            else {
                                Log.i(CTRL, "CENTERING: Dist data not fresh, making no adjustments, not checking centering nor torso tilt");
                            }

                            //stay in CENTERING state
                        }
                        else if (bb_center_off > FOLLOWING_BB_CENTER_THRESHOLD) {
                            Log.i(CTRL, "CENTERING: human too far right, rolling right");

                            //if human too far right, roll right
                            vy = CORRECTION_VEL_ROLL_SMALL;

                            //let's also get dist data, if possible, and make vx adjustment (in case person is moving diagonally)
                            if (freshPosenetDistData.get()) {
                                dist_to_hum = posenetStats.getDistToHum();
                                Log.i(CTRL, "CENTERING: From HumFollower: dist to hum is " + dist_to_hum);

                                //check for inexistent/invalid dist data
                                if (dist_to_hum == -1.0f || dist_to_hum == 0f) {
                                    Log.i(CTRL, "CENTERING: dist data came back -1, skipping adjustment");

                                    //if human not in frame or it's too early, make no adjustment, and don't move out of IDLING state
                                }
                                else if (dist_to_hum < FOLLOWING_NEAR_BOUND) {
                                    Log.i(CTRL, "CENTERING: human too close, pitching back");

                                    //if human too close, move drone back
                                    vx = -CORRECTION_VEL_PITCH;
                                }
                                else if (dist_to_hum > FOLLOWING_FAR_BOUND) {
                                    Log.i(CTRL, "CENTERING: Human too far, pitching forward");

                                    //if human too far, move drone forward
                                    vx = CORRECTION_VEL_PITCH;
                                }
                                else {
                                    Log.i(CTRL, "CENTERING: dist is OKAY");
                                }

                                freshPosenetDistData.set(false);
                            }
                            //otherwise data isn't fresh, just hover in place
                            else {
                                Log.i(CTRL, "CENTERING: Dist data not fresh, making no adjustments, not checking centering nor torso tilt");
                            }

                            //stay in CORRECTING_DIST state
                        }
                        else {
                            //looks centered, so return to IDLING state, no adjustments
                            currState = FollowState.IDLING;
                        }

                        //set Posenet dist data NOT fresh anymore
                        freshPosenetBbCenterOffset.set(false);
                    }
                    //otherwise data isn't fresh, just hover in place
                    else {
                        Log.i(CTRL, "CENTERING: Bb center offset data not fresh, making no adjustments, not checking centering nor torso tilt");
                    }

                    break;
                case CORRECTING_TILT:
                    break;
            }


            //send the packet with appropriate correction settings
            sendPacket(new HeightHoldPacket(vx, vy, yaw, TARG_HEIGHT));


            //Check if a kill has been requested. If so, end this thread.
            //NOTE: DRONE WILL FALL
            if (killCheck()) {
                return -1;
            }

            //Check of a land has been requested. If so, return.
            if (landCheck()) {
                //The landing thread has begun at this point
                return -1;
            }

            //Posenet should already delay us about 20ms
            try {
                Thread.sleep(90);  //ORIGINALLY: 90ms
            }

            //if interrupted by kill()
            catch (InterruptedException e) {
                //stop Posenet backgnd thread
                posenetStats.stop();

                e.printStackTrace();
                following.set(false);
                kill.set(false);

                //notify FollowRunnable thread to exit
                return -1;
            }

            return 0;
        }

        private final String PID_TAG = "CTRL_PID";

        public void setLateralHandlingMethod(LateralHandlingMethod requestedMethod) {
            this.mLateralMethod = requestedMethod;
        }

        public float estimatePsi() {
            //z coordinate of point at center of shoulders
            float z_cs = posenetStats.getDistToHum();

            //estimated dist between drone and hum along camera coordinate frame's x axis
            float x_cs = (posenetStats.getBbOffCenter() - CTR_OFFSET_DESIRED) * posenetStats.getCurrScale();

            //calculate the estimated euclidean dist to the human (should be similar to z_cs)
            double euclidean_dist_to_hum = Math.sqrt((x_cs * x_cs) + (z_cs * z_cs));

            Log.i(LOG_TAG, "Estimated z_cs is " + z_cs + ", x_cs is " + x_cs + ", euclid dist is " + euclidean_dist_to_hum);

            //finally, use basic trig to find psi, the yaw angle from drone to human
            return (float)Math.toDegrees(Math.asin(x_cs / euclidean_dist_to_hum)) * PSI_SCALEUP;
        }


        /**
         * PID controller version of the follower software block.
         * The controllers correspond to POD’s yaw, distance from the human, and position along the human’s x-axis, H_x.
         * Keep in mind that the drone already runs PIDs to set the desired position, so we're nesting more PIDs on top of that lower level.
         */
        private int follow_control_pid() {
            float dist_to_hum, torso_tilt_ratio, torso_tilt_ratio_abs, hum_angle, bb_center_off;
            float recommended_dist_change_pid, recommended_x_change_pid, recommended_yaw_change_pid;

            //angle from drone to hum
            float psi;

            //default to no adjustments
            float dx = 0, dy = 0, yaw = 0;

            //print out velocities
            Log.i(LOG_TAG, "X vel is " + posenetStats.getXVel() + ", y vel is " + posenetStats.getYVel() + ", ang vel is " + posenetStats.getAngVel());

            //get time elapsed in milliseconds since last PID update
            timeElapsed = System.currentTimeMillis() - prevTime;

            //check distance first
            if (freshPosenetDistData.get()) {
                //ready to update distance from human
                dist_to_hum = posenetStats.getDistToHum();
                Log.i(PID_TAG, "From HumFollower PID loop: dist to hum is " + dist_to_hum);


                //we'd like to stay in the distance range ~0.4-0.6m
                if (dist_to_hum == -1.0f || dist_to_hum == 0f) {
                    Log.i(PID_TAG, "Human not found, sending hover pkt");

                    //if human not in frame or it's too early, just hover in place, make sure pusher off
                    mPushaT.switchOff(this);
                }

                //if dist NOT in acceptable range, run PID, with desired always being the middle value of the range
                else if (dist_to_hum < FOLLOWING_NEAR_BOUND || dist_to_hum > FOLLOWING_FAR_BOUND) {
                    //Log.i(PID_TAG, "Human too close or too far, running PID ctlr...");
                    recommended_dist_change_pid = distPid.update(DIST_DESIRED - dist_to_hum, timeElapsed);

                    Log.i(PID_TAG, "Hum too close or far, ran PID, setting position change " + -recommended_dist_change_pid);

                    //set appropriate dist change for PositionPacket. Negate it because, e.g., if recommended change is negative, we need to move drone fwd, etc.
                    dx = -recommended_dist_change_pid;
                }

                //otherwise human in frame and dist in acceptable range, so don't make pitch adjustments

                freshPosenetDistData.set(false);
            }
            else {
                Log.i(PID_TAG, "PID: Dist data not fresh, making no dist adjustments");
            }

            //If yawToPsi is false, that means we want to actually roll the drone when human moves laterally
            //Let's see if the x-axis data is fresh
            if (mLateralMethod == LateralHandlingMethod.ROLL_TO_CENTER && freshPosenetBbCenterOffset.get()) {
                //ready to update bounding box center's offset wrt to frame offset
                bb_center_off = posenetStats.getBbOffCenter();
                Log.i(PID_TAG, "From HumFollower PID loop: bbox center offset is " + bb_center_off);

                //check for inexistent/invalid bb center offset data
                if (bb_center_off == -1.0f || bb_center_off == 0f) {
                    Log.i(PID_TAG, "HumFollower PID: Bb ctr data came back -1, skipping adjustment");

                    //if human not in frame or it's too early, make no adjustment, maintain steady hover
                    mPushaT.switchOff(this);
                }

                //if hum not centered in frame, run PID, with desired always being the middle value of the range
                else if (bb_center_off < CTR_OFFSET_DESIRED - FOLLOWING_BB_CENTER_THRESHOLD || bb_center_off > CTR_OFFSET_DESIRED + FOLLOWING_BB_CENTER_THRESHOLD) {
                    Log.i(PID_TAG, "Human too far left or right, running PID ctlr...");

                    //the value returned from the PID ctrl will actually be in pixels, so scale it up to meters
                    recommended_x_change_pid = xAxisPid.update(CTR_OFFSET_DESIRED - bb_center_off, timeElapsed) * posenetStats.getCurrScale();

                    Log.i(PID_TAG, "Hum not centered in frame, ran PID, setting dy position change " + recommended_x_change_pid);

                    //set appropriate x-axis change for PositionPacket
                    dy = recommended_x_change_pid;
                }

                //set Posenet bb center offset data NOT fresh anymore
                freshPosenetBbCenterOffset.set(false);
            }
            //otherwise if we're in yawtopsi mode, adjust yaw appropriately to bring the human back to center of frame
            else if (mLateralMethod == LateralHandlingMethod.YAW_TO_PSI && freshPosenetBbCenterOffset.get()) {
                //ready to update bounding box center's offset wrt to frame offset
                bb_center_off = posenetStats.getBbOffCenter();
                Log.i(PID_TAG, "From HumFollower PID loop YAWTOPSI: bbox center offset is " + bb_center_off);

                //check for inexistent/invalid bb center offset data
                if (bb_center_off == -1.0f || bb_center_off == 0f) {
                    Log.i(PID_TAG, "HumFollower PID: Bb ctr data came back -1, skipping adjustment");

                    //if human not in frame or it's too early, make no adjustment, maintain steady hover
                    mPushaT.switchOff(this);
                }

                //if hum not centered in frame, run PID, with desired always being the middle value of the range
                else if (bb_center_off < CTR_OFFSET_DESIRED - FOLLOWING_BB_CENTER_THRESHOLD || bb_center_off > CTR_OFFSET_DESIRED + FOLLOWING_BB_CENTER_THRESHOLD) {
                    Log.i(PID_TAG, "Human too far left or right, running PID ctlr for yaw...");

                    //let's calculate psi, the angle from the drone to the human
                    psi = estimatePsi();
                    Log.i(PID_TAG, "Estimated psi as " + psi + " degrees");

                    //the value returned from the PID ctrl will actually be recommended angle adjustment of the drone.
                    //what we'll be sending in the PositionPacket is requested yawVel in deg/s.
                    //So we'll find that velocity by just deriving position using timeElapsed
                    recommended_yaw_change_pid = yawPid.update(PSI_DESIRED - psi, timeElapsed) / (timeElapsed / 1000f); //find deg/sec

                    Log.i(PID_TAG, "Hum not centered in frame, ran PID, setting yaw change " + recommended_yaw_change_pid);

                    //set appropriate x-axis change for PositionPacket
                    yaw = recommended_yaw_change_pid;
                }

                //set Posenet bb center offset data NOT fresh anymore
                freshPosenetBbCenterOffset.set(false);
            }
            else {
                Log.i(PID_TAG, "PID: Centering data not fresh or lateral mode is YAW_TO_PSI, making no centering adjustments");
            }

            //check torso tilt angle tau
            if (freshPosenetAngleData.get()) {
                torso_tilt_ratio = posenetStats.getHumAngle();
                //torso_tilt_ratio_abs = Math.abs(torso_tilt_ratio);

                Log.i(CTRL, "From HumanFollower PID loop: human torso angle is " + torso_tilt_ratio);

                if (torso_tilt_ratio == -1.0f || torso_tilt_ratio == 0f) { //FIXME: DEAL WITH EYES PASSING BEYOND SHOULDERS
                    Log.i(PID_TAG, "HumFollower PID: Torso tilt ratio value problem, sending hover pkt and skipping adjustment");

                    //make no adjustment, make sure pusher is off
                    mPushaT.switchOff(this);
                }
                //if person has rotated too far right or left
                else if (torso_tilt_ratio < -FOLLOWING_ANGLE_THRESHOLD) {
                    Log.i(PID_TAG, "HumFollower PID: Torso tilt ratio too small, switching on Pusha");

                    //switch on the pusher to start pushing the drone left, this call will also set our lateral handling mode to YAWTOPSI
                    mPushaT.switchOn(PushaDirection.LEFT, this);
                }
                else if (torso_tilt_ratio > FOLLOWING_ANGLE_THRESHOLD) {
                    Log.i(PID_TAG, "HumFollower PID: Torso tilt ratio too large, switching on Pusha");

                    //switch on the pusher to start pushing the drone right, this call will also set our lateral handling mode to YAWTOPSI
                    mPushaT.switchOn(PushaDirection.RIGHT, this);
                }
                else {
                    //otherwise torso angle is in appropriate range, make sure pusha is off and lateral mode is back to roll
                    mPushaT.switchOff(this);
                }

                //set Posenet torso tilt ratio data NOT fresh anymore
                freshPosenetTorsoTiltRatio.set(false);

                //set Posenet angle data to NOT fresh anymore
                freshPosenetAngleData.set(false);
            }
            else {
                Log.i(PID_TAG, "PID: Torso tilt data not fresh, making no adjustments");
            }

            //if the pusher is on, push the drone sideways a little
            //note that the pusher will only be on if human is pivoting
            if (mPushaT.isOn()) {
                Log.i(PID_TAG, "PID: getting push from PushaT");
                dy = mPushaT.getPush();
            }

            //send the packet with appropriate correction settings
            sendPacket(new PositionPacket(dx, dy, yaw, TARG_HEIGHT));

            //get current timestamp to use for next loop iteration
            prevTime = System.currentTimeMillis();

            //Check if a kill has been requested. If so, end this thread.
            //NOTE: DRONE WILL FALL
            if (killCheck()) {
                return -1;
            }

            //Check of a land has been requested. If so, return.
            if (landCheck()) {
                //The landing thread has begun at this point
                return -1;
            }

            //Posenet should already delay us about 20ms
            try {
                Thread.sleep(90);  //ORIGINALLY: 90ms
            }

            //if interrupted by kill()
            catch (InterruptedException e) {
                //stop Posenet backgnd thread
                posenetStats.stop();

                e.printStackTrace();
                following.set(false);
                kill.set(false);

                //notify FollowRunnable thread to exit
                return -1;
            }

            return 0;
        }


        public void run() {
            int correctionLock = 0;
            boolean waitOnLock = false;

            Log.i(LOG_TAG, "Running FollowRunnable launchSequence()");

            //launch the drone up to TARG_HEIGHT
            if (launchSequence() != 0) {
                //something went wrong
                return;
            }

            //'landing' should already have been reset to false at this time
            //FIXME: it makes more sense for resetting 'landing' to false to go here

            //at this point, activate Posenet human tracking (separate thread)
            posenetStats.start();

            //take an initial timestamp
            prevTime = System.currentTimeMillis();

            //hover indefinitely, following the human
            while (true) {
                //notify the HoverRunnable that we're about to go to sleep for about 150-220 ms, so HoverRunnable should send hover pkt in about

                /*
                Log.i(LOG_TAG, "starting Wait for new dist data");
                //wait for pulse from PosenetStats, indicating that there's new distance data
                synchronized (xAxisUpdateLock) {
                    try {
                        //have this thread wait until PosenetStats calls onNewDistanceData()
                        xAxisUpdateLock.wait();
                    }

                    catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
                Log.i(LOG_TAG, "wait for dist data complete");*/


                //ONE IMPLEMENTATION - USE RELAX TIME
                /*
                //we'd like to stay in the distance range 0.4-0.6m
                if (dist_to_hum == -1.0f || waitOnLock || dist_to_hum == 0f) {
                    //if human not in frame or it's too early, just hover in place
                    sendPacket(new HeightHoldPacket(0, 0, 0, TARG_HEIGHT));

                    //if we're waiting safely for dist from human to update
                    if (waitOnLock) {
                        //if we still haven't waited long enough, just keep incrementing the lock counter
                        if (correctionLock < CORRECTION_RELAX) {
                            Log.i(CTRL, "Still haven't waited long enough for waitOnLock, incr lock counter...");
                            correctionLock++;

                        }

                        //otherwise our wait time is complete, so set waitOnLock to false to enable correction adjustments
                        else {
                            Log.i(CTRL, "Wait time complete, setting waitOnLock to false...");
                            waitOnLock = false;
                            correctionLock = 0;
                        }
                    }
                }
                else if (dist_to_hum < FOLLOWING_NEAR_BOUND) {
                    Log.i(CTRL, "Human too close, pitch backward one packet...");

                    //if human too close, pitch backward one packet
                    sendPacket(new HeightHoldPacket(-CORRECTION_VEL, 0, 0, TARG_HEIGHT));

                    //lock any corrections for CORRECTION_RELAX cycles, so that we don't get ahead of the Posenet thread
                    waitOnLock = true;
                }
                else if (dist_to_hum > FOLLOWING_FAR_BOUND) {
                    Log.i(CTRL, "Human too far, pitch forward one packet...");

                    //if human too far, pitch forward one packet
                    sendPacket(new HeightHoldPacket(CORRECTION_VEL, 0, 0, TARG_HEIGHT));

                    //lock any corrections for CORRECTION_RELAX cycles, so that we don't get ahead of the Posenet thread
                    waitOnLock = true;
                }

                //otherwise human is in frame, and we're at an appropriate distance, so just hover in place
                else {
                    Log.i(CTRL, "Human in frame, at appropriate dist, sending hover pkt");
                    sendPacket(new HeightHoldPacket(0, 0, 0, TARG_HEIGHT));
                }*/

                //OTHER IMPLEMENTATION IS IN follow() - SYNCHRONIZED ON XAXISUPDATELOCK OBJECT

                //control loop
                if (follow_control_pid() != 0) {
                    //this means a kill or land was requested
                    return;
                }
            }
        }
    }

    }