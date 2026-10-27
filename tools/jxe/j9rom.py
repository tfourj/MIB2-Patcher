"""Read IBM J9 ROM class images, like the rom.classes inside the MIB2 HMI's lsd.jxe.

The layout below was worked out on MHI2_ER_AU57x_K3663_1 (J9 1.16). Other J9
versions may differ.

- lsd.jxe is a zip. The classes live in its rom.classes entry.
- Pointers are self relative (SRP): target = address of the field + signed 32-bit value.
- Strings (J9UTF8) are a little-endian u16 length followed by the bytes.
- Bytecode operands are little-endian, and the method's bytecode size is in 4-byte words.
- static final constants are inlined, so their uses show up as literal bipush/sipush/ldc.
"""

import struct
import zipfile
from collections import namedtuple

ROM_MAGIC = b"J99J"
HDR_CLASS_COUNT = 0x0C
HDR_TOC = 0x14
TOC_ENTRY_SIZE = 8

# J9ROMClass field offsets
RC_CLASS_NAME = 0x08
RC_SUPER_NAME = 0x0C
RC_MODIFIERS = 0x10
RC_INTERFACE_COUNT = 0x14
RC_INTERFACES = 0x18
RC_METHOD_COUNT = 0x1C
RC_METHODS = 0x20
RC_FIELD_COUNT = 0x24
RC_FIELDS = 0x28
RC_CP_COUNT = 0x38
RC_CP_START = 0x68
CP_ENTRY_SIZE = 8

ACC_INTERFACE = 0x0200
FIELD_HAS_CONSTANT = 0x00400000
FIELD_HAS_GENERIC_SIG = 0x40000000
METHOD_HAS_EXCEPTIONS = 0x00020000
METHOD_HAS_GENERIC_SIG = 0x02000000
METHOD_HEADER_SIZE = 20

RomField = namedtuple("RomField", "name sig modifiers constant")
RomMethod = namedtuple("RomMethod", "name sig modifiers code exceptions")

# Standard JVM mnemonics by opcode, 0x00..0xc9
_STD_NAMES = """nop aconst_null iconst_m1 iconst_0 iconst_1 iconst_2 iconst_3 iconst_4 iconst_5
lconst_0 lconst_1 fconst_0 fconst_1 fconst_2 dconst_0 dconst_1 bipush sipush ldc ldc_w ldc2_w
iload lload fload dload aload iload_0 iload_1 iload_2 iload_3 lload_0 lload_1 lload_2 lload_3
fload_0 fload_1 fload_2 fload_3 dload_0 dload_1 dload_2 dload_3 aload_0 aload_1 aload_2 aload_3
iaload laload faload daload aaload baload caload saload istore lstore fstore dstore astore
istore_0 istore_1 istore_2 istore_3 lstore_0 lstore_1 lstore_2 lstore_3 fstore_0 fstore_1
fstore_2 fstore_3 dstore_0 dstore_1 dstore_2 dstore_3 astore_0 astore_1 astore_2 astore_3
iastore lastore fastore dastore aastore bastore castore sastore pop pop2 dup dup_x1 dup_x2
dup2 dup2_x1 dup2_x2 swap iadd ladd fadd dadd isub lsub fsub dsub imul lmul fmul dmul idiv
ldiv fdiv ddiv irem lrem frem drem ineg lneg fneg dneg ishl lshl ishr lshr iushr lushr iand
land ior lor ixor lxor iinc i2l i2f i2d l2i l2f l2d f2i f2l f2d d2i d2l d2f i2b i2c i2s lcmp
fcmpl fcmpg dcmpl dcmpg ifeq ifne iflt ifge ifgt ifle if_icmpeq if_icmpne if_icmplt if_icmpge
if_icmpgt if_icmple if_acmpeq if_acmpne goto jsr ret tableswitch lookupswitch ireturn lreturn
freturn dreturn areturn return getstatic putstatic getfield putfield invokevirtual
invokespecial invokestatic invokeinterface xxxunused new newarray anewarray arraylength
athrow checkcast instanceof monitorenter monitorexit wide multianewarray ifnull ifnonnull
goto_w jsr_w""".split()

# J9-specific opcodes and the J9 return family
_J9_NAMES = {
    0xAC: "return", 0xAD: "return1", 0xAE: "return2",
    0xAF: "syncreturn0", 0xB0: "syncreturn1", 0xB1: "syncreturn2",
    0xCA: "breakpoint",
    0xCB: "iloadw", 0xCC: "lloadw", 0xCD: "floadw", 0xCE: "dloadw", 0xCF: "aloadw",
    0xD0: "istorew", 0xD1: "lstorew", 0xD2: "fstorew", 0xD3: "dstorew", 0xD4: "astorew",
    0xD5: "iincw", 0xD7: "aload0getfield",
    0xE4: "return0(ctor)", 0xE5: "genericReturn", 0xE7: "invokeinterface",
    0xF3: "returnToJIT", 0xF4: "returnFromConstructor", 0xF5: "return1", 0xF9: "ldc2dw",
}

OPNAMES = {op: name for op, name in enumerate(_STD_NAMES)}
OPNAMES.update(_J9_NAMES)

# Instruction lengths in bytes. Switches are variable and handled separately.
OPLEN = dict.fromkeys(range(256), 1)
for _op in (0x10, 0x12, 0x15, 0x16, 0x17, 0x18, 0x19, 0x36, 0x37, 0x38, 0x39, 0x3A, 0xA9, 0xBC):
    OPLEN[_op] = 2
for _op in [0x11, 0x13, 0x14, 0x84, 0xB2, 0xB3, 0xB4, 0xB5, 0xB6, 0xB7, 0xB8, 0xB9, 0xBB, 0xBD,
            0xC0, 0xC1, 0xC6, 0xC7, 0xF9] + list(range(0x99, 0xA9)) + list(range(0xCB, 0xD5)):
    OPLEN[_op] = 3
OPLEN[0xC5] = 4  # multianewarray
OPLEN[0xC8] = 5  # goto_w
OPLEN[0xC9] = 5  # jsr_w
OPLEN[0xD5] = 5  # iincw: u16 local, i16 constant (followed by a padding nop)
OPLEN[0xD7] = 1  # aload0getfield: 1-byte prefix, a normal getfield follows
OPLEN[0xE7] = 5  # invokeinterface: E7 00 B9 <index lo> <index hi>

_CP_INDEX_OPS = (0x13, 0x14, 0xF9, 0xB2, 0xB3, 0xB4, 0xB5, 0xB6, 0xB7, 0xB8, 0xB9, 0xBB, 0xBD, 0xC0, 0xC1)
_LOCAL_INDEX_OPS = (0x15, 0x16, 0x17, 0x18, 0x19, 0x36, 0x37, 0x38, 0x39, 0x3A, 0xBC)


class RomImage:
    """A J9 ROM image. Pass an lsd.jxe (zip) or a bare rom.classes file."""

    def __init__(self, path):
        if zipfile.is_zipfile(path):
            with zipfile.ZipFile(path) as jxe:
                self.data = jxe.read("rom.classes")
        else:
            with open(path, "rb") as f:
                self.data = f.read()
        if self.data[:4] != ROM_MAGIC:
            raise ValueError("%s is not a J9 ROM image (no J99J header)" % path)
        self._offsets = {}
        toc = self.srp(HDR_TOC)
        for i in range(self.u32(HDR_CLASS_COUNT)):
            entry = toc + i * TOC_ENTRY_SIZE
            self._offsets[self.utf8(self.srp(entry))] = self.srp(entry + 4)

    def u16(self, off):
        return struct.unpack_from("<H", self.data, off)[0]

    def u32(self, off):
        return struct.unpack_from("<I", self.data, off)[0]

    def i32(self, off):
        return struct.unpack_from("<i", self.data, off)[0]

    def srp(self, off):
        return off + self.i32(off)

    def utf8(self, off):
        return self.data[off + 2:off + 2 + self.u16(off)].decode("utf-8", "replace")

    def names(self):
        return sorted(self._offsets)

    def has(self, name):
        return name in self._offsets

    def get(self, name):
        if name not in self._offsets:
            raise KeyError("class %s not in ROM image" % name)
        return RomClass(self, name, self._offsets[name])


class RomClass:
    def __init__(self, image, name, offset):
        self.image = image
        self.name = name
        self.offset = offset
        self.modifiers = image.u32(offset + RC_MODIFIERS)
        self.super_name = image.utf8(image.srp(offset + RC_SUPER_NAME)) if image.i32(offset + RC_SUPER_NAME) else None
        self.cp_count = image.u32(offset + RC_CP_COUNT)

    @property
    def is_interface(self):
        return bool(self.modifiers & ACC_INTERFACE)

    def interfaces(self):
        img = self.image
        table = img.srp(self.offset + RC_INTERFACES)
        return [img.utf8(img.srp(table + 4 * i)) for i in range(img.u32(self.offset + RC_INTERFACE_COUNT))]

    def fields(self):
        img = self.image
        p = img.srp(self.offset + RC_FIELDS)
        result = []
        for _ in range(img.u32(self.offset + RC_FIELD_COUNT)):
            name = img.utf8(img.srp(p))
            sig = img.utf8(img.srp(p + 4))
            mods = img.u32(p + 8)
            p += 12
            constant = None
            if mods & FIELD_HAS_CONSTANT:
                if sig in ("J", "D"):
                    constant = img.data[p:p + 8]
                    p += 8
                else:
                    constant = img.i32(p)
                    p += 4
            if mods & FIELD_HAS_GENERIC_SIG:
                p += 4
            result.append(RomField(name, sig, mods, constant))
        return result

    def methods(self):
        img = self.image
        p = img.srp(self.offset + RC_METHODS)
        result = []
        for _ in range(img.u32(self.offset + RC_METHOD_COUNT)):
            name = img.utf8(img.srp(p))
            sig = img.utf8(img.srp(p + 4))
            mods = img.u32(p + 8)
            size = (img.u16(p + 14) | (img.data[p + 16] << 16)) * 4
            code = img.data[p + METHOD_HEADER_SIZE:p + METHOD_HEADER_SIZE + size]
            q = p + METHOD_HEADER_SIZE + size
            # Optional data after the bytecode: generic signature SRP first, then exception info
            if mods & METHOD_HAS_GENERIC_SIG:
                q += 4
            exceptions = []
            if mods & METHOD_HAS_EXCEPTIONS:
                catch_count, throw_count = img.u16(q), img.u16(q + 2)
                for k in range(catch_count):
                    exceptions.append(struct.unpack_from("<4I", img.data, q + 4 + k * 16))
                q += 4 + catch_count * 16 + throw_count * 4
            result.append(RomMethod(name, sig, mods, code, exceptions))
            p = q
        return result

    def cp_entry(self, index):
        """Constant pool entry as text: 'Class.member:desc', a string/class name, or 'int:N'."""
        img = self.image
        e = self.offset + RC_CP_START + index * CP_ENTRY_SIZE
        a, b = img.i32(e), img.i32(e + 4)
        if b == 0:
            return "int:%d" % a
        if 0 < a < self.cp_count and 0 < e + 4 + b < len(img.data):
            nas = e + 4 + b
            try:
                return "%s.%s:%s" % (self.cp_entry(a), img.utf8(img.srp(nas)), img.utf8(img.srp(nas + 4)))
            except (struct.error, IndexError):
                pass
        if a != 0 and 0 < e + a < len(img.data):
            text = img.utf8(e + a)
            if text and all(32 <= ord(c) < 127 or c in "\n\t" for c in text[:50]):
                return text
        return "#%d(%d,%d)" % (index, a, b)

    def disassemble(self, code):
        """List of (pc, mnemonic, argument text)."""
        out = []
        pc = 0
        while pc < len(code):
            op = code[pc]
            name = OPNAMES.get(op, "op_%02x" % op)
            length = OPLEN[op]
            arg = ""
            if op == 0x10:
                arg = str(struct.unpack_from("<b", code, pc + 1)[0])
            elif op == 0x11:
                arg = str(struct.unpack_from("<h", code, pc + 1)[0])
            elif op == 0x12:
                arg = self.cp_entry(code[pc + 1])
            elif op in _CP_INDEX_OPS:
                arg = self.cp_entry(struct.unpack_from("<H", code, pc + 1)[0])
            elif op == 0xE7:
                arg = self.cp_entry(struct.unpack_from("<H", code, pc + 3)[0])
            elif 0x99 <= op <= 0xA8 or op in (0xC6, 0xC7):
                arg = "-> %d" % (pc + struct.unpack_from("<h", code, pc + 1)[0])
            elif op in (0xC8, 0xC9):
                arg = "-> %d" % (pc + struct.unpack_from("<i", code, pc + 1)[0])
            elif op in _LOCAL_INDEX_OPS:
                arg = str(code[pc + 1])
            elif op == 0x84:
                arg = "%d %d" % (code[pc + 1], struct.unpack_from("<b", code, pc + 2)[0])
            elif 0xCB <= op <= 0xD4:
                arg = str(struct.unpack_from("<H", code, pc + 1)[0])
            elif op == 0xD5:
                arg = "%d %d" % struct.unpack_from("<Hh", code, pc + 1)
            elif op == 0xC5:
                arg = "%s dims=%d" % (self.cp_entry(struct.unpack_from("<H", code, pc + 1)[0]), code[pc + 3])
            elif op == 0xAA:
                base = (pc + 4) & ~3
                default, low, high = struct.unpack_from("<iii", code, base)
                targets = struct.unpack_from("<%di" % (high - low + 1), code, base + 12)
                cases = " ".join("%d->%d" % (low + k, pc + t) for k, t in enumerate(targets))
                arg = "default->%d %s" % (pc + default, cases)
                length = base + 12 + 4 * (high - low + 1) - pc
            elif op == 0xAB:
                base = (pc + 4) & ~3
                default, npairs = struct.unpack_from("<ii", code, base)
                pairs = [struct.unpack_from("<ii", code, base + 8 + 8 * k) for k in range(npairs)]
                cases = " ".join("%d->%d" % (key, pc + t) for key, t in pairs)
                arg = "default->%d %s" % (pc + default, cases)
                length = base + 8 + 8 * npairs - pc
            out.append((pc, name, arg))
            pc += length
        return out
