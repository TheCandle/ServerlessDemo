/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.execution;

import com.facebook.airlift.concurrent.SetThreadName;
import com.facebook.airlift.units.Duration;
import com.facebook.presto.Session;
import com.facebook.presto.common.InvalidFunctionArgumentException;
import com.facebook.presto.common.QualifiedObjectName;
import com.facebook.presto.common.analyzer.PreparedQuery;
import com.facebook.presto.common.block.SortOrder;
import com.facebook.presto.common.function.OperatorType;
import com.facebook.presto.common.predicate.TupleDomain;
import com.facebook.presto.common.resourceGroups.QueryType;
import com.facebook.presto.common.type.*;
import com.facebook.presto.cost.CostCalculator;
import com.facebook.presto.cost.HistoryBasedPlanStatisticsManager;
import com.facebook.presto.cost.StatsCalculator;
import com.facebook.presto.execution.StateMachine.StateChangeListener;
import com.facebook.presto.execution.buffer.OutputBuffers;
import com.facebook.presto.execution.buffer.OutputBuffers.OutputBufferId;
import com.facebook.presto.execution.scheduler.ExecutionPolicy;
import com.facebook.presto.execution.scheduler.SectionExecutionFactory;
import com.facebook.presto.execution.scheduler.SplitSchedulerStats;
import com.facebook.presto.execution.scheduler.SqlQueryScheduler;
import com.facebook.presto.execution.scheduler.SqlQuerySchedulerInterface;
import com.facebook.presto.memory.VersionedMemoryPoolId;
import com.facebook.presto.metadata.InternalNodeManager;
import com.facebook.presto.metadata.Metadata;
import com.facebook.presto.server.BasicQueryInfo;
import com.facebook.presto.spi.*;
import com.facebook.presto.spi.analyzer.AnalyzerContext;
import com.facebook.presto.spi.analyzer.AnalyzerProvider;
import com.facebook.presto.spi.analyzer.QueryAnalysis;
import com.facebook.presto.spi.analyzer.QueryAnalyzer;
import com.facebook.presto.spi.function.FunctionHandle;
import com.facebook.presto.spi.function.FunctionKind;
import com.facebook.presto.spi.plan.*;
import com.facebook.presto.spi.relation.CallExpression;
import com.facebook.presto.spi.resourceGroups.ResourceGroupQueryLimits;
import com.facebook.presto.spi.security.AccessControl;
import com.facebook.presto.split.CloseableSplitSourceProvider;
import com.facebook.presto.split.SplitManager;
import com.facebook.presto.sql.Optimizer;
import com.facebook.presto.sql.analyzer.TypeSignatureProvider;
import com.facebook.presto.sql.parser.SqlParser;
import com.facebook.presto.sql.planner.CanonicalPlanWithInfo;
import com.facebook.presto.sql.planner.InputExtractor;
import com.facebook.presto.sql.planner.OutputExtractor;
import com.facebook.presto.sql.planner.Plan;
import com.facebook.presto.sql.planner.PlanCanonicalInfoProvider;
import com.facebook.presto.sql.planner.PlanFragmenter;
import com.facebook.presto.sql.planner.PlanOptimizers;
import com.facebook.presto.sql.planner.SplitSourceFactory;
import com.facebook.presto.sql.planner.SubPlan;
import com.facebook.presto.sql.planner.optimizations.PlanOptimizer;
import com.facebook.presto.sql.planner.sanity.PlanChecker;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.ListenableFuture;
import com.facebook.presto.spi.relation.ConstantExpression;
import com.facebook.presto.spi.relation.RowExpression;
import com.facebook.presto.spi.relation.VariableReferenceExpression;
import com.google.errorprone.annotations.ThreadSafe;
import io.airlift.slice.Slices;
import jakarta.inject.Inject;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static com.facebook.presto.SystemSessionProperties.getExecutionPolicy;
import static com.facebook.presto.SystemSessionProperties.getQueryAnalyzerTimeout;
import static com.facebook.presto.SystemSessionProperties.isEagerPlanValidationEnabled;
import static com.facebook.presto.SystemSessionProperties.isLogInvokedFunctionNamesEnabled;
import static com.facebook.presto.SystemSessionProperties.isSpoolingOutputBufferEnabled;
import static com.facebook.presto.common.RuntimeMetricName.ANALYZE_TIME_NANOS;
import static com.facebook.presto.common.RuntimeMetricName.CREATE_SCHEDULER_TIME_NANOS;
import static com.facebook.presto.common.RuntimeMetricName.FRAGMENT_PLAN_TIME_NANOS;
import static com.facebook.presto.common.RuntimeMetricName.GET_CANONICAL_INFO_TIME_NANOS;
import static com.facebook.presto.common.RuntimeMetricName.LOGICAL_PLANNER_TIME_NANOS;
import static com.facebook.presto.common.RuntimeMetricName.OPTIMIZER_TIME_NANOS;
import static com.facebook.presto.common.RuntimeMetricName.PLAN_AND_OPTIMIZE_TIME_NANOS;
import static com.facebook.presto.execution.QueryStateMachine.pruneHistogramsFromStatsAndCosts;
import static com.facebook.presto.execution.buffer.OutputBuffers.BROADCAST_PARTITION_ID;
import static com.facebook.presto.execution.buffer.OutputBuffers.createInitialEmptyOutputBuffers;
import static com.facebook.presto.execution.buffer.OutputBuffers.createSpoolingOutputBuffers;
import static com.facebook.presto.spi.StandardErrorCode.INVALID_FUNCTION_ARGUMENT;
import static com.facebook.presto.spi.StandardErrorCode.NOT_SUPPORTED;
import static com.facebook.presto.sql.Optimizer.PlanStage.OPTIMIZED_AND_VALIDATED;
import static com.facebook.presto.sql.planner.PlanNodeCanonicalInfo.getCanonicalInfo;
import static com.facebook.presto.util.AnalyzerUtil.checkAccessPermissions;
import static com.facebook.presto.util.AnalyzerUtil.getAnalyzerContext;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Throwables.throwIfInstanceOf;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.SECONDS;

@ThreadSafe
public class SqlQueryExecution
        implements QueryExecution
{
    private static final OutputBufferId OUTPUT_BUFFER_ID = new OutputBufferId(0);

    private final QueryAnalyzer queryAnalyzer;
    private final QueryStateMachine stateMachine;
    private final String slug;
    private final int retryCount;
    private final Metadata metadata;
    private final SqlParser sqlParser;
    private final SplitManager splitManager;
    private final List<PlanOptimizer> planOptimizers;
    private final List<PlanOptimizer> runtimePlanOptimizers;
    private final PlanFragmenter planFragmenter;
    private final RemoteTaskFactory remoteTaskFactory;
    private final LocationFactory locationFactory;
    private final ExecutorService queryExecutor;
    private final ScheduledExecutorService timeoutThreadExecutor;
    private final SectionExecutionFactory sectionExecutionFactory;
    private final InternalNodeManager internalNodeManager;

    private final AtomicReference<SqlQuerySchedulerInterface> queryScheduler = new AtomicReference<>();
    private final AtomicReference<Plan> queryPlan = new AtomicReference<>();
    private final ExecutionPolicy executionPolicy;
    private final SplitSchedulerStats schedulerStats;
    private final StatsCalculator statsCalculator;
    private final CostCalculator costCalculator;
    private final PlanChecker planChecker;
    private final PlanNodeIdAllocator idAllocator = new PlanNodeIdAllocator();
    private final AtomicReference<VariableAllocator> variableAllocator = new AtomicReference<>();
    private final PartialResultQueryManager partialResultQueryManager;
    private final AtomicReference<Optional<ResourceGroupQueryLimits>> resourceGroupQueryLimits = new AtomicReference<>(Optional.empty());
    private final PlanCanonicalInfoProvider planCanonicalInfoProvider;
    private final QueryAnalysis queryAnalysis;
    private final AnalyzerContext analyzerContext;
    private final CompletableFuture<PlanRoot> planFuture;
    private final AtomicBoolean planFutureLocked = new AtomicBoolean();
    private final AccessControl accessControl;
    private final String query;

    private SqlQueryExecution(
            QueryAnalyzer queryAnalyzer,
            PreparedQuery preparedQuery,
            QueryStateMachine stateMachine,
            String slug,
            int retryCount,
            Metadata metadata,
            SqlParser sqlParser,
            SplitManager splitManager,
            List<PlanOptimizer> planOptimizers,
            List<PlanOptimizer> runtimePlanOptimizers,
            PlanFragmenter planFragmenter,
            RemoteTaskFactory remoteTaskFactory,
            LocationFactory locationFactory,
            ExecutorService queryExecutor,
            ScheduledExecutorService timeoutThreadExecutor,
            SectionExecutionFactory sectionExecutionFactory,
            ExecutorService eagerPlanValidationExecutor,
            InternalNodeManager internalNodeManager,
            ExecutionPolicy executionPolicy,
            SplitSchedulerStats schedulerStats,
            StatsCalculator statsCalculator,
            CostCalculator costCalculator,
            PlanChecker planChecker,
            PartialResultQueryManager partialResultQueryManager,
            PlanCanonicalInfoProvider planCanonicalInfoProvider,
            AccessControl accessControl,
            String query)
    {
        try (SetThreadName ignored = new SetThreadName("Query-%s", stateMachine.getQueryId())) {
            this.queryAnalyzer = requireNonNull(queryAnalyzer, "queryAnalyzer is null");
            this.slug = requireNonNull(slug, "slug is null");
            this.retryCount = retryCount;
            this.metadata = requireNonNull(metadata, "metadata is null");
            this.sqlParser = requireNonNull(sqlParser, "sqlParser is null");
            this.splitManager = requireNonNull(splitManager, "splitManager is null");
            this.planOptimizers = requireNonNull(planOptimizers, "planOptimizers is null");
            this.runtimePlanOptimizers = requireNonNull(runtimePlanOptimizers, "runtimePlanOptimizers is null");
            this.planFragmenter = requireNonNull(planFragmenter, "planFragmenter is null");
            this.locationFactory = requireNonNull(locationFactory, "locationFactory is null");
            this.queryExecutor = requireNonNull(queryExecutor, "queryExecutor is null");
            this.timeoutThreadExecutor = requireNonNull(timeoutThreadExecutor, "timeoutThreadExecutor is null");
            this.sectionExecutionFactory = requireNonNull(sectionExecutionFactory, "sectionExecutionFactory is null");
            this.internalNodeManager = requireNonNull(internalNodeManager, "internalNodeManager is null");
            this.executionPolicy = requireNonNull(executionPolicy, "executionPolicy is null");
            this.schedulerStats = requireNonNull(schedulerStats, "schedulerStats is null");
            this.statsCalculator = requireNonNull(statsCalculator, "statsCalculator is null");
            this.costCalculator = requireNonNull(costCalculator, "costCalculator is null");
            this.stateMachine = requireNonNull(stateMachine, "stateMachine is null");
            this.planChecker = requireNonNull(planChecker, "planChecker is null");
            this.planCanonicalInfoProvider = requireNonNull(planCanonicalInfoProvider, "planCanonicalInfoProvider is null");
            this.accessControl = requireNonNull(accessControl, "accessControl is null");
            this.query = requireNonNull(query, "query is null");
            this.analyzerContext = getAnalyzerContext(queryAnalyzer, metadata.getMetadataResolver(stateMachine.getSession()), idAllocator, new VariableAllocator(), stateMachine.getSession(), query);

            // analyze query
            requireNonNull(preparedQuery, "preparedQuery is null");

            stateMachine.beginSemanticAnalyzing();

            try (TimeoutThread unused = new TimeoutThread(
                    Thread.currentThread(),
                    timeoutThreadExecutor,
                    getQueryAnalyzerTimeout(getSession()))) {
                this.queryAnalysis = getSession()
                        .getRuntimeStats()
                        .recordWallAndCpuTime(ANALYZE_TIME_NANOS, () -> queryAnalyzer.analyze(analyzerContext, preparedQuery));
            }

            stateMachine.setUpdateInfo(queryAnalysis.getUpdateInfo());
            stateMachine.setExpandedQuery(queryAnalysis.getExpandedQuery());

            stateMachine.beginColumnAccessPermissionChecking();
            checkAccessPermissions(queryAnalysis.getAccessControlReferences(), queryAnalysis.getViewDefinitionReferences(), query, getSession().getPreparedStatements(), getSession().getIdentity(), accessControl, getSession().getAccessControlContext());
            stateMachine.endColumnAccessPermissionChecking();

            // when the query finishes cache the final query info, and clear the reference to the output stage
            AtomicReference<SqlQuerySchedulerInterface> queryScheduler = this.queryScheduler;
            stateMachine.addStateChangeListener(state -> {
                if (!state.isDone()) {
                    return;
                }

                // query is now done, so abort any work that is still running
                SqlQuerySchedulerInterface scheduler = queryScheduler.get();
                if (scheduler != null) {
                    scheduler.abort();
                }
            });

            this.remoteTaskFactory = new TrackingRemoteTaskFactory(requireNonNull(remoteTaskFactory, "remoteTaskFactory is null"), stateMachine);
            this.partialResultQueryManager = requireNonNull(partialResultQueryManager, "partialResultQueryManager is null");

            if (isLogInvokedFunctionNamesEnabled(getSession())) {
                for (Map.Entry<FunctionKind, Set<String>> entry : queryAnalysis.getInvokedFunctions().entrySet()) {
                    switch (entry.getKey()) {
                        case SCALAR:
                            stateMachine.setScalarFunctions(entry.getValue());
                            break;
                        case AGGREGATE:
                            stateMachine.setAggregateFunctions(entry.getValue());
                            break;
                        case WINDOW:
                            stateMachine.setWindowFunctions(entry.getValue());
                            break;
                    }
                }
            }

            // Optionally build and validate plan immediately, before execution begins
            planFuture = isEagerPlanValidationEnabled(getSession()) ?
                    CompletableFuture.supplyAsync(this::runCreateLogicalPlanAsync, eagerPlanValidationExecutor) : null;
        }
    }

    @Override
    public String getSlug()
    {
        return slug;
    }

    @Override
    public int getRetryCount()
    {
        return retryCount;
    }

    @Override
    public VersionedMemoryPoolId getMemoryPool()
    {
        return stateMachine.getMemoryPool();
    }

    @Override
    public void setMemoryPool(VersionedMemoryPoolId poolId)
    {
        stateMachine.setMemoryPool(poolId);
    }

    /**
     * If query has not started executing, return 0
     * If the query is executing, gets the size of the current user memory consumed by the query
     * If the query has finished executing, gets the value of the final query info's {@link QueryStats#getUserMemoryReservation()}
     */
    @Override
    public long getUserMemoryReservationInBytes()
    {
        // acquire reference to scheduler before checking finalQueryInfo, because
        // state change listener sets finalQueryInfo and then clears scheduler when
        // the query finishes.
        SqlQuerySchedulerInterface scheduler = queryScheduler.get();
        Optional<QueryInfo> finalQueryInfo = stateMachine.getFinalQueryInfo();
        if (finalQueryInfo.isPresent()) {
            return finalQueryInfo.get().getQueryStats().getUserMemoryReservation().toBytes();
        }
        if (scheduler == null) {
            return 0L;
        }
        return scheduler.getUserMemoryReservation();
    }

    /**
     * Gets the current total memory reserved for this query
     */
    @Override
    public long getTotalMemoryReservationInBytes()
    {
        // acquire reference to scheduler before checking finalQueryInfo, because
        // state change listener sets finalQueryInfo and then clears scheduler when
        // the query finishes.
        SqlQuerySchedulerInterface scheduler = queryScheduler.get();
        Optional<QueryInfo> finalQueryInfo = stateMachine.getFinalQueryInfo();
        if (finalQueryInfo.isPresent()) {
            return finalQueryInfo.get().getQueryStats().getTotalMemoryReservation().toBytes();
        }
        if (scheduler == null) {
            return 0L;
        }
        return scheduler.getTotalMemoryReservation();
    }

    /**
     * Gets the timestamp this query was registered for execution with the query state machine
     */
    @Override
    public long getCreateTimeInMillis()
    {
        return stateMachine.getCreateTimeInMillis();
    }

    @Override
    public Duration getQueuedTime()
    {
        return stateMachine.getQueuedTime();
    }

    /**
     * For a query that has started executing, returns the timestamp when this query started executing
     * Otherwise returns a {@link Optional#empty()}
     */
    @Override
    public long getExecutionStartTimeInMillis()
    {
        return stateMachine.getExecutionStartTimeInMillis();
    }

    @Override
    public long getLastHeartbeatInMillis()
    {
        return stateMachine.getLastHeartbeatInMillis();
    }

    /**
     * For a query that has finished execution, returns the timestamp when this query stopped executing
     * Otherwise returns a {@link Optional#empty()}
     */
    @Override
    public long getEndTimeInMillis()
    {
        return stateMachine.getEndTimeInMillis();
    }

    /**
     * Gets the total cputime spent in executing the query
     */
    @Override
    public Duration getTotalCpuTime()
    {
        SqlQuerySchedulerInterface scheduler = queryScheduler.get();
        Optional<QueryInfo> finalQueryInfo = stateMachine.getFinalQueryInfo();
        if (finalQueryInfo.isPresent()) {
            return finalQueryInfo.get().getQueryStats().getTotalCpuTime();
        }
        if (scheduler == null) {
            return new Duration(0, SECONDS);
        }
        return scheduler.getTotalCpuTime();
    }

    @Override
    public long getRawInputDataSizeInBytes()
    {
        SqlQuerySchedulerInterface scheduler = queryScheduler.get();
        Optional<QueryInfo> finalQueryInfo = stateMachine.getFinalQueryInfo();
        if (finalQueryInfo.isPresent()) {
            return finalQueryInfo.get().getQueryStats().getRawInputDataSize().toBytes();
        }
        if (scheduler == null) {
            return 0L;
        }
        return scheduler.getRawInputDataSizeInBytes();
    }

    @Override
    public long getWrittenIntermediateDataSizeInBytes()
    {
        SqlQuerySchedulerInterface scheduler = queryScheduler.get();
        Optional<QueryInfo> finalQueryInfo = stateMachine.getFinalQueryInfo();
        if (finalQueryInfo.isPresent()) {
            return finalQueryInfo.get().getQueryStats().getWrittenIntermediatePhysicalDataSize().toBytes();
        }
        if (scheduler == null) {
            return 0L;
        }
        return scheduler.getWrittenIntermediateDataSizeInBytes();
    }

    @Override
    public long getOutputPositions()
    {
        SqlQuerySchedulerInterface scheduler = queryScheduler.get();
        Optional<QueryInfo> finalQueryInfo = stateMachine.getFinalQueryInfo();
        if (finalQueryInfo.isPresent()) {
            return finalQueryInfo.get().getQueryStats().getOutputPositions();
        }
        if (scheduler == null) {
            return 0;
        }
        return scheduler.getOutputPositions();
    }

    @Override
    public long getOutputDataSizeInBytes()
    {
        SqlQuerySchedulerInterface scheduler = queryScheduler.get();
        Optional<QueryInfo> finalQueryInfo = stateMachine.getFinalQueryInfo();
        if (finalQueryInfo.isPresent()) {
            return finalQueryInfo.get().getQueryStats().getOutputDataSize().toBytes();
        }
        if (scheduler == null) {
            return 0L;
        }
        return scheduler.getOutputDataSizeInBytes();
    }

    @Override
    public BasicQueryInfo getBasicQueryInfo()
    {
        return stateMachine.getFinalQueryInfo()
                .map(BasicQueryInfo::new)
                .orElseGet(() -> stateMachine.getBasicQueryInfo(Optional.ofNullable(queryScheduler.get()).map(SqlQuerySchedulerInterface::getBasicStageStats)));
    }

    /**
     * Gets the number of tasks associated with this query that are still running
     */
    @Override
    public int getRunningTaskCount()
    {
        return stateMachine.getCurrentRunningTaskCount();
    }

    /**
     * Start the execution of the query. At a high level steps are :
     * 1. Build the logical and physical execution plan of the query
     * 2. Start the query execution by calling {@link SqlQuerySchedulerInterface#start()}
     */
    @Override
    public void start()
    {
        try (SetThreadName ignored = new SetThreadName("Query-%s", stateMachine.getQueryId())) {
            try {
                // transition to planning
                if (!stateMachine.transitionToPlanning()) {
                    // query already started or finished
                    return;
                }

                PlanRoot plan;

                // set a thread timeout in case query analyzer ends up in an infinite loop
                try (TimeoutThread unused = new TimeoutThread(
                        Thread.currentThread(),
                        timeoutThreadExecutor,
                        getQueryAnalyzerTimeout(getSession()))) {
                    // If planFuture has not started, cancel and build plan in current thread
                    if (planFuture != null && !planFutureLocked.compareAndSet(false, true)) {
                        plan = planFuture.get();
                    }
                    else {
                        plan = createLogicalPlanAndOptimize();
                    }
                }

                metadata.beginQuery(getSession(), plan.getConnectors());

                // plan distribution of query
                getSession().getRuntimeStats().recordWallAndCpuTime(CREATE_SCHEDULER_TIME_NANOS, () -> createQueryScheduler(plan));

                // transition to starting
                if (!stateMachine.transitionToStarting()) {
                    // query already started or finished
                    return;
                }

                // if query is not finished, start the scheduler, otherwise cancel it
                SqlQuerySchedulerInterface scheduler = queryScheduler.get();

                if (!stateMachine.isDone()) {
                    scheduler.start();
                }
            }
            catch (Throwable e) {
                fail(e);
                throwIfInstanceOf(e, Error.class);
            }
        }
    }

    /**
     * Adds a listener to be notified about {@link QueryState} changes
     *
     * @param stateChangeListener The state change listener
     */
    @Override
    public void addStateChangeListener(StateChangeListener<QueryState> stateChangeListener)
    {
        try (SetThreadName ignored = new SetThreadName("Query-%s", stateMachine.getQueryId())) {
            stateMachine.addStateChangeListener(stateChangeListener);
        }
    }

    @Override
    public Optional<ResourceGroupQueryLimits> getResourceGroupQueryLimits()
    {
        return resourceGroupQueryLimits.get();
    }

    @Override
    public void setResourceGroupQueryLimits(ResourceGroupQueryLimits resourceGroupQueryLimits)
    {
        if (!this.resourceGroupQueryLimits.compareAndSet(Optional.empty(), Optional.of(requireNonNull(resourceGroupQueryLimits, "resourceGroupQueryLimits is null")))) {
            throw new IllegalStateException("Cannot set resourceGroupQueryLimits more than once");
        }
    }

    @Override
    public Session getSession()
    {
        return stateMachine.getSession();
    }

    @Override
    public void addFinalQueryInfoListener(StateChangeListener<QueryInfo> stateChangeListener)
    {
        stateMachine.addQueryInfoStateChangeListener(stateChangeListener);
    }

    private PlanRoot createLogicalPlanAndOptimize()
    {
        return stateMachine.getSession()
                .getRuntimeStats()
                .recordWallAndCpuTime(
                        PLAN_AND_OPTIMIZE_TIME_NANOS,
                        this::doCreateLogicalPlanAndOptimize);
    }

    private PlanNode createHardcodedPlanNode()
    {
        // Produce pre-aggregated TPCH Q1 output columns (one sample row)
        // VariableReferenceExpression returnFlag = analyzerContext.getVariableAllocator().newVariable("l_returnflag", VarcharType.VARCHAR);
        // VariableReferenceExpression lineStatus = analyzerContext.getVariableAllocator().newVariable("l_linestatus", VarcharType.VARCHAR);
        VariableReferenceExpression sumQty = analyzerContext.getVariableAllocator().newVariable("sum_qty", DoubleType.DOUBLE);
        VariableReferenceExpression sumBasePrice = analyzerContext.getVariableAllocator().newVariable("sum_base_price", DoubleType.DOUBLE);
        VariableReferenceExpression sumDiscPrice = analyzerContext.getVariableAllocator().newVariable("sum_disc_price", DoubleType.DOUBLE);
        VariableReferenceExpression sumCharge = analyzerContext.getVariableAllocator().newVariable("sum_charge", DoubleType.DOUBLE);
        VariableReferenceExpression avgQty = analyzerContext.getVariableAllocator().newVariable("avg_qty", DoubleType.DOUBLE);
        VariableReferenceExpression avgPrice = analyzerContext.getVariableAllocator().newVariable("avg_price", DoubleType.DOUBLE);
        VariableReferenceExpression avgDisc = analyzerContext.getVariableAllocator().newVariable("avg_disc", DoubleType.DOUBLE);
        VariableReferenceExpression countOrder = analyzerContext.getVariableAllocator().newVariable("count_order", BigintType.BIGINT);

        java.util.List<VariableReferenceExpression> outputVariables = java.util.Arrays.asList(
//            returnFlag,
//            lineStatus,
            sumQty,
            sumBasePrice,
            sumDiscPrice,
            sumCharge,
            avgQty,
            avgPrice,
            avgDisc,
            countOrder
        );

        java.util.List<RowExpression> row = java.util.Arrays.<RowExpression>asList(
            // new ConstantExpression("N", VarcharType.VARCHAR),
            // new ConstantExpression("O", VarcharType.VARCHAR),
            new ConstantExpression(1000.0, DoubleType.DOUBLE),
            new ConstantExpression(12345.67, DoubleType.DOUBLE),
            new ConstantExpression(11728.03, DoubleType.DOUBLE),
            new ConstantExpression(12345.67, DoubleType.DOUBLE),
            new ConstantExpression(25.0, DoubleType.DOUBLE),
            new ConstantExpression(123.45, DoubleType.DOUBLE),
            new ConstantExpression(0.05, DoubleType.DOUBLE),
            new ConstantExpression(10L, BigintType.BIGINT)
        );

        java.util.List<RowExpression> row2 = java.util.Arrays.<RowExpression>asList(
                // new ConstantExpression("N", VarcharType.VARCHAR),
                // new ConstantExpression("O", VarcharType.VARCHAR),
                new ConstantExpression(100.0, DoubleType.DOUBLE),
                new ConstantExpression(12345.67, DoubleType.DOUBLE),
                new ConstantExpression(11728.03, DoubleType.DOUBLE),
                new ConstantExpression(12345.67, DoubleType.DOUBLE),
                new ConstantExpression(25.0, DoubleType.DOUBLE),
                new ConstantExpression(123.45, DoubleType.DOUBLE),
                new ConstantExpression(0.05, DoubleType.DOUBLE),
                new ConstantExpression(10L, BigintType.BIGINT)
        );
        java.util.List<java.util.List<RowExpression>> rows = java.util.Arrays.asList(row, row2);
//        java.util.List<java.util.List<RowExpression>> rows = java.util.Collections.singletonList(row);

        PlanNode values = new ValuesNode(java.util.Optional.empty(), idAllocator.getNextId(), outputVariables, rows, java.util.Optional.empty());

//        RowExpression predicate = new ConstantExpression(false, BooleanType.BOOLEAN);

        FunctionHandle greaterThan = metadata.getFunctionAndTypeManager().resolveOperator(
                OperatorType.GREATER_THAN,
                TypeSignatureProvider.fromTypes(DoubleType.DOUBLE, DoubleType.DOUBLE)
        );
        RowExpression predicate = new CallExpression(
                OperatorType.GREATER_THAN.name(),
                greaterThan,
                BooleanType.BOOLEAN,
                Arrays.asList(sumQty, new ConstantExpression(500.0, DoubleType.DOUBLE))
        );

        PlanNode filterNode = new FilterNode(
                java.util.Optional.empty(),
                idAllocator.getNextId(),
                values,      // 子节点
                predicate    // 过滤条件
        );

        java.util.List<String> columnNames = java.util.Arrays.asList(
//            "l_returnflag",
//            "l_linestatus",
            "sum_qty",
            "sum_base_price",
            "sum_disc_price",
            "sum_charge",
            "avg_qty",
            "avg_price",
            "avg_disc",
            "count_order"
        );
        return new OutputNode(java.util.Optional.empty(), idAllocator.getNextId(), filterNode, columnNames, outputVariables);
    }

    private PlanNode createTpch1()
    {
        VariableReferenceExpression returnFlag = analyzerContext.getVariableAllocator().newVariable("l_returnflag", VarcharType.VARCHAR);
        VariableReferenceExpression lineStatus  = analyzerContext.getVariableAllocator().newVariable("l_linestatus", VarcharType.VARCHAR);
        VariableReferenceExpression shipDate  = analyzerContext.getVariableAllocator().newVariable("l_shipdate", TimestampType.TIMESTAMP);
        VariableReferenceExpression quantity  = analyzerContext.getVariableAllocator().newVariable("l_quantity", DoubleType.DOUBLE);


        // 聚合输出字段
        VariableReferenceExpression sumQty = analyzerContext.getVariableAllocator().newVariable("sum_qty", DoubleType.DOUBLE);

        // 解析 <= 操作符
        FunctionHandle lessThanOrEqual = metadata.getFunctionAndTypeManager().resolveOperator(
                OperatorType.LESS_THAN_OR_EQUAL,
                TypeSignatureProvider.fromTypes(TimestampType.TIMESTAMP, TimestampType.TIMESTAMP)
        );

        // 构建谓词：shipDate <= 1998-08-04 (时间戳在 Presto 内部通常是 Long 毫秒)
        RowExpression filterPredicate = new CallExpression(
                OperatorType.LESS_THAN_OR_EQUAL.name(),
                lessThanOrEqual,
                BooleanType.BOOLEAN,
                Arrays.asList(
                        shipDate,
                        new ConstantExpression(881280000000L, TimestampType.TIMESTAMP) // 示例时间戳数值
                )
        );

        // 准备一行模拟数据
        List<RowExpression> row = Arrays.asList(
                new ConstantExpression(Slices.utf8Slice("N"), VarcharType.VARCHAR),
                new ConstantExpression(Slices.utf8Slice("O"), VarcharType.VARCHAR),
                new ConstantExpression(881280000000L, TimestampType.TIMESTAMP), // 1998-08-04
                new ConstantExpression(10.0, DoubleType.DOUBLE)
        );

        List<RowExpression> row2 = Arrays.asList(
                new ConstantExpression(Slices.utf8Slice("N"), VarcharType.VARCHAR),
                new ConstantExpression(Slices.utf8Slice("O"), VarcharType.VARCHAR),
                new ConstantExpression(881280000000L, TimestampType.TIMESTAMP), // 1998-08-04
                new ConstantExpression(30.0, DoubleType.DOUBLE)
        );

        List<RowExpression> row3 = Arrays.asList(
                new ConstantExpression(Slices.utf8Slice("N"), VarcharType.VARCHAR),
                new ConstantExpression(Slices.utf8Slice("O"), VarcharType.VARCHAR),
                new ConstantExpression(881280000000L, TimestampType.TIMESTAMP), // 1998-08-04
                new ConstantExpression(2.0, DoubleType.DOUBLE)
        );

        java.util.List<java.util.List<RowExpression>> rows = java.util.Arrays.asList(row, row2, row3);


        List<VariableReferenceExpression> sourceVariables = Arrays.asList(returnFlag, lineStatus, shipDate, quantity);
        PlanNode sourceNode = new ValuesNode(
                Optional.empty(),
                idAllocator.getNextId(),
                sourceVariables,
                rows,
                Optional.empty()
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
        List<String> columnNames = Arrays.asList("l_returnflag", "l_linestatus", "sum_qty");
        List<VariableReferenceExpression> outputVariables = Arrays.asList(returnFlag, lineStatus, sumQty);

        PlanNode root = new OutputNode(
                Optional.empty(),
                idAllocator.getNextId(),
                sortNode,
                columnNames,
                outputVariables
        );
        return root;
    }

    private PlanNode createTpch1S()
    {
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
        assignments.put(returnFlag, columnHandles.get("l_returnflag"));
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

    private PlanRoot doCreateLogicalPlanAndOptimize()
    {
        try {
            // time analysis phase
            stateMachine.beginAnalysis();

            PlanNode planNode;
            TpchPlanTree tpchPlanTree = new TpchPlanTree(analyzerContext, metadata, idAllocator, stateMachine);
            switch (this.query) {
                case "select 1":
                  planNode = tpchPlanTree.getQ1();
                  break;
                case "select 6":
                  planNode = tpchPlanTree.getQ6();
                  break;
                case "select 12":
                  planNode = tpchPlanTree.getQ12();
                  break;
                case "select 13":
                  planNode = tpchPlanTree.getQ13();
                  break;
                case "select 14":
                  planNode = tpchPlanTree.getQ14();
                  break;
                case "select 17":
                  planNode = tpchPlanTree.getQ17();
                  break;
                case "select 18":
                  planNode = tpchPlanTree.getQ18();
                  break;
                case "select 19":
                  planNode = tpchPlanTree.getQ19();
                  break;
                default:
                  planNode = stateMachine.getSession()
                        .getRuntimeStats()
                        .recordWallAndCpuTime(
                                LOGICAL_PLANNER_TIME_NANOS,
                                () -> queryAnalyzer.plan(this.analyzerContext, queryAnalysis));    
            }

            Optimizer optimizer = new Optimizer(
                    stateMachine.getSession(),
                    metadata,
                    planOptimizers,
                    planChecker,
                    analyzerContext.getVariableAllocator(),
                    idAllocator,
                    stateMachine.getWarningCollector(),
                    statsCalculator,
                    costCalculator,
                    false);

            Plan plan = getSession().getRuntimeStats().recordWallAndCpuTime(
                    OPTIMIZER_TIME_NANOS,
                    () -> optimizer.validateAndOptimizePlan(planNode, OPTIMIZED_AND_VALIDATED));

            queryPlan.set(plan);
            stateMachine.setPlanStatsAndCosts(plan.getStatsAndCosts());
            stateMachine.setPlanIdNodeMap(plan.getPlanIdNodeMap());
            List<CanonicalPlanWithInfo> canonicalPlanWithInfos = getSession().getRuntimeStats().recordWallAndCpuTime(
                    GET_CANONICAL_INFO_TIME_NANOS,
                    () -> getCanonicalInfo(getSession(), plan.getRoot(), planCanonicalInfoProvider));
            stateMachine.setPlanCanonicalInfo(canonicalPlanWithInfos);

            // extract inputs
            List<Input> inputs = new InputExtractor(metadata, stateMachine.getSession()).extractInputs(plan.getRoot());
            stateMachine.setInputs(inputs);

            // extract output
            Optional<Output> output = new OutputExtractor().extractOutput(plan.getRoot());
            stateMachine.setOutput(output);

            // fragment the plan
            // the variableAllocator is finally passed to SqlQueryScheduler for runtime cost-based optimizations
            variableAllocator.set(new VariableAllocator(plan.getTypes().allVariables()));
            SubPlan fragmentedPlan = getSession().getRuntimeStats().recordWallAndCpuTime(
                    FRAGMENT_PLAN_TIME_NANOS,
                    () -> planFragmenter.createSubPlans(stateMachine.getSession(), plan, false, idAllocator, variableAllocator.get(), stateMachine.getWarningCollector()));

            // record analysis time
            stateMachine.endAnalysis();

            boolean explainAnalyze = queryAnalysis.isExplainAnalyzeQuery();
            return new PlanRoot(fragmentedPlan, !explainAnalyze, queryAnalysis.extractConnectors());
        }
        catch (StackOverflowError e) {
            throw new PrestoException(NOT_SUPPORTED, "statement is too large (stack overflow during analysis)", e);
        }
        catch (InvalidFunctionArgumentException e) {
            throw new PrestoException(INVALID_FUNCTION_ARGUMENT, e.getMessage(), e);
        }
    }

    private PlanRoot runCreateLogicalPlanAsync()
    {
        try {
            // Check if creating plan async has been cancelled
            if (planFutureLocked.compareAndSet(false, true)) {
                return createLogicalPlanAndOptimize();
            }
            return null;
        }
        catch (Throwable e) {
            fail(e);
            throw e;
        }
    }

    private void createQueryScheduler(PlanRoot plan)
    {
        CloseableSplitSourceProvider splitSourceProvider = new CloseableSplitSourceProvider(splitManager);

        // ensure split sources are closed
        stateMachine.addStateChangeListener(state -> {
            if (state.isDone()) {
                splitSourceProvider.close();
            }
        });

        // if query was canceled, skip creating scheduler
        if (stateMachine.isDone()) {
            return;
        }

        SubPlan outputStagePlan = plan.getRoot();

        // record output field
        stateMachine.setColumns(((OutputNode) outputStagePlan.getFragment().getRoot()).getColumnNames(), outputStagePlan.getFragment().getTypes());

        PartitioningHandle partitioningHandle = outputStagePlan.getFragment().getPartitioningScheme().getPartitioning().getHandle();
        OutputBuffers rootOutputBuffers;
        if (isSpoolingOutputBufferEnabled(getSession())) {
            rootOutputBuffers = createSpoolingOutputBuffers();
        }
        else {
            rootOutputBuffers = createInitialEmptyOutputBuffers(partitioningHandle)
                    .withBuffer(OUTPUT_BUFFER_ID, BROADCAST_PARTITION_ID)
                    .withNoMoreBufferIds();
        }

        SplitSourceFactory splitSourceFactory = new SplitSourceFactory(splitSourceProvider, stateMachine.getWarningCollector());
        // build the stage execution objects (this doesn't schedule execution)
        SqlQuerySchedulerInterface scheduler = SqlQueryScheduler.createSqlQueryScheduler(
                locationFactory,
                executionPolicy,
                queryExecutor,
                schedulerStats,
                sectionExecutionFactory,
                remoteTaskFactory,
                splitSourceFactory,
                stateMachine.getSession(),
                metadata.getFunctionAndTypeManager(),
                stateMachine,
                outputStagePlan,
                rootOutputBuffers,
                plan.isSummarizeTaskInfos(),
                runtimePlanOptimizers,
                stateMachine.getWarningCollector(),
                idAllocator,
                variableAllocator.get(),
                planChecker,
                metadata,
                sqlParser,
                partialResultQueryManager);

        queryScheduler.set(scheduler);

        // if query was canceled during scheduler creation, abort the scheduler
        // directly since the callback may have already fired
        if (stateMachine.isDone()) {
            scheduler.abort();
            queryScheduler.set(null);
        }
    }

    /**
     * Try to cancel the execution of the query.
     * TODO : Add more details on how cancellation request is propagated to tasks, connectors etc
     */
    @Override
    public void cancelQuery()
    {
        stateMachine.transitionToCanceled();
    }

    /**
     * Try to cancel the execution of a specific stage
     *
     * @param stageId id of the stage to cancel
     */
    @Override
    public void cancelStage(StageId stageId)
    {
        requireNonNull(stageId, "stageId is null");

        try (SetThreadName ignored = new SetThreadName("Query-%s", stateMachine.getQueryId())) {
            SqlQuerySchedulerInterface scheduler = queryScheduler.get();
            if (scheduler != null) {
                scheduler.cancelStage(stageId);
            }
        }
    }

    /**
     * Fail the execution of the query with a specific cause
     *
     * @param cause The cause for failing the query execution
     */
    @Override
    public void fail(Throwable cause)
    {
        requireNonNull(cause, "cause is null");

        stateMachine.transitionToFailed(cause);

        // acquire reference to scheduler before checking finalQueryInfo, because
        // state change listener sets finalQueryInfo and then clears scheduler when
        // the query finishes.
        SqlQuerySchedulerInterface scheduler = queryScheduler.get();
        stateMachine.updateQueryInfo(Optional.ofNullable(scheduler).map(SqlQuerySchedulerInterface::getStageInfo));
    }

    /**
     * Checks if the query is done executing
     */
    @Override
    public boolean isDone()
    {
        return getState().isDone();
    }

    /**
     * Register a listener to be notified about new {@link QueryOutputInfo} buffers created as tasks execute in this query
     *
     * @param listener the listener
     */
    @Override
    public void addOutputInfoListener(Consumer<QueryOutputInfo> listener)
    {
        stateMachine.addOutputInfoListener(listener);
    }

    /**
     * Gets a future that completes when the current query state has changed
     *
     * @param currentState The current query state. If the query state is not equal to this state, the future returned will already be completed
     */
    @Override
    public ListenableFuture<QueryState> getStateChange(QueryState currentState)
    {
        return stateMachine.getStateChange(currentState);
    }

    /**
     * Record a heartbeat with the query state machine
     */
    @Override
    public void recordHeartbeat()
    {
        stateMachine.recordHeartbeat();
    }

    @Override
    public void pruneExpiredQueryInfo()
    {
        stateMachine.pruneQueryInfoExpired();
    }

    @Override
    public void pruneFinishedQueryInfo()
    {
        queryPlan.getAndUpdate(nullablePlan -> Optional.ofNullable(nullablePlan)
                .map(plan -> new Plan(
                        plan.getRoot(),
                        plan.getTypes(),
                        pruneHistogramsFromStatsAndCosts(plan.getStatsAndCosts())))
                .orElse(null));
        // drop the reference to the scheduler since execution is finished
        queryScheduler.set(null);
        stateMachine.pruneQueryInfoFinished();
    }

    @Override
    public QueryId getQueryId()
    {
        return stateMachine.getQueryId();
    }

    /**
     * If the query is still executing, build and return a {@link QueryInfo} of the current query state
     * If the query has finished executing, return the final {@link QueryInfo} stored in the query state machine
     */
    @Override
    public QueryInfo getQueryInfo()
    {
        try (SetThreadName ignored = new SetThreadName("Query-%s", stateMachine.getQueryId())) {
            // acquire reference to scheduler before checking finalQueryInfo, because
            // state change listener sets finalQueryInfo and then clears scheduler when
            // the query finishes.
            SqlQuerySchedulerInterface scheduler = queryScheduler.get();

            return stateMachine.getFinalQueryInfo().orElseGet(() -> buildQueryInfo(scheduler));
        }
    }

    @Override
    public QueryState getState()
    {
        return stateMachine.getQueryState();
    }

    /**
     * Gets the logical query plan
     */
    @Override
    public Plan getQueryPlan()
    {
        return queryPlan.get();
    }

    private QueryInfo buildQueryInfo(SqlQuerySchedulerInterface scheduler)
    {
        Optional<StageInfo> stageInfo = Optional.empty();
        if (scheduler != null) {
            stageInfo = Optional.of(scheduler.getStageInfo());
        }

        QueryInfo queryInfo = stateMachine.updateQueryInfo(stageInfo);
        if (queryInfo.isFinalQueryInfo()) {
            // capture the final query state and drop reference to the scheduler
            queryScheduler.set(null);
        }

        return queryInfo;
    }

    private static class PlanRoot
    {
        private final SubPlan root;
        private final boolean summarizeTaskInfos;
        private final Set<ConnectorId> connectors;

        public PlanRoot(SubPlan root, boolean summarizeTaskInfos, Set<ConnectorId> connectors)
        {
            this.root = requireNonNull(root, "root is null");
            this.summarizeTaskInfos = summarizeTaskInfos;
            this.connectors = ImmutableSet.copyOf(connectors);
        }

        public SubPlan getRoot()
        {
            return root;
        }

        public boolean isSummarizeTaskInfos()
        {
            return summarizeTaskInfos;
        }

        public Set<ConnectorId> getConnectors()
        {
            return connectors;
        }
    }

    public static class SqlQueryExecutionFactory
            implements QueryExecutionFactory<QueryExecution>
    {
        private final SplitSchedulerStats schedulerStats;
        private final Metadata metadata;
        private final SqlParser sqlParser;
        private final SplitManager splitManager;
        private final List<PlanOptimizer> planOptimizers;
        private final List<PlanOptimizer> runtimePlanOptimizers;
        private final PlanFragmenter planFragmenter;
        private final RemoteTaskFactory remoteTaskFactory;
        private final LocationFactory locationFactory;
        private final ScheduledExecutorService timeoutThreadExecutor;
        private final ExecutorService queryExecutor;
        private final SectionExecutionFactory sectionExecutionFactory;
        private final ExecutorService eagerPlanValidationExecutor;
        private final InternalNodeManager internalNodeManager;
        private final Map<String, ExecutionPolicy> executionPolicies;
        private final StatsCalculator statsCalculator;
        private final CostCalculator costCalculator;
        private final PlanChecker planChecker;
        private final PartialResultQueryManager partialResultQueryManager;
        private final HistoryBasedPlanStatisticsManager historyBasedPlanStatisticsManager;

        @Inject
        SqlQueryExecutionFactory(
                QueryManagerConfig config,
                Metadata metadata,
                SqlParser sqlParser,
                LocationFactory locationFactory,
                SplitManager splitManager,
                PlanOptimizers planOptimizers,
                PlanFragmenter planFragmenter,
                RemoteTaskFactory remoteTaskFactory,
                @ForQueryExecution ExecutorService queryExecutor,
                @ForTimeoutThread ScheduledExecutorService timeoutThreadExecutor,
                SectionExecutionFactory sectionExecutionFactory,
                @ForEagerPlanValidation ExecutorService eagerPlanValidationExecutor,
                InternalNodeManager internalNodeManager,
                Map<String, ExecutionPolicy> executionPolicies,
                SplitSchedulerStats schedulerStats,
                StatsCalculator statsCalculator,
                CostCalculator costCalculator,
                PlanChecker planChecker,
                PartialResultQueryManager partialResultQueryManager,
                HistoryBasedPlanStatisticsManager historyBasedPlanStatisticsManager)
        {
            requireNonNull(config, "config is null");
            this.schedulerStats = requireNonNull(schedulerStats, "schedulerStats is null");
            this.metadata = requireNonNull(metadata, "metadata is null");
            this.sqlParser = requireNonNull(sqlParser, "sqlParser is null");
            this.locationFactory = requireNonNull(locationFactory, "locationFactory is null");
            this.splitManager = requireNonNull(splitManager, "splitManager is null");
            requireNonNull(planOptimizers, "planOptimizers is null");
            this.planFragmenter = requireNonNull(planFragmenter, "planFragmenter is null");
            this.remoteTaskFactory = requireNonNull(remoteTaskFactory, "remoteTaskFactory is null");
            this.queryExecutor = requireNonNull(queryExecutor, "queryExecutor is null");
            this.timeoutThreadExecutor = requireNonNull(timeoutThreadExecutor, "timeoutThreadExecutor is null");
            this.sectionExecutionFactory = requireNonNull(sectionExecutionFactory, "sectionExecutionFactory is null");
            this.eagerPlanValidationExecutor = requireNonNull(eagerPlanValidationExecutor, "eagerPlanValidationExecutor is null");
            this.internalNodeManager = requireNonNull(internalNodeManager, "internalNodeManager is null");
            this.executionPolicies = requireNonNull(executionPolicies, "schedulerPolicies is null");
            this.planOptimizers = planOptimizers.getPlanningTimeOptimizers();
            this.runtimePlanOptimizers = planOptimizers.getRuntimeOptimizers();
            this.statsCalculator = requireNonNull(statsCalculator, "statsCalculator is null");
            this.costCalculator = requireNonNull(costCalculator, "costCalculator is null");
            this.planChecker = requireNonNull(planChecker, "planChecker is null");
            this.partialResultQueryManager = requireNonNull(partialResultQueryManager, "partialResultQueryManager is null");
            this.historyBasedPlanStatisticsManager = requireNonNull(historyBasedPlanStatisticsManager, "historyBasedPlanStatisticsManager is null");
        }

        @Override
        public QueryExecution createQueryExecution(
                AnalyzerProvider analyzerProvider,
                PreparedQuery preparedQuery,
                QueryStateMachine stateMachine,
                String slug,
                int retryCount,
                WarningCollector warningCollector,
                Optional<QueryType> queryType,
                AccessControl accessControl,
                String query)
        {
            String executionPolicyName = getExecutionPolicy(stateMachine.getSession());
            ExecutionPolicy executionPolicy = executionPolicies.get(executionPolicyName);
            checkArgument(executionPolicy != null, "No execution policy %s", executionPolicy);

            return new SqlQueryExecution(
                    analyzerProvider.getQueryAnalyzer(),
                    preparedQuery,
                    stateMachine,
                    slug,
                    retryCount,
                    metadata,
                    sqlParser,
                    splitManager,
                    planOptimizers,
                    runtimePlanOptimizers,
                    planFragmenter,
                    remoteTaskFactory,
                    locationFactory,
                    queryExecutor,
                    timeoutThreadExecutor,
                    sectionExecutionFactory,
                    eagerPlanValidationExecutor,
                    internalNodeManager,
                    executionPolicy,
                    schedulerStats,
                    statsCalculator,
                    costCalculator,
                    planChecker,
                    partialResultQueryManager,
                    historyBasedPlanStatisticsManager.getPlanCanonicalInfoProvider(),
                    accessControl,
                    query);
        }
    }
}
