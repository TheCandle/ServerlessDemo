package com.facebook.presto.execution.planadapter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class CanonicalCallExpression extends CanonicalExpression
{
    private final String functionName;
    private final List<CanonicalExpression> arguments;

    public CanonicalCallExpression(String functionName, List<CanonicalExpression> arguments)
    {
        this.functionName = functionName;
        this.arguments = Collections.unmodifiableList(new ArrayList<>(arguments));
    }

    public String getFunctionName()
    {
        return functionName;
    }

    public List<CanonicalExpression> getArguments()
    {
        return arguments;
    }
}
