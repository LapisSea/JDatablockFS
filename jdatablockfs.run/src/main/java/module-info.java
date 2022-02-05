module JDatablockFS.run {
	requires JDatablockFS.tools;
	requires jlapisutil;
	requires Jorth;
	
	opens com.lapisseqa.cfs.run;
	opens com.lapisseqa.cfs.run.sparseimage;
}
