select p_partkey, p_brand, p_container, p_name, p_mfgr, p_type, p_size, p_retailprice, p_comment
from part
where p_brand = 'Brand#24'
	and p_container = 'JUMBO DRUM';