package com.facebook.presto.execution.planadapter;

public class CanonicalSymbolReference extends CanonicalExpression
{
    private final String name;

    public CanonicalSymbolReference(String name)
    {
        this.name = name;
    }

    public String getName()
    {
        return name;
    }
}
