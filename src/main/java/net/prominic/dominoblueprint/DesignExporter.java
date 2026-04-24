package net.prominic.dominoblueprint;

import lotus.domino.Database;
import lotus.domino.DxlExporter;
import lotus.domino.NoteCollection;
import lotus.domino.Session;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Orchestrates the export of design elements from a Domino database.
 *
 * <p>Seven categories are exported, each to its own sub-directory:
 * <ul>
 *   <li><b>forms/</b>     – {@code <form>}</li>
 *   <li><b>views/</b>     – {@code <view>}, {@code <folder>}</li>
 *   <li><b>shared/</b>    – Reusable design elements that multiple forms/views can
 *                           reference:
 *                           {@code shared/subforms/} ({@code <subform>}),
 *                           {@code shared/fields/} ({@code <sharedfield>}),
 *                           {@code shared/columns/} ({@code <sharedcolumn>}).</li>
 *   <li><b>code/</b>      – {@code <agent>}, {@code <scriptlibrary>},
 *                           {@code <sharedactions>} (Java agents/libraries are skipped)</li>
 *   <li><b>resources/</b> – {@code <imageresource>}, {@code <stylesheetresource>},
 *                           {@code <fileresource>} (Java resources are skipped)</li>
 *   <li><b>pages/</b>     – {@code <page>}, {@code <frameset>}, {@code <outline>},
 *                           {@code <navigator>}</li>
 *   <li><b>other/</b>     – Database script/icon, Help About/Using, data connections,
 *                           replication formulas, profile documents, misc design notes</li>
 * </ul>
 *
 * <p>Anything Java &mdash; Java agents, Java Script Libraries, and Java Resources
 * &mdash; is always excluded from the export. Compiled Java code does not round-trip
 * reliably through {@code DxlImporter}; import it separately using the Gradle build
 * in {@code DXLImporter-Gradle-Demo/}.
 *
 * <p>Two additional noise categories are filtered out by {@link DxlProcessor} and
 * skipped automatically:
 * <ul>
 *   <li><b>Per-user private replication formulas</b> &mdash; one is stored per user
 *       who has a local replica of the DB; named after the user's canonical name
 *       ({@code CN=…/O=…}). Not design.</li>
 *   <li><b>XPages build artifacts</b> &mdash; file resources under {@code WEB-INF/},
 *       Eclipse dotfiles ({@code .classpath}, {@code .project}, {@code .settings/*}),
 *       and PDE/OSGi descriptors ({@code plugin.xml}, {@code build.properties},
 *       {@code feature.xml}, {@code MANIFEST.MF}). Regenerated on rebuild.</li>
 * </ul>
 *
 * <p>The ACL is <b>not</b> exported here. It can be exported as DXL via
 * {@code NoteCollection.setSelectACL(true)} but is handled by a separate tool.
 *
 * <p>Each design element is written to its own {@code .dxl} file. The DXL is
 * cleaned before writing: database-specific attributes ({@code replicaid},
 * {@code path}, {@code title}, etc.) and note metadata ({@code <noteinfo>},
 * {@code <updatedby>}, {@code <wassignedby>}) are removed so the files can be
 * imported cleanly into a fresh database with
 * {@code java -jar DXLImport.jar <server> <database> <file.dxl>}.
 */
public class DesignExporter {

    private final Session session;
    private final Database db;
    private final File outputDir;

    /**
     * Create an exporter for the given database.
     *
     * @param session   Active Domino session
     * @param db        The database to export from (must be open)
     * @param outputDir Root output directory; sub-directories will be created as needed
     */
    public DesignExporter(Session session, Database db, String outputDir) {
        this.session   = session;
        this.db        = db;
        this.outputDir = new File(outputDir);
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Export every design category in order:
     * forms → views → code → resources → pages → other.
     *
     * <p>{@code exportForms} and {@code exportViews} also populate
     * {@code shared/subforms/}, {@code shared/fields/}, and
     * {@code shared/columns/} for reusable design elements.
     */
    public void exportAll() throws Exception {
        exportForms();
        exportViews();
        exportCode();
        exportResources();
        exportPages();
        exportOther();
    }

    /**
     * Export forms to {@code <outputDir>/forms/}, routing subforms and shared
     * fields into {@code <outputDir>/shared/subforms/} and
     * {@code <outputDir>/shared/fields/} respectively.
     *
     * <p>{@code setSelectSubforms} and {@code setSelectSharedFields} are used
     * on the same {@link NoteCollection} because they share note-class
     * selection with forms; routing to different directories is applied after
     * the fact based on the element's DXL tag name.
     */
    public void exportForms() throws Exception {
        File formsDir      = mkdirs("forms");
        File subformsDir   = mkdirs("shared/subforms");
        File sharedFldsDir = mkdirs("shared/fields");
        System.out.println("=== Exporting Forms ===");

        NoteCollection nc = db.createNoteCollection(false);
        nc.setSelectForms(true);
        nc.setSelectSubforms(true);
        nc.setSelectSharedFields(true);
        nc.buildCollection();

        Map<String, File> routes = new HashMap<>();
        routes.put("subform",     subformsDir);
        routes.put("sharedfield", sharedFldsDir);

        exportCollection(nc, formsDir, /* skipJava= */ false, routes, null);
        nc.recycle();
    }

    /**
     * Export views and folders to {@code <outputDir>/views/}, routing shared
     * columns into {@code <outputDir>/shared/columns/}.
     *
     * <p>Note: there is no {@code setSelectSharedColumns()} in the standard
     * {@code NoteCollection} API.  Shared columns share the same note class as
     * views ({@code NOTE_CLASS_VIEW}), so they are automatically included when
     * {@code setSelectViews(true)} is set.  {@code DxlExporter} outputs
     * {@code <sharedcolumn>} elements (rather than {@code <view>}) for them
     * based on each note's design flags &mdash; routing applied below sends
     * them to {@code shared/columns/}.
     */
    public void exportViews() throws Exception {
        File viewsDir   = mkdirs("views");
        File columnsDir = mkdirs("shared/columns");
        System.out.println("=== Exporting Views ===");

        NoteCollection nc = db.createNoteCollection(false);
        nc.setSelectViews(true);    // also captures shared columns (same note class)
        nc.setSelectFolders(true);
        nc.buildCollection();

        Map<String, File> routes = new HashMap<>();
        routes.put("sharedcolumn", columnsDir);

        exportCollection(nc, viewsDir, /* skipJava= */ false, routes, null);
        nc.recycle();
    }

    /**
     * Export agents, script libraries, and shared actions to {@code <outputDir>/code/}.
     * Java agents and Java script libraries are excluded.
     *
     * <p>Shared actions are selected via {@code setSelectActions(true)} (the singular
     * form &mdash; there is no {@code setSelectSharedActions}). Shared actions are
     * packaged by Domino into a single {@code <sharedactions>} container per
     * database, which the DXL splitter emits as one file.
     */
    public void exportCode() throws Exception {
        File dir = mkdirs("code");
        System.out.println("=== Exporting Code ===");

        NoteCollection nc = db.createNoteCollection(false);
        nc.setSelectAgents(true);
        nc.setSelectScriptLibraries(true);
        nc.setSelectActions(true);   // shared actions container
        nc.buildCollection();

        exportCollection(nc, dir, /* skipJava= */ true, null, null);
        nc.recycle();
    }

    /**
     * Export image resources, stylesheet resources, and file resources to
     * {@code <outputDir>/resources/}. Java resources (compiled class files) are skipped.
     *
     * <p>File resources and applet resources fall under
     * {@code setSelectMiscFormatElements(true)}. Java resources, when requested,
     * appear as {@code <javaresource>} elements &mdash; these are filtered out by
     * {@code DxlProcessor} via the Java-element detection.
     */
    public void exportResources() throws Exception {
        File dir = mkdirs("resources");
        System.out.println("=== Exporting Resources ===");

        NoteCollection nc = db.createNoteCollection(false);
        nc.setSelectImageResources(true);
        nc.setSelectStylesheetResources(true);
        nc.setSelectJavaResources(true);       // detected and skipped per-element below
        nc.setSelectMiscFormatElements(true);  // file resources, applet resources
        nc.buildCollection();

        exportCollection(nc, dir, /* skipJava= */ true, null, null);
        nc.recycle();
    }

    /**
     * Export pages, framesets, outlines, and navigators to {@code <outputDir>/pages/}.
     */
    public void exportPages() throws Exception {
        File dir = mkdirs("pages");
        System.out.println("=== Exporting Pages ===");

        NoteCollection nc = db.createNoteCollection(false);
        nc.setSelectPages(true);
        nc.setSelectFramesets(true);
        nc.setSelectOutlines(true);
        nc.setSelectNavigators(true);
        nc.buildCollection();

        exportCollection(nc, dir, /* skipJava= */ false, null, null);
        nc.recycle();
    }

    /**
     * Export the remaining design notes to {@code <outputDir>/other/}: database
     * script, database icon, help About/Using, data connections, replication
     * formulas, profile documents, and anything classified as misc design.
     *
     * <p>Any Java code that slips through (e.g. in a misc code element) is
     * filtered out &mdash; this mirrors the {@code code/} policy of excluding
     * Java from the DXL round-trip.
     *
     * <p>{@code setSelectMiscIndexElements(true)} also re-catches shared columns
     * (same note class as views), and {@code setSelectMiscCodeElements} can pick
     * up subforms/shared fields in some DBs. Those are already exported by
     * {@link #exportForms()} / {@link #exportViews()} and routed into
     * {@code shared/}, so they are filtered out here to prevent duplicates.
     */
    public void exportOther() throws Exception {
        File dir = mkdirs("other");
        System.out.println("=== Exporting Other ===");

        NoteCollection nc = db.createNoteCollection(false);
        nc.setSelectDatabaseScript(true);
        nc.setSelectIcon(true);
        nc.setSelectHelpAbout(true);
        nc.setSelectHelpUsing(true);
        nc.setSelectHelpIndex(true);
        nc.setSelectDataConnections(true);
        nc.setSelectReplicationFormulas(true);
        nc.setSelectProfiles(true);
        nc.setSelectMiscCodeElements(true);
        nc.setSelectMiscIndexElements(true);
        nc.buildCollection();

        // Shared design elements occasionally leak into the "misc" buckets above;
        // they've already been written to shared/ by exportForms/exportViews, so
        // skip them here to avoid duplicates.
        Set<String> skipTypes = new HashSet<>();
        skipTypes.add("subform");
        skipTypes.add("sharedfield");
        skipTypes.add("sharedcolumn");

        exportCollection(nc, dir, /* skipJava= */ true, null, skipTypes);
        nc.recycle();
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    /**
     * Export every note in the collection to its own DXL file.
     *
     * <p>Steps:
     * <ol>
     *   <li>Export the full NoteCollection to a single DXL string via the Domino API.</li>
     *   <li>Parse that XML and split it into one {@link DxlProcessor.DesignElement} per
     *       design element (skipping {@code <databaseinfo>} and {@code <agentdata>}).</li>
     *   <li>Skip elements flagged by {@link DxlProcessor.DesignElement#isExcluded()}
     *       (private replication formulas, XPages build artifacts).</li>
     *   <li>Optionally skip elements that are Java code (when {@code skipJava} is set).</li>
     *   <li>Optionally skip elements whose type is in {@code skipTypes} &mdash; used
     *       to prevent shared elements captured by the misc-index/misc-code selects
     *       from being re-written into {@code other/}.</li>
     *   <li>Choose an output directory: {@code typeRoutes.get(type)} if present,
     *       otherwise {@code defaultDir}. This is how shared elements land in
     *       {@code shared/subforms/} etc. while ordinary forms/views use their
     *       default directory.</li>
     *   <li>Write each element's cleaned DXL to a named file.</li>
     * </ol>
     *
     * @param nc          NoteCollection to export (must already have {@code buildCollection()} called)
     * @param defaultDir  Directory to write .dxl files into when {@code typeRoutes} has no override
     * @param skipJava    When {@code true}, elements containing {@code <javaproject>} are skipped
     * @param typeRoutes  Optional map of {@code <typeLowerCase> → subdirectory}. Elements whose
     *                    {@link DxlProcessor.DesignElement#getType()} key exists here are written
     *                    to the mapped directory instead of {@code defaultDir}. May be {@code null}.
     * @param skipTypes   Optional set of lowercase type names to skip entirely. May be {@code null}.
     */
    private void exportCollection(NoteCollection nc, File defaultDir, boolean skipJava,
                                  Map<String, File> typeRoutes,
                                  Set<String> skipTypes)
            throws Exception {

        // Normalise nulls so the loop below doesn't have to branch
        if (typeRoutes == null) typeRoutes = Collections.emptyMap();
        if (skipTypes  == null) skipTypes  = Collections.emptySet();

        // Ask Domino to export the whole collection as DXL in one pass.
        // Note: NoteCollection has no getNoteCount() method; we derive the count
        // from the parsed elements below, which is more accurate anyway (the raw
        // collection may include internal system notes that DxlExporter skips).
        DxlExporter exporter = session.createDxlExporter();
        String rawDxl = exporter.exportDxl(nc);
        exporter.recycle();

        // Parse, split, clean and optionally filter the elements
        DxlProcessor processor = new DxlProcessor(rawDxl);
        List<DxlProcessor.DesignElement> elements = processor.splitElements();

        System.out.println("Found " + elements.size() + " design element(s).");

        if (elements.isEmpty()) {
            System.out.println();
            return;
        }

        int exported      = 0;
        int skippedJava   = 0;
        int skippedOther  = 0;

        for (DxlProcessor.DesignElement element : elements) {

            String typeKey = element.getType() == null
                    ? "" : element.getType().toLowerCase();

            // 1. Type-based skip (used by exportOther to suppress shared dupes)
            if (skipTypes.contains(typeKey)) {
                // Silent skip — this is expected and would otherwise spam the log
                continue;
            }

            // 2. Excluded-by-DxlProcessor (private repl formulas, XPages build artifacts)
            if (element.isExcluded()) {
                System.out.println("  [SKIP " + element.getExcludedReason() + "] "
                        + element.getType() + ": " + element.getName());
                skippedOther++;
                continue;
            }

            // 3. Java code (only skipped in categories where Java doesn't round-trip)
            if (skipJava && element.isJava()) {
                System.out.println("  [SKIP Java] " + element.getType() + ": " + element.getName());
                skippedJava++;
                continue;
            }

            // Route by type (shared elements land in shared/<kind>/)
            File targetDir = typeRoutes.getOrDefault(typeKey, defaultDir);

            // Build a safe filename. Elements with a meaningful name (most forms,
            // views, agents, resources) get <SanitizedName>_<TypeSuffix>.dxl.
            // Elements without a name (e.g. database icon, help documents,
            // database script) use just <TypeSuffix>.dxl so we don't end up with
            // "unknown_DatabaseIcon.dxl".
            String sanitizedName = sanitize(element.getName());
            String filename = "unknown".equals(sanitizedName)
                    ? element.getTypeSuffix() + ".dxl"
                    : sanitizedName + "_" + element.getTypeSuffix() + ".dxl";

            // Avoid clobbering when two elements share a sanitised name
            filename = uniqueFilename(targetDir, filename);

            File outputFile = new File(targetDir, filename);
            try (PrintWriter pw = new PrintWriter(
                    new OutputStreamWriter(new FileOutputStream(outputFile), "UTF-8"))) {
                pw.print(element.getCleanDxl());
            }

            // Report the relative path from outputDir so the log makes routing visible
            String relative = relativise(outputFile);
            System.out.println("  Exported: " + relative
                    + "  (" + element.getType() + ": " + element.getName() + ")");
            exported++;
        }

        StringBuilder summary = new StringBuilder("  Total exported: ").append(exported);
        if (skippedJava  > 0) summary.append(", skipped (Java): ").append(skippedJava);
        if (skippedOther > 0) summary.append(", skipped (other): ").append(skippedOther);
        System.out.println(summary);
        System.out.println();
    }

    /**
     * Return {@code file} expressed relative to {@link #outputDir} when possible,
     * otherwise just the file's name. Purely cosmetic &mdash; used for log output
     * so lines like {@code "Exported: shared/subforms/Foo_Subform.dxl"} show the
     * routing at a glance.
     */
    private String relativise(File file) {
        try {
            String rootPath = outputDir.getCanonicalPath() + File.separator;
            String filePath = file.getCanonicalPath();
            if (filePath.startsWith(rootPath)) {
                return filePath.substring(rootPath.length())
                               .replace(File.separatorChar, '/');
            }
        } catch (Exception ignored) {
            // fall through to the simple name
        }
        return file.getName();
    }

    /**
     * Sanitize a design-element name so it is safe to use as a filename.
     * <ul>
     *   <li>Strips aliases (everything after the first {@code |} character).</li>
     *   <li>Replaces filesystem-unsafe characters with underscores.</li>
     *   <li>Collapses consecutive underscores to one.</li>
     * </ul>
     */
    private static String sanitize(String name) {
        if (name == null || name.trim().isEmpty()) return "unknown";

        // Strip alias portion (Domino uses | as separator in $TITLE)
        int pipeIdx = name.indexOf('|');
        if (pipeIdx > 0) {
            name = name.substring(0, pipeIdx);
        }

        // Replace chars that are invalid or problematic in filenames
        name = name.trim()
                   .replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", "_")
                   .replaceAll("\\s+", "_")
                   .replaceAll("_+", "_")   // collapse repeated underscores
                   .replaceAll("^_|_$", ""); // strip leading/trailing underscores

        return name.isEmpty() ? "unknown" : name;
    }

    /**
     * If {@code filename} already exists in {@code dir}, append {@code _2}, {@code _3}, … until
     * a free name is found.
     */
    private static String uniqueFilename(File dir, String filename) {
        if (!new File(dir, filename).exists()) return filename;

        int dot = filename.lastIndexOf('.');
        String base = (dot >= 0) ? filename.substring(0, dot) : filename;
        String ext  = (dot >= 0) ? filename.substring(dot)    : "";

        for (int i = 2; i < 1000; i++) {
            String candidate = base + "_" + i + ext;
            if (!new File(dir, candidate).exists()) return candidate;
        }
        return filename; // fallback – shouldn't reach here
    }

    /** Create {@code outputDir/<name>} and return the resulting {@link File}. */
    private File mkdirs(String subdirName) {
        File dir = new File(outputDir, subdirName);
        dir.mkdirs();
        return dir;
    }
}
