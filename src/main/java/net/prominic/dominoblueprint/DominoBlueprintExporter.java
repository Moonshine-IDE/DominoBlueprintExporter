package net.prominic.dominoblueprint;

import lotus.domino.Database;
import lotus.domino.NotesFactory;
import lotus.domino.NotesThread;
import lotus.domino.Session;

import java.io.Console;
import java.util.Arrays;

/**
 * DominoBlueprint Exporter – Command-line application for exporting HCL Domino design elements.
 *
 * <p>Connects to a Domino database and exports its design elements as individual
 * DXL files organised into six category directories:
 *
 * <pre>
 *   &lt;outputDir&gt;/
 *     forms/      – Forms, subforms, shared fields
 *     views/      – Views, folders, shared columns
 *     code/       – Agents, Script Libraries, shared actions  (Java code excluded)
 *     resources/  – Image, stylesheet, and file resources     (Java resources excluded)
 *     pages/      – Pages, framesets, outlines, navigators
 *     other/      – Database script/icon, Help About/Using, data connections,
 *                   replication formulas, profile documents, misc design notes
 * </pre>
 *
 * <p>The ACL is not exported here; it is handled by a separate tool.
 *
 * <p>Each exported file is cleaned for re-import: database-specific attributes
 * (replicaid, path, title, etc.) and note metadata (noteinfo, updatedby,
 * wassignedby) are stripped, matching the format expected by DXLImport.jar.
 *
 * <h3>Password resolution order</h3>
 * <ol>
 *   <li>{@code --password} / {@code -p} command-line flag</li>
 *   <li>{@code PASSWORD} environment variable</li>
 *   <li>Interactive prompt (input is not echoed) – used when running manually</li>
 *   <li>No password (ID has no password, or Notes already has an open session)</li>
 * </ol>
 *
 * <h3>Usage</h3>
 * <pre>
 *   # Interactive – will prompt for password if the ID requires one
 *   java -jar DominoBlueprintExporter.jar myserver/Org apps/mydb.nsf ./export
 *
 *   # Automation / CI – supply password via environment variable (preferred)
 *   PASSWORD=secret java -jar DominoBlueprintExporter.jar myserver/Org apps/mydb.nsf ./export
 *
 *   # Via Gradle
 *   gradle -PnotesInstallation=/path/to/notes \
 *          -PnotesIDPassword=secret \
 *          -Pserver=myserver/Org \
 *          -PdbName=apps/mydb.nsf \
 *          runExporter
 * </pre>
 */
public class DominoBlueprintExporter {

    // -----------------------------------------------------------------------
    // Entry point
    // -----------------------------------------------------------------------

    public static void main(String[] args) {

        // Defaults
        String  server    = "";
        String  database  = null;
        String  outputDir = "./export";
        // password starts null – resolved later so we can print the header first
        String  password  = null;
        boolean passwordFromFlag = false;   // true  → --password flag was used
        boolean passwordFromEnv  = false;   // true  → PASSWORD env var was used

        // Check env var first (lowest priority – flag can override)
        String envPassword = System.getenv("PASSWORD");
        if (envPassword != null && !envPassword.isEmpty()) {
            password        = envPassword;
            passwordFromEnv = true;
        }

        // Parse command-line arguments
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "-s":
                case "--server":
                    if (i + 1 < args.length) { server = args[++i]; }
                    break;
                case "-d":
                case "--database":
                    if (i + 1 < args.length) { database = args[++i]; }
                    break;
                case "-o":
                case "--output":
                    if (i + 1 < args.length) { outputDir = args[++i]; }
                    break;
                case "-p":
                case "--password":
                    if (i + 1 < args.length) {
                        password         = args[++i];
                        passwordFromFlag = true;
                        passwordFromEnv  = false;
                    }
                    break;
                case "-h":
                case "--help":
                    printUsage();
                    return;
                default:
                    // Support positional arguments: [server] <database> [outputDir]
                    if (!arg.startsWith("-")) {
                        if (database == null && !server.isEmpty()) {
                            // server was set positionally; this must be database
                            database = arg;
                        } else if (database == null) {
                            // first positional: treat as server (may be empty string for local)
                            server = arg;
                        } else {
                            // third positional: output directory
                            outputDir = arg;
                        }
                    } else {
                        System.err.println("Unknown option: " + arg);
                        System.err.println();
                        printUsage();
                        System.exit(1);
                    }
            }
        }

        if (database == null || database.isEmpty()) {
            System.err.println("Error: Database path is required.");
            System.err.println();
            printUsage();
            System.exit(1);
        }

        // ------------------------------------------------------------------
        // Password resolution
        //
        // Priority: --password flag > PASSWORD env var > interactive prompt.
        // We discourage plaintext on the command line and suggest the env var
        // or the interactive prompt for manual use.
        // ------------------------------------------------------------------
        if (passwordFromFlag) {
            System.out.println("[password supplied via --password flag]");
            System.out.println("  Tip: prefer the PASSWORD environment variable to keep");
            System.out.println("  credentials out of shell history and process listings.");
            System.out.println();
        } else if (passwordFromEnv) {
            // Quietly accepted – the env var is the recommended automation path
        } else {
            // No password yet: try an interactive prompt
            password = promptForPassword();
        }

        // Run the export inside a Notes thread
        char[] passwordChars = (password != null) ? password.toCharArray() : null;
        try {
            NotesThread.sinitThread();
            runExport(server, database, outputDir, passwordChars);
        } catch (Exception e) {
            System.err.println("Fatal error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        } finally {
            // Wipe the password from memory as soon as we are done with it
            if (passwordChars != null) Arrays.fill(passwordChars, '\0');
            NotesThread.stermThread();
        }
    }

    // -----------------------------------------------------------------------
    // Interactive password prompt
    // -----------------------------------------------------------------------

    /**
     * Attempt to read the Notes ID password from the terminal without echoing
     * the characters to the screen.
     *
     * <p>Uses {@link Console#readPassword} when a console is available (i.e. when
     * the process is attached to a real terminal).  If no console is available
     * (e.g. output is piped, or the process was launched by a CI runner), the
     * method returns {@code null} and the export proceeds without a password –
     * suitable for IDs that have no password or when Notes already has an open
     * session.
     *
     * @return the entered password string, an empty string if the user pressed
     *         Enter with no input, or {@code null} if no console is available
     */
    private static String promptForPassword() {
        Console console = System.console();

        if (console == null) {
            // Non-interactive environment (pipe, CI runner, IDE launcher…).
            // Proceed without a password; Notes will use whatever ID is cached.
            return null;
        }

        System.out.println("No password was provided via --password or the PASSWORD");
        System.out.println("environment variable.");
        System.out.println();
        System.out.println("  For automation, set the PASSWORD environment variable:");
        System.out.println("    PASSWORD=secret java -jar DominoBlueprintExporter.jar ...");
        System.out.println();

        char[] chars = console.readPassword("Notes ID password (press Enter if none): ");

        if (chars == null || chars.length == 0) {
            return null;  // user pressed Enter – treat as no password
        }

        String password = new String(chars);
        Arrays.fill(chars, '\0');  // wipe the char array immediately
        return password;
    }

    // -----------------------------------------------------------------------
    // Core export flow
    // -----------------------------------------------------------------------

    /**
     * Open the database and run the export.
     *
     * <p><b>Session vs. DIIOP:</b> passing a non-empty server name to
     * {@code NotesFactory.createSession()} triggers a remote DIIOP connection
     * over HTTP, which most Domino servers do not have enabled.  Instead, we
     * always create a <em>local</em> session (using the Domino runtime installed
     * on the current machine) and supply the server name only to
     * {@code session.getDatabase()}.  This is identical to how {@code DXLImport.jar},
     * {@code CreateDatabase.jar}, and the Gradle tasks in
     * {@code DXLImporter-Gradle-Demo} operate when running on the server.
     *
     * @param passwordChars Notes ID password as a char array, or {@code null} / empty
     *                      if no password is required.  The caller is responsible for
     *                      wiping the array after this method returns.
     */
    private static void runExport(String server, String database, String outputDir,
                                  char[] passwordChars) throws Exception {

        System.out.println("Database server      : " + (server.isEmpty() ? "(local)" : server));
        System.out.println("Database path        : " + database);

        // Convert to String only at the last moment; Notes API requires String
        String password = (passwordChars != null && passwordChars.length > 0)
                          ? new String(passwordChars) : null;

        // Always create a LOCAL session – the server name must NOT be passed here.
        // Passing a server name to createSession() triggers a DIIOP remote connection,
        // which requires the HTTP task and DIIOP to be running on the target server.
        // A local session can still open databases on remote servers via getDatabase().
        Session session = (password != null && !password.isEmpty())
                ? NotesFactory.createSession((String) null, (String) null, password)
                : NotesFactory.createSession();

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

        System.out.println("Exporting from       : " + db.getTitle()
                + "  (" + db.getFilePath() + ")");
        System.out.println("Output directory     : " + outputDir);
        System.out.println();

        // Run the exporter
        DesignExporter exporter = new DesignExporter(session, db, outputDir);
        exporter.exportAll();

        System.out.println("Export complete.");

        // Recycle Domino objects
        db.recycle();
        session.recycle();
    }

    // -----------------------------------------------------------------------
    // Usage text
    // -----------------------------------------------------------------------

    private static void printUsage() {
        System.out.println("DominoBlueprint Exporter – Export HCL Domino design elements to DXL files");
        System.out.println();
        System.out.println("Usage:");
        System.out.println("  java -jar DominoBlueprintExporter.jar [options] <server> <database> [outputDir]");
        System.out.println();
        System.out.println("Positional arguments (in order):");
        System.out.println("  server      Domino server name (use \"\" or '' for local)");
        System.out.println("  database    Database file path, e.g. apps/mydb.nsf");
        System.out.println("  outputDir   Output root directory (default: ./export)");
        System.out.println();
        System.out.println("Named options:");
        System.out.println("  -s, --server   <server>    Domino server name");
        System.out.println("  -d, --database <path>      Database file path (required)");
        System.out.println("  -o, --output   <dir>       Output directory (default: ./export)");
        System.out.println("  -p, --password <password>  Notes ID password (see note below)");
        System.out.println("  -h, --help                 Show this help");
        System.out.println();
        System.out.println("Password (resolved in this order):");
        System.out.println("  1. --password flag  (visible in shell history – avoid for sensitive IDs)");
        System.out.println("  2. PASSWORD env var (recommended for automation/CI)");
        System.out.println("  3. Interactive prompt with hidden input (used when running manually)");
        System.out.println("  4. No password      (ID has no password, or session already open)");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  # Interactive – prompts for password");
        System.out.println("  java -jar DominoBlueprintExporter.jar myserver/Org apps/mydb.nsf ./export");
        System.out.println();
        System.out.println("  # Automation – password via env var");
        System.out.println("  PASSWORD=secret java -jar DominoBlueprintExporter.jar myserver/Org apps/mydb.nsf ./export");
        System.out.println();
        System.out.println("  # Local database, no password");
        System.out.println("  java -jar DominoBlueprintExporter.jar \"\" local.nsf ./export");
        System.out.println();
        System.out.println("Output structure:");
        System.out.println("  <outputDir>/forms/      – Forms, subforms, shared fields");
        System.out.println("  <outputDir>/views/      – Views, folders, shared columns");
        System.out.println("  <outputDir>/code/       – Agents, Script Libraries, shared actions (non-Java)");
        System.out.println("  <outputDir>/resources/  – Image, stylesheet, and file resources (non-Java)");
        System.out.println("  <outputDir>/pages/      – Pages, framesets, outlines, navigators");
        System.out.println("  <outputDir>/other/      – Database script/icon, Help docs, data connections,");
        System.out.println("                            replication formulas, profile docs, misc design");
        System.out.println();
        System.out.println("Note: ACL is not exported by this tool.");
    }
}
