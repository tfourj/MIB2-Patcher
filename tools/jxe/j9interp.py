"""Tiny interpreter for table-building code in J9 ROM classes.

It runs straight-line code that fills arrays and fields, like <clinit> table
initialisers and the generated state-machine Init* classes. Only the
instructions those use are supported; calls into other classes are recorded
in fields["CALL:<method>"] instead of being run. Branches other than goto are
ignored, so it is not a general JVM.
"""


class Obj(dict):
    """An array or object built by the interpreted code. Arrays use int keys."""


def to_python(value):
    """Turn interpreted arrays into lists (recursively), for printing."""
    if isinstance(value, Obj):
        keys = [k for k in value if isinstance(k, int)]
        if keys or not value:
            return [to_python(value.get(i)) for i in range(max(keys) + 1 if keys else 0)]
        return {k: to_python(v) for k, v in value.items()}
    if isinstance(value, list):
        return [to_python(v) for v in value]
    return value


def _arg_count(desc):
    count, j = 0, 1
    while desc[j] != ")":
        while desc[j] == "[":
            j += 1
        j = desc.index(";", j) + 1 if desc[j] == "L" else j + 1
        count += 1
    return count


def _member(arg):
    return arg.split(":")[0].split(".")[-1]


def run(cls, method_name, args, fields):
    """Run cls.method_name with locals args (args[0] is 'this'); state goes into fields."""
    for method in cls.methods():
        if method.name != method_name:
            continue
        ins = cls.disassemble(method.code)
        index_of = {pc: i for i, (pc, _, _) in enumerate(ins)}
        local = dict(enumerate(args))
        stack = []
        i = 0
        while i < len(ins):
            _, op, arg = ins[i]
            i += 1
            if op.startswith("iconst_"):
                stack.append(-1 if op.endswith("m1") else int(op[-1]))
            elif op in ("bipush", "sipush"):
                stack.append(int(arg))
            elif op in ("ldc", "ldc_w"):
                stack.append(int(arg[4:]) if arg.startswith("int:") else arg)
            elif op in ("newarray", "anewarray"):
                stack.pop()
                stack.append(Obj())
            elif op == "multianewarray":
                stack.append(Obj())
            elif op == "new":
                stack.append(Obj(__class__=arg))
            elif op == "dup":
                stack.append(stack[-1])
            elif op in ("iastore", "aastore", "bastore", "castore", "sastore"):
                value, key, array = stack.pop(), stack.pop(), stack.pop()
                array[key] = value
            elif op in ("iaload", "aaload", "baload", "caload", "saload"):
                key, array = stack.pop(), stack.pop()
                stack.append(array.get(key))
            elif op.startswith(("aload_", "iload_")):
                stack.append(local.get(int(op[-1])))
            elif op in ("aload", "iload"):
                stack.append(local.get(int(arg)))
            elif op.startswith(("astore_", "istore_")):
                local[int(op[-1])] = stack.pop()
            elif op in ("astore", "istore"):
                local[int(arg)] = stack.pop()
            elif op == "aload0getfield":
                stack.append(local.get(0))
            elif op == "getfield":
                stack.pop()
                stack.append(fields.setdefault(_member(arg), Obj()))
            elif op == "putfield":
                value = stack.pop()
                stack.pop()
                fields[_member(arg)] = value
            elif op == "getstatic":
                stack.append(fields.setdefault(_member(arg), Obj()))
            elif op == "putstatic":
                fields[_member(arg)] = stack.pop()
            elif op.startswith("invoke"):
                desc = arg.split(":", 1)[1]
                call_args = [stack.pop() for _ in range(_arg_count(desc))][::-1]
                target = stack.pop() if op != "invokestatic" else None
                name = _member(arg)
                if name == "<init>" and isinstance(target, Obj):
                    target["args"] = call_args
                if arg.startswith(cls.name + "."):
                    run(cls, name, [None] + call_args, fields)
                else:
                    fields.setdefault("CALL:" + name, []).append(call_args)
                if not desc.endswith("V"):
                    stack.append(None)
            elif op == "pop":
                stack.pop()
            elif op == "goto":
                i = index_of[int(arg.split()[-1])]
        return fields
    raise KeyError("%s has no method %s" % (cls.name, method_name))
