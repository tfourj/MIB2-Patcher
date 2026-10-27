#!/bin/sh
#
# MIB2-Patcher v1.0.0 - one GEM entry for every patch that fits the unit's firmware
# Patches live in patches/<train>/<PATCH>, each with its own installer and GEM screens
#
# Usage: patcher.sh [-force] <action> [<action>...]
#        patcher.sh [-force] <PATCH> <command> [args...]
#   train       show the detected train
#   status      show the train and the status of every patch
#   install     install every patch of the train
#   uninstall   uninstall every patch of the train and remove the MIB2_Patcher GEM screen
#   gem-on      add the MIB2_Patcher screen and the screens of every patch to the GEM
#   gem-off     remove them from the GEM
#   <PATCH> ... run that patch's installer, e.g. "patcher.sh JOKERBTN set info next"
#   -force      use the only train folder when the firmware fingerprint matches none,
#               and pass -force to the patch installers
#
# Runs on the MMI (QNX). Start it over SSH, from the M.I.B. launcher hook
# (mod/command.sh) or from the MIB2_Patcher GEM screen.

VERSION="1.0.0"

export PATH=:/proc/boot:/sbin:/bin:/usr/bin:/usr/sbin:/net/mmx/bin:/net/mmx/usr/bin:/net/mmx/usr/sbin:/net/mmx/sbin:/net/mmx/mnt/app/armle/bin:/net/mmx/mnt/app/armle/sbin:/net/mmx/mnt/app/armle/usr/bin:/net/mmx/mnt/app/armle/usr/sbin
export LD_LIBRARY_PATH=/net/mmx/mnt/app/root/lib-target:/net/mmx/mnt/eso/lib:/net/mmx/eso/lib:/net/mmx/mnt/app/usr/lib:/net/mmx/mnt/app/armle/lib:/net/mmx/mnt/app/armle/lib/dll:/net/mmx/mnt/app/armle/usr/lib

# Patcher folder on the SD card (where this script lives), mib2-patcher
ROOT=$(dirname "$0")
case "$ROOT" in
	/*) ;;
	*) ROOT="$(pwd)/$ROOT" ;;
esac
SD_ROOT="${ROOT%/*}"
PATCHES="$ROOT/patches"

LOG="$ROOT/patcher.log"

# GEM scripts see the SD card read-only, the M.I.B. launcher remounts it the same way
mount -uw "$SD_ROOT" 2> /dev/null

APP_MOUNT="/net/mmx/mnt/app"
ENGDEFS="$APP_MOUNT/eso/hmi/engdefs"
# The HMI runs with -jxe:/ifs/lsd.jxe (the jxe is inside the MMX boot image)
LSD_JXE="/net/mmx/ifs/lsd.jxe"

# The root GEM screen. The patch screens use the z_mib2patcher_ prefix, so they sort after it
ESD_SRC="$ROOT/esd/MIB2_PATCHER.esd"
ESD_LINK="$ENGDEFS/z_mib2patcher.esd"

FORCE=0
TRAIN=""

log() {
	echo "$*"
	{ echo "$*" >> "$LOG"; } 2> /dev/null
}

die() {
	log "ERROR: $*"
	exit 1
}

mount_rw() {
	mount -uw "$APP_MOUNT" 2> /dev/null || log "WARNING: could not remount $APP_MOUNT read-write"
}

mount_ro() {
	mount -ur "$APP_MOUNT" 2> /dev/null
}

sha1_of() {
	for tool in sha1sum /net/rcc/usr/bin/sha1sum /usr/bin/sha1sum "$SD_ROOT/apps/sbin/sha1sum"; do
		sum=$("$tool" "$1" 2> /dev/null) || continue
		set -- $sum
		echo "$1"
		return 0
	done
	return 1
}

# Picks the folder in patches/ whose lsd.jxe.sha1 matches the unit. The sha1 is
# exported, so the patch installers don't spend another minute on the 45 MB file
detect_train() {
	[ -n "$TRAIN" ] && return 0
	[ -f "$LSD_JXE" ] || die "$LSD_JXE not found"
	log "firmware:   checking, this can take a minute..."
	sum=$(sha1_of "$LSD_JXE")
	if [ -n "$sum" ]; then
		log "lsd.jxe sha1: $sum"
		for d in "$PATCHES"/*; do
			if [ "$(cat "$d/lsd.jxe.sha1" 2> /dev/null)" = "$sum" ]; then
				TRAIN="${d##*/}"
				PATCHER_JXE_SHA1="$sum"
				export PATCHER_JXE_SHA1
				log "train:      $TRAIN"
				return 0
			fi
		done
	fi
	[ $FORCE = 1 ] || die "no patches for this firmware in $PATCHES (use -force to try the only train folder)"
	count=0
	for d in "$PATCHES"/*; do
		[ -d "$d" ] && { count=$((count + 1)); TRAIN="${d##*/}"; }
	done
	[ $count = 1 ] || die "firmware not recognised and $count train folders found, can't pick one"
	log "WARNING: firmware not recognised, using $TRAIN because of -force"
}

# Patch folders of the train, one per line
patch_names() {
	for d in "$PATCHES/$TRAIN"/*; do
		[ -d "$d" ] && echo "${d##*/}"
	done
}

# The installer is the .sh file in the patch folder (jokerbtn.sh, tel2acp.sh, ...)
patch_installer() {
	for f in "$PATCHES/$TRAIN/$1"/*.sh; do
		if [ -f "$f" ]; then echo "$f"; return 0; fi
	done
	return 1
}

run_patch() {
	name="$1"
	shift
	sh_file=$(patch_installer "$name") || { log "$name: no installer found, skipped"; return 1; }
	if [ $FORCE = 1 ]; then
		/bin/sh "$sh_file" "$@" -force < /dev/null
	else
		/bin/sh "$sh_file" "$@" < /dev/null
	fi
}

run_all() {
	for name in $(patch_names); do
		log "-- $name"
		run_patch "$name" "$@"
	done
}

has_gem_link() {
	[ -L "$ESD_LINK" ] || [ -f "$ESD_LINK" ]
}

# The standalone patch repos linked z_<PATCH>.esd and z_<PATCH>_*.esd, with
# Main as the parent. Those links would show the patches twice
remove_legacy_links() {
	for name in $(patch_names); do
		for link in "$ENGDEFS/z_$name.esd" "$ENGDEFS/z_$name"_*.esd; do
			if [ -L "$link" ] || [ -f "$link" ]; then
				rm -f "$link" 2> /dev/null || on -f mmx rm -f "$link" 2> /dev/null
				log "removed old GEM screen ${link##*/}"
			fi
		done
	done
}

remove_gem_link() {
	if has_gem_link; then
		rm -f "$ESD_LINK" 2> /dev/null || on -f mmx rm -f "$ESD_LINK" 2> /dev/null
		log "MIB2_Patcher GEM screen removed"
	fi
}

do_status() {
	log "== status (MIB2-Patcher $VERSION, $TRAIN)"
	if has_gem_link; then log "GEM screen: on"; else log "GEM screen: off"; fi
	run_all status
}

do_gem_on() {
	log "== gem-on"
	[ -f "$ESD_SRC" ] || die "$ESD_SRC is missing on the SD card"
	mount_rw
	remove_legacy_links
	rm -f "$ESD_LINK" 2> /dev/null
	ln -s "$ESD_SRC" "$ESD_LINK" 2> /dev/null || on -f mmx ln -s "$ESD_SRC" "$ESD_LINK" 2> /dev/null
	mount_ro
	has_gem_link || die "could not link $ESD_LINK"
	log "MIB2_Patcher GEM screen added"
	run_all gem-on
}

do_gem_off() {
	log "== gem-off"
	run_all gem-off
	mount_rw
	remove_gem_link
	mount_ro
}

do_uninstall() {
	log "== uninstall"
	run_all uninstall
	mount_rw
	remove_gem_link
	mount_ro
}

if [ "$1" = "-force" ]; then
	FORCE=1
	shift
fi

if [ -z "$1" ]; then
	echo "MIB2-Patcher $VERSION"
	echo "Usage: $0 [-force] train | status | install | uninstall | gem-on | gem-off ..."
	echo "       $0 [-force] <PATCH> <command> [args...]"
	exit 1
fi

detect_train
[ -d "$PATCHES/$TRAIN/$1" ] && { run_patch "$@"; exit $?; }

for action in "$@"; do
	case "$action" in
		train) ;;
		status) do_status ;;
		install) log "== install"; run_all install ;;
		uninstall) do_uninstall ;;
		gem-on) do_gem_on ;;
		gem-off) do_gem_off ;;
		*) die "unknown action '$action'" ;;
	esac
done
