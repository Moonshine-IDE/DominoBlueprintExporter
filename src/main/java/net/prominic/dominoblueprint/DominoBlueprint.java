package net.prominic.dominoblueprint;

import java.io.Console;
import java.io.File;
import java.util.Arrays;

import lotus.domino.Database;
import lotus.domino.NotesFactory;
import lotus.domino.NotesThread;
import lotus.domino.Session;

/**
 * DominoBlueprint – single command-line entry point for the DominoBlueprint
 * round-trip toolkit.
 *
 * <p>Dispatches to one of three subcommands, each backed by a work class in this
 * package:</p>
 *
 * <pre>
 *   export     Export a database's design to a DominoBlueprint DXL tree   ({@link DominoBlueprintExporter})
 *   createdb   Create a blank target database for an import               ({@link CreateDatabase})
 *   import     Import a DXL file or blueprint tree into a database        ({@link DominoBlueprintImport})
 * </pre>
 *
 * <p>All subcommands share the same flags and the same authentication flow.
 * This class owns argument parsing, password resolution, and the single local
 * Notes session; the work classes contain no {@code main} of their own and
 * operate on the session handed to them.</p>
 *
 * <h3>Common flags</h3>
 * <ul>
 *   <li>{@code -s, --server   <server>}   Domino server name (use {@code ""} for local)</li>
 *   <li>{@code -d, --database <path>}     Database file path, e.g. {@code apps/mydb.nsf} (required)</li>
 *   <li>{@code -p, --password <password>} Notes ID password (prefer the PASSWORD env var)</li>
 *   <li>{@code -h, --help}                Show help</li>
 * </ul>
 *
 * <h3>Subcommand-specific flags</h3>
 * <ul>
 *   <li>{@code export}:  {@code -o, --output <dir>}   Output root directory (default {@code ./export})</li>
 *   <li>{@code import}:  {@code -i, --input <path>}   A {@code .dxl} file or a directory of them (required)</li>
 *   <li>{@code import}:  {@code --acl-import=<mode>}  How the DXL ACL is applied (default {@code update-else-create})</li>
 *   <li>{@code createdb}:{@code  -t, --title <title>} Set the initial database title; optional. A later
 *                                                     {@code import} usually overwrites it (see {@code import --title})</li>
 *   <li>{@code import}:  {@code  -t, --title <title>} Set the database title after import; overrides the title
 *                                                     carried by the imported icon design note (optional)</li>
 * </ul>
 *
 * <h3>Password resolution order</h3>
 * <ol>
 *   <li>{@code --password} / {@code -p} flag</li>
 *   <li>{@code PASSWORD} environment variable</li>
 *   <li>Interactive prompt (input is not echoed) when attached to a terminal</li>
 *   <li>No password (ID has no password, or Notes already has an open session)</li>
 * </ol>
 *
 * <h3>Usage</h3>
 * <pre>
 *   java -jar DominoBlueprint.jar export   -d apps/mydb.nsf -o ./export
 *   java -jar DominoBlueprint.jar createdb -d apps/restored.nsf
 *   java -jar DominoBlueprint.jar import   -d apps/restored.nsf -i ./export
 * </pre>
 */
public class DominoBlueprint {

    private static final String APP_NAME = "DominoBlueprint";

    public static void main(String[] args) {

        // --------------------------------------------------------------
        // Subcommand selection
        // --------------------------------------------------------------
        if (args.length == 0) {
            printTopUsage();
            System.exit(1);
        }

        String subcommand = args[0];
        if ("-h".equals(subcommand) || "--help".equals(subcommand)) {
            printTopUsage();
            return;
        }

        // Normalise the subcommand name (accept create-db as an alias of createdb)
        String command = subcommand.toLowerCase();
        if ("create-db".equals(command)) {
            command = "createdb";
        }
        if (!("export".equals(command) || "createdb".equals(command) || "import".equals(command))) {
            System.err.println("ERROR: Unknown subcommand '" + subcommand + "'.");
            System.err.println();
            printTopUsage();
            System.exit(1);
        }

        // --------------------------------------------------------------
        // Flag parsing (shared across subcommands)
        // --------------------------------------------------------------
        String  server    = "";
        String  database  = null;
        String  outputDir = "./export";     // export only
        String  input     = null;           // import only
        String  password  = null;
        boolean passwordFromFlag = false;
        boolean passwordFromEnv  = false;
        int     aclImportOption  = DominoBlueprintImport.DEFAULT_ACL_IMPORT_OPTION;  // import only
        boolean failOnCompileError = false;                                          // import only
        String  title             = null;                                            // import + createdb

        // PASSWORD env var first (lowest priority – the flag can override)
        String envPassword = System.getenv("PASSWORD");
        if (envPassword != null && !envPassword.isEmpty()) {
            password        = envPassword;
            passwordFromEnv = true;
        }

        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            if (arg.startsWith("--acl-import=")) {
                if (!"import".equals(command)) { unsupportedFlag(arg, command); }
                try {
                    aclImportOption = DominoBlueprintImport.parseAclImportMode(arg.substring("--acl-import=".length()));
                } catch (IllegalArgumentException ex) {
                    System.err.println("ERROR: " + ex.getMessage());
                    System.exit(1);
                }
                continue;
            }
            if (arg.startsWith("--title=")) {
                if (!("import".equals(command) || "createdb".equals(command))) { unsupportedFlag(arg, command); }
                title = arg.substring("--title=".length());
                continue;
            }
            switch (arg) {
                case "-s":
                case "--server":
                    server = nextValue(args, ++i, arg);
                    break;
                case "-d":
                case "--database":
                    database = nextValue(args, ++i, arg);
                    break;
                case "-o":
                case "--output":
                    if (!"export".equals(command)) { unsupportedFlag(arg, command); }
                    outputDir = nextValue(args, ++i, arg);
                    break;
                case "-i":
                case "--input":
                    if (!"import".equals(command)) { unsupportedFlag(arg, command); }
                    input = nextValue(args, ++i, arg);
                    break;
                case "--acl-import":
                    if (!"import".equals(command)) { unsupportedFlag(arg, command); }
                    try {
                        aclImportOption = DominoBlueprintImport.parseAclImportMode(nextValue(args, ++i, arg));
                    } catch (IllegalArgumentException ex) {
                        System.err.println("ERROR: " + ex.getMessage());
                        System.exit(1);
                    }
                    break;
                case "-t":
                case "--title":
                    if (!("import".equals(command) || "createdb".equals(command))) { unsupportedFlag(arg, command); }
                    title = nextValue(args, ++i, arg);
                    break;
                case "--fail-on-compile-error":
                    if (!"import".equals(command)) { unsupportedFlag(arg, command); }
                    failOnCompileError = true;
                    break;
                case "-p":
                case "--password":
                    password         = nextValue(args, ++i, arg);
                    passwordFromFlag = true;
                    passwordFromEnv  = false;
                    break;
                case "-h":
                case "--help":
                    printSubUsage(command);
                    return;
                default:
                    System.err.println("ERROR: Unknown option '" + arg + "' for subcommand '" + command + "'.");
                    System.err.println();
                    printSubUsage(command);
                    System.exit(1);
            }
        }

        // --------------------------------------------------------------
        // Per-subcommand validation
        // --------------------------------------------------------------
        if (database == null || database.isEmpty()) {
            System.err.println("ERROR: Database path is required.  Add -d <database.nsf>.");
            System.err.println();
            printSubUsage(command);
            System.exit(1);
        }
        if ("import".equals(command) && (input == null || input.isEmpty())) {
            System.err.println("ERROR: Import source is required.  Add -i <dxl-file-or-directory>.");
            System.err.println();
            printSubUsage(command);
            System.exit(1);
        }

        // --------------------------------------------------------------
        // Password resolution
        // --------------------------------------------------------------
        if (passwordFromFlag) {
            System.out.println("[password supplied via --password flag]");
            System.out.println("  Tip: prefer the PASSWORD environment variable to keep");
            System.out.println("  credentials out of shell history and process listings.");
            System.out.println();
        } else if (passwordFromEnv) {
            // Quietly accepted – the env var is the recommended automation path
        } else {
            password = promptForPassword();
        }

        // --------------------------------------------------------------
        // Run inside a Notes thread with a single local session
        // --------------------------------------------------------------
        System.out.println("Application '" + APP_NAME + " " + command + "' started.");
        char[] passwordChars = (password != null) ? password.toCharArray() : null;
        Session session = null;
        try {
            NotesThread.sinitThread();
            session = createLocalSession(password);
            System.out.println("Running as user: '" + session.getUserName() + "'.");
            System.out.println("Database server : " + (server.isEmpty() ? "(local)" : server));
            System.out.println("Database path   : " + database);

            if ("export".equals(command)) {
                DominoBlueprintExporter.export(session, server, database, outputDir);
            } else if ("createdb".equals(command)) {
                CreateDatabase.createDatabase(session, server, database, title);
            } else { // import
                runImport(session, server, database, input, aclImportOption, failOnCompileError, title);
            }
        } catch (Throwable t) {
            System.err.println("Fatal error: " + t.getMessage());
            t.printStackTrace();
            System.exit(1);  // non-zero exit for scripting
        } finally {
            try {
                if (null != session) {
                    session.recycle();
                }
            } catch (Exception ignore) { /* best effort */ }
            if (passwordChars != null) Arrays.fill(passwordChars, '\0');
            NotesThread.stermThread();
            System.out.println("Application '" + APP_NAME + " " + command + "' completed.");
        }
    }

    // -----------------------------------------------------------------------
    // Import dispatch helper
    // -----------------------------------------------------------------------

    private static void runImport(Session session, String server, String database,
                                  String input, int aclImportOption, boolean failOnCompileError,
                                  String title) throws Exception {
        File source = new File(input);
        if (!source.exists()) {
            throw new Exception("Import source not found at: '" + source.getAbsolutePath() + "'.");
        }
        System.out.println("ACL import option: " + DominoBlueprintImport.aclImportOptionName(aclImportOption));
        if (source.isDirectory()) {
            DominoBlueprintImport.importDXLDirectory(session, server, database, source, aclImportOption, failOnCompileError);
        } else {
            DominoBlueprintImport.importDXL(session, server, database, source, aclImportOption);
        }

        // Apply the database title LAST — after every design note has been imported,
        // including the icon design note whose $TITLE item carries the SOURCE
        // database's title. Because the icon import is the last thing to write the
        // title, setting it here (rather than in createdb) is what makes a custom
        // title stick. No-op when --title was not supplied.
        if (title != null && !title.isEmpty()) {
            Database db = session.getDatabase(server, database, false);
            if (null == db || !db.isOpen()) {
                throw new Exception("Could not open database to set title: '" + database + "'.");
            }
            System.out.println("Setting database title to '" + title + "'.");
            db.setTitle(title);
            db.recycle();
        }
    }

    // -----------------------------------------------------------------------
    // Session helper
    //
    // Always create a LOCAL session – the server name must NOT be passed here.
    // Passing a server name to createSession() triggers a DIIOP remote connection,
    // which requires the HTTP task and DIIOP on the target server.  A local session
    // still opens databases on remote servers via session.getDatabase(server, ...).
    // -----------------------------------------------------------------------

    private static Session createLocalSession(String password) throws Exception {
        if (password != null && !password.isEmpty()) {
            return NotesFactory.createSession((String) null, (String) null, password);
        }
        return NotesFactory.createSession();
    }

    // -----------------------------------------------------------------------
    // Argument helpers
    // -----------------------------------------------------------------------

    /** Return args[index], or exit with an error if the flag has no value. */
    private static String nextValue(String[] args, int index, String flag) {
        if (index >= args.length) {
            System.err.println("ERROR: option '" + flag + "' requires a value.");
            System.exit(1);
        }
        return args[index];
    }

    private static void unsupportedFlag(String flag, String command) {
        System.err.println("ERROR: option '" + flag + "' is not valid for subcommand '" + command + "'.");
        System.err.println();
        printSubUsage(command);
        System.exit(1);
    }

    // -----------------------------------------------------------------------
    // Interactive password prompt
    // -----------------------------------------------------------------------

    /**
     * Read the Notes ID password from the terminal without echoing it.  Returns
     * {@code null} when no console is available (piped output, CI runner, IDE
     * launcher), in which case the session proceeds without a password.
     */
    private static String promptForPassword() {
        Console console = System.console();
        if (console == null) {
            return null;  // non-interactive – use whatever ID is cached
        }
        System.out.println("No password was provided via --password or the PASSWORD environment variable.");
        System.out.println("  For automation, set the PASSWORD environment variable:");
        System.out.println("    PASSWORD=secret java -jar DominoBlueprint.jar <subcommand> ...");
        System.out.println();
        char[] chars = console.readPassword("Notes ID password (press Enter if none): ");
        if (chars == null || chars.length == 0) {
            return null;
        }
        String password = new String(chars);
        Arrays.fill(chars, '\0');  // wipe the char array immediately
        return password;
    }

    // -----------------------------------------------------------------------
    // Usage text
    // -----------------------------------------------------------------------

    private static void printTopUsage() {
        System.out.println("DominoBlueprint – HCL Domino design export / import round-trip toolkit");
        System.out.println();
        System.out.println("Usage:");
        System.out.println("  java -jar DominoBlueprint.jar <subcommand> [options]");
        System.out.println();
        System.out.println("Subcommands:");
        System.out.println("  export     Export a database's design to a DominoBlueprint DXL tree");
        System.out.println("  createdb   Create a blank target database (no views) for an import");
        System.out.println("  import     Import a DXL file or blueprint tree into a database");
        System.out.println();
        System.out.println("Common options:");
        System.out.println("  -s, --server   <server>    Domino server name (use \"\" for local)");
        System.out.println("  -d, --database <path>      Database file path (required), e.g. apps/mydb.nsf");
        System.out.println("  -p, --password <password>  Notes ID password (prefer the PASSWORD env var)");
        System.out.println("  -h, --help                 Show help (per subcommand: <subcommand> --help)");
        System.out.println();
        System.out.println("Run 'java -jar DominoBlueprint.jar <subcommand> --help' for subcommand options.");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  java -jar DominoBlueprint.jar export   -d apps/mydb.nsf -o ./export");
        System.out.println("  java -jar DominoBlueprint.jar createdb -d apps/restored.nsf");
        System.out.println("  java -jar DominoBlueprint.jar import   -d apps/restored.nsf -i ./export");
    }

    private static void printSubUsage(String command) {
        System.out.println("Common options:");
        System.out.println("  -s, --server   <server>    Domino server name (use \"\" for local)");
        System.out.println("  -d, --database <path>      Database file path (required)");
        System.out.println("  -p, --password <password>  Notes ID password (prefer the PASSWORD env var)");
        System.out.println("  -h, --help                 Show this help");
        System.out.println();
        if ("export".equals(command)) {
            System.out.println("export options:");
            System.out.println("  -o, --output   <dir>       Output root directory (default: ./export)");
            System.out.println();
            System.out.println("Example:");
            System.out.println("  java -jar DominoBlueprint.jar export -d apps/mydb.nsf -o ./export");
        } else if ("createdb".equals(command)) {
            System.out.println("createdb options:");
            System.out.println("  -t, --title <title>        Initial database title.  Optional; if omitted the");
            System.out.println("                             title is derived from the file name.  NOTE: a later");
            System.out.println("                             'import' re-imports the icon note and usually");
            System.out.println("                             overwrites this -- use 'import --title' for a title");
            System.out.println("                             that survives an import.");
            System.out.println();
            System.out.println("createdb creates a blank database with NO views.  Import a blueprint");
            System.out.println("(which supplies the views) before opening it in the Notes client.");
            System.out.println();
            System.out.println("Example:");
            System.out.println("  java -jar DominoBlueprint.jar createdb -d apps/restored.nsf");
        } else { // import
            System.out.println("import options:");
            System.out.println("  -i, --input    <path>      A .dxl file or a directory of .dxl files (required)");
            System.out.println("  -t, --title <title>        Set the database title after import.  Overrides the");
            System.out.println("                             title carried by the imported icon note.  Optional.");
            System.out.println("  --acl-import=<mode>        How the DXL ACL is applied to the target.");
            System.out.println("                             Default: update-else-create.  Modes (case-insensitive):");
            System.out.println("                               ignore               – Skip ACL in DXL.");
            System.out.println("                               create               – Set ACL only if target has none.");
            System.out.println("                               replace              – Replace target ACL with DXL ACL.");
            System.out.println("                               replace-else-ignore  – Same as 'replace'.");
            System.out.println("                               replace-else-create  – Replace if present, create if not.");
            System.out.println("                               update               – Update matched entries, create new ones.");
            System.out.println("                               update-else-create   – Same as 'update' (default).");
            System.out.println("                               update-else-ignore   – Update matched entries, ignore new ones.");
            System.out.println();
            System.out.println("Examples:");
            System.out.println("  # Import a full blueprint tree (merges ACL, preserves target-only entries)");
            System.out.println("  java -jar DominoBlueprint.jar import -d apps/restored.nsf -i ./export");
            System.out.println();
            System.out.println("  # Import a tree and stamp a custom database title");
            System.out.println("  java -jar DominoBlueprint.jar import -d apps/restored.nsf -i ./export --title \"My DB - build 42\"");
            System.out.println();
            System.out.println("  # Import a single DXL file, replacing the target ACL exactly");
            System.out.println("  java -jar DominoBlueprint.jar import -d apps/restored.nsf -i ./export/acl/acl.dxl --acl-import=replace");
        }
    }
}
