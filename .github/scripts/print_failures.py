"""Prints failed device tests (message and stack trace) from JUnit XML results, so they are readable
in the job log without downloading the artifact."""
import glob
import sys
import xml.etree.ElementTree as ET

root = sys.argv[1]
files = glob.glob(f"{root}/**/*.xml", recursive=True)
if not files:
    print(f"No test result XML under {root}")
for path in files:
    suite = ET.parse(path).getroot()
    for case in suite.iter("testcase"):
        for failure in list(case.findall("failure")) + list(case.findall("error")):
            print(f"=== FAILED {case.get('classname')}.{case.get('name')} ({case.get('time')}s)")
            print((failure.text or failure.get("message") or "").strip()[:6000])
            print()
    print(f"{path}: tests={suite.get('tests')} failures={suite.get('failures')} errors={suite.get('errors')}")
