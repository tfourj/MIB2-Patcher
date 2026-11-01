#!/bin/sh
# CARPLAY2KOMBI: shows CarPlay in the cluster's map window, or the map again.
# Needs libcpkhook.so loaded in dio_manager (LD_PRELOAD in smartphone_integrator.json).
#
# Usage: cpk-cluster.sh on [delay] | off
#   on   the cluster stops showing the map (context 77) and the hook streams CarPlay to it.
#        By default frames have no timestamp and show as soon as they are decoded;
#        delay: ms the cluster holds each frame instead (e.g. 60)
#   off  the hook stops streaming and the cluster shows the map again (context 73)
# Nothing is saved: after a reboot the cluster shows the map.
DMDT=/mnt/app/eso/bin/apps/dmdt
TRIGGER=/ramdisk/cpk/stream
DELAY=/ramdisk/cpk/delay

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
		# The hook reads the delay when streaming starts: ms, or no file for no timestamps
		case "$2" in
			'' | nopts) rm -f "$DELAY" ;;
			*) echo "$2" > "$DELAY" ;;
		esac
		mkdir -p "$TRIGGER"
		echo "CarPlay in the cluster: on ${2:-without timestamps}"
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
