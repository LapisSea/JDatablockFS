package com.lapissea.cfs.io;

import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;

import java.io.IOException;

public interface ReadableWritable{
	
	void read(ContentReader in) throws IOException;
	
	void write(ContentWriter out) throws IOException;
}
