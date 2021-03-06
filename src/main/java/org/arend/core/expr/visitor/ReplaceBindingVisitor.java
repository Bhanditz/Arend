package org.arend.core.expr.visitor;

import org.arend.core.context.binding.Binding;
import org.arend.core.context.param.DependentLink;
import org.arend.core.definition.ClassField;
import org.arend.core.expr.*;
import org.arend.core.sort.Sort;
import org.arend.core.subst.ExprSubstitution;
import org.arend.core.subst.LevelSubstitution;
import org.arend.core.subst.SubstVisitor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ReplaceBindingVisitor extends SubstVisitor {
  private final Binding myBinding;
  private final ClassCallExpression myBindingType;
  private final Map<ClassField, Expression> myImplementations;
  private boolean myOK = true;

  private ReplaceBindingVisitor(Binding binding, ClassCallExpression bindingType, Map<ClassField, Expression> implementations) {
    super(new ExprSubstitution(), LevelSubstitution.EMPTY);
    myBinding = binding;
    myBindingType = bindingType;
    myImplementations = implementations;
  }

  public ReplaceBindingVisitor(Binding binding, ClassCallExpression bindingType) {
    this(binding, bindingType, new HashMap<>(bindingType.getImplementedHere()));
  }

  public boolean isOK() {
    return myOK;
  }

  @Override
  public boolean isEmpty() {
    return false;
  }

  @Override
  public Expression visitReference(ReferenceExpression expr, Void params) {
    if (expr.getBinding() == myBinding) {
      myOK = false;
      return expr;
    }
    return super.visitReference(expr, params);
  }

  private Expression checkArgument(Expression arg, Expression type) {
    ReferenceExpression refArg = arg.checkedCast(ReferenceExpression.class);
    if (refArg != null && refArg.getBinding() == myBinding) {
      ClassCallExpression argType = type.normalize(NormalizeVisitor.Mode.WHNF).checkedCast(ClassCallExpression.class);
      if (argType != null && argType.getDefinition() != myBindingType.getDefinition()) {
        Map<ClassField, Expression> implementations = new HashMap<>();
        List<? extends ClassField> fieldOrder = argType.getDefinition().getTypecheckingFieldOrder();
        if (fieldOrder == null) {
          return arg.accept(this, null);
        }
        for (ClassField field : fieldOrder) {
          Expression impl = myImplementations.computeIfAbsent(field, e -> {
            LamExpression lamImpl = myBindingType.getDefinition().getImplementation(field);
            if (lamImpl != null) {
              ReplaceBindingVisitor visitor = new ReplaceBindingVisitor(lamImpl.getParameters(), myBindingType, myImplementations);
              Expression impl1 = lamImpl.getBody().accept(visitor, null);
              if (visitor.isOK()) {
                return impl1;
              }
            }
            return null;
          });

          if (impl != null) {
            implementations.put(field, impl);
          } else {
            return arg.accept(this, null);
          }
        }

        return new NewExpression(new ClassCallExpression(argType.getDefinition(), myBindingType.getSortArgument(), implementations, Sort.PROP, false));
      }
    }

    return arg.accept(this, null);
  }

  @Override
  public Expression visitDefCall(DefCallExpression expr, Void params) {
    DependentLink link = expr.getDefinition().getParameters();
    List<Expression> args = new ArrayList<>(expr.getDefCallArguments().size());
    for (Expression arg : expr.getDefCallArguments()) {
      args.add(checkArgument(arg, link.getTypeExpr()));
      link = link.getNext();
    }
    return expr.getDefinition().getDefCall(expr.getSortArgument(), args);
  }

  @Override
  public Expression visitConCall(ConCallExpression expr, Void params) {
    DependentLink dataLink = expr.getDefinition().getDataTypeParameters();
    List<Expression> dataTypeArgs = new ArrayList<>(expr.getDataTypeArguments().size());
    for (Expression arg : expr.getDataTypeArguments()) {
      dataTypeArgs.add(checkArgument(arg, dataLink.getTypeExpr()));
      dataLink = dataLink.getNext();
    }

    DependentLink link = expr.getDefinition().getParameters();
    List<Expression> args = new ArrayList<>(expr.getDefCallArguments().size());
    for (Expression arg : expr.getDefCallArguments()) {
      args.add(checkArgument(arg, link.getTypeExpr()));
      link = link.getNext();
    }

    return ConCallExpression.make(expr.getDefinition(), expr.getSortArgument(), dataTypeArgs, args);
  }

  @Override
  public ClassCallExpression visitClassCall(ClassCallExpression expr, Void params) {
    Map<ClassField, Expression> fieldSet = new HashMap<>();
    for (Map.Entry<ClassField, Expression> entry : expr.getImplementedHere().entrySet()) {
      fieldSet.put(entry.getKey(), checkArgument(entry.getValue(), entry.getKey().getType(Sort.STD).getCodomain()));
    }
    return new ClassCallExpression(expr.getDefinition(), expr.getSortArgument(), fieldSet, expr.getSort(), expr.hasUniverses());
  }

  @Override
  public Expression visitFieldCall(FieldCallExpression expr, Void params) {
    ReferenceExpression refExpr = expr.getArgument().checkedCast(ReferenceExpression.class);
    if (refExpr != null && refExpr.getBinding() == myBinding) {
      Expression impl = myBindingType.getImplementation(expr.getDefinition(), refExpr);
      if (impl != null) {
        return impl.accept(this, null);
      }
    }
    return super.visitFieldCall(expr, params);
  }
}
