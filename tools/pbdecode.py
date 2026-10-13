"""Decode OIDB protobuf payloads captured by ProtocolProbe (probe-<date>.log).

Usage:
  python pbdecode.py 0a1f0801100118d4d0...          # decode raw hex
  python pbdecode.py --log probe-20261013.log       # decode every record
  python pbdecode.py --log probe-20261013.log --cmd 0x975e
  python pbdecode.py --log probe-20261013.log --cmd 0x9ab2 --last 1

Wire format is decoded generically: tag = (field_number << 3) | wire_type.
Nested messages are expanded up to --depth levels, so no .proto file is needed.
"""

import argparse
import sys

MAX_DEPTH = 4


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


def decode(data, indent=0, depth=0):
    index = 0
    lines = []
    while index < len(data):
        try:
            tag, index = read_varint(data, index)
        except Exception:
            break
        field = tag >> 3
        wire = tag & 7
        if field == 0 or field > 100000:
            break
        if wire == 0:
            value, index = read_varint(data, index)
            lines.append("%sf%d varint=%d" % ("  " * indent, field, value))
        elif wire == 2:
            length, index = read_varint(data, index)
            if index + length > len(data):
                break
            chunk = data[index:index + length]
            index += length
            text = None
            try:
                candidate = chunk.decode("utf-8")
                if all(c.isprintable() for c in candidate):
                    text = candidate
            except Exception:
                text = None
            if text is not None and chunk:
                lines.append('%sf%d str(%d)="%s"' % ("  " * indent, field, length, text))
            else:
                lines.append("%sf%d bytes(%d) hex=%s" % ("  " * indent, field, length, chunk.hex()))
                if depth + 1 < MAX_DEPTH:
                    lines.extend(decode(chunk, indent + 1, depth + 1))
        elif wire == 5:
            value = int.from_bytes(data[index:index + 4], "little")
            index += 4
            lines.append("%sf%d fixed32=%d" % ("  " * indent, field, value))
        elif wire == 1:
            value = int.from_bytes(data[index:index + 8], "little")
            index += 8
            lines.append("%sf%d fixed64=%d" % ("  " * indent, field, value))
        else:
            break
    return lines


def parse_records(path):
    """Yield (header, hex_body) pairs from a ProtocolProbe log."""
    header = None
    body = []
    with open(path, "r", encoding="utf-8", errors="replace") as handle:
        for raw in handle:
            line = raw.strip()
            if line.startswith("cmd=") or " cmd=OidbSvcTrpcTcp" in line:
                if header and body:
                    yield header, "".join(body)
                header = line
                body = []
            elif not line:
                if header and body:
                    yield header, "".join(body)
                    header, body = None, []
            elif header is not None and all(c in "0123456789abcdef" for c in line.lower()):
                body.append(line)
    if header and body:
        yield header, "".join(body)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("hex", nargs="*", help="raw hex payload(s)")
    parser.add_argument("--log", help="probe log file")
    parser.add_argument("--cmd", help="only records whose cmd contains this fragment")
    parser.add_argument("--last", type=int, default=0, help="only the newest N matching records")
    args = parser.parse_args()

    if args.log:
        records = [
            (header, body)
            for header, body in parse_records(args.log)
            if not args.cmd or args.cmd in header
        ]
        if args.last:
            records = records[-args.last:]
        for header, body in records:
            print("===", header)
            print("\n".join(decode(bytes.fromhex(body))))
        print("[records: %d]" % len(records), file=sys.stderr)
        return

    if not args.hex:
        parser.error("give raw hex or --log")
    for item in args.hex:
        print("===", item)
        print("\n".join(decode(bytes.fromhex(item))))


if __name__ == "__main__":
    main()
