package org.arend.typechecking.implicitargs.equations;

import org.arend.core.context.Utils;
import org.arend.core.context.binding.LevelVariable;
import org.arend.core.context.binding.inference.DerivedInferenceVariable;
import org.arend.core.context.binding.inference.InferenceLevelVariable;
import org.arend.core.context.binding.inference.InferenceVariable;
import org.arend.core.context.binding.inference.TypeClassInferenceVariable;
import org.arend.core.context.param.SingleDependentLink;
import org.arend.core.definition.ClassDefinition;
import org.arend.core.definition.ClassField;
import org.arend.core.expr.*;
import org.arend.core.expr.visitor.CompareVisitor;
import org.arend.core.expr.visitor.ElimBindingVisitor;
import org.arend.core.expr.visitor.NormalizeVisitor;
import org.arend.core.sort.Level;
import org.arend.core.sort.Sort;
import org.arend.core.subst.LevelSubstitution;
import org.arend.core.subst.SimpleLevelSubstitution;
import org.arend.naming.reference.TCClassReferable;
import org.arend.term.concrete.Concrete;
import org.arend.typechecking.error.local.*;
import org.arend.typechecking.visitor.CheckTypeVisitor;
import org.arend.typechecking.visitor.ProcessDefCallsVisitor;
import org.arend.util.Pair;

import java.util.*;

public class TwoStageEquations implements Equations {
  private final List<Equation> myEquations;
  private final LevelEquations<InferenceLevelVariable> myPLevelEquations;      // equations of the forms      c <= ?y and ?x <= max(?y + c', d)
  private final LevelEquations<InferenceLevelVariable> myBasedPLevelEquations; // equations of the forms lp + c <= ?y and ?x <= max(?y + c', d)
  private final LevelEquations<InferenceLevelVariable> myHLevelEquations;
  private final LevelEquations<InferenceLevelVariable> myBasedHLevelEquations;
  private final CheckTypeVisitor myVisitor;
  private final Stack<InferenceVariable> myProps;
  private final List<Pair<InferenceLevelVariable, InferenceLevelVariable>> myBoundVariables;
  private final Map<InferenceLevelVariable, Set<LevelVariable>> myLowerBounds;
  private final Map<InferenceLevelVariable, Level> myConstantUpperBounds;

  public TwoStageEquations(CheckTypeVisitor visitor) {
    myEquations = new ArrayList<>();
    myPLevelEquations = new LevelEquations<>();
    myBasedPLevelEquations = new LevelEquations<>();
    myHLevelEquations = new LevelEquations<>();
    myBasedHLevelEquations = new LevelEquations<>();
    myBoundVariables = new ArrayList<>();
    myLowerBounds = new HashMap<>();
    myConstantUpperBounds = new HashMap<>();
    myProps = new Stack<>();
    myVisitor = visitor;
  }

  private Expression getInstance(InferenceVariable variable, FieldCallExpression fieldCall, Expression expr) {
    if (variable instanceof TypeClassInferenceVariable) {
      TCClassReferable classRef = ((TypeClassInferenceVariable) variable).getClassReferable().getUnderlyingTypecheckable();
      ClassDefinition classDef = classRef == null ? null : (ClassDefinition) myVisitor.getTypecheckingState().getTypechecked(classRef);
      if (classDef == null) {
        return null;
      }
      if (classDef.getClassifyingField() == fieldCall.getDefinition()) {
        Expression stuck2 = expr.getCanonicalStuckExpression();
        if (stuck2 == null || !stuck2.isInstance(InferenceReferenceExpression.class)) {
          return ((TypeClassInferenceVariable) variable).getInstance(myVisitor.getInstancePool(), expr, this, variable.getSourceNode());
        }
      }
    }
    return null;
  }

  @Override
  public boolean addEquation(Expression expr1, Expression expr2, CMP cmp, Concrete.SourceNode sourceNode, InferenceVariable stuckVar1, InferenceVariable stuckVar2) {
    InferenceVariable inf1 = expr1.getInferenceVariable();
    InferenceVariable inf2 = expr2.getInferenceVariable();

    // expr1 == expr2 == ?x
    if (inf1 == inf2 && inf1 != null) {
      return true;
    }

    if (inf1 == null && inf2 == null) {
      Expression result = null;

      // expr1 == field call
      FieldCallExpression fieldCall1 = expr1.checkedCast(FieldCallExpression.class);
      InferenceVariable variable = fieldCall1 == null ? null : fieldCall1.getArgument().getInferenceVariable();
      if (variable != null) {
        // expr1 == view field call
        result = getInstance(variable, fieldCall1, expr2);
      }

      // expr2 == field call
      if (variable == null) {
        FieldCallExpression fieldCall2 = expr2.checkedCast(FieldCallExpression.class);
        variable = fieldCall2 == null ? null : fieldCall2.getArgument().getInferenceVariable();
        if (variable != null) {
          // expr2 == view field call
          result = getInstance(variable, fieldCall2, expr1);
        }
      }

      if (result != null) {
        SolveResult solveResult = solve(variable, result);
        return solveResult != SolveResult.SOLVED || CompareVisitor.compare(this, cmp, expr1, expr2, sourceNode);
      }
    }

    CMP origCmp = cmp;

    // expr1 == ?x && expr2 /= ?y || expr1 /= ?x && expr2 == ?y
    if (inf1 != null && inf2 == null || inf2 != null && inf1 == null) {
      InferenceVariable cInf = inf1 != null ? inf1 : inf2;
      Expression cType = inf1 != null ? expr2 : expr1;
      cType = cType.normalize(NormalizeVisitor.Mode.WHNF);

      // cType /= Pi, cType /= Type, cType /= Class, cType /= stuck on ?X
      if (!cType.isInstance(PiExpression.class) && !cType.isInstance(UniverseExpression.class) && !cType.isInstance(ClassCallExpression.class)) {
        Expression stuck = cType.getCanonicalStuckExpression();
        if (stuck == null || !stuck.isInstance(InferenceReferenceExpression.class)) {
          cmp = CMP.EQ;
        }
      }

      if (inf1 != null) {
        cmp = cmp.not();
      }

      if (cType.isInstance(UniverseExpression.class) && cType.cast(UniverseExpression.class).getSort().isProp()) {
        if (cmp == CMP.LE) {
          myProps.push(cInf);
          return true;
        } else {
          cmp = CMP.EQ;
        }
      }

      // If cType is not pi, classCall, universe, or a stuck expression, then solve immediately.
      if (cmp != CMP.EQ) {
        Expression cod = cType;
        while (cod.isInstance(PiExpression.class)) {
          cod = cod.cast(PiExpression.class).getCodomain();
        }
        if (!cod.isInstance(ClassCallExpression.class) && !cod.isInstance(UniverseExpression.class)) {
          Expression stuck = cod.getCanonicalStuckExpression();
          if (!(stuck != null && stuck.isInstance(InferenceReferenceExpression.class))) {
            cmp = CMP.EQ;
          }
        }
      }

      // ?x == _
      if (cmp == CMP.EQ) {
        solve(cInf, cType);
        return true;
      }

      // ?x <> Pi
      if (cType.isInstance(PiExpression.class)) {
        PiExpression pi = cType.cast(PiExpression.class);
        Sort domSort = pi.getParameters().getType().getSortOfType();
        Sort codSort = Sort.generateInferVars(this, false, sourceNode);
        Sort piSort = PiExpression.generateUpperBound(domSort, codSort, this, sourceNode);

        try (Utils.SetContextSaver ignore = new Utils.SetContextSaver<>(myVisitor.getFreeBindings())) {
          for (SingleDependentLink link = pi.getParameters(); link.hasNext(); link = link.getNext()) {
            myVisitor.getFreeBindings().add(link);
          }
          InferenceVariable infVar = new DerivedInferenceVariable(cInf.getName() + "-cod", cInf, new UniverseExpression(codSort), myVisitor.getAllBindings());
          Expression newRef = new InferenceReferenceExpression(infVar, this);
          solve(cInf, new PiExpression(piSort, pi.getParameters(), newRef));
          return addEquation(pi.getCodomain(), newRef, cmp, sourceNode, pi.getCodomain().getStuckInferenceVariable(), infVar);
        }
      }

      // ?x <> Type
      Sort sort = cType.toSort();
      if (sort != null) {
        Sort genSort = Sort.generateInferVars(this, true, cInf.getSourceNode());
        solve(cInf, new UniverseExpression(genSort));
        if (cmp == CMP.LE) {
          Sort.compare(sort, genSort, CMP.LE, this, sourceNode);
        } else {
          if (!sort.getPLevel().isInfinity()) {
            addLevelEquation(genSort.getPLevel().getVar(), sort.getPLevel().getVar(), sort.getPLevel().getConstant(), sort.getPLevel().getMaxAddedConstant(), sourceNode);
          }
          if (!sort.getHLevel().isInfinity()) {
            addLevelEquation(genSort.getHLevel().getVar(), sort.getHLevel().getVar(), sort.getHLevel().getConstant(), sort.getHLevel().getMaxAddedConstant(), sourceNode);
          }
        }
        return true;
      }
    }

    Equation equation = new Equation(expr1, expr2, origCmp, sourceNode);
    myEquations.add(equation);
    if (inf1 != null && inf2 != null) {
      inf1.addListener(equation);
      inf2.addListener(equation);
    } else {
      if (stuckVar1 != null) {
        stuckVar1.addListener(equation);
      }
      if (stuckVar2 != null) {
        stuckVar2.addListener(equation);
      }
    }

    return true;
  }

  @Override
  public void bindVariables(InferenceLevelVariable pVar, InferenceLevelVariable hVar) {
    assert pVar.getType() == LevelVariable.LvlType.PLVL;
    assert hVar.getType() == LevelVariable.LvlType.HLVL;
    myBoundVariables.add(new Pair<>(pVar, hVar));
  }

  private void addEquation(LevelEquation<InferenceLevelVariable> equation, boolean based) {
    InferenceLevelVariable var1 = equation.isInfinity() ? equation.getVariable() : equation.getVariable1();
    InferenceLevelVariable var2 = equation.isInfinity() ? equation.getVariable() : equation.getVariable2();
    assert var1 == null || var2 == null || var1.getType() == var2.getType();

    if (var1 != null && var1.getType() == LevelVariable.LvlType.PLVL || var2 != null && var2.getType() == LevelVariable.LvlType.PLVL) {
      if (based) {
        myBasedPLevelEquations.addEquation(equation);
      } else {
        myPLevelEquations.addEquation(equation);
      }
    } else
    if (var1 != null && var1.getType() == LevelVariable.LvlType.HLVL || var2 != null && var2.getType() == LevelVariable.LvlType.HLVL) {
      if (based) {
        myBasedHLevelEquations.addEquation(equation);
      } else {
        myHLevelEquations.addEquation(equation);
      }
    } else {
      throw new IllegalStateException();
    }
  }

  private void addLevelEquation(final LevelVariable var1, LevelVariable var2, int constant, int maxConstant, Concrete.SourceNode sourceNode) {
    // _ <= max(-c, -d), _ <= max(l - c, -d) // 6
    if (constant < 0 && maxConstant < 0 && !(var2 instanceof InferenceLevelVariable)) {
      myVisitor.getErrorReporter().report(new SolveLevelEquationsError(Collections.singletonList(new LevelEquation<>(var1, var2, constant, maxConstant)), sourceNode));
      return;
    }

    // 0 <= max(_ +-c, +-d) // 10
    if (var1 == null) {
      // 0 <= max(?y - c, -d) // 1
      if (constant < 0 && maxConstant < 0) {
        addEquation(new LevelEquation<>(null, (InferenceLevelVariable) var2, constant), false);
      }
      return;
    }

    if (var2 instanceof InferenceLevelVariable && var1 != var2) {
      myLowerBounds.computeIfAbsent((InferenceLevelVariable) var2, k -> new HashSet<>()).add(var1);
    }

    // ?x <= max(_ +- c, +-d) // 10
    if (var1 instanceof InferenceLevelVariable) {
      // ?x <= max(?y +- c, +-d) // 4
      if (var2 instanceof InferenceLevelVariable) {
        LevelEquation<InferenceLevelVariable> equation = new LevelEquation<>((InferenceLevelVariable) var1, (InferenceLevelVariable) var2, constant, maxConstant < 0 ? null : maxConstant);
        addEquation(equation, false);
        addEquation(equation, true);
      } else {
        // ?x <= max(+-c, +-d), ?x <= max(l +- c, +-d) // 6
        Level oldLevel = myConstantUpperBounds.get(var1);
        if (oldLevel == null) {
          myConstantUpperBounds.put((InferenceLevelVariable) var1, new Level(var2, constant, maxConstant >= constant ? maxConstant - constant : 0));
        } else {
          if (var2 == null && oldLevel.getVar() != null || var2 != null && oldLevel.getVar() == null) {
            int otherConstant = var2 == null ? Math.max(constant, maxConstant) : Math.max(oldLevel.getConstant(), oldLevel.getMaxConstant());
            int thisConst = var2 == null ? oldLevel.getConstant() : constant;
            int thisMaxConst = var2 == null ? oldLevel.getMaxAddedConstant() : maxConstant;
            myConstantUpperBounds.put((InferenceLevelVariable) var1, new Level(null, Math.max(Math.min(thisMaxConst, otherConstant), Math.min(thisConst, otherConstant))));
          } else {
            int newConst = Math.min(constant, oldLevel.getConstant());
            int newMaxConst = Math.min(maxConstant, oldLevel.getMaxConstant());
            myConstantUpperBounds.put((InferenceLevelVariable) var1, new Level(var2, newConst, newMaxConst >= newConst ? newMaxConst - newConst : 0));
          }
        }
      }
      return;
    }

    // l <= max(_ +- c, +-d) // 10
    {
      // l <= max(l + c, +-d) // 2
      if (var1 == var2 && constant >= 0) {
        return;
      }

      // l <= max(?y +- c, +-d) // 4
      if (var2 instanceof InferenceLevelVariable) {
        if (constant < 0) {
          addEquation(new LevelEquation<>(null, (InferenceLevelVariable) var2, constant), true);
        }
        return;
      }

      // l <= max(l - c, +d), l <= max(+-c, +-d) // 4
      myVisitor.getErrorReporter().report(new SolveLevelEquationsError(Collections.singletonList(new LevelEquation<>(var1, var2, constant, maxConstant)), sourceNode));
    }
  }

  private void addLevelEquation(LevelVariable var, Concrete.SourceNode sourceNode) {
    if (var instanceof InferenceLevelVariable) {
      addEquation(new LevelEquation<>((InferenceLevelVariable) var), false);
    } else {
      myVisitor.getErrorReporter().report(new SolveLevelEquationsError(Collections.singletonList(new LevelEquation<>(var)), sourceNode));
    }
  }

  @Override
  public boolean solve(Expression type, Expression expr, CMP cmp, Concrete.SourceNode sourceNode) {
    if (!CompareVisitor.compare(this, cmp, type, expr, sourceNode)) {
      myVisitor.getErrorReporter().report(new SolveEquationError(type, expr, sourceNode));
      return false;
    } else {
      return true;
    }
  }

  @Override
  public boolean addEquation(Level level1, Level level2, CMP cmp, Concrete.SourceNode sourceNode) {
    if (level1.isInfinity() && level2.isInfinity() || level1.isInfinity() && cmp == CMP.GE || level2.isInfinity() && cmp == CMP.LE) {
      return true;
    }
    if (level1.isInfinity()) {
      addLevelEquation(level2.getVar(), sourceNode);
      return true;
    }
    if (level2.isInfinity()) {
      addLevelEquation(level1.getVar(), sourceNode);
      return true;
    }

    if (cmp == CMP.LE || cmp == CMP.EQ) {
      addLevelEquation(level1.getVar(), level2.getVar(), level2.getConstant() - level1.getConstant(), level2.getMaxAddedConstant() - level1.getConstant(), sourceNode);
      /*
      if (level1.getVar() != null || level1.getConstant() != 0) {
        addLevelEquation(null, level2.getVar(), level2.getConstant() - level1.getMaxAddedConstant(), level2.getMaxAddedConstant() - level1.getMaxAddedConstant(), sourceNode);
      }
      */
    }
    if (cmp == CMP.GE || cmp == CMP.EQ) {
      addLevelEquation(level2.getVar(), level1.getVar(), level1.getConstant() - level2.getConstant(), level1.getMaxAddedConstant() - level2.getConstant(), sourceNode);
      /*
      if (level2.getVar() != null || level2.getConstant() != 0) {
        addLevelEquation(null, level1.getVar(), level1.getConstant() - level2.getMaxAddedConstant(), level1.getMaxAddedConstant() - level2.getMaxAddedConstant(), sourceNode);
      }
      */
    }
    return true;
  }

  @Override
  public boolean addVariable(InferenceLevelVariable var) {
    if (var.getType() == LevelVariable.LvlType.PLVL) {
      myPLevelEquations.addVariable(var);
      myBasedPLevelEquations.addVariable(var);
    } else {
      myHLevelEquations.addVariable(var);
      myBasedHLevelEquations.addVariable(var);
    }
    return true;
  }

  @Override
  public void remove(Equation equation) {
    myEquations.remove(equation);
  }

  private void reportCycle(List<LevelEquation<InferenceLevelVariable>> cycle, Set<InferenceLevelVariable> unBased) {
    Set<LevelEquation<? extends LevelVariable>> basedCycle = new LinkedHashSet<>();
    for (LevelEquation<InferenceLevelVariable> equation : cycle) {
      if (equation.isInfinity() || equation.getVariable1() != null) {
        basedCycle.add(new LevelEquation<>(equation));
      } else {
        basedCycle.add(new LevelEquation<>(equation.getVariable2() == null || unBased.contains(equation.getVariable2()) ? null : equation.getVariable2().getStd(), equation.getVariable2(), equation.getConstant()));
      }
    }
    LevelEquation<InferenceLevelVariable> lastEquation = cycle.get(cycle.size() - 1);
    InferenceLevelVariable var = lastEquation.getVariable1() != null ? lastEquation.getVariable1() : lastEquation.getVariable2();
    myVisitor.getErrorReporter().report(new SolveLevelEquationsError(basedCycle, var.getSourceNode()));
  }

  private void calculateUnBased(LevelEquations<InferenceLevelVariable> basedEquations, Set<InferenceLevelVariable> unBased, Map<InferenceLevelVariable, Integer> basedSolution) {
    Map<InferenceLevelVariable,Boolean> unBasedMap = new HashMap<>();
    for (InferenceLevelVariable var : basedEquations.getVariables()) {
      Level ub = myConstantUpperBounds.get(var);
      if (ub != null && (ub.getVar() == null || ub.getConstant() < basedSolution.get(var))) {
        unBasedMap.put(var, true);
      }
    }

    if (!unBasedMap.isEmpty()) {
      Stack<InferenceLevelVariable> stack = new Stack<>();
      for (InferenceLevelVariable var : unBasedMap.keySet()) {
        stack.push(var);
      }

      boolean ok = true;
      while (!stack.isEmpty()) {
        InferenceLevelVariable var = stack.pop();
        Set<LevelVariable> lowerBounds = myLowerBounds.get(var);
        if (lowerBounds != null) {
          for (LevelVariable lowerBound : lowerBounds) {
            if (lowerBound instanceof InferenceLevelVariable) {
              if (unBasedMap.put((InferenceLevelVariable) lowerBound, true) == null) {
                stack.push((InferenceLevelVariable) lowerBound);
              }
            } else if (ok) {
              myVisitor.getErrorReporter().report(new ConstantSolveLevelEquationError(lowerBound, var.getSourceNode()));
              ok = false;
            }
          }
        }
      }
    }

    for (InferenceLevelVariable variable : basedEquations.getVariables()) {
      calculateUnBasedMap(variable, unBasedMap);
    }

    for (Map.Entry<InferenceLevelVariable, Boolean> entry : unBasedMap.entrySet()) {
      if (entry.getValue()) {
        unBased.add(entry.getKey());
      }
    }
  }

  private boolean calculateUnBasedMap(InferenceLevelVariable variable, Map<InferenceLevelVariable,Boolean> unBasedMap) {
    if (variable.isUniverseLike()) {
      Boolean prev = unBasedMap.putIfAbsent(variable, false);
      return prev == null ? false : prev;
    }
    Boolean val = unBasedMap.get(variable);
    if (val != null) {
      return val;
    }

    unBasedMap.put(variable, true);
    Set<LevelVariable> lowerBounds = myLowerBounds.get(variable);
    if (lowerBounds != null) {
      for (LevelVariable lowerBound : lowerBounds) {
        boolean lowerBoundIsUnBased = lowerBound instanceof InferenceLevelVariable && calculateUnBasedMap((InferenceLevelVariable) lowerBound, unBasedMap);
        if (!lowerBoundIsUnBased) {
          unBasedMap.put(variable, false);
          return false;
        }
      }
    }

    return true;
  }

  private void solveLevelEquations(SimpleLevelSubstitution result) {
    if (myPLevelEquations.isEmpty() &&
        myHLevelEquations.isEmpty() &&
        myBasedPLevelEquations.isEmpty() &&
        myBasedHLevelEquations.isEmpty() &&
        myBoundVariables.isEmpty() &&
        myLowerBounds.isEmpty()) {
      return;
    }

    Map<InferenceLevelVariable, Integer> basedSolution = new HashMap<>();
    List<LevelEquation<InferenceLevelVariable>> cycle = myBasedHLevelEquations.solve(basedSolution);

    Set<InferenceLevelVariable> unBased = new HashSet<>();
    calculateUnBased(myBasedHLevelEquations, unBased, basedSolution);

    boolean ok = cycle == null;
    if (!ok) {
      reportCycle(cycle, unBased);
    }

    Map<InferenceLevelVariable, Integer> solution = new HashMap<>();
    cycle = myHLevelEquations.solve(solution);
    if (ok && cycle != null) {
      reportCycle(cycle, unBased);
    }

    if (!unBased.isEmpty()) {
      for (Pair<InferenceLevelVariable, InferenceLevelVariable> vars : myBoundVariables) {
        if (unBased.contains(vars.proj2)) {
          Integer sol = solution.get(vars.proj2);
          if (sol != null && sol == 0) {
            myPLevelEquations.getEquations().removeIf(equation -> !equation.isInfinity() && (equation.getVariable1() == vars.proj1 || equation.getVariable2() == vars.proj1));
            myBasedPLevelEquations.getEquations().removeIf(equation -> !equation.isInfinity() && (equation.getVariable1() == vars.proj1 || equation.getVariable2() == vars.proj1));
            myConstantUpperBounds.remove(vars.proj1);
          }
        }
      }
    }

    cycle = myBasedPLevelEquations.solve(basedSolution);
    Set<InferenceLevelVariable> pUnBased = new HashSet<>();
    calculateUnBased(myBasedPLevelEquations, pUnBased, basedSolution);
    ok = cycle == null;
    if (!ok) {
      reportCycle(cycle, pUnBased);
    }
    cycle = myPLevelEquations.solve(solution);
    if (ok && cycle != null) {
      reportCycle(cycle, pUnBased);
    }
    unBased.addAll(pUnBased);

    for (InferenceLevelVariable var : unBased) {
      Integer sol = solution.get(var);
      assert sol != null || var.getType() == LevelVariable.LvlType.HLVL;
      result.add(var, sol == null ? Level.INFINITY : new Level(-sol));
    }
    for (Map.Entry<InferenceLevelVariable, Integer> entry : basedSolution.entrySet()) {
      assert entry.getValue() != null || entry.getKey().getType() == LevelVariable.LvlType.HLVL;
      if (!unBased.contains(entry.getKey())) {
        Integer sol = solution.get(entry.getKey());
        assert sol != null || entry.getKey().getType() == LevelVariable.LvlType.HLVL;
        result.add(entry.getKey(), sol == null || entry.getValue() == null ? Level.INFINITY : new Level(entry.getKey().getStd(), -entry.getValue(), -sol <= -entry.getValue() ? 0 : -sol - (-entry.getValue())));
      }
    }

    for (int i = 0; i < myEquations.size(); i++) {
      Equation equation = myEquations.get(i);
      myEquations.set(i, new Equation(equation.type.subst(result), equation.expr.subst(result), equation.cmp, equation.sourceNode));
    }

    myPLevelEquations.clear();
    myHLevelEquations.clear();
    myBasedPLevelEquations.clear();
    myBasedHLevelEquations.clear();
    myBoundVariables.clear();
    myLowerBounds.clear();
  }

  @Override
  public LevelSubstitution solve(Concrete.SourceNode sourceNode) {
    while (!myProps.isEmpty()) {
      InferenceVariable var = myProps.pop();
      if (!var.isSolved()) {
        var.solve(this, new UniverseExpression(Sort.PROP));
      }
    }

    SimpleLevelSubstitution result = new SimpleLevelSubstitution();
    solveLevelEquations(result);
    solveClassCalls();
    solveLevelEquations(result); // We need the second pass since @solveClassCalls can generate new level variables

    for (Map.Entry<InferenceLevelVariable, Level> entry : myConstantUpperBounds.entrySet()) {
      int constant = entry.getValue().getConstant();
      Level level = result.get(entry.getKey());
      if (!Level.compare(level, new Level(entry.getValue().getVar(), constant, entry.getValue().getMaxConstant()), CMP.LE, DummyEquations.getInstance(), null)) {
        int maxConstant = entry.getValue().getMaxAddedConstant();
        List<LevelEquation<LevelVariable>> equations = new ArrayList<>(2);
        equations.add(level.isInfinity() ? new LevelEquation<>(entry.getKey()) : level.getMaxAddedConstant() <= constant || level.getMaxAddedConstant() <= maxConstant ? new LevelEquation<>(level.getVar(), entry.getKey(), -level.getConstant()) : new LevelEquation<>(null, entry.getKey(), -level.getMaxAddedConstant()));
        equations.add(new LevelEquation<>(entry.getKey(), entry.getValue().getVar(), constant, maxConstant));
        myVisitor.getErrorReporter().report(new SolveLevelEquationsError(equations, entry.getKey().getSourceNode()));
      }
    }

    for (Iterator<Equation> iterator = myEquations.iterator(); iterator.hasNext(); ) {
      Equation equation = iterator.next();
      Expression stuckExpr = equation.expr.getCanonicalStuckExpression();
      if (stuckExpr != null && (stuckExpr.isInstance(InferenceReferenceExpression.class) || stuckExpr.isError())) {
        iterator.remove();
      } else {
        stuckExpr = equation.type.getCanonicalStuckExpression();
        if (stuckExpr != null && (stuckExpr.isInstance(InferenceReferenceExpression.class) || stuckExpr.isError())) {
          iterator.remove();
        }
      }
    }
    if (!myEquations.isEmpty()) {
      myVisitor.getErrorReporter().report(new SolveEquationsError(new ArrayList<>(myEquations), sourceNode));
    }

    myEquations.clear();
    myConstantUpperBounds.clear();
    myProps.clear();
    return result;
  }

  private void solveClassCalls() {
    boolean updated = false;
    Map<InferenceVariable,Set<Wrapper>> lowerBounds = new HashMap<>();
    for (Iterator<Equation> iterator = myEquations.iterator(); iterator.hasNext(); ) {
      Equation equation = iterator.next();
      if (equation.cmp == CMP.EQ) {
        InferenceVariable var1 = equation.type.getInferenceVariable();
        InferenceVariable var2 = equation.expr.getInferenceVariable();
        if (var1 != null && var2 != null) {
          lowerBounds.computeIfAbsent(var1, k -> new LinkedHashSet<>()).add(new Wrapper(equation.expr));
          lowerBounds.computeIfAbsent(var2, k -> new LinkedHashSet<>()).add(new Wrapper(equation.type));
        }
        continue;
      }

      Expression lower = equation.getLowerBound();
      Expression upper = equation.getUpperBound();
      InferenceVariable var1 = upper.getInferenceVariable();
      InferenceVariable var2 = lower.getInferenceVariable();
      if (var1 != null && (var2 != null || lower.isInstance(ClassCallExpression.class))) {
        lowerBounds.computeIfAbsent(var1, k -> new LinkedHashSet<>()).add(new Wrapper(lower));
        if (lower.isInstance(ClassCallExpression.class)) {
          updated = true;
          iterator.remove();
        }
      }
    }

    if (updated) {
      // @lowerBounds consists of entries (@v,@list) such that every expression @e in @list is either a classCall or an inference variable and @e <= @v.
      // The result of @calculateClosureOfLowerBounds is the transitive closure of @lowerBounds.
      for (Pair<InferenceVariable,List<ClassCallExpression>> pair : calculateClosureOfLowerBounds(lowerBounds)) {
        solveClassCallLowerBounds(pair.proj2, pair.proj1);
      }
    }

    Map<InferenceVariable, Expression> result = new LinkedHashMap<>();
    for (Iterator<Equation> iterator = myEquations.iterator(); iterator.hasNext(); ) {
      Equation equation = iterator.next();
      Expression lower = equation.getLowerBound();
      Expression upper = equation.getUpperBound();
      if (lower.isInstance(ClassCallExpression.class) && upper.isInstance(ClassCallExpression.class)) {
        if (!CompareVisitor.compare(DummyEquations.getInstance(), equation.cmp == CMP.EQ ? CMP.EQ : CMP.LE, lower, upper, equation.sourceNode)) {
          myVisitor.getErrorReporter().report(new SolveEquationError(lower, upper, equation.sourceNode));
        }
        iterator.remove();
        continue;
      }
      if (equation.cmp == CMP.EQ) {
        continue;
      }

      InferenceVariable var = lower.getInferenceVariable();
      if (var != null && upper.isInstance(ClassCallExpression.class)) {
        Expression oldResult = result.get(var);
        if (oldResult == null || upper.isLessOrEquals(oldResult, DummyEquations.getInstance(), var.getSourceNode())) {
          result.put(var, upper);
        } else if (!oldResult.isLessOrEquals(upper, DummyEquations.getInstance(), var.getSourceNode())) {
          List<Equation> eqs = new ArrayList<>(2);
          eqs.add(new Equation(lower, oldResult, CMP.LE, var.getSourceNode()));
          eqs.add(new Equation(lower, upper, CMP.LE, var.getSourceNode()));
          myVisitor.getErrorReporter().report(new SolveEquationsError(eqs, var.getSourceNode()));
        }
        iterator.remove();
      }
    }

    for (Map.Entry<InferenceVariable, Expression> entry : result.entrySet()) {
      solve(entry.getKey(), entry.getValue());
    }
  }

  private static class Wrapper {
    Expression expression;

    Wrapper(Expression expression) {
      this.expression = expression;
    }
  }

  private List<Pair<InferenceVariable,List<ClassCallExpression>>> calculateClosureOfLowerBounds(Map<InferenceVariable,Set<Wrapper>> lowerBounds) {
    List<Pair<InferenceVariable,List<ClassCallExpression>>> result = new ArrayList<>(lowerBounds.size());
    for (Map.Entry<InferenceVariable, Set<Wrapper>> entry : lowerBounds.entrySet()) {
      Set<Wrapper> varResult = new HashSet<>();
      calculateLowerBoundsOfVariable(entry.getKey(), varResult, lowerBounds, new HashSet<>());
      List<ClassCallExpression> list = new ArrayList<>(varResult.size());
      for (Wrapper wrapper : varResult) {
        list.add(wrapper.expression.cast(ClassCallExpression.class));
      }
      result.add(new Pair<>(entry.getKey(), list));
    }
    return result;
  }

  private void calculateLowerBoundsOfVariable(InferenceVariable variable, Set<Wrapper> result, Map<InferenceVariable,Set<Wrapper>> lowerBounds, Set<InferenceVariable> visited) {
    if (!visited.add(variable)) {
      return;
    }

    Set<Wrapper> varLowerBounds = lowerBounds.get(variable);
    if (varLowerBounds == null) {
      return;
    }

    for (Wrapper wrapper : varLowerBounds) {
      if (wrapper.expression.isInstance(ClassCallExpression.class)) {
        result.add(wrapper);
      } else {
        InferenceVariable var = wrapper.expression.getInferenceVariable();
        if (var != null) {
          calculateLowerBoundsOfVariable(var, result, lowerBounds, visited);
        }
      }
    }
  }

  private void solveClassCallLowerBounds(List<ClassCallExpression> lowerBounds, InferenceVariable variable) {
    if (lowerBounds.isEmpty()) {
      return;
    }

    if (lowerBounds.size() == 1) {
      solve(variable, lowerBounds.get(0));
      return;
    }

    ClassDefinition classDef = lowerBounds.get(0).getDefinition();
    for (ClassCallExpression lowerBound : lowerBounds) {
      if (lowerBound.getDefinition() != classDef) {
        List<Equation> equations = new ArrayList<>(lowerBounds.size());
        Expression upper = new InferenceReferenceExpression(variable, (Expression) null);
        for (ClassCallExpression lowerBound1 : lowerBounds) {
          equations.add(new Equation(lowerBound1, upper, CMP.LE, variable.getSourceNode()));
        }
        myVisitor.getErrorReporter().report(new SolveEquationsError(equations, variable.getSourceNode()));
        return;
      }
    }

    Sort sortArgument = lowerBounds.get(0).getSortArgument();
    Map<ClassField, Expression> implementations = lowerBounds.get(0).getImplementedHere();
    for (ClassCallExpression lowerBound : lowerBounds) {
      sortArgument = sortArgument.max(lowerBound.getSortArgument());
      for (Iterator<Map.Entry<ClassField, Expression>> iterator = implementations.entrySet().iterator(); iterator.hasNext(); ) {
        Map.Entry<ClassField, Expression> entry = iterator.next();
        Expression implementation = lowerBound.getImplementationHere(entry.getKey());
        if (implementation == null || !implementation.equals(entry.getValue())) {
          iterator.remove();
        }
      }
    }

    ClassCallExpression solution = new ClassCallExpression(classDef, sortArgument, implementations, classDef.computeSort(implementations), classDef.hasUniverses());
    for (ClassField field : classDef.getFields()) {
      if (!implementations.containsKey(field)) {
        continue;
      }
      field.getType(sortArgument).getCodomain().accept(new ProcessDefCallsVisitor<Void>() {
        @Override
        protected boolean processDefCall(DefCallExpression expression, Void param) {
          if (expression instanceof FieldCallExpression && classDef.getFields().contains(((FieldCallExpression) expression).getDefinition()) && !solution.isImplemented((ClassField) expression.getDefinition())) {
            implementations.remove(field);
            return true;
          }
          return false;
        }
      }, null);
    }
    solution.updateHasUniverses();
    solve(variable, solution);
  }

  private enum SolveResult { SOLVED, NOT_SOLVED, ERROR }

  private SolveResult solve(InferenceVariable var, Expression expr) {
    expr = expr.normalize(NormalizeVisitor.Mode.WHNF);
    if (expr.getInferenceVariable() == var) {
      return SolveResult.NOT_SOLVED;
    }
    if (myProps.contains(var) && !expr.isInstance(UniverseExpression.class)) {
      LocalError error = var.getErrorInfer(new UniverseExpression(Sort.PROP), expr);
      myVisitor.getErrorReporter().report(error);
      return SolveResult.ERROR;
    }

    if (expr.findBinding(var)) {
      return inferenceError(var, expr);
    }

    Expression expectedType = var.getType();
    Expression actualType = expr.getType();
    if (actualType == null || actualType.isLessOrEquals(expectedType, this, var.getSourceNode())) {
      Expression result = actualType == null ? null : ElimBindingVisitor.findBindings(expr, var.getBounds());
      if (result != null) {
        var.solve(this, OfTypeExpression.make(result, actualType, expectedType));
        return SolveResult.SOLVED;
      } else {
        return inferenceError(var, expr);
      }
    } else {
      LocalError error = var.getErrorMismatch(expectedType, actualType, expr);
      myVisitor.getErrorReporter().report(error);
      var.solve(this, new ErrorExpression(actualType, error));
      return SolveResult.ERROR;
    }
  }

  private SolveResult inferenceError(InferenceVariable var, Expression expr) {
    LocalError error = var.getErrorInfer(expr);
    myVisitor.getErrorReporter().report(error);
    var.solve(this, new ErrorExpression(null, error));
    return SolveResult.ERROR;
  }
}
