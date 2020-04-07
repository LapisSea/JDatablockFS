package com.lapissea.fsf.endpoint;

import java.io.IOException;
import java.util.stream.Stream;

public interface IFileSystem<Identifier>{
	
	IFile<Identifier> getFile(Identifier id) throws IOException;
	
	default IFile<Identifier> createFile(Identifier id) throws IOException{
		return createFile(id, 0);
	}
	
	IFile<Identifier> createFile(Identifier id, long initialSize) throws IOException;
	
	void defragment() throws IOException;
	
	Stream<IFile<Identifier>> listFiles() throws IOException;
	
}
