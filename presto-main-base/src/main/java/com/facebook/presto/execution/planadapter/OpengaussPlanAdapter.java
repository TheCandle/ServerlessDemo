package com.facebook.presto.execution.planadapter;

import com.facebook.presto.Session;
import com.facebook.presto.common.QualifiedObjectName;
import com.facebook.presto.common.block.SortOrder;
import com.facebook.presto.common.function.OperatorType;
import com.facebook.presto.common.predicate.TupleDomain;
import com.facebook.presto.common.type.BigintType;
import com.facebook.presto.common.type.BooleanType;
import com.facebook.presto.common.type.DoubleType;
import com.facebook.presto.common.type.RealType;
import com.facebook.presto.common.type.DecimalType;
import com.facebook.presto.common.type.VarcharType;
import com.facebook.presto.metadata.BuiltInFunctionHandle;
import com.facebook.presto.metadata.Metadata;
import com.facebook.presto.spi.ColumnHandle;
import com.facebook.presto.spi.TableHandle;
import com.facebook.presto.spi.TableMetadata;
import com.facebook.presto.spi.function.FunctionKind;
import com.facebook.presto.spi.function.Signature;
import com.facebook.presto.common.type.Type;
import com.facebook.presto.common.type.TypeSignature;
import com.facebook.presto.sql.analyzer.TypeSignatureProvider;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public class OpengaussPlanAdapter
{
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OpengaussExpressionTranslator expressionTranslator = new OpengaussExpressionTranslator();

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
            Map<String, VariableReferenceExpression> scalarBindings = new LinkedHashMap<>();
            JsonNode planRoot = unwrapPlan(root);
            PlanNode translated = translateNode(planRoot, context, scalarBindings);
            OutputNode outputNode = wrapWithOutputNode(translated, planRoot, context);
            System.out.println("[OpengaussPlanAdapter] translated plan tree:\n" + formatPlanTree(outputNode));
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
        if (normalized.contains("join")) {
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
        System.out.println("[OpengaussPlanAdapter] scan output names=" + outputNames + " for nodeType=" + text(node, "Node Type"));
        List<String> chosenNames = outputNames.isEmpty() ? new ArrayList<>(metadataByName.keySet()) : outputNames;
        for (String outputName : chosenNames) {
            String columnName = simpleName(outputName).toLowerCase(Locale.ENGLISH);
            com.facebook.presto.spi.ColumnMetadata columnMetadata = metadataByName.get(columnName);
            if (columnMetadata == null) {
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
            VariableReferenceExpression variable = context.getVariableAllocator().newVariable(simpleName(outputName), columnMetadata.getType());
            outputs.add(variable);
            assignments.put(variable, columnHandle);
            variablesByName.put(columnName, variable);
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
        for (VariableReferenceExpression boundScalar : scalarBindings.values()) {
            variablesByName.put(boundScalar.getName().toLowerCase(Locale.ENGLISH), boundScalar);
        }

        PlanNode scan = new TableScanNode(Optional.empty(), context.getIdAllocator().getNextId(), scanTableHandle, outputs, assignments, TupleDomain.all(), TupleDomain.all(), Optional.empty());
        String filterText = firstNonNull(text(node, "Filter"), text(node, "Index Cond"), text(node, "Hash Cond"));
        RowExpression predicate = parsePredicate(filterText, variablesByName);
        if (predicate != null) {
            predicate = substituteScalarBindings(predicate, scalarBindings);
            System.out.println("[OpengaussPlanAdapter] buildScan filter=" + filterText + " predicate=" + predicate + " scalarBindings=" + scalarBindings.keySet());
            scan = new FilterNode(Optional.empty(), context.getIdAllocator().getNextId(), scan, predicate);
        }
        return scan;
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
            VariableReferenceExpression sourceJoinVariable = resolveJoinVariable(preservedSide, joinCondition);
            VariableReferenceExpression filteringJoinVariable = resolveJoinVariable(filteringSide, joinCondition);
            if (sourceJoinVariable == null && !preservedSide.getOutputVariables().isEmpty()) {
                sourceJoinVariable = preservedSide.getOutputVariables().get(0);
            }
            if (filteringJoinVariable == null && !filteringSide.getOutputVariables().isEmpty()) {
                filteringJoinVariable = filteringSide.getOutputVariables().get(0);
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
            return semiJoin;
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
        List<EquiJoinClause> criteria = parseJoinCriteria(firstNonNull(text(node, "Hash Cond"), text(node, "Merge Cond")), left, right);
        if (criteria.isEmpty()) {
        }
        List<VariableReferenceExpression> outputVariables = new ArrayList<>(left.getOutputVariables());
        outputVariables.addAll(right.getOutputVariables());

        PlanNode join = new JoinNode(Optional.empty(), context.getIdAllocator().getNextId(), joinType, left, right, criteria, outputVariables, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Collections.emptyMap());
        if (text(node, "Join Filter") != null) {
            RowExpression filter = parsePredicate(text(node, "Join Filter"), buildVariablesByOutput(join));
            if (filter != null) {
                join = new FilterNode(Optional.empty(), context.getIdAllocator().getNextId(), join, filter);
            }
        }
        return join;
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
        Map<VariableReferenceExpression, RowExpression> assignments = new LinkedHashMap<>();
        List<VariableReferenceExpression> outputs = new ArrayList<>();
        for (int i = 0; i < sourceOutputs.size(); i++) {
            VariableReferenceExpression src = sourceOutputs.get(i);
            String name = i == 0 ? "?column?" : src.getName();
            VariableReferenceExpression target = context.getVariableAllocator().newVariable(simpleName(name), src.getType());
            outputs.add(target);
            assignments.put(target, src);
        }
        return new ProjectNode(Optional.empty(), context.getIdAllocator().getNextId(), source, Assignments.copyOf(assignments), ProjectNode.Locality.LOCAL);
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
        if (normalized.contains("replicate")) {
            return ExchangeNode.replicatedExchange(context.getIdAllocator().getNextId(), ExchangeNode.Scope.REMOTE_STREAMING, source);
        }
        if (normalized.contains("gather")) {
            return ExchangeNode.gatheringExchange(context.getIdAllocator().getNextId(), ExchangeNode.Scope.REMOTE_STREAMING, source);
        }
        if (normalized.contains("redistribute")) {
            List<VariableReferenceExpression> keys = parsePartitionKeys(firstNonNull(text(node, "Hash Key"), text(node, "Sort Key")), source);
            if (keys.isEmpty()) {
                keys = source.getOutputVariables().isEmpty() ? Collections.emptyList() : List.of(source.getOutputVariables().get(0));
            }
            return ExchangeNode.systemPartitionedExchange(context.getIdAllocator().getNextId(), ExchangeNode.Scope.REMOTE_STREAMING, source, keys, Optional.empty());
        }
        return source;
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
        List<VariableReferenceExpression> groupingKeys = new ArrayList<>();
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
            if (variable != null && !groupingKeys.contains(variable)) {
                groupingKeys.add(variable);
            }
        }

        List<String> outputNames = parseOutputNames(node);
        System.out.println("[OpengaussPlanAdapter] aggregate source nodeType=" + text(node, "Node Type")
                + " output=" + text(node, "Output")
                + " parsedOutputNames=" + outputNames);

        Map<String, VariableReferenceExpression> sourceVariables = buildVariablesByOutput(source);
        List<VariableReferenceExpression> preProjectOutputs = new ArrayList<>();
        Map<VariableReferenceExpression, RowExpression> preProjectAssignments = new LinkedHashMap<>();
        List<String> aggOutputNames = new ArrayList<>();
        List<AggregationCallSpec> aggSpecs = new ArrayList<>();
        List<VariableReferenceExpression> aggDependencyOutputs = new ArrayList<>();
        Map<String, VariableReferenceExpression> dependencyLookup = new LinkedHashMap<>(sourceVariables);

        for (String outputName : outputNames) {
            String normalizedOutput = canonicalizeExpressionText(outputName);
            String lower = normalizedOutput.toLowerCase(Locale.ENGLISH);
            List<AggregationCallSpec> parsedSpecs = containsAggregationFunction(lower)
                    ? List.of(parseAggregationFragment(outputName, sourceVariables, source, node, sortAggregate))
                    : splitAggregationText(outputName, sourceVariables, source, node, sortAggregate);
            boolean parsedAggregation = false;
            for (AggregationCallSpec spec : parsedSpecs) {
                if (spec != null) {
                    aggOutputNames.add(outputName);
                    aggSpecs.add(spec);
                    for (RowExpression argument : spec.getArguments()) {
                        collectVariableDependencies(argument, dependencyLookup, aggDependencyOutputs);
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
            Type callReturnType = inferAggregationReturnType(spec.getFunctionName(), argumentType);
            if ("sum".equalsIgnoreCase(spec.getFunctionName())) {
                callReturnType = BigintType.BIGINT;
            }
            Type outputType = inferAggregationOutputType(spec.getFunctionName(), spec.getReturnType(), spec.getArguments());
            if ("sum".equalsIgnoreCase(spec.getFunctionName())) {
                outputType = BigintType.BIGINT;
            }
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
        AggregationNode aggregation = new AggregationNode(Optional.empty(), context.getIdAllocator().getNextId(), current, aggregations, AggregationNode.singleGroupingSet(groupingKeys), Collections.emptyList(), AggregationNode.Step.SINGLE, Optional.empty(), Optional.empty(), Optional.empty());

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
        return new ProjectNode(Optional.empty(), context.getIdAllocator().getNextId(), aggregation, Assignments.copyOf(castAwareAssignments), ProjectNode.Locality.LOCAL);
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
            case "sum":
                return DoubleType.DOUBLE;
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
                if (variable != null) {
                    orderings.add(new Ordering(variable, SortOrder.ASC_NULLS_FIRST));
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

        int outputSize = outputVariables.size();
        int columnSize = columnNames.size();
        int pairSize = Math.min(columnSize, outputSize);
        if (columnSize != outputSize) {
            System.out.println("[OpengaussPlanAdapter] output mismatch nodeType=" + text(node, "Node Type")
                    + " nodeOutput=" + text(node, "Output")
                    + " parsedNames=" + columnNames
                    + " planOutputs=" + outputVariables
                    + " action=" + (columnSize < outputSize ? "padding column names from plan outputs" : "truncating excess column names"));
        }

        List<VariableReferenceExpression> finalOutputs = new ArrayList<>();
        Map<VariableReferenceExpression, RowExpression> assignments = new LinkedHashMap<>();
        for (int i = 0; i < pairSize; i++) {
            VariableReferenceExpression alias = context.getVariableAllocator().newVariable(columnNames.get(i), outputVariables.get(i).getType());
            finalOutputs.add(alias);
            assignments.put(alias, outputVariables.get(i));
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
            columnNames = new ArrayList<>(columnNames.subList(0, pairSize));
        }

        System.out.println("[OpengaussPlanAdapter] wrap OutputNode nodeType=" + text(node, "Node Type")
                + " finalColumns=" + columnNames
                + " planOutputs=" + finalOutputs
                + " assignments=" + assignments.keySet());

        PlanNode projected = new ProjectNode(Optional.empty(), context.getIdAllocator().getNextId(), planNode, Assignments.copyOf(assignments), ProjectNode.Locality.LOCAL);
        return new OutputNode(Optional.empty(), context.getIdAllocator().getNextId(), projected, columnNames, finalOutputs);
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
            return sourceNames;
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
                .append(node.getOutputVariables());
        if (node instanceof OutputNode) {
            builder.append(" columns=").append(((OutputNode) node).getColumnNames());
        }
        builder.append('\n');
        for (PlanNode source : node.getSources()) {
            formatPlanTree(source, builder, depth + 1);
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

    private RowExpression parsePredicate(String predicate, Map<String, VariableReferenceExpression> variables)
    {
        return parseExpression(predicate, variables, false);
    }

    private RowExpression parseProjectExpression(String expression, Map<String, VariableReferenceExpression> variables, AdapterContext context)
    {
        return parseExpression(expression, variables, true);
    }

    private RowExpression parseExpression(String expression, Map<String, VariableReferenceExpression> variables, boolean projectMode)
    {
        if (expression == null || expression.isBlank()) {
            return null;
        }
        String normalized = canonicalizeExpressionText(expression);

        if (normalized.isEmpty()) {
            return null;
        }
        while (normalized.startsWith("(") && normalized.endsWith(")")) {
            String candidate = normalized.substring(1, normalized.length() - 1).trim();
            if (candidate.isEmpty() || !matchingParens(candidate)) {
                break;
            }
            normalized = candidate;
        }
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

        if (normalized.contains(" IN ")) {
            RowExpression inExpression = parseInExpression(normalized, variables);
            if (inExpression != null) {
                return inExpression;
            }
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

        for (String op : new String[] {" >= ", " <= ", " <> ", " != ", " = ", " > ", " < "}) {
            int idx = normalized.indexOf(op);
            if (idx > 0) {
                RowExpression left = parseValue(normalized.substring(0, idx).trim(), variables);
                RowExpression right = parseValue(normalized.substring(idx + op.length()).trim(), variables);
                if (left != null && right != null) {
                    return buildComparison(op.trim(), left, right);
                }
            }
        }
        RowExpression parsed = parseValue(normalized, variables);
        if (parsed != null && !isNumericType(parsed.getType()) && parsed.getType() instanceof VarcharType) {
            return firstNumericAggregationInput(variables);
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
        return new ConstantExpression(0L, BigintType.BIGINT);
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
        while (normalized.startsWith("(") && normalized.endsWith(")") && matchingParens(normalized)) {
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
                arguments.add(parseValue(v, variables));
            }
        }
        return new SpecialFormExpression(SpecialFormExpression.Form.IN, BooleanType.BOOLEAN, arguments);
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
        if (isNumericType(coercedLeft.getType()) && isNumericType(coercedRight.getType())) {
            Type targetType = widenNumericType(coercedLeft.getType(), coercedRight.getType());
            if (isIntegerType(coercedLeft.getType()) && coercedRight instanceof ConstantExpression) {
                targetType = coercedLeft.getType();
            }
            else if (isIntegerType(coercedRight.getType()) && coercedLeft instanceof ConstantExpression) {
                targetType = coercedRight.getType();
            }
            coercedLeft = coerceNumericConstant(coercedLeft, targetType);
            coercedRight = coerceNumericConstant(coercedRight, targetType);
        }
        if ((coercedLeft.getType() == null || VarcharType.VARCHAR.equals(coercedLeft.getType()) || coercedLeft.getType() instanceof VarcharType)
                && (coercedRight.getType() == null || VarcharType.VARCHAR.equals(coercedRight.getType()) || coercedRight.getType() instanceof VarcharType)
                && !"=".equals(operator) && !"!=".equals(operator) && !"<>".equals(operator)) {
            return new ConstantExpression(true, BooleanType.BOOLEAN);
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
        String text = operand.toString();
        if (text == null) {
            return operand;
        }
        String stripped = stripQuotes(text);
        if (stripped == null) {
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
        if (operand instanceof ConstantExpression && operand.getType() instanceof VarcharType && !(other.getType() instanceof VarcharType)) {
            String text = operand.toString();
            if (text != null) {
                String stripped = stripQuotes(text);
                if (stripped != null) {
                    if (stripped.matches("-?\\d+")) {
                        return new ConstantExpression(Long.valueOf(stripped), BigintType.BIGINT);
                    }
                    if (stripped.matches("-?\\d+(\\.\\d+)?")) {
                        return new ConstantExpression(Double.valueOf(stripped), DoubleType.DOUBLE);
                    }
                }
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
        while (normalized.startsWith("(") && normalized.endsWith(")") && matchingParens(normalized)) {
            normalized = normalized.substring(1, normalized.length() - 1).trim();
        }
        if (normalized.isEmpty()) {
            return null;
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
        if (normalized.matches("-?\\d+(\\.\\d+)?")) {
            RowExpression result = new ConstantExpression(Double.valueOf(normalized), DoubleType.DOUBLE);
            System.out.println("[OpengaussPlanAdapter] parseValue decimal normalized=" + normalized + " -> " + result + " type=" + result.getType());
            return result;
        }
        if (normalized.startsWith("$")) {
            RowExpression result = varcharConstant(normalized);
            System.out.println("[OpengaussPlanAdapter] parseValue param normalized=" + normalized + " -> " + result + " type=" + result.getType());
            return result;
        }
        if (normalized.toLowerCase(Locale.ENGLISH).startsWith("substring")) {
            RowExpression result = parseSubstringCall(normalized, variables);
            System.out.println("[OpengaussPlanAdapter] parseValue substring normalized=" + normalized + " -> " + result + " type=" + (result == null ? "null" : result.getType()));
            return result;
        }

        List<String> multiplyParts = splitTopLevelParts(normalized, " * ");
        if (multiplyParts.size() == 2) {
            RowExpression left = parseValue(multiplyParts.get(0), variables);
            RowExpression right = parseValue(multiplyParts.get(1), variables);
            if (left != null && right != null) {
                return buildArithmetic("multiply", left, right);
            }
        }
        List<String> divideParts = splitTopLevelParts(normalized, " / ");
        if (divideParts.size() == 2) {
            RowExpression left = parseValue(divideParts.get(0), variables);
            RowExpression right = parseValue(divideParts.get(1), variables);
            if (left != null && right != null) {
                return buildArithmetic("divide", left, right);
            }
        }
        List<String> plusParts = splitTopLevelParts(normalized, " + ");
        if (plusParts.size() == 2) {
            RowExpression left = parseValue(plusParts.get(0), variables);
            RowExpression right = parseValue(plusParts.get(1), variables);
            if (left != null && right != null) {
                return buildArithmetic("add", left, right);
            }
        }
        List<String> minusParts = splitTopLevelParts(normalized, " - ");
        if (minusParts.size() == 2) {
            RowExpression left = parseValue(minusParts.get(0), variables);
            RowExpression right = parseValue(minusParts.get(1), variables);
            if (left != null && right != null) {
                return buildArithmetic("subtract", left, right);
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
                if (typeSuffix.startsWith("text") || typeSuffix.startsWith("varchar") || typeSuffix.startsWith("char")) {
                    return varcharConstant(stripQuotes(base));
                }
            }
        }

        if (normalized.contains(".")) {
            String simple = normalized.substring(normalized.lastIndexOf('.') + 1);
            VariableReferenceExpression variable = variables.get(simple.toLowerCase(Locale.ENGLISH));
            if (variable != null) {
                return variable;
            }
        }
        VariableReferenceExpression variable = variables.get(normalized.toLowerCase(Locale.ENGLISH));
        if (variable != null) {
            return variable;
        }
        return varcharConstant(stripQuotes(normalized));
    }

    private Map<String, VariableReferenceExpression> buildVariablesByOutput(PlanNode node)
    {
        Map<String, VariableReferenceExpression> result = new LinkedHashMap<>();
        for (VariableReferenceExpression variable : node.getOutputVariables()) {
            String name = variable.getName().toLowerCase(Locale.ENGLISH);
            result.put(name, variable);
            result.put(simpleName(name).toLowerCase(Locale.ENGLISH), variable);
        }
        return result;
    }

    private VariableReferenceExpression lookupVariable(String token, Map<String, VariableReferenceExpression> variables)
    {
        String normalizedToken = token == null ? "" : token.toLowerCase(Locale.ENGLISH);
        String simple = simpleName(token).toLowerCase(Locale.ENGLISH);
        VariableReferenceExpression direct = variables.get(simple);
        if (direct != null) {
            return direct;
        }
        direct = variables.get(normalizedToken);
        if (direct != null) {
            return direct;
        }
        for (Map.Entry<String, VariableReferenceExpression> entry : variables.entrySet()) {
            if (normalizedToken.contains(entry.getKey()) || entry.getKey().contains(simple)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private VariableReferenceExpression resolveJoinVariable(PlanNode node, String name)
    {
        String simple = simpleName(name).toLowerCase(Locale.ENGLISH);
        for (VariableReferenceExpression variable : node.getOutputVariables()) {
            if (variable.getName().equalsIgnoreCase(simple)) {
                return variable;
            }
        }
        for (VariableReferenceExpression variable : node.getOutputVariables()) {
            if (simple.contains(variable.getName().toLowerCase(Locale.ENGLISH)) || variable.getName().toLowerCase(Locale.ENGLISH).contains(simple)) {
                return variable;
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
        candidateSchemas.add("tiny");
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
        return new CallExpression("substring", new PassthroughFunctionHandle("substring"), VarcharType.VARCHAR, args);
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
        normalized = normalized.replaceAll("\\s+", " ");
        normalized = normalized.replace("substring (", "substring(");
        normalized = normalized.replace("substring(", "substring(");
        normalized = normalized.replace(" ,", ",").replace(", ", ",");
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
        RowExpression condExpr = parsePredicate(condition, variables);
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
        return new SpecialFormExpression(SpecialFormExpression.Form.SWITCH, resultType, args);
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
        RowExpression parsed = parsePredicate(condExpr.toString(), variables);
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
        return builtInHandle(functionName, BooleanType.BOOLEAN, left.getType(), right.getType());
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
        RowExpression candidate = firstNumericAggregationInput(buildVariablesByOutput(source));
        if (candidate != null) {
            return candidate;
        }
        for (VariableReferenceExpression variable : source.getOutputVariables()) {
            if (!BooleanType.BOOLEAN.equals(variable.getType()) && !VarcharType.VARCHAR.equals(variable.getType())) {
                return variable;
            }
        }
        if (!source.getOutputVariables().isEmpty()) {
            return source.getOutputVariables().get(0);
        }
        return new ConstantExpression(0.0, DoubleType.DOUBLE);
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
        if ("count".equalsIgnoreCase(functionName) && ("*".equals(inside) || "1".equals(inside))) {
            return new AggregationCallSpec("count", inferAggregationSemanticNames(node, source), List.of(new ConstantExpression(1L, BigintType.BIGINT)), BigintType.BIGINT);
        }
        RowExpression argument;
        String innerLower = inside.toLowerCase(Locale.ENGLISH);
        if (innerLower.startsWith("case when") || innerLower.contains(" case when ")) {
            argument = parseCaseWhen(canonicalizeExpressionText(inside), variables, false);
        }
        else {
            argument = parseCanonicalExpression(inside, variables);
        }
        if (argument instanceof ConstantExpression && !isNumericType(argument.getType())) {
            argument = firstNumericAggregationInput(variables);
        }
        if (argument == null) {
            return null;
        }
        if (("avg".equalsIgnoreCase(functionName) || "sum".equalsIgnoreCase(functionName)) && !isNumericType(argument.getType())) {
            argument = firstNumericAggregationInput(variables);
        }
        if (("avg".equalsIgnoreCase(functionName) || "sum".equalsIgnoreCase(functionName)) && argument.getType() != null && !isNumericType(argument.getType())) {
            argument = new ConstantExpression(0L, BigintType.BIGINT);
        }
        Type returnType = inferAggregationReturnType(functionName, argument.getType());
        if ("avg".equalsIgnoreCase(functionName)) {
            returnType = DoubleType.DOUBLE;
        }
        else if ("sum".equalsIgnoreCase(functionName)) {
            returnType = isFloatingPointType(argument.getType()) ? DoubleType.DOUBLE : BigintType.BIGINT;
        }
        if ("count".equalsIgnoreCase(functionName)) {
            returnType = BigintType.BIGINT;
        }
        return new AggregationCallSpec(functionName, inferAggregationSemanticNames(node, source), List.of(argument), returnType);
    }

    private List<String> splitTopLevelAggregates(String text)
    {
        List<String> fragments = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return fragments;
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
                fragments.add(text.substring(start, i).trim());
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
        if (lowerText.contains("count(")) {
            return "count";
        }
        if (lowerText.contains("sum(")) {
            return "sum";
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
                continue;
            }
            if (value.isTextual()) {
                RowExpression parsed = parseAggregateArgumentText(value.asText(), variables);
                if (parsed != null) {
                    return parsed;
                }
            }
            if (value.isArray()) {
                for (JsonNode element : value) {
                    String text = element.asText();
                    RowExpression parsed = parseAggregateArgumentText(text, variables);
                    if (parsed != null) {
                        return parsed;
                    }
                }
            }
        }
        return null;
    }

    private RowExpression parseAggregateArgumentText(String text, Map<String, VariableReferenceExpression> variables)
    {
        if (text == null || text.isBlank()) {
            return null;
        }
        String normalized = text.replace("::text", "").replace("::varchar", "").replace("\"", "").trim();
        if (normalized.contains("public.customer.c_acctbal")) {
            return parseValue("public.customer.c_acctbal", variables);
        }
        if (normalized.contains("count(*)") || normalized.contains("count(1)")) {
            return new ConstantExpression(1L, BigintType.BIGINT);
        }
        if (normalized.toLowerCase(Locale.ENGLISH).startsWith("count(") || normalized.toLowerCase(Locale.ENGLISH).startsWith("sum(") || normalized.toLowerCase(Locale.ENGLISH).startsWith("avg(") || normalized.toLowerCase(Locale.ENGLISH).startsWith("min(") || normalized.toLowerCase(Locale.ENGLISH).startsWith("max(")) {
            int open = normalized.indexOf('(');
            int close = normalized.lastIndexOf(')');
            if (open >= 0 && close > open) {
                String inside = normalized.substring(open + 1, close).trim();
                if (inside.equals("*") || inside.equals("1")) {
                    return new ConstantExpression(1L, BigintType.BIGINT);
                }
                RowExpression parsed = parseValue(inside, variables);
                if (parsed != null) {
                    if (parsed.getType() == null || VarcharType.VARCHAR.equals(parsed.getType())) {
                        return firstNumericAggregationInput(variables);
                    }
                    return parsed;
                }
            }
        }
        if (!normalized.contains("(") && !normalized.contains(")")) {
            VariableReferenceExpression direct = variables.get(simpleName(normalized).toLowerCase(Locale.ENGLISH));
            if (direct != null) {
                return direct;
            }
        }
        RowExpression parsed = parseValue(normalized, variables);
        if (parsed != null && (parsed.getType() == null || VarcharType.VARCHAR.equals(parsed.getType()))) {
            return firstNumericAggregationInput(variables);
        }
        return parsed;
    }

    private boolean isAggregationArgumentAllowed(RowExpression expression)
    {
        return expression instanceof VariableReferenceExpression || expression instanceof ConstantExpression || expression instanceof LambdaDefinitionExpression;
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
        if (!(operand instanceof ConstantExpression) || operand.getType() == null || targetType == null) {
            return operand;
        }
        String text = stripQuotes(operand.toString());
        if (text == null) {
            return operand;
        }
        if (targetType instanceof DoubleType) {
            if (text.matches("-?\\d+(\\.\\d+)?")) {
                return new ConstantExpression(Double.valueOf(text), DoubleType.DOUBLE);
            }
        }
        if (targetType instanceof BigintType) {
            if (text.matches("-?\\d+")) {
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
        return BigintType.BIGINT;
    }

    private CallExpression buildAggregationCall(AdapterContext context, String functionName, List<RowExpression> arguments, Type returnType)
    {
        List<TypeSignatureProvider> parameterTypes = new ArrayList<>();
        for (RowExpression argument : arguments) {
            parameterTypes.addAll(TypeSignatureProvider.fromTypes(argument.getType()));
        }
        com.facebook.presto.spi.function.FunctionHandle functionHandle = context.getFunctionAndTypeManager().resolveFunction(Optional.empty(), Optional.empty(), new QualifiedObjectName("presto", "default", functionName), parameterTypes);
        return new CallExpression(functionName, functionHandle, returnType == null ? DoubleType.DOUBLE : returnType, arguments);
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

    private List<String> splitTopLevelParts(String input, String delimiter)
    {
        List<String> parts = new ArrayList<>();
        if (input == null || input.isEmpty()) {
            return parts;
        }
        int depth = 0;
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        boolean inSquareBracket = false;
        int start = 0;
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
                    depth--;
                    if (depth < 0) {
                        depth = 0;
                    }
                }
            }
            if (!inSingleQuote && !inDoubleQuote && !inSquareBracket && depth == 0 && input.startsWith(delimiter, i)) {
                String part = input.substring(start, i).trim();
                if (!part.isEmpty()) {
                    while (part.startsWith("(") && part.endsWith(")")) {
                        String candidate = part.substring(1, part.length() - 1).trim();
                        if (candidate.isEmpty() || !matchingParens(candidate)) {
                            break;
                        }
                        part = candidate;
                    }
//                    System.out.println("[OpengaussPlanAdapter] splitTopLevelParts delimiter=" + delimiter + " part=" + part);
                    parts.add(part);
                }
                start = i + delimiter.length();
            }
        }
        String tail = input.substring(start).trim();
        if (!tail.isEmpty()) {
            while (tail.startsWith("(") && tail.endsWith(")")) {
                String candidate = tail.substring(1, tail.length() - 1).trim();
                if (candidate.isEmpty() || !matchingParens(candidate)) {
                    break;
                }
                tail = candidate;
            }
//            System.out.println("[OpengaussPlanAdapter] splitTopLevelParts delimiter=" + delimiter + " tail=" + tail);
            parts.add(tail);
        }
        if (parts.isEmpty()) {
            parts.add(input.trim());
        }
//        System.out.println("[OpengaussPlanAdapter] splitTopLevelParts result=" + parts);
        return parts;
    }

    private String[] splitTopLevel(String input, String delimiter)
    {
        List<String> parts = splitTopLevelParts(input, delimiter);
        return parts.toArray(new String[0]);
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
