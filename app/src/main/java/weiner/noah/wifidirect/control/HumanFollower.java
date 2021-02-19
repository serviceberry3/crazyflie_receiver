package weiner.noah.wifidirect.control;

import weiner.noah.wifidirect.usb.UsbController;

/** Convenience class to run a human following script.
 *
 * */
public class HumanFollower {
    private UsbController usbController;
    private final float TARG_HEIGHT = 0.3f;


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

    }


    public void stop() {

    }
}
