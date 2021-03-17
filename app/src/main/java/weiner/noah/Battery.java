package weiner.noah;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;

import weiner.noah.wifidirect.control.MainActivity;

public class Battery {
    final String basePath;

    //use of HardwarePropertiesManager only allowed in Enterprise configured Android devs
    //final HardwarePropertiesManager hwPropsMgr;

    final MainActivity mainActivity;
    private final String LOG_TAG = "Thermal";
    private boolean res;

    public Battery(String path, MainActivity mainActivity) {
        this.basePath = path;
        this.mainActivity = mainActivity;
        //this.hwPropsMgr = mainActivity.getApplicationContext().getSystemService(HardwarePropertiesManager.class);

    }


    public int getBattCurrent() {
        String content = "";
        Process mProc = null;


        String line = "";
        RandomAccessFile reader = null;
        try {
            reader = new RandomAccessFile(basePath + "battery/current_now", "r");
        }
        catch (FileNotFoundException e) {
            e.printStackTrace();
        }

        //if there's a problem instantiating RandomAccessFile, return -1
        if (reader == null)
            return -1;

        try {
            //read a line from the file
            line = reader.readLine();
        }
        catch (IOException e) {
            e.printStackTrace();
        }

        //return battery temp as Integer
        return Integer.parseInt(line);
    }

    public int getBattVoltage() {
        String content = "";
        Process mProc = null;


        String line = "";
        RandomAccessFile reader = null;
        try {
            reader = new RandomAccessFile(basePath + "battery/voltage_now", "r");
        }
        catch (FileNotFoundException e) {
            e.printStackTrace();
        }

        //if there's a problem instantiating RandomAccessFile, return -1
        if (reader == null)
            return -1;

        try {
            //read a line from the file
            line = reader.readLine();
        }
        catch (IOException e) {
            e.printStackTrace();
        }

        //return battery temp as Integer
        return Integer.parseInt(line);
    }
}
