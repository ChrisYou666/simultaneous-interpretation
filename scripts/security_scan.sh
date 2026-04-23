#!/bin/bash
set -e

echo "=== Running Security Scans ==="
echo ""

echo "[1/3] Running SpotBugs..."
cd backend-java
mvn spotbugs:check spotbugs:xml -q
echo "SpotBugs complete"

echo "[2/3] Running OWASP Dependency-Check..."
mvn org.owasp:dependency-check-maven:check -q
echo "OWASP complete"

echo "[3/3] Running npm audit..."
cd ..
npm audit --audit-level=moderate
echo "npm audit complete"

echo ""
echo "=== All Security Scans Passed ==="
