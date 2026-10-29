"""Tap the first on-screen element whose text contains the given string (used to drive the 1.0.0 APK)."""
import re
import subprocess
import sys
import time

needle = sys.argv[1]
for attempt in range(20):
    subprocess.run(["adb", "shell", "uiautomator", "dump", "/sdcard/ui.xml"], capture_output=True)
    xml = subprocess.run(["adb", "shell", "cat", "/sdcard/ui.xml"], capture_output=True, text=True).stdout
    for m in re.finditer(r'<node [^>]*?text="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
        if needle in m.group(1):
            x = (int(m.group(2)) + int(m.group(4))) // 2
            y = (int(m.group(3)) + int(m.group(5))) // 2
            subprocess.run(["adb", "shell", "input", "tap", str(x), str(y)])
            print(f"tapped '{m.group(1)}' at {x},{y}")
            time.sleep(1.5)
            sys.exit(0)
    time.sleep(1)
print(f"not found: {needle}")
sys.exit(1)
