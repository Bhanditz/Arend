package org.arend.term.abs;

import org.arend.naming.reference.Referable;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.math.BigInteger;
import java.util.Collection;

public interface AbstractExpressionVisitor<P, R> {
  boolean visitErrors();
  default boolean reportError(Abstract.ErrorData errorData) {
    return errorData != null;
  }

  R visitReference(@Nullable Object data, @Nonnull Referable referent, @Nullable Abstract.LevelExpression level1, @Nullable Abstract.LevelExpression level2, @Nullable Abstract.ErrorData errorData, P params);
  R visitReference(@Nullable Object data, @Nonnull Referable referent, int lp, int lh, @Nullable Abstract.ErrorData errorData, P params);
  R visitThis(@Nullable Object data);
  R visitLam(@Nullable Object data, @Nonnull Collection<? extends Abstract.Parameter> parameters, /* @Nonnull */ @Nullable Abstract.Expression body, @Nullable Abstract.ErrorData errorData, P params);
  R visitPi(@Nullable Object data, @Nonnull Collection<? extends Abstract.Parameter> parameters, /* @Nonnull */ @Nullable Abstract.Expression codomain, @Nullable Abstract.ErrorData errorData, P params);
  R visitUniverse(@Nullable Object data, @Nullable Integer pLevelNum, @Nullable Integer hLevelNum, @Nullable Abstract.LevelExpression pLevel, @Nullable Abstract.LevelExpression hLevel, @Nullable Abstract.ErrorData errorData, P params);
  R visitInferHole(@Nullable Object data, @Nullable Abstract.ErrorData errorData, P params);
  R visitGoal(@Nullable Object data, @Nullable String name, @Nullable Abstract.Expression expression, @Nullable Abstract.ErrorData errorData, P params);
  R visitTuple(@Nullable Object data, @Nonnull Collection<? extends Abstract.Expression> fields, @Nullable Abstract.ErrorData errorData, P params);
  R visitSigma(@Nullable Object data, @Nonnull Collection<? extends Abstract.Parameter> parameters, @Nullable Abstract.ErrorData errorData, P params);
  R visitBinOpSequence(@Nullable Object data, @Nonnull Abstract.Expression left, @Nonnull Collection<? extends Abstract.BinOpSequenceElem> sequence, @Nullable Abstract.ErrorData errorData, P params);
  R visitCase(@Nullable Object data, @Nonnull Collection<? extends Abstract.CaseArgument> arguments, @Nullable Abstract.Expression resultType, @Nullable Abstract.Expression resultTypeLevel, @Nonnull Collection<? extends Abstract.FunctionClause> clauses, @Nullable Abstract.ErrorData errorData, P params);
  R visitFieldAccs(@Nullable Object data, @Nonnull Abstract.Expression expression, @Nonnull Collection<Integer> fieldAccs, @Nullable Abstract.ErrorData errorData, P params);
  R visitClassExt(@Nullable Object data, boolean isNew, /* @Nonnull */ @Nullable Abstract.Expression baseClass, @Nullable Collection<? extends Abstract.ClassFieldImpl> implementations, @Nonnull Collection<? extends Abstract.BinOpSequenceElem> sequence, @Nullable Abstract.ErrorData errorData, P params);
  R visitLet(@Nullable Object data, @Nonnull Collection<? extends Abstract.LetClause> clauses, /* @Nonnull */ @Nullable Abstract.Expression expression, @Nullable Abstract.ErrorData errorData, P params);
  R visitNumericLiteral(@Nullable Object data, @Nonnull BigInteger number, @Nullable Abstract.ErrorData errorData, P params);
  R visitTyped(@Nullable Object data, @Nonnull Abstract.Expression expr, @Nonnull Abstract.Expression type, @Nullable Abstract.ErrorData errorData, P params);
}
