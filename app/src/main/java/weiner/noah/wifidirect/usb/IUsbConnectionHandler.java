package weiner.noah.wifidirect.usb;

public interface IUsbConnectionHandler {
    void onUsbStopped();

    void onErrorLooperRunningAlready();

    void onDeviceNotFound();
}
