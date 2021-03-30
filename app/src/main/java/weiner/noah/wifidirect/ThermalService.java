package weiner.noah.wifidirect;

import android.content.Context;
import android.os.PowerManager;
import android.util.Log;

import weiner.noah.wifidirect.control.HumanFollower;
import weiner.noah.wifidirect.control.MainActivity;

public class ThermalService {
        private PowerManager.OnThermalStatusChangedListener thermalStatusListener = null;
        private String LOG_TAG = "ThermalService";
        private MainActivity mainActivity;
        private HumanFollower humanFollower;

        //is the phone running too hot, and we need to stop Posenet to let it cool down?
        private boolean needToStopPosenet = false;

        //is the phone running too hot, and we need to land immediately to avoid a shutdown?
        private boolean needToLand = false;


        public ThermalService(MainActivity mainActivity, HumanFollower humanFollower) {
            this.mainActivity = mainActivity;
            this.humanFollower = humanFollower;
        }

        public void startListening() {
            registerThermalListener();
        }

        public void stopListening() {
            unregisterThermalListener();
        }

        private void registerThermalListener() {
            thermalStatusListener = new PowerManager.OnThermalStatusChangedListener() {
                @Override
                public void onThermalStatusChanged(int status) {
                    Log.i(LOG_TAG, "NEW THERMAL STATUS IS " + status);
                    mainActivity.showToastie("NEW THERMAL STATUS IS " + status);

                    //set text for thermal stats text at top of screen
                    mainActivity.setThermalStatusText(status);

                    //if the thermal status is "EMERGENCY", a shutdown is likely soon
                    if (status == PowerManager.THERMAL_STATUS_EMERGENCY) {
                        Log.i(LOG_TAG, "EMERGENCY LAND DUE TO PHONE OVERHEATING");

                        //notify HumanFollower to land, which will in turn stop PosenetStats thread
                        humanFollower.stop();

                        //TODO: send message to cf-usb app to change back the human following button and bool
                    }
                }
            };

            ((PowerManager)mainActivity.getSystemService(Context.POWER_SERVICE)).addThermalStatusListener(thermalStatusListener);
        }

        private void unregisterThermalListener() {
            if (thermalStatusListener != null) {
                ((PowerManager)mainActivity.getSystemService(Context.POWER_SERVICE)).
                        removeThermalStatusListener(thermalStatusListener);
                thermalStatusListener = null;
            }
        }
}
