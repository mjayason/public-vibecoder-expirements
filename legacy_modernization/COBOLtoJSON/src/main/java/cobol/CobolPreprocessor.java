package cobol;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Preprocesses COBOL source code by resolving COPY statements and handling copybooks.
 * Supports caching to improve performance.
 */
public class CobolPreprocessor {
    private final Path includeDir;
    private static final int MAX_DEPTH = 10;
    private static final String[] COPY_EXTENSIONS = {".cpy", ".cob", ".inc"};
    private final Set<String> visitedCopybooks = new HashSet<>();
    private final Map<String, String> copybookCache = new HashMap<>();
    private final List<Integer> lineNumberMap = new ArrayList<>();

    public CobolPreprocessor(Path includeDir) {
        this.includeDir = includeDir;
    }

    public String preprocess(String source) throws IOException {
        visitedCopybooks.clear();
        lineNumberMap.clear();
        return preprocess(source, 0);
    }

    private String preprocess(String source, int depth) throws IOException {
        if (depth > MAX_DEPTH) {
            return "*> ERROR: Maximum COPY depth exceeded\n";
        }

        StringBuilder result = new StringBuilder();
        int sourceLine = 1;
        for (String line : source.split("\\r?\\n")) {
            lineNumberMap.add(sourceLine++);
            String trimmedLine = line.trim();

            if (trimmedLine.toUpperCase().contains("REPLACING")) {
                result.append("*> #unsupported_copy_replacing <").append(trimmedLine).append(">\n");
                continue;
            }

            if (trimmedLine.toUpperCase().startsWith("COPY")) {
                String[] parts = trimmedLine.split("\\s+");
                if (parts.length >= 2) {
                    String rawName = parts[1].replace(".", "");
                    String matchedCopybook = findCopybook(rawName);
                    if (matchedCopybook != null) {
                        if (visitedCopybooks.contains(matchedCopybook)) {
                            result.append("*> #circular_copy <").append(matchedCopybook).append(">\n");
                        } else {
                            visitedCopybooks.add(matchedCopybook);
                            String copyContent = copybookCache.computeIfAbsent(matchedCopybook, k -> {
                                try {
                                    Path copyPath = includeDir.resolve(matchedCopybook);
                                    return new String(Files.readAllBytes(copyPath), StandardCharsets.UTF_8);
                                } catch (IOException e) {
                                    return null;
                                }
                            });
                            if (copyContent != null) {
                                result.append("*> #include <").append(matchedCopybook).append("> line ").append(sourceLine).append("\n");
                                result.append(preprocess(copyContent, depth + 1)).append("\n");
                                result.append("*> #endinclude <").append(matchedCopybook).append(">\n");
                            } else {
                                result.append("*> #error_reading_copy <").append(matchedCopybook).append(">\n");
                            }
                        }
                    } else {
                        result.append("*> #missing_copy <").append(rawName).append(".*>\n");
                    }
                }
            } else {
                result.append(line).append("\n");
            }
        }

        return result.toString();
    }

    private String findCopybook(String baseName) {
        for (String ext : COPY_EXTENSIONS) {
            String candidate = baseName + ext;
            if (copybookCache.containsKey(candidate) || Files.exists(includeDir.resolve(candidate))) {
                return candidate;
            }
        }
        return null;
    }

    public Set<String> getVisitedCopybooks() {
        return visitedCopybooks;
    }

    public List<Integer> getLineNumberMap() {
        return lineNumberMap;
    }
}