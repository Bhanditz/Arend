package com.jetbrains.jetpad.vclang.typechecking.constructions;

import com.jetbrains.jetpad.vclang.typechecking.Matchers;
import com.jetbrains.jetpad.vclang.typechecking.TypeCheckingTestCase;
import org.junit.Test;

public class Case extends TypeCheckingTestCase {
  @Test
  public void testCase() {
    typeCheckModule(
      "\\data Bool | true | false\n" +
      "\\func f (b : Bool) : Bool => \\case b \\with { | true => false | false => true }");
  }

  @Test
  public void testCaseReturn() {
    typeCheckModule(
      "\\data Bool | true | false\n" +
      "\\func not (b : Bool) => \\case b \\return Bool \\with { | true => false | false => true }\n" +
      "\\func f (b : Bool) => \\case b \\as x \\return not (not x) = x \\with { | true => path (\\lam _ => true) | false => path (\\lam _ => false) }");
  }

  @Test
  public void testCaseReturnError() {
    typeCheckModule(
      "\\data Bool | true | false\n" +
      "\\func not (b : Bool) => \\case b \\return Bool \\with { | true => false | false => true }\n" +
      "\\func f (b : Bool) => \\case b \\return not (not b) = b \\with { | true => path (\\lam _ => true) | false => path (\\lam _ => false) }", 2);
    assertThatErrorsAre(Matchers.error(), Matchers.error());
  }

  @Test
  public void testCaseArguments() {
    typeCheckModule(
      "\\data Bool | true | false\n" +
      "\\func not (b : Bool) => \\case b \\return Bool \\with { | true => false | false => true }\n" +
      "\\data Or (A B : \\Type) | inl A | inr B\n" +
      "\\func idp {A : \\Type} {a : A} => path (\\lam _ => a)\n" +
      "\\func f (b : Bool) : (b = true) `Or` (b = false) => \\case b \\as x, idp : b = x \\with { | true, p => inl p | false, p => inr p }");
  }
}
