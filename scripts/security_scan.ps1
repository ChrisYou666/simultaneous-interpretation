Write-Host "=== Running Security Scans ===" -ForegroundColor Cyan
Write-Host ""

Write-Host "[1/3] Running SpotBugs..." -ForegroundColor Yellow
Set-Location backend-java
mvn spotbugs:check spotbugs:xml -q
Write-Host "SpotBugs complete" -ForegroundColor Green

Write-Host "[2/3] Running OWASP Dependency-Check..." -ForegroundColor Yellow
mvn org.owasp:dependency-check-maven:check -q
Write-Host "OWASP complete" -ForegroundColor Green

Write-Host "[3/3] Running npm audit..." -ForegroundColor Yellow
Set-Location ..
npm audit --audit-level=moderate
Write-Host "npm audit complete" -ForegroundColor Green

Write-Host ""
Write-Host "=== All Security Scans Passed ===" -ForegroundColor Green
