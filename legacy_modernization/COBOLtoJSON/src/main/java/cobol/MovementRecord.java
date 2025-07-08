package cobol;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class MovementRecord {
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

    public String getOperation() {
        return operation;
    }

    public List<String> getSource() {
        return source;
    }

    public String getTarget() {
        return target;
    }

    public int getLine() {
        return line;
    }
}

