package com.lapissea.cfs.io.instancepipe;

import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.type.IOField;
import com.lapissea.cfs.type.IOInstance;

import java.io.IOException;

public class ContiguousPipe implements PipeIO{
	
	@Override
	public <T extends IOInstance<T>> void write(ContentWriter dest, T instance) throws IOException{
		for(IOField<T, ?> field : instance.getThisStruct().getIoFields()){
			field.write(dest, instance);
		}
	}
	
	@Override
	public <T extends IOInstance<T>> T read(ContentReader src, T instance) throws IOException{
		for(IOField<T, ?> field : instance.getThisStruct().getIoFields()){
			field.read(src, instance);
		}
		return instance;
	}
}
