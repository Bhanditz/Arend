package org.arend.typechecking.typecheckable.provider;

import org.arend.naming.reference.ClassReferable;
import org.arend.naming.reference.GlobalReferable;
import org.arend.term.concrete.Concrete;

import javax.annotation.Nullable;

public class EmptyConcreteProvider implements ConcreteProvider {
  public final static EmptyConcreteProvider INSTANCE = new EmptyConcreteProvider();

  private EmptyConcreteProvider() {}

  @Nullable
  @Override
  public Concrete.ReferableDefinition getConcrete(GlobalReferable referable) {
    return null;
  }

  @Nullable
  @Override
  public Concrete.FunctionDefinition getConcreteFunction(GlobalReferable referable) {
    return null;
  }

  @Nullable
  @Override
  public Concrete.Instance getConcreteInstance(GlobalReferable referable) {
    return null;
  }

  @Nullable
  @Override
  public Concrete.ClassDefinition getConcreteClass(ClassReferable referable) {
    return null;
  }

  @Nullable
  @Override
  public Concrete.DataDefinition getConcreteData(GlobalReferable referable) {
    return null;
  }
}
