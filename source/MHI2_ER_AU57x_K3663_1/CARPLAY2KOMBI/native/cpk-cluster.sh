#!/bin/sh
# CARPLAY2KOMBI: shows CarPlay in the cluster's map window, or the map again.
# Needs libcpkhook.so loaded in dio_manager (LD_PRELOAD in smartphone_integrator.json).
#
# Usage: cpk-cluster.sh on [delay [rate [repeats]]] | off
#   on   the cluster stops showing the map (context 77) and the hook streams CarPlay to it,
#        as one constant-rate stream.
#        delay: ms each frame's timestamp runs ahead of the stream clock (default 50),
#        or nopts for frames without a timestamp.
#        rate: stream rate in kbit/s (default 4000). Keep it below what the channel takes:
#        /ramdisk/cpk/stream.log shows the rate actually sent and how long writes take.
#        repeats: how often the last picture is sent again after each CarPlay change
#        (default 0)
#   off  the hook stops streaming and the cluster shows the map again (context 73)
# Nothing is saved: after a reboot the cluster shows the map.
DMDT=/mnt/app/eso/bin/apps/dmdt
TRIGGER=/ramdisk/cpk/stream
DELAY=/ramdisk/cpk/delay
RATE=/ramdisk/cpk/rate
REPEAT=/ramdisk/cpk/repeat

. /mnt/app/root/.profile 2>/dev/null
export LD_LIBRARY_PATH=$LD_LIBRARY_PATH:/mnt/app/eso/lib:/eso/lib:/mnt/app/root/lib-target

# dmdt reads ./config/framework.json
mkdir -p /ramdisk/cpk/dmdt
cd /ramdisk/cpk/dmdt || exit 1
[ -e config ] || ln -s /etc/eso/production config

case "$1" in
	on)
		# Context 77 holds only CarPlay (displayable 59): displaymanager can't encode it, so the
		# map encoder stops and the hook's stream is the only one on the cluster's MOST channel
		$DMDT dc 77 59 > /dev/null 2>&1
		$DMDT sc 4 77 > /dev/null 2>&1
		# The hook reads the settings when streaming starts; no file means the default
		if [ -n "$2" ]; then
			echo "$2" > "$DELAY"
		else
			rm -f "$DELAY"
		fi
		if [ -n "$3" ]; then
			echo "$3" > "$RATE"
		else
			rm -f "$RATE"
		fi
		if [ -n "$4" ]; then
			echo "$4" > "$REPEAT"
		else
			rm -f "$REPEAT"
		fi
		mkdir -p "$TRIGGER"
		echo "CarPlay in the cluster: on, delay ${2:-50}, rate ${3:-4000} kbit/s, repeats ${4:-0}"
		;;
	off)
		rm -r "$TRIGGER" 2> /dev/null
		# The hook's writer checks the trigger every 50 ms
		sleep 1
		$DMDT sc 4 73 > /dev/null 2>&1
		echo "CarPlay in the cluster: off, map back"
		;;
	*)
		echo "usage: $0 on | off"
		exit 1
		;;
esac
