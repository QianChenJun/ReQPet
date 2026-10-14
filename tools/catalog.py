"""Dump event catalog items (0x9ab2 responses) in readable form.

Reads a ProtocolProbe log, decodes every 0x9ab2 record, and prints one line per
item: name | cost | duration | reward | extra | canDo | subEventType.
Writes UTF-8 so Chinese survives a GBK console.

Usage:
  python catalog.py probe-20261014.log            # every 0x9ab2 record
  python catalog.py probe-20261014.log --out x.md # also write the report
"""

import argparse
import re
import sys

TARGET = "0x9ab2"


def read_varint(data, index):
    result = 0
    shift = 0
    while True:
        value = data[index]
        index += 1
        result |= (value & 0x7F) << shift
        shift += 7
        if not value & 0x80:
            return result, index


def fields(data):
    """Return {field_number: [raw values]} with wire type kept implicit."""
    out = {}
    index = 0
    while index < len(data):
        try:
            tag, index = read_varint(data, index)
        except Exception:
            break
        field = tag >> 3
        wire = tag & 7
        if wire == 0:
            value, index = read_varint(data, index)
            out.setdefault(field, []).append(("v", value))
        elif wire == 2:
            try:
                length, index = read_varint(data, index)
            except Exception:
                break
            chunk = data[index:index + length]
            if len(chunk) < length:
                break
            index += length
            out.setdefault(field, []).append(("b", chunk))
        elif wire == 5:
            index += 4
        elif wire == 1:
            index += 8
        else:
            break
    return out


def text(chunk):
    try:
        return chunk.decode("utf-8")
    except Exception:
        return chunk.decode("utf-8", "replace")


def first_str(field_map, number):
    for kind, value in field_map.get(number, []):
        if kind == "b":
            return text(value)
    return ""


def first_var(field_map, number):
    for kind, value in field_map.get(number, []):
        if kind == "v":
            return value
    return 0


def strip_markdown(value):
    value = re.sub(r"!\[[^\]]*\]\([^)]*\)", "", value)
    value = re.sub(r"\[([^\]]*)\]\([^)]*\)", r"\1", value)
    return " ".join(value.split())


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("log")
    parser.add_argument("--cmd", default=TARGET)
    parser.add_argument("--out")
    args = parser.parse_args()

    header = re.compile(r"^(\S+ \S+) cmd=(\S*" + re.escape(args.cmd) + r"\S*) code=(-?\d+) len=(\d+).*$")
    hex_only = re.compile(r"^[0-9a-fA-F]+$")
    records = []
    current = None
    body = []
    with open(args.log, "r", encoding="utf-8", errors="replace") as handle:
        for line in handle:
            line = line.rstrip("\n")
            match = header.search(line)
            if match:
                if current is not None:
                    records.append(current + ("".join(body),))
                current = match.groups()
                body = []
                continue
            if current is not None and hex_only.match(line):
                body.append(line)
            elif current is not None and not line:
                continue
    if current is not None:
        records.append(current + ("".join(body),))

    lines = []
    for stamp, cmd, code, length, body in records:
        lines.append(f"### {stamp} cmd={cmd} code={code} len={length}")
        data = bytes.fromhex(body)
        outer = fields(data)
        for kind, chunk in outer.get(1, []):
            if kind != "b":
                continue
            item = fields(chunk)
            name = strip_markdown(first_str(item, 1))
            cost = strip_markdown(first_str(item, 6))
            duration = strip_markdown(first_str(item, 7))
            reward = strip_markdown(first_str(item, 8))
            extra = strip_markdown(first_str(item, 9))
            tip = strip_markdown(first_str(item, 17))
            can = first_var(item, 50)
            sub = first_var(item, 52)
            lines.append(
                f"- {name} | cost={cost} | time={duration} | reward={reward} | extra={extra} | tip={tip} | can={can} | sub={sub}"
            )
        lines.append("")

    report = "\n".join(lines)
    if args.out:
        with open(args.out, "w", encoding="utf-8") as handle:
            handle.write(report + "\n")
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    print(report)


if __name__ == "__main__":
    main()
