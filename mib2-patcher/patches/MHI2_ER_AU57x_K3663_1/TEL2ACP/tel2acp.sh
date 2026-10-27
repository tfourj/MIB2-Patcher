#!/bin/sh
#
# TEL2ACP v1.2.0 - the TEL hard key opens Apple CarPlay
# Audi MHI2, firmware MHI2_ER_AU57x_K3663_1 (MU1425), e.g. A6/A7 C7
#
# Usage: tel2acp.sh <command> [-force]
#   install       copy TEL2ACP.jar to the unit and add the boot guard block to lsd.sh
#                 (also re-arms the patch after the boot guard disabled it)
#   uninstall     remove the lsd.sh block, the jar, the flags and the GEM screen
#   restore       put back the lsd.sh backup made on first install
#   status        show what is installed
#   gem-on        add the TEL2ACP screen to the GEM (green engineering menu)
#   gem-off       remove the TEL2ACP screen from the GEM
#   mode-tel      TEL / CarPlay: TEL again on CarPlay opens the phone menu (default)
#   mode-carplay  CarPlay only: TEL again on CarPlay does nothing
#   mode-custom   CarPlay / CarPlay Custom: TEL again on CarPlay opens CarPlay Custom
#   custom-<ui>   set CarPlay Custom: phone (default), home, maps, music or nowplaying
#   -force        install even if the firmware fingerprint does not match
#
# Runs on the MMI (QNX). Start it over SSH, from the M.I.B. launcher hook
# (mod/command.sh) or from the TEL2ACP GEM screen. Reboot the MMI after
# install, uninstall and restore. The mode-* and custom-* commands work right away.

VERSION="1.2.0"

export PATH=:/proc/boot:/sbin:/bin:/usr/bin:/usr/sbin:/net/mmx/bin:/net/mmx/usr/bin:/net/mmx/usr/sbin:/net/mmx/sbin:/net/mmx/mnt/app/armle/bin:/net/mmx/mnt/app/armle/sbin:/net/mmx/mnt/app/armle/usr/bin:/net/mmx/mnt/app/armle/usr/sbin
export LD_LIBRARY_PATH=/net/mmx/mnt/app/root/lib-target:/net/mmx/mnt/eso/lib:/net/mmx/eso/lib:/net/mmx/mnt/app/usr/lib:/net/mmx/mnt/app/armle/lib:/net/mmx/mnt/app/armle/lib/dll:/net/mmx/mnt/app/armle/usr/lib

# Package folder on the SD card (where this script lives), custom-patches/TEL2ACP
PKG=$(dirname "$0")
case "$PKG" in
	/*) ;;
	*) PKG="$(pwd)/$PKG" ;;
esac
SD_ROOT="${PKG%/*/*}"

LOG="$PKG/tel2acp.log"

# GEM scripts see the SD card read-only, the M.I.B. launcher remounts it the same way
mount -uw "$SD_ROOT" 2> /dev/null

APP_MOUNT="/net/mmx/mnt/app"
HMI="$APP_MOUNT/eso/hmi"
LSD_SH="$HMI/lsd/lsd.sh"
# The HMI runs with -jxe:/ifs/lsd.jxe (the jxe is inside the MMX boot image)
LSD_JXE="/net/mmx/ifs/lsd.jxe"
LSD_BAK="$LSD_SH.tel2acp.bak"
JARS="$HMI/lsd/jars"
JAR_NAME="TEL2ACP.jar"
ESD_SRC="$PKG/esd/TEL2ACP.esd"
ESD_LINK="$HMI/engdefs/z_TEL2ACP.esd"
# Set_CarPlay_Custom submenu. Sorts after z_TEL2ACP.esd, so its parent screen exists first
ESD_CUSTOM_SRC="$PKG/esd/TEL2ACP_custom.esd"
ESD_CUSTOM_LINK="$HMI/engdefs/z_TEL2ACP_custom.esd"
MARKER="#TEL2ACP"

# Boot guard flags. lsd.sh and the patched class see /mnt/persist on the MMX
PERSIST="/net/mmx/mnt/persist"
FLAG_PENDING="$PERSIST/tel2acp_pending"
FLAG_LOADED="$PERSIST/tel2acp_loaded"
FLAG_DISABLED="$PERSIST/tel2acp_disabled"

# TEL mode (tel, carplay or custom; phone is the 1.1.0 name of custom) and the
# CarPlay Custom screen. The patched class reads both at every TEL press
MODE_FILE="$PERSIST/tel2acp_mode"
CUSTOM_FILE="$PERSIST/tel2acp_custom"

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
	sum=$(sha1_of "$LSD_JXE")
	if [ -z "$sum" ]; then
		[ $FORCE = 1 ] || die "no sha1sum tool found to check the firmware (use -force to skip)"
		log "WARNING: firmware check skipped"
		return
	fi
	log "lsd.jxe sha1: $sum"
	if [ "$sum" != "$SUPPORTED_JXE_SHA1" ]; then
		[ $FORCE = 1 ] || die "unsupported firmware, TEL2ACP was built for MHI2_ER_AU57x_K3663_1 only"
		log "WARNING: unsupported firmware, continuing because of -force"
	fi
}

check_lsd_sh() {
	[ -f "$LSD_SH" ] || die "$LSD_SH not found"
	has_j9_line "$LSD_SH" || die "lsd.sh has no J9 start line, unknown layout"
	grep -q 'BOOTCLASSPATH' "$LSD_SH" || die "lsd.sh does not use BOOTCLASSPATH, unknown layout"
	if grep -v "$MARKER" "$LSD_SH" | grep -q 'Xbootclasspath/p:'; then
		log "lsd.sh already has another -Xbootclasspath/p: mod, TEL2ACP will add its jar to the same list"
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
	( : > "$PERSIST/tel2acp_test" ) 2> /dev/null || die "$PERSIST is not writable, the boot guard needs it"
	rm -f "$PERSIST/tel2acp_test"
}

clear_flags() {
	rm -f "$FLAG_PENDING" "$FLAG_LOADED" "$FLAG_DISABLED" 2> /dev/null
}

current_mode() {
	mode=$(cat "$MODE_FILE" 2> /dev/null)
	case "$mode" in
		carplay|custom) echo "$mode" ;;
		phone) echo "custom" ;;
		*) echo "tel" ;;
	esac
}

# Empty for an unknown mode
mode_text() {
	case "$1" in
		tel) echo "TEL / CarPlay" ;;
		carplay) echo "CarPlay only" ;;
		custom) echo "CarPlay / CarPlay Custom" ;;
	esac
}

current_custom() {
	custom=$(cat "$CUSTOM_FILE" 2> /dev/null)
	case "$custom" in
		home|maps|music|nowplaying) echo "$custom" ;;
		*) echo "phone" ;;
	esac
}

# Empty for an unknown CarPlay Custom screen
custom_text() {
	case "$1" in
		phone) echo "Phone" ;;
		home) echo "Home" ;;
		maps) echo "Maps" ;;
		music) echo "Music" ;;
		nowplaying) echo "Now Playing" ;;
	esac
}

# The block added in front of the J9 start line in lsd.sh. It runs on the MMX
# at every HMI start. It loads the jar only after leaving a pending flag; the
# patched class deletes that flag once it has loaded. A flag still there on
# the next start means that start failed, so the patch is disabled.
# If another mod already set -Xbootclasspath/p:, the jar joins that list so
# J9 only ever gets one /p: option (this is how TEL2ACP and JOKERBTN coexist).
patch_block() {
	m="$MARKER"
	out "# TEL2ACP boot guard, managed by tel2acp.sh $m"
	out "TEL2ACP_JAR=/eso/hmi/lsd/jars/$JAR_NAME $m"
	out "TEL2ACP_FLAG=/mnt/persist/tel2acp $m"
	out 'if [ -f $TEL2ACP_JAR ] && [ ! -f ${TEL2ACP_FLAG}_disabled ]; then '"$m"
	out '	rm -f ${TEL2ACP_FLAG}_loaded '"$m"
	out '	if [ -f ${TEL2ACP_FLAG}_pending ]; then '"$m"
	out '		( : > ${TEL2ACP_FLAG}_disabled ) 2> /dev/null '"$m"
	out '		rm -f ${TEL2ACP_FLAG}_pending '"$m"
	out '	elif ( : > ${TEL2ACP_FLAG}_pending ) 2> /dev/null; then '"$m"
	out '		case "$BOOTCLASSPATH" in '"$m"
	out '			*-Xbootclasspath/p:*) BOOTCLASSPATH="${BOOTCLASSPATH%%-Xbootclasspath/p:*}-Xbootclasspath/p:$TEL2ACP_JAR:${BOOTCLASSPATH#*-Xbootclasspath/p:}" ;; '"$m"
	out '			*) BOOTCLASSPATH="$BOOTCLASSPATH -Xbootclasspath/p:$TEL2ACP_JAR" ;; '"$m"
	out '		esac '"$m"
	out '	fi '"$m"
	out 'fi '"$m"
}

insert_patch_block() {
	tmp="$LSD_SH.tel2acp.tmp"
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
	tmp="$LSD_SH.tel2acp.tmp"
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
	if [ -L "$ESD_CUSTOM_LINK" ] || [ -f "$ESD_CUSTOM_LINK" ]; then
		rm -f "$ESD_CUSTOM_LINK" 2> /dev/null || on -f mmx rm -f "$ESD_CUSTOM_LINK" 2> /dev/null
	fi
	if [ -L "$ESD_LINK" ] || [ -f "$ESD_LINK" ]; then
		rm -f "$ESD_LINK" 2> /dev/null || on -f mmx rm -f "$ESD_LINK" 2> /dev/null
		log "GEM screen removed"
	fi
}

# Puts [x] in front of the label of the entry that runs esd/<prefix>-<selected>.sh
# and [ ] in front of the other <prefix>-* entries. GEM reads the file when it
# loads its screens, so it may only show the new marks after an MMI reboot.
mark_esd() {
	file="$1"
	prefix="$2"
	selected="$3"
	tmp="$file.tmp"
	mark=""
	[ -f "$file" ] || return 1
	while IFS= read -r l || [ -n "$l" ]; do
		case "$l" in
			*"esd/$prefix-"*'.sh"'*)
				name="${l##*esd/$prefix-}"
				name="${name%%.sh*}"
				if [ "$name" = "$selected" ]; then mark="[x]"; else mark="[ ]"; fi
				out "$l"
				;;
			*label*'"'*)
				if [ -n "$mark" ]; then
					text="${l#*\"}"
					text="${text%\"*}"
					case "$text" in
						"[x] "*|"[ ] "*) text="${text#????}" ;;
					esac
					out "${l%%\"*}\"$mark $text\""
					mark=""
				else
					out "$l"
				fi
				;;
			*) out "$l" ;;
		esac
	done < "$file" > "$tmp" && cp "$tmp" "$file"
	rc=$?
	rm -f "$tmp" 2> /dev/null
	return $rc
}

refresh_marks() {
	{
		mark_esd "$ESD_SRC" mode "$(current_mode)" &&
		mark_esd "$ESD_CUSTOM_SRC" custom "$(current_custom)"
	} 2> /dev/null || { log "WARNING: could not update the [x] marks in the GEM screens"; return; }
	log "GEM [x] marks updated, GEM may only show them after an MMI reboot"
}

link_esd() {
	rm -f "$2" 2> /dev/null
	ln -s "$1" "$2" 2> /dev/null || on -f mmx ln -s "$1" "$2" 2> /dev/null
}

do_install() {
	log "== install (TEL2ACP $VERSION)"
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
		# Replace an older TEL2ACP block with the current one
		remove_patch_lines || die "could not update the lsd.sh block, use 'restore'"
	else
		if [ ! -f "$LSD_BAK" ]; then
			cp "$LSD_SH" "$LSD_BAK" || die "could not back up lsd.sh, nothing changed"
			log "lsd.sh backed up to $LSD_BAK"
		fi
		mkdir -p "$PKG/backup" 2> /dev/null
		[ -f "$PKG/backup/lsd.sh" ] || cp "$LSD_SH" "$PKG/backup/lsd.sh" 2> /dev/null
	fi
	insert_patch_block || die "could not patch lsd.sh, it was left unchanged"
	log "lsd.sh patched with boot guard"

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
	rm -f "$MODE_FILE" "$CUSTOM_FILE" 2> /dev/null
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
	rm -f "$MODE_FILE" "$CUSTOM_FILE" 2> /dev/null
	mount_ro
	log "done, reboot the MMI"
}

do_status() {
	log "== status (TEL2ACP $VERSION)"
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
	elif [ -f "$FLAG_LOADED" ]; then
		log "boot guard: patch loaded on the last start"
	elif [ -f "$FLAG_PENDING" ]; then
		log "boot guard: start in progress, or the last start failed"
	else
		log "boot guard: no start since install"
	fi
	log "TEL mode:   $(mode_text "$(current_mode)")"
	log "custom:     CarPlay $(custom_text "$(current_custom)")"
	if [ -f "$LSD_BAK" ]; then log "backup:     $LSD_BAK"; else log "backup:     none"; fi
	if [ -L "$ESD_LINK" ] || [ -f "$ESD_LINK" ]; then log "GEM screen: on"; else log "GEM screen: off"; fi

	# Last, because the checksum of the 45 MB lsd.jxe takes a while
	log "firmware:   checking, this can take a minute..."
	sum=$(sha1_of "$LSD_JXE")
	if [ -z "$sum" ]; then
		log "firmware:   unknown (no sha1sum tool)"
	elif [ "$sum" = "$SUPPORTED_JXE_SHA1" ]; then
		log "firmware:   supported (MHI2_ER_AU57x_K3663_1)"
	else
		log "firmware:   NOT supported (lsd.jxe sha1 $sum)"
	fi
}

do_mode() {
	log "== mode-$1"
	[ -n "$(mode_text "$1")" ] || die "unknown mode '$1'"
	[ -d "$PERSIST" ] || die "$PERSIST not found"
	if [ "$1" = tel ]; then
		rm -f "$MODE_FILE" 2> /dev/null
	else
		( echo "$1" > "$MODE_FILE" ) 2> /dev/null || die "could not write $MODE_FILE"
	fi
	[ "$(current_mode)" = "$1" ] || die "could not set the TEL mode"
	refresh_marks
	log "TEL mode set to $(mode_text "$1"), no reboot needed"
	if [ "$1" = custom ]; then
		log "CarPlay Custom is $(custom_text "$(current_custom)")"
	fi
}

do_custom() {
	log "== custom-$1"
	[ -n "$(custom_text "$1")" ] || die "unknown CarPlay Custom screen '$1'"
	[ -d "$PERSIST" ] || die "$PERSIST not found"
	( echo "$1" > "$CUSTOM_FILE" ) 2> /dev/null || die "could not write $CUSTOM_FILE"
	[ "$(current_custom)" = "$1" ] || die "could not set CarPlay Custom"
	refresh_marks
	log "CarPlay Custom set to $(custom_text "$1"), no reboot needed"
	[ "$(current_mode)" = custom ] || log "TEL mode is $(mode_text "$(current_mode)"), set CarPlay / CarPlay Custom to use it"
}

do_gem_on() {
	log "== gem-on"
	[ -f "$ESD_SRC" ] || die "$ESD_SRC is missing on the SD card"
	[ -f "$ESD_CUSTOM_SRC" ] || die "$ESD_CUSTOM_SRC is missing on the SD card"
	refresh_marks
	mount_rw
	link_esd "$ESD_SRC" "$ESD_LINK"
	link_esd "$ESD_CUSTOM_SRC" "$ESD_CUSTOM_LINK"
	mount_ro
	[ -L "$ESD_LINK" ] || [ -f "$ESD_LINK" ] || die "could not link $ESD_LINK"
	[ -L "$ESD_CUSTOM_LINK" ] || [ -f "$ESD_CUSTOM_LINK" ] || die "could not link $ESD_CUSTOM_LINK"
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
	gem-on) do_gem_on ;;
	gem-off) do_gem_off ;;
	mode-*) do_mode "${cmd#mode-}" ;;
	custom-*) do_custom "${cmd#custom-}" ;;
	*)
		echo "TEL2ACP $VERSION"
		echo "Usage: $0 install [-force] | uninstall | restore | status | gem-on | gem-off"
		echo "       $0 mode-tel | mode-carplay | mode-custom"
		echo "       $0 custom-phone | custom-home | custom-maps | custom-music | custom-nowplaying"
		exit 1
		;;
esac
