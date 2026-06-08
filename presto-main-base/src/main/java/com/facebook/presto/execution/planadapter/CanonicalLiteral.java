package com.facebook.presto.execution.planadapter;

public class CanonicalLiteral extends CanonicalExpression
{
    private final Object value;

    public CanonicalLiteral(Object value)
    {
        this.value = value;
    }

    public Object getValue()
    {
        return value;
    }
}
