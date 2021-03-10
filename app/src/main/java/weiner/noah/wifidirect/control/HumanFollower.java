package weiner.noah.wifidirect.control;

import android.graphics.ImageDecoder;
import android.util.Log;

import org.tensorflow.lite.examples.noah.lib.Device;
import org.tensorflow.lite.examples.noah.lib.Posenet;

import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

import weiner.noah.wifidirect.crtp.CommanderPacket;
import weiner.noah.wifidirect.crtp.CrtpPacket;
import weiner.noah.wifidirect.crtp.HeightHoldPacket;
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

    //do we have new angle data from Posenet?
    private final AtomicBoolean freshPosenetAngleData = new AtomicBoolean(false);

    //do we have new torso tilt data from Posenet?
    private final AtomicBoolean freshPosenetTorsoTiltRatio = new AtomicBoolean(false);

    private final PosenetStats posenetStats;

    private final int CORRECTION_RELAX = 5;
    private final float CORRECTION_VEL_PR = 0.2f;
    private final float CORRECTION_VEL_YAW = 15f;
    private final float FOLLOWING_FAR_BOUND = 0.57f;
    private final float FOLLOWING_NEAR_BOUND = 0.37f;
    private final float FOLLOWING_ANGLE_THRESHOLD = 10f; //10 degreees
    private final float FOLLOWING_TILT_RATIO_UPPER_BOUND = 1.7f;
    private final float FOLLOWING_TILT_RATIO_LOWER_BOUND = 0.5f;


    /*control guide:
    * YAW
    *   POSITIVE: left
    *   NEGATIVE: right
    *
    * ROLL (left/rt wrt phone)
    *   POSITIVE: left
    *   NEGATIVE: right
    *
    * PITCH (forward/back wrt phone)
    *   POSITIVE: forward
    *   NEGATIVE: backward
    * */

    private static final Object[] xAxisUpdateLock = new Object[]{};

    public HumanFollower(UsbController usbController, MainActivity mainActivity) {
        this.usbController = usbController;
        this.mainActivity = mainActivity;
        posenetStats = new PosenetStats(new Posenet(mainActivity.getApplicationContext(), "posenet_model.tflite", Device.GPU), mainActivity, this);
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
    //This thread is used in addition to the followrunnable thread
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

        final int[] cnt = {0};
        int thrust_mult = 1;
        int thrust_step = 100;
        int thrust_dstep = 10;
        int thrust = 3000;
        int pitch = 0;
        int roll = 0;
        int yawrate = 0;
        final float start_height = 0.05f;

        //let's always keep track of what's currently being corrected.
        private boolean centering = false, correctingDist = true, correctingPivot = false;


        public FollowRunnable() {
            mPauseLock = new Object();
            mPaused = false;
            mFinished = false;
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



        //main adjustment loop for human tracking
        //pivoting when mannequin turns, back/fwd when mannequin dist changes, centering if mannequin isn't in center of frame
        /*PSEUDO
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
                            b. Keep state in IDLING. After each torso tilt push (**FOR NOW**), we want everything to be checked again just in case.
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
        private int follow_control() {
            float dist_to_hum, torso_tilt_ratio, torso_tilt_ratio_abs, hum_angle;

            //default to no adjustments
            float vx = 0, vy = 0, yaw = 0;

            switch (currState) {
                //the drone is idling, just hovering in place
                case IDLING:
                    //check distance
                    break;
                case CORRECTING_DIST:
                    break;
                case CENTERING:
                    break;
                case CORRECTING_TILT:
                    break;
            }

            //check if there's new angle data available from Posenet threadd
            if (freshPosenetTorsoTiltRatio.get()) {
                torso_tilt_ratio = posenetStats.getTorsoTiltRatio();
                torso_tilt_ratio_abs = Math.abs(torso_tilt_ratio);

                Log.i(CTRL, "From HumanFollower: human torso tilt is " + torso_tilt_ratio);

                if (torso_tilt_ratio == -1.0f || torso_tilt_ratio == 0f || torso_tilt_ratio_abs > 30) { //FIXME: DEAL WITH EYES PASSING BEYOND SHOULDERS
                    Log.i(CTRL, "Torso tilt ratio value problem, sending hover pkt");
                }
                else if (torso_tilt_ratio_abs < FOLLOWING_TILT_RATIO_LOWER_BOUND) {
                    //yaw one unit, then roll one unit
                    Log.i(CTRL, "Torso tilt ratio too small, yawing right, rolling left");
                }
                else if (torso_tilt_ratio_abs > FOLLOWING_TILT_RATIO_UPPER_BOUND) {
                    //yaw one unit, then roll one unit
                    Log.i(CTRL, "Torso tilt ratio too big, yawing left, rolling right");
                }

                //otherwise we're good on tilt
                else {
                    Log.i(CTRL, "Human at an acceptable tilt, sending hover pkt");
                }


                //set Posenet torso tilt ratio data NOT fresh anymore
                freshPosenetTorsoTiltRatio.set(false);
            }

            if (freshPosenetDistData.get()) {
                //ready to update distance from human
                dist_to_hum = posenetStats.getDistToHum();
                Log.i(CTRL, "From HumFollower: dist to hum is " + dist_to_hum);


                //we'd like to stay in the distance range ~0.4-0.6m
                if (dist_to_hum == -1.0f || dist_to_hum == 0f) {
                    Log.i(CTRL, "Human not found, sending hover pkt");

                    //if human not in frame or it's too early, just hover in place
                }
                else if (dist_to_hum < FOLLOWING_NEAR_BOUND) {
                    Log.i(CTRL, "Human too close, pitch backward one packet...");

                    //if human too close, pitch backward one packet
                    vx = -CORRECTION_VEL_PR;
                }
                else if (dist_to_hum > FOLLOWING_FAR_BOUND) {
                    Log.i(CTRL, "Human too far, pitch forward one packet...");

                    //if human too far, pitch forward one packet
                    vx = CORRECTION_VEL_PR;
                }

                //otherwise human is in frame, and we're at an appropriate distance, so just hover in place
                else {
                    Log.i(CTRL, "Human in frame, at appropriate dist, sending hover pkt");
                }





                //set Posenet distance data NOT fresh anymore
                freshPosenetDistData.set(false);
            }

            //otherwise data isn't fresh, just hover in place
            else {
                Log.i(CTRL, "Dist data not fresh, sending hover pkt");
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
                if (follow_control() != 0) {
                    //this means a kill or land was requested
                    return;
                }
            }
        }
    }

    }