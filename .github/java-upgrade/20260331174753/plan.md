# Upgrade Plan: VideoGrab (20260331174753)

- **Generated**: 2026-03-31 17:49:00
- **HEAD Branch**: main
- **HEAD Commit ID**: f877f7d337b5b6e0de084be5c3f40716841f4008

## Available Tools

**JDKs**
- JDK 11.0.16: C:\Users\warun\.jdks\jbr_dcevm-11.0.16\bin (baseline verification in step 2)
- JDK 25.0.2: C:\Users\warun\.jdks\openjdk-25.0.2\bin (target runtime and final validation)

**Build Tools**
- Maven CLI 4.0.0-rc-4: C:\Users\warun\.maven\apache-maven-4.0.0-rc-4\bin\mvn.cmd

## Guidelines

- Upgrade Java runtime to the latest LTS version.

> Note: You can add any specific guidelines or constraints for the upgrade process here if needed, bullet points are preferred.

## Options

- Working branch: appmod/java-upgrade-20260331174753
- Run tests before and after the upgrade: true

## Upgrade Goals

- Upgrade Java runtime target from 11 to 25 (latest LTS).

### Technology Stack

| Technology/Dependency | Current | Min Compatible | Why Incompatible |
| --------------------- | ------- | -------------- | ---------------- |
| Java | 11 | 25 | User requested latest LTS target runtime. |
| Maven CLI | Not installed | 4.0+ | Java 25 workflow needs modern Maven and this project currently has no runnable Maven command. |
| Maven Wrapper | Not present | Optional | No wrapper to guarantee toolchain reproducibility, so CLI Maven must be installed and used. |
| maven-compiler-plugin | 3.13.0 | 3.13.0 | Already compatible with Java 25 source/release compilation. |
| maven-shade-plugin | 3.2.4 | 3.5.0 | Older shade plugin may be less reliable on latest JDK class formats. |
| org.glassfish:jakarta.json | 1.1.4 | 2.0+ | Legacy Jakarta JSON implementation line may have weaker compatibility posture on latest LTS JVMs. |

### Derived Upgrades

- Install a modern Maven CLI (`latest`) to enable build/test commands in this workspace and ensure Java 25-ready build tooling.
- Update project compilation target by changing `maven.compiler.release` from 11 to 25.
- Upgrade `maven-shade-plugin` to a newer stable version for packaging compatibility with modern bytecode.
- Upgrade `org.glassfish:jakarta.json` to a newer compatible line to reduce runtime/library compatibility risks on JDK 25.

## Upgrade Steps

- **Step 1: Setup Environment**
  - **Rationale**: Ensure required build tools are installed and usable before code changes.
  - **Changes to Make**:
    - [ ] Install latest Maven CLI in user space.
    - [ ] Verify JDK 25 path and Maven path are available for subsequent steps.
  - **Verification**:
    - Command: `mvn -v`
    - Expected: Maven and Java versions are printed successfully.

- **Step 2: Setup Baseline**
  - **Rationale**: Establish compile/test baseline before upgrading Java target.
  - **Changes to Make**:
    - [ ] Run baseline compile with JDK 11 and existing project configuration.
    - [ ] Run baseline tests with JDK 11 and capture pass/fail totals.
  - **Verification**:
    - Command: `mvn clean test-compile -q && mvn clean test -q`
    - JDK: C:\Users\warun\.jdks\jbr_dcevm-11.0.16\bin
    - Expected: Compile succeeds; test pass baseline recorded.

- **Step 3: Upgrade Java Target to 25**
  - **Rationale**: Apply the user-requested runtime target change with minimal source changes.
  - **Changes to Make**:
    - [ ] Update `pom.xml` property `maven.compiler.release` from `11` to `25`.
    - [ ] Upgrade `maven-shade-plugin` to a Java 25-friendly version.
    - [ ] Upgrade `org.glassfish:jakarta.json` to a current compatible version.
    - [ ] Fix compile issues, if any.
  - **Verification**:
    - Command: `mvn clean test-compile -q`
    - JDK: C:\Users\warun\.jdks\openjdk-25.0.2\bin
    - Expected: Main and test sources compile successfully.

- **Step 4: Final Validation**
  - **Rationale**: Confirm all upgrade goals are met with full build and test success criteria.
  - **Changes to Make**:
    - [ ] Validate final dependency and Java target versions in `pom.xml`.
    - [ ] Run clean full test suite on JDK 25.
    - [ ] Fix any remaining failures until 100% pass.
  - **Verification**:
    - Command: `mvn clean test -q`
    - JDK: C:\Users\warun\.jdks\openjdk-25.0.2\bin
    - Expected: Compilation success and 100% tests passing.

## Key Challenges

- **Missing Build Tool in Environment**
  - **Challenge**: Project has no Maven wrapper and `mvn` is unavailable.
  - **Strategy**: Install Maven first and pin command usage through explicit PATH in execution steps.
- **Latest LTS Jump (11 → 25)**
  - **Challenge**: Tooling and packaging plugins may fail on latest bytecode/runtime changes.
  - **Strategy**: Upgrade build plugin(s) likely affected by packaging/class handling and validate with clean test-compile.
