#!/usr/bin/env python3
"""Check compiled patch classes against a MIB2 lsd.jxe before putting them on a unit.

For each class in the given .class or .jar files:
- If it replaces a class from the jxe, every original field and method must
  still exist with the same descriptor and visibility/static flags.
- Every method and field it references outside the patch must exist in the
  jxe (searching superclasses and interfaces), and interface methods must be
  called on interfaces and class methods on classes.

Exits with status 1 if anything is wrong. See docs/TOOLS.md.
"""

import argparse
import os
import struct
import sys
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import j9rom  # noqa: E402

# Java class versions: 46 = Java 1.2 (what the known-good M.I.B. jars use), 49 = Java 5
MAX_SAFE_CLASS_VERSION = 48
ACCESS_MASK = 0x000F  # public, private, protected, static

CP_UTF8, CP_CLASS = 1, 7
CP_FIELDREF, CP_METHODREF, CP_IFACE_METHODREF, CP_NAME_AND_TYPE = 9, 10, 11, 12
REF_KINDS = {CP_FIELDREF: "field", CP_METHODREF: "method", CP_IFACE_METHODREF: "interface method"}


def parse_class(data):
    """Minimal Java class file reader: name, version, members and member references."""
    major = struct.unpack_from(">H", data, 6)[0]
    count = struct.unpack_from(">H", data, 8)[0]
    pos = 10
    cp = [None] * count
    i = 1
    while i < count:
        tag = data[pos]
        if tag == CP_UTF8:
            n = struct.unpack_from(">H", data, pos + 1)[0]
            cp[i] = data[pos + 3:pos + 3 + n].decode("utf-8", "replace")
            pos += 3 + n
        elif tag in (3, 4):
            pos += 5
        elif tag in (5, 6):
            pos += 9
            i += 1
        elif tag in (CP_CLASS, 8, 16, 19, 20):
            cp[i] = (tag, struct.unpack_from(">H", data, pos + 1)[0])
            pos += 3
        elif tag in (CP_FIELDREF, CP_METHODREF, CP_IFACE_METHODREF, CP_NAME_AND_TYPE, 17, 18):
            cp[i] = (tag,) + struct.unpack_from(">HH", data, pos + 1)
            pos += 5
        elif tag == 15:
            pos += 4
        else:
            raise ValueError("unknown constant pool tag %d" % tag)
        i += 1

    def class_name(index):
        return cp[cp[index][1]]

    access, this_index, super_index, iface_count = struct.unpack_from(">HHHH", data, pos)
    pos += 8 + 2 * iface_count

    def members():
        nonlocal pos
        result = []
        n = struct.unpack_from(">H", data, pos)[0]
        pos += 2
        for _ in range(n):
            m_access, name_i, desc_i, attr_count = struct.unpack_from(">HHHH", data, pos)
            pos += 8
            for _ in range(attr_count):
                pos += 6 + struct.unpack_from(">I", data, pos + 2)[0]
            result.append((cp[name_i], cp[desc_i], m_access))
        return result

    fields = members()
    methods = members()
    refs = set()
    for entry in cp:
        if isinstance(entry, tuple) and entry[0] in REF_KINDS:
            nat = cp[entry[2]]
            refs.add((entry[0], class_name(entry[1]), cp[nat[1]], cp[nat[2]]))
    return {
        "name": class_name(this_index), "major": major, "access": access,
        "super": class_name(super_index) if super_index else None,
        "fields": fields, "methods": methods, "refs": refs,
    }


def load_classes(paths):
    classes = []
    for path in paths:
        if path.endswith(".class"):
            with open(path, "rb") as f:
                classes.append(parse_class(f.read()))
        else:
            with zipfile.ZipFile(path) as jar:
                for entry in jar.namelist():
                    if entry.endswith(".class"):
                        classes.append(parse_class(jar.read(entry)))
    return classes


def find_member(img, owner, name, desc, want_field, seen=None):
    """Look up a member in the jxe, searching superclasses and interfaces."""
    seen = seen if seen is not None else set()
    if owner in seen or not img.has(owner):
        return False
    seen.add(owner)
    cls = img.get(owner)
    members = cls.fields() if want_field else cls.methods()
    if any(m.name == name and m.sig == desc for m in members):
        return True
    parents = ([cls.super_name] if cls.super_name else []) + cls.interfaces()
    return any(find_member(img, p, name, desc, want_field, seen) for p in parents)


def check(img, classes):
    problems = 0
    own = {c["name"]: c for c in classes}

    for c in classes:
        print("%s (class version %d)" % (c["name"], c["major"]))
        if c["major"] > MAX_SAFE_CLASS_VERSION:
            print("  BAD  class version %d is newer than %d, build with an older target" % (c["major"], MAX_SAFE_CLASS_VERSION))
            problems += 1
        if not img.has(c["name"]):
            print("  new class, not in the jxe")
        else:
            orig = img.get(c["name"])
            before = problems
            print("  replaces the jxe class (super %s)" % orig.super_name)
            if orig.super_name != c["super"]:
                print("  BAD  superclass %s, the original has %s" % (c["super"], orig.super_name))
                problems += 1
            for kind, original, mine in (("field", orig.fields(), c["fields"]), ("method", orig.methods(), c["methods"])):
                mine_by_key = {(n, d): a for n, d, a in mine}
                for member in original:
                    access = mine_by_key.get((member.name, member.sig))
                    if access is None:
                        print("  BAD  missing %s %s %s" % (kind, member.name, member.sig))
                        problems += 1
                    elif access & ACCESS_MASK != member.modifiers & ACCESS_MASK:
                        print("  BAD  %s %s %s has flags %x, the original has %x"
                              % (kind, member.name, member.sig, access & ACCESS_MASK, member.modifiers & ACCESS_MASK))
                        problems += 1
            if problems == before:
                print("  original members: all present")

        for kind, owner, name, desc in sorted(c["refs"]):
            if owner in own or owner.startswith("["):
                continue
            if not img.has(owner):
                print("  BAD  %s refers to class %s, which is not in the jxe" % (REF_KINDS[kind], owner))
                problems += 1
                continue
            is_iface = img.get(owner).is_interface
            ok = find_member(img, owner, name, desc, kind == CP_FIELDREF)
            wrong_kind = (kind == CP_IFACE_METHODREF and not is_iface) or (kind == CP_METHODREF and is_iface)
            status = "OK  " if ok and not wrong_kind else "BAD "
            note = "" if ok else "  (not found)"
            if wrong_kind:
                note += "  (called as %s, but %s is %s)" % (REF_KINDS[kind], owner, "an interface" if is_iface else "a class")
            print("  %s %s %s.%s%s%s" % (status, REF_KINDS[kind], owner, name, desc, note))
            if status != "OK  ":
                problems += 1

    print("\n%d problem(s)" % problems)
    return problems


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("jxe", help="lsd.jxe or an extracted rom.classes")
    parser.add_argument("classes", nargs="+", help=".jar or .class files to check")
    args = parser.parse_args()
    sys.exit(1 if check(j9rom.RomImage(args.jxe), load_classes(args.classes)) else 0)


if __name__ == "__main__":
    main()
