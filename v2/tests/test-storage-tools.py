#!/usr/bin/env python3
import base64
import os
import pathlib
import subprocess
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[1]

with tempfile.TemporaryDirectory() as temporary:
    temp = pathlib.Path(temporary)
    state = temp / "state"
    media = temp / "media"
    download = media / "0" / "Download"
    download.mkdir(parents=True)
    (state / "config.conf").parent.mkdir(parents=True)
    (state / "config.conf").write_text("shared_index_ttl_seconds=300\nmax_file_mb=0\n", encoding="utf-8")

    keeper = download / "原件\t一.bin"
    duplicate = download / "副本\n二.bin"
    other = download / "same-size-not-duplicate.bin"
    keeper.write_bytes(b"A" * 150_000)
    duplicate.write_bytes(keeper.read_bytes())
    other.write_bytes(b"B" * 150_000)

    env = dict(os.environ, BAIZE_STATE_DIR=str(state), BAIZE_MEDIA_ROOT=str(media))
    subprocess.run(["bash", str(ROOT / "module/storage-index.sh"), "refresh", "test"], env=env, check=True)
    subprocess.run(["bash", str(ROOT / "module/duplicate-scanner.sh")], env=env, check=True)
    subprocess.run(["bash", str(ROOT / "module/large-file-scanner.sh"), "0"], env=env, check=True)

    rows = (state / "reports/duplicates.tsv").read_text(encoding="utf-8").splitlines()[1:]
    assert len(rows) == 1, rows
    columns = rows[0].split("\t")
    decoded = {base64.b64decode(columns[3]), base64.b64decode(columns[4])}
    assert decoded == {os.fsencode(keeper), os.fsencode(duplicate)}

    large_rows = (state / "reports/large-files.tsv").read_text(encoding="utf-8").splitlines()[1:]
    decoded_large = {base64.b64decode(row.split("\t", 2)[2]) for row in large_rows}
    assert os.fsencode(keeper) in decoded_large and os.fsencode(duplicate) in decoded_large

    # Same size and restored mtime are insufficient: ctime/cache key and full hash prevent stale matches.
    old_stat = duplicate.stat()
    duplicate.write_bytes(b"C" * 150_000)
    os.utime(duplicate, ns=(old_stat.st_atime_ns, old_stat.st_mtime_ns))
    subprocess.run(["bash", str(ROOT / "module/duplicate-scanner.sh")], env=env, check=True)
    assert (state / "reports/duplicates.tsv").read_text(encoding="utf-8").splitlines()[1:] == []

print("storage tools encoded paths and staged hashing: ok")
