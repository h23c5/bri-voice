#!/usr/bin/env python3
r"""Validate written .vsqx files against the official VOCALOID3 / VOCALOID4 schemas.

usage: validate_vsqx.py <dir-with-vsqx> [schema-dir]

The schema directory defaults to E:\V4 and must contain vsq3.xsd and vsq4.xsd.
Every *.vsqx in <dir> is parsed and validated with lxml.etree.XMLSchema; schema errors are
printed verbatim. Exit code is 1 when at least one file is not valid.
"""
import glob
import os
import sys

try:
    from lxml import etree
except ImportError:  # pragma: no cover
    sys.stderr.write("lxml is required (use the bundled python)\n")
    raise

DEFAULT_SCHEMA_DIR = r"E:\V4"
ROOTS = ("vsq4", "vsq3")


def main(argv):
    if len(argv) < 2:
        sys.stderr.write(__doc__ + "\n")
        return 2
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")
    except AttributeError:
        pass

    directory = argv[1]
    schema_dir = argv[2] if len(argv) > 2 else DEFAULT_SCHEMA_DIR
    if not os.path.isdir(directory):
        sys.stderr.write("no such directory: %s\n" % directory)
        return 2

    schemas = {}
    for name in ROOTS:
        path = os.path.join(schema_dir, name + ".xsd")
        try:
            schemas[name] = etree.XMLSchema(etree.parse(path))
        except Exception as exc:
            sys.stderr.write("cannot load schema %s: %s\n" % (path, exc))
            return 2

    files = sorted(glob.glob(os.path.join(directory, "*.vsqx")))
    if not files:
        sys.stderr.write("no .vsqx file in %s\n" % directory)
        return 2

    ok = 0
    invalid = []
    for path in files:
        name = os.path.basename(path)
        try:
            doc = etree.parse(path)
        except Exception as exc:
            print("[FAIL] %s : not well formed: %s" % (name, exc))
            invalid.append(name)
            continue
        root = etree.QName(doc.getroot()).localname
        schema = schemas.get(root)
        if schema is None:
            print("[FAIL] %s : unexpected root element <%s>" % (name, root))
            invalid.append(name)
            continue
        if schema.validate(doc):
            print("[ok]   %s  (%s.xsd, %d bytes)" % (name, root, os.path.getsize(path)))
            ok += 1
        else:
            print("[FAIL] %s  (%s.xsd)" % (name, root))
            for err in schema.error_log:
                print("       %s" % err)
            invalid.append(name)

    print("")
    print("XSD validation: %d/%d valid" % (ok, len(files)))
    if invalid:
        print("INVALID: " + ", ".join(invalid))
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
