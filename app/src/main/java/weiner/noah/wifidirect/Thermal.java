package weiner.noah.wifidirect;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.CpuUsageInfo;
import android.os.HardwarePropertiesManager;
import android.util.Log;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.io.StreamCorruptedException;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

import weiner.noah.wifidirect.control.MainActivity;
import weiner.noah.wifidirect.crtp.CommanderPacket;
import weiner.noah.wifidirect.crtp.HeightHoldPacket;

public class Thermal {
    private final String basePath;

    //use of HardwarePropertiesManager only allowed in Enterprise configured Android devs
    //final HardwarePropertiesManager hwPropsMgr;

    private final MainActivity mainActivity;
    private final String LOG_TAG = "Thermal";
    private boolean res;

    //Thread to run logging in background
    private Thread mLoggingThread;

    private LogRunnable mLogRunnable;

    public Thermal(String path, MainActivity mainActivity) {
        this.basePath = path;
        this.mainActivity = mainActivity;
        //this.hwPropsMgr = mainActivity.getApplicationContext().getSystemService(HardwarePropertiesManager.class);

        handlePermissions();
    }

    public void handlePermissions() {
        res = isReadStoragePermissionGranted();
        res = isWriteStoragePermissionGranted();
    }

    public boolean isReadStoragePermissionGranted() {
        if (mainActivity.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            Log.i(LOG_TAG,"Permission to read is granted");
            return true;
        }
        /*
        else if (mainActivity.shouldShowRequestPermissionRationale(Manifest.permission.READ_EXTERNAL_STORAGE)) {
            // In an educational UI, explain to the user why your app requires this
            // permission for a specific feature to behave as expected. In this UI,
            // include a "cancel" or "no thanks" button that allows the user to
            // continue using your app without granting the permission.
            return true;
        }*/
        else {
            Log.v(LOG_TAG,"Permission to read not granted, requesting now...");
            mainActivity.requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, 3);
            return false;
        }
    }

    public boolean isWriteStoragePermissionGranted() {

        if (mainActivity.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            Log.v(LOG_TAG,"Permission to write is granted");
            return true;
        }

        else {
            Log.v(LOG_TAG,"Permission to write denied, requesting now...");
            mainActivity.requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 2);
            return false;
        }
    }

    public String getData(String path) {
        String content = "";
        Process mProc = null;


        String line = "";
        RandomAccessFile reader = null;
        try {
            reader = new RandomAccessFile(basePath + path , "r");
        }
        catch (FileNotFoundException e) {
            e.printStackTrace();
        }

        //if there's a problem instantiating RandomAccessFile, return -1
        if (reader == null) {
            Log.e(LOG_TAG, "getData(): reader came up null");
            return null;
        }

        try {
            //read a line from the file
            return reader.readLine();
        }
        catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }


    /**
     * DON'T USE, THROWS SECURITYEXCEPTION
     * @return
     */
    public CpuUsageInfo[] getCpuUsages() {
        //return hwPropsMgr.getCpuUsages();
        return null;
    }

    public int getBattTemp() {

        String content = "";
        Process mProc = null;

        /*
        String line = "";
        RandomAccessFile reader = null;
        try {
            reader = new RandomAccessFile(basePath + "tz-by-name/battery/temp", "r");
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
        return Integer.parseInt(line);*/

        /*
        try {
            Process process = Runtime.getRuntime().exec("su");
            InputStream in = process.getInputStream();
            OutputStream out = process.getOutputStream();
            String cmd = "cat " + basePath + "tz-by-name/battery/temp";
            out.write(cmd.getBytes());
            out.flush();
            out.close();
            byte[] buffer = new byte[1024 * 12]; //Able to read up to 12 KB (12288 bytes)
            int length = in.read(buffer);
            content = new String(buffer, 0, length);

            //Wait until reading finishes
            process.waitFor();

            //Do your stuff here with "content" string
            //The "content" String has the content of file
            //Log.i(LOG_TAG, "Battery temp is: " + content);
        }
        catch (IOException e) {
            Log.e(LOG_TAG, "IOException, " + e.getMessage());
        }
        catch (InterruptedException e) {
            Log.e(LOG_TAG, "InterruptedException, " + e.getMessage());
        }

        return Integer.parseInt(content);*/


        /*
        try {
           //command() sets this process builder's operating system program and arguments. This is a convenience method that sets the command to a string
            //list containing the same strings as the command array, in the same order. It is not checked whether command corresponds to a valid operating
            //system command.


            //If this property true, then any error outpt gen by subprocesses subsequently started by this object's start() method will be merged
             //with the standard output, so that both can be read using the getInputStream() method. This makes it easier to correlate error messages
             //with the corresponding output. The initial value is false.
            mProc = new ProcessBuilder().command("/system/xbin/su").redirectErrorStream(true).start();
        }
        catch (IOException e) {
            e.printStackTrace();
        }

        if (mProc == null) {
            Log.e(LOG_TAG, "gettBattTemp(): mProc is NULL!!");
            return -1;
        }

        OutputStream out = mProc.getOutputStream();
        InputStream in = mProc.getInputStream();

        if (out == null) {
            Log.e(LOG_TAG, "gettBattTemp(): OutputStream is NULL!!");
            return -1;
        }

       // String cmd = "cat " + basePath + "tz-by-name/battery/temp\n";
        String cmd = "ls\n";

        Log.d(LOG_TAG, "Native command = " + cmd);

        try {
            out.write(cmd.getBytes());
            out.flush();
            out.close();

            byte[] buffer = new byte[1024 * 12]; //Able to read up to 12 KB (12288 bytes)
            int length = in.read(buffer);
            content = new String(buffer, 0, length);

            Log.i(LOG_TAG, "File reads " + content);

            //Wait until reading finishes
            mProc.waitFor();

        }
        catch (IOException  | InterruptedException e) {
            e.printStackTrace();
        }
        return Integer.parseInt(content);*/

        return 0;
    }


    public void startLogging() {
        //make sure to nullify existing mLoggingThread
        if (mLoggingThread != null)
            mLoggingThread = null;

        //make sure to stop and nullify existing mLogRunnable
        if (mLogRunnable != null) {
            mLogRunnable.onStop();
            mLogRunnable = null;
        }

        mLogRunnable = new LogRunnable();
        mLoggingThread = new Thread(mLogRunnable);

        mLoggingThread.start();
    }

    public void stopLogging() {
        if (mLogRunnable != null)
            //notify the LogRunnable thread to return
            mLogRunnable.onStop();

        //reset mLogRunnable and mLoggingThread
        mLogRunnable = null;
        mLoggingThread = null;
    }


    class SortedStoreProperties extends Properties {

        @Override
        public void storeToXML(OutputStream out, String comments) throws IOException {
            Properties sortedProps = new Properties() {
                @Override
                public Set<Map.Entry<Object, Object>> entrySet() {
                    /*
                     * Using comparator to avoid the following exception on jdk >=9:
                     * java.lang.ClassCastException: java.base/java.util.concurrent.ConcurrentHashMap$MapEntry cannot be cast to java.base/java.lang.Comparable
                     */
                    Set<Map.Entry<Object, Object>> sortedSet = new TreeSet<Map.Entry<Object, Object>>(new Comparator<Map.Entry<Object, Object>>() {
                        @Override
                        public int compare(Map.Entry<Object, Object> o1, Map.Entry<Object, Object> o2) {
                            return o1.getKey().toString().compareTo(o2.getKey().toString());
                        }
                    }
                    );
                    sortedSet.addAll(super.entrySet());
                    return sortedSet;
                }

                @Override
                public Set<Object> keySet() {
                    return new TreeSet<Object>(super.keySet());
                }

                @Override
                public synchronized Enumeration<Object> keys() {
                    return Collections.enumeration(new TreeSet<Object>(super.keySet()));
                }

            };
            sortedProps.putAll(this);
            sortedProps.storeToXML(out, comments);
        }
    }


    class LogRunnable implements Runnable {
        private final Object mPauseLock;
        private boolean mPaused;
        private volatile boolean mFinished = false;
        //Hashtable<String, String> thermalData = new Hashtable<String, String>();

        private FileOutputStream thermalDataXmlFile;

        private final Properties thermalData = new Properties();

        public LogRunnable() {
            mPauseLock = new Object();
            mPaused = false;
            mFinished = false;
        }


        //Log all thermal data to a file on the phone every second
        public void run() {
            float timeElapsed = 0;
            while (true) {
                //check to see if stopLogging() was called
                if (mFinished)
                    return;

                /*
                thermalData.put("", "Apple");
                my_dict.put("10", "Banana");

                // using get() method
                System.out.println("\nValue at key = 10 : " + my_dict.get("10"));
                System.out.println("Value at key = 11 : " + my_dict.get("11"));

                // using isEmpty() method
                System.out.println("\nIs my dictionary empty? : " + my_dict.isEmpty() + "\n");

                // using remove() method
                // remove value at key 10
                my_dict.remove("10");
                System.out.println("Checking if the removed value exists: " + my_dict.get("10"));
                System.out.println("\nSize of my_dict : " + my_dict.size());*/


                /*
                thermalData.setProperty("camera-lowf", getData("tz-by-name/camera-lowf/temp"));
                thermalData.setProperty("camera-usr", getData("tz-by-name/camera-usr/temp"));
                thermalData.setProperty("cpuss-0-usr", getData("tz-by-name/cpuss-0-usr/temp"));
                thermalData.setProperty("cpuss-1-usr", getData("tz-by-name/cpuss-1-usr/temp"));
                thermalData.setProperty("cwlan-usr", getData("tz-by-name/cwlan-usr/temp"));
                thermalData.setProperty("ddr-usr", getData("tz-by-name/ddr-usr/temp"));
                thermalData.setProperty("cpuss-1-usr", getData("tz-by-name/cpuss-1-usr/temp"));


                for (int j = 0; j < 4; j++) {

                    for (int i = 0; i < 2; i++) {
                        String specifier;

                        if (i == 0) {
                            specifier = "cpu-0-" + j + "-step";
                        }
                        else {
                            specifier = "cpu-0-" + j + "-usr";
                        }
                        thermalData.setProperty(specifier, getData("tz-by-name/" + specifier + "/temp"));
                    }
                }

                for (int j = 0; j < 8; j++) {

                    for (int i = 0; i < 2; i++) {
                        String specifier;

                        if (i == 0) {
                            specifier = "cpu-1-" + j + "-step";
                        }
                        else {
                            specifier = "cpu-1-" + j + "-usr";
                        }
                        thermalData.setProperty(specifier, getData("tz-by-name/" + specifier + "/temp"));
                    }
                }*/



                //iterate over all 90 thermal zones, creating xml property for each one
                for (int i = 0; i < 90; i++) {
                    String name = "", val = "", trip_pt = "";
                    RandomAccessFile reader = null;
                    try {
                        reader = new RandomAccessFile(basePath + "thermal_zone" + i + "/type", "r");
                    }
                    catch (FileNotFoundException e) {
                        e.printStackTrace();
                    }

                    //if there's a problem instantiating RandomAccessFile, return -1
                    if (reader == null) {
                        Log.e(LOG_TAG, "LogRunnable: name reader is NULL for zone " + i);
                    }
                    else {

                        try {
                            //read a line from the file
                            name = reader.readLine();
                            reader.close();
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }

                    Log.i(LOG_TAG, "Name is " + name);

                    try {
                        reader = new RandomAccessFile(basePath + "thermal_zone" + i + "/temp", "r");
                    }
                    catch (FileNotFoundException e) {
                        e.printStackTrace();
                    }

                    //if there's a problem instantiating RandomAccessFile, return -1
                    if (reader == null) {
                        Log.e(LOG_TAG, "LogRunnable: val reader is NULL for zone " + i);
                    }
                    else {

                        try {
                            //read a line from the file
                            val = reader.readLine();
                            reader.close();
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }

                    Log.i(LOG_TAG, "Val is " + val);

                    try {
                        reader = new RandomAccessFile(basePath + "thermal_zone" + i + "/trip_point_0_temp", "r");
                    }
                    catch (FileNotFoundException e) {
                        e.printStackTrace();
                    }

                    //if there's a problem instantiating RandomAccessFile, return -1
                    if (reader == null) {
                        Log.e(LOG_TAG, "LogRunnable: trip pt reader is NULL for zone " + i);
                    }
                    else {

                        try {
                            //read a line from the file
                            trip_pt = reader.readLine();
                            reader.close();
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }

                    Log.i(LOG_TAG, "Trip pt is " + trip_pt);

                    //see if the temp passed the trip point
                    if (!val.equals("") && !trip_pt.equals("") && Integer.parseInt(val) > Integer.parseInt(trip_pt)) {
                        val = val + "****";
                    }

                    //create xml property for this thermal zone
                    thermalData.setProperty(name, val);
                    thermalData.setProperty(name + "-TRIP", trip_pt);
                }

                thermalData.setProperty("total_time_elapsed", String.valueOf(timeElapsed));


                Properties tmp = new Properties() {
                    @Override
                    public Set<Object> keySet() {
                        return Collections.unmodifiableSet(new TreeSet<>(super.keySet()));
                    }
                };

                Properties temp = new Properties() {
                    private static final long serialVersionUID = 1L;

                    @Override
                    public Set<Object> keySet() {
                        return Collections.unmodifiableSet(new TreeSet<Object>(super.keySet()));
                    }

                    @Override
                    public Set<Map.Entry<Object, Object>> entrySet() {

                        Set<Map.Entry<Object, Object>> set1 = super.entrySet();
                        Set<Map.Entry<Object, Object>> set2 = new LinkedHashSet<>(set1.size());

                        Iterator<Entry<Object, Object>> iterator = set1.stream().sorted(new Comparator<Entry<Object, Object>>() {
                            @Override
                            public int compare(java.util.Map.Entry<Object, Object> o1, java.util.Map.Entry<Object, Object> o2) {
                                return o1.getKey().toString().compareTo(o2.getKey().toString());
                            }
                        }).iterator();

                        while (iterator.hasNext())
                            set2.add(iterator.next());

                        return set2;
                    }

                    @Override
                    public synchronized Enumeration<Object> keys() {
                        return Collections.enumeration(new TreeSet<Object>(super.keySet()));
                    }
                };


                temp.putAll(thermalData);

                SortedStoreProperties test = new SortedStoreProperties();
                test.putAll(thermalData);


                Log.i(LOG_TAG, "Path is " + mainActivity.getFilesDir().getPath());

                try {
                    thermalDataXmlFile = new FileOutputStream(mainActivity.getFilesDir().getPath() + "/thermal.xml");
                }
                catch (IOException e) {
                    e.printStackTrace();
                }

                if (thermalDataXmlFile == null) {
                    Log.e(LOG_TAG, "LogRunnable: thermalDataXmlFile came up null");
                }

                try {
                    temp.storeToXML(thermalDataXmlFile, null);
                }
                catch (IOException e) {
                    e.printStackTrace();
                }


                try {
                    Thread.sleep(700);
                }

                catch (InterruptedException e) {
                    e.printStackTrace();
                }

                timeElapsed += 0.7;
            }
        }

        /*
        airbrush-ipu0     bcl-virt1     cpu-0-3-usr   cpu-1-7-lowf    mdm-core-usr       pop-mem-step
        airbrush-ipu1     bcl-virt2     cpu-1-0-step  cpu-1-7-step    mdm-scl-lowf       pop-mem-test
        airbrush-ipu2     btn-therm     cpu-1-0-usr   cpu-1-7-usr     mdm-scl-usr        q6-hvx-step
        airbrush-ipu3     camera-lowf   cpu-1-1-step  cpuss-0-usr     mdm-vec-usr        q6-hvx-usr
        airbrush-tmu      camera-usr    cpu-1-1-usr   cpuss-1-usr     npu-step           quiet-therm
        airbrush-tpu0     charger       cpu-1-2-step  cwlan-usr       npu-usr            rcam-therm
        airbrush-tpu1     chg-therm     cpu-1-2-usr   ddr-usr         pa-therm           s2mpg01_tz
        aoss-1-usr        cmpss-usr     cpu-1-3-step  disp-therm      pm8150_tz          sdm-therm
        aoss0-usr         cpu-0-0-step  cpu-1-3-usr   gpuss-0-lowf    pm8150b-ibat-lvl0  sdm-therm-monitor
        apc-0-max-step    cpu-0-0-usr   cpu-1-4-step  gpuss-0-usr     pm8150b-ibat-lvl1  soc
        apc-1-max-step    cpu-0-1-step  cpu-1-4-usr   gpuss-1-usr     pm8150b-vbat-lvl0  usb
        backup-charge     cpu-0-1-usr   cpu-1-5-step  gpuss-max-step  pm8150b-vbat-lvl1  usbc-therm-adc
        battery           cpu-0-2-step  cpu-1-5-usr   lmh-dcvs-00     pm8150b-vbat-lvl2  usbc-therm-monitor
        bcl-cycle         cpu-0-2-usr   cpu-1-6-step  lmh-dcvs-01     pm8150b_tz         video-usr
        bcl-virt-monitor  cpu-0-3-step  cpu-1-6-usr   maxfg           pm8150l_tz         xo-therm*/



        /*
        private String subFolder = "/userdata";
        private String file = "test.ser";

        public void writeSettings() {
            File cacheDir = null;
            File appDirectory = null;

            if (android.os.Environment.getExternalStorageState().equals(android.os.Environment.MEDIA_MOUNTED)) {
                cacheDir = mainActivity.getApplicationContext().getExternalCacheDir();
                appDirectory = new File(cacheDir + subFolder);

            }
            else {
                cacheDir = mainActivity.getApplicationContext().getCacheDir();
                String BaseFolder = cacheDir.getAbsolutePath();
                appDirectory = new File(BaseFolder + subFolder);

            }

            if (appDirectory != null && !appDirectory.exists()) {
                appDirectory.mkdirs();
            }


            File fileName = new File(appDirectory, file);

            FileOutputStream fos = null;
            ObjectOutputStream out = null;
            try {
                fos = new FileOutputStream(fileName);
                out = new ObjectOutputStream(fos);
                out.writeObject(userSettings);
            }
            catch (IOException ex) {
                ex.printStackTrace();
            }
            catch (Exception e) {
                e.printStackTrace();
            }
            finally {
                try {
                    if (fos != null)
                        fos.flush();
                    fos.close();
                    if (out != null)
                        out.flush();
                    out.close();
                }
                catch (Exception e) {

                }
            }
        }


        public void readSetttings() {
            File cacheDir = null;
            File appDirectory = null;
            if (android.os.Environment.getExternalStorageState().
                    equals(android.os.Environment.MEDIA_MOUNTED)) {
                cacheDir = mainActivity.getApplicationContext().getExternalCacheDir();
                appDirectory = new File(cacheDir + subFolder);
            } else {
                cacheDir = mainActivity.getApplicationContext().getCacheDir();
                String BaseFolder = cacheDir.getAbsolutePath();
                appDirectory = new File(BaseFolder + subFolder);
            }

            if (appDirectory != null && !appDirectory.exists()) return; // File does not exist

            File fileName = new File(appDirectory, file);

            FileInputStream fis = null;
            ObjectInputStream in = null;
            try {
                fis = new FileInputStream(fileName);
                in = new ObjectInputStream(fis);
                Map<String, String> myHashMap = (Map<String, String> ) in.readObject();
                userSettings = myHashMap;
                System.out.println("count of hash map::"+ userSettings.size() + " " + userSettings);

            } catch (FileNotFoundException e) {
                e.printStackTrace();
            } catch (StreamCorruptedException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            } catch (ClassNotFoundException e) {
                e.printStackTrace();
            } catch (Exception e) {
                e.printStackTrace();
            }finally {

                try {
                    if(fis != null) {
                        fis.close();
                    }
                    if(in != null) {
                        in.close();
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }*/




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

        public void onStop() {
            mFinished = true;
        }
    }

}
