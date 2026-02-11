package com.facebook.presto.execution;

import com.facebook.presto.Session;
import com.facebook.presto.common.QualifiedObjectName;
import com.facebook.presto.common.block.SortOrder;
import com.facebook.presto.common.function.OperatorType;
import com.facebook.presto.common.predicate.TupleDomain;
import com.facebook.presto.common.type.*;
import com.facebook.presto.metadata.Metadata;
import com.facebook.presto.spi.ColumnHandle;
import com.facebook.presto.spi.TableHandle;
import com.facebook.presto.spi.analyzer.AnalyzerContext;
import com.facebook.presto.spi.function.FunctionHandle;
import com.facebook.presto.spi.plan.*;
import com.facebook.presto.spi.relation.CallExpression;
import com.facebook.presto.spi.relation.ConstantExpression;
import com.facebook.presto.spi.relation.RowExpression;
import com.facebook.presto.spi.relation.VariableReferenceExpression;
import com.facebook.presto.sql.ExpressionUtils;
import com.facebook.presto.sql.analyzer.TypeSignatureProvider;
import com.facebook.presto.sql.parser.SqlParser;
import com.facebook.presto.sql.planner.TypeProvider;
import com.facebook.presto.sql.relational.SqlToRowExpressionTranslator;
import com.facebook.presto.sql.tree.Expression;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import java.util.*;
import java.util.stream.Collectors;

import static com.facebook.presto.common.type.BigintType.BIGINT;

public class TpchPlanTree {

    public TpchPlanTree(AnalyzerContext analyzerContext, Metadata metadata, PlanNodeIdAllocator idAllocator, QueryStateMachine stateMachine) {
        this.analyzerContext = analyzerContext;
        this.metadata = metadata;
        this.idAllocator = idAllocator;
        this.stateMachine = stateMachine;
        this.sqlToRowExpressionTranslator = new RowExpressionTranslator(metadata, getSession());
    }

    public Session getSession()
    {
        return stateMachine.getSession();
    }

    private void debugJoinNodeVariables(String joinName, PlanNode left, PlanNode right,
                                        List<VariableReferenceExpression> outputVars) {
        System.out.println("\n=== Debug JoinNode: " + joinName + " ===");

        System.out.println("Left source outputs (" + left.getClass().getSimpleName() + "):");
        List<VariableReferenceExpression> leftVars = left.getOutputVariables();
        for (int i = 0; i < leftVars.size(); i++) {
            VariableReferenceExpression var = leftVars.get(i);
            System.out.println(String.format("  [%d] %s (type: %s, hash: %d)",
                    i, var.getName(), var.getType(), System.identityHashCode(var)));
        }

        System.out.println("\nRight source outputs (" + right.getClass().getSimpleName() + "):");
        List<VariableReferenceExpression> rightVars = right.getOutputVariables();
        for (int i = 0; i < rightVars.size(); i++) {
            VariableReferenceExpression var = rightVars.get(i);
            System.out.println(String.format("  [%d] %s (type: %s, hash: %d)",
                    i, var.getName(), var.getType(), System.identityHashCode(var)));
        }

        System.out.println("\nPlanned join outputs:");
        for (int i = 0; i < outputVars.size(); i++) {
            VariableReferenceExpression var = outputVars.get(i);
            System.out.println(String.format("  [%d] %s (type: %s, hash: %d)",
                    i, var.getName(), var.getType(), System.identityHashCode(var)));
        }
    }

    public static int debug_mode = 1;

    public void DEBUG_PRINT(String str) {
        if(debug_mode == 0) {
            return;
        }
        System.out.println(str);
    }

    public PlanNode getOKQ1() {
        VariableReferenceExpression returnFlag = analyzerContext.getVariableAllocator().newVariable("l_returnflag", VarcharType.VARCHAR);
        VariableReferenceExpression lineStatus  = analyzerContext.getVariableAllocator().newVariable("l_linestatus", VarcharType.VARCHAR);
        VariableReferenceExpression shipDate  = analyzerContext.getVariableAllocator().newVariable("l_shipdate", DateType.DATE);
        VariableReferenceExpression quantity  = analyzerContext.getVariableAllocator().newVariable("l_quantity", DoubleType.DOUBLE);


        // 聚合输出字段
        VariableReferenceExpression sumQty = analyzerContext.getVariableAllocator().newVariable("sum_qty", DoubleType.DOUBLE);

        // 解析 <= 操作符
        FunctionHandle lessThanOrEqual = metadata.getFunctionAndTypeManager().resolveOperator(
                OperatorType.LESS_THAN_OR_EQUAL,
                TypeSignatureProvider.fromTypes(DateType.DATE, DateType.DATE)
        );

        // 构建谓词：shipDate <= 1998-08-04 (时间戳在 Presto 内部通常是 Long 毫秒)
        RowExpression filterPredicate = new CallExpression(
                OperatorType.LESS_THAN_OR_EQUAL.name(),
                lessThanOrEqual,
                BooleanType.BOOLEAN,
                Arrays.asList(
                        shipDate,
                        new ConstantExpression(881280000000L, DateType.DATE) // 示例时间戳数值
                )
        );

        // 1. 定位表 (假设使用 tpch connector)
        QualifiedObjectName tableName = new QualifiedObjectName("tpchstandard", "tiny", "lineitem");
        Optional<TableHandle> tableHandle = metadata.getHandleVersion(
                getSession(),
                tableName,
                Optional.empty() // 这里的类型是 Optional<ConnectorTableVersion>
        );

        if (!tableHandle.isPresent()) {
            throw new RuntimeException("Table not found: " + tableName);
        }

        TableHandle handle = tableHandle.get();
        // 获取该表的所有列句柄
        // 该方法返回一个 Map，Key 是列名 (String)，Value 是列句柄 (ColumnHandle)
        Map<String, ColumnHandle> columnHandles = metadata.getColumnHandles(getSession(), handle);

        System.out.println("Available columns: " + columnHandles.keySet());

        List<VariableReferenceExpression> outputVariables = Arrays.asList(returnFlag, lineStatus, shipDate, quantity);

        // 建立 变量 -> 列句柄 的映射
        ImmutableMap.Builder<VariableReferenceExpression, ColumnHandle> assignments = ImmutableMap.builder();
        assignments.put(returnFlag, columnHandles.get("lineitem.l_returnflag"));
        assignments.put(lineStatus, columnHandles.get("l_linestatus"));
        assignments.put(shipDate, columnHandles.get("l_shipdate"));
        assignments.put(quantity, columnHandles.get("l_quantity"));

        // 4. 创建 TableScanNode
        PlanNode sourceNode = new TableScanNode(
                Optional.empty(),               // 1. sourceLocation
                idAllocator.getNextId(),        // 2. id
                tableHandle.get(),                    // 3. table (你的 TableHandle 对象)
                outputVariables,                // 4. outputVariables
                assignments.build(),            // 5. assignments
                TupleDomain.all(),              // 6. currentConstraint (关键：不要传 null)
                TupleDomain.all(),              // 7. enforcedConstraint (关键：解决报错的核心)
                Optional.empty()                // 8. cteMaterializationInfo (传 Optional.empty())
        );


        PlanNode filterNode = new FilterNode(Optional.empty(), idAllocator.getNextId(), sourceNode, filterPredicate);


        // agg node
        // 1. 定义 Grouping Keys
        List<VariableReferenceExpression> groupingKeys = Arrays.asList(returnFlag, lineStatus);

        // 2. 定义聚合函数 (以 SUM(l_quantity) 为例)
        FunctionHandle sumHandle = metadata.getFunctionAndTypeManager().lookupFunction("sum",
                TypeSignatureProvider.fromTypes(DoubleType.DOUBLE));

        AggregationNode.Aggregation sumAggregation = new AggregationNode.Aggregation(
                new CallExpression("sum", sumHandle, DoubleType.DOUBLE, Collections.singletonList(quantity)),
                Optional.empty(), Optional.empty(), false, Optional.empty()
        );

        Map<VariableReferenceExpression, AggregationNode.Aggregation> aggregations = new HashMap<>();
        aggregations.put(sumQty, sumAggregation);

        AggregationNode.GroupingSetDescriptor groupingSetsDescriptor = new AggregationNode.GroupingSetDescriptor(
                groupingKeys,               // List<VariableReferenceExpression>
                1,                          // groupingSetCount
                ImmutableSet.of()           // globalGroupingSets
        );

        AggregationNode aggregationNode = new AggregationNode(
                Optional.empty(),                // sourceLocation (Optional<SourceLocation>)
                idAllocator.getNextId(),         // id (PlanNodeId)
                filterNode,                      // source (PlanNode)
                aggregations,                    // aggregations (Map<VariableReferenceExpression, Aggregation>)
                groupingSetsDescriptor,          // groupingSets (GroupingSetDescriptor)
                ImmutableList.of(),              // preGroupedVariables (List<VariableReferenceExpression>)
                AggregationNode.Step.SINGLE,     // step (Step)
                Optional.empty(),                // hashVariable (Optional<VariableReferenceExpression>)
                Optional.empty(),                // groupIdVariable (Optional<VariableReferenceExpression>)
                Optional.empty()                 // aggregationId (Optional<Integer>) -> 你之前漏掉的就是这个！
        );


        // sort node

        List<Ordering> orderBy = new ArrayList<>();
        orderBy.add(new Ordering(returnFlag, SortOrder.ASC_NULLS_LAST));
        orderBy.add(new Ordering(lineStatus, SortOrder.ASC_NULLS_LAST));

        OrderingScheme orderingScheme = new OrderingScheme(orderBy);

        SortNode sortNode = new SortNode(
                Optional.empty(),               // sourceLocation (Optional<SourceLocation>)
                idAllocator.getNextId(),        // id (PlanNodeId)
                aggregationNode,                // source (这是 AggregationNode 的输出)
                orderingScheme,                 // orderingScheme (刚才定义的排序规则)
                false,                          // isPartial (是否是局部排序？通常填 false 表示最终全局排序)
                ImmutableList.of()              // partitionBy (分区排序字段。源码注释写了：空 List 表示全局排序)
        );

        // output node
        List<String> columnNames = Arrays.asList("l_returnflag", "l_linestatus", "l_sum_qty");
        List<VariableReferenceExpression> outputVariables2 = Arrays.asList(returnFlag, lineStatus, sumQty);

        PlanNode root = new OutputNode(
                Optional.empty(),
                idAllocator.getNextId(),
                sortNode,
                columnNames,
                outputVariables2
        );
        return root;
    }
    public PlanNode getQ1() {
// --- 自动生成的变量定义 ---

        VariableReferenceExpression l_returnflag = analyzerContext.getVariableAllocator().newVariable("l_returnflag", VarcharType.VARCHAR);

        VariableReferenceExpression l_linestatus = analyzerContext.getVariableAllocator().newVariable("l_linestatus", VarcharType.VARCHAR);

        VariableReferenceExpression l_quantity = analyzerContext.getVariableAllocator().newVariable("l_quantity", DoubleType.DOUBLE);

        VariableReferenceExpression l_extendedprice = analyzerContext.getVariableAllocator().newVariable("l_extendedprice", DoubleType.DOUBLE);

        VariableReferenceExpression l_discount = analyzerContext.getVariableAllocator().newVariable("l_discount", DoubleType.DOUBLE);

        VariableReferenceExpression l_shipdate  = analyzerContext.getVariableAllocator().newVariable("l_shipdate", DateType.DATE);

        VariableReferenceExpression l_tax = analyzerContext.getVariableAllocator().newVariable("l_tax", DoubleType.DOUBLE);

        VariableReferenceExpression l_extendedprice____cast_1_as_double____l_discount = analyzerContext.getVariableAllocator().newVariable("l_extendedprice____cast_1_as_double____l_discount", DoubleType.DOUBLE);

        VariableReferenceExpression l_extendedprice____cast_1_as_double____l_discount______cast_1_as_double____l_tax = analyzerContext.getVariableAllocator().newVariable("l_extendedprice____cast_1_as_double____l_discount______cast_1_as_double____l_tax", DoubleType.DOUBLE);

        VariableReferenceExpression sum_l_quantity = analyzerContext.getVariableAllocator().newVariable("sum_l_quantity", DoubleType.DOUBLE);

        VariableReferenceExpression sum_l_extendedprice = analyzerContext.getVariableAllocator().newVariable("sum_l_extendedprice", DoubleType.DOUBLE);

        VariableReferenceExpression sum_l_extendedprice____cast_1_as_double____l_discount = analyzerContext.getVariableAllocator().newVariable("sum_l_extendedprice____cast_1_as_double____l_discount", DoubleType.DOUBLE);

        VariableReferenceExpression sum_l_extendedprice____cast_1_as_double____l_discount______cast_1_as_double____l_tax = analyzerContext.getVariableAllocator().newVariable("sum_l_extendedprice____cast_1_as_double____l_discount______cast_1_as_double____l_tax", DoubleType.DOUBLE);

        VariableReferenceExpression avg_l_quantity = analyzerContext.getVariableAllocator().newVariable("avg_l_quantity", DoubleType.DOUBLE);

        VariableReferenceExpression avg_l_extendedprice = analyzerContext.getVariableAllocator().newVariable("avg_l_extendedprice", DoubleType.DOUBLE);

        VariableReferenceExpression avg_l_discount = analyzerContext.getVariableAllocator().newVariable("avg_l_discount", DoubleType.DOUBLE);

        VariableReferenceExpression count_1 = analyzerContext.getVariableAllocator().newVariable("count_1", BigintType.BIGINT);

// --- TableScanNode Start: n1 (lineitem) ---
        QualifiedObjectName tableName_n1 = new QualifiedObjectName("tpchstandard", "tiny", "lineitem");
        Optional<TableHandle> tableHandle_n1 = metadata.getHandleVersion(
                getSession(),
                tableName_n1,
                Optional.empty()
        );

        if (!tableHandle_n1.isPresent()) {
            throw new RuntimeException("Table not found: " + tableName_n1);
        }

        TableHandle handle_n1 = tableHandle_n1.get();
        Map<String, ColumnHandle> columnHandles_n1 = metadata.getColumnHandles(getSession(), handle_n1);

        List<VariableReferenceExpression> outputVariables_n1 = Arrays.asList(
                l_returnflag, l_linestatus, l_quantity, l_extendedprice, l_discount, l_tax, l_shipdate
        );

        ImmutableMap.Builder<VariableReferenceExpression, ColumnHandle> assignments_n1 = ImmutableMap.builder();

        assignments_n1.put(l_returnflag, columnHandles_n1.get("l_returnflag"));

        assignments_n1.put(l_linestatus, columnHandles_n1.get("l_linestatus"));

        assignments_n1.put(l_quantity, columnHandles_n1.get("l_quantity"));

        assignments_n1.put(l_extendedprice, columnHandles_n1.get("l_extendedprice"));

        assignments_n1.put(l_discount, columnHandles_n1.get("l_discount"));

        assignments_n1.put(l_tax, columnHandles_n1.get("l_tax"));
        assignments_n1.put(l_shipdate, columnHandles_n1.get("l_shipdate"));


        PlanNode n1 = new TableScanNode(
                Optional.empty(),
                idAllocator.getNextId(),
                handle_n1,
                outputVariables_n1,
                assignments_n1.build(),
                TupleDomain.all(),
                TupleDomain.all(),
                Optional.empty()
        );
        // --- TableScanNode End: n1 ---

        // --- FilterNode Start: n1child ---
        // Filter logic: (l_shipdate <= DATE '1998-08-04')
        // 注意：getRowExpression 内部会处理从 SQL 字符串到 RowExpression 的转换
        RowExpression predicate_n1child = getRowExpression("(l_shipdate <= DATE '1998-08-04')");

        PlanNode n11child = new FilterNode(
                Optional.empty(),
                idAllocator.getNextId(),
                n1,
                predicate_n1child
        );
        // --- FilterNode End: n1child ---



// --- ProjectNode Start: n1child ---
        Assignments.Builder assignments_n1child = Assignments.builder();

        // 1. 处理计算列 (公式)

        // 计算:
        assignments_n1child.put(l_returnflag, getRowExpression("l_returnflag"));

        // 计算:
        assignments_n1child.put(l_linestatus, getRowExpression("l_linestatus"));

        assignments_n1child.put(l_quantity, getRowExpression("l_quantity"));
        assignments_n1child.put(l_extendedprice, getRowExpression("l_extendedprice"));
        assignments_n1child.put(l_discount, getRowExpression("l_discount"));
        assignments_n1child.put(l_tax, getRowExpression("l_tax"));


        // 计算:
        assignments_n1child.put(l_extendedprice____cast_1_as_double____l_discount, getRowExpression("(l_extendedprice * (cast(1 as double) - l_discount))"));

        // 计算:
        assignments_n1child.put(l_extendedprice____cast_1_as_double____l_discount______cast_1_as_double____l_tax, getRowExpression("((l_extendedprice * (cast(1 as double) - l_discount)) * (cast(1 as double) + l_tax))"));


        ProjectNode n1child = new ProjectNode(
                idAllocator.getNextId(),
                n11child,
                assignments_n1child.build()
        );
        // --- ProjectNode End: n1child ---
// --- AggregationNode Start: n2 ---
        // 1. 定义 Grouping Keys
        List<VariableReferenceExpression> groupingKeys_n2 = Arrays.asList(
                l_returnflag, l_linestatus
        );

        Map<VariableReferenceExpression, AggregationNode.Aggregation> aggregations_n2 = new HashMap<>();


//        // 这个agg.expression要是sourcenode定义的变量
//        RowExpression raw_l_returnflag = getRowExpression("l_returnflag");
//
//        // 包装成 Aggregation 对象, agg.output_var需要自己进行字段定义
//        aggregations_n2.put(l_returnflag, new AggregationNode.Aggregation(
//                (CallExpression) raw_l_returnflag,
//                Optional.empty(), Optional.empty(), false, Optional.empty()
//        ));
//
//        // 这个agg.expression要是sourcenode定义的变量
//        RowExpression raw_l_linestatus = getRowExpression("l_linestatus");
//
//        // 包装成 Aggregation 对象, agg.output_var需要自己进行字段定义
//        aggregations_n2.put(l_linestatus, new AggregationNode.Aggregation(
//                (CallExpression) raw_l_linestatus,
//                Optional.empty(), Optional.empty(), false, Optional.empty()
//        ));

        // 这个agg.expression要是sourcenode定义的变量
        RowExpression raw_sum_l_quantity = getRowExpression("sum(l_quantity)");

        // 包装成 Aggregation 对象, agg.output_var需要自己进行字段定义
        aggregations_n2.put(sum_l_quantity, new AggregationNode.Aggregation(
                (CallExpression) raw_sum_l_quantity,
                Optional.empty(), Optional.empty(), false, Optional.empty()
        ));

        // 这个agg.expression要是sourcenode定义的变量
        RowExpression raw_sum_l_extendedprice = getRowExpression("sum(l_extendedprice)");

        // 包装成 Aggregation 对象, agg.output_var需要自己进行字段定义
        aggregations_n2.put(sum_l_extendedprice, new AggregationNode.Aggregation(
                (CallExpression) raw_sum_l_extendedprice,
                Optional.empty(), Optional.empty(), false, Optional.empty()
        ));

        // 这个agg.expression要是sourcenode定义的变量
        RowExpression raw_sum_l_extendedprice____cast_1_as_double____l_discount = getRowExpression("sum(l_extendedprice____cast_1_as_double____l_discount)");

        // 包装成 Aggregation 对象, agg.output_var需要自己进行字段定义
        aggregations_n2.put(sum_l_extendedprice____cast_1_as_double____l_discount, new AggregationNode.Aggregation(
                (CallExpression) raw_sum_l_extendedprice____cast_1_as_double____l_discount,
                Optional.empty(), Optional.empty(), false, Optional.empty()
        ));

        // 这个agg.expression要是sourcenode定义的变量
        RowExpression raw_sum_l_extendedprice____cast_1_as_double____l_discount______cast_1_as_double____l_tax = getRowExpression("sum(l_extendedprice____cast_1_as_double____l_discount______cast_1_as_double____l_tax)");

        // 包装成 Aggregation 对象, agg.output_var需要自己进行字段定义
        aggregations_n2.put(sum_l_extendedprice____cast_1_as_double____l_discount______cast_1_as_double____l_tax, new AggregationNode.Aggregation(
                (CallExpression) raw_sum_l_extendedprice____cast_1_as_double____l_discount______cast_1_as_double____l_tax,
                Optional.empty(), Optional.empty(), false, Optional.empty()
        ));

        // 这个agg.expression要是sourcenode定义的变量
        RowExpression raw_avg_l_quantity = getRowExpression("avg(l_quantity)");

        // 包装成 Aggregation 对象, agg.output_var需要自己进行字段定义
        aggregations_n2.put(avg_l_quantity, new AggregationNode.Aggregation(
                (CallExpression) raw_avg_l_quantity,
                Optional.empty(), Optional.empty(), false, Optional.empty()
        ));

        // 这个agg.expression要是sourcenode定义的变量
        RowExpression raw_avg_l_extendedprice = getRowExpression("avg(l_extendedprice)");

        // 包装成 Aggregation 对象, agg.output_var需要自己进行字段定义
        aggregations_n2.put(avg_l_extendedprice, new AggregationNode.Aggregation(
                (CallExpression) raw_avg_l_extendedprice,
                Optional.empty(), Optional.empty(), false, Optional.empty()
        ));

        // 这个agg.expression要是sourcenode定义的变量
        RowExpression raw_avg_l_discount = getRowExpression("avg(l_discount)");

        // 包装成 Aggregation 对象, agg.output_var需要自己进行字段定义
        aggregations_n2.put(avg_l_discount, new AggregationNode.Aggregation(
                (CallExpression) raw_avg_l_discount,
                Optional.empty(), Optional.empty(), false, Optional.empty()
        ));

        // 这个agg.expression要是sourcenode定义的变量
        RowExpression raw_count_1 = getRowExpression("count(1)");

        // 包装成 Aggregation 对象, agg.output_var需要自己进行字段定义
        aggregations_n2.put(count_1, new AggregationNode.Aggregation(
                (CallExpression) raw_count_1,
                Optional.empty(), Optional.empty(), false, Optional.empty()
        ));


        Set<Integer> globalSets_n2 = groupingKeys_n2.isEmpty() ?
                ImmutableSet.of(0) : ImmutableSet.of();

        AggregationNode.GroupingSetDescriptor groupingSets_n2 = new AggregationNode.GroupingSetDescriptor(
                groupingKeys_n2,
                1,
                globalSets_n2
        );

        AggregationNode n2 = new AggregationNode(
                Optional.empty(),                // sourceLocation
                idAllocator.getNextId(),         // id
                n1child,               // source (来自上一层算子)
                aggregations_n2,      // aggregations Map
                groupingSets_n2,      // groupingSets
                ImmutableList.of(),              // preGroupedVariables
                AggregationNode.Step.SINGLE,     // step
                Optional.empty(),                // hashVariable
                Optional.empty(),                // groupIdVariable
                Optional.<Integer>empty()        // aggregationId
        );
        // --- AggregationNode End: n2 ---
// --- SortNode Start: n3 ---
        List<Ordering> orderBy_n3 = new ArrayList<>();

        orderBy_n3.add(new Ordering(l_returnflag, SortOrder.ASC_NULLS_LAST));

        orderBy_n3.add(new Ordering(l_linestatus, SortOrder.ASC_NULLS_LAST));


        OrderingScheme orderingScheme_n3 = new OrderingScheme(orderBy_n3);

        SortNode n3 = new SortNode(
                Optional.empty(),               // sourceLocation
                idAllocator.getNextId(),        // id
                n2,              // source (来自上一层算子)
                orderingScheme_n3,   // orderingScheme
                false, // isPartial
                ImmutableList.of()              // partitionBy
        );
        // --- SortNode End: n3 ---
// --- OutputNode Start: root ---
        List<String> columnNames_root = Arrays.asList(
                "l_returnflag", "l_linestatus", "suml_quantity", "suml_extendedprice", "suml_extendedprice * cast1 as double - l_discount", "suml_extendedprice * cast1 as double - l_discount * cast1 as double + l_tax", "avgl_quantity", "avgl_extendedprice", "avgl_discount", "count1"
        );

        List<VariableReferenceExpression> outputVars_root = Arrays.asList(
                l_returnflag, l_linestatus, sum_l_quantity, sum_l_extendedprice, sum_l_extendedprice____cast_1_as_double____l_discount, sum_l_extendedprice____cast_1_as_double____l_discount______cast_1_as_double____l_tax, avg_l_quantity, avg_l_extendedprice, avg_l_discount, count_1
        );

        PlanNode root = new OutputNode(
                Optional.empty(),
                idAllocator.getNextId(),
                n3,           // 通常是 sortNode 或 aggregationNode
                columnNames_root,
                outputVars_root
        );
        // --- OutputNode End: root ---
        return root;
    }

    public PlanNode getQ6() {
// --- 自动生成的变量定义 ---

        VariableReferenceExpression l_extendedprice = analyzerContext.getVariableAllocator().newVariable("l_extendedprice", DoubleType.DOUBLE);

        VariableReferenceExpression l_discount = analyzerContext.getVariableAllocator().newVariable("l_discount", DoubleType.DOUBLE);

        VariableReferenceExpression l_shipdate = analyzerContext.getVariableAllocator().newVariable("l_shipdate", DateType.DATE);
        VariableReferenceExpression l_quantity = analyzerContext.getVariableAllocator().newVariable("l_quantity", DoubleType.DOUBLE);

        VariableReferenceExpression l_extendedprice___l_discount = analyzerContext.getVariableAllocator().newVariable("l_extendedprice___l_discount", DoubleType.DOUBLE);

        VariableReferenceExpression sum_l_extendedprice___l_discount = analyzerContext.getVariableAllocator().newVariable("sum_l_extendedprice___l_discount", DoubleType.DOUBLE);

// --- TableScanNode Start: n1 (lineitem) ---
        QualifiedObjectName tableName_n1 = new QualifiedObjectName("tpchstandard", "tiny", "lineitem");
        Optional<TableHandle> tableHandle_n1 = metadata.getHandleVersion(
                getSession(),
                tableName_n1,
                Optional.empty()
        );

        if (!tableHandle_n1.isPresent()) {
            throw new RuntimeException("Table not found: " + tableName_n1);
        }

        TableHandle handle_n1 = tableHandle_n1.get();
        Map<String, ColumnHandle> columnHandles_n1 = metadata.getColumnHandles(getSession(), handle_n1);

        List<VariableReferenceExpression> outputVariables_n1 = Arrays.asList(
                l_extendedprice, l_discount, l_shipdate, l_quantity
        );

        ImmutableMap.Builder<VariableReferenceExpression, ColumnHandle> assignments_n1 = ImmutableMap.builder();

        assignments_n1.put(l_extendedprice, columnHandles_n1.get("l_extendedprice"));

        assignments_n1.put(l_discount, columnHandles_n1.get("l_discount"));
        assignments_n1.put(l_shipdate, columnHandles_n1.get("l_shipdate"));
        assignments_n1.put(l_quantity, columnHandles_n1.get("l_quantity"));


        PlanNode n1 = new TableScanNode(
                Optional.empty(),
                idAllocator.getNextId(),
                handle_n1,
                outputVariables_n1,
                assignments_n1.build(),
                TupleDomain.all(),
                TupleDomain.all(),
                Optional.empty()
        );
        // --- TableScanNode End: n1 ---
// --- FilterNode Start: n1child ---
        // Filter logic: ((l_shipdate >= DATE '1997-01-01'(0) without time zone) AND (l_shipdate < DATE '1998-01-01') AND (l_discount >= .01) AND (l_discount <= .03) AND (l_quantity < 24))
        // 注意：getRowExpression 内部会处理从 SQL 字符串到 RowExpression 的转换
        RowExpression predicate_n1child = getRowExpression("(l_shipdate >= DATE '1997-01-01' AND l_shipdate < DATE '1998-01-01' AND l_discount >= cast('0.01' as double) AND l_discount <= cast('0.03' as double) AND l_quantity < cast(24 as double))");

        PlanNode n1child = new FilterNode(
                Optional.empty(),
                idAllocator.getNextId(),
                n1,
                predicate_n1child
        );
        // --- FilterNode End: n1child ---
// --- ProjectNode Start: n1childchild ---
        Assignments.Builder assignments_n1childchild = Assignments.builder();

        // 1. 处理计算列 (公式)

        // 计算:
        assignments_n1childchild.put(l_extendedprice___l_discount, getRowExpression("(l_extendedprice * l_discount)"));


        ProjectNode n1childchild = new ProjectNode(
                idAllocator.getNextId(),
                n1child,
                assignments_n1childchild.build()
        );
        // --- ProjectNode End: n1childchild ---
// --- AggregationNode Start: n2 ---
        // 1. 定义 Grouping Keys
        List<VariableReferenceExpression> groupingKeys_n2 = Arrays.asList(

        );

        Map<VariableReferenceExpression, AggregationNode.Aggregation> aggregations_n2 = new HashMap<>();


        // 这个agg.expression要是sourcenode定义的变量
        RowExpression raw_sum_l_extendedprice___l_discount = getRowExpression("sum(l_extendedprice___l_discount)");

        // 包装成 Aggregation 对象, agg.output_var需要自己进行字段定义
        aggregations_n2.put(sum_l_extendedprice___l_discount, new AggregationNode.Aggregation(
                (CallExpression) raw_sum_l_extendedprice___l_discount,
                Optional.empty(), Optional.empty(), false, Optional.empty()
        ));


        Set<Integer> globalSets_n2 = groupingKeys_n2.isEmpty() ?
                ImmutableSet.of(0) : ImmutableSet.of();

        AggregationNode.GroupingSetDescriptor groupingSets_n2 = new AggregationNode.GroupingSetDescriptor(
                groupingKeys_n2,
                1,
                globalSets_n2
        );

        AggregationNode n2 = new AggregationNode(
                Optional.empty(),                // sourceLocation
                idAllocator.getNextId(),         // id
                n1childchild,               // source (来自上一层算子)
                aggregations_n2,      // aggregations Map
                groupingSets_n2,      // groupingSets
                ImmutableList.of(),              // preGroupedVariables
                AggregationNode.Step.SINGLE,     // step
                Optional.empty(),                // hashVariable
                Optional.empty(),                // groupIdVariable
                Optional.<Integer>empty()        // aggregationId
        );
        // --- AggregationNode End: n2 ---
// --- OutputNode Start: root ---
        List<String> columnNames_root = Arrays.asList(
                "suml_extendedprice * l_discount"
        );

        List<VariableReferenceExpression> outputVars_root = Arrays.asList(
                sum_l_extendedprice___l_discount
        );

        PlanNode root = new OutputNode(
                Optional.empty(),
                idAllocator.getNextId(),
                n2,           // 通常是 sortNode 或 aggregationNode
                columnNames_root,
                outputVars_root
        );
        // --- OutputNode End: root ---
        return root;
    }


    public PlanNode getJoin() {
// --- 自动生成的变量定义 ---

        VariableReferenceExpression l_extendedprice = analyzerContext.getVariableAllocator().newVariable("l_extendedprice", DoubleType.DOUBLE);

        VariableReferenceExpression l_discount = analyzerContext.getVariableAllocator().newVariable("l_discount", DoubleType.DOUBLE);

        VariableReferenceExpression l_partkey = analyzerContext.getVariableAllocator().newVariable("l_partkey", BigintType.BIGINT);

        VariableReferenceExpression p_partkey = analyzerContext.getVariableAllocator().newVariable("p_partkey", BigintType.BIGINT);

        VariableReferenceExpression p_brand = analyzerContext.getVariableAllocator().newVariable("p_brand", VarcharType.VARCHAR);

        VariableReferenceExpression l_extendedprice____cast_1_as_double____l_discount = analyzerContext.getVariableAllocator().newVariable("l_extendedprice____cast_1_as_double____l_discount", DoubleType.DOUBLE);

        VariableReferenceExpression sum_l_extendedprice____cast_1_as_double____l_discount = analyzerContext.getVariableAllocator().newVariable("sum_l_extendedprice____cast_1_as_double____l_discount", DoubleType.DOUBLE);

// --- TableScanNode Start: n1 (lineitem) ---
        QualifiedObjectName tableName_n1 = new QualifiedObjectName("tpchstandard", "tiny", "lineitem");
        Optional<TableHandle> tableHandle_n1 = metadata.getHandleVersion(
                getSession(),
                tableName_n1,
                Optional.empty()
        );

        if (!tableHandle_n1.isPresent()) {
            throw new RuntimeException("Table not found: " + tableName_n1);
        }

        TableHandle handle_n1 = tableHandle_n1.get();
        Map<String, ColumnHandle> columnHandles_n1 = metadata.getColumnHandles(getSession(), handle_n1);

        List<VariableReferenceExpression> outputVariables_n1 = Arrays.asList(
                l_extendedprice, l_discount, l_partkey
        );

        ImmutableMap.Builder<VariableReferenceExpression, ColumnHandle> assignments_n1 = ImmutableMap.builder();

        assignments_n1.put(l_extendedprice, columnHandles_n1.get("l_extendedprice"));

        assignments_n1.put(l_discount, columnHandles_n1.get("l_discount"));

        assignments_n1.put(l_partkey, columnHandles_n1.get("l_partkey"));


        PlanNode n1 = new TableScanNode(
                Optional.empty(),
                idAllocator.getNextId(),
                handle_n1,
                outputVariables_n1,
                assignments_n1.build(),
                TupleDomain.all(),
                TupleDomain.all(),
                Optional.empty()
        );
        // --- TableScanNode End: n1 ---
// --- TableScanNode Start: n2 (part) ---
        QualifiedObjectName tableName_n2 = new QualifiedObjectName("tpchstandard", "tiny", "part");
        Optional<TableHandle> tableHandle_n2 = metadata.getHandleVersion(
                getSession(),
                tableName_n2,
                Optional.empty()
        );

        if (!tableHandle_n2.isPresent()) {
            throw new RuntimeException("Table not found: " + tableName_n2);
        }

        TableHandle handle_n2 = tableHandle_n2.get();
        Map<String, ColumnHandle> columnHandles_n2 = metadata.getColumnHandles(getSession(), handle_n2);

        List<VariableReferenceExpression> outputVariables_n2 = Arrays.asList(
                p_partkey, p_brand
        );

        ImmutableMap.Builder<VariableReferenceExpression, ColumnHandle> assignments_n2 = ImmutableMap.builder();

        assignments_n2.put(p_partkey, columnHandles_n2.get("p_partkey"));

        assignments_n2.put(p_brand, columnHandles_n2.get("p_brand"));


        PlanNode n2 = new TableScanNode(
                Optional.empty(),
                idAllocator.getNextId(),
                handle_n2,
                outputVariables_n2,
                assignments_n2.build(),
                TupleDomain.all(),
                TupleDomain.all(),
                Optional.empty()
        );
        // --- TableScanNode End: n2 ---
// --- FilterNode Start: n2child ---
        // Filter logic: (p_brand = 'Brand#13')
        // 注意：getRowExpression 内部会处理从 SQL 字符串到 RowExpression 的转换
        RowExpression predicate_n2child = getRowExpression("(p_brand = 'Brand#13')");

        PlanNode n2child = new FilterNode(
                Optional.empty(),
                idAllocator.getNextId(),
                n2,
                predicate_n2child
        );
        // --- FilterNode End: n2child ---
// --- JoinNode Start: n3 (INNER) ---
        List<EquiJoinClause> criteria_n3 = Arrays.asList(

                new EquiJoinClause(l_partkey, p_partkey)

        );

        // 核心：Join 节点的输出变量是左右子节点输出的并集
        List<VariableReferenceExpression> outputVars_n3 = ImmutableList.<VariableReferenceExpression>builder()
                .addAll(n1.getOutputVariables())
                .addAll(n2child.getOutputVariables())
                .build();

        JoinNode n3 = new JoinNode(
                Optional.empty(),
                idAllocator.getNextId(),
                JoinType.INNER,
                n1,
                n2child,
                criteria_n3,
                outputVars_n3,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                ImmutableMap.of()
        );
        // --- JoinNode End: n3 ---
// --- ProjectNode Start: n3child ---
        Assignments.Builder assignments_n3child = Assignments.builder();

        // 1. 处理计算列 (公式)

        // 计算:
        assignments_n3child.put(l_extendedprice____cast_1_as_double____l_discount, getRowExpression("(l_extendedprice * (cast(1 as double) - l_discount))"));


        ProjectNode n3child = new ProjectNode(
                idAllocator.getNextId(),
                n3,
                assignments_n3child.build()
        );
        // --- ProjectNode End: n3child ---
// --- AggregationNode Start: n4 ---
        // 1. 定义 Grouping Keys
        List<VariableReferenceExpression> groupingKeys_n4 = Arrays.asList(

        );

        Map<VariableReferenceExpression, AggregationNode.Aggregation> aggregations_n4 = new HashMap<>();


        // 这个agg.expression要是sourcenode定义的变量
        RowExpression raw_sum_l_extendedprice____cast_1_as_double____l_discount = getRowExpression("sum(l_extendedprice____cast_1_as_double____l_discount)");

        // 包装成 Aggregation 对象, agg.output_var需要自己进行字段定义
        aggregations_n4.put(sum_l_extendedprice____cast_1_as_double____l_discount, new AggregationNode.Aggregation(
                (CallExpression) raw_sum_l_extendedprice____cast_1_as_double____l_discount,
                Optional.empty(), Optional.empty(), false, Optional.empty()
        ));


        Set<Integer> globalSets_n4 = groupingKeys_n4.isEmpty() ?
                ImmutableSet.of(0) : ImmutableSet.of();

        AggregationNode.GroupingSetDescriptor groupingSets_n4 = new AggregationNode.GroupingSetDescriptor(
                groupingKeys_n4,
                1,
                globalSets_n4
        );

        AggregationNode n4 = new AggregationNode(
                Optional.empty(),                // sourceLocation
                idAllocator.getNextId(),         // id
                n3child,               // source (来自上一层算子)
                aggregations_n4,      // aggregations Map
                groupingSets_n4,      // groupingSets
                ImmutableList.of(),              // preGroupedVariables
                AggregationNode.Step.SINGLE,     // step
                Optional.empty(),                // hashVariable
                Optional.empty(),                // groupIdVariable
                Optional.<Integer>empty()        // aggregationId
        );
        // --- AggregationNode End: n4 ---
// --- OutputNode Start: root ---
        List<String> columnNames_root = Arrays.asList(
                "l_discount"
        );

        List<VariableReferenceExpression> outputVars_root = Arrays.asList(
                sum_l_extendedprice____cast_1_as_double____l_discount
        );

        PlanNode root = new OutputNode(
                Optional.empty(),
                idAllocator.getNextId(),
                n4,           // 通常是 sortNode 或 aggregationNode
                columnNames_root,
                outputVars_root
        );
        // --- OutputNode End: root ---
        return root;
    }

//    public PlanNode getQ2()
//    {
//
//        // 1. Region Filter: r_name = 'AFRICA'
//        PlanNode filteredRegion = new FilterNode(..., regionScan,
//            createEqualsPredicate(v_r_name, "AFRICA"));
//
//        // 2. Join Nation & Region (Hash Join)
//                PlanNode nationRegionJoin = new JoinNode(
//                        idAllocator.getNextId(),
//                        JoinNode.Type.INNER,
//                        nationScan, filteredRegion,
//                        ImmutableList.of(new JoinNode.EquiJoinClause(v_n_regionkey, v_r_regionkey)),
//            ...);
//
//        // 3. Join Supplier & (Nation+Region)
//                PlanNode snrSubtree = new JoinNode(
//                        idAllocator.getNextId(),
//                        JoinNode.Type.INNER,
//                        supplierScan, nationRegionJoin,
//                        ImmutableList.of(new JoinNode.EquiJoinClause(v_s_nationkey, v_n_nationkey)),
//            ...);
//    }

    // some probelm
    public PlanNode getQ12() {
        VariableReferenceExpression l_shipmode = analyzerContext.getVariableAllocator().newVariable("l_shipmode", VarcharType.VARCHAR);

        VariableReferenceExpression l_orderkey = analyzerContext.getVariableAllocator().newVariable("l_orderkey", BigintType.BIGINT);
        VariableReferenceExpression l_commitdate = analyzerContext.getVariableAllocator().newVariable("l_commitdate", DateType.DATE);
        VariableReferenceExpression l_receiptdate = analyzerContext.getVariableAllocator().newVariable("l_receiptdate", DateType.DATE);
        VariableReferenceExpression l_shipdate = analyzerContext.getVariableAllocator().newVariable("l_shipdate", DateType.DATE);

        VariableReferenceExpression o_orderpriority = analyzerContext.getVariableAllocator().newVariable("o_orderpriority", VarcharType.VARCHAR);

        VariableReferenceExpression o_orderkey = analyzerContext.getVariableAllocator().newVariable("o_orderkey", BigintType.BIGINT);

        VariableReferenceExpression CASE_WHEN___o_orderpriority____1_URGENT___bpchar__OR__o_orderpriority____2_HIGH___bpchar___THEN_1_ELSE_0_END = analyzerContext.getVariableAllocator().newVariable("high_priority_flag",BigintType.BIGINT);

        VariableReferenceExpression CASE_WHEN___o_orderpriority_____1_URGENT___bpchar__AND__o_orderpriority_____2_HIGH___bpchar___THEN_1_ELSE_0_END = analyzerContext.getVariableAllocator().newVariable("low_priority_flag",BigintType.BIGINT);

        VariableReferenceExpression sum_CASE_WHEN___o_orderpriority____1_URGENT___bpchar__OR__o_orderpriority____2_HIGH___bpchar___THEN_1_ELSE_0_END = analyzerContext.getVariableAllocator().newVariable("sum_high_priority_flag", BigintType.BIGINT);

        VariableReferenceExpression sum_CASE_WHEN___o_orderpriority_____1_URGENT___bpchar__AND__o_orderpriority_____2_HIGH___bpchar___THEN_1_ELSE_0_END = analyzerContext.getVariableAllocator().newVariable("sum_low_priority_flag", BigintType.BIGINT);

// --- TableScanNode Start: n1 (lineitem) ---
        QualifiedObjectName tableName_n1 = new QualifiedObjectName("tpchstandard", "tiny", "lineitem");
        Optional<TableHandle> tableHandle_n1 = metadata.getHandleVersion(
                getSession(),
                tableName_n1,
                Optional.empty()
        );

        if (!tableHandle_n1.isPresent()) {
            throw new RuntimeException("Table not found: " + tableName_n1);
        }

        TableHandle handle_n1 = tableHandle_n1.get();
        Map<String, ColumnHandle> columnHandles_n1 = metadata.getColumnHandles(getSession(), handle_n1);

        List<VariableReferenceExpression> outputVariables_n1 = Arrays.asList(
                l_shipmode, l_orderkey, l_commitdate, l_receiptdate, l_shipdate
        );

        ImmutableMap.Builder<VariableReferenceExpression, ColumnHandle> assignments_n1 = ImmutableMap.builder();

        assignments_n1.put(l_shipmode, columnHandles_n1.get("l_shipmode"));

        assignments_n1.put(l_orderkey, columnHandles_n1.get("l_orderkey"));
        assignments_n1.put(l_commitdate, columnHandles_n1.get("l_commitdate"));
        assignments_n1.put(l_receiptdate, columnHandles_n1.get("l_receiptdate"));
        assignments_n1.put(l_shipdate, columnHandles_n1.get("l_shipdate"));


        PlanNode n1 = new TableScanNode(
                Optional.empty(),
                idAllocator.getNextId(),
                handle_n1,
                outputVariables_n1,
                assignments_n1.build(),
                TupleDomain.all(),
                TupleDomain.all(),
                Optional.empty()
        );


        System.out.println("DEBUG n1 build finish");
        System.out.println("n1 output variables: " +
                n1.getOutputVariables().stream().map(v -> v.getName()).collect(Collectors.toList()));

        // --- TableScanNode End: n1 ---
// --- FilterNode Start: n1child ---
        // Filter logic: ((l_shipmode = ANY ('{RAIL,TRUCK}'[])) AND (l_commitdate < l_receiptdate) AND (l_shipdate < l_commitdate) AND (l_receiptdate >= DATE '1993-01-01'(0) without time zone) AND (l_receiptdate < DATE '1994-01-01'))
        // 注意：getRowExpression 内部会处理从 SQL 字符串到 RowExpression 的转换
//        RowExpression predicate_n1child = getRowExpression("((l_shipmode = ANY ('{RAIL,TRUCK}'[])) AND (l_commitdate < l_receiptdate) AND (l_shipdate < l_commitdate) AND (l_receiptdate >= DATE '1993-01-01'(0) without time zone) AND (l_receiptdate < DATE '1994-01-01'))");
        RowExpression predicate_n1child = getRowExpression(
                "((l_shipmode IN ('RAIL', 'TRUCK')) AND (l_commitdate < l_receiptdate) AND (l_shipdate < l_commitdate) AND (l_receiptdate >= DATE '1993-01-01') AND (l_receiptdate < DATE '1994-01-01'))"
        );


        PlanNode n1child = new FilterNode(
                Optional.empty(),
                idAllocator.getNextId(),
                n1,
                predicate_n1child
        );


        System.out.println("DEBUG n1child build finish");
        System.out.println("n1child output variables: " +
                n1child.getOutputVariables().stream().map(v -> v.getName()).collect(Collectors.toList()));
        // --- FilterNode End: n1child ---
// --- TableScanNode Start: n2 (orders) ---
        QualifiedObjectName tableName_n2 = new QualifiedObjectName("tpchstandard", "tiny", "orders");
        Optional<TableHandle> tableHandle_n2 = metadata.getHandleVersion(
                getSession(),
                tableName_n2,
                Optional.empty()
        );

        if (!tableHandle_n2.isPresent()) {
            throw new RuntimeException("Table not found: " + tableName_n2);
        }

        TableHandle handle_n2 = tableHandle_n2.get();
        Map<String, ColumnHandle> columnHandles_n2 = metadata.getColumnHandles(getSession(), handle_n2);

        List<VariableReferenceExpression> outputVariables_n2 = Arrays.asList(
                o_orderpriority, o_orderkey
        );

        ImmutableMap.Builder<VariableReferenceExpression, ColumnHandle> assignments_n2 = ImmutableMap.builder();

        assignments_n2.put(o_orderpriority, columnHandles_n2.get("o_orderpriority"));

        assignments_n2.put(o_orderkey, columnHandles_n2.get("o_orderkey"));


        PlanNode n2 = new TableScanNode(
                Optional.empty(),
                idAllocator.getNextId(),
                handle_n2,
                outputVariables_n2,
                assignments_n2.build(),
                TupleDomain.all(),
                TupleDomain.all(),
                Optional.empty()
        );


        System.out.println("DEBUG n2 build finish");
        System.out.println("n2 output variables: " +
                n2.getOutputVariables().stream().map(v -> v.getName()).collect(Collectors.toList()));

        // --- TableScanNode End: n2 ---
// --- JoinNode Start: n3 ---
        // Join Filter 逻辑:

        Optional<RowExpression> filter_n3 = Optional.empty();


        List<EquiJoinClause> criteria_n3 = Arrays.asList(

                new EquiJoinClause(l_orderkey, o_orderkey)

        );

        // 使用 LinkedHashSet 保持顺序并去重
        Set<VariableReferenceExpression> outputSet_n3 = new LinkedHashSet<>();
        outputSet_n3.addAll(n1child.getOutputVariables());
        outputSet_n3.addAll(n2.getOutputVariables());

        List<VariableReferenceExpression> outputVars_n3 = ImmutableList.copyOf(outputSet_n3);

        JoinNode n3 = new JoinNode(
                Optional.empty(),
                idAllocator.getNextId(),
                JoinType.INNER,
                n1child,
                n2,
                criteria_n3,
                outputVars_n3,
                filter_n3,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                ImmutableMap.of()
        );


        System.out.println("DEBUG n3 build finish");
        System.out.println("n3 output variables: " +
                n3.getOutputVariables().stream().map(v -> v.getName()).collect(Collectors.toList()));
// --- ProjectNode Start: n3child ---
        Assignments.Builder assignments_n3child = Assignments.builder();

        // 1. 处理计算列 (公式)

        // 计算:
        assignments_n3child.put(l_shipmode, getRowExpression("l_shipmode"));
//
//        // 计算:
//        assignments_n3child.put(CASE_WHEN___o_orderpriority____1_URGENT___bpchar__OR__o_orderpriority____2_HIGH___bpchar___THEN_1_ELSE_0_END, getRowExpression("CASE WHEN ((o_orderpriority = '1-URGENT) OR (o_orderpriority = '2-HIGH')) THEN 1 ELSE 0 END"));
//
//        // 计算:
//        assignments_n3child.put(CASE_WHEN___o_orderpriority_____1_URGENT___bpchar__AND__o_orderpriority_____2_HIGH___bpchar___THEN_1_ELSE_0_END, getRowExpression("CASE WHEN ((o_orderpriority <> '1-URGENT') AND (o_orderpriority <> '2-HIGH'" +
//                ")) THEN 1 ELSE 0 END"));

        // 计算: 当orderpriority是'1-URGENT'或'2-HIGH'时返回1，否则返回0
        assignments_n3child.put(CASE_WHEN___o_orderpriority____1_URGENT___bpchar__OR__o_orderpriority____2_HIGH___bpchar___THEN_1_ELSE_0_END,
                getRowExpression("CASE WHEN (o_orderpriority = '1-URGENT' OR o_orderpriority = '2-HIGH') THEN 1L ELSE 0L END"));

// 计算: 当orderpriority既不是'1-URGENT'也不是'2-HIGH'时返回1，否则返回0
        assignments_n3child.put(CASE_WHEN___o_orderpriority_____1_URGENT___bpchar__AND__o_orderpriority_____2_HIGH___bpchar___THEN_1_ELSE_0_END,
                getRowExpression("CASE WHEN (o_orderpriority <> '1-URGENT' AND o_orderpriority <> '2-HIGH') THEN 1L ELSE 0L END"));


        ProjectNode n3child = new ProjectNode(
                idAllocator.getNextId(),
                n3,
                assignments_n3child.build()
        );


        System.out.println("DEBUG n3child build finish");
        System.out.println("n3child output variables: " +
                n3child.getOutputVariables().stream().map(v -> v.getName()).collect(Collectors.toList()));

        // --- ProjectNode End: n3child ---
// --- AggregationNode Start: n4 ---
        // 1. 定义 Grouping Keys
        List<VariableReferenceExpression> groupingKeys_n4 = Arrays.asList(
                l_shipmode
        );

        Map<VariableReferenceExpression, AggregationNode.Aggregation> aggregations_n4 = new HashMap<>();


//        // 这个agg.expression要是sourcenode定义的变量
//        RowExpression raw_l_shipmode = getRowExpression("l_shipmode");
//
//        // 包装成 Aggregation 对象, agg.output_var需要自己进行字段定义
//        aggregations_n4.put(l_shipmode, new AggregationNode.Aggregation(
//                (CallExpression) raw_l_shipmode,
//                Optional.empty(), Optional.empty(), false, Optional.empty()
//        ));

        // 这个agg.expression要是sourcenode定义的变量
        RowExpression raw_sum_CASE_WHEN___o_orderpriority____1_URGENT___bpchar__OR__o_orderpriority____2_HIGH___bpchar___THEN_1_ELSE_0_END = getRowExpression("sum(high_priority_flag)");

        // 包装成 Aggregation 对象, agg.output_var需要自己进行字段定义
        aggregations_n4.put(sum_CASE_WHEN___o_orderpriority____1_URGENT___bpchar__OR__o_orderpriority____2_HIGH___bpchar___THEN_1_ELSE_0_END, new AggregationNode.Aggregation(
                (CallExpression) raw_sum_CASE_WHEN___o_orderpriority____1_URGENT___bpchar__OR__o_orderpriority____2_HIGH___bpchar___THEN_1_ELSE_0_END,
                Optional.empty(), Optional.empty(), false, Optional.empty()
        ));

        // 这个agg.expression要是sourcenode定义的变量
        RowExpression raw_sum_CASE_WHEN___o_orderpriority_____1_URGENT___bpchar__AND__o_orderpriority_____2_HIGH___bpchar___THEN_1_ELSE_0_END = getRowExpression("sum(low_priority_flag)");

        // 包装成 Aggregation 对象, agg.output_var需要自己进行字段定义
        aggregations_n4.put(sum_CASE_WHEN___o_orderpriority_____1_URGENT___bpchar__AND__o_orderpriority_____2_HIGH___bpchar___THEN_1_ELSE_0_END, new AggregationNode.Aggregation(
                (CallExpression) raw_sum_CASE_WHEN___o_orderpriority_____1_URGENT___bpchar__AND__o_orderpriority_____2_HIGH___bpchar___THEN_1_ELSE_0_END,
                Optional.empty(), Optional.empty(), false, Optional.empty()
        ));


        Set<Integer> globalSets_n4 = groupingKeys_n4.isEmpty() ?
                ImmutableSet.of(0) : ImmutableSet.of();

        AggregationNode.GroupingSetDescriptor groupingSets_n4 = new AggregationNode.GroupingSetDescriptor(
                groupingKeys_n4,
                1,
                globalSets_n4
        );

        AggregationNode n4 = new AggregationNode(
                Optional.empty(),                // sourceLocation
                idAllocator.getNextId(),         // id
                n3child,               // source (来自上一层算子)
                aggregations_n4,      // aggregations Map
                groupingSets_n4,      // groupingSets
                ImmutableList.of(),              // preGroupedVariables
                AggregationNode.Step.SINGLE,     // step
                Optional.empty(),                // hashVariable
                Optional.empty(),                // groupIdVariable
                Optional.<Integer>empty()        // aggregationId
        );

        System.out.println("DEBUG n4 build finish");
        System.out.println("n4 output variables: " +
                n4.getOutputVariables().stream().map(v -> v.getName()).collect(Collectors.toList()));

        // --- AggregationNode End: n4 ---
// --- SortNode Start: n5 ---
        List<Ordering> orderBy_n5 = new ArrayList<>();

        orderBy_n5.add(new Ordering(l_shipmode, SortOrder.ASC_NULLS_LAST));


        OrderingScheme orderingScheme_n5 = new OrderingScheme(orderBy_n5);

        SortNode n5 = new SortNode(
                Optional.empty(),               // sourceLocation
                idAllocator.getNextId(),        // id
                n4,              // source (来自上一层算子)
                orderingScheme_n5,   // orderingScheme
                false, // isPartial
                ImmutableList.of()              // partitionBy
        );


        System.out.println("DEBUG n5 build finish");
        System.out.println("n5 output variables: " +
                n5.getOutputVariables().stream().map(v -> v.getName()).collect(Collectors.toList()));

        // --- SortNode End: n5 ---
// --- OutputNode Start: root ---
        List<String> columnNames_root = Arrays.asList(
                "l_shipmode", "agg1", "agg2"
        );

        List<VariableReferenceExpression> outputVars_root = Arrays.asList(
                l_shipmode, sum_CASE_WHEN___o_orderpriority____1_URGENT___bpchar__OR__o_orderpriority____2_HIGH___bpchar___THEN_1_ELSE_0_END, sum_CASE_WHEN___o_orderpriority_____1_URGENT___bpchar__AND__o_orderpriority_____2_HIGH___bpchar___THEN_1_ELSE_0_END
        );

        PlanNode root = new OutputNode(
                Optional.empty(),
                idAllocator.getNextId(),
                n5,           // 通常是 sortNode 或 aggregationNode
                columnNames_root,
                outputVars_root
        );


        System.out.println("DEBUG root build finish");
        System.out.println("root output variables: " +
                root.getOutputVariables().stream().map(v -> v.getName()).collect(Collectors.toList()));

        // --- OutputNode End: root ---
        return root;
    }

    public PlanNode getQ13() {
        VariableReferenceExpression o_orderkey = analyzerContext.getVariableAllocator().newVariable("o_orderkey", BigintType.BIGINT);

        VariableReferenceExpression o_custkey = analyzerContext.getVariableAllocator().newVariable("o_custkey", BigintType.BIGINT);
        VariableReferenceExpression o_comment = analyzerContext.getVariableAllocator().newVariable("o_comment", VarcharType.VARCHAR);

        VariableReferenceExpression c_custkey = analyzerContext.getVariableAllocator().newVariable("c_custkey", BigintType.BIGINT);

//        VariableReferenceExpression  = analyzerContext.getVariableAllocator().newVariable("", UnknownType.UNKNOWN);
        VariableReferenceExpression count_o_orderkey = analyzerContext.getVariableAllocator().newVariable("count_o_orderkey", BigintType.BIGINT);

//        VariableReferenceExpression c_count = analyzerContext.getVariableAllocator().newVariable("c_count", BigintType.BIGINT);

        VariableReferenceExpression count_all = analyzerContext.getVariableAllocator().newVariable("count_all", BigintType.BIGINT);

// --- TableScanNode Start: n1 (orders) ---
        QualifiedObjectName tableName_n1 = new QualifiedObjectName("tpchstandard", "tiny", "orders");
        Optional<TableHandle> tableHandle_n1 = metadata.getHandleVersion(
                getSession(),
                tableName_n1,
                Optional.empty()
        );

        if (!tableHandle_n1.isPresent()) {
            throw new RuntimeException("Table not found: " + tableName_n1);
        }

        TableHandle handle_n1 = tableHandle_n1.get();
        Map<String, ColumnHandle> columnHandles_n1 = metadata.getColumnHandles(getSession(), handle_n1);

        List<VariableReferenceExpression> outputVariables_n1 = Arrays.asList(
                o_orderkey, o_custkey, o_comment
        );

        ImmutableMap.Builder<VariableReferenceExpression, ColumnHandle> assignments_n1 = ImmutableMap.builder();

        assignments_n1.put(o_orderkey, columnHandles_n1.get("o_orderkey"));

        assignments_n1.put(o_custkey, columnHandles_n1.get("o_custkey"));
        assignments_n1.put(o_comment, columnHandles_n1.get("o_comment"));


        PlanNode n1 = new TableScanNode(
                Optional.empty(),
                idAllocator.getNextId(),
                handle_n1,
                outputVariables_n1,
                assignments_n1.build(),
                TupleDomain.all(),
                TupleDomain.all(),
                Optional.empty()
        );


        System.out.println("DEBUG n1 build finish");
        System.out.println("n1 output variables: " +
                n1.getOutputVariables().stream().map(v -> v.getName()).collect(Collectors.toList()));

        // --- TableScanNode End: n1 ---
// --- FilterNode Start: n1child ---
        // Filter logic: ((o_comment) NOT LIKE '%pending%packages%')
        // 注意：getRowExpression 内部会处理从 SQL 字符串到 RowExpression 的转换
//        RowExpression predicate_n1child = getRowExpression(
//                "NOT like(o_comment, CAST('%pending%packages%' AS VARCHAR))"
//        );
        RowExpression predicate_n1child = getRowExpression(
                "NOT regexp_like(o_comment, CAST('.*pending.*packages.*' AS VARCHAR))"
        );
        PlanNode n1child = new FilterNode(
                Optional.empty(),
                idAllocator.getNextId(),
                n1,
                predicate_n1child
        );


        System.out.println("DEBUG n1child build finish");
        System.out.println("n1child output variables: " +
                n1child.getOutputVariables().stream().map(v -> v.getName()).collect(Collectors.toList()));
        // --- FilterNode End: n1child ---
// --- TableScanNode Start: n2 (customer) ---
        QualifiedObjectName tableName_n2 = new QualifiedObjectName("tpchstandard", "tiny", "customer");
        Optional<TableHandle> tableHandle_n2 = metadata.getHandleVersion(
                getSession(),
                tableName_n2,
                Optional.empty()
        );

        if (!tableHandle_n2.isPresent()) {
            throw new RuntimeException("Table not found: " + tableName_n2);
        }

        TableHandle handle_n2 = tableHandle_n2.get();
        Map<String, ColumnHandle> columnHandles_n2 = metadata.getColumnHandles(getSession(), handle_n2);

        List<VariableReferenceExpression> outputVariables_n2 = Arrays.asList(
                c_custkey
        );

        ImmutableMap.Builder<VariableReferenceExpression, ColumnHandle> assignments_n2 = ImmutableMap.builder();

        assignments_n2.put(c_custkey, columnHandles_n2.get("c_custkey"));


        PlanNode n2 = new TableScanNode(
                Optional.empty(),
                idAllocator.getNextId(),
                handle_n2,
                outputVariables_n2,
                assignments_n2.build(),
                TupleDomain.all(),
                TupleDomain.all(),
                Optional.empty()
        );


        System.out.println("DEBUG n2 build finish");
        System.out.println("n2 output variables: " +
                n2.getOutputVariables().stream().map(v -> v.getName()).collect(Collectors.toList()));

        // --- TableScanNode End: n2 ---
// --- JoinNode Start: n3 ---
        // Join Filter 逻辑:

        Optional<RowExpression> filter_n3 = Optional.empty();


        List<EquiJoinClause> criteria_n3 = Arrays.asList(

                new EquiJoinClause(o_custkey, c_custkey)

        );

        // 使用 LinkedHashSet 保持顺序并去重
        Set<VariableReferenceExpression> outputSet_n3 = new LinkedHashSet<>();
        outputSet_n3.addAll(n1child.getOutputVariables());
        outputSet_n3.addAll(n2.getOutputVariables());

        List<VariableReferenceExpression> outputVars_n3 = ImmutableList.copyOf(outputSet_n3);

        JoinNode n3 = new JoinNode(
                Optional.empty(),
                idAllocator.getNextId(),
                JoinType.RIGHT,
                n1child,
                n2,
                criteria_n3,
                outputVars_n3,
                filter_n3,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                ImmutableMap.of()
        );


        System.out.println("DEBUG n3 build finish");
        System.out.println("n3 output variables: " +
                n3.getOutputVariables().stream().map(v -> v.getName()).collect(Collectors.toList()));
// --- AggregationNode Start: n4 ---
        // 1. 定义 Grouping Keys
        List<VariableReferenceExpression> groupingKeys_n4 = Arrays.asList(
                c_custkey
        );

        Map<VariableReferenceExpression, AggregationNode.Aggregation> aggregations_n4 = new HashMap<>();


        // 这个agg.expression要是sourcenode定义的变量
//        RowExpression raw_c_custkey = getRowExpression("c_custkey");
//
//        // 包装成 Aggregation 对象, agg.output_var需要自己进行字段定义
//        aggregations_n4.put(c_custkey, new AggregationNode.Aggregation(
//                (CallExpression) raw_c_custkey,
//                Optional.empty(), Optional.empty(), false, Optional.empty()
//        ));

        // 这个agg.expression要是sourcenode定义的变量
        RowExpression raw_count_o_orderkey = getRowExpression("count(o_orderkey)");

        // 包装成 Aggregation 对象, agg.output_var需要自己进行字段定义
        aggregations_n4.put(count_o_orderkey, new AggregationNode.Aggregation(
                (CallExpression) raw_count_o_orderkey,
                Optional.empty(), Optional.empty(), false, Optional.empty()
        ));


        Set<Integer> globalSets_n4 = groupingKeys_n4.isEmpty() ?
                ImmutableSet.of(0) : ImmutableSet.of();

        AggregationNode.GroupingSetDescriptor groupingSets_n4 = new AggregationNode.GroupingSetDescriptor(
                groupingKeys_n4,
                1,
                globalSets_n4
        );

        AggregationNode n4 = new AggregationNode(
                Optional.empty(),                // sourceLocation
                idAllocator.getNextId(),         // id
                n3,               // source (来自上一层算子)
                aggregations_n4,      // aggregations Map
                groupingSets_n4,      // groupingSets
                ImmutableList.of(),              // preGroupedVariables
                AggregationNode.Step.SINGLE,     // step
                Optional.empty(),                // hashVariable
                Optional.empty(),                // groupIdVariable
                Optional.<Integer>empty()        // aggregationId
        );

        System.out.println("DEBUG n4 build finish");
        System.out.println("n4 output variables: " +
                n4.getOutputVariables().stream().map(v -> v.getName()).collect(Collectors.toList()));

        // --- AggregationNode End: n4 ---
//// --- ProjectNode Start: n5child ---
//        Assignments.Builder assignments_n5child = Assignments.builder();
//
//        // 1. 处理计算列 (公式)
//
//        // 计算:
//        assignments_n5child.put(c_count, getRowExpression("c_count"));
//
//        // 计算:
//        assignments_n5child.put(, getRowExpression("*"));
//
//
//        ProjectNode n5child = new ProjectNode(
//                idAllocator.getNextId(),
//                n5,
//                assignments_n5child.build()
//        );
//
//
//        System.out.println("DEBUG n5child build finish");
//        System.out.println("n5child output variables: " +
//                n5child.getOutputVariables().stream().map(v -> v.getName()).collect(Collectors.toList()));

        // --- ProjectNode End: n5child ---
// --- AggregationNode Start: n6 ---
        // 1. 定义 Grouping Keys
        List<VariableReferenceExpression> groupingKeys_n6 = Arrays.asList(
                count_o_orderkey
        );

        Map<VariableReferenceExpression, AggregationNode.Aggregation> aggregations_n6 = new HashMap<>();


//        // 这个agg.expression要是sourcenode定义的变量
//        RowExpression raw_c_count = getRowExpression("c_count");
//
//        // 包装成 Aggregation 对象, agg.output_var需要自己进行字段定义
//        aggregations_n6.put(c_count, new AggregationNode.Aggregation(
//                (CallExpression) raw_c_count,
//                Optional.empty(), Optional.empty(), false, Optional.empty()
//        ));

        // 这个agg.expression要是sourcenode定义的变量
        RowExpression raw_count = getRowExpression("count(*)");

        // 包装成 Aggregation 对象, agg.output_var需要自己进行字段定义
        aggregations_n6.put(count_all, new AggregationNode.Aggregation(
                (CallExpression) raw_count,
                Optional.empty(), Optional.empty(), false, Optional.empty()
        ));


        Set<Integer> globalSets_n6 = groupingKeys_n6.isEmpty() ?
                ImmutableSet.of(0) : ImmutableSet.of();

        AggregationNode.GroupingSetDescriptor groupingSets_n6 = new AggregationNode.GroupingSetDescriptor(
                groupingKeys_n6,
                1,
                globalSets_n6
        );

        AggregationNode n6 = new AggregationNode(
                Optional.empty(),                // sourceLocation
                idAllocator.getNextId(),         // id
                n4,               // source (来自上一层算子)
                aggregations_n6,      // aggregations Map
                groupingSets_n6,      // groupingSets
                ImmutableList.of(),              // preGroupedVariables
                AggregationNode.Step.SINGLE,     // step
                Optional.empty(),                // hashVariable
                Optional.empty(),                // groupIdVariable
                Optional.<Integer>empty()        // aggregationId
        );

        System.out.println("DEBUG n6 build finish");
        System.out.println("n6 output variables: " +
                n6.getOutputVariables().stream().map(v -> v.getName()).collect(Collectors.toList()));

        // --- AggregationNode End: n6 ---
// --- SortNode Start: n7 ---
        List<Ordering> orderBy_n7 = new ArrayList<>();

        orderBy_n7.add(new Ordering(count_all, SortOrder.DESC_NULLS_LAST));

        orderBy_n7.add(new Ordering(count_o_orderkey, SortOrder.DESC_NULLS_LAST));


        OrderingScheme orderingScheme_n7 = new OrderingScheme(orderBy_n7);

        SortNode n7 = new SortNode(
                Optional.empty(),               // sourceLocation
                idAllocator.getNextId(),        // id
                n6,              // source (来自上一层算子)
                orderingScheme_n7,   // orderingScheme
                false, // isPartial
                ImmutableList.of()              // partitionBy
        );


        System.out.println("DEBUG n7 build finish");
        System.out.println("n7 output variables: " +
                n7.getOutputVariables().stream().map(v -> v.getName()).collect(Collectors.toList()));

        // --- SortNode End: n7 ---
// --- OutputNode Start: root ---
        List<String> columnNames_root = Arrays.asList(
                "count_o_orderkey", "count_all"
        );

        List<VariableReferenceExpression> outputVars_root = Arrays.asList(
                count_o_orderkey, count_all
        );

        PlanNode root = new OutputNode(
                Optional.empty(),
                idAllocator.getNextId(),
                n7,           // 通常是 sortNode 或 aggregationNode
                columnNames_root,
                outputVars_root
        );


        System.out.println("DEBUG root build finish");
        System.out.println("root output variables: " +
                root.getOutputVariables().stream().map(v -> v.getName()).collect(Collectors.toList()));

        // --- OutputNode End: root ---
        return root;
    }

    public PlanNode getQ14() {
        // --- 自动生成的变量定义 ---

        VariableReferenceExpression l_extendedprice = analyzerContext.getVariableAllocator().newVariable("l_extendedprice", DoubleType.DOUBLE);

        VariableReferenceExpression l_discount = analyzerContext.getVariableAllocator().newVariable("l_discount", DoubleType.DOUBLE);

        VariableReferenceExpression l_partkey = analyzerContext.getVariableAllocator().newVariable("l_partkey", BigintType.BIGINT);
        VariableReferenceExpression l_shipdate  = analyzerContext.getVariableAllocator().newVariable("l_shipdate", DateType.DATE);

        VariableReferenceExpression p_type = analyzerContext.getVariableAllocator().newVariable("p_type", VarcharType.VARCHAR);

        VariableReferenceExpression p_partkey = analyzerContext.getVariableAllocator().newVariable("p_partkey", BigintType.BIGINT);


        VariableReferenceExpression revenue_input = analyzerContext.getVariableAllocator().newVariable("revenue_input", DoubleType.DOUBLE);
        VariableReferenceExpression promo_revenue_input = analyzerContext.getVariableAllocator().newVariable("promo_revenue_input", DoubleType.DOUBLE);
        VariableReferenceExpression promo_revenue_sum = analyzerContext.getVariableAllocator().newVariable("promo_revenue_sum", DoubleType.DOUBLE);
        VariableReferenceExpression total_revenue_sum = analyzerContext.getVariableAllocator().newVariable("total_revenue_sum", DoubleType.DOUBLE);
        VariableReferenceExpression q14_final_result = analyzerContext.getVariableAllocator().newVariable("q14_final_result", DoubleType.DOUBLE);


// --- TableScanNode Start: n1 (lineitem) ---
        QualifiedObjectName tableName_n1 = new QualifiedObjectName("tpchstandard", "tiny", "lineitem");
        Optional<TableHandle> tableHandle_n1 = metadata.getHandleVersion(
                getSession(),
                tableName_n1,
                Optional.empty()
        );

        if (!tableHandle_n1.isPresent()) {
            throw new RuntimeException("Table not found: " + tableName_n1);
        }

        TableHandle handle_n1 = tableHandle_n1.get();
        Map<String, ColumnHandle> columnHandles_n1 = metadata.getColumnHandles(getSession(), handle_n1);

        List<VariableReferenceExpression> outputVariables_n1 = Arrays.asList(
                l_extendedprice, l_discount, l_partkey, l_shipdate
        );

        ImmutableMap.Builder<VariableReferenceExpression, ColumnHandle> assignments_n1 = ImmutableMap.builder();

        assignments_n1.put(l_extendedprice, columnHandles_n1.get("l_extendedprice"));

        assignments_n1.put(l_discount, columnHandles_n1.get("l_discount"));

        assignments_n1.put(l_partkey, columnHandles_n1.get("l_partkey"));
        assignments_n1.put(l_shipdate, columnHandles_n1.get("l_shipdate"));


        PlanNode n1 = new TableScanNode(
                Optional.empty(),
                idAllocator.getNextId(),
                handle_n1,
                outputVariables_n1,
                assignments_n1.build(),
                TupleDomain.all(),
                TupleDomain.all(),
                Optional.empty()
        );


        System.out.println("DEBUG n1 build finish");
        System.out.println("n1 output variables: " +
                n1.getOutputVariables().stream().map(v -> v.getName()).collect(Collectors.toList()));

        // --- TableScanNode End: n1 ---
// --- FilterNode Start: n1child ---
        // Filter logic: ((l_shipdate >= DATE '1993-10-01'(0) without time zone) AND (l_shipdate < DATE '1993-11-01'))
        // 注意：getRowExpression 内部会处理从 SQL 字符串到 RowExpression 的转换
//        RowExpression predicate_n1child = getRowExpression("((l_shipdate >= DATE '1993-10-01'(0) without time zone) AND (l_shipdate < DATE '1993-11-01'))");
        RowExpression predicate_n1child = getRowExpression(
                "(l_shipdate >= DATE '1993-10-01') AND (l_shipdate < DATE '1993-11-01')");

        PlanNode n1child = new FilterNode(
                Optional.empty(),
                idAllocator.getNextId(),
                n1,
                predicate_n1child
        );


        System.out.println("DEBUG n1child build finish");
        System.out.println("n1child output variables: " +
                n1child.getOutputVariables().stream().map(v -> v.getName()).collect(Collectors.toList()));
        // --- FilterNode End: n1child ---
// --- TableScanNode Start: n2 (part) ---
        QualifiedObjectName tableName_n2 = new QualifiedObjectName("tpchstandard", "tiny", "part");
        Optional<TableHandle> tableHandle_n2 = metadata.getHandleVersion(
                getSession(),
                tableName_n2,
                Optional.empty()
        );

        if (!tableHandle_n2.isPresent()) {
            throw new RuntimeException("Table not found: " + tableName_n2);
        }

        TableHandle handle_n2 = tableHandle_n2.get();
        Map<String, ColumnHandle> columnHandles_n2 = metadata.getColumnHandles(getSession(), handle_n2);

        List<VariableReferenceExpression> outputVariables_n2 = Arrays.asList(
                p_type, p_partkey
        );

        ImmutableMap.Builder<VariableReferenceExpression, ColumnHandle> assignments_n2 = ImmutableMap.builder();

        assignments_n2.put(p_type, columnHandles_n2.get("p_type"));

        assignments_n2.put(p_partkey, columnHandles_n2.get("p_partkey"));


        PlanNode n2 = new TableScanNode(
                Optional.empty(),
                idAllocator.getNextId(),
                handle_n2,
                outputVariables_n2,
                assignments_n2.build(),
                TupleDomain.all(),
                TupleDomain.all(),
                Optional.empty()
        );


        System.out.println("DEBUG n2 build finish");
        System.out.println("n2 output variables: " +
                n2.getOutputVariables().stream().map(v -> v.getName()).collect(Collectors.toList()));

        // --- TableScanNode End: n2 ---
// --- JoinNode Start: n3 ---
        // Join Filter 逻辑:

        Optional<RowExpression> filter_n3 = Optional.empty();


        List<EquiJoinClause> criteria_n3 = Arrays.asList(

                new EquiJoinClause(l_partkey, p_partkey)

        );

        // 使用 LinkedHashSet 保持顺序并去重
        Set<VariableReferenceExpression> outputSet_n3 = new LinkedHashSet<>();
        outputSet_n3.addAll(n1child.getOutputVariables());
        outputSet_n3.addAll(n2.getOutputVariables());

        List<VariableReferenceExpression> outputVars_n3 = ImmutableList.copyOf(outputSet_n3);

        JoinNode n3 = new JoinNode(
                Optional.empty(),
                idAllocator.getNextId(),
                JoinType.INNER,
                n1child,
                n2,
                criteria_n3,
                outputVars_n3,
                filter_n3,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                ImmutableMap.of()
        );


        System.out.println("DEBUG n3 build finish");
        System.out.println("n3 output variables: " +
                n3.getOutputVariables().stream().map(v -> v.getName()).collect(Collectors.toList()));

// --- ProjectNode Start: n3child ---
        Assignments.Builder assignments_n3child = Assignments.builder();

        // 1. 处理计算列 (公式)

        // 计算:
        assignments_n3child.put(revenue_input , getRowExpression("l_extendedprice * (CAST(1 AS DOUBLE) - l_discount)"));
        assignments_n3child.put(promo_revenue_input , getRowExpression("CASE WHEN p_type LIKE 'PROMO%' THEN l_extendedprice * (CAST(1 AS DOUBLE) - l_discount) ELSE 0E0 END"));


        ProjectNode n3child = new ProjectNode(
                idAllocator.getNextId(),
                n3,
                assignments_n3child.build()
        );


        System.out.println("DEBUG n3child build finish");
        System.out.println("n3child output variables: " +
                n3child.getOutputVariables().stream().map(v -> v.getName()).collect(Collectors.toList()));

        // --- ProjectNode End: n3child ---
// --- AggregationNode Start: n4 ---
        // 1. 定义 Grouping Keys
        List<VariableReferenceExpression> groupingKeys_n4 = Arrays.asList(

        );

        Map<VariableReferenceExpression, AggregationNode.Aggregation> aggregations_n4 = new HashMap<>();


        // 这个agg.expression要是sourcenode定义的变量
        RowExpression raw_sum_promo_revenue_sum = getRowExpression("sum(promo_revenue_input)");

        // 包装成 Aggregation 对象, agg.output_var需要自己进行字段定义
        aggregations_n4.put(promo_revenue_sum, new AggregationNode.Aggregation(
                (CallExpression) raw_sum_promo_revenue_sum,
                Optional.empty(), Optional.empty(), false, Optional.empty()
        ));

        // 这个agg.expression要是sourcenode定义的变量
        RowExpression raw_sum_total_revenue_sum = getRowExpression("sum(revenue_input)");

        // 包装成 Aggregation 对象, agg.output_var需要自己进行字段定义
        aggregations_n4.put(total_revenue_sum, new AggregationNode.Aggregation(
                (CallExpression) raw_sum_total_revenue_sum,
                Optional.empty(), Optional.empty(), false, Optional.empty()
        ));



        Set<Integer> globalSets_n4 = groupingKeys_n4.isEmpty() ?
                ImmutableSet.of(0) : ImmutableSet.of();

        AggregationNode.GroupingSetDescriptor groupingSets_n4 = new AggregationNode.GroupingSetDescriptor(
                groupingKeys_n4,
                1,
                globalSets_n4
        );

        AggregationNode n4 = new AggregationNode(
                Optional.empty(),                // sourceLocation
                idAllocator.getNextId(),         // id
                n3child,               // source (来自上一层算子)
                aggregations_n4,      // aggregations Map
                groupingSets_n4,      // groupingSets
                ImmutableList.of(),              // preGroupedVariables
                AggregationNode.Step.SINGLE,     // step
                Optional.empty(),                // hashVariable
                Optional.empty(),                // groupIdVariable
                Optional.<Integer>empty()        // aggregationId
        );

        System.out.println("DEBUG n4 build finish");
        System.out.println("n4 output variables: " +
                n4.getOutputVariables().stream().map(v -> v.getName()).collect(Collectors.toList()));

        // --- AggregationNode End: n4 ---

        // N4-CHILD
        // --- 3. Project 层 (Post-Aggregation: 最终比例计算) ---
        Assignments.Builder post_agg_assign = Assignments.builder();
        // 计算 100.00 * sum_promo / sum_total
        post_agg_assign.put(q14_final_result, getRowExpression(
                "100E0 * promo_revenue_sum / total_revenue_sum"
        ));

        ProjectNode n4_post_project = new ProjectNode(idAllocator.getNextId(), n4, post_agg_assign.build());


    // --- OutputNode Start: root ---
        List<String> columnNames_root = Arrays.asList(
                "q14_final_result"
        );

        List<VariableReferenceExpression> outputVars_root = Arrays.asList(
                q14_final_result
        );

        PlanNode root = new OutputNode(
                Optional.empty(),
                idAllocator.getNextId(),
                n4_post_project,           // 通常是 sortNode 或 aggregationNode
                columnNames_root,
                outputVars_root
        );


        System.out.println("DEBUG root build finish");
        System.out.println("root output variables: " +
                root.getOutputVariables().stream().map(v -> v.getName()).collect(Collectors.toList()));

        // --- OutputNode End: root ---
        return root;
    }

    public PlanNode getQ17() {
        // --- 自动生成的变量定义 ---

        VariableReferenceExpression l_extendedprice = analyzerContext.getVariableAllocator().newVariable("l_extendedprice", DoubleType.DOUBLE);

        VariableReferenceExpression l_partkey = analyzerContext.getVariableAllocator().newVariable("l_partkey", BigintType.BIGINT);
        VariableReferenceExpression l_partkey_n4 = analyzerContext.getVariableAllocator().newVariable("l_partkey_n4", BigintType.BIGINT);

        VariableReferenceExpression l_quantity = analyzerContext.getVariableAllocator().newVariable("l_quantity", DoubleType.DOUBLE);
        VariableReferenceExpression l_quantity_n4 = analyzerContext.getVariableAllocator().newVariable("l_quantity_n4", DoubleType.DOUBLE);

        VariableReferenceExpression p_partkey = analyzerContext.getVariableAllocator().newVariable("p_partkey", BigintType.BIGINT);
        VariableReferenceExpression p_brand = analyzerContext.getVariableAllocator().newVariable("p_brand", VarcharType.VARCHAR);
        VariableReferenceExpression p_container = analyzerContext.getVariableAllocator().newVariable("p_container", VarcharType.VARCHAR);

        VariableReferenceExpression p_partkey_n5 = analyzerContext.getVariableAllocator().newVariable("p_partkey_n5", BigintType.BIGINT);
        VariableReferenceExpression p_brand_n5 = analyzerContext.getVariableAllocator().newVariable("p_brand_n5", VarcharType.VARCHAR);
        VariableReferenceExpression p_container_n5 = analyzerContext.getVariableAllocator().newVariable("p_container_n5", VarcharType.VARCHAR);

        VariableReferenceExpression sum_l_extendedprice____0 = analyzerContext.getVariableAllocator().newVariable("sum_l_extendedprice", DoubleType.DOUBLE);

        System.out.println("Variable object string: " + sum_l_extendedprice____0.getName());
        System.out.println("Variable string length: " + sum_l_extendedprice____0.getName().length());

        VariableReferenceExpression sum_sum_l_extendedprice____0 = analyzerContext.getVariableAllocator().newVariable("sum_sum_l_extendedprice____0", DoubleType.DOUBLE);

        // for n7
        VariableReferenceExpression avg_2___avg_l_quantity = analyzerContext.getVariableAllocator().newVariable("avg_2___avg_l_quantity", DoubleType.DOUBLE);
        VariableReferenceExpression avg_l_quantity_n4 = analyzerContext.getVariableAllocator().newVariable("avg_l_quantity_n4", DoubleType.DOUBLE);



// --- TableScanNode Start: n1 (lineitem) ---
        QualifiedObjectName tableName_n1 = new QualifiedObjectName("tpchstandard", "tiny", "lineitem");
        Optional<TableHandle> tableHandle_n1 = metadata.getHandleVersion(
                getSession(),
                tableName_n1,
                Optional.empty()
        );

        if (!tableHandle_n1.isPresent()) {
            throw new RuntimeException("Table not found: " + tableName_n1);
        }

        TableHandle handle_n1 = tableHandle_n1.get();
        Map<String, ColumnHandle> columnHandles_n1 = metadata.getColumnHandles(getSession(), handle_n1);

        List<VariableReferenceExpression> outputVariables_n1 = Arrays.asList(
                l_extendedprice, l_partkey, l_quantity
        );

        ImmutableMap.Builder<VariableReferenceExpression, ColumnHandle> assignments_n1 = ImmutableMap.builder();

        assignments_n1.put(l_extendedprice, columnHandles_n1.get("l_extendedprice"));

        assignments_n1.put(l_partkey, columnHandles_n1.get("l_partkey"));

        assignments_n1.put(l_quantity, columnHandles_n1.get("l_quantity"));


        PlanNode n1 = new TableScanNode(
                Optional.empty(),
                idAllocator.getNextId(),
                handle_n1,
                outputVariables_n1,
                assignments_n1.build(),
                TupleDomain.all(),
                TupleDomain.all(),
                Optional.empty()
        );
        // --- TableScanNode End: n1 ---
// --- TableScanNode Start: n2 (part) ---
        QualifiedObjectName tableName_n2 = new QualifiedObjectName("tpchstandard", "tiny", "part");
        Optional<TableHandle> tableHandle_n2 = metadata.getHandleVersion(
                getSession(),
                tableName_n2,
                Optional.empty()
        );

        if (!tableHandle_n2.isPresent()) {
            throw new RuntimeException("Table not found: " + tableName_n2);
        }

        TableHandle handle_n2 = tableHandle_n2.get();
        Map<String, ColumnHandle> columnHandles_n2 = metadata.getColumnHandles(getSession(), handle_n2);

        List<VariableReferenceExpression> outputVariables_n2 = Arrays.asList(
                p_partkey, p_brand, p_container
        );

        ImmutableMap.Builder<VariableReferenceExpression, ColumnHandle> assignments_n2 = ImmutableMap.builder();

        assignments_n2.put(p_partkey, columnHandles_n2.get("p_partkey"));
        assignments_n2.put(p_brand, columnHandles_n2.get("p_brand"));
        assignments_n2.put(p_container, columnHandles_n2.get("p_container"));


        PlanNode n2 = new TableScanNode(
                Optional.empty(),
                idAllocator.getNextId(),
                handle_n2,
                outputVariables_n2,
                assignments_n2.build(),
                TupleDomain.all(),
                TupleDomain.all(),
                Optional.empty()
        );
        // --- TableScanNode End: n2 ---
// --- FilterNode Start: n2child ---
        // Filter logic: ((p_brand = 'Brand#23') AND (p_container = 'SM JAR'))
        // 注意：getRowExpression 内部会处理从 SQL 字符串到 RowExpression 的转换
        RowExpression predicate_n2child = getRowExpression("((p_brand = CAST('Brand#23' AS VARCHAR(10)) AND (p_container = CAST('SM JAR' AS VARCHAR(10)))))");

        PlanNode n2child = new FilterNode(
                Optional.empty(),
                idAllocator.getNextId(),
                n2,
                predicate_n2child
        );
        // --- FilterNode End: n2child ---
// --- JoinNode Start: n3 ---
        // Join Filter 逻辑:

        Optional<RowExpression> filter_n3 = Optional.empty();


        List<EquiJoinClause> criteria_n3 = Arrays.asList(

                new EquiJoinClause(l_partkey, p_partkey)

        );

        // 使用 LinkedHashSet 保持顺序并去重
        Set<VariableReferenceExpression> outputSet_n3 = new LinkedHashSet<>();
        outputSet_n3.addAll(n1.getOutputVariables());
        outputSet_n3.addAll(n2child.getOutputVariables());

        List<VariableReferenceExpression> outputVars_n3 = ImmutableList.copyOf(outputSet_n3);

        JoinNode n3 = new JoinNode(
                Optional.empty(),
                idAllocator.getNextId(),
                JoinType.INNER,
                n1,
                n2child,
                criteria_n3,
                outputVars_n3,
                filter_n3,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                ImmutableMap.of()
        );
// --- TableScanNode Start: n4 (lineitem) ---
        QualifiedObjectName tableName_n4 = new QualifiedObjectName("tpchstandard", "tiny", "lineitem");
        Optional<TableHandle> tableHandle_n4 = metadata.getHandleVersion(
                getSession(),
                tableName_n4,
                Optional.empty()
        );

        if (!tableHandle_n4.isPresent()) {
            throw new RuntimeException("Table not found: " + tableName_n4);
        }

        TableHandle handle_n4 = tableHandle_n4.get();
        Map<String, ColumnHandle> columnHandles_n4 = metadata.getColumnHandles(getSession(), handle_n4);

        List<VariableReferenceExpression> outputVariables_n4 = Arrays.asList(
                l_partkey_n4, l_quantity_n4
        );

        ImmutableMap.Builder<VariableReferenceExpression, ColumnHandle> assignments_n4 = ImmutableMap.builder();

        assignments_n4.put(l_partkey_n4, columnHandles_n4.get("l_partkey"));

        assignments_n4.put(l_quantity_n4, columnHandles_n4.get("l_quantity"));


        PlanNode n4 = new TableScanNode(
                Optional.empty(),
                idAllocator.getNextId(),
                handle_n4,
                outputVariables_n4,
                assignments_n4.build(),
                TupleDomain.all(),
                TupleDomain.all(),
                Optional.empty()
        );
        // --- TableScanNode End: n4 ---
// --- TableScanNode Start: n5 (part) ---
        QualifiedObjectName tableName_n5 = new QualifiedObjectName("tpchstandard", "tiny", "part");
        Optional<TableHandle> tableHandle_n5 = metadata.getHandleVersion(
                getSession(),
                tableName_n5,
                Optional.empty()
        );

        if (!tableHandle_n5.isPresent()) {
            throw new RuntimeException("Table not found: " + tableName_n5);
        }

        TableHandle handle_n5 = tableHandle_n5.get();
        Map<String, ColumnHandle> columnHandles_n5 = metadata.getColumnHandles(getSession(), handle_n5);

        List<VariableReferenceExpression> outputVariables_n5 = Arrays.asList(
                p_partkey_n5, p_brand_n5, p_container_n5
        );

        ImmutableMap.Builder<VariableReferenceExpression, ColumnHandle> assignments_n5 = ImmutableMap.builder();

        assignments_n5.put(p_partkey_n5, columnHandles_n5.get("p_partkey"));
        assignments_n5.put(p_brand_n5, columnHandles_n5.get("p_brand"));
        assignments_n5.put(p_container_n5, columnHandles_n5.get("p_container"));


        PlanNode n5 = new TableScanNode(
                Optional.empty(),
                idAllocator.getNextId(),
                handle_n5,
                outputVariables_n5,
                assignments_n5.build(),
                TupleDomain.all(),
                TupleDomain.all(),
                Optional.empty()
        );
        System.out.println("DEBUG n5 build finish");
        System.out.println("n5 output variables: " +
                n5.getOutputVariables().stream().map(v -> v.getName()).collect(Collectors.toList()));


        // --- TableScanNode End: n5 ---
// --- FilterNode Start: n5child ---
        // Filter logic: ((p_brand = 'Brand#23') AND (p_container = 'SM JAR'))
        // 注意：getRowExpression 内部会处理从 SQL 字符串到 RowExpression 的转换
        RowExpression predicate_n5child = getRowExpression("((p_brand_n5 = CAST('Brand#23' AS VARCHAR(10)) AND (p_container_n5 = CAST('SM JAR' AS VARCHAR(10)))))");

        PlanNode n5child = new FilterNode(
                Optional.empty(),
                idAllocator.getNextId(),
                n5,
                predicate_n5child
        );
        // --- FilterNode End: n5child ---
// --- JoinNode Start: n6 ---
        // 1. 创建布尔输出变量
        VariableReferenceExpression semiMatchVar = analyzerContext.getVariableAllocator()
                .newVariable("semi_match_n6", BooleanType.BOOLEAN);

        // 2. 调用 11 参数构造函数
        SemiJoinNode n6Semi = new SemiJoinNode(
                Optional.empty(),                // 1. sourceLocation
                idAllocator.getNextId(),         // 2. id
                n4,                              // 3. source (左子树)
                n5child,                     // 4. filteringSource (右子树)
                l_partkey_n4,                       // 5. sourceJoinVariable (左键)
                p_partkey_n5,                       // 6. filteringSourceJoinVariable (右键)
                semiMatchVar,                    // 7. semiJoinOutput (结果列)
                Optional.empty(),                // 8. sourceHashVariable
                Optional.empty(),                // 9. filteringSourceHashVariable
                Optional.empty(),                // 10. distributionType (Optional<DistributionType>)
                ImmutableMap.of()                // 11. dynamicFilters (Map<String, VariableReferenceExpression>)
        );

        // 3. 必须紧跟一个 FilterNode 来实现真正的 "IN" 语义
        PlanNode n6_child = new FilterNode(
                Optional.empty(),
                idAllocator.getNextId(),
                n6Semi,
                semiMatchVar                     // 谓词就是刚才那个布尔变量
        );

        // --- AggregationNode Start: n7 ---
        // 1. 定义 Grouping Keys
        List<VariableReferenceExpression> groupingKeys_n7 = Arrays.asList(
                l_partkey_n4
        );

        Map<VariableReferenceExpression, AggregationNode.Aggregation> aggregations_n7 = new HashMap<>();


        // 这个agg.expression要是sourcenode定义的变量
//        RowExpression raw_avg_2___avg_l_quantity = getRowExpression("avg(l_quantity_n4) * CAST(0.2 AS DOUBLE)");
//
//        // 包装成 Aggregation 对象, agg.output_var需要自己进行字段定义
//        aggregations_n7.put(avg_2___avg_l_quantity, new AggregationNode.Aggregation(
//                (CallExpression) raw_avg_2___avg_l_quantity,
//                Optional.empty(), Optional.empty(), false, Optional.empty()
//        ));

//        // 这个agg.expression要是sourcenode定义的变量
//        RowExpression raw_l_partkey = getRowExpression("l_partkey");
//
//        // 包装成 Aggregation 对象, agg.output_var需要自己进行字段定义
//        aggregations_n7.put(l_partkey, new AggregationNode.Aggregation(
//                (CallExpression) raw_l_partkey,
//                Optional.empty(), Optional.empty(), false, Optional.empty()
//        ));

        // 这个agg.expression要是sourcenode定义的变量
        RowExpression raw_avg_l_quantity_n4 = getRowExpression("avg(l_quantity_n4)");

        // 包装成 Aggregation 对象, agg.output_var需要自己进行字段定义
        aggregations_n7.put(avg_l_quantity_n4, new AggregationNode.Aggregation(
                (CallExpression) raw_avg_l_quantity_n4,
                Optional.empty(), Optional.empty(), false, Optional.empty()
        ));


        Set<Integer> globalSets_n7 = groupingKeys_n7.isEmpty() ?
                ImmutableSet.of(0) : ImmutableSet.of();

        AggregationNode.GroupingSetDescriptor groupingSets_n7 = new AggregationNode.GroupingSetDescriptor(
                groupingKeys_n7,
                1,
                globalSets_n7
        );

        AggregationNode n7 = new AggregationNode(
                Optional.empty(),                // sourceLocation
                idAllocator.getNextId(),         // id
                n6_child,               // source (来自上一层算子)
                aggregations_n7,      // aggregations Map
                groupingSets_n7,      // groupingSets
                ImmutableList.of(),              // preGroupedVariables
                AggregationNode.Step.SINGLE,     // step
                Optional.empty(),                // hashVariable
                Optional.empty(),                // groupIdVariable
                Optional.<Integer>empty()        // aggregationId
        );
        // --- AggregationNode End: n7 ---



        Assignments.Builder assignments_n8 = Assignments.builder();
// 透传分组键
        assignments_n8.put(l_partkey_n4, l_partkey_n4);
// 执行乘法逻辑 (注意：这里要加空格)
        assignments_n8.put(avg_2___avg_l_quantity, getRowExpression("avg_l_quantity_n4 * 0.2E0"));

        ProjectNode n8 = new ProjectNode(
                idAllocator.getNextId(),
                n7, // 挂在聚合节点之上
                assignments_n8.build()
        );



// --- JoinNode Start: n9 ---
        // Join Filter 逻辑: (public.lineitem.l_quantity < (.2 * subquery."?column?"))...

        RowExpression join_filter_n9 = getRowExpression("(l_quantity < avg_2___avg_l_quantity)");
        Optional<RowExpression> filter_n9 = Optional.of(join_filter_n9);


        List<EquiJoinClause> criteria_n9 = Arrays.asList(

                new EquiJoinClause(p_partkey, l_partkey_n4)

        );

        // 使用 LinkedHashSet 保持顺序并去重
        Set<VariableReferenceExpression> outputSet_n9 = new LinkedHashSet<>();
        outputSet_n9.addAll(n3.getOutputVariables());
        outputSet_n9.addAll(n8.getOutputVariables());

        List<VariableReferenceExpression> outputVars_n9 = ImmutableList.copyOf(outputSet_n9);

        JoinNode n9 = new JoinNode(
                Optional.empty(),
                idAllocator.getNextId(),
                JoinType.INNER,
                n3,
                n8,
                criteria_n9,
                outputVars_n9,
                filter_n9,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                ImmutableMap.of()
        );

// --- AggregationNode Start: n10 ---
        // 1. 定义 Grouping Keys
        List<VariableReferenceExpression> groupingKeys_n10 = Arrays.asList(

        );

        Map<VariableReferenceExpression, AggregationNode.Aggregation> aggregations_n10 = new HashMap<>();


        // 这个agg.expression要是sourcenode定义的变量
        RowExpression raw_sum_l_extendedprice____0 = getRowExpression("sum(l_extendedprice)");

        // 包装成 Aggregation 对象, agg.output_var需要自己进行字段定义
        aggregations_n10.put(sum_l_extendedprice____0, new AggregationNode.Aggregation(
                (CallExpression) raw_sum_l_extendedprice____0,
                Optional.empty(), Optional.empty(), false, Optional.empty()
        ));


        Set<Integer> globalSets_n10 = groupingKeys_n10.isEmpty() ?
                ImmutableSet.of(0) : ImmutableSet.of();

        AggregationNode.GroupingSetDescriptor groupingSets_n10 = new AggregationNode.GroupingSetDescriptor(
                groupingKeys_n10,
                1,
                globalSets_n10
        );

        AggregationNode n10 = new AggregationNode(
                Optional.empty(),                // sourceLocation
                idAllocator.getNextId(),         // id
                n9,               // source (来自上一层算子)
                aggregations_n10,      // aggregations Map
                groupingSets_n10,      // groupingSets
                ImmutableList.of(),              // preGroupedVariables
                AggregationNode.Step.SINGLE,     // step
                Optional.empty(),                // hashVariable
                Optional.empty(),                // groupIdVariable
                Optional.<Integer>empty()        // aggregationId
        );
        // --- AggregationNode End: n10 ---

        System.out.println("DEBUG n10");
        System.out.println("n10 output variables: " +
                n10.getOutputVariables().stream().map(v -> v.getName()).collect(Collectors.toList()));


        // --- ProjectNode Start: n10child ---
        Assignments.Builder assignments_n10child = Assignments.builder();

        // 1. 处理计算列 (公式)

        // 计算:
        assignments_n10child.put(sum_sum_l_extendedprice____0, getRowExpression("sum_l_extendedprice / 7E0"));

        ProjectNode n10child = new ProjectNode(
                idAllocator.getNextId(),
                n10,
                assignments_n10child.build()
        );
        // --- ProjectNode End: assignments_n10child ---



// --- OutputNode Start: root ---
        List<String> columnNames_root = Arrays.asList(
                "sum_sum_l_extendedprice____0"
        );

        List<VariableReferenceExpression> outputVars_root = Arrays.asList(
                sum_sum_l_extendedprice____0
        );

        PlanNode root = new OutputNode(
                Optional.empty(),
                idAllocator.getNextId(),
                n10child,           // 通常是 sortNode 或 aggregationNode
                columnNames_root,
                outputVars_root
        );
        // --- OutputNode End: root ---
        return root;
    }

    public PlanNode getQ18() {
// --- 自动生成的变量定义 ---

        VariableReferenceExpression l_quantity = analyzerContext.getVariableAllocator().newVariable("l_quantity", DoubleType.DOUBLE);

        VariableReferenceExpression l_orderkey = analyzerContext.getVariableAllocator().newVariable("l_orderkey", BigintType.BIGINT);

        VariableReferenceExpression l_orderkey_n3 = analyzerContext.getVariableAllocator().newVariable("l_orderkey_n3", BigintType.BIGINT);

        VariableReferenceExpression o_orderkey = analyzerContext.getVariableAllocator().newVariable("o_orderkey", BigintType.BIGINT);

        VariableReferenceExpression o_orderdate = analyzerContext.getVariableAllocator().newVariable("o_orderdate", DateType.DATE);

        VariableReferenceExpression o_totalprice = analyzerContext.getVariableAllocator().newVariable("o_totalprice", DoubleType.DOUBLE);

        VariableReferenceExpression o_custkey = analyzerContext.getVariableAllocator().newVariable("o_custkey", BigintType.BIGINT);

        VariableReferenceExpression c_name = analyzerContext.getVariableAllocator().newVariable("c_name", VarcharType.VARCHAR);

        VariableReferenceExpression c_custkey = analyzerContext.getVariableAllocator().newVariable("c_custkey", BigintType.BIGINT);

        VariableReferenceExpression sum_l_quantity = analyzerContext.getVariableAllocator().newVariable("sum_l_quantity", DoubleType.DOUBLE);

// --- TableScanNode Start: n1 (lineitem) ---
        QualifiedObjectName tableName_n1 = new QualifiedObjectName("tpchstandard", "tiny", "lineitem");
        Optional<TableHandle> tableHandle_n1 = metadata.getHandleVersion(
                getSession(),
                tableName_n1,
                Optional.empty()
        );

        if (!tableHandle_n1.isPresent()) {
            throw new RuntimeException("Table not found: " + tableName_n1);
        }

        TableHandle handle_n1 = tableHandle_n1.get();
        Map<String, ColumnHandle> columnHandles_n1 = metadata.getColumnHandles(getSession(), handle_n1);

        List<VariableReferenceExpression> outputVariables_n1 = Arrays.asList(
                l_quantity, l_orderkey
        );

        ImmutableMap.Builder<VariableReferenceExpression, ColumnHandle> assignments_n1 = ImmutableMap.builder();

        assignments_n1.put(l_quantity, columnHandles_n1.get("l_quantity"));

        assignments_n1.put(l_orderkey, columnHandles_n1.get("l_orderkey"));


        PlanNode n1 = new TableScanNode(
                Optional.empty(),
                idAllocator.getNextId(),
                handle_n1,
                outputVariables_n1,
                assignments_n1.build(),
                TupleDomain.all(),
                TupleDomain.all(),
                Optional.empty()
        );
        // --- TableScanNode End: n1 ---
// --- TableScanNode Start: n2 (orders) ---
        QualifiedObjectName tableName_n2 = new QualifiedObjectName("tpchstandard", "tiny", "orders");
        Optional<TableHandle> tableHandle_n2 = metadata.getHandleVersion(
                getSession(),
                tableName_n2,
                Optional.empty()
        );

        if (!tableHandle_n2.isPresent()) {
            throw new RuntimeException("Table not found: " + tableName_n2);
        }

        TableHandle handle_n2 = tableHandle_n2.get();
        Map<String, ColumnHandle> columnHandles_n2 = metadata.getColumnHandles(getSession(), handle_n2);

        List<VariableReferenceExpression> outputVariables_n2 = Arrays.asList(
                o_orderkey, o_orderdate, o_totalprice, o_custkey
        );

        ImmutableMap.Builder<VariableReferenceExpression, ColumnHandle> assignments_n2 = ImmutableMap.builder();

        assignments_n2.put(o_orderkey, columnHandles_n2.get("o_orderkey"));

        assignments_n2.put(o_orderdate, columnHandles_n2.get("o_orderdate"));

        assignments_n2.put(o_totalprice, columnHandles_n2.get("o_totalprice"));

        assignments_n2.put(o_custkey, columnHandles_n2.get("o_custkey"));


        PlanNode n2 = new TableScanNode(
                Optional.empty(),
                idAllocator.getNextId(),
                handle_n2,
                outputVariables_n2,
                assignments_n2.build(),
                TupleDomain.all(),
                TupleDomain.all(),
                Optional.empty()
        );
        // --- TableScanNode End: n2 ---
// --- TableScanNode Start: n3 (lineitem) ---
        QualifiedObjectName tableName_n3 = new QualifiedObjectName("tpchstandard", "tiny", "lineitem");
        Optional<TableHandle> tableHandle_n3 = metadata.getHandleVersion(
                getSession(),
                tableName_n3,
                Optional.empty()
        );

        if (!tableHandle_n3.isPresent()) {
            throw new RuntimeException("Table not found: " + tableName_n3);
        }

        TableHandle handle_n3 = tableHandle_n3.get();
        Map<String, ColumnHandle> columnHandles_n3 = metadata.getColumnHandles(getSession(), handle_n3);

        List<VariableReferenceExpression> outputVariables_n3 = Arrays.asList(
                l_orderkey_n3, l_quantity
        );

        ImmutableMap.Builder<VariableReferenceExpression, ColumnHandle> assignments_n3 = ImmutableMap.builder();

        assignments_n3.put(l_orderkey_n3, columnHandles_n3.get("l_orderkey"));

        assignments_n3.put(l_quantity, columnHandles_n3.get("l_quantity"));


        PlanNode n3 = new TableScanNode(
                Optional.empty(),
                idAllocator.getNextId(),
                handle_n3,
                outputVariables_n3,
                assignments_n3.build(),
                TupleDomain.all(),
                TupleDomain.all(),
                Optional.empty()
        );
        // --- TableScanNode End: n3 ---
// --- AggregationNode Start: n4 ---
        // 1. 定义 Grouping Keys
        List<VariableReferenceExpression> groupingKeys_n4 = Arrays.asList(
                l_orderkey_n3
        );

        Map<VariableReferenceExpression, AggregationNode.Aggregation> aggregations_n4 = new HashMap<>();


//        // 这个agg.expression要是sourcenode定义的变量
//        RowExpression raw_l_orderkey = getRowExpression("l_orderkey");
//
//        // 包装成 Aggregation 对象, agg.output_var需要自己进行字段定义
//        aggregations_n4.put(l_orderkey, new AggregationNode.Aggregation(
//                (CallExpression) raw_l_orderkey,
//                Optional.empty(), Optional.empty(), false, Optional.empty()
//        ));


        Set<Integer> globalSets_n4 = groupingKeys_n4.isEmpty() ?
                ImmutableSet.of(0) : ImmutableSet.of();

        AggregationNode.GroupingSetDescriptor groupingSets_n4 = new AggregationNode.GroupingSetDescriptor(
                groupingKeys_n4,
                1,
                globalSets_n4
        );

        AggregationNode n4 = new AggregationNode(
                Optional.empty(),                // sourceLocation
                idAllocator.getNextId(),         // id
                n3,               // source (来自上一层算子)
                aggregations_n4,      // aggregations Map
                groupingSets_n4,      // groupingSets
                ImmutableList.of(),              // preGroupedVariables
                AggregationNode.Step.SINGLE,     // step
                Optional.empty(),                // hashVariable
                Optional.empty(),                // groupIdVariable
                Optional.<Integer>empty()        // aggregationId
        );
        // --- AggregationNode End: n4 ---
        System.out.println("DEBUG n4");
        System.out.println("DEBUG n4 output variables: " +
                n4.getOutputVariables().stream().map(v -> v.getName()).collect(Collectors.toList()));
// --- JoinNode Start: n5 ---
        // Join Filter 逻辑:

        Optional<RowExpression> filter_n5 = Optional.empty();


        List<EquiJoinClause> criteria_n5 = Arrays.asList(

                new EquiJoinClause(o_orderkey, l_orderkey_n3)

        );

        // 使用 LinkedHashSet 保持顺序并去重
        Set<VariableReferenceExpression> outputSet_n5 = new LinkedHashSet<>();
        outputSet_n5.addAll(n2.getOutputVariables());
        outputSet_n5.addAll(n4.getOutputVariables());

        List<VariableReferenceExpression> outputVars_n5 = ImmutableList.copyOf(outputSet_n5);

        JoinNode n5 = new JoinNode(
                Optional.empty(),
                idAllocator.getNextId(),
                JoinType.INNER,
                n2,
                n4,
                criteria_n5,
                outputVars_n5,
                filter_n5,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                ImmutableMap.of()
        );
        System.out.println("DEBUG n5");
        System.out.println("DEBUG n5 output variables: " +
                n5.getOutputVariables().stream().map(v -> v.getName()).collect(Collectors.toList()));
// --- JoinNode Start: n6 ---
        // Join Filter 逻辑:

        Optional<RowExpression> filter_n6 = Optional.empty();


        List<EquiJoinClause> criteria_n6 = Arrays.asList(

                new EquiJoinClause(l_orderkey, o_orderkey)

        );

        // 使用 LinkedHashSet 保持顺序并去重
        Set<VariableReferenceExpression> outputSet_n6 = new LinkedHashSet<>();
        outputSet_n6.addAll(n1.getOutputVariables());
        outputSet_n6.addAll(n5.getOutputVariables());

        List<VariableReferenceExpression> outputVars_n6 = ImmutableList.copyOf(outputSet_n6);

        JoinNode n6 = new JoinNode(
                Optional.empty(),
                idAllocator.getNextId(),
                JoinType.INNER,
                n1,
                n5,
                criteria_n6,
                outputVars_n6,
                filter_n6,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                ImmutableMap.of()
        );
        System.out.println("DEBUG n6");
        System.out.println("n6 output variables: " +
                n6.getOutputVariables().stream().map(v -> v.getName()).collect(Collectors.toList()));

// --- TableScanNode Start: n7 (customer) ---
        QualifiedObjectName tableName_n7 = new QualifiedObjectName("tpchstandard", "tiny", "customer");
        Optional<TableHandle> tableHandle_n7 = metadata.getHandleVersion(
                getSession(),
                tableName_n7,
                Optional.empty()
        );

        if (!tableHandle_n7.isPresent()) {
            throw new RuntimeException("Table not found: " + tableName_n7);
        }

        TableHandle handle_n7 = tableHandle_n7.get();
        Map<String, ColumnHandle> columnHandles_n7 = metadata.getColumnHandles(getSession(), handle_n7);

        List<VariableReferenceExpression> outputVariables_n7 = Arrays.asList(
                c_name, c_custkey
        );

        ImmutableMap.Builder<VariableReferenceExpression, ColumnHandle> assignments_n7 = ImmutableMap.builder();

        assignments_n7.put(c_name, columnHandles_n7.get("c_name"));

        assignments_n7.put(c_custkey, columnHandles_n7.get("c_custkey"));


        PlanNode n7 = new TableScanNode(
                Optional.empty(),
                idAllocator.getNextId(),
                handle_n7,
                outputVariables_n7,
                assignments_n7.build(),
                TupleDomain.all(),
                TupleDomain.all(),
                Optional.empty()
        );
        // --- TableScanNode End: n7 ---
        System.out.println("DEBUG n7");
// --- JoinNode Start: n8 ---
        // Join Filter 逻辑:

        Optional<RowExpression> filter_n8 = Optional.empty();


        List<EquiJoinClause> criteria_n8 = Arrays.asList(

                new EquiJoinClause(o_custkey, c_custkey)

        );

        // 使用 LinkedHashSet 保持顺序并去重
        Set<VariableReferenceExpression> outputSet_n8 = new LinkedHashSet<>();
        outputSet_n8.addAll(n6.getOutputVariables());
        outputSet_n8.addAll(n7.getOutputVariables());

        List<VariableReferenceExpression> outputVars_n8 = ImmutableList.copyOf(outputSet_n8);

        JoinNode n8 = new JoinNode(
                Optional.empty(),
                idAllocator.getNextId(),
                JoinType.INNER,
                n6,
                n7,
                criteria_n8,
                outputVars_n8,
                filter_n8,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                ImmutableMap.of()
        );
        System.out.println("DEBUG n8");
        System.out.println("n8 output variables: " +
                n8.getOutputVariables().stream().map(v -> v.getName()).collect(Collectors.toList()));
// --- SortNode Start: n9 ---
        List<Ordering> orderBy_n9 = new ArrayList<>();

        orderBy_n9.add(new Ordering(o_totalprice, SortOrder.DESC_NULLS_LAST));

        orderBy_n9.add(new Ordering(o_orderdate, SortOrder.ASC_NULLS_LAST));

        orderBy_n9.add(new Ordering(c_name, SortOrder.ASC_NULLS_LAST));

        orderBy_n9.add(new Ordering(c_custkey, SortOrder.ASC_NULLS_LAST));

        orderBy_n9.add(new Ordering(o_orderkey, SortOrder.ASC_NULLS_LAST));


        OrderingScheme orderingScheme_n9 = new OrderingScheme(orderBy_n9);

        SortNode n9 = new SortNode(
                Optional.empty(),               // sourceLocation
                idAllocator.getNextId(),        // id
                n8,              // source (来自上一层算子)
                orderingScheme_n9,   // orderingScheme
                false, // isPartial
                ImmutableList.of()              // partitionBy
        );
        // --- SortNode End: n9 ---
        System.out.println("DEBUG n9");
        System.out.println("n9 output variables: " +
                n9.getOutputVariables().stream().map(v -> v.getName()).collect(Collectors.toList()));
// --- AggregationNode Start: n10 ---
        // 1. 定义 Grouping Keys
        List<VariableReferenceExpression> groupingKeys_n10 = Arrays.asList(
                o_totalprice, o_orderdate, c_name, c_custkey, o_orderkey
        );

        Map<VariableReferenceExpression, AggregationNode.Aggregation> aggregations_n10 = new HashMap<>();


//        // 这个agg.expression要是sourcenode定义的变量
//        RowExpression raw_c_name = getRowExpression("c_name");
//
//        // 包装成 Aggregation 对象, agg.output_var需要自己进行字段定义
//        aggregations_n10.put(c_name, new AggregationNode.Aggregation(
//                (CallExpression) raw_c_name,
//                Optional.empty(), Optional.empty(), false, Optional.empty()
//        ));
//
//        // 这个agg.expression要是sourcenode定义的变量
//        RowExpression raw_c_custkey = getRowExpression("c_custkey");
//
//        // 包装成 Aggregation 对象, agg.output_var需要自己进行字段定义
//        aggregations_n10.put(c_custkey, new AggregationNode.Aggregation(
//                (CallExpression) raw_c_custkey,
//                Optional.empty(), Optional.empty(), false, Optional.empty()
//        ));
//
//        // 这个agg.expression要是sourcenode定义的变量
//        RowExpression raw_o_orderkey = getRowExpression("o_orderkey");
//
//        // 包装成 Aggregation 对象, agg.output_var需要自己进行字段定义
//        aggregations_n10.put(o_orderkey, new AggregationNode.Aggregation(
//                (CallExpression) raw_o_orderkey,
//                Optional.empty(), Optional.empty(), false, Optional.empty()
//        ));
//
//        // 这个agg.expression要是sourcenode定义的变量
//        RowExpression raw_o_orderdate = getRowExpression("o_orderdate");
//
//        // 包装成 Aggregation 对象, agg.output_var需要自己进行字段定义
//        aggregations_n10.put(o_orderdate, new AggregationNode.Aggregation(
//                (CallExpression) raw_o_orderdate,
//                Optional.empty(), Optional.empty(), false, Optional.empty()
//        ));
//
//        // 这个agg.expression要是sourcenode定义的变量
//        RowExpression raw_o_totalprice = getRowExpression("o_totalprice");
//
//        // 包装成 Aggregation 对象, agg.output_var需要自己进行字段定义
//        aggregations_n10.put(o_totalprice, new AggregationNode.Aggregation(
//                (CallExpression) raw_o_totalprice,
//                Optional.empty(), Optional.empty(), false, Optional.empty()
//        ));
//
        // 这个agg.expression要是sourcenode定义的变量
        RowExpression raw_sum_l_quantity = getRowExpression("sum(l_quantity)");

        // 包装成 Aggregation 对象, agg.output_var需要自己进行字段定义
        aggregations_n10.put(sum_l_quantity, new AggregationNode.Aggregation(
                (CallExpression) raw_sum_l_quantity,
                Optional.empty(), Optional.empty(), false, Optional.empty()
        ));


        Set<Integer> globalSets_n10 = groupingKeys_n10.isEmpty() ?
                ImmutableSet.of(0) : ImmutableSet.of();

        AggregationNode.GroupingSetDescriptor groupingSets_n10 = new AggregationNode.GroupingSetDescriptor(
                groupingKeys_n10,
                1,
                globalSets_n10
        );

        AggregationNode n10 = new AggregationNode(
                Optional.empty(),                // sourceLocation
                idAllocator.getNextId(),         // id
                n9,               // source (来自上一层算子)
                aggregations_n10,      // aggregations Map
                groupingSets_n10,      // groupingSets
                ImmutableList.of(),              // preGroupedVariables
                AggregationNode.Step.SINGLE,     // step
                Optional.empty(),                // hashVariable
                Optional.empty(),                // groupIdVariable
                Optional.<Integer>empty()        // aggregationId
        );
        System.out.println("DEBUG n10");
        System.out.println("n10 output variables: " +
                n10.getOutputVariables().stream().map(v -> v.getName()).collect(Collectors.toList()));
        // --- AggregationNode End: n10 ---
// --- OutputNode Start: root ---
        List<String> columnNames_root = Arrays.asList(
                "c_name", "c_custkey", "o_orderkey", "o_orderdate", "o_totalprice", "sum_l_quantity"
        );

        List<VariableReferenceExpression> outputVars_root = Arrays.asList(
                c_name, c_custkey, o_orderkey, o_orderdate, o_totalprice, sum_l_quantity
        );

        PlanNode root = new OutputNode(
                Optional.empty(),
                idAllocator.getNextId(),
                n10,           // 通常是 sortNode 或 aggregationNode
                columnNames_root,
                outputVars_root
        );
        System.out.println("DEBUG root");
        // --- OutputNode End: root ---
        return root;
    }

    public PlanNode getQ19() {
// --- 自动生成的变量定义 ---

        VariableReferenceExpression l_extendedprice = analyzerContext.getVariableAllocator().newVariable("l_extendedprice", DoubleType.DOUBLE);

        VariableReferenceExpression l_discount = analyzerContext.getVariableAllocator().newVariable("l_discount", DoubleType.DOUBLE);

        VariableReferenceExpression l_partkey = analyzerContext.getVariableAllocator().newVariable("l_partkey", BigintType.BIGINT);

        VariableReferenceExpression l_quantity = analyzerContext.getVariableAllocator().newVariable("l_quantity", DoubleType.DOUBLE);

        VariableReferenceExpression l_shipmode = analyzerContext.getVariableAllocator().newVariable("l_shipmode", VarcharType.VARCHAR);

        VariableReferenceExpression l_shipinstruct = analyzerContext.getVariableAllocator().newVariable("l_shipinstruct", VarcharType.VARCHAR);

        VariableReferenceExpression p_partkey = analyzerContext.getVariableAllocator().newVariable("p_partkey", BigintType.BIGINT);

        VariableReferenceExpression p_brand = analyzerContext.getVariableAllocator().newVariable("p_brand", VarcharType.VARCHAR);

        VariableReferenceExpression p_container = analyzerContext.getVariableAllocator().newVariable("p_container", VarcharType.VARCHAR);

        VariableReferenceExpression p_size = analyzerContext.getVariableAllocator().newVariable("p_size", IntegerType.INTEGER);

        VariableReferenceExpression l_extendedprice____cast_1_as_double___l_discount = analyzerContext.getVariableAllocator().newVariable("l_extendedprice____cast_1_as_double___l_discount", DoubleType.DOUBLE);

        VariableReferenceExpression sum_l_extendedprice____cast_1_as_double___l_discount = analyzerContext.getVariableAllocator().newVariable("sum_l_extendedprice____cast_1_as_double___l_discount", DoubleType.DOUBLE);

// --- TableScanNode Start: n1 (lineitem) ---
        QualifiedObjectName tableName_n1 = new QualifiedObjectName("tpchstandard", "tiny", "lineitem");
        Optional<TableHandle> tableHandle_n1 = metadata.getHandleVersion(
                getSession(),
                tableName_n1,
                Optional.empty()
        );

        if (!tableHandle_n1.isPresent()) {
            throw new RuntimeException("Table not found: " + tableName_n1);
        }

        TableHandle handle_n1 = tableHandle_n1.get();
        Map<String, ColumnHandle> columnHandles_n1 = metadata.getColumnHandles(getSession(), handle_n1);

        List<VariableReferenceExpression> outputVariables_n1 = Arrays.asList(
                l_extendedprice, l_discount, l_partkey, l_quantity, l_shipmode, l_shipinstruct
        );

        ImmutableMap.Builder<VariableReferenceExpression, ColumnHandle> assignments_n1 = ImmutableMap.builder();

        assignments_n1.put(l_extendedprice, columnHandles_n1.get("l_extendedprice"));

        assignments_n1.put(l_discount, columnHandles_n1.get("l_discount"));

        assignments_n1.put(l_partkey, columnHandles_n1.get("l_partkey"));

        assignments_n1.put(l_quantity, columnHandles_n1.get("l_quantity"));

        assignments_n1.put(l_shipmode, columnHandles_n1.get("l_shipmode"));

        assignments_n1.put(l_shipinstruct, columnHandles_n1.get("l_shipinstruct"));


        PlanNode n1 = new TableScanNode(
                Optional.empty(),
                idAllocator.getNextId(),
                handle_n1,
                outputVariables_n1,
                assignments_n1.build(),
                TupleDomain.all(),
                TupleDomain.all(),
                Optional.empty()
        );
        // --- TableScanNode End: n1 ---
// --- FilterNode Start: n1child ---
        // Filter logic: ((l_shipmode = ANY ('{AIR,AIR REG}'[])) AND (l_shipinstruct = 'DELIVER IN PERSON'))
        // 注意：getRowExpression 内部会处理从 SQL 字符串到 RowExpression 的转换
//        RowExpression predicate_n1child = getRowExpression("((l_shipmode IN ('AIR', 'AIR REG')) AND (l_shipinstruct = 'DELIVER IN PERSON'))");
        RowExpression predicate_n1child = getRowExpression(
                "((l_shipmode IN (CAST('AIR' AS VARCHAR), CAST('AIR REG' AS VARCHAR))) AND " +
                        "(l_shipinstruct = CAST('DELIVER IN PERSON' AS VARCHAR)))"
        );

        PlanNode n1child = new FilterNode(
                Optional.empty(),
                idAllocator.getNextId(),
                n1,
                predicate_n1child
        );
        // --- FilterNode End: n1child ---
// --- TableScanNode Start: n2 (part) ---
        QualifiedObjectName tableName_n2 = new QualifiedObjectName("tpchstandard", "tiny", "part");
        Optional<TableHandle> tableHandle_n2 = metadata.getHandleVersion(
                getSession(),
                tableName_n2,
                Optional.empty()
        );

        if (!tableHandle_n2.isPresent()) {
            throw new RuntimeException("Table not found: " + tableName_n2);
        }

        TableHandle handle_n2 = tableHandle_n2.get();
        Map<String, ColumnHandle> columnHandles_n2 = metadata.getColumnHandles(getSession(), handle_n2);

        List<VariableReferenceExpression> outputVariables_n2 = Arrays.asList(
                p_partkey, p_brand, p_container, p_size
        );

        ImmutableMap.Builder<VariableReferenceExpression, ColumnHandle> assignments_n2 = ImmutableMap.builder();

        assignments_n2.put(p_partkey, columnHandles_n2.get("p_partkey"));

        assignments_n2.put(p_brand, columnHandles_n2.get("p_brand"));

        assignments_n2.put(p_container, columnHandles_n2.get("p_container"));

        assignments_n2.put(p_size, columnHandles_n2.get("p_size"));


        PlanNode n2 = new TableScanNode(
                Optional.empty(),
                idAllocator.getNextId(),
                handle_n2,
                outputVariables_n2,
                assignments_n2.build(),
                TupleDomain.all(),
                TupleDomain.all(),
                Optional.empty()
        );
        // --- TableScanNode End: n2 ---
// --- FilterNode Start: n2child ---
        // Filter logic: (p_size >= 1)
        // 注意：getRowExpression 内部会处理从 SQL 字符串到 RowExpression 的转换
        RowExpression predicate_n2child = getRowExpression("(p_size >= 1)");

        PlanNode n2child = new FilterNode(
                Optional.empty(),
                idAllocator.getNextId(),
                n2,
                predicate_n2child
        );
        // --- FilterNode End: n2child ---
// --- JoinNode Start: n3 ---
        // Join Filter 逻辑: (((part.p_brand = 'Brand#13'::bpchar) AND (part.p_container = ANY ('{"SM CASE","SM BOX","SM PACK","S...

//        RowExpression join_filter_n3 = getRowExpression("((p_brand = 'Brand#13') AND (p_container IN ('SM CASE', 'SM BOX', 'SM PACK', 'SM PKG')) AND (l_quantity >= 6) AND (l_quantity <= 16) AND (p_size <= 5) OR (p_brand = 'Brand#24') AND (p_container IN ('MED BAG', 'MED BOX', 'MED PKG', 'MED PACK')) AND (l_quantity >= 16) AND (l_quantity <= 26) AND (p_size <= 10) OR (p_brand = 'Brand#22') AND (p_container IN ('LG CASE', 'LG BOX', 'LG PACK', 'LG PKG')) AND (l_quantity >= 26) AND (l_quantity <= 36) AND (p_size <= 15))");
        RowExpression join_filter_n3 = getRowExpression(
                "((p_brand = CAST('Brand#13' AS VARCHAR)) AND " +
                        "(p_container IN (CAST('SM CASE' AS VARCHAR), CAST('SM BOX' AS VARCHAR), CAST('SM PACK' AS VARCHAR), CAST('SM PKG' AS VARCHAR))) AND " +
                        "(l_quantity >= 6) AND (l_quantity <= 16) AND (p_size <= 5)) " +
                        "OR ((p_brand = CAST('Brand#24' AS VARCHAR)) AND " +
                        "(p_container IN (CAST('MED BAG' AS VARCHAR), CAST('MED BOX' AS VARCHAR), CAST('MED PKG' AS VARCHAR), CAST('MED PACK' AS VARCHAR))) AND " +
                        "(l_quantity >= 16) AND (l_quantity <= 26) AND (p_size <= 10)) " +
                        "OR ((p_brand = CAST('Brand#22' AS VARCHAR)) AND " +
                        "(p_container IN (CAST('LG CASE' AS VARCHAR), CAST('LG BOX' AS VARCHAR), CAST('LG PACK' AS VARCHAR), CAST('LG PKG' AS VARCHAR))) AND " +
                        "(l_quantity >= 26) AND (l_quantity <= 36) AND (p_size <= 15))"
        );
        Optional<RowExpression> filter_n3 = Optional.of(join_filter_n3);


        List<EquiJoinClause> criteria_n3 = Arrays.asList(

                new EquiJoinClause(l_partkey, p_partkey)

        );

        // 核心：Join 节点的输出变量是左右子节点输出的并集
        List<VariableReferenceExpression> outputVars_n3 = ImmutableList.<VariableReferenceExpression>builder()
                .addAll(n1child.getOutputVariables())
                .addAll(n2child.getOutputVariables())
                .build();

        JoinNode n3 = new JoinNode(
                Optional.empty(),
                idAllocator.getNextId(),
                JoinType.INNER,
                n1child,
                n2child,
                criteria_n3,
                outputVars_n3, // 左右合并的变量
                filter_n3,      // <--- 关键修改：传入解析后的 Filter
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                ImmutableMap.of()
        );
// --- ProjectNode Start: n3child ---
        Assignments.Builder assignments_n3child = Assignments.builder();

        // 1. 处理计算列 (公式)

        // 计算:
        assignments_n3child.put(l_extendedprice____cast_1_as_double___l_discount, getRowExpression("(l_extendedprice * (cast(1 as double) - l_discount))"));


        ProjectNode n3child = new ProjectNode(
                idAllocator.getNextId(),
                n3,
                assignments_n3child.build()
        );
        // --- ProjectNode End: n3child ---
// --- AggregationNode Start: n4 ---
        // 1. 定义 Grouping Keys
        List<VariableReferenceExpression> groupingKeys_n4 = Arrays.asList(

        );

        Map<VariableReferenceExpression, AggregationNode.Aggregation> aggregations_n4 = new HashMap<>();


        // 这个agg.expression要是sourcenode定义的变量
        RowExpression raw_sum_l_extendedprice____cast_1_as_double___l_discount = getRowExpression("sum(l_extendedprice____cast_1_as_double___l_discount)");

        // 包装成 Aggregation 对象, agg.output_var需要自己进行字段定义
        aggregations_n4.put(sum_l_extendedprice____cast_1_as_double___l_discount, new AggregationNode.Aggregation(
                (CallExpression) raw_sum_l_extendedprice____cast_1_as_double___l_discount,
                Optional.empty(), Optional.empty(), false, Optional.empty()
        ));


        Set<Integer> globalSets_n4 = groupingKeys_n4.isEmpty() ?
                ImmutableSet.of(0) : ImmutableSet.of();

        AggregationNode.GroupingSetDescriptor groupingSets_n4 = new AggregationNode.GroupingSetDescriptor(
                groupingKeys_n4,
                1,
                globalSets_n4
        );

        AggregationNode n4 = new AggregationNode(
                Optional.empty(),                // sourceLocation
                idAllocator.getNextId(),         // id
                n3child,               // source (来自上一层算子)
                aggregations_n4,      // aggregations Map
                groupingSets_n4,      // groupingSets
                ImmutableList.of(),              // preGroupedVariables
                AggregationNode.Step.SINGLE,     // step
                Optional.empty(),                // hashVariable
                Optional.empty(),                // groupIdVariable
                Optional.<Integer>empty()        // aggregationId
        );
        // --- AggregationNode End: n4 ---
// --- OutputNode Start: root ---
        List<String> columnNames_root = Arrays.asList(
                "l_discount"
        );

        List<VariableReferenceExpression> outputVars_root = Arrays.asList(
                sum_l_extendedprice____cast_1_as_double___l_discount
        );

        PlanNode root = new OutputNode(
                Optional.empty(),
                idAllocator.getNextId(),
                n4,           // 通常是 sortNode 或 aggregationNode
                columnNames_root,
                outputVars_root
        );
        // --- OutputNode End: root ---
        return root;
    }

    public PlanNode getQ20() {
        return null;
    }

    public static Expression expression(String sql) {
        return ExpressionUtils.rewriteIdentifiersToSymbolReferences(new SqlParser().createExpression(sql));
    }

    public RowExpression getRowExpression(String expression) {
//        TypeProvider typeProvider = TypeProvider.copyOf(ImmutableMap.of("l_quantity", DoubleType.DOUBLE));
        TypeProvider typeProvider = TpchSchemaRegistry.getProvider();
        RowExpression rowExpression = sqlToRowExpressionTranslator.translateAndOptimize(expression(expression), typeProvider);
        return rowExpression;
    }

    AnalyzerContext analyzerContext;
    Metadata metadata;
    PlanNodeIdAllocator idAllocator;
    QueryStateMachine stateMachine;

    // for expression
    private final RowExpressionTranslator sqlToRowExpressionTranslator;
}
