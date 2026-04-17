package net.prominic.dominoblueprint;

import lotus.domino.Database;
import lotus.domino.DxlExporter;
import lotus.domino.NoteCollection;
import lotus.domino.Session;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.List;

/**
 * Orchestrates the export of design elements from a Domino database.
 *
 * <p>Three categories are exported, each to its own sub-directory:
 * <ul>
 *   <li><b>forms/</b>  – {@code <form>}, {@code <subform>}, {@code <sharedfield>}</li>
 *   <li><b>views/</b>  – {@code <view>}, {@code <folder>}, {@code <sharedcolumn>}</li>
 *   <li><b>code/</b>   – {@code <agent>}, {@code <scriptlibrary>}
 *                        (Java agents/libraries are skipped; shared actions are not available
 *                        via the standard {@code NoteCollection} API — see {@link #exportCode()})</li>
 * </ul>
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
     * Export all three design categories in order: forms → views → code.
     */
    public void exportAll() throws Exception {
        exportForms();
        exportViews();
        exportCode();
    }

    /**
     * Export forms, subforms, and shared fields to {@code <outputDir>/forms/}.
     */
    public void exportForms() throws Exception {
        File dir = mkdirs("forms");
        System.out.println("=== Exporting Forms ===");

        NoteCollection nc = db.createNoteCollection(false);
        nc.setSelectForms(true);
        nc.setSelectSubforms(true);
        nc.setSelectSharedFields(true);
        nc.buildCollection();

        exportCollection(nc, dir, /* skipJava= */ false);
        nc.recycle();
    }

    /**
     * Export views, folders, and shared columns to {@code <outputDir>/views/}.
     *
     * <p>Note: there is no {@code setSelectSharedColumns()} in the standard
     * {@code NoteCollection} API.  Shared columns share the same note class as
     * views ({@code NOTE_CLASS_VIEW}), so they are automatically included when
     * {@code setSelectViews(true)} is set.  {@code DxlExporter} outputs
     * {@code <sharedcolumn>} elements (rather than {@code <view>}) for them
     * based on each note's design flags, so they land in the correct files.
     */
    public void exportViews() throws Exception {
        File dir = mkdirs("views");
        System.out.println("=== Exporting Views ===");

        NoteCollection nc = db.createNoteCollection(false);
        nc.setSelectViews(true);    // also captures shared columns (same note class)
        nc.setSelectFolders(true);
        nc.buildCollection();

        exportCollection(nc, dir, /* skipJava= */ false);
        nc.recycle();
    }

    /**
     * Export agents and script libraries to {@code <outputDir>/code/}.
     * Java agents and Java script libraries are excluded.
     *
     * <p>Note: {@code setSelectSharedActions()} does not exist in the standard
     * {@code NoteCollection} API.  Shared actions use {@code NOTE_CLASS_DESIGN},
     * which has no dedicated selector.  If you need shared actions, export them
     * by running a second pass with {@code setSelectAllDesignElements(true)} and
     * filtering the resulting DXL for {@code <sharedactions>} elements.
     */
    public void exportCode() throws Exception {
        File dir = mkdirs("code");
        System.out.println("=== Exporting Code ===");

        NoteCollection nc = db.createNoteCollection(false);
        nc.setSelectAgents(true);
        nc.setSelectScriptLibraries(true);
        // Note: setSelectSharedActions() is not available in the NoteCollection API.
        // See Javadoc above for a workaround.
        nc.buildCollection();

        exportCollection(nc, dir, /* skipJava= */ true);
        nc.recycle();
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    /**
     * Export every note in the collection to its own DXL file in {@code targetDir}.
     *
     * <p>Steps:
     * <ol>
     *   <li>Export the full NoteCollection to a single DXL string via the Domino API.</li>
     *   <li>Parse that XML and split it into one {@link DxlProcessor.DesignElement} per
     *       design element (skipping {@code <databaseinfo>}).</li>
     *   <li>Optionally skip elements that contain a {@code <javaproject>} child.</li>
     *   <li>Write each element's cleaned DXL to a named file.</li>
     * </ol>
     *
     * @param nc        NoteCollection to export (must already have {@code buildCollection()} called)
     * @param targetDir Directory to write .dxl files into
     * @param skipJava  When {@code true}, elements containing {@code <javaproject>} are skipped
     */
    private void exportCollection(NoteCollection nc, File targetDir, boolean skipJava)
            throws Exception {

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

        int exported = 0;
        int skipped  = 0;

        for (DxlProcessor.DesignElement element : elements) {

            if (skipJava && element.isJava()) {
                System.out.println("  [SKIP Java] " + element.getType() + ": " + element.getName());
                skipped++;
                continue;
            }

            // Build a safe filename: <SanitizedName>_<TypeSuffix>.dxl
            String filename = sanitize(element.getName()) + "_" + element.getTypeSuffix() + ".dxl";

            // Avoid clobbering when two elements share a sanitised name
            filename = uniqueFilename(targetDir, filename);

            File outputFile = new File(targetDir, filename);
            try (PrintWriter pw = new PrintWriter(
                    new OutputStreamWriter(new FileOutputStream(outputFile), "UTF-8"))) {
                pw.print(element.getCleanDxl());
            }

            System.out.println("  Exported: " + filename
                    + "  (" + element.getType() + ": " + element.getName() + ")");
            exported++;
        }

        System.out.println("  Total exported: " + exported
                + (skipped > 0 ? ", skipped (Java): " + skipped : ""));
        System.out.println();
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
