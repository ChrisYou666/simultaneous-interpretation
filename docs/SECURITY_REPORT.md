# Security Scan Report

This document tracks security scan results for the Simultaneous Interpretation project.

## Overview

This project uses multiple security scanning tools:

1. **SpotBugs** - Static analysis for Java bytecode
2. **OWASP Dependency-Check** - Vulnerability scanning for dependencies
3. **npm audit** - Frontend JavaScript/Node.js vulnerability scanning

## Running Security Scans

### Local Development

**Bash (Linux/macOS/WSL):**
```bash
./scripts/security_scan.sh
```

**PowerShell (Windows):**
```powershell
.\scripts\security_scan.ps1
```

### Individual Scans

**SpotBugs (Java):**
```bash
cd backend-java
mvn spotbugs:check
mvn spotbugs:xml  # Generate XML report
```

**OWASP Dependency-Check:**
```bash
cd backend-java
mvn org.owasp:dependency-check-maven:check
```

**npm audit (Frontend):**
```bash
npm audit --audit-level=moderate
```

## Understanding Reports

### SpotBugs XML Report

Location: `backend-java/target/spotbugsXml.xml`

**Common Bug Patterns:**

| Pattern | Severity | Description |
|---------|----------|-------------|
| EI_EXPOSE_REP | Medium | May expose internal representation by returning reference to mutable object |
| EI_EXPOSE_REP2 | Medium | May expose internal representation by incorporating reference to mutable object |
| SQL_INJECTION | High | Database query built from user-controlled sources |
| XSS | High | Cross-site scripting vulnerability |
| XXE | High | XML External Entity attack |

**Configuration:**

The SpotBugsExcludeFilter.xml excludes:
- Lombok-generated code (EI_EXPOSE_REP patterns)
- Test classes
- Generated source packages

### OWASP Dependency-Check Report

Location: `backend-java/target/dependency-check-report.html`

**CVSS Scoring:**

| Score | Severity | Action Required |
|-------|----------|-----------------|
| 9.0-10.0 | Critical | Fix immediately |
| 7.0-8.9 | High | Fix within 7 days |
| 4.0-6.9 | Medium | Fix within 30 days |
| 0.1-3.9 | Low | Monitor |

**Configuration:**

- `failBuildOnCVSS=7`: Build fails if vulnerabilities with CVSS >= 7 are found
- Suppressions are configured in `dependency-check-suppression.xml`

### npm audit Results

**Severity Levels:**

| Level | Action |
|-------|--------|
| critical | Fix immediately |
| high | Fix within 24 hours |
| moderate | Fix within 7 days |
| low | Fix within 30 days |

## Known Issues and Mitigations

### Current Suppressions

| Suppression | Reason | Review Date |
|-------------|--------|-------------|
| Lombok EI_EXPOSE_REP | False positive - Lombok generates defensive copies |
| Development dependencies | Not included in production builds |

### Resolved Issues

| Issue ID | Tool | Description | Resolution Date |
|----------|------|-------------|-----------------|
| - | - | - | - |

## CI/CD Integration

Security scans run automatically:

1. **On Push**: Main and develop branches
2. **On Pull Request**: All PRs targeting main/develop
3. **Weekly Schedule**: Sunday at 3 AM UTC

### GitHub Actions Artifacts

Scan results are uploaded as artifacts:
- `spotbugs-results`: SpotBugs XML output
- `owasp-results`: OWASP HTML report

## Fixing Common Findings

### For SpotBugs

**EI_EXPOSE_REP / EI_EXPOSE_REP2:**
```java
// Bad
public class User {
    private List<String> names;
    public List<String> getNames() { return names; }
}

// Good
public class User {
    private List<String> names;
    public List<String> getNames() {
        return Collections.unmodifiableList(names);
    }
    // Or make a copy
    public List<String> getNames() {
        return new ArrayList<>(names);
    }
}
```

**SQL Injection:**
```java
// Bad
String query = "SELECT * FROM users WHERE name = '" + userInput + "'";

// Good - Use parameterized queries
String query = "SELECT * FROM users WHERE name = ?";
PreparedStatement ps = connection.prepareStatement(query);
ps.setString(1, userInput);
```

### For OWASP Dependency-Check

1. Update the vulnerable dependency to a patched version
2. If no patch is available, add a suppression with justification
3. Monitor for new releases that address the vulnerability

### For npm audit

1. Run `npm audit fix` for auto-fixable issues
2. Update packages to non-vulnerable versions
3. If no fix available, use `npm audit --production` to ignore dev dependencies

## Report Template

```markdown
## Security Scan Report - [DATE]

### SpotBugs Results
- **Issues Found**: [NUMBER]
- **High**: [COUNT]
- **Medium**: [COUNT]
- **Low**: [COUNT]

### OWASP Dependency-Check Results
- **Vulnerabilities Found**: [NUMBER]
- **Critical**: [COUNT]
- **High**: [COUNT]
- **Medium**: [COUNT]

### npm audit Results
- **Vulnerabilities Found**: [NUMBER]
- **Critical**: [COUNT]
- **High**: [COUNT]
- **Moderate**: [COUNT]

### Action Items
1. [ITEM]
2. [ITEM]

### Sign-off
Reviewed by: _______________
Date: _______________
```

## Additional Resources

- [SpotBugs Documentation](https://spotbugs.readthedocs.io/)
- [OWASP Dependency-Check](https://jeremylong.github.io/DependencyCheck/dependency-check-maven/)
- [npm audit](https://docs.npmjs.com/cli/v10/commands/npm-audit)
