#!/usr/bin/env python3
"""Run the upgrade tests against preinstalled simulator apps."""
import plistlib
import sys
from pathlib import Path

TARGET = "InkOTPUpgradeUITests"
APP_IDENTIFIER = "io.github.rhczz.inkotp"


def prepare(product_dir: Path) -> Path:
    files = list(product_dir.glob("InkOTPUpgrade_*.xctestrun"))
    if len(files) != 1:
        raise SystemExit("Expected one xctestrun for the InkOTPUpgrade scheme")

    source = files[0]
    plan = plistlib.loads(source.read_bytes())
    if "TestConfigurations" in plan:
        targets = [target for config in plan["TestConfigurations"] for target in config["TestTargets"]]
        if len(targets) != 1 or targets[0].get("BlueprintName") != TARGET:
            raise SystemExit("Expected one upgrade UI test target in the test plan")
        target = targets[0]
    else:
        if set(plan).difference({"__xctestrun_metadata__"}) != {TARGET}:
            raise SystemExit("Expected one upgrade UI test target in the test run")
        target = plan[TARGET]

    host = Path(target.pop("TestHostPath").replace("__TESTROOT__", str(product_dir)))
    bundle = Path(target.pop("TestBundlePath").replace("__TESTHOST__", str(host)).replace("__TESTROOT__", str(product_dir)))
    if host.name != f"{TARGET}-Runner.app" or not bundle.is_dir():
        raise SystemExit("Expected the standalone upgrade UI test runner product")

    metadata = plistlib.loads((host / "Info.plist").read_bytes())
    host_identifier = metadata["CFBundleIdentifier"]
    if host_identifier == APP_IDENTIFIER:
        raise SystemExit("Upgrade test runner must have a separate bundle identifier")

    for key in ("UITargetAppPath", "DependentProductPaths"):
        target.pop(key, None)
    target.update({
        "UseDestinationArtifacts": True,
        "TestHostBundleIdentifier": host_identifier,
        "TestBundleDestinationRelativePath": "__TESTHOST__/" + bundle.relative_to(host).as_posix(),
        "UITargetAppBundleIdentifier": APP_IDENTIFIER,
        "ParallelizationEnabled": False,
    })
    (product_dir / "upgrade-preinstalled.xctestrun").write_bytes(plistlib.dumps(plan))
    return host


if __name__ == "__main__":
    print(prepare(Path(sys.argv[1]).resolve()))
