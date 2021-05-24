package com.lapissea.cfs.io.instancepipe;

import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.type.IOInstance;

import java.io.IOException;

public interface PipeIO{
	
	<T extends IOInstance<T>> void write(ContentWriter dest, T instance) throws IOException;
	<T extends IOInstance<T>> T read(ContentReader src, T instance) throws IOException;
	
}
