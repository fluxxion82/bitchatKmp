#!/bin/bash
# Helper script to display noise module test output

echo "Running noise tests and displaying output..."
./gradlew :data:noise:iosSimulatorArm64Test --rerun-tasks > /dev/null 2>&1

echo ""
echo "========================================="
echo "NOISE MODULE TEST OUTPUT"
echo "========================================="
echo ""

python3 << 'PYTHON'
import xml.etree.ElementTree as ET
import os

test_file = 'data/noise/build/test-results/iosSimulatorArm64Test/TEST-com.bitchat.noise.NoiseSessionNativeTest.xml'

if os.path.exists(test_file):
    tree = ET.parse(test_file)
    root = tree.getroot()
    
    # Get test summary
    tests = root.get('tests', '?')
    failures = root.get('failures', '?')
    
    print(f"Tests: {tests} | Failures: {failures}\n")
    
    # Extract system-out
    for elem in root.findall('.//system-out'):
        if elem.text:
            print(elem.text.strip())
else:
    print(f"Test file not found: {test_file}")
PYTHON
