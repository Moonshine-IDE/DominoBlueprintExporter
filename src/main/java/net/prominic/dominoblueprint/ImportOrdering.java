package net.prominic.dominoblueprint;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dependency-aware import ordering for a DominoBlueprint export tree.
 *
 * <p>{@code DxlImporter} compiles Java agents, Java script libraries and LotusScript
 * <em>at import time</em>, and it compiles each element in isolation against whatever
 * already exists in the target database. A blueprint is a directory of one-element-per-file
 * {@code .dxl} files; importing them in plain alphabetical path order does not honour the
 * dependency graph, so an element imported before its dependencies fails to compile
 * (HCL log id {@code 7005}, "Java compile errors"). This class splits the files into an
 * import order that respects those dependencies:</p>
 *
 * <ol>
 *   <li><b>Phase 1 — non-compiled elements.</b> Everything that is not compiled at import
 *       time (forms, views, pages, subforms, resources incl. {@code .jar}s, images, imported
 *       [pre-compiled] Java, JavaScript/SSJS, formula/simple agents, ACL, db properties).
 *       Imported first so that referenced resources and design notes already exist before
 *       any code is compiled.</li>
 *   <li><b>Phase 2 — compiled code in dependency order.</b> Source Java agents / Java script
 *       libraries (deps from {@code <sharedlibraryref>}) and LotusScript agents / libraries
 *       (deps parsed from {@code Use "lib"}), topologically sorted (Kahn's algorithm) so a
 *       library is imported before everything that references it.</li>
 * </ol>
 *
 * <p>This class is deliberately free of any {@code lotus.domino} dependency: it works purely
 * on {@link File}s and their textual DXL content, so its logic can be unit-tested off a
 * Domino box. The caller ({@code DominoBlueprintImport} / {@code DXLImport}) owns the actual
 * {@code DxlImporter} calls and the multi-pass fallback.</p>
 *
 * <p><b>Known limits (tracked in {@code DominoBlueprint_Java_Dependency_Ordering.md}):</b>
 * ordering is necessary but not sufficient. A {@code .jar}'s build-path link to a Java library
 * is not encoded in the DXL (the {@code mustache -> jmustache.jar} gap), and a Java agent's
 * {@code <javaproject codepath="...">} points at the authoring machine and carries no
 * {@code Notes.jar} reference (the {@code lotus.domino} not-found gap). Those are exporter /
 * environment fidelity gaps that ordering alone cannot fix; this class imports resources first
 * and surfaces the residual failures clearly rather than hiding them.</p>
 */
final class ImportOrdering {

    private ImportOrdering() { /* static-only */ }

    // Read enough of each file to see the design-element open tag and (for code) the whole
    // element. Code elements are source text, not large base64 payloads, so reading them in
    // full is cheap; non-code files are classified from a short prefix (see readClassifyHead).
    private static final int HEAD_BYTES = 8192;

    // <agent ...> or <scriptlibrary ...>  — capture the name="..." (or name='...') attribute.
    private static final Pattern DESIGN_NAME =
        Pattern.compile("<(?:agent|scriptlibrary)\\b[^>]*\\bname\\s*=\\s*([\"'])(.*?)\\1",
                        Pattern.DOTALL);

    // <sharedlibraryref name="..."/>  — an explicit Java dependency edge.
    private static final Pattern SHARED_LIBRARY_REF =
        Pattern.compile("<sharedlibraryref\\b[^>]*\\bname\\s*=\\s*([\"'])(.*?)\\1");

    // LotusScript  Use "lib"  — a script-library dependency. The negative look-behind on a word
    // char keeps it from matching inside identifiers (e.g. "Reuse"); "UseLSX" is naturally
    // excluded because there is no word boundary between "Use" and "LSX". The quote may be a
    // literal " or the XML entity &quot; depending on how the source was serialised.
    private static final Pattern LS_USE =
        Pattern.compile("(?:^|[^A-Za-z0-9_])Use\\s+(?:\"|&quot;)([^\"&]+)(?:\"|&quot;)",
                        Pattern.MULTILINE);

    // -----------------------------------------------------------------------
    // Classification
    // -----------------------------------------------------------------------

    /**
     * @return {@code true} if this DXL file is a compiled-at-import-time code element whose
     *         dependencies must be honoured (a source Java agent/library or a LotusScript
     *         agent/library). Pre-compiled "imported Java" ({@code <javaproject imported="true">})
     *         and non-code elements return {@code false}.
     */
    static boolean isOrderedCompiledCode(File dxlFile) {
        return isOrderedCompiledCodeDxl(readFully(dxlFile));
    }

    /** Content-only form of {@link #isOrderedCompiledCode(File)} — unit-testable. */
    static boolean isOrderedCompiledCodeDxl(String dxl) {
        if (null == dxl || dxl.isEmpty()) {
            return false;
        }
        // Scope ordering to agents and script libraries — the elements whose primary content
        // is compiled code and whose dependencies (<sharedlibraryref> / Use) form the graph.
        // Forms, views, pages and subforms stay in phase 1 even when they carry embedded
        // LotusScript events: the doc classifies them as structural notes that code may depend
        // on, so they must exist first. (A form/view whose embedded LS `Use`s a not-yet-imported
        // library can still warn; that residual case is out of scope here — see the status doc.)
        if (extractDesignElementName(dxl) == null) {
            return false;  // not an <agent>/<scriptlibrary> root
        }
        if (dxl.contains("<lotusscript")) {
            return true;   // LotusScript agent or library
        }
        if (dxl.contains("<javaproject")) {
            // Source Java is compiled at import; pre-compiled "imported Java" is not.
            return !isImportedJava(dxl);
        }
        return false;      // JavaScript/SSJS/formula/simple agents
    }

    private static boolean isImportedJava(String dxl) {
        Matcher m = Pattern.compile("<javaproject\\b[^>]*>").matcher(dxl);
        if (m.find()) {
            return m.group().contains("imported=\"true\"") || m.group().contains("imported='true'");
        }
        return false;
    }

    // -----------------------------------------------------------------------
    // Name / dependency extraction (content-only, unit-testable)
    // -----------------------------------------------------------------------

    /**
     * The design-element name that other elements reference (the {@code name} attribute of the
     * {@code <agent>} / {@code <scriptlibrary>} root), XML-entity decoded. {@code null} if none.
     */
    static String extractDesignElementName(String dxl) {
        if (null == dxl) {
            return null;
        }
        Matcher m = DESIGN_NAME.matcher(dxl);
        return m.find() ? decodeXml(m.group(2)) : null;
    }

    /**
     * Names this element depends on: every {@code <sharedlibraryref>} target plus every
     * LotusScript {@code Use "lib"} target, XML-entity decoded, de-duplicated, self-references
     * removed.
     */
    static List<String> extractDependencyNames(String dxl) {
        Set<String> deps = new LinkedHashSet<String>();
        if (null == dxl) {
            return new ArrayList<String>(deps);
        }
        Matcher j = SHARED_LIBRARY_REF.matcher(dxl);
        while (j.find()) {
            String d = decodeXml(j.group(2)).trim();
            if (!d.isEmpty()) { deps.add(d); }
        }
        Matcher l = LS_USE.matcher(dxl);
        while (l.find()) {
            String d = decodeXml(l.group(1)).trim();
            if (!d.isEmpty()) { deps.add(d); }
        }
        String self = extractDesignElementName(dxl);
        if (self != null) { deps.remove(self); }
        return new ArrayList<String>(deps);
    }

    // -----------------------------------------------------------------------
    // Phase split
    // -----------------------------------------------------------------------

    /** Result of {@link #split(List)}: the two ordered phases plus diagnostics. */
    static final class Plan {
        final List<File> phase1NonCompiled = new ArrayList<File>();
        final List<File> phase2Compiled    = new ArrayList<File>();     // topologically ordered
        final List<String> missingRefs     = new ArrayList<String>();   // deps not present in the tree
        final List<String> cycleNodes      = new ArrayList<String>();   // elements involved in a cycle
    }

    /**
     * Partition {@code dxlFiles} into the non-compiled phase and the topologically-ordered
     * compiled phase. The input order is treated as the stable base order (callers pass an
     * alphabetically-sorted list), which is preserved for phase 1 and used as the deterministic
     * tie-break when several compiled elements are ready at once.
     */
    static Plan split(List<File> dxlFiles) {
        Plan plan = new Plan();
        List<File> compiled = new ArrayList<File>();
        for (File f : dxlFiles) {
            if (isOrderedCompiledCode(f)) {
                compiled.add(f);
            } else {
                plan.phase1NonCompiled.add(f);   // keep caller's stable order
            }
        }
        topologicalOrder(compiled, plan);
        return plan;
    }

    /**
     * Kahn topological sort over the compiled files. Edge {@code dep -> element} means the
     * dependency must import first, so the emitted order lists dependencies before their
     * dependents. Ready nodes are emitted in the caller's stable (alphabetical) order.
     * Dependencies that name an element not present in the tree are recorded in
     * {@link Plan#missingRefs} and ignored (they cannot be ordered — usually a {@code .jar}
     * or a platform LSX). Any nodes left over form a cycle: they are appended in stable order
     * and recorded in {@link Plan#cycleNodes}.
     */
    private static void topologicalOrder(List<File> compiled, Plan plan) {
        // name -> file (stable order); later duplicates keep the first occurrence.
        Map<String, File>   fileByName = new LinkedHashMap<String, File>();
        Map<File, String>   nameByFile = new LinkedHashMap<File, String>();
        Map<File, List<String>> depsByFile = new LinkedHashMap<File, List<String>>();

        for (File f : compiled) {
            String dxl  = readFully(f);
            String name = extractDesignElementName(dxl);
            if (name == null) {
                // No parseable name: use the file path so it is still a distinct node.
                name = f.getAbsolutePath();
            }
            if (!fileByName.containsKey(name)) {
                fileByName.put(name, f);
            }
            nameByFile.put(f, name);
            depsByFile.put(f, extractDependencyNames(dxl));
        }

        // Resolve edges; count in-degree per file (number of deps present in this tree).
        Map<File, Integer>    inDegree = new LinkedHashMap<File, Integer>();
        Map<File, List<File>> dependents = new LinkedHashMap<File, List<File>>();  // dep-file -> files that need it
        Set<String> missing = new LinkedHashSet<String>();
        for (File f : compiled) {
            inDegree.put(f, 0);
        }
        for (File f : compiled) {
            for (String dep : depsByFile.get(f)) {
                File depFile = fileByName.get(dep);
                if (depFile == null) {
                    missing.add(dep);
                    continue;                         // unresolved (jar / external LSX): ignore
                }
                if (depFile.equals(f)) {
                    continue;                         // self-reference: no edge
                }
                List<File> ds = dependents.get(depFile);
                if (ds == null) { ds = new ArrayList<File>(); dependents.put(depFile, ds); }
                ds.add(f);
                inDegree.put(f, inDegree.get(f) + 1);
            }
        }
        plan.missingRefs.addAll(missing);

        // Kahn: repeatedly emit the earliest (stable-order) file with in-degree 0.
        List<File> ordered = new ArrayList<File>();
        Set<File> emitted = new LinkedHashSet<File>();
        boolean progress = true;
        while (progress) {
            progress = false;
            for (File f : compiled) {                 // compiled is the stable order
                if (!emitted.contains(f) && inDegree.get(f) == 0) {
                    ordered.add(f);
                    emitted.add(f);
                    progress = true;
                    List<File> ds = dependents.get(f);
                    if (ds != null) {
                        for (File d : ds) {
                            inDegree.put(d, inDegree.get(d) - 1);
                        }
                    }
                    break;                            // restart scan to keep order deterministic
                }
            }
        }

        // Anything not emitted is in a cycle (shouldn't happen for a valid design).
        for (File f : compiled) {
            if (!emitted.contains(f)) {
                ordered.add(f);
                plan.cycleNodes.add(nameByFile.get(f));
            }
        }

        plan.phase2Compiled.addAll(ordered);
    }

    // -----------------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------------

    /** Read a whole file as UTF-8; empty string on any error (caller degrades gracefully). */
    private static String readFully(File f) {
        InputStream in = null;
        try {
            in = new FileInputStream(f);
            byte[] buf = new byte[HEAD_BYTES];
            StringBuilder sb = new StringBuilder();
            int n;
            while ((n = in.read(buf)) > 0) {
                sb.append(new String(buf, 0, n, "UTF-8"));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        } finally {
            if (in != null) { try { in.close(); } catch (Exception ignore) { /* best effort */ } }
        }
    }

    /** Decode the handful of XML entities that appear in DXL names. */
    private static String decodeXml(String s) {
        if (s == null || s.indexOf('&') < 0) {
            return s;
        }
        return s.replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&amp;", "&");   // must be last
    }
}
