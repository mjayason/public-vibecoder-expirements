IDENTIFICATION DIVISION.
PROGRAM-ID. FILETEST.

ENVIRONMENT DIVISION.
INPUT-OUTPUT SECTION.
FILE-CONTROL.
    SELECT EMP-FILE ASSIGN TO 'EMPLOYEE.DAT'
        ORGANIZATION IS LINE SEQUENTIAL.

DATA DIVISION.
FILE SECTION.
FD  EMP-FILE.
01  EMP-RECORD.
    05 EMP-ID     PIC X(5).
    05 EMP-NAME   PIC X(20).

WORKING-STORAGE SECTION.
01  EOF-FLAG     PIC X VALUE 'N'.

PROCEDURE DIVISION.
    OPEN INPUT EMP-FILE
    PERFORM UNTIL EOF-FLAG = 'Y'
        READ EMP-FILE
            AT END
                MOVE 'Y' TO EOF-FLAG
            NOT AT END
                DISPLAY "EMPLOYEE: " EMP-ID " - " EMP-NAME
        END-READ
    END-PERFORM
    CLOSE EMP-FILE
    STOP RUN.
