package cobol;

import cobol.antlr.Cobol85BaseVisitor;
import cobol.antlr.Cobol85Parser;
import org.antlr.v4.runtime.tree.ParseTree;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import java.util.*;

/**
 * Tracks variable reads, writes, and movements across COBOL paragraphs.
 * Supports MOVE, ADD, COMPUTE, READ, WRITE, INSPECT, and other statements.
 */
public class VariableTrackerVisitor extends Cobol85BaseVisitor<Void> {

    public static class MovementRecord {
        private final String operation;
        private final List<String> source;
        private final String target;
        private final int line;

        public MovementRecord(String operation, List<String> source, String target, int line) {
            this.operation = operation;
            this.source = source;
            this.target = target;
            this.line = line;
        }

        public String getOperation() { return operation; }
        public List<String> getSource() { return source; }
        public String getTarget() { return target; }
        public int getLine() { return line; }
    }

    private final Map<String, Set<String>> paragraphReads = new LinkedHashMap<>();
    private final Map<String, Set<String>> paragraphWrites = new LinkedHashMap<>();
    private final Set<String> declaredVariables;
    private final List<MovementRecord> movements = new ArrayList<>();
    private String currentParagraph = "_MAIN";

    /**
     * Constructs a visitor with declared variables.
     * @param declaredVariables Set of declared variable names
     */
    public VariableTrackerVisitor(Set<String> declaredVariables) {
        this.declaredVariables = declaredVariables;
    }

    @Override
    public Void visitProcedureDivision(Cobol85Parser.ProcedureDivisionContext ctx) {
        currentParagraph = "_MAIN";
        ensureParagraphMaps(currentParagraph);
        return visitChildren(ctx);
    }

    @Override
    public Void visitParagraph(Cobol85Parser.ParagraphContext ctx) {
        if (ctx.paragraphName() != null) {
            currentParagraph = ctx.paragraphName().getText();
            ensureParagraphMaps(currentParagraph);
        }
        return visitChildren(ctx);
    }

    @Override
    public Void visitMoveStatement(Cobol85Parser.MoveStatementContext ctx) {
        String source = null;
        String target = null;
        for (int i = 0; i < ctx.children.size(); i++) {
            String token = ctx.children.get(i).getText().toUpperCase();
            if ("TO".equals(token) && i > 0 && i + 1 < ctx.children.size()) {
                source = ctx.children.get(i - 1).getText().toUpperCase();
                target = ctx.children.get(i + 1).getText().toUpperCase();
                break;
            }
        }
        if (source != null && target != null) {
            if (isDeclared(source)) paragraphReads.get(currentParagraph).add(source);
            if (isDeclared(target)) paragraphWrites.get(currentParagraph).add(target);
            movements.add(new MovementRecord("MOVE", Collections.singletonList(source), target, ctx.getStart().getLine()));
        }
        return super.visitMoveStatement(ctx);
    }

    @Override
    public Void visitAddStatement(Cobol85Parser.AddStatementContext ctx) {
        List<String> sources = new ArrayList<>();
        List<String> targets = new ArrayList<>();

        for (ParseTree child : ctx.children) {
            String text = child.getText().toUpperCase();
            if (isDeclared(text)) sources.add(text);
        }

        if (ctx.addToStatement() != null) {
            for (ParseTree id : ctx.addToStatement().children) {
                String target = id.getText().toUpperCase();
                if (isDeclared(target)) {
                    paragraphWrites.get(currentParagraph).add(target);
                    targets.add(target);
                }
            }
        }

        for (String src : sources) paragraphReads.get(currentParagraph).add(src);
        for (String tgt : targets) movements.add(new MovementRecord("ADD", sources, tgt, ctx.getStart().getLine()));
        return super.visitAddStatement(ctx);
    }

    @Override
    public Void visitAcceptStatement(Cobol85Parser.AcceptStatementContext ctx) {
        if (ctx.getChildCount() >= 2) {
            String target = ctx.getChild(1).getText().toUpperCase();
            if (isDeclared(target)) {
                paragraphWrites.get(currentParagraph).add(target);
                movements.add(new MovementRecord("ACCEPT", new ArrayList<>(), target, ctx.getStart().getLine()));
            }
        }
        return super.visitAcceptStatement(ctx);
    }

    @Override
    public Void visitSubtractStatement(Cobol85Parser.SubtractStatementContext ctx) {
        List<String> sources = new ArrayList<>();
        List<String> targets = new ArrayList<>();

        for (ParseTree child : ctx.children) {
            String text = child.getText().toUpperCase();
            if (isDeclared(text)) sources.add(text);
        }

        if (ctx.subtractFromStatement() != null) {
            for (ParseTree id : ctx.subtractFromStatement().children) {
                String target = id.getText().toUpperCase();
                if (isDeclared(target)) {
                    paragraphWrites.get(currentParagraph).add(target);
                    targets.add(target);
                }
            }
        }

        for (String src : sources) paragraphReads.get(currentParagraph).add(src);
        for (String tgt : targets) movements.add(new MovementRecord("SUBTRACT", sources, tgt, ctx.getStart().getLine()));
        return super.visitSubtractStatement(ctx);
    }

    @Override
    public Void visitComputeStatement(Cobol85Parser.ComputeStatementContext ctx) {
        String target = null;
        for (ParseTree child : ctx.children) {
            String text = child.getText().toUpperCase();
            if ("=".equals(text)) break;
            if (isDeclared(text)) {
                target = text;
                paragraphWrites.get(currentParagraph).add(target);
            }
        }

        boolean isExpr = false;
        Set<String> sources = new HashSet<>();
        for (ParseTree child : ctx.children) {
            String text = child.getText().toUpperCase();
            if ("=".equals(text)) {
                isExpr = true;
                continue;
            }
            if (isExpr && isDeclared(text)) {
                sources.add(text);
                paragraphReads.get(currentParagraph).add(text);
            }
        }

        if (target != null) {
            movements.add(new MovementRecord("COMPUTE", new ArrayList<>(sources), target, ctx.getStart().getLine()));
        }
        return super.visitComputeStatement(ctx);
    }

    @Override
    public Void visitMultiplyStatement(Cobol85Parser.MultiplyStatementContext ctx) {
        List<String> sources = new ArrayList<>();
        List<String> targets = new ArrayList<>();

        for (ParseTree child : ctx.children) {
            String text = child.getText().toUpperCase();
            if (isDeclared(text)) sources.add(text);
        }

        if (ctx.multiplyRegular() != null && ctx.multiplyRegular().multiplyRegularOperand() != null) {
            for (ParseTree id : ctx.multiplyRegular().children) {
                String target = id.getText().toUpperCase();
                if (isDeclared(target)) {
                    paragraphWrites.get(currentParagraph).add(target);
                    targets.add(target);
                }
            }
        }

        if (ctx.multiplyGiving() != null) {
            for (ParseTree id : ctx.multiplyGiving().children) {
                String target = id.getText().toUpperCase();
                if (isDeclared(target)) {
                    paragraphWrites.get(currentParagraph).add(target);
                    targets.add(target);
                }
            }
        }

        for (String src : sources) paragraphReads.get(currentParagraph).add(src);
        for (String tgt : targets) movements.add(new MovementRecord("MULTIPLY", sources, tgt, ctx.getStart().getLine()));
        return super.visitMultiplyStatement(ctx);
    }

    @Override
    public Void visitDivideStatement(Cobol85Parser.DivideStatementContext ctx) {
        List<String> sources = new ArrayList<>();
        List<String> targets = new ArrayList<>();

        for (ParseTree child : ctx.children) {
            String text = child.getText().toUpperCase();
            if (isDeclared(text)) sources.add(text);
        }

        if (ctx.divideIntoStatement() != null) {
            for (ParseTree id : ctx.divideIntoStatement().children) {
                String target = id.getText().toUpperCase();
                if (isDeclared(target)) {
                    paragraphWrites.get(currentParagraph).add(target);
                    targets.add(target);
                }
            }
        }

        for (String src : sources) paragraphReads.get(currentParagraph).add(src);
        for (String tgt : targets) movements.add(new MovementRecord("DIVIDE", sources, tgt, ctx.getStart().getLine()));
        return super.visitDivideStatement(ctx);
    }

    @Override
    public Void visitInitializeStatement(Cobol85Parser.InitializeStatementContext ctx) {
        for (ParseTree child : ctx.children) {
            String var = child.getText().toUpperCase();
            if (isDeclared(var)) {
                paragraphWrites.get(currentParagraph).add(var);
                movements.add(new MovementRecord("INITIALIZE", Collections.emptyList(), var, ctx.getStart().getLine()));
            }
        }
        return super.visitInitializeStatement(ctx);
    }

    @Override
    public Void visitStringStatement(Cobol85Parser.StringStatementContext ctx) {
        List<String> sources = new ArrayList<>();
        String target = null;

        for (ParseTree child : ctx.children) {
            String text = child.getText().toUpperCase();
            if ("INTO".equalsIgnoreCase(text)) break;
            if (isDeclared(text)) {
                sources.add(text);
                paragraphReads.get(currentParagraph).add(text);
            }
        }

        boolean intoFound = false;
        for (ParseTree child : ctx.children) {
            if ("INTO".equalsIgnoreCase(child.getText())) {
                intoFound = true;
                continue;
            }
            if (intoFound && isDeclared(child.getText().toUpperCase())) {
                target = child.getText().toUpperCase();
                paragraphWrites.get(currentParagraph).add(target);
                break;
            }
        }

        if (target != null) {
            movements.add(new MovementRecord("STRING", sources, target, ctx.getStart().getLine()));
        }
        return super.visitStringStatement(ctx);
    }

    @Override
    public Void visitUnstringStatement(Cobol85Parser.UnstringStatementContext ctx) {
        String source = null;
        List<String> targets = new ArrayList<>();

        for (ParseTree child : ctx.children) {
            if ("INTO".equalsIgnoreCase(child.getText())) break;
            String text = child.getText().toUpperCase();
            if (isDeclared(text)) {
                source = text;
                paragraphReads.get(currentParagraph).add(text);
            }
        }

        boolean intoMode = false;
        for (ParseTree child : ctx.children) {
            if ("INTO".equalsIgnoreCase(child.getText())) {
                intoMode = true;
                continue;
            }
            if (intoMode && isDeclared(child.getText().toUpperCase())) {
                String target = child.getText().toUpperCase();
                targets.add(target);
                paragraphWrites.get(currentParagraph).add(target);
            }
        }

        for (String tgt : targets) {
            movements.add(new MovementRecord("UNSTRING", Collections.singletonList(source), tgt, ctx.getStart().getLine()));
        }
        return super.visitUnstringStatement(ctx);
    }

    @Override
    public Void visitReadStatement(Cobol85Parser.ReadStatementContext ctx) {
        if (ctx.fileName() != null) {
            String fileVar = ctx.fileName().getText().toUpperCase();
            if (isDeclared(fileVar)) {
                paragraphReads.get(currentParagraph).add(fileVar);
                movements.add(new MovementRecord("READ", Collections.emptyList(), fileVar, ctx.getStart().getLine()));
            }
        }
        return super.visitReadStatement(ctx);
    }

    @Override
    public Void visitWriteStatement(Cobol85Parser.WriteStatementContext ctx) {
        if (ctx.recordName() != null) {
            String recordVar = ctx.recordName().getText().toUpperCase();
            if (isDeclared(recordVar)) {
                paragraphWrites.get(currentParagraph).add(recordVar);
                movements.add(new MovementRecord("WRITE", Collections.singletonList(recordVar), null, ctx.getStart().getLine()));
            }
        }
        return super.visitWriteStatement(ctx);
    }

    @Override
    public Void visitInspectStatement(Cobol85Parser.InspectStatementContext ctx) {
        List<String> sources = new ArrayList<>();
        for (ParseTree child : ctx.children) {
            String text = child.getText().toUpperCase();
            if (isDeclared(text)) {
                sources.add(text);
                paragraphReads.get(currentParagraph).add(text);
            }
        }
        if (!sources.isEmpty()) {
            movements.add(new MovementRecord("INSPECT", sources, null, ctx.getStart().getLine()));
        }
        return super.visitInspectStatement(ctx);
    }

    @Override
    public Void visitCallStatement(Cobol85Parser.CallStatementContext ctx) {
        List<String> usingVars = extractDeclaredIdentifiers(ctx.getText());
        for (String var : usingVars) {
            if (isDeclared(var)) {
                paragraphReads.get(currentParagraph).add(var);
                String targetProgram = ctx.literal() != null ? ctx.literal().getText().replaceAll("[\"']", "").toUpperCase() :
                        (ctx.identifier() != null ? ctx.identifier().getText().toUpperCase() : "PROGRAM");
                movements.add(new MovementRecord("CALL", Collections.singletonList(var), targetProgram, ctx.getStart().getLine()));
            }
        }
        return super.visitCallStatement(ctx);
    }

    @Override
    public Void visitSectionHeader(Cobol85Parser.ProcedureSectionHeaderContext ctx) {
        return null;
    }

    /**
     * Generates a Mermaid diagram for data flow based on variable movements.
     * @return String containing Mermaid syntax
     */
    public String getDataFlowMermaid() {
        StringBuilder sb = new StringBuilder("graph TD\n");
        for (MovementRecord m : movements) {
            String target = m.getTarget() != null ? m.getTarget() : "EXTERNAL";
            for (String src : m.getSource()) {
                sb.append("  ").append(src).append(" -->|").append(m.getOperation()).append("| ").append(target).append("\n");
            }
            if (m.getSource().isEmpty()) {
                sb.append("  INPUT -->|").append(m.getOperation()).append("| ").append(target).append("\n");
            }
        }
        sb.append("\nclassDef external fill:#fdd,stroke:#d00;\n");
        return sb.toString();
    }

    private boolean isDeclared(String token) {
        return declaredVariables.contains(token.toUpperCase());
    }

    private void ensureParagraphMaps(String para) {
        paragraphReads.computeIfAbsent(para, k -> new LinkedHashSet<>());
        paragraphWrites.computeIfAbsent(para, k -> new LinkedHashSet<>());
    }

    private List<String> extractDeclaredIdentifiers(String expr) {
        List<String> vars = new ArrayList<>();
        for (String token : expr.split("[^A-Za-z0-9-]")) {
            if (isDeclared(token)) vars.add(token.toUpperCase());
        }
        return vars;
    }

    public Map<String, Set<String>> getReadMap() { return paragraphReads; }
    public Map<String, Set<String>> getWriteMap() { return paragraphWrites; }
    public List<MovementRecord> getMovements() { return movements; }

    public JSONArray getMovementJson() {
        JSONArray arr = new JSONArray();
        for (MovementRecord m : movements) {
            JSONObject obj = new JSONObject();
            obj.put("operation", m.getOperation());
            obj.put("source", new JSONArray() {{ addAll(m.getSource()); }});
            obj.put("target", m.getTarget());
            obj.put("line", m.getLine());
            arr.add(obj);
        }
        return arr;
    }

    public JSONObject getJsonUsage() {
        JSONObject root = new JSONObject();
        for (String para : paragraphReads.keySet()) {
            JSONObject usage = new JSONObject();
            JSONArray reads = new JSONArray();
            JSONArray writes = new JSONArray();
            reads.addAll(paragraphReads.get(para));
            writes.addAll(paragraphWrites.get(para));
            usage.put("reads", reads);
            usage.put("writes", writes);
            root.put(para, usage);
        }
        return root;
    }
}