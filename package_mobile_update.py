"""Create a bounded, checksum-verified phone import bundle; never include signing keys."""
import argparse, hashlib, json, struct
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED

CIA_HEADER_SIZE = 0x2020
CIA_TITLE_ID = 0x000400000F764200


def _align64(n):
    return (n + 63) & ~63


def _inspect_cia(data, path):
    if len(data) < CIA_HEADER_SIZE + 0x200 or struct.unpack_from('<I', data, 0)[0] != CIA_HEADER_SIZE:
        raise ValueError('Not a valid CIA header: ' + str(path))
    content_offset = _align64(struct.unpack_from('<I', data, 0)[0])
    for offset in (8, 12, 16):
        content_offset += _align64(struct.unpack_from('<I', data, offset)[0])
    content_size = struct.unpack_from('<Q', data, 24)[0]
    if content_offset + content_size > len(data) or content_size < 0x200:
        raise ValueError('Invalid CIA content bounds: ' + str(path))
    ncch = data[content_offset:content_offset + content_size]
    if ncch[0x100:0x104] != b'NCCH':
        raise ValueError('NCCH magic missing in CIA: ' + str(path))
    if struct.unpack_from('<Q', ncch, 0x118)[0] != CIA_TITLE_ID:
        raise ValueError('Unexpected CIA Title ID: ' + str(path))


def package(apk, three_ds, output, version, cia=None):
    entries = []
    files = []
    specs = [
        ('3ds', three_ds, 'studygadget.3dsx', 16 * 1024 * 1024),
        ('cia', cia, 'studygadget.cia', 32 * 1024 * 1024),
        ('android', apk, 'StudyCompanion.apk', 80 * 1024 * 1024),
    ]
    for target, path, name, limit in specs:
        if path is None:
            continue
        path = Path(path)
        data = path.read_bytes()
        if not 0 < len(data) <= limit:
            raise ValueError('Invalid file size: ' + str(path))
        if target == '3ds':
            if len(data) < 32 or data[:4] != b'3DSX' or b'STUDYGADGET_APP_V1' not in data:
                raise ValueError('Not an update-capable Study Gadget 3DSX')
            header, reloc, *rest = struct.unpack_from('<HH6I', data, 4)
            code, ro, rw, bss = rest[2:]
            if not (32 <= header <= 512 and 8 <= reloc <= 512 and code and bss <= rw and header + 3 * reloc + code + ro + rw - bss <= len(data)):
                raise ValueError('Invalid 3DSX layout')
        elif target == 'cia':
            _inspect_cia(data, path)
        else:
            with ZipFile(path) as z:
                if not {'AndroidManifest.xml', 'classes.dex'}.issubset(z.namelist()):
                    raise ValueError('Not a base APK')
        entries.append({'target': target, 'path': name, 'size': len(data), 'sha256': hashlib.sha256(data).hexdigest()})
        files.append((path, name))
    if not entries:
        raise ValueError('Select at least one executable')
    manifest = {'format': 'studygadget-update', 'schema': 1, 'version': version, 'files': entries}
    output = Path(output)
    output.parent.mkdir(parents=True, exist_ok=True)
    with ZipFile(output, 'w', ZIP_DEFLATED, compresslevel=6) as z:
        z.writestr('study-update.json', json.dumps(manifest, ensure_ascii=False))
        for path, name in files:
            z.write(path, name)
    with ZipFile(output) as z:
        if z.testzip() is not None:
            raise ValueError('ZIP verification failed')
        for entry in entries:
            data = z.read(entry['path'])
            if len(data) != entry['size'] or hashlib.sha256(data).hexdigest() != entry['sha256']:
                raise ValueError('Bundle checksum differs')
    return manifest


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--apk', type=Path)
    parser.add_argument('--three-ds', type=Path)
    parser.add_argument('--cia', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--version', default='local build')
    args = parser.parse_args()
    print(json.dumps(package(args.apk, args.three_ds, args.output, args.version, cia=args.cia), ensure_ascii=False))
