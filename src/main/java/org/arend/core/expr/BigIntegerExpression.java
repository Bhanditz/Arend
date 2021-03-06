package org.arend.core.expr;

import java.math.BigInteger;

import static org.arend.core.expr.ExpressionFactory.Neg;
import static org.arend.core.expr.ExpressionFactory.Pos;

public class BigIntegerExpression extends IntegerExpression {
  private final BigInteger myInteger;

  public BigIntegerExpression(BigInteger integer) {
    myInteger = integer;
  }

  @Override
  public BigInteger getBigInteger() {
    return myInteger;
  }

  @Override
  public BigIntegerExpression suc() {
    return new BigIntegerExpression(myInteger.add(BigInteger.ONE));
  }

  @Override
  public IntegerExpression pred() {
    return new BigIntegerExpression(myInteger.subtract(BigInteger.ONE));
  }

  @Override
  public boolean isZero() {
    return myInteger.equals(BigInteger.ZERO);
  }

  @Override
  public boolean isNatural() {
    return myInteger.compareTo(BigInteger.ZERO) >= 0;
  }

  @Override
  public boolean isEqual(IntegerExpression expr) {
    return expr instanceof SmallIntegerExpression ? myInteger.equals(BigInteger.valueOf(((SmallIntegerExpression) expr).getInteger())) : myInteger.equals(expr.getBigInteger());
  }

  @Override
  public int compare(IntegerExpression expr) {
    return myInteger.compareTo(expr.getBigInteger());
  }

  @Override
  public BigIntegerExpression plus(IntegerExpression expr) {
    return new BigIntegerExpression(myInteger.add(expr.getBigInteger()));
  }

  @Override
  public BigIntegerExpression mul(IntegerExpression expr) {
    return new BigIntegerExpression(myInteger.multiply(expr.getBigInteger()));
  }

  @Override
  public ConCallExpression minus(IntegerExpression expr) {
    BigInteger other = expr.getBigInteger();
    return myInteger.compareTo(other) >= 0 ? Pos(new BigIntegerExpression(myInteger.subtract(other))) : Neg(new BigIntegerExpression(other.subtract(myInteger)));
  }
}
