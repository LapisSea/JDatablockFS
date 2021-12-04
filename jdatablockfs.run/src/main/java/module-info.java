module JDatablockFS.run {
	requires JDatablockFS;
//	requires JDatablockFS.tools;
	requires jlapisutil;
	
	opens com.lapisseqa.cfs.run;
	opens com.lapisseqa.cfs.run.sparseimage;
}
