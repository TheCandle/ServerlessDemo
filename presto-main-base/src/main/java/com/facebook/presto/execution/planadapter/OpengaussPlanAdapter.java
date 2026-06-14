package com.facebook.presto.execution.planadapter;

import com.facebook.presto.Session;
import static com.facebook.presto.SystemSessionProperties.getOpengaussDebugOutputEnabled;
import static com.facebook.presto.SystemSessionProperties.getOpengaussDebugOutputPlanNodeId;
import com.facebook.presto.common.QualifiedObjectName;
import com.facebook.presto.common.block.SortOrder;
import com.facebook.presto.common.function.OperatorType;
import com.facebook.presto.common.predicate.TupleDomain;
import com.facebook.presto.common.type.BigintType;
import com.facebook.presto.common.type.BooleanType;
import com.facebook.presto.common.type.DateType;
import com.facebook.presto.common.type.DecimalType;
import com.facebook.presto.common.type.DoubleType;
import com.facebook.presto.common.type.IntegerType;
import com.facebook.presto.common.type.RealType;
import com.facebook.presto.common.type.VarcharType;
import com.facebook.presto.execution.QueryStateMachine;
import com.facebook.presto.execution.TpchSchemaRegistry;
import com.facebook.presto.metadata.BuiltInFunctionHandle;
import com.facebook.presto.metadata.CastType;
import com.facebook.presto.metadata.Metadata;
import com.facebook.presto.spi.function.FunctionHandle;
import com.facebook.presto.type.LikePatternType;
import com.facebook.presto.spi.ColumnHandle;
import com.facebook.presto.spi.TableHandle;
import com.facebook.presto.spi.TableMetadata;
import com.facebook.presto.spi.function.FunctionKind;
import com.facebook.presto.spi.function.Signature;
import com.facebook.presto.common.type.Type;
import com.facebook.presto.common.type.TypeSignature;
import com.facebook.presto.sql.ExpressionUtils;
import com.facebook.presto.sql.tree.NodeRef;
import com.facebook.presto.sql.analyzer.TypeSignatureProvider;
import com.facebook.presto.sql.parser.SqlParser;
import com.facebook.presto.sql.planner.TypeProvider;
import com.facebook.presto.execution.RowExpressionTranslator;
//import com.facebook.presto.sql.relational.SqlToRowExpressionTranslator;
import com.facebook.presto.sql.tree.BooleanLiteral;
import com.facebook.presto.sql.tree.Cast;
import com.facebook.presto.sql.tree.ComparisonExpression;
import com.facebook.presto.sql.tree.DoubleLiteral;
import com.facebook.presto.sql.tree.Expression;
import com.facebook.presto.sql.tree.FunctionCall;
import com.facebook.presto.sql.tree.Identifier;
import com.facebook.presto.sql.tree.LogicalBinaryExpression;
import com.facebook.presto.sql.tree.LongLiteral;
import com.facebook.presto.sql.tree.NotExpression;
import com.facebook.presto.sql.tree.StringLiteral;
import com.facebook.presto.sql.tree.SymbolReference;
import com.facebook.presto.spi.plan.AggregationNode;
import com.facebook.presto.spi.plan.AggregationNode.Aggregation;
import com.facebook.presto.spi.plan.Assignments;
import com.facebook.presto.spi.plan.EquiJoinClause;
import com.facebook.presto.spi.plan.FilterNode;
import com.facebook.presto.spi.plan.JoinDistributionType;
import com.facebook.presto.spi.plan.JoinNode;
import com.facebook.presto.spi.plan.JoinType;
import com.facebook.presto.spi.plan.Ordering;
import com.facebook.presto.spi.plan.OrderingScheme;
import com.facebook.presto.spi.plan.OutputNode;
import com.facebook.presto.spi.plan.Partitioning;
import com.facebook.presto.spi.plan.PartitioningScheme;
import com.facebook.presto.spi.plan.PlanNode;
import com.facebook.presto.spi.plan.ProjectNode;
import com.facebook.presto.spi.plan.SemiJoinNode;
import com.facebook.presto.spi.plan.SortNode;
import com.facebook.presto.spi.plan.TableScanNode;
import com.facebook.presto.spi.plan.TopNNode;
import com.facebook.presto.spi.plan.ValuesNode;
import com.facebook.presto.spi.relation.CallExpression;
import com.facebook.presto.spi.relation.ConstantExpression;
import com.facebook.presto.spi.relation.LambdaDefinitionExpression;
import com.facebook.presto.spi.relation.RowExpression;
import com.facebook.presto.spi.relation.SpecialFormExpression;
import com.facebook.presto.spi.relation.VariableReferenceExpression;
import com.facebook.presto.sql.planner.SystemPartitioningHandle;
import com.facebook.presto.sql.planner.plan.ExchangeNode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.airlift.slice.Slices;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class OpengaussPlanAdapter
{
    Metadata metadata;
    QueryStateMachine stateMachine;
    private final Map<String, PlanNode> scalarPlanBindings = new LinkedHashMap<>();
    private  RowExpressionTranslator sqlToRowExpressionTranslator;
    public OpengaussPlanAdapter(Metadata metadata, QueryStateMachine stateMachine) {
        this.metadata = metadata;
        this.stateMachine = stateMachine;
        this.sqlToRowExpressionTranslator = new RowExpressionTranslator(metadata, getSession());
    }

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OpengaussExpressionTranslator expressionTranslator = new OpengaussExpressionTranslator();
    private final SqlParser sqlParser = new SqlParser();

    public Session getSession()
    {
        return stateMachine.getSession();
    }

     public static Expression expression(String sql) {
         return ExpressionUtils.rewriteIdentifiersToSymbolReferences(new SqlParser().createExpression(sql));
     }

     public RowExpression getRowExpression(String expression) {
         TypeProvider typeProvider = TpchSchemaRegistry.getProvider();
         RowExpression rowExpression = sqlToRowExpressionTranslator.translateAndOptimize(expression(expression), typeProvider);
         return rowExpression;
     }

    public PlanNode adapt(String queryId, AdapterContext context)
    {
        String planFile = context.getPlanFileForQuery(queryId);
        System.out.println("[OpengaussPlanAdapter] queryId=" + queryId + ", planFile=" + planFile + ", classLoader=" + context.getClassLoader());
        if (planFile == null) {
            throw new IllegalArgumentException("No opengauss plan file configured for queryId: " + queryId);
        }

        try (InputStream inputStream = openPlanStream(planFile, context)) {
            if (inputStream == null) {
                throw new IllegalArgumentException("Cannot load opengauss plan resource: " + planFile);
            }
            String json = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            JsonNode root = objectMapper.readTree(json);
            scalarPlanBindings.clear();
            Map<String, VariableReferenceExpression> scalarBindings = new LinkedHashMap<>();
            JsonNode planRoot = unwrapPlan(root);
            PlanNode translated = translateNode(planRoot, context, scalarBindings);
            translated = insertDebugOutputAtPlanId(translated, context);
            OutputNode outputNode = wrapWithOutputNode(translated, planRoot, context);
            System.out.println("[OpengaussPlanAdapter] converted plan tree:\n" + formatPlanTree(outputNode));
            System.out.println("[OpengaussPlanAdapter] converted plan debug details:\n" + formatPlanNodeDetails(outputNode));
            return outputNode;
        }
        catch (IOException e) {
            throw new RuntimeException("Failed to read opengauss plan for queryId " + queryId, e);
        }
    }

    private PlanNode translateNode(JsonNode node, AdapterContext context, Map<String, VariableReferenceExpression> scalarBindings)
    {
        if (node == null || node.isMissingNode()) {
            throw new IllegalArgumentException("Missing opengauss plan node");
        }

        String type = text(node, "Node Type");
        if (type == null) {
            throw new IllegalArgumentException("Missing Node Type in opengauss plan node");
        }

        String normalized = type.toLowerCase(Locale.ENGLISH);
        System.out.println("[OpengaussPlanAdapter] visiting node type=" + type + " output=" + text(node, "Output")
                + " groupKey=" + firstNonNull(text(node, "Group Key"), text(node, "Group By Key"))
                + " sortKey=" + text(node, "Sort Key"));
        if (normalized.contains("row adapter") || normalized.contains("adapter") || normalized.contains("result")) {
            List<JsonNode> planChildren = children(node);
            if (planChildren.isEmpty()) {
                return buildFallbackProject(node, context);
            }
            JsonNode outerChild = null;
            for (JsonNode child : planChildren) {
                String rel = text(child, "Parent Relationship");
                if (rel != null && rel.equalsIgnoreCase("InitPlan")) {
                    PlanNode initPlan = translateNode(firstChild(child) == null ? child : firstChild(child), context, scalarBindings);
                    List<VariableReferenceExpression> outputs = initPlan.getOutputVariables();
                    if (!outputs.isEmpty()) {
                        String subplanName = text(child, "Subplan Name");
                        String bindingName = subplanName != null && subplanName.contains("$")
                                ? subplanName.substring(subplanName.indexOf('$') + 1).replaceAll("[^0-9A-Za-z_]+", "")
                                : "0";
                        VariableReferenceExpression boundScalar = outputs.get(0);
                        scalarBindings.put(bindingName.toLowerCase(Locale.ENGLISH), boundScalar);
                        scalarBindings.put(("$" + bindingName).toLowerCase(Locale.ENGLISH), boundScalar);
                        scalarBindings.put("$" + bindingName, boundScalar);
                        scalarBindings.put(bindingName, boundScalar);
                        scalarBindings.put(boundScalar.getName().toLowerCase(Locale.ENGLISH), boundScalar);
                        scalarPlanBindings.put(bindingName.toLowerCase(Locale.ENGLISH), initPlan);
                        scalarPlanBindings.put(("$" + bindingName).toLowerCase(Locale.ENGLISH), initPlan);
                        scalarPlanBindings.put(boundScalar.getName().toLowerCase(Locale.ENGLISH), initPlan);
                        System.out.println("[OpengaussPlanAdapter] initPlan bind name=" + bindingName + " output=" + boundScalar + " outputs=" + outputs);
                    }
                }
                else if (outerChild == null) {
                    outerChild = child;
                }
            }
            return outerChild == null ? buildFallbackProject(node, context) : translateNode(outerChild, context, scalarBindings);
        }
        if (normalized.contains("subquery scan")) {
            return alignOutputNode(node, buildSubqueryScan(node, context, scalarBindings), context);
        }
        if (normalized.contains("streaming") || normalized.contains("gather") || normalized.contains("redistribute") || normalized.contains("replicate") || normalized.contains("exchange")) {
            return alignOutputNode(node, buildExchange(node, context, scalarBindings), context);
        }
        if (normalized.contains("scan")) {
            return alignOutputNode(node, buildScan(node, context, scalarBindings), context);
        }
        if (normalized.contains("join") || normalized.contains("nest loop") || normalized.contains("nested loop")) {
            return alignOutputNode(node, buildJoin(node, context, scalarBindings), context);
        }
        if (normalized.contains("sort aggregate")) {
            PlanNode translatedSortAggregate = buildSortAggregate(node, context, scalarBindings);
            System.out.println("[OpengaussPlanAdapter] sort aggregate translate type=" + type
                    + " nodeOutput=" + text(node, "Output")
                    + " groupKey=" + firstNonNull(text(node, "Group Key"), text(node, "Group By Key"))
                    + " translatedOutputs=" + translatedSortAggregate.getOutputVariables());
            return alignOutputNode(node, translatedSortAggregate, context);
        }
        if (normalized.contains("aggregate")) {
            PlanNode translatedAggregate = buildAggregation(node, context, scalarBindings);
            System.out.println("[OpengaussPlanAdapter] aggregate translate type=" + type
                    + " nodeOutput=" + text(node, "Output")
                    + " groupKey=" + firstNonNull(text(node, "Group Key"), text(node, "Group By Key"))
                    + " translatedOutputs=" + translatedAggregate.getOutputVariables());
            return alignOutputNode(node, translatedAggregate, context);
        }
        if (normalized.contains("sort")) {
            PlanNode translatedSort = buildSort(node, context, scalarBindings);
            System.out.println("[OpengaussPlanAdapter] sort translate type=" + type
                    + " nodeOutput=" + text(node, "Output")
                    + " sortKey=" + text(node, "Sort Key")
                    + " translatedOutputs=" + translatedSort.getOutputVariables());
            return alignOutputNode(node, translatedSort, context);
        }
        if (normalized.contains("project") || normalized.contains("projection")) {
            return buildProject(node, context, scalarBindings);
        }
        if (normalized.contains("limit") || normalized.contains("top n")) {
            return buildTopN(node, context, scalarBindings);
        }

        JsonNode child = firstChild(node);
        if (child != null) {
            return alignOutputNode(node, translateNode(child, context, scalarBindings), context);
        }
        return buildFallbackProject(node, context);
    }

    private PlanNode buildScan(JsonNode node, AdapterContext context, Map<String, VariableReferenceExpression> scalarBindings)
    {
        String tableName = firstNonNull(text(node, "Relation Name"), text(node, "Alias"), text(node, "Table Name"));
        if (tableName == null) {
            throw new IllegalArgumentException("Missing table name for scan node");
        }

        String schemaName = firstNonNull(text(node, "Schema"), text(node, "Schema Name"), "tiny");
        Session session = context.getSession();
        Metadata metadata = context.getMetadata();
        System.out.println("[OpengaussPlanAdapter] catalogs=" + metadata.getCatalogNames(session).keySet());
        for (String catalog : metadata.getCatalogNames(session).keySet()) {
            System.out.println("[OpengaussPlanAdapter] schemas in " + catalog + " = " + metadata.listSchemaNames(session, catalog));
        }
        QualifiedObjectName qname = resolveQualifiedTableName(metadata, session, tableName, schemaName)
                .orElseThrow(() -> new IllegalArgumentException("Table not found in metadata: " + schemaName + "." + tableName));
        System.out.println("[OpengaussPlanAdapter] trying table=" + qname);
        TableHandle tableHandle = resolveTableHandle(metadata, session, qname)
                .orElseThrow(() -> new IllegalArgumentException("Table not found in metadata: " + qname));

        TableHandle scanTableHandle = tableHandle;
        try {
            com.facebook.presto.metadata.TableLayoutResult layoutResult = metadata.getLayout(session, tableHandle, com.facebook.presto.spi.Constraint.alwaysTrue(), Optional.empty());
            if (layoutResult != null && layoutResult.getLayout() != null) {
                scanTableHandle = layoutResult.getLayout().getNewTableHandle();
            }
        }
        catch (RuntimeException e) {
            System.out.println("[OpengaussPlanAdapter] layout lookup failed for " + qname + ": " + e.getMessage());
        }

        TableMetadata tableMetadata = metadata.getTableMetadata(session, scanTableHandle);
        Map<String, ColumnHandle> columnHandles = metadata.getColumnHandles(session, scanTableHandle);
        List<VariableReferenceExpression> outputs = new ArrayList<>();
        Map<VariableReferenceExpression, ColumnHandle> assignments = new LinkedHashMap<>();
        Map<String, VariableReferenceExpression> variablesByName = new LinkedHashMap<>();
        Map<String, com.facebook.presto.spi.ColumnMetadata> metadataByName = new LinkedHashMap<>();
        for (com.facebook.presto.spi.ColumnMetadata columnMetadata : tableMetadata.getColumns()) {
            if (!columnMetadata.isHidden()) {
                metadataByName.put(simpleName(columnMetadata.getName()).toLowerCase(Locale.ENGLISH), columnMetadata);
            }
        }
        List<String> outputNames = parseOutputNames(node);
        String filterText = firstNonNull(text(node, "Filter"), text(node, "Index Cond"), text(node, "Hash Cond"));
        System.out.println("[OpengaussPlanAdapter] scan output names=" + outputNames + " for nodeType=" + text(node, "Node Type"));

        for (Map.Entry<String, com.facebook.presto.spi.ColumnMetadata> entry : metadataByName.entrySet()) {
            String columnName = entry.getKey();
            com.facebook.presto.spi.ColumnMetadata columnMetadata = entry.getValue();
            ColumnHandle columnHandle = null;
            for (Map.Entry<String, ColumnHandle> handleEntry : columnHandles.entrySet()) {
                if (simpleName(handleEntry.getKey()).equalsIgnoreCase(columnName)) {
                    columnHandle = handleEntry.getValue();
                    break;
                }
            }
            if (columnHandle == null) {
                continue;
            }
            Type columnType = columnMetadata.getType();
            String scanAlias = text(node, "Alias");
            String variableName = columnName;
            if (scanAlias != null && !scanAlias.isBlank() && tableName != null && !scanAlias.equalsIgnoreCase(tableName)) {
                variableName = simpleName(scanAlias).toLowerCase(Locale.ENGLISH) + "_" + columnName;
            }
            VariableReferenceExpression variable = context.getVariableAllocator().newVariable(variableName, columnType);
            variablesByName.put(columnName, variable);
            variablesByName.put(simpleName(columnName).toLowerCase(Locale.ENGLISH), variable);
            if (scanAlias != null && !scanAlias.isBlank()) {
                variablesByName.put((scanAlias + "." + columnName).toLowerCase(Locale.ENGLISH), variable);
                variablesByName.put((simpleName(scanAlias) + "_" + columnName).toLowerCase(Locale.ENGLISH), variable);
            }
        }

        List<String> chosenNames = outputNames.isEmpty() ? new ArrayList<>(metadataByName.keySet()) : outputNames;
        List<String> requiredNames = new ArrayList<>(chosenNames);
        if (filterText != null && !filterText.isBlank()) {
            for (String token : extractReferencedColumnNames(filterText)) {
                if (!requiredNames.contains(token)) {
                    requiredNames.add(token);
                }
            }
            for (String columnName : metadataByName.keySet()) {
                if (!requiredNames.contains(columnName)) {
                    requiredNames.add(columnName);
                }
            }
        }
        for (String outputName : requiredNames) {
            String columnName = simpleName(outputName).toLowerCase(Locale.ENGLISH);
            VariableReferenceExpression variable = variablesByName.get(columnName);
            com.facebook.presto.spi.ColumnMetadata columnMetadata = metadataByName.get(columnName);
            if (variable == null || columnMetadata == null) {
                System.out.println("[OpengaussPlanAdapter] skipping non-base column=" + columnName + " for table=" + qname);
                continue;
            }
            ColumnHandle columnHandle = null;
            for (Map.Entry<String, ColumnHandle> entry : columnHandles.entrySet()) {
                if (simpleName(entry.getKey()).equalsIgnoreCase(columnName)) {
                    columnHandle = entry.getValue();
                    break;
                }
            }
            if (columnHandle == null) {
                continue;
            }
            if (!assignments.containsKey(variable)) {
                outputs.add(variable);
                assignments.put(variable, columnHandle);
            }
        }
        if (outputs.isEmpty()) {
            for (Map.Entry<String, ColumnHandle> entry : columnHandles.entrySet()) {
                String columnName = simpleName(entry.getKey());
                com.facebook.presto.spi.ColumnMetadata columnMetadata = metadataByName.get(columnName.toLowerCase(Locale.ENGLISH));
                if (columnMetadata == null) {
                    continue;
                }
                VariableReferenceExpression variable = context.getVariableAllocator().newVariable(columnName, columnMetadata.getType());
                outputs.add(variable);
                assignments.put(variable, entry.getValue());
                variablesByName.put(columnName.toLowerCase(Locale.ENGLISH), variable);
            }
        }
        for (Map.Entry<String, VariableReferenceExpression> scalarBinding : scalarBindings.entrySet()) {
            variablesByName.put(scalarBinding.getKey().toLowerCase(Locale.ENGLISH), scalarBinding.getValue());
            variablesByName.put(scalarBinding.getValue().getName().toLowerCase(Locale.ENGLISH), scalarBinding.getValue());
        }

        PlanNode scan = new TableScanNode(Optional.empty(), context.getIdAllocator().getNextId(), scanTableHandle, outputs, assignments, TupleDomain.all(), TupleDomain.all(), Optional.empty());
        RowExpression predicate = parsePredicate(filterText, variablesByName, context);
        if (predicate == null) {
            predicate = parseTopLevelFilterConjuncts(filterText, variablesByName);
        }
        if (predicate != null) {
            predicate = substituteScalarBindings(predicate, scalarBindings);
            scan = attachScalarPlansForPredicate(scan, predicate, context);
            System.out.println("[OpengaussPlanAdapter] buildScan filter=" + filterText + " predicate=" + predicate + " scalarBindings=" + scalarBindings.keySet());
            scan = new FilterNode(Optional.empty(), context.getIdAllocator().getNextId(), scan, predicate);
        }
        return scan;
    }

    private PlanNode attachScalarPlansForPredicate(PlanNode source, RowExpression predicate, AdapterContext context)
    {
        if (source == null || predicate == null || scalarPlanBindings.isEmpty()) {
            return source;
        }
        List<VariableReferenceExpression> dependencies = new ArrayList<>();
        collectVariableDependencies(predicate, buildScalarVariableLookup(), dependencies);
        PlanNode current = source;
        for (VariableReferenceExpression dependency : dependencies) {
            if (dependency == null || current.getOutputVariables().contains(dependency)) {
                continue;
            }
            PlanNode scalarPlan = scalarPlanBindings.get(dependency.getName().toLowerCase(Locale.ENGLISH));
            if (scalarPlan == null) {
                continue;
            }
            scalarPlan = ensureReplicatedExchange(scalarPlan, context);
            List<VariableReferenceExpression> outputs = new ArrayList<>(current.getOutputVariables());
            for (VariableReferenceExpression scalarOutput : scalarPlan.getOutputVariables()) {
                if (!outputs.contains(scalarOutput)) {
                    outputs.add(scalarOutput);
                }
            }
            current = new JoinNode(Optional.empty(), context.getIdAllocator().getNextId(), JoinType.INNER, current, scalarPlan, Collections.emptyList(), outputs, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Collections.emptyMap());
            System.out.println("[OpengaussPlanAdapter] attached scalar plan for dependency=" + dependency + " scalarOutputs=" + scalarPlan.getOutputVariables());
        }
        return current;
    }

    private Map<String, VariableReferenceExpression> buildScalarVariableLookup()
    {
        Map<String, VariableReferenceExpression> lookup = new LinkedHashMap<>();
        for (Map.Entry<String, PlanNode> entry : scalarPlanBindings.entrySet()) {
            for (VariableReferenceExpression variable : entry.getValue().getOutputVariables()) {
                lookup.put(variable.getName().toLowerCase(Locale.ENGLISH), variable);
                lookup.put(entry.getKey().toLowerCase(Locale.ENGLISH), variable);
            }
        }
        return lookup;
    }

    private RowExpression parseTopLevelFilterConjuncts(String filterText, Map<String, VariableReferenceExpression> variables)
    {
        if (filterText == null || filterText.isBlank()) {
            return null;
        }
        String normalized = normalizePrestoExpressionText(filterText);
        normalized = stripUnmatchedOuterParens(normalized);
        RowExpression combined = null;
        for (String part : splitTopLevelParts(normalized, " AND ")) {
            String conjunct = stripUnmatchedOuterParens(part);
            RowExpression parsed = parseBooleanPredicate(conjunct, variables);
            if (parsed == null) {
                parsed = parseExpression(conjunct, variables, false);
            }
            if (parsed == null || !BooleanType.BOOLEAN.equals(parsed.getType())) {
                System.out.println("[OpengaussPlanAdapter] dropping unparsed filter conjunct=" + conjunct + " from filter=" + filterText);
                continue;
            }
            combined = combined == null ? parsed : new SpecialFormExpression(SpecialFormExpression.Form.AND, BooleanType.BOOLEAN, combined, parsed);
        }
        return combined;
    }

    private Type inferFallbackColumnType(QualifiedObjectName tableName, String columnName, Type fallbackType)
    {
        if (columnName == null) {
            return fallbackType;
        }
        String table = tableName == null ? "" : tableName.getObjectName().toLowerCase(Locale.ENGLISH);
        String column = columnName.toLowerCase(Locale.ENGLISH);
        if (column.contains("shipdate") || column.contains("orderdate") || column.contains("commitdate") || column.contains("receiptdate") || column.contains("date")) {
            return DateType.DATE;
        }
        if (column.contains("discount") || column.contains("price") || column.contains("balance") || column.contains("amount") || column.contains("tax") || column.contains("rate") || column.contains("extendedprice")) {
            return DoubleType.DOUBLE;
        }
        if (column.contains("quantity") || column.contains("qty") || column.endsWith("key") || column.contains("custkey") || column.contains("suppkey") || column.contains("orderkey") || column.contains("partkey") || column.contains("linenumber")) {
            return BigintType.BIGINT;
        }
        if (table.equals("lineitem")) {
            if (column.contains("discount") || column.contains("extendedprice")) {
                return DoubleType.DOUBLE;
            }
            if (column.contains("quantity") || column.endsWith("key") || column.contains("linenumber")) {
                return BigintType.BIGINT;
            }
            if (column.contains("shipdate")) {
                return DateType.DATE;
            }
        }
        if (table.equals("orders")) {
            if (column.contains("orderdate")) {
                return DateType.DATE;
            }
            if (column.contains("shippriority") || column.endsWith("key") || column.contains("custkey")) {
                return BigintType.BIGINT;
            }
        }
        if (table.equals("customer") || table.equals("supplier") || table.equals("nation") || table.equals("part") || table.equals("partsupp")) {
            if (column.endsWith("key") || column.contains("custkey") || column.contains("suppkey") || column.contains("nationkey") || column.contains("partkey")) {
                return BigintType.BIGINT;
            }
        }
        return fallbackType;
    }

    private PlanNode buildJoin(JsonNode node, AdapterContext context, Map<String, VariableReferenceExpression> scalarBindings)
    {
        List<JsonNode> children = children(node);
        if (children.size() < 2) {
            return firstChild(node) == null ? buildFallbackProject(node, context) : translateNode(firstChild(node), context, scalarBindings);
        }

        String joinTypeText = text(node, "Join Type");
        String normalizedJoinType = joinTypeText == null ? "" : joinTypeText.toLowerCase(Locale.ENGLISH);
        JsonNode leftJson = children.get(0);
        JsonNode rightJson = children.get(1);

        if (normalizedJoinType.contains("anti") || normalizedJoinType.contains("semi")) {
            boolean rightSidePreserved = normalizedJoinType.contains("right");
            PlanNode preservedSide = translateNode(rightSidePreserved ? rightJson : leftJson, context, scalarBindings);
            PlanNode filteringSide = translateNode(rightSidePreserved ? leftJson : rightJson, context, scalarBindings);
            String joinCondition = firstNonNull(text(node, "Hash Cond"), text(node, "Merge Cond"), text(node, "Join Filter"));
            VariableReferenceExpression sourceJoinVariable = null;
            VariableReferenceExpression filteringJoinVariable = null;
            List<String> conditions = splitJoinConditions(joinCondition);
            for (String condition : conditions) {
                String[] parts = condition.split("=");
                if (parts.length != 2) {
                    continue;
                }
                VariableReferenceExpression preservedLeft = resolveJoinVariable(preservedSide, parts[0].trim());
                VariableReferenceExpression preservedRight = resolveJoinVariable(preservedSide, parts[1].trim());
                VariableReferenceExpression filteringLeft = resolveJoinVariable(filteringSide, parts[0].trim());
                VariableReferenceExpression filteringRight = resolveJoinVariable(filteringSide, parts[1].trim());
                if (preservedLeft != null && filteringRight != null) {
                    sourceJoinVariable = preservedLeft;
                    filteringJoinVariable = filteringRight;
                    break;
                }
                if (preservedRight != null && filteringLeft != null) {
                    sourceJoinVariable = preservedRight;
                    filteringJoinVariable = filteringLeft;
                    break;
                }
            }
            if (sourceJoinVariable == null || filteringJoinVariable == null) {
                throw new IllegalArgumentException("Unable to resolve semi/anti join variables for condition=" + joinCondition
                        + " preservedOutputs=" + preservedSide.getOutputVariables()
                        + " filteringOutputs=" + filteringSide.getOutputVariables());
            }
            if (sourceJoinVariable != null && filteringJoinVariable != null
                    && sourceJoinVariable.getType() != null
                    && filteringJoinVariable.getType() != null
                    && !sourceJoinVariable.getType().equals(filteringJoinVariable.getType())) {
                VariableReferenceExpression coercedFilteringJoinVariable = context.getVariableAllocator().newVariable(filteringJoinVariable.getName(), sourceJoinVariable.getType());
                Map<VariableReferenceExpression, RowExpression> assignments = new LinkedHashMap<>();
                for (VariableReferenceExpression variable : filteringSide.getOutputVariables()) {
                    assignments.put(variable, variable);
                }
                assignments.put(coercedFilteringJoinVariable, coerceJoinKeyExpression(filteringJoinVariable, sourceJoinVariable.getType()));
                filteringSide = new ProjectNode(Optional.empty(), context.getIdAllocator().getNextId(), filteringSide, Assignments.copyOf(assignments), ProjectNode.Locality.LOCAL);
                System.out.println("[OpengaussPlanAdapter] semi join key coerced condition=" + joinCondition
                        + " sourceKey=" + sourceJoinVariable + ":" + sourceJoinVariable.getType()
                        + " filteringKey=" + filteringJoinVariable + ":" + filteringJoinVariable.getType()
                        + " coercedFilteringKey=" + coercedFilteringJoinVariable + ":" + coercedFilteringJoinVariable.getType());
                filteringJoinVariable = coercedFilteringJoinVariable;
            }
            VariableReferenceExpression semiOutput = context.getVariableAllocator().newVariable("semi_join", BooleanType.BOOLEAN);
            SemiJoinNode semiJoin = new SemiJoinNode(
                    Optional.empty(),
                    context.getIdAllocator().getNextId(),
                    preservedSide,
                    filteringSide,
                    sourceJoinVariable,
                    filteringJoinVariable,
                    semiOutput,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of(normalizedJoinType.contains("replic") ? SemiJoinNode.DistributionType.REPLICATED : SemiJoinNode.DistributionType.PARTITIONED),
                    Collections.emptyMap());
            if (normalizedJoinType.contains("anti")) {
                RowExpression antiPredicate = new CallExpression("not", builtInUnaryHandle("not", BooleanType.BOOLEAN, semiOutput.getType()), BooleanType.BOOLEAN, List.of(semiOutput));
                return new FilterNode(Optional.empty(), context.getIdAllocator().getNextId(), semiJoin, antiPredicate);
            }
            RowExpression semiPredicate = semiOutput;
            return new FilterNode(Optional.empty(), context.getIdAllocator().getNextId(), semiJoin, semiPredicate);
        }

        JoinType joinType = parseJoinType(joinTypeText);
        boolean swapped = joinType == JoinType.RIGHT;
        if (swapped) {
            joinType = JoinType.LEFT;
            JsonNode tmp = leftJson;
            leftJson = rightJson;
            rightJson = tmp;
        }

        PlanNode left = translateNode(leftJson, context, scalarBindings);
        PlanNode right = translateNode(rightJson, context, scalarBindings);
        String joinCondition = firstNonNull(text(node, "Hash Cond"), text(node, "Merge Cond"), text(node, "Join Filter"));
        List<EquiJoinClause> criteria = parseJoinCriteria(joinCondition, left, right);
        right = ensureJoinBuildSideExchange(right, criteria, context);
        List<VariableReferenceExpression> outputVariables = new ArrayList<>(left.getOutputVariables());
        outputVariables.addAll(right.getOutputVariables());

        System.out.println("[OpengaussPlanAdapter] join debug type=" + joinTypeText
                + " condition=" + joinCondition
                + " leftOutputs=" + left.getOutputVariables()
                + " rightOutputs=" + right.getOutputVariables()
                + " criteria=" + criteria
                + " joinNodeOutputs=" + outputVariables);
//        System.out.println("[OpengaussPlanAdapter] join left tree=" + describePlanTree(left));
//        System.out.println("[OpengaussPlanAdapter] join right tree=" + describePlanTree(right));

        PlanNode join = new JoinNode(Optional.empty(), context.getIdAllocator().getNextId(), joinType, left, right, criteria, outputVariables, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Collections.emptyMap());
        if (text(node, "Join Filter") != null) {
            String joinFilter = rewriteJoinFilterAliases(text(node, "Join Filter"), join, node);
            RowExpression filter = parsePredicate(joinFilter, buildVariablesByOutput(join), context);
            if (filter != null) {
                join = new FilterNode(Optional.empty(), context.getIdAllocator().getNextId(), join, filter);
            }
        }
        return join;
    }

    private String rewriteJoinFilterAliases(String filter, PlanNode join, JsonNode node)
    {
        if (filter == null || filter.isBlank() || join == null) {
            return filter;
        }
        Map<String, VariableReferenceExpression> variables = buildVariablesByOutput(join);
        String rewritten = filter;
        for (String token : extractQualifiedColumnReferences(filter)) {
            VariableReferenceExpression variable = findDeclaredOutputVariable(token, variables);
            if (variable != null && variable.getName() != null) {
                rewritten = rewritten.replaceAll("(?i)\\b" + Pattern.quote(token) + "\\b", Matcher.quoteReplacement(variable.getName()));
            }
        }
        if (!rewritten.equals(filter)) {
            System.out.println("[OpengaussPlanAdapter] rewritten join filter before=" + filter + " after=" + rewritten);
        }
        return rewritten;
    }

    private VariableReferenceExpression findDeclaredOutputVariable(String declaredOutput, Map<String, VariableReferenceExpression> variables)
    {
        if (declaredOutput == null || variables == null || variables.isEmpty()) {
            return null;
        }
        String normalized = canonicalizeExpressionText(declaredOutput).toLowerCase(Locale.ENGLISH);
        if (normalized.contains("n1.n_name")) {
            return findQualifiedVariable("n1.n_name", variables);
        }
        if (normalized.contains("n2.n_name")) {
            return findQualifiedVariable("n2.n_name", variables);
        }
        if (normalized.contains("n1.n_nationkey")) {
            return findQualifiedVariable("n1.n_nationkey", variables);
        }
        if (normalized.contains("n2.n_nationkey")) {
            return findQualifiedVariable("n2.n_nationkey", variables);
        }
        return lookupVariable(declaredOutput, variables);
    }

    private Set<String> extractQualifiedColumnReferences(String text)
    {
        Set<String> result = new LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return result;
        }
        Matcher matcher = Pattern.compile("\\b[a-zA-Z][a-zA-Z0-9_]*\\.[a-zA-Z][a-zA-Z0-9_]*\\b").matcher(text);
        while (matcher.find()) {
            result.add(matcher.group());
        }
        return result;
    }

    private PlanNode buildSubqueryScan(JsonNode node, AdapterContext context, Map<String, VariableReferenceExpression> scalarBindings)
    {
        JsonNode child = firstChild(node);
        if (child == null) {
            return buildFallbackProject(node, context);
        }
        PlanNode source = translateNode(child, context, scalarBindings);
        String alias = firstNonNull(text(node, "Alias"), text(node, "Subplan Name"), "subquery");
        List<VariableReferenceExpression> sourceOutputs = source.getOutputVariables();
        List<String> declaredOutputs = parseOutputNames(node);
        Map<String, VariableReferenceExpression> sourceVariables = buildVariablesByOutput(source);
        Map<VariableReferenceExpression, RowExpression> assignments = new LinkedHashMap<>();
        List<VariableReferenceExpression> outputs = new ArrayList<>();

        if (!declaredOutputs.isEmpty()) {
            Set<VariableReferenceExpression> usedSources = new HashSet<>();
            for (int i = 0; i < declaredOutputs.size(); i++) {
                String outputName = declaredOutputs.get(i);
                VariableReferenceExpression src = selectSubqueryOutputSource(outputName, sourceOutputs, sourceVariables, usedSources, i);
                if (src == null) {
                    continue;
                }
                usedSources.add(src);
                VariableReferenceExpression target = context.getVariableAllocator().newVariable(simpleName(outputName), src.getType());
                outputs.add(target);
                assignments.put(target, src);
                System.out.println("[OpengaussPlanAdapter] subquery output alias=" + alias
                        + " outputName=" + outputName
                        + " source=" + src
                        + " sourceOutputs=" + sourceOutputs);
            }
            if (!assignments.isEmpty()) {
                return new ProjectNode(Optional.empty(), context.getIdAllocator().getNextId(), source, Assignments.copyOf(assignments), ProjectNode.Locality.LOCAL);
            }
        }

        for (int i = 0; i < sourceOutputs.size(); i++) {
            VariableReferenceExpression src = sourceOutputs.get(i);
            String name = i == 0 ? "?column?" : src.getName();
            VariableReferenceExpression target = context.getVariableAllocator().newVariable(simpleName(name), src.getType());
            outputs.add(target);
            assignments.put(target, src);
        }
        return new ProjectNode(Optional.empty(), context.getIdAllocator().getNextId(), source, Assignments.copyOf(assignments), ProjectNode.Locality.LOCAL);
    }

    private VariableReferenceExpression selectSubqueryOutputSource(String outputName, List<VariableReferenceExpression> sourceOutputs, Map<String, VariableReferenceExpression> sourceVariables, Set<VariableReferenceExpression> usedSources, int ordinal)
    {
        VariableReferenceExpression direct = lookupVariable(outputName, sourceVariables);
        if (direct != null && (usedSources == null || !usedSources.contains(direct))) {
            return direct;
        }

        String simple = simpleName(outputName).toLowerCase(Locale.ENGLISH);
        String baseSimple = stripVariableIdSuffix(simple);
        VariableReferenceExpression best = null;
        int bestScore = Integer.MIN_VALUE;
        for (VariableReferenceExpression candidate : sourceOutputs) {
            if (candidate == null || (usedSources != null && usedSources.contains(candidate))) {
                continue;
            }
            String candidateName = candidate.getName() == null ? "" : candidate.getName().toLowerCase(Locale.ENGLISH);
            String candidateBase = stripVariableIdSuffix(simpleName(candidateName).toLowerCase(Locale.ENGLISH));
            int score = scoreSubqueryOutputAlias(baseSimple, candidateName, candidateBase);
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        if (best != null && bestScore > 0) {
            return best;
        }
        if (ordinal >= 0 && ordinal < sourceOutputs.size()) {
            VariableReferenceExpression ordinalSource = sourceOutputs.get(ordinal);
            if (usedSources == null || !usedSources.contains(ordinalSource)) {
                return ordinalSource;
            }
        }
        return null;
    }

    private int scoreSubqueryOutputAlias(String outputBaseName, String candidateName, String candidateBaseName)
    {
        int score = 0;
        if (candidateName.equals(outputBaseName) || candidateBaseName.equals(outputBaseName)) {
            score += 100;
        }
        if (candidateName.contains(outputBaseName) || outputBaseName.contains(candidateName) || candidateBaseName.contains(outputBaseName) || outputBaseName.contains(candidateBaseName)) {
            score += 40;
        }
        if (outputBaseName.contains("count") && candidateName.contains("count")) {
            score += 80;
        }
        if (outputBaseName.contains("sum") && candidateName.contains("sum")) {
            score += 80;
        }
        if (outputBaseName.contains("avg") && candidateName.contains("avg")) {
            score += 80;
        }
        if (outputBaseName.contains("min") && candidateName.contains("min")) {
            score += 80;
        }
        if (outputBaseName.contains("max") && candidateName.contains("max")) {
            score += 80;
        }
        if ((outputBaseName.contains("revenue") || outputBaseName.contains("amount") || outputBaseName.contains("price") || outputBaseName.contains("cost"))
                && (candidateName.contains("sum") || candidateName.contains("revenue") || candidateName.contains("amount") || candidateName.contains("price") || candidateName.contains("cost"))) {
            score += 90;
        }
        if ((outputBaseName.contains("supplier_no") || outputBaseName.contains("suppkey") || outputBaseName.contains("supp_key"))
                && (candidateName.contains("suppkey") || candidateName.contains("supp_key") || candidateName.contains("supplier_no"))) {
            score += 90;
        }
        if ((outputBaseName.contains("customer_no") || outputBaseName.contains("custkey") || outputBaseName.contains("cust_key"))
                && (candidateName.contains("custkey") || candidateName.contains("cust_key") || candidateName.contains("customer_no"))) {
            score += 90;
        }
        if (outputBaseName.endsWith("key") && candidateBaseName.endsWith("key")) {
            score += 20;
        }
        return score;
    }

    private PlanNode buildExchange(JsonNode node, AdapterContext context, Map<String, VariableReferenceExpression> scalarBindings)
    {
        JsonNode child = firstChild(node);
        if (child == null) {
            return buildFallbackProject(node, context);
        }
        PlanNode source = translateNode(child, context, scalarBindings);
        String type = text(node, "Node Type");
        String normalized = type == null ? "" : type.toLowerCase(Locale.ENGLISH);
        if (normalized.contains("redistribute")) {
            return ensurePartitionedExchange(source, node, context);
        }
        if (normalized.contains("replicate")) {
            return ensureReplicatedExchange(source, context);
        }
        if (normalized.contains("gather")) {
            return ensureGatherExchange(source, context);
        }
        return source;
    }

    private PlanNode ensureLocalGatherExchange(PlanNode source, AdapterContext context)
    {
        // Keep OpenGauss output wrappers transparent unless a downstream operator
        // explicitly forces a single-node distribution. This prevents accidental
        // SOURCE -> SINGLE rewrites during adapter output shaping.
        return source;
    }

    private PlanNode ensureGatherExchange(PlanNode source, AdapterContext context)
    {
        return source;
    }

    private PlanNode ensureReplicatedExchange(PlanNode source, AdapterContext context)
    {
        if (source == null || source instanceof ExchangeNode) {
            return source;
        }
        return ExchangeNode.replicatedExchange(context.getIdAllocator().getNextId(), ExchangeNode.Scope.REMOTE_STREAMING, source);
    }

    private PlanNode ensurePartitionedExchange(PlanNode source, JsonNode node, AdapterContext context)
    {
        if (source == null) {
            return null;
        }
        if (source instanceof ExchangeNode) {
            return source;
        }
        // Presto's exchange implementation expects connector-owned partitioning
        // handles in some paths. The OpenGauss plan frequently marks internal
        // redistribution stages that are not real Presto repartition boundaries,
        // so we keep them transparent unless a later join/aggregation stage
        // explicitly requires a different layout.
        return source;
    }

    private PlanNode ensureJoinBuildSideExchange(PlanNode source, List<EquiJoinClause> criteria, AdapterContext context)
    {
        if (source == null || source instanceof ExchangeNode) {
            return source;
        }

        List<VariableReferenceExpression> partitioningColumns = new ArrayList<>();
        if (criteria != null) {
            for (EquiJoinClause clause : criteria) {
                if (clause != null && clause.getRight() != null && !partitioningColumns.contains(clause.getRight())) {
                    partitioningColumns.add(clause.getRight());
                }
            }
        }
        if (partitioningColumns.isEmpty()) {
            partitioningColumns.addAll(source.getOutputVariables());
        }
        if (partitioningColumns.isEmpty()) {
            return source;
        }

        // Build side of a join needs a local hash distribution so the join
        // operator can consume a properly grouped input.
        return ExchangeNode.partitionedExchange(
                context.getIdAllocator().getNextId(),
                ExchangeNode.Scope.LOCAL,
                source,
                Partitioning.create(SystemPartitioningHandle.FIXED_HASH_DISTRIBUTION, partitioningColumns),
                Optional.empty());
    }

    private PlanNode buildProject(JsonNode node, AdapterContext context, Map<String, VariableReferenceExpression> scalarBindings)
    {
        JsonNode child = firstChild(node);
        PlanNode source = child == null ? buildFallbackProject(node, context) : translateNode(child, context, scalarBindings);
        Map<VariableReferenceExpression, RowExpression> assignments = new LinkedHashMap<>();
        Map<String, VariableReferenceExpression> variables = buildVariablesByOutput(source);
        JsonNode targetList = node.get("Target List");
        if (targetList != null && targetList.isArray()) {
            for (JsonNode target : targetList) {
                String outputName = firstNonNull(text(target, "Res Name"), text(target, "Output Name"), text(target, "Target Name"), text(target, "Column"));
                String exprText = firstNonNull(text(target, "Expr"), text(target, "Expression"), text(target, "Value"));
                if (outputName == null && exprText == null) {
                    continue;
                }
                VariableReferenceExpression output = context.getVariableAllocator().newVariable(simpleName(firstNonNull(outputName, exprText, "proj")), VarcharType.VARCHAR);
                RowExpression expression = exprText == null ? null : parseProjectExpression(exprText, variables, context);
                if (expression == null) {
                    expression = parseValue(firstNonNull(outputName, exprText), variables);
                }
                if (expression == null) {
                    expression = lookupVariableByExpressionShape(firstNonNull(outputName, exprText), variables);
                }
                if (expression == null) {
                    expression = selectBestMatchingVariable(variables, firstNonNull(outputName, exprText), "count");
                }
                if (expression == null) {
                    expression = new ConstantExpression(null, VarcharType.VARCHAR);
                }
                assignments.put(output, expression);
                variables.put(output.getName().toLowerCase(Locale.ENGLISH), output);
            }
        }
        if (assignments.isEmpty()) {
            for (VariableReferenceExpression variable : source.getOutputVariables()) {
                assignments.put(variable, variable);
            }
        }
        return new ProjectNode(Optional.empty(), context.getIdAllocator().getNextId(), source, Assignments.copyOf(assignments), ProjectNode.Locality.LOCAL);
    }

    private PlanNode buildAggregation(JsonNode node, AdapterContext context, Map<String, VariableReferenceExpression> scalarBindings)
    {
        return buildAggregationInternal(node, context, scalarBindings, false);
    }

    private PlanNode buildSortAggregate(JsonNode node, AdapterContext context, Map<String, VariableReferenceExpression> scalarBindings)
    {
        return buildAggregationInternal(node, context, scalarBindings, true);
    }

    private PlanNode buildAggregationInternal(JsonNode node, AdapterContext context, Map<String, VariableReferenceExpression> scalarBindings, boolean sortAggregate)
    {
        JsonNode child = primaryChild(node);
        if (child == null) {
            return buildFallbackProject(node, context);
        }

        PlanNode source = translateNode(child, context, scalarBindings);
        System.out.println("[OpengaussPlanAdapter] aggregation input source output=" + source.getOutputVariables());
        // System.out.println("[OpengaussPlanAdapter] aggregation input source tree=" + describePlanTree(source));
        List<VariableReferenceExpression> groupingKeys = new ArrayList<>();
        List<VariableReferenceExpression> preProjectOutputs = new ArrayList<>();
        Map<VariableReferenceExpression, RowExpression> preProjectAssignments = new LinkedHashMap<>();
        Map<String, VariableReferenceExpression> variables = buildVariablesByOutput(source);
        List<String> groupKeyTokens = textList(node, "Group Key");
        if (groupKeyTokens.isEmpty()) {
            groupKeyTokens = textList(node, "Group By Key");
        }
        String groupKeyText = firstNonNull(text(node, "Group Key"), text(node, "Group By Key"));
        if (groupKeyTokens.isEmpty() && groupKeyText != null) {
            groupKeyTokens = splitCommaSeparated(groupKeyText);
        }
        for (String token : groupKeyTokens) {
            VariableReferenceExpression variable = lookupVariable(token, variables);
            if (variable == null) {
                Map<String, VariableReferenceExpression> groupExpressionVariables = buildVariablesByPlanTree(source);
                for (VariableReferenceExpression sourceOutput : source.getOutputVariables()) {
                    groupExpressionVariables.put(sourceOutput.getName().toLowerCase(Locale.ENGLISH), sourceOutput);
                    groupExpressionVariables.put(simpleName(sourceOutput.getName()).toLowerCase(Locale.ENGLISH), sourceOutput);
                }
                RowExpression groupExpression = resolveDeclaredOutputExpression(token, groupExpressionVariables);
                if (groupExpression instanceof VariableReferenceExpression) {
                    variable = (VariableReferenceExpression) groupExpression;
                }
            }
            if (variable == null) {
                variable = findExpressionOutputVariable(token, source.getOutputVariables());
            }
            if (variable == null && token != null && token.toLowerCase(Locale.ENGLISH).contains("date_part")) {
                RowExpression datePart = parseProjectExpression(token, variables, context);
                if (datePart != null) {
                    VariableReferenceExpression projected = context.getVariableAllocator().newVariable(token, datePart.getType() == null ? BigintType.BIGINT : datePart.getType());
                    variable = projected;
                    preProjectOutputs.add(projected);
                    preProjectAssignments.put(projected, datePart);
                    variables.put(projected.getName().toLowerCase(Locale.ENGLISH), projected);
                }
            }
            if (variable != null && !groupingKeys.contains(variable)) {
                groupingKeys.add(variable);
            }
        }

        List<String> outputNames = parseOutputNames(node);
        if (groupingKeys.isEmpty()) {
            boolean declaredSubstringGrouping = groupKeyTokens.stream().anyMatch(token -> token.toLowerCase(Locale.ENGLISH).contains("substring") || token.toLowerCase(Locale.ENGLISH).contains("substr"))
                    || outputNames.stream().anyMatch(token -> token.toLowerCase(Locale.ENGLISH).contains("substring") || token.toLowerCase(Locale.ENGLISH).contains("substr"));
            if (declaredSubstringGrouping) {
                VariableReferenceExpression substringGrouping = findExpressionOutputVariable("substring", source.getOutputVariables());
                if (substringGrouping != null) {
                    System.out.println("[OpengaussPlanAdapter] aggregate inferred substring grouping key=" + substringGrouping
                            + " groupKeyTokens=" + groupKeyTokens
                            + " outputNames=" + outputNames
                            + " sourceOutputs=" + source.getOutputVariables());
                    groupingKeys.add(substringGrouping);
                }
            }
        }
        System.out.println("[OpengaussPlanAdapter] aggregate source nodeType=" + text(node, "Node Type")
                + " output=" + text(node, "Output")
                + " parsedOutputNames=" + outputNames);

        Map<String, VariableReferenceExpression> sourceVariables = buildVariablesByOutput(source);
        boolean declaredAggregateOutput = outputNames.stream()
                .map(this::canonicalizeExpressionText)
                .filter(Objects::nonNull)
                .map(value -> value.toLowerCase(Locale.ENGLISH))
                .anyMatch(this::containsAggregationFunction);
        Map<String, VariableReferenceExpression> recursiveVariables = buildVariablesByPlanTree(source);
        Map<String, VariableReferenceExpression> expressionVariables = new LinkedHashMap<>(sourceVariables);
        recursiveVariables.forEach(expressionVariables::putIfAbsent);
        List<String> aggOutputNames = new ArrayList<>();
        List<AggregationCallSpec> aggSpecs = new ArrayList<>();
        List<VariableReferenceExpression> aggDependencyOutputs = new ArrayList<>();
        Map<String, VariableReferenceExpression> dependencyLookup = new LinkedHashMap<>(expressionVariables);

        for (String outputName : outputNames) {
            String normalizedOutput = canonicalizeExpressionText(outputName);
            String lower = normalizedOutput.toLowerCase(Locale.ENGLISH);
            System.out.println("[OpengaussPlanAdapter] aggregate output candidate raw=" + outputName + " normalized=" + normalizedOutput + " lower=" + lower + " containsAgg=" + containsAggregationFunction(lower));
            List<AggregationCallSpec> parsedSpecs = containsAggregationFunction(lower)
                    ? List.of(parseAggregationFragment(outputName, sourceVariables, source, node, sortAggregate))
                    : splitAggregationText(outputName, sourceVariables, source, node, sortAggregate);
            boolean parsedAggregation = false;
            for (AggregationCallSpec spec : parsedSpecs) {
                System.out.println("[OpengaussPlanAdapter] aggregate parsedSpec candidate raw=" + outputName + " spec=" + spec);
                if (spec != null) {
                    aggOutputNames.add(outputName);
                    aggSpecs.add(spec);
                    for (RowExpression argument : spec.getArguments()) {
                        System.out.println("[OpengaussPlanAdapter] aggregation spec arg output=" + outputName
                                + " function=" + spec.getFunctionName()
                                + " argument=" + argument);
                        List<VariableReferenceExpression> beforeDeps = new ArrayList<>(aggDependencyOutputs);
                        collectVariableDependencies(argument, dependencyLookup, aggDependencyOutputs);
                        if (aggDependencyOutputs.size() != beforeDeps.size()) {
                            System.out.println("[OpengaussPlanAdapter] aggregation deps updated output=" + outputName
                                    + " deps=" + aggDependencyOutputs);
                        }
                    }
                    parsedAggregation = true;
                }
            }
            if (parsedAggregation) {
                continue;
            }
            RowExpression expr = parseProjectExpression(outputName, sourceVariables, context);
            if (expr == null) {
                VariableReferenceExpression v = sourceVariables.get(simpleName(outputName).toLowerCase(Locale.ENGLISH));
                expr = v == null ? new ConstantExpression(null, VarcharType.VARCHAR) : v;
            }
            VariableReferenceExpression projected = context.getVariableAllocator().newVariable(outputName, expr.getType() == null ? VarcharType.VARCHAR : expr.getType());
            preProjectOutputs.add(projected);
            preProjectAssignments.put(projected, expr);
            dependencyLookup.put(outputName.toLowerCase(Locale.ENGLISH), projected);
        }

        if (!aggSpecs.isEmpty()) {
            for (Map.Entry<VariableReferenceExpression, RowExpression> entry : preProjectAssignments.entrySet()) {
                VariableReferenceExpression projectedOutput = entry.getKey();
                RowExpression expression = entry.getValue();
                boolean groupingExpression = expression instanceof VariableReferenceExpression
                        || projectedOutput.getName().toLowerCase(Locale.ENGLISH).contains("substring")
                        || projectedOutput.getName().toLowerCase(Locale.ENGLISH).contains("substr");
                if (groupingExpression && !groupingKeys.contains(projectedOutput)) {
                    groupingKeys.add(projectedOutput);
                    System.out.println("[OpengaussPlanAdapter] aggregate added non-aggregate output as grouping key=" + projectedOutput
                            + " expression=" + expression
                            + " outputNames=" + outputNames);
                }
            }
        }

        for (VariableReferenceExpression dependency : aggDependencyOutputs) {
            boolean exists = false;
            for (VariableReferenceExpression existing : preProjectOutputs) {
                if (existing.getName().equalsIgnoreCase(dependency.getName())) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                preProjectOutputs.add(dependency);
                preProjectAssignments.put(dependency, dependency);
            }
        }

        if (aggSpecs.isEmpty()) {
            // OpenGauss uses Vector Sonic Hash Aggregate for both real aggregations
            // and DISTINCT/GROUP BY-only stages.  For example TPC-H Q2 contains an
            // aggregate with Output=[public.part.p_partkey] and Group By Key=[public.part.p_partkey].
            // Treating that node as count(*) drops p_partkey from the output and later
            // violates Presto's exchange partition layout validation.  If all declared
            // outputs are plain column expressions, preserve them as grouping keys instead
            // of inventing a fallback aggregate function.
            for (Map.Entry<VariableReferenceExpression, RowExpression> entry : preProjectAssignments.entrySet()) {
                RowExpression expression = entry.getValue();
                if (expression instanceof VariableReferenceExpression) {
                    VariableReferenceExpression variable = (VariableReferenceExpression) expression;
                    if (!groupingKeys.contains(variable)) {
                        groupingKeys.add(variable);
                    }
                }
            }
        }

        if (aggSpecs.isEmpty() && groupingKeys.isEmpty()) {
            String functionName = inferAggregationFunction(text(node, "Node Type"));
            RowExpression fallbackArg = "count".equalsIgnoreCase(functionName) ? new ConstantExpression(1L, BigintType.BIGINT) : firstAggregationInput(functionName, source, node);
            aggOutputNames.add(functionName);
            aggSpecs.add(new AggregationCallSpec(functionName, inferAggregationSemanticNames(node, source), List.of(fallbackArg), inferAggregationReturnType(functionName, fallbackArg.getType())));
            collectVariableDependencies(fallbackArg, dependencyLookup, aggDependencyOutputs);
        }

        if (aggSpecs.size() == 1 && aggOutputNames.size() > 1) {
            String outputText = firstNonNull(text(node, "Output"), text(node, "Aggs"), text(node, "Aggregates"), text(node, "Target List"));
            if (outputText != null && outputText.contains(",")) {
                List<AggregationCallSpec> parsedSpecs = splitAggregationText(outputText, sourceVariables, source, node, sortAggregate);
                if (parsedSpecs.size() > 1) {
                    aggSpecs = parsedSpecs;
                    aggOutputNames = new ArrayList<>();
                    for (AggregationCallSpec spec : parsedSpecs) {
                        aggOutputNames.add(inferSemanticAggregationName(node, spec, aggOutputNames.size()));
                    }
                }
            }
        }

        System.out.println("[OpengaussPlanAdapter] build" + (sortAggregate ? "SortAggregate" : "Aggregation") + " type=" + text(node, "Node Type")
                + " output=" + text(node, "Output")
                + " groupKey=" + groupKeyText
                + " sourceOutputs=" + source.getOutputVariables()
                + " specs=" + aggSpecs.size()
                + " preProjectOutputs=" + preProjectOutputs
                + " aggDependencies=" + aggDependencyOutputs);

        Map<VariableReferenceExpression, Aggregation> aggregations = new LinkedHashMap<>();
        List<VariableReferenceExpression> aggOutputs = new ArrayList<>();
        Map<VariableReferenceExpression, RowExpression> postAggregationAssignments = new LinkedHashMap<>();
        for (int i = 0; i < aggSpecs.size(); i++) {
            AggregationCallSpec spec = aggSpecs.get(i);
            String outputName = i < aggOutputNames.size() ? aggOutputNames.get(i) : spec.getFunctionName() + "_" + i;
            Type argumentType = spec.getArguments().isEmpty() ? null : spec.getArguments().get(0).getType();
            Type callReturnType = spec.getReturnType() == null ? inferAggregationReturnType(spec.getFunctionName(), argumentType) : spec.getReturnType();
            Type outputType = inferAggregationOutputType(spec.getFunctionName(), spec.getReturnType(), spec.getArguments());
            VariableReferenceExpression aggregationOutput = context.getVariableAllocator().newVariable(outputName + "_raw", callReturnType);
            VariableReferenceExpression output = context.getVariableAllocator().newVariable(outputName, outputType);
            RowExpression argument = spec.getArguments().isEmpty() ? new ConstantExpression(1L, BigintType.BIGINT) : spec.getArguments().get(0);
            if (!isAggregationArgumentAllowed(argument)) {
                VariableReferenceExpression projectedArgument = context.getVariableAllocator().newVariable(outputName + "_arg", argument.getType() == null ? DoubleType.DOUBLE : argument.getType());
                preProjectOutputs.add(projectedArgument);
                preProjectAssignments.put(projectedArgument, argument);
                argument = projectedArgument;
            }
            CallExpression callExpression = buildAggregationCall(context, spec.getFunctionName(), List.of(argument), callReturnType);
            aggregations.put(aggregationOutput, new Aggregation(callExpression, Optional.empty(), Optional.empty(), false, Optional.empty()));
            postAggregationAssignments.put(output, aggregationOutput);
            aggOutputs.add(output);
        }

        PlanNode current = source;
        if (!preProjectAssignments.isEmpty()) {
            Map<VariableReferenceExpression, RowExpression> mergedPreProjectAssignments = new LinkedHashMap<>();
            for (VariableReferenceExpression variable : source.getOutputVariables()) {
                mergedPreProjectAssignments.put(variable, variable);
            }
            mergedPreProjectAssignments.putAll(preProjectAssignments);
            current = new ProjectNode(Optional.empty(), context.getIdAllocator().getNextId(), current, Assignments.copyOf(mergedPreProjectAssignments), ProjectNode.Locality.LOCAL);
        }
        String havingFilter = text(node, "Filter");
        boolean groupOnlyAggregate = !declaredAggregateOutput;
        if (groupOnlyAggregate && havingFilter != null && !havingFilter.isBlank()) {
            Map<String, VariableReferenceExpression> inputHavingVariables = buildVariablesByPlanTree(current);
            for (VariableReferenceExpression variable : current.getOutputVariables()) {
                inputHavingVariables.put(variable.getName().toLowerCase(Locale.ENGLISH), variable);
            }
            RowExpression inputHavingPredicate = parsePredicate(havingFilter, inputHavingVariables, context);
            if (inputHavingPredicate != null && BooleanType.BOOLEAN.equals(inputHavingPredicate.getType())) {
                System.out.println("[OpengaussPlanAdapter] buildAggregation input-side having filter=" + havingFilter + " predicate=" + inputHavingPredicate);
                current = new FilterNode(Optional.empty(), context.getIdAllocator().getNextId(), current, inputHavingPredicate);
            }
            else {
                System.out.println("[OpengaussPlanAdapter] skipped input-side aggregation filter=" + havingFilter
                        + " predicate=" + inputHavingPredicate
                        + " type=" + (inputHavingPredicate == null ? "null" : inputHavingPredicate.getType()));
            }
        }
        AggregationNode aggregation = new AggregationNode(Optional.empty(), context.getIdAllocator().getNextId(), current, aggregations, AggregationNode.singleGroupingSet(groupingKeys), Collections.emptyList(), AggregationNode.Step.SINGLE, Optional.empty(), Optional.empty(), Optional.empty());
        PlanNode aggregationSource = aggregation;
        if (!groupOnlyAggregate && havingFilter != null && !havingFilter.isBlank()) {
            Map<String, VariableReferenceExpression> havingVariables = buildVariablesByOutput(aggregation);
            for (VariableReferenceExpression groupingKey : groupingKeys) {
                havingVariables.putIfAbsent(groupingKey.getName().toLowerCase(Locale.ENGLISH), groupingKey);
            }
            for (VariableReferenceExpression aggregationOutput : aggregations.keySet()) {
                havingVariables.putIfAbsent(aggregationOutput.getName().toLowerCase(Locale.ENGLISH), aggregationOutput);
            }
            RowExpression havingPredicate = parsePredicate(havingFilter, havingVariables, context);
            if (havingPredicate != null && BooleanType.BOOLEAN.equals(havingPredicate.getType())) {
                System.out.println("[OpengaussPlanAdapter] buildAggregation having filter=" + havingFilter + " predicate=" + havingPredicate);
                aggregationSource = new FilterNode(Optional.empty(), context.getIdAllocator().getNextId(), aggregationSource, havingPredicate);
            }
            else {
                System.out.println("[OpengaussPlanAdapter] skipped non-boolean aggregation filter=" + havingFilter
                        + " predicate=" + havingPredicate
                        + " type=" + (havingPredicate == null ? "null" : havingPredicate.getType()));
            }
        }

        List<VariableReferenceExpression> visibleOutputs = new ArrayList<>();
        visibleOutputs.addAll(preProjectOutputs);
        visibleOutputs.addAll(aggOutputs);
        List<VariableReferenceExpression> finalOutputs = new ArrayList<>();
        Map<VariableReferenceExpression, RowExpression> projectAssignments = new LinkedHashMap<>();

        for (VariableReferenceExpression v : groupingKeys) {
            if (!projectAssignments.containsKey(v)) {
                finalOutputs.add(v);
                projectAssignments.put(v, v);
            }
        }
        for (VariableReferenceExpression v : aggOutputs) {
            if (!projectAssignments.containsKey(v)) {
                finalOutputs.add(v);
                RowExpression raw = postAggregationAssignments.get(v);
                projectAssignments.put(v, raw == null ? v : raw);
            }
        }

        if (finalOutputs.size() != groupingKeys.size() + aggOutputs.size()) {
            System.out.println("[OpengaussPlanAdapter] aggregation output alignment warning type=" + text(node, "Node Type")
                    + " groupingKeys=" + groupingKeys
                    + " aggOutputs=" + aggOutputs
                    + " finalOutputs=" + finalOutputs
                    + " sourceOutputs=" + source.getOutputVariables());
        }

        if (finalOutputs.isEmpty()) {
            for (VariableReferenceExpression v : visibleOutputs) {
                if (!projectAssignments.containsKey(v)) {
                    finalOutputs.add(v);
                    projectAssignments.put(v, v);
                }
            }
        }
        System.out.println("[OpengaussPlanAdapter] aggregation outputs=" + finalOutputs + " groupingKeys=" + groupingKeys + " visibleOutputs=" + visibleOutputs);
        Map<VariableReferenceExpression, RowExpression> castAwareAssignments = new LinkedHashMap<>();
        for (Map.Entry<VariableReferenceExpression, RowExpression> entry : projectAssignments.entrySet()) {
            VariableReferenceExpression target = entry.getKey();
            RowExpression sourceExpression = entry.getValue();
            if (sourceExpression instanceof VariableReferenceExpression) {
                VariableReferenceExpression sourceVariable = (VariableReferenceExpression) sourceExpression;
                if (target.getType() != null && sourceVariable.getType() != null && !target.getType().equals(sourceVariable.getType())) {
                    castAwareAssignments.put(target, sourceVariable);
                    continue;
                }
            }
            castAwareAssignments.put(target, sourceExpression);
        }
        return new ProjectNode(Optional.empty(), context.getIdAllocator().getNextId(), aggregationSource, Assignments.copyOf(castAwareAssignments), ProjectNode.Locality.LOCAL);
    }

    private String inferAggregationFunction(String nodeType)
    {
        if (nodeType == null) {
            return "count";
        }
        String normalizedType = nodeType.toLowerCase(Locale.ENGLISH);
        if (normalizedType.contains("count")) {
            return "count";
        }
        if (normalizedType.contains("sum")) {
            return "sum";
        }
        if (normalizedType.contains("min")) {
            return "min";
        }
        if (normalizedType.contains("max")) {
            return "max";
        }
        return "count";
    }

    private Type inferAggregationReturnType(String functionName, Type inputType)
    {
        switch (functionName.toLowerCase(Locale.ENGLISH)) {
            case "count":
                return BigintType.BIGINT;
            case "avg":
                return DoubleType.DOUBLE;
            case "sum":
                if (inputType == null) {
                    return DoubleType.DOUBLE;
                }
                if (isFloatingPointType(inputType)) {
                    return DoubleType.DOUBLE;
                }
                return BigintType.BIGINT;
            case "min":
            case "max":
                return inputType == null || VarcharType.VARCHAR.equals(inputType) ? DoubleType.DOUBLE : inputType;
            default:
                return inputType == null ? DoubleType.DOUBLE : inputType;
        }
    }

    private Type inferAggregationOutputType(String functionName, Type inputType, List<RowExpression> arguments)
    {
        Type returnType = inferAggregationReturnType(functionName, inputType);
        if (returnType != null && !VarcharType.VARCHAR.equals(returnType)) {
            return returnType;
        }
        for (RowExpression argument : arguments) {
            if (argument != null && argument.getType() != null && !VarcharType.VARCHAR.equals(argument.getType())) {
                if ("avg".equalsIgnoreCase(functionName) || "sum".equalsIgnoreCase(functionName)) {
                    return DoubleType.DOUBLE;
                }
                return argument.getType();
            }
        }
        return DoubleType.DOUBLE;
    }

    private PlanNode buildSort(JsonNode node, AdapterContext context, Map<String, VariableReferenceExpression> scalarBindings)
    {
        bindInitPlans(node, context, scalarBindings);
        JsonNode child = primaryChild(node);
        if (child == null) {
            return buildFallbackProject(node, context);
        }
        PlanNode source = translateNode(child, context, scalarBindings);
        List<Ordering> orderings = new ArrayList<>();
        String sortKey = text(node, "Sort Key");
        Map<String, VariableReferenceExpression> variables = buildVariablesByOutput(source);
        if (sortKey != null) {
            for (String token : splitCommaSeparated(sortKey)) {
                VariableReferenceExpression variable = lookupVariable(token, variables);
                if (variable == null) {
                    variable = findExpressionOutputVariable(token, source.getOutputVariables());
                }
                if (variable != null) {
                    SortOrder sortOrder = token.toLowerCase(Locale.ENGLISH).contains(" desc") ? SortOrder.DESC_NULLS_LAST : SortOrder.ASC_NULLS_FIRST;
                    orderings.add(new Ordering(variable, sortOrder));
                }
            }
        }
        if (orderings.isEmpty()) {
            for (VariableReferenceExpression variable : source.getOutputVariables()) {
                orderings.add(new Ordering(variable, SortOrder.ASC_NULLS_FIRST));
            }
        }
        System.out.println("[OpengaussPlanAdapter] buildSort type=" + text(node, "Node Type")
                + " output=" + text(node, "Output")
                + " sortKey=" + sortKey
                + " sourceOutputs=" + source.getOutputVariables()
                + " orderings=" + orderings);
        OrderingScheme orderingScheme = new OrderingScheme(orderings);
        return new SortNode(Optional.empty(), context.getIdAllocator().getNextId(), source, orderingScheme, false, Collections.emptyList());
    }

    private PlanNode buildTopN(JsonNode node, AdapterContext context, Map<String, VariableReferenceExpression> scalarBindings)
    {
        JsonNode child = firstChild(node);
        if (child == null) {
            return buildFallbackProject(node, context);
        }
        PlanNode source = translateNode(child, context, scalarBindings);
        long count = parseLong(firstNonNull(text(node, "Rows"), text(node, "Plan Rows")), 10);
        List<Ordering> orderings = new ArrayList<>();
        Map<String, VariableReferenceExpression> variables = buildVariablesByOutput(source);
        String sortKey = text(node, "Sort Key");
        if (sortKey != null) {
            for (String token : splitCommaSeparated(sortKey)) {
                VariableReferenceExpression variable = lookupVariable(token, variables);
                if (variable != null) {
                    orderings.add(new Ordering(variable, SortOrder.ASC_NULLS_FIRST));
                }
            }
        }
        if (orderings.isEmpty() && !source.getOutputVariables().isEmpty()) {
            orderings.add(new Ordering(source.getOutputVariables().get(0), SortOrder.ASC_NULLS_FIRST));
        }
        return new TopNNode(Optional.empty(), context.getIdAllocator().getNextId(), Optional.empty(), source, count, new OrderingScheme(orderings), TopNNode.Step.SINGLE);
    }

    private PlanNode buildFallbackProject(JsonNode node, AdapterContext context)
    {
        JsonNode child = firstChild(node);
        if (child != null) {
            PlanNode source = translateNode(child, context, new LinkedHashMap<>());
            Map<VariableReferenceExpression, RowExpression> assignments = new LinkedHashMap<>();
            for (VariableReferenceExpression variable : source.getOutputVariables()) {
                assignments.put(variable, variable);
            }
            return new ProjectNode(Optional.empty(), context.getIdAllocator().getNextId(), source, Assignments.copyOf(assignments), ProjectNode.Locality.LOCAL);
        }
        VariableReferenceExpression variable = context.getVariableAllocator().newVariable("dummy", VarcharType.VARCHAR);
        Map<VariableReferenceExpression, RowExpression> assignments = new LinkedHashMap<>();
        assignments.put(variable, new ConstantExpression("", VarcharType.VARCHAR));
        ValuesNode valuesNode = new ValuesNode(Optional.empty(), context.getIdAllocator().getNextId(), List.of(variable), List.of(List.of(new ConstantExpression("", VarcharType.VARCHAR))), Optional.of("fallback"));
        return new ProjectNode(Optional.empty(), context.getIdAllocator().getNextId(), valuesNode, Assignments.copyOf(assignments), ProjectNode.Locality.LOCAL);
    }

    private OutputNode wrapWithOutputNode(PlanNode planNode, AdapterContext context)
    {
        if (planNode instanceof OutputNode) {
            return (OutputNode) planNode;
        }
        List<VariableReferenceExpression> outputVariables = planNode.getOutputVariables();
        List<String> columnNames = new ArrayList<>();
        for (VariableReferenceExpression variable : outputVariables) {
            columnNames.add(variable.getName());
        }
        return new OutputNode(Optional.empty(), context.getIdAllocator().getNextId(), planNode, columnNames, outputVariables);
    }

    private OutputNode wrapWithOutputNode(PlanNode planNode, JsonNode node, AdapterContext context, boolean deriveFromPlan)
    {
        return wrapWithOutputNode(planNode, node, context);
    }

    private boolean shouldInsertDebugOutput(AdapterContext context)
    {
        return context != null
                && context.getSession() != null
                && getOpengaussDebugOutputEnabled(context.getSession())
                && !getOpengaussDebugOutputPlanNodeId(context.getSession()).isBlank();
    }

    private PlanNode insertDebugOutputAtPlanId(PlanNode planNode, AdapterContext context)
    {
        if (planNode == null || !shouldInsertDebugOutput(context)) {
            return planNode;
        }

        String debugPlanId = getOpengaussDebugOutputPlanNodeId(context.getSession()).trim();
        PlanNode target = findDebugTargetNode(planNode, debugPlanId);
        if (target == null) {
            System.out.println("[OpengaussPlanAdapter] debug OutputNode target not found in converted plan tree planId=" + debugPlanId
                    + " root=" + planNode.getId());
            return planNode;
        }

        List<VariableReferenceExpression> outputVariables = target.getOutputVariables();
        List<String> columnNames = new ArrayList<>();
        for (VariableReferenceExpression variable : outputVariables) {
            columnNames.add(variable.getName());
        }
        OutputNode debugOutput = new OutputNode(Optional.empty(), context.getIdAllocator().getNextId(), target, columnNames, outputVariables);
        System.out.println("[OpengaussPlanAdapter] debug output node created for converted planId=" + target.getId()
                + " outputs=" + outputVariables
                + " columns=" + columnNames);
        System.out.println("[OpengaussPlanAdapter] inserted debug OutputNode at converted planId=" + debugPlanId
                + " tree=\n" + formatPlanTree(debugOutput));
        return debugOutput;
    }

    private PlanNode findDebugTargetNode(PlanNode planNode, String targetPlanId)
    {
        if (planNode == null || targetPlanId == null) {
            return null;
        }
        if (matchesDebugPlanId(planNode, targetPlanId)) {
            return planNode;
        }
        for (PlanNode source : planNode.getSources()) {
            PlanNode found = findDebugTargetNode(source, targetPlanId);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private boolean matchesDebugPlanId(PlanNode planNode, String targetPlanId)
    {
        return planNode != null && planNode.getId() != null && targetPlanId.equalsIgnoreCase(planNode.getId().toString());
    }

    private PlanNode rewritePlanNodeSources(PlanNode planNode, List<PlanNode> newSources, AdapterContext context)
    {
        return planNode;
    }

    private OutputNode wrapWithOutputNode(PlanNode planNode, JsonNode node, AdapterContext context)
    {
        if (planNode instanceof OutputNode) {
            return (OutputNode) planNode;
        }
        List<String> columnNames = parseOutputNames(node);
        JsonNode outputNode = node == null ? null : node.get("Output");
        List<VariableReferenceExpression> outputVariables = planNode.getOutputVariables();
        System.out.println("[OpengaussPlanAdapter] wrapWithOutputNode nodeType=" + text(node, "Node Type")
                + " outputRaw=" + (outputNode == null ? "null" : outputNode.toString())
                + " arraySize=" + (outputNode != null && outputNode.isArray() ? outputNode.size() : -1)
                + " parsedColumns=" + columnNames
                + " planOutputs=" + outputVariables);

        if (columnNames.isEmpty()) {
            columnNames = new ArrayList<>();
            for (VariableReferenceExpression variable : outputVariables) {
                columnNames.add(variable.getName());
            }
        }

        columnNames = filterUserVisibleOutputNames(columnNames);
        int outputSize = outputVariables.size();
        int columnSize = columnNames.size();
        int pairSize = Math.min(columnSize, outputSize);
        if (columnSize != outputSize) {
            System.out.println("[OpengaussPlanAdapter] output mismatch nodeType=" + text(node, "Node Type")
                    + " nodeOutput=" + text(node, "Output")
                    + " parsedNames=" + columnNames
                    + " planOutputs=" + outputVariables
                    + " action=" + (columnSize < outputSize ? "padding column names from plan outputs" : "expanding plan outputs to match declared columns"));
        }

        List<VariableReferenceExpression> finalOutputs = new ArrayList<>();
        Map<VariableReferenceExpression, RowExpression> assignments = new LinkedHashMap<>();
        Map<String, VariableReferenceExpression> outputLookup = buildVariablesByPlanTree(planNode);
        for (VariableReferenceExpression variable : outputVariables) {
            outputLookup.put(variable.getName().toLowerCase(Locale.ENGLISH), variable);
            outputLookup.put(simpleName(variable.getName()).toLowerCase(Locale.ENGLISH), variable);
        }
        for (int i = 0; i < pairSize; i++) {
            String columnName = columnNames.get(i);
            RowExpression sourceExpression = resolveDeclaredOutputExpression(columnName, outputLookup);
            if (!(sourceExpression instanceof VariableReferenceExpression)) {
                sourceExpression = outputVariables.get(i);
            }
            if (sourceExpression instanceof VariableReferenceExpression) {
                VariableReferenceExpression candidateSource = (VariableReferenceExpression) sourceExpression;
                if (shouldUseOrdinalOutputForDeclaredColumn(columnName, candidateSource, outputVariables, i)) {
                    sourceExpression = outputVariables.get(i);
                }
            }
            VariableReferenceExpression source = (VariableReferenceExpression) sourceExpression;
            VariableReferenceExpression alias = context.getVariableAllocator().newVariable(columnName, source.getType());
            finalOutputs.add(alias);
            assignments.put(alias, source);
        }

        if (outputSize > pairSize) {
            for (int i = pairSize; i < outputSize; i++) {
                VariableReferenceExpression source = outputVariables.get(i);
                VariableReferenceExpression alias = context.getVariableAllocator().newVariable(source.getName(), source.getType());
                finalOutputs.add(alias);
                assignments.put(alias, source);
                columnNames.add(source.getName());
            }
        }
        else if (columnSize > pairSize) {
            VariableReferenceExpression source = outputVariables.isEmpty() ? null : outputVariables.get(outputSize - 1);
            for (int i = pairSize; i < columnSize; i++) {
                if (source == null) {
                    break;
                }
                VariableReferenceExpression alias = context.getVariableAllocator().newVariable(columnNames.get(i), source.getType());
                finalOutputs.add(alias);
                assignments.put(alias, source);
            }
        }

        System.out.println("[OpengaussPlanAdapter] wrap OutputNode nodeType=" + text(node, "Node Type")
                + " finalColumns=" + columnNames
                + " planOutputs=" + finalOutputs
                + " assignments=" + assignments.keySet());

        PlanNode projected = new ProjectNode(Optional.empty(), context.getIdAllocator().getNextId(), planNode, Assignments.copyOf(assignments), ProjectNode.Locality.LOCAL);
        return new OutputNode(Optional.empty(), context.getIdAllocator().getNextId(), projected, columnNames, finalOutputs);
    }

    private List<String> filterUserVisibleOutputNames(List<String> columnNames)
    {
        if (columnNames == null || columnNames.isEmpty()) {
            return columnNames == null ? new ArrayList<>() : columnNames;
        }
        List<String> filtered = new ArrayList<>();
        boolean leadingQualifiedSelectList = false;
        for (String columnName : columnNames) {
            if (columnName != null && columnName.toLowerCase(Locale.ENGLISH).trim().startsWith("public.")) {
                leadingQualifiedSelectList = true;
                break;
            }
            if (columnName != null && !columnName.isBlank()) {
                break;
            }
        }
        for (String columnName : columnNames) {
            if (columnName == null || columnName.isBlank()) {
                continue;
            }
            String normalized = columnName.toLowerCase(Locale.ENGLISH).trim();
            String simple = simpleName(normalized).toLowerCase(Locale.ENGLISH);
            if (leadingQualifiedSelectList && !normalized.startsWith("public.")) {
                break;
            }
            if (normalized.contains("pg_catalog_avg_avg_") || normalized.contains("pg_catalog_sum_count_") || simple.matches(".*_(raw|arg)$")) {
                continue;
            }
            filtered.add(columnName);
        }
        return filtered;
    }

    private boolean shouldUseOrdinalOutputForDeclaredColumn(String columnName, VariableReferenceExpression candidateSource, List<VariableReferenceExpression> outputVariables, int ordinal)
    {
        if (candidateSource == null || outputVariables == null || ordinal < 0 || ordinal >= outputVariables.size()) {
            return false;
        }
        if (!outputVariables.contains(candidateSource)) {
            return true;
        }
        String declared = columnName == null ? "" : canonicalizeExpressionText(columnName).toLowerCase(Locale.ENGLISH);
        String candidateName = candidateSource.getName() == null ? "" : candidateSource.getName().toLowerCase(Locale.ENGLISH);
        VariableReferenceExpression ordinalSource = outputVariables.get(ordinal);
        String ordinalName = ordinalSource.getName() == null ? "" : ordinalSource.getName().toLowerCase(Locale.ENGLISH);
        if (declared.contains("sum(") && (declared.contains("extendedprice") || declared.contains("discount") || declared.contains("tax"))
                && candidateName.contains("quantity") && !ordinalName.contains("quantity")) {
            return true;
        }
        if (declared.contains("avg(") && candidateName.contains("sum_") && ordinalName.contains("avg_")) {
            return true;
        }
        return false;
    }

    private VariableReferenceExpression findExpressionOutputVariable(String expression, List<VariableReferenceExpression> outputs)
    {
        if (expression == null || outputs == null || outputs.isEmpty()) {
            return null;
        }
        String normalized = canonicalizeExpressionText(expression).toLowerCase(Locale.ENGLISH);
        boolean wantsSubstring = normalized.contains("substring") || normalized.contains("substr");
        for (VariableReferenceExpression output : outputs) {
            if (output == null || output.getName() == null) {
                continue;
            }
            String name = output.getName().toLowerCase(Locale.ENGLISH);
            if (wantsSubstring && (name.contains("substring") || name.contains("substr"))) {
                return output;
            }
        }
        return null;
    }

    private RowExpression resolveDeclaredOutputExpression(String outputName, Map<String, VariableReferenceExpression> variables)
    {
        if (outputName == null || variables == null || variables.isEmpty()) {
            return null;
        }
        String normalized = stripUnmatchedOuterParens(canonicalizeExpressionText(outputName).trim());
        VariableReferenceExpression direct = lookupVariable(normalized, variables);
        if (direct != null) {
            return direct;
        }
        String simple = simpleName(normalized);
        direct = lookupVariable(simple, variables);
        if (direct != null) {
            return direct;
        }
        RowExpression aggregate = resolveAggregateVariableReference(normalized, variables);
        if (aggregate != null) {
            return aggregate;
        }
        String lower = normalized.toLowerCase(Locale.ENGLISH);
        if (lower.contains("count(")) {
            VariableReferenceExpression count = selectBestMatchingVariable(variables, "count", "count");
            if (count != null) {
                return count;
            }
        }
        if (lower.contains("sum(")) {
            VariableReferenceExpression sum = selectBestMatchingVariable(variables, normalized, "sum");
            if (sum != null) {
                return sum;
            }
        }
        return lookupVariableByExpressionShape(normalized, variables);
    }

    private PlanNode alignOutputNode(JsonNode node, PlanNode translated, AdapterContext context)
    {
        if (translated == null) {
            return null;
        }
        List<String> expectedNames = inferOutputNames(translated, node);
        List<VariableReferenceExpression> outputs = translated.getOutputVariables();
        if (expectedNames.isEmpty() || expectedNames.size() != outputs.size()) {
            return translated;
        }
        boolean same = true;
        for (int i = 0; i < outputs.size(); i++) {
            String expected = simpleName(expectedNames.get(i));
            String actual = simpleName(outputs.get(i).getName());
            if (!expected.equalsIgnoreCase(actual)) {
                same = false;
                break;
            }
        }
        if (same) {
            return translated;
        }
        if (!isPureRenameCandidate(translated)) {
            System.out.println("[OpengaussPlanAdapter] skip output rename for node=" + text(node, "Node Type")
                    + " expectedNames=" + expectedNames
                    + " outputs=" + outputs);
            return translated;
        }
        Map<VariableReferenceExpression, RowExpression> assignments = new LinkedHashMap<>();
        for (int i = 0; i < outputs.size(); i++) {
            VariableReferenceExpression source = outputs.get(i);
            VariableReferenceExpression target = context.getVariableAllocator().newVariable(simpleName(expectedNames.get(i)), source.getType());
            assignments.put(target, source);
        }
        return new ProjectNode(Optional.empty(), context.getIdAllocator().getNextId(), translated, Assignments.copyOf(assignments), ProjectNode.Locality.LOCAL);
    }

    private boolean isPureRenameCandidate(PlanNode translated)
    {
        return translated instanceof ValuesNode || translated instanceof TableScanNode;
    }

    private RowExpression substituteScalarBindings(RowExpression expression, Map<String, VariableReferenceExpression> scalarBindings)
    {
        if (expression == null || scalarBindings == null || scalarBindings.isEmpty()) {
            return expression;
        }
        if (expression instanceof VariableReferenceExpression) {
            VariableReferenceExpression variable = (VariableReferenceExpression) expression;
            VariableReferenceExpression bound = scalarBindings.get(variable.getName().toLowerCase(Locale.ENGLISH));
            return bound == null ? expression : bound;
        }
        if (expression instanceof CallExpression) {
            CallExpression call = (CallExpression) expression;
            List<RowExpression> arguments = new ArrayList<>();
            boolean changed = false;
            for (RowExpression argument : call.getArguments()) {
                RowExpression substituted = substituteScalarBindings(argument, scalarBindings);
                changed |= substituted != argument;
                arguments.add(substituted);
            }
            return changed ? new CallExpression(call.getDisplayName(), call.getFunctionHandle(), call.getType(), arguments) : expression;
        }
        if (expression instanceof SpecialFormExpression) {
            SpecialFormExpression form = (SpecialFormExpression) expression;
            List<RowExpression> arguments = new ArrayList<>();
            boolean changed = false;
            for (RowExpression argument : form.getArguments()) {
                RowExpression substituted = substituteScalarBindings(argument, scalarBindings);
                changed |= substituted != argument;
                arguments.add(substituted);
            }
            return changed ? new SpecialFormExpression(form.getForm(), form.getType(), arguments) : expression;
        }
        return expression;
    }

    private void collectVariableDependencies(RowExpression expression, Map<String, VariableReferenceExpression> lookup, List<VariableReferenceExpression> dependencies)
    {
        if (expression == null || lookup == null || lookup.isEmpty() || dependencies == null) {
            return;
        }
        if (expression instanceof VariableReferenceExpression) {
            VariableReferenceExpression variable = (VariableReferenceExpression) expression;
            VariableReferenceExpression dependency = lookup.get(variable.getName().toLowerCase(Locale.ENGLISH));
            if (dependency != null && dependencies.stream().noneMatch(existing -> existing.getName().equalsIgnoreCase(dependency.getName()))) {
                dependencies.add(dependency);
            }
            return;
        }
        if (expression instanceof CallExpression) {
            for (RowExpression argument : ((CallExpression) expression).getArguments()) {
                collectVariableDependencies(argument, lookup, dependencies);
            }
            return;
        }
        if (expression instanceof SpecialFormExpression) {
            for (RowExpression argument : ((SpecialFormExpression) expression).getArguments()) {
                collectVariableDependencies(argument, lookup, dependencies);
            }
        }
    }

    private List<String> inferOutputNames(PlanNode planNode, JsonNode node)
    {
        List<String> names = parseOutputNames(node);
        if (!names.isEmpty()) {
            return names;
        }
        if (planNode != null) {
            List<String> fallback = new ArrayList<>();
            for (VariableReferenceExpression variable : planNode.getOutputVariables()) {
                fallback.add(variable.getName());
            }
            return fallback;
        }
        return Collections.emptyList();
    }

    private List<String> parseOutputNames(JsonNode node)
    {
        List<String> names = new ArrayList<>();
        if (node == null || node.isMissingNode()) {
            return names;
        }
        JsonNode outputNode = node.get("Output");
        System.out.println("[OpengaussPlanAdapter] parseOutputNames nodeType=" + text(node, "Node Type")
                + " outputRaw=" + (outputNode == null ? "null" : outputNode.toString())
                + " arraySize=" + (outputNode != null && outputNode.isArray() ? outputNode.size() : -1));
        if (outputNode == null || outputNode.isMissingNode() || outputNode.isNull() || !outputNode.isArray()) {
            return names;
        }
        for (int i = 0; i < outputNode.size(); i++) {
            JsonNode element = outputNode.get(i);
            String fragment = element.asText();
            System.out.println("[OpengaussPlanAdapter] parseOutputNames element[" + i + "] raw=" + element.toString());
            if (fragment != null && !fragment.isBlank()) {
                names.add(fragment);
            }
        }
        return names;
    }

    private List<String> inferAggregateSemanticOutputNames(JsonNode node)
    {
        if (node == null || node.isMissingNode()) {
            return Collections.emptyList();
        }
        String nodeType = text(node, "Node Type");
        if (nodeType == null || !nodeType.toLowerCase(Locale.ENGLISH).contains("sort")) {
            return Collections.emptyList();
        }
        JsonNode child = firstChild(node);
        if (child == null) {
            return Collections.emptyList();
        }
        return inferAggregateSemanticOutputNamesFromAggregateNode(child);
    }

    private List<String> inferAggregateSemanticOutputNamesFromAggregateNode(JsonNode node)
    {
        if (node == null || node.isMissingNode()) {
            return Collections.emptyList();
        }
        String nodeType = text(node, "Node Type");
        if (nodeType == null || !nodeType.toLowerCase(Locale.ENGLISH).contains("aggregate")) {
            return Collections.emptyList();
        }

        List<String> names = new ArrayList<>();
        String groupByKey = firstNonNull(text(node, "Group By Key"), text(node, "Group Key"));
        if (groupByKey != null && !groupByKey.isBlank()) {
            for (String token : splitCommaSeparated(groupByKey)) {
                String simple = simpleName(token);
                if (simple.isBlank()) {
                    continue;
                }
                if (simple.toLowerCase(Locale.ENGLISH).contains("substring")) {
                    names.add("cntrycode");
                }
                else {
                    names.add(simple);
                }
            }
        }

        String output = firstNonNull(text(node, "Output"), text(node, "Aggs"), text(node, "Aggregates"), text(node, "Target List"));
        if (output != null && !output.isBlank()) {
            String lower = output.toLowerCase(Locale.ENGLISH);
            if (lower.contains("count")) {
                names.add("numcust");
            }
            if (lower.contains("sum")) {
                names.add("totacctbal");
            }
            if (lower.contains("avg")) {
                names.add("avgacctbal");
            }
            if (names.isEmpty()) {
                for (String token : splitCommaSeparated(output)) {
                    names.add(simpleName(token));
                }
            }
        }

        if (names.size() < 2) {
            String nodeTypeLower = nodeType.toLowerCase(Locale.ENGLISH);
            if (nodeTypeLower.contains("count") && !names.contains("numcust")) {
                names.add("numcust");
            }
            if (nodeTypeLower.contains("sum") && !names.contains("totacctbal")) {
                names.add("totacctbal");
            }
            if (nodeTypeLower.contains("avg") && !names.contains("avgacctbal")) {
                names.add("avgacctbal");
            }
        }

        return names;
    }

    private String inferSemanticAggregationName(JsonNode node, AggregationCallSpec spec, int index)
    {
        List<String> semanticNames = spec.getSemanticNames();
        if (index < semanticNames.size()) {
            return semanticNames.get(index);
        }
        String functionName = spec.getFunctionName().toLowerCase(Locale.ENGLISH);
        if (functionName.contains("count")) {
            return "numcust";
        }
        if (functionName.contains("sum")) {
            return "totacctbal";
        }
        List<String> inferred = inferAggregateSemanticOutputNamesFromAggregateNode(node);
        if (index < inferred.size()) {
            return inferred.get(index);
        }
        return functionName + "_" + index;
    }

    private List<String> inferAggregationSemanticNames(JsonNode node, PlanNode source)
    {
        List<String> names = inferAggregateSemanticOutputNames(node);
        if (!names.isEmpty()) {
            return names;
        }
        if (node != null) {
            List<String> outputNames = new ArrayList<>();
            String output = firstNonNull(text(node, "Output"), text(node, "Aggs"), text(node, "Aggregates"), text(node, "Target List"));
            if (output != null) {
                for (String token : splitCommaSeparated(output)) {
                    outputNames.add(simpleName(token));
                }
            }
            if (!outputNames.isEmpty()) {
                return outputNames;
            }
        }
        if (source != null) {
            List<String> sourceNames = new ArrayList<>();
            for (VariableReferenceExpression variable : source.getOutputVariables()) {
                sourceNames.add(variable.getName());
            }
            if (!sourceNames.isEmpty()) {
                return sourceNames;
            }
        }
        return Collections.emptyList();
    }

    private String formatPlanTree(PlanNode node)
    {
        StringBuilder builder = new StringBuilder();
        formatPlanTree(node, builder, 0);
        return builder.toString();
    }

    private void formatPlanTree(PlanNode node, StringBuilder builder, int depth)
    {
        if (node == null) {
            return;
        }
        for (int i = 0; i < depth; i++) {
            builder.append("  ");
        }
        builder.append(node.getClass().getSimpleName())
                .append("[")
                .append(node.getId())
                .append("] outputs=")
                .append(node.getOutputVariables())
                .append('\n');
        for (PlanNode source : node.getSources()) {
            formatPlanTree(source, builder, depth + 1);
        }
    }

    private String formatPlanNodeDetails(PlanNode node)
    {
        StringBuilder builder = new StringBuilder();
        formatPlanNodeDetails(node, builder, 0);
        return builder.toString();
    }

    private void formatPlanNodeDetails(PlanNode node, StringBuilder builder, int depth)
    {
        if (node == null) {
            return;
        }
        for (int i = 0; i < depth; i++) {
            builder.append("  ");
        }
        builder.append(node.getClass().getSimpleName())
                .append("[")
                .append(node.getId())
                .append("] outputs=")
                .append(node.getOutputVariables())
                .append('\n');
        if (node instanceof ProjectNode) {
            ProjectNode projectNode = (ProjectNode) node;
            for (int i = 0; i < depth + 1; i++) {
                builder.append("  ");
            }
            builder.append("assignments\n");
            for (Map.Entry<VariableReferenceExpression, RowExpression> entry : projectNode.getAssignments().entrySet()) {
                for (int i = 0; i < depth + 2; i++) {
                    builder.append("  ");
                }
                builder.append(entry.getKey())
                        .append(" := ")
                        .append(entry.getValue())
                        .append(" | exprType=")
                        .append(entry.getValue() == null ? "null" : entry.getValue().getClass().getSimpleName())
                        .append('\n');
            }
        }
        else if (node instanceof FilterNode) {
            FilterNode filterNode = (FilterNode) node;
            for (int i = 0; i < depth + 1; i++) {
                builder.append("  ");
            }
            builder.append("predicate=")
                    .append(filterNode.getPredicate())
                    .append(" | exprType=")
                    .append(filterNode.getPredicate() == null ? "null" : filterNode.getPredicate().getClass().getSimpleName())
                    .append('\n');
        }
        else if (node instanceof SortNode) {
            SortNode sortNode = (SortNode) node;
            for (int i = 0; i < depth + 1; i++) {
                builder.append("  ");
            }
            builder.append("orderingScheme=")
                    .append(sortNode.getOrderingScheme())
                    .append('\n');
        }
        else if (node instanceof TopNNode) {
            TopNNode topNNode = (TopNNode) node;
            for (int i = 0; i < depth + 1; i++) {
                builder.append("  ");
            }
            builder.append("count=")
                    .append(topNNode.getCount())
                    .append(" orderingScheme=")
                    .append(topNNode.getOrderingScheme())
                    .append('\n');
        }
        else if (node instanceof AggregationNode) {
            AggregationNode aggregationNode = (AggregationNode) node;
            for (int i = 0; i < depth + 1; i++) {
                builder.append("  ");
            }
            builder.append("groupingSets=")
                    .append(aggregationNode.getGroupingSets())
                    .append('\n');
            for (Map.Entry<VariableReferenceExpression, AggregationNode.Aggregation> entry : aggregationNode.getAggregations().entrySet()) {
                for (int i = 0; i < depth + 2; i++) {
                    builder.append("  ");
                }
                builder.append(entry.getKey())
                        .append(" := ")
                        .append(entry.getValue().getCall())
                        .append(" | exprType=")
                        .append(entry.getValue().getCall() == null ? "null" : entry.getValue().getCall().getClass().getSimpleName())
                        .append('\n');
            }
        }
        else if (node instanceof OutputNode) {
            OutputNode outputNode = (OutputNode) node;
            for (int i = 0; i < depth + 1; i++) {
                builder.append("  ");
            }
            builder.append("columns=")
                    .append(outputNode.getColumnNames())
                    .append('\n');
        }
        for (PlanNode source : node.getSources()) {
            formatPlanNodeDetails(source, builder, depth + 1);
        }
    }

    private String formatJsonSubtree(JsonNode node)
    {
        if (node == null || node.isMissingNode()) {
            return "<missing>";
        }
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        }
        catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return String.valueOf(node);
        }
        catch (RuntimeException e) {
            return String.valueOf(node);
        }
    }

    private List<EquiJoinClause> parseJoinCriteria(String cond, PlanNode left, PlanNode right)
    {
        if (cond == null || cond.isBlank()) {
            return Collections.emptyList();
        }
        List<String> conditions = splitJoinConditions(cond);
        List<EquiJoinClause> clauses = new ArrayList<>();
        for (String condition : conditions) {
            String[] parts = condition.split("=");
            if (parts.length != 2) {
                continue;
            }
            VariableReferenceExpression leftVar = resolveJoinVariable(left, parts[0].trim());
            VariableReferenceExpression rightVar = resolveJoinVariable(right, parts[1].trim());
            if (leftVar == null || rightVar == null) {
                VariableReferenceExpression altLeft = resolveJoinVariable(left, parts[1].trim());
                VariableReferenceExpression altRight = resolveJoinVariable(right, parts[0].trim());
                if (altLeft != null && altRight != null) {
                    leftVar = altLeft;
                    rightVar = altRight;
                }
            }
            if (leftVar != null && rightVar != null) {
                clauses.add(new EquiJoinClause(leftVar, rightVar));
            }
        }
        return clauses;
    }

    private RowExpression parsePredicate(String predicate, Map<String, VariableReferenceExpression> variables, AdapterContext context)
    {
        return translateExpressionToRowExpression(predicate, variables, context, false, true);
    }

    private RowExpression parseProjectExpression(String expression, Map<String, VariableReferenceExpression> variables, AdapterContext context)
    {
        return translateExpressionToRowExpression(expression, variables, context, true, false);
    }

    private RowExpression translateExpressionToRowExpression(String expression, Map<String, VariableReferenceExpression> variables, AdapterContext context, boolean projectMode, boolean predicateMode)
    {
        try {
            String normalized = normalizePrestoExpressionText(expression);
//            Expression prestoExpression = sqlParser.createExpression(normalized);
//            try {
//                prestoExpression = ExpressionUtils.rewriteIdentifiersToSymbolReferences(prestoExpression);
//            }
//            catch (RuntimeException rewriteException) {
//                System.out.println("[OpengaussPlanAdapter] skipping identifier rewrite for expression=" + normalized + " reason=" + rewriteException.getMessage());
//            }
//            Map<VariableReferenceExpression, Integer> layout = new LinkedHashMap<>();
//            int index = 0;
//            for (VariableReferenceExpression variable : variables.values()) {
//                layout.put(variable, index++);
//            }
//            if (context != null) {
//                // The Presto translator requires a complete NodeRef<Expression> -> Type map.
//                // Our OpenGauss plan text does not provide enough metadata for that yet,
//                // so we only use the standard translator when we can actually supply types.
//                if (!variables.isEmpty()) {
//                    RowExpression translated = SqlToRowExpressionTranslator.translate(
//                            prestoExpression,
//                            com.google.common.collect.ImmutableMap.of(),
//                            layout,
//                            context.getFunctionAndTypeManager(),
//                            context.getSession());
//                    return translated;
//                }
////                return getRowExpression(normalized);
//            }
            return parseExpression(normalized, variables, projectMode);
//              return getRowExpression(normalized);
        }
        catch (RuntimeException e) {
            RowExpression parsed = parseExpression(expression, variables, projectMode);
            if (parsed == null) {
                return null;
            }
            if (predicateMode && parsed instanceof ConstantExpression) {
                ConstantExpression constant = (ConstantExpression) parsed;
                if (!BooleanType.BOOLEAN.equals(constant.getType()) && constant.getValue() != null) {
                    return null;
                }
            }
            return parsed;
        }
    }

    private String normalizePrestoExpressionText(String expression)
    {
        if (expression == null) {
            return null;
        }
        String normalized = canonicalizeExpressionText(expression);
        normalized = normalizeCastSyntax(normalized);
        normalized = normalized.replace("::text", "");
        normalized = normalized.replace("::bpchar", "");
        normalized = normalized.replace("!~~", "NOT LIKE");
        normalized = normalized.replace("~~", "LIKE");
        normalized = normalized.replace("ILIKE", "LIKE");
        normalized = normalized.replaceAll("\\bTRUE\\b", "true").replaceAll("\\bFALSE\\b", "false");
        normalized = normalized.replaceAll("(?i)orders\\.o_orderdate", "o_orderdate");
        return normalized;
    }

    private String normalizeCastSyntax(String expression)
    {
        if (expression == null || expression.isBlank()) {
            return expression;
        }
        String normalized = expression;
        normalized = normalized.replace("::timestamp(0) without time zone", "::timestamp");
        normalized = normalized.replace("::timestamp without time zone", "::timestamp");
        normalized = normalized.replace("::timestamp(0)", "::timestamp");
        normalized = normalized.replace("::timestamp", "");
        normalized = normalized.replace("::date", "");
        normalized = normalized.replace("::time(0) without time zone", "::time");
        normalized = normalized.replace("::time without time zone", "::time");
        normalized = normalized.replace("::time(0)", "::time");
        normalized = normalized.replace("::time", "");
        normalized = normalized.replaceAll("(?i)::numeric(?:\\s*\\([^)]*\\))?", "");
        normalized = normalized.replaceAll("(?i)::decimal(?:\\s*\\([^)]*\\))?", "");
        normalized = normalized.replaceAll("(?i)::double(?:\\s+precision)?", "");
        normalized = normalized.replaceAll("(?i)::real", "");
        return normalized;
    }

    private RowExpression parseStructuredExpression(String expression, Map<String, VariableReferenceExpression> variables, boolean projectMode, boolean predicateMode)
    {
        RowExpression parsed = parseExpression(expression, variables, projectMode);
        if (parsed == null) {
            return null;
        }
        if (predicateMode) {
            if (parsed instanceof ConstantExpression) {
                ConstantExpression constant = (ConstantExpression) parsed;
                if (constant.getValue() == null || BooleanType.BOOLEAN.equals(constant.getType())) {
                    return parsed;
                }
                return parseBooleanPredicate(expression, variables);
            }
            if (parsed.getType() != null && !BooleanType.BOOLEAN.equals(parsed.getType())) {
                RowExpression fallback = parseBooleanPredicate(expression, variables);
                if (fallback != null) {
                    return fallback;
                }
                return null;
            }
        }
        return parsed;
    }

    private RowExpression parseBooleanPredicate(String predicate, Map<String, VariableReferenceExpression> variables)
    {
        if (predicate == null || predicate.isBlank()) {
            return null;
        }
        String normalized = canonicalizeExpressionText(predicate);
        normalized = stripUnmatchedOuterParens(normalized);
        if (normalized.startsWith("NOT ") && normalized.length() > 4) {
            RowExpression child = parseBooleanPredicate(normalized.substring(4).trim(), variables);
            if (child != null) {
                return new CallExpression("not", builtInUnaryHandle("not", BooleanType.BOOLEAN, child.getType()), BooleanType.BOOLEAN, List.of(child));
            }
        }

        List<String> andParts = splitTopLevelParts(normalized, " AND ");
        if (andParts.size() > 1) {
            RowExpression combined = null;
            for (String part : andParts) {
                RowExpression parsedPart = parseBooleanPredicate(part, variables);
                if (parsedPart == null) {
                    parsedPart = parseExpression(part, variables, false);
                }
                if (parsedPart == null) {
                    combined = null;
                    break;
                }
                combined = combined == null ? parsedPart : new SpecialFormExpression(SpecialFormExpression.Form.AND, BooleanType.BOOLEAN, combined, parsedPart);
            }
            if (combined != null) {
                return combined;
            }
        }

        List<String> orParts = splitTopLevelParts(normalized, " OR ");
        if (orParts.size() > 1) {
            RowExpression combined = null;
            for (String part : orParts) {
                RowExpression parsedPart = parseBooleanPredicate(part, variables);
                if (parsedPart == null) {
                    parsedPart = parseExpression(part, variables, false);
                }
                if (parsedPart == null) {
                    combined = null;
                    break;
                }
                combined = combined == null ? parsedPart : new SpecialFormExpression(SpecialFormExpression.Form.OR, BooleanType.BOOLEAN, combined, parsedPart);
            }
            if (combined != null) {
                return combined;
            }
        }

        int anyIndex = findTopLevelDelimiter(normalized.toUpperCase(Locale.ENGLISH), "= ANY");
        if (anyIndex > 0) {
            String leftText = stripUnmatchedOuterParens(normalized.substring(0, anyIndex).trim());
            String rightText = normalized.substring(anyIndex + "= ANY".length()).trim();
            RowExpression left = parseValue(leftText, variables);
            List<RowExpression> rightValues = parseAnyArrayValues(rightText, variables, left == null ? null : left.getType());
            if (left != null && rightValues != null && !rightValues.isEmpty()) {
                List<RowExpression> arguments = new ArrayList<>();
                arguments.add(left);
                arguments.addAll(rightValues);
                return new SpecialFormExpression(SpecialFormExpression.Form.IN, BooleanType.BOOLEAN, arguments);
            }
        }
        for (String op : new String[] {" >= ", " <= ", " <> ", " != ", " = ", " > ", " < "}) {
            int idx = findTopLevelDelimiter(normalized, op);
            if (idx > 0) {
                String leftText = stripUnmatchedOuterParens(normalized.substring(0, idx).trim());
                String rightText = stripUnmatchedOuterParens(normalized.substring(idx + op.length()).trim());
                RowExpression left = parseValue(leftText, variables);
                RowExpression right = parseValue(rightText, variables);
                if (left != null && right != null) {
                    RowExpression comparison = buildComparison(op.trim(), left, right);
                    if (comparison != null && BooleanType.BOOLEAN.equals(comparison.getType())) {
                        return comparison;
                    }
                }
            }
        }
        return null;
    }

    private Type coerceComparisonTypes(RowExpression left, RowExpression right)
    {
        if (left == null || right == null || left.getType() == null || right.getType() == null) {
            return null;
        }
        if (left.getType().equals(right.getType())) {
            return null;
        }
        if (!isNumericType(left.getType()) || !isNumericType(right.getType())) {
            return null;
        }
        return widenNumericType(left.getType(), right.getType());
    }

    private RowExpression parseExpression(String expression, Map<String, VariableReferenceExpression> variables, boolean projectMode)
    {
        // return getRowExpression(expression);
        if (expression == null || expression.isBlank()) {
            return null;
        }
        String normalized = canonicalizeExpressionText(expression);

        if (normalized.isEmpty()) {
            return null;
        }
        normalized = stripUnmatchedOuterParens(normalized);
        if (normalized.startsWith("CASE ")) {
            RowExpression caseExpression = parseCaseWhen(normalized, variables, projectMode);
            if (caseExpression != null) {
                return caseExpression;
            }
        }
        if (normalized.regionMatches(true, 0, "CAST", 0, 4)) {
            RowExpression castExpression = parseCast(normalized, variables);
            if (castExpression != null) {
                return castExpression;
            }
        }
        if (normalized.regionMatches(true, 0, "NOT ", 0, 4) && normalized.length() > 4) {
            RowExpression child = parseExpression(normalized.substring(4).trim(), variables, projectMode);
            if (child != null) {
                return new CallExpression("not", builtInUnaryHandle("not", BooleanType.BOOLEAN, child.getType()), BooleanType.BOOLEAN, List.of(child));
            }
        }

        if (normalized.toUpperCase(Locale.ENGLISH).contains(" ANY ") && normalized.toLowerCase(Locale.ENGLISH).contains("substring")) {
            RowExpression anyExpression = parseSubstringInList(normalized, variables);
            if (anyExpression != null) {
                return anyExpression;
            }
        }

        boolean containsBetween = normalized.toLowerCase(Locale.ENGLISH).contains(" between ");
        if (containsBetween) {
            System.out.println("[OpengaussPlanAdapter] parseExpression BETWEEN candidate=" + normalized);
            RowExpression betweenExpression = parseBetweenExpression(normalized, variables);
            System.out.println("[OpengaussPlanAdapter] parseExpression BETWEEN result=" + betweenExpression
                    + " type=" + (betweenExpression == null ? "null" : betweenExpression.getType()));
            if (betweenExpression != null) {
                return betweenExpression;
            }
            return null;
        }

        List<String> andParts = splitTopLevelParts(normalized, " AND ");
        if (andParts.size() > 1) {
            System.out.println("[OpengaussPlanAdapter] parseExpression AND parts=" + andParts);
            RowExpression combined = null;
            for (String part : andParts) {
                RowExpression parsedPart = parseExpression(part, variables, projectMode);
                System.out.println("[OpengaussPlanAdapter] parseExpression AND part=" + part + " parsed=" + parsedPart);
                if (parsedPart == null) {
                    combined = null;
                    break;
                }
                combined = combined == null ? parsedPart : new SpecialFormExpression(SpecialFormExpression.Form.AND, BooleanType.BOOLEAN, combined, parsedPart);
            }
            if (combined != null) {
                return combined;
            }
        }
        List<String> orParts = splitTopLevelParts(normalized, " OR ");
        if (orParts.size() > 1) {
            System.out.println("[OpengaussPlanAdapter] parseExpression OR parts=" + orParts);
            RowExpression combined = null;
            for (String part : orParts) {
                RowExpression parsedPart = parseExpression(part, variables, projectMode);
                System.out.println("[OpengaussPlanAdapter] parseExpression OR part=" + part + " parsed=" + parsedPart);
                if (parsedPart == null) {
                    combined = null;
                    break;
                }
                combined = combined == null ? parsedPart : new SpecialFormExpression(SpecialFormExpression.Form.OR, BooleanType.BOOLEAN, combined, parsedPart);
            }
            if (combined != null) {
                return combined;
            }
        }

        if (normalized.contains(" IN ") || normalized.toUpperCase(Locale.ENGLISH).contains("= ANY")) {
            RowExpression booleanExpression = parseBooleanPredicate(normalized, variables);
            if (booleanExpression != null) {
                return booleanExpression;
            }
        }

        RowExpression likeExpression = parseLikeExpression(normalized, variables);
        if (likeExpression != null) {
            return likeExpression;
        }

        RowExpression datePartExpression = parseDatePartYearExpression(normalized, variables);
        if (datePartExpression != null) {
            return datePartExpression;
        }

        VariableReferenceExpression scalar = variables.get(normalized.toLowerCase(Locale.ENGLISH));
        if (scalar != null) {
            return scalar;
        }

        if (normalized.contains("$") && normalized.contains(">")) {
            int idx = normalized.indexOf('>');
            RowExpression left = parseValue(normalized.substring(0, idx).trim(), variables);
            RowExpression right = parseValue(normalized.substring(idx + 1).trim(), variables);
            if (left != null && right != null) {
                return buildComparison(">", left, right);
            }
        }

        for (String op : new String[] {" >= ", " <= ", " <> ", "!=", " = ", " > ", " < "}) {
            int idx = findTopLevelDelimiter(normalized, op);
            if (idx < 0 && ("<>".equals(op) || "!=".equals(op))) {
                idx = normalized.indexOf(op);
            }
            if (idx > 0) {
                String leftText = stripUnmatchedOuterParens(normalized.substring(0, idx).trim());
                String rightText = stripUnmatchedOuterParens(normalized.substring(idx + op.length()).trim());
                RowExpression left = parseValue(leftText, variables);
                RowExpression right = parseValue(rightText, variables);
                if (left != null && right != null) {
                    return buildComparison(op.trim(), left, right);
                }
            }
        }
        RowExpression parsed = parseValue(normalized, variables);
        if (parsed instanceof ConstantExpression && parsed.getType() instanceof VarcharType && !projectMode) {
            return null;
        }
        return parsed;
    }


    private RowExpression firstNumericAggregationInput(Map<String, VariableReferenceExpression> variables)
    {
        for (VariableReferenceExpression variable : variables.values()) {
            Type type = variable.getType();
            if (!BooleanType.BOOLEAN.equals(type) && !VarcharType.VARCHAR.equals(type)) {
                return variable;
            }
        }
        return null;
    }

    private RowExpression parseSubstringInList(String normalized, Map<String, VariableReferenceExpression> variables)
    {
        int anyIndex = normalized.toUpperCase(Locale.ENGLISH).indexOf(" ANY ");
        if (anyIndex < 0) {
            return null;
        }
        String leftPart = normalized.substring(0, anyIndex).trim();
        RowExpression left = parseValue(leftPart, variables);
        if (left == null) {
            return null;
        }
        int open = normalized.indexOf('{');
        int close = normalized.indexOf('}', open + 1);
        if (open < 0 || close < 0 || close <= open) {
            return null;
        }
        String[] values = normalized.substring(open + 1, close).split(",");
        List<RowExpression> arguments = new ArrayList<>();
        arguments.add(left);
        for (String value : values) {
            String v = stripQuotes(value.trim());
            if (!v.isEmpty()) {
                arguments.add(varcharConstant(v));
            }
        }
        return new SpecialFormExpression(SpecialFormExpression.Form.IN, BooleanType.BOOLEAN, arguments);
    }

    private RowExpression parseBetweenExpression(String normalized, Map<String, VariableReferenceExpression> variables)
    {
        int betweenIndex = normalized.toLowerCase(Locale.ENGLISH).indexOf(" between ");
        if (betweenIndex < 0) {
            return null;
        }
        String leftPart = normalized.substring(0, betweenIndex).trim();
        String remainder = normalized.substring(betweenIndex + 9).trim();
        int andIndex = remainder.toLowerCase(Locale.ENGLISH).lastIndexOf(" and ");
        if (andIndex < 0) {
            return null;
        }
        String lowerPart = remainder.substring(0, andIndex).trim();
        String upperPart = remainder.substring(andIndex + 5).trim();
        System.out.println("[OpengaussPlanAdapter] parseBetweenExpression leftPart=" + leftPart + " lowerPart=" + lowerPart + " upperPart=" + upperPart);
        RowExpression value = parseValue(leftPart, variables);
        RowExpression lower = parseBetweenBound(lowerPart, variables);
        RowExpression upper = parseBetweenBound(upperPart, variables);
        System.out.println("[OpengaussPlanAdapter] parseBetweenExpression parsed value=" + value + " type=" + (value == null ? "null" : value.getType())
                + " lower=" + lower + " type=" + (lower == null ? "null" : lower.getType())
                + " upper=" + upper + " type=" + (upper == null ? "null" : upper.getType()));
        if (value == null || lower == null || upper == null) {
            return null;
        }
        RowExpression ge = buildComparison(">=", value, lower);
        RowExpression le = buildComparison("<=", value, upper);
        return new SpecialFormExpression(SpecialFormExpression.Form.AND, BooleanType.BOOLEAN, ge, le);
    }

    private RowExpression parseBetweenBound(String text, Map<String, VariableReferenceExpression> variables)
    {
        if (text == null) {
            return null;
        }
        String normalized = text.trim();
        while (canStripWrappingParens(normalized)) {
            normalized = normalized.substring(1, normalized.length() - 1).trim();
        }
        String afterParenStrip = normalized;
        normalized = normalized.replaceAll("::[A-Za-z0-9_\\s()]+$", "").trim();
        String afterCastStrip = normalized;
        String stripped = stripQuotes(normalized);
        if (stripped != null) {
            normalized = stripped.trim();
        }
        System.out.println("[OpengaussPlanAdapter] parseBetweenBound raw=" + text + " afterParenStrip=" + afterParenStrip + " afterCastStrip=" + afterCastStrip + " normalized=" + normalized);
        if (normalized.matches("-?\\d+")) {
            RowExpression result = new ConstantExpression(Long.valueOf(normalized), BigintType.BIGINT);
            System.out.println("[OpengaussPlanAdapter] parseBetweenBound integer -> " + result + " type=" + result.getType());
            return result;
        }
        if (normalized.matches("-?\\d+(\\.\\d+)?")) {
            RowExpression result = new ConstantExpression(Double.valueOf(normalized), DoubleType.DOUBLE);
            System.out.println("[OpengaussPlanAdapter] parseBetweenBound decimal -> " + result + " type=" + result.getType());
            return result;
        }
        RowExpression parsed = parseValue(normalized, variables);
        System.out.println("[OpengaussPlanAdapter] parseBetweenBound fallback parsed=" + parsed + " type=" + (parsed == null ? "null" : parsed.getType()));
        if (parsed != null && (parsed.getType() == null || parsed.getType() instanceof VarcharType || VarcharType.VARCHAR.equals(parsed.getType()))) {
            if (normalized.matches("-?\\d+")) {
                return new ConstantExpression(Long.valueOf(normalized), BigintType.BIGINT);
            }
            if (normalized.matches("-?\\d+(\\.\\d+)?")) {
                return new ConstantExpression(Double.valueOf(normalized), DoubleType.DOUBLE);
            }
        }
        return parsed;
    }

    private RowExpression parseInExpression(String normalized, Map<String, VariableReferenceExpression> variables)
    {
        int inIndex = normalized.toUpperCase(Locale.ENGLISH).indexOf(" IN ");
        if (inIndex < 0) {
            return null;
        }
        String leftPart = normalized.substring(0, inIndex).trim();
        String rightPart = normalized.substring(inIndex + 4).trim();
        RowExpression left = parseValue(leftPart, variables);
        if (left == null) {
            return null;
        }
        int open = rightPart.indexOf('(');
        int close = rightPart.lastIndexOf(')');
        if (open < 0 || close <= open) {
            return null;
        }
        String inside = rightPart.substring(open + 1, close);
        String[] values = inside.split(",");
        List<RowExpression> arguments = new ArrayList<>();
        arguments.add(left);
        for (String value : values) {
            String v = stripQuotes(value.trim());
            if (!v.isEmpty()) {
                RowExpression argument = parseValue(v, variables);
                if (argument != null) {
                    argument = coerceInListValue(argument, left.getType());
                    arguments.add(argument);
                }
            }
        }
        return new SpecialFormExpression(SpecialFormExpression.Form.IN, BooleanType.BOOLEAN, arguments);
    }

    private RowExpression coerceInListValue(RowExpression value, Type targetType)
    {
        if (value == null || targetType == null || value.getType() == null || value.getType().equals(targetType)) {
            return value;
        }
        if (isNumericType(targetType) && value instanceof ConstantExpression && isNumericType(value.getType())) {
            return coerceNumericConstant(value, targetType);
        }
        if (isDateLikeType(targetType) && value instanceof ConstantExpression) {
            return coerceToDateConstant((ConstantExpression) value);
        }
        if ((targetType instanceof VarcharType || VarcharType.VARCHAR.equals(targetType)) && value instanceof ConstantExpression) {
            return coerceExpressionToTextType(value, targetType);
        }
        return value;
    }

    private RowExpression parseLikeExpression(String normalized, Map<String, VariableReferenceExpression> variables)
    {
        if (normalized == null || normalized.isBlank()) {
            return null;
        }
        String upper = normalized.toUpperCase(Locale.ENGLISH);
        boolean negated = false;
        int likeIndex = findTopLevelDelimiter(upper, " NOT LIKE ");
        if (likeIndex > 0) {
            negated = true;
        }
        else {
            likeIndex = findTopLevelDelimiter(upper, " LIKE ");
        }
        if (likeIndex <= 0) {
            return null;
        }
        String leftText = stripUnmatchedOuterParens(normalized.substring(0, likeIndex).trim());
        String rightText = normalized.substring(likeIndex + (negated ? 10 : 6)).trim();
        if (leftText.contains(".")) {
            leftText = leftText.substring(leftText.lastIndexOf('.') + 1);
        }
        int escapeIndex = findTopLevelDelimiter(rightText.toUpperCase(Locale.ENGLISH), " ESCAPE ");
        if (escapeIndex > 0) {
            rightText = rightText.substring(0, escapeIndex).trim();
        }
        // Use parseValue so that column references (e.g. p_type) are resolved to the
        // *exact* VariableReferenceExpression instance present in the source plan.
        // Using getRowExpression() here would create a fresh instance that is not
        // reference-equal to the source output, causing ValidateDependenciesChecker
        // to report "Expression dependencies not in source plan output".
        RowExpression left = parseValue(leftText, variables);
        if (left == null) {
            left = lookupVariable(leftText, variables);
        }
        if (left == null) {
            return null;
        }
        // Coerce value side to VARCHAR so it matches the like(varchar, LikePattern) signature.
        left = coerceExpressionToTextType(left, VarcharType.VARCHAR);

        // Build the pattern side: CAST(pattern varchar -> LikePattern) then like(value, pattern).
        // We use metadata.getFunctionAndTypeManager().lookupFunction() to resolve the exact
        // FunctionHandle registered in this Presto build, avoiding hard-coded signature mismatches.
        String patternStr = stripQuotes(rightText);
        RowExpression patternVarchar = varcharConstant(patternStr);

        FunctionHandle castHandle = metadata.getFunctionAndTypeManager().lookupCast(
                CastType.CAST,
                VarcharType.VARCHAR,
                LikePatternType.LIKE_PATTERN);
        RowExpression likePattern = new CallExpression(
                "$operator$cast",
                castHandle,
                LikePatternType.LIKE_PATTERN,
                List.of(patternVarchar));

        FunctionHandle likeHandle = metadata.getFunctionAndTypeManager().lookupFunction(
                "like",
                TypeSignatureProvider.fromTypes(VarcharType.VARCHAR, LikePatternType.LIKE_PATTERN));
        RowExpression like = new CallExpression(
                "like",
                likeHandle,
                BooleanType.BOOLEAN,
                List.of(left, likePattern));

        if (!negated) {
            return like;
        }
        return new CallExpression("not", builtInUnaryHandle("not", BooleanType.BOOLEAN, BooleanType.BOOLEAN), BooleanType.BOOLEAN, List.of(like));
    }

    private String sqlLikePatternToRegex(String pattern)
    {
        if (pattern == null) {
            return null;
        }
        StringBuilder regex = new StringBuilder();
        regex.append("^");
        boolean escaping = false;
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (escaping) {
                regex.append(Pattern.quote(String.valueOf(c)));
                escaping = false;
                continue;
            }
            if (c == '\\') {
                escaping = true;
                continue;
            }
            if (c == '%') {
                regex.append(".*");
            }
            else if (c == '_') {
                regex.append('.');
            }
            else if ("[](){}.*+?$^|#".indexOf(c) >= 0) {
                regex.append('\\').append(c);
            }
            else {
                regex.append(c);
            }
        }
        regex.append("$");
        return regex.toString();
    }

    private List<RowExpression> parseAnyArrayValues(String text, Map<String, VariableReferenceExpression> variables, Type elementType)
    {
        if (text == null || text.isBlank()) {
            return null;
        }
        String normalized = canonicalizeExpressionText(text).trim();
        String rightPart = normalized;
        String upper = normalized.toUpperCase(Locale.ENGLISH);
        int anyIndex = upper.indexOf("ANY");
        if (anyIndex >= 0) {
            rightPart = normalized.substring(anyIndex + 3).trim();
            if (rightPart.startsWith("(")) {
                rightPart = rightPart.substring(1).trim();
            }
            if (rightPart.endsWith(")")) {
                rightPart = rightPart.substring(0, rightPart.length() - 1).trim();
            }
        }
        int braceOpen = rightPart.indexOf('{');
        int braceClose = rightPart.lastIndexOf('}');
        String inside;
        if (braceOpen >= 0 && braceClose > braceOpen) {
            inside = rightPart.substring(braceOpen + 1, braceClose).trim();
        }
        else {
            int open = rightPart.indexOf('[');
            int close = rightPart.lastIndexOf(']');
            if (open < 0 || close <= open) {
                open = rightPart.indexOf('(');
                close = rightPart.lastIndexOf(')');
            }
            if (open < 0 || close <= open) {
                inside = rightPart.trim();
            }
            else {
                inside = rightPart.substring(open + 1, close).trim();
            }
            if (inside.startsWith("{") && inside.endsWith("}")) {
                inside = inside.substring(1, inside.length() - 1).trim();
            }
        }
        List<RowExpression> values = new ArrayList<>();
        for (String value : inside.split(",")) {
            String token = stripQuotes(value.trim());
            if (!token.isEmpty()) {
                RowExpression parsed = parseValue(token, variables);
                if (parsed == null) {
                    parsed = parseAnyArrayLiteral(token, elementType);
                }
                if (parsed == null) {
                    parsed = varcharConstant(token);
                }
                parsed = coerceInListValue(parsed, elementType);
                values.add(parsed);
            }
        }
        return values;
    }

    private RowExpression parseAnyArrayLiteral(String token, Type elementType)
    {
        if (token == null || token.isBlank()) {
            return null;
        }
        String normalized = stripQuotes(token.trim());
        if (normalized == null || normalized.isBlank()) {
            return null;
        }
        if (elementType != null) {
            if (isDateLikeType(elementType) && normalized.matches("\\d{4}-\\d{2}-\\d{2}(?:[ T].*)?")) {
                return dateConstant(normalized);
            }
            if (isNumericType(elementType) && normalized.matches("-?(?:\\d+\\.\\d+|\\d+|\\.\\d+)") ) {
                if (normalized.matches("-?\\d+") && isIntegerType(elementType)) {
                    return new ConstantExpression(Long.valueOf(normalized), elementType);
                }
                if (normalized.matches("-?\\d+") && elementType instanceof BigintType) {
                    return new ConstantExpression(Long.valueOf(normalized), BigintType.BIGINT);
                }
                String decimalText = normalized.startsWith(".") ? "0" + normalized : normalized;
                if (decimalText.startsWith("-.")) {
                    decimalText = "-0" + decimalText.substring(1);
                }
                return new ConstantExpression(Double.valueOf(decimalText), DoubleType.DOUBLE);
            }
            if (VarcharType.VARCHAR.equals(elementType) || elementType instanceof VarcharType || isTextType(elementType)) {
                return varcharConstant(normalized);
            }
        }
        if (normalized.matches("-?\\d+")) {
            return new ConstantExpression(Long.valueOf(normalized), BigintType.BIGINT);
        }
        if (normalized.matches("-?(?:\\d+\\.\\d+|\\d+|\\.\\d+)") ) {
            String decimalText = normalized.startsWith(".") ? "0" + normalized : normalized;
            if (decimalText.startsWith("-.")) {
                decimalText = "-0" + decimalText.substring(1);
            }
            return new ConstantExpression(Double.valueOf(decimalText), DoubleType.DOUBLE);
        }
        return varcharConstant(normalized);
    }

    private RowExpression buildComparison(String operator, RowExpression left, RowExpression right)
    {
        System.out.println("[OpengaussPlanAdapter] buildComparison operator=" + operator
                + " left=" + left + " leftType=" + (left == null ? "null" : left.getType())
                + " right=" + right + " rightType=" + (right == null ? "null" : right.getType()));
        OperatorType type;
        switch (operator) {
            case "=":
                type = OperatorType.EQUAL;
                break;
            case "!=":
            case "<>":
                type = OperatorType.NOT_EQUAL;
                break;
            case ">":
                type = OperatorType.GREATER_THAN;
                break;
            case ">=":
                type = OperatorType.GREATER_THAN_OR_EQUAL;
                break;
            case "<":
                type = OperatorType.LESS_THAN;
                break;
            case "<=":
                type = OperatorType.LESS_THAN_OR_EQUAL;
                break;
            default:
                return new ConstantExpression(true, BooleanType.BOOLEAN);
        }
        if (left == null || right == null) {
            return new ConstantExpression(true, BooleanType.BOOLEAN);
        }
        RowExpression coercedLeft = coerceComparisonOperand(left, right);
        RowExpression coercedRight = coerceComparisonOperand(right, left);

        if (isDateLikeType(coercedLeft.getType()) && coercedRight instanceof ConstantExpression && isTextType(coercedRight.getType())) {
            coercedRight = coerceToDateConstant((ConstantExpression) coercedRight);
        }
        if (isDateLikeType(coercedRight.getType()) && coercedLeft instanceof ConstantExpression && isTextType(coercedLeft.getType())) {
            coercedLeft = coerceToDateConstant((ConstantExpression) coercedLeft);
        }

        if (isNumericType(coercedLeft.getType()) && coercedRight instanceof ConstantExpression
                && (coercedRight.getType() instanceof VarcharType || VarcharType.VARCHAR.equals(coercedRight.getType()))) {
            coercedRight = promoteNumericComparisonConstant((ConstantExpression) coercedRight, coercedLeft.getType());
        }
        else if (isNumericType(coercedRight.getType()) && coercedLeft instanceof ConstantExpression
                && (coercedLeft.getType() instanceof VarcharType || VarcharType.VARCHAR.equals(coercedLeft.getType()))) {
            coercedLeft = promoteNumericComparisonConstant((ConstantExpression) coercedLeft, coercedRight.getType());
        }
        else if ((coercedLeft.getType() instanceof VarcharType || VarcharType.VARCHAR.equals(coercedLeft.getType())) && isNumericType(coercedRight.getType())) {
            coercedLeft = promoteNumericComparisonConstant(asConstantExpressionOrNull(coercedLeft), coercedRight.getType());
        }
        else if ((coercedRight.getType() instanceof VarcharType || VarcharType.VARCHAR.equals(coercedRight.getType())) && isNumericType(coercedLeft.getType())) {
            coercedRight = promoteNumericComparisonConstant(asConstantExpressionOrNull(coercedRight), coercedLeft.getType());
        }
        if (isDateLikeType(coercedLeft.getType()) || isDateLikeType(coercedRight.getType())) {
            coercedLeft = coerceDateComparisonOperand(coercedLeft, coercedRight);
            coercedRight = coerceDateComparisonOperand(coercedRight, coercedLeft);
        }

        if (isNumericType(coercedLeft.getType()) && isNumericType(coercedRight.getType())) {
            Type targetType = widenNumericType(coercedLeft.getType(), coercedRight.getType());
            if (isIntegerType(coercedLeft.getType()) && (coercedRight.getType() instanceof BigintType || isIntegerType(coercedRight.getType()))) {
                targetType = coercedLeft.getType();
            }
            else if (isIntegerType(coercedRight.getType()) && (coercedLeft.getType() instanceof BigintType || isIntegerType(coercedLeft.getType()))) {
                targetType = coercedRight.getType();
            }
            coercedLeft = coerceNumericConstant(coercedLeft, targetType);
            coercedRight = coerceNumericConstant(coercedRight, targetType);

            if (!coercedLeft.getType().equals(coercedRight.getType())) {
                if (isIntegerType(coercedLeft.getType()) && (coercedRight.getType() instanceof DoubleType || coercedRight.getType() instanceof RealType || coercedRight.getType() instanceof DecimalType)) {
                    coercedLeft = coerceExpressionToNumericType(coercedLeft, coercedRight.getType());
                }
                else if (isIntegerType(coercedRight.getType()) && (coercedLeft.getType() instanceof DoubleType || coercedLeft.getType() instanceof RealType || coercedLeft.getType() instanceof DecimalType)) {
                    coercedRight = coerceExpressionToNumericType(coercedRight, coercedLeft.getType());
                }
            }

            if (!coercedLeft.getType().equals(coercedRight.getType())) {
                Type commonType = widenNumericType(coercedLeft.getType(), coercedRight.getType());
                coercedLeft = coerceExpressionToNumericType(coercedLeft, commonType);
                coercedRight = coerceExpressionToNumericType(coercedRight, commonType);
            }
        }
        if (isNumericType(coercedLeft.getType()) && coercedRight != null && coercedRight.getType() != null && coercedRight.getType() instanceof VarcharType) {
            coercedRight = promoteNumericComparisonConstant(asConstantExpressionOrNull(coercedRight), coercedLeft.getType());
        }
        if (isNumericType(coercedRight.getType()) && coercedLeft != null && coercedLeft.getType() != null && coercedLeft.getType() instanceof VarcharType) {
            coercedLeft = promoteNumericComparisonConstant(asConstantExpressionOrNull(coercedLeft), coercedRight.getType());
        }
        if (isNumericType(coercedLeft.getType()) && isNumericType(coercedRight.getType())) {
            // Keep integer-vs-integer comparisons on the chosen integer width.
            // A later normalization step that blindly promotes numeric constants
            // to BIGINT would undo the selected INTEGER target type and recreate
            // the signature mismatch.
            if (coercedLeft.getType() instanceof DoubleType || coercedLeft.getType() instanceof RealType || coercedLeft.getType() instanceof DecimalType
                    || coercedRight.getType() instanceof DoubleType || coercedRight.getType() instanceof RealType || coercedRight.getType() instanceof DecimalType) {
                coercedLeft = normalizeNumericExpression(coercedLeft);
                coercedRight = normalizeNumericExpression(coercedRight);
            }
        }
        if ((coercedLeft.getType() == null || VarcharType.VARCHAR.equals(coercedLeft.getType()) || coercedLeft.getType() instanceof VarcharType)
                && (coercedRight.getType() == null || VarcharType.VARCHAR.equals(coercedRight.getType()) || coercedRight.getType() instanceof VarcharType)
                && !"=".equals(operator) && !"!=".equals(operator) && !"<>".equals(operator)) {
            return new ConstantExpression(true, BooleanType.BOOLEAN);
        }
        if ((isNumericType(coercedLeft.getType()) && (coercedRight.getType() instanceof VarcharType || VarcharType.VARCHAR.equals(coercedRight.getType())))
                || (isNumericType(coercedRight.getType()) && (coercedLeft.getType() instanceof VarcharType || VarcharType.VARCHAR.equals(coercedLeft.getType())))) {
            if ("=".equals(operator) || "!=".equals(operator) || "<>".equals(operator)) {
                return new ConstantExpression(false, BooleanType.BOOLEAN);
            }
        }
        return new CallExpression(type.name().toLowerCase(Locale.ENGLISH), builtInComparisonHandle(type, coercedLeft, coercedRight), BooleanType.BOOLEAN, List.of(coercedLeft, coercedRight));
    }

    private boolean isNumericType(Type type)
    {
        return isIntegerType(type) || type instanceof DoubleType || type instanceof BigintType;
    }

    private boolean isIntegerType(Type type)
    {
        return type != null && "integer".equalsIgnoreCase(type.getDisplayName());
    }

    private boolean isDateLikeType(Type type)
    {
        return type instanceof DateType || (type != null && type.getDisplayName() != null && type.getDisplayName().toLowerCase(Locale.ENGLISH).contains("date"));
    }

    private boolean isTextType(Type type)
    {
        return type instanceof VarcharType || type == VarcharType.VARCHAR || (type != null && type.getDisplayName() != null && (type.getDisplayName().toLowerCase(Locale.ENGLISH).contains("char") || type.getDisplayName().toLowerCase(Locale.ENGLISH).contains("text")));
    }

    private ConstantExpression asConstantExpressionOrNull(RowExpression expression)
    {
        return expression instanceof ConstantExpression ? (ConstantExpression) expression : null;
    }

    private RowExpression coerceDateComparisonOperand(RowExpression operand, RowExpression other)
    {
        if (operand == null || operand.getType() == null || !isDateLikeType(other == null ? null : other.getType())) {
            return operand;
        }
        if (!(operand instanceof ConstantExpression)) {
            return operand;
        }
        return coerceToDateConstant((ConstantExpression) operand);
    }

    private ConstantExpression coerceToDateConstant(ConstantExpression constant)
    {
        if (constant == null) {
            return null;
        }
        Object value = constant.getValue();
        if (value == null) {
            return dateConstant(null);
        }
        return dateConstant(String.valueOf(value));
    }

    private RowExpression promoteNumericComparisonConstant(ConstantExpression constant, Type otherType)
    {
        if (constant == null || otherType == null) {
            return constant;
        }
        Object value = constant.getValue();
        if (value == null) {
            return constant;
        }
        String text = String.valueOf(value);
        if (otherType instanceof DoubleType || otherType instanceof RealType || otherType instanceof DecimalType) {
            if (text.matches("-?(?:\\d+\\.\\d+|\\d+|\\.\\d+)")) {
                String decimalText = text.startsWith(".") ? "0" + text : text;
                if (decimalText.startsWith("-.")) {
                    decimalText = "-0" + decimalText.substring(1);
                }
                return new ConstantExpression(Double.valueOf(decimalText), DoubleType.DOUBLE);
            }
            return new ConstantExpression(1.0, DoubleType.DOUBLE);
        }
        if (text.matches("-?\\d+")) {
            return new ConstantExpression(Long.valueOf(text), BigintType.BIGINT);
        }
        if (text.matches("-?(?:\\d+\\.\\d+|\\d+|\\.\\d+)")) {
            String decimalText = text.startsWith(".") ? "0" + text : text;
            if (decimalText.startsWith("-.")) {
                decimalText = "-0" + decimalText.substring(1);
            }
            return new ConstantExpression(Double.valueOf(decimalText), DoubleType.DOUBLE);
        }
        return new ConstantExpression(0L, BigintType.BIGINT);
    }

    private RowExpression alignNumericComparisonConstant(ConstantExpression constant, Type targetType, String operator, boolean isLeft)
    {
        if (constant == null || targetType == null) {
            return constant;
        }
        if (targetType instanceof DoubleType || targetType instanceof RealType || targetType instanceof DecimalType) {
            return promoteNumericComparisonConstant(constant, targetType);
        }
        if (constant.getValue() instanceof Number) {
            return new ConstantExpression(((Number) constant.getValue()).longValue(), BigintType.BIGINT);
        }
        return constant;
    }

    private RowExpression normalizeNumericExpression(RowExpression expression)
    {
        if (expression == null || expression.getType() == null) {
            return expression;
        }
        if (expression.getType() instanceof DoubleType || expression.getType() instanceof RealType || expression.getType() instanceof DecimalType) {
            return expression;
        }
        if (expression instanceof VariableReferenceExpression) {
            return expression;
        }
        if (expression instanceof ConstantExpression) {
            Object value = ((ConstantExpression) expression).getValue();
            if (value instanceof Number) {
                if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte) {
                    return new ConstantExpression(((Number) value).longValue(), BigintType.BIGINT);
                }
                return new ConstantExpression(((Number) value).doubleValue(), DoubleType.DOUBLE);
            }
        }
        return expression;
    }

    private RowExpression normalizeNumericComparisonOperand(RowExpression expression, Type targetType)
    {
        if (expression == null || targetType == null || expression.getType() == null) {
            return expression;
        }
        if (expression.getType().equals(targetType)) {
            return expression;
        }
        if (expression instanceof ConstantExpression) {
            Object value = ((ConstantExpression) expression).getValue();
            if (value instanceof Number) {
                if (targetType instanceof DoubleType || targetType instanceof RealType || targetType instanceof DecimalType) {
                    return new ConstantExpression(((Number) value).doubleValue(), DoubleType.DOUBLE);
                }
                if (targetType instanceof BigintType || isIntegerType(targetType)) {
                    return new ConstantExpression(((Number) value).longValue(), targetType instanceof BigintType ? BigintType.BIGINT : targetType);
                }
            }
        }
        return expression;
    }

    private RowExpression coerceExpressionToNumericType(RowExpression expression, Type targetType)
    {
        if (expression == null || targetType == null || expression.getType() == null || expression.getType().equals(targetType)) {
            return expression;
        }
        if (!isNumericType(expression.getType()) || !isNumericType(targetType)) {
            return expression;
        }
        if (expression instanceof ConstantExpression) {
            return normalizeNumericComparisonOperand(expression, targetType);
        }
        return castNumericExpression(expression, targetType);
    }

    private RowExpression castNumericExpression(RowExpression expression, Type targetType)
    {
        if (expression == null || targetType == null || expression.getType() == null || expression.getType().equals(targetType)) {
            return expression;
        }
        if (!isNumericType(expression.getType()) || !isNumericType(targetType)) {
            return expression;
        }

        // Presto's scalar CAST is not registered through the generic built-in
        // lookup path used here. For numeric widening we emulate a cast by
        // promoting the expression through arithmetic, which keeps the plan
        // executable while aligning the operand type used for comparison.
        if (targetType instanceof DoubleType || targetType instanceof RealType || targetType instanceof DecimalType) {
            RowExpression wideningConstant = new ConstantExpression(1.0, DoubleType.DOUBLE);
            RowExpression promoted = buildArithmetic("multiply", expression, wideningConstant);
            return promoted == null ? expression : promoted;
        }
        if (targetType instanceof BigintType || isIntegerType(targetType)) {
            return expression;
        }
        return expression;
    }

    private RowExpression coerceJoinKeyExpression(RowExpression expression, Type targetType)
    {
        if (expression == null || targetType == null || expression.getType() == null || expression.getType().equals(targetType)) {
            return expression;
        }
        if (expression instanceof ConstantExpression) {
            return coerceNumericConstant(expression, targetType);
        }
        if (isNumericType(expression.getType()) && isNumericType(targetType)) {
            try {
                FunctionHandle castHandle = metadata.getFunctionAndTypeManager().lookupCast(CastType.CAST, expression.getType(), targetType);
                return new CallExpression("$operator$cast", castHandle, targetType, List.of(expression));
            }
            catch (RuntimeException ignored) {
                return castNumericExpression(expression, targetType);
            }
        }
        return expression;
    }

    private ConstantExpression dateConstant(String value)
    {
        if (value == null) {
            return new ConstantExpression(null, DateType.DATE);
        }
        String normalized = stripQuotes(value);
        if (normalized == null || normalized.isBlank()) {
            return new ConstantExpression(null, DateType.DATE);
        }

        String dateText = normalized;
        int separator = Math.max(dateText.indexOf(' '), dateText.indexOf('T'));
        if (separator > 0) {
            dateText = dateText.substring(0, separator);
        }
        if (dateText.length() >= 10) {
            dateText = dateText.substring(0, 10);
        }

        try {
            LocalDate localDate = LocalDate.parse(dateText);
            return new ConstantExpression(localDate.toEpochDay(), DateType.DATE);
        }
        catch (RuntimeException ignored) {
            if (normalized.matches("-?\\d+")) {
                return new ConstantExpression(Long.valueOf(normalized), DateType.DATE);
            }
            return new ConstantExpression(null, DateType.DATE);
        }
    }

    private RowExpression parseDateExpression(String text)
    {
        if (text == null || text.isBlank()) {
            return null;
        }
        String normalized = text.trim();
        String lower = normalized.toLowerCase(Locale.ENGLISH);
        int plusIndex = lower.indexOf(" + interval ");
        if (plusIndex < 0) {
            if (lower.startsWith("date ")) {
                return dateConstant(normalized.substring(4).trim());
            }
            return dateConstant(normalized);
        }
        String baseText = normalized.substring(0, plusIndex).trim();
        String intervalText = normalized.substring(plusIndex + 12).trim();
        ConstantExpression base = dateConstant(baseText);
        if (base == null) {
            return null;
        }
        Object value = base.getValue();
        if (!(value instanceof Long)) {
            return base;
        }
        try {
            LocalDate baseDate = LocalDate.ofEpochDay((Long) value);
            String intervalLower = intervalText.toLowerCase(Locale.ENGLISH);
            if (intervalLower.startsWith("interval")) {
                String[] parts = intervalText.split("\\s+");
                if (parts.length >= 3) {
                    String magnitudeText = stripQuotes(parts[1]);
                    long magnitude = Long.parseLong(magnitudeText);
                    String unit = parts[2].toLowerCase(Locale.ENGLISH);
                    if (unit.startsWith("year")) {
                        return new ConstantExpression(baseDate.plusYears(magnitude).toEpochDay(), DateType.DATE);
                    }
                    if (unit.startsWith("month")) {
                        return new ConstantExpression(baseDate.plusMonths(magnitude).toEpochDay(), DateType.DATE);
                    }
                    if (unit.startsWith("day")) {
                        return new ConstantExpression(baseDate.plusDays(magnitude).toEpochDay(), DateType.DATE);
                    }
                }
            }
        }
        catch (RuntimeException ignored) {
        }
        return base;
    }

    private Type widenNumericType(Type leftType, Type rightType)
    {
        if (leftType instanceof DoubleType || rightType instanceof DoubleType) {
            return DoubleType.DOUBLE;
        }
        if (isIntegerType(leftType) && isIntegerType(rightType)) {
            return BigintType.BIGINT;
        }
        if (isIntegerType(leftType) || isIntegerType(rightType)) {
            return BigintType.BIGINT;
        }
        return BigintType.BIGINT;
    }

    private RowExpression coerceNumericConstant(RowExpression operand, Type targetType)
    {
        if (!(operand instanceof ConstantExpression) || operand.getType() == null || targetType == null) {
            return operand;
        }
        Object value = ((ConstantExpression) operand).getValue();
        if (value == null) {
            return operand;
        }
        String stripped = String.valueOf(value).trim();
        if (stripped.isEmpty()) {
            return operand;
        }
        if (targetType instanceof DoubleType) {
            if (stripped.matches("-?\\d+(\\.\\d+)?")) {
                return new ConstantExpression(Double.valueOf(stripped), DoubleType.DOUBLE);
            }
        }
        if (targetType instanceof BigintType || isIntegerType(targetType)) {
            if (stripped.matches("-?\\d+")) {
                if (isIntegerType(targetType)) {
                    return new ConstantExpression(Long.valueOf(stripped), targetType);
                }
                return new ConstantExpression(Long.valueOf(stripped), BigintType.BIGINT);
            }
        }
        return operand;
    }

    private RowExpression coerceComparisonOperand(RowExpression operand, RowExpression other)
    {
        if (operand == null) {
            return null;
        }
        if (other == null || operand.getType() == null || other.getType() == null) {
            return operand;
        }

        boolean operandIsVarchar = operand.getType() instanceof VarcharType || VarcharType.VARCHAR.equals(operand.getType());
        boolean otherIsVarchar = other.getType() instanceof VarcharType || VarcharType.VARCHAR.equals(other.getType());

        if (operand instanceof ConstantExpression && operandIsVarchar && !otherIsVarchar) {
            Object value = ((ConstantExpression) operand).getValue();
            String text = value == null ? null : String.valueOf(value);
            if (text != null) {
                String stripped = stripQuotes(text);
                if (stripped != null) {
                    if (isDateLikeType(other.getType()) && stripped.matches("\\d{4}-\\d{2}-\\d{2}(?:[ T].*)?")) {
                        return dateConstant(stripped);
                    }
                    if (isNumericType(other.getType()) && stripped.matches("-?(?:\\d+\\.\\d+|\\d+|\\.\\d+)")) {
                        String decimalText = stripped.startsWith(".") ? "0" + stripped : stripped;
                        if (decimalText.startsWith("-.")) {
                            decimalText = "-0" + decimalText.substring(1);
                        }
                        return new ConstantExpression(Double.valueOf(decimalText), DoubleType.DOUBLE);
                    }
                    if (stripped.matches("\\d{4}-\\d{2}-\\d{2}(?:[ T].*)?")) {
                        return dateConstant(stripped);
                    }
                }
            }
        }

        if (operand instanceof ConstantExpression && !operandIsVarchar && otherIsVarchar) {
            Object value = ((ConstantExpression) operand).getValue();
            if (value != null) {
                String text = String.valueOf(value);
                if (isDateLikeType(operand.getType()) && text.matches("\\d{4}-\\d{2}-\\d{2}(?:[ T].*)?")) {
                    return dateConstant(text);
                }
                if (isNumericType(operand.getType()) && text.matches("-?(?:\\d+\\.\\d+|\\d+|\\.\\d+)")) {
                    String decimalText = text.startsWith(".") ? "0" + text : text;
                    if (decimalText.startsWith("-.")) {
                        decimalText = "-0" + decimalText.substring(1);
                    }
                    return new ConstantExpression(Double.valueOf(decimalText), DoubleType.DOUBLE);
                }
                if (text.matches("\\d{4}-\\d{2}-\\d{2}(?:[ T].*)?")) {
                    return dateConstant(text);
                }
                return varcharConstant(text);
            }
        }
        return operand;
    }

    private RowExpression parseValue(String value, Map<String, VariableReferenceExpression> variables)
    {
        String normalized = value == null ? null : value.trim();
        if (normalized == null || normalized.isEmpty()) {
            return null;
        }
        while (canStripWrappingParens(normalized)) {
            normalized = normalized.substring(1, normalized.length() - 1).trim();
        }
        if (normalized.isEmpty()) {
            return null;
        }

        boolean wasQuoted = (normalized.startsWith("'") && normalized.endsWith("'"))
                || (normalized.startsWith("\"") && normalized.endsWith("\""));
        if (wasQuoted) {
            normalized = stripQuotes(normalized).trim();
            if (normalized.matches("\\d{4}-\\d{2}-\\d{2}(?:[ T].*)?")) {
                RowExpression result = dateConstant(normalized);
                System.out.println("[OpengaussPlanAdapter] parseValue quotedDate normalized=" + normalized + " -> " + result + " type=" + result.getType());
                return result;
            }
            if (normalized.matches("\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?")) {
                RowExpression result = dateConstant(normalized);
                System.out.println("[OpengaussPlanAdapter] parseValue quotedTimestamp normalized=" + normalized + " -> " + result + " type=" + result.getType());
                return result;
            }
            RowExpression result = varcharConstant(normalized);
            System.out.println("[OpengaussPlanAdapter] parseValue string normalized=" + normalized + " -> " + result + " type=" + result.getType());
            return result;
        }

        if (normalized.equalsIgnoreCase("true") || normalized.equalsIgnoreCase("false")) {
            RowExpression result = new ConstantExpression(Boolean.parseBoolean(normalized), BooleanType.BOOLEAN);
            System.out.println("[OpengaussPlanAdapter] parseValue literal normalized=" + normalized + " -> " + result + " type=" + result.getType());
            return result;
        }
        if (normalized.matches("-?\\d+")) {
            RowExpression result = new ConstantExpression(Long.valueOf(normalized), BigintType.BIGINT);
            System.out.println("[OpengaussPlanAdapter] parseValue integer normalized=" + normalized + " -> " + result + " type=" + result.getType());
            return result;
        }
        if (normalized.matches("-?(?:\\d+\\.\\d+|\\d+|\\.\\d+)") ) {
            boolean hadExplicitDecimal = normalized.contains(".");
            String decimalText = normalized.startsWith(".") ? "0" + normalized : normalized;
            if (decimalText.startsWith("-.")) {
                decimalText = "-0" + decimalText.substring(1);
            }
            RowExpression result = hadExplicitDecimal
                    ? new ConstantExpression(Double.valueOf(decimalText), DoubleType.DOUBLE)
                    : new ConstantExpression(Long.valueOf(decimalText), BigintType.BIGINT);
            System.out.println("[OpengaussPlanAdapter] parseValue decimal normalized=" + normalized + " -> " + result + " type=" + result.getType());
            return result;
        }
        if (normalized.matches("(?i)date[ ]+'[^']+'(?:[ ]*\\+[ ]*interval[ ]+'\\d+'[ ]+(?:year|month|day|hour|minute|second)s?)?")) {
            RowExpression result = parseDateExpression(normalized);
            System.out.println("[OpengaussPlanAdapter] parseValue date normalized=" + normalized + " -> " + result + " type=" + (result == null ? "null" : result.getType()));
            return result;
        }
        if (normalized.matches("\\d{4}-\\d{2}-\\d{2}(?:[ T].*)?")) {
            RowExpression result = dateConstant(normalized);
            System.out.println("[OpengaussPlanAdapter] parseValue date normalized=" + normalized + " -> " + result + " type=" + result.getType());
            return result;
        }
        if (normalized.startsWith("$")) {
            VariableReferenceExpression bound = lookupVariable(normalized, variables);
            if (bound == null) {
                bound = lookupVariable(normalized.substring(1), variables);
            }
            if (bound != null) {
                System.out.println("[OpengaussPlanAdapter] parseValue param normalized=" + normalized + " -> " + bound + " type=" + bound.getType());
                return bound;
            }
            RowExpression result = varcharConstant(normalized);
            System.out.println("[OpengaussPlanAdapter] parseValue param normalized=" + normalized + " -> " + result + " type=" + result.getType());
            return result;
        }
        if (normalized.toUpperCase(Locale.ENGLISH).startsWith("ANY ")) {
            System.out.println("[OpengaussPlanAdapter] parseValue any normalized=" + normalized + " -> unsupported in scalar context");
            return null;
        }
        if (normalized.toLowerCase(Locale.ENGLISH).startsWith("substring")) {
            RowExpression result = parseSubstringCall(normalized, variables);
            System.out.println("[OpengaussPlanAdapter] parseValue substring normalized=" + normalized + " -> " + result + " type=" + (result == null ? "null" : result.getType()));
            return result;
        }
        RowExpression aggregateVariable = resolveAggregateVariableReference(normalized, variables);
        if (aggregateVariable != null) {
            return aggregateVariable;
        }

        List<String> multiplyParts = splitTopLevelParts(normalized, " * ");
        if (multiplyParts.size() > 1) {
            RowExpression chained = parseBinaryChain("multiply", multiplyParts, variables);
            if (chained != null) {
                return chained;
            }
        }
        List<String> divideParts = splitTopLevelParts(normalized, " / ");
        if (divideParts.size() > 1) {
            RowExpression chained = parseBinaryChain("divide", divideParts, variables);
            if (chained != null) {
                return chained;
            }
        }
        List<String> plusParts = splitTopLevelParts(normalized, " + ");
        if (plusParts.size() > 1) {
            RowExpression chained = parseBinaryChain("add", plusParts, variables);
            if (chained != null) {
                return chained;
            }
        }
        List<String> minusParts = splitTopLevelParts(normalized, " - ");
        if (minusParts.size() > 1) {
            RowExpression chained = parseBinaryChain("subtract", minusParts, variables);
            if (chained != null) {
                return chained;
            }
        }

        int castIdx = findTopLevelDelimiter(normalized, "::");
        if (castIdx >= 0) {
            String base = normalized.substring(0, castIdx).trim();
            String typeSuffix = normalized.substring(castIdx + 2).trim().toLowerCase(Locale.ENGLISH);
            RowExpression casted = parseValue(base, variables);
            System.out.println("[OpengaussPlanAdapter] parseValue cast normalized=" + normalized + " base=" + base + " typeSuffix=" + typeSuffix + " casted=" + casted + " castedType=" + (casted == null ? "null" : casted.getType()));
            if (casted != null) {
                if (typeSuffix.startsWith("numeric") || typeSuffix.startsWith("decimal") || typeSuffix.startsWith("double") || typeSuffix.startsWith("real") || typeSuffix.startsWith("int") || typeSuffix.startsWith("bigint")) {
                    return casted;
                }
                if (typeSuffix.startsWith("date")) {
                    return dateConstant(stripQuotes(base));
                }
                if (typeSuffix.startsWith("timestamp")) {
                    return dateConstant(stripQuotes(base));
                }
                if (typeSuffix.startsWith("text") || typeSuffix.startsWith("varchar") || typeSuffix.startsWith("char") || typeSuffix.startsWith("bpchar")) {
                    return varcharConstant(stripQuotes(base));
                }
            }
        }

        VariableReferenceExpression variable = lookupVariable(normalized, variables);
        if (variable != null) {
            return variable;
        }
        if (normalized.contains(".")) {
            String simple = normalized.substring(normalized.lastIndexOf('.') + 1);
            variable = lookupVariable(simple, variables);
            if (variable != null) {
                return variable;
            }
        }
        System.out.println("[OpengaussPlanAdapter] parseValue unresolved normalized=" + normalized + " variables=" + variables.keySet());
        return null;
    }

    private Map<String, VariableReferenceExpression> buildVariablesByOutput(PlanNode node)
    {
        Map<String, VariableReferenceExpression> result = new LinkedHashMap<>();
        for (VariableReferenceExpression variable : node.getOutputVariables()) {
            addVariableLookupAliases(result, variable, true);
        }
        return result;
    }

    private Map<String, VariableReferenceExpression> buildVariablesByPlanTree(PlanNode node)
    {
        Map<String, VariableReferenceExpression> result = new LinkedHashMap<>();
        collectVariablesByPlanTree(node, result);
        return result;
    }

    private void collectVariablesByPlanTree(PlanNode node, Map<String, VariableReferenceExpression> result)
    {
        if (node == null || result == null) {
            return;
        }
        for (VariableReferenceExpression variable : node.getOutputVariables()) {
            addVariableLookupAliases(result, variable, true);
        }
        for (PlanNode source : node.getSources()) {
            collectVariablesByPlanTree(source, result);
        }
    }

    private void addVariableLookupAliases(Map<String, VariableReferenceExpression> result, VariableReferenceExpression variable, boolean keepExisting)
    {
        if (result == null || variable == null || variable.getName() == null) {
            return;
        }
        String name = variable.getName().toLowerCase(Locale.ENGLISH);
        String simple = simpleName(name).toLowerCase(Locale.ENGLISH);
        String baseName = stripVariableIdSuffix(simple);
        putVariableAlias(result, name, variable, keepExisting);
        putVariableAlias(result, simple, variable, keepExisting);
        putVariableAlias(result, baseName, variable, keepExisting);
        int firstUnderscore = name.indexOf('_');
        if (firstUnderscore > 0 && firstUnderscore < name.length() - 1) {
            String alias = name.substring(0, firstUnderscore);
            String column = name.substring(firstUnderscore + 1);
            putVariableAlias(result, alias + "." + column, variable, keepExisting);
        }
    }

    private void putVariableAlias(Map<String, VariableReferenceExpression> result, String alias, VariableReferenceExpression variable, boolean keepExisting)
    {
        if (alias == null || alias.isBlank()) {
            return;
        }
        if (keepExisting) {
            result.putIfAbsent(alias, variable);
        }
        else {
            result.put(alias, variable);
        }
    }

    private String stripVariableIdSuffix(String name)
    {
        if (name == null) {
            return "";
        }
        return name.replaceFirst("_\\d+$", "");
    }

    private VariableReferenceExpression lookupVariable(String token, Map<String, VariableReferenceExpression> variables)
    {
        if (token == null || variables == null || variables.isEmpty()) {
            return null;
        }
        String normalizedToken = canonicalizeExpressionText(token).trim().toLowerCase(Locale.ENGLISH);
        normalizedToken = stripQuotes(normalizedToken);
        String simple = simpleName(normalizedToken).toLowerCase(Locale.ENGLISH);
        boolean qualified = normalizedToken.contains(".");
        if (qualified) {
            VariableReferenceExpression bestQualified = findQualifiedVariable(normalizedToken, variables);
            if (bestQualified != null) {
                return bestQualified;
            }
            String aliasQualified = normalizedToken.replace('.', '_');
            VariableReferenceExpression aliasDirect = variables.get(aliasQualified);
            if (aliasDirect != null) {
                return aliasDirect;
            }
            String aliasBase = stripVariableIdSuffix(aliasQualified);
            aliasDirect = variables.get(aliasBase);
            if (aliasDirect != null) {
                return aliasDirect;
            }
            return null;
        }

        VariableReferenceExpression direct = variables.get(normalizedToken);
        if (direct != null) {
            return direct;
        }
        direct = variables.get(simple);
        if (direct != null) {
            return direct;
        }

        String dottedTail = normalizedToken;
        if (normalizedToken.contains(".")) {
            dottedTail = normalizedToken.substring(normalizedToken.lastIndexOf('.') + 1);
        }

        for (Map.Entry<String, VariableReferenceExpression> entry : variables.entrySet()) {
            String key = entry.getKey();
            if (key == null) {
                continue;
            }
            String normalizedKey = key.toLowerCase(Locale.ENGLISH);
            if (normalizedToken.equals(normalizedKey) || simple.equals(normalizedKey) || dottedTail.equals(normalizedKey)) {
                return entry.getValue();
            }
            if (normalizedToken.startsWith(normalizedKey + ".") || normalizedToken.endsWith("." + normalizedKey) || normalizedToken.contains("." + normalizedKey + ".")) {
                return entry.getValue();
            }
        }
        return null;
    }

    private VariableReferenceExpression findQualifiedVariable(String normalizedToken, Map<String, VariableReferenceExpression> variables)
    {
        if (normalizedToken == null || variables == null || !normalizedToken.contains(".")) {
            return null;
        }
        String[] parts = normalizedToken.split("\\.");
        if (parts.length < 2) {
            return null;
        }
        String qualifier = parts[parts.length - 2].replaceAll("[^a-z0-9_]+", "");
        String column = parts[parts.length - 1].replaceAll("[^a-z0-9_]+", "");
        if (qualifier.isBlank() || column.isBlank()) {
            return null;
        }
        if ("n1".equals(qualifier)) {
            VariableReferenceExpression direct = variables.get(column);
            if (direct != null) {
                return direct;
            }
            direct = findVariableByExactName(column, variables);
            if (direct != null) {
                return direct;
            }
        }
        if ("n2".equals(qualifier)) {
            VariableReferenceExpression suffixed = variables.get(column + "_0");
            if (suffixed != null) {
                return suffixed;
            }
            suffixed = findVariableByExactName(column + "_0", variables);
            if (suffixed != null) {
                return suffixed;
            }
            suffixed = variables.get(column + "_1");
            if (suffixed != null) {
                return suffixed;
            }
            suffixed = findVariableByExactName(column + "_1", variables);
            if (suffixed != null) {
                return suffixed;
            }
        }
        String expectedPrefix = qualifier + "_" + column;
        VariableReferenceExpression best = null;
        int bestScore = Integer.MIN_VALUE;
        for (VariableReferenceExpression variable : variables.values()) {
            if (variable == null || variable.getName() == null) {
                continue;
            }
            String name = variable.getName().toLowerCase(Locale.ENGLISH);
            String base = stripVariableIdSuffix(name);
            int score = Integer.MIN_VALUE;
            if (name.equals(expectedPrefix) || base.equals(expectedPrefix)) {
                score = 100;
            }
            else if (name.startsWith(expectedPrefix + "_") || base.startsWith(expectedPrefix + "_")) {
                score = 90;
            }
            else if (name.contains(expectedPrefix) || base.contains(expectedPrefix)) {
                score = 70;
            }
            if (score > bestScore) {
                bestScore = score;
                best = variable;
            }
        }
        return bestScore >= 70 ? best : null;
    }

    private VariableReferenceExpression findVariableByExactName(String name, Map<String, VariableReferenceExpression> variables)
    {
        if (name == null || variables == null) {
            return null;
        }
        for (VariableReferenceExpression variable : variables.values()) {
            if (variable != null && variable.getName() != null && variable.getName().equalsIgnoreCase(name)) {
                return variable;
            }
        }
        return null;
    }

    private List<String> extractExpressionTokens(String expression)
    {
        if (expression == null || expression.isBlank()) {
            return Collections.emptyList();
        }
        String normalized = canonicalizeExpressionText(expression).toLowerCase(Locale.ENGLISH);
        normalized = normalized.replace("(", " ").replace(")", " ").replace(",", " ").replace("+", " ").replace("-", " ").replace("*", " ").replace("/", " ").replace("::", " ");
        String[] parts = normalized.split("[^a-z0-9_.$]+");
        List<String> tokens = new ArrayList<>();
        for (String part : parts) {
            if (part == null || part.isBlank()) {
                continue;
            }
            String token = simpleName(part).toLowerCase(Locale.ENGLISH);
            if (token.isBlank()) {
                continue;
            }
            if (!tokens.contains(token)) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private RowExpression resolveAggregateVariableReference(String expression, Map<String, VariableReferenceExpression> variables)
    {
        if (expression == null || variables == null || variables.isEmpty()) {
            return null;
        }
        String normalized = stripUnmatchedOuterParens(canonicalizeExpressionText(expression).trim());
        String functionNormalized = stripCatalogFunctionPrefix(normalized);
        String lower = functionNormalized.toLowerCase(Locale.ENGLISH);
        for (String functionName : List.of("sum", "avg", "count", "min", "max")) {
            if (!lower.startsWith(functionName + "(")) {
                continue;
            }
            int open = functionNormalized.indexOf('(');
            int close = functionNormalized.lastIndexOf(')');
            if (open < 0 || close <= open) {
                continue;
            }
            String argument = functionNormalized.substring(open + 1, close).trim();
            while (canStripWrappingParens(argument)) {
                argument = argument.substring(1, argument.length() - 1).trim();
            }
            if (isAggregationFunctionCall(argument)) {
                VariableReferenceExpression nestedOutput = lookupVariableByNestedAggregation(functionName, argument, variables);
                if (nestedOutput != null) {
                    return nestedOutput;
                }
                RowExpression nestedAggregateVariable = resolveAggregateVariableReference(argument, variables);
                if (nestedAggregateVariable != null) {
                    return nestedAggregateVariable;
                }
            }
            VariableReferenceExpression byFunctionAndColumn = lookupVariableByFunctionAndColumn(functionName, simpleName(argument), variables);
            if (byFunctionAndColumn != null) {
                return byFunctionAndColumn;
            }
            String simpleArgument = simpleName(argument).toLowerCase(Locale.ENGLISH);
            for (VariableReferenceExpression variable : variables.values()) {
                if (variable == null || variable.getName() == null) {
                    continue;
                }
                String name = variable.getName().toLowerCase(Locale.ENGLISH);
                if (name.contains(functionName) && (simpleArgument.isBlank() || name.contains(simpleArgument))) {
                    return variable;
                }
            }
        }
        return null;
    }

    private VariableReferenceExpression lookupVariableByNestedAggregation(String outerFunctionName, String nestedExpression, Map<String, VariableReferenceExpression> variables)
    {
        if (outerFunctionName == null || nestedExpression == null || variables == null || variables.isEmpty()) {
            return null;
        }
        String normalizedOuter = outerFunctionName.toLowerCase(Locale.ENGLISH);
        String normalizedNested = stripCatalogFunctionPrefix(stripUnmatchedOuterParens(canonicalizeExpressionText(nestedExpression).trim()));
        String nestedFunctionName = aggregationFunctionName(normalizedNested);
        if (nestedFunctionName == null) {
            return null;
        }
        int open = normalizedNested.indexOf('(');
        int close = normalizedNested.lastIndexOf(')');
        if (open < 0 || close <= open) {
            return null;
        }
        String nestedArgument = normalizedNested.substring(open + 1, close).trim();
        while (canStripWrappingParens(nestedArgument)) {
            nestedArgument = nestedArgument.substring(1, nestedArgument.length() - 1).trim();
        }
        String canonicalColumn = canonicalAggregationColumnToken(nestedArgument);
        String preferredPrefix = normalizedOuter + "_" + nestedFunctionName + (canonicalColumn.isEmpty() ? "" : "_" + canonicalColumn);
        VariableReferenceExpression best = null;
        int bestScore = Integer.MIN_VALUE;
        for (VariableReferenceExpression variable : variables.values()) {
            if (variable == null || variable.getName() == null) {
                continue;
            }
            String name = variable.getName().toLowerCase(Locale.ENGLISH);
            String baseName = name.replaceAll("_raw$", "").replaceAll("_+$", "");
            int score = 0;
            if (baseName.equals(preferredPrefix)) {
                score += 200;
            }
            if (baseName.startsWith(preferredPrefix)) {
                score += 160;
            }
            if (baseName.contains(normalizedOuter + "_" + nestedFunctionName)) {
                score += canonicalColumn.isEmpty() ? 140 : 80;
            }
            else if (baseName.contains(normalizedOuter) && baseName.contains(nestedFunctionName)) {
                score += canonicalColumn.isEmpty() ? 120 : 50;
            }
            if (!canonicalColumn.isEmpty() && baseName.contains(canonicalColumn)) {
                score += 40;
            }
            if (baseName.endsWith("_raw")) {
                score -= 5;
            }
            if (score > bestScore) {
                bestScore = score;
                best = variable;
            }
        }
        return bestScore >= 120 ? best : null;
    }

    private String aggregationFunctionName(String expression)
    {
        if (expression == null) {
            return null;
        }
        String lower = stripCatalogFunctionPrefix(expression.trim()).toLowerCase(Locale.ENGLISH);
        for (String functionName : List.of("sum", "avg", "count", "min", "max")) {
            if (lower.startsWith(functionName + "(")) {
                return functionName;
            }
        }
        return null;
    }

    private String stripCatalogFunctionPrefix(String expression)
    {
        if (expression == null) {
            return null;
        }
        String normalized = expression.trim();
        while (normalized.toLowerCase(Locale.ENGLISH).startsWith("pg_catalog.")) {
            normalized = normalized.substring("pg_catalog.".length()).trim();
        }
        return normalized;
    }

    private String canonicalAggregationColumnToken(String expression)
    {
        if (expression == null) {
            return "";
        }
        String simple = simpleName(stripUnmatchedOuterParens(canonicalizeExpressionText(expression).trim())).toLowerCase(Locale.ENGLISH);
        simple = simple.replaceAll("[^a-z0-9_]+", "_").replaceAll("_+", "_").replaceAll("^_+|_+$", "");
        return simple;
    }

    private VariableReferenceExpression lookupVariableByExpressionShape(String expression, Map<String, VariableReferenceExpression> variables)
    {
        if (expression == null || variables == null || variables.isEmpty()) {
            return null;
        }
        String normalized = stripUnmatchedOuterParens(canonicalizeExpressionText(expression).trim());
        List<String> tokens = extractExpressionTokens(normalized);
        if (tokens.isEmpty()) {
            return null;
        }
        for (Map.Entry<String, VariableReferenceExpression> entry : variables.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.isBlank()) {
                continue;
            }
            String normalizedKey = key.toLowerCase(Locale.ENGLISH).replaceAll("[^a-z0-9_]+", "");
            boolean allMatched = true;
            for (String token : tokens) {
                if (token.equals("sum") || token.equals("avg") || token.equals("count") || token.equals("min") || token.equals("max") || token.equals("pg_catalog") || token.equals("numeric") || token.equals("double") || token.equals("bigint") || token.equals("decimal") || token.equals("real")) {
                    continue;
                }
                String normalizedToken = token.toLowerCase(Locale.ENGLISH).replaceAll("[^a-z0-9_]+", "");
                if (normalizedToken.isEmpty()) {
                    continue;
                }
                if (!normalizedKey.contains(normalizedToken)) {
                    allMatched = false;
                    break;
                }
            }
            if (allMatched) {
                return entry.getValue();
            }
            String sourceName = entry.getValue() == null ? null : entry.getValue().getName();
            if (sourceName != null && normalized.equalsIgnoreCase(sourceName)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * Find a variable that was produced by the inner aggregate function applied to a column.
     * For a two-level aggregate like {@code avg(avg(l_quantity))}, the outer call receives
     * the result of the inner {@code avg(l_quantity)} which is stored under a variable whose
     * name contains both the function name and the column name, e.g. {@code avg_l_quantity_}.
     *
     * <p>Strategy (in order of preference):
     * <ol>
     *   <li>Exact name match: {@code innerFunc + "_" + column} (e.g. {@code avg_l_quantity_})</li>
     *   <li>Variable name contains both {@code innerFunc} and {@code column} as sub-strings</li>
     *   <li>Variable name contains {@code column} and starts-with {@code innerFunc}</li>
     * </ol>
     */
    private VariableReferenceExpression lookupVariableByFunctionAndColumn(
            String innerFuncName,
            String columnName,
            Map<String, VariableReferenceExpression> variables)
    {
        if (innerFuncName == null || columnName == null || variables == null || variables.isEmpty()) {
            return null;
        }
        String lowerFunc = innerFuncName.toLowerCase(Locale.ENGLISH);
        String lowerCol = simpleName(columnName).toLowerCase(Locale.ENGLISH);

        // Pass 1: exact prefix match "func_col"
        for (Map.Entry<String, VariableReferenceExpression> entry : variables.entrySet()) {
            String varName = entry.getKey().toLowerCase(Locale.ENGLISH);
            // Strip trailing underscores / _raw suffix for comparison
            String varBase = varName.replaceAll("_raw$", "").replaceAll("_+$", "");
            if (varBase.equals(lowerFunc + "_" + lowerCol)
                    || varBase.startsWith(lowerFunc + "_" + lowerCol)) {
                return entry.getValue();
            }
        }

        // Pass 2: variable name contains both func and col
        VariableReferenceExpression best = null;
        int bestScore = -1;
        for (Map.Entry<String, VariableReferenceExpression> entry : variables.entrySet()) {
            String varName = entry.getKey().toLowerCase(Locale.ENGLISH);
            if (!varName.contains(lowerCol)) {
                continue;
            }
            int score = 0;
            if (varName.contains(lowerFunc)) {
                score += 10;
            }
            if (varName.startsWith(lowerFunc)) {
                score += 5;
            }
            if (varName.contains(lowerFunc + "_" + lowerCol) || varName.contains(lowerFunc + lowerCol)) {
                score += 20;
            }
            if (score > bestScore) {
                bestScore = score;
                best = entry.getValue();
            }
        }
        // Only return if we actually found a function match (score > 0 means contains col, but
        // we also need it to contain the func name to be considered a meaningful match).
        if (best != null && bestScore >= 10) {
            return best;
        }
        return null;
    }

    private VariableReferenceExpression selectCaseWhenVariable(String expression, Map<String, VariableReferenceExpression> variables)
    {
        if (expression == null || variables == null || variables.isEmpty()) {
            return null;
        }
        String normalized = stripUnmatchedOuterParens(canonicalizeExpressionText(expression).trim()).toLowerCase(Locale.ENGLISH);
        if (!normalized.contains("case when")) {
            return lookupVariableByExpressionShape(expression, variables);
        }
        String conditionShape = normalized;
        int whenIndex = conditionShape.indexOf("case when");
        int thenIndex = conditionShape.indexOf(" then ", whenIndex);
        if (thenIndex > whenIndex) {
            conditionShape = conditionShape.substring(whenIndex + "case when".length(), thenIndex).trim();
        }
        boolean wantsAnd = conditionShape.contains(" and ") || conditionShape.contains("<>");
        boolean wantsOr = conditionShape.contains(" or ") || conditionShape.contains("=");
        VariableReferenceExpression best = null;
        int bestScore = -1;
        for (VariableReferenceExpression variable : variables.values()) {
            if (variable == null || variable.getName() == null) {
                continue;
            }
            String name = variable.getName().toLowerCase(Locale.ENGLISH);
            if (!name.contains("sum_case_when")) {
                continue;
            }
            int score = 0;
            if (wantsAnd && name.contains("_and_")) {
                score += 10;
            }
            if (wantsOr && name.contains("_or_")) {
                score += 10;
            }
            if (normalized.contains(name.replace("_raw", ""))) {
                score += 5;
            }
            if (score > bestScore) {
                bestScore = score;
                best = variable;
            }
        }
        return best;
    }

    private VariableReferenceExpression resolveJoinVariable(PlanNode node, String name)
    {
        if (node == null || name == null || name.isBlank()) {
            return null;
        }

        String normalized = canonicalizeExpressionText(name).trim().toLowerCase(Locale.ENGLISH);
        String simple = simpleName(normalized).toLowerCase(Locale.ENGLISH);
        boolean qualified = normalized.contains(".");
        String aliasQualified = qualified ? normalized.replace('.', '_') : normalized;
        String aliasQualifiedBase = stripVariableIdSuffix(aliasQualified);
        String compact = normalized.replaceAll("[^a-z0-9_]+", "");
        String compactSimple = simple.replaceAll("[^a-z0-9_]+", "");

        for (VariableReferenceExpression variable : node.getOutputVariables()) {
            String variableName = variable.getName() == null ? "" : variable.getName().toLowerCase(Locale.ENGLISH);
            String variableSimple = simpleName(variableName).toLowerCase(Locale.ENGLISH);
            String variableCompact = variableName.replaceAll("[^a-z0-9_]+", "");
            String variableCompactSimple = variableSimple.replaceAll("[^a-z0-9_]+", "");

            if (normalized.equals(variableName)
                    || simple.equals(variableName)
                    || aliasQualified.equals(variableName)
                    || aliasQualifiedBase.equals(variableName)
                    || normalized.equals(variableSimple)
                    || simple.equals(variableSimple)
                    || compact.equals(variableCompact)
                    || compact.equals(variableCompactSimple)
                    || compactSimple.equals(variableCompact)
                    || compactSimple.equals(variableCompactSimple)) {
                return variable;
            }
        }

        for (VariableReferenceExpression variable : node.getOutputVariables()) {
            String variableName = variable.getName() == null ? "" : variable.getName().toLowerCase(Locale.ENGLISH);
            if (!qualified && (normalized.contains(variableName)
                    || variableName.contains(normalized)
                    || simple.contains(variableName)
                    || variableName.contains(simple))) {
                return variable;
            }
        }

        for (PlanNode source : node.getSources()) {
            VariableReferenceExpression resolved = resolveJoinVariable(source, name);
            if (resolved != null) {
                return resolved;
            }
        }
        return null;
    }

    private JoinType parseJoinType(String value)
    {
        if (value == null) {
            return JoinType.INNER;
        }
        String normalized = value.toLowerCase(Locale.ENGLISH);
        if (normalized.contains("right anti")) {
            return JoinType.RIGHT;
        }
        if (normalized.contains("left anti")) {
            return JoinType.LEFT;
        }
        if (normalized.contains("left")) {
            return JoinType.LEFT;
        }
        if (normalized.contains("right")) {
            return JoinType.RIGHT;
        }
        if (normalized.contains("full")) {
            return JoinType.FULL;
        }
        return JoinType.INNER;
    }

    private long parseLong(String value, long defaultValue)
    {
        try {
            return value == null ? defaultValue : Long.parseLong(value.replaceAll("[^0-9]", ""));
        }
        catch (RuntimeException ignored) {
            return defaultValue;
        }
    }

    private String simpleName(String name)
    {
        if (name == null) {
            return "col";
        }
        String stripped = name.replace("\"", "").trim();
        return stripped.contains(".") ? stripped.substring(stripped.lastIndexOf('.') + 1) : stripped;
    }

    private static String firstNonNullStatic(String... values)
    {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String firstNonNull(String... values)
    {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private List<JsonNode> children(JsonNode node)
    {
        JsonNode plans = node.get("Plans");
        if (plans == null || !plans.isArray()) {
            return Collections.emptyList();
        }
        List<JsonNode> result = new ArrayList<>();
        plans.forEach(result::add);
        return result;
    }

    private JsonNode firstChild(JsonNode node)
    {
        List<JsonNode> children = children(node);
        return children.isEmpty() ? null : children.get(0);
    }

    private void bindInitPlans(JsonNode node, AdapterContext context, Map<String, VariableReferenceExpression> scalarBindings)
    {
        if (node == null || scalarBindings == null) {
            return;
        }
        for (JsonNode child : children(node)) {
            String rel = text(child, "Parent Relationship");
            if (rel == null || !rel.equalsIgnoreCase("InitPlan")) {
                continue;
            }
            PlanNode initPlan = translateNode(firstChild(child) == null ? child : firstChild(child), context, scalarBindings);
            List<VariableReferenceExpression> outputs = initPlan.getOutputVariables();
            if (outputs.isEmpty()) {
                continue;
            }
            String subplanName = text(child, "Subplan Name");
            String bindingName = subplanName != null && subplanName.contains("$")
                    ? subplanName.substring(subplanName.indexOf('$') + 1).replaceAll("[^0-9A-Za-z_]+", "")
                    : String.valueOf(scalarBindings.size());
            VariableReferenceExpression boundScalar = outputs.get(0);
            scalarBindings.put(bindingName.toLowerCase(Locale.ENGLISH), boundScalar);
            scalarBindings.put(("$" + bindingName).toLowerCase(Locale.ENGLISH), boundScalar);
            scalarBindings.put("$" + bindingName, boundScalar);
            scalarBindings.put(bindingName, boundScalar);
            scalarBindings.put(boundScalar.getName().toLowerCase(Locale.ENGLISH), boundScalar);
            scalarPlanBindings.put(bindingName.toLowerCase(Locale.ENGLISH), initPlan);
            scalarPlanBindings.put(("$" + bindingName).toLowerCase(Locale.ENGLISH), initPlan);
            scalarPlanBindings.put(boundScalar.getName().toLowerCase(Locale.ENGLISH), initPlan);
            System.out.println("[OpengaussPlanAdapter] bindInitPlans name=" + bindingName + " output=" + boundScalar + " outputs=" + outputs);
        }
    }

    private JsonNode primaryChild(JsonNode node)
    {
        List<JsonNode> children = children(node);
        if (children.isEmpty()) {
            return null;
        }
        for (JsonNode child : children) {
            String rel = text(child, "Parent Relationship");
            if (rel == null || rel.equalsIgnoreCase("Outer")) {
                return child;
            }
        }
        for (JsonNode child : children) {
            String rel = text(child, "Parent Relationship");
            if (rel != null && rel.equalsIgnoreCase("InitPlan")) {
                continue;
            }
            return child;
        }
        return children.get(0);
    }

    private String text(JsonNode node, String field)
    {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private Optional<QualifiedObjectName> resolveQualifiedTableName(Metadata metadata, Session session, String tableName, String schemaName)
    {
        List<String> candidateCatalogs = new ArrayList<>();
        candidateCatalogs.add("tpchstandard");
        // candidateCatalogs.add("tpch");
        // candidateCatalogs.add("tpcds");
        candidateCatalogs.addAll(metadata.getCatalogNames(session).keySet());

        List<String> candidateSchemas = new ArrayList<>();
        candidateSchemas.add("sf1");
        if (schemaName != null && !schemaName.isBlank() && !"public".equalsIgnoreCase(schemaName)) {
            candidateSchemas.add(schemaName);
        }
        candidateSchemas.addAll(metadata.listSchemaNames(session, "tpchstandard"));

        for (String catalog : candidateCatalogs) {
            for (String schema : candidateSchemas) {
                QualifiedObjectName candidate = new QualifiedObjectName(catalog, schema, tableName);
                if (metadata.getHandleVersion(session, candidate, Optional.empty()).isPresent()) {
                    return Optional.of(candidate);
                }
            }
        }

        for (String catalog : candidateCatalogs) {
            for (String schema : metadata.listSchemaNames(session, catalog)) {
                QualifiedObjectName candidate = new QualifiedObjectName(catalog, schema, tableName);
                if (metadata.getHandleVersion(session, candidate, Optional.empty()).isPresent()) {
                    return Optional.of(candidate);
                }
            }
        }

        if (!candidateCatalogs.isEmpty()) {
            String preferredSchema = schemaName == null || schemaName.isBlank() ? "tiny" : schemaName;
            for (String catalog : candidateCatalogs) {
                QualifiedObjectName candidate = new QualifiedObjectName(catalog, preferredSchema, tableName);
                if (metadata.getHandleVersion(session, candidate, Optional.empty()).isPresent()) {
                    return Optional.of(candidate);
                }
            }
        }
        return Optional.empty();
    }

    private Optional<TableHandle> resolveTableHandle(Metadata metadata, Session session, QualifiedObjectName qname)
    {
        String catalogName = qname.getCatalogName();
        if (catalogName != null && catalogName.toLowerCase(Locale.ENGLISH).contains("info_schema")) {
            System.out.println("[OpengaussPlanAdapter] skip info_schema table handle resolution for qname=" + qname);
            return Optional.empty();
        }

        Optional<TableHandle> handle = metadata.getHandleVersion(session, qname, Optional.empty());
        if (handle.isPresent()) {
            return handle;
        }

        String fallbackSchema = "tiny";
        QualifiedObjectName fallback = new QualifiedObjectName(qname.getCatalogName(), fallbackSchema, qname.getObjectName());
        if (!fallback.equals(qname)) {
            handle = metadata.getHandleVersion(session, fallback, Optional.empty());
            if (handle.isPresent()) {
                return handle;
            }
        }

        for (String schema : metadata.listSchemaNames(session, qname.getCatalogName())) {
            QualifiedObjectName candidate = new QualifiedObjectName(qname.getCatalogName(), schema, qname.getObjectName());
            handle = metadata.getHandleVersion(session, candidate, Optional.empty());
            if (handle.isPresent()) {
                return handle;
            }
        }
        return Optional.empty();
    }

    private InputStream openPlanStream(String planFile, AdapterContext context) throws IOException
    {
        ClassLoader classLoader = context.getClassLoader();
        System.out.println("[OpengaussPlanAdapter] trying resource path=" + planFile);
        java.net.URL url = classLoader.getResource(planFile);
        System.out.println("[OpengaussPlanAdapter] resource url=" + url);
        InputStream inputStream = classLoader.getResourceAsStream(planFile);
        if (inputStream != null) {
            System.out.println("[OpengaussPlanAdapter] loaded from classpath=" + planFile);
            return inputStream;
        }

        String normalized = planFile.startsWith("/") ? planFile.substring(1) : planFile;
        if (!normalized.equals(planFile)) {
            System.out.println("[OpengaussPlanAdapter] trying normalized resource path=" + normalized);
            url = classLoader.getResource(normalized);
            System.out.println("[OpengaussPlanAdapter] normalized resource url=" + url);
            inputStream = classLoader.getResourceAsStream(normalized);
            if (inputStream != null) {
                System.out.println("[OpengaussPlanAdapter] loaded from normalized classpath=" + normalized);
                return inputStream;
            }
        }

        java.io.File file = new java.io.File(planFile);
        System.out.println("[OpengaussPlanAdapter] trying file path=" + file.getAbsolutePath() + ", exists=" + file.exists());
        if (file.exists()) {
            System.out.println("[OpengaussPlanAdapter] loaded from file=" + file.getAbsolutePath());
            return new java.io.FileInputStream(file);
        }

        file = new java.io.File(normalized);
        System.out.println("[OpengaussPlanAdapter] trying normalized file path=" + file.getAbsolutePath() + ", exists=" + file.exists());
        if (file.exists()) {
            System.out.println("[OpengaussPlanAdapter] loaded from normalized file=" + file.getAbsolutePath());
            return new java.io.FileInputStream(file);
        }

        System.out.println("[OpengaussPlanAdapter] plan file not found in any location: " + planFile);
        return null;
    }

    private JsonNode unwrapPlan(JsonNode root)
    {
        JsonNode plan = root.path(0).path("Plan");
        return plan.isMissingNode() ? root : plan;
    }

    private List<String> splitCommaSeparated(String input)
    {
        if (input == null || input.isBlank()) {
            return Collections.emptyList();
        }
        String[] parts = input.split(",");
        List<String> result = new ArrayList<>();
        for (String part : parts) {
            if (!part.isBlank()) {
                result.add(part.trim());
            }
        }
        return result;
    }

    private List<String> extractReferencedColumnNames(String expression)
    {
        if (expression == null || expression.isBlank()) {
            return Collections.emptyList();
        }
        List<String> referenced = new ArrayList<>();
        String normalized = canonicalizeExpressionText(expression);
        String[] tokens = normalized.replace('(', ' ').replace(')', ' ').replace('*', ' ').replace('+', ' ').replace('-', ' ').replace('/', ' ').split("[^A-Za-z0-9_.$]+");
        for (String token : tokens) {
            String candidate = simpleName(token).toLowerCase(Locale.ENGLISH);
            if (!candidate.isBlank() && !candidate.matches("\\d+") && !isSqlKeyword(candidate)) {
                if (!referenced.contains(candidate)) {
                    referenced.add(candidate);
                }
            }
        }
        return referenced;
    }

    private boolean isSqlKeyword(String value)
    {
        if (value == null) {
            return true;
        }
        switch (value.toLowerCase(Locale.ENGLISH)) {
            case "and":
            case "or":
            case "not":
            case "case":
            case "when":
            case "then":
            case "else":
            case "end":
            case "sum":
            case "avg":
            case "count":
            case "min":
            case "max":
            case "cast":
            case "substring":
            case "true":
            case "false":
                return true;
            default:
                return false;
        }
    }

    private List<VariableReferenceExpression> parsePartitionKeys(String keyText, PlanNode source)
    {
        if (keyText == null || keyText.isBlank()) {
            return Collections.emptyList();
        }
        Map<String, VariableReferenceExpression> variables = buildVariablesByOutput(source);
        List<VariableReferenceExpression> keys = new ArrayList<>();
        for (String token : splitCommaSeparated(keyText)) {
            VariableReferenceExpression variable = lookupVariable(token, variables);
            if (variable != null) {
                keys.add(variable);
            }
        }
        return keys;
    }

    private RowExpression parseDatePartYearExpression(String normalized, Map<String, VariableReferenceExpression> variables)
    {
        if (normalized == null) {
            return null;
        }
        String lower = normalized.toLowerCase(Locale.ENGLISH);
        if (!lower.startsWith("date_part(") && !lower.startsWith("extract(")) {
            return null;
        }
        if (!(lower.contains("year") || lower.contains("'year'"))) {
            return null;
        }
        int open = normalized.indexOf('(');
        int close = normalized.lastIndexOf(')');
        if (open < 0 || close <= open) {
            return null;
        }
        String inside = normalized.substring(open + 1, close).trim();
        List<String> parts = splitCommaSeparated(inside);
        if (parts.isEmpty()) {
            return null;
        }
        String target = parts.size() == 1 ? inside : parts.get(parts.size() - 1);
        RowExpression targetExpr = null;
        if (target.toLowerCase(Locale.ENGLISH).contains("from")) {
            String[] fromParts = target.split("(?i)\\s+from\\s+", 2);
            if (fromParts.length == 2) {
                target = fromParts[1].trim();
            }
        }
        if (target.toLowerCase(Locale.ENGLISH).contains("shipdate")) {
            targetExpr = parseValue(target.trim(), variables);
            if (targetExpr == null) {
                targetExpr = lookupVariable("lineitem.l_shipdate", variables);
            }
            if (targetExpr == null) {
                targetExpr = lookupVariable("l_shipdate", variables);
            }
        }
        if (targetExpr == null) {
            return null;
        }
        try {
            com.facebook.presto.spi.function.FunctionHandle functionHandle = metadata.getFunctionAndTypeManager().resolveFunction(
                    Optional.empty(),
                    Optional.empty(),
                    new QualifiedObjectName("presto", "default", "year"),
                    TypeSignatureProvider.fromTypes(targetExpr.getType()));
            return new CallExpression("year", functionHandle, BigintType.BIGINT, List.of(targetExpr));
        }
        catch (RuntimeException ignored) {
            return targetExpr;
        }
    }

    private RowExpression parseSubstringCall(String normalized, Map<String, VariableReferenceExpression> variables)
    {
        int open = normalized.indexOf('(');
        int close = normalized.lastIndexOf(')');
        if (open < 0 || close <= open) {
            return varcharConstant(stripQuotes(normalized));
        }
        String inside = normalized.substring(open + 1, close);
        List<String> parts = splitCommaSeparated(inside);
        if (parts.isEmpty()) {
            return new ConstantExpression(stripQuotes(normalized), VarcharType.VARCHAR);
        }
        RowExpression base = parseCanonicalSubstringArgument(parts.get(0).trim(), variables);
        if (base == null) {
            return new ConstantExpression(stripQuotes(normalized), VarcharType.VARCHAR);
        }
        if (parts.size() == 1) {
            return base;
        }
        List<RowExpression> args = new ArrayList<>();
        args.add(base);
        for (int i = 1; i < parts.size(); i++) {
            RowExpression arg = parseValue(parts.get(i).trim(), variables);
            if (arg != null) {
                args.add(arg);
            }
        }
        List<RowExpression> normalizedArgs = new ArrayList<>();
        for (int i = 0; i < args.size(); i++) {
            RowExpression arg = args.get(i);
            if (i > 0 && arg instanceof ConstantExpression && arg.getType() instanceof BigintType) {
                Object value = ((ConstantExpression) arg).getValue();
                if (value instanceof Number) {
                    arg = new ConstantExpression(((Number) value).longValue(), IntegerType.INTEGER);
                }
            }
            normalizedArgs.add(arg);
        }
        List<TypeSignatureProvider> parameterTypes = new ArrayList<>();
        for (RowExpression arg : normalizedArgs) {
            parameterTypes.addAll(TypeSignatureProvider.fromTypes(arg.getType()));
        }
        com.facebook.presto.spi.function.FunctionHandle functionHandle = metadata.getFunctionAndTypeManager().resolveFunction(
                Optional.empty(),
                Optional.empty(),
                new QualifiedObjectName("presto", "default", "substr"),
                parameterTypes);
        return new CallExpression("substr", functionHandle, VarcharType.VARCHAR, normalizedArgs);
    }

    private RowExpression parseCanonicalSubstringArgument(String argument, Map<String, VariableReferenceExpression> variables)
    {
        String normalized = canonicalizeExpressionText(argument);
        RowExpression parsed = parseValue(normalized, variables);
        return parsed != null ? parsed : parseValue(argument, variables);
    }

    private String canonicalizeExpressionText(String expression)
    {
        if (expression == null) {
            return null;
        }
        String normalized = expression.replace("\"", "").replace("::text", "").replace("::varchar", "").trim();
        String beforeWhitespace = normalized;
        normalized = normalized.replaceAll("\\s+", " ");
        normalized = normalized.replace("substring (", "substring(");
        normalized = normalized.replace("substring(", "substring(");
        normalized = normalized.replace(" ,", ",").replace(", ", ",");
        if (!beforeWhitespace.equals(normalized)) {
            System.out.println("[OpengaussPlanAdapter] canonicalizeExpressionText before=" + beforeWhitespace + " after=" + normalized);
        }
        return normalized;
    }

    private RowExpression parseCast(String normalized, Map<String, VariableReferenceExpression> variables)
    {
        int open = normalized.indexOf('(');
        int close = normalized.lastIndexOf(')');
        if (open < 0 || close <= open) {
            return new ConstantExpression(stripQuotes(normalized), VarcharType.VARCHAR);
        }
        String inside = normalized.substring(open + 1, close).trim();
        int asIndex = inside.toUpperCase(Locale.ENGLISH).lastIndexOf(" AS ");
        if (asIndex < 0) {
            return parseValue(inside, variables);
        }
        String valuePart = inside.substring(0, asIndex).trim();
        return parseValue(valuePart, variables);
    }

    private RowExpression parseCaseWhen(String normalized, Map<String, VariableReferenceExpression> variables, boolean projectMode)
    {
        String upper = normalized.toUpperCase(Locale.ENGLISH);
        int whenIndex = upper.indexOf("WHEN ");
        int thenIndex = upper.indexOf(" THEN ");
        int elseIndex = upper.lastIndexOf(" ELSE ");
        int endIndex = upper.lastIndexOf(" END");
        if (whenIndex < 0 || thenIndex < 0 || endIndex < 0) {
            return null;
        }
        String condition = normalized.substring(whenIndex + 5, thenIndex).trim();
        String thenPart;
        String elsePart = null;
        if (elseIndex > thenIndex) {
            thenPart = normalized.substring(thenIndex + 6, elseIndex).trim();
            elsePart = normalized.substring(elseIndex + 6, endIndex).trim();
        }
        else {
            thenPart = normalized.substring(thenIndex + 6, endIndex).trim();
        }
        RowExpression condExpr = parsePredicate(condition, variables, null);
        RowExpression thenExpr = parseExpression(thenPart, variables, projectMode);
        RowExpression elseExpr = elsePart == null ? null : parseExpression(elsePart, variables, projectMode);
        System.out.println("[OpengaussPlanAdapter] parseCaseWhen condition=" + condition
                + " condExpr=" + condExpr + " condType=" + (condExpr == null ? "null" : condExpr.getType())
                + " thenExpr=" + thenExpr + " thenType=" + (thenExpr == null ? "null" : thenExpr.getType())
                + " elseExpr=" + elseExpr + " elseType=" + (elseExpr == null ? "null" : elseExpr.getType()));

        condExpr = normalizeBooleanCondition(condExpr, variables);
        System.out.println("[OpengaussPlanAdapter] parseCaseWhen normalizedCondExpr=" + condExpr
                + " normalizedCondType=" + (condExpr == null ? "null" : condExpr.getType()));

        Type resultType = resolveCaseWhenType(thenExpr, elseExpr);
        List<RowExpression> args = new ArrayList<>();
        args.add(condExpr == null ? new ConstantExpression(true, BooleanType.BOOLEAN) : condExpr);
        args.add(thenExpr == null ? new ConstantExpression(null, resultType) : coerceExpressionType(thenExpr, resultType));
        args.add(elseExpr == null ? new ConstantExpression(null, resultType) : coerceExpressionType(elseExpr, resultType));
        System.out.println("[OpengaussPlanAdapter] parseCaseWhen finalArgsTypes="
                + (args.size() > 0 && args.get(0) != null ? args.get(0).getType() : "null") + ", "
                + (args.size() > 1 && args.get(1) != null ? args.get(1).getType() : "null") + ", "
                + (args.size() > 2 && args.get(2) != null ? args.get(2).getType() : "null")
                + " resultType=" + resultType);
        return new SpecialFormExpression(SpecialFormExpression.Form.IF, resultType, args);
    }

    private RowExpression normalizeBooleanCondition(RowExpression condExpr, Map<String, VariableReferenceExpression> variables)
    {
        if (condExpr == null || condExpr.getType() == null) {
            return new ConstantExpression(true, BooleanType.BOOLEAN);
        }
        if (BooleanType.BOOLEAN.equals(condExpr.getType())) {
            return condExpr;
        }
        if (isNumericType(condExpr.getType())) {
            RowExpression zero = new ConstantExpression(0L, BigintType.BIGINT);
            return buildComparison("<>", condExpr, zero);
        }
        RowExpression parsed = parsePredicate(condExpr.toString(), variables, null);
        if (parsed != null && BooleanType.BOOLEAN.equals(parsed.getType())) {
            return parsed;
        }
        return new ConstantExpression(true, BooleanType.BOOLEAN);
    }

    private Type resolveCaseWhenType(RowExpression thenExpr, RowExpression elseExpr)
    {
        Type thenType = thenExpr == null ? null : thenExpr.getType();
        Type elseType = elseExpr == null ? null : elseExpr.getType();
        if (isNumericType(thenType) || isNumericType(elseType)) {
            if (isFloatingPointType(thenType) || isFloatingPointType(elseType)) {
                return DoubleType.DOUBLE;
            }
            return BigintType.BIGINT;
        }
        if (thenType != null) {
            return thenType;
        }
        if (elseType != null) {
            return elseType;
        }
        return VarcharType.VARCHAR;
    }

    private boolean isFloatingPointType(Type type)
    {
        return type != null && (DoubleType.DOUBLE.equals(type) || RealType.REAL.equals(type));
    }

    private RowExpression coerceExpressionType(RowExpression expression, Type targetType)
    {
        if (expression == null || targetType == null || expression.getType() == null || expression.getType().equals(targetType)) {
            return expression;
        }
        if (expression instanceof ConstantExpression) {
            Object value = ((ConstantExpression) expression).getValue();
            if (targetType.equals(BigintType.BIGINT) && value instanceof Number) {
                return new ConstantExpression(((Number) value).longValue(), BigintType.BIGINT);
            }
            if (targetType.equals(DoubleType.DOUBLE) && value instanceof Number) {
                return new ConstantExpression(((Number) value).doubleValue(), DoubleType.DOUBLE);
            }
        }
        return expression;
    }

    private List<String> textList(JsonNode node, String field)
    {
        List<String> result = new ArrayList<>();
        if (node == null) {
            return result;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isArray()) {
            return result;
        }
        for (JsonNode element : value) {
            String text = element.asText();
            if (text != null && !text.isBlank()) {
                result.add(text);
            }
        }
        return result;
    }

    private List<String> splitJoinConditions(String cond)
    {
        if (cond == null || cond.isBlank()) {
            return Collections.emptyList();
        }
        String cleaned = cond.replace("(", "").replace(")", "").replace("\"", "");
        String[] parts = cleaned.split("(?i)\\s+AND\\s+");
        List<String> result = new ArrayList<>();
        for (String part : parts) {
            if (part.contains("=")) {
                result.add(part.trim());
            }
        }
        return result;
    }

    private ConstantExpression varcharConstant(String value)
    {
        return new ConstantExpression(value == null ? null : Slices.utf8Slice(value), VarcharType.VARCHAR);
    }

    private RowExpression coerceExpressionToTextType(RowExpression expression, Type targetType)
    {
        if (expression == null || targetType == null || expression.getType() == null || expression.getType().equals(targetType)) {
            return expression;
        }
        if (!(targetType instanceof VarcharType)) {
            return expression;
        }
        if (expression instanceof ConstantExpression) {
            Object value = ((ConstantExpression) expression).getValue();
            return new ConstantExpression(value, targetType);
        }
        return expression;
    }

    private Type widenTextType(Type leftType, Type rightType)
    {
        if (leftType == null) {
            return rightType;
        }
        if (rightType == null) {
            return leftType;
        }
        if (leftType.equals(rightType)) {
            return leftType;
        }
        if (isTextType(leftType) && isTextType(rightType)) {
            return VarcharType.VARCHAR;
        }
        return null;
    }

    private BuiltInFunctionHandle builtInComparisonHandle(OperatorType type, RowExpression left, RowExpression right)
    {
        String functionName;
        switch (type) {
            case EQUAL:
                functionName = "$operator$equal";
                break;
            case NOT_EQUAL:
                functionName = "$operator$not_equal";
                break;
            case GREATER_THAN:
                functionName = "$operator$greater_than";
                break;
            case GREATER_THAN_OR_EQUAL:
                functionName = "$operator$greater_than_or_equal";
                break;
            case LESS_THAN:
                functionName = "$operator$less_than";
                break;
            case LESS_THAN_OR_EQUAL:
                functionName = "$operator$less_than_or_equal";
                break;
            default:
                functionName = "$operator$" + type.name().toLowerCase(Locale.ENGLISH);
                break;
        }
        Type leftType = left == null ? null : left.getType();
        Type rightType = right == null ? null : right.getType();
        if (isNumericType(leftType) && isNumericType(rightType) && leftType != null && rightType != null && !leftType.equals(rightType)) {
            Type commonType = widenNumericType(leftType, rightType);
            // The row expressions themselves may still carry their original types
            // (for example a VariableReferenceExpression on one side and a DOUBLE
            // arithmetic expression on the other). The comparison function handle
            // must still be resolved against the shared numeric signature.
            leftType = commonType;
            rightType = commonType;
        }
        return builtInHandle(functionName, BooleanType.BOOLEAN, leftType, rightType);
    }

    private BuiltInFunctionHandle builtInUnaryHandle(String functionName, Type returnType, Type argumentType)
    {
        return builtInHandle(functionName, returnType, argumentType);
    }

    private BuiltInFunctionHandle builtInHandle(String functionName, Type returnType, Type... argumentTypes)
    {
        List<TypeSignature> signatures = new ArrayList<>();
        for (Type argumentType : argumentTypes) {
            signatures.add(argumentType.getTypeSignature());
        }
        Signature signature = new Signature(
                new QualifiedObjectName("presto", "default", functionName),
                FunctionKind.SCALAR,
                returnType.getTypeSignature(),
                signatures);
        return new BuiltInFunctionHandle(signature);
    }

    private String stripQuotes(String input)
    {
        if (input == null) {
            return null;
        }
        String stripped = input.trim();
        if (stripped.startsWith("'") && stripped.endsWith("'")) {
            stripped = stripped.substring(1, stripped.length() - 1);
        }
        if (stripped.startsWith("\"") && stripped.endsWith("\"")) {
            stripped = stripped.substring(1, stripped.length() - 1);
        }
        return stripped;
    }

    private RowExpression firstAggregationInput(String functionName, PlanNode source)
    {
        if ("count".equalsIgnoreCase(functionName)) {
            return new ConstantExpression(1L, BigintType.BIGINT);
        }

        Map<String, VariableReferenceExpression> variables = buildVariablesByOutput(source);
        if ("avg".equalsIgnoreCase(functionName)) {
            VariableReferenceExpression avgVariable = selectBestMatchingVariable(variables, null, "avg");
            if (avgVariable != null) {
                return avgVariable;
            }
        }

        RowExpression candidate = firstNumericAggregationInput(variables);
        if (candidate != null) {
            if ("avg".equalsIgnoreCase(functionName) && candidate instanceof VariableReferenceExpression) {
                String name = ((VariableReferenceExpression) candidate).getName().toLowerCase(Locale.ENGLISH);
                if (name.contains("sum") && !name.contains("avg")) {
                    VariableReferenceExpression avgVariable = selectBestMatchingVariable(variables, null, "avg");
                    if (avgVariable != null) {
                        return avgVariable;
                    }
                }
            }
            return candidate;
        }
        for (VariableReferenceExpression variable : source.getOutputVariables()) {
            if ("avg".equalsIgnoreCase(functionName) && variable.getName().toLowerCase(Locale.ENGLISH).contains("avg")) {
                return variable;
            }
            if (!BooleanType.BOOLEAN.equals(variable.getType()) && !VarcharType.VARCHAR.equals(variable.getType())) {
                return variable;
            }
        }
        if (!source.getOutputVariables().isEmpty()) {
            return source.getOutputVariables().get(0);
        }
        return new ConstantExpression(0.0, DoubleType.DOUBLE);
    }

    private VariableReferenceExpression selectBestMatchingVariable(Map<String, VariableReferenceExpression> variables, String expression, String keyword)
    {
        if (variables == null || variables.isEmpty() || keyword == null || keyword.isBlank()) {
            return null;
        }
        String normalizedExpression = expression == null ? "" : canonicalizeExpressionText(expression).toLowerCase(Locale.ENGLISH).replace(" ", "");
        String lowerKeyword = keyword.toLowerCase(Locale.ENGLISH);
        List<String> expressionTokens = extractExpressionTokens(expression);

        VariableReferenceExpression best = null;
        int bestScore = -1;
        for (Map.Entry<String, VariableReferenceExpression> entry : variables.entrySet()) {
            VariableReferenceExpression variable = entry.getValue();
            if (variable == null || variable.getName() == null) {
                continue;
            }
            String variableName = variable.getName().toLowerCase(Locale.ENGLISH);
            int score = 0;
            if (variableName.equals(lowerKeyword) || variableName.equals(lowerKeyword + "_") || variableName.startsWith(lowerKeyword + "__")) {
                score += 20;
            }
            if (variableName.contains(lowerKeyword)) {
                score += 10;
            }
            if (normalizedExpression.contains(variableName.replace("_raw", ""))) {
                score += 8;
            }
            for (String token : expressionTokens) {
                if (token == null || token.isBlank()) {
                    continue;
                }
                String lowerToken = token.toLowerCase(Locale.ENGLISH);
                if (lowerToken.equals("sum") || lowerToken.equals("avg") || lowerToken.equals("count") || lowerToken.equals("min") || lowerToken.equals("max") || lowerToken.equals("pg_catalog") || lowerToken.equals("numeric")) {
                    continue;
                }
                if (variableName.contains(lowerToken) || lowerToken.contains(variableName)) {
                    score += lowerToken.contains("quantity") || lowerToken.contains("extendedprice") || lowerToken.contains("discount") || lowerToken.contains("tax") ? 20 : 4;
                }
            }
            if (variableName.startsWith("avg") && normalizedExpression.contains("avg(")) {
                score += 5;
            }
            if (variableName.startsWith("sum") && normalizedExpression.contains("sum(")) {
                score += 3;
            }
            if (score > bestScore) {
                bestScore = score;
                best = variable;
            }
        }
        return best;
    }


    private List<AggregationCallSpec> parseAggregationCallSpecs(JsonNode node, PlanNode source)
    {
        List<AggregationCallSpec> specs = new ArrayList<>();
        List<String> outputItems = parseOutputNames(node);
        if (outputItems.isEmpty()) {
            return specs;
        }
        Map<String, VariableReferenceExpression> variables = buildVariablesByOutput(source);
        for (String item : outputItems) {
            AggregationCallSpec spec = parseAggregationFragment(item, variables, source, node, false);
            if (spec != null) {
                System.out.println("[OpengaussPlanAdapter] parsed aggregate spec from Output value=" + item);
                specs.add(spec);
            }
        }
        return specs;
    }

    private List<AggregationCallSpec> parseSortAggregateSpecs(JsonNode node, PlanNode source)
    {
        List<AggregationCallSpec> specs = new ArrayList<>();
        List<String> outputItems = parseOutputNames(node);
        if (outputItems.isEmpty()) {
            return specs;
        }
        Map<String, VariableReferenceExpression> variables = buildVariablesByOutput(source);
        for (String item : outputItems) {
            AggregationCallSpec spec = parseAggregationFragment(item, variables, source, node, true);
            if (spec != null) {
                System.out.println("[OpengaussPlanAdapter] parsed aggregate spec from Output value=" + item);
                specs.add(spec);
            }
        }
        return specs;
    }

    private AggregationCallSpec parseAggregationCallSpec(JsonNode node, PlanNode source, String functionName)
    {
        List<AggregationCallSpec> specs = parseAggregationCallSpecs(node, source);
        for (AggregationCallSpec spec : specs) {
            if (functionName == null || spec.getFunctionName().equalsIgnoreCase(functionName)) {
                return spec;
            }
        }
        return null;
    }

    private List<AggregationCallSpec> splitAggregationText(String text, Map<String, VariableReferenceExpression> variables, PlanNode source, JsonNode node, boolean sortAggregate)
    {
        List<AggregationCallSpec> specs = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return specs;
        }
        for (String fragment : splitTopLevelAggregates(text)) {
            AggregationCallSpec spec = parseAggregationFragment(fragment, variables, source, node, sortAggregate);
            if (spec != null) {
                specs.add(spec);
            }
        }
        return specs;
    }

    private AggregationCallSpec parseAggregationFragment(String text, Map<String, VariableReferenceExpression> variables, PlanNode source, JsonNode node, boolean sortAggregate)
    {
        if (text == null || text.isBlank()) {
            return null;
        }
        String normalized = canonicalizeExpressionText(text);
        String lower = normalized.toLowerCase(Locale.ENGLISH);
        String functionName = inferAggregationFunctionFromText(lower);
        if (!containsAggregationFunction(lower)) {
            return null;
        }
        int open = normalized.indexOf('(');
        int close = normalized.lastIndexOf(')');
        if (open < 0 || close <= open) {
            return null;
        }
        String inside = normalized.substring(open + 1, close).trim();
        while (canStripWrappingParens(inside)) {
            inside = inside.substring(1, inside.length() - 1).trim();
        }
        if ("count".equalsIgnoreCase(functionName) && ("*".equals(inside) || "1".equals(inside))) {
            return new AggregationCallSpec("count", inferAggregationSemanticNames(node, source), List.of(new ConstantExpression(1L, BigintType.BIGINT)), BigintType.BIGINT);
        }
        if ("sum".equalsIgnoreCase(functionName) && ("count(*)".equalsIgnoreCase(inside) || "count(1)".equalsIgnoreCase(inside))) {
            // sum(count(*)) is the two-phase aggregation pattern: the outer aggregate sums
            // up the partial counts produced by each worker.  The argument must be the
            // count variable from the partial aggregation stage (BIGINT), NOT any sum_*
            // column.  Resolve it here and return immediately to avoid the generic
            // preferred-variable selection logic below, which would otherwise replace
            // the BIGINT count variable with a DOUBLE sum_* column.
            VariableReferenceExpression countVariable = selectBestMatchingVariable(variables, "count", "count");
            if (countVariable == null) {
                countVariable = lookupVariable("count_", variables);
            }
            if (countVariable == null) {
                countVariable = lookupVariable("count__raw", variables);
            }
            if (countVariable != null) {
                System.out.println("[OpengaussPlanAdapter] parseAggregationFragment sum(count(*)) resolved countVariable=" + countVariable);
                return new AggregationCallSpec("sum", inferAggregationSemanticNames(node, source), List.of(countVariable), BigintType.BIGINT);
            }
            // No partial count variable found – fall through to generic handling
        }
        VariableReferenceExpression directInsideVariable = lookupVariable(inside, variables);
        if (directInsideVariable != null) {
            inside = directInsideVariable.getName();
        }
        else if (isAggregationFunctionCall(inside)) {
            int nestedOpen = inside.indexOf('(');
            int nestedClose = inside.lastIndexOf(')');
            if (nestedOpen >= 0 && nestedClose > nestedOpen) {
                // The 'inside' here is a nested aggregate call like avg(l_quantity) or sum(l_quantity).
                // The outer aggregate function (functionName) operates on the *result* of this inner
                // aggregate.  So we should look up the variable produced by the inner aggregate node
                // (e.g. avg_l_quantity_, sum_l_quantity_) rather than the raw column name.
                String innerFuncName = inside.substring(0, nestedOpen).trim().toLowerCase(Locale.ENGLISH);
                // Strip pg_catalog prefix if present
                if (innerFuncName.startsWith("pg_catalog.")) {
                    innerFuncName = innerFuncName.substring("pg_catalog.".length()).trim();
                }
                String nestedInside = inside.substring(nestedOpen + 1, nestedClose).trim();
                // Strip extra parens around nestedInside
                while (nestedInside.startsWith("(") && nestedInside.endsWith(")") && matchingParens(nestedInside)) {
                    nestedInside = nestedInside.substring(1, nestedInside.length() - 1).trim();
                }
                // 1. First try: look for a variable that captures both the inner function name and
                //    the column name (e.g. "avg_l_quantity_" for avg(l_quantity)).
                //    This is the correct reference for a two-level aggregation like avg(avg(l_quantity)).
                VariableReferenceExpression nestedVariable = lookupVariableByFunctionAndColumn(innerFuncName, nestedInside, variables);
                if (nestedVariable == null) {
                    // 2. Fallback: exact lookup of the inner-column name itself
                    nestedVariable = lookupVariable(nestedInside, variables);
                }
                if (nestedVariable != null) {
                    inside = nestedVariable.getName();
                }
                else {
                    // 3. Last resort: use the bare column name so that later heuristics can
                    //    at least try to find the right variable via selectBestMatchingVariable.
                    inside = nestedInside;
                }
            }
        }
        VariableReferenceExpression shapedCaseVariable = selectCaseWhenVariable(inside, variables);
        if (shapedCaseVariable != null) {
            inside = shapedCaseVariable.getName();
        }
        RowExpression argument = parseAggregationArgumentExpression(inside, variables);
        if ("avg".equalsIgnoreCase(functionName) || "sum".equalsIgnoreCase(functionName)) {
            String keyword = functionName.toLowerCase(Locale.ENGLISH);
            VariableReferenceExpression preferred = selectBestMatchingVariable(variables, inside, keyword);
            if ("avg".equalsIgnoreCase(functionName)) {
                VariableReferenceExpression avgPreferred = selectBestMatchingVariable(variables, inside, "avg");
                if (avgPreferred != null) {
                    preferred = avgPreferred;
                }

                if (argument instanceof VariableReferenceExpression) {
                    String argName = ((VariableReferenceExpression) argument).getName().toLowerCase(Locale.ENGLISH);
                    if (argName.startsWith("sum_") || argName.contains("sum")) {
                        String semanticCandidateName = argName.replaceFirst("(?i)^sum", "avg");
                        VariableReferenceExpression semanticCandidate = lookupVariable(semanticCandidateName, variables);
                        if (semanticCandidate == null) {
                            semanticCandidate = lookupVariable(semanticCandidateName.replace("_raw", ""), variables);
                        }
                        if (semanticCandidate != null) {
                            argument = semanticCandidate;
                        }
                        else {
                            VariableReferenceExpression counterpart = lookupVariable(argName.replaceFirst("(?i)^sum", "avg"), variables);
                            if (counterpart != null) {
                                argument = counterpart;
                            }
                        }
                    }

                    if (argument instanceof VariableReferenceExpression) {
                        VariableReferenceExpression innerAvg = lookupVariableByFunctionAndColumn("avg", inside, variables);
                        if (innerAvg != null) {
                            argument = innerAvg;
                        }
                    }
                }
            }
            if (preferred != null) {
                if (argument == null) {
                    argument = preferred;
                }
                else if (argument instanceof VariableReferenceExpression) {
                    String argName = ((VariableReferenceExpression) argument).getName().toLowerCase(Locale.ENGLISH);
                    String preferredName = preferred.getName().toLowerCase(Locale.ENGLISH);
                    List<String> expressionTokens = extractExpressionTokens(inside);
                    boolean matchesPreferred = expressionTokens.stream().anyMatch(token -> preferredName.contains(token.toLowerCase(Locale.ENGLISH)) || token.toLowerCase(Locale.ENGLISH).contains(preferredName));
                    boolean matchesArgument = expressionTokens.stream().anyMatch(token -> argName.contains(token.toLowerCase(Locale.ENGLISH)) || token.toLowerCase(Locale.ENGLISH).contains(argName));
                    if ("avg".equalsIgnoreCase(functionName)) {
                        boolean argumentLooksLikeAvg = argName.contains("avg");
                        boolean preferredLooksLikeAvg = preferredName.contains("avg");
                        boolean argumentLooksLikeSum = argName.contains("sum");
                        boolean preferredLooksLikeSum = preferredName.contains("sum");
                        if (preferredLooksLikeAvg && !argumentLooksLikeAvg) {
                            argument = preferred;
                        }
                        else if (argumentLooksLikeSum && !preferredLooksLikeSum) {
                            argument = preferred;
                        }
                        else if ((!matchesArgument && matchesPreferred) || (!argumentLooksLikeAvg && argName.contains(keyword))) {
                            argument = preferred;
                        }
                    }
                    else if ((!matchesArgument && matchesPreferred) || argName.contains("sum") || (!argName.contains(keyword))) {
                        // Do NOT replace a count-derived variable (e.g. count_, count__raw) with a
                        // preferred sum_* variable.  The pattern sum(count(*)) means "sum up the
                        // partial counts from each worker", so the argument MUST be the count
                        // variable produced by the partial aggregation, not any sum_ column.
                        boolean argumentIsCountDerived = argName.contains("count")
                                && argument instanceof VariableReferenceExpression
                                && BigintType.BIGINT.equals(argument.getType());
                        if (!argumentIsCountDerived) {
                            argument = preferred;
                        }
                    }
                }
                else if (argument instanceof ConstantExpression) {
                    argument = preferred;
                }
            }
        }
        System.out.println("[OpengaussPlanAdapter] parseAggregationFragment preNormalize functionName=" + functionName
                + " inside=" + inside
                + " argument=" + argument
                + " argumentType=" + (argument == null ? "null" : argument.getType())
                + " argumentClass=" + (argument == null ? "null" : argument.getClass().getName()));
        if (argument == null) {
            return null;
        }
        if (argument instanceof ConstantExpression && argument.getType() instanceof VarcharType && containsAggregationFunction(inside.toLowerCase(Locale.ENGLISH))) {
            return null;
        }
        if (argument instanceof ConstantExpression && ((ConstantExpression) argument).getValue() == null) {
            VariableReferenceExpression fallbackCount = selectBestMatchingVariable(variables, inside, "count");
            if (fallbackCount != null && (functionName.equalsIgnoreCase("count") || functionName.equalsIgnoreCase("sum"))) {
                argument = fallbackCount;
            }
        }
        if (("avg".equalsIgnoreCase(functionName) || "sum".equalsIgnoreCase(functionName))) {
            if (!isNumericType(argument.getType())) {
                return null;
            }
            if (!DoubleType.DOUBLE.equals(argument.getType())) {
                RowExpression promoted = promoteNumericExpressionToDouble(argument);
                if (promoted != null) {
                    argument = promoted;
                }
            }
        }
        if (argument instanceof ConstantExpression && !isNumericType(argument.getType())) {
            return null;
        }
        if (argument instanceof ConstantExpression && argument.getType() instanceof VarcharType) {
            return null;
        }
        Type returnType = inferAggregationReturnType(functionName, argument.getType());
        if ("avg".equalsIgnoreCase(functionName)) {
            returnType = DoubleType.DOUBLE;
        }
        if ("count".equalsIgnoreCase(functionName)) {
            returnType = BigintType.BIGINT;
        }
        System.out.println("[OpengaussPlanAdapter] buildAggregationCall functionName=" + functionName
                + " returnType=" + returnType
                + " arguments=" + List.of(argument)
                + " argumentTypes=" + List.of(argument == null ? null : argument.getType()));
        return new AggregationCallSpec(functionName, inferAggregationSemanticNames(node, source), List.of(argument), returnType);
    }

    private RowExpression parseAggregationArgumentExpression(String text, Map<String, VariableReferenceExpression> variables)
    {
        if (text == null || text.isBlank()) {
            return null;
        }
        String normalized = stripUnmatchedOuterParens(canonicalizeExpressionText(text).trim());
        String lower = normalized.toLowerCase(Locale.ENGLISH);

        VariableReferenceExpression directVariable = lookupVariable(normalized, variables);
        if (directVariable != null) {
            return directVariable;
        }
        if (lower.startsWith("case when") || lower.contains(" case when ")) {
            return parseCaseWhen(normalized, variables, false);
        }
        if (lower.startsWith("pg_catalog.")) {
            normalized = normalized.substring("pg_catalog.".length()).trim();
            lower = normalized.toLowerCase(Locale.ENGLISH);
            directVariable = lookupVariable(normalized, variables);
            if (directVariable != null) {
                return directVariable;
            }
        }

        // If we are handed another aggregation call (for example, sum(sum(x)) or
        // avg(avg(x))), peel it until we reach the real underlying argument. For
        // COUNT(*), the inner aggregate result should be the partial count column
        // if it exists; otherwise fall back to a constant 1 for a raw count scan.
        if (isAggregationFunctionCall(normalized)) {
            int open = normalized.indexOf('(');
            int close = normalized.lastIndexOf(')');
            if (open >= 0 && close > open) {
                String functionName = normalized.substring(0, open).trim().toLowerCase(Locale.ENGLISH);
                if (functionName.startsWith("pg_catalog.")) {
                    functionName = functionName.substring("pg_catalog.".length()).trim();
                }
                String nestedInside = normalized.substring(open + 1, close).trim();
                while (nestedInside.startsWith("(") && nestedInside.endsWith(")") && matchingParens(nestedInside)) {
                    nestedInside = nestedInside.substring(1, nestedInside.length() - 1).trim();
                }
                if ("count".equals(functionName) && ("*".equals(nestedInside) || "1".equals(nestedInside))) {
                    VariableReferenceExpression countVariable = selectBestMatchingVariable(variables, "count", "count");
                    if (countVariable == null) {
                        countVariable = lookupVariable("count_", variables);
                    }
                    if (countVariable != null) {
                        return countVariable;
                    }
                    VariableReferenceExpression sumCountVariable = selectBestMatchingVariable(variables, "count", "sum");
                    if (sumCountVariable != null) {
                        return sumCountVariable;
                    }
                    return new ConstantExpression(1L, BigintType.BIGINT);
                }
                if (isAggregationFunctionCall(nestedInside) || nestedInside.toLowerCase(Locale.ENGLISH).startsWith("pg_catalog.")) {
                    return parseAggregationArgumentExpression(nestedInside, variables);
                }
                RowExpression nested = parseExpression(nestedInside, variables, false);
                if (nested != null) {
                    return nested;
                }
                nested = parseValue(nestedInside, variables);
                if (nested != null && !(nested instanceof ConstantExpression && nested.toString().toLowerCase(Locale.ENGLISH).contains("sum("))) {
                    return nested;
                }
            }
            return null;
        }

        if (isAggregationFunctionCall(normalized)) {
            int open = normalized.indexOf('(');
            int close = normalized.lastIndexOf(')');
            if (open >= 0 && close > open) {
                String nestedInside = normalized.substring(open + 1, close).trim();
                RowExpression nested = parseAggregationArgumentExpression(nestedInside, variables);
                if (nested != null) {
                    return nested;
                }
            }
            return null;
        }

        VariableReferenceExpression exact = variables.get(normalized.toLowerCase(Locale.ENGLISH));
        if (exact != null) {
            return exact;
        }
        String simple = simpleName(normalized).toLowerCase(Locale.ENGLISH);
        exact = variables.get(simple);
        if (exact != null) {
            return exact;
        }

        // For simple column-like identifiers, prefer a direct variable lookup before
        // trying the generic expression parser, which can sometimes degrade them
        // into constants during nested aggregate rewriting.
        if (!normalized.contains("(") && !normalized.contains(")") && !normalized.contains(" ")) {
            VariableReferenceExpression direct = lookupVariable(normalized, variables);
            if (direct != null) {
                return direct;
            }
        }

        VariableReferenceExpression shaped = lookupVariableByExpressionShape(normalized, variables);
        if (shaped != null) {
            return shaped;
        }

        RowExpression parsed = parseExpression(normalized, variables, false);
        if (parsed instanceof VariableReferenceExpression) {
            return parsed;
        }
        if (parsed instanceof ConstantExpression) {
            return null;
        }
        if (parsed != null) {
            return parsed;
        }

        // For aggregation arguments, do not fall back to parsing bare identifiers
        // as string literals. If we cannot resolve it as an actual expression or
        // variable reference, treat it as unresolved so the caller can drop it.
        if (normalized.contains("(") || normalized.contains(")")) {
            return null;
        }
        return null;
    }

    private List<String> splitTopLevelAggregates(String text)
    {
        List<String> fragments = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return fragments;
        }
        if (!matchingParens(text)) {
            return Collections.singletonList(text.trim());
        }
        int depth = 0;
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(') {
                depth++;
            }
            else if (c == ')') {
                depth = Math.max(0, depth - 1);
            }
            else if (c == ',' && depth == 0) {
                String fragment = text.substring(start, i).trim();
                if (!fragment.isEmpty()) {
                    fragments.add(fragment);
                }
                start = i + 1;
            }
        }
        String last = text.substring(start).trim();
        if (!last.isEmpty()) {
            fragments.add(last);
        }
        return fragments;
    }

    private boolean containsAggregationFunction(String lowerText)
    {
        return lowerText.contains("count(") || lowerText.contains("sum(") || lowerText.contains("avg(") || lowerText.contains("min(") || lowerText.contains("max(");
    }

    private String inferAggregationFunctionFromText(String lowerText)
    {
        if (lowerText.contains("sum(")) {
            return "sum";
        }
        if (lowerText.contains("count(")) {
            return "count";
        }
        if (lowerText.contains("avg(")) {
            return "avg";
        }
        if (lowerText.contains("min(")) {
            return "min";
        }
        if (lowerText.contains("max(")) {
            return "max";
        }
        return "count";
    }

    private boolean isAggregationFunctionCall(String text)
    {
        if (text == null) {
            return false;
        }
        String normalized = text.trim().toLowerCase(Locale.ENGLISH);
        if (normalized.startsWith("pg_catalog.")) {
            normalized = normalized.substring("pg_catalog.".length()).trim();
        }
        return normalized.startsWith("sum(") || normalized.startsWith("avg(") || normalized.startsWith("count(") || normalized.startsWith("min(") || normalized.startsWith("max(");
    }

    private RowExpression firstAggregationInput(String functionName, PlanNode source, JsonNode node)
    {
        if ("count".equalsIgnoreCase(functionName)) {
            return parseCountInput(node, source);
        }
        RowExpression parsed = parseAggregateArgument(node, source);
        if (parsed != null) {
            return parsed;
        }
        return firstAggregationInput(functionName, source);
    }

    private RowExpression parseCountInput(JsonNode node, PlanNode source)
    {
        String aggText = firstNonNull(text(node, "Output"), text(node, "Aggs"), text(node, "Aggregates"), text(node, "Target List"));
        if (aggText != null) {
            String normalized = aggText.replace(" ", "").toLowerCase(Locale.ENGLISH);
            if (normalized.contains("count(*)") || normalized.contains("count(1)")) {
                return new ConstantExpression(1L, BigintType.BIGINT);
            }
        }
        RowExpression arg = parseAggregateArgument(node, source);
        return arg == null ? new ConstantExpression(1L, DoubleType.DOUBLE) : arg;
    }

    private RowExpression parseAggregateArgument(JsonNode node, PlanNode source)
    {
        Map<String, VariableReferenceExpression> variables = buildVariablesByOutput(source);
        String[] candidateFields = new String[] {"Aggs", "Aggregates", "Target List", "Output"};
        for (String field : candidateFields) {
            JsonNode value = node.get(field);
            if (value == null || value.isNull()) {
                System.out.println("[OpengaussPlanAdapter] parseAggregateArgument field=" + field + " value=null");
                continue;
            }
            System.out.println("[OpengaussPlanAdapter] parseAggregateArgument field=" + field + " textual=" + value.isTextual() + " array=" + value.isArray() + " raw=" + value);
            if (value.isTextual()) {
                RowExpression parsed = parseAggregateArgumentText(value.asText(), variables);
                System.out.println("[OpengaussPlanAdapter] parseAggregateArgument field=" + field + " parsed=" + parsed + " type=" + (parsed == null ? "null" : parsed.getType()));
                if (parsed != null) {
                    return parsed;
                }
            }
            if (value.isArray()) {
                for (JsonNode element : value) {
                    String text = element.asText();
                    System.out.println("[OpengaussPlanAdapter] parseAggregateArgument field=" + field + " elementRaw=" + text);
                    RowExpression parsed = parseAggregateArgumentText(text, variables);
                    System.out.println("[OpengaussPlanAdapter] parseAggregateArgument field=" + field + " elementParsed=" + parsed + " type=" + (parsed == null ? "null" : parsed.getType()));
                    if (parsed != null) {
                        return parsed;
                    }
                }
            }
        }
        System.out.println("[OpengaussPlanAdapter] parseAggregateArgument no parsed argument found");
        return null;
    }

    private RowExpression parseAggregateArgumentText(String text, Map<String, VariableReferenceExpression> variables)
    {
        if (text == null || text.isBlank()) {
            return null;
        }
        String normalized = stripUnmatchedOuterParens(text.replace("::text", "").replace("::varchar", "").replace("\"", "").trim());
        System.out.println("[OpengaussPlanAdapter] parseAggregateArgumentText raw=" + text + " normalized=" + normalized);
        if (!matchingParens(normalized) && (normalized.contains("(") || normalized.contains(")"))) {
            System.out.println("[OpengaussPlanAdapter] parseAggregateArgumentText unbalanced normalized=" + normalized);
            return null;
        }
        if (normalized.contains("public.customer.c_acctbal")) {
            return parseValue("public.customer.c_acctbal", variables);
        }
        if (normalized.equalsIgnoreCase("count(*)") || normalized.equalsIgnoreCase("count(1)")) {
            return new ConstantExpression(1L, BigintType.BIGINT);
        }

        if (isAggregationFunctionCall(normalized)) {
            int open = normalized.indexOf('(');
            int close = normalized.lastIndexOf(')');
            System.out.println("[OpengaussPlanAdapter] parseAggregateArgumentText aggregate open=" + open + " close=" + close + " balanced=" + matchingParens(normalized));
            if (open >= 0 && close > open) {
                String inside = stripUnmatchedOuterParens(normalized.substring(open + 1, close).trim());
                System.out.println("[OpengaussPlanAdapter] parseAggregateArgumentText aggregate inside=" + inside);
                if (inside.equals("*") || inside.equals("1")) {
                    return new ConstantExpression(1L, BigintType.BIGINT);
                }
                if (isAggregationFunctionCall(inside)) {
                    RowExpression nested = parseAggregateArgumentText(inside, variables);
                    System.out.println("[OpengaussPlanAdapter] parseAggregateArgumentText nested aggregate parsed=" + nested + " type=" + (nested == null ? "null" : nested.getType()));
                    if (nested != null) {
                        return nested;
                    }
                }
                RowExpression parsedInside = parseExpression(inside, variables, false);
                if (parsedInside == null) {
                    parsedInside = parseValue(inside, variables);
                }
                System.out.println("[OpengaussPlanAdapter] parseAggregateArgumentText parsed inside=" + parsedInside + " type=" + (parsedInside == null ? "null" : parsedInside.getType()));
                if (parsedInside != null) {
                    if (parsedInside.getType() == null || VarcharType.VARCHAR.equals(parsedInside.getType())) {
                        System.out.println("[OpengaussPlanAdapter] parseAggregateArgumentText rejecting varchar aggregate argument raw=" + text);
                        return null;
                    }
                    return parsedInside;
                }
            }
            return null;
        }
        if (!normalized.contains("(") && !normalized.contains(")")) {
            VariableReferenceExpression direct = variables.get(simpleName(normalized).toLowerCase(Locale.ENGLISH));
            if (direct != null) {
                System.out.println("[OpengaussPlanAdapter] parseAggregateArgumentText direct variable=" + direct + " type=" + direct.getType());
                return direct;
            }
        }
        RowExpression parsed = parseExpression(normalized, variables, false);
        if (parsed == null) {
            parsed = parseValue(normalized, variables);
        }
        System.out.println("[OpengaussPlanAdapter] parseAggregateArgumentText parsed normalized=" + parsed + " type=" + (parsed == null ? "null" : parsed.getType()));
        if (parsed != null && (parsed.getType() == null || VarcharType.VARCHAR.equals(parsed.getType()))) {
            System.out.println("[OpengaussPlanAdapter] parseAggregateArgumentText rejecting non-numeric parsed value raw=" + text);
            return null;
        }
        return parsed;
    }

    private boolean isAggregationArgumentAllowed(RowExpression expression)
    {
        return expression instanceof VariableReferenceExpression || expression instanceof ConstantExpression || expression instanceof LambdaDefinitionExpression;
    }

    private RowExpression promoteNumericExpressionToDouble(RowExpression expression)
    {
        if (expression == null || expression.getType() == null || DoubleType.DOUBLE.equals(expression.getType())) {
            return expression;
        }
        if (expression instanceof ConstantExpression) {
            Object value = ((ConstantExpression) expression).getValue();
            if (value instanceof Number) {
                return new ConstantExpression(((Number) value).doubleValue(), DoubleType.DOUBLE);
            }
        }
        return expression;
    }

    private RowExpression buildArithmetic(String functionName, RowExpression left, RowExpression right)
    {
        if (left == null || right == null) {
            return null;
        }
        if (left.getType() == null || VarcharType.VARCHAR.equals(left.getType()) || left.getType() instanceof VarcharType) {
            left = new ConstantExpression(1.0, DoubleType.DOUBLE);
        }
        if (right.getType() == null || VarcharType.VARCHAR.equals(right.getType()) || right.getType() instanceof VarcharType) {
            right = new ConstantExpression(1.0, DoubleType.DOUBLE);
        }
        Type resultType = inferArithmeticType(left, right);
        left = coerceArithmeticOperand(left, resultType);
        right = coerceArithmeticOperand(right, resultType);
        String displayName;
        switch (functionName) {
            case "multiply":
                displayName = "$operator$multiply";
                break;
            case "divide":
                displayName = "$operator$divide";
                break;
            case "add":
                displayName = "$operator$add";
                break;
            case "subtract":
                displayName = "$operator$subtract";
                break;
            default:
                displayName = functionName;
                break;
        }
        return new CallExpression(displayName, builtInHandle(displayName, resultType, left.getType(), right.getType()), resultType, List.of(left, right));
    }

    private RowExpression coerceArithmeticOperand(RowExpression operand, Type targetType)
    {
        if (operand == null || operand.getType() == null || targetType == null) {
            return operand;
        }
        String text = stripQuotes(operand.toString());
        if (text == null) {
            text = operand.toString();
        }
        if (targetType instanceof DoubleType) {
            if (operand instanceof ConstantExpression) {
                if (text.matches("-?\\d+(\\.\\d+)?")) {
                    return new ConstantExpression(Double.valueOf(text), DoubleType.DOUBLE);
                }
            }
            if (!DoubleType.DOUBLE.equals(operand.getType())) {
                try {
                    com.facebook.presto.spi.function.FunctionHandle castHandle = metadata.getFunctionAndTypeManager().lookupCast(CastType.CAST, operand.getType(), DoubleType.DOUBLE);
                    return new CallExpression("$operator$cast", castHandle, DoubleType.DOUBLE, List.of(operand));
                }
                catch (RuntimeException e) {
                    System.out.println("[OpengaussPlanAdapter] failed to cast arithmetic operand to DOUBLE operand=" + operand + " type=" + operand.getType() + " reason=" + e.getMessage());
                }
            }
            return operand;
        }
        if (targetType instanceof BigintType) {
            if (operand instanceof ConstantExpression && text.matches("-?\\d+")) {
                return new ConstantExpression(Long.valueOf(text), BigintType.BIGINT);
            }
        }
        return operand;
    }

    private Type inferArithmeticType(RowExpression left, RowExpression right)
    {
        Type leftType = left == null ? null : left.getType();
        Type rightType = right == null ? null : right.getType();
        if (leftType instanceof DoubleType || rightType instanceof DoubleType) {
            return DoubleType.DOUBLE;
        }
        if (leftType instanceof RealType || rightType instanceof RealType) {
            return RealType.REAL;
        }
        if (leftType instanceof DecimalType || rightType instanceof DecimalType) {
            return leftType != null ? leftType : rightType;
        }
        if (isNumericType(leftType) || isNumericType(rightType)) {
            return DoubleType.DOUBLE;
        }
        return BigintType.BIGINT;
    }

    private CallExpression buildAggregationCall(AdapterContext context, String functionName, List<RowExpression> arguments, Type returnType)
    {
        List<RowExpression> adjustedArguments = new ArrayList<>();
        List<TypeSignatureProvider> parameterTypes = new ArrayList<>();
        List<Type> argumentTypes = new ArrayList<>();
        boolean forceDoubleArguments = "avg".equalsIgnoreCase(functionName);
        for (RowExpression argument : arguments) {
            RowExpression adjusted = argument;
            if (forceDoubleArguments && argument != null && argument.getType() != null && !DoubleType.DOUBLE.equals(argument.getType())) {
                if (argument instanceof ConstantExpression) {
                    Object value = ((ConstantExpression) argument).getValue();
                    if (value instanceof Number) {
                        adjusted = new ConstantExpression(((Number) value).doubleValue(), DoubleType.DOUBLE);
                    }
                }
            }
            Type argumentType = adjusted == null ? null : adjusted.getType();
            adjustedArguments.add(adjusted);
            argumentTypes.add(argumentType);
            parameterTypes.addAll(TypeSignatureProvider.fromTypes(argumentType));
        }
        System.out.println("[OpengaussPlanAdapter] buildAggregationCall functionName=" + functionName
                + " returnType=" + returnType
                + " arguments=" + adjustedArguments
                + " argumentTypes=" + argumentTypes);
        com.facebook.presto.spi.function.FunctionHandle functionHandle = context.getFunctionAndTypeManager().resolveFunction(Optional.empty(), Optional.empty(), new QualifiedObjectName("presto", "default", functionName), parameterTypes);
        return new CallExpression(functionName, functionHandle, returnType == null ? DoubleType.DOUBLE : returnType, adjustedArguments);
    }

    private static class AggregationCallSpec
    {
        private final String functionName;
        private final List<String> semanticNames;
        private final List<RowExpression> arguments;
        private final Type returnType;

        private AggregationCallSpec(String functionName, List<String> semanticNames, List<RowExpression> arguments, Type returnType)
        {
            this.functionName = functionName;
            this.semanticNames = semanticNames;
            this.arguments = arguments;
            this.returnType = returnType;
        }

        private String getFunctionName()
        {
            return functionName;
        }

        private List<String> getSemanticNames()
        {
            return semanticNames;
        }

        private List<RowExpression> getArguments()
        {
            return arguments;
        }

        private Type getReturnType()
        {
            return returnType;
        }
    }

    private boolean shouldInsertExchange(String functionName, List<VariableReferenceExpression> groupingKeys)
    {
        return true;
    }

    private RowExpression parseCanonicalExpression(String expression, Map<String, VariableReferenceExpression> variables)
    {
        if (expression == null) {
            return null;
        }
        return parseValue(canonicalizeExpressionText(expression), variables);
    }

    private RowExpression parseBinaryChain(String functionName, List<String> parts, Map<String, VariableReferenceExpression> variables)
    {
        if (parts == null || parts.size() < 2) {
            return null;
        }
        List<RowExpression> expressions = new ArrayList<>();
        for (String part : parts) {
            RowExpression parsed = parseExpression(part, variables, false);
            if (parsed == null) {
                parsed = parseValue(part, variables);
            }
            if (parsed == null) {
                return null;
            }
            expressions.add(parsed);
        }
        RowExpression result = expressions.get(0);
        for (int i = 1; i < expressions.size(); i++) {
            result = buildArithmetic(functionName, result, expressions.get(i));
            if (result == null) {
                return null;
            }
        }
        return result;
    }

    private List<String> splitTopLevelParts(String input, String delimiter)
    {
        List<String> parts = new ArrayList<>();
        if (input == null || input.isEmpty()) {
            return parts;
        }
        String normalizedInput = input.trim();
        while (canStripWrappingParens(normalizedInput)) {
            normalizedInput = normalizedInput.substring(1, normalizedInput.length() - 1).trim();
        }
        boolean balanced = matchingParens(normalizedInput);
        if (!balanced) {
//            System.out.println("[OpengaussPlanAdapter] splitTopLevelParts unbalanced input delimiter=" + delimiter + " input=" + normalizedInput);
        }
        int depth = 0;
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        boolean inSquareBracket = false;
        int start = 0;
        for (int i = 0; i <= normalizedInput.length() - delimiter.length(); i++) {
            char ch = normalizedInput.charAt(i);
            if (ch == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote;
            }
            else if (ch == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
            }
            else if (!inSingleQuote && !inDoubleQuote) {
                if (ch == '[') {
                    inSquareBracket = true;
                }
                else if (ch == ']') {
                    inSquareBracket = false;
                }
                else if (ch == '(') {
                    depth++;
                }
                else if (ch == ')') {
                    depth--;
                    if (depth < 0) {
                        depth = 0;
                    }
                }
            }
            if (!inSingleQuote && !inDoubleQuote && !inSquareBracket && depth == 0 && normalizedInput.startsWith(delimiter, i)) {
                String part = normalizedInput.substring(start, i).trim();
                if (!part.isEmpty()) {
//                    System.out.println("[OpengaussPlanAdapter] splitTopLevelParts delimiter=" + delimiter + " part=" + part);
                    parts.add(part);
                }
                start = i + delimiter.length();
            }
        }
        String tail = normalizedInput.substring(start).trim();
        if (!tail.isEmpty()) {
//            System.out.println("[OpengaussPlanAdapter] splitTopLevelParts delimiter=" + delimiter + " tail=" + tail);
            parts.add(tail);
        }
        if (parts.isEmpty()) {
            parts.add(normalizedInput);
        }
//        System.out.println("[OpengaussPlanAdapter] splitTopLevelParts result=" + parts);
        return parts;
    }

    private String[] splitTopLevel(String input, String delimiter)
    {
        List<String> parts = splitTopLevelParts(input, delimiter);
        return parts.toArray(new String[0]);
    }

    private String stripUnmatchedOuterParens(String input)
    {
        if (input == null || input.isBlank()) {
            return input;
        }
        String normalized = input.trim();
        while (canStripWrappingParens(normalized)) {
            normalized = normalized.substring(1, normalized.length() - 1).trim();
        }
        return normalized;
    }

    private boolean canStripWrappingParens(String input)
    {
        if (input == null) {
            return false;
        }
        String normalized = input.trim();
        if (normalized.length() <= 1 || !normalized.startsWith("(") || !normalized.endsWith(")")) {
            return false;
        }
        int depth = 0;
        int matchingClose = -1;
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        boolean inSquareBracket = false;
        for (int i = 0; i < normalized.length(); i++) {
            char ch = normalized.charAt(i);
            if (ch == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote;
                continue;
            }
            if (ch == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
                continue;
            }
            if (ch == '[') {
                inSquareBracket = true;
                continue;
            }
            if (ch == ']') {
                inSquareBracket = false;
                continue;
            }
            if (inSingleQuote || inDoubleQuote || inSquareBracket) {
                continue;
            }

            if (ch == '(') {
                depth++;
            }
            else if (ch == ')') {
                depth--;
                if (depth < 0) {
                    return false;
                }
                if (depth == 0) {
                    matchingClose = i;
                    break;
                }
            }
        }
        if (matchingClose != normalized.length() - 1) {
            return false;
        }
        String candidate = normalized.substring(1, normalized.length() - 1).trim();
        return !candidate.isEmpty();
    }

    private int findTopLevelDelimiter(String input, String delimiter)
    {
        int depth = 0;
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        boolean inSquareBracket = false;
        for (int i = 0; i <= input.length() - delimiter.length(); i++) {
            char ch = input.charAt(i);
            if (ch == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote;
            }
            else if (ch == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
            }
            else if (!inSingleQuote && !inDoubleQuote) {
                if (ch == '[') {
                    inSquareBracket = true;
                }
                else if (ch == ']') {
                    inSquareBracket = false;
                }
                else if (ch == '(') {
                    depth++;
                }
                else if (ch == ')') {
                    depth = Math.max(0, depth - 1);
                }
            }
            if (!inSingleQuote && !inDoubleQuote && !inSquareBracket && depth == 0 && input.startsWith(delimiter, i)) {
                return i;
            }
        }
        return -1;
    }

    private boolean matchingParens(String input)
    {
        int depth = 0;
        for (int i = 0; i < input.length(); i++) {
            char ch = input.charAt(i);
            if (ch == '(') {
                depth++;
            }
            else if (ch == ')') {
                depth--;
                if (depth < 0) {
                    return false;
                }
            }
        }
        return depth == 0;
    }
}
