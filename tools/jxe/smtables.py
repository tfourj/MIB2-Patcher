#!/usr/bin/env python3
"""Query the generated HMI state machines in a MIB2 lsd.jxe. See docs/TOOLS.md.

A state machine like de/audi/tghu/system/sm/SystemSMM is generated code. Its
tables are filled by <base>InitStates.initStates, <base>InitTransitions
.initTransitions and <base>InitMediators.initMediators. This tool evaluates
those methods and answers questions about the result. State numbers are
indexes into the tables; the firmware has no state names.
"""

import argparse
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import j9interp  # noqa: E402
import j9rom  # noqa: E402

EVENT_CLASS_PREFIX = "de/audi/atip/statemachine/SMEvent"


def load_event_names(img):
    """Map event id -> name from the SMEvent* constant classes."""
    names = {}
    for cls_name in img.names():
        if not cls_name.startswith(EVENT_CLASS_PREFIX):
            continue
        short = cls_name[len("de/audi/atip/statemachine/"):]
        for field in img.get(cls_name).fields():
            if isinstance(field.constant, int):
                names.setdefault(field.constant, []).append("%s.%s" % (short, field.name))
    return names


def resolve_event(value, names):
    if value.lstrip("-").isdigit():
        return int(value)
    matches = [eid for eid, labels in names.items() if any(label.endswith("." + value) for label in labels)]
    if not matches:
        sys.exit("unknown event name %s" % value)
    return matches[0]


class StateMachine:
    def __init__(self, img, base):
        calls = {}
        for suffix, method in (("InitStates", "initStates"), ("InitTransitions", "initTransitions"),
                               ("InitMediators", "initMediators")):
            if not img.has(base + suffix):
                print("note: %s%s not found, skipped" % (base, suffix), file=sys.stderr)
                continue
            fields = j9interp.run(img.get(base + suffix), method, [None], {})
            # e.g. CALL:setStateTriggerEventList -> StateTriggerEventList
            calls.update({k[len("CALL:set"):]: v[0][0] for k, v in fields.items() if k.startswith("CALL:set")})

        def table(name):
            return j9interp.to_python(calls.get(name, j9interp.Obj())) or []

        self.triggers = table("StateTriggerEventList")
        self.out_trans = table("StateOutTransList")
        self.superstate = table("StateSuperstateList")
        self.trans_targets = table("TransTrgtStateList")
        self.trans_flags = table("TransFlagList")
        self.mediators = table("MediatorList")

    def outgoing(self, state):
        """(event, transition, targets, flags) for each trigger of a state."""
        events = self.triggers[state] if state < len(self.triggers) and self.triggers[state] else []
        trans = self.out_trans[state] if state < len(self.out_trans) and self.out_trans[state] else []
        for event, tr in zip(events, trans):
            targets = self.trans_targets[tr] if tr < len(self.trans_targets) else None
            flags = self.trans_flags[tr] if tr < len(self.trans_flags) else None
            yield event, tr, targets or [], flags

    def chain(self, state):
        result = [state]
        while state < len(self.superstate) and self.superstate[state] not in (None, -1, state) and len(result) < 50:
            state = self.superstate[state]
            result.append(state)
        return result

    def children(self, state):
        return [s for s, parent in enumerate(self.superstate) if parent == state and s != state]


def label(event, names):
    return "%d %s" % (event, "/".join(names.get(event, [])))


def cmd_event(sm, names, args):
    event = resolve_event(args.event, names)
    for state in range(len(sm.triggers)):
        for ev, tr, targets, flags in sm.outgoing(state):
            if ev == event:
                print("state %d (parents %s): %s -> %s  [transition %d, flags %s]"
                      % (state, sm.chain(state)[1:], label(ev, names), targets, tr, flags))


def cmd_into(sm, names, args):
    for state in range(len(sm.triggers)):
        for ev, tr, targets, flags in sm.outgoing(state):
            if args.state in targets:
                print("state %d: %s -> %s  [transition %d, flags %s]" % (state, label(ev, names), targets, tr, flags))


def cmd_state(sm, names, args):
    print("state %d" % args.state)
    print("  parents:  %s" % sm.chain(args.state)[1:])
    print("  children: %s" % sm.children(args.state))
    for ev, tr, targets, flags in sm.outgoing(args.state):
        print("  on %s -> %s  [transition %d, flags %s]" % (label(ev, names), targets, tr, flags))


def cmd_mediators(sm, names, args):
    for i, med in enumerate(sm.mediators):
        if not med:
            continue
        kind = med.get("__class__", "?").split("/")[-1]
        med_args = med.get("args", [])[1:]
        event = med_args[0] if med_args and isinstance(med_args[0], int) else None
        rest = med_args[1:] if event is not None else med_args
        print("%3d %s event %s  args %s" % (i, kind, label(event, names) if event is not None else "?", rest))


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("jxe", help="lsd.jxe or an extracted rom.classes")
    parser.add_argument("base", help="state machine class, e.g. de/audi/tghu/system/sm/SystemSMM")
    sub = parser.add_subparsers(dest="command", required=True)

    p = sub.add_parser("event", help="where an event (id or name) is handled and where it leads")
    p.add_argument("event", help="e.g. 1903 or SWITCH_TO_TERMINAL_MODE")
    p.set_defaults(func=cmd_event)

    p = sub.add_parser("into", help="every transition that leads to a state")
    p.add_argument("state", type=int)
    p.set_defaults(func=cmd_into)

    p = sub.add_parser("state", help="a state's parents, children and outgoing transitions")
    p.add_argument("state", type=int)
    p.set_defaults(func=cmd_state)

    p = sub.add_parser("mediators", help="model-change mediators (model id -> event)")
    p.set_defaults(func=cmd_mediators)

    args = parser.parse_args()
    img = j9rom.RomImage(args.jxe)
    args.func(StateMachine(img, args.base), load_event_names(img), args)


if __name__ == "__main__":
    main()
