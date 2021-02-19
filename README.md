UPDATE: the app is intended to be paired with the [crazyflie_usb](https://github.com/serviceberry3/crazyflie_usb) app running on __another Android device__. But you can also use it to test a basic Wifi Direct connection __between two Android devices__, or __between a Linux PC (tested on Ubuntu) and an Android device__.

# Android-Android #  
To use between two Androids, open this app on both devices, hit "Discover Peers" on both, and then simultaneously click on the respective target device (the list entry) on both screens to initiate the connection. Sometimes it takes up to ~30 seconds to connect. If it's taking longer than that, try restarting the process.

# Ubuntu-Android #  
For Linux PC: open the app on the Android device and hit "DISCOVER PEERS," then run the connect.sh script on the PC to open up a p2p server and assign it an IP. Right now I'm running it so that the Android device is forced to become the host (go_intent = 0), but you can easily change that by running p2p_connect with go_intent = 15. After you run connect.sh, you should be able to open up a client socket and connect to the host using C, Java, etc. (my example is in TestServer.java).  

Use restore.sh to restart network-manager service on your computer and move wpa_supplicant service file back to its default location.   

UPDATE(11/02/20): when you're using the PC-Android version, make sure your /etc/wpa_supplicant.conf file contains the following:  

ctrl_interface=/var/run/wpa_supplicant  
update_config=1  
device_name=My P2P Device  

(the device name is optional). You also might need to change all instances of wlp3s0 in the shell script to wlan0 depending on what your interface is named (run ifconfig to see).  


# NOTES #  
* I've been having some problems with dhclient hanging when trying to assign an IP to the p2p-wlan0-0 interface (last line of the shell script). I'm working on figuring out why. 
* Use the Android app onboard a Crazyflie quadcopter to connect with my version of the Crazyflie Android client in [crazyflie_usb](https://github.com/serviceberry3/crazyflie_usb).  



