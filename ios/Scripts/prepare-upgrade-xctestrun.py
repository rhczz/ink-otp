#!/usr/bin/env python3
"""Point a standard simulator test run at the release app for this phase."""
import plistlib
import sys
from pathlib import Path

TARGET = "InkOTPUpgradeUITests"
APP = "InkOTP.app"


def prepare(product_dir: Path, app: Path) -> Path:
    files = list(product_dir.glob("InkOTPUpgrade_*.xctestrun"))
    if len(files) != 1:
        raise SystemExit("Expected one xctestrun for the InkOTPUpgrade scheme")
    if app.name != APP or not app.is_dir():
        raise SystemExit("Expected a simulator release app for the upgrade phase")

    plan = plistlib.loads(files[0].read_bytes())
    if "TestConfigurations" in plan:
        targets = [target for config in plan["TestConfigurations"] for target in config["TestTargets"]]
        if len(targets) != 1 or targets[0].get("BlueprintName") != TARGET:
            raise SystemExit("Expected one upgrade UI test target in the test plan")
        target = targets[0]
    else:
        if set(plan).difference({"__xctestrun_metadata__"}) != {TARGET}:
            raise SystemExit("Expected one upgrade UI test target in the test run")
        target = plan[TARGET]

    debug_app = Path(target["UITargetAppPath"].replace("__TESTROOT__", str(product_dir)))
    if debug_app != product_dir / "Debug-iphonesimulator" / APP:
        raise SystemExit("Unexpected app product path in the generated upgrade test run")

    def phase_product(path: str) -> str:
        absolute = Path(path.replace("__TESTROOT__", str(product_dir)))
        if absolute.is_relative_to(debug_app):
            return str(app / absolute.relative_to(debug_app))
        return path

    target["UITargetAppPath"] = str(app)
    target["DependentProductPaths"] = [phase_product(path) for path in target.get("DependentProductPaths", [])]
    target["ParallelizationEnabled"] = False
    output = product_dir / "upgrade-release-app.xctestrun"
    output.write_bytes(plistlib.dumps(plan))
    return output


if __name__ == "__main__":
    print(prepare(Path(sys.argv[1]).resolve(), Path(sys.argv[2]).resolve()))
