#!/bin/bash

sudo mv /usr/share/dbus-1/system-services/fi.w1.wpa_supplicant1.service .
sudo killall udhcpd
sudo wpa_cli -i wlp3s0 terminate

sudo wpa_supplicant -i wlp3s0 -c /etc/wpa_supplicant.conf -B

sleep 8

sudo wpa_cli -i wlp3s0 p2p_find

sleep 6

sudo wpa_cli -i wlp3s0 p2p_peers

sleep 3

sudo wpa_cli -i wlp3s0 p2p_connect 7a:4a:4b:cd:0c:62 pbc go_intent=0

sudo udhcpd /etc/udhcpd.conf
sudo dhclient p2p-wlp3s0-0

