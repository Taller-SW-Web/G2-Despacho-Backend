# Java Runtime Upgrade Plan: G2 Despacho Backend

- **Generated**: 2026-11-24
- **Workspace**: `C:\Users\Usuario\Desktop\TallerSw\sistema-despacho`
- **Version control**: Unavailable; this workspace is not a Git repository.
- **Execution mode**: Automatic

## Upgrade Goal

Move the backend runtime and build target from Java 21 to Java 25, the latest LTS release as of this plan. Adopt the latest available Java 25 patch for local verification.

## Current Stack and Compatibility

| Component | Current | Target / action | Rationale |
|---|---|---|---|
| Java compiler target | 21 in three Maven modules | 25 | Requested runtime upgrade |
| Java CI runtime | 21 in four workflow jobs | 25 | Keep CI builds and standalone Java checks aligned |
| Container runtimes | Eclipse Temurin 21 in five Dockerfiles | Eclipse Temurin 25 | Keep packaged services and local Java helpers aligned |
| Spring Boot | 4.1.3 | Keep | Existing framework is compatible with Java 25; no framework migration is needed |
| Maven Wrapper | 3.10.0 in all three services | Keep | Current wrapper supports the target JDK; no wrapper change is needed |
| Compiler plugin / Lombok | Inherited Spring Boot plugin management | Keep unless Java 25 verification proves incompatibility | Avoid unnecessary dependency churn |

## Findings and Planned Changes

| File(s) | Current state | Planned change |
|---|---|---|
| `api-gateway/pom.xml`, `servicio-gestion-despachos/pom.xml`, `servicio-operacion-reparto/pom.xml` | `<java.version>21</java.version>` | Set the compiler/runtime target to 25 |
| `.github/workflows/ci.yaml` | Four CI jobs set up Java 21 | Set Java 25 and update corresponding step labels |
| `api-gateway/Dockerfile`, `servicio-gestion-despachos/Dockerfile`, `servicio-operacion-reparto/Dockerfile`, `seguridad-local/Dockerfile`, `ventas-local/Dockerfile`, `pruebas/compose.f02.yaml` | Build/test/runtime images use Temurin 21/Jammy | Move to Temurin 25/Noble JDK/JRE images |
| `README.md`, `docs/pruebas.md` | Current developer instructions specify Java 21 | Specify Java 25 |
| `../despacho-docs/overview.md`, `../despacho-docs/arquitectura/c4-contenedores.md` | Current backend stack documentation specifies Java 21 | Update the current stack description to Java 25 |
| `../despacho-docs/historias-usuario/F-05-MonitoreoFlotaCapacidad/HU-F05*.md` | Active story implementation criteria specify Java 21 | Specify Java 25 |
| Historical evidence and test reports | Record actual past Java 21 runs | Leave unchanged; these are historical evidence, not current runtime configuration |

No application source changes, dependency upgrades, or Spring/Jakarta migrations are anticipated. No Java 25-specific removed API usage was found in the initial configuration scan.

## Execution and Verification

1. Install and verify Temurin JDK 25 locally.
2. Update all Java compiler targets, CI setup, and Docker runtime/build images to Java 25.
3. Update current developer and architecture documentation; retain historical execution records.
4. Review the diff for completeness and scope.
5. Run `clean test-compile` for all three Maven services with JDK 25.
6. Run `clean verify` for all three services with JDK 25, preserving integration tests (which require Docker).
7. Baseline tests on the configured Java 21 runtime are skipped because JDK 21 is not installed. The pre-existing installed JDK is 23.0.2 and is not treated as a Java 21 baseline.

## Execution Notes

The standard upgrade orchestration tools were unavailable in this session. The JDK was obtained from the official Adoptium API/release and its SHA-256 checksum was verified before extraction. No version-control operations are possible in this workspace.

## Completed Verification

- JDK 25.0.5.1+2 installed at `C:\Users\Usuario\.jdk\temurin-25.0.5.1\jdk-25.0.5.1+2`; the downloaded archive matched the published SHA-256.
- `clean test-compile` succeeded for Gateway, Gestión, and Operación using JDK 25.
- `clean verify` succeeded for Gateway (**37 tests**), Gestión (**299 tests**), and Operación (**133 tests**): **469 tests total; zero failures, errors, or skips**.
- Docker compile stages succeeded for all three Maven services; Docker test stages succeeded for Seguridad Local and Ventas Local.
- `eclipse-temurin:25-jre-noble` ran `java -version` successfully; the F-02 Compose configuration validated.
- Java 21 baseline was not run because JDK 21 was not installed. An initial test attempt occurred before Docker Desktop was ready and errored on Testcontainers; all suites passed when rerun after Docker became available.
- The dedicated CVE assessment was not available through the upgrade tooling in this session; no dependency versions were changed as part of this Java-only upgrade.
