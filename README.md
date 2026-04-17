# DominoBlueprint Exporter

A Gradle-based Java command-line tool that exports HCL Domino design elements
from an existing database so they can be imported into a fresh copy with
[`DXLImport.jar`](../DXLImporter-Gradle-Demo/).

## Output structure

```
<outputDir>/
  forms/   – one .dxl file per form, subform, and shared field
  views/   – one .dxl file per view, folder, and shared column
  code/    – one .dxl file per agent and Script Library
             (Java agents and Java Script Libraries are excluded)
```

Each file is cleaned before being written:
- Database-replica attributes (`replicaid`, `path`, `title`, etc.) are removed
- `<databaseinfo>` is removed
- Note metadata (`<noteinfo>`, `<updatedby>`, `<wassignedby>`) is removed

The resulting files match the format of `dxl/HelloWorld.dxl` in the importer
project and can be imported directly with:

```bash
java -jar DXLImport.jar <server> <database> <file.dxl>
```

## Building

The only build-time requirement is Notes/Domino on the build machine
(needed to compile against `Notes.jar`).  The output is a **fat JAR** —
`Notes.jar` and the other Domino JARs are bundled inside, so the JAR is
self-contained at the Java level.  The Domino *native* libraries still need to
be on `LD_LIBRARY_PATH` at runtime (see deployment options below).

```bash
cp gradle.properties.example gradle.properties
# edit gradle.properties → set notesInstallation for your platform

gradle shadowJar          # or: gradle build
# → build/libs/DominoBlueprintExporter.jar  (fat JAR, ~30 MB)
```

`gradle shadowJar` never requires `-PdbName` or any other runtime flag —
those are only needed by the `runExporter` Gradle task.

## Password

The password is resolved in this order, so you never have to type it in
plaintext on the command line unless you choose to:

| Priority | Source | Notes |
|----------|--------|-------|
| 1 | `--password` flag | Visible in shell history and `ps` output — avoid for sensitive IDs |
| 2 | `PASSWORD` env var | Recommended for automation and CI |
| 3 | Interactive prompt | Hidden input via `Console.readPassword()` — used when running manually |
| 4 | No password | ID has no password, or Notes already has an open session |

## Running via Gradle (same machine as Notes)

Gradle has no real TTY, so interactive password prompting is not possible.
The password **must** be supplied via one of these two methods:

```bash
# Option A: -PnotesIDPassword flag
gradle -PnotesInstallation=/Applications/HCL\ Notes.app/Contents/MacOS/ \
       -PnotesIDPassword=secret \
       -Pserver="myserver/Org" \
       -PdbName=apps/mydb.nsf \
       -PoutputDir=./export \
       runExporter

# Option B: PASSWORD environment variable (keeps credentials out of the command line)
PASSWORD=secret gradle \
       -PnotesInstallation=/Applications/HCL\ Notes.app/Contents/MacOS/ \
       -Pserver="myserver/Org" \
       -PdbName=apps/mydb.nsf \
       -PoutputDir=./export \
       runExporter
```

If the Notes ID has no password, omit both — the task will still work.

## Deploying to a Linux Domino server

The JAR is plain Java bytecode — build it once on any platform and copy it
to the target server.  There are no Maven/external dependencies to bundle;
the only runtime requirement is the Domino JARs already present on the server.

### Option A — `run.sh` (recommended)

`run.sh` auto-discovers the Domino installation and sets `LD_LIBRARY_PATH`:

```bash
# Copy both files to the server
scp build/libs/DominoBlueprintExporter.jar run.sh user@domino-server:/local/notesjava/

# On the server
chmod +x /local/notesjava/run.sh
PASSWORD=secret /local/notesjava/run.sh "myserver/Org" apps/mydb.nsf ./export
```

If Domino is in a non-standard location, set `DOMINO_INSTALL`:

```bash
DOMINO_INSTALL=/opt/hcl/domino/notes/12.0.2/linux \
PASSWORD=secret \
/local/notesjava/run.sh "myserver/Org" apps/mydb.nsf ./export
```

### Option B — alongside Notes.jar (same pattern as DXLImport.jar)

Place `DominoBlueprintExporter.jar` in the same directory as the Domino JARs:

```bash
cp build/libs/DominoBlueprintExporter.jar /local/notesjava/   # Notes.jar already lives here

# Identical invocation pattern to DXLImport.jar
PASSWORD=password java -jar /local/notesjava/DominoBlueprintExporter.jar \
    $SERVER $DATABASE ./export 2>&1
```

### Option C — explicit classpath

```bash
DOMINO=/opt/hcl/domino/notes/latest/linux
java -cp /local/notesjava/DominoBlueprintExporter.jar:$DOMINO/Notes.jar \
     -Djava.library.path=$DOMINO \
     net.prominic.dominoblueprint.DominoBlueprintExporter \
     "$SERVER" "$DATABASE" ./export
```

## GitHub Actions integration

Add an export step before your `Import` steps:

```yaml
- name: Export design elements
  run: |
    PASSWORD=password java \
        -jar /local/notesjava/DominoBlueprintExporter.jar \
        $SERVER $SOURCE_DATABASE ./export 2>&1

- name: Import forms
  run: |
    for f in ./export/forms/*.dxl; do
      PASSWORD=password java -jar /local/notesjava/DXLImport.jar \
          $SERVER $TARGET_DATABASE "$f" 2>&1
    done

- name: Import views
  run: |
    for f in ./export/views/*.dxl; do
      PASSWORD=password java -jar /local/notesjava/DXLImport.jar \
          $SERVER $TARGET_DATABASE "$f" 2>&1
    done

- name: Import code
  run: |
    for f in ./export/code/*.dxl; do
      PASSWORD=password java -jar /local/notesjava/DXLImport.jar \
          $SERVER $TARGET_DATABASE "$f" 2>&1
    done
```

## Design note: excluded Java code

Java agents and Java Script Libraries contain a `<javaproject>` element in
their DXL.  The exporter detects this and writes them to `code/` only if they
are *not* Java.  Pure Java code should be compiled and imported separately
using the Gradle build in `DXLImporter-Gradle-Demo/`.

## Project structure

```
DominoBlueprintExporter/
├── build.gradle                    Gradle build (mirrors DXLImporter-Gradle-Demo)
├── gradle.properties.example       Template – copy to gradle.properties
├── run.sh                          Linux launcher – discovers Domino JARs automatically
├── LICENSE.md                      Server Side Public License v1
├── src/main/java/net/prominic/dominoblueprint/
│   ├── DominoBlueprintExporter.java  CLI entry point & argument parsing
│   ├── DesignExporter.java           Export orchestrator (forms / views / code)
│   └── DxlProcessor.java             XML split, clean, and Java detection
└── README.md
```
