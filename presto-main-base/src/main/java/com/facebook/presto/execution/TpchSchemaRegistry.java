package com.facebook.presto.execution;

import com.facebook.presto.common.type.*;
import com.facebook.presto.spi.relation.VariableReferenceExpression;
import com.facebook.presto.sql.planner.TypeProvider;
import com.google.common.collect.ImmutableMap;

import java.util.HashMap;
import java.util.Map;

public class TpchSchemaRegistry {
    private static final Map<String, Type> COLUMN_TYPES = new HashMap<>();

    // 1. LINEITEM 表
    private static final Map<String, Type> LINEITEM = ImmutableMap.<String, Type>builder()
            .put("l_orderkey", BigintType.BIGINT)
            .put("l_partkey", BigintType.BIGINT)
            .put("l_suppkey", BigintType.BIGINT)
            .put("l_linenumber", IntegerType.INTEGER)
            .put("l_quantity", DoubleType.DOUBLE)
            .put("l_extendedprice", DoubleType.DOUBLE)
            .put("l_discount", DoubleType.DOUBLE)
            .put("l_tax", DoubleType.DOUBLE)
            .put("l_returnflag", VarcharType.VARCHAR)
            .put("l_linestatus", VarcharType.VARCHAR)
            .put("l_shipdate", DateType.DATE)
            .put("l_commitdate", DateType.DATE)
            .put("l_receiptdate", DateType.DATE)
            .put("l_shipinstruct", VarcharType.VARCHAR)
            .put("l_shipmode", VarcharType.VARCHAR)
            .put("l_comment", VarcharType.VARCHAR)
            .build();

    // 2. ORDERS 表（订单表）
    private static final Map<String, Type> ORDERS = ImmutableMap.<String, Type>builder()
            .put("o_orderkey", BigintType.BIGINT)
            .put("o_custkey", BigintType.BIGINT)          // 客户键（外键）
            .put("o_orderstatus", VarcharType.VARCHAR)    // 订单状态（O, F, P）
            .put("o_totalprice", DoubleType.DOUBLE)       // 总价
            .put("o_orderdate", DateType.DATE)            // 订单日期
            .put("o_orderpriority", VarcharType.VARCHAR)  // 订单优先级
            .put("o_clerk", VarcharType.VARCHAR)          // 职员
            .put("o_shippriority", IntegerType.INTEGER)  // 发货优先级
            .put("o_comment", VarcharType.VARCHAR)        // 注释
            .build();


    // 3. PART 字段定义
//    private static final Map<String, Type> PART = ImmutableMap.<String, Type>builder()
//            .put("p_partkey", BigintType.BIGINT)
//            .put("p_name", VarcharType.createVarcharType(55))
//            .put("p_mfgr", VarcharType.createVarcharType(25))
//            .put("p_brand", VarcharType.createVarcharType(10))
//            .put("p_type", VarcharType.createVarcharType(25))
//            .put("p_size", IntegerType.INTEGER)
//            .put("p_container", VarcharType.createVarcharType(10))
//            .put("p_retailprice", DoubleType.DOUBLE)
//            .put("p_comment", VarcharType.createVarcharType(23))
//            .build();

    // 3. PART 表（零件表）
    private static final Map<String, Type> PART = ImmutableMap.<String, Type>builder()
            .put("p_partkey", BigintType.BIGINT)
            .put("p_name", VarcharType.VARCHAR)          // 零件名称
            .put("p_mfgr", VarcharType.VARCHAR)           // 制造商
            .put("p_brand", VarcharType.VARCHAR)          // 品牌
            .put("p_type", VarcharType.VARCHAR)           // 类型
            .put("p_size", IntegerType.INTEGER)          // 尺寸
            .put("p_container", VarcharType.VARCHAR)      // 容器类型
            .put("p_retailprice", DoubleType.DOUBLE)      // 零售价格
            .put("p_comment", VarcharType.VARCHAR)        // 注释
            .build();

    // 4. PARTSUPP 表（零件供应商表）
    private static final Map<String, Type> PARTSUPP = ImmutableMap.<String, Type>builder()
            .put("ps_partkey", BigintType.BIGINT)         // 零件键（外键）
            .put("ps_suppkey", BigintType.BIGINT)         // 供应商键（外键）
            .put("ps_availqty", IntegerType.INTEGER)     // 可用数量
            .put("ps_supplycost", DoubleType.DOUBLE)      // 供应成本
            .put("ps_comment", VarcharType.VARCHAR)       // 注释
            .build();

    // 5. CUSTOMER 表（客户表）
    private static final Map<String, Type> CUSTOMER = ImmutableMap.<String, Type>builder()
            .put("c_custkey", BigintType.BIGINT)
            .put("c_name", VarcharType.VARCHAR)           // 客户名称
            .put("c_address", VarcharType.VARCHAR)        // 地址
            .put("c_nationkey", BigintType.BIGINT)        // 国家键（外键）
            .put("c_phone", VarcharType.VARCHAR)          // 电话
            .put("c_acctbal", DoubleType.DOUBLE)          // 账户余额
            .put("c_mktsegment", VarcharType.VARCHAR)     // 市场细分
            .put("c_comment", VarcharType.VARCHAR)        // 注释
            .build();

    // 6. SUPPLIER 表（供应商表）
    private static final Map<String, Type> SUPPLIER = ImmutableMap.<String, Type>builder()
            .put("s_suppkey", BigintType.BIGINT)
            .put("s_name", VarcharType.VARCHAR)           // 供应商名称
            .put("s_address", VarcharType.VARCHAR)        // 地址
            .put("s_nationkey", BigintType.BIGINT)        // 国家键（外键）
            .put("s_phone", VarcharType.VARCHAR)          // 电话
            .put("s_acctbal", DoubleType.DOUBLE)          // 账户余额
            .put("s_comment", VarcharType.VARCHAR)        // 注释
            .build();

    // 7. NATION 表（国家表）
    private static final Map<String, Type> NATION = ImmutableMap.<String, Type>builder()
            .put("n_nationkey", BigintType.BIGINT)
            .put("n_name", VarcharType.VARCHAR)           // 国家名称
            .put("n_regionkey", BigintType.BIGINT)        // 地区键（外键）
            .put("n_comment", VarcharType.VARCHAR)        // 注释
            .build();

    // 8. REGION 表（地区表）
    private static final Map<String, Type> REGION = ImmutableMap.<String, Type>builder()
            .put("r_regionkey", BigintType.BIGINT)
            .put("r_name", VarcharType.VARCHAR)           // 地区名称
            .put("r_comment", VarcharType.VARCHAR)        // 注释
            .build();

    // 5. SUPPLIER, CUSTOMER, NATION, REGION 字段定义 (可以合并成公共基础表)
//    private static final Map<String, Type> OTHERS = ImmutableMap.<String, Type>builder()
//            .put("s_suppkey", BigintType.BIGINT)
//            .put("s_name", VarcharType.createVarcharType(25))
//            .put("s_nationkey", BigintType.BIGINT)
//            .put("c_custkey", BigintType.BIGINT)
//            .put("c_name", VarcharType.createVarcharType(25))
//            .put("c_nationkey", BigintType.BIGINT)
//            .put("n_nationkey", BigintType.BIGINT)
//            .put("n_name", VarcharType.createVarcharType(25))
//            .put("n_regionkey", BigintType.BIGINT)
//            .put("r_regionkey", BigintType.BIGINT)
//            .put("r_name", VarcharType.createVarcharType(25))
//            .build();

    // 6. 聚合产生的别名映射 (Q1, Q3, Q6, Q10 等常用别名)
    private static final Map<String, Type> ALIASES = ImmutableMap.<String, Type>builder()
            .put("sum_qty", DoubleType.DOUBLE)
            .put("sum_base_price", DoubleType.DOUBLE)
            .put("sum_disc_price", DoubleType.DOUBLE)
            .put("sum_charge", DoubleType.DOUBLE)
            .put("avg_qty", DoubleType.DOUBLE)
            .put("avg_price", DoubleType.DOUBLE)
            .put("avg_disc", DoubleType.DOUBLE)
            .put("count_order", BigintType.BIGINT)
            .put("revenue", DoubleType.DOUBLE)
            .build();

    // 对于复杂表达，我们需要指定其中间变量的类型
    private  static  final Map<String, Type> q1_temp = ImmutableMap.<String, Type>builder()
            .put("l_extendedprice____cast_1_as_double____l_discount", DoubleType.DOUBLE)
            .put("l_extendedprice____cast_1_as_double____l_discount______cast_1_as_double____l_tax", DoubleType.DOUBLE)
            .build();

    // 对于复杂表达，我们需要指定其中间变量的类型
    private  static  final Map<String, Type> q6_temp = ImmutableMap.<String, Type>builder()
            .put("l_extendedprice___l_discount", DoubleType.DOUBLE)
            .build();
    // 对于复杂表达，我们需要指定其中间变量的类型
    private  static  final Map<String, Type> q12_temp = ImmutableMap.<String, Type>builder()
//            .put("case_when___o_orderpriority____1_urgent___bpchar__or__o_orderpriority____2_high___bpchar___then_1_else_0_end", BigintType.BIGINT)
//            .put("case_when___o_orderpriority_____1_urgent___bpchar__and__o_orderpriority_____2_high___bpchar___then_1_else_0_end", BigintType.BIGINT)
            .put("high_priority_flag", BigintType.BIGINT)
            .put("low_priority_flag", BigintType.BIGINT)
            .build();


    // 对于复杂表达，我们需要指定其中间变量的类型
    private  static  final Map<String, Type> q14_temp = ImmutableMap.<String, Type>builder()
            .put("revenue_input", DoubleType.DOUBLE)
            .put("promo_revenue_input", DoubleType.DOUBLE)
            .put("promo_revenue_sum", DoubleType.DOUBLE)
            .put("total_revenue_sum", DoubleType.DOUBLE)
            .build();

    // 对于复杂表达，我们需要指定其中间变量的类型
    private  static  final Map<String, Type> q17_temp = ImmutableMap.<String, Type>builder()
            .put("l_quantity_n4", DoubleType.DOUBLE)
            .put("avg_2___avg_l_quantity", DoubleType.DOUBLE)
            .put("sum_l_extendedprice", DoubleType.DOUBLE)
            .put("p_partkey_n5", BigintType.BIGINT)
            .put("p_brand_n5", VarcharType.VARCHAR)
            .put("p_container_n5", VarcharType.VARCHAR)
            .put("avg_l_quantity_n4", DoubleType.DOUBLE)
            .build();

    // 对于复杂表达，我们需要指定其中间变量的类型
    private  static  final Map<String, Type> q19_temp = ImmutableMap.<String, Type>builder()
            .put("l_extendedprice____cast_1_as_double___l_discount", DoubleType.DOUBLE)
            .build();





    // 汇总所有的映射关系
    private static final Map<String, Type> ALL_TYPES = ImmutableMap.<String, Type>builder()
            .putAll(LINEITEM)
            .putAll(ORDERS)
            .putAll(PART)
            .putAll(PARTSUPP)
            .putAll(CUSTOMER)
            .putAll(SUPPLIER)
            .putAll(NATION)
            .putAll(REGION)
//            .putAll(OTHERS)
            .putAll(ALIASES)
            .putAll(q1_temp)
            .putAll(q6_temp)
            .putAll(q12_temp)
            .putAll(q14_temp)
            .putAll(q17_temp)
            .putAll(q19_temp)
            .build();

    public static TypeProvider getProvider() {
        return TypeProvider.copyOf(ALL_TYPES);
    }
}
