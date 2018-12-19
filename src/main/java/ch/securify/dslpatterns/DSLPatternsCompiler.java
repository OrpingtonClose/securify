package ch.securify.dslpatterns;

import ch.securify.analysis.DSLAnalysis;
import ch.securify.decompiler.Variable;
import ch.securify.decompiler.instructions.Balance;
import ch.securify.decompiler.instructions.CallDataLoad;
import ch.securify.decompiler.instructions.Caller;
import ch.securify.decompiler.instructions.SLoad;
import ch.securify.dslpatterns.datalogpattern.DatalogRule;
import ch.securify.dslpatterns.instructions.AbstractDSLInstruction;
import ch.securify.dslpatterns.tags.DSLArg;
import ch.securify.dslpatterns.util.DSLLabel;
import ch.securify.dslpatterns.util.DSLLabelDC;
import ch.securify.dslpatterns.util.InvalidPatternException;
import ch.securify.dslpatterns.util.VariableDC;
import ch.securify.utils.CommandRunner;

import java.io.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class DSLPatternsCompiler extends DSLPatternFactory {

    private static final String SOUFFLE_RULES = "smt_files/allInOneAnalysis.dl";
    private static final String TMP_DL_FILE = "smt_files/finalTmpFile.dl";
    public static final String FINAL_EXECUTABLE = "build/dslSolver";

    private static final String SOUFFLE_BIN = "souffle";

    private static final boolean debug = true;

    public static final String DATALOG_PATTERNS_FILE = "smt_files/CompiledPatterns.dl";
    public static final String PATTERN_NAMES_CSV = "smt_files/CompiledPatternsNames.csv";

    private static class CompletePattern {
        private InstructionDSLPattern DSLCompliancePattern;
        private InstructionDSLPattern DSLViolationPattern;
        private List<DatalogRule> translatedRules;
        private String name;

        CompletePattern(String name, InstructionDSLPattern DSLCompliancePattern, InstructionDSLPattern DSLViolationPattern) {
            this.DSLCompliancePattern = DSLCompliancePattern;
            this.DSLViolationPattern = DSLViolationPattern;
            this.name = name;
            translatedRules = new ArrayList<>();
        }

        InstructionDSLPattern getDSLCompliancePattern() {
            return DSLCompliancePattern;
        }

        InstructionDSLPattern getDSLViolationPattern() {
            return DSLViolationPattern;
        }

        void addTranslatedRules(List<DatalogRule> translatedRules) {
            this.translatedRules.addAll(translatedRules);
        }

        List<DatalogRule> getTranslatedRules() {
            return translatedRules;
        }

        public String getName() {
            return name;
        }

        String getComplianceName() {
            return name + "Compliance";
        }

        String getViolationName() {
            return name + "Violation";
        }

        String getWaringRuleDeclaration() {
            StringBuilder sb = new StringBuilder();

            //example of declaration
            //.decl jump		(l1: Label, l2: Label, l3: Label) output
            sb.append(".decl ");
            sb.append(name);
            sb.append("Warnings(L: Label)\n");
            sb.append(".output ");
            sb.append(name);
            sb.append("Warnings");

            return sb.toString();
        }

        String getWarningRule(DSLAnalysis analyzer) {
            StringBuilder sb = new StringBuilder();

            AbstractDSLInstruction quantifiedInst = DSLCompliancePattern.getQuantifiedInstr();
            if(quantifiedInst.getLabel() instanceof DSLLabelDC) {
                quantifiedInst = quantifiedInst.getCopy();
                quantifiedInst.setLabel(new DSLLabel());
            }
            String label = quantifiedInst.getLabel().getName();

            sb.append(name);
            sb.append("Warnings(");
            sb.append(label);
            sb.append(") :- ");
            sb.append(quantifiedInst.getDatalogStringRepDC(analyzer));
            sb.append(" , !");
            sb.append(name);
            sb.append("Compliance(");
            sb.append(label);
            sb.append("), !");
            sb.append(name);
            sb.append("Violation(");
            sb.append(label);
            sb.append(").");

            return sb.toString();
        }
    }

    private static boolean isSouffleInstalled() {
        try {
            Process process = new ProcessBuilder(SOUFFLE_BIN).start();
            process.waitFor();
            return process.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
            return false;
        }
    }

    public static void generateDatalogExecutable() throws IOException, InterruptedException {

        if(!isSouffleInstalled()){
            System.err.println("Soufflé does not seem to be installed.");
            System.exit(7);
        }

        List<CompletePattern> patterns = createPatterns();
        translatePatterns(patterns);
        try {
            writePatternsToFile(patterns);
            writePatternsNameToCSV(patterns);

        } catch (IOException e) {
            e.printStackTrace();
        }

        collapseSouffleRulesAndQueries();

        String cmd = SOUFFLE_BIN + " --dl-program=" + FINAL_EXECUTABLE + " " + TMP_DL_FILE;
        log(cmd);
        CommandRunner.runCommand(cmd);
    }

    private static void collapseSouffleRulesAndQueries() throws IOException {
        PrintWriter pw = new PrintWriter( TMP_DL_FILE);
        BufferedReader br = new BufferedReader(new FileReader(SOUFFLE_RULES));

        String line = br.readLine();
        while (line != null) {
            pw.println(line);
            line = br.readLine();
        }
        br = new BufferedReader(new FileReader(DSLPatternsCompiler.DATALOG_PATTERNS_FILE));
        line = br.readLine();

        while(line != null)
        {
            pw.println(line);
            line = br.readLine();
        }

        pw.flush();
        br.close();
        pw.close();
    }

    private static List<CompletePattern> createPatterns() {

        List<CompletePattern> patterns = new ArrayList<>(12);

        DSLPatternFactory pattFct = new DSLPatternFactory();

        DSLLabel l1 = new DSLLabel();
        DSLLabel l2 = new DSLLabel();
        DSLLabel l3 = new DSLLabel();
        DSLLabel l4 = new DSLLabel();
        DSLLabelDC dcLabel = new DSLLabelDC();
        VariableDC dcVar = new VariableDC();
        Variable X = new Variable();
        Variable Y = new Variable();
        Variable amount = new Variable();

        DSLToDatalogTranslator transl = new DSLToDatalogTranslator();

        /*log(" *** LQ - Ether liquidity");
        AbstractDSLPattern patternComplianceOneLQ = pattFct.all(instrFct.stop(l1),
                pattFct.some(instrFct.dslgoto(l2, X, l3),
                        pattFct.and(
                                pattFct.eq(X, CallValue.class),
                                prdFct.follow(l2, l4),
                                pattFct.not(pattFct.eq(l3, l4)),
                                prdFct.mustFollow(l4, l1))));

        log(patternComplianceOneLQ.getStringRepresentation());

        patterns.add(patternComplianceOneLQ);

        AbstractDSLPattern patternComplianceTwoLQ = pattFct.some(instrFct.call(l1, dcVar, dcVar, amount),
                pattFct.or(pattFct.not(pattFct.eq(amount, 0)), prdFct.detBy(amount, CallDataLoad.class)));

        log(patternComplianceTwoLQ.getStringRepresentation());

        AbstractDSLPattern patternViolationLQ = pattFct.and(
                pattFct.some(instrFct.stop(l1),
                        pattFct.not(prdFct.mayDepOn(l1, CallValue.class))),
                pattFct.all(instrFct.call(dcLabel, dcVar, dcVar, amount),
                        pattFct.eq(amount, 0)));

        log(patternViolationLQ.getStringRepresentation());*/

        log(" *** NW - No writes after call - DAO");
        InstructionDSLPattern patternComplianceNW = pattFct.instructionPattern(
                pattFct.call(l1, dcVar, dcVar, dcVar),
                pattFct.all(pattFct.sstore(l2, dcVar, dcVar),
                        pattFct.not(pattFct.mayFollow(l1, l2)))
        );

        log(patternComplianceNW.getStringRepresentation());

        InstructionDSLPattern patternViolationNW = pattFct.instructionPattern(
                pattFct.call(l1, dcVar, dcVar, dcVar),
                pattFct.some(pattFct.sstore(l2, dcVar, dcVar),
                        pattFct.mustFollow(l1, l2))
        );

        log(patternViolationNW.getStringRepresentation());
        patterns.add(new CompletePattern("NoWritesAfterCallDAO", patternComplianceNW, patternViolationNW));

        log(" *** RW - restricted write");
        InstructionDSLPattern patternComplianceRW = pattFct.instructionPattern(pattFct.sstore(dcLabel, X, dcVar),
                pattFct.detBy(X, Caller.class));
        log(patternComplianceRW.getStringRepresentation());

        InstructionDSLPattern patternViolationRW = instructionPattern(pattFct.sstore(l1, X, dcVar),
                pattFct.and(pattFct.not(pattFct.mayDepOn(X, Caller.class)), pattFct.not(pattFct.mayDepOn(l1, Caller.class))));
        log(patternViolationRW.getStringRepresentation());
        patterns.add(new CompletePattern("unRestrictedWrite", patternComplianceRW, patternViolationRW));

        log(" *** RT - restricted transfer");
        InstructionDSLPattern patternComplianceRT = pattFct.instructionPattern(pattFct.call(dcLabel, dcVar, dcVar, amount),
                pattFct.eq(amount, 0));
        log(patternComplianceRT.getStringRepresentation());

        InstructionDSLPattern patternViolationRT = pattFct.instructionPattern(pattFct.call(l1, dcVar, dcVar, amount),
                pattFct.or(pattFct.and(pattFct.detBy(amount, CallDataLoad.class),
                        pattFct.not(pattFct.mayDepOn(l1, Caller.class)),
                        pattFct.not(pattFct.mayDepOn(l1, CallDataLoad.class))), pattFct.and(pattFct.isConst(amount), pattFct.greaterThan(amount, 0))));
        log(patternViolationRT.getStringRepresentation());
        CompletePattern unrestTrans = new CompletePattern("unRestrictedTransferEtherFlow", patternComplianceRT, patternViolationRT);
        patterns.add(unrestTrans);

        log(" *** HE - handled exception");
        InstructionDSLPattern patternComplianceHE = pattFct.instructionPattern(pattFct.call(l1, Y, dcVar, dcVar),
                pattFct.some(pattFct.dslgoto(l2, X, dcLabel),
                        pattFct.and(pattFct.mustFollow(l1, l2), pattFct.detBy(X, Y))));
        log(patternComplianceHE.getStringRepresentation());

        InstructionDSLPattern patternViolationHE =
                instructionPattern(call(l1, Y, dcVar, dcVar),
                                    all(dslgoto(l2, X, dcLabel),
                                            implies(mayFollow(l1, l2), not(mayDepOn(X, Y)))));

        log(patternViolationHE.getStringRepresentation());
        patterns.add(new CompletePattern("unHandledException", patternComplianceHE, patternViolationHE));

        log(" *** TOD - transaction ordering dependency");
        InstructionDSLPattern patternComplianceTOD = pattFct.instructionPattern(pattFct.call(dcLabel, dcVar, dcVar, amount),
                    pattFct.and(pattFct.not(pattFct.mayDepOn(amount, SLoad.class)), pattFct.not(pattFct.mayDepOn(amount, Balance.class))));
        log(patternComplianceTOD.getStringRepresentation());

        InstructionDSLPattern patternViolationTOD = pattFct.instructionPattern(pattFct.call(dcLabel, dcVar, dcVar, amount),
                pattFct.some(pattFct.sload(dcLabel, Y, X),
                        pattFct.some(pattFct.sstore(dcLabel, X, dcVar),
                                pattFct.and(pattFct.detBy(amount, Y), pattFct.isConst(X)))));
        log(patternViolationTOD.getStringRepresentation());
        patterns.add(new CompletePattern("TOD", patternComplianceTOD, patternViolationTOD));

        log(" *** VA - validated arguments");
        InstructionDSLPattern patternComplianceVA = instructionPattern(sstore(l1, dcVar, X),
                implies(mayDepOn(X, DSLArg.class),
                        some(dslgoto(l2, Y, dcLabel),
                                and(pattFct.mustFollow(l2, l1),
                                        detBy(Y, DSLArg.class)))));
        log(patternComplianceVA.getStringRepresentation());

        InstructionDSLPattern patternViolationVA = pattFct.instructionPattern(pattFct.sstore(l1, dcVar, X),
                pattFct.and(pattFct.detBy(X, DSLArg.class),
                        pattFct.not(pattFct.some(pattFct.dslgoto(l2, Y, dcLabel),
                                pattFct.and(pattFct.mustFollow(l2, l1),
                                        pattFct.mayDepOn(Y, DSLArg.class))))));
        log(patternViolationVA.getStringRepresentation());
        patterns.add(new CompletePattern("ValidatedArgumentsMissingInputValidation", patternComplianceVA, patternViolationVA));
        
        return patterns;

    }
    
    private static void log(String str) {
        if(debug)
            System.out.println(str);
    }

    private static void translatePatterns(List<CompletePattern> patterns) {
        patterns.forEach(completePattern -> {
            try {
                completePattern.addTranslatedRules(
                        DSLToDatalogTranslator.translateInstructionPattern(
                                completePattern.getDSLCompliancePattern(), completePattern.getComplianceName()));
                completePattern.addTranslatedRules(
                        DSLToDatalogTranslator.translateInstructionPattern(
                                completePattern.getDSLViolationPattern(), completePattern.getViolationName()));
            } catch (InvalidPatternException e) {
                e.printStackTrace();
            }
        });
    }

    private static void writePatternsToFile(List<CompletePattern> patterns) throws IOException {
        DSLAnalysis analyzer;

        Set<String> alreadyDeclaredRules = new HashSet<>(patterns.size());

        try {
            analyzer = new DSLAnalysis();
        } catch (InterruptedException e) {
            e.printStackTrace();
            return;
        }

        BufferedWriter bwr;
        bwr = new BufferedWriter(new FileWriter(new File(DATALOG_PATTERNS_FILE)));
        for (CompletePattern patt : patterns) {
            for (DatalogRule rule : patt.getTranslatedRules()) {

                String name = rule.getHead().getName();
                if(!alreadyDeclaredRules.contains(name)) {
                    alreadyDeclaredRules.add(name);
                    bwr.write(rule.getDeclaration());
                    bwr.newLine();
                }
                bwr.write(rule.getStingRepresentation(analyzer));
                bwr.newLine();
            }
            bwr.write(patt.getWaringRuleDeclaration());
            bwr.newLine();
            bwr.write(patt.getWarningRule(analyzer));
            bwr.newLine();
            bwr.newLine();
        }
        bwr.close();

    }

    private static void writePatternsNameToCSV(List<CompletePattern> patterns) throws IOException {
        BufferedWriter bwr;
        bwr = new BufferedWriter(new FileWriter(new File(PATTERN_NAMES_CSV)));
        if(!patterns.isEmpty())
            bwr.write(patterns.get(0).getName());
        for (int i = 1; i < patterns.size(); ++i) {
            bwr.write(" , " + patterns.get(i).getName());
        }
        bwr.close();
    }

}