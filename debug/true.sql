select l_returnflag, l_linestatus, sum(l_quantity), sum(l_extendedprice), sum(l_extendedprice * (1 - l_discount)), sum(l_extendedprice * (1 - l_discount) * (1 + l_tax)), avg(l_quantity) as avg_qty,
	avg(l_extendedprice) as avg_price,
	avg(l_discount) as avg_disc,
	count(*) as count_order
 from tpchstandard.tiny.lineitem where
	l_shipdate <= date '1998-12-01' - interval '63' day
	group by l_returnflag, l_linestatus;
