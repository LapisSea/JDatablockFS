module JDatablockFS.run {
	requires JDatablockFS.core;
	requires JDatablockFS.tools;
	requires jlapisutil;
	requires Jorth;
	requires jmh.core;
	requires jmh.generator.annprocess;
	requires com.google.gson;
	
	opens com.lapissea.cfs.run;
	opens com.lapissea.cfs.run.sparseimage;
	opens com.lapissea.cfs.run.world;
	opens com.lapissea.cfs.run.examples;
	opens com.lapissea.cfs.benchmark;
}
