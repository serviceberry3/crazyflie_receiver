package weiner.noah.wifidirect.control;

import android.util.Log;

import java.util.concurrent.atomic.AtomicBoolean;

import weiner.noah.wifidirect.crtp.CommanderPacket;
import weiner.noah.wifidirect.crtp.CrtpPacket;
import weiner.noah.wifidirect.crtp.HeightHoldPacket;
import weiner.noah.wifidirect.usb.UsbController;

/** Convenience class to run a human following script.
 *
 * */
public class HumanFollower {
    private UsbController usbController;
    private final float TARG_HEIGHT = 0.3f;
    private final String LOG_TAG = "HumanFollower";

    private Thread mFollowThread;
    private Thread mLandingThread;

    private AtomicBoolean following = new AtomicBoolean(false);
    private AtomicBoolean landing = new AtomicBoolean(false);


    public HumanFollower(UsbController usbController) {
        this.usbController = usbController;
    }

    /* Pseudocode
     *  - In a new Thread, run a USB flight script that does the following:
     *  - Launch the drone up to TARG_HEIGHT, and hover there indefinitely
     *  - Once up to height, start monitoring Posenet. Adjust the drone's position based on that.
     *  - When stop is called, stop monitoring Posenet, and safely land the drone, wherever it currently is.
     * */
    public void start() {
        //set 'following' AtomicBool to true
        following.set(true);

        mFollowThread = new Thread(new FollowRunnable());
        mFollowThread.start();
    }


    public void stop() {

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

                    /*
                    if (killCheck()) {
                        return;
                    }*/

                    Thread.sleep(50);

                    cnt[0]++;
                }


                //STOP
                sendPacket(new CommanderPacket(0, 0, 0, (char) 0));


                //'landing' should already have been reset to false at this time
                //FIXME: it makes more sense for resetting 'landing' to false to go here
            }

            //If a kill was requested, stop and resume joystick thread
            catch (InterruptedException e) {
                e.printStackTrace();
                //thread now stops and goes home
            }
        }


        //CONVENIENCE FXNS
        //pause the thread to stop streaming joystick data to onboard phone so that we can run a navigation sequence, etc.
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


    //send a CRTP packet to the drone, waiting for ack from drone but ignoring it
    private void sendPacket(CrtpPacket packet) {
        usbController.sendBulkTransfer(packet.toByteArray(), new byte[1]);
    }



    //Runnable that safely lands the drone, starting from TARG_HEIGHT
    class FollowRunnable implements Runnable {
        private final Object mPauseLock;
        private boolean mPaused;
        private boolean mFinished;

        public FollowRunnable() {
            mPauseLock = new Object();
            mPaused = false;
            mFinished = false;
        }



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


                //UP SEQUENCE
                while (cnt[0] < 50) {
                    sendPacket(new HeightHoldPacket(0, 0, 0, (float) start_height + (TARG_HEIGHT - start_height) * (cnt[0] / 50.0f)));

                    /*
                    //always check if 'Kill' button has been pressed
                    if (killCheck()) {
                        return;
                    }*/

                    Thread.sleep(50);

                    cnt[0]++;
                }

                //STOP
                sendPacket(new CommanderPacket(0, 0, 0, (char) 0));

                //'landing' should already have been reset to false at this time
                //FIXME: it makes more sense for resetting 'landing' to false to go here
            }

            //If a kill was requested, stop and resume joystick thread
            catch (InterruptedException e) {
                e.printStackTrace();
                //thread now stops and goes home
            }
        }

    }
}