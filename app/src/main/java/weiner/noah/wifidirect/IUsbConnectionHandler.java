package weiner.noah.wifidirect;

public interface IUsbConnectionHandler {
    void onUsbStopped();

    void onErrorLooperRunningAlready();

    void onDeviceNotFound();
}
