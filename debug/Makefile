run-query:
	java -jar ../presto-cli/target/presto-cli-*-executable.jar \
		--server 127.0.0.1:8082 \
		--catalog tpchstandard \
		--schema tiny \
		--output-format CSV \
		-f true.sql > true.csv

	# java -jar ../presto-cli/target/presto-cli-*-executable.jar \
	# 	--server 127.0.0.1:8082 \
	# 	--catalog tpchstandard \
	# 	--schema tiny \
	# 	--output-format CSV \
	# 	--session max_drivers_per_task=1 \
	# 	-f my.sql > my.csv

	java -jar ../presto-cli/target/presto-cli-*-executable.jar \
		--server 127.0.0.1:8082 \
		--catalog tpchstandard \
		--schema tiny \
		--output-format CSV \
		--session max_drivers_per_task=1 \
		--session opengauss_debug_output_enabled=true \
		--session opengauss_debug_output_plan_node_id=9 \
		-f my.sql > my.csv

	sort true.csv > output1.sorted
	sort my.csv > output2.sorted

	diff output1.sorted output2.sorted

clean:
	rm -f *.sorted