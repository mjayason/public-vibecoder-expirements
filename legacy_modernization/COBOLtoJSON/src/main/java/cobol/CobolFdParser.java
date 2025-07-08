package cobol;

import cobol.antlr.Cobol85BaseVisitor;
import cobol.antlr.Cobol85Parser;
import org.antlr.v4.runtime.tree.ParseTree;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import java.util.*;

public class CobolFdParser extends Cobol85BaseVisitor<Void> {

    private final Map<String, JSONObject> fileDescriptions = new HashMap<>();
    private final Map<String, JSONObject> fileControlEntries = new HashMap<>();
    private final Map<String, JSONObject> workingStorageVariables = new HashMap<>();
    private final List<ParsingError> errors = new ArrayList<>();
    private final List<Integer> lineNumberMap = new ArrayList<>();

    public CobolFdParser() {
    }

    public Map<String, JSONObject> getFileDescriptions() {
        return fileDescriptions;
    }

    public Map<String, JSONObject> getFileControlEntries() {
        return fileControlEntries;
    }

    public List<ParsingError> getErrors() {
        return errors;
    }

    public void setWorkingStorageVariables(Map<String, JSONObject> variables) {
        this.workingStorageVariables.putAll(variables);
    }

    public void setLineNumberMap(List<Integer> lineNumberMap) {
        this.lineNumberMap.clear();
        this.lineNumberMap.addAll(lineNumberMap);
    }

    private int mapLineNumber(int preprocessedLine) {
        if (preprocessedLine > 0 && preprocessedLine <= lineNumberMap.size()) {
            return lineNumberMap.get(preprocessedLine - 1);
        }
        return preprocessedLine;
    }

    @Override
    public Void visitFileControlEntry(Cobol85Parser.FileControlEntryContext ctx) {
        int line = mapLineNumber(ctx.getStart().getLine());
        String text = ctx.getText().toUpperCase();
        JSONObject fileControl = new JSONObject();
        String fileName = "UNKNOWN";

        // Parse FILE-CONTROL entry manually
        String[] parts = text.split("\\s+");
        int i = 0;
        boolean inSelect = false;
        StringBuilder selectClause = new StringBuilder();
        String assignClause = null;
        String organizationClause = null;
        String accessMode = null;
        String recordKey = null;

        while (i < parts.length) {
            String part = parts[i].trim();
            if (part.equals("SELECT") && i + 1 < parts.length) {
                inSelect = true;
                fileName = parts[i + 1].replace(".", "");
                fileControl.put("name", fileName);
                selectClause.append("SELECT ").append(fileName);
                i += 2;
            } else if (inSelect && part.equals("ASSIGN") && i + 2 < parts.length) {
                assignClause = parts[i + 1] + " " + parts[i + 2].replace(".", "");
                fileControl.put("assign", assignClause);
                selectClause.append(" ASSIGN ").append(assignClause);
                i += 3;
            } else if (inSelect && part.equals("ORGANIZATION") && i + 2 < parts.length) {
                organizationClause = parts[i + 1] + " " + parts[i + 2].replace(".", "");
                fileControl.put("organization", organizationClause);
                selectClause.append(" ORGANIZATION ").append(organizationClause);
                i += 3;
            } else if (inSelect && part.equals("ACCESS") && i + 3 < parts.length) {
                accessMode = parts[i + 1] + " " + parts[i + 2] + " " + parts[i + 3].replace(".", "");
                fileControl.put("accessMode", accessMode);
                selectClause.append(" ACCESS ").append(accessMode);
                i += 4;
            } else if (inSelect && part.equals("RECORD") && i + 2 < parts.length) {
                recordKey = parts[i + 1] + " " + parts[i + 2].replace(".", "");
                fileControl.put("recordKey", recordKey);
                selectClause.append(" RECORD ").append(recordKey);
                i += 3;
            } else {
                i++;
            }
        }

        if (fileName.equals("UNKNOWN")) {
            errors.add(new ParsingError(fileName, "Missing file name in FILE-CONTROL entry", line));
        }
        fileControl.put("line", line);
        fileControl.put("select", selectClause.length() > 0 ? selectClause.toString() : "UNKNOWN");

        fileControlEntries.put(fileName, fileControl);
        System.out.println("Processed FILE-CONTROL for file " + fileName + " at line " + line + ": " + fileControl.toJSONString());
        return super.visitFileControlEntry(ctx);
    }

    @Override
    public Void visitFileDescriptionEntry(Cobol85Parser.FileDescriptionEntryContext ctx) {
        String fileName = "UNKNOWN";
        for (ParseTree child : ctx.children) {
            if (child instanceof Cobol85Parser.IdentifierContext) {
                fileName = child.getText().toUpperCase();
                break;
            }
        }
        int line = mapLineNumber(ctx.getStart().getLine());
        JSONObject fdData = new JSONObject();
        fdData.put("name", fileName);
        fdData.put("line", line);

        // Handle LABEL RECORD clause
        boolean foundLabel = false;
        for (ParseTree child : ctx.children) {
            String childText = child.getText().toUpperCase();
            if (childText.startsWith("LABEL RECORD")) {
                String labelText = childText.replace("LABEL RECORDS ARE ", "");
                fdData.put("label", labelText);
                if (!labelText.contains("STANDARD") && !labelText.contains("OMITTED")) {
                    errors.add(new ParsingError(fileName, "Invalid LABEL RECORD clause for file " + fileName + ": " + labelText, line));
                }
                foundLabel = true;
                break;
            }
        }
        if (!foundLabel) {
            fdData.put("label", "OMITTED");
        }

        // Handle RECORD DESCRIPTION
        JSONArray records = new JSONArray();
        for (ParseTree child : ctx.children) {
            if (child instanceof Cobol85Parser.DataDescriptionEntryContext) {
                Cobol85Parser.DataDescriptionEntryContext dataCtx = (Cobol85Parser.DataDescriptionEntryContext) child;
                JSONObject recordData = new JSONObject();
                String recordName = "UNKNOWN";
                String level = "UNKNOWN";
                String picture = null;
                int recordLine = mapLineNumber(dataCtx.getStart().getLine());

                for (ParseTree dataChild : dataCtx.children) {
                    String text = dataChild.getText().toUpperCase();
                    if (text.matches("\\d+")) {
                        level = text;
                    } else if (dataChild instanceof Cobol85Parser.IdentifierContext) {
                        recordName = dataChild.getText().toUpperCase();
                    } else if (text.startsWith("PIC") || text.startsWith("PICTURE")) {
                        picture = text;
                    }
                }

                recordData.put("name", recordName);
                recordData.put("level", level);
                recordData.put("line", recordLine);
                if (picture != null) {
                    recordData.put("picture", picture);
                }

                // Map to WORKING-STORAGE SECTION
                if (workingStorageVariables.containsKey(recordName)) {
                    JSONObject wsVar = workingStorageVariables.get(recordName);
                    recordData.put("workingStorageRef", wsVar);
                } else {
                    System.out.println("No WORKING-STORAGE mapping found for record " + recordName + " in file " + fileName + " at line " + recordLine);
                }

                records.add(recordData);
            }
        }
        if (!records.isEmpty()) {
            fdData.put("records", records);
        } else {
            errors.add(new ParsingError(fileName, "No records defined in FD for file " + fileName, line));
        }

        // Link to FILE-CONTROL
        if (fileControlEntries.containsKey(fileName)) {
            fdData.put("fileControl", fileControlEntries.get(fileName));
        } else {
            errors.add(new ParsingError(fileName, "No FILE-CONTROL entry found for file " + fileName, line));
        }

        fileDescriptions.put(fileName, fdData);
        System.out.println("Processed FD for file " + fileName + " at line " + line + ": " + fdData.toJSONString());
        return super.visitFileDescriptionEntry(ctx);
    }

    @Override
    public Void visitSectionHeader(Cobol85Parser.ProcedureSectionHeaderContext ctx) {
        return null;
    }
}