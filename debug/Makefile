
SCALE_FACTOR = sf1
QUERIES = q1 q2 q3 q4 q5 q6 q7 q8 q9 q10 q11 q12 q13 q14 q15 q16 q17 q18 q19 q20 q21 q22

run-query:
	java -jar ../presto-cli/target/presto-cli-*-executable.jar \
		--server 127.0.0.1:8082 \
		--catalog tpchstandard \
		--schema $(SCALE_FACTOR) \
		--output-format CSV \
		-f true.sql > true.csv
		 2> error.log

	# java -jar ../presto-cli/target/presto-cli-*-executable.jar \
	# 	--server 127.0.0.1:8082 \
	# 	--catalog tpchstandard \
	# 	--schema $(SCALE_FACTOR) \
	# 	--output-format CSV \
	# 	--session max_drivers_per_task=1 \
	# 	-f my.sql > my.csv

	java -jar ../presto-cli/target/presto-cli-*-executable.jar \
		--server 127.0.0.1:8082 \
		--catalog tpchstandard \
		--schema $(SCALE_FACTOR) \
		--output-format CSV \
		--session max_drivers_per_task=1 \
		--session opengauss_debug_output_enabled=true \
		--session opengauss_debug_output_plan_node_id=8 \
		-f my.sql > my.csv

	sort true.csv > output1.sorted
	sort my.csv > output2.sorted

	diff output1.sorted output2.sorted

temp:
	java -jar ../presto-cli/target/presto-cli-*-executable.jar \
		--server 127.0.0.1:8082 \
		--catalog tpchstandard \
		--schema $(SCALE_FACTOR) \
		--output-format CSV_HEADER \
		-f tmp.sql > tmp.csv

test-q%:
	mkdir -p output
	java -jar ../presto-cli/target/presto-cli-*-executable.jar \
		--server 127.0.0.1:8082 \
		--catalog tpchstandard \
		--schema $(SCALE_FACTOR) \
		--output-format CSV \
		-f test-query/q$*_r1.sql > output/q$*.csv

	java -jar ../presto-cli/target/presto-cli-*-executable.jar \
		--server 127.0.0.1:8082 \
		--catalog tpchstandard \
		--schema $(SCALE_FACTOR) \
		--output-format CSV \
		--execute "select $*" > output/my-q$*.csv

	sort output/q$*.csv > output/q$*.sorted
	sort output/my-q$*.csv > output/my-q$*.sorted
	diff output/q$*.sorted output/my-q$*.sorted

run-q%:
	mkdir -p run

	java -jar ../presto-cli/target/presto-cli-*-executable.jar \
		--server 127.0.0.1:8082 \
		--catalog tpchstandard \
		--schema $(SCALE_FACTOR) \
		--output-format CSV \
		--execute "select $*" > run/my-q$*.csv

$(QUERIES):
	$(MAKE) test-q$(@:q%=%)

clean:
	rm -f *.sorted