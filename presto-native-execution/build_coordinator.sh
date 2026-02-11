sudo chown -R candle:candle ./target
mvn dependency:copy-dependencies -DincludeScope=test -DoutputDirectory=target/test-dependency
mvn dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=target/dependency
cp ./target.bak/run-hive-worker.sh  target
./target/run-hive-worker.sh
