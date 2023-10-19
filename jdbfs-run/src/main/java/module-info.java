module JDatablockFS.run {
	requires JDatablockFS.core;
	requires JDatablockFS.tools;
	requires Jorth;
	requires jlapisutil;
	requires jmh.core;
	requires com.google.gson;
	requires Fuzzer;
	
	opens com.lapissea.dfs.run;
	opens com.lapissea.dfs.run.sparseimage;
	opens com.lapissea.dfs.run.world;
	opens com.lapissea.dfs.run.examples;
	opens com.lapissea.dfs.benchmark;
	
	exports com.lapissea.dfs.benchmark.jmh_generated to jmh.core;
}
