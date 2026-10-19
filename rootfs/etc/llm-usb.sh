#!/bin/sh
# USB RNDIS network: phone 172.16.42.1, the PC gets 172.16.42.2 by DHCP.
G=/sys/class/android_usb/android0
[ -d $G ] || { echo "no android_usb gadget"; exit 0; }
echo 0     > $G/enable
echo 18d1  > $G/idVendor
echo 4ee4  > $G/idProduct
echo nexus5x-local-llm > $G/iProduct
echo rndis > $G/functions
echo 1     > $G/enable
for i in 1 2 3 4 5; do ifconfig rndis0 >/dev/null 2>&1 && break; sleep 1; done
ifconfig rndis0 172.16.42.1 netmask 255.255.255.0 up
udhcpd /etc/udhcpd.conf
