#!/bin/sh
#
# MIB2-Patcher v1.2.4 - one GEM entry for every patch that fits the unit's firmware
# Patches live in patches/<train>/<PATCH>, each with its own installer and GEM screens.
# "update" copies the patcher and the patches of the unit's train from the SD card
# to /mnt/app/eso/mib2-patcher, so the GEM screens work without the card afterwards
#
# Usage: patcher.sh [-force] <action> [<action>...]
#        patcher.sh [-force] <PATCH> <command> [args...]
#   update        copy MIB2-Patcher to the unit if the card has a newer patcher or patch
#                 and nothing older, then add the GEM screens and reinstall the patches
#                 that are installed
#   update-force  the same, also when nothing on the card is newer or something is older
#   status        show the version, the train and the status of every patch
#   install       install every patch of the train
#   uninstall     uninstall every patch and remove MIB2-Patcher from the unit
#   gem-on        add the MIB2_Patcher screen and the screens of every patch to the GEM
#   gem-off       remove them from the GEM
#   logs          copy the logs to mib2-patcher/logs on the SD card (with a card in,
#                 the logs are written there already)
#   train         show the train
#   <PATCH> ...   run that patch's installer, e.g. "patcher.sh JOKERBTN set info next"
#   -force        pass -force to the patch installers, and use the only train folder
#                 when the firmware fingerprint matches none
#
# Runs on the MMI (QNX). The first update needs the M.I.B. launcher hook
# (mod/command.sh) or SSH. After that, the copy on the unit runs everything
# from the MIB2_Patcher GEM screen. Run from the card, every action except
# update and update-force is passed on to the copy on the unit.

VERSION="1.2.4"

export PATH=:/proc/boot:/sbin:/bin:/usr/bin:/usr/sbin:/net/mmx/bin:/net/mmx/usr/bin:/net/mmx/usr/sbin:/net/mmx/sbin:/net/mmx/mnt/app/armle/bin:/net/mmx/mnt/app/armle/sbin:/net/mmx/mnt/app/armle/usr/bin:/net/mmx/mnt/app/armle/usr/sbin
export LD_LIBRARY_PATH=/net/mmx/mnt/app/root/lib-target:/net/mmx/mnt/eso/lib:/net/mmx/eso/lib:/net/mmx/mnt/app/usr/lib:/net/mmx/mnt/app/armle/lib:/net/mmx/mnt/app/armle/lib/dll:/net/mmx/mnt/app/armle/usr/lib

# Folder this script lives in: the copy on the unit, or mib2-patcher on the SD card
ROOT=$(dirname "$0")
case "$ROOT" in
	/*) ;;
	*) ROOT="$(pwd)/$ROOT" ;;
esac
SD_ROOT="${ROOT%/*}"

APP_MOUNT="/net/mmx/mnt/app"
ENGDEFS="$APP_MOUNT/eso/hmi/engdefs"
LSD_SH="$APP_MOUNT/eso/hmi/lsd/lsd.sh"
# The HMI runs with -jxe:/ifs/lsd.jxe (the jxe is inside the MMX boot image)
LSD_JXE="/net/mmx/ifs/lsd.jxe"

# The copy on the unit. The previous version stays in mib2-patcher.old until the next update
UNIT_DIR="$APP_MOUNT/eso/mib2-patcher"
PATCHES="$UNIT_DIR/patches"

# Where update looks for a mib2-patcher folder: SD1, SD2, USB (the same order as M.I.B.)
CARDS="/net/mmx/fs/sda0 /net/mmx/fs/sdb0 /net/mmx/fs/usb0_0"

# The lsd.sh backups of the patches. /mnt/app is read-only, /mnt/persist is not
DATA="/net/mmx/mnt/persist/mib2-patcher"
# Logs of the patcher and the patches. pick_logs moves them to the SD card when
# one with a mib2-patcher folder is in, otherwise they stay in $DATA
LOGS="$DATA"
LOG="$LOGS/patcher.log"
# Logs on the unit bigger than this are moved to <log>.old, so they can't fill /mnt/persist
LOG_MAX=65536

# The root GEM screen. The patch screens use the z_0mib2patcher_ prefix, so they sort after it.
# The 0 sorts them before M.I.B.'s z_Launcher-sda0.esd, a link to the SD card: without the
# card it points nowhere and GEM doesn't load the screens that sort after it
ESD_SRC="$UNIT_DIR/esd/MIB2_PATCHER.esd"
ESD_LINK="$ENGDEFS/z_0mib2patcher.esd"

# M.I.B. lock files: do not run while M.I.B. is flashing, backing up or rebooting
SHMEM="/net/rcc/dev/shmem"

FORCE=0
TRAIN=""

mkdir -p "$DATA" 2> /dev/null

# GEM scripts see the SD card read-only, the M.I.B. launcher remounts it the same way
case "$ROOT" in
	"$UNIT_DIR") ;;
	*) mount -uw "$SD_ROOT" 2> /dev/null ;;
esac

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

check_not_busy() {
	for lock in backup reboot flash; do
		if [ -f "$SHMEM/$lock.mib" ]; then
			die "M.I.B. $lock is running, try again when it has finished"
		fi
	done
}

rotate_logs() {
	for f in "$DATA"/*.log "$DATA"/*/*.log; do
		[ -f "$f" ] || continue
		set -- $(ls -l "$f" 2> /dev/null)
		if [ "${5:-0}" -gt $LOG_MAX ] 2> /dev/null; then
			mv -f "$f" "$f.old" 2> /dev/null
		fi
	done
}

# Prints the VERSION="..." of a patcher.sh, nothing if there is none
read_version() {
	[ -f "$1" ] || return 1
	while IFS= read -r l || [ -n "$l" ]; do
		case "$l" in
			'VERSION="'*'"')
				l="${l#VERSION=\"}"
				echo "${l%\"}"
				return 0
				;;
		esac
	done < "$1"
	return 1
}

# True if version $1 is newer than $2 (dotted numbers, like 1.10.0 > 1.9.2)
version_gt() {
	a="$1"
	b="$2"
	while [ -n "$a$b" ]; do
		x="${a%%.*}"
		y="${b%%.*}"
		case "$a" in *.*) a="${a#*.}" ;; *) a="" ;; esac
		case "$b" in *.*) b="${b#*.}" ;; *) b="" ;; esac
		[ "${x:-0}" -gt "${y:-0}" ] 2> /dev/null && return 0
		[ "${x:-0}" -lt "${y:-0}" ] 2> /dev/null && return 1
	done
	return 1
}

# The first mib2-patcher folder on a card or stick
find_card() {
	for c in $CARDS; do
		if [ -f "$c/mib2-patcher/patcher.sh" ]; then
			echo "$c/mib2-patcher"
			return 0
		fi
	done
	return 1
}

# Logs to mib2-patcher/logs on the card when it can be written. Cards without a
# mib2-patcher folder (navigation or music cards) are left alone
pick_logs() {
	if [ "$ROOT" = "$UNIT_DIR" ]; then
		card=$(find_card) || return 0
	else
		card="$ROOT"
	fi
	mount -uw "${card%/*}" 2> /dev/null
	mkdir -p "$card/logs" 2> /dev/null
	{ : >> "$card/logs/patcher.log"; } 2> /dev/null || return 0
	LOGS="$card/logs"
	LOG="$LOGS/patcher.log"
	move_unit_logs
}

# Appends the logs written on the unit while there was no card to the card's logs
move_unit_logs() {
	for f in "$DATA"/*.log "$DATA"/*/*.log; do
		[ -f "$f" ] || continue
		dest="$LOGS/${f#$DATA/}"
		mkdir -p "${dest%/*}" 2> /dev/null
		for part in "$f.old" "$f"; do
			[ -f "$part" ] || continue
			{ cat "$part" >> "$dest" && rm -f "$part"; } 2> /dev/null
		done
	done
}

# Picks the folder in $1/patches whose lsd.jxe.sha1 matches the unit. The sha1 is
# exported, so the patch installers don't spend another minute on the 45 MB file
detect_train() {
	[ -f "$LSD_JXE" ] || die "$LSD_JXE not found"
	log "firmware:   checking, this can take a minute..."
	sum=$(sha1_of "$LSD_JXE")
	if [ -n "$sum" ]; then
		log "lsd.jxe sha1: $sum"
		for d in "$1/patches"/*; do
			if [ "$(cat "$d/lsd.jxe.sha1" 2> /dev/null)" = "$sum" ]; then
				TRAIN="${d##*/}"
				PATCHER_JXE_SHA1="$sum"
				export PATCHER_JXE_SHA1
				log "train:      $TRAIN"
				return 0
			fi
		done
	fi
	[ $FORCE = 1 ] || die "no patches for this firmware in $1/patches (use -force to try the only train folder)"
	count=0
	for d in "$1/patches"/*; do
		[ -d "$d" ] && { count=$((count + 1)); TRAIN="${d##*/}"; }
	done
	[ $count = 1 ] || die "firmware not recognised and $count train folders found, can't pick one"
	log "WARNING: firmware not recognised, using $TRAIN because of -force"
}

# Checks once that the firmware still is the installed train, and exports the
# sha1 for the patch installers. With "warn" a mismatch is only reported
verify_firmware() {
	[ -n "$PATCHER_JXE_SHA1" ] && return 0
	log "firmware:   checking, this can take a minute..."
	sum=$(sha1_of "$LSD_JXE")
	if [ -z "$sum" ]; then
		log "WARNING: no sha1sum tool found, the patch installers check the firmware themselves"
		return 0
	fi
	if [ "$sum" = "$(cat "$PATCHES/$TRAIN/lsd.jxe.sha1" 2> /dev/null)" ]; then
		PATCHER_JXE_SHA1="$sum"
		export PATCHER_JXE_SHA1
		log "firmware:   $TRAIN"
		return 0
	fi
	[ "$1" = warn ] && { log "firmware:   NOT $TRAIN any more (lsd.jxe sha1 $sum), run update-force from a card"; return 0; }
	[ $FORCE = 1 ] || die "the firmware is not $TRAIN any more (lsd.jxe sha1 $sum), run update-force from a card"
	log "WARNING: the firmware is not $TRAIN any more, continuing because of -force"
}

# Patch folders of the train, one per line
patch_names() {
	for d in "$PATCHES/$TRAIN"/*; do
		[ -d "$d" ] && echo "${d##*/}"
	done
}

# The installer is the .sh file in a patch folder (jokerbtn.sh, tel2acp.sh, ...)
installer_in() {
	for f in "$1"/*.sh; do
		if [ -f "$f" ]; then echo "$f"; return 0; fi
	done
	return 1
}

patch_installer() {
	installer_in "$PATCHES/$TRAIN/$1"
}

# Logs one part whose version differs between the card ($2) and the unit ($3)
# and counts it in card_newer or card_older
compare_part() {
	cv=$(read_version "$2")
	uv=$(read_version "$3")
	[ -n "$cv" ] || return 0
	if [ -z "$uv" ]; then
		log "newer:      $1 $cv on the card, not on the unit"
		card_newer=1
	elif version_gt "$cv" "$uv"; then
		log "newer:      $1 $cv on the card, $uv on the unit"
		card_newer=1
	elif version_gt "$uv" "$cv"; then
		log "older:      $1 $cv on the card, $uv on the unit"
		card_older=1
	fi
}

# Compares the patcher and the patches of the unit's train on the card ($1) with
# the copy on the unit. Returns 0 when something is newer and nothing older,
# 1 when nothing is newer, 2 when something is older (only Force update installs that)
compare_card() {
	card_newer=0
	card_older=0
	compare_part MIB2-Patcher "$1/patcher.sh" "$UNIT_DIR/patcher.sh"
	unit_train=$(cat "$UNIT_DIR/train" 2> /dev/null)
	if [ -n "$unit_train" ]; then
		for d in "$1/patches/$unit_train"/*; do
			[ -d "$d" ] || continue
			compare_part "${d##*/}" "$(installer_in "$d")" "$(installer_in "$PATCHES/$unit_train/${d##*/}")"
		done
	fi
	[ $card_older = 1 ] && return 2
	[ $card_newer = 1 ] && return 0
	return 1
}

# The patch installers keep their backups in $PATCHER_DATA/<PATCH> and their logs in $PATCHER_LOGS/<PATCH>
run_patch() {
	name="$1"
	shift
	sh_file=$(patch_installer "$name") || { log "$name: no installer found, skipped"; return 1; }
	PATCHER_DATA="$DATA"
	PATCHER_LOGS="$LOGS"
	export PATCHER_DATA PATCHER_LOGS
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

# Every patch marks its lsd.sh lines with #<PATCH>
is_installed() {
	grep -q "#$1" "$LSD_SH" 2> /dev/null
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
	# Up to 1.2.3 the links were z_mib2patcher*.esd, which sort after M.I.B.'s link
	for link in "$ENGDEFS"/z_mib2patcher*.esd; do
		if [ -L "$link" ] || [ -f "$link" ]; then
			rm -f "$link" 2> /dev/null || on -f mmx rm -f "$link" 2> /dev/null
			log "removed old GEM screen ${link##*/}"
		fi
	done
}

remove_gem_link() {
	if has_gem_link; then
		rm -f "$ESD_LINK" 2> /dev/null || on -f mmx rm -f "$ESD_LINK" 2> /dev/null
		log "MIB2_Patcher GEM screen removed"
	fi
}

# Runs one step of an update and logs its error output when it fails
update_step() {
	err=$("$@" 2>&1) && return 0
	log "failed:     $* (${err:-no error output})"
	return 1
}

# Writes to /mnt/app over qnet can fail from the RCC (GEM screens run there),
# so a failed step is tried again on the MMX itself
mmx_step() {
	update_step "$@" || update_step on -f mmx "$@"
}

# Copies the patcher and the train's patches from the card ($1) to $stage.
# With "mmx" as $2, every step runs on the MMX
stage_copy() {
	run_on=""
	[ "$2" = mmx ] && run_on="on -f mmx"
	update_step $run_on rm -rf "$stage" &&
		update_step $run_on mkdir -p "$stage/patches" &&
		update_step $run_on cp "$1/patcher.sh" "$stage/patcher.sh" &&
		copy_tree "$1/esd" "$stage/esd" &&
		copy_tree "$1/patches/$TRAIN" "$stage/patches/$TRAIN" &&
		update_step $run_on /bin/sh -c 'echo "$1" > "$2/train"' sh "$TRAIN" "$stage"
}

# Copies folder $1 to $2 file by file, on $run_on. The glob skips hidden files, like
# the ._ files macOS writes next to every file on a FAT card, which cp -r can't read
copy_tree() {
	update_step $run_on mkdir -p "$2" || return 1
	for f in "$1"/*; do
		if [ -d "$f" ]; then
			copy_tree "$f" "$2/${f##*/}" || return 1
		elif [ -f "$f" ]; then
			update_step $run_on cp "$f" "$2/${f##*/}" || return 1
		fi
	done
}

# Copies the patcher and the train's patches from the card to the unit ($1 = 1
# also when the card is not newer). Runs the new copy afterwards, so the GEM
# screens and patches come from the new version
do_update() {
	force="$1"
	if [ "$ROOT" = "$UNIT_DIR" ]; then
		src=$(find_card) || die "no mib2-patcher folder found on the SD card (SD1, SD2) or a USB stick"
	else
		src="$ROOT"
	fi
	log "== update from $src"
	new=$(read_version "$src/patcher.sh")
	old=$(read_version "$UNIT_DIR/patcher.sh")
	[ -n "$new" ] || die "$src/patcher.sh has no version"
	log "on the unit: ${old:-none}"
	log "on the card: $new"
	# An update needs a newer patcher or patch on the card, and nothing older
	if [ -n "$old" ] && [ "$force" != 1 ]; then
		compare_card "$src"
		case $? in
			1)
				log "no update, nothing on the card is newer. Use Force update to install it anyway"
				return 0
				;;
			2)
				log "no update, the card has an older version of a part. Use Force update to install it anyway"
				return 0
				;;
		esac
	fi
	check_not_busy

	# The card's patcher does the copy, so no file of this copy is in use while it is replaced
	if [ "$ROOT" = "$UNIT_DIR" ]; then
		log "continuing with $src/patcher.sh"
		action=update
		[ "$force" = 1 ] && action=update-force
		[ $FORCE = 1 ] && exec /bin/sh "$src/patcher.sh" -force "$action" < /dev/null
		exec /bin/sh "$src/patcher.sh" "$action" < /dev/null
	fi

	if [ -f "$UNIT_DIR/train" ]; then
		TRAIN=$(cat "$UNIT_DIR/train")
		log "train:      $TRAIN"
	else
		detect_train "$src"
	fi
	[ -d "$src/patches/$TRAIN" ] || die "the card has no patches for $TRAIN"

	stage="$UNIT_DIR.new"
	mount_rw
	mmx_step rm -rf "$stage" "$UNIT_DIR.old" || { mount_ro; die "could not remove the leftovers of the last update, nothing changed"; }
	if ! stage_copy "$src" && ! { log "copying again on the MMX"; stage_copy "$src" mmx; }; then
		rm -rf "$stage" 2> /dev/null || on -f mmx rm -rf "$stage" 2> /dev/null
		mount_ro
		die "could not copy MIB2-Patcher to the unit, nothing changed"
	fi

	# Patches the new version no longer has are uninstalled by their old installer
	for name in $(patch_names); do
		if [ ! -d "$src/patches/$TRAIN/$name" ] && is_installed "$name"; then
			log "-- $name is not in $new any more, uninstalling it"
			run_patch "$name" uninstall
			mount_rw
		fi
	done

	if [ -d "$UNIT_DIR" ]; then
		mmx_step mv "$UNIT_DIR" "$UNIT_DIR.old" || { mmx_step rm -rf "$stage"; mount_ro; die "could not replace $UNIT_DIR, nothing changed"; }
	fi
	if ! mmx_step mv "$stage" "$UNIT_DIR"; then
		[ -d "$UNIT_DIR.old" ] && mmx_step mv "$UNIT_DIR.old" "$UNIT_DIR"
		mount_ro
		die "could not move the new version in place, kept $old"
	fi
	mount_ro
	log "copied MIB2-Patcher $new to $UNIT_DIR"

	if [ $FORCE = 1 ]; then
		/bin/sh "$UNIT_DIR/patcher.sh" -force refresh < /dev/null
	else
		/bin/sh "$UNIT_DIR/patcher.sh" refresh < /dev/null
	fi
}

# Run by update with the new version: adds the GEM screens and reinstalls the
# patches that are installed, so a new jar or lsd.sh block takes effect
do_refresh() {
	log "== refresh (MIB2-Patcher $VERSION, $TRAIN)"
	do_gem_on
	installed=""
	for name in $(patch_names); do
		is_installed "$name" && installed="$installed $name"
	done
	if [ -n "$installed" ]; then
		verify_firmware
		for name in $installed; do
			log "-- $name"
			run_patch "$name" install
		done
	fi
	log "done, reboot the MMI to load the GEM screens and patches"
}

do_status() {
	log "== status (MIB2-Patcher $VERSION, $TRAIN)"
	if has_gem_link; then log "GEM screen: on"; else log "GEM screen: off"; fi
	if src=$(find_card); then
		compare_card "$src"
		case $? in
			0) log "update:     on ${src%/*}, run Update" ;;
			2) log "update:     ${src%/*} has an older version of a part, only Force update installs it" ;;
			*) log "update:     none ($(read_version "$src/patcher.sh") on ${src%/*})" ;;
		esac
	fi
	verify_firmware warn
	run_all status
}

do_install() {
	log "== install"
	verify_firmware
	run_all install
}

do_gem_on() {
	log "== gem-on"
	[ -f "$ESD_SRC" ] || die "$ESD_SRC is missing"
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
	check_not_busy
	run_all uninstall
	mount_rw
	remove_gem_link
	rm -rf "$UNIT_DIR" "$UNIT_DIR.old" "$UNIT_DIR.new" 2> /dev/null
	mount_ro
	log "MIB2-Patcher removed from the unit, the logs stay in $LOGS. Reboot the MMI"
}

do_logs() {
	log "== logs"
	if [ "$LOGS" != "$DATA" ]; then
		# The logs are on the card already, this only adds the lsd.sh backups
		cp -r "$DATA"/* "$LOGS/" 2> /dev/null
		log "the logs are on the card in $LOGS"
		return 0
	fi
	card=""
	for c in $CARDS; do
		if [ -d "$c" ]; then card="$c"; break; fi
	done
	[ -n "$card" ] || die "no SD card or USB stick found"
	mount -uw "$card" 2> /dev/null
	mkdir -p "$card/mib2-patcher/logs" 2> /dev/null
	cp -r "$DATA"/* "$card/mib2-patcher/logs/" 2> /dev/null || die "could not copy the logs to $card/mib2-patcher/logs"
	log "logs copied to $card/mib2-patcher/logs"
}

if [ "$1" = "-force" ]; then
	FORCE=1
	shift
fi

if [ -z "$1" ]; then
	echo "MIB2-Patcher $VERSION"
	echo "Usage: $0 [-force] update | update-force | status | install | uninstall | gem-on | gem-off | logs | train ..."
	echo "       $0 [-force] <PATCH> <command> [args...]"
	exit 1
fi

pick_logs
rotate_logs

# From the card, only update runs here. Everything else goes to the copy on the unit
if [ "$ROOT" != "$UNIT_DIR" ]; then
	case "$1" in
		update|update-force) ;;
		*)
			[ -f "$UNIT_DIR/patcher.sh" ] || die "MIB2-Patcher is not on the unit yet, run update first"
			[ $FORCE = 1 ] && set -- -force "$@"
			exec /bin/sh "$UNIT_DIR/patcher.sh" "$@"
			;;
	esac
else
	TRAIN=$(cat "$UNIT_DIR/train" 2> /dev/null)
	[ -n "$TRAIN" ] || die "$UNIT_DIR/train is missing, run update-force from a card"
	[ -d "$PATCHES/$TRAIN/$1" ] && { run_patch "$@"; exit $?; }
fi

for action in "$@"; do
	case "$action" in
		update) do_update 0 ;;
		update-force) do_update 1 ;;
		*)
			# Actions after an update from the card run on the new copy on the unit
			if [ "$ROOT" != "$UNIT_DIR" ]; then
				if [ $FORCE = 1 ]; then
					/bin/sh "$UNIT_DIR/patcher.sh" -force "$action" < /dev/null
				else
					/bin/sh "$UNIT_DIR/patcher.sh" "$action" < /dev/null
				fi
				continue
			fi
			case "$action" in
				refresh) do_refresh ;;
				train) log "train:      $TRAIN" ;;
				status) do_status ;;
				install) do_install ;;
				uninstall) do_uninstall ;;
				gem-on) do_gem_on ;;
				gem-off) do_gem_off ;;
				logs) do_logs ;;
				*) die "unknown action '$action'" ;;
			esac
			;;
	esac
done
