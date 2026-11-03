#!/usr/bin/env python3
"""Check the actual packaged ELF files, including Chaquopy's native modules."""
import io
import json
from pathlib import Path
import struct
import sys
import zipfile


def check(path):
    count = 0
    with zipfile.ZipFile(path) as apk:
        names = apk.namelist()
        assert "classes.dex" in names and "AndroidManifest.xml" in names
        for name in names:
            if name.startswith("lib/") and name.endswith(".so"):
                assert name.startswith("lib/x86_64/"), name
                elf(apk.read(name), name)
                count += 1
            if name.startswith("assets/chaquopy/") and name.endswith(".imy"):
                data = apk.read(name)
                if zipfile.is_zipfile(io.BytesIO(data)):
                    with zipfile.ZipFile(io.BytesIO(data)) as nested:
                        for module in nested.namelist():
                            if module.endswith(".so"):
                                elf(nested.read(module), name + "/" + module)
                                count += 1
        assert "lib/x86_64/libquickjs-android-wrapper.so" in names
        assert "lib/x86_64/libpython3.10.so" in names
        assert not any("/libmpv.so" in name or "/libplayer.so" in name for name in names)
        for kind, minimum in (("vod", 55), ("live", 2)):
            catalog = json.loads(apk.read("assets/z10/" + kind + ".json"))
            assert len(catalog) >= minimum
            assert all(item["urls"] for item in catalog)
    print(f"PASS: {Path(path).name}: {count} x86_64 ELF files; bundled VOD/live catalogs")


def elf(data, name):
    assert data[:4] == b"\x7fELF" and data[4:6] == b"\x02\x01", name
    assert struct.unpack_from("<H", data, 18)[0] == 62, name


if __name__ == "__main__":
    for apk_path in sys.argv[1:]:
        check(apk_path)
