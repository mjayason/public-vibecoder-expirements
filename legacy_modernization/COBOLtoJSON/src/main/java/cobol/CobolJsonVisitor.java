package cobol;

import cobol.antlr.Cobol85BaseVisitor;
import cobol.antlr.Cobol85Parser;
import org.antlr.v4.runtime.TokenStream;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import java.util.*;

/**
 * Visitor for extracting COBOL program structure and control flow into JSON and Mermaid formats.
 * Supports error reporting and case normalization for modernization.
 */
public class CobolJsonVisitor extends Cobol85BaseVisitor<Void> {
    private final Map<String, JSONArray> paragraphMap = new LinkedHashMap<>();
    private final Set<String> performCalls = new LinkedHashSet<>();
    private final Set<String> callStatements = new LinkedHashSet<>();
    private final Map<String, String> paragraphOrigins = new HashMap<>();
    private final Map<String, Set<String>> callGraph = new LinkedHashMap<>();
    private final Set<String> callGraphCallNodes = new HashSet<>();
    private final List<ParsingError> errors = new ArrayList<>();

    private String currentParagraph = null;
    private String programId = "UNKNOWN";
    private TokenStream tokens;

    /**
     * Sets the program ID for the COBOL program.
     * @param id The program ID
     */
    public void setProgramId(String id) {
        this.programId = id.toUpperCase();
    }

    /**
     * Sets the token stream for extracting text.
     * @param tokens The ANTLR token stream
     */
    public void setTokenStream(TokenStream tokens) {
        this.tokens = tokens;
    }

    /**
     * Gets the list of parsing errors encountered during visitation.
     * @return List of ParsingError objects
     */
    public List<ParsingError> getErrors() {
        return errors;
    }

    /**
     * Extracts and validates the program ID from the PROGRAM-ID paragraph.
     * Normalizes the ID to uppercase for consistency with COBOL's case-insensitive nature.
     * Reports errors for missing, invalid, or conflicting IDs to aid modernization debugging.
     * @param ctx The ProgramIdParagraphContext from the COBOL AST
     * @return Void as per the ANTLR visitor pattern
     */
    public Void visitProgramId(Cobol85Parser.ProgramIdParagraphContext ctx) {
        if (ctx.programName() == null) {
            errors.add(new ParsingError("UNKNOWN", "Missing program name in PROGRAM-ID paragraph", ctx.getStart().getLine()));
            programId = "UNKNOWN_" + UUID.randomUUID().toString().substring(0, 8);
        } else {
            String name = ctx.programName().getText().trim();
            if (name.isEmpty()) {
                errors.add(new ParsingError("UNKNOWN", "Empty program name in PROGRAM-ID paragraph", ctx.getStart().getLine()));
                programId = "UNKNOWN_" + UUID.randomUUID().toString().substring(0, 8);
            } else if (!name.matches("[A-Za-z0-9-]{1,31}")) {
                errors.add(new ParsingError("UNKNOWN", "Invalid program name: " + name + " (must be alphanumeric or hyphen, max 31 characters)", ctx.getStart().getLine()));
                programId = name.toUpperCase();
            } else {
                programId = name.toUpperCase();
            }
            if (paragraphMap.containsKey(programId)) {
                errors.add(new ParsingError(programId, "Program ID conflicts with paragraph name: " + programId, ctx.getStart().getLine()));
            }
        }
        return super.visitProgramIdParagraph(ctx);
    }

    @Override
    public Void visitProcedureDivision(Cobol85Parser.ProcedureDivisionContext ctx) {
        currentParagraph = "_MAIN";
        paragraphMap.put(currentParagraph, new JSONArray());
        paragraphOrigins.put(currentParagraph, programId);
        return visitChildren(ctx);
    }

    @Override
    public Void visitParagraph(Cobol85Parser.ParagraphContext ctx) {
        if (ctx.paragraphName() != null) {
            currentParagraph = ctx.paragraphName().getText();
            paragraphMap.put(currentParagraph, new JSONArray());
            paragraphOrigins.put(currentParagraph, programId);
        }
        return visitChildren(ctx);
    }

    /**
     * Processes a COBOL sentence, capturing its text and line number.
     * @param ctx The SentenceContext from the COBOL AST
     * @return Void as per the visitor pattern
     */
    @Override
    public Void visitSentence(Cobol85Parser.SentenceContext ctx) {
        String text = tokens.getText(ctx);
        String[] lines = text.split("\\r?\\n");
        int startLine = ctx.getStart().getLine();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (!line.isEmpty()) {
                line = line.replaceAll("\\s+", " ").replaceAll("'([^']*)'", "'$1'");
                System.out.println("Sentence at line " + (startLine + i) + ": " + line);
                JSONObject lineObj = new JSONObject();
                lineObj.put("text", line);
                lineObj.put("from", currentParagraph);
                lineObj.put("line", startLine + i);
                paragraphMap.get(currentParagraph).add(lineObj);
                processControlFlowTokens(line);
            }
        }
        return super.visitSentence(ctx);
    }

    @Override
    public Void visitSectionHeader(Cobol85Parser.ProcedureSectionHeaderContext ctx) {
        return null;
    }

    /**
     * Processes control flow statements (PERFORM, CALL) to build the call graph.
     * Normalizes CALL targets to uppercase for consistency.
     * @param logic The COBOL statement text
     */
    private void processControlFlowTokens(String logic) {
        String upper = logic.toUpperCase();
        if (upper.startsWith("CALL")) {
            int firstQuote = logic.indexOf('\'');
            int secondQuote = logic.indexOf('\'', firstQuote + 1);
            if (firstQuote != -1 && secondQuote != -1) {
                String target = logic.substring(firstQuote + 1, secondQuote).trim().toUpperCase();
                String callNode = "CALL::" + target;
                callStatements.add(target);
                addEdge(currentParagraph, callNode);
                callGraphCallNodes.add(callNode);
            } else {
                errors.add(new ParsingError(programId, "Invalid CALL statement: " + logic, 0));
            }
        } else if (upper.startsWith("PERFORM ")) {
            String[] tokens = logic.split("\\s+", 3);
            if (tokens.length >= 2 && !tokens[1].equalsIgnoreCase("VARYING")) {
                String performTarget = tokens[1].replace(".", "");
                performCalls.add(performTarget);
                addEdge(currentParagraph, performTarget);
            }
            if (tokens.length >= 4 && tokens[2].equalsIgnoreCase("THRU")) {
                performCalls.add(tokens[1] + "-THRU-" + tokens[3]);
            }
        }
    }

    /**
     * Adds an edge to the call graph.
     * @param from Source paragraph
     * @param to Target paragraph or call
     */
    private void addEdge(String from, String to) {
        callGraph.computeIfAbsent(from, k -> new LinkedHashSet<>()).add(to);
    }

    /**
     * Generates the JSON output for the COBOL program.
     * @return JSONObject containing program structure, control flow, and errors
     */
    public JSONObject getJsonOutput() {
        JSONObject root = new JSONObject();
        root.put("programId", programId);
        root.put("paragraphs", getParagraphJson());
        root.put("paragraphOrigins", getOriginJson());

        JSONObject calls = new JSONObject();
        calls.put("PERFORM", new JSONArray() {{ addAll(performCalls); }});
        calls.put("CALL", new JSONArray() {{ addAll(callStatements); }});
        root.put("calls", calls);

        root.put("callGraph", getCallGraphJson());

        // Add errors
        JSONArray errorArray = new JSONArray();
        for (ParsingError error : errors) {
            errorArray.add(error.toJson());
        }
        root.put("errors", errorArray);

        return root;
    }

    /**
     * Finds unreachable paragraphs using DFS.
     * @return Set of unreachable paragraph names
     */
    public Set<String> findUnreachableParagraphs() {
        Set<String> reachable = new HashSet<>();
        Set<String> allParagraphs = new HashSet<>(paragraphMap.keySet());
        dfs("_MAIN", reachable);
        allParagraphs.removeAll(reachable);
        return allParagraphs;
    }

    /**
     * Performs DFS to mark reachable paragraphs.
     * @param node Current paragraph
     * @param visited Set of visited paragraphs
     */
    private void dfs(String node, Set<String> visited) {
        if (!visited.add(node)) return;
        Set<String> targets = callGraph.getOrDefault(node, Collections.emptySet());
        for (String target : targets) {
            if (!target.startsWith("CALL::")) {
                dfs(target, visited);
            }
        }
    }

    /**
     * Generates paragraph JSON structure.
     * @return JSONObject mapping paragraphs to statements
     */
    private JSONObject getParagraphJson() {
        JSONObject paraJson = new JSONObject();
        for (Map.Entry<String, JSONArray> entry : paragraphMap.entrySet()) {
            paraJson.put(entry.getKey(), entry.getValue());
        }
        return paraJson;
    }

    /**
     * Generates paragraph origin JSON.
     * @return JSONObject mapping paragraphs to program IDs
     */
    private JSONObject getOriginJson() {
        JSONObject originJson = new JSONObject();
        for (Map.Entry<String, String> entry : paragraphOrigins.entrySet()) {
            originJson.put(entry.getKey(), entry.getValue());
        }
        return originJson;
    }

    /**
     * Generates call graph JSON.
     * @return JSONObject representing the call graph
     */
    private JSONObject getCallGraphJson() {
        JSONObject graphJson = new JSONObject();
        for (Map.Entry<String, Set<String>> entry : callGraph.entrySet()) {
            graphJson.put(entry.getKey(), new JSONArray() {{ addAll(entry.getValue()); }});
        }
        return graphJson;
    }

    /**
     * Generates Mermaid diagram for the call graph.
     * @return String containing Mermaid syntax
     */
    public String getCallGraphMermaid() {
        StringBuilder sb = new StringBuilder("graph TD\n");
        for (Map.Entry<String, Set<String>> entry : callGraph.entrySet()) {
            String from = entry.getKey();
            for (String target : entry.getValue()) {
                boolean isCallNode = target.startsWith("CALL::");
                String displayTarget = isCallNode ? target.substring(6) : target;
                String targetId = isCallNode ? "CALL_" + displayTarget : displayTarget;
                sb.append("  ").append(from).append(" --> ").append(targetId).append("\n");
            }
        }

        for (String callNode : callGraphCallNodes) {
            String label = callNode.substring(6);
            sb.append("  CALL_").append(label).append(":::externalCall\n");
        }

        sb.append("\nclassDef externalCall fill:#fdd,stroke:#d00;\n");
        return sb.toString();
    }

    public Map<String, JSONArray> getParagraphMap() {
        return paragraphMap;
    }

    public String getProgramId() {
        return this.programId;
    }

    public Set<String> getCallStatements() {
        return callStatements;
    }
}