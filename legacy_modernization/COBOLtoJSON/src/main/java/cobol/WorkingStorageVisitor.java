package cobol;

import cobol.antlr.Cobol85BaseVisitor;
import cobol.antlr.Cobol85Parser;
import org.antlr.v4.runtime.tree.ParseTree;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import java.util.*;

public class WorkingStorageVisitor extends Cobol85BaseVisitor<Void> {

    private final Set<String> declaredVariables = new LinkedHashSet<String>();
    private final Map<String, String> workingStorageMap = new LinkedHashMap<String,String>();

    @Override
    public Void visitDataDescriptionEntryFormat1(Cobol85Parser.DataDescriptionEntryFormat1Context ctx) {
        String varName = null;
        StringBuilder definition = new StringBuilder();

        for (ParseTree child : ctx.children) {
            String token = child.getText();
            if (varName == null && token.matches("[A-Za-z][A-Za-z0-9-]*")) {
                varName = token.toUpperCase();
            } else if ("PIC".equalsIgnoreCase(token) || "PICTURE".equalsIgnoreCase(token)) {
                definition.append("PIC ");
            } else if ("VALUE".equalsIgnoreCase(token)) {
                definition.append("VALUE ");
            } else if (definition.length() > 0 || token.matches("[A-Za-z0-9()-]+")) {
                definition.append(token).append(" ");
            }
        }

        if (varName != null) {
            declaredVariables.add(varName);
            workingStorageMap.put(varName, definition.toString().trim().replaceAll("\\s+", " "));
        }
        return super.visitDataDescriptionEntryFormat1(ctx);
    }

    @Override
    public Void visitSectionHeader(Cobol85Parser.ProcedureSectionHeaderContext ctx) {
        return null;
    }

    public Set<String> getDeclaredVariables() {
        return declaredVariables;
    }

    public Map<String, String> getWorkingStorageMap() {
        return workingStorageMap;
    }

    public JSONObject getWorkingStorageJson() {
        JSONObject root = new JSONObject();
        JSONArray vars = new JSONArray();
        JSONObject dict = new JSONObject();

        for (String var : declaredVariables) {
            vars.add(var);
            dict.put(var, workingStorageMap.getOrDefault(var, ""));
        }

        root.put("variables", vars);
        root.put("dictionary", dict);
        return root;
    }
}
