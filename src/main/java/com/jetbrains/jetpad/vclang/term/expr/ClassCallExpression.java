package com.jetbrains.jetpad.vclang.term.expr;

import com.jetbrains.jetpad.vclang.term.definition.ClassDefinition;
import com.jetbrains.jetpad.vclang.term.definition.ClassField;
import com.jetbrains.jetpad.vclang.term.definition.TypeUniverse;
import com.jetbrains.jetpad.vclang.term.expr.visitor.ExpressionVisitor;
import com.jetbrains.jetpad.vclang.term.expr.visitor.NormalizeVisitor;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static com.jetbrains.jetpad.vclang.term.expr.ExpressionFactory.Lam;

public class ClassCallExpression extends DefCallExpression {
  private final Map<ClassField, ImplementStatement> myStatements;
  private TypeUniverse myUniverse;

  public ClassCallExpression(ClassDefinition definition) {
    super(definition);
    myStatements = new HashMap<>();
    myUniverse = definition.getUniverse();
  }

  public ClassCallExpression(ClassDefinition definition, Map<ClassField, ImplementStatement> statements) {
    super(definition);
    myStatements = statements;
  }

  @Override
  public Expression applyThis(Expression thisExpr) {
    ClassField parent = getDefinition().getParentField();
    myStatements.put(parent, new ImplementStatement(null, thisExpr));
    return this;
  }

  @Override
  public ClassDefinition getDefinition() {
    return (ClassDefinition) super.getDefinition();
  }

  public Map<ClassField, ImplementStatement> getImplementStatements() {
    return myStatements;
  }

  public TypeUniverse getUniverse() {
    if (myUniverse == null) {
      Substitution substitution = null;
      for (ClassField field : getDefinition().getFields()) {
        if (field.hasErrors()) {
          continue;
        }

        if (!myStatements.containsKey(field)) {
          if (substitution == null) {
            substitution = new Substitution();
            for (Map.Entry<ClassField, ImplementStatement> entry : myStatements.entrySet()) {
              if (entry.getValue().term != null) {
                substitution.add(entry.getKey(), Lam(entry.getKey().getThisParameter(), entry.getValue().term));
              }
            }
          }

          Expression expr1 = field.getBaseType().subst(substitution).normalize(NormalizeVisitor.Mode.WHNF);
          UniverseExpression expr = null;
          if (expr1.toOfType() != null) {
            Expression expr2 = expr1.toOfType().getExpression().getType();
            if (expr2 != null) {
              expr = expr2.normalize(NormalizeVisitor.Mode.WHNF).toUniverse();
            }
          }
          if (expr == null) {
            expr = expr1.getType().normalize(NormalizeVisitor.Mode.WHNF).toUniverse();
          }

          TypeUniverse fieldUniverse = expr != null ? expr.getUniverse() : field.getUniverse();
          if (myUniverse == null) {
            myUniverse = fieldUniverse;
            continue;
          }
          myUniverse = myUniverse.max(fieldUniverse);
          assert expr != null;
        }
      }
    }

    if (myUniverse == null) {
      myUniverse = TypeUniverse.PROP;
    }

    return myUniverse;
  }

  @Override
  public UniverseExpression getType() {
    return new UniverseExpression(getUniverse());
  }

  @Override
  public <P, R> R accept(ExpressionVisitor<? super P, ? extends R> visitor, P params) {
    return visitor.visitClassCall(this, params);
  }

  public static class ImplementStatement {
    public Expression type;
    public Expression term;

    public ImplementStatement(Expression type, Expression term) {
      this.type = type;
      this.term = term;
    }
  }

  @Override
  public ClassCallExpression toClassCall() {
    return this;
  }
}
