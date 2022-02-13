module JDatablockFS.run {
	requires JDatablockFS.tools;
	requires jlapisutil;
	requires Jorth;
	requires gson;
	
	opens com.lapisseqa.cfs.run;
	opens com.lapisseqa.cfs.run.sparseimage;
}
