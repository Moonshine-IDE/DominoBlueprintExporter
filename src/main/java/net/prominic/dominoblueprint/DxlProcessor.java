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
 *         <li>{@code replicaid}, {@code path}, {@code title}, {@code increasemaxfields},
 *             {@code compressdesign}, {@code compressdata}, {@code uselz1} attributes
 *             on the root {@code <database>} element</li>
 *         <li>{@code <noteinfo>}, {@code <updatedby>}, {@code <wassignedby>} children
 *             of each design element</li>
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
 * {@code java -jar DXLImport.jar}.
 */
public class DxlProcessor {

    // -----------------------------------------------------------------------
    // Constants
    // -----------------------------------------------------------------------

    /** Database-level attributes that identify the source replica – must be removed. */
    private static final Set<String> DATABASE_ATTRS_TO_REMOVE = new HashSet<>(Arrays.asList(
            "replicaid", "path", "title",
            "increasemaxfields", "compressdesign", "compressdata", "uselz1"
    ));

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
            "noteinfo", "updatedby", "wassignedby", "logentry"
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
            String  excluded = resolveExclusion(type, name);
            String  cleanDxl = buildCleanDxl(database, el);

            result.add(new DesignElement(type, name, isJava, excluded, cleanDxl));
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
     */
    private static String buildCleanDxl(Element origDatabase, Element designEl)
            throws Exception {

        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        DocumentBuilder        db  = dbf.newDocumentBuilder();
        Document               newDoc = db.newDocument();

        // --- Clean <database> wrapper -------------------------------------------
        Element newDatabase = newDoc.createElementNS(
                origDatabase.getNamespaceURI(),
                origDatabase.getTagName()
        );

        // Copy attributes, skipping the source-replica-specific ones
        NamedNodeMap attrs = origDatabase.getAttributes();
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

        // --- Import and clean the design element --------------------------------
        Node importedEl = newDoc.importNode(designEl, /* deep= */ true);
        cleanNoteMetadata((Element) importedEl);
        newDatabase.appendChild(importedEl);

        // --- Serialise ----------------------------------------------------------
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
            String tag = localName((Element) child);
            if (NOTE_CHILDREN_TO_REMOVE.contains(tag)) {
                toRemove.add(child);
            }
        }

        for (Node node : toRemove) {
            el.removeChild(node);
        }
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
        private final String  excludedReason;
        private final String  cleanDxl;

        DesignElement(String type, String name, boolean java,
                      String excludedReason, String cleanDxl) {
            this.type           = type;
            this.name           = name;
            this.java           = java;
            this.excludedReason = excludedReason;
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
