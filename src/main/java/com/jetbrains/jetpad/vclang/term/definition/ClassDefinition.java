package com.jetbrains.jetpad.vclang.term.definition;

import com.jetbrains.jetpad.vclang.naming.namespace.Namespace;
import com.jetbrains.jetpad.vclang.naming.namespace.SimpleStaticNamespaceProvider;
import com.jetbrains.jetpad.vclang.term.Abstract;
import com.jetbrains.jetpad.vclang.term.expr.ClassCallExpression;
import com.jetbrains.jetpad.vclang.term.expr.Expression;
import com.jetbrains.jetpad.vclang.term.expr.UniverseExpression;

import java.util.*;

import static com.jetbrains.jetpad.vclang.term.expr.ExpressionFactory.*;

public class ClassDefinition extends Definition {
  private final Namespace myOwnNamespace;
  private final Namespace myInstanceNamespace;

  private final Map<String, ClassField> myFields = new HashMap<>();
  private Set<ClassDefinition> mySuperClasses = null;

  public ClassDefinition(String name, Namespace ownNamespace, Namespace instanceNamespace) {
    super(name, Abstract.Binding.DEFAULT_PRECEDENCE);
    super.hasErrors(false);
    myOwnNamespace = ownNamespace;
    myInstanceNamespace = instanceNamespace;
  }

  public boolean isSubClassOf(ClassDefinition classDefinition) {
    if (this == classDefinition) {
      return true;
    }
    if (mySuperClasses == null) {
      return false;
    }
    for (ClassDefinition superClass : mySuperClasses) {
      if (superClass.isSubClassOf(classDefinition)) {
        return true;
      }
    }
    return false;
  }

  public void addSuperClass(ClassDefinition superClass) {
    if (mySuperClasses == null) {
      mySuperClasses = new HashSet<>();
    }
    mySuperClasses.add(superClass);
  }

  @Override
  public Expression getType() {
    return new UniverseExpression(getUniverse());
  }

  @Override
  public ClassCallExpression getDefCall() {
    return ClassCall(this, new HashMap<ClassField, ClassCallExpression.ImplementStatement>());
  }

  public ClassField getField(String name) {
    return myFields.get(name);
  }

  public Collection<ClassField> getFields() {
    return myFields.values();
  }

  public int getNumberOfVisibleFields() {
    int result = myFields.size();
    if (getParentField() != null) {
      --result;
    }
    return result;
  }

  public void addField(ClassField field) {
    myFields.put(field.getName(), field);
    field.setThisClass(this);
  }

  public ClassField tryAddField(ClassField field) {
    ClassField oldField = myFields.get(field.getName());
    if (oldField == field) {
      return null;
    }
    if (oldField != null) {
      return oldField;
    }
    myFields.put(field.getName(), field);
    return null;
  }

  public ClassField removeField(String name) {
    return myFields.remove(name);
  }

  public void removeField(ClassField field) {
    myFields.remove(field.getName());
  }

  public ClassField getParentField() {
    return getField("\\parent");
  }

  public void addParentField(ClassDefinition parentClass) {
    setThisClass(parentClass);
    ClassField field = new ClassField("\\parent", Abstract.Binding.DEFAULT_PRECEDENCE, ClassCall(parentClass), this, param("\\this", ClassCall(this)));
    addField(field);
    // TODO[\\parent] is this required?
    //getResolvedName().toNamespace().addDefinition(field);
  }

  @Override
  public Expression getTypeWithThis() {
    Expression type = getType();
    if (getThisClass() != null) {
      type = Pi(ClassCall(getThisClass()), type);
    }
    return type;
  }

  @Override
  public Namespace getOwnNamespace() {
    return myOwnNamespace;
  }

  @Override
  public Namespace getInstanceNamespace() {
    return myInstanceNamespace;
  }
}
