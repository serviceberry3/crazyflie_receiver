#!/bin/bash
sudo wpa_cli -i wlan0 terminate -B
sudo wpa_cli -i p2p-wlan0-0 terminate -B
sudo mv ./fi.* /usr/share/dbus-1/system-services/
sudo service network-manager restart
