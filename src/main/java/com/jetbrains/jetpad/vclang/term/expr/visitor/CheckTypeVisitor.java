package com.jetbrains.jetpad.vclang.term.expr.visitor;

import com.jetbrains.jetpad.vclang.term.Abstract;
import com.jetbrains.jetpad.vclang.term.Concrete;
import com.jetbrains.jetpad.vclang.term.Prelude;
import com.jetbrains.jetpad.vclang.term.definition.*;
import com.jetbrains.jetpad.vclang.term.error.*;
import com.jetbrains.jetpad.vclang.term.expr.*;
import com.jetbrains.jetpad.vclang.term.expr.arg.Argument;
import com.jetbrains.jetpad.vclang.term.expr.arg.NameArgument;
import com.jetbrains.jetpad.vclang.term.expr.arg.TelescopeArgument;
import com.jetbrains.jetpad.vclang.term.expr.arg.TypeArgument;

import java.util.*;

import static com.jetbrains.jetpad.vclang.term.error.ArgInferenceError.*;
import static com.jetbrains.jetpad.vclang.term.expr.Expression.compare;
import static com.jetbrains.jetpad.vclang.term.expr.ExpressionFactory.*;
import static com.jetbrains.jetpad.vclang.term.expr.ExpressionFactory.Error;
import static com.jetbrains.jetpad.vclang.term.expr.arg.Utils.numberOfVariables;

public class CheckTypeVisitor implements AbstractExpressionVisitor<Expression, CheckTypeVisitor.Result> {
  private final Map<String, Definition> myGlobalContext;
  private final List<Binding> myLocalContext;
  private final List<TypeCheckingError> myErrors;
  private final Side mySide;

  private static class Arg {
    boolean isExplicit;
    String name;
    Abstract.Expression expression;

    Arg(boolean isExplicit, String name, Abstract.Expression expression) {
      this.isExplicit = isExplicit;
      this.name = name;
      this.expression = expression;
    }
  }

  public static abstract class Result {
    public Expression expression;
  }

  public static class OKResult extends Result {
    public Expression type;
    public List<CompareVisitor.Equation> equations;

    public OKResult(Expression expression, Expression type, List<CompareVisitor.Equation> equations) {
      this.expression = expression;
      this.type = type;
      this.equations = equations;
    }
  }

  public static class InferErrorResult extends Result {
    public TypeCheckingError error;

    public InferErrorResult(InferHoleExpression hole, TypeCheckingError error) {
      expression = hole;
      this.error = error;
    }
  }

  public enum Side { LHS, RHS }

  public CheckTypeVisitor(Map<String, Definition> globalContext, List<Binding> localContext, List<TypeCheckingError> errors, Side side) {
    myGlobalContext = globalContext;
    myLocalContext = localContext;
    myErrors = errors;
    mySide = side;
  }

  private OKResult checkResult(Expression expectedType, OKResult result, Abstract.Expression expression) {
    if (result == null) return null;
    if (expectedType == null) {
      expression.setWellTyped(result.expression);
      return result;
    }
    Expression actualNorm = result.type.normalize(NormalizeVisitor.Mode.NF);
    Expression expectedNorm = expectedType.normalize(NormalizeVisitor.Mode.NF);
    result.equations = compare(expectedNorm, actualNorm, CompareVisitor.CMP.GEQ);
    if (result.equations == null) {
      TypeCheckingError error = new TypeMismatchError(expectedNorm, actualNorm, expression, getNames(myLocalContext));
      expression.setWellTyped(Error(result.expression, error));
      myErrors.add(error);
      return null;
    } else {
      expression.setWellTyped(result.expression);
      return result;
    }
  }

  private Result checkResultImplicit(Expression expectedType, OKResult result, Abstract.Expression expression) {
    if (result == null) return null;
    if (expectedType == null) {
      expression.setWellTyped(result.expression);
      return result;
    }

    if (result.expression instanceof DefCallExpression) {
      DefCallExpression defCall = (DefCallExpression) result.expression;
      if (defCall.getDefinition() instanceof Constructor && !((Constructor) defCall.getDefinition()).getDataType().getParameters().isEmpty()) {
        return typeCheckApps(expression, new ArrayList<Arg>(), expectedType, expression);
      }
    }

    Signature actualSignature = new Signature(result.type);
    Signature expectedSignature = new Signature(expectedType);
    if (actualSignature.getArguments().size() > expectedSignature.getArguments().size()) {
      return typeCheckApps(expression, new ArrayList<Arg>(), expectedType, expression);
    } else {
      return checkResult(expectedType, result, expression);
    }
  }

  private Result typeCheck(Abstract.Expression expr, Expression expectedType) {
    if (expr == null) {
      return null;
    } else {
      return expr.accept(this, expectedType);
    }
  }

  private int solveEquations(int size, Abstract.Expression[] argsImp, Result[] resultArgs, List<CompareVisitor.Equation> equations, List<CompareVisitor.Equation> resultEquations, Abstract.Expression fun) {
    int found = size;
    for (CompareVisitor.Equation equation : equations) {
      for (int i = 0; i < size; ++i) {
        if (resultArgs[i] instanceof InferErrorResult && resultArgs[i].expression == equation.hole) {
          if (!(argsImp[i] instanceof Abstract.InferHoleExpression)) {
            boolean isLess = compare(argsImp[i], (Expression) equation.expression, CompareVisitor.CMP.LEQ) != null;
            if (isLess || compare(argsImp[i], (Expression) equation.expression, CompareVisitor.CMP.GEQ) != null) {
              if (!isLess) {
                argsImp[i] = equation.expression;
              }
            } else {
              List<Expression> options = new ArrayList<>(2);
              options.add((Expression) argsImp[i]);
              options.add((Expression) equation.expression);
              for (int j = i + 1; j < size; ++j) {
                if (resultArgs[j] instanceof InferErrorResult && resultArgs[j].expression == equation.hole) {
                  options.add((Expression) equation.expression);
                }
              }
              myErrors.add(new InferedArgumentsMismatch(i, options, fun, getNames(myLocalContext)));
              return -1;
            }
          }

          argsImp[i] = equation.expression;
          found = i < found ? i : found;
          break;
        }
      }
      resultEquations.add(equation);
    }
    return found;
  }

  private boolean typeCheckArgs(Abstract.Expression[] argsImp, Result[] resultArgs, Signature signature, List<CompareVisitor.Equation> resultEquations, int startIndex, Abstract.Expression fun) {
    for (int i = startIndex; i < resultArgs.length; ++i) {
      if (resultArgs[i] instanceof OKResult) continue;

      if (argsImp[i] instanceof Abstract.InferHoleExpression) {
        resultArgs[i] = new InferErrorResult(new InferHoleExpression(), new ArgInferenceError(functionArg(i + 1), fun, getNames(myLocalContext)));
        continue;
      }

      List<Expression> substExprs = new ArrayList<>(i);
      for (int j = i - 1; j >= 0; --j) {
        substExprs.add(resultArgs[j].expression);
      }
      Expression type = signature.getArgument(i).getType().subst(substExprs, 0);

      resultArgs[i] = typeCheck(argsImp[i], type);
      if (resultArgs[i] == null) {
        for (int j = i + 1; j < resultArgs.length; ++j) {
          if (!(argsImp[j] instanceof Abstract.InferHoleExpression)) {
            typeCheck(argsImp[j], null);
          }
        }
        return false;
      }
      if (resultArgs[i] instanceof InferErrorResult) {
        continue;
      }

      OKResult okResult = (OKResult) resultArgs[i];
      if (okResult.equations == null) continue;
      int found = solveEquations(i, argsImp, resultArgs, okResult.equations, resultEquations, fun);
      if (found < 0) return false;
      if (found != i) {
        i = found - 1;
      }
    }
    return true;
  }

  private Result typeCheckApps(Abstract.Expression fun, List<Arg> args, Expression expectedType, Abstract.Expression expression) {
    Result function = typeCheck(fun, null);
    if (!(function instanceof OKResult)) {
      if (function instanceof InferErrorResult) {
        myErrors.add(((InferErrorResult) function).error);
      }
      for (Arg arg : args) {
        typeCheck(arg.expression, null);
      }
      return null;
    }
    OKResult okFunction = (OKResult) function;

    List<TypeArgument> parameters = new ArrayList<>();
    if (okFunction.expression instanceof DefCallExpression) {
      Definition def = ((DefCallExpression) okFunction.expression).getDefinition();
      if (def instanceof Constructor) {
        parameters = new ArrayList<>(((Constructor) def).getDataType().getParameters());
      }
    }
    int parametersNumber = numberOfVariables(parameters);

    Signature signature = new Signature(okFunction.type);
    if (!parameters.isEmpty()) {
      parameters.addAll(signature.getArguments());
      signature = new Signature(parameters, signature.getResultType());
    }
    Abstract.Expression[] argsImp = new Abstract.Expression[signature.getArguments().size()];
    for (int i = 0; i < parametersNumber; ++i) {
      argsImp[i] = new InferHoleExpression();
    }

    int i, j;
    for (i = parametersNumber, j = 0; i < signature.getArguments().size() && j < args.size(); ++i, ++j) {
      if (args.get(j).isExplicit == signature.getArgument(i).getExplicit()) {
        argsImp[i] = args.get(j).expression;
      } else
      if (args.get(j).isExplicit) {
        argsImp[i] = new InferHoleExpression();
        --j;
      } else {
        TypeCheckingError error = new TypeCheckingError("Unexpected implicit argument", args.get(j).expression, getNames(myLocalContext));
        args.get(j).expression.setWellTyped(Error(null, error));
        myErrors.add(error);
        for (Arg arg : args) {
          typeCheck(arg.expression, null);
        }
        return null;
      }
    }

    if (j < args.size()) {
      TypeCheckingError error = new TypeCheckingError("Function expects " + i + " arguments, but is applied to " + (i + args.size() - j), fun, getNames(myLocalContext));
      fun.setWellTyped(Error(okFunction.expression, error));
      myErrors.add(error);
      for (Arg arg : args) {
        typeCheck(arg.expression, null);
      }
      return null;
    }

    if (expectedType != null) {
      Signature expectedSignature = new Signature(expectedType);
      for (; i < signature.getArguments().size() - expectedSignature.getArguments().size(); ++i) {
        if (signature.getArgument(i).getExplicit()) {
          break;
        } else {
          argsImp[i] = new InferHoleExpression();
        }
      }
    }

    int argsNumber = i;
    Result[] resultArgs = new Result[argsNumber];
    List<CompareVisitor.Equation> resultEquations = new ArrayList<>();
    if (!typeCheckArgs(argsImp, resultArgs, signature, resultEquations, 0, fun)) {
      expression.setWellTyped(Error(null, myErrors.get(myErrors.size() - 1)));
      return null;
    }

    Expression resultType;
    if (signature.getArguments().size() == argsNumber) {
      resultType = signature.getResultType();
    } else {
      int size = signature.getArguments().size() - argsNumber;
      List<TypeArgument> rest = new ArrayList<>(size);
      for (i = 0; i < size; ++i) {
        rest.add(signature.getArgument(argsNumber + i));
      }
      resultType = Pi(rest, signature.getResultType());
    }
    List<Expression> substExprs = new ArrayList<>(argsNumber);
    for (i = argsNumber - 1; i >= 0; --i) {
      substExprs.add(resultArgs[i].expression);
    }
    resultType = resultType.subst(substExprs, 0);

    int argIndex = 0;
    for (i = 0; i < argsNumber; ++i) {
      if (!(resultArgs[i] instanceof OKResult)) {
        argIndex = i + 1;
        break;
      }
    }

    if (argIndex != 0) {
      if (expectedType != null && expectedType.accept(new FindHoleVisitor()) == null) {
        Expression expectedNorm = expectedType.normalize(NormalizeVisitor.Mode.NF);
        Expression actualNorm = resultType.normalize(NormalizeVisitor.Mode.NF);
        List<CompareVisitor.Equation> equations = compare(actualNorm, expectedNorm, CompareVisitor.CMP.LEQ);
        if (equations == null) {
          Expression resultExpr = okFunction.expression;
          for (i = parametersNumber; i < argsNumber; ++i) {
            resultExpr = App(resultExpr, resultArgs[i].expression, signature.getArgument(i).getExplicit());
          }

          TypeCheckingError error = new TypeMismatchError(expectedNorm, actualNorm, expression, getNames(myLocalContext));
          expression.setWellTyped(Error(resultExpr, error));
          myErrors.add(error);
          return null;
        }

        int found = solveEquations(argsNumber, argsImp, resultArgs, equations, resultEquations, fun);
        if (found < 0 || (found != argsNumber && !typeCheckArgs(argsImp, resultArgs, signature, resultEquations, found, fun))) {
          Expression resultExpr = okFunction.expression;
          for (i = parametersNumber; i < argsNumber; ++i) {
            resultExpr = App(resultExpr, resultArgs[i] == null ? new InferHoleExpression() : resultArgs[i].expression, signature.getArgument(i).getExplicit());
          }
          expression.setWellTyped(Error(resultExpr, myErrors.get(myErrors.size() - 1)));
          return null;
        }
      }

      argIndex = 0;
      for (i = 0; i < argsNumber; ++i) {
        if (!(resultArgs[i] instanceof OKResult)) {
          argIndex = i + 1;
          break;
        }
      }

      if (argIndex == 0) {
        if (signature.getArguments().size() == argsNumber) {
          resultType = signature.getResultType();
        } else {
          int size = signature.getArguments().size() - argsNumber;
          List<TypeArgument> rest = new ArrayList<>(size);
          for (i = 0; i < size; ++i) {
            rest.add(signature.getArgument(argsNumber + i));
          }
          resultType = Pi(rest, signature.getResultType());
        }
        substExprs = new ArrayList<>(argsNumber);
        for (i = argsNumber - 1; i >= 0; --i) {
          substExprs.add(resultArgs[i].expression);
        }
        resultType = resultType.subst(substExprs, 0);
      }
    }

    Expression resultExpr = okFunction.expression;
    for (i = parametersNumber; i < argsNumber; ++i) {
      resultExpr = App(resultExpr, resultArgs[i].expression, signature.getArgument(i).getExplicit());
    }

    if (argIndex == 0) {
      return checkResult(expectedType, new OKResult(resultExpr, resultType, resultEquations), expression);
    } else {
      TypeCheckingError error;
      if (argIndex > parametersNumber) {
        error = new ArgInferenceError(functionArg(argIndex - parametersNumber), fun, getNames(myLocalContext));
      } else {
        error = new ArgInferenceError(parameter(argIndex), DefCall(((Constructor) ((DefCallExpression) okFunction.expression).getDefinition()).getDataType()), getNames(myLocalContext));
      }
      expression.setWellTyped(Error(resultExpr, error));
      myErrors.add(error);
      return null;
    }
  }

  public OKResult checkType(Abstract.Expression expr, Expression expectedType) {
    if (expr == null) return null;
    Result result = expr.accept(this, expectedType);
    if (result == null) return null;
    if (result instanceof OKResult) return (OKResult) result;
    InferErrorResult errorResult = (InferErrorResult) result;
    myErrors.add(errorResult.error);
    return null;
  }

  private static Abstract.Expression getFunction(Abstract.Expression expr, List<Arg> args) {
    while (expr instanceof Abstract.AppExpression) {
      args.add(new Arg(((Abstract.AppExpression) expr).isExplicit(), null, ((Abstract.AppExpression) expr).getArgument()));
      expr = ((Abstract.AppExpression) expr).getFunction();
    }
    Collections.reverse(args);
    return expr;
  }

  @Override
  public Result visitApp(Abstract.AppExpression expr, Expression expectedType) {
    List<Arg> args = new ArrayList<>();
    return typeCheckApps(getFunction(expr, args), args, expectedType, expr);
  }

  @Override
  public Result visitDefCall(Abstract.DefCallExpression expr, Expression expectedType) {
    Definition def = myGlobalContext.get(expr.getDefinition().getName());
    if (def == null) {
      NotInScopeError error = new NotInScopeError(Var(expr.getDefinition().getName()), getNames(myLocalContext));
      expr.setWellTyped(Error(null, error));
      myErrors.add(error);
      return null;
    } else {
      return checkResultImplicit(expectedType, new OKResult(DefCall(def), def.getType(), null), expr);
    }
  }

  @Override
  public Result visitIndex(Abstract.IndexExpression expr, Expression expectedType) {
    assert expr.getIndex() < myLocalContext.size();
    Expression actualType = myLocalContext.get(myLocalContext.size() - 1 - expr.getIndex()).getType().liftIndex(0, expr.getIndex() + 1);
    return checkResultImplicit(expectedType, new OKResult(Index(expr.getIndex()), actualType, null), expr);
  }

  @Override
  public Result visitLam(Abstract.LamExpression expr, Expression expectedType) {
    List<CompareVisitor.Equation> resultEquations = new ArrayList<>();

    List<Arg> lambdaArgs = new ArrayList<>();
    for (Abstract.Argument arg : expr.getArguments()) {
      if (arg instanceof Abstract.NameArgument) {
        lambdaArgs.add(new Arg(arg.getExplicit(), ((Abstract.NameArgument) arg).getName(), null));
      } else {
        for (String name : ((Abstract.TelescopeArgument) arg).getNames()) {
          lambdaArgs.add(new Arg(arg.getExplicit(), name, ((Abstract.TelescopeArgument) arg).getType()));
        }
      }
    }

    List<TypeArgument> piArgs = new ArrayList<>();
    int actualNumberOfPiArgs;
    Expression resultType;
    if (expectedType == null) {
      for (Arg ignored : lambdaArgs) {
        piArgs.add(null);
      }
      actualNumberOfPiArgs = 0;
      resultType = null;
    } else {
      resultType = expectedType.splitAt(lambdaArgs.size(), piArgs);
      actualNumberOfPiArgs = piArgs.size();
      if (resultType instanceof Abstract.InferHoleExpression) {
        for (int i = piArgs.size(); i < lambdaArgs.size(); ++i) {
          piArgs.add(null);
        }
      }
    }

    if (piArgs.size() < lambdaArgs.size()) {
      TypeCheckingError error = new TypeCheckingError("Expected a function of " + piArgs.size() + " arguments, but the lambda has " + lambdaArgs.size(), expr, getNames(myLocalContext));
      expr.setWellTyped(Error(null, error));
      myErrors.add(error);
      return null;
    }

    List<TypeCheckingError> errors = new ArrayList<>(lambdaArgs.size());
    for (int i = 0; i < lambdaArgs.size(); ++i) {
      if (piArgs.get(i) == null && lambdaArgs.get(i).expression == null) {
        errors.add(new ArgInferenceError(lambdaArg(i + 1), expr, getNames(myLocalContext)));
        if (resultType instanceof InferHoleExpression) {
          TypeCheckingError error = new ArgInferenceError(lambdaArg(i + 1), expr, getNames(myLocalContext));
          expr.setWellTyped(Error(null, error));
          return new InferErrorResult((InferHoleExpression) resultType, error);
        }
      } else
      if (piArgs.get(i) != null && lambdaArgs.get(i).expression == null) {
        InferHoleExpression hole = piArgs.get(i).getType().accept(new FindHoleVisitor());
        if (hole != null) {
          if (!errors.isEmpty()) {
            break;
          } else {
            TypeCheckingError error = new ArgInferenceError(lambdaArg(i + 1), expr, getNames(myLocalContext));
            expr.setWellTyped(Error(null, error));
            return new InferErrorResult(hole, error);
          }
        }
      } else
      if (piArgs.get(i) != null && lambdaArgs.get(i).expression != null) {
        if (piArgs.get(i).getExplicit() != lambdaArgs.get(i).isExplicit) {
          errors.add(new TypeCheckingError((i + 1) + suffix(i + 1) + " argument of the lambda should be " + (piArgs.get(i).getExplicit() ? "explicit" : "implicit"), expr, getNames(myLocalContext)));
        }
      }
    }
    if (!errors.isEmpty()) {
      expr.setWellTyped(Error(null, errors.get(0)));
      myErrors.addAll(errors);
      return null;
    }

    List<TypeArgument> argumentTypes = new ArrayList<>(lambdaArgs.size());
    for (int i = 0; i < lambdaArgs.size(); ++i) {
      if (lambdaArgs.get(i).expression != null) {
        Result argResult = typeCheck(lambdaArgs.get(i).expression, Universe());
        if (!(argResult instanceof OKResult)) {
          while (i-- > 0) {
            myLocalContext.remove(myLocalContext.size() - 1);
          }
          return argResult;
        }
        OKResult okArgResult = (OKResult) argResult;
        if (okArgResult.equations != null) {
          for (CompareVisitor.Equation equation : okArgResult.equations) {
            try {
              if (equation.expression instanceof Expression) {
                resultEquations.add(new CompareVisitor.Equation(equation.hole, ((Expression) equation.expression).liftIndex(0, -i)));
              }
            } catch (LiftIndexVisitor.NegativeIndexException ignored) {
            }
          }
        }
        argumentTypes.add(Tele(lambdaArgs.get(i).isExplicit, vars(lambdaArgs.get(i).name), okArgResult.expression));

        if (piArgs.get(i) != null) {
          Expression argExpectedType = piArgs.get(i).getType().normalize(NormalizeVisitor.Mode.NF);
          Expression argActualType = argumentTypes.get(i).getType().normalize(NormalizeVisitor.Mode.NF);
          List<CompareVisitor.Equation> equations = compare(argExpectedType, argActualType, CompareVisitor.CMP.LEQ);
          if (equations == null) {
            errors.add(new TypeMismatchError(piArgs.get(i).getType(), lambdaArgs.get(i).expression, expr, getNames(myLocalContext)));
          } else {
            resultEquations.addAll(equations);
          }
        }
      } else {
        argumentTypes.add(Tele(piArgs.get(i).getExplicit(), vars(lambdaArgs.get(i).name), piArgs.get(i).getType()));
      }
      myLocalContext.add(new TypedBinding(lambdaArgs.get(i).name, argumentTypes.get(i).getType()));
    }

    Result bodyResult = typeCheck(expr.getBody(), resultType instanceof Abstract.InferHoleExpression ? null : resultType);

    for (int i = 0; i < lambdaArgs.size(); ++i) {
      myLocalContext.remove(myLocalContext.size() - 1);
    }

    if (!(bodyResult instanceof OKResult)) return bodyResult;
    OKResult okBodyResult = (OKResult) bodyResult;
    if (okBodyResult.equations != null) {
      for (CompareVisitor.Equation equation : okBodyResult.equations) {
        try {
          if (equation.expression instanceof Expression) {
            resultEquations.add(new CompareVisitor.Equation(equation.hole, ((Expression) equation.expression).liftIndex(0, -lambdaArgs.size())));
          }
        } catch (LiftIndexVisitor.NegativeIndexException ignored) {
        }
      }
    }

    if (resultType instanceof Abstract.InferHoleExpression) {
      Expression actualType = okBodyResult.type;
      if (lambdaArgs.size() > actualNumberOfPiArgs) {
        actualType = Pi(argumentTypes.subList(actualNumberOfPiArgs, lambdaArgs.size()), actualType);
      }
      try {
        resultEquations.add(new CompareVisitor.Equation((Abstract.InferHoleExpression) resultType, actualType.liftIndex(0, -actualNumberOfPiArgs)));
      } catch (LiftIndexVisitor.NegativeIndexException ignored) {
      }
    }

    List<Argument> resultLambdaArgs = new ArrayList<>(argumentTypes.size());
    for (TypeArgument argumentType : argumentTypes) {
      resultLambdaArgs.add(argumentType);
    }
    OKResult result = new OKResult(Lam(resultLambdaArgs, okBodyResult.expression), Pi(argumentTypes, okBodyResult.type), resultEquations);
    expr.setWellTyped(result.expression);
    return result;
  }

  @Override
  public Result visitPi(Abstract.PiExpression expr, Expression expectedType) {
    OKResult[] domainResults = new OKResult[expr.getArguments().size()];
    int numberOfVars = 0;
    List<CompareVisitor.Equation> equations = new ArrayList<>();
    for (int i = 0; i < domainResults.length; ++i) {
      Result result = typeCheck(expr.getArgument(i).getType(), Universe());
      if (!(result instanceof OKResult)) return result;
      domainResults[i] = (OKResult) result;
      if (domainResults[i].equations != null) {
        for (CompareVisitor.Equation equation : domainResults[i].equations) {
          try {
            if (equation.expression instanceof Expression) {
              equations.add(new CompareVisitor.Equation(equation.hole, ((Expression) equation.expression).liftIndex(0, -numberOfVars)));
            }
          } catch (LiftIndexVisitor.NegativeIndexException ignored) {
          }
        }
      }
      if (expr.getArgument(i) instanceof Abstract.TelescopeArgument) {
        for (String name : ((Abstract.TelescopeArgument) expr.getArgument(i)).getNames()) {
          myLocalContext.add(new TypedBinding(name, domainResults[i].expression));
          ++numberOfVars;
        }
      } else {
        myLocalContext.add(new TypedBinding(null, domainResults[i].expression));
        ++numberOfVars;
      }
    }

    Result codomainResult = typeCheck(expr.getCodomain(), Universe());
    for (int i = 0; i < numberOfVars; ++i) {
      myLocalContext.remove(myLocalContext.size() - 1);
    }
    if (!(codomainResult instanceof OKResult)) return codomainResult;
    OKResult okCodomainResult = (OKResult) codomainResult;

    Universe universe = new Universe.Type(Universe.NO_LEVEL, Universe.Type.PROP);
    for (int i = 0; i < domainResults.length; ++i) {
      Universe argUniverse = ((UniverseExpression) domainResults[i].type).getUniverse();
      Universe maxUniverse = universe.max(argUniverse);
      if (maxUniverse == null) {
        String msg = "Universe " + argUniverse + " of " + (i + 1) + suffix(i + 1) + " argument is not comparable to universe " + universe + " of previous arguments";
        TypeCheckingError error = new TypeCheckingError(msg, expr, getNames(myLocalContext));
        expr.setWellTyped(Error(null, error));
        myErrors.add(error);
        return null;
      }
      universe = maxUniverse;
    }
    Universe codomainUniverse = ((UniverseExpression) okCodomainResult.type).getUniverse();
    Universe maxUniverse = universe.max(codomainUniverse);
    if (maxUniverse == null) {
      String msg = "Universe " + codomainUniverse + " the codomain is not comparable to universe " + universe + " of arguments";
      TypeCheckingError error = new TypeCheckingError(msg, expr, getNames(myLocalContext));
      expr.setWellTyped(Error(null, error));
      myErrors.add(error);
      return null;
    }
    Expression actualType = new UniverseExpression(maxUniverse);

    if (okCodomainResult.equations != null) {
      for (CompareVisitor.Equation equation : okCodomainResult.equations) {
        try {
          if (equation.expression instanceof Expression) {
            okCodomainResult.equations.add(new CompareVisitor.Equation(equation.hole, ((Expression) equation.expression).liftIndex(0, -numberOfVars)));
          }
        } catch (LiftIndexVisitor.NegativeIndexException ignored) {
        }
      }
    }

    List<TypeArgument> resultArguments = new ArrayList<>(domainResults.length);
    for (int i = 0; i < domainResults.length; ++i) {
      if (expr.getArgument(i) instanceof Abstract.TelescopeArgument) {
        resultArguments.add(new TelescopeArgument(expr.getArgument(i).getExplicit(), ((Abstract.TelescopeArgument) expr.getArgument(i)).getNames(), domainResults[i].expression));
      } else {
        resultArguments.add(new TypeArgument(expr.getArgument(i).getExplicit(), domainResults[i].expression));
      }
    }
    return checkResult(expectedType, new OKResult(Pi(resultArguments, okCodomainResult.expression), actualType, equations), expr);
  }

  @Override
  public Result visitUniverse(Abstract.UniverseExpression expr, Expression expectedType) {
    return checkResult(expectedType, new OKResult(new UniverseExpression(expr.getUniverse()), new UniverseExpression(expr.getUniverse().succ()), null), expr);
  }

  @Override
  public Result visitVar(Abstract.VarExpression expr, Expression expectedType) {
    ListIterator<Binding> it = myLocalContext.listIterator(myLocalContext.size());
    int index = 0;
    while (it.hasPrevious()) {
      Binding def = it.previous();
      if (expr.getName().equals(def.getName())) {
        return checkResultImplicit(expectedType, new OKResult(Index(index), def.getType().liftIndex(0, index + 1), null), expr);
      }
      ++index;
    }
    Definition def = myGlobalContext.get(expr.getName());
    if (def == null) {
      NotInScopeError error = new NotInScopeError(expr, getNames(myLocalContext));
      expr.setWellTyped(Error(null, error));
      myErrors.add(error);
      return null;
    } else {
      return checkResultImplicit(expectedType, new OKResult(DefCall(def), def.getType(), null), expr);
    }
  }

  @Override
  public Result visitError(Abstract.ErrorExpression expr, Expression expectedType) {
    myErrors.add(new GoalError(myLocalContext, expectedType));
    return null;
  }

  @Override
  public Result visitInferHole(Abstract.InferHoleExpression expr, Expression expectedType) {
    TypeCheckingError error = new ArgInferenceError(expression(), null, getNames(myLocalContext));
    expr.setWellTyped(Error(null, error));
    myErrors.add(error);
    return null;
  }

  @Override
  public Result visitTuple(Abstract.TupleExpression expr, Expression expectedType) {
    Expression expectedTypeNorm = null;
    if (expectedType != null) {
      expectedTypeNorm = expectedType.normalize(NormalizeVisitor.Mode.WHNF);
      if (expectedTypeNorm instanceof SigmaExpression) {
        SigmaExpression expectedSigma = (SigmaExpression) expectedTypeNorm;
        int argsNumber = 0;
        for (TypeArgument arg : expectedSigma.getArguments()) {
          if (arg instanceof TelescopeArgument) {
            argsNumber += ((TelescopeArgument) arg).getNames().size();
          } else {
            ++argsNumber;
          }
        }

        if (expr.getFields().size() != argsNumber) {
          TypeCheckingError error = new TypeCheckingError("Expected a tuple with " + argsNumber + " fields, but given " + expr.getFields().size(), expr, getNames(myLocalContext));
          expr.setWellTyped(Error(null, error));
          myErrors.add(error);
          return null;
        }

        int i = 0;
        List<Expression> fields = new ArrayList<>(expr.getFields().size());
        Expression expression = Tuple(fields);
        List<TypeArgument> arguments = new ArrayList<>(expr.getFields().size());
        Expression type = Sigma(arguments);
        List<CompareVisitor.Equation> equations = new ArrayList<>();
        for (TypeArgument arg : expectedSigma.getArguments()) {
          if (arg instanceof TelescopeArgument) {
            for (String ignored : ((TelescopeArgument) arg).getNames()) {
              Result result = typeCheck(expr.getField(i), arg.getType());
              if (!(result instanceof OKResult)) return result;
              OKResult okResult = (OKResult) result;
              fields.add(okResult.expression);
              arguments.add(TypeArg(okResult.type));
              equations.addAll(okResult.equations);
              ++i;
            }
          } else {
            Result result = typeCheck(expr.getField(i), arg.getType());
            if (!(result instanceof OKResult)) return result;
            OKResult okResult = (OKResult) result;
            fields.add(okResult.expression);
            arguments.add(TypeArg(okResult.type));
            equations.addAll(okResult.equations);
            ++i;
          }
        }
        return new OKResult(expression, type, equations);
      } else
      if (!(expectedTypeNorm instanceof Abstract.InferHoleExpression)) {
        TypeCheckingError error = new TypeMismatchError(expectedTypeNorm, Sigma(args(TypeArg(Var("?")), TypeArg(Var("?")))), expr, getNames(myLocalContext));
        expr.setWellTyped(Error(null, error));
        myErrors.add(error);
        return null;
      }
    }

    List<Expression> fields = new ArrayList<>(expr.getFields().size());
    Expression expression = Tuple(fields);
    List<TypeArgument> arguments = new ArrayList<>(expr.getFields().size());
    Expression type = Sigma(arguments);
    List<CompareVisitor.Equation> equations = new ArrayList<>();
    for (Abstract.Expression field : expr.getFields()) {
      Result result = typeCheck(field, null);
      if (!(result instanceof OKResult)) return result;
      OKResult okResult = (OKResult) result;
      fields.add(okResult.expression);
      arguments.add(TypeArg(okResult.type));
      equations.addAll(okResult.equations);
    }
    if (expectedTypeNorm != null) {
      equations.add(new CompareVisitor.Equation((Abstract.InferHoleExpression) expectedTypeNorm, type));
    }
    return new OKResult(expression, type, equations);
  }

  @Override
  public Result visitSigma(Abstract.SigmaExpression expr, Expression expectedType) {
    OKResult[] domainResults = new OKResult[expr.getArguments().size()];
    int numberOfVars = 0;
    List<CompareVisitor.Equation> equations = new ArrayList<>();
    for (int i = 0; i < domainResults.length; ++i) {
      Result result = typeCheck(expr.getArgument(i).getType(), Universe());
      if (!(result instanceof OKResult)) return result;
      domainResults[i] = (OKResult) result;
      if (domainResults[i].equations != null) {
        for (CompareVisitor.Equation equation : domainResults[i].equations) {
          try {
            if (equation.expression instanceof Expression) {
              equations.add(new CompareVisitor.Equation(equation.hole, ((Expression) equation.expression).liftIndex(0, -numberOfVars)));
            }
          } catch (LiftIndexVisitor.NegativeIndexException ignored) {
          }
        }
      }
      if (expr.getArgument(i) instanceof Abstract.TelescopeArgument) {
        for (String name : ((Abstract.TelescopeArgument) expr.getArgument(i)).getNames()) {
          myLocalContext.add(new TypedBinding(name, domainResults[i].expression));
          ++numberOfVars;
        }
      } else {
        myLocalContext.add(new TypedBinding(null, domainResults[i].expression));
        ++numberOfVars;
      }
    }

    for (int i = 0; i < numberOfVars; ++i) {
      myLocalContext.remove(myLocalContext.size() - 1);
    }

    Universe universe = new Universe.Type(Universe.NO_LEVEL, Universe.Type.PROP);
    for (int i = 0; i < domainResults.length; ++i) {
      Universe argUniverse = ((UniverseExpression) domainResults[i].type).getUniverse();
      Universe maxUniverse = universe.max(argUniverse);
      if (maxUniverse == null) {
        String msg = "Universe " + argUniverse + " of " + (i + 1) + suffix(i + 1) + " argument is not comparable to universe " + universe + " of previous arguments";
        TypeCheckingError error = new TypeCheckingError(msg, expr, getNames(myLocalContext));
        expr.setWellTyped(Error(null, error));
        myErrors.add(error);
        return null;
      }
      universe = maxUniverse;
    }
    Expression actualType = new UniverseExpression(universe);

    List<TypeArgument> resultArguments = new ArrayList<>(domainResults.length);
    for (int i = 0; i < domainResults.length; ++i) {
      if (expr.getArgument(i) instanceof Abstract.TelescopeArgument) {
        resultArguments.add(new TelescopeArgument(expr.getArgument(i).getExplicit(), ((Abstract.TelescopeArgument) expr.getArgument(i)).getNames(), domainResults[i].expression));
      } else {
        resultArguments.add(new TypeArgument(expr.getArgument(i).getExplicit(), domainResults[i].expression));
      }
    }
    return checkResult(expectedType, new OKResult(Sigma(resultArguments), actualType, equations), expr);
  }

  @Override
  public Result visitBinOp(Abstract.BinOpExpression expr, Expression expectedType) {
    List<Arg> args = new ArrayList<>(2);
    args.add(new Arg(true, null, expr.getLeft()));
    args.add(new Arg(true, null, expr.getRight()));

    Concrete.Position position = expr instanceof Concrete.Expression ? ((Concrete.Expression) expr).getPosition() : null;
    Result result = typeCheckApps(new Concrete.DefCallExpression(position, expr.getBinOp()), args, expectedType, expr);
    if (!(result instanceof OKResult) || !(result.expression instanceof AppExpression)) return result;
    AppExpression appExpr1 = (AppExpression) result.expression;
    if (!(appExpr1.getFunction() instanceof AppExpression)) return result;
    AppExpression appExpr2 = (AppExpression) appExpr1.getFunction();
    if (!(appExpr2.getFunction() instanceof DefCallExpression)) return result;
    result.expression = BinOp(appExpr2.getArgument(), ((DefCallExpression) appExpr2.getFunction()).getDefinition(), appExpr1.getArgument());
    expr.setWellTyped(result.expression);
    return result;
  }

  @Override
  public Result visitElim(Abstract.ElimExpression expr, Expression expectedType) {
    TypeCheckingError error = null;
    if (expr.getElimType() == Abstract.ElimExpression.ElimType.CASE) {
      error = new TypeCheckingError("Not implemented yet: \\case", expr, getNames(myLocalContext));
    }
    if (expectedType == null && error == null) {
      error = new TypeCheckingError("Cannot infer the type of the expression", expr, getNames(myLocalContext));
    }
    if (mySide != Side.LHS && error == null) {
      error = new TypeCheckingError("\\elim is allowed only at the root of a definition", expr, getNames(myLocalContext));
    }

    Result exprResult = typeCheck(expr.getExpression(), null);
    if (!(exprResult instanceof OKResult)) return exprResult;
    OKResult exprOKResult = (OKResult) exprResult;
    if (!(exprOKResult.expression instanceof IndexExpression) && error == null) {
      error = new TypeCheckingError("\\elim can be applied only to a local variable", expr.getExpression(), getNames(myLocalContext));
    }

    List<Expression> parameters = new ArrayList<>();
    Expression type = exprOKResult.type.normalize(NormalizeVisitor.Mode.WHNF);
    Expression ftype = type.getFunction(parameters);
    if (!(ftype instanceof DefCallExpression && ((DefCallExpression) ftype).getDefinition() instanceof DataDefinition) && error == null) {
      error = new TypeMismatchError("a data type", type, expr.getExpression(), getNames(myLocalContext));
    }

    if (error != null) {
      expr.setWellTyped(Error(null, error));
      myErrors.add(error);
      return null;
    }

    int varIndex = ((IndexExpression) exprOKResult.expression).getIndex();
    DataDefinition dataType = (DataDefinition) ((DefCallExpression) ftype).getDefinition();
    List<Constructor> constructors = new ArrayList<>(dataType.getConstructors());
    List<Clause> clauses = new ArrayList<>(dataType.getConstructors().size());
    Clause otherwise = null;
    for (int i = 0; i < dataType.getConstructors().size(); ++i) {
      clauses.add(null);
    }

    clauses_loop:
    for (Abstract.Clause clause : expr.getClauses()) {
      if (clause == null) continue;

      if (clause.getName() == null) {
        Side side = clause.getArrow() == Abstract.Definition.Arrow.RIGHT || !(clause.getExpression() instanceof Abstract.ElimExpression && ((Abstract.ElimExpression) clause.getExpression()).getElimType() == Abstract.ElimExpression.ElimType.ELIM) ? Side.RHS : Side.LHS;
        CheckTypeVisitor visitor = side != mySide ? new CheckTypeVisitor(myGlobalContext, myLocalContext, myErrors, side) : this;
        Result clauseResult = visitor.typeCheck(clause.getExpression(), expectedType);
        if (!(clauseResult instanceof OKResult)) return exprResult;
        OKResult clauseOKResult = (OKResult) clauseResult;
        otherwise = new Clause(null, null, clause.getArrow(), clauseOKResult.expression, null);
        continue;
      }

      int index;
      for (index = 0; index < constructors.size(); ++index) {
        if (constructors.get(index).getName().equals(clause.getName())) {
          break;
        }
      }
      if (index == constructors.size()) {
        for (index = 0; index < dataType.getConstructors().size(); ++index) {
          if (dataType.getConstructor(index).getName().equals(clause.getName())) {
            break;
          }
        }
        if (index == dataType.getConstructors().size()) {
          error = new NotInScopeError(Var(clause.getName() == null ? "" : clause.getName()), new ArrayList<String>());
        } else {
          error = new TypeCheckingError("Overlapping pattern matching: " + dataType.getConstructor(index), clause, getNames(myLocalContext));
        }
        expr.setWellTyped(Error(null, error));
        myErrors.add(error);
        continue;
      }

      Constructor constructor = constructors.get(index);
      constructors.remove(index);

      for (Abstract.Argument argument : clause.getArguments()) {
        if (!(argument instanceof Abstract.NameArgument)) {
          error = new TypeCheckingError("Expected a variable", argument, getNames(myLocalContext));
          expr.setWellTyped(Error(null, error));
          myErrors.add(error);
          continue clauses_loop;
        }
      }
      List<TypeArgument> constructorArguments = new Signature(constructor.getType()).getArguments();
      if (clause.getArguments().size() != constructorArguments.size()) {
        String msg = "Expected " + constructorArguments.size() + " arguments to " + constructor.getName() + ", but given " + clause.getArguments().size();
        error = new TypeCheckingError(msg, clause, getNames(myLocalContext));
        expr.setWellTyped(Error(null, error));
        myErrors.add(error);
        continue;
      }

      List<Argument> arguments = new ArrayList<>(clause.getArguments().size());
      for (int i = 0; i < clause.getArguments().size(); ++i) {
        arguments.add(new NameArgument(clause.getArgument(i).getExplicit(), ((Abstract.NameArgument) clause.getArgument(i)).getName()));
      }

      Expression substExpr = DefCall(constructor);
      for (int j = constructorArguments.size() - 1; j >= 0; --j) {
        substExpr = Apps(substExpr, Index(j));
      }
      List<Expression> substExprs = new ArrayList<>(1);
      substExprs.add(substExpr);
      Expression clauseExpectedType = expectedType.liftIndex(0, constructorArguments.size()).subst(substExprs, varIndex + constructorArguments.size());

      List<Binding> localContext = new ArrayList<>(myLocalContext.size() - 1 + clause.getArguments().size());
      for (int i = 0; i < myLocalContext.size() - 1 - varIndex; ++i) {
        localContext.add(myLocalContext.get(i));
      }
      for (int i = 0; i < constructorArguments.size(); ++i) {
        localContext.add(new TypedBinding(((NameArgument) arguments.get(i)).getName(), constructorArguments.get(i).getType().subst(parameters, 0)));
      }
      for (int i = myLocalContext.size() - varIndex; i < myLocalContext.size(); ++i) {
        int i0 = i - myLocalContext.size() + varIndex;
        localContext.add(new TypedBinding(myLocalContext.get(i).getName(), myLocalContext.get(i).getType().liftIndex(i0 + 1, constructorArguments.size()).subst(substExprs, i0)));
      }

      Side side = clause.getArrow() == Abstract.Definition.Arrow.RIGHT || !(clause.getExpression() instanceof Abstract.ElimExpression && ((Abstract.ElimExpression) clause.getExpression()).getElimType() == Abstract.ElimExpression.ElimType.ELIM) ? Side.RHS : Side.LHS;
      Result clauseResult = new CheckTypeVisitor(myGlobalContext, localContext, myErrors, side).typeCheck(clause.getExpression(), clauseExpectedType);
      if (!(clauseResult instanceof OKResult)) return exprResult;
      OKResult clauseOKResult = (OKResult) clauseResult;

      clauses.set(constructor.getIndex(), new Clause(constructor, arguments, clause.getArrow(), clauseOKResult.expression, null));
    }

    if (!constructors.isEmpty()) {
      String msg = "Incomplete pattern matching";
      if (!dataType.equals(Prelude.INTERVAL)) {
        msg += ". Unhandled constructors: ";
        for (int i = 0; i < constructors.size(); ++i) {
          if (i > 0) msg += ", ";
          msg += constructors.get(i).getName();
        }
      }
      error = new TypeCheckingError(msg + ".", expr, getNames(myLocalContext));
      expr.setWellTyped(Error(null, error));
      myErrors.add(error);
      return null;
    }

    ElimExpression result = Elim(expr.getElimType(), exprOKResult.expression, clauses, otherwise);
    for (Clause clause : clauses) {
      clause.setElimExpression(result);
    }
    if (otherwise != null) {
      otherwise.setElimExpression(result);
    }
    return new OKResult(result, expectedType, null);
  }
}