"""Pick an available iPhone from the newest installed iOS simulator runtime."""
import json
import subprocess

data = json.loads(subprocess.check_output(["xcrun", "simctl", "list", "devices", "available", "--json"]))
for runtime in sorted(data["devices"], key=lambda s: [int(n) for n in s.split("iOS-")[-1].split("-")] if "iOS-" in s else [], reverse=True):
    if "iOS-" not in runtime:
        continue
    phone = next((device for device in data["devices"][runtime] if device["name"].startswith("iPhone") and device["isAvailable"]), None)
    if phone:
        print(phone["udid"])
        break
else:
    raise SystemExit("No available iPhone simulator was found")
