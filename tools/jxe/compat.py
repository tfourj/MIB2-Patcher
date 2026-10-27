#!/usr/bin/env python3
"""Check whether the TEL2ACP jar fits another firmware's lsd.jxe. See docs/TOOLS.md.

TEL2ACP.jar replaces the whole HardKeyManager class and hardcodes event and
screen ids, so a new firmware needs more than checkrefs.py. This compares the
new lsd.jxe against the reference firmware (MHI2_ER_AU57x_K3663_1, stored in
compat_ref.json) and checks:

- HardKeyManager: same code as the reference, key by key
- the jar itself against the new jxe (checkrefs.py)
- the event ids the jar uses (HK_TEL, SWITCH_TO_TERMINAL_MODE)
- the CarPlay app id and screen range the jar uses
- the System state machine: SWITCH_TO_TERMINAL_MODE works from the phone screen
- the TEL key light id used by the phone state machine
- the model and DSI ids the CarPlay Custom mode uses
- optionally the lsd.sh start line layout the installer needs

Verdict and exit status: 0 the jar fits, 1 the patch needs a rebuild for this
firmware, 2 not supported (something the patch relies on is missing).
"""

import argparse
import contextlib
import difflib
import hashlib
import io
import json
import os
import re
import sys

TOOLS_DIR = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, TOOLS_DIR)

import checkrefs  # noqa: E402
import j9rom  # noqa: E402
import smtables  # noqa: E402

REF_PATH = os.path.join(TOOLS_DIR, "compat_ref.json")
DEFAULT_JAR = os.path.join(TOOLS_DIR, "..", "..", "mib2-patcher", "patches", "MHI2_ER_AU57x_K3663_1", "TEL2ACP", "TEL2ACP.jar")

HARDKEY_MANAGER = "de/audi/tghu/fwhmi/appmgr/HardKeyManager"
EVENT_CLASS = "de/audi/atip/statemachine/SMEventSystem"
SYSTEM_SMM = "de/audi/tghu/system/sm/SystemSMM"
TERMINAL_APP = "de/audi/app/terminalmode/TerminalModeHMIApplication"
SCREEN_FACTORY_SUFFIX = "TerminalModeScreenFactory"
PHONE_ACTIONS = "de/audi/tghu/phone/sm/PhoneSMMActions"
LIGHT_METHOD = "setHKIlluminationExclusive"

# Values hardcoded in src/de/audi/tghu/fwhmi/appmgr/HardKeyManager.java
EVENTS = {"HK_TEL": 103, "SWITCH_TO_TERMINAL_MODE": 1903}
TERMINAL_APP_ID = 24
SCREEN_APP_DIVISOR = 10000
TEL_LIGHT_KEY = 4
# CarPlay Custom mode: (class, field, value) the jar hardcodes to open a CarPlay screen on the iPhone
CUSTOM_MODE_IDS = [
    ("de/audi/atip/model/ICoreSystemModelBank", "ACTIVE_SMARTPHONE_DEVICE_CHOICE", 1122),
    ("de/audi/app/terminalmode/device/TMDeviceControl", "ACTIVE_DEVICE_CARPLAY", 1),
    ("org/dsi/ifc/carplay/DSICarplay", "UI_HOME", 1),
    ("org/dsi/ifc/carplay/DSICarplay", "UI_PHONE", 2),
    ("org/dsi/ifc/carplay/DSICarplay", "UI_NAVIGATION", 3),
    ("org/dsi/ifc/carplay/DSICarplay", "UI_MEDIA", 4),
    ("org/dsi/ifc/carplay/DSICarplay", "UI_MEDIA_NOWPLAYING", 5),
]

# J9 keeps its own flags above the Java access bits; they don't change behaviour
JAVA_MODIFIER_MASK = 0xFFFF

FITS, REBUILD, UNSUPPORTED = 0, 1, 2
VERDICTS = {
    FITS: "FITS: the TEL2ACP jar works unchanged, add the lsd.jxe sha1 to the installer",
    REBUILD: "REBUILD: the patch idea works, but the jar must be rebuilt from this firmware",
    UNSUPPORTED: "NOT SUPPORTED: something the patch relies on is missing or different",
}

_BRANCH = re.compile(r"-> ?(\d+)")
_LITERAL_OPS = ("bipush", "sipush", "ldc", "ldc_w")


class Report:
    def __init__(self):
        self.level = FITS

    def ok(self, text):
        print("  OK    %s" % text)

    def info(self, text):
        print("  info  %s" % text)

    def warn(self, text):
        print("  WARN  %s" % text)

    def rebuild(self, text):
        print("  DIFF  %s" % text)
        self.level = max(self.level, REBUILD)

    def fail(self, text):
        print("  FAIL  %s" % text)
        self.level = UNSUPPORTED


def sha1_of(path):
    h = hashlib.sha1()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def normalize(cls, method):
    """Disassembly with pcs replaced by instruction numbers, so builds with other offsets compare equal."""
    ins = cls.disassemble(method.code)
    while ins and ins[-1][1] == "nop":
        ins.pop()
    number = {pc: i for i, (pc, _, _) in enumerate(ins)}
    lines = []
    for _, op, arg in ins:
        arg = _BRANCH.sub(lambda m: "->@%s" % number.get(int(m.group(1)), "?" + m.group(1)), arg)
        lines.append(("%s %s" % (op, arg)).rstrip())
    return lines


def hardkey_snapshot(img):
    cls = img.get(HARDKEY_MANAGER)
    return {
        "super": cls.super_name,
        "fields": ["%s %s %x" % (f.name, f.sig, f.modifiers & JAVA_MODIFIER_MASK) for f in cls.fields()],
        "methods": {"%s%s %x" % (m.name, m.sig, m.modifiers & JAVA_MODIFIER_MASK): normalize(cls, m)
                    for m in cls.methods()},
    }


def key_actions(lines):
    """What triggerKeyEvent does per key: literals and calls, e.g. {4: 'getCurrentPopup 250002 103 fireSMEvent'}."""
    if not lines or not lines[1].startswith("tableswitch"):
        return {}
    targets = dict(part.split("->@") for part in lines[1].split()[1:])
    actions = {}
    for key, start in targets.items():
        words = []
        for line in lines[int(start):]:
            op, _, arg = line.partition(" ")
            if op in ("goto", "return"):
                break
            if op in _LITERAL_OPS:
                words.append(arg[4:] if arg.startswith("int:") else arg)
            elif op.startswith("invoke") and not re.search(r"\.get(LogChannel|HmiService):", arg):
                words.append(arg.split(":")[0].split(".")[-1])
        actions[key] = " ".join(words)
    return actions


def key_order(key):
    return (key == "default", int(key) if key.isdigit() else 0)


def int_fields(img, name):
    return {f.name: f.constant for f in img.get(name).fields() if isinstance(f.constant, int)}


def screen_ids(img):
    """Screen ids built by the terminal mode screen factories (tableswitch cases of createScreen)."""
    ids = set()
    for name in img.names():
        if not name.endswith(SCREEN_FACTORY_SUFFIX) or "terminalmode" not in name:
            continue
        cls = img.get(name)
        for method in cls.methods():
            if method.name != "createScreen":
                continue
            for _, op, arg in cls.disassemble(method.code):
                if op in ("tableswitch", "lookupswitch"):
                    ids.update(int(part.split("->")[0]) for part in arg.split()[1:])
    return sorted(ids)


def tel_light_keys(img):
    """Key ids the phone state machine passes to setHKIlluminationExclusive(terminal, key, true)."""
    cls = img.get(PHONE_ACTIONS)
    keys = set()
    for method in cls.methods():
        ins = cls.disassemble(method.code)
        for i, (_, op, arg) in enumerate(ins):
            if LIGHT_METHOD in arg and i >= 2:
                key_op, key_arg = ins[i - 2][1], ins[i - 2][2]
                if key_op.startswith("iconst_") and key_op != "iconst_m1":
                    keys.add(int(key_op[-1]))
                elif key_op in ("bipush", "sipush"):
                    keys.add(int(key_arg))
    return sorted(keys)


def descendants(sm, state):
    found, todo = set(), [state]
    while todo:
        for child in sm.children(todo.pop()):
            if child not in found:
                found.add(child)
                todo.append(child)
    return found


def sm_facts(img):
    """Where TEL and SWITCH_TO_TERMINAL_MODE are handled in the System state machine."""
    sm = smtables.StateMachine(img, SYSTEM_SMM)
    tel, term = EVENTS["HK_TEL"], EVENTS["SWITCH_TO_TERMINAL_MODE"]
    tel_handlers, phone_states, term_routes = set(), set(), []
    for state in range(len(sm.triggers)):
        for event, _, targets, flags in sm.outgoing(state):
            if event == tel and targets and targets != [state]:
                tel_handlers.add(state)
                phone_states.update(targets)
            elif event == term:
                term_routes.append((state, targets, flags))
    tel_area = set().union(*[descendants(sm, s) | {s} for s in tel_handlers]) if tel_handlers else set()
    open_routes = [r for r in term_routes if not r[2]]
    term_area = set().union(*[descendants(sm, r[0]) | {r[0]} for r in open_routes]) if open_routes else set()
    return {
        "tel_handlers": sorted(tel_handlers),
        "phone_states": sorted(phone_states),
        "terminal_routes": term_routes,
        "phone_reaches_carplay": any(r[0] in sm.chain(p) for r in open_routes for p in phone_states),
        "coverage": [len(tel_area & term_area), len(tel_area)],
    }


def snapshot(img):
    return {"hardkey_manager": hardkey_snapshot(img), "coverage": sm_facts(img)["coverage"]}


def check_hardkey_manager(img, ref, report):
    print("HardKeyManager")
    if not img.has(HARDKEY_MANAGER):
        report.fail("%s not in the jxe, keys are handled elsewhere on this firmware" % HARDKEY_MANAGER)
        return
    mine, theirs = ref["hardkey_manager"], hardkey_snapshot(img)
    if mine == theirs:
        report.ok("same code as the reference")
        return
    if mine["super"] != theirs["super"] or mine["fields"] != theirs["fields"]:
        report.rebuild("superclass or fields differ: %s %s" % (theirs["super"], theirs["fields"]))
    for sig in sorted(set(mine["methods"]) | set(theirs["methods"])):
        name = sig.rsplit(" ", 1)[0]
        if sig not in theirs["methods"]:
            report.rebuild("method %s is missing or has other flags" % name)
        elif sig not in mine["methods"]:
            report.rebuild("extra method %s" % name)
        elif mine["methods"][sig] != theirs["methods"][sig]:
            report.rebuild("method %s differs" % name)
            if sig.startswith("triggerKeyEvent"):
                ref_keys, new_keys = key_actions(mine["methods"][sig]), key_actions(theirs["methods"][sig])
                for key in sorted(set(ref_keys) | set(new_keys), key=key_order):
                    if ref_keys.get(key) != new_keys.get(key):
                        print("          key %-7s reference: %-40s this firmware: %s"
                              % (key, ref_keys.get(key, "-"), new_keys.get(key, "-")))
            else:
                for line in difflib.unified_diff(mine["methods"][sig], theirs["methods"][sig],
                                                 "reference", "this firmware", lineterm="", n=1):
                    print("          " + line)


def check_jar(img, jar, report):
    print("Jar (%s)" % os.path.normpath(jar))
    if not os.path.isfile(jar):
        report.warn("jar not found, skipped (pass --jar)")
        return
    out = io.StringIO()
    with contextlib.redirect_stdout(out):
        problems = checkrefs.check(img, checkrefs.load_classes([jar]))
    if problems:
        report.rebuild("checkrefs.py found %d problem(s):" % problems)
        for line in out.getvalue().splitlines():
            if "BAD" in line:
                print("        " + line.strip())
    else:
        report.ok("checkrefs.py: class layout and every firmware call match")


def check_constants(img, report):
    print("Event and screen ids")
    if not img.has(EVENT_CLASS):
        report.fail("%s not in the jxe" % EVENT_CLASS)
    else:
        events = int_fields(img, EVENT_CLASS)
        for name, value in EVENTS.items():
            if name not in events:
                report.fail("%s.%s does not exist" % (EVENT_CLASS.split("/")[-1], name))
            elif events[name] != value:
                report.rebuild("%s is %d here, the jar uses %d" % (name, events[name], value))
            else:
                report.ok("%s = %d" % (name, value))

    if not img.has(TERMINAL_APP):
        report.fail("%s not in the jxe (no CarPlay / terminal mode app)" % TERMINAL_APP)
        return
    app_id = int_fields(img, TERMINAL_APP).get("APPLICATION_ID")
    if app_id != TERMINAL_APP_ID:
        report.rebuild("terminal mode APPLICATION_ID is %s, the jar uses %d" % (app_id, TERMINAL_APP_ID))
    else:
        report.ok("terminal mode APPLICATION_ID = %d" % app_id)
    ids = screen_ids(img)
    outside = [s for s in ids if s // SCREEN_APP_DIVISOR != TERMINAL_APP_ID]
    if not ids:
        report.warn("no terminal mode screen factory found, the CarPlay screen check is unverified")
    elif outside:
        report.rebuild("terminal mode screens %s don't start with app id %d" % (outside, TERMINAL_APP_ID))
    else:
        report.ok("terminal mode screens %d..%d" % (ids[0], ids[-1]))


def check_state_machine(img, ref, report):
    print("System state machine")
    if not img.has(SYSTEM_SMM + "InitTransitions"):
        report.fail("%s not in the jxe" % SYSTEM_SMM)
        return
    facts = sm_facts(img)
    report.info("TEL handled in state(s) %s, opens phone state(s) %s" % (facts["tel_handlers"], facts["phone_states"]))
    for state, targets, flags in facts["terminal_routes"]:
        report.info("SWITCH_TO_TERMINAL_MODE: state %d -> %s, flags %s" % (state, targets, flags))
    if not facts["terminal_routes"]:
        report.fail("no state handles SWITCH_TO_TERMINAL_MODE")
    elif not facts["phone_reaches_carplay"]:
        report.fail("SWITCH_TO_TERMINAL_MODE has no unguarded transition from a parent of the phone screen")
    else:
        report.ok("SWITCH_TO_TERMINAL_MODE works from the phone screen without a guard")
    covered, total = facts["coverage"]
    ref_covered, ref_total = ref["coverage"]
    text = "TEL opens CarPlay from %d of %d states where TEL works (reference %d of %d)" % (
        covered, total, ref_covered, ref_total)
    if total and ref_total and covered * ref_total < ref_covered * total:
        report.warn(text + ", fewer screens than on the reference")
    else:
        report.info(text)


def check_light(img, report):
    print("TEL key light")
    if not img.has(PHONE_ACTIONS):
        report.warn("%s not found, the TEL light id is unverified" % PHONE_ACTIONS)
        return
    keys = tel_light_keys(img)
    if TEL_LIGHT_KEY in keys:
        report.ok("phone state machine lights key %d" % TEL_LIGHT_KEY)
    else:
        report.rebuild("phone state machine lights key(s) %s, the jar uses %d" % (keys, TEL_LIGHT_KEY))


def check_custom_mode(img, report):
    print("CarPlay Custom mode (TEL opens a CarPlay screen on the iPhone)")
    for name, field, value in CUSTOM_MODE_IDS:
        short = "%s.%s" % (name.split("/")[-1], field)
        found = int_fields(img, name).get(field) if img.has(name) else None
        if found is None:
            report.warn("%s not found, CarPlay Custom falls back to the phone menu" % short)
        elif found != value:
            report.rebuild("%s is %d here, the jar uses %d" % (short, found, value))
        else:
            report.ok("%s = %d" % (short, value))


def check_lsd_sh(path, report):
    """Same rules as is_j9_line/check_lsd_sh in tel2acp.sh."""
    print("lsd.sh (%s)" % path)
    with open(path, encoding="latin-1") as f:
        lines = f.read().splitlines()

    def is_j9_line(line):
        if line.startswith("#"):
            return False
        return line.startswith("$J9") or re.search(r"/j9 .*\$BOOTCLASSPATH", line) is not None

    j9 = [l for l in lines if is_j9_line(l)]
    if not j9:
        report.fail("no J9 start line, the installer would refuse this layout")
    else:
        report.ok("J9 start line: %s" % j9[0].strip())
    if not any("BOOTCLASSPATH" in l for l in lines):
        report.fail("does not use BOOTCLASSPATH, the installer would refuse this layout")
    if any("Xbootclasspath/p:" in l and "#TEL2ACP" not in l for l in lines):
        report.info("another mod already uses -Xbootclasspath/p:, TEL2ACP joins that list")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("jxe", help="lsd.jxe of the firmware to check")
    parser.add_argument("--jar", default=DEFAULT_JAR, help="TEL2ACP.jar to check (default: the built one)")
    parser.add_argument("--lsd-sh", help="the unit's /mnt/app/eso/hmi/lsd/lsd.sh, to check the installer layout")
    parser.add_argument("--save-ref", metavar="NAME",
                        help="write this jxe as the new reference (compat_ref.json), NAME is the train")
    args = parser.parse_args()

    img = j9rom.RomImage(args.jxe)
    sha1 = sha1_of(args.jxe)

    if args.save_ref:
        ref = {"train": args.save_ref, "jxe_sha1": sha1}
        ref.update(snapshot(img))
        with open(REF_PATH, "w", encoding="utf-8") as f:
            json.dump(ref, f, indent=1)
            f.write("\n")
        print("wrote %s (%s, %s)" % (REF_PATH, args.save_ref, sha1))
        return

    with open(REF_PATH, encoding="utf-8") as f:
        ref = json.load(f)
    print("lsd.jxe sha1 %s" % sha1)
    same = "  <- same file" if sha1 == ref["jxe_sha1"] else ""
    print("reference    %s (%s)%s\n" % (ref["train"], ref["jxe_sha1"], same))

    report = Report()
    check_hardkey_manager(img, ref, report)
    check_jar(img, args.jar, report)
    check_constants(img, report)
    check_state_machine(img, ref, report)
    check_light(img, report)
    check_custom_mode(img, report)
    if args.lsd_sh:
        check_lsd_sh(args.lsd_sh, report)

    print("\n" + VERDICTS[report.level])
    if report.level == FITS:
        print("  sha1 for tel2acp.sh: %s" % sha1)
    sys.exit(report.level)


if __name__ == "__main__":
    main()
