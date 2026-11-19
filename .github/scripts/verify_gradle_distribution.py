"""Compare the pinned distribution checksum with Gradle's published checksum."""

from pathlib import Path
import re
from urllib.request import urlopen


def read_properties(path):
    # Accept unescaped keys and single-line values. Fail closed on Java
    # Properties syntax outside this subset instead of interpreting it differently.
    properties = {}
    with path.open(encoding="iso-8859-1") as stream:
        for number, line in enumerate(stream, 1):
            line = line.removesuffix("\n").lstrip(" \t\f")
            if not line or line.startswith(("#", "!")):
                continue
            entry = re.fullmatch(r"([A-Za-z0-9_.-]+)[ \t\f]*=[ \t\f]*((?:[^\\]|\\:)*)", line)
            if not entry:
                raise ValueError(f"Unsupported wrapper property syntax on line {number}")
            key, value = entry.groups()
            if key in properties:
                raise ValueError(f"Duplicate wrapper property {key} on line {number}")
            properties[key] = value.replace(r"\:", ":")
    return properties


def verify():
    properties = read_properties(Path("gradle/wrapper/gradle-wrapper.properties"))
    url = properties["distributionUrl"]
    if not re.fullmatch(r"https://services\.gradle\.org/distributions/gradle-[0-9.]+-bin\.zip", url):
        raise ValueError("Expected a stable Gradle binary distribution")
    with urlopen(url + ".sha256", timeout=30) as response:
        official = response.read(100).decode().strip()
    if not re.fullmatch("[0-9a-f]{64}", official) or official != properties["distributionSha256Sum"]:
        raise ValueError("Distribution checksum differs from Gradle's official checksum")


if __name__ == "__main__":
    verify()
