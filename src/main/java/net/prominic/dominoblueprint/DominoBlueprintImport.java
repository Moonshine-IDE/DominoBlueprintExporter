package net.prominic.dominoblueprint;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import lotus.domino.*;

/**
 * Import a DXL file (or a directory of DXL files) into the target database.
 * This does not validate the DXL.  Importing to a production database is discouraged.
 *
 * <p>Invoked via the {@code import} subcommand of {@link DominoBlueprint}; the
 * dispatcher owns the Notes session, password handling, and CLI parsing, so this
 * class only contains the import work methods and the {@code --acl-import} mode
 * mapping.</p>
 *
 * <p>If the import target is a directory, all {@code .dxl} files within the directory
 * and its subdirectories are imported into the same database.  This mode is intended
 * for use with DominoBlueprint exports, which write each design element to its own
 * {@code .dxl} file.</p>
 *
 * <p>The ACL import behaviour is controlled by the {@code --acl-import=<mode>} flag; see
 * {@link #parseAclImportMode(String)} for the mapping.  The default is
 * {@code update-else-create}, which preserves any target ACL entries not present in
 * the DXL while updating the ones that are and creating new ones for unmatched DXL
 * entries.  This default is set <b>explicitly</b> on every import so behaviour is
 * deterministic across Notes versions and call sites.</p>
 *
 * FUTURE TASK:  Validate as XML. Use the DXL schema if available
 */
public class DominoBlueprintImport {

    /**
     * Default ACL import behaviour applied when {@code --acl-import} is not given.
     * Existing ACL entries (e.g. the stub {@code -Default-} entry on a freshly-created
     * database) are updated when the DXL contains a matching name, and entries that
     * exist only in the DXL are created.  Stub entries that the DXL does not mention
     * are <b>preserved</b> &mdash; if you want a clean clone of the source ACL,
     * pass {@code --acl-import=replace} instead.
     */
    public static final int DEFAULT_ACL_IMPORT_OPTION = DxlImporter.DXLIMPORTOPTION_UPDATE_ELSE_CREATE;

    /**
     * Map a {@code --acl-import} CLI value to the corresponding
     * {@code DxlImporter.DXLIMPORTOPTION_*} constant.  Matching is case-insensitive
     * and accepts hyphenated, underscored, and shorthand forms.
     *
     * @throws IllegalArgumentException for any unrecognised value, with a message
     *         that lists the accepted modes.  The caller is responsible for
     *         translating this into a CLI error.
     */
    public static int parseAclImportMode(String raw) {
        if (null == raw) {
            throw new IllegalArgumentException("--acl-import requires a value (use --help to see modes)");
        }
        String key = raw.trim().toLowerCase().replace('_', '-');
        if ("ignore".equals(key))                                            return DxlImporter.DXLIMPORTOPTION_IGNORE;
        if ("create".equals(key))                                            return DxlImporter.DXLIMPORTOPTION_CREATE;
        if ("replace".equals(key) || "replace-else-ignore".equals(key))      return DxlImporter.DXLIMPORTOPTION_REPLACE_ELSE_IGNORE;
        if ("replace-else-create".equals(key))                               return DxlImporter.DXLIMPORTOPTION_REPLACE_ELSE_CREATE;
        if ("update".equals(key) || "update-else-create".equals(key))        return DxlImporter.DXLIMPORTOPTION_UPDATE_ELSE_CREATE;
        if ("update-else-ignore".equals(key))                                return DxlImporter.DXLIMPORTOPTION_UPDATE_ELSE_IGNORE;

        throw new IllegalArgumentException(
            "Unknown --acl-import mode: '" + raw + "'.  Accepted: ignore, create, " +
            "replace, replace-else-ignore, replace-else-create, " +
            "update, update-else-create, update-else-ignore");
    }

    /**
     * Return the canonical name (matching the {@code DXLIMPORTOPTION_*} constant)
     * for log/echo output, so the user can see exactly what behaviour is in effect.
     */
    public static String aclImportOptionName(int option) {
        if (option == DxlImporter.DXLIMPORTOPTION_IGNORE)              return "IGNORE";
        if (option == DxlImporter.DXLIMPORTOPTION_CREATE)              return "CREATE";
        if (option == DxlImporter.DXLIMPORTOPTION_REPLACE_ELSE_IGNORE) return "REPLACE_ELSE_IGNORE";
        if (option == DxlImporter.DXLIMPORTOPTION_REPLACE_ELSE_CREATE) return "REPLACE_ELSE_CREATE";
        if (option == DxlImporter.DXLIMPORTOPTION_UPDATE_ELSE_IGNORE)  return "UPDATE_ELSE_IGNORE";
        if (option == DxlImporter.DXLIMPORTOPTION_UPDATE_ELSE_CREATE)  return "UPDATE_ELSE_CREATE";
        return "UNKNOWN(" + option + ")";
    }

    // -----------------------------------------------------------------------
    // importDXL — single file
    // -----------------------------------------------------------------------

    /**
     * Backwards-compatible overload that uses {@link #DEFAULT_ACL_IMPORT_OPTION}.
     */
    public static void importDXL(Session session, String server, String databaseName, File dxlFile)
            throws NotesException, Exception {
        importDXL(session, server, databaseName, dxlFile, DEFAULT_ACL_IMPORT_OPTION);
    }

    public static void importDXL(Session session, String server, String databaseName, File dxlFile,
                                 int aclImportOption) throws NotesException, Exception {
        Database database = null;
        try {
            database = session.getDatabase(server, databaseName, false);
            if (null == database || !database.isOpen()) {
                throw new Exception("Could not open database '" + databaseName + "'.");
            }
            importDXL(session, database, dxlFile, aclImportOption);
        }
        finally {
            if (null != database) {
                database.recycle();
            }
        }
    }

    /**
     * Backwards-compatible overload that uses {@link #DEFAULT_ACL_IMPORT_OPTION}.
     */
    public static void importDXL(Session session, Database database, File dxlFile)
            throws NotesException, Exception {
        importDXL(session, database, dxlFile, DEFAULT_ACL_IMPORT_OPTION);
    }

    /**
     * Import a single DXL file into the given (already-opened) database.
     * This overload is used when the database should remain open across multiple imports
     * (see {@link #importDXLDirectory}), so callers are responsible for opening and recycling
     * the database themselves.
     *
     * @param aclImportOption One of the {@code DxlImporter.DXLIMPORTOPTION_*} constants.
     *                        Always set explicitly so behaviour is deterministic.
     */
    public static void importDXL(Session session, Database database, File dxlFile,
                                 int aclImportOption) throws NotesException, Exception {
        importDXLTracked(session, database, dxlFile, aclImportOption);
    }

    /**
     * As {@link #importDXL(Session, Database, File, int)}, but returns whether DxlImporter
     * reported a code-compile failure (HCL log id {@code 7005}) for this file. Used by
     * {@link #importDXLDirectory} to drive the dependency-ordered re-import passes.
     */
    static boolean importDXLTracked(Session session, Database database, File dxlFile,
                                    int aclImportOption) throws NotesException, Exception {
        Stream stream = null;
        DxlImporter importer = null;
        boolean hadCompileError = false;

        try {
            // https://help.hcl-software.com/dom_designer/14.0.0/basic/H_IMPORTDXL_METHOD_IMPORTER_JAVA.html
            // https://help.hcl-software.com/dom_designer/14.0.0/basic/H_EXAMPLES_NOTESDXLIMPORTER_CLASS_JAVA.html
            stream = session.createStream();
            if (stream.open(dxlFile.getAbsolutePath()) & (stream.getBytes() >0)) {
                // Import DXL from file to new database
                importer = session.createDxlImporter();
                // Database-level properties (e.g. <launchsettings>) live in the <database>
                // wrapper, not in a note. A DominoBlueprint export gives every design file a
                // bare <database> wrapper with no such properties, so enabling property
                // replacement for every file makes the alphabetically-last file win and
                // silently resets launch settings to default. Enable it only for files that
                // actually carry a database-property block (see fileDeclaresDbProperties).
                boolean replaceDbProperties = fileDeclaresDbProperties(dxlFile);
                importer.setReplaceDbProperties(replaceDbProperties);
                if (replaceDbProperties) {
                    System.out.println("  (database properties in this file will be applied to the target)");
                }
                importer.setReplicaRequiredForReplaceOrUpdate(false);  // don't require a matching replica ID in the DXL
                importer.setAclImportOption(aclImportOption);   // configurable; see --acl-import
                importer.setDesignImportOption(DxlImporter.DXLIMPORTOPTION_REPLACE_ELSE_CREATE);  // Create any missing design elements, overwrite existing design elements
                importer.setCompileLotusScript(true);  // Automatically compile any included LotusScript
                importer.setDocumentImportOption(DxlImporter.DXLIMPORTOPTION_REPLACE_ELSE_CREATE);   // allow importing documents.  Replace existing documents (replicaID and universal ID must match)
                importer.importDxl(stream, database);

                // Capture the log once so we can both print it and scan it for compile failures.
                String importLog = importer.getLog();
                System.out.println("## Log:  " + importer.getLogComment());
                System.out.println(importLog);
                System.out.println("## End Log");
                System.out.println("Imported " + importer.getImportedNoteCount() + " elements");
                // TODO: iterate over imported elements if log is insufficient

                // A code-compile failure is reported by DxlImporter as a *log warning*
                // (HCL log id 7005), not as a thrown NotesException: the design note is still
                // created and counted as imported, so it would otherwise pass silently.
                hadCompileError = warnIfCompileErrors(dxlFile, importLog);
            }
        }
        finally {
            if (null != stream) {
                stream.recycle();
            }
            if (null != importer) {
                importer.recycle();
            }
        }
        return hadCompileError;
    }

    // -----------------------------------------------------------------------
    // importDXLDirectory — walk a directory of .dxl files
    // -----------------------------------------------------------------------

    /**
     * Backwards-compatible overload that uses {@link #DEFAULT_ACL_IMPORT_OPTION}.
     */
    public static void importDXLDirectory(Session session, String server, String databaseName, File dxlDir)
            throws NotesException, Exception {
        importDXLDirectory(session, server, databaseName, dxlDir, DEFAULT_ACL_IMPORT_OPTION);
    }

    /**
     * Import every {@code .dxl} file under the given directory (recursively) into the target
     * database.  The database is opened once and reused across all files.
     *
     * <p>Files are imported in alphabetical order of their absolute path to keep runs
     * reproducible, <b>except</b> that any file whose {@code <database>} wrapper declares
     * database-level properties (see {@link #fileDeclaresDbProperties}) is moved to the end
     * of the list, preserving their relative order (see {@link #orderDbPropertyFilesLast}).
     * A DominoBlueprint export has exactly two such files &mdash; {@code acl/acl.dxl} and
     * {@code other/DatabaseSettings.dxl} &mdash; and today's alphabetical directory naming
     * (<code>acl/</code> &lt; <code>other/</code>) already happens to import them in that
     * order, but that's an accident of file naming, not something the code enforces. This
     * makes it explicit instead. See {@code DominoBlueprint_RoundTrip_Status.md} item B.</p>
     *
     * <p>If a single file fails to import, the error is logged and the remaining files are
     * still attempted; at the end, this method throws if any file failed, so the process
     * exits with a non-zero status.</p>
     *
     * @param aclImportOption Applied to every file in the directory.  This is intentional:
     *                        {@code acl.dxl} (in {@code acl/}) is the only file in a
     *                        DominoBlueprint export that contains an {@code <acl>} element,
     *                        so the option is a no-op for the other files.
     */
    public static void importDXLDirectory(Session session, String server, String databaseName, File dxlDir,
                                          int aclImportOption) throws NotesException, Exception {
        importDXLDirectory(session, server, databaseName, dxlDir, aclImportOption, false);
    }

    /**
     * Extra dependency-ordered re-import passes after the first, used to resolve residual
     * compile failures (e.g. a LotusScript {@code Use} chain the exporter didn't fully order,
     * or the {@code .jar} build-path gap). Bounded so a genuinely broken element cannot loop.
     */
    private static final int MAX_EXTRA_COMPILE_PASSES = 5;

    /**
     * Dependency-aware directory import.
     *
     * <p>Files are imported in two phases (see {@link ImportOrdering} and
     * {@code DominoBlueprint_Java_Dependency_Ordering.md}):</p>
     * <ol>
     *   <li><b>Phase 1</b> &mdash; every non-compiled element (forms, views, pages, subforms,
     *       resources incl. {@code .jar}s, imported/pre-compiled Java, JavaScript/SSJS,
     *       formula/simple agents, ACL, db properties), in stable alphabetical order with the
     *       db-property-bearing files ({@code acl.dxl}, {@code other/DatabaseSettings.dxl})
     *       moved last (see {@link #orderDbPropertyFilesLast}). Imported first so referenced
     *       resources and design notes already exist before any code compiles.</li>
     *   <li><b>Phase 2</b> &mdash; source Java agents/libraries (deps from
     *       {@code <sharedlibraryref>}) and LotusScript agents/libraries (deps from
     *       {@code Use "lib"}), topologically sorted so a library imports before everything
     *       that references it.</li>
     * </ol>
     *
     * <p>After the ordered pass, any element that still reported a compile failure is
     * re-imported (up to {@link #MAX_EXTRA_COMPILE_PASSES} extra passes) until no further
     * progress is made &mdash; each pass recompiles under {@code REPLACE_ELSE_CREATE}. Elements
     * that never compile are counted separately ("Imported-with-compile-errors") so a broken
     * element cannot hide inside {@code Failed: 0}; ordering cannot fix the exporter/environment
     * fidelity gaps (jar build path, {@code lotus.domino} on the agent compile path), so this is
     * a warning by default and only a hard failure when {@code failOnCompileError} is set.</p>
     *
     * @param failOnCompileError when {@code true}, a non-empty compile-error set after the final
     *                           pass throws (non-zero exit). Default is {@code false} to preserve
     *                           historical behaviour, since some compile failures are known
     *                           environment gaps rather than ordering problems.
     */
    public static void importDXLDirectory(Session session, String server, String databaseName, File dxlDir,
                                          int aclImportOption, boolean failOnCompileError) throws NotesException, Exception {
        if (null == dxlDir || !dxlDir.isDirectory()) {
            throw new Exception("DXL directory not found or not a directory: '" + (null == dxlDir ? "null" : dxlDir.getAbsolutePath()) + "'.");
        }

        List<File> dxlFiles = new ArrayList<File>();
        collectDXLFiles(dxlDir, dxlFiles);
        Collections.sort(dxlFiles);   // stable alphabetical base order

        // Split into (1) non-compiled elements and (2) compiled code in dependency order.
        ImportOrdering.Plan plan = ImportOrdering.split(dxlFiles);
        // Within phase 1, keep the db-property-bearing files (acl.dxl, DatabaseSettings.dxl) last.
        List<File> phase1 = orderDbPropertyFilesLast(plan.phase1NonCompiled);
        List<File> phase2 = plan.phase2Compiled;

        System.out.println("Found " + dxlFiles.size() + " DXL file(s) under '" + dxlDir.getAbsolutePath() + "'.");
        System.out.println("  Phase 1 (non-compiled, imported first)     : " + phase1.size() + " file(s).");
        System.out.println("  Phase 2 (compiled code, dependency-ordered): " + phase2.size() + " file(s).");
        if (!plan.missingRefs.isEmpty()) {
            System.out.println("  [NOTE] code references not present as elements in this blueprint "
                + "(e.g. .jar build-path entries or platform LSX, which cannot be ordered): " + plan.missingRefs);
        }
        if (!plan.cycleNodes.isEmpty()) {
            System.out.println("  [WARNING] dependency cycle detected among: " + plan.cycleNodes
                + " -- these were appended in alphabetical order and may not compile cleanly.");
        }
        if (dxlFiles.isEmpty()) {
            return;
        }

        Database database = null;
        int successCount = 0;
        List<String> failures = new ArrayList<String>();
        Set<File> compileFailed = new LinkedHashSet<File>();
        try {
            database = session.getDatabase(server, databaseName, false);
            if (null == database || !database.isOpen()) {
                throw new Exception("Could not open database '" + databaseName + "'.");
            }

            // ---- Phase 1: non-compiled elements ----
            for (File file : phase1) {
                System.out.println("--- [phase 1] Importing '" + file.getAbsolutePath() + "' ---");
                try {
                    importDXL(session, database, file, aclImportOption);
                    successCount++;
                }
                catch (Exception ex) {
                    System.err.println("Failed to import '" + file.getAbsolutePath() + "': " + ex.getMessage());
                    ex.printStackTrace();
                    failures.add(file.getAbsolutePath());
                }
            }

            // ---- Phase 2: compiled code, dependency order ----
            for (File file : phase2) {
                System.out.println("--- [phase 2] Importing '" + file.getAbsolutePath() + "' ---");
                try {
                    boolean hadCompileError = importDXLTracked(session, database, file, aclImportOption);
                    successCount++;
                    if (hadCompileError) {
                        compileFailed.add(file);
                    }
                }
                catch (Exception ex) {
                    System.err.println("Failed to import '" + file.getAbsolutePath() + "': " + ex.getMessage());
                    ex.printStackTrace();
                    failures.add(file.getAbsolutePath());
                }
            }

            // ---- Fallback: extra dependency-ordered passes to a fixpoint ----
            int pass = 0;
            while (!compileFailed.isEmpty() && pass < MAX_EXTRA_COMPILE_PASSES) {
                pass++;
                int before = compileFailed.size();
                System.out.println("--- Re-import pass " + pass + " for " + before
                    + " element(s) that have not compiled yet ---");
                Set<File> stillFailing = new LinkedHashSet<File>();
                for (File file : phase2) {                 // preserve dependency order
                    if (!compileFailed.contains(file)) {
                        continue;
                    }
                    System.out.println("--- [pass " + pass + "] Re-importing '" + file.getAbsolutePath() + "' ---");
                    try {
                        boolean hadCompileError = importDXLTracked(session, database, file, aclImportOption);
                        if (hadCompileError) {
                            stillFailing.add(file);
                        }
                    }
                    catch (Exception ex) {
                        System.err.println("Failed to re-import '" + file.getAbsolutePath() + "': " + ex.getMessage());
                        stillFailing.add(file);
                    }
                }
                compileFailed = stillFailing;
                if (compileFailed.size() >= before) {
                    break;   // no progress -- further passes will not help
                }
            }
        }
        finally {
            if (null != database) {
                database.recycle();
            }
        }

        System.out.println("Directory import complete.  Imported: " + successCount
            + ", Failed: " + failures.size()
            + ", Imported-with-compile-errors: " + compileFailed.size() + ".");
        // ------------------------------------------------------------------
        // CLASSPATH INVESTIGATION (2026-07-14) — why compiled Java still fails.
        //
        // On a Domino *server*, DxlImporter compiles Java agents/libraries at import
        // time with a javac it spawns into a temp dir (/tmp/notes.../jar....dir/), and
        // every such element fails with "package lotus.domino does not exist" — i.e.
        // Notes.jar is not on that javac's classpath. Ordering does NOT cause this and
        // cannot fix it (all Java fails identically regardless of dependency position;
        // LotusScript compiles fine). What we tried, all with NO effect on the compile:
        //   - notes.ini JavaUserClasses + JavaUserClassesExt = .../ndext/Notes.jar,
        //     in BOTH the runtime-dir notes.ini (confirmed the one in effect) and
        //     /local/notesdata/notes.ini. Confirmed Notes.jar exists and contains
        //     lotus/domino/Session.class.
        //   - Rewriting the <javaproject codepath="..."> from the authoring-machine
        //     Windows path to the Linux program dir.
        // Conclusion: the import-time compile appears to use only the note's own stored
        // build path, not the server/notes.ini classpath, and there is no supported
        // server-side knob to feed it the Domino API (HCL expects a Designer-class
        // environment for Java-agent compilation). Decision: the generic tool DEFERS
        // Java to the dedicated pre-build/import path (the "DXL Importer" Gradle build
        // that compiles first and imports precompiled), rather than compiling at import.
        // A "--skip-java" option (skip the source-Java flavour subdirs) is the planned
        // clean follow-up. Full detail: DominoBlueprint_Java_Dependency_Ordering.md and
        // DominoBlueprint_RoundTrip_Status.md items C/D.
        // ------------------------------------------------------------------
        if (!compileFailed.isEmpty()) {
            StringBuilder cb = new StringBuilder();
            cb.append(compileFailed.size())
              .append(" element(s) imported but did NOT compile after all dependency-ordered passes:");
            for (File f : compileFailed) {
                cb.append("\n  - ").append(f.getAbsolutePath());
            }
            cb.append("\n  (Usually the exporter/environment fidelity gaps in ")
              .append("DominoBlueprint_Java_Dependency_Ordering.md: a .jar build-path entry not encoded in ")
              .append("the DXL, or the Domino API / Notes.jar not on the agent compile path. Recompile in ")
              .append("Designer or fix the build path to clear them.)");
            System.out.println(cb.toString());
        }
        if (!failures.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            sb.append(failures.size()).append(" DXL file(s) failed to import:");
            for (String path : failures) {
                sb.append("\n  - ").append(path);
            }
            throw new Exception(sb.toString());
        }
        if (failOnCompileError && !compileFailed.isEmpty()) {
            throw new Exception(compileFailed.size()
                + " element(s) imported but did not compile (--fail-on-compile-error was set).");
        }
    }

    /**
     * Detect a code-compile failure in a DXL import log and print a short, actionable
     * warning when one is found.
     *
     * <p>When DxlImporter imports a Java agent, Java script library, or LotusScript element it
     * compiles the code at import time.  A compile failure is reported as a <em>log warning</em>
     * (HCL log id {@code 7005}, e.g. {@code "Java compile errors: ..."}), <strong>not</strong> as
     * a thrown {@link NotesException}.  The design note is still created and is included in
     * {@link DxlImporter#getImportedNoteCount()}, so without this check a broken element would
     * import "successfully" and only fail later at runtime.</p>
     *
     * <p>This is deliberately a warning rather than a hard failure.  The usual cause is
     * dependency <em>ordering</em>, not bad DXL: each element is compiled against whatever already
     * exists in the target database, so a referenced Java script library
     * ({@code <sharedlibraryref>}), a LotusScript library referenced via {@code Use}, or a
     * {@code .jar} file resource imported later is simply not on the build path yet.  Re-running
     * the import once every element and resource is present (or recompiling in Domino Designer)
     * typically clears it.  Dependency-aware import ordering is tracked as a separate enhancement
     * for the DominoBlueprint importer.</p>
     *
     * @return {@code true} if a compile error was detected and a warning was printed.
     */
    static boolean warnIfCompileErrors(File dxlFile, String importLog) {
        if (null == importLog || importLog.isEmpty()) {
            return false;
        }
        // Match the numeric HCL log id and the human-readable phrasing, which covers both
        // "Java compile errors" and "LotusScript ... compile error" wording across versions.
        boolean hasCompileError =
               importLog.contains("id='7005'")
            || importLog.contains("id=\"7005\"")
            || importLog.toLowerCase().contains("compile error");
        if (!hasCompileError) {
            return false;
        }
        String name = (null == dxlFile) ? "(unknown)" : dxlFile.getName();
        System.out.println("  [WARNING] '" + name + "' was imported as a design note, but its code");
        System.out.println("            did NOT compile (see the 'compile errors' in the log above).");
        System.out.println("            The note exists in the target database but will not run until it");
        System.out.println("            compiles cleanly.  This is normally a dependency-ordering issue:");
        System.out.println("            code is compiled at import time against whatever is already in the");
        System.out.println("            database, so a referenced script library or .jar file resource that");
        System.out.println("            is imported later is not yet on the build path.  Re-run the import");
        System.out.println("            once all elements/resources are present, or recompile in Designer.");
        return true;
    }

    /**
     * Return {@code true} if the DXL file carries a database-level property block in its
     * {@code <database>} wrapper &mdash; currently {@code <launchsettings>} (web/Notes launch
     * options) or {@code <databaseinfo>}, plus {@code <acl>} so ACL-bearing files keep their
     * historical {@code setReplaceDbProperties(true)} behaviour for attributes such as
     * {@code maxinternetaccess}.
     *
     * <p>Bare design-element files (a single {@code <form>}/{@code <view>}/... inside an
     * otherwise-empty {@code <database>} wrapper) return {@code false}, so importing them
     * with {@code setReplaceDbProperties(false)} cannot overwrite database properties that
     * an earlier file (e.g. {@code other/DatabaseSettings.dxl}) just applied.</p>
     *
     * <p>The relevant block sits at the very top of the file, immediately after the
     * {@code <database>} open tag, so a short prefix is enough to detect it without reading
     * large base64 file-resource payloads.</p>
     */
    static boolean fileDeclaresDbProperties(File dxlFile) {
        java.io.InputStream in = null;
        try {
            in = new java.io.FileInputStream(dxlFile);
            byte[] buf = new byte[4096];
            int n = in.read(buf);
            if (n <= 0) {
                return false;
            }
            String head = new String(buf, 0, n, "UTF-8");
            return head.contains("<launchsettings")
                || head.contains("<databaseinfo")
                || head.contains("<acl");
        }
        catch (Exception e) {
            // On any read error, fall back to the safe default: do not replace properties.
            return false;
        }
        finally {
            if (null != in) {
                try { in.close(); } catch (Exception ignore) { /* best effort */ }
            }
        }
    }

    /**
     * Stable-partition {@code dxlFiles} so that every file for which
     * {@link #fileDeclaresDbProperties} is {@code true} moves to the end of the list,
     * preserving the relative order of both groups. See the identical helper (and its full
     * rationale/NOTE about future order-sensitivity) in the {@code hcl_roles} copy of this
     * tool, {@code net.prominic.domino.vagrant.DXLImport.orderDbPropertyFilesLast} &mdash;
     * these two classes are independent, duplicate implementations, and this fix had to be
     * ported here separately since neither wraps the other.
     */
    static List<File> orderDbPropertyFilesLast(List<File> dxlFiles) {
        List<File> ordinary   = new ArrayList<File>();
        List<File> dbProperty = new ArrayList<File>();
        for (File f : dxlFiles) {
            if (fileDeclaresDbProperties(f)) {
                dbProperty.add(f);
            } else {
                ordinary.add(f);
            }
        }
        ordinary.addAll(dbProperty);
        return ordinary;
    }

    /**
     * Recursively collect every regular file with a {@code .dxl} extension (case-insensitive)
     * under {@code dir} into {@code out}.
     */
    private static void collectDXLFiles(File dir, List<File> out) {
        File[] children = dir.listFiles();
        if (null == children) {
            return;  // unreadable or not a directory
        }
        for (File child : children) {
            if (child.isDirectory()) {
                collectDXLFiles(child, out);
            }
            else if (child.isFile() && child.getName().toLowerCase().endsWith(".dxl")) {
                out.add(child);
            }
        }
    }
}
