module Fuzzer {
	requires com.google.gson;
	requires java.management;
	exports com.lapissea.fuzz;
	exports com.lapissea.fuzz.distributed;
	exports com.lapissea.fuzz.distributed.master;
	opens com.lapissea.fuzz.distributed.bootstrap to com.google.gson;
}
