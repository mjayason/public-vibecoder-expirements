package cobol;

import cobol.antlr.Cobol85BaseVisitor;
import cobol.antlr.Cobol85Parser;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import java.util.LinkedHashSet;
import java.util.Set;

public class DivisionSectionVisitor extends Cobol85BaseVisitor<Void> {
    private final Set<String> divisions = new LinkedHashSet<>();
    private final Set<String> sections = new LinkedHashSet<>();

    @Override
    public Void visitEnvironmentDivision(Cobol85Parser.EnvironmentDivisionContext ctx) {
        divisions.add("ENVIRONMENT");
        return visitChildren(ctx);
    }

    @Override
    public Void visitConfigurationSection(Cobol85Parser.ConfigurationSectionContext ctx) {
        sections.add("CONFIGURATION");
        return visitChildren(ctx);
    }

    @Override
    public Void visitIdentificationDivision(Cobol85Parser.IdentificationDivisionContext ctx) {
        divisions.add("IDENTIFICATION");
        return visitChildren(ctx);
    }

    @Override
    public Void visitProcedureDivision(Cobol85Parser.ProcedureDivisionContext ctx) {
        divisions.add("PROCEDURE");
        return visitChildren(ctx);
    }

    @Override
    public Void visitDataDivision(Cobol85Parser.DataDivisionContext ctx) {
        divisions.add("DATA");
        return visitChildren(ctx);
    }

    @Override
    public Void visitSectionHeader(Cobol85Parser.ProcedureSectionHeaderContext ctx) {
        if (ctx != null && ctx.sectionName() != null) {
            String sectionName = ctx.sectionName().getText();
            sections.add(sectionName.toUpperCase());
        }
        return visitChildren(ctx);
    }

    public JSONObject getDivisionStructure() {
        JSONObject divJson = new JSONObject();
        divJson.put("divisions", new JSONArray() {{ addAll(divisions); }});
        divJson.put("sections", new JSONArray() {{ addAll(sections); }});
        return divJson;
    }
}