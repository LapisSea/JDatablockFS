import com.lapissea.dfs.io.compress.Packer;
import com.lapissea.dfs.io.compress.lz4.Lz4Packer;

module JDatablockFS.lz4 {
	requires JDatablockFS.core;
	requires org.lz4.java;
	
	
	provides Packer
		with Lz4Packer.Fast, Lz4Packer.High;
}