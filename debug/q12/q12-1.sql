select
l_shipmode, l_orderkey, l_commitdate, l_receiptdate, l_shipdate, l_partkey, l_suppkey, 
l_linenumber, l_quantity, l_extendedprice, l_discount, l_tax, l_returnflag, l_linestatus, l_shipinstruct, l_comment
from
	lineitem
where
	and l_shipmode in ('RAIL', 'MAIL')
	and l_commitdate < l_receiptdate
	and l_shipdate < l_commitdate
	and l_receiptdate >= date '1995-01-01'
	and l_receiptdate < date '1995-01-01' + interval '1' year
