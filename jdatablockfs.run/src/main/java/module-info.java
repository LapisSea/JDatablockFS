module JDatablockFS.run {
	requires JDatablockFS.tools;
	requires jlapisutil;
	requires Jorth;
	requires jmh.core;
	requires jmh.generator.annprocess;
	requires gson;
	
	opens com.lapissea.cfs.run;
	opens com.lapissea.cfs.run.sparseimage;
}
