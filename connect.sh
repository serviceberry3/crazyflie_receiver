#!/bin/bash

#kill current supplicant and avoid its auto creation

#move the service out of system-services to avoid auto-creation
sudo mv /usr/share/dbus-1/system-services/fi.w1.wpa_supplicant1.service .

#kill udhcpd server already running on pc
sudo killall udhcpd

#terminate current session of wpa_supplicant that's running
sudo wpa_cli -i wlan0 terminate

sleep 2

#make sure all wpa_supplicant processes are killed now
sudo killall wpa_supplicant

sleep 3

#start new supplicant in the background
sudo wpa_supplicant -i wlan0 -c /etc/wpa_supplicant.conf -B

sleep 8

#start discovery of p2p devices
sudo wpa_cli -i wlan0 p2p_find

sleep 6

#list the MAC addies of p2p peers found
DROID=$(sudo wpa_cli -i wlan0 p2p_peers)

sleep 3

#intitiate pushbutton connection event
sudo wpa_cli -i wlan0 p2p_connect $DROID pbc go_intent=0

#start up the IP assigner
sudo udhcpd /etc/udhcpd.conf

#assign IP address to the p2p network (required)
sudo dhclient p2p-wlan0-0

