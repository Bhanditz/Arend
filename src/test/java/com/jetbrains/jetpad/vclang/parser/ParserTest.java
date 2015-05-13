package com.jetbrains.jetpad.vclang.parser;

import com.jetbrains.jetpad.vclang.term.Concrete;
import com.jetbrains.jetpad.vclang.term.definition.Definition;
import com.jetbrains.jetpad.vclang.term.definition.FunctionDefinition;
import com.jetbrains.jetpad.vclang.term.expr.arg.TelescopeArgument;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.jetbrains.jetpad.vclang.parser.ParserTestCase.*;
import static com.jetbrains.jetpad.vclang.term.expr.ExpressionFactory.*;
import static org.junit.Assert.*;

public class ParserTest {
  @Test
  public void parserLam() {
    Concrete.Expression expr = parseExpr("\\lam x y z => y");
    assertTrue(compare(Lam("x", Lam("y", Lam("z", Var("y")))), expr));
  }

  @Test
  public void parserLam2() {
    Concrete.Expression expr = parseExpr("\\lam x y => (\\lam z w => y z) y");
    assertTrue(compare(Lam("x'", Lam("y'", Apps(Lam("z'", Lam("w'", Apps(Var("y"), Var("z")))), Var("y")))), expr));
  }

  @Test
  public void parserLamTele() {
    Concrete.Expression expr = parseExpr("\\lam p {x t : Nat} {y} (a : Nat -> Nat) => (\\lam (z w : Nat) => y z) y");
    assertTrue(compare(Lam(lamArgs(Name("p"), Tele(false, vars("x", "t"), Var("Nat")), Name(false, "y"), Tele(vars("a"), Pi(Var("Nat"), Var("Nat")))), Apps(Lam(lamArgs(Tele(vars("z", "w"), Var("Nat"))), Apps(Var("y"), Var("z"))), Var("y"))), expr));
  }

  @Test
  public void parserPi() {
    Concrete.Expression expr = parseExpr("\\Pi (x y z : Nat) (w t : Nat -> Nat) -> \\Pi (a b : \\Pi (c : Nat) -> Nat c) -> Nat b y w");
    assertTrue(compare(Pi(args(Tele(vars("x", "y", "z"), Var("Nat")), Tele(vars("w", "t"), Pi(Var("Nat"), Var("Nat")))), Pi(args(Tele(vars("a", "b"), Pi("c", Var("Nat"), Apps(Var("Nat"), Var("c"))))), Apps(Var("Nat"), Var("b"), Var("y"), Var("w")))), expr));
  }

  @Test
  public void parserPi2() {
    Concrete.Expression expr = parseExpr("\\Pi (x y : Nat) (z : Nat x -> Nat y) -> Nat z y x");
    assertTrue(compare(Pi(args(Tele(vars("x", "y"), Var("Nat")), Tele(vars("z"), Pi(Apps(Var("Nat"), Var("x")), Apps(Var("Nat"), Var("y"))))), Apps(Var("Nat"), Var("z"), Var("y"), Var("x"))), expr));
  }

  @Test
  public void parserLamOpen() {
    Concrete.Expression expr = parseExpr("\\lam x => (\\Pi (y : Nat) -> (\\lam y => y)) y");
    assertTrue(compare(Lam("x", Apps(Pi("y", Var("Nat"), Lam("y", Var("y"))), Var("y"))), expr));
  }

  @Test
  public void parserPiOpen() {
    Concrete.Expression expr = parseExpr("\\Pi (a b : Nat a) -> Nat a b");
    assertTrue(compare(Pi(args(Tele(vars("a", "b"), Apps(Var("Nat"), Var("a")))), Apps(Var("Nat"), Var("a"), Var("b"))), expr));
  }

  @Test
  public void parserDef() {
    List<Concrete.Definition> defs = parseDefs(
        "\\function x : Nat => zero\n" +
            "\\function y : Nat => x");
    assertEquals(2, defs.size());
  }

  @Test
  public void parserDefType() {
    List<Concrete.Definition> defs = parseDefs(
        "\\function x : \\Type0 => Nat\n" +
            "\\function y : x => zero");
    assertEquals(2, defs.size());
  }

  @Test
  public void parserImplicit() {
    Concrete.FunctionDefinition def = (Concrete.FunctionDefinition) parseDef("\\function f (x y : Nat) {z w : Nat} (t : Nat) {r : Nat} : Nat x y z w t r => Nat");
    assertEquals(4, def.getArguments().size());
    assertTrue(def.getArgument(0).getExplicit());
    assertFalse(def.getArgument(1).getExplicit());
    assertTrue(def.getArgument(2).getExplicit());
    assertFalse(def.getArgument(3).getExplicit());
    assertTrue(compare(Var("Nat"), def.getArgument(0).getType()));
    assertTrue(compare(Var("Nat"), def.getArgument(1).getType()));
    assertTrue(compare(Var("Nat"), def.getArgument(2).getType()));
    assertTrue(compare(Var("Nat"), def.getArgument(3).getType()));
    assertTrue(compare(Apps(Var("Nat"), Var("x"), Var("y"), Var("z"), Var("w"), Var("t"), Var("r")), def.getResultType()));
  }

  @Test
  public void parserImplicit2() {
    Concrete.FunctionDefinition def = (Concrete.FunctionDefinition) parseDef("\\function f {x : Nat} (_ : Nat) {y z : Nat} (_ : Nat x y z) : Nat => Nat");
    assertEquals(4, def.getArguments().size());
    assertFalse(def.getArgument(0).getExplicit());
    assertTrue(def.getArgument(1).getExplicit());
    assertFalse(def.getArgument(2).getExplicit());
    assertTrue(def.getArgument(3).getExplicit());
    assertTrue(compare(Var("Nat"), def.getArgument(0).getType()));
    assertTrue(compare(Var("Nat"), def.getArgument(1).getType()));
    assertTrue(compare(Var("Nat"), def.getArgument(2).getType()));
    assertTrue(compare(Apps(Var("Nat"), Var("x"), Var("y"), Var("z")), def.getArgument(3).getType()));
    assertTrue(compare(Var("Nat"), def.getResultType()));
  }

  @Test
  public void parserInfix() {
    Map<String, Definition> definitions = new HashMap<>();
    Definition plus = new FunctionDefinition("+", new Definition.Precedence(Definition.Associativity.LEFT_ASSOC, (byte) 6), Definition.Fixity.INFIX, new ArrayList<TelescopeArgument>(), Var("Nat"), Definition.Arrow.LEFT, Var("+"));
    Definition mul = new FunctionDefinition("*", new Definition.Precedence(Definition.Associativity.LEFT_ASSOC, (byte) 7), Definition.Fixity.INFIX, new ArrayList<TelescopeArgument>(), Var("Nat"), Definition.Arrow.LEFT, Var("*"));
    definitions.put("+", plus);
    definitions.put("*", mul);
    Concrete.Expression expr = parseExpr("a + b * c + d * (e * f) * (g + h)", definitions);
    assertTrue(compare(BinOp(BinOp(Var("a"), plus, BinOp(Var("b"), mul, Var("c"))), plus, BinOp(BinOp(Var("d"), mul, BinOp(Var("e"), mul, Var("f"))), mul, BinOp(Var("g"), plus, Var("h")))), expr));
  }

  @Test
  public void parserInfixDef() {
    List<Concrete.Definition> defs = parseDefs(
        "\\function (+) : Nat -> Nat -> Nat => \\lam x y => x\n" +
            "\\function (*) : Nat -> Nat => \\lam x => x + zero");
    assertEquals(2, defs.size());
  }

  @Test
  public void parserInfixError() {
    Map<String, Definition> definitions = new HashMap<>();
    Definition plus = new FunctionDefinition("+", new Definition.Precedence(Definition.Associativity.LEFT_ASSOC, (byte) 6), Definition.Fixity.INFIX, new ArrayList<TelescopeArgument>(), Var("Nat"), Definition.Arrow.LEFT, Var("+"));
    Definition mul = new FunctionDefinition("*", new Definition.Precedence(Definition.Associativity.RIGHT_ASSOC, (byte) 6), Definition.Fixity.INFIX, new ArrayList<TelescopeArgument>(), Var("Nat"), Definition.Arrow.LEFT, Var("*"));
    definitions.put("+", plus);
    definitions.put("*", mul);

    BuildVisitor builder = new BuildVisitor(definitions);
    builder.visitExpr(parse("a + b * c", builder.getErrors()).expr());
    assertEquals(1, builder.getErrors().size());
  }
}
