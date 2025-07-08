package cobol;

import cobol.antlr.Cobol85BaseVisitor;
import cobol.antlr.Cobol85Parser;
import org.antlr.v4.runtime.tree.ParseTree;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import java.util.*;

/**
 * Transforms COBOL statements into a structured JSON tree for control flow analysis.
 * Supports PERFORM THRU/VARYING, CALL tracing, COPYBOOK integration, GO TO graph handling,
 * and File I/O with FD section modeling.
 */
public class StructuredStatementTransformer extends Cobol85BaseVisitor<Void> {

    private final JSONArray structuredStatements = new JSONArray();
    private final Deque<JSONObject> statementStack = new ArrayDeque<>();
    private String currentParagraph = "_MAIN";
    private boolean visitingThen = false;
    private boolean visitingElse = false;
    private boolean inWhenClause = false;
    private final List<ParsingError> errors = new ArrayList<>();
    private final Set<String> copybooksIncluded = new HashSet<>();
    private final Map<String, JSONObject> fileDescriptions = new HashMap<>();

    public static class ContextMetadata {
        public final String paragraph;
        public final String programId;

        public ContextMetadata(String paragraph, String programId) {
            this.paragraph = paragraph;
            this.programId = programId;
        }
    }

    public static class StructuredStatement {
        public final String type;
        public final int line;
        public final String content;
        public final String pseudocode;
        public final JSONArray thenBlock;
        public final JSONArray elseBlock;
        public final JSONArray cases;
        public final JSONObject metadata;

        public StructuredStatement(String type, int line, String content) {
            this(type, line, content, type.equals("IF") || type.equals("PERFORM") ? new JSONArray() : null,
                    type.equals("IF") ? new JSONArray() : null,
                    type.equals("EVALUATE") ? new JSONArray() : null,
                    new JSONObject());
        }

        public StructuredStatement(String type, int line, String content, JSONArray thenBlock, JSONArray elseBlock, JSONArray cases, JSONObject metadata) {
            this.type = type;
            this.line = line;
            this.content = content;
            this.pseudocode = generatePseudocode(type, content, metadata);
            this.thenBlock = thenBlock;
            this.elseBlock = elseBlock;
            this.cases = cases;
            this.metadata = metadata;
        }

        public JSONObject toJson() {
            JSONObject obj = new JSONObject();
            obj.put("type", type);
            obj.put("line", line);
            obj.put("content", content);
            obj.put("pseudocode", pseudocode);
            if (thenBlock != null) {
                obj.put("then", thenBlock);
            }
            if (elseBlock != null) {
                obj.put("else", elseBlock);
            }
            if (cases != null) {
                obj.put("cases", cases);
            }
            if (!metadata.isEmpty()) {
                obj.put("metadata", metadata);
            }
            return obj;
        }

        private static String generatePseudocode(String type, String content, JSONObject metadata) {
            switch (type) {
                case "MOVE":
                    String[] moveParts = content.split("\\s+TO\\s+", 2);
                    if (moveParts.length == 2) {
                        return moveParts[1] + " = " + moveParts[0].replace("MOVE ", "") + ";";
                    }
                    break;
                case "IF":
                    return "if (" + content.replace("IF ", "") + ") {";
                case "ELSE":
                    return "} else {";
                case "END-IF":
                    return "}";
                case "PERFORM":
                    String target = (String) metadata.getOrDefault("target", "");
                    String thru = (String) metadata.getOrDefault("thru", "");
                    String varying = (String) metadata.getOrDefault("varying", "");
                    if (!varying.isEmpty()) {
                        return "for (" + varying + ") {";
                    } else if (!thru.isEmpty()) {
                        return "call " + target + " thru " + thru + ";";
                    } else {
                        return "call " + content.replace("PERFORM ", "") + ";";
                    }
                case "CALL":
                    String program = (String) metadata.getOrDefault("program", "");
                    JSONArray params = (JSONArray) metadata.getOrDefault("parameters", new JSONArray());
                    return "call_program(" + program + "(" + params.toJSONString() + "));";
                case "ADD":
                    return content.replace("ADD ", "") + ";";
                case "SUBTRACT":
                    return content.replace("SUBTRACT ", "") + ";";
                case "READ":
                    return "read_file(" + content.replace("READ ", "") + ");";
                case "WRITE":
                    return "write_file(" + content.replace("WRITE ", "") + ");";
                case "OPEN":
                    return "open_file(" + content.replace("OPEN ", "") + ");";
                case "CLOSE":
                    return "close_file(" + content.replace("CLOSE ", "") + ");";
                case "INSPECT":
                    return "inspect(" + content.replace("INSPECT ", "") + ");";
                case "EVALUATE":
                    return "switch (" + content.replace("EVALUATE ", "") + ") {";
                case "WHEN":
                    return "case " + content.replace("WHEN ", "") + ":";
                case "END-EVALUATE":
                    return "}";
                case "END-PERFORM":
                    return "}";
                case "GO TO":
                    String gotoTarget = (String) metadata.getOrDefault("target", "");
                    return "goto " + gotoTarget + ";";
                default:
                    return content + ";";
            }
            return content + ";";
        }
    }

    public static class TransformationResult {
        public final List<JSONObject> statements = new ArrayList<>();
        public final Map<String, Set<String>> callGraph = new HashMap<>();
        public final Set<String> callList = new LinkedHashSet<>();
        public final Set<String> performList = new LinkedHashSet<>();
        public final Set<String> gotoList = new LinkedHashSet<>();
        public int cyclomaticComplexity = 1;

        public TransformationResult() {
        }
    }

    private static final Set<String> controlKeywords = new HashSet<>(Arrays.asList(
            "IF", "ELSE", "END-IF", "EVALUATE", "WHEN", "END-EVALUATE", "PERFORM", "END-PERFORM", "CALL", "GOBACK",
            "MOVE", "DISPLAY", "ACCEPT", "ADD", "SUBTRACT", "GO TO", "STOP RUN", "COMPUTE", "OPEN",
            "CLOSE", "READ", "WRITE", "INSPECT"
    ));

    public List<ParsingError> getErrors() {
        return errors;
    }

    public Set<String> getCopybooksIncluded() {
        return copybooksIncluded;
    }

    public Map<String, JSONObject> getFileDescriptions() {
        return fileDescriptions;
    }

    public TransformationResult transformParagraph(JSONArray lines, ContextMetadata context) {
        TransformationResult result = new TransformationResult();
        Deque<StructuredStatement> controlStack = new ArrayDeque<>();
        StructuredStatement currentControlStmt = null;
        System.out.println("Starting transformation for paragraph " + context.paragraph);

        for (Object obj : lines) {
            if (!(obj instanceof JSONObject)) continue;
            JSONObject lineObj = (JSONObject) obj;

            String text = ((String) lineObj.get("text")).trim().toUpperCase();
            int line = ((Number) lineObj.get("line")).intValue();

            if (text.isEmpty()) continue;

            String keyword = getLeadingKeyword(text);
            if (keyword == null) {
                StructuredStatement stmt = new StructuredStatement("OTHER", line, text);
                addToCurrentBlock(stmt, controlStack, result, inWhenClause, currentControlStmt);
                continue;
            }

            StructuredStatement stmt;
            if (keyword.equals("END-IF") || keyword.equals("END-EVALUATE") || keyword.equals("END-PERFORM")) {
                if (!controlStack.isEmpty()) {
                    currentControlStmt = controlStack.peek();
                    if ((keyword.equals("END-IF") && currentControlStmt.type.equals("IF")) ||
                            (keyword.equals("END-EVALUATE") && currentControlStmt.type.equals("EVALUATE")) ||
                            (keyword.equals("END-PERFORM") && currentControlStmt.type.equals("PERFORM"))) {
                        StructuredStatement controlStmt = controlStack.pop();
                        result.statements.add(controlStmt.toJson());
                        System.out.println("Popped " + controlStmt.type + " at line " + line + " for " + keyword + ": " + controlStmt.content);
                    } else {
                        errors.add(new ParsingError(context.programId, "Mismatched " + keyword + " for " + currentControlStmt.type + " in paragraph " + context.paragraph + ": " + text, line));
                    }
                } else {
                    errors.add(new ParsingError(context.programId, "Unmatched " + keyword + " in paragraph " + context.paragraph + ": " + text, line));
                }
                inWhenClause = false;
                visitingThen = false;
                visitingElse = false;
                currentControlStmt = controlStack.isEmpty() ? null : controlStack.peek();
                continue;
            }

            if (keyword.equals("IF") || text.startsWith("ELSE IF")) {
                result.cyclomaticComplexity++;
                String stmtText = text.startsWith("ELSE IF") ? text.replace("ELSE IF", "IF") : text;
                stmt = new StructuredStatement("IF", line, stmtText);
                System.out.println("Pushing IF at line " + line + ": " + text);
                if (text.startsWith("ELSE IF")) {
                    if (currentControlStmt != null && currentControlStmt.type.equals("IF")) {
                        currentControlStmt.elseBlock.add(stmt.toJson());
                    } else {
                        errors.add(new ParsingError(context.programId, "Unmatched ELSE IF in paragraph " + context.paragraph + ": " + text, line));
                        continue;
                    }
                } else {
                    addToCurrentBlock(stmt, controlStack, result, inWhenClause, currentControlStmt);
                }
                controlStack.push(stmt);
                currentControlStmt = stmt;
                visitingThen = true;
                visitingElse = false;
                inWhenClause = false;
            } else if (keyword.equals("ELSE") && !text.startsWith("ELSE IF")) {
                if (currentControlStmt != null && currentControlStmt.type.equals("IF")) {
                    stmt = new StructuredStatement(keyword, line, text);
                    currentControlStmt.elseBlock.add(stmt.toJson());
                    visitingThen = false;
                    visitingElse = true;
                    inWhenClause = false;
                } else {
                    errors.add(new ParsingError(context.programId, "Unmatched ELSE in paragraph " + context.paragraph + ": " + text, line));
                }
                continue;
            } else if (keyword.equals("EVALUATE")) {
                result.cyclomaticComplexity++;
                stmt = new StructuredStatement(keyword, line, text);
                System.out.println("Pushing EVALUATE at line " + line + ": " + text);
                if (currentControlStmt != null && currentControlStmt.type.equals("PERFORM")) {
                    currentControlStmt.thenBlock.add(stmt.toJson());
                } else {
                    addToCurrentBlock(stmt, controlStack, result, inWhenClause, currentControlStmt);
                }
                controlStack.push(stmt);
                currentControlStmt = stmt;
                inWhenClause = true;
                visitingThen = false;
                visitingElse = false;
            } else if (keyword.equals("WHEN") && currentControlStmt != null && currentControlStmt.type.equals("EVALUATE")) {
                result.cyclomaticComplexity++;
                stmt = new StructuredStatement(keyword, line, text);
                currentControlStmt.cases.add(stmt.toJson());
                inWhenClause = true;
            } else if (keyword.equals("PERFORM")) {
                result.cyclomaticComplexity++;
                JSONObject metadata = new JSONObject();
                String[] parts = text.split("\\s+", 6);
                if (parts.length >= 2) {
                    if (parts.length >= 5 && parts[1].equals("VARYING")) {
                        metadata.put("varying", parts[2] + " FROM " + parts[3] + " BY " + parts[4] + (parts.length > 5 ? " UNTIL " + parts[5] : ""));
                    } else if (parts.length >= 3 && parts[2].equals("THRU")) {
                        metadata.put("target", parts[1]);
                        metadata.put("thru", parts[3]);
                        result.performList.add(parts[1]);
                        result.performList.add(parts[3]);
                        result.callGraph.computeIfAbsent(context.paragraph, k -> new LinkedHashSet<>()).add(parts[1]);
                        result.callGraph.computeIfAbsent(context.paragraph, k -> new LinkedHashSet<>()).add(parts[3]);
                    } else {
                        metadata.put("target", parts[1]);
                        result.performList.add(parts[1]);
                        result.callGraph.computeIfAbsent(context.paragraph, k -> new LinkedHashSet<>()).add(parts[1]);
                    }
                }
                stmt = new StructuredStatement(keyword, line, text, new JSONArray(), null, null, metadata);
                System.out.println("Pushing PERFORM at line " + line + ": " + text);
                addToCurrentBlock(stmt, controlStack, result, inWhenClause, currentControlStmt);
                controlStack.push(stmt);
                currentControlStmt = stmt;
                visitingThen = true;
                visitingElse = false;
                inWhenClause = false;
            } else if (keyword.equals("CALL")) {
                JSONObject metadata = new JSONObject();
                String[] parts = text.split("\\s+", 5);
                if (parts.length >= 2) {
                    String program = parts[1].replaceAll("[\"']", "").replace(".", "").toUpperCase();
                    metadata.put("program", program);
                    if (parts.length >= 3 && parts[2].equals("USING")) {
                        JSONArray params = new JSONArray();
                        for (int i = 3; i < parts.length; i++) {
                            params.add(parts[i].replaceAll("[\"']", ""));
                        }
                        metadata.put("parameters", params);
                    }
                    result.callList.add(program);
                    result.callGraph.computeIfAbsent(context.paragraph, k -> new LinkedHashSet<>()).add(program);
                } else {
                    errors.add(new ParsingError(context.programId, "Invalid CALL statement in paragraph " + context.paragraph + ": " + text, line));
                }
                stmt = new StructuredStatement(keyword, line, text, null, null, null, metadata);
                addToCurrentBlock(stmt, controlStack, result, inWhenClause, currentControlStmt);
            } else if (keyword.equals("GO TO")) {
                JSONObject metadata = new JSONObject();
                String[] parts = text.split("\\s+", 3);
                if (parts.length >= 2) {
                    String target = parts[1].replace(".", "").toUpperCase();
                    metadata.put("target", target);
                    result.gotoList.add(target);
                    result.callGraph.computeIfAbsent(context.paragraph, k -> new LinkedHashSet<>()).add(target);
                }
                stmt = new StructuredStatement(keyword, line, text, null, null, null, metadata);
                addToCurrentBlock(stmt, controlStack, result, inWhenClause, currentControlStmt);
            } else if (keyword.equals("OPEN") || keyword.equals("READ") || keyword.equals("WRITE") || keyword.equals("CLOSE")) {
                JSONObject metadata = new JSONObject();
                String[] parts = text.split("\\s+", 3);
                if (parts.length >= 2) {
                    String fileName = parts[1].replace(".", "").toUpperCase();
                    metadata.put("file", fileName);
                    if (fileDescriptions.containsKey(fileName)) {
                        metadata.put("fd", fileDescriptions.get(fileName));
                    }
                }
                stmt = new StructuredStatement(keyword, line, text, null, null, null, metadata);
                addToCurrentBlock(stmt, controlStack, result, inWhenClause, currentControlStmt);
            } else {
                stmt = new StructuredStatement(keyword, line, text);
                addToCurrentBlock(stmt, controlStack, result, inWhenClause, currentControlStmt);
            }
        }

        if (!controlStack.isEmpty()) {
            System.out.println("Unclosed structures in paragraph " + context.paragraph + ": " + controlStack.size());
            while (!controlStack.isEmpty()) {
                StructuredStatement controlStmt = controlStack.pop();
                errors.add(new ParsingError(context.programId, "Unclosed " + controlStmt.type + " in paragraph " + context.paragraph + ": " + controlStmt.content, controlStmt.line));
                result.statements.add(controlStmt.toJson());
            }
        } else {
            System.out.println("All control structures closed in paragraph " + context.paragraph);
        }

        return result;
    }

    private void addToCurrentBlock(StructuredStatement stmt, Deque<StructuredStatement> controlStack, TransformationResult result, boolean inWhenClause, StructuredStatement currentControlStmt) {
        if (currentControlStmt != null) {
            JSONArray targetBlock = inWhenClause ? currentControlStmt.cases :
                    (visitingThen || currentControlStmt.thenBlock.isEmpty()) ? currentControlStmt.thenBlock : currentControlStmt.elseBlock;
            if (targetBlock != null) {
                targetBlock.add(stmt.toJson());
                return;
            }
        }
        result.statements.add(stmt.toJson());
    }

    private String getLeadingKeyword(String text) {
        for (String keyword : controlKeywords) {
            if (text.startsWith(keyword + " ") || text.equals(keyword)) {
                return keyword;
            }
        }
        return null;
    }

    public void processCopybooks(CobolPreprocessor preprocessor) {
        copybooksIncluded.addAll(preprocessor.getVisitedCopybooks());
    }

    @Override
    public Void visitFileDescriptionEntry(Cobol85Parser.FileDescriptionEntryContext ctx) {
        String fileName = ctx.fileName() != null ? ctx.fileName().getText().toUpperCase() : "UNKNOWN";
        JSONObject fdData = new JSONObject();
        fdData.put("name", fileName);

        // Handle LABEL RECORD clause (generic approach)
        boolean foundLabel = false;
        for (ParseTree child : ctx.children) {
            if (child.getText().toUpperCase().startsWith("LABEL RECORD")) {
                String labelText = child.getText().toUpperCase();
                fdData.put("label", labelText);
                if (!labelText.contains("STANDARD") && !labelText.contains("OMITTED")) {
                    errors.add(new ParsingError("UNKNOWN", "Invalid LABEL RECORD clause for file " + fileName + ": " + labelText, ctx.getStart().getLine()));
                }
                foundLabel = true;
                break;
            }
        }
        if (!foundLabel) {
            fdData.put("label", "OMITTED");
        }

        // Handle RECORD DESCRIPTION (generic approach)
        JSONArray records = new JSONArray();
        for (ParseTree child : ctx.children) {
            if (child instanceof Cobol85Parser.DataDescriptionEntryContext) {
                Cobol85Parser.DataDescriptionEntryContext dataCtx = (Cobol85Parser.DataDescriptionEntryContext) child;
                JSONObject recordData = new JSONObject();
                String recordName = "UNKNOWN";
                String level = "UNKNOWN";
                String picture = null;

                // Extract data name and level number
                for (ParseTree dataChild : dataCtx.children) {
                    String text = dataChild.getText();
                    if (text.matches("\\d+")) {
                        level = text;
                    } else if (dataChild instanceof Cobol85Parser.IdentifierContext) {
                        recordName = dataChild.getText().toUpperCase();
                    } else if (dataChild.getText().toUpperCase().startsWith("PIC") || dataChild.getText().toUpperCase().startsWith("PICTURE")) {
                        picture = dataChild.getText();
                    }
                }

                recordData.put("name", recordName);
                recordData.put("level", level);
                if (picture != null) {
                    recordData.put("picture", picture);
                }
                records.add(recordData);
            }
        }
        if (!records.isEmpty()) {
            fdData.put("records", records);
        }

        fileDescriptions.put(fileName, fdData);
        System.out.println("Processed FD for file " + fileName + ": " + fdData.toJSONString());
        return super.visitFileDescriptionEntry(ctx);
    }

    @Override
    public Void visitProcedureDivision(Cobol85Parser.ProcedureDivisionContext ctx) {
        currentParagraph = "_MAIN";
        return visitChildren(ctx);
    }

    @Override
    public Void visitParagraph(Cobol85Parser.ParagraphContext ctx) {
        if (ctx.paragraphName() != null) {
            currentParagraph = ctx.paragraphName().getText();
        }
        return visitChildren(ctx);
    }

    @Override
    public Void visitIfStatement(Cobol85Parser.IfStatementContext ctx) {
        JSONObject ifNode = new JSONObject();
        ifNode.put("type", "IF");
        ifNode.put("line", ctx.getStart().getLine());
        ifNode.put("condition", ctx.condition() != null ? ctx.condition().getText() : "UNKNOWN");
        ifNode.put("then", new JSONArray());
        ifNode.put("else", new JSONArray());

        insertStructuredStatement(ifNode);
        statementStack.push(ifNode);

        visitingThen = true;
        if (ctx.ifThen() != null) visitChildren(ctx.ifThen());
        visitingThen = false;

        visitingElse = true;
        if (ctx.ifElse() != null) visitChildren(ctx.ifElse());
        visitingElse = false;

        statementStack.pop();
        return null;
    }

    @Override
    public Void visitComputeStatement(Cobol85Parser.ComputeStatementContext ctx) {
        JSONObject node = new JSONObject();
        node.put("type", "COMPUTE");
        node.put("line", ctx.getStart().getLine());
        node.put("text", ctx.getText());
        insertStructuredStatement(node);
        return super.visitComputeStatement(ctx);
    }

    @Override
    public Void visitOpenStatement(Cobol85Parser.OpenStatementContext ctx) {
        JSONObject node = new JSONObject();
        node.put("type", "OPEN");
        node.put("line", ctx.getStart().getLine());
        node.put("text", ctx.getText());
        JSONObject metadata = new JSONObject();
        String[] parts = ctx.getText().toUpperCase().split("\\s+", 3);
        if (parts.length >= 2) {
            String fileName = parts[1].replace(".", "");
            metadata.put("file", fileName);
            if (fileDescriptions.containsKey(fileName)) {
                metadata.put("fd", fileDescriptions.get(fileName));
            }
        }
        node.put("metadata", metadata);
        insertStructuredStatement(node);
        return super.visitOpenStatement(ctx);
    }

    @Override
    public Void visitCloseStatement(Cobol85Parser.CloseStatementContext ctx) {
        JSONObject node = new JSONObject();
        node.put("type", "CLOSE");
        node.put("line", ctx.getStart().getLine());
        node.put("text", ctx.getText());
        JSONObject metadata = new JSONObject();
        String[] parts = ctx.getText().toUpperCase().split("\\s+", 3);
        if (parts.length >= 2) {
            String fileName = parts[1].replace(".", "");
            metadata.put("file", fileName);
            if (fileDescriptions.containsKey(fileName)) {
                metadata.put("fd", fileDescriptions.get(fileName));
            }
        }
        node.put("metadata", metadata);
        insertStructuredStatement(node);
        return super.visitCloseStatement(ctx);
    }

    @Override
    public Void visitReadStatement(Cobol85Parser.ReadStatementContext ctx) {
        JSONObject node = new JSONObject();
        node.put("type", "READ");
        node.put("line", ctx.getStart().getLine());
        node.put("text", ctx.getText());
        JSONObject metadata = new JSONObject();
        if (ctx.fileName() != null) {
            String fileName = ctx.fileName().getText().toUpperCase();
            metadata.put("file", fileName);
            if (fileDescriptions.containsKey(fileName)) {
                metadata.put("fd", fileDescriptions.get(fileName));
            }
        }
        node.put("metadata", metadata);
        insertStructuredStatement(node);
        return super.visitReadStatement(ctx);
    }

    @Override
    public Void visitWriteStatement(Cobol85Parser.WriteStatementContext ctx) {
        JSONObject node = new JSONObject();
        node.put("type", "WRITE");
        node.put("line", ctx.getStart().getLine());
        node.put("text", ctx.getText());
        JSONObject metadata = new JSONObject();
        if (ctx.recordName() != null) {
            String recordName = ctx.recordName().getText().toUpperCase();
            metadata.put("record", recordName);
            for (JSONObject fd : fileDescriptions.values()) {
                JSONArray records = (JSONArray) fd.get("records");
                if (records != null && records.stream().anyMatch(r -> ((JSONObject) r).get("name").equals(recordName))) {
                    metadata.put("fd", fd);
                    break;
                }
            }
        }
        node.put("metadata", metadata);
        insertStructuredStatement(node);
        return super.visitWriteStatement(ctx);
    }

    @Override
    public Void visitInspectStatement(Cobol85Parser.InspectStatementContext ctx) {
        JSONObject node = new JSONObject();
        node.put("type", "INSPECT");
        node.put("line", ctx.getStart().getLine());
        node.put("text", ctx.getText());
        insertStructuredStatement(node);
        return super.visitInspectStatement(ctx);
    }

    @Override
    public Void visitPerformStatement(Cobol85Parser.PerformStatementContext ctx) {
        JSONObject performNode = new JSONObject();
        performNode.put("type", "PERFORM");
        performNode.put("line", ctx.getStart().getLine());
        JSONObject metadata = new JSONObject();

        String text = ctx.getText().toUpperCase();
        String[] parts = text.split("\\s+", 6);
        if (parts.length >= 2) {
            if (parts[1].equals("VARYING")) {
                metadata.put("varying", parts[2] + " FROM " + parts[3] + " BY " + parts[4] + (parts.length > 5 ? " UNTIL " + parts[5] : ""));
            } else if (parts.length >= 3 && parts[2].equals("THRU")) {
                metadata.put("target", parts[1]);
                metadata.put("thru", parts[3]);
            } else {
                metadata.put("target", parts[1]);
            }
        }

        performNode.put("metadata", metadata);
        insertStructuredStatement(performNode);
        return super.visitPerformStatement(ctx);
    }

    @Override
    public Void visitCallStatement(Cobol85Parser.CallStatementContext ctx) {
        JSONObject callNode = new JSONObject();
        callNode.put("type", "CALL");
        callNode.put("line", ctx.getStart().getLine());
        JSONObject metadata = new JSONObject();

        String programName = "UNKNOWN";
        if (ctx.literal() != null) {
            programName = ctx.literal().getText().replaceAll("[\"']", "").toUpperCase();
        } else if (ctx.identifier() != null) {
            programName = ctx.identifier().getText().toUpperCase();
        }
        metadata.put("program", programName);

        JSONArray using = new JSONArray();
        if (ctx.callUsingPhrase() != null) {
            for (ParseTree child : ctx.callUsingPhrase().children) {
                using.add(child.getText());
            }
        }
        if (!using.isEmpty()) {
            metadata.put("parameters", using);
        }

        callNode.put("metadata", metadata);
        insertStructuredStatement(callNode);
        return super.visitCallStatement(ctx);
    }

    @Override
    public Void visitDisplayStatement(Cobol85Parser.DisplayStatementContext ctx) {
        JSONObject displayNode = new JSONObject();
        displayNode.put("type", "DISPLAY");
        displayNode.put("line", ctx.getStart().getLine());

        StringBuilder message = new StringBuilder();
        for (ParseTree child : ctx.children) {
            message.append(child.getText()).append(" ");
        }

        displayNode.put("message", message.toString().trim());
        insertStructuredStatement(displayNode);
        return super.visitDisplayStatement(ctx);
    }

    @Override
    public Void visitExitStatement(Cobol85Parser.ExitStatementContext ctx) {
        JSONObject exitNode = new JSONObject();
        exitNode.put("type", "EXIT");
        exitNode.put("line", ctx.getStart().getLine());
        insertStructuredStatement(exitNode);
        return super.visitExitStatement(ctx);
    }

    @Override
    public Void visitStopStatement(Cobol85Parser.StopStatementContext ctx) {
        JSONObject stopNode = new JSONObject();
        stopNode.put("type", "STOP RUN");
        stopNode.put("line", ctx.getStart().getLine());
        insertStructuredStatement(stopNode);
        return super.visitStopStatement(ctx);
    }

    @Override
    public Void visitGoToStatement(Cobol85Parser.GoToStatementContext ctx) {
        JSONObject gotoNode = new JSONObject();
        gotoNode.put("type", "GO TO");
        gotoNode.put("line", ctx.getStart().getLine());
        JSONObject metadata = new JSONObject();

        if (ctx.goToStatementSimple() != null && ctx.goToStatementSimple().procedureName() != null) {
            String target = ctx.goToStatementSimple().procedureName().getText().toUpperCase();
            metadata.put("target", target);
        }

        gotoNode.put("metadata", metadata);
        insertStructuredStatement(gotoNode);
        return super.visitGoToStatement(ctx);
    }

    @Override
    public Void visitEvaluateStatement(Cobol85Parser.EvaluateStatementContext ctx) {
        JSONObject evalNode = new JSONObject();
        evalNode.put("type", "EVALUATE");
        evalNode.put("line", ctx.getStart().getLine());
        evalNode.put("expression", ctx.getText());
        evalNode.put("cases", new JSONArray());

        insertStructuredStatement(evalNode);
        return super.visitEvaluateStatement(ctx);
    }

    @Override
    public Void visitSectionHeader(Cobol85Parser.ProcedureSectionHeaderContext ctx) {
        return null;
    }

    private void insertStructuredStatement(JSONObject stmt) {
        if (!statementStack.isEmpty()) {
            JSONObject parent = statementStack.peek();
            JSONArray block = visitingThen ? (JSONArray) parent.get("then") :
                    visitingElse ? (JSONArray) parent.get("else") :
                            parent.get("cases") != null ? (JSONArray) parent.get("cases") : null;
            if (block != null) {
                block.add(stmt);
                return;
            }
        }
        structuredStatements.add(stmt);
    }

    public JSONArray getStructuredStatements() {
        return structuredStatements;
    }
}