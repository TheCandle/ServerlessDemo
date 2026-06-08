package com.facebook.presto.execution.planadapter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class CanonicalLogicalExpression extends CanonicalExpression
{
    public enum Operator
    {
        AND,
        OR
    }

    private final Operator operator;
    private final List<CanonicalExpression> arguments;

    public CanonicalLogicalExpression(Operator operator, List<CanonicalExpression> arguments)
    {
        this.operator = operator;
        this.arguments = Collections.unmodifiableList(new ArrayList<>(arguments));
    }

    public Operator getOperator()
    {
        return operator;
    }

    public List<CanonicalExpression> getArguments()
    {
        return arguments;
    }
}
