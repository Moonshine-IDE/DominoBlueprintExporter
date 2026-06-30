# DominoBlueprint

A Gradle-based Java command-line toolkit for the HCL Domino design round-trip.
A single `DominoBlueprint.jar` exposes three subcommands:

- `export`   – export a database's design to a tree of DXL files
- `createdb` – create a blank target database (no views) ready for an import
- `import`   – import a DXL file, or a full blueprint tree, into a database

```bash
java -jar DominoBlueprint.jar export   -d apps/mydb.nsf -o ./export
java -jar DominoBlueprint.jar createdb -d apps/restored.nsf
java -jar DominoBlueprint.jar import   -d apps/restored.nsf -i ./export
```

All subcommands share the same flags (`-s/--server`, `-d/--database`,
`-p/--password`) and the same password resolution; run
`java -jar DominoBlueprint.jar <subcommand> --help` for the per-subcommand options.

## Requirements

| Dependency | Version | Notes |
|------------|---------|-------|
| JDK | **8** | The build targets Java 8 (`sourceCompatibility`/`targetCompatibility = 1.8`) and must be compiled with JDK 8 until HCL updates the Domino API for newer Java releases. |
| Gradle | **7.5.1** | Use the bundled wrapper (`./gradlew`), which pins this version. Newer Gradle (8.x / 9.x) is **not** supported by the current build (Shadow plugin 7.1.2 and the `archivesBaseName` / `mainClassName` conventions). |
| HCL Notes / Domino | installed locally | Needed to compile against `Notes.jar`; set `notesInstallation` in `gradle.properties`. The licensed Domino JARs are **not** bundled (see Building). |

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
  acl/                – database ACL (acl.dxl), applied on import per --acl-import
```

Together these directories represent the full database **design** &mdash;
importing the tree back into an empty database with the `import` subcommand
reproduces the original design.

Each file is cleaned before being written:
- Database-replica attributes (`replicaid`, `path`, `title`, etc.) are removed
- `<databaseinfo>` is removed
- Note metadata (`<noteinfo>`, `<updatedby>`, `<wassignedby>`) is removed

The resulting files can be imported directly with:

```bash
java -jar DominoBlueprint.jar import -d <database> -i <file-or-directory>
```

### Anything Java is excluded

Java agents, Java Script Libraries, and Java Resources all contain compiled
bytecode that does not round-trip reliably through `DxlImporter`. They are
detected and skipped in both `code/` and `resources/`. Compile and import
Java code separately using the Gradle build in `DXLImporter-Gradle-Demo/`.

## Building

The only build-time requirement is Notes/Domino on the build machine
(needed to compile against `Notes.jar`).  The licensed HCL Domino JARs
(`Notes.jar`, `websvc.jar`, `lwpd.*`) are **not** bundled into the jar — they
are supplied at runtime via the jar's manifest `Class-Path`, which lists each
JAR both at its detected absolute install path and as `./<name>`.  The Domino
*native* libraries still need to be on `LD_LIBRARY_PATH` at runtime (see
deployment options below).

```bash
cp gradle.properties.example gradle.properties
# edit gradle.properties → set notesInstallation for your platform

./gradlew shadowJar       # or: ./gradlew build
# → build/libs/DominoBlueprint.jar
```

`./gradlew shadowJar` never requires `-PdbName` or any other runtime flag —
those are only needed by the `runExporter` / `runCreateDb` / `runImport` tasks.

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
The password **must** be supplied via `-PnotesIDPassword` or the `PASSWORD`
environment variable (or omitted entirely if the ID has no password).

```bash
# export
PASSWORD=secret ./gradlew \
       -PnotesInstallation=/Applications/HCL\ Notes.app/Contents/MacOS/ \
       -Pserver="myserver/Org" \
       -PdbName=apps/mydb.nsf \
       -PoutputDir=./export \
       runExporter

# createdb (blank target database)
PASSWORD=secret ./gradlew -Pserver="myserver/Org" -PdbName=apps/restored.nsf runCreateDb

# import a blueprint tree (optionally -PaclImport=<mode>)
PASSWORD=secret ./gradlew -Pserver="myserver/Org" -PdbName=apps/restored.nsf \
       -PdxlInput=./export runImport
```

## Deploying to a Linux Domino server

The jar contains only this project's own bytecode; the licensed Domino JARs are
resolved at runtime via the manifest `Class-Path`.  That `Class-Path` bakes in
the **absolute** Domino install path detected on the *build* machine, plus a
relative `./<name>` entry for each JAR.  So when deploying to a different
machine, place `DominoBlueprint.jar` next to the Domino JARs (Option B) so the
`./<name>` entries resolve — or rebuild on the target server.

### Option A — `run.sh` (recommended)

`run.sh` auto-discovers the Domino installation and sets `LD_LIBRARY_PATH`:

```bash
# Copy both files to the server (place next to the Domino JARs so Class-Path resolves)
scp build/libs/DominoBlueprint.jar run.sh user@domino-server:/local/notesjava/

# On the server
chmod +x /local/notesjava/run.sh
PASSWORD=secret /local/notesjava/run.sh export -s "myserver/Org" -d apps/mydb.nsf -o ./export
```

If Domino is in a non-standard location, set `DOMINO_INSTALL`:

```bash
DOMINO_INSTALL=/opt/hcl/domino/notes/12.0.2/linux \
PASSWORD=secret \
/local/notesjava/run.sh export -s "myserver/Org" -d apps/mydb.nsf -o ./export
```

### Option B — alongside the Domino JARs

Place `DominoBlueprint.jar` in the same directory as `Notes.jar` so the
`./<name>` Class-Path entries resolve:

```bash
cp build/libs/DominoBlueprint.jar /local/notesjava/   # Notes.jar already lives here

PASSWORD=password java -jar /local/notesjava/DominoBlueprint.jar \
    export -s "$SERVER" -d "$DATABASE" -o ./export 2>&1
```

### Option C — explicit classpath

```bash
DOMINO=/opt/hcl/domino/notes/latest/linux
java -cp /local/notesjava/DominoBlueprint.jar:$DOMINO/Notes.jar \
     -Djava.library.path=$DOMINO \
     net.prominic.dominoblueprint.DominoBlueprint \
     export -s "$SERVER" -d "$DATABASE" -o ./export
```

## GitHub Actions integration

Because the `import` subcommand accepts a directory and walks it recursively,
you can export and re-import an entire design tree in one invocation each:

```yaml
- name: Export design elements
  run: |
    PASSWORD=password java -jar /local/notesjava/DominoBlueprint.jar \
        export -s "$SERVER" -d "$SOURCE_DATABASE" -o ./export 2>&1

- name: Create the target database
  run: |
    PASSWORD=password java -jar /local/notesjava/DominoBlueprint.jar \
        createdb -s "$SERVER" -d "$TARGET_DATABASE" 2>&1

- name: Import the full design
  run: |
    PASSWORD=password java -jar /local/notesjava/DominoBlueprint.jar \
        import -s "$SERVER" -d "$TARGET_DATABASE" -i ./export 2>&1
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
have to be collected via `setSelectMiscIndexElements(true)` — `setSelectViews`
does *not* pick them up despite shared columns sharing `NOTE_CLASS_VIEW` with
views, because Domino filters by design flag (the shared-column flag `=` is
excluded from the views select). They are emitted by `DxlExporter` as
`<sharedcolumn>` elements during the `exportOther()` pass and routed from there
to `shared/columns/`.

## Design note: ACL

The ACL is a DXL-exportable design note (via
`NoteCollection.setSelectACL(true)` or the `<acl>` element in a full-database
export) but it is intentionally left out of this tool. ACL import/export is
handled by a separate utility.

## Project structure

```
DominoBlueprint/
├── build.gradle                    Gradle build (Shadow jar; Domino JARs via Class-Path)
├── gradle.properties.example       Template – copy to gradle.properties
├── run.sh                          Linux launcher – discovers Domino native libs automatically
├── LICENSE.md                      Server Side Public License v1
├── src/main/java/net/prominic/dominoblueprint/
│   ├── DominoBlueprint.java         CLI entry point – subcommand dispatch, auth, session
│   ├── DominoBlueprintExporter.java Export work method
│   ├── DominoBlueprintImport.java   Import work methods (single file + recursive tree)
│   ├── CreateDatabase.java          Create a blank target database (no default view)
│   ├── DesignExporter.java          Export orchestrator
│   │                                (forms / views / code / resources / pages / other)
│   └── DxlProcessor.java            XML split, clean, and Java detection
└── README.md
```
