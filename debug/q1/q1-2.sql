select l_returnflag, l_linestatus, l_quantity, l_extendedprice, l_discount, l_tax, l_shipdate, l_orderkey, 
l_partkey, l_suppkey, l_linenumber, l_commitdate, l_receiptdate, l_shipinstruct, l_shipmode, l_comment, 
l_returnflag, l_linestatus, l_extendedprice * (1 - l_discount), 
l_extendedprice * (1 - l_discount) * (1 + l_tax)
 from tpchstandard.tiny.lineitem where
	l_shipdate <= date '1998-12-01' - interval '63' day;
