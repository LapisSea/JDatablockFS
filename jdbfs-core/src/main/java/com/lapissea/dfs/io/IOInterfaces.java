package com.lapissea.dfs.io;

import com.lapissea.dfs.io.impl.ClosableIOData;
import com.lapissea.dfs.io.impl.FileMemoryMappedData;
import com.lapissea.dfs.io.impl.MemoryData;

import java.io.File;
import java.io.IOException;

public class IOInterfaces{
	
	public static IOInterface ofMemory(){
		return MemoryData.empty();
	}
	
	public static ClosableIOData ofFile(File file) throws IOException{
		return new FileMemoryMappedData(file);
	}
	public static ClosableIOData ofFile(String fileName) throws IOException{
		return new FileMemoryMappedData(fileName);
	}
	public static ClosableIOData ofFile(File file, boolean readOnly) throws IOException{
		return new FileMemoryMappedData(file, readOnly);
	}
	
}
