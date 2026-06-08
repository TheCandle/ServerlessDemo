package com.facebook.presto.execution.planadapter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

public class CanonicalCaseExpression extends CanonicalExpression
{
    private final CanonicalExpression operand;
    private final List<WhenClause> whenClauses;
    private final CanonicalExpression defaultValue;

    public CanonicalCaseExpression(CanonicalExpression operand, List<WhenClause> whenClauses, CanonicalExpression defaultValue)
    {
        this.operand = operand;
        this.whenClauses = Collections.unmodifiableList(new ArrayList<>(whenClauses));
        this.defaultValue = defaultValue;
    }

    public Optional<CanonicalExpression> getOperand()
    {
        return Optional.ofNullable(operand);
    }

    public List<WhenClause> getWhenClauses()
    {
        return whenClauses;
    }

    public Optional<CanonicalExpression> getDefaultValue()
    {
        return Optional.ofNullable(defaultValue);
    }

    public static class WhenClause
    {
        private final CanonicalExpression condition;
        private final CanonicalExpression result;

        public WhenClause(CanonicalExpression condition, CanonicalExpression result)
        {
            this.condition = condition;
            this.result = result;
        }

        public CanonicalExpression getCondition()
        {
            return condition;
        }

        public CanonicalExpression getResult()
        {
            return result;
        }
    }
}
