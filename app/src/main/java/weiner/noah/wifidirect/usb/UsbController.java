package weiner.noah.wifidirect.usb;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.hardware.usb.UsbRequest;
import android.util.Log;
import android.widget.TextView;
import android.widget.Toast;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

public class UsbController {
    public final Context mApplicationContext;
    public final UsbManager mUsbManager;
    private UsbDevice device;
    private UsbDeviceConnection connection;
    public final IUsbConnectionHandler mConnectionHandler;
    private UsbEndpoint in, out;
    private final int VID;
    private final int PID;
    protected static final String ACTION_USB_PERMISSION = "weiner.noah.USB_PERMISSION";
    private volatile int direction = 0, transferring = 0;
    public final Activity activity;
    public int error;

    //keep track of USB data transfer latency
    private long sendTimeValue, receiveTimeValue;
    private long latency;

    //textviews for timestamps
    public TextView sendTime, receiveTime, latencyText;

    public byte b;

    private final String TAG = "UsbController";

    //constant variable for the UsbRunnable (data transfer loop)
    private UsbRunnable mLoop;
    private ReadRunnable mReceiver;

    private UsbRequest readingRequest = new UsbRequest();
    private UsbRequest sendingRequest = new UsbRequest();
    private UsbRequest pktSendRequest = new UsbRequest();


    //make bulktransfer block until receive data
    private static int TRANSFER_TIMEOUT = 0;

    //separate thread for usb data transfer
    private Thread mUsbThread, mReceiveThread;

    //instantiate a new IPermissionReceiver interface, implementing the perm denied fxn
    IPermissionListener mPermissionListener = new IPermissionListener() {
        @Override
        public void onPermissionDenied(UsbDevice d) {
            Log.e("USBERROR", "Permission denied for device " + d.getDeviceId());
        }
    };

    //instantiate a new PermissionReceiver for registering in init()
    private BroadcastReceiver mPermissionReceiver = new PermissionReceiver(mPermissionListener);


    public UsbController (Activity parentActivity, IUsbConnectionHandler connectionHandler, int vid, int pid, Activity act) {
        mApplicationContext = parentActivity.getApplicationContext();
        mConnectionHandler = connectionHandler;
        mUsbManager = (UsbManager) mApplicationContext.getSystemService(Context.USB_SERVICE);
        VID = vid;
        PID = pid;
        activity = act;
        error = 0;
        init();
    }

    public UsbDeviceConnection getConnection() {
        return connection;
    }

    private class PermissionReceiver extends BroadcastReceiver {
        private final IPermissionListener permissionListener;

        //constructor
        public PermissionReceiver(IPermissionListener listener) {
            permissionListener = listener;
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            //unregister this broadcast receiver
            mApplicationContext.unregisterReceiver(this);

            String action = intent.getAction();

            //check to see if this action was regarding USB permission
            if (ACTION_USB_PERMISSION.equals(action)) {
                //check granted
                boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);

                //get the specific device through intent extra
                UsbDevice device = (UsbDevice)intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);

                //if permission was not granted, call onPermDenied method of the passed IPermissionListener interface to display Log error message
                if (!granted) {
                    permissionListener.onPermissionDenied(device);
                }
                else {
                    //otherwise we can set up communication with the device
                    Log.d("USBTAG", "Permission granted for the device");

                    //first check if device is null
                    if (device != null) {
                        //make sure this is the Arduino
                        if (device.getVendorId() == VID && device.getProductId() == PID) {
                            //locked onto the Arduino, now start the USB protocol setup
                            openConnectionOnReceivedPermission();
                        }
                        else {
                            //Arduino not present
                            Log.e("USBERROR", "USB permission granted, but this device is not Arduino");
                        }
                    }
                }
            }
        }
    }

    private void init() {
        Log.i(TAG, "UsbController init() calling listDevices()");
        listDevices(new IPermissionListener() {
            @Override
            public void onPermissionDenied(UsbDevice d) {
                //get a USB manager instance
                UsbManager usbManager = (UsbManager) mApplicationContext.getSystemService(Context.USB_SERVICE);

                //sends permission intent in the broadcast ("the getBroadcast method retrieves a PendingIntent that WILL perform a broadcast(it's waiting)")
                //basically broadcasts the given Intent to all interested BroadcastReceivers
                PendingIntent pi = PendingIntent.getBroadcast(mApplicationContext, 0, new Intent(ACTION_USB_PERMISSION), 0); //asynchronous, returns immediately

                //register a broadcast receiver to listen
                //Register a BroadcastReceiver to be run in the main activity thread. The receiver will be called with any
                // broadcast Intent that matches filter, in the main application thread.
                mApplicationContext.registerReceiver(mPermissionReceiver, new IntentFilter(ACTION_USB_PERMISSION));

                //request permission to access last USB device found in map, store result (success or failure) in permissionIntent, results in system dialog displayed
                usbManager.requestPermission(d, pi); //extras that will be added to pi: EXTRA_DEVICE containing device passed, and EXTRA_PERMISSION_GRANTED containing bool of result
            }
        });
    }

    private void openConnectionOnReceivedPermission() {
        if (error == 0) {
            //open communication with the device
            connection = mUsbManager.openDevice(device);

            Log.i("USBTAG", "Getting interface...");
            UsbInterface usb2serial = device.getInterface(0);
            Log.i("USBTAG", "Interface gotten");


            Log.i("USBTAG", "Claiming interface...");
            //claim interface 1 (Usb-serial) of the Duino, disconnecting kernel driver if necessary
            if (!connection.claimInterface(usb2serial, true)) {
                //if we can't claim exclusive access to this UART line, then FAIL
                Log.e("CONNECTION", "Failed to claim exclusive access to the USB interface.");
                return;
            }

            Log.i("USBTAG", "Interface claimed");

            //USB CONTROL INITIALIZATION
            Log.i("USBTAG", "Control transfer start...");
            //set control line state, as defined in https://cscott.net/usb_dev/data/devclass/usbcdc11.pdf, p. 51
            connection.controlTransfer(0x21, 34, 0, 0, null, 0, 10);

            //set line encoding: 9600 bits/sec, 8data bits, no parity bit, 1 stop bit for UART
            connection.controlTransfer(0x21, 32, 0, 0, new byte[] { (byte) 0x80, 0x25, 0x00, 0x00, 0x00, 0x00, 0x08 }, 7, 10);

            Log.i("USBTAG", "Control transfer end...");

            in = null;
            out = null;

            //iterate through all USB endpoints on this interface, looking for bulk transfer endpoints
            for (int i = 0; i < usb2serial.getEndpointCount(); i++) {
                UsbEndpoint thisEndpoint = usb2serial.getEndpoint(i);
                if (thisEndpoint.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    //found bulk endpoint, now distinguish which are read and write points
                    if (thisEndpoint.getDirection() == UsbConstants.USB_DIR_IN) {
                        Log.d("ENDPTS", "Found in point");
                        Log.d("ENDPTS", String.format("In address: %d", thisEndpoint.getAddress()));
                        in = thisEndpoint;
                    }
                    else {
                        Log.d("ENDPTS", "Found out point");
                        Log.d("ENDPTS", String.format("Out address: %d", thisEndpoint.getAddress()));
                        out = thisEndpoint;
                    }
                }
            }

            //initialize an asynchronous requests for USB data from the connected device
            readingRequest.initialize(connection, in);
            sendingRequest.initialize(connection, out);
            pktSendRequest.initialize(connection, out);


            //start receiving data from drone asynchronously
            mReceiveThread = new Thread(new ReadRunnable());
            mReceiveThread.start();
            Log.i(TAG, "USB connection setup finished successfully.");
        }

        else {
            Log.d("ERROR", "Error found");
        }
    }

    private void listDevices(IPermissionListener permissionListener) {
        Log.d("DBUG", "Welcome to listDevices()");
        HashMap<String, UsbDevice> devices = mUsbManager.getDeviceList();

        //print out all connected USB devices found
        device = null;
        int prodId, vendId;

        for (Map.Entry<String, UsbDevice> entry : devices.entrySet()) {
            device = (UsbDevice)entry.getValue();
            prodId = device.getProductId();
            vendId = device.getVendorId();

            //print out the device found
            Toast.makeText(mApplicationContext, "Found device:" + device.getDeviceName() + " with ID "+
                    String.format("%04X:%04X", device.getVendorId(), device.getProductId()), Toast.LENGTH_SHORT).show();

            //check to see if this device is one we're looking for
            if (vendId == VID && prodId == PID) {
                Log.d("DEVICE", "listDevices found the expected device");
                Toast.makeText(mApplicationContext, "Device found: " + device.getDeviceName(), Toast.LENGTH_SHORT).show();

                //if we don't have permission to access the device, try getting permission by calling onPermDenied method of the passed IPermissionListener interface
                if (!mUsbManager.hasPermission(device)) {
                    Log.d("PERM", "Asking user for USB permission...");
                    permissionListener.onPermissionDenied(device);
                    return;
                }
                else {
                    //start the setup and return
                    openConnectionOnReceivedPermission();
                    return;
                }
            }
        }

        //if reached here with no return, we couldn't lock onto a found device or couldn't find, ERROR
        Log.e("USBERROR", "No more devices to list");

        //set error flag
        error = 1;

        //It's important to note that Java constructor's CANNOT return null (can't set the instance of the object to null when they return), so the onDeviceNotFound() method
        //isn't sufficient for setting usbController back to null. Hence the error flag that we set above
        mConnectionHandler.onDeviceNotFound();
    }

    //small interface for the USB permission listener
    private static interface IPermissionListener {
        void onPermissionDenied(UsbDevice d);
    }

    //This is the meat. We set up the USB communication interface similar to how we did in the PC to Arduino interface

    //an empty array is less overhead space than an actual instantiation of a new Object()
    private static final Object[] sSendLock = new Object[]{};
    private static final Object[] killLock = new Object[]{};

    //lock to synchronize packet sending
    private static final Object[] pktSendLock = new Object[]{};

    private volatile boolean mStop = false, mKillReceiver = false;

    //the byte for sending
    private byte mData = 0x00;

    //public data received from Arduino for parsing
    public byte[] dataIn = new byte[1];

    private class UsbRunnable implements Runnable {
        private final UsbDevice device;
        private final int sens;

        //constructor
        UsbRunnable(UsbDevice dev, int way) {device = dev; sens = way;}

        @Override
        //implement main USB functionality
        public void run() {
            if (sens == 1) {
                //data transferring loop
                while (true) {
                    //synchronized means only one thread at a time can do this stuff. Basically no other thread can do stuff to the sSendLock object because this thread has the lock on it
                    synchronized (sSendLock) { //create an output queue
                        try {
                            //have this thread wait until another thread invokes notify (sSendLock)
                            sSendLock.wait();
                        }

                        catch (InterruptedException e) {
                            //on interrupt exception, if stop is set, then call onStopped()
                            if (mStop) {
                                Log.e("ERROR", "InterruptedException in synchron");
                                mConnectionHandler.onUsbStopped();
                                return;
                            }
                            e.printStackTrace();
                        }
                    }

                    Log.d("THREAD", String.format("Value of direction is: %d", direction));

                    if (mStop) {
                        Log.e("ERROR", "Stopped after the sending thread was notify()ed, returning...");
                        mConnectionHandler.onUsbStopped();
                        transferring = 0;
                        return;
                    }

                    if (direction == 1) {
                        //transfer the byte of length 1, sending or receiving as specified
                        connection.bulkTransfer(out, new byte[]{mData}, 1, 0);
                    }

                    else { //never reached
                        /*
                        //transfer the byte of length 1, sending or receiving as specified
                        Log.e("TRANSFER", "Beginning receive transfer...");

                        int bytesTransferred = connection.bulkTransfer(in, dataIn, 22, 1000);

                        Log.e("TRANSFER", String.format("# of bytes received: %d", bytesTransferred));
                        if (bytesTransferred<0) {
                            mStop = true;
                        }
                        */
                    }

                    Log.d("THREAD", "Setting |transferring| back to 0");
                    transferring = 0;
                }
            }
            else {
                Log.d("QUEUE", "QUEUEING UP");
                //queue up

                ByteBuffer buffer = ByteBuffer.allocate(1);

                UsbRequest request = new UsbRequest();
                request.initialize(connection, in);

                //wait for data to become available for receiving from the Arduino
                while (true) {
                    if (request.queue(buffer, 1)) {
                        Log.d("QUEUE", "WAITING FOR DATA...");
                        connection.requestWait();
                        // wait for this request to be completed
                        // at this point buffer contains the data received
                    }
                    dataIn[0] = buffer.get(0);
                }
            }
        }
    }

    //function to send a byte of data (wakes up data transfer thread)
    public void send (byte data) {
        if (mStop) {
            return;
        }

        //set data out byte to the passed byte
        mData = data;
        direction = 1;

        //display sending timestamp
        sendTimeValue = System.currentTimeMillis();

        synchronized (sSendLock) {
            //wake up sSendLock for bulk transfer
            sSendLock.notify();
        }
    }

    /*
    //Runnable that safely lands the drone, starting from TARG_HEIGHT
    public class BulkTransferRunnable implements Runnable {
        private final Object mPauseLock;
        private boolean mPaused;
        private boolean mFinished;
        private byte[] data;
        private byte[] receiveData;

        public BulkTransferRunnable(byte[] data, byte[] receiveData) {
            mPauseLock = new Object();
            mPaused = false;
            mFinished = false;
            this.data = data;
            this.receiveData = receiveData;
        }


        public void run() {
            try {
                long start, end;
                Log.i(TAG, "sendBulkTransfer...");

                int returnCode = -1;

                int ctr = 0;

                //make sure we have a valid connection
                if (connection != null) {
                    //start = System.currentTimeMillis();

                    //send the data, which will always be a packet here
                    connection.bulkTransfer(out, data, data.length, TRANSFER_TIMEOUT);

                    //receive the Ack
                    returnCode = connection.bulkTransfer(in, receiveData, receiveData.length, TRANSFER_TIMEOUT);

                    //there are two things that might be on the drone's USB TX queue at this point, so we might get one of two things in receiveData[0]
                    //0xCC: this is drone's request for an ack from the phone. In this case, we should immediately send back 0x12 to the drone


                    //0x09: this is an ack from the drone indicating that it received the packet and next one can be sent. We should wait here until we get an 0x09

                    //if we got 0xCC, send 0x12 back immediately


                    if (receiveData[0] == (byte)0xcc) {
                        Log.i(TAG, "sendBulkTransfer(): sending phone ack to drone");
                        connection.bulkTransfer(out, new byte[]{0x12}, 1, TRANSFER_TIMEOUT);

                        //receive the Ack
                        //returnCode = connection.bulkTransfer(in, receiveData, receiveData.length, TRANSFER_TIMEOUT);

                        //ctr++;
                        //Log.i(TAG, "Received 0xCC #" + ctr);


                        //if the byte received is 0xcc (this should always be true initially
                        while (receiveData[0] != (byte)0x09) {
                            //receive a new byte from the drone
                            returnCode = connection.bulkTransfer(in, receiveData, receiveData.length, TRANSFER_TIMEOUT);

                            //FIXME: if we flush some 0xCC that was sent by usbCheckPhoneTask, drone won't receive the matching 0x12, which will cause fail?

                            //if we still got 0xcc, which is possible (it's undefined the order in which things will be sent, since two separate tasks send USB data in the drone firmware), send another 0x12 to confirm
                            if (receiveData[0] == (byte)0xcc) {
                                //send 0x12 to the drone to confirm the phone is alive
                                connection.bulkTransfer(out, new byte[]{0x12}, 1, TRANSFER_TIMEOUT);

                                //FIXME: drone still misses some 0x12's

                                //ctr++;
                                //Log.i(TAG, "Received 0xCC #" + ctr);
                            }

                            Thread.sleep(4);
                        }
                    }



                    Log.i(TAG, "sendBulkTransfer waiting for notify...");
                    //need to wait here until ReadRunnable gets 0x09
                    synchronized (pktSendLock) {
                        try {
                            pktSendLock.wait();
                        }
                        catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }
                    Log.i(TAG, "sendBulkTransfer got notify...");

                    //at this point we've surely received 0x09 into receiveData, so we can return


                    //end = System.currentTimeMillis();
                    //Log.i(TAG, String.format("Got back USB transfer from drone: data is %x. Returning...", receiveData[0] , end - start));
                }
                else {
                    Log.e(TAG, "sendBulkTransfer(): cnnxn null!");
                }
            }

            //If a kill was requested, stop and resume joystick thread
            catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }*/



    //send packet to drone via USB, and receive Ack back
    public int sendBulkTransfer(byte[] data, byte[] receiveData) {
        long start, end;
        Log.i(TAG, "sendBulkTransfer...");

        int returnCode = -1;

        int ctr = 0;

        UsbEndpoint completedRequest = null;
        UsbRequest completedRqst = null;

        ByteBuffer incoming = ByteBuffer.allocate(1);
        ByteBuffer phoneAck = ByteBuffer.allocate(1);
        phoneAck.put((byte)0x12);

        //make sure we have a valid connection
        if (connection != null) {
            //start = System.currentTimeMillis();

            //send the data, which will always be a packet here
            //connection.bulkTransfer(out, data, data.length, TRANSFER_TIMEOUT);


            //send the packet asynchronously
            pktSendRequest.queue(ByteBuffer.wrap(data));

            /*
            while (completedRqst != pktSendRequest) {
                completedRqst = connection.requestWait();
                Log.i(TAG, "requestWait() for pkt send");
                // wait for confirmation (request was sent)


                // the direction is dictated by this initialisation to the incoming endpoint.
                if (readingRequest.queue(incoming, 1)) {
                    connection.requestWait();
                    // wait for this request to be completed
                    // at this point buffer contains the data received

                    //copy received byte into receiveData[0]
                    receiveData[0] = incoming.get(0);

                    Log.i(TAG, "Sendbulktransfer got " + receiveData[0] + " from drone");
                }
            }*/


            //receive the Ack
            //returnCode = connection.bulkTransfer(in, receiveData, receiveData.length, TRANSFER_TIMEOUT);

            //there are two things that might be on the drone's USB TX queue at this point, so we might get one of two things in receiveData[0]
            //0xCC: this is drone's request for an ack from the phone. In this case, we should immediately send back 0x12 to the drone


            //0x09: this is an ack from the drone indicating that it received the packet and next one can be sent. We should wait here until we get an 0x09

            //if we got 0xCC, send 0x12 back immediately


            /*
            if (receiveData[0] == (byte)0xcc) {
                Log.i(TAG, "sendBulkTransfer(): sending phone ack to drone");
                //connection.bulkTransfer(out, new byte[]{0x12}, 1, TRANSFER_TIMEOUT);

                pktSendRequest.queue(phoneAck);
                connection.requestWait();

                //receive the Ack
                //returnCode = connection.bulkTransfer(in, receiveData, receiveData.length, TRANSFER_TIMEOUT);

                //ctr++;
                //Log.i(TAG, "Received 0xCC #" + ctr);


                //if the byte received is 0xcc (this should always be true initially
                while (receiveData[0] != (byte)0x09) {
                    //receive a new byte from the drone
                    //returnCode = connection.bulkTransfer(in, receiveData, receiveData.length, TRANSFER_TIMEOUT);

                    if (readingRequest.queue(incoming, 1)) {
                        connection.requestWait();
                        // wait for this request to be completed
                        // at this point buffer contains the data received

                        //copy received byte into receiveData[0]
                        receiveData[0] = incoming.get(0);
                        Log.i(TAG, "Sendbulktransfer got " + receiveData[0] + " from drone [INNER]");
                    }

                    //FIXME: if we flush some 0xCC that was sent by usbCheckPhoneTask, drone won't receive the matching 0x12, which will cause fail?

                    //if we still got 0xcc, which is possible (it's undefined the order in which things will be sent, since two separate tasks send USB data in the drone firmware), send another 0x12 to confirm
                    if (receiveData[0] == (byte)0xcc) {
                        //send 0x12 to the drone to confirm the phone is alive
                        //connection.bulkTransfer(out, new byte[]{0x12}, 1, TRANSFER_TIMEOUT);

                        Log.i(TAG, "sendBulkTransfer(): sending phone ack to drone [INNER]");
                        pktSendRequest.queue(phoneAck);
                        connection.requestWait();

                        //FIXME: drone still misses some 0x12's

                        //ctr++;
                        //Log.i(TAG, "Received 0xCC #" + ctr);
                    }
                }
            }*/


            Log.i(TAG, "sendBulkTransfer waiting for notify...");
            //need to wait here until ReadRunnable gets 0x09
            synchronized (pktSendLock) {
                try {
                    pktSendLock.wait();
                }
                catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            Log.i(TAG, "sendBulkTransfer got notify...");

            //at this point we've surely received 0x09 ack from drone, so we can return

            //end = System.currentTimeMillis();
            //Log.i(TAG, String.format("Got back USB transfer from drone: data is %x. Returning...", receiveData[0] /*, end - start*/));
        }
        else {
            Log.e(TAG, "sendBulkTransfer(): cnnxn null!");
        }
        return returnCode;
    }

    //receive data
    public void receive () {
        if (mStop) {
            return;
        }
        direction = 0;
        synchronized (sSendLock) {
            //wake up sReceiveLock for receiving
            sSendLock.notify();
        }
        transferring = 1;
        Log.d("TRANSFERRING VAL", String.format("%d", transferring));

        //wait until all receiving finished
        while (transferring == 1) {
            ;
        }

        //Log debugging statements
        for (byte thisByte : dataIn) {
            Log.d("BYTEREAD", String.format("%x", thisByte));
        }
        Log.d("TRANSFERRING VAL", String.format("%d", transferring));
    }

    //stop usb data transfer
    public void stop() {
        Log.i(TAG, "Stop() called on UsbController instance");

        //IGNORE FOR NOW, ONLY NEEDED IF USING RECEIVERUNNABLE
        /*
        synchronized (killLock) {
            mKillReceiver = true;

            //ping a kill signal off of the STM32 over to the requestWait() blocking function
            send((byte) 0xFF);

            try {
                //wait to make sure sending of kill signal is done and receiver has shut down
                killLock.wait();
            }
            catch (InterruptedException e) {
                e.printStackTrace();
            }
        }*/

        //IGNORE FOR NOW, ONLY NEEDED IF USING USBRUNNABLE
        /*
        synchronized (sSendLock) {
            //wake up sending thread to make it return
            mStop = true;
            sSendLock.notify();
        }*/

        //readingRequest.close();

        //terminate the data transfer thread by joining it to main UI thread, also terminate receiving thread
        try { //cleaning up threads
            if (mUsbThread != null) {
                Log.d("DBUG", "Joining UsbThread...");
                mUsbThread.join();
            }
            if (mReceiveThread != null) {
                Log.d("DBUG", "Joining ReceiveThread...");
                mReceiveThread.join();
            }
        }
        catch (InterruptedException e) {
            e.printStackTrace();
        }

        //reset stop flag, current usbrunnable and readrunnable instance, and both data transfer threads
        mStop = false;
        mLoop = null;
        mReceiver = null;
        mUsbThread = null;
        mReceiveThread = null;

        //Close the USB connection
        connection.close();

        //try to unregister the permission receiver
        try {
            mApplicationContext.unregisterReceiver(mPermissionReceiver);
        }
        catch (IllegalArgumentException e) {
            e.printStackTrace();
        }
    }

    //start up a new thread for USB comms with the given device
    private void startDataTransferThreads(UsbDevice device) {
        if (mLoop != null) {
            //USB data transfer thread already running
            mConnectionHandler.onErrorLooperRunningAlready();
            return;
        }
        //make new UsbRunnable and thread for comms with the device
        mLoop = new UsbRunnable(device, 1);
        mReceiver = new ReadRunnable();

        //assign the new runnable to new thread
        mUsbThread = new Thread(mLoop);
        mReceiveThread = new Thread(mReceiver);

        //start new threads in background
        mUsbThread.start();
        mReceiveThread.start();
    }



    private class ReadRunnable implements Runnable {
        //private byte[] incomingData = new byte[33];
        @Override
        public void run() {
            //queue up
            final ByteBuffer buffer = ByteBuffer.allocate(1);

            //phone ack buffer
            final ByteBuffer outBuffer = ByteBuffer.allocate(1);
            outBuffer.put((byte)0x12);

            UsbEndpoint completedRequest = null;
            UsbRequest completedRqst = null;

            //wait for data to become available to receive
            while (true) {
                completedRequest = null;

                //make sure queuing operation succeeds
                if (readingRequest.queue(buffer, 1)) {  //FIXME
                    Log.d(TAG, "WAITING FOR INCOMING USB DATA...");

                    //wait for read request to finish
                    while (completedRequest != in) {
                        Log.i(TAG, "requestWait() for in");
                        completedRequest = connection.requestWait().getEndpoint();
                    }
                    Log.i(TAG, "completedRequest is in");


                    /*
                    //stamp time of data reception
                    receiveTimeValue = System.currentTimeMillis();
                    latency = receiveTimeValue - sendTimeValue;
                    */

                    //wait for the read request to be completed
                    //at this point buffer contains the data received
                    byte firstChar = buffer.get(0);
                    Log.d(TAG, "Received byte " + firstChar + " from drone");

                    //if this is confirmation that drone received pkt, notify the pktSendLock
                    if (firstChar == (byte)0x09) {
                        synchronized (pktSendLock) {
                            pktSendLock.notify();
                        }
                    }

                    //if this is request for phone ack, queue 0x12 to be sent
                    else if (firstChar == (byte)0xcc) {
                        if (!sendingRequest.queue(outBuffer, 1)) {
                            Log.e(TAG, "FAILED TO QUEUE PHONE ACK");
                            return;
                        }
                        else {
                            Log.i(TAG, "ReadRunnable Queued phone ack successfully");

                            /*
                            //FIXME: Do we need to wait for the send request queueing operation to succeed? Probably not, since we already check all acks...
                            while (completedRqst != sendingRequest) {
                                Log.i(TAG, "requestWait() for out");
                                completedRqst = connection.requestWait();
                            }*/
                        }
                        //don't block after sending 0x12 ack
                    }

                    //if signal to kill has been sent by stop function, then end the thread so that we can reset
                    if (mKillReceiver) {
                        Log.d(TAG, "ReadRunnable flagged to stop, returning...");
                        mConnectionHandler.onUsbStopped();

                        synchronized (killLock) {
                            killLock.notify();
                        }

                        return;
                    }
                }
            }
        }
    }
}


