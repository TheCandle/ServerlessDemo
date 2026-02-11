package com.facebook.presto.execution;

import com.facebook.presto.Session;
import com.facebook.presto.common.type.Type;
import com.facebook.presto.metadata.Metadata;
import com.facebook.presto.metadata.MetadataManager;
import com.facebook.presto.spi.WarningCollector;
import com.facebook.presto.spi.relation.RowExpression;
import com.facebook.presto.sql.ExpressionUtils;
import com.facebook.presto.sql.analyzer.ExpressionAnalyzer;
import com.facebook.presto.sql.analyzer.Scope;
import com.facebook.presto.sql.parser.SqlParser;
import com.facebook.presto.sql.planner.ExpressionInterpreter;
import com.facebook.presto.sql.planner.LiteralEncoder;
import com.facebook.presto.sql.planner.NoOpVariableResolver;
import com.facebook.presto.sql.planner.TypeProvider;
import com.facebook.presto.sql.relational.RowExpressionOptimizer;
import com.facebook.presto.sql.relational.SqlToRowExpressionTranslator;
import com.facebook.presto.sql.tree.Expression;
import com.facebook.presto.sql.tree.NodeRef;
import com.google.common.collect.ImmutableMap;

import java.util.Map;

//import static com.facebook.presto.SessionTestUtils.TEST_SESSION;
import static com.facebook.presto.spi.relation.ExpressionOptimizer.Level.OPTIMIZED;
import static java.util.Collections.emptyMap;

public class RowExpressionTranslator
{
    private final Metadata metadata;
    private final LiteralEncoder literalEncoder;
    private final Session session;

    public RowExpressionTranslator(Metadata metadata)
    {
        this.metadata = metadata;
        this.literalEncoder = new LiteralEncoder(metadata.getBlockEncodingSerde());
        this.session = null;
    }

    public RowExpressionTranslator(Metadata metadata, Session session)
    {
        this.metadata = metadata;
        this.literalEncoder = new LiteralEncoder(metadata.getBlockEncodingSerde());
        this.session = session;
    }

    public RowExpressionTranslator()
    {
        this(MetadataManager.createTestMetadataManager());
    }

    public RowExpression translateAndOptimize(Expression expression)
    {
        return translateAndOptimize(expression, getExpressionTypes(expression, TypeProvider.empty()));
    }

    public RowExpression translateAndOptimize(Expression expression, TypeProvider typeProvider)
    {
        return translateAndOptimize(expression, getExpressionTypes(expression, typeProvider));
    }

    public RowExpression translate(String sql, Map<String, Type> types)
    {
        return translate(ExpressionUtils.rewriteIdentifiersToSymbolReferences(new SqlParser().createExpression(sql)), TypeProvider.viewOf(types));
    }

    public RowExpression translate(Expression expression, TypeProvider typeProvider)
    {
        return SqlToRowExpressionTranslator.translate(
                expression,
                getExpressionTypes(expression, typeProvider),
                ImmutableMap.of(),
                metadata.getFunctionAndTypeManager(),
                session);
    }

    public RowExpression translateAndOptimize(Expression expression, Map<NodeRef<Expression>, Type> types)
    {
        RowExpression rowExpression = SqlToRowExpressionTranslator.translate(expression, types, ImmutableMap.of(), metadata.getFunctionAndTypeManager(), session);
        RowExpressionOptimizer optimizer = new RowExpressionOptimizer(metadata);
        return optimizer.optimize(rowExpression, OPTIMIZED, session.toConnectorSession());
    }

    Expression simplifyExpression(Expression expression)
    {
        // Testing simplified expressions is important, since simplification may create CASTs or function calls that cannot be simplified by the ExpressionOptimizer

        Map<NodeRef<Expression>, Type> expressionTypes = getExpressionTypes(expression, TypeProvider.empty());
        ExpressionInterpreter interpreter = ExpressionInterpreter.expressionOptimizer(expression, metadata, session, expressionTypes);
        Object value = interpreter.optimize(NoOpVariableResolver.INSTANCE);
        return literalEncoder.toExpression(value, expressionTypes.get(NodeRef.of(expression)));
    }

    private Map<NodeRef<Expression>, Type> getExpressionTypes(Expression expression, TypeProvider typeProvider)
    {
        ExpressionAnalyzer expressionAnalyzer = ExpressionAnalyzer.createWithoutSubqueries(
                metadata.getFunctionAndTypeManager().getFunctionAndTypeResolver(),
                session,
                typeProvider,
                emptyMap(),
                node -> new IllegalStateException("Unexpected node: %s" + node),
                WarningCollector.NOOP,
                false);
        expressionAnalyzer.analyze(expression, Scope.create());
        return expressionAnalyzer.getExpressionTypes();
    }
}
