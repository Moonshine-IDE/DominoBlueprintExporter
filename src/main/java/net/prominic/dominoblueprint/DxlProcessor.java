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
 *   <li><b>Detect Java</b> – an element is flagged as Java code when it contains a
 *       {@code <javaproject>} child, allowing the caller to skip it when exporting
 *       the {@code code/} category.</li>
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

    /** Top-level children of {@code <database>} that should not appear in per-element exports. */
    private static final Set<String> DATABASE_CHILDREN_TO_REMOVE = new HashSet<>(Arrays.asList(
            "databaseinfo"
    ));

    /**
     * Children of a design-element note that contain metadata about the source
     * replica and should be removed before re-import.
     */
    private static final Set<String> NOTE_CHILDREN_TO_REMOVE = new HashSet<>(Arrays.asList(
            "noteinfo", "updatedby", "wassignedby"
    ));

    /**
     * If a design element contains a child element with one of these names it is
     * a Java design element and should be excluded from the {@code code/} export.
     */
    private static final Set<String> JAVA_ELEMENT_NAMES = new HashSet<>(Arrays.asList(
            "javaproject"
    ));

    /**
     * Human-readable file-name suffixes keyed by the DXL element tag name.
     * Anything not listed here falls back to a capitalised version of the tag name.
     */
    private static final java.util.Map<String, String> TYPE_SUFFIXES =
            new java.util.LinkedHashMap<String, String>() {{
                put("form",          "Form");
                put("subform",       "Subform");
                put("sharedfield",   "SharedField");
                put("view",          "View");
                put("folder",        "Folder");
                put("sharedcolumn",  "SharedColumn");
                put("agent",         "Agent");
                put("scriptlibrary", "ScriptLibrary");
                put("sharedactions", "SharedActions");
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
            String  type    = localName(el);
            String  name    = elementName(el);
            boolean isJava  = containsJavaProject(el);
            String  cleanDxl = buildCleanDxl(database, el);

            result.add(new DesignElement(type, name, isJava, cleanDxl));
        }

        return result;
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
     * Return {@code true} if the element or any of its descendants is a
     * {@code <javaproject>} element (indicating this note contains Java code).
     */
    private static boolean containsJavaProject(Element el) {
        for (String javaTag : JAVA_ELEMENT_NAMES) {
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
     * Return the human-readable name of a design element.
     * Prefers the {@code name} attribute; falls back to {@code title}; then {@code "unknown"}.
     */
    private static String elementName(Element el) {
        String name = el.getAttribute("name");
        if (name == null || name.isEmpty()) name = el.getAttribute("title");
        if (name == null || name.isEmpty()) name = "unknown";
        return name;
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
        private final String  cleanDxl;

        DesignElement(String type, String name, boolean java, String cleanDxl) {
            this.type     = type;
            this.name     = name;
            this.java     = java;
            this.cleanDxl = cleanDxl;
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
