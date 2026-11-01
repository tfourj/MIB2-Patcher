#!/bin/sh
#
# MENUSCREEN v0.2.3 - a MENU tile that opens a custom screen (prototype)
# Audi MHI2, firmware MHI2_ER_AU57x_K3663_1 (MU1425), e.g. A6/A7 C7
#
# Usage: menuscreen.sh <command> [-force]
#   install     copy MENUSCREEN.jar to the unit and add the boot guard block to lsd.sh
#               (also re-arms the patch after the boot guard disabled it)
#   uninstall   remove the lsd.sh block, the jar, the flags, the settings and the GEM screen
#   restore     put back the lsd.sh backup made on first install
#   status      show what is installed, the settings and the patch log
#               (also copies the patch log to the SD card)
#   slot <online|breakdown|off>
#               choose the hidden MENU tile to reuse, works without a reboot
#   gem-on      add the MENUSCREEN screen to the GEM (green engineering menu)
#   gem-off     remove the MENUSCREEN screen from the GEM
#   -force      install even if the firmware fingerprint does not match
#
# Runs on the MMI (QNX). Start it over SSH, from the M.I.B. launcher hook
# (mod/command.sh) or from the MENUSCREEN GEM screen. Reboot the MMI after
# install, uninstall and restore.

VERSION="0.2.3"

export PATH=:/proc/boot:/sbin:/bin:/usr/bin:/usr/sbin:/net/mmx/bin:/net/mmx/usr/bin:/net/mmx/usr/sbin:/net/mmx/sbin:/net/mmx/mnt/app/armle/bin:/net/mmx/mnt/app/armle/sbin:/net/mmx/mnt/app/armle/usr/bin:/net/mmx/mnt/app/armle/usr/sbin
export LD_LIBRARY_PATH=/net/mmx/mnt/app/root/lib-target:/net/mmx/mnt/eso/lib:/net/mmx/eso/lib:/net/mmx/mnt/app/usr/lib:/net/mmx/mnt/app/armle/lib:/net/mmx/mnt/app/armle/lib/dll:/net/mmx/mnt/app/armle/usr/lib

# Package folder (where this script lives), mib2-patcher/patches/<train>/MENUSCREEN,
# in /mnt/app/eso on the unit or on the SD card
PKG=$(dirname "$0")
case "$PKG" in
	/*) ;;
	*) PKG="$(pwd)/$PKG" ;;
esac
SD_ROOT="${PKG%/*/*/*/*}"

# The lsd.sh copy goes to the folder patcher.sh passes (on /mnt/persist, because
# /mnt/app is read-only), or next to this script when it runs on its own
DATA="$PKG"
if [ -n "$PATCHER_DATA" ]; then
	DATA="$PATCHER_DATA/MENUSCREEN"
	mkdir -p "$DATA" 2> /dev/null
fi
# The log goes to the log folder patcher.sh passes (on the SD card when one is in)
LOGS="$DATA"
if [ -n "$PATCHER_LOGS" ]; then
	LOGS="$PATCHER_LOGS/MENUSCREEN"
	mkdir -p "$LOGS" 2> /dev/null
fi
LOG="$LOGS/menuscreen.log"

# GEM scripts see the SD card read-only, the M.I.B. launcher remounts it the same way
case "$PKG" in
	/net/mmx/mnt/app/*) ;;
	*) mount -uw "$SD_ROOT" 2> /dev/null ;;
esac

APP_MOUNT="/net/mmx/mnt/app"
HMI="$APP_MOUNT/eso/hmi"
LSD_SH="$HMI/lsd/lsd.sh"
# The HMI runs with -jxe:/ifs/lsd.jxe (the jxe is inside the MMX boot image)
LSD_JXE="/net/mmx/ifs/lsd.jxe"
LSD_BAK="$LSD_SH.menuscreen.bak"
JARS="$HMI/lsd/jars"
JAR_NAME="MENUSCREEN.jar"
ESD_SRC="$PKG/esd/MENUSCREEN.esd"
ESD_LINK="$HMI/engdefs/z_0mib2patcher_MENUSCREEN.esd"
MARKER="#MENUSCREEN"

# Boot guard flags, seen by lsd.sh as /mnt/persist on the MMX
PERSIST="/net/mmx/mnt/persist"
FLAG_PENDING="$PERSIST/menuscreen_pending"
FLAG_DISABLED="$PERSIST/menuscreen_disabled"

# Settings, read by the patched classes every few seconds
CONFIG="$PERSIST/menuscreen.conf"
SLOTS="online breakdown off"
DEFAULT_SLOT="online"

# Written by the patched classes on the MMX
SCREEN_LOG="$PERSIST/menuscreen.log"
# Copy of the patch log next to the installer log, made by status and uninstall
SCREEN_LOG_COPY="$LOGS/menuscreen-unit.log"

# sha1 of lsd.jxe on MHI2_ER_AU57x_K3663_1 (MU1425), the firmware the patch was built from
SUPPORTED_JXE_SHA1="2be4758b1e9f3068173c8df6dac9be541113a3e6"

# M.I.B. lock files: do not run while M.I.B. is flashing, backing up or rebooting
SHMEM="/net/rcc/dev/shmem"

FORCE=0

if (print -r -- x) > /dev/null 2>&1; then
	out() { print -r -- "$1"; }
else
	out() { printf '%s\n' "$1"; }
fi

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

# The lsd.sh line that starts the J9 VM. Other trains use $J9, MU1425 runs
# "on -p 12 /ifs/jre/bin/j9 $VMOPTIONS -Xbootclasspath:$BOOTCLASSPATH ..."
is_j9_line() {
	case "$1" in
		'#'*) return 1 ;;
		'$J9'*|*'/j9 '*'$BOOTCLASSPATH'*) return 0 ;;
	esac
	return 1
}

has_j9_line() {
	while IFS= read -r l || [ -n "$l" ]; do
		is_j9_line "$l" && return 0
	done < "$1"
	return 1
}

is_patched() {
	grep -q "$MARKER" "$LSD_SH" 2> /dev/null
}

check_not_busy() {
	for lock in backup reboot flash; do
		if [ -f "$SHMEM/$lock.mib" ]; then
			die "M.I.B. $lock is running, try again when it has finished"
		fi
	done
}

check_firmware() {
	[ -f "$LSD_JXE" ] || die "$LSD_JXE not found"
	# patcher.sh exports the sha1 it already checked
	sum=${PATCHER_JXE_SHA1:-$(sha1_of "$LSD_JXE")}
	if [ -z "$sum" ]; then
		[ $FORCE = 1 ] || die "no sha1sum tool found to check the firmware (use -force to skip)"
		log "WARNING: firmware check skipped"
		return
	fi
	log "lsd.jxe sha1: $sum"
	if [ "$sum" != "$SUPPORTED_JXE_SHA1" ]; then
		[ $FORCE = 1 ] || die "unsupported firmware, MENUSCREEN was built for MHI2_ER_AU57x_K3663_1 only"
		log "WARNING: unsupported firmware, continuing because of -force"
	fi
}

check_lsd_sh() {
	[ -f "$LSD_SH" ] || die "$LSD_SH not found"
	has_j9_line "$LSD_SH" || die "lsd.sh has no J9 start line, unknown layout"
	grep -q 'BOOTCLASSPATH' "$LSD_SH" || die "lsd.sh does not use BOOTCLASSPATH, unknown layout"
	if grep -v "$MARKER" "$LSD_SH" | grep -q 'Xbootclasspath/p:'; then
		log "lsd.sh already has another -Xbootclasspath/p: mod, MENUSCREEN will add its jar to the same list"
	fi
}

check_jar() {
	expected=$(cat "$PKG/$JAR_NAME.sha1" 2> /dev/null)
	[ -n "$expected" ] || die "$PKG/$JAR_NAME.sha1 is missing on the SD card"
	sum=$(sha1_of "$1")
	if [ -z "$sum" ]; then
		[ $FORCE = 1 ] || die "no sha1sum tool found to check $1 (use -force to skip)"
		log "WARNING: jar check skipped"
		return 0
	fi
	[ "$sum" = "$expected" ]
}

check_persist() {
	[ -d "$PERSIST" ] || die "$PERSIST not found, the boot guard needs it"
	( : > "$PERSIST/menuscreen_test" ) 2> /dev/null || die "$PERSIST is not writable, the boot guard needs it"
	rm -f "$PERSIST/menuscreen_test"
}

clear_flags() {
	rm -f "$FLAG_PENDING" "$FLAG_DISABLED" 2> /dev/null
}

# The block added in front of the J9 start line in lsd.sh. It runs on the MMX
# at every HMI start. The patched classes only load when the MENU or CAR menu
# opens, so they can't report a good start themselves. Instead the block
# leaves a pending flag and removes it after a minute in the background. A
# flag still there on the next start means the HMI or the unit restarted
# within that minute, so the patch is disabled.
# If another mod already set -Xbootclasspath/p:, the jar joins that list so
# J9 only ever gets one /p: option (this is how it coexists with TEL2ACP and JOKERBTN).
patch_block() {
	m="$MARKER"
	out "# MENUSCREEN boot guard, managed by menuscreen.sh $m"
	out "MENUSCREEN_JAR=/eso/hmi/lsd/jars/$JAR_NAME $m"
	out "MENUSCREEN_FLAG=/mnt/persist/menuscreen $m"
	out 'if [ -f $MENUSCREEN_JAR ] && [ ! -f ${MENUSCREEN_FLAG}_disabled ]; then '"$m"
	out '	if [ -f ${MENUSCREEN_FLAG}_pending ]; then '"$m"
	out '		( : > ${MENUSCREEN_FLAG}_disabled ) 2> /dev/null '"$m"
	out '		rm -f ${MENUSCREEN_FLAG}_pending '"$m"
	out '	elif ( : > ${MENUSCREEN_FLAG}_pending ) 2> /dev/null; then '"$m"
	out '		( sleep 60; rm -f ${MENUSCREEN_FLAG}_pending ) > /dev/null 2>&1 & '"$m"
	out '		case "$BOOTCLASSPATH" in '"$m"
	out '			*-Xbootclasspath/p:*) BOOTCLASSPATH="${BOOTCLASSPATH%%-Xbootclasspath/p:*}-Xbootclasspath/p:$MENUSCREEN_JAR:${BOOTCLASSPATH#*-Xbootclasspath/p:}" ;; '"$m"
	out '			*) BOOTCLASSPATH="$BOOTCLASSPATH -Xbootclasspath/p:$MENUSCREEN_JAR" ;; '"$m"
	out '		esac '"$m"
	out '	fi '"$m"
	out 'fi '"$m"
}

insert_patch_block() {
	tmp="$LSD_SH.menuscreen.tmp"
	block=$(patch_block)
	inserted=0
	while IFS= read -r l || [ -n "$l" ]; do
		if [ $inserted = 0 ] && is_j9_line "$l"; then
			out "$block"
			inserted=1
		fi
		out "$l"
	done < "$LSD_SH" > "$tmp"
	if ! grep -q "$MARKER" "$tmp" || ! has_j9_line "$tmp"; then
		rm -f "$tmp"
		return 1
	fi
	cp "$tmp" "$LSD_SH" && rm -f "$tmp"
}

remove_patch_lines() {
	tmp="$LSD_SH.menuscreen.tmp"
	while IFS= read -r l || [ -n "$l" ]; do
		case "$l" in
			*"$MARKER"*) ;;
			*) out "$l" ;;
		esac
	done < "$LSD_SH" > "$tmp"
	if grep -q "$MARKER" "$tmp" || ! has_j9_line "$tmp"; then
		rm -f "$tmp"
		return 1
	fi
	cp "$tmp" "$LSD_SH" && rm -f "$tmp"
}

remove_gem_link() {
	if [ -L "$ESD_LINK" ] || [ -f "$ESD_LINK" ]; then
		rm -f "$ESD_LINK" 2> /dev/null || on -f mmx rm -f "$ESD_LINK" 2> /dev/null
		log "GEM screen removed"
	fi
}

valid_slot() {
	for s in $SLOTS; do
		[ "$s" = "$1" ] && return 0
	done
	return 1
}

# The slot= value from the settings, the default if there is none
get_slot() {
	v=""
	if [ -f "$CONFIG" ]; then
		while IFS= read -r l || [ -n "$l" ]; do
			case "$l" in
				slot=*) v="${l#*=}" ;;
			esac
		done < "$CONFIG"
	fi
	valid_slot "$v" || v="$DEFAULT_SLOT"
	echo "$v"
}

slot_text() {
	case "$1" in
		breakdown) echo "breakdown (Breakdown call tile, China only, adds a 9th tile)" ;;
		off) echo "off (MENU is stock)" ;;
		*) echo "online (Audi connect tile)" ;;
	esac
}

# Writes slot=<slot> and keeps every other line. The rename keeps the patched
# classes from reading a half-written file
write_slot() {
	tmp="$CONFIG.tmp"
	{
		if [ -f "$CONFIG" ]; then
			while IFS= read -r l || [ -n "$l" ]; do
				case "$l" in
					slot=*) ;;
					*) out "$l" ;;
				esac
			done < "$CONFIG"
		else
			out "# MENUSCREEN settings, see README.md"
			out "tile=Custom"
			out "title=MENUSCREEN"
		fi
		out "slot=$1"
	} > "$tmp" 2> /dev/null || return 1
	mv -f "$tmp" "$CONFIG" 2> /dev/null && return 0
	cp "$tmp" "$CONFIG" && rm -f "$tmp"
}

# Copies the patch log to the SD card, where it can be read on a computer
save_screen_log() {
	if [ -f "$SCREEN_LOG" ]; then
		cp "$SCREEN_LOG" "$SCREEN_LOG_COPY" 2> /dev/null && log "patch log copied to $SCREEN_LOG_COPY"
	fi
}

do_install() {
	log "== install (MENUSCREEN $VERSION)"
	check_not_busy
	[ -f "$PKG/$JAR_NAME" ] || die "$PKG/$JAR_NAME is missing on the SD card"
	check_jar "$PKG/$JAR_NAME" || die "$PKG/$JAR_NAME is damaged (checksum mismatch), copy it to the SD card again"
	check_firmware
	check_lsd_sh
	check_persist

	mount_rw
	mkdir -p "$JARS" 2> /dev/null
	cp "$PKG/$JAR_NAME" "$JARS/$JAR_NAME" || die "could not copy $JAR_NAME to $JARS"
	if ! check_jar "$JARS/$JAR_NAME"; then
		rm -f "$JARS/$JAR_NAME"
		die "copied $JAR_NAME does not match the checksum, removed it again"
	fi
	log "copied $JAR_NAME to $JARS"

	if is_patched; then
		# Replace an older MENUSCREEN block with the current one
		remove_patch_lines || die "could not update the lsd.sh block, use 'restore'"
	else
		if [ ! -f "$LSD_BAK" ]; then
			cp "$LSD_SH" "$LSD_BAK" || die "could not back up lsd.sh, nothing changed"
			log "lsd.sh backed up to $LSD_BAK"
		fi
		mkdir -p "$DATA/backup" 2> /dev/null
		[ -f "$DATA/backup/lsd.sh" ] || cp "$LSD_SH" "$DATA/backup/lsd.sh" 2> /dev/null
	fi
	insert_patch_block || die "could not patch lsd.sh, it was left unchanged"
	log "lsd.sh patched with boot guard"

	if [ ! -f "$CONFIG" ]; then
		write_slot "$DEFAULT_SLOT" || log "WARNING: could not write $CONFIG"
	fi
	log "MENU tile:  $(slot_text "$(get_slot)")"

	clear_flags
	mount_ro
	log "done, reboot the MMI to activate"
}

do_uninstall() {
	log "== uninstall"
	check_not_busy
	[ -f "$LSD_SH" ] || die "$LSD_SH not found"

	mount_rw
	if is_patched; then
		remove_patch_lines || die "could not clean lsd.sh, use 'restore' instead"
		log "lsd.sh block removed"
	else
		log "lsd.sh is not patched"
	fi
	if [ -f "$JARS/$JAR_NAME" ]; then
		rm -f "$JARS/$JAR_NAME" && log "$JAR_NAME removed"
	fi
	remove_gem_link
	clear_flags
	save_screen_log
	rm -f "$CONFIG" "$CONFIG.tmp" "$SCREEN_LOG" 2> /dev/null
	mount_ro
	log "done, reboot the MMI to deactivate"
}

do_restore() {
	log "== restore"
	check_not_busy
	[ -f "$LSD_BAK" ] || die "no backup found at $LSD_BAK"

	mount_rw
	cp "$LSD_BAK" "$LSD_SH" || die "could not restore lsd.sh"
	log "lsd.sh restored from $LSD_BAK"
	rm -f "$JARS/$JAR_NAME"
	remove_gem_link
	clear_flags
	save_screen_log
	rm -f "$CONFIG" "$CONFIG.tmp" "$SCREEN_LOG" 2> /dev/null
	mount_ro
	log "done, reboot the MMI"
}

do_status() {
	log "== status (MENUSCREEN $VERSION)"
	if is_patched; then log "lsd.sh:     patched"; else log "lsd.sh:     not patched"; fi
	jar_sum=$(sha1_of "$JARS/$JAR_NAME")
	if [ ! -f "$JARS/$JAR_NAME" ]; then
		log "jar:        not installed"
	elif [ -z "$jar_sum" ]; then
		log "jar:        installed (not checked, no sha1sum tool)"
	elif [ "$jar_sum" = "$(cat "$PKG/$JAR_NAME.sha1" 2> /dev/null)" ]; then
		log "jar:        installed"
	else
		log "jar:        installed but different from the SD card copy, run install again"
	fi
	if [ -f "$FLAG_DISABLED" ]; then
		log "boot guard: DISABLED the patch after a failed start, run install to re-arm"
	elif [ -f "$FLAG_PENDING" ]; then
		log "boot guard: started less than a minute ago, or the last start failed"
	else
		log "boot guard: ok"
	fi
	log "MENU tile:  $(slot_text "$(get_slot)")"
	if [ -f "$LSD_BAK" ]; then log "backup:     $LSD_BAK"; else log "backup:     none"; fi
	if [ -L "$ESD_LINK" ] || [ -f "$ESD_LINK" ]; then log "GEM screen: on"; else log "GEM screen: off"; fi
	if [ -f "$SCREEN_LOG" ]; then
		save_screen_log
		log "patch log (last lines of $SCREEN_LOG):"
		total=0
		while IFS= read -r l; do
			total=$((total + 1))
		done < "$SCREEN_LOG"
		i=0
		while IFS= read -r l; do
			i=$((i + 1))
			[ $i -gt $((total - 8)) ] && log "  $l"
		done < "$SCREEN_LOG"
	else
		log "patch log:  none yet, open MENU once after installing"
	fi

	# Last, because the checksum of the 45 MB lsd.jxe takes a while
	log "firmware:   checking, this can take a minute..."
	# patcher.sh exports the sha1 it already checked
	sum=${PATCHER_JXE_SHA1:-$(sha1_of "$LSD_JXE")}
	if [ -z "$sum" ]; then
		log "firmware:   unknown (no sha1sum tool)"
	elif [ "$sum" = "$SUPPORTED_JXE_SHA1" ]; then
		log "firmware:   supported (MHI2_ER_AU57x_K3663_1)"
	else
		log "firmware:   NOT supported (lsd.jxe sha1 $sum)"
	fi
}

do_slot() {
	log "== slot $1"
	valid_slot "$1" || die "unknown slot '$1', use one of: $SLOTS"
	[ -d "$PERSIST" ] || die "$PERSIST not found"
	write_slot "$1" || die "could not write $CONFIG"
	[ "$(get_slot)" = "$1" ] || die "could not set the slot"
	log "MENU tile:  $(slot_text "$1")"
	log "saved, works the next time MENU opens"
}

do_gem_on() {
	log "== gem-on"
	[ -f "$ESD_SRC" ] || die "$ESD_SRC is missing on the SD card"
	mount_rw
	rm -f "$ESD_LINK" 2> /dev/null
	ln -s "$ESD_SRC" "$ESD_LINK" 2> /dev/null || on -f mmx ln -s "$ESD_SRC" "$ESD_LINK" 2> /dev/null
	mount_ro
	[ -L "$ESD_LINK" ] || [ -f "$ESD_LINK" ] || die "could not link $ESD_LINK"
	log "GEM screen added, it shows up after the next reboot"
}

do_gem_off() {
	log "== gem-off"
	mount_rw
	remove_gem_link
	mount_ro
}

cmd="$1"
[ "$2" = "-force" ] && FORCE=1

case "$cmd" in
	install) do_install ;;
	uninstall) do_uninstall ;;
	restore) do_restore ;;
	status) do_status ;;
	slot) do_slot "$2" ;;
	gem-on) do_gem_on ;;
	gem-off) do_gem_off ;;
	*)
		echo "MENUSCREEN $VERSION"
		echo "Usage: $0 install [-force] | uninstall | restore | status | slot <online|breakdown|off> | gem-on | gem-off"
		exit 1
		;;
esac
