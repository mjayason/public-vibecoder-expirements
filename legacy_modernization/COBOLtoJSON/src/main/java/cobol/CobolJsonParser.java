package cobol;

import cobol.antlr.Cobol85Lexer;
import cobol.antlr.Cobol85Parser;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.ParseTree;
import org.json.simple.JSONObject;
import org.json.simple.JSONArray;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Main class for parsing COBOL files and generating JSON and Mermaid outputs.
 * Supports single files or directories, with preprocessing and configuration options.
 */
public class CobolJsonParser {
    private static final int THREAD_POOL_SIZE = Runtime.getRuntime().availableProcessors();
    private static JSONObject config;

    /**
     * Entry point for the COBOL parser.
     * @param args Command-line arguments: <input_file_or_folder> <output_folder> [include_dir]
     * @throws Exception If parsing fails due to I/O or configuration errors
     */
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: java CobolJsonParser <input_file_or_folder> <output_folder> [include_dir]");
            System.exit(1);
        }

        // Load configuration
        loadConfig();

        Path inputPath = Paths.get(args[0]);
        Path outputDir = Paths.get(args[1]);
        Path includeDir = (args.length >= 3) ? Paths.get(args[2]) : inputPath;

        Files.createDirectories(outputDir);
        CobolPreprocessor preprocessor = new CobolPreprocessor(includeDir);
        List<ParsingError> errors = Collections.synchronizedList(new ArrayList<>());

        if (Files.isDirectory(inputPath)) {
            processDirectory(inputPath, outputDir, preprocessor, errors);
        } else {
            processFile(inputPath, outputDir, preprocessor, errors);
        }

        // Write error report
        if (!errors.isEmpty()) {
            Path errorPath = outputDir.resolve("parsing_errors.json");
            writeErrorReport(errorPath, errors);
        }
    }

    /**
     * Loads configuration from config.json in the current directory.
     * @throws IOException If the config file cannot be read
     * @throws ParseException If the config file is invalid JSON
     */
    private static void loadConfig() throws IOException, ParseException {
        Path configPath = Paths.get("config.json");
        if (Files.exists(configPath)) {
            String configContent = new String(Files.readAllBytes(configPath), StandardCharsets.UTF_8);
            JSONParser parser = new JSONParser();
            config = (JSONObject) parser.parse(configContent);
        } else {
            config = new JSONObject();
        }
    }

    /**
     * Processes a directory of COBOL files in parallel.
     * @param inputDir Input directory path
     * @param outputDir Output directory path
     * @param preprocessor COBOL preprocessor instance
     * @param errors List to collect parsing errors
     */
    private static void processDirectory(Path inputDir, Path outputDir, CobolPreprocessor preprocessor, List<ParsingError> errors) {
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
        try (Stream<Path> paths = Files.walk(inputDir)) {
            paths.filter(p -> p.toString().endsWith(".cbl") || p.toString().endsWith(".cob"))
                    .forEach(p -> executor.submit(() -> processFile(p, outputDir, preprocessor, errors)));
        } catch (IOException e) {
            errors.add(new ParsingError(inputDir.toString(), "Failed to walk directory: " + e.getMessage(), 0));
        }

        executor.shutdown();
        try {
            if (!executor.awaitTermination(1, TimeUnit.HOURS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Processes a single COBOL file and generates JSON and Mermaid outputs.
     * @param inputFile Input file path
     * @param outputDir Output directory path
     * @param preprocessor COBOL preprocessor instance
     * @param errors List to collect parsing errors
     */
    static void processFile(Path inputFile, Path outputDir, CobolPreprocessor preprocessor, List<ParsingError> errors) {
        try {
            String sourceCode = new String(Files.readAllBytes(inputFile), StandardCharsets.UTF_8);
            String preprocessed = preprocessor.preprocess(sourceCode);
            CharStream input = CharStreams.fromString(preprocessed);
            Cobol85Lexer lexer = new Cobol85Lexer(input);
            CommonTokenStream tokens = new CommonTokenStream(lexer);
            Cobol85Parser parser = new Cobol85Parser(tokens);
            parser.removeErrorListeners();
            parser.addErrorListener(new DiagnosticErrorListener(inputFile, errors));

            ParseTree tree = parser.startRule();

            if (parser.getNumberOfSyntaxErrors() > 0) {
                errors.add(new ParsingError(inputFile.toString(), "Syntax errors detected in COBOL code", 0));
                return;
            }

            CobolJsonVisitor visitor = new CobolJsonVisitor();
            String programId = inputFile.getFileName().toString().replaceAll("\\.(cbl|cob)$", "");
            visitor.setProgramId(programId);
            visitor.setTokenStream(tokens);

            WorkingStorageVisitor wsVisitor = new WorkingStorageVisitor();
            DivisionSectionVisitor structureVisitor = new DivisionSectionVisitor();
            VariableTrackerVisitor variableVisitor = new VariableTrackerVisitor(wsVisitor.getDeclaredVariables());
            StructuredStatementTransformer transformer = new StructuredStatementTransformer();

            visitor.visit(tree);
            wsVisitor.visit(tree);
            structureVisitor.visit(tree);
            variableVisitor.visit(tree);
            transformer.visit(tree); // Process FD entries
            transformer.processCopybooks(preprocessor); // Process copybooks
            errors.addAll(visitor.getErrors());
            errors.addAll(transformer.getErrors());

            JSONObject finalOutput = visitor.getJsonOutput();
            finalOutput.put("workingStorage", wsVisitor.getWorkingStorageJson());
            finalOutput.put("structure", structureVisitor.getDivisionStructure());
            finalOutput.put("dataMovement", variableVisitor.getMovementJson());
            finalOutput.put("copybooks", new JSONArray() {{ addAll(transformer.getCopybooksIncluded()); }});
            finalOutput.put("fileDescriptions", transformer.getFileDescriptions());

            StructuredStatementTransformer.TransformationResult result = transformer.transformParagraph(visitor.getParagraphMap().getOrDefault("_MAIN", new JSONArray()), new StructuredStatementTransformer.ContextMetadata("_MAIN", programId));
            finalOutput.put("structuredStatements", new JSONObject() {{ put("_MAIN", result.statements); }});
            finalOutput.put("callGraph", new JSONObject() {{ put("_MAIN", new JSONArray() {{ addAll(result.callGraph.getOrDefault("_MAIN", Collections.emptySet())); }}); }});
            finalOutput.put("complexity", result.cyclomaticComplexity);

            String outputPrefix = (String) config.getOrDefault("outputPrefix", "");
            String jsonName = outputPrefix + inputFile.getFileName().toString().replaceAll("\\.(cbl|cob)$", ".json");
            Path outPath = outputDir.resolve(jsonName);
            try (BufferedWriter writer = Files.newBufferedWriter(outPath, StandardCharsets.UTF_8)) {
                writer.write(finalOutput.toJSONString());
            }

            Path callGraphPath = outputDir.resolve(outputPrefix + "callgraph_" + visitor.getProgramId() + ".md");
            Files.write(callGraphPath, visitor.getCallGraphMermaid().getBytes(StandardCharsets.UTF_8));

            Path dataFlowPath = outputDir.resolve(outputPrefix + "dataflow_" + visitor.getProgramId() + ".md");
            Files.write(dataFlowPath, variableVisitor.getDataFlowMermaid().getBytes(StandardCharsets.UTF_8));

            System.out.printf("✔ Parsed %-30s → %s%n", inputFile.getFileName(), outPath.getFileName());

        } catch (IOException e) {
            errors.add(new ParsingError(inputFile.toString(), "I/O error: " + e.getMessage(), 0));
        } catch (Exception e) {
            errors.add(new ParsingError(inputFile.toString(), "Processing error: " + e.getMessage(), 0));
        }
    }

    /**
     * Splits a COBOL line into fragments based on configured keywords, preserving line numbers using token positions.
     * @param text The input COBOL line
     * @param line The starting line number
     * @param tokens The ANTLR token stream
     * @return List of JSON objects with text and line number
     */
    private static List<JSONObject> splitLine(String text, long line, CommonTokenStream tokens) {
        JSONArray splitKeywords = (JSONArray) config.getOrDefault("splitKeywords", new JSONArray());
        if (splitKeywords.isEmpty()) {
            splitKeywords.addAll(Arrays.asList("IF", "ELSE", "END-IF", "CALL", "DISPLAY", "PERFORM", "ADD", "SUBTRACT",
                    "GOBACK", "MOVE", "EVALUATE", "WHEN", "END-EVALUATE", "ACCEPT", "GO TO", "READ", "WRITE", "INSPECT"));
        }
        String regex = "(?=\\b(" + String.join("|", splitKeywords) + ")\\b(?!.*\\b(USING|WHEN)\\b))";
        String[] fragments = text.split(regex);
        List<JSONObject> splitLines = new ArrayList<>();
        int currentLine = (int) line;
        for (String fragment : fragments) {
            fragment = fragment.trim();
            if (!fragment.isEmpty()) {
                JSONObject newLine = new JSONObject();
                newLine.put("text", fragment);
                newLine.put("line", currentLine);
                splitLines.add(newLine);
                currentLine++;
            }
        }
        return splitLines;
    }

    /**
     * Writes parsing errors to a JSON file.
     * @param errorPath Output path for the error report
     * @param errors List to collect parsing errors
     * @throws IOException If writing fails
     */
    private static void writeErrorReport(Path errorPath, List<ParsingError> errors) throws IOException {
        JSONArray errorArray = new JSONArray();
        for (ParsingError error : errors) {
            errorArray.add(error.toJson());
        }
        JSONObject errorReport = new JSONObject();
        errorReport.put("errors", errorArray);
        try (BufferedWriter writer = Files.newBufferedWriter(errorPath, StandardCharsets.UTF_8)) {
            writer.write(errorReport.toJSONString());
        }
    }

    /**
     * Custom ANTLR error listener to collect syntax errors.
     */
    private static class DiagnosticErrorListener extends BaseErrorListener {
        private final Path file;
        private final List<ParsingError> errors;

        public DiagnosticErrorListener(Path file, List<ParsingError> errors) {
            this.file = file;
            this.errors = errors;
        }

        @Override
        public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line, int charPositionInLine,
                                String msg, RecognitionException e) {
            errors.add(new ParsingError(file.toString(), "Syntax error: " + msg, line));
        }
    }
}