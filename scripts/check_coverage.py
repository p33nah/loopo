"""Verify that JVM and Robolectric tests both contribute to the coverage report."""

import argparse
import xml.etree.ElementTree as ET
from pathlib import Path


def check(report: Path) -> None:
    root = ET.parse(report).getroot()
    classes = {item.get("name"): item for item in root.findall("package/class")}
    required = (
        "com/p33nah/loopo/data/audio/CachedLoopCycle",
        "com/p33nah/loopo/data/audio/WavPcmDecoder",
        "com/p33nah/loopo/data/waveform/WaveformCache",
    )
    for name in required:
        item = classes.get(name)
        counter = None if item is None else item.find("counter[@type='LINE']")
        if counter is None or int(counter.get("covered", "0")) == 0:
            raise ValueError(f"No executed lines recorded for {name}; check the test and JaCoCo class-loader settings")
    for counter in root.findall("counter"):
        if counter.get("type") in ("LINE", "BRANCH"):
            covered = int(counter.get("covered"))
            total = covered + int(counter.get("missed"))
            print(f"JVM-only {counter.get('type').lower()} coverage: {covered}/{total}")
    print("Pure JVM and Robolectric class execution is present; Android and native coverage are separate")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("report", type=Path)
    check(parser.parse_args().report)
