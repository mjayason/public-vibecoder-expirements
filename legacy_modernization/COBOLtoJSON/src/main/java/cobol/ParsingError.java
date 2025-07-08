package cobol;

import org.json.simple.JSONObject;

/**
 * Represents a parsing error with file, message, and line number.
 */
public class ParsingError {
    private final String file;
    private final String message;
    private final int line;

    /**
     * Constructs a parsing error.
     * @param file The file where the error occurred
     * @param message The error message
     * @param line The line number (0 if unknown)
     */
    public ParsingError(String file, String message, int line) {
        this.file = file;
        this.message = message;
        this.line = line;
    }

    /**
     * Converts the error to JSON.
     * @return JSONObject representing the error
     */
    public JSONObject toJson() {
        JSONObject obj = new JSONObject();
        obj.put("file", file);
        obj.put("message", message);
        obj.put("line", line);
        return obj;
    }
}