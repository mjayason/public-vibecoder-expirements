package cobol;

import cobol.antlr.Cobol85Parser;
import org.antlr.v4.runtime.tree.ParseTree;

import java.util.ArrayList;
import java.util.List;

/**
 * Utility methods for extracting semantic elements from a COBOL AST.
 * Includes helpers for section/paragraph names, sentence text, and statement detection.
 */
public class CobolAstUtils {

    /**
     * Extracts all paragraph names from a procedure division.
     */
    public static List<String> getAllParagraphNames(Cobol85Parser.ProcedureDivisionContext ctx) {
        List<String> paragraphNames = new ArrayList<>();
        if (ctx == null || ctx.children == null) return paragraphNames;

        for (ParseTree child : ctx.children) {
            if (child instanceof Cobol85Parser.ParagraphContext) {
                Cobol85Parser.ParagraphContext paragraphCtx = (Cobol85Parser.ParagraphContext) child;
                if (paragraphCtx.paragraphName() != null) {
                    paragraphNames.add(paragraphCtx.paragraphName().getText());
                }
            }
        }
        return paragraphNames;
    }

    /**
     * Extracts all section header names from a procedure division.
     */
    public static List<String> getAllSectionHeaders(Cobol85Parser.ProcedureDivisionContext ctx) {
        List<String> sectionHeaders = new ArrayList<>();
        if (ctx == null || ctx.children == null) return sectionHeaders;

        for (ParseTree child : ctx.children) {
            if (child instanceof Cobol85Parser.ProcedureSectionHeaderContext) {
                Cobol85Parser.ProcedureSectionHeaderContext sectionHeaderCtx = (Cobol85Parser.ProcedureSectionHeaderContext) child;
                if (sectionHeaderCtx.sectionName() != null) {
                    sectionHeaders.add(sectionHeaderCtx.sectionName().getText());
                }
            }
        }
        return sectionHeaders;
    }

    /**
     * Extracts all sentence text (flattened) from a procedure division.
     */
    public static List<String> getAllSentenceTexts(Cobol85Parser.ProcedureDivisionContext ctx) {
        List<String> sentences = new ArrayList<>();
        if (ctx == null || ctx.children == null) return sentences;

        for (ParseTree child : ctx.children) {
            if (child instanceof Cobol85Parser.ParagraphContext) {
                Cobol85Parser.ParagraphContext paragraphCtx = (Cobol85Parser.ParagraphContext) child;
                for (Cobol85Parser.SentenceContext sentence : paragraphCtx.sentence()) {
                    String normalized = normalizeSentence(sentence.getText());
                    sentences.add(normalized);
                }
            } else if (child instanceof Cobol85Parser.SentenceContext) {
                String normalized = normalizeSentence(child.getText());
                sentences.add(normalized);
            }
        }
        return sentences;
    }

    /**
     * Extracts all paragraph targets used in PERFORM statements.
     */
    public static List<String> extractPerformTargets(Cobol85Parser.ProcedureDivisionContext ctx) {
        List<String> targets = new ArrayList<>();
        if (ctx == null || ctx.children == null) return targets;

        for (ParseTree child : ctx.children) {
            if (child instanceof Cobol85Parser.ParagraphContext) {
                Cobol85Parser.ParagraphContext paragraphCtx = (Cobol85Parser.ParagraphContext) child;
                for (Cobol85Parser.SentenceContext sentence : paragraphCtx.sentence()) {
                    String text = sentence.getText().toUpperCase();
                    if (text.startsWith("PERFORM ")) {
                        String[] parts = text.split("\\s+");
                        if (parts.length > 1 && !parts[1].equalsIgnoreCase("VARYING")) {
                            targets.add(parts[1].replace(".", ""));
                        }
                    }
                }
            }
        }
        return targets;
    }

    /**
     * Checks if a statement starts with a CALL.
     */
    public static boolean isCallStatement(String text) {
        return text != null && text.trim().toUpperCase().startsWith("CALL ");
    }

    /**
     * Checks if a statement starts with a PERFORM.
     */
    public static boolean isPerformStatement(String text) {
        return text != null && text.trim().toUpperCase().startsWith("PERFORM ");
    }

    /**
     * Checks if a sentence looks like a COMPUTE statement.
     */
    public static boolean isComputeStatement(String text) {
        return text != null && text.trim().toUpperCase().startsWith("COMPUTE ");
    }

    /**
     * Removes redundant whitespace and trims the sentence.
     */
    private static String normalizeSentence(String raw) {
        return raw.replaceAll("\\s+", " ").trim().replaceAll("'([^']*)'", "'$1'");
    }
}