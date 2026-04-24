# DominoBlueprint Exporter

A Gradle-based Java command-line tool that exports HCL Domino design elements
from an existing database so they can be imported into a fresh copy with
[`DXLImport.jar`](../DXLImporter-Gradle-Demo/).

## Output structure

```
<outputDir>/
  forms/              – one .dxl file per form
  views/              – one .dxl file per view and folder
  shared/             – reusable design elements forms and views reference
    subforms/         – one .dxl file per subform
    fields/           – one .dxl file per shared field
    columns/          – one .dxl file per shared column
  code/               – agents, Script Libraries, shared actions
                        (Java agents and Java Script Libraries are excluded)
  resources/          – image, stylesheet, and file resources
                        (Java resources and XPages build artifacts are excluded)
  pages/              – pages, framesets, outlines, navigators
  other/              – database script, database icon, Help About / Help Using,
                        data connections, replication formulas, profile documents,
                        and anything else classified as misc design
```

Together these directories represent the full database **design** &mdash;
importing every file back into an empty database with `DXLImport.jar`
reproduces the original design. The ACL is **not** exported here and is
handled by a separate tool.

Each file is cleaned before being written:
- Database-replica attributes (`replicaid`, `path`, `title`, etc.) are removed
- `<databaseinfo>` is removed
- Note metadata (`<noteinfo>`, `<updatedby>`, `<wassignedby>`) is removed

The resulting files match the format of `dxl/HelloWorld.dxl` in the importer
project and can be imported directly with:

```bash
java -jar DXLImport.jar <server> <database> <file.dxl>
```

### Anything Java is excluded

Java agents, Java Script Libraries, and Java Resources all contain compiled
bytecode that does not round-trip reliably through `DxlImporter`. They are
detected and skipped in both `code/` and `resources/`. Compile and import
Java code separately using the Gradle build in `DXLImporter-Gradle-Demo/`.

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

Add an export step before your `Import` steps. Because `DXLImport.jar` accepts
a directory and walks it recursively, you can import the entire export tree in
one invocation:

```yaml
- name: Export design elements
  run: |
    PASSWORD=password java \
        -jar /local/notesjava/DominoBlueprintExporter.jar \
        $SERVER $SOURCE_DATABASE ./export 2>&1

- name: Import the full design
  run: |
    PASSWORD=password java -jar /local/notesjava/DXLImport.jar \
        $SERVER $TARGET_DATABASE ./export 2>&1
```

If you prefer per-category control, import each directory in order &mdash; forms
first so views/code/resources/pages can reference them:

```yaml
- name: Import design by category
  run: |
    for dir in forms views code resources pages other; do
      for f in ./export/$dir/*.dxl; do
        [ -f "$f" ] || continue
        PASSWORD=password java -jar /local/notesjava/DXLImport.jar \
            $SERVER $TARGET_DATABASE "$f" 2>&1
      done
    done
```

## Design note: excluded Java code

Java agents and Java Script Libraries contain a `<javaproject>` descendant in
their DXL. Java Resources export as `<javaresource>` elements holding
compiled `.class` data. In all three cases the exporter detects them and
skips them &mdash; Java code is imported separately using the Gradle build in
`DXLImporter-Gradle-Demo/`.

## Design note: excluded agent run-history

Every agent in a Domino database has an associated `<agentdata>` note that
stores the agent's last-run information, `$Signature`, and other runtime
state. These are not design &mdash; they are re-created automatically when
agents run in the target database &mdash; so the exporter filters them out.
A database with 200 agents would otherwise produce 200 `Agentdata_*.dxl`
files with no value.

## Design note: generic `<note class="…">` wrappers

For some design kinds (database icon, replication formulas, hidden file
resources used by XPages, …) `DxlExporter` emits a generic
`<note class="icon">` or `<note class="form">` wrapper rather than a
dedicated tag like `<dbicon>` or `<fileresource>`. The exporter reads the
`class` attribute to pick the right type suffix and falls back to the
`$FileNames` or `$TITLE` item for the filename, so these land with
descriptive names instead of `Note_2.dxl`, `Note_3.dxl`, etc.

## Design note: excluded private replication formulas

Domino stores one `<replicationformula>` note per user who has opened the
database with a local replica — these record each user's selective-replication
rules and are named after the user's canonical hierarchical name
(`CN=Jane Doe/OU=Dept/O=Acme`). Because they are per-user state, not shared
design, the exporter skips any replication formula whose name begins with
`CN=`. A database-wide replication formula (not named after a user) is kept.

## Design note: excluded XPages build artifacts

When a database contains XPages, Designer compiles them into an OSGi plugin
project and persists the build output as hidden file resources inside the
NSF. These regenerate on every rebuild and do not need to round-trip. The
exporter filters out any `<fileresource>` whose `$FileNames` path matches:

- anything under `WEB-INF/` (including `xsp.properties`, `faces-config.xml`,
  compiled `.class` files, generated Java sources)
- any hidden dotfile (`.classpath`, `.project`, `.settings/*`, …)
- the OSGi/PDE descriptors `plugin.xml`, `build.properties`, `feature.xml`,
  and `MANIFEST.MF`

A compiled `Activator.class` landing at `WEB-INF/classes/plugin/Activator.class`
slips past the `<javaresource>` filter because it's packaged as a plain file
resource, not a Java resource — the path-based filter catches it.

## Design note: shared design elements

Subforms, shared fields, and shared columns are design elements that multiple
forms and views can reference. Rather than burying them inside `forms/` and
`views/`, they are grouped under `shared/` in dedicated subdirectories
(`shared/subforms/`, `shared/fields/`, `shared/columns/`). This keeps the
top-level `forms/` and `views/` directories focused on the first-class design
containers and makes the shared-element counts easier to see at a glance.
Because `NoteCollection` exposes no `setSelectSharedColumns()`, shared columns
are collected alongside views (they share `NOTE_CLASS_VIEW`) and then routed
to `shared/columns/` based on the `<sharedcolumn>` tag emitted by
`DxlExporter`.

## Design note: ACL

The ACL is a DXL-exportable design note (via
`NoteCollection.setSelectACL(true)` or the `<acl>` element in a full-database
export) but it is intentionally left out of this tool. ACL import/export is
handled by a separate utility.

## Project structure

```
DominoBlueprintExporter/
├── build.gradle                    Gradle build (mirrors DXLImporter-Gradle-Demo)
├── gradle.properties.example       Template – copy to gradle.properties
├── run.sh                          Linux launcher – discovers Domino JARs automatically
├── LICENSE.md                      Server Side Public License v1
├── src/main/java/net/prominic/dominoblueprint/
│   ├── DominoBlueprintExporter.java  CLI entry point & argument parsing
│   ├── DesignExporter.java           Export orchestrator
│   │                                 (forms / views / code / resources / pages / other)
│   └── DxlProcessor.java             XML split, clean, and Java detection
└── README.md
```
