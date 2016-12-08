package com.jetbrains.jetpad.vclang.term.expr.subst;

import com.jetbrains.jetpad.vclang.term.context.binding.LevelBinding;
import com.jetbrains.jetpad.vclang.term.definition.Referable;
import com.jetbrains.jetpad.vclang.term.expr.sort.Level;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class LevelSubstitution {
  private final Map<Referable, Level> myLevels;

  public LevelSubstitution() {
    myLevels = new HashMap<>();
  }

  public LevelSubstitution(Map<Referable, Level> levels) {
    myLevels = levels;
  }

  public boolean isEmpty() {
    return myLevels.isEmpty();
  }

  public Level get(Referable variable) {
    return myLevels.get(variable);
  }

  public void add(Referable variable, Level level) {
    myLevels.put(variable, level);
  }

  public static List<LevelBinding> clone(List<LevelBinding> params, LevelSubstitution subst) {
    List<LevelBinding> newParams = new ArrayList<>();

    for (LevelBinding param : params) {
      LevelBinding newParam = new LevelBinding(param.getName(), param.getType());
      subst.add(param, new Level(newParam));
      newParams.add(newParam);
    }

    return newParams;
  }
}
