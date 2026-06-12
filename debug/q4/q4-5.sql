select
o_orderpriority, o_orderkey, o_orderdate, o_custkey, o_orderstatus, o_totalprice, o_clerk, o_shippriority, o_comment,  o_orderpriority
from
	orders
where
	o_orderdate >= date '1997-04-01'
	and o_orderdate < date '1997-04-01' + interval '3' month
	and exists (
		select
			*
		from
			lineitem
		where
			l_orderkey = o_orderkey
			and l_commitdate < l_receiptdate
	);