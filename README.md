So far I've made it work for data transfer over Wifi Direct from one Android to another. Working on writing some shell scripts to allow handshaking between the Android and a PC running Ubuntu.

UPDATE: the app works both between two Android devices and between a Linux PC (tested on Ubuntu) and and Android. To use between two Androids, open the app on both devices, hit "Discover Peers" on both, and then simultaneously click on the respective target device on both screens to initiate the connection. For PC, open the app on the Android device and hit "DISCOVER PEERS," then run the connect.sh script to open up a p2p server and assign it an IP. Right now I'm running it so that the Android device is forced to become the host (go_intent = 0), but you can easily change that by running p2p_connect with go_intent = 15. After you run connect.sh, you should be able to open up a client socket and connect to the host using C, Java, etc. (my example is in TestServer.java).  

Use restore.sh to restart network-manager service on your computer and move wpa_supplicant service file back to its default location.   

UPDATE(11/02/20): when you're using the PC-Android version, make sure your /etc/wpa_supplicant.conf file contains the following:  

ctrl_interface=/var/run/wpa_supplicant  
update_config=1  
device_name=My P2P Device  

(the device name is optional). You also might need to change all instances of wlp3s0 in the shell script to wlan0 depending on what your interface is named (run ifconfig to see).  

I've been having some problems with dhclient hanging when trying to assign an IP to the p2p-wlan0-0 interface (last line of the shell script). I'm working on figuring out why. 



