package rars;

import rars.assembler.SymbolTable;
import rars.assembler.Token;
import rars.assembler.TokenList;
import rars.assembler.TokenTypes;
import rars.riscv.hardware.ControlAndStatusRegisterFile;
import rars.riscv.hardware.FloatingPointRegisterFile;
import rars.riscv.hardware.Register;
import rars.riscv.hardware.RegisterFile;
import rars.riscv.BasicInstruction;
import rars.riscv.BasicInstructionFormat;
import rars.riscv.Instruction;
import rars.util.Binary;
import rars.venus.NumberDisplayBaseChooser;

import java.util.ArrayList;

/**
 * Represents one assembly/machine statement.  This represents the "bare machine" level.
 * Pseudo-instructions have already been processed at this point and each assembly
 * statement generated by them is one of these.
 *
 * @author Pete Sanderson and Jason Bumgarner
 * @version August 2003
 */


public class ProgramStatement implements Comparable<ProgramStatement> {
    private RISCVprogram sourceProgram;
    private String source, basicAssemblyStatement, machineStatement;
    private TokenList originalTokenList, strippedTokenList;
    private BasicStatementList basicStatementList;
    private int[] operands;
    private int numOperands;
    private Instruction instruction;
    private int textAddress;
    private int sourceLine;
    private int binaryStatement;
    private boolean altered;
    private static final String invalidOperator = "<INVALID>";

    //////////////////////////////////////////////////////////////////////////////////

    /**
     * Constructor for ProgramStatement when there are links back to all source and token
     * information.  These can be used by a debugger later on.
     *
     * @param sourceProgram     The RISCVprogram object that contains this statement
     * @param source            The corresponding RISCV source statement.
     * @param origTokenList     Complete list of Token objects (includes labels, comments, parentheses, etc)
     * @param strippedTokenList List of Token objects with all but operators and operands removed.
     * @param inst              The Instruction object for this statement's operator.
     * @param textAddress       The Text Segment address in memory where the binary machine code for this statement
     *                          is stored.
     **/
    public ProgramStatement(RISCVprogram sourceProgram, String source, TokenList origTokenList, TokenList strippedTokenList,
                            Instruction inst, int textAddress, int sourceLine) {
        this.sourceProgram = sourceProgram;
        this.source = source;
        this.originalTokenList = origTokenList;
        this.strippedTokenList = strippedTokenList;
        this.operands = new int[5];
        this.numOperands = 0;
        this.instruction = inst;
        this.textAddress = textAddress;
        this.sourceLine = sourceLine;
        this.basicAssemblyStatement = null;
        this.basicStatementList = new BasicStatementList();
        this.machineStatement = null;
        this.binaryStatement = 0;  // nop, or sll $0, $0, 0  (32 bits of 0's)
        this.altered = false;
    }


    //////////////////////////////////////////////////////////////////////////////////

    /**
     * Constructor for ProgramStatement used only for writing a binary machine
     * instruction with no source code to refer back to.  Originally supported
     * only NOP instruction (all zeroes), but extended in release 4.4 to support
     * all basic instructions.  This was required for the self-modifying code
     * feature.
     *
     * @param binaryStatement The 32-bit machine code.
     * @param textAddress     The Text Segment address in memory where the binary machine code for this statement
     *                        is stored.
     **/
    public ProgramStatement(int binaryStatement, int textAddress) {
        this.sourceProgram = null;
        this.binaryStatement = binaryStatement;
        this.textAddress = textAddress;
        this.originalTokenList = this.strippedTokenList = null;
        this.source = "";
        this.machineStatement = this.basicAssemblyStatement = null;
        BasicInstruction instr = Globals.instructionSet.findByBinaryCode(binaryStatement);
        if (instr == null) {
            this.operands = null;
            this.numOperands = 0;
            this.instruction = null;
        } else {
            this.operands = new int[5];
            this.numOperands = 0;
            this.instruction = instr;
            String mask = instr.getOperationMask();
            BasicInstructionFormat format = instr.getInstructionFormat();
            if (format == BasicInstructionFormat.J_FORMAT) {
                this.operands[0] = this.readBinaryCode(mask, Instruction.operandMask[0], binaryStatement);
                this.operands[1] = fromJumpImmediate(this.readBinaryCode(mask, Instruction.operandMask[1], binaryStatement));
                this.numOperands = 2;
            } else if (format == BasicInstructionFormat.B_FORMAT) {
                this.operands[0] = this.readBinaryCode(mask, Instruction.operandMask[0], binaryStatement);
                this.operands[1] = this.readBinaryCode(mask, Instruction.operandMask[1], binaryStatement);
                this.operands[2] = fromBranchImmediate(this.readBinaryCode(mask, Instruction.operandMask[2], binaryStatement));
                this.numOperands = 3;
            } else {  // Everything else is normal
                for (int i = 0; i < 5; i++) {
                    if (mask.indexOf(Instruction.operandMask[i]) != -1) {
                        this.operands[i] = this.readBinaryCode(mask, Instruction.operandMask[i], binaryStatement);
                        this.numOperands++;
                    }
                }
            }
        }
        this.altered = false;
        this.basicStatementList = buildBasicStatementListFromBinaryCode(binaryStatement, instr, operands, numOperands);
    }

    public int compareTo(ProgramStatement obj1) {
        int addr1 = getAddress();
        int addr2 = obj1.getAddress();
        return (addr1 < 0 && addr2 >= 0 || addr1 >= 0 && addr2 < 0) ? addr2 : addr1 - addr2;
    }

    /////////////////////////////////////////////////////////////////////////////

    /**
     * Given specification of BasicInstruction for this operator, build the
     * corresponding assembly statement in basic assembly format (e.g. substituting
     * register numbers for register names, replacing labels by values).
     *
     * @param errors The list of assembly errors encountered so far.  May add to it here.
     **/
    public void buildBasicStatementFromBasicInstruction(ErrorList errors) {
        Token token = strippedTokenList.get(0);
        String basicStatementElement = token.getValue() + " ";

        String basic = basicStatementElement;
        basicStatementList.addString(basicStatementElement); // the operator
        TokenTypes tokenType, nextTokenType;
        String tokenValue;
        int registerNumber;
        this.numOperands = 0;
        for (int i = 1; i < strippedTokenList.size(); i++) {
            token = strippedTokenList.get(i);
            tokenType = token.getType();
            tokenValue = token.getValue();
            if (tokenType == TokenTypes.REGISTER_NUMBER) {
                basicStatementElement = tokenValue;
                basic += basicStatementElement;
                basicStatementList.addString(basicStatementElement);
                try {
                    registerNumber = RegisterFile.getRegister(tokenValue).getNumber();
                } catch (Exception e) {
                    // should never happen; should be caught before now...
                    errors.add(new ErrorMessage(this.sourceProgram, token.getSourceLine(), token.getStartPos(), "invalid register name"));
                    return;
                }
                this.operands[this.numOperands++] = registerNumber;
            } else if (tokenType == TokenTypes.REGISTER_NAME) {
                registerNumber = RegisterFile.getRegister(tokenValue).getNumber();
                basicStatementElement = "x" + registerNumber;
                basic += basicStatementElement;
                basicStatementList.addString(basicStatementElement);
                if (registerNumber < 0) {
                    // should never happen; should be caught before now...
                    errors.add(new ErrorMessage(this.sourceProgram, token.getSourceLine(), token.getStartPos(), "invalid register name"));
                    return;
                }
                this.operands[this.numOperands++] = registerNumber;
            } else if (tokenType == TokenTypes.CSR_NAME) {
                // Little bit of a hack because CSRFile doesn't supoprt getRegister(strinug)
                Register[] regs = ControlAndStatusRegisterFile.getRegisters();
                registerNumber = -1;
                for(Register r : regs){
                    if (r.getName().equals(tokenValue)){
                        registerNumber = r.getNumber();
                        break;
                    }
                }
                if (registerNumber < 0) {
                    // should never happen; should be caught before now...
                    errors.add(new ErrorMessage(this.sourceProgram, token.getSourceLine(), token.getStartPos(), "invalid CSR name"));
                    return;
                }
                basic += registerNumber;
                basicStatementList.addString(""+registerNumber);
                this.operands[this.numOperands++] = registerNumber;
            } else if (tokenType == TokenTypes.FP_REGISTER_NAME) {
                registerNumber = FloatingPointRegisterFile.getRegister(tokenValue).getNumber();
                basicStatementElement = "f" + registerNumber;
                basic += basicStatementElement;
                basicStatementList.addString(basicStatementElement);
                if (registerNumber < 0) {
                    // should never happen; should be caught before now...
                    errors.add(new ErrorMessage(this.sourceProgram, token.getSourceLine(), token.getStartPos(), "invalid FPU register name"));
                    return;
                }
                this.operands[this.numOperands++] = registerNumber;
            } else if(tokenType == TokenTypes.ROUNDING_MODE){
                int rounding_mode = -1;
                if(tokenValue.equals("rne")){
                    rounding_mode = 0;
                }else if ( tokenValue.equals("rtz")){
                    rounding_mode = 1;
                }else if (tokenValue.equals("rdn")) {
                    rounding_mode = 2;
                } else if (tokenValue.equals("rup")) {
                    rounding_mode = 3;
                } else if (tokenValue.equals("rmm")) {
                    rounding_mode = 4;
                } else if (tokenValue.equals("dyn")){
                    rounding_mode = 7;
                }
                if (rounding_mode == -1){
                    errors.add(new ErrorMessage(this.sourceProgram, token.getSourceLine(), token.getStartPos(), "invalid rounding mode"));
                    return;
                }
                basic += tokenValue;
                basicStatementList.addString(tokenValue);
                this.operands[this.numOperands++] = rounding_mode;
            } else if (tokenType == TokenTypes.IDENTIFIER) {

                int address = this.sourceProgram.getLocalSymbolTable().getAddressLocalOrGlobal(tokenValue, token.getSourceLine());
                if (address == SymbolTable.NOT_FOUND) { // symbol used without being defined
                    errors.add(new ErrorMessage(this.sourceProgram, token.getSourceLine(), token.getStartPos(),
                            "Symbol \"" + tokenValue + "\" not found in symbol table."));
                    return;
                }
                boolean absoluteAddress = true; // (used below)

                if (instruction instanceof BasicInstruction) {
                    BasicInstructionFormat format = ((BasicInstruction) instruction).getInstructionFormat();
                    if (format == BasicInstructionFormat.B_FORMAT) {
                        address -= this.textAddress;
                        if (address >= (1 << 12) || address < -(1 << 12)) {
                            // SPIM flags as warning, I'll flag as error b/c RARS text segment not long enough for it to be OK.
                            errors.add(new ErrorMessage(this.sourceProgram, this.sourceLine, 0,
                                    "Branch target word address beyond 12-bit range"));
                            return;
                        }
                        absoluteAddress = false;
                    } else if (format == BasicInstructionFormat.J_FORMAT) {
                        address -= this.textAddress;
                        if (address >= (1 << 20) || address < -(1 << 20)) {
                            errors.add(new ErrorMessage(this.sourceProgram, this.sourceLine, 0,
                                    "Jump target word address beyond 20-bit range"));
                            return;
                        }
                        absoluteAddress = false;
                    }
                }
                //////////////////////////////////////////////////////////////////////
                basic += address;
                if (absoluteAddress) { // record as address if absolute, value if relative
                    basicStatementList.addAddress(address);
                } else {
                    basicStatementList.addValue(address);
                }
                this.operands[this.numOperands++] = address;
            } else if (tokenType == TokenTypes.INTEGER_5 || tokenType == TokenTypes.INTEGER_6 || tokenType == TokenTypes.INTEGER_12 ||
                    tokenType == TokenTypes.INTEGER_12U || tokenType == TokenTypes.INTEGER_20 || tokenType == TokenTypes.INTEGER_32) {

                int tempNumeric = Binary.stringToInt(tokenValue);

                /***************************************************************************
                 *  MODIFICATION AND COMMENT, DPS 3-July-2008
                 *
                 * The modifications of January 2005 documented below are being rescinded.
                 * All hexadecimal immediate values are considered 32 bits in length and
                 * their classification as INTEGER_5, INTEGER_16, INTEGER_16U (new)
                 * or INTEGER_32 depends on their 32 bit value.  So 0xFFFF will be
                 * equivalent to 0x0000FFFF instead of 0xFFFFFFFF.  This change, along with
                 * the introduction of INTEGER_16U (adopted from Greg Gibeling of Berkeley),
                 * required extensive changes to instruction templates especially for
                 * pseudo-instructions.
                 *
                 * This modification also appears inbuildBasicStatementFromBasicInstruction()
                 * in rars.ProgramStatement.
                 *
                 *  ///// Begin modification 1/4/05 KENV   ///////////////////////////////////////////
                 *  // We have decided to interpret non-signed (no + or -) 16-bit hexadecimal immediate
                 *  // operands as signed values in the range -32768 to 32767. So 0xffff will represent
                 *  // -1, not 65535 (bit 15 as sign bit), 0x8000 will represent -32768 not 32768.
                 *  // NOTE: 32-bit hexadecimal immediate operands whose values fall into this range
                 *  // will be likewise affected, but they are used only in pseudo-instructions.  The
                 *  // code in ExtendedInstruction.java to split this number into upper 16 bits for "lui"
                 *  // and lower 16 bits for "ori" works with the original source code token, so it is
                 *  // not affected by this tweak.  32-bit immediates in data segment directives
                 *  // are also processed elsewhere so are not affected either.
                 *  ////////////////////////////////////////////////////////////////////////////////
                 *
                 *        if (tokenType != TokenTypes.INTEGER_16U) { // part of the Berkeley mod...
                 *           if ( Binary.isHex(tokenValue) &&
                 *             (tempNumeric >= 32768) &&
                 *             (tempNumeric <= 65535) )  // Range 0x8000 ... 0xffff
                 *           {
                 *              // Subtract the 0xffff bias, because strings in the
                 *              // range "0x8000" ... "0xffff" are used to represent
                 *              // 16-bit negative numbers, not positive numbers.
                 *              tempNumeric = tempNumeric - 65536;
                 *              // Note: no action needed for range 0xffff8000 ... 0xffffffff
                 *           }
                 *        }
                 **************************  END DPS 3-July-2008 COMMENTS *******************************/

                basic += tempNumeric;
                if (tokenType == TokenTypes.INTEGER_5) {
                    basicStatementList.addShortValue(tempNumeric);
                } else {
                    basicStatementList.addValue(tempNumeric);
                }
                this.operands[this.numOperands++] = tempNumeric;
                ///// End modification 1/7/05 KENV   ///////////////////////////////////////////
            } else {
                basicStatementElement = tokenValue;
                basic += basicStatementElement;
                basicStatementList.addString(basicStatementElement);
            }
            // add separator if not at end of token list AND neither current nor 
            // next token is a parenthesis
            if ((i < strippedTokenList.size() - 1)) {
                nextTokenType = strippedTokenList.get(i + 1).getType();
                if (tokenType != TokenTypes.LEFT_PAREN && tokenType != TokenTypes.RIGHT_PAREN &&
                        nextTokenType != TokenTypes.LEFT_PAREN && nextTokenType != TokenTypes.RIGHT_PAREN) {
                    basicStatementElement = ",";
                    basic += basicStatementElement;
                    basicStatementList.addString(basicStatementElement);
                }
            }
        }
        this.basicAssemblyStatement = basic;
    } //buildBasicStatementFromBasicInstruction()


    /////////////////////////////////////////////////////////////////////////////

    /**
     * Given the current statement in Basic Assembly format (see above), build the
     * 32-bit binary machine code statement.
     *
     * @param errors The list of assembly errors encountered so far.  May add to it here.
     **/
    public void buildMachineStatementFromBasicStatement(ErrorList errors) {
        if (!(instruction instanceof BasicInstruction)) {
            // This means the pseudo-instruction expansion generated another
            // pseudo-instruction (expansion must be to all basic instructions).
            // This is an error on the part of the pseudo-instruction author.
            errors.add(new ErrorMessage(this.sourceProgram, this.sourceLine, 0,
                    "INTERNAL ERROR: pseudo-instruction expansion contained a pseudo-instruction"));
            return;
        }

        //mask indicates bit positions for 'f'irst, 's'econd, 't'hird operand
        this.machineStatement = ((BasicInstruction) instruction).getOperationMask();
        BasicInstructionFormat format = ((BasicInstruction) instruction).getInstructionFormat();

        if (format == BasicInstructionFormat.J_FORMAT) {
            this.insertBinaryCode(this.operands[0], Instruction.operandMask[0], errors);
            this.insertBinaryCode(toJumpImmediate(this.operands[1]), Instruction.operandMask[1], errors);
        } else if (format == BasicInstructionFormat.B_FORMAT) {
            this.insertBinaryCode(this.operands[0], Instruction.operandMask[0], errors);
            this.insertBinaryCode(this.operands[1], Instruction.operandMask[1], errors);
            this.insertBinaryCode(toBranchImmediate(this.operands[2]), Instruction.operandMask[2], errors);
        } else {  // Everything else is normal
            for (int i = 0; i < this.numOperands; i++)
                this.insertBinaryCode(this.operands[i], Instruction.operandMask[i], errors);
        }
        this.binaryStatement = Binary.binaryStringToInt(this.machineStatement);
    }


    private int toJumpImmediate(int address) {
        // trying to produce immediate[20:1] where immediate = address[20|10:1|11|19:12]
        address = address >> 1; // Shift it down one byte
        return (address & (1 << 19)) |         // keep the top bit in the same place
                ((address & 0x3FF) << 9) |     // move address[10:1] to the right place
                ((address & (1 << 10)) >> 2) | // move address[11] to the right place
                ((address & 0x7F800) >> 11);   // move address[19:12] to the right place
    }

    private int fromJumpImmediate(int immediate) {
        // trying to produce address[20:0] where immediate = address[20|10:1|11|19:12]
        int tmp = ((immediate) & (1 << 19)) |    // keep the top bit in the same place
                ((immediate & 0x7FE00) >> 9) | // move address[10:1] to the right place
                ((immediate & (1 << 8)) << 2) |// move address[11] to the right place
                ((immediate & 0xFF) << 11);   // move address[19:12] to the right place
        return (tmp << 12) >> 11; // sign-extend and add extra 0
    }

    private int toBranchImmediate(int address) {
        // trying to produce imm[12:1] where immediate = address[12|10:1|11]
        address = address >> 1; // Shift it down one byte
        return (address & (1 << 11)) |         // keep the top bit in the same place
                ((address & 0x3FF) << 1) |     // move address[10:1] to the right place
                ((address & (1 << 10)) >> 10); // move address[11] to the right place
    }

    private int fromBranchImmediate(int immediate) {
        // trying to produce address[12:0] where immediate = address[12|10:1|11]
        int tmp = (immediate & (1 << 11)) |  // keep the top bit in the same place
                ((immediate & 0x7FE) >> 1) | // move address[10:1] to the right place
                ((immediate & 1) << 10);     // move address[11] to the right place
        return (tmp << 20) >> 19; // sign-extend and add extra 0
    }

    /**
     * Crude attempt at building String representation of this complex structure.
     *
     * @return A String representing the ProgramStatement.
     **/

    public String toString() {
        // a crude attempt at string formatting.  Where's C when you need it?
        String blanks = "                               ";
        String result = "[" + this.textAddress + "]";
        if (this.basicAssemblyStatement != null) {
            int firstSpace = this.basicAssemblyStatement.indexOf(" ");
            result += blanks.substring(0, 16 - result.length()) + this.basicAssemblyStatement.substring(0, firstSpace);
            result += blanks.substring(0, 24 - result.length()) + this.basicAssemblyStatement.substring(firstSpace + 1);
        } else {
            result += blanks.substring(0, 16 - result.length()) + "0x" + Integer.toString(this.binaryStatement, 16);
        }
        result += blanks.substring(0, 40 - result.length()) + ";  "; // this.source;
        if (operands != null) {
            for (int i = 0; i < this.numOperands; i++)
                // result += operands[i] + " ";
                result += Integer.toString(operands[i], 16) + " ";
        }
        if (this.machineStatement != null) {
            result += "[" + Binary.binaryStringToHexString(this.machineStatement) + "]";
            result += "  " + this.machineStatement.substring(0, 6) + "|" + this.machineStatement.substring(6, 11) + "|" +
                    this.machineStatement.substring(11, 16) + "|" + this.machineStatement.substring(16, 21) + "|" +
                    this.machineStatement.substring(21, 26) + "|" + this.machineStatement.substring(26, 32);
        }
        return result;
    } // toString()

    /**
     * Assigns given String to be Basic Assembly statement equivalent to this source line.
     *
     * @param statement A String containing equivalent Basic Assembly statement.
     **/

    public void setBasicAssemblyStatement(String statement) {
        basicAssemblyStatement = statement;
    }

    /**
     * Assigns given String to be binary machine code (32 characters, all of them 0 or 1)
     * equivalent to this source line.
     *
     * @param statement A String containing equivalent machine code.
     **/

    public void setMachineStatement(String statement) {
        machineStatement = statement;
    }

    /**
     * Assigns given int to be binary machine code equivalent to this source line.
     *
     * @param binaryCode An int containing equivalent binary machine code.
     **/

    public void setBinaryStatement(int binaryCode) {
        binaryStatement = binaryCode;
    }


    /**
     * associates RISCV source statement.  Used by assembler when generating basic
     * statements during macro expansion of extended statement.
     *
     * @param src a RISCV source statement.
     **/

    public void setSource(String src) {
        source = src;
    }


    /**
     * Produces RISCVprogram object representing the source file containing this statement.
     *
     * @return The RISCVprogram object.  May be null...
     **/
    public RISCVprogram getSourceProgram() {
        return sourceProgram;
    }

    /**
     * Produces String name of the source file containing this statement.
     *
     * @return The file name.
     **/
    public String getSourceFile() {
        return (sourceProgram == null) ? "" : sourceProgram.getFilename();
    }


    /**
     * Produces RISCV source statement.
     *
     * @return The RISCV source statement.
     **/

    public String getSource() {
        return source;
    }

    /**
     * Produces line number of RISCV source statement.
     *
     * @return The RISCV source statement line number.
     **/

    public int getSourceLine() {
        return sourceLine;
    }

    /**
     * Produces Basic Assembly statement for this RISCV source statement.
     * All numeric values are in decimal.
     *
     * @return The Basic Assembly statement.
     **/

    public String getBasicAssemblyStatement() {
        return basicAssemblyStatement;
    }

    /**
     * Produces printable Basic Assembly statement for this RISCV source
     * statement.  This is generated dynamically and any addresses and
     * values will be rendered in hex or decimal depending on the current
     * setting.
     *
     * @return The Basic Assembly statement.
     **/
    public String getPrintableBasicAssemblyStatement() {
        return basicStatementList.toString();
    }

    /**
     * Produces binary machine statement as 32 character string, all '0' and '1' chars.
     *
     * @return The String version of 32-bit binary machine code.
     **/

    public String getMachineStatement() {
        return machineStatement;
    }

    /**
     * Produces 32-bit binary machine statement as int.
     *
     * @return The int version of 32-bit binary machine code.
     **/
    public int getBinaryStatement() {
        return binaryStatement;
    }

    /**
     * Produces token list generated from original source statement.
     *
     * @return The TokenList of Token objects generated from original source.
     **/
    public TokenList getOriginalTokenList() {
        return originalTokenList;
    }

    /**
     * Produces token list stripped of all but operator and operand tokens.
     *
     * @return The TokenList of Token objects generated by stripping original list of all
     * except operator and operand tokens.
     **/
    public TokenList getStrippedTokenList() {
        return strippedTokenList;
    }

    /**
     * Produces Instruction object corresponding to this statement's operator.
     *
     * @return The Instruction that matches the operator used in this statement.
     **/
    public Instruction getInstruction() {
        return instruction;
    }

    /**
     * Produces Text Segment address where the binary machine statement is stored.
     *
     * @return address in Text Segment of this binary machine statement.
     **/
    public int getAddress() {
        return textAddress;
    }

    /**
     * Produces int array of operand values for this statement.
     *
     * @return int array of operand values (if any) required by this statement's operator.
     **/
    public int[] getOperands() {
        return operands;
    }

    /**
     * Produces operand value from given array position (first operand is position 0).
     *
     * @param i Operand position in array (first operand is position 0).
     * @return Operand value at given operand array position.  If < 0 or >= numOperands, it returns -1.
     **/
    public int getOperand(int i) {
        if (i >= 0 && i < this.numOperands) {
            return operands[i];
        } else {
            return -1;
        }
    }

    /**
     * Reads an operand from a binary statement according to a mask and format
     * <p>
     * i.e.
     * <pre>
     * 0b01001 == readBinaryCode("ttttttttttttttsssss010fffff1101001",'f',
     *                          0b0101000001000001000010010011101001)</pre>
     *
     * @param format          the format of the full binary statement (all operands present)
     * @param mask            the value (f,s, or t) to mask out
     * @param binaryStatement the binary statement to read from
     * @return the bits read pushed to the right
     */
    private int readBinaryCode(String format, char mask, int binaryStatement) {
        int out = 0;
        for (int i = 0; i < 32; i++) {
            if (format.charAt(i) == mask) {
                // if the mask says to read, shift the output left and add substitute bit i
                out = (out << 1) | ((binaryStatement >> (31 - i)) & 1);
            }
        }
        return out;
    }

    /**
     * Given operand (register or integer) and mask character ('f', 's', or 't'),
     * generate the correct sequence of bits and replace the mask with them.
     *
     * @param value  the value to be masked in (will be converted to binary)
     * @param mask   the value (f,s, or t) to mask out
     * @param errors error list to append errors to in the event of unrecoverable errors
     */
    private void insertBinaryCode(int value, char mask, ErrorList errors) {
        StringBuilder state = new StringBuilder(this.machineStatement);


        // Just counts the number of occurrences of the mask in machineStatement.
        // This could be done with a method from StringUtils, but I didn't think
        // bringing in another dependency was worth it.
        int length = 0;
        for (int i = 0; i < state.length(); i++) {
            if (state.charAt(i) == mask) length++;
        }

        // should NEVER occur
        // if it does, then one of the BasicInstructions is malformed
        if (length == 0) {
            errors.add(new ErrorMessage(this.sourceProgram, this.sourceLine, 0,
                    "INTERNAL ERROR: mismatch in number of operands in statement vs mask"));
            return;
        }

        // Replace the mask bit for bit with the binary version of the value
        // The old version of this function assumed that the mask was continuous
        String bitString = Binary.intToBinaryString(value, length);
        int valueIndex = 0;
        for (int i = 0; i < state.length(); i++) {
            if (state.charAt(i) == mask) {
                state.setCharAt(i, bitString.charAt(valueIndex));
                valueIndex++;
            }
        }

        this.machineStatement = state.toString();
    }


    //////////////////////////////////////////////////////////////////////////////
   /*
    *   Given a model BasicInstruction and the assembled (not source) operand array for a statement, 
    *   this method will construct the corresponding basic instruction list.  This method is
    *   used by the constructor that is given only the int address and binary code.  It is not
    *   intended to be used when source code is available.  DPS 11-July-2013
    */
    private BasicStatementList buildBasicStatementListFromBinaryCode(int binary, BasicInstruction instr, int[] operands, int numOperands) {
        BasicStatementList statementList = new BasicStatementList();
        int tokenListCounter = 1;  // index 0 is operator; operands start at index 1
        if (instr == null) {
            statementList.addString(invalidOperator);
            return statementList;
        } else {
            statementList.addString(instr.getName() + " ");
        }
        for (int i = 0; i < numOperands; i++) {
            // add separator if not at end of token list AND neither current nor 
            // next token is a parenthesis
            if (tokenListCounter > 1 && tokenListCounter < instr.getTokenList().size()) {
                TokenTypes thisTokenType = instr.getTokenList().get(tokenListCounter).getType();
                if (thisTokenType != TokenTypes.LEFT_PAREN && thisTokenType != TokenTypes.RIGHT_PAREN) {
                    statementList.addString(",");
                }
            }
            boolean notOperand = true;
            while (notOperand && tokenListCounter < instr.getTokenList().size()) {
                TokenTypes tokenType = instr.getTokenList().get(tokenListCounter).getType();
                if (tokenType.equals(TokenTypes.LEFT_PAREN)) {
                    statementList.addString("(");
                } else if (tokenType.equals(TokenTypes.RIGHT_PAREN)) {
                    statementList.addString(")");
                } else if (tokenType.toString().contains("REGISTER")) {
                    String marker = (tokenType.toString().contains("FP_REGISTER")) ? "f" : "x";
                    statementList.addString(marker + operands[i]);
                    notOperand = false;
                } else if (tokenType.equals(TokenTypes.INTEGER_12)) {
                    statementList.addValue((operands[i]<<20)>>20);
                    notOperand = false;
                } else if(tokenType.equals(TokenTypes.ROUNDING_MODE)) {
                    String[] modes = new String[]{"rne","rtz","rdn","rup","rmm","invalid","invalid","dyn"};
                    String value = "invalid";
                    if(operands[i] >=0 && operands[i] < 8){
                        value = modes[operands[i]];
                    }
                    statementList.addString(value);
                    notOperand = false;
                } else {
                    statementList.addValue(operands[i]);
                    notOperand = false;
                }
                tokenListCounter++;
            }
        }
        while (tokenListCounter < instr.getTokenList().size()) {
            TokenTypes tokenType = instr.getTokenList().get(tokenListCounter).getType();
            if (tokenType.equals(TokenTypes.LEFT_PAREN)) {
                statementList.addString("(");
            } else if (tokenType.equals(TokenTypes.RIGHT_PAREN)) {
                statementList.addString(")");
            }
            tokenListCounter++;
        }
        return statementList;
    } // buildBasicStatementListFromBinaryCode()


    //////////////////////////////////////////////////////////
    //
    //  Little class to represent basic statement as list
    //  of elements.  Each element is either a string, an
    //  address or a value.  The toString() method will
    //  return a string representation of the basic statement
    //  in which any addresses or values are rendered in the
    //  current number format (e.g. decimal or hex).
    //
    //  NOTE: Address operands on Branch instructions are
    //  considered values instead of addresses because they
    //  are relative to the PC.
    //
    //  DPS 29-July-2010

    private class BasicStatementList {

        private ArrayList<ListElement> list;

        BasicStatementList() {
            list = new ArrayList<>();
        }

        void addString(String string) {
            list.add(new ListElement(0, string, 0));
        }

        void addAddress(int address) {
            list.add(new ListElement(1, null, address));
        }

        void addValue(int value) {
            list.add(new ListElement(2, null, value));
        }
        void addShortValue(int value) {
            list.add(new ListElement(3, null, value));
        }

        public String toString() {
            int addressBase = (Globals.getSettings().getBooleanSetting(Settings.Bool.DISPLAY_ADDRESSES_IN_HEX)) ? NumberDisplayBaseChooser.HEXADECIMAL : NumberDisplayBaseChooser.DECIMAL;
            int valueBase = (Globals.getSettings().getBooleanSetting(Settings.Bool.DISPLAY_VALUES_IN_HEX)) ? NumberDisplayBaseChooser.HEXADECIMAL : NumberDisplayBaseChooser.DECIMAL;

            StringBuffer result = new StringBuffer();
            for (ListElement e : list) {
                switch (e.type) {
                    case 0:
                        result.append(e.sValue);
                        break;
                    case 1:
                        result.append(NumberDisplayBaseChooser.formatNumber(e.iValue, addressBase));
                        break;
                    case 2:
                        if (valueBase == NumberDisplayBaseChooser.HEXADECIMAL) {
                            result.append(rars.util.Binary.intToHexString(e.iValue)); // 13-July-2011, was: intToHalfHexString()
                        } else {
                            result.append(NumberDisplayBaseChooser.formatNumber(e.iValue, valueBase));
                        }
                        break;
                    case 3:
                        result.append(e.iValue);
                        break;
                    default:
                        break;
                }
            }
            return result.toString();
        }

        private class ListElement {
            int type;
            String sValue;
            int iValue;

            ListElement(int type, String sValue, int iValue) {
                this.type = type;
                this.sValue = sValue;
                this.iValue = iValue;
            }
        }
    }

}
