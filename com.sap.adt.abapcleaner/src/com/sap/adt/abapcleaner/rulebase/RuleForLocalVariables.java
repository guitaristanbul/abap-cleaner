package com.sap.adt.abapcleaner.rulebase;

import java.util.HashMap;

import com.sap.adt.abapcleaner.base.*;
import com.sap.adt.abapcleaner.parser.*;
import com.sap.adt.abapcleaner.programbase.*;
import com.sap.adt.abapcleaner.rulehelpers.*;

/**
 * base class for a Rule that requires information on local variable definitions and usages
 */
public abstract class RuleForLocalVariables extends Rule {
	protected abstract void executeOn(Code code, Command methodStart, LocalVariables localVariables, int releaseRestriction) throws UnexpectedSyntaxAfterChanges;
	
   private final static String[] declarationKeywords = new String[] {"CONSTANTS", "DATA", "FIELD-SYMBOLS", "STATICS"}; // "DATA(", "FINAL(" and "FIELD-SYMBOL(" do NOT belong here!
   private final static String[] constantsDeclarationKeywords = new String[] {"CONSTANTS"};

   protected static String getNameKey(String name) {
      return AbapCult.toUpper(name);
   }

	protected RuleForLocalVariables(Profile profile) {
		super(profile);
	}

	@Override
	public void executeOn(Code code, int releaseRestriction) throws UnexpectedSyntaxAfterChanges {
		if (code == null)
			throw new NullPointerException("code");

		CommentIdentifier commentIdentifier = new CommentIdentifier();

		HashMap<String, ClassInfo> classes = new HashMap<String, ClassInfo>();
		ClassInfo curClass = null;

		LocalVariables localVariables = new LocalVariables(this, null);
		
		Command command = code.firstCommand;
		Command methodStart = null;
		
		// do NOT initialize isInMethod  with "= (!code.hasMethodFunctionOrFormStart())", because if an incomplete part 
		// of the class declaration is processed, this will delete attributes!
		boolean isInClassDefinition = false;
		MethodVisibility methodVisibility = MethodVisibility.PUBLIC;
		boolean isInMethod = false; 
		boolean skipMethod = false;
		int blockLevel = 0;

		while (command != null) {
			// determine the current class to add to its method definitions, or later (in the implementation section) read from them 
			if (command.isClassDefinitionStart()) {
				isInClassDefinition = true;
				curClass = new ClassInfo(command.getDefinedName());
				classes.put(getNameKey(curClass.name), curClass);
			} else if (command.isClassImplementationStart()) {
				curClass = classes.get(getNameKey(command.getDefinedName()));
			} else if (command.isClassEnd()) {
				isInClassDefinition = false;
				curClass = null;
			}
			
			if (isInClassDefinition) {
				// determine method visibility section
				Token firstCode = command.getFirstCodeToken();
				if (firstCode == null) {
					// nothing to do
				} else if (firstCode.matchesOnSiblings(true, "PUBLIC", "SECTION")) {
					methodVisibility = MethodVisibility.PUBLIC;
				} else if (firstCode.matchesOnSiblings(true, "PROTECTED", "SECTION")) {
					methodVisibility = MethodVisibility.PROTECTED;
				} else if (firstCode.matchesOnSiblings(true, "PRIVATE", "SECTION")) {
					methodVisibility = MethodVisibility.PRIVATE;
				} else if (firstCode.isAnyKeyword("METHODS", "CLASS-METHODS")) {
					// add one or several method definitions
					addMethodDefinitions(curClass, methodVisibility, command);
				}
			}
			
			
			if (command.endsLocalVariableContext()) {
				if (!skipMethod)
					executeOn(code, methodStart, localVariables, releaseRestriction);
				methodStart = null;
				isInMethod = false;
				localVariables = new LocalVariables(this, null);
			}
			// do NOT attach the next section with "else if", since "AT SELECTION-SCREEN" may both end and start   
			// a "local variable context" at the same time
			if (command.startsLocalVariableContext()) {
				if (!skipMethod && !localVariables.isEmpty())
					executeOn(code, methodStart, localVariables, releaseRestriction);
				methodStart = command;
				isInMethod = true;
				MethodInfo curMethod = null;
				if (curClass != null) {
					curMethod = curClass.getMethod(command.getDefinedName()); // may be null
				}
				skipMethod = false;
				localVariables = new LocalVariables(this, curMethod);
			}
			
			// skip macro definitions (i.e. DEFINE ... END-OF-DEFINITION sections) 
			if (command.firstCodeTokenIsKeyword("DEFINE")) {
				command = command.getNextSibling();
				continue;
			}

			commandForErrorMsg = command;
			
			// read local variable declarations or usage from the current Command
			if (isInMethod && !skipMethod) {
				try {
					if (command.firstCodeTokenIsAnyKeyword(declarationKeywords))
						blockLevel = executeOnDeclarationCommand(command, methodStart, localVariables, command.firstCodeTokenIsAnyKeyword(constantsDeclarationKeywords), blockLevel);
					else if (command.isAsteriskCommentLine())
						executeOnCommentLine(command, localVariables, commentIdentifier);
					else if (!command.isAbap())
						executeOnNonAbapSection(command, localVariables);
					else
						executeOnOtherCommand(command, localVariables);

				} catch (UnexpectedSyntaxBeforeChanges ex) {
					ex.addToLog();
					skipMethod = true;
				}
			}

			command = command.getNext();
		}
		if (!localVariables.isEmpty()) {
			if (!skipMethod)
				executeOn(code, methodStart, localVariables, releaseRestriction);
			localVariables = new LocalVariables(this, null);
		}
	}

	private void addMethodDefinitions(ClassInfo curClass, MethodVisibility methodVisibility, Command command) {
		Token token = command.getFirstCodeToken();
		if (token == null)
			return;
		if (command.isSimpleChain())
			token = token.getNextCodeSibling();
		
		while (token != null && !token.isPeriod()) {
			// move to the method name 
			token = token.getNextCodeSibling();
			if (token == null || !token.isIdentifier())
				return;

			MethodInfo methodInfo = new MethodInfo(token.getText(), methodVisibility);
			curClass.addMethod(methodInfo);
			
			// read the parameters
			ParameterAccessType accessType = ParameterAccessType.IMPORTING;
			while (!token.isCommaOrPeriod()) {
				if (token.isKeyword()) {
					// determine parameter access type
					if (token.isKeyword("IMPORTING"))
						accessType = ParameterAccessType.IMPORTING;
					else if (token.isKeyword("EXPORTING"))
						accessType = ParameterAccessType.EXPORTING;
					else if (token.isKeyword("CHANGING"))
						accessType = ParameterAccessType.CHANGING;
					else if (token.isKeyword("RETURNING"))
						accessType = ParameterAccessType.RETURNING;

					if (token.isKeyword("REDEFINITION"))
						methodInfo.isRedefinition = true;

					// the parameter name is the Token before "TYPE" or "LIKE", possibly inside "VALUE(...)" or "REFERENCE(...)",
					// and possibly with an escape char ! before it (e.g. "IMPORTING !iv_number TYPE i")
					if (token.isAnyKeyword("TYPE", "LIKE")) {
						Token parameterName = token.getPrevCodeSibling();
						if (parameterName.closesLevel()) { // VALUE(...) or REFERENCE(...)
							parameterName = parameterName.getPrevSibling().getFirstChild(); 
						}
						String parameterNameText = parameterName.getText();
						if (parameterNameText.startsWith(ABAP.OPERAND_ESCAPE_CHAR_STRING))
							parameterNameText = parameterNameText.substring(ABAP.OPERAND_ESCAPE_CHAR_STRING.length());
						methodInfo.addParameter(new ParameterInfo(parameterNameText, accessType));
					}
				}
				token = token.getNextCodeSibling();
			}
			// in case the token is a comma, continue with the next method definition
		}
	}

	private int executeOnDeclarationCommand(Command command, Command methodStart, LocalVariables localVariables, boolean isConstantsDeclaration, int blockLevel) throws UnexpectedSyntaxBeforeChanges {
		Token token = command.getFirstCodeToken().getNextCodeToken();
		boolean isChain = token.isChainColon();
		if (isChain)
			token = token.getNextCodeToken();

		while (token != null) {
			boolean isBoundStructuredData = false;
			do {
				if (token.matchesOnSiblings(true, "END", "OF")) {
					--blockLevel;
					break;
					
				} else if (token.matchesOnSiblings(true, "BEGIN", "OF")) {
					++blockLevel;

					// in case of nested BEGIN OF, do not enter names of inner structures  
					if (blockLevel > 1)
						break;

					// move token to the name of the structure to add its declaration below
					token = token.getNextCodeSibling();
					token = token.getNextCodeSibling();
					isBoundStructuredData = true;
					
				} else if (blockLevel > 0) { 	
					break;
				}
				
				// add declaration
				String varName = token.getText();
				VariableInfo varInfo = localVariables.addDeclaration(token, false, isConstantsDeclaration, isBoundStructuredData);
	
				// if the declaration uses "LIKE ...", count that as a usage of that variable
				Token next = token.getNextCodeSibling();
				if (next != null && next.isKeyword("LIKE") && next.getNextCodeSibling() != null) {
					token = next.getNextCodeSibling();
					// skip any keywords before the identifier of the data object, e.g. LINE OF, RANGE OF, REF TO, 
					// { STANDARD | SORTED | HASHED } TABLE OF 
					while (token.isKeyword() && token.getNextCodeSibling() != null) {
						token = token.getNextCodeSibling();
					}
					// the Token may contain more than the object name, e.g. 'DATA ls_struc LIKE LINE OF lr_ref->lt_table.'
					String usedObjectName = LocalVariables.getObjectName(token.getText());
					localVariables.addUsageInLikeClause(token, usedObjectName, methodStart, varInfo);
				}
	
				// if the pragma ##NEEDED or the pseudo comment "#EC NEEDED is defined for this variable, add a "usage" 
				// to prevent it from being commented out or deleted
				if (isNeededPragmaOrPseudoCommentFound(token))
					localVariables.setNeeded(varName);
	
				if (!isChain)
					return blockLevel;
			} while (false);
			
			// move to the next identifier
			token = token.getLastTokenOnSiblings(true, TokenSearch.ASTERISK, ",|.");
			if (token != null)
				token = token.getNextCodeToken();
		}
		return blockLevel;
	}

	private void executeOnCommentLine(Command command, LocalVariables localVariables, CommentIdentifier commentIdentifier) {
		// ignore * comment lines if no commentIdentifier is passed
		if (commentIdentifier == null)
			return;

		// determine whether the comment line looks like ABAP code
		CommentIdentification identification = commentIdentifier.identifyComment(command.getFirstToken().getText(), false);
		if (identification.isTextual() || identification.words == null || identification.words.length == 0)
			return;

		// do not process this line if it is a declaration line
		String firstWord = identification.words[0];
		for (String declarationKeyword : declarationKeywords) {
			if (AbapCult.stringEquals(firstWord, declarationKeyword, true))
				return;
		}
		// treat all words as if they were a usage of a local variable
		boolean isClearCommand = AbapCult.stringEqualsAny(true, firstWord, "CLEAR", "CLEAR:");
		boolean isAddCommand = AbapCult.stringEquals(firstWord, "ADD", true);
		boolean isSubtractCommand = AbapCult.stringEquals(firstWord, "SUBTRACT", true);
		boolean isMultiplyOrDivideCommand = AbapCult.stringEqualsAny(true, firstWord, "MULTIPLY", "DIVIDE");
		// TODO: the next line does not work, because identification does not include "=" 
		boolean isAssignmentCommand = identification.words.length > 2 && ABAP.isAssignmentOperator(identification.words[1]);
		String assignedToVar = isAssignmentCommand ? identification.words[0] : null;

		String prevWordText = "";
		for (int i = 0; i < identification.words.length; ++i) {
			String word = identification.words[i];
			boolean isAssignment = isClearCommand || (isAssignmentCommand && i == 0) || (isAddCommand && AbapCult.stringEquals(prevWordText, "TO", true))
					|| (isSubtractCommand && AbapCult.stringEquals(prevWordText, "FROM", true)) || (isMultiplyOrDivideCommand && i == 1);
			boolean isUsageInSelfAssignment = !isAssignment && AbapCult.stringEquals(word, assignedToVar, true);
			localVariables.addUsage(command.getFirstToken(), word, isAssignment, isUsageInSelfAssignment, true, false);
			prevWordText = word;
		}
	}

	private void executeOnNonAbapSection(Command command, LocalVariables localVariables) throws UnexpectedSyntaxBeforeChanges {
		final String hostVarEscape = " :";
		
		if (localVariables.isEmpty())
			return;
		
		// try to find any (write or read) access to host variables, e.g. in a line like "WHERE bukrs = :lv_company_code"; 
		// note that for non-ABAP sections, each token may represent a full line
		// TODO: SQL comments are NOT yet considered here
		Token token = command.getFirstToken();
		while (token != null) {
			String text = token.getText();
			int colonPos = 0;
			while (colonPos < text.length()) {
				colonPos = text.indexOf(hostVarEscape, colonPos);
				if (colonPos < 0)
					break;
				colonPos += hostVarEscape.length();
				String varName = ABAP.readTillEndOfVariableName(text, colonPos, true);
				if (!StringUtil.isNullOrEmpty(varName)) {
					colonPos += varName.length();
					localVariables.addUsage(token, varName);
				}
			}
			
			token = token.getNext();
		}
	}
	
	private void executeOnOtherCommand(Command command, LocalVariables localVariables) throws UnexpectedSyntaxBeforeChanges {
		Token firstCode = command.getFirstCodeToken();
		if (firstCode == null)
			return;
		boolean isAssignmentCommand = command.isAssignment();

		// determine the receiving variable of an assignment, e.g. "lv_receiver" or "ls_receiver" in statements like 
		// - "lv_receiver = ..."
		// - "ls_receiver-component = ..."
		// - "lv_receiver+4(2) = ..."
		// note, however, that in "lo_instance->member = ...", lo_instance is NOT the receiver, because the assignment only happens to the memory 
		// which is *referenced* by lo_instance (so, in this case, lo_instance is *used*, NOT assigned to)  
		String assignedToVar = isAssignmentCommand ? LocalVariables.getObjectName(firstCode.getText()) : null;
		if (assignedToVar != null && !isAccessToVarMemory(firstCode.getText(), assignedToVar.length())) // e.g. in "var->member = ...", "var" is not assigned to, but *used*
			assignedToVar = null;

		Token token = firstCode;
		while (token != null) {
			if (token.isKeyword("REF")) {
				addRefConstructor(token, localVariables);
				
			} else if (token.opensInlineDeclaration()) {
				// process inline declaration
				token = token.getNext();
				localVariables.addDeclaration(token, true, false, false);

				// if the pragma ##NEEDED is defined for this variable, add a "usage" to prevent it from being commented out or deleted
				if (isNeededPragmaOrPseudoCommentFound(token))
					localVariables.setNeeded(token.getText());

				// determine whether a field-symbol is assigned directly at its declaration position, using 
				// ASSIGN ... TO FIELD-SYMBOL(<...>) or LOOP AT ... ASSIGNING FIELD-SYMBOL(<...>) etc., 
				// or a data reference is created
				MemoryAccessType accessType = token.getMemoryAccessType();
				if (accessType == MemoryAccessType.ASSIGN_TO_FS_OR_DREF) {
					addAssignedFieldSymbolOrDataRef(token, localVariables);
					localVariables.addInlineDeclaration(token, token.getText());
				}
	
			} else if (token.isIdentifier()) {
				// process non-comment, non-declaration code
				
				// get the name of the used object, e.g. "struc" from "struc-component", "var" from "var->member" or "var+offset(length)" etc.
				String tokenText = token.getText();
				int readPos = 0; 

				// remove the @ used in SQL statements
				if (AbapCult.stringStartsWith(tokenText, "@"))
					++readPos;

				// reduce the identifier to its first part, i.e.
				// - get the structure variable in cases of "structure-component",
				// - get the identifier of the instance in cases of "instance_var->member_var",
				// - get the first part of a substring expression like lv_date+4(2)
				String objectName = ABAP.readTillEndOfVariableName(tokenText, readPos, true);
				readPos += objectName.length();
				
				// determine from the tokenText and the context whether this is an assignment
				boolean isAssignment = false;
				boolean writesToReferencedMemory = false; 
				boolean isFieldSymbol = ABAP.isFieldSymbol(objectName); 
				if (isFieldSymbol) {
					// any mentioning of a field symbol is a 'usage', except in "ASSIGN ... TO <...>", "... ASSIGNING <...>" and "UNASSIGN <...>" commands: 
					// even a write to the memory referenced by the field symbol is not a write to the field symbol itself, as the reference is unchanged
					MemoryAccessType accessType = token.getMemoryAccessType();
					isAssignment = (accessType == MemoryAccessType.ASSIGN_TO_FS_OR_DREF);
					if (isAssignment) {
						addAssignedFieldSymbolOrDataRef(token, localVariables);
					}
					writesToReferencedMemory = (accessType == MemoryAccessType.WRITE || accessType == MemoryAccessType.READ_WRITE);
					
				} else if (isAccessToVarMemory(tokenText, readPos)) {
					MemoryAccessType accessType = token.getMemoryAccessType();
					if (accessType == MemoryAccessType.ASSIGN_TO_FS_OR_DREF) {
						isAssignment = true;
						addAssignedFieldSymbolOrDataRef(token, localVariables);
					} else if (accessType == MemoryAccessType.WRITE) {
						isAssignment = true;
					} else if (accessType == MemoryAccessType.READ_WRITE) {
						// if the variable is used as an actual CHANGING parameter, add both a usage and an assignment (below)
						localVariables.addUsage(token, objectName);
						isAssignment = true;
					}
				}

				boolean isUsageInSelfAssignment = !isAssignment && !isFieldSymbol && AbapCult.stringEquals(objectName, assignedToVar, true);
				localVariables.addUsage(token, objectName, isAssignment, isUsageInSelfAssignment, false, writesToReferencedMemory);
				
				// in "var+offset(length)", "offset" may also be an identifier
				if (readPos < tokenText.length() && tokenText.charAt(readPos) == ABAP.SUBSTRING_OFFSET) {
					objectName = ABAP.readTillEndOfVariableName(tokenText, readPos + 1, false);
					if (!StringUtil.isNullOrEmpty(objectName)) {
						localVariables.addUsage(token, objectName);
						readPos += objectName.length();
					} else {
						readPos = tokenText.indexOf(ABAP.SUBSTRING_LENGTH_OPEN, readPos);
					}
				}
				
				// in "var+offset(length)", "length" may also be an identifier
				if (readPos > 0 && readPos < tokenText.length() && tokenText.charAt(readPos) == ABAP.SUBSTRING_LENGTH_OPEN) {
					objectName = ABAP.readTillEndOfVariableName(tokenText, readPos + 1, false);
					if (!StringUtil.isNullOrEmpty(objectName)) 
						localVariables.addUsage(token, objectName);
				}
			}
			token = token.getNextCodeToken();
		}
	}

	private boolean isAccessToVarMemory(String tokenText, int readPosAfterObjName) {
		if (readPosAfterObjName == tokenText.length()) {
			// tokenText contains the object name only
			return true;
			
		} else if (tokenText.charAt(readPosAfterObjName) == ABAP.SUBSTRING_OFFSET) {
			// tokenText defines a substring of object name, e.g. "lv_date+4(2)" - which is still an access to the memory represented by the variable 
			return true;
			
		} else if (tokenText.charAt(readPosAfterObjName) == ABAP.COMPONENT_SELECTOR && readPosAfterObjName + 1 < tokenText.length() 
				&& ABAP.isCharAllowedForVariableNames(tokenText.charAt(readPosAfterObjName + 1), true, false)) {
			// tokenText defines a component of object name, e.g. "struc-component" - which also is an access to the memory represented by the variable
			return true;
			
		} else {
			// in cases like "lo_instance->member_var", the assignment only happens to the memory which is *referenced* by lo_instance 
			// (so, in such a case, lo_instance is *used*, NOT assigned to)   
			return false;
		}
	}
	
	private boolean isNeededPragmaOrPseudoCommentFound(Token startToken) {
		// determine whether ##NEEDED or #EC NEEDED are found before the next comma or period 
		// (or #EC NEEDED within the comments following it),
		boolean commaOrPeriodFound = false;
		Token token = startToken;
		while (token != null) {
			if (token.isCommaOrPeriod()) {
				commaOrPeriodFound = true;
			} else if (commaOrPeriodFound && !token.isComment()) {
				// at this position, #EC NEEDED no more applies to the startToken  
				break;
			}
			// ##NEEDED belongs before the comma or period, while #EC NEEDED can be part of the comments following it 
			if (!commaOrPeriodFound && token.isPragma() && token.textEquals("##NEEDED"))
				return true;
			if (token.isComment() && token.textStartsWith(ABAP.PSEUDO_COMMENT_EC_PREFIX + "NEEDED"))
				return true;
			token = token.getNext();
		}

		// determine whether ##NEEDED or #EC NEEDED are found before the chain colon 
		// (or #EC NEEDED within the comments following it) - they then apply to all elements of the chain
		boolean chainColonFound = false;
		Command command = startToken.getParentCommand();
		if (command.containsChainColon()) {
			token = command.getFirstToken();
			while (token != null) {
				if (token.isChainColon()) {
					chainColonFound = true;
				} else if (chainColonFound && !token.isComment()) {
					// at this position, #EC NEEDED no more applies to the chain
					break;
				}
				if (!chainColonFound && token.isPragma() && token.textEquals("##NEEDED"))
					return true;
				if (token.isComment() && token.textStartsWith(ABAP.PSEUDO_COMMENT_EC_PREFIX + "NEEDED"))
					return true;
				token = token.getNext();
			}
		}
		return false;
	}

	private void addRefConstructor(Token refKeyword, LocalVariables localVariables) {
		// In cases like 'DATA(dref) = REF #( dobj )' or 'any_method( ir_data = REF #( dobj ) )', note down that 
		// a data reference is created to the memory area of the local variable; in such a case, the FinalVariableRule 
		// will never change DATA(dobj) to FINAL(dobj), because the data reference could change the memory anywhere. 
		Token dataType = refKeyword.getNextCodeSibling();
		if (dataType.getOpensLevel() && dataType.hasChildren()) {
			Token identifier = dataType.getFirstChild(); 
			VariableInfo varInfo = localVariables.getVariableInfo(identifier);
			if (varInfo != null) {
				varInfo.addReferenceByDataRef();
			}
		}
	}
	
	private void addAssignedFieldSymbolOrDataRef(Token fieldSymbolOrDataRef, LocalVariables localVariables) {
		// a) In cases like 'ASSIGN itab[ ... ] TO <fs>', notify itab about the assignment of a <fs>, so indirect  
		// write accesses to itab with '<fs>-component = 1' can later be identified as (potential) write accesses to itab.

		// b) In cases like 'GET REFERENCE OF dobj INTO dref', note down that a data reference points to the memory area
		// of the local variable; in such a case, the FinalVariableRule will never change DATA(dobj) to FINAL(dobj), 
		// because the data reference could be passed on and the data changed from any place. This is relevant for: 

		// Both a) and b) is only relevant in the following cases:
		// - ASSIGN ...itab... TO <fs>
		// - LOOP AT [GROUP] itab ... ASSIGNING <fs> / REFERENCE INTO dref
		// - READ TABLE itab ... ASSIGNING <fs> / REFERENCE INTO dref
		// - GET REFERENCE OF dobj INTO dref
		
		// By contrast, we do NOT have to consider cases where the itab itself is already in a write position, such as
		// - INSERT ... INTO [TABLE] itab ASSIGNING <fs> / REFERENCE INTO dref
		// - MODIFY [TABLE] itab ... ASSIGNING <fs> / REFERENCE INTO dref
		// - COLLECT ... INTO itab ASSIGNING <fs> / REFERENCE INTO dref

		// The REF #( ) constructor expression is handled in addRefConstructor()
		
		// determine the last keyword of ASSIGN / LOOP AT [GROUP] / READ TABLE / GET REFERENCE OF / dref = REF ...
		Token firstCode = fieldSymbolOrDataRef.getParentCommand().getFirstCodeToken();
		if (firstCode == null)
			return;
		Token lastKeyword = firstCode.getLastTokenOnSiblings(true, "ASSIGN");
		if (lastKeyword == null)
			lastKeyword = firstCode.getLastTokenOnSiblings(true, "LOOP", "AT", TokenSearch.makeOptional("GROUP"));
		if (lastKeyword == null)
			lastKeyword = firstCode.getLastTokenOnSiblings(true, "READ", "TABLE");
		if (lastKeyword == null)
			lastKeyword = firstCode.getLastTokenOnSiblings(true, "GET", "REFERENCE", "OF");
		if (lastKeyword == null)
			return;			

		// determine the identifier after the last keyword - this is the memory area that is being assigned or referenced
		Token identifier = lastKeyword.getNextCodeSibling();
		if (identifier == null || !identifier.isIdentifier())
			return;

		// get the VariableInfo of that identifier, if it is a local variable (in that case, it must already be known)
		VariableInfo varInfo = localVariables.getVariableInfo(identifier);
		if (varInfo == null)
			return;
		
		if (ABAP.isFieldSymbol(fieldSymbolOrDataRef.getText())) {
			// enter the field-symbol to the list of field-symbols that are assigned to a memory area of the local variable
			VariableInfo fieldSymbolInfo = localVariables.getVariableInfo(fieldSymbolOrDataRef.getText());
			varInfo.addAssignedFieldSymol(fieldSymbolInfo);

		} else {
			// note down that a data reference points to the memory area of the local variable
			varInfo.addReferenceByDataRef();
		}
	}
}