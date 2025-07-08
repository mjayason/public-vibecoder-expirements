# COBOL Modernization Workflow Guide

## ✅ Step 1: Convert COBOL to JSON

📂 **Input**: COBOL source files  
📁 **Output**: JSON representation + documentation

### 🛠 Instructions:

1. **Place COBOL Files**  
   Put your `.cob` or `.cbl` files in the `./input/` folder.

2. **Run the Java Parser**  
   Use the prebuilt JAR to parse COBOL to structured JSON:
   ```bash
   java -jar target/cobol-json-parser-1.0-SNAPSHOT.jar ./input ./output
   ```

3. ✅ **Expected Output** (in `./output/`):
   - `*.json` files (parsed structure)
   - `*.md` documentation per COBOL program

---

## ✅ Step 2: Convert JSON + COBOL to Target Language (Java, .NET, JS, etc.)

📂 **Input**: JSON from Step 1 + original COBOL  
📁 **Output**: Modernized code + parity report

### 🧠 Notebook Setup:

1. **Copy Files to Notebook Input Folder**  
   Place both:
   - COBOL files
   - Generated JSON files  
   into:  
   ```
   ./batch_input/
   ```

2. **Set Up OpenAI Key**  
   Configure your secret key for model access:
   - For Colab: `os.environ["OPENAI_API_KEY"] = "your-key-here"`
   - For local notebook: Use `.env` or notebook secrets manager.

3. **Run All Cells**  
   Execute the notebook: `OpenAI_COBOL_Java_Modernizer_With_Summary.ipynb`

4. ✅ **Expected Output** (in `./batch_output/`):
   - Generated `.java`, `.cs`, or `.js` files
   - Field-level **Feature Parity Report**


