#!/usr/bin/env python3
"""Inspect the classes in a MIB2 lsd.jxe (IBM J9 ROM image). See docs/TOOLS.md."""

import argparse
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import j9interp  # noqa: E402
import j9rom  # noqa: E402


def cmd_list(img, args):
    for name in img.names():
        if not args.filter or args.filter in name:
            print(name)


def print_method(cls, method):
    print("\n== %s.%s%s mod=%x" % (cls.name, method.name, method.sig, method.modifiers))
    for start, end, handler, cp_index in method.exceptions:
        print("   catch %d-%d -> %d  %s" % (start, end, handler, cls.cp_entry(cp_index) if cp_index else "any"))
    for pc, op, arg in cls.disassemble(method.code):
        print("  %5d %s %s" % (pc, op, arg))


def cmd_class(img, args):
    cls = img.get(args.name)
    kind = "interface" if cls.is_interface else "class"
    print("%s %s extends %s mod=%x" % (kind, cls.name, cls.super_name, cls.modifiers))
    for iface in cls.interfaces():
        print("  implements", iface)
    for field in cls.fields():
        const = "" if field.constant is None else " = %r" % (field.constant,)
        print("  field %s %s mod=%x%s" % (field.sig, field.name, field.modifiers, const))
    for method in cls.methods():
        if args.methods and method.name not in args.methods:
            continue
        if args.signatures:
            print("  method %s%s mod=%x" % (method.name, method.sig, method.modifiers))
        else:
            print_method(cls, method)


def cmd_fields(img, args):
    for field in img.get(args.name).fields():
        print("%-40s %-30s %s" % (field.name, field.sig, "" if field.constant is None else field.constant))


def cmd_grep_fields(img, args):
    pattern = re.compile(args.pattern)
    for name in img.names():
        try:
            hits = [f for f in img.get(name).fields() if pattern.search(f.name)]
        except Exception:
            continue
        if args.value is not None:
            hits = [f for f in hits if f.constant == args.value]
        for field in hits:
            print("%s.%s = %s" % (name, field.name, field.constant))


def cmd_clinit(img, args):
    statics = j9interp.run(img.get(args.name), "<clinit>", [None], {})
    for key, value in statics.items():
        if key.startswith("CALL:"):
            continue
        value = j9interp.to_python(value)
        print(key)
        if isinstance(value, list):
            for row in value:
                print("   ", row)
        else:
            print("   ", value)


def iter_disassembly(img, prefixes):
    for name in img.names():
        if prefixes and not name.startswith(tuple(prefixes)):
            continue
        try:
            cls = img.get(name)
            for method in cls.methods():
                yield cls, method, cls.disassemble(method.code)
        except Exception as e:
            print("!! %s: %s" % (name, e), file=sys.stderr)


def cmd_dump(img, args):
    with open(args.out, "w", encoding="utf-8") as out:
        for cls, method, ins in iter_disassembly(img, args.prefix):
            short = "%s.%s" % (cls.name.split("/")[-1], method.name)
            out.write("\n== %s.%s%s mod=%x\n" % (cls.name, method.name, method.sig, method.modifiers))
            for pc, op, arg in ins:
                out.write("%s %5d %s %s\n" % (short, pc, op, arg))
    print("wrote", args.out)


def cmd_find_int(img, args):
    wanted = str(args.value)
    for cls, method, ins in iter_disassembly(img, args.prefix):
        for pc, op, arg in ins:
            if (op in ("bipush", "sipush") and arg == wanted) or (op.startswith("ldc") and arg == "int:" + wanted):
                print("%s.%s%s  pc=%d  %s %s" % (cls.name, method.name, method.sig, pc, op, arg))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("jxe", help="lsd.jxe or an extracted rom.classes")
    sub = parser.add_subparsers(dest="command", required=True)

    p = sub.add_parser("list", help="list class names")
    p.add_argument("filter", nargs="?", help="only names containing this text")
    p.set_defaults(func=cmd_list)

    p = sub.add_parser("class", help="show a class: fields and disassembled methods")
    p.add_argument("name", help="class name with slashes, e.g. de/audi/tghu/fwkbd/KeyMap")
    p.add_argument("methods", nargs="*", help="only these method names")
    p.add_argument("-s", "--signatures", action="store_true", help="list method signatures without code")
    p.set_defaults(func=cmd_class)

    p = sub.add_parser("fields", help="list a class's fields with constant values")
    p.add_argument("name")
    p.set_defaults(func=cmd_fields)

    p = sub.add_parser("grep-fields", help="search every class for fields by name (regex)")
    p.add_argument("pattern")
    p.add_argument("--value", type=int, help="only constants with this value")
    p.set_defaults(func=cmd_grep_fields)

    p = sub.add_parser("clinit", help="evaluate a class's static initializer and print its tables")
    p.add_argument("name")
    p.set_defaults(func=cmd_clinit)

    p = sub.add_parser("dump", help="disassemble classes into one text file for grepping")
    p.add_argument("out")
    p.add_argument("prefix", nargs="*", help="only classes starting with these, e.g. de/audi/")
    p.set_defaults(func=cmd_dump)

    p = sub.add_parser("find-int", help="find code that uses an int literal (e.g. an event id)")
    p.add_argument("value", type=int)
    p.add_argument("prefix", nargs="*", help="only classes starting with these")
    p.set_defaults(func=cmd_find_int)

    args = parser.parse_args()
    args.func(j9rom.RomImage(args.jxe), args)


if __name__ == "__main__":
    main()
