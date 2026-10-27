# JXE analysis tools

Python tools in [`tools/jxe/`](../tools/jxe) for reading the HMI code in a MIB2 `lsd.jxe`.
They were used to find every patch in this repo (see the `FINDINGS.md` of each patch in [`docs/`](.)) and work for other button or HMI patches too.

They need Python 3.8 or newer and nothing else (standard library only).

## Getting the lsd.jxe

- From an M.I.B. backup on the SD card: `backup/<MU>-<train>/<MU>-lsd.jxe`.
- On the unit it's `/ifs/lsd.jxe` on the MMX.

All tools take the `.jxe` directly. An extracted `rom.classes` works too.
Firmware files are in `.gitignore`. Don't commit them.

## Tools

| File | What it does |
|---|---|
| `jxe.py` | List classes, show fields and disassembled methods, evaluate static tables, search for constants |
| `smtables.py` | Answer "which event goes where" for the generated HMI state machines |
| `checkrefs.py` | Check a compiled patch jar against the firmware before installing it |
| `compat.py` | Check whether the TEL2ACP jar fits another firmware |
| `j9rom.py`, `j9interp.py` | Shared code: J9 ROM image reader, disassembler, small table interpreter |

In the examples, `JXE` is the path to your `lsd.jxe`:

```bash
JXE="path/to/MU1425-lsd.jxe"
```

## jxe.py

Class names use slashes, like in the JVM: `de/audi/tghu/fwkbd/KeyMap`.

**Find classes**

```bash
python tools/jxe/jxe.py "$JXE" list fwkbd
```

**Show a class.** `-s` lists method signatures only. Method names after the class limit the output to those methods.

```bash
python tools/jxe/jxe.py "$JXE" class -s de/audi/tghu/fwhmi/appmgr/HardKeyManager
python tools/jxe/jxe.py "$JXE" class de/audi/tghu/fwhmi/appmgr/HardKeyManager triggerKeyEvent
```

**Constants.** `fields` lists one class; `grep-fields` searches every class by field name (regex), optionally by value.

```bash
python tools/jxe/jxe.py "$JXE" fields de/audi/atip/statemachine/SMEventSystem
python tools/jxe/jxe.py "$JXE" grep-fields 'TERMINAL_MODE'
python tools/jxe/jxe.py "$JXE" grep-fields 'KEY_' --value 114
```

**Static tables.** `clinit` runs a class's static initializer and prints the arrays it builds, like the DSI → key tables:

```bash
python tools/jxe/jxe.py "$JXE" clinit de/audi/tghu/fwkbd/KeyMap
```

**Who uses a number.** `static final` constants are inlined, so code uses event and model ids as literals.
`find-int` finds them, optionally limited to class name prefixes:

```bash
python tools/jxe/jxe.py "$JXE" find-int 1903 de/audi/
```

**Full dump for grepping.** All Audi classes take about 30 seconds and 600 MB:

```bash
python tools/jxe/jxe.py "$JXE" dump audi.dis de/audi/
grep "HardKeyManager.triggerKeyEvent" audi.dis
```

Each line starts with `Class.method`, so `grep` results show where they come from.

### Reading the disassembly

- Operands are resolved: `invokevirtual de/audi/atip/log/LogChannel.log:(II)Z`, `ldc int:1000000`, `goto -> 281`.
- J9 has its own return opcodes: `return` (void), `return1` (int or object), `return2` (long or double).
- `aload0getfield` is J9's `aload_0`, always followed by a normal `getfield`.
- Local variable names, line numbers and source file names are not in the ROM.

## smtables.py

The HMI's state machines are generated code. `smtables.py` evaluates their tables.
Give it the state machine class, for example `de/audi/tghu/system/sm/SystemSMM` (the main one) or another `*SMM` class.
State numbers are table indexes; the firmware has no state names. Event names come from the `SMEvent*` classes.

```bash
B=de/audi/tghu/system/sm/SystemSMM
python tools/jxe/smtables.py "$JXE" $B event SWITCH_TO_TERMINAL_MODE
python tools/jxe/smtables.py "$JXE" $B event 103
python tools/jxe/smtables.py "$JXE" $B into 246
python tools/jxe/smtables.py "$JXE" $B state 179
python tools/jxe/smtables.py "$JXE" $B mediators
```

| Command | Shows |
|---|---|
| `event <id or name>` | Every state that handles the event, its parent states and the target state(s) |
| `into <state>` | Every transition that leads to the state |
| `state <state>` | Parents, children and all outgoing transitions |
| `mediators` | `ChangeMediator`s: which HMI model change fires which event |

An event handled in a parent state works in all of its children. For example, `SWITCH_TO_TERMINAL_MODE` is handled in state 179, the parent of the phone, menu and CarPlay states.
The `flags` value is printed raw. Transitions with a guard condition show non-zero flags, but the bits are not fully decoded.

## checkrefs.py

Run it on a patch before it goes on a unit. It exits with status 1 if anything is wrong.

```bash
python tools/jxe/checkrefs.py "$JXE" mib2-patcher/patches/MHI2_ER_AU57x_K3663_1/TEL2ACP/TEL2ACP.jar
```

For each class in the jar it checks that:

- the class version is old enough for the MMI's J9 VM (48 or older; the known-good jars use 46)
- a class that replaces one in the jxe keeps the same superclass and every original field and method, with the same descriptor and public/private/protected/static flags
- every method and field it uses outside the jar exists in the jxe (superclasses and interfaces included)
- interface methods are called on interfaces and class methods on classes

`JXE=... ./build.sh <train> <patch>` runs it after every build.

## compat.py

Run it on the `lsd.jxe` of another firmware to see whether TEL2ACP can support it.
`--lsd-sh` also checks the unit's `/mnt/app/eso/hmi/lsd/lsd.sh` layout for the installer.

```bash
python tools/jxe/compat.py "$JXE" --lsd-sh path/to/lsd.sh
```

It compares the firmware against the K3663 reference in `compat_ref.json` and checks:

- `HardKeyManager` has the same code. If not, it lists each key that behaves differently.
- `checkrefs.py` passes for the K3663 `TEL2ACP.jar` (or `--jar`)
- the ids the jar hardcodes: `HK_TEL`, `SWITCH_TO_TERMINAL_MODE`, CarPlay app id 24 and its screens, TEL light key 4
- the CarPlay Custom ids: model 1122, CarPlay device value 1, `DSICarplay.UI_HOME` 1 to `UI_MEDIA_NOWPLAYING` 5
- `SWITCH_TO_TERMINAL_MODE` has an unguarded transition from a parent of the phone screen

| Verdict | Exit | Meaning |
|---|---|---|
| `FITS` | 0 | The jar works as is. Add the printed sha1 to `tel2acp.sh` and a train folder for it (see the [README](../README.md#adding-a-train)). |
| `REBUILD` | 1 | The patch should work, but the jar must be rebuilt from this firmware's `HardKeyManager` and ids |
| `NOT SUPPORTED` | 2 | Something the patch needs is missing |

`--save-ref <train>` replaces `compat_ref.json` with the given jxe. Only use it on the firmware the jar was built from.

## Making another patch

1. Find the code: `find-int` for a known id, or `grep` in a `dump`.
2. Read the class with `class`, and its constants with `fields`.
3. Rewrite the whole class in `source/<train>/<patch>/src/`, changing only what you need. Add stubs for any firmware types it uses to `stubs/` next to it.
4. Build with `./build.sh <train> <patch>` and run `checkrefs.py` until it reports no problems.

## J9 ROM format notes

Worked out on `MHI2_ER_AU57x_K3663_1` (J9 1.16). Other firmware may differ.

- `lsd.jxe` is a zip. The classes are in `rom.classes`, which starts with `J99J` and a table of contents (class name, ROM class) at offset `0x14`.
- Pointers are self-relative (SRP): target = address of the field + its signed 32-bit value.
- Strings are a little-endian u16 length followed by the bytes.
- ROM class header: name `0x08`, superclass `0x0C`, modifiers `0x10`, interfaces `0x14`/`0x18`, methods `0x1C`/`0x20`, fields `0x24`/`0x28`, constant pool count `0x38`, constant pool `0x68` (8 bytes per entry).
- Method header is 20 bytes. The bytecode size is in 4-byte words, and operands are little-endian.
- After the bytecode: the generic signature (if any) comes before the exception table (if any).
- `0xD7` is a 1-byte `aload0getfield` prefix; `0xE7` is a 5-byte `invokeinterface` (`E7 00 B9 <index>`); `0xCB`–`0xD5` are wide loads, stores and `iinc`.
