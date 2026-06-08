package com.facebook.presto.execution.planadapter;

public class CanonicalComparisonExpression extends CanonicalExpression
{
    public enum Operator
    {
        EQUAL,
        NOT_EQUAL,
        GREATER_THAN,
        GREATER_THAN_OR_EQUAL,
        LESS_THAN,
        LESS_THAN_OR_EQUAL
    }

    private final Operator operator;
    private final CanonicalExpression left;
    private final CanonicalExpression right;

    public CanonicalComparisonExpression(Operator operator, CanonicalExpression left, CanonicalExpression right)
    {
        this.operator = operator;
        this.left = left;
        this.right = right;
    }

    public Operator getOperator()
    {
        return operator;
    }

    public CanonicalExpression getLeft()
    {
        return left;
    }

    public CanonicalExpression getRight()
    {
        return right;
    }
}
