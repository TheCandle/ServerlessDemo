package com.facebook.presto.execution.planadapter;

import com.facebook.presto.common.type.BooleanType;
import com.facebook.presto.common.type.DoubleType;
import com.facebook.presto.common.type.VarcharType;
import com.facebook.presto.spi.relation.CallExpression;
import com.facebook.presto.spi.relation.ConstantExpression;
import com.facebook.presto.spi.relation.RowExpression;
import com.facebook.presto.spi.relation.SpecialFormExpression;
import com.facebook.presto.spi.relation.VariableReferenceExpression;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public class OpengaussExpressionTranslator
{
    public RowExpression translateToRowExpression(CanonicalExpression expression)
    {
        if (expression instanceof CanonicalLiteral) {
            Object value = ((CanonicalLiteral) expression).getValue();
            if (value instanceof Boolean) {
                return new ConstantExpression(value, BooleanType.BOOLEAN);
            }
            if (value instanceof Number) {
                return new ConstantExpression(((Number) value).doubleValue(), DoubleType.DOUBLE);
            }
            return new ConstantExpression(String.valueOf(value), VarcharType.VARCHAR);
        }
        if (expression instanceof CanonicalSymbolReference) {
            return new VariableReferenceExpression(Optional.empty(), ((CanonicalSymbolReference) expression).getName(), VarcharType.VARCHAR);
        }
        if (expression instanceof CanonicalCallExpression) {
            CanonicalCallExpression call = (CanonicalCallExpression) expression;
            String name = call.getFunctionName().toLowerCase(Locale.ENGLISH);
            List<RowExpression> args = new ArrayList<>();
            for (CanonicalExpression arg : call.getArguments()) {
                args.add(translateToRowExpression(arg));
            }
            if (name.equals("cast")) {
                return translateCast(call, args);
            }
            if (name.equals("substring") || name.equals("+") || name.equals("-") || name.equals("*") || name.equals("/") || name.equals("not")) {
                return new CallExpression(name, new PassthroughFunctionHandle(name), inferType(name, args), args);
            }
            return new CallExpression(name, new PassthroughFunctionHandle(name), VarcharType.VARCHAR, args);
        }
        if (expression instanceof CanonicalComparisonExpression) {
            CanonicalComparisonExpression comparison = (CanonicalComparisonExpression) expression;
            return new CallExpression(comparison.getOperator().name().toLowerCase(Locale.ENGLISH), new PassthroughFunctionHandle(comparison.getOperator().name().toLowerCase(Locale.ENGLISH)), BooleanType.BOOLEAN, List.of(translateToRowExpression(comparison.getLeft()), translateToRowExpression(comparison.getRight())));
        }
        if (expression instanceof CanonicalLogicalExpression) {
            CanonicalLogicalExpression logical = (CanonicalLogicalExpression) expression;
            List<RowExpression> args = new ArrayList<>();
            for (CanonicalExpression arg : logical.getArguments()) {
                args.add(translateToRowExpression(arg));
            }
            return new SpecialFormExpression(logical.getOperator() == CanonicalLogicalExpression.Operator.AND ? SpecialFormExpression.Form.AND : SpecialFormExpression.Form.OR, BooleanType.BOOLEAN, args);
        }
        if (expression instanceof CanonicalCaseExpression) {
            return translateCase((CanonicalCaseExpression) expression);
        }
        return new ConstantExpression(true, BooleanType.BOOLEAN);
    }

    public CanonicalExpression translateSimpleFilter(String filter)
    {
        if (filter == null || filter.isEmpty()) {
            return new CanonicalLiteral(true);
        }
        return new CanonicalLiteral(true);
    }

    private RowExpression translateCast(CanonicalCallExpression call, List<RowExpression> args)
    {
        if (args.isEmpty()) {
            return new ConstantExpression(null, VarcharType.VARCHAR);
        }
        String targetType = call.getArguments().size() > 1 && call.getArguments().get(1) instanceof CanonicalSymbolReference
                ? ((CanonicalSymbolReference) call.getArguments().get(1)).getName()
                : call.getArguments().size() > 1 && call.getArguments().get(1) instanceof CanonicalLiteral
                        ? String.valueOf(((CanonicalLiteral) call.getArguments().get(1)).getValue())
                        : null;
        RowExpression value = args.get(0);
        return new CallExpression("cast", new PassthroughFunctionHandle("cast"), inferCastType(targetType, value), List.of(value));
    }

    private RowExpression translateCase(CanonicalCaseExpression caseExpression)
    {
        List<RowExpression> args = new ArrayList<>();
        boolean simpleCase = caseExpression.getOperand().isPresent();
        caseExpression.getOperand().ifPresent(operand -> args.add(translateToRowExpression(operand)));
        for (CanonicalCaseExpression.WhenClause clause : caseExpression.getWhenClauses()) {
            if (simpleCase) {
                args.add(translateToRowExpression(clause.getCondition()));
            }
            else {
                args.add(translateToRowExpression(clause.getCondition()));
            }
            args.add(translateToRowExpression(clause.getResult()));
        }
        caseExpression.getDefaultValue().ifPresent(defaultValue -> args.add(translateToRowExpression(defaultValue)));
        return new SpecialFormExpression(simpleCase ? SpecialFormExpression.Form.SWITCH : SpecialFormExpression.Form.WHEN, inferCaseType(caseExpression), args);
    }

    private com.facebook.presto.common.type.Type inferCaseType(CanonicalCaseExpression caseExpression)
    {
        if (caseExpression.getDefaultValue().isPresent()) {
            RowExpression defaultValue = translateToRowExpression(caseExpression.getDefaultValue().get());
            return defaultValue.getType();
        }
        if (!caseExpression.getWhenClauses().isEmpty()) {
            return translateToRowExpression(caseExpression.getWhenClauses().get(0).getResult()).getType();
        }
        return VarcharType.VARCHAR;
    }

    private com.facebook.presto.common.type.Type inferCastType(String targetType, RowExpression value)
    {
        if (targetType == null) {
            return value.getType();
        }
        String normalized = targetType.toLowerCase(Locale.ENGLISH);
        if (normalized.contains("bool")) {
            return BooleanType.BOOLEAN;
        }
        if (normalized.contains("double") || normalized.contains("float") || normalized.contains("numeric") || normalized.contains("decimal") || normalized.contains("real")) {
            return DoubleType.DOUBLE;
        }
        if (normalized.contains("char") || normalized.contains("text") || normalized.contains("string") || normalized.contains("varchar")) {
            return VarcharType.VARCHAR;
        }
        return value.getType();
    }

    private com.facebook.presto.common.type.Type inferType(String name, List<RowExpression> args)
    {
        if (name.equals("substring")) {
            return VarcharType.VARCHAR;
        }
        if (name.equals("not")) {
            return BooleanType.BOOLEAN;
        }
        if (name.equals("+") || name.equals("-") || name.equals("*") || name.equals("/")) {
            return DoubleType.DOUBLE;
        }
        return args.isEmpty() ? VarcharType.VARCHAR : args.get(0).getType();
    }
}
