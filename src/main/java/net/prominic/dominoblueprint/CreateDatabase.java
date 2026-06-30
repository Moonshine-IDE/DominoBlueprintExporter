package net.prominic.dominoblueprint;

import lotus.domino.ACL;
import lotus.domino.ACLEntry;
import lotus.domino.Database;
import lotus.domino.DbDirectory;
import lotus.domino.NotesException;
import lotus.domino.Session;

/**
 * Create a blank database suitable for DominoBlueprint DXL imports.
 *
 * <p>Invoked via the {@code createdb} subcommand of {@link DominoBlueprint};
 * the dispatcher owns the Notes session and password handling, so this class
 * only contains the database-creation work method.</p>
 *
 * <p>The created database is intentionally <b>empty</b> &mdash; it has no forms
 * and, deliberately, <b>no default view</b>.  Earlier versions created a
 * {@code (default)} view here, but that view does not exist in the source
 * blueprint and so produced spurious diffs on export/import round-trips.  A
 * Notes database needs at least one view to open in the client, so a warning is
 * printed reminding the caller to import a blueprint (which supplies the views)
 * before opening the database.</p>
 */
public class CreateDatabase {

    /**
     * Create a blank database on {@code server} at {@code databaseName}.
     *
     * <p>If the database already exists it is left untouched and the method
     * returns without printing the post-creation warning.  When a new database
     * is created its title is derived from the file name and a minimal ACL is
     * configured ({@code -Default-} = Manager, {@code Anonymous} = Editor).</p>
     *
     * @param session      an open Notes session (owned by the caller)
     * @param server       Domino server name; use {@code ""} for local
     * @param databaseName target database path, e.g. {@code apps/mydb.nsf}
     */
    public static void createDatabase(Session session, String server, String databaseName)
            throws NotesException, Exception {
        DbDirectory dbDirectory = null;
        Database database = null;
        ACL acl = null;

        try {
            // Check if the database already exists.
            //
            // The third parameter to getDatabase() MUST stay false: when true, Domino
            // auto-creates the database on miss (which typically fails with permission
            // errors).  With false, getDatabase() opens the database if it exists and
            // returns a non-null-but-unopened handle if it does not, so existence is
            // confirmed with isOpen() rather than a null check.
            database = session.getDatabase(server, databaseName, false);
            if (null != database && database.isOpen()) {
                System.out.println("Database '" + databaseName + "' already exists.  Skipping...");
                return;
                // TODO:  delete instead?
            }

            // NOTE: The database could also be created from a template.  See CreateNamesDatabase for an example.

            // Create with DBDirectory:  https://help.hcl-software.com/dom_designer/14.0.0/basic/H_CREATE_METHOD_JAVA.html
            // If "" is used for the server the database will be created in the directory configured in notes.ini
            dbDirectory = session.getDbDirectory(server);
            // The second parameter will open the database so that more options may be run.
            System.out.println("Creating Database '" + databaseName + "'.");
            database = dbDirectory.createDatabase(databaseName, true);
            // The database is blank, with no forms or views.
            //
            // NOTE: We deliberately do NOT create a default "(default)" view here.
            // The blueprint being imported supplies its own views, and a view
            // created here has no counterpart in the source, which generated
            // mismatches on export/import round-trips.  The closing warning below
            // reminds the caller that the database will not open in the Notes
            // client until at least one view has been imported.

            // For the title, use the database name, but strip the directory and extension
            String title = databaseName;
            int index = title.lastIndexOf('.');
            if (index >= 0) {
                title = title.substring(0, index);
            }
            index = title.lastIndexOf("/");
            if (index < 0) { // no match
                index = title.lastIndexOf("\\");  // try backslash instead
            }
            if (index >= 0) {
                title = title.substring(index + 1);
            }
            System.out.println("Setting title to '" + title + "'.");
            database.setTitle(title);

            // Update the ACL
            // Update default to allow user access from Notes or Designer
            // TODO: support user ID
            System.out.println("Setting ACL for database '" + databaseName + "'.");
            acl = database.getACL();
            ACLEntry defaultEntry = acl.getEntry("-Default-");
            if (null == defaultEntry) {
                defaultEntry = acl.createACLEntry("-Default-", ACL.LEVEL_MANAGER);
            }
            ACLEntry anonymousEntry = acl.getEntry("Anonymous");
            if (null == anonymousEntry) {
                anonymousEntry = acl.createACLEntry("Anonymous", ACL.LEVEL_EDITOR);   // this should be sufficient for most agents
                anonymousEntry.setUserType(ACLEntry.TYPE_PERSON);
                // minimal roles for agent - included in EDITOR
                // anonymousEntry.setPublicReader(true);
                // anonymousEntry.setPublicWriter(true);
                // anonymousEntry.setCanReplicateOrCopyDocuments(true);
            }

            System.out.println(databaseName + " is ready for use.");

            // The database has no views yet (see note above), so it will not open
            // in the Notes client until a blueprint is imported into it.
            System.out.println();
            System.out.println("  [WARNING] '" + databaseName + "' was created with NO views.");
            System.out.println("            A Notes database needs at least one view to open in the");
            System.out.println("            client.  Import a DominoBlueprint into it (the 'import'");
            System.out.println("            subcommand) before attempting to open it; the blueprint");
            System.out.println("            supplies the views.");
        }
        finally {
            if (null != database) {
                database.recycle();
            }
            if (null != dbDirectory) {
                dbDirectory.recycle();
            }
        }
    }
}
