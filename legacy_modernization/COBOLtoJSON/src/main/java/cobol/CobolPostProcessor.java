package cobol;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import java.util.*;

public class CobolPostProcessor {

    private final JSONObject root;
    private final List<Integer> lineNumberMap;

    public CobolPostProcessor(JSONObject rootJson, List<Integer> lineNumberMap) {
        this.root = rootJson;
        this.lineNumberMap = lineNumberMap != null ? lineNumberMap : new ArrayList<>();
    }

    public JSONObject process() {
        unifyStructuredStatements();
        normalizeMetadata();
        normalizePerforms();
        deduplicateParagraphs();
        normalizePseudocode();
        cleanupOtherTypes();
        promoteSemanticTypes();
        normalizeConditions();
        structureFileDescriptions();
        generateControlFlowGraph();
        return root;
    }

    private void unifyStructuredStatements() {
        JSONObject structuredStatements = (JSONObject) root.get("structuredStatements");
        if (structuredStatements == null) return;

        for (Object key : structuredStatements.keySet()) {
            String paraName = (String) key;
            JSONArray statements = (JSONArray) structuredStatements.get(paraName);
            JSONArray unified = new JSONArray();
            Set<String> seen = new HashSet<>();

            for (Object obj : statements) {
                JSONObject stmt = (JSONObject) obj;
                String signature = generateStatementSignature(stmt);
                if (seen.add(signature)) {
                    Long line = (Long) stmt.get("line");
                    if (line != null && line > 0 && line <= lineNumberMap.size()) {
                        stmt.put("line", lineNumberMap.get(line.intValue() - 1));
                    }
                    flattenNestedBlocks(stmt);
                    unified.add(stmt);
                } else {
                    System.out.println("Skipping duplicate statement in paragraph " + paraName + ": " + signature);
                }
            }
            structuredStatements.put(paraName, unified);
        }
    }

    private String generateStatementSignature(JSONObject stmt) {
        String type = (String) stmt.getOrDefault("type", "");
        Long line = (Long) stmt.getOrDefault("line", 0L);
        String content = (String) stmt.getOrDefault("content", "");
        return type + ":" + line + ":" + content;
    }

    private void flattenNestedBlocks(JSONObject stmt) {
        if (stmt.containsKey("then")) {
            JSONArray thenBlock = (JSONArray) stmt.get("then");
            if (thenBlock.isEmpty()) {
                stmt.remove("then");
            } else {
                for (Object subStmt : thenBlock) {
                    flattenNestedBlocks((JSONObject) subStmt);
                }
            }
        }
        if (stmt.containsKey("else")) {
            JSONArray elseBlock = (JSONArray) stmt.get("else");
            if (elseBlock.isEmpty()) {
                stmt.remove("else");
            } else {
                for (Object subStmt : elseBlock) {
                    flattenNestedBlocks((JSONObject) subStmt);
                }
            }
        }
        if (stmt.containsKey("cases")) {
            JSONArray cases = (JSONArray) stmt.get("cases");
            if (cases.isEmpty()) {
                stmt.remove("cases");
            } else {
                for (Object subStmt : cases) {
                    flattenNestedBlocks((JSONObject) subStmt);
                }
            }
        }
    }

    private void normalizeMetadata() {
        JSONObject structuredStatements = (JSONObject) root.get("structuredStatements");
        if (structuredStatements == null) return;

        for (Object key : structuredStatements.keySet()) {
            JSONArray statements = (JSONArray) structuredStatements.get(key);
            for (Object obj : statements) {
                JSONObject stmt = (JSONObject) obj;
                if (!stmt.containsKey("metadata")) continue;
                JSONObject meta = (JSONObject) stmt.get("metadata");

                // Normalize VARYING
                if (meta.containsKey("varying")) {
                    JSONObject varying = (JSONObject) meta.get("varying");
                    JSONObject loop = new JSONObject();
                    loop.put("variable", varying.getOrDefault("variable", "UNKNOWN"));
                    loop.put("from", varying.getOrDefault("from", "UNKNOWN"));
                    loop.put("by", varying.getOrDefault("by", "UNKNOWN"));
                    loop.put("until", varying.getOrDefault("until", "UNKNOWN"));
                    loop.put("loopLevel", varying.getOrDefault("loopLevel", 0));
                    loop.put("parent", varying.getOrDefault("parent", null));
                    meta.put("loop", loop);
                    meta.remove("varying");
                }

                // Normalize PERFORM THRU
                if (meta.containsKey("target") && meta.containsKey("thru")) {
                    JSONObject perform = new JSONObject();
                    perform.put("start", meta.get("target"));
                    perform.put("end", meta.get("thru"));
                    perform.put("loopLevel", meta.getOrDefault("loopLevel", 0));
                    perform.put("parent", meta.getOrDefault("parent", null));
                    meta.put("perform", perform);
                    meta.remove("target");
                    meta.remove("thru");
                }

                // Normalize CALL
                if (meta.containsKey("program")) {
                    JSONObject call = new JSONObject();
                    call.put("name", meta.get("program"));
                    if (meta.containsKey("parameters")) {
                        JSONArray params = (JSONArray) meta.get("parameters");
                        JSONArray normalizedParams = new JSONArray();
                        for (Object param : params) {
                            JSONObject paramObj = (JSONObject) param;
                            normalizedParams.add(paramObj);
                        }
                        call.put("args", normalizedParams);
                    }
                    call.put("parent", meta.getOrDefault("parent", null));
                    meta.put("call", call);
                    meta.remove("program");
                    meta.remove("parameters");
                }

                // Normalize GO TO
                if (meta.containsKey("target") && !meta.containsKey("perform")) {
                    JSONObject gotoStmt = new JSONObject();
                    gotoStmt.put("destination", meta.get("target"));
                    gotoStmt.put("parent", meta.getOrDefault("parent", null));
                    meta.put("goto", gotoStmt);
                    meta.remove("target");
                }

                // Normalize FILE I/O
                if (meta.containsKey("file")) {
                    JSONObject fileOp = new JSONObject();
                    fileOp.put("name", meta.get("file"));
                    if (meta.containsKey("fd")) {
                        fileOp.put("description", meta.get("fd"));
                    }
                    fileOp.put("parent", meta.getOrDefault("parent", null));
                    meta.put("fileOp", fileOp);
                    meta.remove("file");
                    meta.remove("fd");
                }
            }
        }
    }

    private void normalizePerforms() {
        JSONObject structuredStatements = (JSONObject) root.get("structuredStatements");
        if (structuredStatements == null) return;

        for (Object key : structuredStatements.keySet()) {
            JSONArray statements = (JSONArray) structuredStatements.get(key);
            for (Object obj : statements) {
                JSONObject stmt = (JSONObject) obj;
                if (!stmt.getOrDefault("type", "").equals("PERFORM")) continue;
                String pseudocode = (String) stmt.getOrDefault("pseudocode", "");
                JSONObject meta = (JSONObject) stmt.getOrDefault("metadata", new JSONObject());

                // Strip trailing semicolon
                if (pseudocode.endsWith(";")) {
                    stmt.put("pseudocode", pseudocode.substring(0, pseudocode.length() - 1));
                }

                // Extract target for simple PERFORM or PERFORM THRU
                if (!meta.containsKey("loop") && !meta.containsKey("perform")) {
                    String content = ((String) stmt.getOrDefault("content", "")).toUpperCase();
                    String[] parts = content.split("\\s+", 4);
                    if (parts.length >= 2) {
                        JSONObject perform = new JSONObject();
                        perform.put("start", parts[1]);
                        if (parts.length >= 3 && parts[2].equals("THRU")) {
                            perform.put("end", parts[3]);
                        }
                        perform.put("loopLevel", meta.getOrDefault("loopLevel", 0));
                        perform.put("parent", meta.getOrDefault("parent", null));
                        meta.put("perform", perform);
                    }
                }
            }
        }
    }

    private void deduplicateParagraphs() {
        JSONObject structuredStatements = (JSONObject) root.get("structuredStatements");
        if (structuredStatements == null) return;

        JSONObject deduplicated = new JSONObject();
        for (Object key : structuredStatements.keySet()) {
            String paraName = (String) key;
            if (!deduplicated.containsKey(paraName)) {
                deduplicated.put(paraName, structuredStatements.get(paraName));
            } else {
                System.out.println("Skipping duplicate paragraph: " + paraName);
            }
        }
        root.put("structuredStatements", deduplicated);
    }

    private void normalizePseudocode() {
        JSONObject structuredStatements = (JSONObject) root.get("structuredStatements");
        if (structuredStatements == null) return;

        for (Object key : structuredStatements.keySet()) {
            JSONArray statements = (JSONArray) structuredStatements.get(key);
            for (Object obj : statements) {
                JSONObject stmt = (JSONObject) obj;
                String ps = (String) stmt.getOrDefault("pseudocode", "");
                ps = ps.trim().replaceAll("\\s+", " ");
                stmt.put("pseudocode", ps);
            }
        }
    }

    private void cleanupOtherTypes() {
        JSONObject structuredStatements = (JSONObject) root.get("structuredStatements");
        if (structuredStatements == null) return;

        for (Object key : structuredStatements.keySet()) {
            JSONArray statements = (JSONArray) structuredStatements.get(key);
            for (Object obj : statements) {
                JSONObject stmt = (JSONObject) obj;
                String content = ((String) stmt.getOrDefault("content", "")).toUpperCase();
                if (content.startsWith("AT END")) {
                    stmt.put("type", "AT_END");
                    if (!stmt.containsKey("then")) {
                        stmt.put("then", new JSONArray());
                    }
                } else if (content.startsWith("NOT AT END")) {
                    stmt.put("type", "NOT_AT_END");
                    if (!stmt.containsKey("then")) {
                        stmt.put("then", new JSONArray());
                    }
                }
            }
        }
    }

    private void promoteSemanticTypes() {
        JSONObject structuredStatements = (JSONObject) root.get("structuredStatements");
        if (structuredStatements == null) return;

        for (Object key : structuredStatements.keySet()) {
            JSONArray statements = (JSONArray) structuredStatements.get(key);
            for (Object obj : statements) {
                JSONObject stmt = (JSONObject) obj;
                String content = ((String) stmt.getOrDefault("content", "")).toUpperCase();
                String type = (String) stmt.getOrDefault("type", "");
                if (type.equals("OTHER")) {
                    if (content.startsWith("DISPLAY")) {
                        stmt.put("type", "DISPLAY");
                    } else if (content.startsWith("MOVE")) {
                        stmt.put("type", "MOVE");
                    } else if (content.startsWith("ADD")) {
                        stmt.put("type", "ADD");
                    } else if (content.startsWith("CALL")) {
                        stmt.put("type", "CALL");
                    } else if (content.startsWith("READ")) {
                        stmt.put("type", "READ");
                    } else if (content.startsWith("CLOSE")) {
                        stmt.put("type", "CLOSE");
                    } else if (content.startsWith("OPEN")) {
                        stmt.put("type", "OPEN");
                    } else if (content.startsWith("SUBTRACT")) {
                        stmt.put("type", "SUBTRACT");
                    } else if (content.startsWith("INSPECT")) {
                        stmt.put("type", "INSPECT");
                    } else if (content.startsWith("ACCEPT")) {
                        stmt.put("type", "ACCEPT");
                    } else if (content.startsWith("GOBACK")) {
                        stmt.put("type", "GOBACK");
                    } else if (content.startsWith("STOP RUN")) {
                        stmt.put("type", "STOP RUN");
                    }
                }
            }
        }
    }

    private void normalizeConditions() {
        JSONObject structuredStatements = (JSONObject) root.get("structuredStatements");
        if (structuredStatements == null) return;

        for (Object key : structuredStatements.keySet()) {
            JSONArray statements = (JSONArray) structuredStatements.get(key);
            for (Object obj : statements) {
                JSONObject stmt = (JSONObject) obj;
                String content = ((String) stmt.getOrDefault("content", "")).toUpperCase();
                if ((stmt.getOrDefault("type", "").equals("IF") || stmt.getOrDefault("type", "").equals("WHEN")) && !stmt.containsKey("condition")) {
                    String conditionText = content.startsWith("IF ") ? content.substring(3) : content.startsWith("WHEN ") ? content.substring(5) : content;
                    JSONObject condition = new JSONObject();
                    String operator = null;
                    String[] parts = null;

                    if (conditionText.contains("=")) {
                        parts = conditionText.split("\\s*=\\s*");
                        operator = "=";
                    } else if (conditionText.contains(">")) {
                        parts = conditionText.split("\\s*>\\s*");
                        operator = ">";
                    } else if (conditionText.contains("<")) {
                        parts = conditionText.split("\\s*<\\s*");
                        operator = "<";
                    } else if (conditionText.contains("NOT=")) {
                        parts = conditionText.split("\\s*NOT=\\s*");
                        operator = "NOT=";
                    }

                    if (parts != null && parts.length == 2 && operator != null) {
                        condition.put("lhs", parts[0].trim());
                        condition.put("rhs", parts[1].trim());
                        condition.put("operator", operator);
                        stmt.put("condition", condition);
                        stmt.put("type", "CONDITION");
                    }
                }
            }
        }
    }

    private void structureFileDescriptions() {
        JSONObject fileDescriptions = (JSONObject) root.get("fileDescriptions");
        if (fileDescriptions == null) return;

        for (Object key : fileDescriptions.keySet()) {
            JSONObject fd = (JSONObject) fileDescriptions.get(key);
            JSONObject structuredFd = new JSONObject();
            structuredFd.put("name", fd.getOrDefault("name", "UNKNOWN"));
            structuredFd.put("label", fd.getOrDefault("label", "OMITTED"));
            structuredFd.put("line", fd.getOrDefault("line", 0));

            JSONArray records = (JSONArray) fd.getOrDefault("records", new JSONArray());
            JSONArray structuredRecords = new JSONArray();
            for (Object rec : records) {
                JSONObject record = (JSONObject) rec;
                JSONObject structuredRec = new JSONObject();
                structuredRec.put("name", record.getOrDefault("name", "UNKNOWN"));
                structuredRec.put("level", record.getOrDefault("level", "UNKNOWN"));
                structuredRec.put("line", record.getOrDefault("line", 0));
                if (record.containsKey("picture")) {
                    structuredRec.put("picture", record.get("picture"));
                }
                if (record.containsKey("workingStorageRef")) {
                    structuredRec.put("workingStorageRef", record.get("workingStorageRef"));
                }
                structuredRecords.add(structuredRec);
            }
            structuredFd.put("records", structuredRecords);
            if (fd.containsKey("fileControl")) {
                structuredFd.put("fileControl", fd.get("fileControl"));
            }
            fileDescriptions.put(key, structuredFd);
        }
    }

    private void generateControlFlowGraph() {
        JSONObject structuredStatements = (JSONObject) root.get("structuredStatements");
        if (structuredStatements == null) return;

        JSONObject controlFlow = new JSONObject();
        JSONArray edges = new JSONArray();
        String entryPoint = "UNKNOWN";

        for (Object key : structuredStatements.keySet()) {
            String paraName = (String) key;
            JSONArray statements = (JSONArray) structuredStatements.get(paraName);
            Long prevLine = null;

            // Set entry point for _MAIN paragraph
            if (paraName.equals("_MAIN") && !statements.isEmpty()) {
                JSONObject firstStmt = (JSONObject) statements.get(0);
                Long line = (Long) firstStmt.get("line");
                if (line != null && line > 0 && line <= lineNumberMap.size()) {
                    line = (long) lineNumberMap.get(line.intValue() - 1);
                }
                entryPoint = "LINE_" + line;
            }

            // Sort statements by line number for consistent nesting
            List<JSONObject> sortedStatements = new ArrayList<>();
            for (Object stmtObj : statements) {
                sortedStatements.add((JSONObject) stmtObj);
            }
            sortedStatements.sort(Comparator.comparingLong(stmt -> (Long) stmt.get("line")));

            Deque<JSONObject> blockStack = new ArrayDeque<>();
            for (JSONObject stmt : sortedStatements) {
                Long line = (Long) stmt.get("line");
                if (line != null && line > 0 && line <= lineNumberMap.size()) {
                    line = (long) lineNumberMap.get(line.intValue() - 1);
                    stmt.put("line", line);
                }
                String type = (String) stmt.get("type");
                JSONObject meta = (JSONObject) stmt.getOrDefault("metadata", new JSONObject());

                // Add sequential edges
                if (prevLine != null && !type.equals("END-IF") && !type.equals("END-EVALUATE") && !type.equals("END-PERFORM")) {
                    JSONObject edge = new JSONObject();
                    edge.put("from", "LINE_" + prevLine);
                    edge.put("to", "LINE_" + line);
                    edges.add(edge);
                }

                // Add control flow edges
                if (type.equals("GO TO") && meta.containsKey("goto")) {
                    JSONObject gotoStmt = (JSONObject) meta.get("goto");
                    String destination = (String) gotoStmt.get("destination");
                    JSONObject edge = new JSONObject();
                    edge.put("from", "LINE_" + line);
                    edge.put("to", destination);
                    edges.add(edge);
                } else if (type.equals("PERFORM") && meta.containsKey("perform")) {
                    JSONObject perform = (JSONObject) meta.get("perform");
                    String start = (String) perform.get("start");
                    String end = (String) perform.getOrDefault("end", null);
                    JSONObject edge = new JSONObject();
                    edge.put("from", "LINE_" + line);
                    edge.put("to", start);
                    edges.add(edge);
                    if (end != null) {
                        JSONObject endEdge = new JSONObject();
                        endEdge.put("from", "LINE_" + line);
                        endEdge.put("to", end);
                        edges.add(endEdge);
                    }
                    blockStack.push(stmt);
                } else if (type.equals("PERFORM") && meta.containsKey("loop")) {
                    blockStack.push(stmt);
                } else if (type.equals("IF") || type.equals("EVALUATE") || type.equals("AT_END") || type.equals("NOT_AT_END")) {
                    blockStack.push(stmt);
                } else if (type.equals("END-IF") || type.equals("END-EVALUATE") || type.equals("END-PERFORM")) {
                    if (!blockStack.isEmpty()) {
                        JSONObject parentStmt = blockStack.pop();
                        JSONObject edge = new JSONObject();
                        edge.put("from", "LINE_" + parentStmt.get("line"));
                        edge.put("to", "LINE_" + line);
                        edges.add(edge);
                    }
                }

                // Add edges for nested blocks
                if (blockStack.size() > 1) {
                    JSONObject parentStmt = blockStack.peek();
                    JSONObject edge = new JSONObject();
                    edge.put("from", "LINE_" + parentStmt.get("line"));
                    edge.put("to", "LINE_" + line);
                    edges.add(edge);
                }

                prevLine = line;
            }
        }

        controlFlow.put("edges", edges);
        controlFlow.put("entryPoint", entryPoint);
        root.put("controlFlow", controlFlow);
    }
}