/*
 * Copyright (c) Joachim Ansorg, mail@ansorg-it.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ansorgit.plugins.bash.lang.parser.command;

import com.ansorgit.plugins.bash.lang.lexer.BashTokenTypes;
import com.ansorgit.plugins.bash.lang.parser.BashElementTypes;
import com.ansorgit.plugins.bash.lang.parser.BashPsiBuilder;
import com.ansorgit.plugins.bash.lang.parser.Parsing;
import com.ansorgit.plugins.bash.lang.parser.misc.ShellCommandParsing;
import com.ansorgit.plugins.bash.lang.parser.util.ParserUtil;
import com.intellij.lang.PsiBuilder;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.tree.TokenSet;

/**
 * Parsing function for commands.
 * <br>
 *
 * @author jansorg
 */
public class CommandParsingUtil implements BashTokenTypes, BashElementTypes {
    private final static TokenSet assignmentSeparators = TokenSet.create(LINE_FEED, SEMI, WHITESPACE);
    private final static TokenSet validWordTokens = TokenSet.create(ARITH_NUMBER);

    private CommandParsingUtil() {
    }

    public static boolean readCommandParams(final BashPsiBuilder builder) {
        return readCommandParams(builder, TokenSet.EMPTY);
    }

    /**
     * Reads a list of optional command params.
     *
     * @param builder
     * @param validExtraTokens
     * @return True if the list is either empty or parsed fine.
     */
    public static boolean readCommandParams(final BashPsiBuilder builder, TokenSet validExtraTokens) {
        boolean ok = true;

        while (!builder.eof() && ok) {
            if (Parsing.redirection.isRedirect(builder, true)) {
                ok = Parsing.redirection.parseList(builder, false, true);
            } else if (Parsing.word.isWordToken(builder, true)) {
                ok = Parsing.word.parseWord(builder, true);
            } else if (validExtraTokens.contains(builder.getTokenType())) {
                builder.advanceLexer();
                ok = true;
            } else {
                break;
            }
        }

        return ok;
    }

    public static boolean isAssignment(final BashPsiBuilder builder, Mode mode, boolean acceptArrayVars) {
        final IElementType tokenType = builder.getTokenType();

        switch (mode) {
            case SimpleMode:
                return (acceptArrayVars && ParserUtil.hasNextTokens(builder, false, ASSIGNMENT_WORD, LEFT_SQUARE))
                        || ParserUtil.isWordToken(tokenType)
                        || Parsing.word.isWordToken(builder)
                        || Parsing.var.isValid(builder);

            case LaxAssignmentMode:
                return tokenType == ASSIGNMENT_WORD
                        || ParserUtil.isWordToken(tokenType)
                        || Parsing.word.isWordToken(builder)
                        || Parsing.var.isValid(builder);

            default:
                return tokenType == ASSIGNMENT_WORD || (builder.isEvalMode() && ParserUtil.hasNextTokens(builder, false, VARIABLE, EQ));
        }
    }

    public static boolean readOptionalAssignmentOrRedirects(BashPsiBuilder builder, Mode asssignmentMode, boolean markAsVarDef, boolean acceptArrayVars) {
        boolean ok = true;

        while (ok && isAssignmentOrRedirect(builder, asssignmentMode, acceptArrayVars)) {
            ok = readAssignmentsAndRedirects(builder, markAsVarDef, asssignmentMode, acceptArrayVars);
        }

        return ok;
    }

    public static boolean isAssignmentOrRedirect(BashPsiBuilder builder, Mode assignmentMode, boolean acceptArrayVars) {
        return isAssignment(builder, assignmentMode, acceptArrayVars) || Parsing.redirection.isRedirect(builder, true);
    }

    /**
     * Reads an optional list of assignments and redirects which are before a command.
     *
     * @param builder         The current builder
     * @param markAsVarDef    Mark as a variable definition
     * @param mode
     * @param acceptArrayVars
     * @return
     */
    public static boolean readAssignmentsAndRedirects(final BashPsiBuilder builder, boolean markAsVarDef, Mode mode, boolean acceptArrayVars) {
        boolean ok = false;

        do {
            if (isAssignment(builder, mode, acceptArrayVars)) {
                ok = readAssignment(builder, mode, markAsVarDef, acceptArrayVars);
            } else if (Parsing.redirection.isRedirect(builder, true)) {
                ok = Parsing.redirection.parseSingleRedirect(builder, true);
            } else if (mode == Mode.LaxAssignmentMode && Parsing.word.isWordToken(builder)) {
                ok = Parsing.word.parseWord(builder);
            } else {
                break;
            }
        } while (ok && !builder.eof());

        return ok;
    }

    /**
     * Reads a single assignment
     *
     * @param builder         Provides the tokens
     * @param mode            Set to true if a variable assignment with "declare" is being processed right now.
     * @param markAsVarDef    True if the assignments should be marked with a psi marker as such.
     * @param acceptArrayVars
     * @return True if the assignment has been read successfully.
     */
    public static boolean readAssignment(BashPsiBuilder builder, Mode mode, boolean markAsVarDef, boolean acceptArrayVars) {
        final PsiBuilder.Marker assignment = builder.mark();

        switch (mode) {
            case SimpleMode:
                if (acceptArrayVars && builder.getTokenType() == ASSIGNMENT_WORD) {
                    break;
                }
                if (!Parsing.word.parseWord(builder)) {
                    assignment.drop();
                    return false;
                }
                break;

            case LaxAssignmentMode:
                if (builder.getTokenType() == ASSIGNMENT_WORD) {
                    builder.advanceLexer();
                } else if (Parsing.var.isValid(builder)) {
                    if (!Parsing.var.parse(builder)) {
                        assignment.drop();
                        return false;
                    }
                } else if (!Parsing.word.parseWord(builder, false, BashTokenTypes.EQ_SET, TokenSet.EMPTY)) {
                    assignment.drop();
                    return false;
                }
                break;

            case StrictAssignmentMode: {
                if (builder.isEvalMode() && ParserUtil.hasNextTokens(builder, false, VARIABLE, EQ)) {
                    //assignment with variable on the left
                    markAsVarDef = false;
                    if (!Parsing.var.parse(builder)) {
                        assignment.drop();
                        return false;
                    }

                    break;
                }

                final IElementType nextToken = ParserUtil.getTokenAndAdvance(builder);
                if (nextToken != ASSIGNMENT_WORD) {
                    ParserUtil.error(assignment, "parser.unexpected.token");
                    return false;
                }
                break;
            }

            default:
                assignment.drop();
                throw new IllegalStateException("Invalid parsing mode found");
        }

        if (mode == Mode.SimpleMode && acceptArrayVars && builder.getTokenType() == ASSIGNMENT_WORD) {
            //the accept array vars is only evaluated in simple mode, e.g. simple variable use
            //the other modes parse the array index with assignment following later on
            builder.advanceLexer();

            //if it has the [] marker
            boolean hasArrayIndex = readArrayIndex(builder, assignment);
            if (!hasArrayIndex) {
                //error parsing the array index marker, if it was present
                return false;
            }
        }

        if (mode != Mode.SimpleMode) {
            if (!readArrayIndex(builder, assignment)) {
                //error parsing the array index marker, if it was present
                return false;
            }

            //here the next token should be the EQ token, i.e. after the element reference part
            final IElementType nextToken = builder.getTokenType(true);
            boolean hasAssignment = nextToken == EQ || nextToken == ADD_EQ;
            if (!hasAssignment && mode == Mode.StrictAssignmentMode) {
                ParserUtil.error(assignment, "parser.unexpected.token");
                return false;
            }

            if (hasAssignment) {
                builder.advanceLexer();
            }

            // now parse the assignment if it's available
            // we've already checked if the assignment is mandatory
            if (hasAssignment) {
                if (builder.getTokenType(true) == LEFT_PAREN) {
                    //assignment list for an array
                    final boolean ok = parseAssignmentList(builder);
                    if (!ok) {
                        ParserUtil.error(builder, "parser.unexpected.token");
                        assignment.drop();
                        return false;
                    }
                }

                final IElementType token = builder.getTokenType(true);
                final boolean isEndToken = assignmentSeparators.contains(token);
                if (token != null && !isEndToken) {
                    if (!Parsing.word.parseWord(builder, true, TokenSet.EMPTY, validWordTokens)) {
                        ParserUtil.error(builder, "parser.unexpected.token");
                        assignment.drop();
                        return false;
                    }
                }
            }
        }

        if (markAsVarDef) {
            assignment.done(VAR_DEF_ELEMENT);
        } else {
            assignment.drop();
        }

        return true;
    }

    /**
     * Parses an assignment list like a=(1 2 3)
     * Grammar (selfmade):
     * assignment_list ::= "(" array_assignment {" " {array_assignment}* ")"
     * <br>
     * array_assignment ::=
     * WORD_EXPR
     * |    STRING
     * |    STRING2
     * |   "[ arith_expression "]"=(WORD|STRING|STRING2)
     *
     * @param builder
     * @return
     */
    public static boolean parseAssignmentList(BashPsiBuilder builder) {
        IElementType first = builder.getTokenType();
        if (first != LEFT_PAREN) {
            builder.advanceLexer(); //make sure that at lease the first token is read   
            return false;
        }

        PsiBuilder.Marker marker = builder.mark();
        builder.advanceLexer(); //the left paren token

        while (!builder.eof() && (builder.getTokenType(true) != RIGHT_PAREN)) {
            //optional newlines at the beginning
            builder.readOptionalNewlines();

            if (builder.getTokenType() == LEFT_SQUARE) {
                //array value assignment to specific position
                boolean ok = ShellCommandParsing.arithmeticParser.parse(builder, LEFT_SQUARE, RIGHT_SQUARE);
                if (!ok) {
                    marker.drop();
                    return false;
                }

                //now we expect an equal sign
                final IElementType eqToken = ParserUtil.getTokenAndAdvance(builder);
                if (eqToken != EQ) {
                    marker.drop();
                    return false;
                }

                //continued below
            }

            if (Parsing.word.isWordToken(builder)) {
                final boolean ok = Parsing.word.parseWord(builder, true);

                if (!ok) {
                    marker.drop();
                    return false;
                }
            }

            //optional newlines after the comma
            boolean hadNewlines = builder.readOptionalNewlines(-1, true);

            //whitespace tokens separate the array assignment values
            //if the next token is not whitespace, we break the loop, cause we're at the last element
            if (!hadNewlines && builder.getTokenType(true) != WHITESPACE) {
                break;
            }

            //the current RAW token is whitespace, but the non-raw token is already the next,
            // i.e. the closing bracket or the start of the next value
            //don't: builder.advanceLexer();
        }

        if (!(ParserUtil.getTokenAndAdvance(builder) == RIGHT_PAREN)) {
            marker.drop();
            return false;
        }

        marker.done(VAR_ASSIGNMENT_LIST);
        return true;
    }

    private static boolean readArrayIndex(BashPsiBuilder builder, PsiBuilder.Marker assignment) {
        if (builder.getTokenType() == LEFT_SQUARE) {
            //this is an array assignment, e.g. a[1]=x
            //parse the arithmetic expression in the array assignment square brackets
            boolean valid = ShellCommandParsing.arithmeticParser.parse(builder, LEFT_SQUARE, RIGHT_SQUARE);
            if (!valid) {
                ParserUtil.error(builder, "parser.unexpected.token");
                assignment.drop();
                return false;
            }
        }

        return true;
    }

    public enum Mode {
        /**
         * Only accept an ASSIGNENT_WORD or ARRAY_ASSIGNMENT_WORD in front .
         * The =value part is mandatory.
         */
        StrictAssignmentMode,
        /**
         * Optional =value part and allows simple word tokens and variable names in front
         */
        LaxAssignmentMode,
        /**
         * Only single word tokens are valid, used for read commands.
         */
        SimpleMode
    }

}
