"""Pick an available simulator from the newest installed iOS runtime."""
import argparse
import json
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--family", choices=("iPhone", "iPad"), default="iPhone")
family = parser.parse_args().family

data = json.loads(subprocess.check_output(["xcrun", "simctl", "list", "devices", "available", "--json"]))
for runtime in sorted(data["devices"], key=lambda s: [int(n) for n in s.split("iOS-")[-1].split("-")] if "iOS-" in s else [], reverse=True):
    if "iOS-" not in runtime:
        continue
    simulator = next(
        (device for device in data["devices"][runtime] if device["name"].startswith(family) and device["isAvailable"]),
        None,
    )
    if simulator:
        print(simulator["udid"])
        break
else:
    raise SystemExit(f"No available {family} simulator was found")
