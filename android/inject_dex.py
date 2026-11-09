import sys
import zipfile

src, dex, dst = sys.argv[1], sys.argv[2], sys.argv[3]
with zipfile.ZipFile(src, "r") as zin, zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED) as zout:
    for item in zin.infolist():
        if item.filename == "classes.dex":
            continue
        zout.writestr(item, zin.read(item.filename))
    with open(dex, "rb") as f:
        zout.writestr("classes.dex", f.read())
print("wrote", dst)
