package net.prominic.dominoblueprint;

import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Processes raw DXL exported by Domino's {@code DxlExporter}.
 *
 * <p>Responsibilities:
 * <ol>
 *   <li><b>Split</b> – a single DXL string containing multiple design elements is
 *       separated into one {@link DesignElement} per note.</li>
 *   <li><b>Clean</b> – removes information that must not be present when the DXL
 *       is imported into a different (or new) database:
 *       <ul>
 *         <li>{@code <databaseinfo>} element</li>
 *         <li>{@code replicaid}, {@code path}, {@code title} attributes on the root
 *             {@code <database>} element &mdash; source-replica identity, not portable</li>
 *         <li>{@code <noteinfo>}, {@code <updatedby>}, {@code <wassignedby>} children
 *             of each design element</li>
 *         <li>{@code <rundata>}, {@code <runlog>}, {@code <designchange>} children
 *             of each design element — agent run history / modified-timestamp
 *             noise a freshly imported copy has not accumulated yet</li>
 *         <li>{@code <item name="$DesignerBuild">} — a Designer-applied build
 *             stamp, not source design content</li>
 *         <li>Empty {@code <code event="declarations"><lotusscript/></code>} blocks
 *             — Domino does not re-emit an empty declarations event on import</li>
 *         <li>Portable database-settings attributes ({@link #DATABASE_SETTINGS_ATTRS}) on
 *             every file except the {@code <launchsettings>} element's, so they exist in
 *             exactly one exported file instead of duplicated everywhere</li>
 *       </ul>
 *   </li>
 *   <li><b>Detect Java</b> – an element is flagged as Java code when its own tag
 *       name matches a Java-only tag (e.g. {@code <javaresource>}) or when any
 *       descendant is a {@code <javaproject>} element, allowing the caller to skip
 *       it when exporting the {@code code/} or {@code resources/} categories.</li>
 *   <li><b>Detect excluded noise</b> – flags elements that are not true design but
 *       still surface in the export: per-user private replication formulas (named
 *       after the user, e.g. {@code CN=Jane Doe/O=Acme}) and XPages build artifacts
 *       (file resources under {@code WEB-INF/}, Eclipse dotfiles, {@code plugin.xml},
 *       {@code build.properties}).</li>
 * </ol>
 *
 * <p>The output matches the format used by the manually-exported example files
 * in the project (e.g. {@code HelloWorld.dxl}) and is compatible with
 * the {@code import} subcommand ({@code java -jar DominoBlueprint.jar import ...}).
 */
public class DxlProcessor {

    // -----------------------------------------------------------------------
    // Constants
    // -----------------------------------------------------------------------

    /**
     * Database-level attributes that identify the source replica and must be removed
     * before re-import.
     *
     * <p>{@code increasemaxfields}, {@code compressdesign}, {@code compressdata}, and
     * {@code uselz1} (Advanced tab: Allow more fields / Compress design / Compress data /
     * LZ1 compression) used to be stripped here too, on the assumption they were
     * source-specific like {@code replicaid}/{@code path}. A 2026-07-02 test DB
     * ({@code TestDominoBlueprintExporter-v3}, Compress design + Compress data toggled on)
     * confirmed they are real, portable design settings with no other encoding anywhere
     * in the export (not in {@code $Flags}, not duplicated on the icon note) &mdash; so
     * they are restored here and now round-trip like the other {@code <database>}
     * attributes. See item B in {@code DominoBlueprint_RoundTrip_Status.md}.
     */
    private static final Set<String> DATABASE_ATTRS_TO_REMOVE = new HashSet<>(Arrays.asList(
            "replicaid", "path", "title"
    ));

    /**
     * Portable {@code <database>} attributes that represent real Advanced/Design/Basics
     * tab settings (as opposed to source-replica identity): {@code categories},
     * {@code defaultlanguage}, {@code fromtemplate}, {@code maintainunread},
     * {@code maxrevisionentries}, {@code softdeletionsexpirein}, {@code allowsoftdeletion},
     * {@code compressdesign}, {@code compressdata}, {@code uselz1}, {@code increasemaxfields}.
     *
     * <p>{@code buildCleanDxl()} used to copy these onto every exported file's
     * {@code <database>} wrapper, which round-trips correctly (confirmed empirically,
     * see {@code DominoBlueprint_RoundTrip_Status.md} item B) but duplicates the same
     * values across all ~40 files for no reason — only files with
     * {@code setReplaceDbProperties(true)} on import ever act on them.
     *
     * <p>As of 2026-07-02 these attributes are kept on exactly one file: the
     * {@code <launchsettings>} element's wrapper, exported as
     * {@code other/DatabaseSettings.dxl} (see {@link #TYPE_SUFFIXES}). Every other
     * file, <b>including {@code acl/acl.dxl}</b>, has them stripped like an identity
     * attribute. {@code acl.dxl} still needs {@code setReplaceDbProperties(true)} on import for
     * its own {@code maxinternetaccess}/{@code adminserver} attributes (which live on
     * {@code <acl>}, not {@code <database>}) — that is unaffected by stripping these here.
     */
    private static final Set<String> DATABASE_SETTINGS_ATTRS = new HashSet<>(Arrays.asList(
            "categories", "defaultlanguage", "fromtemplate", "maintainunread",
            "maxrevisionentries", "softdeletionsexpirein", "allowsoftdeletion",
            "compressdesign", "compressdata", "uselz1", "increasemaxfields"
    ));

    /**
     * Tag name of the one design element whose wrapper is allowed to keep
     * {@link #DATABASE_SETTINGS_ATTRS}. See {@link #buildCleanDxl}.
     */
    private static final String DATABASE_SETTINGS_CARRIER_TAG = "launchsettings";

    /**
     * Top-level children of {@code <database>} that are never treated as design
     * elements and are dropped entirely from the split output.
     *
     * <ul>
     *   <li>{@code <databaseinfo>} – source-database metadata (replica id, path, …).</li>
     *   <li>{@code <agentdata>} – agent run-history notes. Domino stores one per
     *       agent with {@code $Signature}, last-run info, and other runtime state.
     *       Not part of the design; re-created automatically when agents run.</li>
     * </ul>
     */
    private static final Set<String> DATABASE_CHILDREN_TO_REMOVE = new HashSet<>(Arrays.asList(
            "databaseinfo",
            "agentdata"
    ));

    /**
     * Children of a design-element note that contain metadata about the source
     * replica and should be removed before re-import.
     */
    /**
     * Children of a design-element note that contain source-replica metadata
     * or history information and should be removed before re-import.
     *
     * <ul>
     *   <li>{@code <noteinfo>}, {@code <updatedby>}, {@code <wassignedby>} &mdash;
     *       per-note metadata about the source replica (NOTEID, sequence,
     *       last-edit user, signer).</li>
     *   <li>{@code <logentry>} &mdash; appears only inside {@code <acl>}; each
     *       entry is a change-history line (timestamp + admin + action). Pure
     *       source-database history, never relevant in a target.</li>
     * </ul>
     */
    private static final Set<String> NOTE_CHILDREN_TO_REMOVE = new HashSet<>(Arrays.asList(
            "noteinfo", "updatedby", "wassignedby", "logentry",
            "rundata", "runlog", "designchange"
    ));

    /**
     * {@code <item>} names that are Designer-applied stamps rather than portable
     * design content, and should be stripped from every design element.
     *
     * <ul>
     *   <li>{@code $DesignerBuild} &mdash; the Designer build number that stamped
     *       the note (e.g. {@code Build V1450_06062025}). Written by Domino on
     *       import/save; the source element predates it, so it is a guaranteed
     *       round-trip diff if left in place.</li>
     * </ul>
     */
    private static final Set<String> ITEM_NAMES_TO_REMOVE = new HashSet<>(Arrays.asList(
            "$DesignerBuild"
    ));

    /**
     * If a design element contains a <b>descendant</b> element with one of these names it
     * is a Java design element and should be excluded when {@code skipJava} is enabled.
     */
    private static final Set<String> JAVA_DESCENDANT_NAMES = new HashSet<>(Arrays.asList(
            "javaproject"
    ));

    /**
     * If a design element's <b>own</b> tag name matches one of these, it is a Java
     * design element (e.g. a Java Resource holding compiled .class files) and should
     * be excluded when {@code skipJava} is enabled.
     */
    private static final Set<String> JAVA_ELEMENT_TAGS = new HashSet<>(Arrays.asList(
            "javaresource"
    ));

    /**
     * Human-readable file-name suffixes keyed by the DXL element tag name
     * (or, for generic {@code <note class="X">} wrappers, by the {@code class}
     * attribute value — see {@link #resolveType(Element)}).
     *
     * <p>Anything not listed here falls back to a capitalised version of the key.
     */
    private static final java.util.Map<String, String> TYPE_SUFFIXES =
            new java.util.LinkedHashMap<String, String>() {{
                // Forms category
                put("form",                   "Form");
                put("subform",                "Subform");
                put("sharedfield",            "SharedField");
                // Views category
                put("view",                   "View");
                put("folder",                 "Folder");
                put("sharedcolumn",           "SharedColumn");
                // Code category
                put("agent",                  "Agent");
                put("scriptlibrary",          "ScriptLibrary");
                put("sharedactions",          "SharedActions");
                // Resources category
                put("imageresource",          "Image");
                put("stylesheetresource",     "Stylesheet");
                put("fileresource",           "FileResource");
                put("javaresource",           "JavaResource");
                // Pages category
                put("page",                   "Page");
                put("frameset",               "Frameset");
                put("outline",                "Outline");
                put("navigator",              "Navigator");
                // Other category
                put("databasescript",         "DatabaseScript");
                put("dbicon",                 "DatabaseIcon");
                put("helpaboutdocument",      "HelpAbout");
                put("helpusingdocument",      "HelpUsing");
                put("aboutdocument",          "HelpAbout");     // older DXL variant
                put("usingdocument",          "HelpUsing");     // older DXL variant
                put("dataconnectionresource", "DataConnection");
                put("replicationformula",     "ReplicationFormula");
                put("databaseprofile",        "Profile");
                put("launchsettings",         "DatabaseSettings");
                // Generic <note class="X"> values that DxlExporter uses instead of
                // dedicated tags for certain element kinds
                put("icon",                   "DatabaseIcon");
                put("help",                   "HelpIndex");
            }};

    /** The DOCTYPE system identifier used in exported DXL files. */
    private static final String DOCTYPE_SYSTEM = "xmlschemas/domino_11_0_1.dtd";

    // -----------------------------------------------------------------------
    // State
    // -----------------------------------------------------------------------

    private final String rawDxl;

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    /**
     * @param rawDxl Raw DXL XML string as produced by {@code DxlExporter.exportDxl(NoteCollection)}.
     */
    public DxlProcessor(String rawDxl) {
        this.rawDxl = rawDxl;
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Parse the raw DXL and return one {@link DesignElement} per design note found
     * inside the {@code <database>} wrapper (the {@code <databaseinfo>} entry is
     * always excluded).
     *
     * @return ordered list of design elements (may be empty, never {@code null})
     * @throws Exception on XML parse / transform errors
     */
    public List<DesignElement> splitElements() throws Exception {
        Document doc = parseDxl(rawDxl);
        Element  database = doc.getDocumentElement(); // <database>

        List<Element> designElements = collectDesignElements(database);
        List<DesignElement> result   = new ArrayList<>(designElements.size());

        for (Element el : designElements) {
            String  type     = resolveType(el);
            String  name     = resolveName(el);
            boolean isJava   = isJavaElement(el);
            // isGhostElement() falls through to `true` for an element with zero
            // attributes and zero child elements (a legitimate soft-deleted stub for
            // most note types). The <launchsettings> carrier element is the one
            // exception: a fully-default Launch tab (no <weblaunch>/<noteslaunch>
            // customisation) is exported by Domino as a bare <launchsettings/> with no
            // attributes or children, which is real, meaningful design state — not a
            // deletion stub — and now the sole carrier of DATABASE_SETTINGS_ATTRS (see
            // buildCleanDxl). Ghost-skipping it would silently drop those 11 attributes
            // from the entire export whenever Launch settings are left at defaults.
            boolean isGhost  = DATABASE_SETTINGS_CARRIER_TAG.equals(type)
                    ? false : isGhostElement(el);
            String  excluded = resolveExclusion(type, name);
            String  language = resolveLanguage(el, type, isJava);
            String  cleanDxl = buildCleanDxl(database, el);

            result.add(new DesignElement(type, name, isJava, isGhost, excluded, language, cleanDxl));
        }

        return result;
    }

    /**
     * Re-format a DXL XML string with indentation so it can be reviewed
     * by humans. Whitespace-only text nodes from the input are stripped
     * before re-serialisation so the indenting transformer can apply a
     * consistent style.
     *
     * <p>Use sparingly &mdash; pretty-printing is safe for elements whose
     * content is purely structural (e.g. {@code <acl>} with {@code <aclentry>}
     * and {@code <role>} children) but is <b>not</b> safe for elements that
     * embed code (LotusScript, formula, JavaScript, HTML) inside text nodes
     * where whitespace is significant.
     *
     * @param xml DXL XML string (with or without DOCTYPE declaration)
     * @return The same DXL pretty-printed; the {@code <?xml?>} declaration is
     *         normalised to single quotes / lowercase encoding to match the
     *         rest of the export, and a trailing newline is ensured.
     * @throws Exception on XML parse / transform errors
     */
    public static String prettyPrint(String xml) throws Exception {
        Document doc = parseDxl(xml);
        stripWhitespaceTextNodes(doc.getDocumentElement());

        TransformerFactory tf          = TransformerFactory.newInstance();
        Transformer        transformer = tf.newTransformer();

        transformer.setOutputProperty(OutputKeys.ENCODING,             "UTF-8");
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
        transformer.setOutputProperty(OutputKeys.DOCTYPE_SYSTEM,       DOCTYPE_SYSTEM);
        transformer.setOutputProperty(OutputKeys.INDENT,               "yes");
        try {
            transformer.setOutputProperty(
                    "{http://xml.apache.org/xslt}indent-amount", "2");
        } catch (IllegalArgumentException ignored) {
            // Older XSLT engines may not support this property; the default
            // indent (usually 2 spaces) is acceptable.
        }

        StringWriter sw = new StringWriter();
        transformer.transform(new DOMSource(doc), new StreamResult(sw));

        String out = sw.toString();
        out = out.replaceFirst(
                "<\\?xml[^?]*\\?>",
                "<?xml version='1.0' encoding='utf-8'?>");
        if (!out.endsWith("\n")) out = out + "\n";
        return out;
    }

    /**
     * Recursively remove whitespace-only text nodes from a DOM subtree.
     * Used as a pre-pass for {@link #prettyPrint(String)} so the indenting
     * transformer is not confused by pre-existing whitespace.
     */
    private static void stripWhitespaceTextNodes(Node node) {
        NodeList children = node.getChildNodes();
        List<Node> toRemove = new ArrayList<>();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.TEXT_NODE) {
                String txt = child.getNodeValue();
                if (txt == null || txt.trim().isEmpty()) {
                    toRemove.add(child);
                }
            } else if (child.getNodeType() == Node.ELEMENT_NODE) {
                stripWhitespaceTextNodes(child);
            }
        }
        for (Node n : toRemove) node.removeChild(n);
    }

    // -----------------------------------------------------------------------
    // XML helpers
    // -----------------------------------------------------------------------

    /** Parse a DXL string without loading the external DTD (avoids network calls). */
    private static Document parseDxl(String dxl) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        // Disable DTD validation and external entity loading
        factory.setFeature(
                "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setFeature("http://xml.org/sax/features/validation", false);
        factory.setNamespaceAware(true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(new InputSource(new StringReader(dxl)));
    }

    /**
     * Return all element children of {@code database} that are design element nodes
     * (i.e. everything except {@link #DATABASE_CHILDREN_TO_REMOVE}).
     */
    private static List<Element> collectDesignElements(Element database) {
        NodeList   children = database.getChildNodes();
        List<Element> result = new ArrayList<>();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() != Node.ELEMENT_NODE) continue;
            String tag = localName((Element) child);
            if (!DATABASE_CHILDREN_TO_REMOVE.contains(tag)) {
                result.add((Element) child);
            }
        }
        return result;
    }

    /**
     * Build a clean DXL document string for a single design element.
     *
     * <p>Creates a fresh {@code <database>} wrapper copied from {@code origDatabase}
     * (minus the excluded attributes), imports and cleans the design element, then
     * serialises everything back to XML.
     *
     * <p>{@link #DATABASE_SETTINGS_ATTRS} are copied only when {@code designEl} is the
     * {@link #DATABASE_SETTINGS_CARRIER_TAG} element (i.e. {@code <launchsettings>}) —
     * every other file, ACL included, gets them stripped like a source-identity
     * attribute, so the portable database settings live in exactly one output file.
     */
    private static String buildCleanDxl(Element origDatabase, Element designEl)
            throws Exception {

        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        DocumentBuilder        db  = dbf.newDocumentBuilder();
        Document               newDoc = db.newDocument();

        boolean keepSettingsAttrs =
                DATABASE_SETTINGS_CARRIER_TAG.equals(localName(designEl));

        // --- Clean <database> wrapper -------------------------------------------
        Element newDatabase = newDoc.createElementNS(
                origDatabase.getNamespaceURI(),
                origDatabase.getTagName()
        );

        // Copy attributes, skipping source-identity attrs always, and skipping the
        // portable settings attrs unless this is the one file that carries them.
        NamedNodeMap attrs = origDatabase.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            Attr attr = (Attr) attrs.item(i);
            String attrLocal = attr.getLocalName() != null ? attr.getLocalName() : attr.getName();
            if (DATABASE_ATTRS_TO_REMOVE.contains(attrLocal)) continue;
            if (!keepSettingsAttrs && DATABASE_SETTINGS_ATTRS.contains(attrLocal)) continue;

            if (attr.getNamespaceURI() != null) {
                newDatabase.setAttributeNS(
                        attr.getNamespaceURI(), attr.getName(), attr.getValue());
            } else {
                newDatabase.setAttribute(attr.getName(), attr.getValue());
            }
        }

        newDoc.appendChild(newDatabase);

        // --- Import and clean the design element --------------------------------
        Node importedEl = newDoc.importNode(designEl, /* deep= */ true);
        cleanNoteMetadata((Element) importedEl);
        newDatabase.appendChild(importedEl);

        // --- Serialise ----------------------------------------------------------
        return serialise(newDoc);
    }

    /**
     * Build the standalone {@code other/DatabaseSettings.dxl} content directly from a
     * category's raw DXL, <b>independent of whether {@link #splitElements()} would
     * surface a {@code <launchsettings>} design element for it.</b>
     *
     * <p>Confirmed empirically 2026-07-13 (`TestDominoBlueprintExporter-v3.nsf`, Launch tab
     * left at full Domino defaults): {@code DxlExporter.exportDxl(NoteCollection)} does
     * <b>not</b> always emit a {@code <launchsettings>} child of {@code <database>} the way
     * earlier testing against a different database (with a customised Launch tab) suggested
     * &mdash; it appears to depend on the Launch tab having some non-default configuration.
     * When absent, there was no design element left to carry {@link #DATABASE_SETTINGS_ATTRS}
     * at all, silently dropping all 11 attributes from the export (a second bug beyond the
     * {@code isGhostElement} fallthrough fixed earlier the same day). See item B in
     * {@code DominoBlueprint_RoundTrip_Status.md}.
     *
     * <p>This method sidesteps that entirely: it parses the raw DXL, copies the identity-
     * stripped {@code <database>} attributes (keeping {@link #DATABASE_SETTINGS_ATTRS}
     * unconditionally, since this file is always their sole carrier), and embeds whatever
     * {@code <launchsettings>} child actually exists &mdash; or a synthesised empty
     * {@code <launchsettings/>} placeholder when Domino omitted it, so the file's shape and
     * the importer's {@code fileDeclaresDbProperties()} substring match stay consistent
     * either way.
     *
     * @param rawDxl Raw DXL for the same category/{@code NoteCollection} that
     *               {@code other/} is otherwise built from (i.e. {@code exportOther()}'s).
     * @return Clean DXL string for {@code other/DatabaseSettings.dxl}.
     * @throws Exception on XML parse / transform errors
     */
    public static String buildDatabaseSettingsDxl(String rawDxl) throws Exception {
        Document doc      = parseDxl(rawDxl);
        Element  database = doc.getDocumentElement();

        Element launchSettings = null;
        NodeList children = database.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() != Node.ELEMENT_NODE) continue;
            if (DATABASE_SETTINGS_CARRIER_TAG.equals(localName((Element) child))) {
                launchSettings = (Element) child;
                break;
            }
        }

        DocumentBuilderFactory dbf    = DocumentBuilderFactory.newInstance();
        DocumentBuilder        db     = dbf.newDocumentBuilder();
        Document               newDoc = db.newDocument();

        Element newDatabase = newDoc.createElementNS(
                database.getNamespaceURI(),
                database.getTagName()
        );

        // Copy every attribute except source-identity ones. Unlike buildCleanDxl(), the
        // settings attrs are ALWAYS kept here — this file is their one carrier.
        NamedNodeMap attrs = database.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            Attr attr = (Attr) attrs.item(i);
            String attrLocal = attr.getLocalName() != null ? attr.getLocalName() : attr.getName();
            if (DATABASE_ATTRS_TO_REMOVE.contains(attrLocal)) continue;

            if (attr.getNamespaceURI() != null) {
                newDatabase.setAttributeNS(
                        attr.getNamespaceURI(), attr.getName(), attr.getValue());
            } else {
                newDatabase.setAttribute(attr.getName(), attr.getValue());
            }
        }

        newDoc.appendChild(newDatabase);

        if (launchSettings != null) {
            Node imported = newDoc.importNode(launchSettings, /* deep= */ true);
            cleanNoteMetadata((Element) imported);
            newDatabase.appendChild(imported);
        } else {
            // Domino omitted <launchsettings> entirely (fully-default Launch tab) —
            // synthesise an empty placeholder so the file is still self-describing and
            // still matches DXLImport's fileDeclaresDbProperties() substring check.
            newDatabase.appendChild(
                    newDoc.createElementNS(database.getNamespaceURI(), "launchsettings"));
        }

        return serialise(newDoc);
    }

    /**
     * Remove {@code <noteinfo>}, {@code <updatedby>}, and {@code <wassignedby>} from
     * the top-level children of a design element.
     */
    private static void cleanNoteMetadata(Element el) {
        NodeList   children   = el.getChildNodes();
        List<Node> toRemove   = new ArrayList<>();

        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() != Node.ELEMENT_NODE) continue;
            Element childEl = (Element) child;
            String  tag     = localName(childEl);

            if (NOTE_CHILDREN_TO_REMOVE.contains(tag)) {
                toRemove.add(child);
            } else if ("item".equals(tag)
                    && ITEM_NAMES_TO_REMOVE.contains(childEl.getAttribute("name"))) {
                toRemove.add(child);
            } else if (isEmptyDeclarationsCode(childEl, tag)) {
                toRemove.add(child);
            }
        }

        for (Node node : toRemove) {
            el.removeChild(node);
        }
    }

    /**
     * Return {@code true} when {@code childEl} is a
     * {@code <code event="declarations">} block whose only content is an empty
     * {@code <lotusscript/>} element (no source text). Domino does not re-emit an
     * empty declarations event on import, so leaving this block in the export
     * guarantees a spurious round-trip diff; stripping it here matches what a
     * fresh import would produce.
     */
    private static boolean isEmptyDeclarationsCode(Element childEl, String tag) {
        if (!"code".equals(tag)) return false;
        if (!"declarations".equals(childEl.getAttribute("event"))) return false;

        NodeList grand          = childEl.getChildNodes();
        boolean  sawLotusScript = false;
        for (int i = 0; i < grand.getLength(); i++) {
            Node n = grand.item(i);
            if (n.getNodeType() == Node.TEXT_NODE) {
                String txt = n.getNodeValue();
                if (txt != null && !txt.trim().isEmpty()) return false;
                continue;
            }
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;

            Element ge = (Element) n;
            if (!"lotusscript".equals(localName(ge))) return false; // real content present
            String text = ge.getTextContent();
            if (text != null && !text.trim().isEmpty()) return false; // non-empty script
            sawLotusScript = true;
        }
        return sawLotusScript;
    }

    /**
     * Return {@code true} if this element is a <b>ghost note</b>: a NOTE_CLASS_FILTER
     * (or similar) note whose payload has been emptied, leaving only stub items
     * such as an empty {@code $FLAGS} and empty {@code $UpdatedBy}. This is the
     * signature of a soft-deleted design element whose deletion stub has not yet
     * been purged from the database.
     *
     * <p>Concretely, an element is treated as a ghost when <b>all</b> of these
     * conditions hold:
     * <ul>
     *   <li>The element has no {@code name} or {@code title} attribute, and
     *       no {@code $FileNames} or {@code $TITLE} item with text content
     *       (i.e. {@link #resolveName(Element)} would return {@code "unknown"}).</li>
     *   <li>No child {@code <item>} has non-empty text content. Empty stub
     *       items like {@code <item name="$FLAGS"><text/></item>} or
     *       {@code <item name="$UpdatedBy"><text/></item>} do not count as
     *       content.</li>
     *   <li>The element has no structural children other than an empty
     *       {@code <trigger/>}. A real agent has a {@code <code>},
     *       {@code <simpleaction>}, or {@code <documentset>} child; a real
     *       form has {@code <par>}/{@code <pardef>} children; etc.</li>
     * </ul>
     *
     * <p>Ghost notes carry no recoverable design information &mdash; they
     * round-trip as empty stub files (244 bytes for {@code <agent>}) &mdash;
     * and would only add noise to a versioned export, so the exporter skips
     * them with a {@code [SKIP ghost]} log entry rather than writing them.
     *
     * <p>The fix on the source database side is to run a server-side compact
     * with the discard option ({@code load compact -c -D <db>.nsf}) so the
     * expired deletion stubs are purged; if any survive, {@code load fixup
     * <db>.nsf -F -J} forces a design-note rebuild.
     */
    /**
     * Element tags that are metadata or runtime state (not design content), so
     * their presence on a design element does not disqualify it from being a
     * ghost note. Includes everything the note-metadata cleaner strips
     * ({@link #NOTE_CHILDREN_TO_REMOVE}) plus runtime/state elements Domino
     * emits on every agent regardless of whether the agent has a body
     * ({@code <designchange>}, {@code <rundata>}, {@code <runlog>}, {@code <trigger>},
     * {@code <documentset>}, {@code <agentmodified>}).
     */
    private static final Set<String> GHOST_IGNORED_CHILDREN = new HashSet<>(Arrays.asList(
            "noteinfo", "updatedby", "wassignedby", "logentry",
            "designchange", "rundata", "runlog", "agentmodified",
            "trigger", "documentset"
    ));

    private static boolean isGhostElement(Element el) {
        // 1. Has no usable name?
        String attr = el.getAttribute("name");
        if (attr != null && !attr.isEmpty()) return false;
        attr = el.getAttribute("title");
        if (attr != null && !attr.isEmpty()) return false;
        if (itemText(el, "$FileNames") != null) return false;
        if (itemText(el, "$TITLE")     != null) return false;

        // 2. No <item> child with text content, and no structural children
        //    other than known metadata / runtime-state tags.
        NodeList children = el.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element child = (Element) n;
            String  tag   = localName(child);

            if ("item".equals(tag)) {
                // Empty stub items (e.g. $FLAGS, $UpdatedBy with no text) are
                // characteristic of ghost notes and do not disqualify.
                String t = child.getTextContent();
                if (t != null && !t.trim().isEmpty()) return false;
            } else if (GHOST_IGNORED_CHILDREN.contains(tag)) {
                // Metadata / runtime-state tags appear on every note Domino
                // emits, including ghost stubs. They carry no design content,
                // so they do not disqualify.
                continue;
            } else {
                // Any other element (<code>, <simpleaction>, <formula>,
                // <javaproject>, <par>, <pardef>, <viewformat>, <column>,
                // <subform>, <actionbar>, ...) means real design content.
                return false;
            }
        }
        return true;
    }

    /**
     * Return {@code true} if the element itself, or any of its descendants, is a
     * Java design element.
     *
     * <p>Two flavours are detected:
     * <ul>
     *   <li>The element's own tag name matches {@link #JAVA_ELEMENT_TAGS}
     *       &mdash; e.g. a {@code <javaresource>} containing compiled {@code .class}
     *       files.</li>
     *   <li>A descendant with a tag name in {@link #JAVA_DESCENDANT_NAMES} exists
     *       &mdash; e.g. a {@code <javaproject>} child inside an {@code <agent>}
     *       or {@code <scriptlibrary>}.</li>
     * </ul>
     */
    private static boolean isJavaElement(Element el) {
        // Check the element's own tag name first
        if (JAVA_ELEMENT_TAGS.contains(localName(el))) return true;

        // Then scan descendants
        for (String javaTag : JAVA_DESCENDANT_NAMES) {
            // Check with namespace wildcard first, then without
            if (el.getElementsByTagNameNS("*", javaTag).getLength() > 0) return true;
            if (el.getElementsByTagName(javaTag).getLength() > 0) return true;
        }
        return false;
    }

    /**
     * Resolve the coding language of an {@code <agent>} or {@code <scriptlibrary>}
     * element by inspecting its XML structure. Returns one of:
     * <ul>
     *   <li>{@code "java"}          – {@code <javaproject>} present without
     *                                  {@code imported="true"}: source is editable
     *                                  in Designer.</li>
     *   <li>{@code "imported_java"} – {@code <javaproject imported="true">}: the
     *                                  agent/library was created by importing a JAR;
     *                                  no editable source is stored in the NSF.</li>
     *   <li>{@code "lotusscript"} – {@code <lotusscript>} descendant present.</li>
     *   <li>{@code "javascript"}  – {@code <javascript>} descendant present
     *                                (client-side JS library for web).</li>
     *   <li>{@code "ssjs"}        – script library with a
     *                                {@code $ServerJavaScriptLibrary} item
     *                                (Server-Side JavaScript / XPages SSJS).</li>
     *   <li>{@code "simple"}      – agent has a {@code <simpleaction>} descendant
     *                                (checked before {@code "formula"} because simple
     *                                actions wrap their formula inside
     *                                {@code <simpleaction>}).</li>
     *   <li>{@code "formula"}     – a {@code <code>} child of the element contains a
     *                                {@code <formula>} descendant, or carries
     *                                {@code language="formula"}. Note: Domino DXL
     *                                does NOT emit {@code language="formula"} in
     *                                practice — the formula is identified by the
     *                                presence of the {@code <formula>} child element.</li>
     *   <li>{@code null}           – element type does not have a language concept,
     *                                or the language could not be determined.</li>
     * </ul>
     */
    private static String resolveLanguage(Element el, String type, boolean isJava) {
        if (isJava) {
            // Distinguish imported Java (JAR-only, no editable source) from
            // non-imported Java (source written/visible in Designer).
            // The <javaproject> element carries imported="true" when the agent
            // or library was created by importing a JAR rather than authoring source.
            NodeList jpNodes = el.getElementsByTagNameNS("*", "javaproject");
            if (jpNodes.getLength() == 0) jpNodes = el.getElementsByTagName("javaproject");
            for (int i = 0; i < jpNodes.getLength(); i++) {
                Node n = jpNodes.item(i);
                if (n.getNodeType() != Node.ELEMENT_NODE) continue;
                if ("true".equalsIgnoreCase(((Element) n).getAttribute("imported"))) {
                    return "imported_java";
                }
            }
            return "java";
        }
        if (!"agent".equals(type) && !"scriptlibrary".equals(type)) return null;

        // LotusScript: <lotusscript> element anywhere inside the design element
        if (hasDescendantNamed(el, "lotusscript")) return "lotusscript";

        // Client-side JavaScript library: <javascript> element
        if (hasDescendantNamed(el, "javascript")) return "javascript";

        // Server-Side JavaScript (XPages SSJS): stored as raw binary in a
        // $ServerJavaScriptLibrary item — no <code> wrapper is emitted.
        if ("scriptlibrary".equals(type) && hasItemNamed(el, "$ServerJavaScriptLibrary")) {
            return "ssjs";
        }

        // Simple action agents: <simpleaction> must be detected BEFORE formula because
        // a simple action can contain a <formula> child inside <simpleaction>.
        if ("agent".equals(type) && hasDescendantNamed(el, "simpleaction")) return "simple";

        // Formula: look for a <formula> element inside any <code> child, or an explicit
        // language="formula" attribute (defensive — Domino DXL does not emit this in practice).
        NodeList codeNodes = el.getElementsByTagNameNS("*", "code");
        if (codeNodes.getLength() == 0) codeNodes = el.getElementsByTagName("code");
        for (int i = 0; i < codeNodes.getLength(); i++) {
            Node n = codeNodes.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element codeEl = (Element) n;
            if ("formula".equalsIgnoreCase(codeEl.getAttribute("language"))) return "formula";
            if (hasDescendantNamed(codeEl, "formula")) return "formula";
        }

        return null;  // language undetermined
    }

    /**
     * Return {@code true} if {@code el} has any descendant element with the given
     * local name. Tries namespace-aware lookup first, then falls back to plain tag-name
     * lookup for parsers that do not preserve namespace information.
     */
    private static boolean hasDescendantNamed(Element el, String tagName) {
        if (el.getElementsByTagNameNS("*", tagName).getLength() > 0) return true;
        return el.getElementsByTagName(tagName).getLength() > 0;
    }

    /**
     * Serialise a DOM {@link Document} to a UTF-8 XML string with a DOCTYPE
     * declaration matching the Domino DXL format.
     */
    private static String serialise(Document doc) throws Exception {
        TransformerFactory tf          = TransformerFactory.newInstance();
        Transformer        transformer = tf.newTransformer();

        transformer.setOutputProperty(OutputKeys.ENCODING,       "UTF-8");
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
        transformer.setOutputProperty(OutputKeys.DOCTYPE_SYSTEM, DOCTYPE_SYSTEM);
        // Preserve the compact formatting Domino uses (no extra indentation)
        transformer.setOutputProperty(OutputKeys.INDENT, "no");

        StringWriter sw = new StringWriter();
        transformer.transform(new DOMSource(doc), new StreamResult(sw));

        String xml = sw.toString();

        // Normalise the XML declaration to use single quotes and lowercase encoding,
        // matching the format of existing DXL files in the project.
        xml = xml.replaceFirst(
                "<\\?xml[^?]*\\?>",
                "<?xml version='1.0' encoding='utf-8'?>"
        );

        // Ensure the file ends with a newline
        if (!xml.endsWith("\n")) xml = xml + "\n";

        return xml;
    }

    // -----------------------------------------------------------------------
    // Utility helpers
    // -----------------------------------------------------------------------

    /** Return the local name of an element, falling back to the tag name. */
    private static String localName(Element el) {
        String local = el.getLocalName();
        return (local != null) ? local : el.getTagName();
    }

    /**
     * Return the type key to use for {@link DesignElement#getType()} and
     * {@link TYPE_SUFFIXES} lookup.
     *
     * <p>Normally this is just the element's tag name ({@code form}, {@code view},
     * {@code agent}, …). For the generic {@code <note class="X">} wrapper that
     * {@code DxlExporter} emits for certain element kinds (database icon,
     * replication formulas, hidden file resources, …), the {@code class}
     * attribute is a much better key than the literal tag name "note".
     *
     * <p>Special case: {@code <note class="form">} with a {@code $FileData} item
     * is an XPages-style file resource (NOTE_CLASS_FORM + design flag {@code g}).
     * Return {@code "fileresource"} so it is named and binned correctly.
     */
    private static String resolveType(Element el) {
        String tag = localName(el);
        if (!"note".equals(tag)) return tag;

        String cls = el.getAttribute("class");
        if (cls == null || cls.isEmpty()) return tag;

        // File resources stored as NOTE_CLASS_FORM advertise themselves via $FileData.
        if ("form".equals(cls) && hasItemNamed(el, "$FileData")) {
            return "fileresource";
        }
        return cls;
    }

    /**
     * Return the human-readable name of a design element for use as a filename
     * base. Tries, in order:
     * <ol>
     *   <li>The {@code name} attribute on the element itself.</li>
     *   <li>The {@code title} attribute on the element itself.</li>
     *   <li>The text content of the {@code $FileNames} item
     *       (file resources carry their original file path here).</li>
     *   <li>The text content of the {@code $TITLE} item
     *       (database icon, replication formulas, help docs, …).</li>
     *   <li>{@code "unknown"} as a last resort.</li>
     * </ol>
     */
    private static String resolveName(Element el) {
        String name = el.getAttribute("name");
        if (name == null || name.isEmpty()) name = el.getAttribute("title");
        if (name != null && !name.isEmpty()) return name;

        String itemText = itemText(el, "$FileNames");
        if (itemText != null && !itemText.isEmpty()) return itemText;

        itemText = itemText(el, "$TITLE");
        if (itemText != null && !itemText.isEmpty()) return itemText;

        return "unknown";
    }

    /**
     * Return the text content of the first {@code <item name="itemName">} child
     * of {@code el}, or {@code null} if no such item exists or it has no text.
     *
     * <p>Matches the common Domino DXL item shape:
     * {@code <item name="$TITLE"><text>hello</text></item>}.
     */
    private static String itemText(Element el, String itemName) {
        NodeList children = el.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element child = (Element) n;
            if (!"item".equals(localName(child))) continue;
            if (!itemName.equals(child.getAttribute("name"))) continue;

            // Prefer the nested <text> child
            NodeList grand = child.getChildNodes();
            for (int j = 0; j < grand.getLength(); j++) {
                Node g = grand.item(j);
                if (g.getNodeType() == Node.ELEMENT_NODE
                        && "text".equals(localName((Element) g))) {
                    String t = g.getTextContent();
                    if (t != null && !t.isEmpty()) return t;
                }
            }
            // Fall back to the item's full text content
            String t = child.getTextContent();
            if (t != null && !t.trim().isEmpty()) return t.trim();
        }
        return null;
    }

    /**
     * Return a human-readable reason why this element should be excluded from the
     * design export, or {@code null} if it is a legitimate design element.
     *
     * <p>Current exclusions:
     * <ul>
     *   <li><b>Private replication formulas</b> &mdash; Domino stores one
     *       {@code <replicationformula>} per user who has opened the database
     *       with a local replica (to record their selective-replication rules).
     *       They are named after the user's canonical name
     *       ({@code CN=Jane Doe/OU=Dept/O=Acme}) and are per-user state, not design.
     *       The database-wide replication formula, if present, is kept.</li>
     *   <li><b>XPages build artifacts</b> &mdash; file resources auto-generated
     *       by the XPages compiler: everything under {@code WEB-INF/}, all hidden
     *       Eclipse dotfiles ({@code .classpath}, {@code .project},
     *       {@code .settings/*}), and OSGi/PDE build descriptors
     *       ({@code plugin.xml}, {@code build.properties}, {@code feature.xml},
     *       {@code MANIFEST.MF}). These are regenerated on the target side when
     *       XPages are rebuilt and would not round-trip cleanly anyway.</li>
     * </ul>
     */
    private static String resolveExclusion(String type, String name) {
        if (type == null) return null;

        // Private (per-user) replication formula — named after the user's
        // canonical hierarchical name. The DB-wide one is named "$formula"
        // or similar and does not start with "CN=".
        if ("replicationformula".equals(type)
                && name != null
                && name.startsWith("CN=")) {
            return "private replication formula";
        }

        // XPages build artifacts surface as file resources with well-known paths.
        if ("fileresource".equals(type)
                && name != null
                && isXPagesBuildArtifact(name)) {
            return "XPages build artifact";
        }

        return null;
    }

    /**
     * Return {@code true} when {@code path} matches a known XPages build artifact:
     * anything under {@code WEB-INF/}, any hidden dotfile (Eclipse {@code .classpath},
     * {@code .project}, {@code .settings/}), or a top-level PDE/OSGi build descriptor
     * ({@code plugin.xml}, {@code build.properties}, {@code feature.xml},
     * {@code MANIFEST.MF}).
     */
    private static boolean isXPagesBuildArtifact(String path) {
        if (path == null || path.isEmpty()) return false;

        // Normalise any alias separator the caller might have left in place
        int pipe = path.indexOf('|');
        if (pipe > 0) path = path.substring(0, pipe);

        // Directory prefixes — WEB-INF on all platforms
        if (path.startsWith("WEB-INF/") || path.startsWith("WEB-INF\\")) return true;

        // Hidden files — Eclipse/PDE metadata (.classpath, .project, .settings/*, …)
        if (path.startsWith(".")) return true;

        // Top-level PDE / OSGi build descriptors
        switch (path) {
            case "plugin.xml":
            case "build.properties":
            case "feature.xml":
            case "MANIFEST.MF":
                return true;
            default:
                return false;
        }
    }

    /** Return {@code true} if {@code el} has an {@code <item name="itemName">} child. */
    private static boolean hasItemNamed(Element el, String itemName) {
        NodeList children = el.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element child = (Element) n;
            if ("item".equals(localName(child))
                    && itemName.equals(child.getAttribute("name"))) {
                return true;
            }
        }
        return false;
    }

    // -----------------------------------------------------------------------
    // DesignElement – value object returned to callers
    // -----------------------------------------------------------------------

    /**
     * Represents a single cleaned design element ready to be written to disk.
     */
    public static final class DesignElement {

        private final String  type;
        private final String  name;
        private final boolean java;
        private final boolean ghost;
        private final String  excludedReason;
        private final String  language;
        private final String  cleanDxl;

        DesignElement(String type, String name, boolean java, boolean ghost,
                      String excludedReason, String language, String cleanDxl) {
            this.type           = type;
            this.name           = name;
            this.java           = java;
            this.ghost          = ghost;
            this.excludedReason = excludedReason;
            this.language       = language;
            this.cleanDxl       = cleanDxl;
        }

        /** DXL element tag name, e.g. {@code "form"}, {@code "agent"}, {@code "view"}. */
        public String getType() { return type; }

        /**
         * Design-element name as stored in the {@code name} (or {@code title}) attribute,
         * e.g. {@code "Config Value"}, {@code "HelloWorld"}.
         */
        public String getName() { return name; }

        /**
         * {@code true} when the element contains a {@code <javaproject>} child,
         * meaning it is a Java agent or Java script library.
         */
        public boolean isJava() { return java; }

        /**
         * {@code true} when this element is a ghost note &mdash; an empty
         * deletion-stub note left behind in the source database with no name
         * and no payload. Callers should skip writing it to disk; see
         * {@link DxlProcessor#isGhostElement(Element)} for the precise rule.
         */
        public boolean isGhost() { return ghost; }

        /**
         * {@code true} when this element has been flagged for exclusion from the
         * design export — e.g. a per-user private replication formula or an
         * XPages build artifact. Callers should skip writing it to disk.
         */
        public boolean isExcluded() { return excludedReason != null; }

        /**
         * Human-readable reason this element is excluded, for log output. Returns
         * {@code null} when {@link #isExcluded()} is {@code false}.
         */
        public String getExcludedReason() { return excludedReason; }

        /**
         * The coding language/kind of this element:
         * {@code "java"} (source present in Designer),
         * {@code "imported_java"} (JAR-only, no editable source),
         * {@code "lotusscript"}, {@code "javascript"}, {@code "ssjs"},
         * {@code "formula"}, {@code "simple"},
         * or {@code null} when not applicable or undetermined.
         * Meaningful only for {@code agent} and {@code scriptlibrary} elements.
         */
        public String getLanguage() { return language; }

        /** Cleaned DXL XML string ready to write to a {@code .dxl} file. */
        public String getCleanDxl() { return cleanDxl; }

        /**
         * A human-readable, CamelCase suffix that identifies the element type for
         * use in file names (e.g. {@code "Form"}, {@code "ScriptLibrary"}).
         */
        public String getTypeSuffix() {
            String suffix = TYPE_SUFFIXES.get(type.toLowerCase());
            return (suffix != null) ? suffix : capitalise(type);
        }

        private static String capitalise(String s) {
            if (s == null || s.isEmpty()) return s;
            return Character.toUpperCase(s.charAt(0)) + s.substring(1);
        }
    }
}
