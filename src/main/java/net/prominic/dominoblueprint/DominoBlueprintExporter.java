package net.prominic.dominoblueprint;

import lotus.domino.Database;
import lotus.domino.Session;

/**
 * DominoBlueprint Exporter – exports HCL Domino design elements as a tree of DXL files.
 *
 * <p>Invoked via the {@code export} subcommand of {@link DominoBlueprint}; the
 * dispatcher owns the Notes session, password handling, and CLI parsing, so this
 * class only contains the export work method.</p>
 *
 * <p>Connects to a Domino database and exports its design elements as individual
 * DXL files organised into category directories:</p>
 *
 * <pre>
 *   &lt;outputDir&gt;/
 *     forms/      – Forms, subforms, shared fields
 *     views/      – Views, folders, shared columns
 *     code/agents/formula/            – Formula agents
 *     code/agents/imported_java/      – Imported (JAR-only) Java agents (exclude for source import)
 *     code/agents/java/               – Java agents with editable source (exclude for source import)
 *     code/agents/lotusscript/        – LotusScript agents
 *     code/agents/simple/             – Simple action agents
 *     code/script_libraries/imported_java/ – Imported (JAR-only) Java script libraries (exclude)
 *     code/script_libraries/java/     – Java script libraries with editable source (exclude)
 *     code/script_libraries/lotusscript/ – LotusScript script libraries
 *     code/                           – Shared actions and unclassified code
 *     resources/  – Image, stylesheet, and file resources     (Java resources excluded)
 *     pages/      – Pages, framesets, outlines, navigators
 *     other/      – Database script/icon, Help About/Using, data connections,
 *                   replication formulas, profile documents, misc design notes
 *     acl/        – Database ACL (pretty-printed for human review)
 * </pre>
 *
 * <p>Each exported file is cleaned for re-import: database-specific attributes
 * (replicaid, path, title, etc.) and note metadata (noteinfo, updatedby,
 * wassignedby) are stripped, matching the format expected by the importer.</p>
 */
public class DominoBlueprintExporter {

    // -----------------------------------------------------------------------
    // Core export flow
    // -----------------------------------------------------------------------

    /**
     * Open the database and run the export.
     *
     * <p><b>Session vs. DIIOP:</b> the {@code session} passed in must be a
     * <em>local</em> session (see {@link DominoBlueprint}); the server name is
     * supplied only to {@code session.getDatabase()} so that databases on remote
     * servers can be opened without requiring DIIOP.</p>
     *
     * <p>The caller (the dispatcher) owns the session lifecycle.  This method
     * recycles only the {@link Database} it opens, not the session.</p>
     *
     * @param session   an open local Notes session (owned by the caller)
     * @param server    Domino server name; use {@code ""} for local
     * @param database  database file path, e.g. {@code apps/mydb.nsf}
     * @param outputDir output root directory for the exported DXL tree
     */
    public static void export(Session session, String server, String database, String outputDir)
            throws Exception {

        // Open the database – server name goes HERE, not in createSession()
        Database db = session.getDatabase(server, database, false);
        if (db == null) {
            throw new Exception("Database not found: " + database);
        }
        if (!db.isOpen()) {
            db.open();
        }
        if (!db.isOpen()) {
            throw new Exception("Cannot open database: " + database
                    + "  (check server, path, and ID permissions)");
        }

        System.out.println("Exporting from   : " + db.getTitle()
                + "  (" + db.getFilePath() + ")");
        System.out.println("Output directory : " + outputDir);
        System.out.println();

        try {
            // Run the exporter
            DesignExporter exporter = new DesignExporter(session, db, outputDir);
            exporter.exportAll();
            System.out.println("Export complete.");
        }
        finally {
            // Recycle only the database; the session is owned by the caller.
            db.recycle();
        }
    }
}
