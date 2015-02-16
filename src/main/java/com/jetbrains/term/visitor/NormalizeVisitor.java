package main.java.com.jetbrains.term.visitor;

import main.java.com.jetbrains.term.expr.*;

// TODO: Rewrite normalization using thunks
// TODO: Add normalization to whnf
public class NormalizeVisitor implements ExpressionVisitor<Expression> {
    @Override
    public Expression visitApp(AppExpression expr) {
        Expression function1 = expr.getFunction().accept(this);
        if (function1 instanceof LamExpression) {
            Expression body = ((LamExpression)function1).getBody();
            return body.subst(expr.getArgument(), 0).normalize();
        }
        if (function1 instanceof AppExpression) {
            AppExpression appExpr1 = (AppExpression)function1;
            if (appExpr1.getFunction() instanceof AppExpression) {
                AppExpression appExpr2 = (AppExpression)appExpr1.getFunction();
                if (appExpr2.getFunction() instanceof NelimExpression) {
                    Expression zeroClause = appExpr2.getArgument();
                    Expression sucClause = appExpr1.getArgument();
                    Expression caseExpr = expr.getArgument().normalize();
                    if (caseExpr instanceof ZeroExpression) return zeroClause;
                    if (caseExpr instanceof AppExpression) {
                        AppExpression appExpr3 = (AppExpression)caseExpr;
                        if (appExpr3.getFunction() instanceof SucExpression) {
                            Expression recursiveCall = new AppExpression(appExpr1, appExpr3.getArgument());
                            Expression result = new AppExpression(new AppExpression(sucClause, appExpr3.getArgument()), recursiveCall);
                            return result.normalize();
                        }
                    }
                }
            }
        }
        return new AppExpression(function1, expr.getArgument().normalize());
    }

    @Override
    public Expression visitDefCall(DefCallExpression expr) {
        return expr.getDefinition().getTerm().normalize();
    }

    @Override
    public Expression visitIndex(IndexExpression expr) {
        return expr;
    }

    @Override
    public Expression visitLam(LamExpression expr) {
        return new LamExpression(expr.getVariable(), expr.getBody().normalize());
    }

    @Override
    public Expression visitNat(NatExpression expr) {
        return expr;
    }

    @Override
    public Expression visitNelim(NelimExpression expr) {
        return expr;
    }

    @Override
    public Expression visitPi(PiExpression expr) {
        return new PiExpression(expr.getVariable(), expr.getLeft().normalize(), expr.getRight().normalize());
    }

    @Override
    public Expression visitSuc(SucExpression expr) {
        return expr;
    }

    @Override
    public Expression visitUniverse(UniverseExpression expr) {
        return expr;
    }

    @Override
    public Expression visitVar(VarExpression expr) {
        return expr;
    }

    @Override
    public Expression visitZero(ZeroExpression expr) {
        return expr;
    }
}