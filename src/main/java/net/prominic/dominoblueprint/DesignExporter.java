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
 *                           {@code shared/subforms/} ({@code <subform>}, from {@link #exportForms()}),
 *                           {@code shared/fields/} ({@code <sharedfield>}, from {@link #exportForms()}),
 *                           {@code shared/columns/} ({@code <sharedcolumn>}, from {@link #exportOther()}
 *                           via {@code setSelectMiscIndexElements}).</li>
 *   <li><b>code/agents/formula/</b>       – Formula agents</li>
 *   <li><b>code/agents/imported_java/</b> – Java agents imported as a JAR (no editable source;
 *                                           {@code <javaproject imported="true">})</li>
 *   <li><b>code/agents/java/</b>          – Java agents with source in Designer
 *                                           (exclude when using DXLImport for Java source import)</li>
 *   <li><b>code/agents/lotusscript/</b>   – LotusScript agents</li>
 *   <li><b>code/agents/simple/</b>         – Simple action agents</li>
 *   <li><b>code/script_libraries/imported_java/</b> – Java script libraries imported as a JAR</li>
 *   <li><b>code/script_libraries/java/</b>          – Java script libraries with source in Designer</li>
 *   <li><b>code/script_libraries/javascript/</b>  – Client-side JavaScript script libraries</li>
 *   <li><b>code/script_libraries/lotusscript/</b> – LotusScript script libraries</li>
 *   <li><b>code/script_libraries/ssjs/</b>        – Server-Side JavaScript (XPages) script libraries</li>
 *   <li><b>code/</b>                      – Shared actions and any remaining code elements</li>
 *   <li><b>resources/</b>          – {@code <imageresource>}, {@code <stylesheetresource>},
 *                                   {@code <fileresource>} (Java resources are skipped)</li>
 *   <li><b>pages/</b>     – {@code <page>}, {@code <frameset>}, {@code <outline>},
 *                           {@code <navigator>}</li>
 *   <li><b>other/</b>     – Database script/icon, Help About/Using, data connections,
 *                           replication formulas, profile documents, misc design notes</li>
 *   <li><b>acl/</b>      &ndash; Database ACL ({@code <acl>} with all
 *                           {@code <aclentry>} and {@code <role>} children).
 *                           Pretty-printed for human review.</li>
 * </ul>
 *
 * <p>Java agents and Java script libraries are exported to dedicated subdirectories
 * ({@code code/java-agents/} and {@code code/java-libraries/}) rather than being skipped.
 * This lets AI tools edit them as DXL directly. Users who prefer to import Java agents
 * from source via the Prominic DXLImport Gradle project can exclude those subdirectories.
 *
 * <p>Java <em>resources</em> (compiled {@code .class} files stored as
 * {@code <javaresource>} elements) are still skipped; they do not round-trip through
 * {@code DxlImporter} and should be rebuilt from source.
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
 * <p>The database ACL is exported by {@link #exportACL()} to {@code acl/acl.dxl}
 * via {@code NoteCollection.setSelectAcl(true)}. The ACL is pretty-printed for
 * easy human review &mdash; unlike the other categories, which preserve Domino's
 * compact formatting because they may embed code where whitespace is significant.
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
     * forms → views → code → resources → pages → other → acl.
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
        exportACL();
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
     * Export views and folders to {@code <outputDir>/views/}.
     *
     * <p>Shared columns are <b>not</b> picked up by this pass &mdash; despite
     * sharing {@code NOTE_CLASS_VIEW} with views, {@code setSelectViews(true)}
     * matches only notes whose design flag identifies them as a real view, not
     * the shared-column flag ({@code =}). They are collected in
     * {@link #exportOther()} via {@code setSelectMiscIndexElements(true)} and
     * routed from there to {@code shared/columns/}.
     *
     * <p>The {@code sharedcolumn} type is still listed in the routing map below
     * as defensive insurance &mdash; if a future Domino release does emit a
     * {@code <sharedcolumn>} element from a views-only collection, it will be
     * filed correctly rather than landing in {@code views/}.
     */
    public void exportViews() throws Exception {
        File viewsDir   = mkdirs("views");
        File columnsDir = mkdirs("shared/columns");
        System.out.println("=== Exporting Views ===");

        NoteCollection nc = db.createNoteCollection(false);
        nc.setSelectViews(true);
        nc.setSelectFolders(true);
        nc.buildCollection();

        Map<String, File> routes = new HashMap<>();
        routes.put("sharedcolumn", columnsDir);   // defensive — see Javadoc

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
        // Agents sub-categorized by language
        File agentsDir       = mkdirs("code/agents");
        File agFormulaDir    = mkdirs("code/agents/formula");
        File agImportedJaDir = mkdirs("code/agents/imported_java");
        File agJavaDir       = mkdirs("code/agents/java");
        File agLSDir         = mkdirs("code/agents/lotusscript");
        File agSimpleDir     = mkdirs("code/agents/simple");
        // Script libraries sub-categorized by language
        File libsDir         = mkdirs("code/script_libraries");
        File libImportedJaDir= mkdirs("code/script_libraries/imported_java");
        File libJavaDir      = mkdirs("code/script_libraries/java");
        File libJSDir        = mkdirs("code/script_libraries/javascript");
        File libLSDir        = mkdirs("code/script_libraries/lotusscript");
        File libSSJSDir      = mkdirs("code/script_libraries/ssjs");
        // Shared actions and any unclassified code elements land here
        File codeDir      = mkdirs("code");
        System.out.println("=== Exporting Code ===");

        NoteCollection nc = db.createNoteCollection(false);
        nc.setSelectAgents(true);
        nc.setSelectScriptLibraries(true);
        nc.setSelectActions(true);   // shared actions container
        nc.buildCollection();

        // Route by compound "type:language" key.  Empty-language entries ("") act as
        // catch-alls for agents/libraries whose language could not be determined.
        Map<String, File> languageRoutes = new HashMap<>();
        languageRoutes.put("agent:imported_java",      agImportedJaDir);
        languageRoutes.put("agent:java",               agJavaDir);
        languageRoutes.put("agent:lotusscript",        agLSDir);
        languageRoutes.put("agent:formula",            agFormulaDir);
        languageRoutes.put("agent:simple",             agSimpleDir);
        languageRoutes.put("agent:",                   agentsDir);   // unknown-language fallback
        languageRoutes.put("scriptlibrary:imported_java", libImportedJaDir);
        languageRoutes.put("scriptlibrary:java",          libJavaDir);
        languageRoutes.put("scriptlibrary:javascript",    libJSDir);
        languageRoutes.put("scriptlibrary:lotusscript",   libLSDir);
        languageRoutes.put("scriptlibrary:ssjs",          libSSJSDir);
        languageRoutes.put("scriptlibrary:",              libsDir);  // unknown-language fallback

        // Within typed subdirectories the directory name already communicates the
        // element type, so the "_Agent" / "_ScriptLibrary" filename suffix is redundant.
        Set<String> suppressSuffix = new HashSet<>();
        suppressSuffix.add("agent");
        suppressSuffix.add("scriptlibrary");

        exportCollection(nc, codeDir, /* skipJava= */ false, null, null,
                         languageRoutes, suppressSuffix);
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
     * <p><b>Shared columns.</b> {@code setSelectMiscIndexElements(true)} is the
     * only selector that actually emits {@code <sharedcolumn>} elements
     * &mdash; {@code setSelectViews(true)} does not include them despite the
     * shared note class, because Domino filters by design flag (the
     * shared-column flag {@code =} is excluded from the views select).
     * Shared columns therefore originate here and are routed to
     * {@code <outputDir>/shared/columns/} via the type-route map below.
     *
     * <p><b>Duplicate suppression.</b> {@code setSelectMiscCodeElements} can
     * occasionally pick up subforms or shared fields that were already
     * exported by {@link #exportForms()}; those are filtered out via
     * {@code skipTypes} to avoid double-writes.
     */
    public void exportOther() throws Exception {
        File dir        = mkdirs("other");
        File columnsDir = mkdirs("shared/columns");
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

        // Route shared columns out of "other/" and into shared/columns/.
        // setSelectMiscIndexElements(true) above is the source for them.
        Map<String, File> routes = new HashMap<>();
        routes.put("sharedcolumn", columnsDir);

        // Subforms / shared fields can leak into the "misc" buckets above; they
        // were already written to shared/ by exportForms(), so skip them here
        // to prevent duplicates. Shared columns are intentionally NOT skipped
        // — this method is where they are produced.
        Set<String> skipTypes = new HashSet<>();
        skipTypes.add("subform");
        skipTypes.add("sharedfield");

        exportCollection(nc, dir, /* skipJava= */ true, routes, skipTypes);
        nc.recycle();
    }

    /**
     * Export the database ACL to {@code <outputDir>/acl/acl.dxl}.
     *
     * <p>The ACL is selected via {@code NoteCollection.setSelectAcl(true)} and
     * routed through the same cleaning pipeline as design elements: source-replica
     * attributes ({@code replicaid}, {@code path}, {@code title}, ...) are stripped
     * from the {@code <database>} wrapper and any note metadata
     * ({@code <noteinfo>}, {@code <updatedby>}, {@code <wassignedby>}) is removed.
     *
     * <p>Unlike other categories, the resulting DXL is then <b>pretty-printed</b>
     * via {@link DxlProcessor#prettyPrint(String)} so a reviewer can read the
     * {@code <aclentry>}, {@code <aclflags>}, and {@code <role>} children at a
     * glance. ACL DXL contains no embedded code, so re-indentation is safe.
     *
     * <p>Only one {@code <acl>} element is expected per database; if Domino
     * unexpectedly returns more than one, each is written with a numeric suffix
     * ({@code acl_1.dxl}, {@code acl_2.dxl}, ...) so nothing is silently dropped.
     */
    public void exportACL() throws Exception {
        File aclDir = mkdirs("acl");
        System.out.println("=== Exporting ACL ===");

        NoteCollection nc = db.createNoteCollection(false);
        nc.setSelectAcl(true);
        nc.buildCollection();

        DxlExporter exporter = session.createDxlExporter();
        String rawDxl = exporter.exportDxl(nc);
        exporter.recycle();

        DxlProcessor processor = new DxlProcessor(rawDxl);
        List<DxlProcessor.DesignElement> elements = processor.splitElements();

        // Filter to just the ACL element(s) — defensive, in case Domino emits
        // anything else under <database> when only ACL is selected.
        int aclCount = 0;
        for (DxlProcessor.DesignElement e : elements) {
            if ("acl".equalsIgnoreCase(e.getType())) aclCount++;
        }

        if (aclCount == 0) {
            System.out.println("  No <acl> element returned by DxlExporter — skipping.");
            System.out.println();
            nc.recycle();
            return;
        }

        int written     = 0;
        int aclSeen     = 0;
        int skippedOther = 0;

        for (DxlProcessor.DesignElement element : elements) {
            if (!"acl".equalsIgnoreCase(element.getType())) {
                System.out.println("  [SKIP unexpected " + element.getType()
                        + "] in ACL export");
                skippedOther++;
                continue;
            }
            aclSeen++;

            String filename = (aclCount == 1) ? "acl.dxl" : "acl_" + aclSeen + ".dxl";
            File outputFile = new File(aclDir, filename);

            String prettyDxl = DxlProcessor.prettyPrint(element.getCleanDxl());
            try (PrintWriter pw = new PrintWriter(
                    new OutputStreamWriter(new FileOutputStream(outputFile), "UTF-8"))) {
                pw.print(prettyDxl);
            }

            System.out.println("  Exported: " + relativise(outputFile));
            written++;
        }

        StringBuilder summary = new StringBuilder("  Total exported: ").append(written);
        if (skippedOther > 0) summary.append(", skipped (other): ").append(skippedOther);
        System.out.println(summary);
        System.out.println();

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
    /** Overload for call sites that do not supply java-specific routing. */
    private void exportCollection(NoteCollection nc, File defaultDir, boolean skipJava,
                                  Map<String, File> typeRoutes,
                                  Set<String> skipTypes)
            throws Exception {
        exportCollection(nc, defaultDir, skipJava, typeRoutes, skipTypes, null, null);
    }

    /**
     * Full implementation.
     *
     * <p>{@code languageRoutes} is a compound-key map whose keys have the form
     * {@code "type:language"} (e.g. {@code "agent:java"}, {@code "scriptlibrary:lotusscript"}).
     * A key of {@code "type:"} (empty language) acts as a catch-all for elements
     * of that type whose language could not be determined. When {@code null} or empty,
     * routing falls through to {@code typeRoutes} / {@code defaultDir}.
     *
     * <p>{@code suppressSuffixTypes} lists element types (e.g. {@code "agent"},
     * {@code "scriptlibrary"}) for which the {@code _TypeSuffix} portion of the
     * filename is omitted because the subdirectory already conveys the type.
     */
    private void exportCollection(NoteCollection nc, File defaultDir, boolean skipJava,
                                  Map<String, File> typeRoutes,
                                  Set<String> skipTypes,
                                  Map<String, File> languageRoutes,
                                  Set<String> suppressSuffixTypes)
            throws Exception {

        // Normalise nulls so the loop below doesn't have to branch
        if (typeRoutes         == null) typeRoutes         = Collections.emptyMap();
        if (skipTypes          == null) skipTypes          = Collections.emptySet();
        if (languageRoutes     == null) languageRoutes     = Collections.emptyMap();
        if (suppressSuffixTypes== null) suppressSuffixTypes= Collections.emptySet();

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
        int skippedGhost  = 0;
        int skippedOther  = 0;

        for (DxlProcessor.DesignElement element : elements) {

            String typeKey = element.getType() == null
                    ? "" : element.getType().toLowerCase();

            // 1. Type-based skip (used by exportOther to suppress shared dupes)
            if (skipTypes.contains(typeKey)) {
                // Silent skip — this is expected and would otherwise spam the log
                continue;
            }

            // 2. Ghost notes — empty deletion stubs left in the source NSF
            //    (typically NOTE_CLASS_FILTER notes with only $FLAGS/$UpdatedBy
            //    items, both empty). They round-trip as 244-byte empty
            //    <agent><trigger/></agent> stubs with no recoverable design info,
            //    so we drop them here. Cleanup at the source: `load compact -c -D
            //    <db>.nsf`, then `load fixup <db>.nsf -F -J` if any survive.
            if (element.isGhost()) {
                System.out.println("  [SKIP ghost] " + element.getType()
                        + " (empty stub — no name, no content)");
                skippedGhost++;
                continue;
            }

            // 3. Excluded-by-DxlProcessor (private repl formulas, XPages build artifacts)
            if (element.isExcluded()) {
                System.out.println("  [SKIP " + element.getExcludedReason() + "] "
                        + element.getType() + ": " + element.getName());
                skippedOther++;
                continue;
            }

            // 4. Java code: skip only when explicitly requested (skipJava=true).
            //    Otherwise fall through and let languageRoutes handle the directory.
            if (element.isJava() && skipJava) {
                System.out.println("  [SKIP Java] " + element.getType() + ": " + element.getName());
                skippedJava++;
                continue;
            }

            // Route by compound "type:language" key first, then plain type route, then default.
            String lang    = element.getLanguage();
            String langKey = typeKey + ":" + (lang != null ? lang : "");
            File targetDir;
            if (!languageRoutes.isEmpty() && languageRoutes.containsKey(langKey)) {
                targetDir = languageRoutes.get(langKey);
            } else if (!languageRoutes.isEmpty() && languageRoutes.containsKey(typeKey + ":")) {
                // Fallback: type catch-all ("type:") for unrecognised/null language
                targetDir = languageRoutes.get(typeKey + ":");
            } else {
                targetDir = typeRoutes.getOrDefault(typeKey, defaultDir);
            }

            // Build a safe filename. Elements with a meaningful name (most forms,
            // views, agents, resources) get <SanitizedName>_<TypeSuffix>.dxl.
            // Elements without a name (e.g. database icon, help documents,
            // database script) use just <TypeSuffix>.dxl so we don't end up with
            // "unknown_DatabaseIcon.dxl".
            String sanitizedName = sanitize(element.getName());
            boolean useSuffix = !suppressSuffixTypes.contains(typeKey);
            String filename;
            if ("unknown".equals(sanitizedName)) {
                // Always use the suffix for unnamed elements to keep filenames descriptive
                filename = element.getTypeSuffix() + ".dxl";
            } else if (useSuffix) {
                filename = sanitizedName + "_" + element.getTypeSuffix() + ".dxl";
            } else {
                filename = sanitizedName + ".dxl";
            }

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
        if (skippedGhost > 0) summary.append(", skipped (ghost): ").append(skippedGhost);
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
