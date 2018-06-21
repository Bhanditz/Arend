package com.jetbrains.jetpad.vclang.naming.reference;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collection;

public interface ClassReferable extends LocatedReferable {
  @Nonnull Collection<? extends ClassReferable> getSuperClassReferences();
  @Nonnull Collection<? extends LocatedReferable> getFieldReferables();
  @Override @Nullable ClassReferable getUnderlyingReference();
}
