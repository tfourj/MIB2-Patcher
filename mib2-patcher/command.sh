#!/bin/sh
# MIB2-Patcher hook for the M.I.B. launcher
#
# Copy this file to mod/command.sh on the M.I.B. SD card. It runs patcher.sh
# from the mib2-patcher folder in the root of the card.
# The M.I.B. launcher runs it at the end of its run, with $VOLUME set to the SD
# card mount point. GEM -> M.I.B. -> individual script also runs it, but without
# $VOLUME, so the SD1 slot is used then.
#
# PATCHER_RUN selects what runs, one patcher.sh call per line:
#   update                 copy MIB2-Patcher to the unit and add its GEM screen (default)
#   update-force           the same, also when the unit has the same version
#   install                install every patch of the train
#   uninstall              remove every patch and MIB2-Patcher again
#   status                 only write the status to the logs
#   <PATCH> <command>      run one patch's installer
# Everything except update needs MIB2-Patcher on the unit, so keep update first.
# Example:
#   PATCHER_RUN="
#   update
#   TEL2ACP install
#   JOKERBTN uninstall
#   "
PATCHER_RUN="update"

patcher_gem=0
if [ -z "$VOLUME" ]; then
	VOLUME=/net/mmx/fs/sda0
	patcher_gem=1
fi
patcher_sh="$VOLUME/mib2-patcher/patcher.sh"

if [ ! -f "$patcher_sh" ]; then
	echo "$patcher_sh not found, copy the mib2-patcher folder to the SD card"
else
	echo "$PATCHER_RUN" | while read patcher_line; do
		case "$patcher_line" in
			''|'#'*) continue ;;
		esac
		/bin/sh "$patcher_sh" $patcher_line < /dev/null
	done
fi

# GEM closes the output when the script ends, so leave time to read it
if [ $patcher_gem = 1 ]; then
	sleep 10
fi
