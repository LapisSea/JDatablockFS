package com.lapissea.cfs.io.content;

import java.io.ByteArrayOutputStream;

public class ContentOutputBuilder extends ByteArrayOutputStream implements ContentWriter{
	public ContentOutputBuilder(){
	}
	public ContentOutputBuilder(int size){
		super(size);
	}
}
