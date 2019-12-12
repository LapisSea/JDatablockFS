package com.lapissea.fsf;

import java.io.IOException;

public abstract class FileObject{
	
	public FileObject(){}
	
	public abstract void read(ContentInputStream dest) throws IOException;
	
	public abstract void write(ContentOutputStream dest) throws IOException;
	
	public abstract int length();
	
}
